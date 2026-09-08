package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.layout.MathLayoutPass.Companion.BIG_POINT_TO_PX
import org.tiqian.math.layout.MathLayoutPass.Companion.CENTIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.CSS_PIXELS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.MILLIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_POINT_TO_PX
import org.tiqian.math.layout.MathLayoutPass.DelimiterTargetEvidence
import org.tiqian.math.layout.MathLayoutPass.LaidNode

/**
 * The shared explainability surface of one responsive wrap: every wrap decision carries these
 * keys, whatever path produced it, so dumps and corpus tooling parse one schema.
 */
internal fun MathLayoutPass.responsiveWrapDecisionDetails(
    breakResolution: ResponsiveDisplayBreakResolution,
    fragments: List<MathInlineFragment>,
    style: MathStyle,
): Array<Pair<String, Any?>> {
    val broken = breakResolution.layout
    return arrayOf(
        "lineCount" to broken.lines.size,
        "overfullLineCount" to broken.lines.count { it.unbreakableOverflow },
        "pinnedClauseLineCount" to broken.lines.count { it.pinned },
        "lineSourceRanges" to responsiveLineSourceRanges(fragments, broken),
        "continuationBreakKinds" to responsiveContinuationBreakKinds(broken),
        "continuationBreakDepths" to breakResolution.continuationFenceDepths.joinToString(","),
        "terminalLineCostPolicy" to "ContinuationFinalLineSquaredRaggednessWithOrphanBias",
        "displayRowJotPx" to (DISPLAY_ROW_JOT_EM * fontSize(style)),
        "operatorPlacement" to "LeadingContinuationLine",
        "clausePlacement" to "ClauseContinuationRightAligned",
        "boundaryPenaltyPolicy" to "KindBasePlusFenceDepthScaled",
        "selection" to "MinimumCostOverLegalBreaksAtResolvedSharedIndent",
        "continuationAlignment" to broken.continuationAlignment,
        "continuationAnchorPx" to broken.continuationAnchorPx,
        "requestedSemanticIndentPx" to breakResolution.requestedSemanticIndentPx,
        "defaultIndentPx" to breakResolution.defaultIndentPx,
        "maximumCommonFeasibleIndentPx" to breakResolution.maximumCommonFeasibleIndentPx,
        "resolvedContinuationIndentPx" to breakResolution.resolvedIndentPx,
        "continuationIndentTier" to breakResolution.indentTier,
        "defaultIndentPolicy" to DISPLAY_CONTINUATION_INDENT_POLICY,
    )
}

internal fun MathLayoutPass.resolveSoftWrappedDisplayBody(
    horizontal: MathLayoutPass.HorizontalLayout,
    style: MathStyle,
    range: SourceRange,
    targetWidthPx: Float?,
    decisionName: String,
    recordTaggedDisplayBaseline: Boolean,
    pinClausesToViewport: Boolean = false,
): MathBox {
    val viewportWidth = targetWidthPx
    val body = horizontal.laid.box
    if (!softWrapDisplay || viewportWidth == null) return body
    val fragments = inlineFragments(horizontal)
    if (fragments.size <= 1) return body
    val lineMetrics = formulaLineMetrics(body, style)
    val breakResolution = resolveResponsiveDisplayBreak(
        fragments = fragments,
        lineMetrics = lineMetrics,
        maxWidthPx = viewportWidth,
        defaultContinuationIndentPx = DISPLAY_CONTINUATION_INDENT_EM * fontSize(style),
        displayRowJotPx = DISPLAY_ROW_JOT_EM * fontSize(style),
        resourceLimits = resourceLimits,
    )
    diagnostics += breakResolution.layout.diagnostics.filterNot(diagnostics::contains)
    val broken = breakResolution.layout
    if (broken.lines.size == 1 && body.visualWidth <= viewportWidth) return body

    val wrapped = replayResponsiveDisplayBody(
        fragments = fragments,
        broken = broken,
        viewportWidthPx = viewportWidth,
        range = range,
        pinClausesToViewport = pinClausesToViewport,
    )
    if (recordTaggedDisplayBaseline) {
        taggedDisplayBodyLastBaselineY =
            broken.lines.last().baselineFromTop - broken.lines.first().baselineFromTop
    }
    decision(
        decisionName,
        range,
        *responsiveWrapDecisionDetails(breakResolution, fragments, style),
        "viewportWidthPx" to viewportWidth,
        "unbrokenVisualWidthPx" to body.visualWidth,
        "wrappedBodyWidthPx" to wrapped.width,
        "breakPolicy" to broken.policy,
        "policy" to "ResponsiveDisplayLeadingOperators",
    )
    return wrapped
}

