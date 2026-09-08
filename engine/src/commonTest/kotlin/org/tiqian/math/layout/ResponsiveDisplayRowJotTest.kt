package org.tiqian.math.layout

import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DisplayRowJot: broken display rows carry \jot of extra advance in every non-final row's
 * descent, so ink-heavy rows (a fraction denominator over the next row's bracket) never sit on
 * each other, and both the engine replay and an ascent/descent-restacking frontend agree.
 */
class ResponsiveDisplayRowJotTest {
    @Test
    fun everyRowButTheLastCarriesTheJotInItsDescent() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(90f, 10f, 90f, 10f, 90f),
            lineMetrics = lineMetrics,
            maxWidthPx = 120f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
            displayRowJotPx = 7f,
        )

        val lines = resolution.layout.lines
        assertEquals(3, lines.size)
        lines.dropLast(1).forEach { line ->
            assertNear(lineMetrics.logicalDescentPx + 7f, line.descent)
        }
        assertNear(lineMetrics.logicalDescentPx, lines.last().descent)
        assertNear(
            lineMetrics.logicalAscentPx + lineMetrics.logicalDescentPx + 7f,
            lines[1].baselineFromTop - lines[0].baselineFromTop,
        )
        assertNear(
            lines.last().baselineFromTop + lines.last().descent,
            resolution.layout.height,
        )
    }

    @Test
    fun zeroJotKeepsTheInkDrivenStacking() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(90f, 10f, 90f),
            lineMetrics = lineMetrics,
            maxWidthPx = 120f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(2, lines.size)
        assertNear(
            lineMetrics.logicalAscentPx + lineMetrics.logicalDescentPx,
            lines[1].baselineFromTop - lines[0].baselineFromTop,
        )
    }

    private fun fragments(vararg widths: Float): List<MathInlineFragment> =
        widths.mapIndexed { index, width ->
            MathInlineFragment(
                index = index,
                sourceRange = SourceRange(index, index + 1),
                atomClass = if (index % 2 == 1) MathAtomClass.Binary else MathAtomClass.Ordinary,
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

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 0.02f, "expected=$expected actual=$actual")
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
