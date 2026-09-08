package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.mathResourceLimitDiagnostic

enum class MathTokenKind {
    Symbol,
    ControlWord,
    ControlSymbol,
    OpenGroup,
    CloseGroup,
    Superscript,
    Subscript,
    Parameter,
    Space,
    Comment,
    End,
}

data class MathToken(
    val kind: MathTokenKind,
    val text: String,
    val range: SourceRange,
    val parameterIndex: Int? = null,
)

data class TokenizationResult(
    val tokens: List<MathToken>,
    val diagnostics: List<MathDiagnostic>,
)

class MathTokenizer {
    fun tokenize(
        source: String,
        resourceLimits: MathResourceLimits = MathResourceLimits.Default,
    ): TokenizationResult {
        val tokens = mutableListOf<MathToken>()
        val diagnostics = mutableListOf<MathDiagnostic>()
        if (source.length > resourceLimits.maximumSourceLength) {
            diagnostics += mathResourceLimitDiagnostic(
                code = DiagnosticCode.SourceLengthLimitExceeded,
                resource = "sourceLength",
                actual = source.length,
                limit = resourceLimits.maximumSourceLength,
                range = SourceRange(0, source.length),
            )
            tokens += MathToken(MathTokenKind.End, "", SourceRange.Empty)
            return TokenizationResult(tokens, diagnostics)
        }
        var index = 0
        var awaitingBboxOptions = false
        var insideBboxOptions = false
        // HtmlHexColorArgument: xcolor-in-MathJax color arguments carry `#RGB`/`#RRGGBB`, so the
        // single group right after \color relaxes TeX's macro-parameter reading of `#`.
        var awaitingColorArgument = false
        var insideColorArgument = false
        while (index < source.length) {
            if (tokens.size >= resourceLimits.maximumTokenCount) {
                diagnostics += mathResourceLimitDiagnostic(
                    code = DiagnosticCode.TokenCountLimitExceeded,
                    resource = "tokenCount",
                    actual = tokens.size + 1,
                    limit = resourceLimits.maximumTokenCount,
                    range = SourceRange(index, source.nextCodePointIndex(index)),
                )
                break
            }
            val start = index
            val char = source[index]
            if (awaitingBboxOptions && !char.isWhitespace() && char != '[' && char != '%') {
                awaitingBboxOptions = false
            }
            if (awaitingColorArgument && !char.isWhitespace() && char != '{' && char != '%') {
                awaitingColorArgument = false
            }
            when {
                char == '\\' -> {
                    index++
                    if (index == source.length) {
                        val range = SourceRange(start, index)
                        tokens += MathToken(MathTokenKind.ControlSymbol, "\\", range)
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.TrailingEscape,
                            "Trailing backslash has no control sequence target",
                            range,
                        )
                    } else if (source[index].isAsciiLetter()) {
                        val nameStart = index
                        while (index < source.length && source[index].isAsciiLetter()) index++
                        val name = source.substring(nameStart, index)
                        tokens += MathToken(
                            MathTokenKind.ControlWord,
                            name,
                            SourceRange(start, index),
                        )
                        awaitingBboxOptions = name == "bbox"
                        awaitingColorArgument = name in ParserState.hexColorArgumentCommands
                    } else {
                        index = source.nextCodePointIndex(index)
                        tokens += MathToken(
                            MathTokenKind.ControlSymbol,
                            source.substring(start + 1, index),
                            SourceRange(start, index),
                        )
                    }
                }
                char == '{' -> {
                    // The parser uses the body opener as the recovery boundary for an
                    // unterminated bbox option list. Do not leak its relaxed '#' rule
                    // into the following math field or later source.
                    insideBboxOptions = false
                    insideColorArgument = awaitingColorArgument
                    awaitingColorArgument = false
                    index++
                    tokens += MathToken(MathTokenKind.OpenGroup, "{", SourceRange(start, index))
                }
                char == '}' -> {
                    insideColorArgument = false
                    index++
                    tokens += MathToken(MathTokenKind.CloseGroup, "}", SourceRange(start, index))
                }
                char == '^' -> {
                    index++
                    tokens += MathToken(MathTokenKind.Superscript, "^", SourceRange(start, index))
                }
                char == '_' -> {
                    index++
                    tokens += MathToken(MathTokenKind.Subscript, "_", SourceRange(start, index))
                }
                char == '#' && (insideBboxOptions || insideColorArgument) -> {
                    index++
                    tokens += MathToken(MathTokenKind.Symbol, "#", SourceRange(start, index))
                }
                char == '#' -> {
                    index++
                    if (index < source.length && source[index] in '1'..'9') {
                        val parameter = source[index].digitToInt()
                        index++
                        tokens += MathToken(
                            MathTokenKind.Parameter,
                            source.substring(start, index),
                            SourceRange(start, index),
                            parameter,
                        )
                    } else {
                        val range = SourceRange(start, index)
                        tokens += MathToken(MathTokenKind.Symbol, "#", range)
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.InvalidParameterMarker,
                            "Macro parameter marker must be followed by 1 through 9",
                            range,
                        )
                    }
                }
                char == '%' -> {
                    index++
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                    tokens += MathToken(
                        MathTokenKind.Comment,
                        source.substring(start, index),
                        SourceRange(start, index),
                    )
                }
                char.isWhitespace() -> {
                    index++
                    while (index < source.length && source[index].isWhitespace()) index++
                    tokens += MathToken(
                        MathTokenKind.Space,
                        source.substring(start, index),
                        SourceRange(start, index),
                    )
                }
                else -> {
                    index = source.nextCodePointIndex(index)
                    tokens += MathToken(
                        MathTokenKind.Symbol,
                        source.substring(start, index),
                        SourceRange(start, index),
                    )
                    if (char == '[' && awaitingBboxOptions) {
                        insideBboxOptions = true
                        awaitingBboxOptions = false
                    } else if (char == ']' && insideBboxOptions) {
                        insideBboxOptions = false
                    }
                }
            }
        }
        tokens += MathToken(MathTokenKind.End, "", SourceRange(index, index))
        return TokenizationResult(tokens, diagnostics)
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun String.nextCodePointIndex(index: Int): Int {
    val first = this[index]
    return if (
        first.isHighSurrogate() &&
        index + 1 < length &&
        this[index + 1].isLowSurrogate()
    ) {
        index + 2
    } else {
        index + 1
    }
}