internal fun MathLayoutPass.replayResponsiveDisplayBody(
    fragments: List<MathInlineFragment>,
    broken: MathBrokenLayout,
    viewportWidthPx: Float,
    range: SourceRange,
    pinClausesToViewport: Boolean = false,
): MathBox {
    val firstBaseline = broken.lines.firstOrNull()?.baselineFromTop ?: return emptyBox(range)
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val groups = mutableListOf<MathConstructionPaintGroup>()
    val children = mutableListOf<Pair<MathBox, Float>>()
    broken.lines.forEach { line ->
        // The pin decision is engine layout truth on the line; this replay only honors it when a
        // tagged completion is in flight to anchor the clause.
        val pinned = pinClausesToViewport && line.pinned
        val lineOriginX = line.horizontalOffsetPx - line.visualLeft
        val baselineY = line.baselineFromTop - firstBaseline
        if (pinned) {
            val lineGlyphs = mutableListOf<MathGlyphPlacement>()
            val lineRules = mutableListOf<MathRulePlacement>()
            val lineHostRuns = mutableListOf<MathHostTextPlacement>()
            val lineGroups = mutableListOf<MathConstructionPaintGroup>()
            val lineChildren = mutableListOf<Pair<MathBox, Float>>()
            line.fragments.forEach { placement ->
                val fragment = fragments[placement.fragmentIndex]
                val shifted = fragment.box.translated(placement.x - line.visualLeft, 0f)
                lineGlyphs += shifted.glyphs
                lineRules += shifted.rules
                lineHostRuns += shifted.hostTextRuns
                lineGroups += shifted.constructionPaintGroups
                lineChildren += fragment.box to 0f
            }
            val sourceStart = line.fragments.minOf { fragments[it.fragmentIndex].sourceRange.start }
            val sourceEnd = line.fragments.maxOf { fragments[it.fragmentIndex].sourceRange.endExclusive }
            val clauseBox = geometryExtentsPreservingLogicalChildren(
                width = line.width,
                glyphs = lineGlyphs,
                rules = lineRules,
                range = SourceRange(sourceStart, sourceEnd),
                children = lineChildren,
                constructionPaintGroups = lineGroups,
                hostTextRuns = lineHostRuns,
            )
            taggedDisplayPendingPinnedClauses += MathPinnedClauseReplay(
                box = clauseBox,
                sourceRange = SourceRange(sourceStart, sourceEnd),
                logicalX = (viewportWidthPx - line.width).coerceAtLeast(0f),
                baselineY = baselineY,
            )
            return@forEach
        }
        line.fragments.forEach { placement ->
            val fragment = fragments[placement.fragmentIndex]
            val shifted = fragment.box.translated(lineOriginX + placement.x, baselineY)
            glyphs += shifted.glyphs
            rules += shifted.rules
            hostTextRuns += shifted.hostTextRuns
            groups += shifted.constructionPaintGroups
            children += fragment.box to baselineY
        }
    }
    val width = maxOf(viewportWidthPx, broken.width)
    val geometry = geometryExtentsPreservingLogicalChildren(
        width = width,
        glyphs = glyphs,
        rules = rules,
        range = range,
        children = children,
        constructionPaintGroups = groups,
        hostTextRuns = hostTextRuns,
    )
    val blockAscent = firstBaseline
    val blockDescent = (broken.height - firstBaseline).coerceAtLeast(0f)
    return geometry.copy(
        ascent = maxOf(geometry.ascent, blockAscent),
        descent = maxOf(geometry.descent, blockDescent),
        texCleanBoxMetrics = geometry.texCleanBoxMetrics.copy(
            ascent = maxOf(geometry.texCleanBoxMetrics.ascent, blockAscent),
            descent = maxOf(geometry.texCleanBoxMetrics.descent, blockDescent),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = geometry.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
}

internal fun MathLayoutPass.layoutMisplacedEquationTag(node: MathEquationTag, style: MathStyle): LaidNode {
    diagnostics += MathDiagnostic(
        DiagnosticCode.MisplacedEquationTag,
        "Equation tag is only valid at the top level of a display row",
        node.range,
    )
    return LaidNode(
        node,
        emptyBox(node.range),
        MathAtomClass.Ordinary,
        0f,
        style,
        ScriptBaseKind.CompoundBox,
    )
}

/** Painted vertical extent of a completed box: logical metric or ink, whichever reaches further. */
internal fun MathBox.completedAscentPx(): Float = maxOf(ascent, -inkBounds.top)

internal fun MathBox.completedDescentPx(): Float = maxOf(descent, inkBounds.bottom)

/**
 * ShiftedTagClearsCompletedBodyByHalfEm: every shifted equation tag shares one clearance rule —
 * at least one em below the anchoring baseline, and at least half an em of painted separation
 * between the completed body bottom and the completed tag top.
 */
internal fun MathLayoutPass.shiftedTagBaselineFloor(
    oneEmBaselineFloorPx: Float,
    completedBodyBottomPx: Float,
    tagBox: MathBox,
): Float = maxOf(
    oneEmBaselineFloorPx,
    completedBodyBottomPx + baseFontSizePx / 2f + tagBox.completedAscentPx(),
)

internal fun MathLayoutPass.completeTaggedRows(
    body: MathBox,
    rows: List<MathTableRow>,
    tagBoxes: List<MathBox?>,
    rowBaselines: List<Float>,
    rowLogicalExtents: List<ClosedFloatingPointRange<Float>>,
    range: SourceRange,
    layoutRole: String,
): MathBox {
    val width = resolvedEquationTagDisplayWidth(range) ?: body.width
    val centeredBodyX = (width - body.width) / 2f
    val bodyX = if (body.width <= width) centeredBodyX else 0f
    val shiftedBody = body.translated(bodyX, 0f)
    val glyphs = shiftedBody.glyphs.toMutableList()
    val hostTextRuns = shiftedBody.hostTextRuns.toMutableList()
    val rules = shiftedBody.rules.toMutableList()
    val groups = shiftedBody.constructionPaintGroups.toMutableList()
    val children = mutableListOf(body to 0f)
    val tagReplays = mutableListOf<MathEquationTagReplay>()
    var nextShiftedTagBaseline = maxOf(body.descent, body.inkBounds.bottom)
    rows.forEachIndexed { index, row ->
        val tag = row.tag ?: return@forEachIndexed
        val tagBox = checkNotNull(tagBoxes[index])
        val tagX = width - tagBox.width
        val rowExtent = rowLogicalExtents.getOrElse(index) { 0f..body.width }
        val rowLeft = bodyX + rowExtent.start
        val rowRight = bodyX + rowExtent.endInclusive
        val minimumSeparation = baseFontSizePx / 2f
        val fitsSameLine = rowLeft >= 0f && rowRight + minimumSeparation <= tagX
        val rowBaselineY = rowBaselines.getOrElse(index) { 0f }
        val baselineY = if (fitsSameLine) {
            rowBaselineY
        } else {
            shiftedTagBaselineFloor(
                oneEmBaselineFloorPx = rowBaselineY + baseFontSizePx,
                completedBodyBottomPx = nextShiftedTagBaseline,
                tagBox = tagBox,
            )
        }
        val placement = if (fitsSameLine) {
            MathEquationTagPlacement.SameLineRight
        } else {
            MathEquationTagPlacement.ShiftedBelowRight
        }
        if (!fitsSameLine) {
            decision(
                "ShiftedRowEquationTagVerticalSeparation",
                tag.range,
                "rowIndex" to index,
                "rowBaselineY" to rowBaselineY,
                "completedBodyBottomPx" to nextShiftedTagBaseline,
                "tagCompletedAscentPx" to tagBox.completedAscentPx(),
                "minimumSeparationPx" to minimumSeparation,
                "resolvedTagBaselinePx" to baselineY,
                "policy" to "ShiftedTagClearsCompletedBodyByHalfEm",
            )
            nextShiftedTagBaseline = baselineY + maxOf(tagBox.descent, baseFontSizePx / 2f)
        }
        equationTagFitDecision(
            bodyX = rowLeft,
            bodyWidth = rowExtent.endInclusive - rowExtent.start,
            tagX = tagX,
            tag = tag,
            width = width,
            layoutRole = layoutRole,
            fits = fitsSameLine,
            tagBaselineY = baselineY,
            placement = placement.name,
        )
        val shifted = tagBox.translated(tagX, baselineY)
        glyphs += shifted.glyphs
        hostTextRuns += shifted.hostTextRuns
        rules += shifted.rules
        groups += shifted.constructionPaintGroups
        children += tagBox to baselineY
        tagReplays += MathEquationTagReplay(
            box = tagBox,
            sourceRange = tag.range,
            logicalX = tagX,
            baselineY = baselineY,
            placement = placement,
        )
        equationTagDecision(
            tag = tag,
            tagBox = tagBox,
            bodyWidth = rowExtent.endInclusive - rowExtent.start,
            width = width,
            bodyX = rowLeft,
            tagX = tagX,
            tagBaselineY = baselineY,
            layoutRole = layoutRole,
            placement = placement.name,
        )
    }
    if (taggedDisplayReplay != null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedEquationTag,
            "Only one tagged display body may complete per formula; an earlier tagged body's replay was replaced",
            range,
        )
    }
    taggedDisplayReplay = MathTaggedDisplayReplay(
        body = body,
        bodyLogicalX = bodyX,
        viewportWidthPx = width,
        tags = tagReplays,
    )
    taggedDisplayReplayDecision(range, checkNotNull(taggedDisplayReplay), layoutRole)
    return geometryExtentsPreservingLogicalChildren(
        width.coerceAtLeast(0f),
        glyphs,
        rules,
        range,
        children,
        groups,
        hostTextRuns,
    )
}

internal fun MathLayoutPass.completeTaggedEquationBox(
    body: MathBox,
    tag: MathEquationTag,
    style: MathStyle,
    tagBox: MathBox = layoutEquationTagBox(tag, style),
    range: SourceRange,
    layoutRole: String,
    centeredBesideMultiline: Boolean,
    responsiveBodyViewportWidthPx: Float? = null,
    shiftedTagMustClearCompletedBody: Boolean = false,
): MathBox {
    if (formulaMode != MathMode.Display) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedEquationTag,
            "Equation tag requires display math mode",
            tag.range,
        )
    }
    val width = resolvedEquationTagDisplayWidth(tag.range)
        ?: (body.width + tagBox.width + baseFontSizePx / 2f)
    val centeredBodyX = (width - body.width) / 2f
    val tagX = width - tagBox.width
    val minimumSeparation = baseFontSizePx / 2f
    val responsiveElectronicBody = responsiveBodyViewportWidthPx != null
    val responsiveMultiline = responsiveElectronicBody && taggedDisplayBodyLastBaselineY > 0f
    val fitsSameLine = !responsiveMultiline && centeredBodyX >= 0f &&
        centeredBodyX + body.width + minimumSeparation <= tagX
    val bodyX = if (fitsSameLine || body.width <= width) centeredBodyX else 0f
    val bodyVisualLeft = bodyX + body.visualLeft
    val bodyVisualRight = bodyX + body.visualRight
    val horizontallyScrollableBody =
        bodyVisualLeft < -DISPLAY_GEOMETRY_EPSILON_PX ||
            bodyVisualRight > width + DISPLAY_GEOMETRY_EPSILON_PX
    val pinnedClauses = taggedDisplayPendingPinnedClauses.toList()
        .also { taggedDisplayPendingPinnedClauses.clear() }
        .also { taggedDisplayReplayExpected = false }
    val bodyCompletedBottom = maxOf(
        body.completedDescentPx(),
        pinnedClauses.maxOfOrNull { it.baselineY + it.box.completedDescentPx() } ?: Float.NEGATIVE_INFINITY,
    )
    val tagCompletedAscent = tagBox.completedAscentPx()
    val baselineFloor = equationTagBaselineBelow()
    val completedBoxSeparationFloor =
        shiftedTagBaselineFloor(baselineFloor, bodyCompletedBottom, tagBox)
    val preservesCompletedBodyGap =
        responsiveElectronicBody || horizontallyScrollableBody || shiftedTagMustClearCompletedBody
    val tagBaselineY = when {
        fitsSameLine -> 0f
        preservesCompletedBodyGap -> completedBoxSeparationFloor
        else -> baselineFloor
    }
    val placement = when {
        !fitsSameLine -> MathEquationTagPlacement.ShiftedBelowRight
        centeredBesideMultiline -> MathEquationTagPlacement.CenteredBesideMultiline
        else -> MathEquationTagPlacement.SameLineRight
    }
    equationTagFitDecision(
        bodyX = bodyX,
        bodyWidth = body.width,
        tagX = tagX,
        tag = tag,
        width = width,
        layoutRole = layoutRole,
        fits = fitsSameLine,
        tagBaselineY = tagBaselineY,
        placement = placement.name,
    )
    if (!fitsSameLine && preservesCompletedBodyGap) {
        val tagCompletedTop = tagBaselineY - tagCompletedAscent
        decision(
            "ShiftedEquationTagVerticalSeparation",
            tag.range,
            "bodyVisualLeftPx" to bodyVisualLeft,
            "bodyVisualRightPx" to bodyVisualRight,
            "viewportWidthPx" to width,
            "horizontallyScrollableBody" to horizontallyScrollableBody,
            "responsiveElectronicBody" to responsiveElectronicBody,
            "responsiveMultilineBody" to responsiveMultiline,
            "completedExteriorFrame" to shiftedTagMustClearCompletedBody,
            "bodyCompletedBottomPx" to bodyCompletedBottom,
            "tagCompletedAscentPx" to tagCompletedAscent,
            "minimumSeparationPx" to minimumSeparation,
            "baselineFloorPx" to baselineFloor,
            "completedBoxSeparationFloorPx" to completedBoxSeparationFloor,
            "resolvedTagBaselinePx" to tagBaselineY,
            "resolvedCompletedBoxGapPx" to (tagCompletedTop - bodyCompletedBottom),
            "policy" to "CompletedBodyToShiftedTagHalfEmMinimumWithOneEmBaselineFloor",
        )
    }
    val shiftedBody = body.translated(bodyX, 0f)
    val shiftedTag = tagBox.translated(tagX, tagBaselineY)
    equationTagDecision(
        tag,
        tagBox,
        body.width,
        width,
        bodyX,
        tagX,
        tagBaselineY,
        layoutRole,
        placement.name,
    )
    if (taggedDisplayReplay != null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MisplacedEquationTag,
            "Only one tagged display body may complete per formula; an earlier tagged body's replay was replaced",
            range,
        )
    }
    taggedDisplayReplay = MathTaggedDisplayReplay(
        body = body,
        bodyLogicalX = bodyX,
        viewportWidthPx = width,
        tags = listOf(
            MathEquationTagReplay(
                box = tagBox,
                sourceRange = tag.range,
                logicalX = tagX,
                baselineY = tagBaselineY,
                placement = placement,
            ),
        ),
        pinnedClauses = pinnedClauses,
    )
    taggedDisplayReplayDecision(range, checkNotNull(taggedDisplayReplay), layoutRole)
    val shiftedClauses = pinnedClauses.map { it.box.translated(it.logicalX, it.baselineY) }
    return geometryExtentsPreservingLogicalChildren(
        width,
        shiftedBody.glyphs + shiftedClauses.flatMap { it.glyphs } + shiftedTag.glyphs,
        shiftedBody.rules + shiftedClauses.flatMap { it.rules } + shiftedTag.rules,
        range,
        listOf(body to 0f) + pinnedClauses.map { it.box to it.baselineY } + listOf(tagBox to tagBaselineY),
        shiftedBody.constructionPaintGroups + shiftedClauses.flatMap { it.constructionPaintGroups } +
            shiftedTag.constructionPaintGroups,
        shiftedBody.hostTextRuns + shiftedClauses.flatMap { it.hostTextRuns } + shiftedTag.hostTextRuns,
    )
}

