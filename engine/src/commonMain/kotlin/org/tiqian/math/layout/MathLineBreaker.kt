package org.tiqian.math.layout

import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathBreakOpportunity
import org.tiqian.math.core.MathBrokenLayout
import org.tiqian.math.core.MathBrokenLine
import org.tiqian.math.core.MathContinuationAlignment
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathGroup
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathList
import org.tiqian.math.core.MathLineAdjustmentMode
import org.tiqian.math.core.MathLineBreakPolicy
import org.tiqian.math.core.MathLineFragmentPlacement
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.invalidResolvedDimensionDiagnostic
import org.tiqian.math.core.mathResourceLimitDiagnostic
import kotlin.math.abs

/**
 * WholeFormulaGroupTransparentForBreaking: an author (or exporter) wrapping the entire formula in
 * braces creates one Ord atom, which would leave the responsive breaker with no legal boundary at
 * all. A group whose scope already spans the whole list changes nothing but style scoping, so it
 * is unwrapped before layout and the top-level boundaries are restored. Delimited fields
 * (\left...\right) are real atoms and are never unwrapped.
 */
internal fun unwrapWholeFormulaGroups(root: MathList): MathList {
    var current = root
    while (true) {
        val single = current.children.singleOrNull() as? MathGroup ?: return current
        current = single.body
    }
}

internal fun MathLayoutPass.inlineFragments(
    horizontal: MathLayoutPass.HorizontalLayout,
): List<MathInlineFragment> = horizontal.items.mapIndexed { itemIndex, item ->
    val trailingGlue = horizontal.items.getOrNull(itemIndex + 1)?.glueBefore ?: MathGlueAdjustment.Zero
    val breakKind = when (item.atomClass) {
        MathAtomClass.Punctuation -> MathBreakKind.PunctuationTrailing
        MathAtomClass.Binary -> MathBreakKind.BinaryOperatorTrailing
        MathAtomClass.Relation -> MathBreakKind.RelationTrailing
        else -> null
    }
    val opportunity = breakKind?.let {
        MathBreakOpportunity(
            afterFragmentIndex = itemIndex,
            sourceOffset = item.node.range.endExclusive,
            kind = it,
            discardedTrailingGlue = trailingGlue,
            priority = adjustmentPriority(item.atomClass, null),
        )
    }
    MathInlineFragment(
        index = itemIndex,
        sourceRange = item.node.range,
        atomClass = item.atomClass,
        box = item.laid.box,
        leadingKernPx = item.leadingKernPx,
        trailingItalicCorrectionPx = item.trailingItalicCorrectionPx,
        trailingGlue = trailingGlue,
        breakAfter = opportunity,
    )
}

/**
 * Greedy reference breaker over the public fragment contract.
 *
 * Break selection, adjustment, reported geometry, and rendering placements all use the same
 * resolved glue values. Glue after the last visible fragment is never placed on a line. The
 * formula's captured resource policy is reused, with any rejection reported on the returned
 * [MathBrokenLayout].
 */
fun MathLayoutResult.breakIntoLines(
    maxWidthPx: Float,
    adjustmentMode: MathLineAdjustmentMode = MathLineAdjustmentMode.Fit,
): MathBrokenLayout {
    val breakpointCount = mathBreakpointCount(fragments, MathLineBreakPolicy.InlineTrailingOperators)
    if (breakpointCount <= resourceLimits.maximumBreakpointCount) {
        return breakMathFragments(fragments, lineMetrics, maxWidthPx, adjustmentMode)
    }
    val diagnostic = mathResourceLimitDiagnostic(
        code = DiagnosticCode.BreakpointCountLimitExceeded,
        resource = "breakpointCount",
        actual = breakpointCount,
        limit = resourceLimits.maximumBreakpointCount,
        range = fragments.first().sourceRange.cover(fragments.last().sourceRange),
    )
    return resourceRejectedUnbrokenLayout(
        fragments = fragments,
        lineMetrics = lineMetrics,
        maxWidthPx = maxWidthPx,
        policy = MathLineBreakPolicy.InlineTrailingOperators,
        diagnostic = diagnostic,
    )
}

/**
 * Responsive display policy for variable-width electronic reading.
 *
 * Unlike the inline contract, binary and relation operators begin continuation lines. Breaks are
 * selected by minimum cost over the legal top-level boundaries at the resolved shared indent,
 * relation boundaries are preferred, and continuation operators align to the painted edge of the
 * first usable relation (or binary operator) on the first line. A punctuation-trailing break
 * starts a new clause rather than an operator continuation, so its line right-aligns to the block
 * right edge (multline final-line convention) instead of taking the operator anchor. The
 * resulting multiline block is centered as a group instead of centering every line independently.
 * Atomic sub-formulas remain indivisible and are reported as overflow rather than split at an
 * invented boundary. The formula's captured resource policy is reused, with any rejection
 * reported on the returned [MathBrokenLayout].
 */
fun MathLayoutResult.breakResponsiveDisplayLines(
    maxWidthPx: Float,
    adjustmentMode: MathLineAdjustmentMode = MathLineAdjustmentMode.Fit,
): MathBrokenLayout = resolveResponsiveDisplayBreak(
    fragments = fragments,
    lineMetrics = lineMetrics,
    maxWidthPx = maxWidthPx,
    adjustmentMode = adjustmentMode,
    defaultContinuationIndentPx = DISPLAY_CONTINUATION_INDENT_EM * fontSizePx,
    displayRowJotPx = DISPLAY_ROW_JOT_EM * fontSizePx,
    resourceLimits = resourceLimits,
).layout

/** Uses the same legal boundaries as the requested breaker, including boundary coalescing. */
internal fun mathBreakpointCount(
    fragments: List<MathInlineFragment>,
    policy: MathLineBreakPolicy,
): Int = when (policy) {
    MathLineBreakPolicy.InlineTrailingOperators -> fragments.internalBreakpointCount { it.breakAfter != null }
    MathLineBreakPolicy.ResponsiveDisplayLeadingOperators -> responsiveBoundaries(fragments).responsiveBreakpointCount()
}

private fun List<ResponsiveBoundary>.responsiveBreakpointCount(): Int = internalBreakpointCount { it.kind != null }

