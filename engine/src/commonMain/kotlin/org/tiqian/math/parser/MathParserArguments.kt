package org.tiqian.math.parser

import org.tiqian.math.core.*
import org.tiqian.math.parser.ParserState.Companion.bboxBorderPattern
import org.tiqian.math.parser.ParserState.Companion.bboxDimensionPattern
import org.tiqian.math.parser.ParserState.Companion.bboxDimensionUnits
import org.tiqian.math.parser.ParserState.Companion.longHexColorPattern
import org.tiqian.math.parser.ParserState.Companion.namedPaintColors
import org.tiqian.math.parser.ParserState.Companion.shortHexColorPattern
import org.tiqian.math.parser.ParserState.Companion.textControlSymbols
import org.tiqian.math.parser.ParserState.ParsedBboxOptions
import org.tiqian.math.parser.ParserState.ParsedFractionAlignment
import org.tiqian.math.parser.ParserState.ParsedOptionalMathList
import org.tiqian.math.parser.ParserState.ParsedRadicalDegree
import org.tiqian.math.parser.ParserState.ParsedTextArgument

internal fun ParserState.parseOptionalBboxOptions(command: MathToken): ParsedBboxOptions {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.Symbol || opening.text != "[") {
        return ParsedBboxOptions(MathBboxOptions(), null)
    }
    advance()
    val contentStart = opening.range.endExclusive
    var closing: MathToken? = null
    while (peek().kind != MathTokenKind.End) {
        val token = peek()
        if (token.kind == MathTokenKind.Symbol && token.text == "]") {
            closing = advance()
            break
        }
        if (token.kind == MathTokenKind.OpenGroup) break
        advance()
    }
    val contentEnd = closing?.range?.start ?: peek().range.start
    val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
    val totalRange = closing?.range?.let(opening.range::cover) ?: opening.range.cover(contentRange)
    if (closing == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnclosedBboxOptions,
            "Optional bbox properties are not closed",
            totalRange,
        )
    }

    var padding: MathBboxDimension? = null
    var background: MathBboxColor? = null
    var border: MathBboxBorder? = null
    splitBboxOptions(contentRange).forEach { (raw, range) ->
        val part = raw.trim()
        if (part.isEmpty()) return@forEach
        val trimmedRange = trimSourceRange(range)
        val dimension = parseBboxDimension(part, trimmedRange)
        when {
            dimension != null -> {
                if (padding != null) {
                    duplicateBboxProperty("padding", trimmedRange)
                } else {
                    padding = dimension
                }
            }
            part.contains(':') -> {
                val parsedBorder = parseBboxBorder(part, trimmedRange)
                if (parsedBorder == null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnsupportedBboxStyle,
                        "Only the safe 'border: <dimension> [solid] [color]' bbox style is supported",
                        trimmedRange,
                    )
                } else if (border != null) {
                    duplicateBboxProperty("border", trimmedRange)
                } else {
                    border = parsedBorder
                }
            }
            else -> {
                val parsedColor = parseBboxColor(part, trimmedRange)
                if (parsedColor == null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.InvalidBboxOption,
                        "'$part' is not a supported bbox color, padding dimension, or border",
                        trimmedRange,
                    )
                } else if (background != null) {
                    duplicateBboxProperty("background", trimmedRange)
                } else {
                    background = parsedColor
                }
            }
        }
    }
    return ParsedBboxOptions(MathBboxOptions(padding, background, border), totalRange)
}

private fun ParserState.splitBboxOptions(range: SourceRange): List<Pair<String, SourceRange>> {
    val result = mutableListOf<Pair<String, SourceRange>>()
    var start = range.start
    for (offset in range.start until range.endExclusive) {
        if (source[offset] == ',') {
            result += source.substring(start, offset) to SourceRange(start, offset)
            start = offset + 1
        }
    }
    result += source.substring(start, range.endExclusive) to SourceRange(start, range.endExclusive)
    return result
}