private fun MathLayoutPass.equationTagBaselineBelow(): Float =
    taggedDisplayBodyLastBaselineY + baseFontSizePx

internal const val DISPLAY_GEOMETRY_EPSILON_PX = 0.01f

private fun MathLayoutPass.taggedDisplayReplayDecision(
    range: SourceRange,
    replay: MathTaggedDisplayReplay,
    layoutRole: String,
) = decision(
    "AmsmathTaggedDisplayReplay",
    range,
    "viewportWidthPx" to replay.viewportWidthPx,
    "bodyLogicalX" to replay.bodyLogicalX,
    "bodyLogicalWidthPx" to replay.body.width,
    "bodyVisualLeftPx" to (replay.bodyLogicalX + replay.body.visualLeft),
    "bodyVisualRightPx" to (replay.bodyLogicalX + replay.body.visualRight),
    "tagCount" to replay.tags.size,
    "tagPlacements" to replay.tags.joinToString(",") { it.placement.name },
    "layoutRole" to layoutRole,
    "policy" to "CompletedBodyAndViewportAnchoredTagsReplayIndependently",
)

private fun MathLayoutPass.resolvedEquationTagDisplayWidth(range: SourceRange): Float? {
    val width = displayWidthPx
    if (width == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingEquationTagDisplayWidth,
            "Equation tag layout requires an explicit completed display width",
            range,
        )
    }
    return width
}

