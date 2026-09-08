package org.tiqian.math.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.breakResponsiveDisplayLines
import kotlin.math.ceil
import kotlin.math.floor

/** Display-only presentation: legal math breaks first, then horizontal overflow when necessary. */
@Composable
internal fun ScrollableDisplayTiqianMath(
    result: MathLayoutResult,
    requestedLineHeightPx: Float?,
    color: Color,
    softWrap: Boolean,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    scrollState: ScrollState,
    horizontalInsetPx: Float,
    displayContentWidthPx: Float?,
    modifier: Modifier,
    onMathLayout: (MathLayoutResult) -> Unit,
    fallback: (@Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit)?,
) {
    val unbroken = RenderPlan.unbroken(result, requestedLineHeightPx)
    val contentWidth = displayContentWidthPx ?: unbroken.width
    // The multi-tier break is the most expensive step on the measure path; recompute it only
    // when the formula or the width actually changes, not on every recomposition.
    val broken = remember(result, contentWidth, softWrap) {
        if (softWrap && result.fragments.size > 1) {
            result.breakResponsiveDisplayLines(contentWidth)
        } else {
            null
        }
    }
    val failure = lineBreakFailure(result, broken)
    if (failure != null) {
        val errorPresentation = fallback ?: throw MathFormulaStrictException(failure)
        errorPresentation(failure)
        return
    }
    SideEffect { onMathLayout(result) }
    // PinnedClauseLikeTag: when the block must scroll, its fitting clause lines anchor to the
    // viewport like an equation tag instead of traveling with the scrolled content.
    val plans = if (broken != null) {
        RenderPlan.brokenWithPinnedClauses(result, broken, requestedLineHeightPx, contentWidth)
    } else {
        unbroken.centeredIn(contentWidth) to null
    }
    val renderPlan = plans.first.insetHorizontally(horizontalInsetPx)
    val pinnedPlan = plans.second?.insetHorizontally(horizontalInsetPx)
    val requestedViewportWidth = contentWidth + horizontalInsetPx * 2f
    // NoScrollForSubpixelExcess: when every line fits the engine viewport, the scrolled
    // content must not measure wider than the container. The container width is an Int from
    // the parent constraint while the plan width made a constraints -> Dp -> px round trip,
    // so a positive float epsilon would ceil into a phantom pixel of scrollable range.
    // Flooring the fitting plan to the viewport keeps it at or below the container Int.
    val contentFits = if (broken != null) {
        broken.lines.none { it.unbreakableOverflow }
    } else {
        unbroken.width <= contentWidth + PhantomScrollTolerancePx
    }
    val boundedPlan = if (contentFits) {
        renderPlan.copy(width = minOf(renderPlan.width, floor(requestedViewportWidth)))
    } else {
        renderPlan
    }
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.horizontalScroll(scrollState)) {
                FixedTiqianMathPlan(boundedPlan, Modifier, color, face, textRunProvider)
            }
            if (pinnedPlan != null) {
                FixedTiqianMathPlan(pinnedPlan, Modifier, color, face, textRunProvider)
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            ceil(requestedViewportWidth).toInt()
        }
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val planHeight = maxOf(renderPlan.height, pinnedPlan?.height ?: 0f)
        val height = ceil(planHeight).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placed = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to renderPlan.firstBaseline.toInt()),
        ) {
            placed.forEach { it.place(0, 0) }
        }
    }
}