private fun ParserState.trimSourceRange(range: SourceRange): SourceRange {
    var start = range.start
    var end = range.endExclusive
    while (start < end && source[start].isWhitespace()) start += 1
    while (end > start && source[end - 1].isWhitespace()) end -= 1
    return SourceRange(start, end)
}

private fun ParserState.parseBboxDimension(text: String, range: SourceRange): MathBboxDimension? {
    val match = bboxDimensionPattern.matchEntire(text) ?: return null
    val value = match.groupValues[1].toFloatOrNull() ?: return null
    val unit = bboxDimensionUnits[match.groupValues[2].lowercase()] ?: return null
    if (!value.isFinite() || value < 0f) return null
    return MathBboxDimension(value, unit, text, range)
}

/** xcolor base names, the dvips RoyalBlue alias, CSS/SVG keywords, and 3/6-digit hex triplets. */
internal fun resolvePaintColorText(text: String): MathPaintColor? = when {
    shortHexColorPattern.matches(text) -> {
        val digits = text.drop(1)
        MathPaintColor(
            digits[0].digitToInt(16) * 17,
            digits[1].digitToInt(16) * 17,
            digits[2].digitToInt(16) * 17,
        )
    }
    longHexColorPattern.matches(text) -> MathPaintColor(
        text.substring(1, 3).toInt(16),
        text.substring(3, 5).toInt(16),
        text.substring(5, 7).toInt(16),
    )
    else -> namedPaintColors[text.lowercase()]
        ?: ParserState.cssSvgPaintColors[text.lowercase()]
}

private fun ParserState.parseBboxColor(text: String, range: SourceRange): MathBboxColor? {
    val color = resolvePaintColorText(text) ?: return null
    return MathBboxColor(text, color, range)
}

private fun ParserState.parseBboxBorder(text: String, range: SourceRange): MathBboxBorder? {
    val match = bboxBorderPattern.matchEntire(text) ?: return null
    val widthText = match.groupValues[1]
    val widthOffset = text.indexOf(widthText)
    val widthRange = SourceRange(range.start + widthOffset, range.start + widthOffset + widthText.length)
    val width = parseBboxDimension(widthText, widthRange) ?: return null
    val style = if (match.groupValues[2].equals("solid", ignoreCase = true)) {
        MathBboxBorderStyle.Solid
    } else {
        MathBboxBorderStyle.None
    }
    val colorText = match.groupValues[3].takeIf { it.isNotEmpty() }
    val color = colorText?.let {
        val offset = text.lastIndexOf(it)
        parseBboxColor(it, SourceRange(range.start + offset, range.start + offset + it.length))
            ?: return null
    }
    return MathBboxBorder(width, style, color, range)
}

private fun ParserState.duplicateBboxProperty(name: String, range: SourceRange) {
    diagnostics += MathDiagnostic(
        DiagnosticCode.DuplicateBboxOption,
        "Bbox $name is specified more than once",
        range,
    )
}


internal fun ParserState.parseTextArgument(
    command: MathToken,
    role: String,
    requestedWeight: MathFontWeight? = null,
): ParsedTextArgument = withResourceRecursion(command.range) {
    parseTextArgumentUnchecked(command, role, requestedWeight)
}

