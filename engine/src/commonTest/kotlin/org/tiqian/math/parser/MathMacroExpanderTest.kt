package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MathMacroExpanderTest {
    @Test
    fun formulaRecursionLimitAlsoBoundsMacroExpansionDepth() {
        assertFailsWith<IllegalArgumentException> {
            MacroExpansionLimits(maximumDepth = 513)
        }
        assertFailsWith<IllegalArgumentException> {
            MacroExpansionLimits(maximumOutputTokens = 250_001)
        }
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("one", 0, "x"),
                MathMacroDefinition("two", 0, "\\one"),
            ),
        )
        val limits = MathResourceLimits.Default.copy(maximumRecursionDepth = 1)

        val exact = expander.expand(tokens("\\one"), limits)
        assertTrue(exact.diagnostics.isEmpty(), exact.diagnostics.toString())

        val exceeded = expander.expand(tokens("\\two"), limits)
        assertEquals(
            listOf(DiagnosticCode.MacroExpansionDepthExceeded),
            exceeded.diagnostics.map { it.code },
        )
    }

    @Test
    fun shrinkingReplacementIsMeasuredByFinalOutputTokens() {
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("empty", 0, ""),
                MathMacroDefinition("many", 0, "\\empty\\empty"),
            ),
            limits = MacroExpansionLimits(maximumOutputTokens = 1),
        )

        val result = expander.expand(tokens("\\many"))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.End), result.tokens.map { it.kind })

        val formulaBounded = expander.expand(
            tokens("\\many"),
            MathResourceLimits.Default.copy(maximumTokenCount = 1),
        )
        assertTrue(formulaBounded.diagnostics.isEmpty(), formulaBounded.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.End), formulaBounded.tokens.map { it.kind })

        val exactOutputThenContraction = expander.expand(tokens("x\\many"))
        assertTrue(
            exactOutputThenContraction.diagnostics.isEmpty(),
            exactOutputThenContraction.diagnostics.toString(),
        )
        assertEquals(
            listOf(MathTokenKind.Symbol, MathTokenKind.End),
            exactOutputThenContraction.tokens.map { it.kind },
        )
    }

    @Test
    fun realExpandedOutputStillStopsAtItsTokenBudget() {
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("two", 0, "xy")),
            limits = MacroExpansionLimits(maximumOutputTokens = 1),
        )

        val result = expander.expand(tokens("\\two"))

        assertEquals(listOf(DiagnosticCode.MacroExpansionBudgetExceeded), result.diagnostics.map { it.code })
        assertEquals(listOf(MathTokenKind.Symbol, MathTokenKind.End), result.tokens.map { it.kind })
    }

    @Test
    fun replacementSourceAboveTheFormulaDefaultIsNotSilentlyErased() {
        val replacement = "\\" + "a".repeat(MathResourceLimits.Default.maximumSourceLength)
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, replacement)),
        )
        val invocation = tokens("\\huge")

        val accepted = expander.expand(invocation)

        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.ControlWord, MathTokenKind.End), accepted.tokens.map { it.kind })
        assertEquals(replacement.drop(1), accepted.tokens.first().text)
        assertEquals(SourceRange(0, 5), accepted.tokens.first().range)
    }

    @Test
    fun replacementTokensAboveTheFormulaDefaultRetainTheirTail() {
        val repeatedEmptyCount = MathResourceLimits.Default.maximumTokenCount + 1
        val expander = MathMacroExpander(
            definitions = listOf(
                MathMacroDefinition("e", 0, ""),
                MathMacroDefinition("huge", 0, "\\e".repeat(repeatedEmptyCount) + "\\+"),
            ),
        )

        val result = expander.expand(tokens("\\huge"))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(listOf(MathTokenKind.ControlSymbol, MathTokenKind.End), result.tokens.map { it.kind })
        assertEquals("+", result.tokens.first().text)
    }

    @Test
    fun replacementBeyondTheHardCompilationEnvelopeReportsItsInvocation() {
        val replacement = "\\" + "a".repeat(1_000_000)
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, replacement)),
        )

        val result = expander.expand(tokens("\\huge"))

        assertEquals(listOf(DiagnosticCode.SourceLengthLimitExceeded), result.diagnostics.map { it.code })
        assertEquals(SourceRange(0, 5), result.diagnostics.single().range)
        assertTrue(result.diagnostics.single().message.startsWith("Macro \\huge replacement:"))
        assertEquals(listOf(MathTokenKind.End), result.tokens.map { it.kind })
    }

    @Test
    fun publicExpansionUsesTheDefaultFormulaBudgetUnlessExplicitlyWidened() {
        val outputCount = MathResourceLimits.Default.maximumTokenCount + 1
        val expander = MathMacroExpander(
            definitions = listOf(MathMacroDefinition("huge", 0, "x".repeat(outputCount))),
            limits = MacroExpansionLimits(maximumOutputTokens = outputCount),
        )

        val defaultBounded = expander.expand(tokens("\\huge"))
        assertEquals(listOf(DiagnosticCode.TokenCountLimitExceeded), defaultBounded.diagnostics.map { it.code })
        assertEquals(MathResourceLimits.Default.maximumTokenCount + 1, defaultBounded.tokens.size)
        assertEquals(MathTokenKind.End, defaultBounded.tokens.last().kind)

        val widened = expander.expand(
            tokens("\\huge"),
            MathResourceLimits.Default.copy(maximumTokenCount = outputCount),
        )
        assertTrue(widened.diagnostics.isEmpty(), widened.diagnostics.toString())
        assertEquals(outputCount + 1, widened.tokens.size)
        assertEquals(MathTokenKind.End, widened.tokens.last().kind)
    }

    @Test
    fun formulaBudgetAloneCanRaiseExpandedOutputPastTheLegacyDefault() {
        val count = MathResourceLimits.Default.maximumTokenCount + 1
        val expander = MathMacroExpander(listOf(MathMacroDefinition("many", 0, "x".repeat(count))))
        val limits = MathResourceLimits.Default.copy(maximumTokenCount = count)
        val accepted = expander.expand(tokens("\\many"), limits)
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(count + 1, accepted.tokens.size) // Includes End.
        assertEquals(MathTokenKind.End, accepted.tokens.last().kind)

        val rejected = expander.expand(tokens("\\many"), limits.copy(maximumTokenCount = count - 1))
        assertEquals(listOf(DiagnosticCode.TokenCountLimitExceeded), rejected.diagnostics.map { it.code })
        assertEquals(count, rejected.tokens.size)
    }

    @Test
    fun formulaDepthAloneCanRaiseExpansionPastTheLegacyDefault() {
        val names = (1..40).map { "m" + "a".repeat(it) }
        val definitions = names.mapIndexed { index, name ->
            MathMacroDefinition(name, 0, names.getOrNull(index + 1)?.let { "\\$it" } ?: "x")
        }
        val input = tokens("\\${names.first()}")
        val expander = MathMacroExpander(definitions)
        val limits = MathResourceLimits.Default.copy(maximumRecursionDepth = 40)
        val accepted = expander.expand(input, limits)
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(listOf("x", ""), accepted.tokens.map { it.text })
        assertTrue(expander.expand(input).diagnostics.isEmpty(), "formula defaults allow depth 40")

        val rejected = expander.expand(input, limits.copy(maximumRecursionDepth = 39))
        assertEquals(listOf(DiagnosticCode.MacroExpansionDepthExceeded), rejected.diagnostics.map { it.code })

        val hostBounded = MathMacroExpander(definitions, MacroExpansionLimits(maximumDepth = 32))
        assertEquals(
            listOf(DiagnosticCode.MacroExpansionDepthExceeded),
            hostBounded.expand(input, limits).diagnostics.map { it.code },
        )
        val parsed = MathParser(macros = definitions).parse("\\${names.first()}", limits)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(1, parsed.root.children.size)
    }

    private fun tokens(source: String): List<MathToken> = MathTokenizer().tokenize(source).tokens
}