private fun MathLayoutPass.equationTagFitDecision(
    bodyX: Float,
    bodyWidth: Float,
    tagX: Float,
    tag: MathEquationTag,
    width: Float,
    layoutRole: String,
    fits: Boolean,
    tagBaselineY: Float,
    placement: String,
) {
    val minimumSeparation = baseFontSizePx / 2f
    decision(
        "AmsmathEquationTagFit",
        tag.range,
        "displayWidthPx" to width,
        "bodyLeftPx" to bodyX,
        "bodyRightPx" to (bodyX + bodyWidth),
        "tagLeftPx" to tagX,
        "minimumSeparationPx" to minimumSeparation,
        "fits" to fits,
        "placement" to placement,
        "tagBaselineY" to tagBaselineY,
        "bodyHorizontalPlacement" to if (bodyWidth <= width) "CenteredWithinDisplay" else "OverfullBodyStartsAtDisplayLeft",
        "tagLineBaselineSkipPx" to if (placement == MathEquationTagPlacement.ShiftedBelowRight.name) {
            baseFontSizePx
        } else {
            0f
        },
        "layoutRole" to layoutRole,
        "policy" to when (placement) {
            MathEquationTagPlacement.CenteredBesideMultiline.name ->
                "ResponsiveDisplayTagCenteredOnCompletedMultilineBody"
            MathEquationTagPlacement.ShiftedBelowRight.name ->
                "ShiftedTagClearsCompletedBodyByHalfEm"
            else -> "AmsmathMinTagSeparationHalfEmSameLine"
        },
    )
}

