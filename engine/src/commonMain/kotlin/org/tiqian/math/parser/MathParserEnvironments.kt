package org.tiqian.math.parser

import org.tiqian.math.core.*
import org.tiqian.math.parser.ParserState.Companion.displayEnvironments
import org.tiqian.math.parser.ParserState.Companion.rowSpacingPattern
import org.tiqian.math.parser.ParserState.Companion.rowSpacingUnits
import org.tiqian.math.parser.ParserState.Companion.tableEnvironments
import org.tiqian.math.parser.ParserState.ParsedEnvironmentName

internal fun ParserState.parseEnvironment(beginCommand: MathToken): MathNode {
    val parsedName = parseEnvironmentName(beginCommand)
    if (parsedName == null) {
        return MathErrorNode(sourceSlice(beginCommand.range), beginCommand.range)
    }
    val displayEnvironment = displayEnvironments[parsedName.name]
    if (
        displayEnvironment != null && structureDepth > 0 &&
        structureDepth !in boxDisplayContainerDepths
    ) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedDisplayEnvironment,
            "Display environment ${parsedName.name} cannot be nested inside a math atom or another environment",
            beginCommand.range.cover(parsedName.totalRange),
        )
    }
    if (displayEnvironment != null && !displayEnvironment.alignment) {
        return parseSingleEquationEnvironment(beginCommand, parsedName, displayEnvironment)
    }
    val environment = tableEnvironments[parsedName.name]
        ?: when (displayEnvironment) {
            MathDisplayEnvironmentKind.Gather,
            MathDisplayEnvironmentKind.GatherStar,
            -> MathTableEnvironment.Gathered
            null -> null
            else -> if (displayEnvironment.alignment) MathTableEnvironment.Aligned else null
        }
    if (environment == null && displayEnvironment == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedEnvironment,
            "Environment ${parsedName.name} is not supported",
            parsedName.contentRange,
        )
    }
    val columnAlignments = if (environment == MathTableEnvironment.Array) {
        parseArrayColumnSpecification(beginCommand)
    } else {
        emptyList()
    }

    val rows = mutableListOf<MathTableRow>()
    val horizontalRules = mutableListOf<MathTableHorizontalRule>()
    var currentNodes = mutableListOf<MathNode>()
    var currentCells = mutableListOf<MathTableCell>()
    var currentCellStart = parsedName.totalRange.endExclusive
    var currentRowStart = currentCellStart
    var endCommand: MathToken? = null
    var endName: ParsedEnvironmentName? = null

    fun finishCell(separatorRange: SourceRange?) {
        val range = if (currentNodes.isEmpty()) {
            val insertion = separatorRange?.start ?: peek().range.start
            SourceRange(insertion, insertion)
        } else {
            currentNodes.first().range.cover(currentNodes.last().range)
        }
        currentCells += MathTableCell(
            body = MathList(currentNodes.toList(), range),
            columnSeparatorRange = separatorRange,
            range = range,
        )
        currentNodes = mutableListOf()
        currentCellStart = separatorRange?.endExclusive ?: range.endExclusive
    }

    fun finishRow(
        separatorRange: SourceRange?,
        additionalSpacing: MathTeXDimension?,
        allowEmpty: Boolean,
    ) {
        finishCell(null)
        val tags = currentCells.flatMap { it.body.children.filterIsInstance<MathEquationTag>() }
        tags.drop(1).forEach { duplicate ->
            diagnostics += MathDiagnostic(
                DiagnosticCode.MultipleEquationTags,
                "An alignment row may contain only one equation tag",
                duplicate.range,
            )
        }
        val cleanCells = currentCells.map { cell ->
            val children = cell.body.children.filterNot { it is MathEquationTag }
            val range = children.firstOrNull()?.range?.cover(children.last().range)
                ?: SourceRange(cell.range.start, cell.range.start)
            cell.copy(body = MathList(children, range), range = range)
        }
        val hasContent = cleanCells.any { it.body.children.isNotEmpty() }
        if (hasContent || allowEmpty) {
            val range = currentCells.firstOrNull()?.range?.cover(currentCells.last().range)
                ?: SourceRange(currentRowStart, currentRowStart)
            rows += MathTableRow(
                cells = cleanCells,
                rowSeparatorRange = separatorRange,
                additionalSpacing = additionalSpacing,
                range = range,
                tag = tags.firstOrNull(),
            )
        }
        currentCells = mutableListOf()
        currentRowStart = additionalSpacing?.range?.endExclusive
            ?: separatorRange?.endExclusive
            ?: currentCellStart
        currentCellStart = currentRowStart
    }

    structureDepth += 1
    try {
        while (true) {
            skipIgnored()
            val token = peek()
            when {
                token.kind == MathTokenKind.End -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingEnvironmentEnd,
                        "Environment ${parsedName.name} is missing \\end{${parsedName.name}}",
                        beginCommand.range.cover(parsedName.totalRange),
                    )
                    finishRow(null, null, rows.isEmpty())
                    break
                }
                token.kind == MathTokenKind.ControlWord && token.text == "end" -> {
                    val closingCommand = advance()
                    val closingName = parseEnvironmentName(closingCommand)
                    endCommand = closingCommand
                    endName = closingName
                    if (closingName == null || closingName.name != parsedName.name) {
                        val range = closingName?.totalRange?.let(closingCommand.range::cover) ?: closingCommand.range
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.MismatchedEnvironmentEnd,
                            "Expected \\end{${parsedName.name}} but found \\end{${closingName?.name.orEmpty()}}",
                            range,
                        )
                    }
                    finishRow(null, null, rows.isEmpty())
                    break
                }
                token.kind == MathTokenKind.Symbol && token.text == "&" -> {
                    val separator = advance()
                    if (environment == MathTableEnvironment.Gathered) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.UnexpectedAlignmentTab,
                            "Environment ${parsedName.name} centers one formula per row and does not accept alignment tabs",
                            separator.range,
                        )
                    }
                    finishCell(separator.range)
                }
                token.kind == MathTokenKind.ControlSymbol && token.text == "\\" -> {
                    val separator = advance()
                    val additionalSpacing = parseOptionalRowSpacing(separator)
                    finishRow(separator.range, additionalSpacing, true)
                }
                token.kind == MathTokenKind.ControlWord && token.text == "hline" -> {
                    val command = advance()
                    val rowHasContent = currentNodes.isNotEmpty() || currentCells.any {
                        it.body.children.isNotEmpty()
                    }
                    if (rowHasContent) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.MisplacedHorizontalRule,
                            "Command \\hline must occur before the first row or immediately after a row separator",
                            command.range,
                        )
                    } else {
                        horizontalRules += MathTableHorizontalRule(rows.size, command.range)
                    }
                }
                else -> parseAtomWithScripts()?.let { currentNodes += it }
            }
        }
    } finally {
        structureDepth -= 1
    }

    val finalRange = endName?.totalRange?.let(beginCommand.range::cover)
        ?: rows.lastOrNull()?.range?.let(beginCommand.range::cover)
        ?: beginCommand.range.cover(parsedName.totalRange)
    val table = MathTable(
        environmentName = parsedName.name,
        environment = environment,
        rows = rows,
        horizontalRules = horizontalRules,
        columnAlignments = columnAlignments,
        beginCommandRange = beginCommand.range,
        beginNameRange = parsedName.contentRange,
        endCommandRange = endCommand?.range,
        endNameRange = endName?.contentRange,
        range = finalRange,
    )
    return if (displayEnvironment != null) {
        MathDisplayEnvironment(
            kind = displayEnvironment,
            body = table,
            beginCommandRange = beginCommand.range,
            beginNameRange = parsedName.contentRange,
            endCommandRange = endCommand?.range,
            endNameRange = endName?.contentRange,
            range = finalRange,
        )
    } else {
        table
    }
}