private fun ParserState.parseTextArgumentUnchecked(
    command: MathToken,
    role: String,
    requestedWeight: MathFontWeight?,
): ParsedTextArgument {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.OpenGroup) {
        if (opening.kind !in setOf(
                MathTokenKind.End,
                MathTokenKind.CloseGroup,
                MathTokenKind.Superscript,
                MathTokenKind.Subscript,
            )
        ) {
            val token = advance()
            val text = when (token.kind) {
                MathTokenKind.ControlSymbol -> textControlSymbols[token.text]
                MathTokenKind.Symbol, MathTokenKind.Space -> sourceSlice(token.range)
                else -> null
            }
            if (text != null) {
                return ParsedTextArgument(
                    segments = listOf(MathTextSegment(text, token.range, requestedWeight)),
                    contentRange = token.range,
                    totalRange = token.range,
                )
            }
        }
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingCommandArgument,
            "Command \\${command.text} requires a text token or braced $role",
            command.range,
        )
        val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
        return ParsedTextArgument(emptyList(), insertion, insertion)
    }
    advance()
    val segments = mutableListOf<MathTextSegment>()
    fun appendText(text: String, range: SourceRange, weight: MathFontWeight? = requestedWeight) {
        if (text.isEmpty()) return
        // Coalesce once after scanning. Rebuilding a growing String for every token is quadratic.
        segments += MathTextSegment(text, range, weight)
    }
    var depth = 1
    var closing: MathToken? = null
    var lastContentEnd = opening.range.endExclusive
    while (depth > 0) {
        val token = peek()
        when (token.kind) {
            MathTokenKind.End -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnclosedGroup,
                    "$role opened here is not closed",
                    opening.range,
                )
                break
            }
            MathTokenKind.OpenGroup -> {
                advance()
                depth += 1
                lastContentEnd = token.range.endExclusive
            }
            MathTokenKind.CloseGroup -> {
                advance()
                depth -= 1
                if (depth == 0) {
                    closing = token
                } else {
                    lastContentEnd = token.range.endExclusive
                }
            }
            MathTokenKind.Comment -> {
                advance()
                lastContentEnd = token.range.endExclusive
            }
            MathTokenKind.ControlWord -> {
                advance()
                if (token.text == "textbf") {
                    val nested = parseTextArgument(token, "bold text content", MathFontWeight.Bold)
                    nested.segments.forEach { segment ->
                        appendText(segment.text, segment.range, segment.requestedWeight)
                    }
                    lastContentEnd = nested.totalRange.endExclusive
                } else {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnsupportedCommand,
                        "Text-mode command \\${token.text} is not supported",
                        token.range,
                    )
                    lastContentEnd = token.range.endExclusive
                }
            }
            MathTokenKind.ControlSymbol -> {
                advance()
                val decoded = textControlSymbols[token.text]
                if (decoded == null) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnsupportedCommand,
                        "Text-mode control symbol \\${token.text} is not supported",
                        token.range,
                    )
                } else {
                    appendText(decoded, token.range)
                }
                lastContentEnd = token.range.endExclusive
            }
            else -> {
                advance()
                val rendered = if (token.kind == MathTokenKind.Symbol && token.text == "~") {
                    "\u00A0"
                } else {
                    sourceSlice(token.range)
                }
                appendText(rendered, token.range)
                lastContentEnd = token.range.endExclusive
            }
        }
    }
    val contentEnd = closing?.range?.start ?: lastContentEnd
    val contentRange = SourceRange(opening.range.endExclusive, contentEnd.coerceAtLeast(opening.range.endExclusive))
    val totalRange = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(contentRange)
    return ParsedTextArgument(coalesceTextSegments(segments), contentRange, totalRange)
}

private fun coalesceTextSegments(segments: List<MathTextSegment>): List<MathTextSegment> {
    if (segments.size < 2) return segments
    val result = mutableListOf<MathTextSegment>()
    var range = segments.first().range
    var weight = segments.first().requestedWeight
    var text = StringBuilder(segments.first().text)

    fun flush() {
        result += MathTextSegment(text.toString(), range, weight)
    }

    for (index in 1 until segments.size) {
        val segment = segments[index]
        if (range.endExclusive == segment.range.start && weight == segment.requestedWeight) {
            text.append(segment.text)
            range = range.cover(segment.range)
        } else {
            flush()
            range = segment.range
            weight = segment.requestedWeight
            text = StringBuilder(segment.text)
        }
    }
    flush()
    return result
}

internal fun ParserState.parseRequiredArgument(command: MathToken, role: String): MathNode {
    skipIgnored()
    val next = peek()
    if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingCommandArgument,
            "Command \\${command.text} is missing its $role",
            command.range,
        )
        return MathErrorNode("", SourceRange(command.range.endExclusive, command.range.endExclusive))
    }
    return parsePrimary() ?: MathErrorNode("", next.range)
}