/** Counts legal internal boundaries while excluding a terminal marker in the last slot. */
internal inline fun <T> List<T>.internalBreakpointCount(isBreakpoint: (T) -> Boolean): Int {
    var count = 0
    for (index in 0 until lastIndex) {
        if (isBreakpoint(this[index])) count += 1
    }
    return count
}

private fun breakMathFragments(
    fragments: List<MathInlineFragment>,
    lineMetrics: MathFormulaLineMetrics,
    maxWidthPx: Float,
    adjustmentMode: MathLineAdjustmentMode = MathLineAdjustmentMode.Fit,
): MathBrokenLayout {
    require(maxWidthPx.isFinite() && maxWidthPx > 0f) { "line width must be finite and positive" }
    if (fragments.isEmpty()) return MathBrokenLayout(emptyList(), 0f, 0f)

    // Legal breaks partition the formula into indivisible segments. A segment that cannot fit
    // is reported as an overflowing line; no synthetic atom-boundary break is invented.
    val segmentEnds = buildList {
        fragments.indices.forEach { index -> if (fragments[index].breakAfter != null) add(index) }
        if (lastOrNull() != fragments.lastIndex) add(fragments.lastIndex)
    }
    val segments = buildList {
        var start = 0
        segmentEnds.forEach { end ->
            add(start..end)
            start = end + 1
        }
    }
    val ranges = mutableListOf<IntRange>()
    var currentStart = segments.first().first
    var currentEnd = segments.first().last
    segments.drop(1).forEach { segment ->
        val candidate = currentStart..segment.last
        val minimumGeometry = geometry(fragments, candidate, internalGlue(fragments, candidate) { it.minimumPx })
        if (minimumGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX) {
            ranges += currentStart..currentEnd
            currentStart = segment.first
            currentEnd = segment.last
        } else {
            currentEnd = segment.last
        }
    }
    ranges += currentStart..currentEnd

    var top = 0f
    val lines = ranges.mapIndexed { lineIndex, range ->
        val natural = internalGlue(fragments, range) { it.naturalPx }
        val naturalGeometry = geometry(fragments, range, natural)
        val visualOverhang = naturalGeometry.visualWidth - naturalGeometry.logicalWidth
        val desiredLogicalWidth = (maxWidthPx - visualOverhang).coerceAtLeast(0f)
        val shouldStretch = adjustmentMode == MathLineAdjustmentMode.Justify && lineIndex != ranges.lastIndex
        val targetLogicalWidth = when {
            naturalGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX -> desiredLogicalWidth
            shouldStretch -> desiredLogicalWidth
            else -> naturalGeometry.logicalWidth
        }
        val resolved = resolveGlue(fragments, range, natural, naturalGeometry.logicalWidth, targetLogicalWidth)
        val lineGeometry = geometry(fragments, range, resolved)
        val safeMetrics = lineMetrics.forInk(lineGeometry.inkAscent, lineGeometry.inkDescent)
        val baseline = top + safeMetrics.logicalAscentPx
        top = baseline + safeMetrics.logicalDescentPx
        MathBrokenLine(
            fragments = lineGeometry.placements,
            logicalWidth = lineGeometry.logicalWidth,
            inkBounds = lineGeometry.inkBounds,
            visualLeft = lineGeometry.visualLeft,
            visualRight = lineGeometry.visualRight,
            width = lineGeometry.visualWidth,
            inkAscent = lineGeometry.inkAscent,
            inkDescent = lineGeometry.inkDescent,
            ascent = safeMetrics.logicalAscentPx,
            descent = safeMetrics.logicalDescentPx,
            baselineFromTop = baseline,
            unbreakableOverflow = lineGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX,
        )
    }
    val height = lines.lastOrNull()?.let { it.baselineFromTop + it.descent } ?: 0f
    return MathBrokenLayout(
        lines = lines,
        width = lines.maxOfOrNull { it.width } ?: 0f,
        height = height,
        policy = MathLineBreakPolicy.InlineTrailingOperators,
        targetWidthPx = maxWidthPx,
    )
}

private fun resourceRejectedUnbrokenLayout(
    fragments: List<MathInlineFragment>,
    lineMetrics: MathFormulaLineMetrics,
    maxWidthPx: Float,
    policy: MathLineBreakPolicy,
    diagnostic: MathDiagnostic,
): MathBrokenLayout {
    require(maxWidthPx.isFinite() && maxWidthPx > 0f) { "line width must be finite and positive" }
    if (fragments.isEmpty()) {
        return MathBrokenLayout(
            lines = emptyList(),
            width = 0f,
            height = 0f,
            policy = policy,
            targetWidthPx = maxWidthPx,
            diagnostics = listOf(diagnostic),
        )
    }

    // Resource rejection must not enter the ordinary breaker: even an effectively infinite
    // width still makes it probe every growing segment. Build the one retained line directly in
    // one geometry pass instead.
    val range = fragments.indices
    val lineGeometry = geometry(
        fragments = fragments,
        range = range,
        resolvedGlue = internalGlue(fragments, range) { it.naturalPx },
    )
    val safeMetrics = lineMetrics.forInk(lineGeometry.inkAscent, lineGeometry.inkDescent)
    val line = MathBrokenLine(
        fragments = lineGeometry.placements,
        logicalWidth = lineGeometry.logicalWidth,
        inkBounds = lineGeometry.inkBounds,
        visualLeft = lineGeometry.visualLeft,
        visualRight = lineGeometry.visualRight,
        width = lineGeometry.visualWidth,
        inkAscent = lineGeometry.inkAscent,
        inkDescent = lineGeometry.inkDescent,
        ascent = safeMetrics.logicalAscentPx,
        descent = safeMetrics.logicalDescentPx,
        baselineFromTop = safeMetrics.logicalAscentPx,
        unbreakableOverflow = lineGeometry.visualWidth > maxWidthPx + GEOMETRY_EPSILON_PX,
    )
    return MathBrokenLayout(
        lines = listOf(line),
        width = lineGeometry.visualWidth,
        height = line.baselineFromTop + line.descent,
        policy = policy,
        targetWidthPx = maxWidthPx,
        diagnostics = listOf(diagnostic),
    )
}

internal enum class ResponsiveContinuationIndentTier {
    None,
    SemanticOperatorAnchor,
    DefaultIndent,
    MaximumCommonFeasibleIndent,
}

