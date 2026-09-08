package org.tiqian.math.layout

import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathBreakOpportunity
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponsiveDisplayBreakPenaltyTest {
    /**
     * "A , B + C" where "A ,|B + C" and "A , B|+ C" are a raggedness near-tie (7.3 cost apart),
     * so the boundary penalties decide. A top-level punctuation break separates complete
     * sub-formulas and must beat tearing the second term apart at its binary operator; a
     * punctuation penalty above the binary penalty flips this fixture to the binary break.
     */
    @Test
    fun prefersTopLevelPunctuationBreakOverTearingATermAtABinaryOperator() {
        val fragments = listOf(
            fragment(index = 0, width = 58f, atomClass = MathAtomClass.Ordinary),
            fragment(index = 1, width = 10f, atomClass = MathAtomClass.Punctuation, punctuationBreakAfter = true),
            fragment(index = 2, width = 29f, atomClass = MathAtomClass.Ordinary),
            fragment(index = 3, width = 10f, atomClass = MathAtomClass.Binary),
            fragment(index = 4, width = 153f, atomClass = MathAtomClass.Ordinary),
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val layout = resolution.layout
        assertEquals(
            listOf(0..1, 2..4),
            layout.lines.map { line ->
                line.fragments.first().fragmentIndex..line.fragments.last().fragmentIndex
            },
            "expected the break after the top-level comma, not before the binary operator",
        )
        assertEquals(
            MathBreakKind.PunctuationTrailing,
            layout.lines[1].breakKind,
        )
        assertTrue(layout.lines.none { it.unbreakableOverflow })
    }

    private fun fragment(
        index: Int,
        width: Float,
        atomClass: MathAtomClass,
        punctuationBreakAfter: Boolean = false,
    ): MathInlineFragment = MathInlineFragment(
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
        breakAfter = if (punctuationBreakAfter) {
            MathBreakOpportunity(
                afterFragmentIndex = index,
                sourceOffset = index + 1,
                kind = MathBreakKind.PunctuationTrailing,
                discardedTrailingGlue = MathGlueAdjustment.Zero,
                priority = MathAdjustmentPriority.Punctuation,
            )
        } else {
            null
        },
    )

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