internal fun ParserState.parseBboxRequiredArgument(command: MathToken): MathNode {
    skipIgnored()
    val displayContainerDepth = structureDepth + if (peek().kind == MathTokenKind.OpenGroup) 1 else 0
    boxDisplayContainerDepths += displayContainerDepth
    return try {
        parseRequiredArgument(command, "bbox math field")
    } finally {
        boxDisplayContainerDepths.removeAt(boxDisplayContainerDepths.lastIndex)
    }
}


internal fun ParserState.parseOptionalRadicalDegree(): ParsedRadicalDegree? {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
    advance()

    val children = mutableListOf<MathNode>()
    var closing: MathToken? = null
    while (true) {
        skipIgnored()
        val token = peek()
        when {
            token.kind == MathTokenKind.End -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnclosedRadicalDegree,
                    "Optional radical degree opened here is not closed with ]",
                    opening.range,
                )
                break
            }
            token.kind == MathTokenKind.Symbol && token.text == "]" -> {
                closing = advance()
                break
            }
            token.kind == MathTokenKind.CloseGroup -> {
                advance()
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnexpectedClosingGroup,
                    "Unexpected closing brace inside radical degree",
                    token.range,
                )
                children += MathErrorNode(token.text, token.range)
            }
            else -> parseAtomWithScripts()?.let { children += it }
        }
    }
    val bodyRange = when {
        children.isNotEmpty() -> children.first().range.cover(children.last().range)
        else -> SourceRange(opening.range.endExclusive, opening.range.endExclusive)
    }
    val range = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(bodyRange)
    if (children.isEmpty()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingRadicalDegree,
            "Optional radical degree is empty",
            range,
        )
    }
    return ParsedRadicalDegree(
        node = if (children.isEmpty()) null else MathList(children, bodyRange),
        range = range,
    )
}

internal fun ParserState.parseContinuedFractionAlignment(): ParsedFractionAlignment? {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
    advance()

    skipIgnored()
    val valueToken = peek()
    val alignment = if (valueToken.kind == MathTokenKind.Symbol && valueToken.text in setOf("l", "c", "r")) {
        advance()
        when (valueToken.text) {
            "l" -> MathFractionAlignment.Left
            "r" -> MathFractionAlignment.Right
            else -> MathFractionAlignment.Center
        }
    } else {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidContinuedFractionAlignment,
            "Continued-fraction alignment must be l, c, or r",
            valueToken.range,
        )
        if (valueToken.kind != MathTokenKind.End) advance()
        MathFractionAlignment.Center
    }

    skipIgnored()
    val closing = peek()
    val range = if (closing.kind == MathTokenKind.Symbol && closing.text == "]") {
        opening.range.cover(advance().range)
    } else {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnclosedContinuedFractionAlignment,
            "Continued-fraction alignment is not closed with ]",
            opening.range.cover(valueToken.range),
        )
        opening.range.cover(valueToken.range)
    }
    return ParsedFractionAlignment(alignment, range)
}

internal fun ParserState.parseOptionalExtensibleArrowBelow(): ParsedOptionalMathList? {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
    advance()

    val children = mutableListOf<MathNode>()
    var closing: MathToken? = null
    while (true) {
        skipIgnored()
        val token = peek()
        when {
            token.kind == MathTokenKind.End -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnclosedExtensibleArrowBelow,
                    "Optional extensible-arrow lower label opened here is not closed with ]",
                    opening.range,
                )
                break
            }
            token.kind == MathTokenKind.Symbol && token.text == "]" -> {
                closing = advance()
                break
            }
            token.kind == MathTokenKind.CloseGroup -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnclosedExtensibleArrowBelow,
                    "Optional extensible-arrow lower label is not closed before this group ends",
                    opening.range.cover(token.range),
                )
                break
            }
            else -> parseAtomWithScripts()?.let { children += it }
        }
    }
    val bodyRange = if (children.isEmpty()) {
        SourceRange(opening.range.endExclusive, opening.range.endExclusive)
    } else {
        children.first().range.cover(children.last().range)
    }
    val range = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(bodyRange)
    return ParsedOptionalMathList(
        node = MathList(children, bodyRange),
        range = range,
    )
}