internal data class ResponsiveDisplayBreakResolution(
    val layout: MathBrokenLayout,
    /** Fence depth of each continuation line's starting boundary, aligned with lines[1..]. */
    val continuationFenceDepths: List<Int>,
    val indentTier: ResponsiveContinuationIndentTier,
    /** All indent values are relative to the centered multiline block's left edge. */
    val requestedSemanticIndentPx: Float,
    val defaultIndentPx: Float,
    val maximumCommonFeasibleIndentPx: Float,
    val resolvedIndentPx: Float,
)

/**
 * Indent-independent visual widths for every boundary-pair range, measured once and shared by all
 * indent tiers: the DP's fit and raggedness math needs only these scalars, so no per-candidate
 * placement lists are allocated and no tier re-measures what another tier already measured.
 */
internal class ResponsiveRangeWidths(
    fragments: List<MathInlineFragment>,
    boundaries: List<ResponsiveBoundary>,
) {
    private val minimumWidths = Array(boundaries.size) { FloatArray(boundaries.size) }
    private val naturalWidths = Array(boundaries.size) { FloatArray(boundaries.size) }

    init {
        for (startBoundary in 0 until boundaries.lastIndex) {
            var minX = 0f
            var naturalX = 0f
            var minInkLeft = 0f
            var minInkRight = 0f
            var minHasInk = false
            var naturalInkLeft = 0f
            var naturalInkRight = 0f
            var naturalHasInk = false
            var nextBoundary = startBoundary + 1
            for (fragmentIndex in boundaries[startBoundary].position until boundaries.last().position) {
                val fragment = fragments[fragmentIndex]
                minX += fragment.leadingKernPx
                naturalX += fragment.leadingKernPx
                val ink = fragment.box.inkBounds
                val hasOwnInk = ink.width != 0f || ink.height != 0f
                if (!minHasInk) {
                    minInkLeft = ink.left + minX
                    minInkRight = ink.right + minX
                    minHasInk = hasOwnInk
                } else {
                    minInkLeft = minOf(minInkLeft, ink.left + minX)
                    minInkRight = maxOf(minInkRight, ink.right + minX)
                }
                if (!naturalHasInk) {
                    naturalInkLeft = ink.left + naturalX
                    naturalInkRight = ink.right + naturalX
                    naturalHasInk = hasOwnInk
                } else {
                    naturalInkLeft = minOf(naturalInkLeft, ink.left + naturalX)
                    naturalInkRight = maxOf(naturalInkRight, ink.right + naturalX)
                }
                minX += fragment.box.width + fragment.trailingItalicCorrectionPx
                naturalX += fragment.box.width + fragment.trailingItalicCorrectionPx
                if (fragmentIndex + 1 == boundaries[nextBoundary].position) {
                    // The range ending here zeroes this fragment's trailing glue, so record before
                    // adding it; the walk then continues for the longer ranges.
                    minimumWidths[startBoundary][nextBoundary] =
                        visualWidth(minX, minInkLeft, minInkRight, minHasInk)
                    naturalWidths[startBoundary][nextBoundary] =
                        visualWidth(naturalX, naturalInkLeft, naturalInkRight, naturalHasInk)
                    if (nextBoundary == boundaries.lastIndex) break
                    nextBoundary += 1
                }
                minX += fragment.trailingGlue.minimumPx
                naturalX += fragment.trailingGlue.naturalPx
            }
        }
    }

    private fun visualWidth(x: Float, inkLeft: Float, inkRight: Float, hasInk: Boolean): Float {
        val left = if (hasInk) inkLeft else 0f
        val right = if (hasInk) inkRight else 0f
        return maxOf(x, right) - minOf(0f, left)
    }

    fun minimum(startBoundary: Int, endBoundary: Int): Float = minimumWidths[startBoundary][endBoundary]

    fun natural(startBoundary: Int, endBoundary: Int): Float = naturalWidths[startBoundary][endBoundary]
}

private data class ResponsiveSelectedLine(
    val range: IntRange,
    val startingBoundary: ResponsiveBoundary?,
    val startBoundaryIndex: Int,
    val endBoundaryIndex: Int,
)

private data class ResponsiveFixedIndentSelection(
    val lines: List<ResponsiveSelectedLine>,
    val hasIndentInducedOverflow: Boolean,
    /** An operator continuation whose indivisible segment cannot fit at any indent. */
    val hasInherentOperatorOverflow: Boolean,
)