internal fun MathLayoutPass.layoutEquationTagBox(tag: MathEquationTag, style: MathStyle): MathBox {
    val textStyle = styleForLevel(MathStyleLevel.Text)
    val wrapperLeftRange = SourceRange(tag.commandRange.start, tag.commandRange.start + 1)
    val wrapperRightRange = SourceRange(tag.commandRange.endExclusive - 1, tag.commandRange.endExclusive)
    val segments = if (tag.starred) {
        tag.segments
    } else {
        listOf(MathTextSegment("(", wrapperLeftRange)) + tag.segments +
            MathTextSegment(")", wrapperRightRange)
    }
    return layoutTextSegments(
        segments = segments,
        style = textStyle,
        range = tag.range,
        origin = MathTextOrigin.EquationTag,
    )
}

private fun MathLayoutPass.equationTagDecision(
    tag: MathEquationTag,
    tagBox: MathBox,
    bodyWidth: Float,
    width: Float,
    bodyX: Float,
    tagX: Float,
    tagBaselineY: Float,
    layoutRole: String,
    placement: String = "SameLineRight",
) = decision(
    "AmsmathEquationTag",
    tag.range,
    "text" to tag.text,
    "starred" to tag.starred,
    "commandRange" to tag.commandRange,
    "contentRange" to tag.contentRange,
    "argumentRange" to tag.argumentRange,
    "displayWidthPx" to width,
    "bodyWidthPx" to bodyWidth,
    "bodyX" to bodyX,
    "tagWidthPx" to tagBox.width,
    "tagAscentPx" to tagBox.ascent,
    "tagDescentPx" to tagBox.descent,
    "tagInkTopPx" to tagBox.inkBounds.top,
    "tagInkBottomPx" to tagBox.inkBounds.bottom,
    "tagFaceIds" to tagBox.glyphs.map { it.faceId }.distinct().joinToString(","),
    "tagX" to tagX,
    "tagBaselineY" to tagBaselineY,
    "placement" to placement,
    "tagTextStyle" to MathStyle.Text,
    "wrapperPolicy" to if (tag.starred) "TagStarUnwrapped" else "TagParenthesesFromOperatorsFamily",
    "layoutRole" to layoutRole,
    "policy" to when (placement) {
        MathEquationTagPlacement.SameLineRight.name ->
            "AmsmathDisplayBodyCenteredTagRightAlignedAtHostDisplayWidth"
        MathEquationTagPlacement.CenteredBesideMultiline.name ->
            "ResponsiveDisplayBodyWrappedTagRightAlignedAndVerticallyCentered"
        else -> "AmsmathOverfullEquationBodyPreservedTagShiftedBelowRight"
    },
)

