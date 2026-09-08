package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FenceDepthScaledBreakPenalty: plain parentheses do not group in TeX, so the binary operator
 * inside "x(a - b) + y" is a legal top-level boundary. Breaking there leaves the fence dangling
 * open at the line end, so it must lose to the shallower "+" even when the fenced break packs
 * the lines measurably better — this fixture gives the depth-1 break a 360-point raggedness
 * advantage, far above the kind bases yet nowhere near the per-level fence penalty, which is
 * sized so that no realistic line-balance saving can ever buy a fenced break.
 */
class ResponsiveDisplayFenceDepthTest {
    @Test
    fun breakInsideAFenceLosesToAShallowerBoundaryDespiteBetterRaggedness() {
        val fragments = fragments(
            60f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Opening,
            40f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            20f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Closing,
            10f to MathAtomClass.Binary,
            120f to MathAtomClass.Ordinary,
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(
            listOf(0..5, 6..7),
            lines.map { line ->
                line.fragments.first().fragmentIndex..line.fragments.last().fragmentIndex
            },
            "expected the depth-0 break before the trailing operator, not the one inside the fence",
        )
        assertEquals(MathBreakKind.BinaryOperatorLeading, lines[1].breakKind)
        assertTrue(lines.none { it.unbreakableOverflow })
    }

    @Test
    fun fencedBoundaryRemainsTheLastLegalResortWhenNothingShallowerFits() {
        val fragments = fragments(
            60f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Opening,
            80f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            80f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Closing,
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 160f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(
            listOf(0..2, 3..5),
            lines.map { line ->
                line.fragments.first().fragmentIndex..line.fragments.last().fragmentIndex
            },
            "with no depth-0 boundary the fenced break must still beat unbreakable overflow",
        )
        assertTrue(lines.none { it.unbreakableOverflow })
    }

    @Test
    fun unavoidableOverflowLineAbsorbsLeadingDebrisAndStartsAtTheShallowBoundary() {
        // "E = M + e(2 - k)[HUGE]": the bracket atom cannot fit at any width, so an overflow
        // line is unavoidable. It must start at the depth-0 "+" and absorb "e(2", instead of
        // stranding "e(2" on the previous line and starting at the fenced "-".
        val fragments = fragments(
            50f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Relation,
            40f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            30f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Opening,
            20f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            20f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Closing,
            500f to MathAtomClass.Ordinary,
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(
            listOf(0..2, 3..10),
            lines.map { line ->
                line.fragments.first().fragmentIndex..line.fragments.last().fragmentIndex
            },
            "the overflow line must absorb the pre-bracket debris and start at the depth-0 binary",
        )
        assertEquals(MathBreakKind.BinaryOperatorLeading, lines[1].breakKind)
        assertTrue(lines[1].unbreakableOverflow)
        assertTrue(!lines[0].unbreakableOverflow)
    }

    @Test
    fun oneOverflowLineAbsorbsSegmentsAcrossAnInterveningFittingSegment() {
        // [huge A][+ small B][+ huge C]: one overflow line absorbing everything beats paying the
        // flat overflow penalty twice; the fitting middle segment must not stop the DP from
        // evaluating the longer absorption candidate.
        val fragments = fragments(
            500f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            20f to MathAtomClass.Ordinary,
            10f to MathAtomClass.Binary,
            500f to MathAtomClass.Ordinary,
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(1, resolution.layout.lines.size, "a single absorbing overflow line expected")
        assertTrue(resolution.layout.lines.single().unbreakableOverflow)
    }

    private fun fragments(vararg widthToClass: Pair<Float, MathAtomClass>): List<MathInlineFragment> =
        widthToClass.mapIndexed { index, (width, atomClass) ->
            MathInlineFragment(
                index = index,
                sourceRange = SourceRange(index, index + 1),
                atomClass = atomClass,
                box = MathBox(
                    width = width,
                    ascent = 8f,
                    descent = 2f,
                    inkBounds = MathRect(0f, -8f, width, 2f),
                    glyphs = emptyList(),
                    rules = emptyList(),
                    range = SourceRange(index, index + 1),
                ),
                leadingKernPx = 0f,
                trailingItalicCorrectionPx = 0f,
                trailingGlue = MathGlueAdjustment.Zero,
                breakAfter = null,
            )
        }

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