internal fun resolveResponsiveDisplayBreak(
    fragments: List<MathInlineFragment>,
    lineMetrics: MathFormulaLineMetrics,
    maxWidthPx: Float,
    adjustmentMode: MathLineAdjustmentMode = MathLineAdjustmentMode.Fit,
    defaultContinuationIndentPx: Float,
    resourceLimits: MathResourceLimits,
    displayRowJotPx: Float = 0f,
): ResponsiveDisplayBreakResolution {
    require(maxWidthPx.isFinite() && maxWidthPx > 0f) { "line width must be finite and positive" }
    require(defaultContinuationIndentPx.isFinite() && defaultContinuationIndentPx >= 0f) {
        "default continuation indent must be finite and non-negative"
    }
    require(displayRowJotPx.isFinite() && displayRowJotPx >= 0f) {
        "display row jot must be finite and non-negative"
    }
    if (defaultContinuationIndentPx > resourceLimits.maximumResolvedDimensionPx) {
        val range = fragments.firstOrNull()?.sourceRange
            ?.cover(fragments.last().sourceRange)
            ?: SourceRange.Empty
        return rejectedResponsiveResolution(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = maxWidthPx,
            defaultIndentPx = 0f,
            diagnostic = invalidResolvedDimensionDiagnostic(
                sourceText = "responsive continuation indent",
                resolvedPx = defaultContinuationIndentPx,
                maximumAbsolutePx = resourceLimits.maximumResolvedDimensionPx,
                range = range,
            ),
        )
    }
    if (fragments.isEmpty()) {
        return ResponsiveDisplayBreakResolution(
            continuationFenceDepths = emptyList(),
            layout = MathBrokenLayout(
                lines = emptyList(),
                width = 0f,
                height = 0f,
                policy = MathLineBreakPolicy.ResponsiveDisplayLeadingOperators,
                targetWidthPx = maxWidthPx,
            ),
            indentTier = ResponsiveContinuationIndentTier.None,
            requestedSemanticIndentPx = 0f,
            defaultIndentPx = defaultContinuationIndentPx,
            maximumCommonFeasibleIndentPx = 0f,
            resolvedIndentPx = 0f,
        )
    }

    val boundaries = responsiveBoundaries(fragments)
    val breakpointCount = boundaries.responsiveBreakpointCount()
    if (breakpointCount > resourceLimits.maximumBreakpointCount) {
        val diagnostic = mathResourceLimitDiagnostic(
            code = DiagnosticCode.BreakpointCountLimitExceeded,
            resource = "breakpointCount",
            actual = breakpointCount,
            limit = resourceLimits.maximumBreakpointCount,
            range = fragments.first().sourceRange.cover(fragments.last().sourceRange),
        )
        return rejectedResponsiveResolution(
            fragments = fragments,
            lineMetrics = lineMetrics,
            maxWidthPx = maxWidthPx,
            defaultIndentPx = defaultContinuationIndentPx,
            diagnostic = diagnostic,
        )
    }
    val rangeWidths = ResponsiveRangeWidths(fragments, boundaries)
    val anchorRequest = responsiveContinuationAnchor(fragments)
    val alignment = anchorRequest.alignment
    val requestedAnchorWithinBlock = anchorRequest.anchorPx.coerceAtLeast(0f)
    val maximumCommonFeasibleIndentPx = if (alignment == MathContinuationAlignment.None) {
        0f
    } else {
        maximumFeasibleResponsiveIndent(fragments, boundaries, rangeWidths, maxWidthPx)
    }
    fun selectAt(anchorPx: Float): ResponsiveFixedIndentSelection = selectResponsiveLinesAtIndent(
        fragments = fragments,
        boundaries = boundaries,
        rangeWidths = rangeWidths,
        maxWidthPx = maxWidthPx,
        continuationAnchorWithinBlock = anchorPx,
    )
    // Tiers are tried lazily: each quadratic DP pass runs only after the previous tier is
    // actually rejected, and the shared range widths keep every pass allocation-free.
    val (indentTier, continuationAnchorWithinBlock, fixedIndentSelection) = run {
        if (alignment == MathContinuationAlignment.None) {
            return@run Triple(ResponsiveContinuationIndentTier.None, 0f, selectAt(0f))
        }
        val semanticSelection = selectAt(requestedAnchorWithinBlock)
        // OverflowingContinuationFallsBackToDefaultIndent: when a selected operator continuation
        // cannot fit at any indent, a deep semantic anchor only wastes viewport on a line that is
        // clipped and scrolled anyway — and per-line yielding would break the shared painted-edge
        // alignment of its sibling continuations. The whole block falls back to the 2em default
        // (and reoptimizes there) unless the anchor is already within it.
        val semanticAnchorUsable = !semanticSelection.hasIndentInducedOverflow &&
            !(semanticSelection.hasInherentOperatorOverflow &&
                requestedAnchorWithinBlock > defaultContinuationIndentPx + GEOMETRY_EPSILON_PX)
        if (semanticAnchorUsable) {
            return@run Triple(
                ResponsiveContinuationIndentTier.SemanticOperatorAnchor,
                requestedAnchorWithinBlock,
                semanticSelection,
            )
        }
        val defaultSelection = selectAt(defaultContinuationIndentPx)
        if (!defaultSelection.hasIndentInducedOverflow) {
            return@run Triple(
                ResponsiveContinuationIndentTier.DefaultIndent,
                defaultContinuationIndentPx,
                defaultSelection,
            )
        }
        Triple(
            ResponsiveContinuationIndentTier.MaximumCommonFeasibleIndent,
            maximumCommonFeasibleIndentPx,
            selectAt(maximumCommonFeasibleIndentPx),
        )
    }
    // Line ranges must be optimized for the indent that is actually replayed. Previously the
    // ranges were selected at the semantic anchor and then replayed after the indent fell back,
    // leaving avoidable overflow and stale, visibly poor breakpoints.
    val selected = fixedIndentSelection.lines

    var top = 0f
    // PaintedAnchorFollowsResolvedFirstLine: Fit may shrink the first line's glue, moving the
    // anchor operator left of its naturally measured position; continuations must align to the
    // operator as painted. Updated from the first line's resolved geometry below; shrink-only,
    // and clamped to the tier anchor, so every tier fit check stays valid.
    var paintedAnchorWithinBlock = continuationAnchorWithinBlock
    val operatorAnchoredLines = selected.mapIndexed { lineIndex, line ->
        val (range, startingBoundary) = line
        val natural = internalGlue(fragments, range) { it.naturalPx }
        val naturalGeometry = geometry(fragments, range, natural)
        val relativeHorizontalOffset = if (lineIndex == 0 || startingBoundary.startsClause()) {
            0f
        } else {
            responsiveContinuationLineOffset(fragments[range.first], paintedAnchorWithinBlock)
        }
        val availableWidth = (maxWidthPx - relativeHorizontalOffset).coerceAtLeast(0f)
        val visualOverhang = naturalGeometry.visualWidth - naturalGeometry.logicalWidth
        val desiredLogicalWidth = (availableWidth - visualOverhang).coerceAtLeast(0f)
        val shouldStretch = adjustmentMode == MathLineAdjustmentMode.Justify && lineIndex != selected.lastIndex
        val targetLogicalWidth = when {
            naturalGeometry.visualWidth > availableWidth + GEOMETRY_EPSILON_PX -> desiredLogicalWidth
            shouldStretch -> desiredLogicalWidth
            else -> naturalGeometry.logicalWidth
        }
        val resolved = resolveGlue(fragments, range, natural, naturalGeometry.logicalWidth, targetLogicalWidth)
        val lineGeometry = geometry(fragments, range, resolved)
        if (lineIndex == 0 &&
            indentTier == ResponsiveContinuationIndentTier.SemanticOperatorAnchor &&
            anchorRequest.anchorFragmentIndex in range
        ) {
            val anchorPlacement = lineGeometry.placements.first {
                it.fragmentIndex == anchorRequest.anchorFragmentIndex
            }
            paintedAnchorWithinBlock = (
                anchorPlacement.x +
                    fragments[anchorRequest.anchorFragmentIndex].box.inkBounds.left -
                    lineGeometry.visualLeft
                ).coerceIn(0f, continuationAnchorWithinBlock)
        }
        val safeMetrics = lineMetrics.forInk(lineGeometry.inkAscent, lineGeometry.inkDescent)
        val baseline = top + safeMetrics.logicalAscentPx
        // DisplayRowJot: broken display rows read as one display, so every row but the last
        // carries TeX's \jot of extra advance in its descent. Baking it into the row descent
        // keeps the engine replay and any frontend that restacks from ascent/descent in agreement.
        val rowDescent = safeMetrics.logicalDescentPx +
            if (lineIndex == selected.lastIndex) 0f else displayRowJotPx
        top = baseline + rowDescent
        MathBrokenLine(
            fragments = lineGeometry.placements,
            logicalWidth = lineGeometry.logicalWidth,
            inkBounds = lineGeometry.inkBounds,
            visualLeft = lineGeometry.visualLeft,
            visualRight = lineGeometry.visualRight,
            width = lineGeometry.visualWidth,
            inkAscent = lineGeometry.inkAscent,
            inkDescent = lineGeometry.inkDescent,
            ascent = safeMetrics.logicalAscentPx,
            descent = rowDescent,
            baselineFromTop = baseline,
            unbreakableOverflow = relativeHorizontalOffset + lineGeometry.visualWidth >
                maxWidthPx + GEOMETRY_EPSILON_PX,
            horizontalOffsetPx = relativeHorizontalOffset,
            breakKind = startingBoundary?.kind,
            isClause = lineIndex > 0 && startingBoundary.startsClause(),
        )
    }
    // ClauseContinuationRightAligned: a punctuation-trailing break starts a new clause, not an
    // operator continuation, so the operator anchor is meaningless for it. The clause line
    // right-aligns to the block right edge established by the first line and the operator-anchored
    // lines (multline final-line convention); a clause wider than that edge stays flush left and
    // defines the block width itself.
    // PinnedClauseLikeTag: a clause line never right-aligns past the viewport — when the block
    // scrolls, the replay anchors the clause there like the equation tag (framed fields
    // included: the frame's horizontal rules run behind the pinned clause band).
    val clauseRightEdgePx = operatorAnchoredLines
        .filterIndexed { lineIndex, _ -> lineIndex == 0 || !selected[lineIndex].startingBoundary.startsClause() }
        .maxOf { it.horizontalOffsetPx + it.width }
        .coerceAtMost(maxWidthPx)
    val relativeLines = operatorAnchoredLines.mapIndexed { lineIndex, line ->
        if (lineIndex == 0 || !selected[lineIndex].startingBoundary.startsClause()) {
            line
        } else {
            val clauseOffset = (clauseRightEdgePx - line.width).coerceAtLeast(0f)
            line.copy(
                horizontalOffsetPx = clauseOffset,
                unbreakableOverflow = clauseOffset + line.width > maxWidthPx + GEOMETRY_EPSILON_PX,
            )
        }
    }
    val relativeBlockWidth = relativeLines.maxOfOrNull { it.horizontalOffsetPx + it.width } ?: 0f
    val blockOrigin = ((maxWidthPx - relativeBlockWidth) / 2f).coerceAtLeast(0f)
    val lines = relativeLines.map { line ->
        line.copy(
            horizontalOffsetPx = line.horizontalOffsetPx + blockOrigin,
            unbreakableOverflow = line.horizontalOffsetPx + blockOrigin + line.width >
                maxWidthPx + GEOMETRY_EPSILON_PX,
        )
    }
    // PinnedClauseLikeTag is decided here, once, as layout truth: a fitting clause line pins to
    // the viewport exactly when some sibling line cannot fit at any indent.
    val bodyScrolls = lines.any { it.unbreakableOverflow }
    val pinnedLines = lines.map { line ->
        if (bodyScrolls && line.isClause && !line.unbreakableOverflow) line.copy(pinned = true) else line
    }
    val height = pinnedLines.lastOrNull()?.let { it.baselineFromTop + it.descent } ?: 0f
    return ResponsiveDisplayBreakResolution(
        continuationFenceDepths = selected.drop(1).map { it.startingBoundary?.fenceDepth ?: 0 },
        layout = MathBrokenLayout(
            lines = pinnedLines,
            // Sub-epsilon float noise from block centering must not round up into a phantom
            // scrollable pixel: a block that fits reports exactly the viewport width or less.
            width = (pinnedLines.maxOfOrNull { it.horizontalOffsetPx + it.width } ?: 0f)
                .let { if (it <= maxWidthPx + GEOMETRY_EPSILON_PX) minOf(it, maxWidthPx) else it },
            height = height,
            policy = MathLineBreakPolicy.ResponsiveDisplayLeadingOperators,
            targetWidthPx = maxWidthPx,
            continuationAlignment = alignment,
            continuationAnchorPx = blockOrigin + paintedAnchorWithinBlock,
        ),
        indentTier = indentTier,
        requestedSemanticIndentPx = requestedAnchorWithinBlock,
        defaultIndentPx = defaultContinuationIndentPx,
        maximumCommonFeasibleIndentPx = maximumCommonFeasibleIndentPx,
        resolvedIndentPx = continuationAnchorWithinBlock,
    )
}