internal fun MathLayoutPass.resolveTeXDimension(dimension: MathTeXDimension, emSizePx: Float): Float {
    val unvalidatedPixels = when (dimension.unit) {
        MathTeXDimensionUnit.Point -> dimension.value * TEX_POINT_TO_PX
        MathTeXDimensionUnit.BigPoint -> dimension.value * BIG_POINT_TO_PX
        MathTeXDimensionUnit.Em -> dimension.value * emSizePx
        MathTeXDimensionUnit.Centimeter -> dimension.value * CSS_PIXELS_PER_INCH / CENTIMETERS_PER_INCH
        MathTeXDimensionUnit.Millimeter -> dimension.value * CSS_PIXELS_PER_INCH / MILLIMETERS_PER_INCH
        MathTeXDimensionUnit.Inch -> dimension.value * CSS_PIXELS_PER_INCH
    }
    decision(
        "TeXExplicitRowSpacing",
        dimension.range,
        "sourceText" to dimension.sourceText,
        "value" to dimension.value,
        "unit" to dimension.unit.sourceName,
        "emSizePx" to emSizePx,
        "resolvedPx" to unvalidatedPixels,
        "policy" to "TeXDimensionAt96CssPixelsPerInch",
    )
    return validatedResolvedDimension(
        sourceText = dimension.sourceText,
        resolvedPx = unvalidatedPixels,
        range = dimension.range,
    ) ?: 0f
}

