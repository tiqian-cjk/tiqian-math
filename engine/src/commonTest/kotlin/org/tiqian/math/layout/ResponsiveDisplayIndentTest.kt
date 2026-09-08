package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathGlueKind
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponsiveDisplayIndentTest {
    @Test
    fun fallsBackFromSemanticAnchorToTwoEmForEveryContinuationLine() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 100f, 10f, 100f),
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 80f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(ResponsiveContinuationIndentTier.DefaultIndent, resolution.indentTier)
        assertNear(100f, resolution.requestedSemanticIndentPx)
        assertNear(80f, resolution.defaultIndentPx)
        assertNear(90f, resolution.maximumCommonFeasibleIndentPx)
        assertNear(80f, resolution.resolvedIndentPx)
        assertSharedPaintedOperatorAnchor(resolution)
    }

    @Test
    fun fallsBackToMaximumCommonFeasibleIndentWhenTwoEmCannotFit() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 185f, 10f, 180f),
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 80f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(
            ResponsiveContinuationIndentTier.MaximumCommonFeasibleIndent,
            resolution.indentTier,
        )
        assertNear(100f, resolution.requestedSemanticIndentPx)
        assertNear(5f, resolution.maximumCommonFeasibleIndentPx)
        assertNear(5f, resolution.resolvedIndentPx)
        assertTrue(resolution.resolvedIndentPx > 0f, "the last tier preserves every feasible indent")
        assertSharedPaintedOperatorAnchor(resolution)
    }

    @Test
    fun keepsSemanticOperatorAnchorWhenEveryContinuationLineCanFitIt() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 50f, 10f, 40f),
            lineMetrics = lineMetrics,
            maxWidthPx = 170f,
            defaultContinuationIndentPx = 80f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(ResponsiveContinuationIndentTier.SemanticOperatorAnchor, resolution.indentTier)
        assertNear(100f, resolution.resolvedIndentPx)
        assertSharedPaintedOperatorAnchor(resolution)
    }

    @Test
    fun semanticAnchorOutsideViewportStillUsesTheDefaultTierInsteadOfDroppingIndent() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(250f, 10f, 40f, 10f, 30f),
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 80f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(ResponsiveContinuationIndentTier.DefaultIndent, resolution.indentTier)
        assertNear(250f, resolution.requestedSemanticIndentPx)
        assertNear(80f, resolution.resolvedIndentPx)
        assertSharedPaintedOperatorAnchor(resolution)
    }

    @Test
    fun reoptimizesLineRangesAfterTheIndentFallsBack() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 80f, 10f, 20f),
            lineMetrics = lineMetrics,
            maxWidthPx = 170f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(ResponsiveContinuationIndentTier.DefaultIndent, resolution.indentTier)
        assertNear(100f, resolution.requestedSemanticIndentPx)
        assertNear(40f, resolution.resolvedIndentPx)
        assertEquals(
            listOf(0..0, 1..4),
            resolution.layout.lines.map { line ->
                line.fragments.first().fragmentIndex..line.fragments.last().fragmentIndex
            },
            "the fallback indent must rerun the optimizer instead of replaying semantic-indent ranges",
        )
        assertTrue(resolution.layout.lines.none { it.unbreakableOverflow })
        assertSharedPaintedOperatorAnchor(resolution)
    }

    @Test
    fun inherentlyOverflowingContinuationDropsTheWholeBlockToTheDefaultIndent() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 300f),
            lineMetrics = lineMetrics,
            maxWidthPx = 150f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        // The continuation cannot fit at any indent, so a 100px anchor would only waste viewport
        // on a clipped line; the whole block falls back to the 40px default tier.
        assertEquals(ResponsiveContinuationIndentTier.DefaultIndent, resolution.indentTier)
        assertNear(100f, resolution.requestedSemanticIndentPx)
        assertNear(40f, resolution.resolvedIndentPx)
        val lines = resolution.layout.lines
        assertEquals(2, lines.size)
        assertNear(40f, lines[1].horizontalOffsetPx)
        assertTrue(lines[1].unbreakableOverflow)
        assertTrue(!lines[0].unbreakableOverflow)
    }

    @Test
    fun siblingContinuationsStayAlignedWhenOneOfThemCannotFitAtAnyIndent() {
        // LHS, "=" with an overflowing RHS segment, then a fitting "-" continuation: both
        // operator lines must share one painted edge at the default indent, not split between
        // the default and the stale semantic anchor.
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f, 10f, 300f, 10f, 60f),
            lineMetrics = lineMetrics,
            maxWidthPx = 150f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(ResponsiveContinuationIndentTier.DefaultIndent, resolution.indentTier)
        val lines = resolution.layout.lines
        assertEquals(3, lines.size)
        assertNear(lines[1].horizontalOffsetPx, lines[2].horizontalOffsetPx)
        assertNear(40f, lines[1].horizontalOffsetPx)
    }

    @Test
    fun continuationsAlignToTheAnchorOperatorAsPaintedAfterFirstLineGlueShrinks() {
        val glue = MathGlueAdjustment(
            kind = MathGlueKind.Thick,
            naturalPx = 30f,
            minimumPx = 0f,
            maximumPx = 30f,
            shrinkPx = 30f,
            stretchPx = 0f,
            priority = MathAdjustmentPriority.Relation,
        )
        val fragments = fragments(50f, 10f, 100f, 10f, 60f).mapIndexed { index, fragment ->
            if (index <= 1) fragment.copy(trailingGlue = glue) else fragment
        }

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 210f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        // The first line shrinks 220 -> 210, moving the relation from its natural x=80 to a
        // painted x=75; the continuation must sit under the operator as painted, and the
        // reported anchor must be the painted one.
        assertEquals(ResponsiveContinuationIndentTier.SemanticOperatorAnchor, resolution.indentTier)
        assertNear(80f, resolution.requestedSemanticIndentPx)
        val lines = resolution.layout.lines
        assertEquals(2, lines.size)
        val paintedRelation = lines[0].fragments.first { it.fragmentIndex == 1 }
        assertNear(75f, lines[0].horizontalOffsetPx - lines[0].visualLeft + paintedRelation.x)
        assertNear(75f, lines[1].horizontalOffsetPx)
        assertNear(75f, resolution.layout.continuationAnchorPx)
    }

    private fun assertSharedPaintedOperatorAnchor(resolution: ResponsiveDisplayBreakResolution) {
        val layout = resolution.layout
        assertTrue(layout.lines.size >= 2)
        layout.lines.drop(1).forEach { line ->
            val first = line.fragments.first()
            val fragment = when (first.fragmentIndex) {
                1 -> fragmentsForAssertion[1]
                3 -> fragmentsForAssertion[3]
                else -> error("continuation did not begin at an operator: ${first.fragmentIndex}")
            }
            val paintedLeft = line.horizontalOffsetPx - line.visualLeft + first.x + fragment.box.inkBounds.left
            assertNear(layout.continuationAnchorPx, paintedLeft)
        }
        assertNear(
            resolution.resolvedIndentPx,
            layout.continuationAnchorPx - layout.lines.first().horizontalOffsetPx,
        )
    }

    private fun fragments(vararg widths: Float): List<MathInlineFragment> = widths.mapIndexed { index, width ->
        MathInlineFragment(
            index = index,
            sourceRange = SourceRange(index, index + 1),
            atomClass = when (index) {
                1 -> MathAtomClass.Relation
                3 -> MathAtomClass.Binary
                else -> MathAtomClass.Ordinary
            },
            box = box(width, index),
            leadingKernPx = 0f,
            trailingItalicCorrectionPx = 0f,
            trailingGlue = MathGlueAdjustment.Zero,
            breakAfter = null,
        )
    }.also { fragmentsForAssertion = it }

    private fun box(width: Float, index: Int) = MathBox(
        width = width,
        ascent = 8f,
        descent = 2f,
        inkBounds = MathRect(0f, -8f, width, 2f),
        glyphs = emptyList(),
        rules = emptyList(),
        range = SourceRange(index, index + 1),
    )

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 0.02f, "expected=$expected actual=$actual")
    }

    private var fragmentsForAssertion: List<MathInlineFragment> = emptyList()

    private val lineMetrics = MathFormulaLineMetrics(
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
