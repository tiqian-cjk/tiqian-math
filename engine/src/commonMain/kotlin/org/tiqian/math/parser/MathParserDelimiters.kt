package org.tiqian.math.parser

import org.tiqian.math.core.*
import org.tiqian.math.parser.ParserState.Companion.commandDelimiters
import org.tiqian.math.parser.ParserState.Companion.controlSymbolDelimiters
import org.tiqian.math.parser.ParserState.Companion.delimiterBoundaryCommands
import org.tiqian.math.parser.ParserState.Companion.delimiterMissingKinds
import org.tiqian.math.parser.ParserState.Companion.literalDelimiters
import org.tiqian.math.parser.ParserState.FixedDelimiterCommand

internal fun ParserState.parseDelimited(leftCommand: MathToken): MathDelimited {
    val left = parseDelimiterSpec(leftCommand, MathDelimiterSide.Left)
    val children = mutableListOf<MathNode>()
    var right: MathDelimiterSpec? = null
    while (right == null) {
        skipIgnored()
        val token = peek()
        when {
            token.kind == MathTokenKind.End || token.kind == MathTokenKind.CloseGroup -> {
                val insertion = SourceRange(token.range.start, token.range.start)
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingRightDelimiter,
                    "Delimited group opened by \\left is missing its matching \\right",
                    insertion,
                )
                right = syntheticInvisibleDelimiter(MathDelimiterSide.Right, insertion)
            }
            token.kind == MathTokenKind.ControlWord && token.text == "right" -> {
                val command = advance()
                right = parseDelimiterSpec(command, MathDelimiterSide.Right)
            }
            token.kind == MathTokenKind.ControlWord && token.text == "middle" -> {
                val command = advance()
                children += MathMiddleDelimiter(parseDelimiterSpec(command, MathDelimiterSide.Middle))
            }
            else -> parseAtomWithScripts()?.let { children += it }
        }
    }
    val bodyRange = when {
        children.isNotEmpty() -> children.first().range.cover(children.last().range)
        else -> SourceRange(left.range.endExclusive, left.range.endExclusive)
    }
    val body = MathList(children, bodyRange)
    return MathDelimited(
        left = left,
        body = body,
        right = right,
        range = leftCommand.range.cover(right.range),
    )
}

internal fun ParserState.parseStrayDelimiterCommand(command: MathToken, side: MathDelimiterSide): MathNode {
    val delimiter = parseDelimiterSpec(command, side)
    diagnostics += MathDiagnostic(
        if (side == MathDelimiterSide.Right) {
            DiagnosticCode.UnexpectedRightDelimiter
        } else {
            DiagnosticCode.MiddleOutsideDelimitedGroup
        },
        if (side == MathDelimiterSide.Right) {
            "Command \\right has no matching \\left"
        } else {
            "Command \\middle has no enclosing \\left ... \\right group"
        },
        delimiter.range,
    )
    return MathErrorNode(sourceSlice(delimiter.range), delimiter.range)
}

internal fun ParserState.parseFixedDelimiter(
    command: MathToken,
    fixed: FixedDelimiterCommand,
): MathFixedDelimiter {
    skipIgnored()
    val token = peek()
    if (token.kind in delimiterMissingKinds ||
        (token.kind == MathTokenKind.ControlWord && token.text in delimiterBoundaryCommands)
    ) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingDelimiterAfterFixedSizeCommand,
            "Command \\${command.text} must be followed by a delimiter token",
            command.range,
        )
        val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
        return MathFixedDelimiter(
            delimiter = syntheticInvisibleDelimiter(
                side = fixed.role.asDelimiterSide(),
                insertion = insertion,
                commandRange = command.range,
            ),
            size = fixed.size,
            role = fixed.role,
            range = command.range,
        )
    }

    advance()
    val identity = delimiterIdentity(token)
    val totalRange = command.range.cover(token.range)
    if (identity == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedDelimiter,
            "${sourceSlice(token.range)} is not a supported delimiter after \\${command.text}",
            token.range,
        )
    }
    return MathFixedDelimiter(
        delimiter = MathDelimiterSpec(
            sourceText = sourceSlice(token.range),
            identity = identity,
            side = fixed.role.asDelimiterSide(),
            commandRange = command.range,
            delimiterRange = token.range,
            range = totalRange,
        ),
        size = fixed.size,
        role = fixed.role,
        range = totalRange,
    )
}


private fun ParserState.parseDelimiterSpec(
    command: MathToken,
    side: MathDelimiterSide,
): MathDelimiterSpec {
    skipIgnored()
    val token = peek()
    if (token.kind in delimiterMissingKinds ||
        (token.kind == MathTokenKind.ControlWord && token.text in delimiterBoundaryCommands)
    ) {
        diagnostics += MathDiagnostic(
            when (side) {
                MathDelimiterSide.Left -> DiagnosticCode.MissingDelimiterAfterLeft
                MathDelimiterSide.Middle -> DiagnosticCode.MissingDelimiterAfterMiddle
                MathDelimiterSide.Right -> DiagnosticCode.MissingDelimiterAfterRight
            },
            "Command \\${command.text} must be followed by a delimiter token",
            command.range,
        )
        val insertion = SourceRange(command.range.endExclusive, command.range.endExclusive)
        return syntheticInvisibleDelimiter(side, insertion, command.range)
    }

    advance()
    val identity = delimiterIdentity(token)
    val totalRange = command.range.cover(token.range)
    if (identity == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedDelimiter,
            "${sourceSlice(token.range)} is not a supported delimiter after \\${command.text}",
            token.range,
        )
    }
    return MathDelimiterSpec(
        sourceText = sourceSlice(token.range),
        identity = identity,
        side = side,
        commandRange = command.range,
        delimiterRange = token.range,
        range = totalRange,
    )
}

private fun ParserState.syntheticInvisibleDelimiter(
    side: MathDelimiterSide,
    insertion: SourceRange,
    commandRange: SourceRange = insertion,
): MathDelimiterSpec = MathDelimiterSpec(
    sourceText = "",
    identity = MathDelimiterIdentity.Invisible,
    side = side,
    commandRange = commandRange,
    delimiterRange = insertion,
    range = if (commandRange.isEmpty) insertion else commandRange,
)

private fun ParserState.delimiterIdentity(token: MathToken): MathDelimiterIdentity? = when (token.kind) {
    MathTokenKind.Symbol -> literalDelimiters[token.text]
    MathTokenKind.ControlSymbol -> controlSymbolDelimiters[token.text]
    MathTokenKind.ControlWord -> commandDelimiters[token.text]
    else -> null
}
