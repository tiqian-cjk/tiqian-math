package org.tiqian.math.parser

import org.tiqian.math.core.*

interface MathFormulaParser {
    fun parse(
        source: String,
        resourceLimits: MathResourceLimits = MathResourceLimits.Default,
    ): MathParseResult
}

class MathParser(
    macros: List<MathMacroDefinition> = emptyList(),
    expansionLimits: MacroExpansionLimits? = null,
) : MathFormulaParser {
    private val tokenizer = MathTokenizer()
    private val macroExpander = MathMacroExpander(macros, expansionLimits)

    override fun parse(source: String, resourceLimits: MathResourceLimits): MathParseResult {
        val tokenized = tokenizer.tokenize(source, resourceLimits)
        tokenized.diagnostics.firstOrNull { it.code.isResourceLimitCode() }?.let { diagnostic ->
            return rejectedParseResult(source, tokenized.diagnostics, diagnostic)
        }
        val expanded = macroExpander.expand(tokenized.tokens, resourceLimits)
        expanded.diagnostics.firstOrNull { it.code.isResourceLimitCode() }?.let { diagnostic ->
            return rejectedParseResult(
                source,
                tokenized.diagnostics + expanded.diagnostics,
                diagnostic,
            )
        }
        val diagnostics = (tokenized.diagnostics + expanded.diagnostics).toMutableList()
        return try {
            val result = ParserState(
                source = source,
                tokens = expanded.tokens,
                diagnostics = diagnostics,
                resourceLimits = resourceLimits,
            ).parse()
            inspectMathAstResources(result.root, resourceLimits)?.let { diagnostic ->
                rejectedParseResult(source, result.diagnostics, diagnostic)
            } ?: result
        } catch (failure: ParserResourceLimitSignal) {
            rejectedParseResult(
                source,
                diagnostics,
                failure.diagnostic,
            )
        }
    }
}

private fun rejectedParseResult(
    source: String,
    diagnostics: List<MathDiagnostic>,
    resourceDiagnostic: MathDiagnostic,
): MathParseResult = MathParseResult(
    source = source,
    root = MathList(emptyList(), SourceRange.Empty),
    diagnostics = (diagnostics.filterNot { it.code.isResourceLimitCode() } + resourceDiagnostic).distinct(),
)

private fun DiagnosticCode.isResourceLimitCode(): Boolean = when (this) {
    DiagnosticCode.SourceLengthLimitExceeded,
    DiagnosticCode.TokenCountLimitExceeded,
    DiagnosticCode.AstNodeCountLimitExceeded,
    DiagnosticCode.RecursionDepthLimitExceeded,
    DiagnosticCode.MacroExpansionDepthExceeded,
    DiagnosticCode.MacroExpansionBudgetExceeded,
    -> true
    else -> false
}

private class ParserResourceLimitSignal(
    val diagnostic: MathDiagnostic,
) : RuntimeException()

