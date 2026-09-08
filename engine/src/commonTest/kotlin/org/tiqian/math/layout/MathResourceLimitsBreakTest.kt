package org.tiqian.math.layout

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathBreakOpportunity
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathLineBreakPolicy
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals

class MathResourceLimitsBreakTest {
    @Test
    fun breakpointCountingUsesLeadingDisplayOperatorsAndCoalescesBoundaries() {
        val leading = listOf(fragment(0, MathAtomClass.Relation, true), fragment(1, MathAtomClass.Ordinary))
        val trailing = listOf(fragment(0, MathAtomClass.Ordinary), fragment(1, MathAtomClass.Relation, true))
        val punctuation = fragment(1, MathAtomClass.Punctuation, true).let {
            it.copy(breakAfter = it.breakAfter!!.copy(kind = MathBreakKind.PunctuationTrailing))
        }
        val adjacent = listOf(
            fragment(0, MathAtomClass.Ordinary), punctuation,
            fragment(2, MathAtomClass.Relation, true), fragment(3, MathAtomClass.Ordinary),
        )
        val cases = listOf(Triple(leading, 1, 0), Triple(trailing, 0, 1), Triple(adjacent, 2, 1))
        for ((fragments, inlineCount, displayCount) in cases) {
            assertEquals(inlineCount, mathBreakpointCount(fragments, MathLineBreakPolicy.InlineTrailingOperators))
            assertEquals(displayCount, mathBreakpointCount(fragments, MathLineBreakPolicy.ResponsiveDisplayLeadingOperators))
            val result = layoutResult(fragments, MathResourceLimits.Default.copy(maximumBreakpointCount = 0))
            assertEquals(inlineCount > 0, result.breakIntoLines(25f).diagnostics.any {
                it.code == DiagnosticCode.BreakpointCountLimitExceeded
            })
            assertEquals(displayCount > 0, result.breakResponsiveDisplayLines(25f).diagnostics.any {
                it.code == DiagnosticCode.BreakpointCountLimitExceeded
            })
        }
    }

    @Test
    fun terminalBreakDoesNotConsumeInlineBreakpointBudget() {
        val fragments = listOf(
            fragment(0, MathAtomClass.Binary, breakAfter = true),
            fragment(1, MathAtomClass.Binary, breakAfter = true),
        )
        val limits = MathResourceLimits.Default.copy(maximumBreakpointCount = 1)

        val broken = layoutResult(fragments, limits).breakIntoLines(maxWidthPx = 15f)

        assertEquals(2, broken.lines.size)
        assertEquals(emptyList(), broken.diagnostics)
    }

    @Test
    fun breakpointRejectionReturnsOneDirectNaturalGeometryLine() {
        val fragments = listOf(
            fragment(0, MathAtomClass.Binary, breakAfter = true),
            fragment(1, MathAtomClass.Relation, breakAfter = true),
            fragment(2, MathAtomClass.Binary, breakAfter = true),
        )
        val limits = MathResourceLimits.Default.copy(maximumBreakpointCount = 1)

        val broken = layoutResult(fragments, limits).breakIntoLines(maxWidthPx = 15f)

        assertEquals(DiagnosticCode.BreakpointCountLimitExceeded, broken.diagnostics.single().code)
        assertEquals(1, broken.lines.size)
        assertEquals(listOf(0, 1, 2), broken.lines.single().fragments.map { it.fragmentIndex })
        assertEquals(30f, broken.lines.single().width)
        assertEquals(true, broken.lines.single().unbreakableOverflow)
        assertEquals(10f, broken.height)
    }

