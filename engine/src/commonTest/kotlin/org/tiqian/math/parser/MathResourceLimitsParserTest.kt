package org.tiqian.math.parser

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathParseResult
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathText
import org.tiqian.math.core.SourceRange
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathResourceLimitsParserTest {
    @Test
    fun defaultLimitsAreStableAndPositive() {
        val limits = MathResourceLimits.Default

        assertEquals(65_536, limits.maximumSourceLength)
        assertEquals(20_000, limits.maximumTokenCount)
        assertEquals(20_000, limits.maximumNodeCount)
        assertEquals(128, limits.maximumRecursionDepth)
        assertEquals(1_024, limits.maximumBreakpointCount)
        assertEquals(4_096, limits.maximumExtenderCount)
        assertEquals(65_536f, limits.maximumResolvedDimensionPx)
    }

    @Test
    fun limitsRejectValuesOutsideTheHardSafetyEnvelope() {
        val invalidIntegerLimits = listOf<() -> MathResourceLimits>(
            { MathResourceLimits(maximumSourceLength = 0) },
            { MathResourceLimits(maximumTokenCount = 0) },
            { MathResourceLimits(maximumNodeCount = 0) },
            { MathResourceLimits(maximumRecursionDepth = 0) },
            { MathResourceLimits(maximumSourceLength = Int.MAX_VALUE) },
            { MathResourceLimits(maximumTokenCount = Int.MAX_VALUE) },
            { MathResourceLimits(maximumNodeCount = Int.MAX_VALUE) },
            { MathResourceLimits(maximumRecursionDepth = Int.MAX_VALUE) },
            { MathResourceLimits(maximumBreakpointCount = Int.MAX_VALUE) },
            { MathResourceLimits(maximumExtenderCount = Int.MAX_VALUE) },
        )
        invalidIntegerLimits.forEachIndexed { index, construct ->
            assertFailsWith<IllegalArgumentException>("invalid integer limit case $index") { construct() }
        }

        val zeroGeneratedResources = MathResourceLimits(
            maximumBreakpointCount = 0,
            maximumExtenderCount = 0,
        )
        assertEquals(0, zeroGeneratedResources.maximumBreakpointCount)
        assertEquals(0, zeroGeneratedResources.maximumExtenderCount)

        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)
            .forEach { value ->
                assertFailsWith<IllegalArgumentException>("invalid resolved dimension limit $value") {
                    MathResourceLimits(maximumResolvedDimensionPx = value)
                }
            }
    }

    @Test
    fun sourceLengthUsesUtf16AndRejectsBeforeTokenization() {
        val source = "𝑥"
        val accepted = MathParser().parse(
            source,
            MathResourceLimits.Default.copy(maximumSourceLength = 2),
        )
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())

        val rejected = MathParser().parse(
            source,
            MathResourceLimits.Default.copy(maximumSourceLength = 1),
        )
        assertResourceRejection(rejected, DiagnosticCode.SourceLengthLimitExceeded)
        assertEquals(SourceRange(0, 2), rejected.diagnostics.single().range)

        val trailingEscape = MathParser().parse(
            "x\\",
            MathResourceLimits.Default.copy(maximumSourceLength = 1),
        )
        assertResourceRejection(trailingEscape, DiagnosticCode.SourceLengthLimitExceeded)
        assertTrue(trailingEscape.diagnostics.none { it.code == DiagnosticCode.TrailingEscape })
    }

    @Test
    fun rawTokenLimitExcludesEndButIncludesIgnoredTokens() {
        val oneToken = MathResourceLimits.Default.copy(maximumTokenCount = 1)
        val accepted = MathParser().parse("x", oneToken)
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())

        val secondSymbol = MathParser().parse("xy", oneToken)
        assertResourceRejection(secondSymbol, DiagnosticCode.TokenCountLimitExceeded)
        assertEquals(SourceRange(1, 2), secondSymbol.diagnostics.single().range)

        val ignoredSpace = MathParser().parse("x ", oneToken)
        assertResourceRejection(ignoredSpace, DiagnosticCode.TokenCountLimitExceeded)
        assertEquals(SourceRange(1, 2), ignoredSpace.diagnostics.single().range)
    }

    @Test
    fun expandedTokenStreamHasAnIndependentExactLimit() {
        val parser = MathParser(
            macros = listOf(MathMacroDefinition("dup", 0, "x+y")),
            expansionLimits = MacroExpansionLimits(maximumOutputTokens = 100),
        )
        val accepted = parser.parse(
            "\\dup",
            MathResourceLimits.Default.copy(maximumTokenCount = 3),
        )
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(3, accepted.root.children.size)

        val rejected = parser.parse(
            "\\dup",
            MathResourceLimits.Default.copy(maximumTokenCount = 2),
        )
        assertResourceRejection(rejected, DiagnosticCode.TokenCountLimitExceeded)
        assertEquals(SourceRange(0, 4), rejected.diagnostics.single().range)
    }

    @Test
    fun nodeLimitCountsOnlyNodesRetainedByTheFinalAstIncludingRoot() {
        val twoNodes = MathResourceLimits.Default.copy(maximumNodeCount = 2)
        val symbol = MathParser().parse("x", twoNodes)
        assertTrue(symbol.diagnostics.isEmpty(), symbol.diagnostics.toString())

        val mergedCjk = MathParser().parse("中文", twoNodes)
        assertTrue(mergedCjk.diagnostics.isEmpty(), mergedCjk.diagnostics.toString())
        assertEquals(1, mergedCjk.root.children.size)
        assertEquals(2, assertIs<MathText>(mergedCjk.root.children.single()).segments.size)

        assertResourceRejection(
            MathParser().parse("x", twoNodes.copy(maximumNodeCount = 1)),
            DiagnosticCode.AstNodeCountLimitExceeded,
        )

        val grouped = MathParser().parse(
            "{x}",
            MathResourceLimits.Default.copy(maximumNodeCount = 4),
        )
        assertTrue(grouped.diagnostics.isEmpty(), grouped.diagnostics.toString())
        assertResourceRejection(
            MathParser().parse(
                "{x}",
                MathResourceLimits.Default.copy(maximumNodeCount = 3),
            ),
            DiagnosticCode.AstNodeCountLimitExceeded,
        )
    }

    @Test
    fun recursionLimitAppliesToActiveParserFramesAndFinalAstDepth() {
        val leafAtExactAstDepth = MathParser().parse(
            "x",
            MathResourceLimits.Default.copy(maximumRecursionDepth = 2),
        )
        assertTrue(leafAtExactAstDepth.diagnostics.isEmpty(), leafAtExactAstDepth.diagnostics.toString())

        val astDepthExceeded = MathParser().parse(
            "x",
            MathResourceLimits.Default.copy(maximumRecursionDepth = 1),
        )
        assertResourceRejection(astDepthExceeded, DiagnosticCode.RecursionDepthLimitExceeded)

        val parserFrameExceeded = MathParser().parse(
            "{{x}}",
            MathResourceLimits.Default.copy(maximumRecursionDepth = 2),
        )
        assertResourceRejection(parserFrameExceeded, DiagnosticCode.RecursionDepthLimitExceeded)

        val nestedAtExactAstDepth = MathParser().parse(
            "{{x}}",
            MathResourceLimits.Default.copy(maximumRecursionDepth = 6),
        )
        assertTrue(nestedAtExactAstDepth.diagnostics.isEmpty(), nestedAtExactAstDepth.diagnostics.toString())
    }

    @Test
    fun widenedFormulaTokenBudgetDoesNotRequireLegacyMacroConfiguration() {
        val count = MathResourceLimits.Default.maximumTokenCount + 1
        val source = "x".repeat(count)
        val limits = MathResourceLimits.Default.copy(
            maximumTokenCount = count,
            maximumNodeCount = count + 1,
        )
        val accepted = MathParser().parse(source, limits)
        assertTrue(accepted.diagnostics.isEmpty(), accepted.diagnostics.toString())
        assertEquals(count, accepted.root.children.size)
        assertResourceRejection(MathParser().parse(source), DiagnosticCode.TokenCountLimitExceeded)

        val explicitHostLimit = MathParser(expansionLimits = MacroExpansionLimits(maximumOutputTokens = 10))
        assertResourceRejection(explicitHostLimit.parse(source, limits), DiagnosticCode.MacroExpansionBudgetExceeded)
    }

    @Test
    fun recursionRejectionPreservesEarlierSyntaxDiagnosticsAndRanges() {
        // The first case fails while parsing; the second fails in final AST validation.
        listOf("}{{x}}" to 2, "}x" to 1).forEach { (source, maximumDepth) ->
            val result = MathParser().parse(
                source,
                MathResourceLimits.Default.copy(maximumRecursionDepth = maximumDepth),
            )
            assertEquals(
                listOf(DiagnosticCode.UnexpectedClosingGroup, DiagnosticCode.RecursionDepthLimitExceeded),
                result.diagnostics.map { it.code },
                source,
            )
            assertEquals(SourceRange(0, 1), result.diagnostics.first().range)
            assertTrue(result.root.children.isEmpty(), "rejection must not expose a partial AST")
        }
    }

    @Test
    fun everyResourceDiagnosticHasTheResourceLimitCapabilityCategory() {
        val resourceCodes = listOf(
            DiagnosticCode.SourceLengthLimitExceeded,
            DiagnosticCode.TokenCountLimitExceeded,
            DiagnosticCode.AstNodeCountLimitExceeded,
            DiagnosticCode.RecursionDepthLimitExceeded,
            DiagnosticCode.BreakpointCountLimitExceeded,
            DiagnosticCode.ExtenderCountLimitExceeded,
            DiagnosticCode.InvalidResolvedDimension,
            DiagnosticCode.MacroExpansionDepthExceeded,
            DiagnosticCode.MacroExpansionBudgetExceeded,
        )

        resourceCodes.forEach { code ->
            assertEquals(
                MathFormulaCapabilityCategory.ResourceLimitExceeded,
                MathFormulaCapabilityClassifier.category(code),
                code.name,
            )
        }
    }

    private fun assertResourceRejection(result: MathParseResult, code: DiagnosticCode) {
        assertEquals(listOf(code), result.diagnostics.map { it.code }, result.diagnostics.toString())
        assertTrue(result.root.children.isEmpty(), "resource rejection must return a bounded empty root")
    }
}