private fun ParserState.parseSingleEquationEnvironment(
    beginCommand: MathToken,
    parsedName: ParsedEnvironmentName,
    kind: MathDisplayEnvironmentKind,
): MathDisplayEnvironment {
    val children = mutableListOf<MathNode>()
    var endCommand: MathToken? = null
    var endName: ParsedEnvironmentName? = null
    structureDepth += 1
    try {
        while (true) {
            skipIgnored()
            val token = peek()
            when {
                token.kind == MathTokenKind.End -> {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingEnvironmentEnd,
                        "Environment ${parsedName.name} is missing \\end{${parsedName.name}}",
                        beginCommand.range.cover(parsedName.totalRange),
                    )
                    break
                }
                token.kind == MathTokenKind.ControlWord && token.text == "end" -> {
                    val closingCommand = advance()
                    val closingName = parseEnvironmentName(closingCommand)
                    endCommand = closingCommand
                    endName = closingName
                    if (closingName == null || closingName.name != parsedName.name) {
                        val range = closingName?.totalRange?.let(closingCommand.range::cover) ?: closingCommand.range
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.MismatchedEnvironmentEnd,
                            "Expected \\end{${parsedName.name}} but found \\end{${closingName?.name.orEmpty()}}",
                            range,
                        )
                    }
                    break
                }
                else -> parseAtomWithScripts()?.let { children += it }
            }
        }
    } finally {
        structureDepth -= 1
    }
    val (bodyChildren, tag) = extractEquationTag(children)
    val bodyRange = if (bodyChildren.isEmpty()) {
        SourceRange(parsedName.totalRange.endExclusive, parsedName.totalRange.endExclusive)
    } else {
        bodyChildren.first().range.cover(bodyChildren.last().range)
    }
    val finalRange = endName?.totalRange?.let(beginCommand.range::cover)
        ?: children.lastOrNull()?.range?.let(beginCommand.range::cover)
        ?: beginCommand.range.cover(parsedName.totalRange)
    return MathDisplayEnvironment(
        kind = kind,
        body = MathList(bodyChildren, bodyRange),
        beginCommandRange = beginCommand.range,
        beginNameRange = parsedName.contentRange,
        endCommandRange = endCommand?.range,
        endNameRange = endName?.contentRange,
        range = finalRange,
        tag = tag,
    )
}