/** Keeps equation tags fixed while only the independently replayable body may scroll. */
@Composable
internal fun TaggedDisplayTiqianMath(
    result: MathLayoutResult,
    requestedLineHeightPx: Float?,
    color: Color,
    /** Equation tags are navigation labels, not formula content; unspecified inherits [color]. */
    tagColor: Color,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    scrollState: ScrollState,
    horizontalInsetPx: Float,
    modifier: Modifier,
) {
    val replay = checkNotNull(result.taggedDisplayReplay)
    val whole = RenderPlan.unbroken(result, requestedLineHeightPx)
    val rawVisualLeft = replay.bodyLogicalX + replay.body.visualLeft
    val rawVisualRight = replay.bodyLogicalX + replay.body.visualRight
    val contentVisualLeft = if (rawVisualLeft >= -DisplayGeometryEpsilonPx) 0f else rawVisualLeft
    val contentVisualRight = if (rawVisualRight <= replay.viewportWidthPx + DisplayGeometryEpsilonPx) {
        replay.viewportWidthPx
    } else {
        rawVisualRight
    }
    val rawBodyPlan = RenderPlan(
        boxes = listOf(
            PositionedBox(
                box = replay.body,
                x = replay.bodyLogicalX - contentVisualLeft,
                baselineFromTop = whole.firstBaseline,
            ),
        ),
        width = contentVisualRight - contentVisualLeft,
        height = whole.height,
        firstBaseline = whole.firstBaseline,
    )
    val bodyPlan = rawBodyPlan.insetHorizontally(horizontalInsetPx)
    // The tag is a navigation label and may carry its own secondary color; pinned clauses are
    // formula content and always keep the formula color, so the two anchor layers paint apart.
    val overlayWidth = replay.viewportWidthPx + horizontalInsetPx * 2f
    val tagPlan = RenderPlan(
        boxes = replay.tags.map { tag ->
            PositionedBox(
                box = tag.box,
                x = horizontalInsetPx + tag.logicalX,
                baselineFromTop = whole.firstBaseline + tag.baselineY,
            )
        },
        width = overlayWidth,
        height = whole.height,
        firstBaseline = whole.firstBaseline,
    )
    val pinnedClausePlan = if (replay.pinnedClauses.isEmpty()) {
        null
    } else {
        RenderPlan(
            boxes = replay.pinnedClauses.map { clause ->
                // PinnedClauseLikeTag: the clause anchors to the viewport with the tag while the
                // body scrolls beneath it.
                PositionedBox(
                    box = clause.box,
                    x = horizontalInsetPx + clause.logicalX,
                    baselineFromTop = whole.firstBaseline + clause.baselineY,
                )
            },
            width = overlayWidth,
            height = whole.height,
            firstBaseline = whole.firstBaseline,
        )
    }
    // NoScrollForSubpixelExcess: see ScrollableDisplayTiqianMath.
    val bodyFits = rawVisualLeft >= -PhantomScrollTolerancePx &&
        rawVisualRight <= replay.viewportWidthPx + PhantomScrollTolerancePx
    val boundedBodyPlan = if (bodyFits) {
        bodyPlan.copy(width = minOf(bodyPlan.width, floor(replay.viewportWidthPx + horizontalInsetPx * 2f)))
    } else {
        bodyPlan
    }
    val resolvedTagColor = if (tagColor == Color.Unspecified) color else tagColor
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.horizontalScroll(scrollState)) {
                FixedTiqianMathPlan(boundedBodyPlan, Modifier, color, face, textRunProvider)
            }
            FixedTiqianMathPlan(tagPlan, Modifier, resolvedTagColor, face, textRunProvider)
            if (pinnedClausePlan != null) {
                FixedTiqianMathPlan(pinnedClausePlan, Modifier, color, face, textRunProvider)
            }
        },
    ) { measurables, constraints ->
        val width = ceil(overlayWidth)
            .toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(whole.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val bodyWidth = ceil(overlayWidth).toInt().coerceIn(0, width)
        val body = measurables[0].measure(Constraints.fixed(bodyWidth, height))
        val overlays = measurables.drop(1).map { it.measure(Constraints.fixed(width, height)) }
        layout(
            width,
            height,
            alignmentLines = mapOf(FirstBaseline to whole.firstBaseline.toInt()),
        ) {
            body.place(0, 0)
            overlays.forEach { it.place(0, 0) }
        }
    }
}

private const val DisplayGeometryEpsilonPx = 0.01f
private const val PhantomScrollTolerancePx = 0.5f