private fun rejectedResponsiveResolution(
    fragments: List<MathInlineFragment>,
    lineMetrics: MathFormulaLineMetrics,
    maxWidthPx: Float,
    defaultIndentPx: Float,
    diagnostic: MathDiagnostic,
): ResponsiveDisplayBreakResolution {
    val fallback = resourceRejectedUnbrokenLayout(
        fragments = fragments,
        lineMetrics = lineMetrics,
        policy = MathLineBreakPolicy.ResponsiveDisplayLeadingOperators,
        maxWidthPx = maxWidthPx,
        diagnostic = diagnostic,
    )
    return ResponsiveDisplayBreakResolution(
        layout = fallback,
        continuationFenceDepths = emptyList(),
        indentTier = ResponsiveContinuationIndentTier.None,
        requestedSemanticIndentPx = 0f,
        defaultIndentPx = defaultIndentPx,
        maximumCommonFeasibleIndentPx = 0f,
        resolvedIndentPx = 0f,
    )
}

private fun selectResponsiveLinesAtIndent(
    fragments: List<MathInlineFragment>,
    boundaries: List<ResponsiveBoundary>,
    rangeWidths: ResponsiveRangeWidths,
    maxWidthPx: Float,
    continuationAnchorWithinBlock: Float,
): ResponsiveFixedIndentSelection {
    val costs = FloatArray(boundaries.size) { Float.POSITIVE_INFINITY }
    val nextBoundary = IntArray(boundaries.size) { -1 }
    costs[boundaries.lastIndex] = 0f

    for (startBoundaryIndex in boundaries.lastIndex - 1 downTo 0) {
        val start = boundaries[startBoundaryIndex].position
        // Clause lines right-align later and never consume the operator anchor, so they measure
        // against the full width here.
        val lineOffset = if (start == 0 || boundaries[startBoundaryIndex].startsClause()) {
            0f
        } else {
            responsiveContinuationLineOffset(fragments[start], continuationAnchorWithinBlock)
        }
        val availableWidth = (maxWidthPx - lineOffset).coerceAtLeast(0f)
        for (endBoundaryIndex in startBoundaryIndex + 1..boundaries.lastIndex) {
            val end = boundaries[endBoundaryIndex].position
            val minimumWidth = rangeWidths.minimum(startBoundaryIndex, endBoundaryIndex)
            val fits = minimumWidth <= availableWidth + GEOMETRY_EPSILON_PX
            val isSingleIndivisibleSegment = endBoundaryIndex == startBoundaryIndex + 1
            // OverflowLineAbsorbsLeadingDebris: when the candidate's final segment cannot fit at
            // any indent, an overflow line is unavoidable — the line may then start at a cleaner
            // shallower boundary and absorb the segments before the overwide atom, instead of
            // being forced to begin exactly at the (often fenced) boundary that precedes it.
            // Skip (not break) other non-fitting candidates: a later end whose final segment
            // overflows is still a legal absorption target even after a fitting-tail candidate.
            val finalSegmentOverflows = rangeWidths.minimum(endBoundaryIndex - 1, endBoundaryIndex) >
                maxWidthPx + GEOMETRY_EPSILON_PX
            if (!fits && !isSingleIndivisibleSegment && !finalSegmentOverflows) continue

            val naturalWidth = rangeWidths.natural(startBoundaryIndex, endBoundaryIndex)
            val isFinal = end == fragments.size
            val slack = (availableWidth - naturalWidth).coerceAtLeast(0f)
            val raggedness = if (availableWidth <= GEOMETRY_EPSILON_PX) {
                0f
            } else {
                val ratio = slack / availableWidth
                val weight = if (isFinal && start > 0) {
                    RESPONSIVE_FINAL_LINE_RAGGEDNESS_WEIGHT
                } else if (isFinal) {
                    0f
                } else {
                    RESPONSIVE_RAGGEDNESS_WEIGHT
                }
                ratio * ratio * weight
            }
            // The flat term makes any overflow a last resort; the excess term scales with how
            // far past the viewport the line reaches, so an unavoidable overflow line absorbs
            // only the debris it must and never swallows content that could stay readable.
            val overflowCost = if (fits) {
                0f
            } else {
                RESPONSIVE_UNBREAKABLE_OVERFLOW_PENALTY +
                    RESPONSIVE_OVERFLOW_EXCESS_WEIGHT * ((minimumWidth - maxWidthPx) / maxWidthPx)
            }
            val boundaryPenalty = if (isFinal) 0f else responsiveBreakPenalty(boundaries[endBoundaryIndex])
            val candidate = raggedness + overflowCost + boundaryPenalty + costs[endBoundaryIndex]
            if (candidate < costs[startBoundaryIndex]) {
                costs[startBoundaryIndex] = candidate
                nextBoundary[startBoundaryIndex] = endBoundaryIndex
            }
        }
    }

    val selected = mutableListOf<ResponsiveSelectedLine>()
    var boundaryIndex = 0
    while (boundaryIndex < boundaries.lastIndex) {
        val next = nextBoundary[boundaryIndex].takeIf { it > boundaryIndex } ?: boundaryIndex + 1
        selected += ResponsiveSelectedLine(
            range = boundaries[boundaryIndex].position..(boundaries[next].position - 1),
            startingBoundary = boundaries[boundaryIndex].takeUnless { boundaryIndex == 0 },
            startBoundaryIndex = boundaryIndex,
            endBoundaryIndex = next,
        )
        boundaryIndex = next
    }
    val hasIndentInducedOverflow = selected.drop(1).any { line ->
        if (line.startingBoundary.startsClause()) return@any false
        val minimumWidth = rangeWidths.minimum(line.startBoundaryIndex, line.endBoundaryIndex)
        val lineOffset = responsiveContinuationLineOffset(
            fragments[line.range.first],
            continuationAnchorWithinBlock,
        )
        minimumWidth <= maxWidthPx + GEOMETRY_EPSILON_PX &&
            lineOffset + minimumWidth > maxWidthPx + GEOMETRY_EPSILON_PX
    }
    val hasInherentOperatorOverflow = selected.drop(1).any { line ->
        !line.startingBoundary.startsClause() &&
            rangeWidths.minimum(line.startBoundaryIndex, line.endBoundaryIndex) >
            maxWidthPx + GEOMETRY_EPSILON_PX
    }
    return ResponsiveFixedIndentSelection(selected, hasIndentInducedOverflow, hasInherentOperatorOverflow)
}

