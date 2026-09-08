package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.mathResourceLimitDiagnostic

private const val ABSOLUTE_MAXIMUM_MACRO_EXPANSION_DEPTH = 512
private const val ABSOLUTE_MAXIMUM_MACRO_OUTPUT_TOKENS = 250_000

data class MathMacroDefinition(
    val name: String,
    val parameterCount: Int,
    val replacement: String,
) {
    init {
        require(name.matches(Regex("[A-Za-z]+"))) { "macro name must be an ASCII control word" }
        require(parameterCount in 0..9) { "macro parameter count must be between 0 and 9" }
    }
}

data class MacroExpansionLimits(
    val maximumDepth: Int = 32,
    val maximumOutputTokens: Int = 20_000,
) {
    init {
        require(maximumDepth in 0..ABSOLUTE_MAXIMUM_MACRO_EXPANSION_DEPTH) {
            "maximumDepth must be between 0 and $ABSOLUTE_MAXIMUM_MACRO_EXPANSION_DEPTH"
        }
        require(maximumOutputTokens in 1..ABSOLUTE_MAXIMUM_MACRO_OUTPUT_TOKENS) {
            "maximumOutputTokens must be between 1 and $ABSOLUTE_MAXIMUM_MACRO_OUTPUT_TOKENS"
        }
    }
}

data class MacroExpansionResult(
    val tokens: List<MathToken>,
    val diagnostics: List<MathDiagnostic>,
)

/**
 * Expands only macros supplied explicitly by the host. Source-level `newcommand`
 * is intentionally outside the Markdown-math slice.
 */