    @Test
    fun rejectsBeforeResponsiveRangeMatricesWhenBreakpointLimitIsExceeded() {
        val fragments = listOf(
            fragment(0, MathAtomClass.Ordinary),
            fragment(1, MathAtomClass.Binary),
            fragment(2, MathAtomClass.Ordinary),
            fragment(3, MathAtomClass.Relation),
            fragment(4, MathAtomClass.Ordinary),
        )
        val exact = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = LINE_METRICS,
            maxWidthPx = 25f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default.copy(maximumBreakpointCount = 2),
        )
        assertEquals(emptyList(), exact.layout.diagnostics)

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = LINE_METRICS,
            maxWidthPx = 25f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default.copy(maximumBreakpointCount = 1),
        )

        assertEquals(
            DiagnosticCode.BreakpointCountLimitExceeded,
            resolution.layout.diagnostics.single().code,
        )
        assertEquals(ResponsiveContinuationIndentTier.None, resolution.indentTier)
    }

    @Test
    fun rejectsResolvedContinuationIndentAboveDimensionLimit() {
        val exact = resolveResponsiveDisplayBreak(
            fragments = listOf(fragment(0, MathAtomClass.Ordinary)),
            lineMetrics = LINE_METRICS,
            maxWidthPx = 25f,
            defaultContinuationIndentPx = 10f,
            resourceLimits = MathResourceLimits.Default.copy(maximumResolvedDimensionPx = 10f),
        )
        assertEquals(emptyList(), exact.layout.diagnostics)

        val resolution = resolveResponsiveDisplayBreak(
            fragments = listOf(fragment(0, MathAtomClass.Ordinary)),
            lineMetrics = LINE_METRICS,
            maxWidthPx = 25f,
            defaultContinuationIndentPx = 11f,
            resourceLimits = MathResourceLimits.Default.copy(maximumResolvedDimensionPx = 10f),
        )

        assertEquals(DiagnosticCode.InvalidResolvedDimension, resolution.layout.diagnostics.single().code)
        assertEquals(1, resolution.layout.lines.size)
        assertEquals(0f, resolution.defaultIndentPx)
    }

    private fun fragment(
        index: Int,
        atomClass: MathAtomClass,
        breakAfter: Boolean = false,
    ): MathInlineFragment = MathInlineFragment(
        index = index,
        sourceRange = SourceRange(index, index + 1),
        atomClass = atomClass,
        box = MathBox(
            width = 10f,
            ascent = 8f,
            descent = 2f,
            inkBounds = MathRect(0f, -8f, 10f, 2f),
            glyphs = emptyList(),
            rules = emptyList(),
            range = SourceRange(index, index + 1),
        ),
        leadingKernPx = 0f,
        trailingItalicCorrectionPx = 0f,
        trailingGlue = MathGlueAdjustment.Zero,
        breakAfter = if (breakAfter) {
            MathBreakOpportunity(
                afterFragmentIndex = index,
                sourceOffset = index + 1,
                kind = MathBreakKind.BinaryOperatorTrailing,
                discardedTrailingGlue = MathGlueAdjustment.Zero,
                priority = MathAdjustmentPriority.BinaryOperator,
            )
        } else {
            null
        },
    )

    private fun layoutResult(
        fragments: List<MathInlineFragment>,
        resourceLimits: MathResourceLimits = MathResourceLimits.Default,
    ): MathLayoutResult = MathLayoutResult(
        source = "x".repeat(fragments.size),
        mode = MathMode.Inline,
        initialStyle = MathStyle.Text,
        box = MathBox(
            width = fragments.size * 10f,
            ascent = 8f,
            descent = 2f,
            inkBounds = MathRect(0f, -8f, fragments.size * 10f, 2f),
            glyphs = emptyList(),
            rules = emptyList(),
            range = SourceRange(0, fragments.size),
        ),
        fragments = fragments,
        breakOpportunities = fragments.mapNotNull { it.breakAfter },
        diagnostics = emptyList(),
        lineMetrics = LINE_METRICS,
        decisions = emptyList(),
        debugDumpRenderer = { "" },
        fontSizePx = 10f,
        resourceLimits = resourceLimits,
    )

    private companion object {
        val LINE_METRICS = MathFormulaLineMetrics(
            fontAscentPx = 8f,
            fontDescentPx = 2f,
            fontLineGapPx = 0f,
            mathLeadingPx = 0f,
            inkAscentPx = 8f,
            inkDescentPx = 2f,
            logicalAscentPx = 8f,
            logicalDescentPx = 2f,
        )
    }
}