internal class ParserState(
    internal val source: String,
    private val tokens: List<MathToken>,
    internal val diagnostics: MutableList<MathDiagnostic>,
    private val resourceLimits: MathResourceLimits,
) {
    internal data class ParsedBboxOptions(
        val options: MathBboxOptions,
        val totalRange: SourceRange?,
    )
    private var index = 0
    private var recursionDepth = 0
    internal var structureDepth = 0
    internal val boxDisplayContainerDepths = mutableListOf<Int>()
    internal val rowSeparatorContainerDepths = mutableListOf<Int>()

    fun parse(): MathParseResult {
        val parsedRoot = parseList(stopAtClosingGroup = false, opening = null)
        val root = foldTopLevelEquationTag(foldTopLevelDisplayRows(parsedRoot))
        val completeDisplay = root.children.singleOrNull().let { only ->
            when (only) {
                is MathDisplayEnvironment -> only
                is MathDisplayRows -> only.rows.singleOrNull()
                    ?.body
                    ?.children
                    ?.singleOrNull() as? MathDisplayEnvironment
                else -> null
            }
        }
        root.children.flatMap { child ->
            when (child) {
                is MathDisplayEnvironment -> listOf(child)
                is MathDisplayRows -> child.rows.flatMap { row -> row.body.children.filterIsInstance<MathDisplayEnvironment>() }
                else -> emptyList()
            }
        }.filter { it !== completeDisplay }.forEach { display ->
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MisplacedDisplayEnvironment,
                    "Display environment ${display.kind.sourceName} must be the complete formula source",
                    display.range,
                )
        }
        return MathParseResult(source, root, diagnostics.toList())
    }

    private fun foldTopLevelDisplayRows(root: MathList): MathList {
        if (root.children.none { it is MathExplicitRowBreak }) return root
        val rows = mutableListOf<MathDisplayRow>()
        var pending = mutableListOf<MathNode>()
        var rowStart = root.range.start

        fun finishRow(separator: MathExplicitRowBreak?, allowEmpty: Boolean) {
            if (pending.isEmpty() && !allowEmpty) return
            val bodyRange = if (pending.isEmpty()) {
                SourceRange(rowStart, rowStart)
            } else {
                pending.first().range.cover(pending.last().range)
            }
            val (bodyChildren, tag) = extractEquationTag(pending)
            val cleanBodyRange = bodyChildren.firstOrNull()?.range?.cover(bodyChildren.last().range)
                ?: SourceRange(bodyRange.start, bodyRange.start)
            rows += MathDisplayRow(
                body = MathList(bodyChildren, cleanBodyRange),
                rowSeparatorRange = separator?.separatorRange,
                additionalSpacing = separator?.additionalSpacing,
                range = bodyRange,
                tag = tag,
            )
            pending = mutableListOf()
            rowStart = separator?.additionalSpacing?.range?.endExclusive
                ?: separator?.separatorRange?.endExclusive
                ?: bodyRange.endExclusive
        }

        root.children.forEach { child ->
            if (child is MathExplicitRowBreak) {
                finishRow(child, allowEmpty = true)
            } else {
                pending += child
            }
        }
        finishRow(separator = null, allowEmpty = rows.isEmpty())
        return MathList(
            children = listOf(MathDisplayRows(rows, root.range)),
            range = root.range,
        )
    }

    private fun foldTopLevelEquationTag(root: MathList): MathList {
        val (normalizedChildren, boxedTag) = extractOutermostBoxedEquationTag(root.children)
        if (boxedTag == null && normalizedChildren.none { it is MathEquationTag }) return root
        val (bodyChildren, directTag) = extractEquationTag(normalizedChildren)
        val tag = directTag ?: boxedTag
        if (directTag != null && boxedTag != null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MultipleEquationTags,
                "A display row may contain only one equation tag",
                boxedTag.range,
            )
        }
        val selected = tag ?: return root
        val bodyRange = bodyChildren.firstOrNull()?.range?.cover(bodyChildren.last().range)
            ?: SourceRange(root.range.start, root.range.start)
        return MathList(
            children = listOf(
                MathTaggedEquation(
                    body = MathList(bodyChildren, bodyRange),
                    tag = selected,
                    range = root.range,
                ),
            ),
            range = root.range,
        )
    }

    /**
     * Zhihu/MathJax content in the wild sometimes places the row-level `\\tag` as the final
     * direct item of an outermost `\\boxed{...}` argument. A TeX box never owns an equation
     * number, so keep the box around its math field and promote that tag to the completed display
     * row. This compatibility rule is intentionally limited to one outermost boxed noad; tags in
     * fractions, scripts, or other nested math lists remain misplaced and diagnosable.
     */
    private fun extractOutermostBoxedEquationTag(
        nodes: List<MathNode>,
    ): Pair<List<MathNode>, MathEquationTag?> {
        val boxed = nodes.singleOrNull() as? MathBoxed ?: return nodes to null
        val group = boxed.body as? MathGroup ?: return nodes to null
        val (bodyChildren, tag) = extractEquationTag(group.body.children)
        val selected = tag ?: return nodes to null
        val bodyRange = bodyChildren.firstOrNull()?.range?.cover(bodyChildren.last().range)
            ?: SourceRange(group.body.range.start, group.body.range.start)
        val normalized = boxed.copy(
            body = group.copy(body = MathList(bodyChildren, bodyRange)),
        )
        return listOf(normalized) to selected
    }

    internal fun extractEquationTag(nodes: List<MathNode>): Pair<List<MathNode>, MathEquationTag?> {
        val tags = nodes.filterIsInstance<MathEquationTag>()
        tags.drop(1).forEach { duplicate ->
            diagnostics += MathDiagnostic(
                DiagnosticCode.MultipleEquationTags,
                "A display row may contain only one equation tag",
                duplicate.range,
            )
        }
        return nodes.filterNot { it is MathEquationTag } to tags.firstOrNull()
    }

    private fun parseList(
        stopAtClosingGroup: Boolean,
        opening: MathToken?,
        unclosedCode: DiagnosticCode = DiagnosticCode.UnclosedGroup,
        unclosedMessage: String = "Group opened here is not closed",
        generalizedFractionAllowed: Boolean = true,
    ): MathList {
        val children = mutableListOf<MathNode>()
        while (true) {
            skipIgnored()
            val token = peek()
            if (token.kind == MathTokenKind.ControlWord && token.text in GENERALIZED_FRACTION_COMMANDS) {
                advance()
                val isChoose = token.text == "choose"
                val isOver = token.text == "over"
                val command = "\\${token.text}"
                if (!generalizedFractionAllowed) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.AmbiguousGeneralizedFraction,
                        "Only one generalized fraction command is allowed in a math list",
                        token.range,
                    )
                    children += MathErrorNode(sourceSlice(token.range), token.range)
                    continue
                }
                val numerator = mathListFrom(children, token.range.start)
                if (children.isEmpty()) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingGeneralizedFractionNumerator,
                        "Command $command requires material before it in the containing math list",
                        token.range,
                    )
                }
                val denominator = withResourceRecursion(token.range) {
                    parseList(
                        stopAtClosingGroup = stopAtClosingGroup,
                        opening = opening,
                        unclosedCode = unclosedCode,
                        unclosedMessage = unclosedMessage,
                        generalizedFractionAllowed = false,
                    )
                }
                if (denominator.children.isEmpty()) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingGeneralizedFractionDenominator,
                        "Command $command requires material after it in the containing math list",
                        token.range,
                    )
                }
                val fractionRange = numerator.range.cover(token.range).cover(denominator.range)
                children.clear()
                children += MathFraction(
                    numerator = numerator,
                    denominator = denominator,
                    kind = if (isOver) FractionKind.Barred else FractionKind.Ruleless,
                    hasParentheses = isChoose,
                    range = fractionRange,
                    origin = when {
                        isChoose -> MathFractionOrigin.GeneralizedChoose
                        isOver -> MathFractionOrigin.GeneralizedOver
                        else -> MathFractionOrigin.GeneralizedAtop
                    },
                    commandRange = token.range,
                )
                break
            }
            when (token.kind) {
                MathTokenKind.End -> {
                    if (stopAtClosingGroup && opening != null) {
                        diagnostics += MathDiagnostic(
                            unclosedCode,
                            unclosedMessage,
                            opening.range,
                        )
                    }
                    break
                }
                MathTokenKind.CloseGroup -> {
                    if (stopAtClosingGroup) break
                    advance()
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.UnexpectedClosingGroup,
                        "Unexpected closing brace",
                        token.range,
                    )
                    children += MathErrorNode(token.text, token.range)
                }
                else -> parseAtomWithScripts()?.let { children += it }
            }
        }

        val range = when {
            children.isNotEmpty() -> children.first().range.cover(children.last().range)
            opening != null -> SourceRange(opening.range.endExclusive, opening.range.endExclusive)
            else -> SourceRange(0, 0)
        }
        return MathList(children, range)
    }

    private fun mathListFrom(nodes: List<MathNode>, insertionOffset: Int): MathList {
        val range = nodes.firstOrNull()?.range?.cover(nodes.last().range)
            ?: SourceRange(insertionOffset, insertionOffset)
        return MathList(nodes.toList(), range)
    }

    internal fun parseAtomWithScripts(): MathNode? {
        val first = peek()
        if (first.kind == MathTokenKind.Superscript || first.kind == MathTokenKind.Subscript) {
            advance()
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingScriptBase,
                "Script marker has no base atom",
                first.range,
            )
            val argument = parseScriptArgument(first)
            return MathErrorNode(sourceSlice(first.range.cover(argument.range)), first.range.cover(argument.range))
        }

        var base = parsePrimary() ?: return null
        if (
            base is MathStyleDeclaration || base is MathAlphabetDeclaration || base is MathVersionDeclaration ||
            base is MathExplicitRowBreak || base is MathEquationTag
        ) {
            return base
        }
        var superscript: MathNode? = null
        var subscript: MathNode? = null
        var totalRange = base.range
        while (true) {
            skipIgnored()
            val marker = peek()
            val modifiedOperator = if (
                marker.kind == MathTokenKind.ControlWord && marker.text in limitsModifiers
            ) {
                val policy = if (marker.text == "limits") MathLimitsPolicy.Limits else MathLimitsPolicy.NoLimits
                when (base) {
                    is MathOperator -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathOperatorName -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathOperatorNoad -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    is MathBraceNoad -> base.copy(
                        limitsPolicy = policy,
                        limitsModifierRange = marker.range,
                        range = base.range.cover(marker.range),
                    )
                    else -> null
                }
            } else {
                null
            }
            if (modifiedOperator != null) {
                advance()
                base = modifiedOperator
                totalRange = totalRange.cover(marker.range)
                continue
            }
            if (marker.kind != MathTokenKind.Superscript && marker.kind != MathTokenKind.Subscript) break
            advance()
            if (base is MathDisplayEnvironment) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MisplacedDisplayEnvironment,
                    "Display environment ${base.kind.sourceName} cannot be used as a scripted math atom",
                    marker.range,
                )
            }
            val argument = parseScriptArgument(marker)
            totalRange = totalRange.cover(marker.range).cover(argument.range)
            if (marker.kind == MathTokenKind.Superscript) {
                if (superscript == null) {
                    superscript = argument
                } else {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.DuplicateSuperscript,
                        "Base already has a superscript",
                        marker.range,
                    )
                }
            } else {
                if (subscript == null) {
                    subscript = argument
                } else {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.DuplicateSubscript,
                        "Base already has a subscript",
                        marker.range,
                    )
                }
            }
        }
        if (superscript != null || subscript != null) {
            base = MathScripts(base, superscript, subscript, totalRange)
        }
        return base
    }

    private fun parseScriptArgument(marker: MathToken): MathNode {
        skipIgnored()
        val next = peek()
        if (next.kind in setOf(MathTokenKind.End, MathTokenKind.CloseGroup, MathTokenKind.Superscript, MathTokenKind.Subscript)) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingScriptArgument,
                "Script marker requires an argument",
                marker.range,
            )
            return MathErrorNode("", SourceRange(marker.range.endExclusive, marker.range.endExclusive))
        }
        return parsePrimary() ?: MathErrorNode("", next.range)
    }

    internal fun parsePrimary(): MathNode? {
        skipIgnored()
        val nextRange = peek().range
        return withResourceRecursion(nextRange) {
            val token = advance()
            when (token.kind) {
            MathTokenKind.Symbol -> if (token.text == "&") {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.UnexpectedAlignmentTab,
                    "Alignment tab & is only valid inside a supported table environment",
                    token.range,
                )
                MathErrorNode(token.text, token.range)
            } else if (token.text.scalarValues().all { it.isCjkMathTextScalar() }) {
                // Consume the complete contiguous run once. Merging one token at a time by
                // repeatedly copying the growing segment list makes a long CJK run quadratic.
                val segments = mutableListOf(MathTextSegment(token.text, token.range))
                var contentRange = token.range
                while (true) {
                    val following = peek()
                    if (
                        following.kind != MathTokenKind.Symbol ||
                        following.range.start != contentRange.endExclusive ||
                        !following.text.scalarValues().all { it.isCjkMathTextScalar() }
                    ) {
                        break
                    }
                    // Preserve TeX script binding: in `中文^2`, the script belongs to `文`, not
                    // to a greedily merged `中文` text atom. Ignored tokens are skipped by the
                    // script parser, so include them in this look-ahead without consuming them.
                    var afterFollowing = index + 1
                    var followingKind = tokens.getOrElse(afterFollowing) { tokens.last() }.kind
                    while (followingKind == MathTokenKind.Space || followingKind == MathTokenKind.Comment) {
                        afterFollowing += 1
                        followingKind = tokens.getOrElse(afterFollowing) { tokens.last() }.kind
                    }
                    if (followingKind == MathTokenKind.Superscript || followingKind == MathTokenKind.Subscript) {
                        break
                    }
                    advance()
                    segments += MathTextSegment(following.text, following.range)
                    contentRange = contentRange.cover(following.range)
                }
                MathText(
                    segments = segments,
                    commandRange = SourceRange(token.range.start, token.range.start),
                    contentRange = contentRange,
                    range = contentRange,
                    origin = MathTextOrigin.ImplicitCjk,
                )
            } else {
                symbolNode(token, TeXMathSymbolTable.literal(token.text))
            }
            MathTokenKind.ControlSymbol -> parseControlSymbol(token)
            MathTokenKind.ControlWord -> parseControlWord(token)
            MathTokenKind.OpenGroup -> parseGroup(token)
            MathTokenKind.Parameter -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.InvalidParameterMarker,
                    "Parameter marker is only valid inside a registered macro replacement",
                    token.range,
                )
                MathErrorNode(token.text, token.range)
            }
            MathTokenKind.CloseGroup, MathTokenKind.End -> null
            MathTokenKind.Superscript, MathTokenKind.Subscript -> null
            MathTokenKind.Space, MathTokenKind.Comment -> null
            }
        }
    }

    internal fun <T> withResourceRecursion(range: SourceRange, block: () -> T): T {
        val attemptedDepth = recursionDepth + 1
        if (attemptedDepth > resourceLimits.maximumRecursionDepth) {
            throw ParserResourceLimitSignal(
                mathResourceLimitDiagnostic(
                    code = DiagnosticCode.RecursionDepthLimitExceeded,
                    resource = "recursionDepth",
                    actual = attemptedDepth,
                    limit = resourceLimits.maximumRecursionDepth,
                    range = range,
                ),
            )
        }
        recursionDepth = attemptedDepth
        try {
            return block()
        } finally {
            recursionDepth -= 1
        }
    }

    internal fun parseGroup(
        opening: MathToken,
        unclosedCode: DiagnosticCode = DiagnosticCode.UnclosedGroup,
        unclosedMessage: String = "Group opened here is not closed",
    ): MathGroup {
        structureDepth += 1
        val body = try {
            parseList(
                stopAtClosingGroup = true,
                opening = opening,
                unclosedCode = unclosedCode,
                unclosedMessage = unclosedMessage,
            )
        } finally {
            structureDepth -= 1
        }
        val closing = if (peek().kind == MathTokenKind.CloseGroup) advance() else null
        val range = if (closing != null) opening.range.cover(closing.range) else opening.range.cover(body.range)
        return MathGroup(body, range)
    }

    internal fun MathFixedDelimiterRole.asDelimiterSide(): MathDelimiterSide = when (this) {
        MathFixedDelimiterRole.Opening -> MathDelimiterSide.Left
        MathFixedDelimiterRole.Closing -> MathDelimiterSide.Right
        MathFixedDelimiterRole.Ordinary,
        MathFixedDelimiterRole.Relation,
        -> MathDelimiterSide.Middle
    }

    private fun String.scalarValues(): List<Int> = buildList {
        var offset = 0
        while (offset < length) {
            val first = this@scalarValues[offset]
            val scalar = if (first.isHighSurrogate() && offset + 1 < length && this@scalarValues[offset + 1].isLowSurrogate()) {
                ((first.code - 0xD800) shl 10) + (this@scalarValues[offset + 1].code - 0xDC00) + 0x10000
            } else {
                first.code
            }
            add(scalar)
            offset += if (scalar > 0xFFFF) 2 else 1
        }
    }

    internal fun symbolNode(token: MathToken, spec: TeXMathSymbolSpec): MathSymbol = MathSymbol(
        sourceText = sourceSlice(token.range),
        identity = spec.identity,
        atomClass = spec.atomClass,
        family = spec.family,
        familyBinding = spec.familyBinding,
        alphabet = spec.alphabet,
        range = token.range,
    )

    internal fun skipIgnored() {
        while (peek().kind == MathTokenKind.Space || peek().kind == MathTokenKind.Comment) index++
    }

    internal fun peek(): MathToken = tokens.getOrElse(index) { tokens.last() }
    internal fun advance(): MathToken = peek().also { if (index < tokens.size) index++ }
    internal fun sourceSlice(range: SourceRange): String =
        if (range.endExclusive <= source.length) source.substring(range.start, range.endExclusive) else ""

    internal companion object {
        val GENERALIZED_FRACTION_COMMANDS = setOf("over", "atop", "choose")

        val styleCommands = mapOf(
            "displaystyle" to MathStyleLevel.Display,
            "textstyle" to MathStyleLevel.Text,
            "scriptstyle" to MathStyleLevel.Script,
            "scriptscriptstyle" to MathStyleLevel.ScriptScript,
        )

        // LaTeX math-alphabet commands. Family only matters for MathNormal (Letters italicises,
        // Operators stays upright); an explicit alphabet drives the glyph directly.
        val alphabetCommands = mapOf(
            "mathrm" to (MathFamily.Operators to MathAlphabet.Roman),
            "mathnormal" to (MathFamily.Letters to MathAlphabet.MathNormal),
            "mathbf" to (MathFamily.Operators to MathAlphabet.Bold),
            // MathJax alias for mathbf.
            "bold" to (MathFamily.Operators to MathAlphabet.Bold),
            "mathit" to (MathFamily.Letters to MathAlphabet.Italic),
            "mathsf" to (MathFamily.Operators to MathAlphabet.SansSerif),
            "mathbb" to (MathFamily.Operators to MathAlphabet.DoubleStruck),
            "mathfrak" to (MathFamily.Operators to MathAlphabet.Fraktur),
            "mathcal" to (MathFamily.Operators to MathAlphabet.Script),
            "mathscr" to (MathFamily.Operators to MathAlphabet.Script),
            "mathtt" to (MathFamily.Operators to MathAlphabet.Monospace),
        )

        // Log-like function names (TeX \mathop). The "limits" group stacks scripts over/under in
        // display style; the rest keep side scripts. Amsmath's canonical limit operators are
        // det gcd inf injlim lim liminf limsup max min Pr projlim sup.
        val functionNames: Map<String, MathLimitsPolicy> = buildMap {
            listOf(
                "sin", "cos", "tan", "cot", "sec", "csc",
                "sinh", "cosh", "tanh", "coth",
                "arcsin", "arccos", "arctan",
                "exp", "log", "ln", "lg",
                "arg", "deg", "dim", "hom", "ker",
            ).forEach { put(it, MathLimitsPolicy.NoLimits) }
            listOf(
                "lim", "limsup", "liminf", "max", "min", "sup", "inf",
                "det", "gcd", "Pr", "injlim", "projlim",
            ).forEach { put(it, MathLimitsPolicy.Auto) }
        }

        val accentCommands = mapOf(
            "acute" to MathAccentIdentity.Acute,
            "grave" to MathAccentIdentity.Grave,
            "breve" to MathAccentIdentity.Breve,
            "check" to MathAccentIdentity.Check,
            "mathring" to MathAccentIdentity.Ring,
            "hat" to MathAccentIdentity.Hat,
            "bar" to MathAccentIdentity.Bar,
            "tilde" to MathAccentIdentity.Tilde,
            "dot" to MathAccentIdentity.Dot,
            "ddot" to MathAccentIdentity.DoubleDot,
            "vec" to MathAccentIdentity.Vec,
            "widehat" to MathAccentIdentity.WideHat,
            "widetilde" to MathAccentIdentity.WideTilde,
            "overleftarrow" to MathAccentIdentity.OverLeftArrow,
            "overrightarrow" to MathAccentIdentity.OverRightArrow,
            "underleftarrow" to MathAccentIdentity.UnderLeftArrow,
            "underrightarrow" to MathAccentIdentity.UnderRightArrow,
        )

        val ruleDecorationCommands = mapOf(
            "overline" to MathRuleDecorationKind.Overline,
            "underline" to MathRuleDecorationKind.Underline,
        )

        val overUnderCommands = mapOf(
            "overset" to MathOverUnderKind.Overset,
            "underset" to MathOverUnderKind.Underset,
            "stackrel" to MathOverUnderKind.StackRel,
        )

        val extensibleArrowCommands = mapOf(
            "xleftarrow" to MathExtensibleArrowIdentity.Left,
            "xrightarrow" to MathExtensibleArrowIdentity.Right,
        )

        val textControlSymbols = mapOf(
            " " to " ",
            "," to "\u2009",
            "\\" to "\\",
            "{" to "{",
            "}" to "}",
            "%" to "%",
            "#" to "#",
            "_" to "_",
            "&" to "&",
            "$" to "$",
        )

        val explicitlyUnsupportedCommands = setOf(
            "limits", "nolimits",
            "matrix", "cases", "newcommand", "def",
        )

        /** Commands whose single braced argument reads `#` as a hex color, not a macro marker. */
        val hexColorArgumentCommands = setOf("color")

        /** LaTeX 10pt-class size switches as em ratios (MathJax renders the same table). */
        val latexSizeScales = mapOf(
            "tiny" to 0.5f,
            "scriptsize" to 0.7f,
            "small" to 0.9f,
            "normalsize" to 1.0f,
            "large" to 1.2f,
            "Large" to 1.44f,
            "LARGE" to 1.728f,
            "huge" to 2.074f,
            "Huge" to 2.488f,
        )

        /** xcolor's always-available names plus the corpus-used dvips `RoyalBlue` alias. */
        val namedPaintColors = mapOf(
            "black" to MathPaintColor(0, 0, 0),
            "darkgray" to MathPaintColor(64, 64, 64),
            "gray" to MathPaintColor(128, 128, 128),
            "lightgray" to MathPaintColor(191, 191, 191),
            "white" to MathPaintColor(255, 255, 255),
            "red" to MathPaintColor(255, 0, 0),
            "green" to MathPaintColor(0, 255, 0),
            "blue" to MathPaintColor(0, 0, 255),
            "cyan" to MathPaintColor(0, 255, 255),
            "magenta" to MathPaintColor(255, 0, 255),
            "yellow" to MathPaintColor(255, 255, 0),
            "brown" to MathPaintColor(191, 128, 64),
            "lime" to MathPaintColor(191, 255, 0),
            "olive" to MathPaintColor(128, 128, 0),
            "orange" to MathPaintColor(255, 128, 0),
            "pink" to MathPaintColor(255, 191, 191),
            "purple" to MathPaintColor(191, 0, 64),
            "teal" to MathPaintColor(0, 128, 128),
            "violet" to MathPaintColor(128, 0, 128),
            "royalblue" to MathPaintColor(0, 128, 255),
        )

        /** CSS/SVG named colors; the xcolor base names above win on overlap. */
        val cssSvgPaintColors = mapOf(
            "aliceblue" to MathPaintColor(240, 248, 255),
            "antiquewhite" to MathPaintColor(250, 235, 215),
            "aqua" to MathPaintColor(0, 255, 255),
            "aquamarine" to MathPaintColor(127, 255, 212),
            "azure" to MathPaintColor(240, 255, 255),
            "beige" to MathPaintColor(245, 245, 220),
            "bisque" to MathPaintColor(255, 228, 196),
            "blanchedalmond" to MathPaintColor(255, 235, 205),
            "blueviolet" to MathPaintColor(138, 43, 226),
            "burlywood" to MathPaintColor(222, 184, 135),
            "cadetblue" to MathPaintColor(95, 158, 160),
            "chartreuse" to MathPaintColor(127, 255, 0),
            "chocolate" to MathPaintColor(210, 105, 30),
            "coral" to MathPaintColor(255, 127, 80),
            "cornflowerblue" to MathPaintColor(100, 149, 237),
            "cornsilk" to MathPaintColor(255, 248, 220),
            "crimson" to MathPaintColor(220, 20, 60),
            "darkblue" to MathPaintColor(0, 0, 139),
            "darkcyan" to MathPaintColor(0, 139, 139),
            "darkgoldenrod" to MathPaintColor(184, 134, 11),
            "darkgreen" to MathPaintColor(0, 100, 0),
            "darkkhaki" to MathPaintColor(189, 183, 107),
            "darkmagenta" to MathPaintColor(139, 0, 139),
            "darkolivegreen" to MathPaintColor(85, 107, 47),
            "darkorange" to MathPaintColor(255, 140, 0),
            "darkorchid" to MathPaintColor(153, 50, 204),
            "darkred" to MathPaintColor(139, 0, 0),
            "darksalmon" to MathPaintColor(233, 150, 122),
            "darkseagreen" to MathPaintColor(143, 188, 143),
            "darkslateblue" to MathPaintColor(72, 61, 139),
            "darkslategray" to MathPaintColor(47, 79, 79),
            "darkslategrey" to MathPaintColor(47, 79, 79),
            "darkturquoise" to MathPaintColor(0, 206, 209),
            "darkviolet" to MathPaintColor(148, 0, 211),
            "deeppink" to MathPaintColor(255, 20, 147),
            "deepskyblue" to MathPaintColor(0, 191, 255),
            "dimgray" to MathPaintColor(105, 105, 105),
            "dimgrey" to MathPaintColor(105, 105, 105),
            "dodgerblue" to MathPaintColor(30, 144, 255),
            "firebrick" to MathPaintColor(178, 34, 34),
            "floralwhite" to MathPaintColor(255, 250, 240),
            "forestgreen" to MathPaintColor(34, 139, 34),
            "fuchsia" to MathPaintColor(255, 0, 255),
            "gainsboro" to MathPaintColor(220, 220, 220),
            "ghostwhite" to MathPaintColor(248, 248, 255),
            "gold" to MathPaintColor(255, 215, 0),
            "goldenrod" to MathPaintColor(218, 165, 32),
            "greenyellow" to MathPaintColor(173, 255, 47),
            "grey" to MathPaintColor(128, 128, 128),
            "honeydew" to MathPaintColor(240, 255, 240),
            "hotpink" to MathPaintColor(255, 105, 180),
            "indianred" to MathPaintColor(205, 92, 92),
            "indigo" to MathPaintColor(75, 0, 130),
            "ivory" to MathPaintColor(255, 255, 240),
            "khaki" to MathPaintColor(240, 230, 140),
            "lavender" to MathPaintColor(230, 230, 250),
            "lavenderblush" to MathPaintColor(255, 240, 245),
            "lawngreen" to MathPaintColor(124, 252, 0),
            "lemonchiffon" to MathPaintColor(255, 250, 205),
            "lightblue" to MathPaintColor(173, 216, 230),
            "lightcoral" to MathPaintColor(240, 128, 128),
            "lightcyan" to MathPaintColor(224, 255, 255),
            "lightgoldenrodyellow" to MathPaintColor(250, 250, 210),
            "lightgreen" to MathPaintColor(144, 238, 144),
            "lightgrey" to MathPaintColor(211, 211, 211),
            "lightpink" to MathPaintColor(255, 182, 193),
            "lightsalmon" to MathPaintColor(255, 160, 122),
            "lightseagreen" to MathPaintColor(32, 178, 170),
            "lightskyblue" to MathPaintColor(135, 206, 250),
            "lightslategray" to MathPaintColor(119, 136, 153),
            "lightslategrey" to MathPaintColor(119, 136, 153),
            "lightsteelblue" to MathPaintColor(176, 196, 222),
            "lightyellow" to MathPaintColor(255, 255, 224),
            "limegreen" to MathPaintColor(50, 205, 50),
            "linen" to MathPaintColor(250, 240, 230),
            "maroon" to MathPaintColor(128, 0, 0),
            "mediumaquamarine" to MathPaintColor(102, 205, 170),
            "mediumblue" to MathPaintColor(0, 0, 205),
            "mediumorchid" to MathPaintColor(186, 85, 211),
            "mediumpurple" to MathPaintColor(147, 112, 219),
            "mediumseagreen" to MathPaintColor(60, 179, 113),
            "mediumslateblue" to MathPaintColor(123, 104, 238),
            "mediumspringgreen" to MathPaintColor(0, 250, 154),
            "mediumturquoise" to MathPaintColor(72, 209, 204),
            "mediumvioletred" to MathPaintColor(199, 21, 133),
            "midnightblue" to MathPaintColor(25, 25, 112),
            "mintcream" to MathPaintColor(245, 255, 250),
            "mistyrose" to MathPaintColor(255, 228, 225),
            "moccasin" to MathPaintColor(255, 228, 181),
            "navajowhite" to MathPaintColor(255, 222, 173),
            "navy" to MathPaintColor(0, 0, 128),
            "oldlace" to MathPaintColor(253, 245, 230),
            "olivedrab" to MathPaintColor(107, 142, 35),
            "orangered" to MathPaintColor(255, 69, 0),
            "orchid" to MathPaintColor(218, 112, 214),
            "palegoldenrod" to MathPaintColor(238, 232, 170),
            "palegreen" to MathPaintColor(152, 251, 152),
            "paleturquoise" to MathPaintColor(175, 238, 238),
            "palevioletred" to MathPaintColor(219, 112, 147),
            "papayawhip" to MathPaintColor(255, 239, 213),
            "peachpuff" to MathPaintColor(255, 218, 185),
            "peru" to MathPaintColor(205, 133, 63),
            "plum" to MathPaintColor(221, 160, 221),
            "powderblue" to MathPaintColor(176, 224, 230),
            "rebeccapurple" to MathPaintColor(102, 51, 153),
            "rosybrown" to MathPaintColor(188, 143, 143),
            "saddlebrown" to MathPaintColor(139, 69, 19),
            "salmon" to MathPaintColor(250, 128, 114),
            "sandybrown" to MathPaintColor(244, 164, 96),
            "seagreen" to MathPaintColor(46, 139, 87),
            "seashell" to MathPaintColor(255, 245, 238),
            "sienna" to MathPaintColor(160, 82, 45),
            "silver" to MathPaintColor(192, 192, 192),
            "skyblue" to MathPaintColor(135, 206, 235),
            "slateblue" to MathPaintColor(106, 90, 205),
            "slategray" to MathPaintColor(112, 128, 144),
            "slategrey" to MathPaintColor(112, 128, 144),
            "snow" to MathPaintColor(255, 250, 250),
            "springgreen" to MathPaintColor(0, 255, 127),
            "steelblue" to MathPaintColor(70, 130, 180),
            "tan" to MathPaintColor(210, 180, 140),
            "thistle" to MathPaintColor(216, 191, 216),
            "tomato" to MathPaintColor(255, 99, 71),
            "turquoise" to MathPaintColor(64, 224, 208),
            "wheat" to MathPaintColor(245, 222, 179),
            "whitesmoke" to MathPaintColor(245, 245, 245),
            "yellowgreen" to MathPaintColor(154, 205, 50),
        )

        val tableEnvironments = MathTableEnvironment.entries.associateBy { it.sourceName }
        val displayEnvironments = MathDisplayEnvironmentKind.entries.associateBy { it.sourceName }

        val rowSpacingPattern = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([A-Za-z]+)")
        val rowSpacingUnits = MathTeXDimensionUnit.entries.associateBy { it.sourceName }
        val bboxDimensionPattern = Regex(
            "(\\.\\d+|\\d+(?:\\.\\d*)?)(pt|em|ex|mu|px|in|cm|mm)",
            RegexOption.IGNORE_CASE,
        )
        val bboxDimensionUnits = MathBboxDimensionUnit.entries.associateBy { it.sourceName }
        val bboxBorderPattern = Regex(
            "border\\s*:\\s*((?:\\.\\d+|\\d+(?:\\.\\d*)?)(?:pt|em|ex|mu|px|in|cm|mm))" +
                "(?:\\s+(solid))?(?:\\s+([A-Za-z0-9]+|#[0-9A-Fa-f]{3}|#[0-9A-Fa-f]{6}))?\\s*",
            RegexOption.IGNORE_CASE,
        )
        val shortHexColorPattern = Regex("#[0-9A-Fa-f]{3}")
        val longHexColorPattern = Regex("#[0-9A-Fa-f]{6}")

        val explicitMathSpaces = mapOf(
            "quad" to 18f,
            "qquad" to 36f,
        )
        val explicitControlSpaces = mapOf(
            "!" to -3f,
            "," to 3f,
            ":" to 4f,
            ">" to 4f,
            ";" to 5f,
            " " to 6f,
        )

        val limitsModifiers = setOf("limits", "nolimits")

        val delimiterMissingKinds = setOf(
            MathTokenKind.End,
            MathTokenKind.CloseGroup,
            MathTokenKind.Superscript,
            MathTokenKind.Subscript,
        )
        val delimiterBoundaryCommands = setOf("left", "middle", "right")
        val literalDelimiters = mapOf(
            "(" to MathDelimiterIdentity.LeftParenthesis,
            ")" to MathDelimiterIdentity.RightParenthesis,
            "[" to MathDelimiterIdentity.LeftBracket,
            "]" to MathDelimiterIdentity.RightBracket,
            "|" to MathDelimiterIdentity.VerticalBar,
            "/" to MathDelimiterIdentity.Solidus,
            "." to MathDelimiterIdentity.Invisible,
        )
        val controlSymbolDelimiters = mapOf(
            "{" to MathDelimiterIdentity.LeftBrace,
            "}" to MathDelimiterIdentity.RightBrace,
            "|" to MathDelimiterIdentity.DoubleVerticalBar,
        )
        val commandDelimiters = mapOf(
            "vert" to MathDelimiterIdentity.VerticalBar,
            "Vert" to MathDelimiterIdentity.DoubleVerticalBar,
            "lvert" to MathDelimiterIdentity.VerticalBar,
            "rvert" to MathDelimiterIdentity.VerticalBar,
            "lVert" to MathDelimiterIdentity.DoubleVerticalBar,
            "rVert" to MathDelimiterIdentity.DoubleVerticalBar,
            "lbrack" to MathDelimiterIdentity.LeftBracket,
            "rbrack" to MathDelimiterIdentity.RightBracket,
            "langle" to MathDelimiterIdentity.LeftAngleBracket,
            "rangle" to MathDelimiterIdentity.RightAngleBracket,
            "lfloor" to MathDelimiterIdentity.LeftFloor,
            "rfloor" to MathDelimiterIdentity.RightFloor,
            "lceil" to MathDelimiterIdentity.LeftCeiling,
            "rceil" to MathDelimiterIdentity.RightCeiling,
            "backslash" to MathDelimiterIdentity.ReverseSolidus,
            "uparrow" to MathDelimiterIdentity.UpArrow,
            "downarrow" to MathDelimiterIdentity.DownArrow,
            "updownarrow" to MathDelimiterIdentity.UpDownArrow,
            "Uparrow" to MathDelimiterIdentity.DoubleUpArrow,
            "Downarrow" to MathDelimiterIdentity.DoubleDownArrow,
            "Updownarrow" to MathDelimiterIdentity.DoubleUpDownArrow,
        )

        val fixedDelimiterCommands = buildMap {
            MathFixedDelimiterSize.entries.forEach { size ->
                put(size.commandStem, FixedDelimiterCommand(size, MathFixedDelimiterRole.Ordinary))
                put("${size.commandStem}l", FixedDelimiterCommand(size, MathFixedDelimiterRole.Opening))
                put("${size.commandStem}m", FixedDelimiterCommand(size, MathFixedDelimiterRole.Relation))
                put("${size.commandStem}r", FixedDelimiterCommand(size, MathFixedDelimiterRole.Closing))
            }
        }
    }

    internal data class ParsedTextArgument(
        val segments: List<MathTextSegment>,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )

    internal data class ParsedRadicalDegree(
        val node: MathNode?,
        val range: SourceRange,
    )

    internal data class ParsedOptionalMathList(
        val node: MathNode,
        val range: SourceRange,
    )

    internal data class ParsedFractionAlignment(
        val alignment: MathFractionAlignment,
        val range: SourceRange,
    )

    internal data class FixedDelimiterCommand(
        val size: MathFixedDelimiterSize,
        val role: MathFixedDelimiterRole,
    )

    internal data class ParsedEnvironmentName(
        val name: String,
        val contentRange: SourceRange,
        val totalRange: SourceRange,
    )
}