internal fun ParserState.parseEquationTag(command: MathToken): MathEquationTag {
    skipIgnored()
    val star = peek().takeIf { it.kind == MathTokenKind.Symbol && it.text == "*" }?.also { advance() }
    skipIgnored()
    val next = peek()
    if (next.kind in setOf(
            MathTokenKind.End,
            MathTokenKind.CloseGroup,
            MathTokenKind.Superscript,
            MathTokenKind.Subscript,
        )
    ) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingEquationTagArgument,
            "Command \\tag requires a text token or braced tag",
            command.range,
        )
        val insertion = SourceRange((star?.range ?: command.range).endExclusive, (star?.range ?: command.range).endExclusive)
        return MathEquationTag(
            segments = emptyList(),
            starred = star != null,
            commandRange = command.range,
            starRange = star?.range,
            contentRange = insertion,
            argumentRange = insertion,
            range = command.range.cover(star?.range ?: command.range),
        )
    }
    val argument = parseTextArgument(command, "equation tag")
    return MathEquationTag(
        segments = argument.segments,
        starred = star != null,
        commandRange = command.range,
        starRange = star?.range,
        contentRange = argument.contentRange,
        argumentRange = argument.totalRange,
        range = command.range.cover(argument.totalRange),
    )
}

internal fun ParserState.parseOptionalRowSpacing(separator: MathToken): MathTeXDimension? {
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
        if (
            token.kind == MathTokenKind.ControlSymbol && token.text == "\\" ||
            token.kind == MathTokenKind.ControlWord && token.text == "end"
        ) {
            break
        }
        advance()
    }
    val contentEnd = closing?.range?.start ?: peek().range.start
    val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
    val totalRange = closing?.range?.let(opening.range::cover) ?: opening.range.cover(contentRange)
    if (closing == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidRowSpacing,
            "Optional row spacing after \\\\ is not closed",
            separator.range.cover(totalRange),
        )
        return null
    }
    val sourceText = sourceSlice(contentRange)
    val match = rowSpacingPattern.matchEntire(sourceText.trim())
    if (match == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidRowSpacing,
            "Optional row spacing must be a supported TeX dimension",
            contentRange,
        )
        return null
    }
    val value = match.groupValues[1].toFloatOrNull()
    val unitName = match.groupValues[2].lowercase()
    val unit = rowSpacingUnits[unitName]
    if (value == null || !value.isFinite() || unit == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidRowSpacing,
            "Optional row spacing ${sourceText.trim()} is not a finite supported TeX dimension",
            contentRange,
        )
        return null
    }
    return MathTeXDimension(
        value = value,
        unit = unit,
        sourceText = sourceText,
        contentRange = contentRange,
        range = totalRange,
    )
}


internal fun ParserState.parseEnvironmentName(command: MathToken): ParsedEnvironmentName? {
    skipIgnored()
    val opening = peek()
    if (opening.kind != MathTokenKind.OpenGroup) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingEnvironmentName,
            "Command \\${command.text} requires a braced environment name",
            command.range,
        )
        return null
    }
    advance()
    val contentStart = opening.range.endExclusive
    var closing: MathToken? = null
    while (peek().kind != MathTokenKind.End) {
        val token = advance()
        if (token.kind == MathTokenKind.CloseGroup) {
            closing = token
            break
        }
        if (token.kind == MathTokenKind.OpenGroup) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingEnvironmentName,
                "Environment names cannot contain nested groups",
                token.range,
            )
        }
    }
    val contentEnd = closing?.range?.start ?: peek().range.start
    val contentRange = SourceRange(contentStart, contentEnd.coerceAtLeast(contentStart))
    if (closing == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnclosedGroup,
            "Environment name opened here is not closed",
            opening.range,
        )
    }
    val name = sourceSlice(contentRange).trim()
    if (name.isEmpty()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingEnvironmentName,
            "Environment name must not be empty",
            contentRange,
        )
    }
    return ParsedEnvironmentName(
        name = name,
        contentRange = contentRange,
        totalRange = closing?.let { opening.range.cover(it.range) } ?: opening.range.cover(contentRange),
    )
}

private fun ParserState.parseArrayColumnSpecification(command: MathToken): List<MathTableColumnAlignment> {
    val parsed = parseEnvironmentName(command)
    if (parsed == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingArrayColumnSpecification,
            "Array environment requires a braced column specification",
            command.range,
        )
        return emptyList()
    }
    val alignments = parsed.name.mapNotNull { character ->
        when (character) {
            'l' -> MathTableColumnAlignment.Left
            'c' -> MathTableColumnAlignment.Center
            'r' -> MathTableColumnAlignment.Right
            else -> {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.InvalidArrayColumnSpecification,
                    "Array column specifier $character is not supported",
                    parsed.contentRange,
                )
                null
            }
        }
    }
    if (alignments.isEmpty()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingArrayColumnSpecification,
            "Array environment needs at least one l, c, or r column",
            parsed.contentRange,
        )
    }
    return alignments
}