internal fun MathLayoutPass.wrapTableDelimiters(
    node: MathTable,
    body: MathBox,
    style: MathStyle,
): MathBox {
    val environment = node.environment ?: return body
    val leftIdentity = environment.leftDelimiter
    val rightIdentity = environment.rightDelimiter
    if (leftIdentity == null && rightIdentity == null) return body
    val size = fontSize(style)
    val axisHeight = glyphSource.mathFont.scaleDesignUnits(constants.axisHeight, size)
    val maxAxisDistance = maxOf(body.descent + axisHeight, body.ascent - axisHeight).coerceAtLeast(0f)
    val factorTarget = maxAxisDistance * delimiterFactor / 500f
    val shortfallTarget = 2f * maxAxisDistance - delimiterShortfallPx
    val target = DelimiterTargetEvidence(
        innerCleanAscentPx = body.texCleanBoxMetrics.ascent,
        innerCleanDescentPx = body.texCleanBoxMetrics.descent,
        axisHeightPx = axisHeight,
        maxAxisDistancePx = maxAxisDistance,
        factor = delimiterFactor,
        shortfallPx = delimiterShortfallPx,
        factorTargetPx = factorTarget,
        shortfallTargetPx = shortfallTarget,
        targetPx = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f),
    )
    fun spec(identity: MathDelimiterIdentity, side: MathDelimiterSide): MathDelimiterSpec {
        val range = if (side == MathDelimiterSide.Left) {
            node.beginCommandRange.cover(node.beginNameRange)
        } else {
            node.endCommandRange?.let { command ->
                node.endNameRange?.let(command::cover) ?: command
            } ?: SourceRange(node.range.endExclusive, node.range.endExclusive)
        }
        return MathDelimiterSpec(
            sourceText = node.environmentName,
            identity = identity,
            side = side,
            commandRange = if (side == MathDelimiterSide.Left) node.beginCommandRange else node.endCommandRange ?: range,
            delimiterRange = if (side == MathDelimiterSide.Left) node.beginNameRange else node.endNameRange ?: range,
            range = range,
        )
    }
    val left = leftIdentity?.let { layoutDelimiter(spec(it, MathDelimiterSide.Left), style, target) }
    val right = rightIdentity?.let { layoutDelimiter(spec(it, MathDelimiterSide.Right), style, target) }
    var x = 0f
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val groups = mutableListOf<MathConstructionPaintGroup>()
    fun append(box: MathBox?) {
        if (box == null) return
        val shifted = box.translated(x, 0f)
        glyphs += shifted.glyphs
        hostTextRuns += shifted.hostTextRuns
        rules += shifted.rules
        groups += shifted.constructionPaintGroups
        x += box.width
    }
    append(left)
    append(body)
    append(right)
    val painted = geometryExtents(x, glyphs, rules, node.range, groups, hostTextRuns)
    val children = listOfNotNull(left, body, right)
    return painted.copy(
        ascent = children.maxOfOrNull { it.ascent } ?: 0f,
        descent = children.maxOfOrNull { it.descent } ?: 0f,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = children.maxOfOrNull { it.texCleanBoxMetrics.ascent } ?: 0f,
            descent = children.maxOfOrNull { it.texCleanBoxMetrics.descent } ?: 0f,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = children.flatMap { it.texCleanBoxMetrics.evidence }.toSet() +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
}