internal fun ParserState.binRelClass(node: MathNode): MathAtomClass = when (node) {
    is MathSymbol -> node.atomClass.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
    is MathExtensibleArrow -> MathAtomClass.Relation
    is MathOverUnder -> node.atomClass.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
    is MathScripts -> binRelClass(node.base).takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
    is MathGroup -> node.body.children.singleOrNull()?.let(::binRelClass)
        ?.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
    is MathList -> node.children.singleOrNull()?.let(::binRelClass)
        ?.takeIf { it == MathAtomClass.Binary || it == MathAtomClass.Relation }
    else -> null
} ?: MathAtomClass.Ordinary

internal fun ParserState.parseRadicalRadicand(command: MathToken): MathNode {
    skipIgnored()
    val next = peek()
    if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingRadicalRadicand,
            "Command \\sqrt is missing its radicand",
            command.range,
        )
        return MathErrorNode("", SourceRange(command.range.endExclusive, command.range.endExclusive))
    }
    return if (next.kind == MathTokenKind.OpenGroup) {
        advance()
        parseGroup(
            opening = next,
            unclosedCode = DiagnosticCode.UnclosedRadicalRadicand,
            unclosedMessage = "Radicand group opened here is not closed",
        )
    } else {
        parsePrimary() ?: MathErrorNode("", next.range)
    }
}

/** One braced `\rule` dimension argument; negative values allowed only for the raise. */
internal fun ParserState.parseRuleDimensionArgument(
    command: MathToken,
    role: String,
    allowNegative: Boolean,
): MathBboxDimension? {
    val argument = parseTextArgument(command, role)
    val text = argument.segments.joinToString("") { it.text }.trim()
    val negative = allowNegative && text.startsWith("-")
    val magnitudeText = if (negative) text.drop(1).trim() else text
    val match = bboxDimensionPattern.matchEntire(magnitudeText)
    val value = match?.groupValues?.get(1)?.toFloatOrNull()
    val unit = match?.groupValues?.get(2)?.lowercase()?.let(bboxDimensionUnits::get)
    if (match == null || value == null || unit == null || !value.isFinite()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidRuleDimension,
            "Command \\${command.text} requires a TeX dimension for its $role",
            argument.contentRange,
        )
        return null
    }
    return MathBboxDimension(if (negative) -value else value, unit, text, argument.contentRange)
}

/** Optional `[raise]` bracket group before \rule's braced arguments. */
internal fun ParserState.parseOptionalRuleRaise(command: MathToken): MathBboxDimension? {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.Symbol || opening.text != "[") return null
    advance()
    val contentStart = opening.range.endExclusive
    var closing: MathToken? = null
    while (peek().kind != MathTokenKind.End) {
        val token = peek()
        if (token.kind == MathTokenKind.Symbol && token.text == "]") {
            closing = advance()
            break
        }
        if (token.kind == MathTokenKind.OpenGroup) break
        advance()
    }
    val contentEnd = closing?.range?.start ?: peek().range.start
    val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
    val text = sourceSlice(contentRange).trim()
    val negative = text.startsWith("-")
    val magnitudeText = if (negative) text.drop(1).trim() else text
    val match = bboxDimensionPattern.matchEntire(magnitudeText)
    val value = match?.groupValues?.get(1)?.toFloatOrNull()
    val unit = match?.groupValues?.get(2)?.lowercase()?.let(bboxDimensionUnits::get)
    if (match == null || value == null || unit == null || !value.isFinite()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidRuleDimension,
            "Command \\${command.text} has an invalid optional raise dimension",
            contentRange,
        )
        return null
    }
    return MathBboxDimension(if (negative) -value else value, unit, text, contentRange)
}