/** Largest shared anchor that still fits every avoidably-overflowing indivisible continuation. */
private fun maximumFeasibleResponsiveIndent(
    fragments: List<MathInlineFragment>,
    boundaries: List<ResponsiveBoundary>,
    rangeWidths: ResponsiveRangeWidths,
    maxWidthPx: Float,
): Float {
    val constraints = (1 until boundaries.lastIndex).mapNotNull { startBoundaryIndex ->
        if (boundaries[startBoundaryIndex].startsClause()) {
            // Clause lines right-align instead of taking the shared indent, so they never
            // constrain it.
            return@mapNotNull null
        }
        val start = boundaries[startBoundaryIndex].position
        val minimumWidth = rangeWidths.minimum(startBoundaryIndex, startBoundaryIndex + 1)
        if (minimumWidth > maxWidthPx + GEOMETRY_EPSILON_PX) {
            // This segment overflows even with zero indent; moving every other continuation to the
            // left edge cannot repair it and must not destroy their common alignment.
            null
        } else {
            val firstInkFromVisualLineLeft = responsiveContinuationOperatorInkFromVisualLeft(fragments[start])
            (maxWidthPx - minimumWidth + firstInkFromVisualLineLeft).coerceAtLeast(0f)
        }
    }
    return constraints.minOrNull() ?: 0f
}