class MathMacroExpander(
    definitions: List<MathMacroDefinition> = emptyList(),
    private val limits: MacroExpansionLimits? = null,
) {
    private val tokenizer = MathTokenizer()
    private val definitionsByName = definitions.associateBy { it.name }
    private val replacements = definitionsByName.mapValues { (_, definition) ->
        lazy {
            tokenizer.tokenize(definition.replacement, hostReplacementTokenizationLimits)
        }
    }

    fun expand(
        input: List<MathToken>,
        resourceLimits: MathResourceLimits = MathResourceLimits.Default,
    ): MacroExpansionResult {
        val effectiveMaximum = minOf(
            limits?.maximumOutputTokens ?: resourceLimits.maximumTokenCount,
            resourceLimits.maximumTokenCount,
        )
        return expand(
            input = input,
            maximumOutputTokens = effectiveMaximum,
            maximumDepth = minOf(
                limits?.maximumDepth ?: resourceLimits.maximumRecursionDepth,
                resourceLimits.maximumRecursionDepth,
            ),
            outputDiagnosticCode = if (limits == null || resourceLimits.maximumTokenCount < limits.maximumOutputTokens) {
                DiagnosticCode.TokenCountLimitExceeded
            } else {
                DiagnosticCode.MacroExpansionBudgetExceeded
            },
        )
    }

    private fun expand(
        input: List<MathToken>,
        maximumOutputTokens: Int,
        maximumDepth: Int,
        outputDiagnosticCode: DiagnosticCode,
    ): MacroExpansionResult {
        val diagnostics = mutableListOf<MathDiagnostic>()
        val budget = ExpansionBudget(
            maximumOutputTokens = maximumOutputTokens,
            maximumIntermediateWork = hostReplacementTokenizationLimits.maximumTokenCount.toLong(),
            diagnosticCode = outputDiagnosticCode,
            diagnostics = diagnostics,
        )
        val output = expandTokens(
            input.filterNot { it.kind == MathTokenKind.End },
            diagnostics,
            depth = 0,
            maximumDepth = maximumDepth,
            stack = emptyList(),
            budget = budget,
        ).toMutableList()
        val endRange = input.lastOrNull()?.range ?: SourceRange.Empty
        output += MathToken(MathTokenKind.End, "", SourceRange(endRange.endExclusive, endRange.endExclusive))
        return MacroExpansionResult(output, diagnostics)
    }

    private fun expandTokens(
        input: List<MathToken>,
        diagnostics: MutableList<MathDiagnostic>,
        depth: Int,
        maximumDepth: Int,
        stack: List<String>,
        budget: ExpansionBudget,
    ): List<MathToken> {
        if (depth > maximumDepth) {
            val range = input.firstOrNull()?.range ?: SourceRange.Empty
            diagnostics += MathDiagnostic(
                DiagnosticCode.MacroExpansionDepthExceeded,
                "Macro expansion exceeded depth $maximumDepth",
                range,
            )
            return buildList {
                input.forEach { token ->
                    if (!budget.consumeOutput(token.range)) return@buildList
                    add(token)
                }
            }
        }

        val output = mutableListOf<MathToken>()
        var index = 0
        while (index < input.size && !budget.exhausted) {
            val token = input[index]
            val definition = if (token.kind == MathTokenKind.ControlWord) definitionsByName[token.text] else null
            if (definition == null) {
                if (!budget.consumeOutput(token.range)) break
                output += token
                index++
                continue
            }
            if (definition.name in stack) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.RecursiveMacro,
                    "Recursive macro expansion: ${(stack + definition.name).joinToString(" -> ")}",
                    token.range,
                )
                if (!budget.consumeOutput(token.range)) break
                output += token
                index++
                continue
            }

            val arguments = mutableListOf<List<MathToken>>()
            var cursor = index + 1
            var invocationRange = token.range
            var missing = false
            repeat(definition.parameterCount) { argumentIndex ->
                while (cursor < input.size && input[cursor].kind in ignoredKinds) cursor++
                if (cursor >= input.size) {
                    diagnostics += MathDiagnostic(
                        DiagnosticCode.MissingMacroArgument,
                        "Macro \\${definition.name} is missing argument ${argumentIndex + 1}",
                        invocationRange,
                    )
                    arguments.add(emptyList())
                    missing = true
                } else {
                    val argument = readArgument(input, cursor)
                    arguments += argument.tokens
                    cursor = argument.nextIndex
                    invocationRange = invocationRange.cover(argument.range)
                }
            }
            if (missing) {
                if (!budget.consumeOutput(token.range)) break
                output += token
                index++
                continue
            }

            val tokenizedReplacement = replacements.getValue(definition.name).value
            val replacementResourceDiagnostic = tokenizedReplacement.diagnostics.firstOrNull {
                it.code == DiagnosticCode.SourceLengthLimitExceeded ||
                    it.code == DiagnosticCode.TokenCountLimitExceeded
            }
            if (replacementResourceDiagnostic != null) {
                budget.abort(
                    replacementResourceDiagnostic.copy(
                        message = "Macro \\${definition.name} replacement: " +
                            replacementResourceDiagnostic.message,
                        range = invocationRange,
                    ),
                )
                break
            }

            val substituted = mutableListOf<MathToken>()
            replacementLoop@ for (replacementToken in tokenizedReplacement.tokens) {
                if (replacementToken.kind == MathTokenKind.End) continue
                val replacement = if (replacementToken.kind == MathTokenKind.Parameter) {
                    val argumentIndex = (replacementToken.parameterIndex ?: 1) - 1
                    arguments.getOrElse(argumentIndex) {
                        diagnostics += MathDiagnostic(
                            DiagnosticCode.InvalidParameterMarker,
                            "Macro \\${definition.name} references absent parameter #${argumentIndex + 1}",
                            invocationRange,
                        )
                        emptyList()
                    }
                } else {
                    listOf(replacementToken.copy(range = invocationRange))
                }
                val work = maxOf(1, replacement.size).toLong()
                if (!budget.consumeIntermediate(work, invocationRange)) break@replacementLoop
                substituted += replacement
            }
            if (budget.exhausted) break
            output += expandTokens(
                substituted,
                diagnostics,
                depth + 1,
                maximumDepth,
                stack + definition.name,
                budget,
            )
            index = cursor
        }
        return output
    }

    private class ExpansionBudget(
        private val maximumOutputTokens: Int,
        private val maximumIntermediateWork: Long,
        private val diagnosticCode: DiagnosticCode,
        private val diagnostics: MutableList<MathDiagnostic>,
    ) {
        private var emittedOutputTokens = 0
        private var intermediateWork = 0L
        var exhausted: Boolean = false
            private set

        fun consumeOutput(range: SourceRange): Boolean {
            if (emittedOutputTokens >= maximumOutputTokens) {
                rejectOutput(range)
                return false
            }
            emittedOutputTokens += 1
            return true
        }

        fun consumeIntermediate(work: Long, range: SourceRange): Boolean {
            require(work > 0L)
            if (work > maximumIntermediateWork - intermediateWork) {
                rejectIntermediate(range)
                return false
            }
            intermediateWork += work
            return true
        }

        fun abort(diagnostic: MathDiagnostic) {
            if (exhausted) return
            exhausted = true
            diagnostics += diagnostic
        }

        private fun rejectOutput(range: SourceRange) {
            if (exhausted) return
            exhausted = true
            diagnostics += if (diagnosticCode == DiagnosticCode.TokenCountLimitExceeded) {
                mathResourceLimitDiagnostic(
                    code = diagnosticCode,
                    resource = "tokenCount",
                    actual = maximumOutputTokens.toLong() + 1L,
                    limit = maximumOutputTokens,
                    range = range,
                )
            } else {
                MathDiagnostic(
                    code = diagnosticCode,
                    message = "Macro expansion exceeded $maximumOutputTokens output tokens",
                    range = range,
                )
            }
        }

        private fun rejectIntermediate(range: SourceRange) {
            if (exhausted) return
            exhausted = true
            diagnostics += MathDiagnostic(
                code = DiagnosticCode.MacroExpansionBudgetExceeded,
                message = "Macro expansion exceeded $maximumIntermediateWork intermediate replacement work units",
                range = range,
            )
        }
    }

    private fun readArgument(tokens: List<MathToken>, start: Int): MacroArgument {
        val first = tokens[start]
        if (first.kind != MathTokenKind.OpenGroup) {
            return MacroArgument(listOf(first), start + 1, first.range)
        }

        var depth = 1
        var cursor = start + 1
        val body = mutableListOf<MathToken>()
        var range = first.range
        while (cursor < tokens.size && depth > 0) {
            val token = tokens[cursor]
            range = range.cover(token.range)
            when (token.kind) {
                MathTokenKind.OpenGroup -> {
                    depth++
                    body += token
                }
                MathTokenKind.CloseGroup -> {
                    depth--
                    if (depth > 0) body += token
                }
                else -> body += token
            }
            cursor++
        }
        return MacroArgument(body, cursor, range)
    }

    private data class MacroArgument(
        val tokens: List<MathToken>,
        val nextIndex: Int,
        val range: SourceRange,
    )

    private companion object {
        /**
         * Host-owned definitions are not part of the formula source or its raw token stream. Keep
         * their compilation independent from per-formula limits, but inside MathResourceLimits'
         * absolute safety envelope; a breach is reported at the invoking formula range.
         */
        val hostReplacementTokenizationLimits = MathResourceLimits(
            maximumSourceLength = 1_000_000,
            maximumTokenCount = ABSOLUTE_MAXIMUM_MACRO_OUTPUT_TOKENS,
        )

        val ignoredKinds = setOf(MathTokenKind.Space, MathTokenKind.Comment)
    }
}
