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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ClauseContinuationRightAligned: a punctuation-trailing break starts a new clause (an equation's
 * domain condition, the next formula in a list), so its line right-aligns to the block right edge
 * like a multline final line, instead of borrowing the operator anchor that only means something
 * for continuation lines led by a relation or binary operator.
 */
class ResponsiveDisplayClausePlacementTest {
    @Test
    fun clauseContinuationRightAlignsToTheBlockRightEdge() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(100f to false, 10f to true, 40f to false),
            lineMetrics = lineMetrics,
            maxWidthPx = 120f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(2, lines.size)
        assertEquals(MathBreakKind.PunctuationTrailing, lines[1].breakKind)
        assertNear(
            lines[0].horizontalOffsetPx + lines[0].width,
            lines[1].horizontalOffsetPx + lines[1].width,
        )
        assertTrue(
            lines[1].horizontalOffsetPx > lines[0].horizontalOffsetPx,
            "a narrower clause line must sit right of the block start, not on the operator anchor",
        )
        assertTrue(lines.none { it.unbreakableOverflow })
    }

    @Test
    fun clauseWiderThanEveryOtherLineStaysFlushLeftAndDefinesTheBlockWidth() {
        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments(40f to false, 10f to true, 100f to false),
            lineMetrics = lineMetrics,
            maxWidthPx = 120f,
            defaultContinuationIndentPx = 0f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        assertEquals(2, lines.size)
        assertEquals(MathBreakKind.PunctuationTrailing, lines[1].breakKind)
        assertNear(lines[0].horizontalOffsetPx, lines[1].horizontalOffsetPx)
        assertTrue(lines.none { it.unbreakableOverflow })
    }

    @Test
    fun clauseNextToAnOverflowingSiblingIsAViewportPinnedClauseLine() {
        // PinnedClauseLikeTag: with an unavoidable overflow sibling, the fitting clause line
        // right-aligns within the viewport and is marked as a clause so the replay can anchor it
        // like the equation tag while the rest of the block scrolls.
        val fragments = listOf(
            plain(0, 40f, MathAtomClass.Ordinary),
            plain(1, 10f, MathAtomClass.Relation),
            plain(2, 400f, MathAtomClass.Ordinary),
            punct(3, 10f),
            plain(4, 60f, MathAtomClass.Ordinary),
        )

        val resolution = resolveResponsiveDisplayBreak(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = 200f,
            defaultContinuationIndentPx = 40f,
            resourceLimits = MathResourceLimits.Default,
        )

        val lines = resolution.layout.lines
        val clause = lines.last()
        assertEquals(MathBreakKind.PunctuationTrailing, clause.breakKind)
        assertTrue(clause.isClause)
        assertTrue(!clause.unbreakableOverflow)
        assertNear(200f, clause.horizontalOffsetPx + clause.width)
    }

    private fun plain(index: Int, width: Float, atomClass: MathAtomClass) = MathInlineFragment(
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

    private fun punct(index: Int, width: Float) = plain(index, width, MathAtomClass.Punctuation).copy(
        breakAfter = MathBreakOpportunity(
            afterFragmentIndex = index,
            sourceOffset = index + 1,
            kind = MathBreakKind.PunctuationTrailing,
            discardedTrailingGlue = MathGlueAdjustment.Zero,
            priority = MathAdjustmentPriority.Punctuation,
        ),
    )

    private fun fragments(vararg widthToPunctuation: Pair<Float, Boolean>): List<MathInlineFragment> =
        widthToPunctuation.mapIndexed { index, (width, isPunctuation) ->
            MathInlineFragment(
                index = index,
                sourceRange = SourceRange(index, index + 1),
                atomClass = if (isPunctuation) MathAtomClass.Punctuation else MathAtomClass.Ordinary,
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
                breakAfter = if (isPunctuation) {
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