internal data class ResponsiveBoundary(
    val position: Int,
    val kind: MathBreakKind?,
    /** Unclosed Opening fences left of this boundary; deeper boundaries cost more to break. */
    val fenceDepth: Int = 0,
)

internal fun responsiveLineSourceRanges(
    fragments: List<MathInlineFragment>,
    broken: MathBrokenLayout,
): String = broken.lines.joinToString(";") { line ->
    val first = fragments[line.fragments.first().fragmentIndex].sourceRange.start
    val last = fragments[line.fragments.last().fragmentIndex].sourceRange.endExclusive
    "$first..$last"
}

internal fun responsiveContinuationBreakKinds(broken: MathBrokenLayout): String =
    broken.lines.drop(1).joinToString(",") { checkNotNull(it.breakKind).name }

internal fun responsiveBoundaries(fragments: List<MathInlineFragment>): List<ResponsiveBoundary> = buildList {
    add(ResponsiveBoundary(0, null))
    // Plain parentheses do not group in TeX, so their interior operators are top-level fragments.
    // FenceDepthScaledBreakPenalty tracks how many Opening fences are unclosed at each boundary;
    // breaking inside a fence leaves it dangling open at the line end, so such boundaries only
    // win when every same-or-shallower alternative is markedly worse.
    var fenceDepth = 0
    for (position in 1 until fragments.size) {
        val current = fragments[position]
        val previous = fragments[position - 1]
        when (previous.atomClass) {
            MathAtomClass.Opening -> fenceDepth += 1
            MathAtomClass.Closing -> fenceDepth = (fenceDepth - 1).coerceAtLeast(0)
            else -> Unit
        }
        val kind = when (current.atomClass) {
            MathAtomClass.Relation -> MathBreakKind.RelationLeading
            MathAtomClass.Binary -> MathBreakKind.BinaryOperatorLeading
            else -> previous.breakAfter?.kind?.takeIf { it == MathBreakKind.PunctuationTrailing }
        }
        if (kind != null) add(ResponsiveBoundary(position, kind, fenceDepth))
    }
    add(ResponsiveBoundary(fragments.size, null))
}

/*
 * The anchor is measured on the unbroken line, before breaks are chosen. This is safe without a
 * post-break reconciliation: any anchor whose continuation cannot fit at its offset is rejected
 * by the indent-induced-overflow check, and an anchor beyond the first line's own end implies the
 * anchored continuations extend the block past that edge themselves, so the shared painted-edge
 * alignment they establish remains visually meaningful.
 */
private data class ResponsiveAnchorRequest(
    val alignment: MathContinuationAlignment,
    val anchorPx: Float,
    val anchorFragmentIndex: Int,
)

private fun responsiveContinuationAnchor(
    fragments: List<MathInlineFragment>,
): ResponsiveAnchorRequest {
    val fullRange = fragments.indices
    val geometry = geometry(fragments, fullRange, internalGlue(fragments, fullRange) { it.naturalPx })
    val candidates = listOf(
        MathAtomClass.Relation to MathContinuationAlignment.FirstRelation,
        MathAtomClass.Binary to MathContinuationAlignment.FirstBinaryOperator,
    )
    candidates.forEach { (atomClass, alignment) ->
        val position = fragments.indexOfFirst { it.atomClass == atomClass }
        if (position > 0) {
            val placement = geometry.placements.first { it.fragmentIndex == position }
            val anchor = placement.x + fragments[position].box.inkBounds.left - geometry.visualLeft
            if (anchor >= 0f) {
                return ResponsiveAnchorRequest(alignment, anchor, position)
            }
        }
    }
    return ResponsiveAnchorRequest(MathContinuationAlignment.None, 0f, -1)
}

/**
 * Visual-line origin needed to put the first painted operator edge on [continuationAnchorPx].
 * Logical side bearings remain intact; they are not discarded from measurement or replay.
 */
private fun responsiveContinuationLineOffset(
    firstFragment: MathInlineFragment,
    continuationAnchorPx: Float,
): Float {
    val firstInkFromVisualLineLeft = responsiveContinuationOperatorInkFromVisualLeft(firstFragment)
    return (continuationAnchorPx - firstInkFromVisualLineLeft).coerceAtLeast(0f)
}

private fun responsiveContinuationOperatorInkFromVisualLeft(
    firstFragment: MathInlineFragment,
): Float {
    val firstInkLeft = firstFragment.leadingKernPx + firstFragment.box.inkBounds.left
    return firstInkLeft - minOf(0f, firstInkLeft)
}

/**
 * A depth-0 punctuation-trailing break starts a new clause (e.g. a domain condition after a
 * comma), not an operator continuation, so its line follows ClauseContinuationRightAligned
 * placement. A comma inside a fence separates function arguments — never a clause.
 */
private fun ResponsiveBoundary?.startsClause(): Boolean =
    this != null && kind == MathBreakKind.PunctuationTrailing && fenceDepth == 0

private fun responsiveBreakPenalty(boundary: ResponsiveBoundary): Float {
    val base = when (boundary.kind) {
        MathBreakKind.RelationLeading -> RESPONSIVE_RELATION_BREAK_PENALTY
        MathBreakKind.BinaryOperatorLeading -> RESPONSIVE_BINARY_BREAK_PENALTY
        // FencedArgumentComma: inside a fence a comma separates function arguments — the worst
        // legal break at its depth, unlike a top-level clause comma which is the cheapest.
        MathBreakKind.PunctuationTrailing -> if (boundary.fenceDepth > 0) {
            RESPONSIVE_FENCED_ARGUMENT_COMMA_PENALTY
        } else {
            RESPONSIVE_PUNCTUATION_BREAK_PENALTY
        }
        else -> error("responsive boundaries never produce break kind ${boundary.kind}")
    }
    return base + boundary.fenceDepth * RESPONSIVE_FENCE_DEPTH_PENALTY
}

internal fun internalGlue(
    fragments: List<MathInlineFragment>,
    range: IntRange,
    selector: (org.tiqian.math.core.MathGlueAdjustment) -> Float,
): List<Float> =
    range.map { fragmentIndex ->
        if (fragmentIndex == range.last) 0f else selector(fragments[fragmentIndex].trailingGlue)
    }

internal fun geometry(
    fragments: List<MathInlineFragment>,
    range: IntRange,
    resolvedGlue: List<Float>,
): LineGeometry {
    var x = 0f
    var inkLeft = 0f
    var inkTop = 0f
    var inkRight = 0f
    var inkBottom = 0f
    var hasInk = false
    val placements = range.mapIndexed { localIndex, fragmentIndex ->
        val fragment = fragments[fragmentIndex]
        x += fragment.leadingKernPx
        val placement = MathLineFragmentPlacement(fragmentIndex, x, resolvedGlue[localIndex])
        val translated = fragment.box.inkBounds.translated(x, 0f)
        if (!hasInk) {
            inkLeft = translated.left
            inkTop = translated.top
            inkRight = translated.right
            inkBottom = translated.bottom
            hasInk = translated.width != 0f || translated.height != 0f
        } else {
            inkLeft = minOf(inkLeft, translated.left)
            inkTop = minOf(inkTop, translated.top)
            inkRight = maxOf(inkRight, translated.right)
            inkBottom = maxOf(inkBottom, translated.bottom)
        }
        x += fragment.box.width + fragment.trailingItalicCorrectionPx + resolvedGlue[localIndex]
        placement
    }
    val ink = if (hasInk) MathRect(inkLeft, inkTop, inkRight, inkBottom) else MathRect(0f, 0f, 0f, 0f)
    val visualLeft = minOf(0f, ink.left)
    val visualRight = maxOf(x, ink.right)
    return LineGeometry(
        placements,
        x,
        ink,
        visualLeft,
        visualRight,
        visualRight - visualLeft,
        (-ink.top).coerceAtLeast(0f),
        ink.bottom.coerceAtLeast(0f),
    )
}

private fun resolveGlue(
    fragments: List<MathInlineFragment>,
    range: IntRange,
    natural: List<Float>,
    naturalLogicalWidth: Float,
    targetLogicalWidth: Float,
): List<Float> {
    val resolved = natural.toMutableList()
    var remaining = targetLogicalWidth - naturalLogicalWidth
    if (abs(remaining) <= GEOMETRY_EPSILON_PX) return resolved

    MathAdjustmentPriority.entries.sortedBy { it.order }.forEach { priority ->
        if (priority == MathAdjustmentPriority.None || abs(remaining) <= GEOMETRY_EPSILON_PX) return@forEach
        val eligible = range.mapIndexedNotNull { localIndex, fragmentIndex ->
            if (fragmentIndex == range.last) return@mapIndexedNotNull null
            val glue = fragments[fragmentIndex].trailingGlue
            if (glue.priority == priority) localIndex to glue else null
        }.toMutableList()
        while (eligible.isNotEmpty() && abs(remaining) > GEOMETRY_EPSILON_PX) {
            val share = remaining / eligible.size
            val exhausted = mutableListOf<Pair<Int, org.tiqian.math.core.MathGlueAdjustment>>()
            eligible.forEach { (index, glue) ->
                val capacity = if (remaining > 0f) glue.maximumPx - resolved[index] else resolved[index] - glue.minimumPx
                val applied = if (remaining > 0f) minOf(share, capacity) else maxOf(share, -capacity)
                resolved[index] += applied
                remaining -= applied
                if (capacity - abs(applied) <= GEOMETRY_EPSILON_PX) exhausted += index to glue
            }
            eligible.removeAll(exhausted.toSet())
        }
    }
    return resolved
}

internal data class LineGeometry(
    val placements: List<MathLineFragmentPlacement>,
    val logicalWidth: Float,
    val inkBounds: MathRect,
    val visualLeft: Float,
    val visualRight: Float,
    val visualWidth: Float,
    val inkAscent: Float,
    val inkDescent: Float,
)

private const val GEOMETRY_EPSILON_PX = 0.02f
private const val RESPONSIVE_RAGGEDNESS_WEIGHT = 1_000f
private const val RESPONSIVE_FINAL_LINE_RAGGEDNESS_WEIGHT = 5_000f
// Relation breaks read best; a top-level punctuation break separates complete sub-formulas and
// must stay cheaper than tearing a term apart at a binary operator.
private const val RESPONSIVE_RELATION_BREAK_PENALTY = 0f
private const val RESPONSIVE_PUNCTUATION_BREAK_PENALTY = 20f
private const val RESPONSIVE_BINARY_BREAK_PENALTY = 40f
private const val RESPONSIVE_FENCED_ARGUMENT_COMMA_PENALTY = 200f

// FenceDepthScaledBreakPenalty: each unclosed fence at a boundary adds this much. The value sits
// above any realistic raggedness differential (lines cost at most ~1000, the final line 5000), so
// a fenced break can never be bought by better line balance — splitting a function's argument
// list to fill lines is worse than any number of short depth-0 lines. It stays far below the
// overflow penalty, so fenced breaks remain the last legal resort before clipping, and depth
// levels keep their order.
private const val RESPONSIVE_FENCE_DEPTH_PENALTY = 10_000f

// DisplayRowJot: TeX opens display rows up by \jot (3pt at 10pt) beyond the ink-driven metrics;
// without it, a fraction denominator sits directly on the next row's bracket top.
internal const val DISPLAY_ROW_JOT_EM = 0.3f

// DisplayTextStyleTwoEm: single source for the default responsive continuation indent, shared by
// the public breaker entry point and the engine's soft-wrap paths.
internal const val DISPLAY_CONTINUATION_INDENT_EM = 2f
internal const val DISPLAY_CONTINUATION_INDENT_POLICY = "DisplayTextStyleTwoEm"
private const val RESPONSIVE_UNBREAKABLE_OVERFLOW_PENALTY = 1_000_000f
private const val RESPONSIVE_OVERFLOW_EXCESS_WEIGHT = 10_000f
