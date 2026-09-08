package org.tiqian.math.layout

import org.tiqian.math.core.*
import kotlin.math.floor
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_MU_PER_EM
import org.tiqian.math.layout.MathLayoutPass.AmsmathArrowFaceEvidence
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride
import org.tiqian.math.layout.MathLayoutPass.MeasurementLayoutNode
import org.tiqian.math.layout.MathLayoutPass.OperatorLimitsSemantics
import org.tiqian.math.layout.MathLayoutPass.StackedLimitsPlacement

internal fun MathLayoutPass.layoutOperatorScripts(
    node: MathScripts,
    operator: MathOperator,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val base = layoutOperator(operator, style, alphabetOverride)
    return layoutScriptsWithOperatorLimits(
        node = node,
        base = base,
        semantics = OperatorLimitsSemantics(
            identity = operator.identity.debugName,
            declaredPolicy = operator.limitsPolicy,
            explicit = operator.hasExplicitLimitsPolicy,
            modifierRange = operator.limitsModifierRange,
            sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.XeTeXOperatorNoLimits,
            sideScriptGeometry = "XeTeXMakeOpWidthDeltaPlusSharedSideScriptKernel",
        ),
        style = style,
        alphabetOverride = alphabetOverride,
    )
}

internal fun MathLayoutPass.layoutOperatorNameScripts(
    node: MathScripts,
    operator: MathOperatorName,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode = layoutScriptsWithOperatorLimits(
    node = node,
    base = layoutOperatorName(operator, style, alphabetOverride),
    semantics = OperatorLimitsSemantics(
        identity = "operator-name:${operator.name}",
        declaredPolicy = operator.limitsPolicy,
        explicit = operator.hasExplicitLimitsPolicy,
        modifierRange = operator.limitsModifierRange,
        sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
        sideScriptGeometry = "UprightOperatorNamePlusSharedSideScriptKernel",
    ),
    style = style,
    alphabetOverride = alphabetOverride,
)

internal fun MathLayoutPass.layoutOperatorNoadScripts(
    node: MathScripts,
    operator: MathOperatorNoad,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode = layoutScriptsWithOperatorLimits(
    node = node,
    base = layoutOperatorNoad(operator, style, alphabetOverride),
    semantics = OperatorLimitsSemantics(
        identity = "mathop",
        declaredPolicy = operator.limitsPolicy,
        explicit = operator.hasExplicitLimitsPolicy,
        modifierRange = operator.limitsModifierRange,
        sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
        sideScriptGeometry = "XeTeXSubMlistOperatorPlusSharedSideScriptKernel",
    ),
    style = style,
    alphabetOverride = alphabetOverride,
)

internal fun MathLayoutPass.layoutBraceNoadScripts(
    node: MathScripts,
    brace: MathBraceNoad,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode = layoutScriptsWithOperatorLimits(
    node = node,
    base = layoutBraceNoad(brace, style, alphabetOverride),
    semantics = OperatorLimitsSemantics(
        identity = "${brace.kind.name.lowercase()}brace",
        declaredPolicy = brace.limitsPolicy,
        explicit = brace.hasExplicitLimitsPolicy,
        modifierRange = brace.limitsModifierRange,
        sideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
        sideScriptGeometry = "XeTeXBraceAccentOperatorPlusSharedSideScriptKernel",
    ),
    style = style,
    alphabetOverride = alphabetOverride,
)

private fun MathLayoutPass.layoutScriptsWithOperatorLimits(
    node: MathScripts,
    base: LaidNode,
    semantics: OperatorLimitsSemantics,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val effectivePolicy = when (semantics.declaredPolicy) {
        MathLimitsPolicy.Limits -> MathLimitsPolicy.Limits
        MathLimitsPolicy.NoLimits -> MathLimitsPolicy.NoLimits
        MathLimitsPolicy.Auto -> if (style.level == MathStyleLevel.Display) {
            MathLimitsPolicy.Limits
        } else {
            MathLimitsPolicy.NoLimits
        }
    }
    val reason = when {
        semantics.explicit -> "explicit-postfix-modifier"
        semantics.declaredPolicy == MathLimitsPolicy.Auto && style.level == MathStyleLevel.Display -> "auto-display"
        semantics.declaredPolicy == MathLimitsPolicy.Auto -> "auto-non-display"
        else -> "plain-tex-operator-default"
    }
    decision(
        "TeXOperatorLimitsPolicy",
        node.range,
        "identity" to semantics.identity,
        "declaredPolicy" to semantics.declaredPolicy,
        "effectivePolicy" to effectivePolicy,
        "explicit" to semantics.explicit,
        "modifierRange" to semantics.modifierRange,
        "style" to style,
        "reason" to reason,
        "upperPresent" to (node.superscript != null),
        "lowerPresent" to (node.subscript != null),
    )
    return if (effectivePolicy == MathLimitsPolicy.Limits) {
        layoutStackedOperatorLimits(node, base, style, alphabetOverride)
    } else {
        decision(
            "TeXOperatorSideScripts",
            node.range,
            "identity" to semantics.identity,
            "style" to style,
            "geometry" to semantics.sideScriptGeometry,
            "italicCorrectionDeltaPx" to base.italicCorrectionPx,
            "subscriptPresent" to (node.subscript != null),
            "makeOpWidthReductionPx" to if (node.subscript != null) base.italicCorrectionPx else 0f,
        )
        layoutScriptsWithBase(
            node,
            base,
            style,
            alphabetOverride,
            semantics.sideScriptHorizontalPolicy,
        )
    }
}

private fun MathLayoutPass.layoutStackedOperatorLimits(
    node: MathScripts,
    rawBase: LaidNode,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val base = rawBase.copy(box = rawBase.box.completedTeXBox())
    val upper = node.superscript
        ?.let { layoutNode(it, style.superscript(), alphabetOverride) }
        ?.completedTeXMathField()
    val lower = node.subscript
        ?.let { layoutNode(it, style.subscript(), alphabetOverride) }
        ?.completedTeXMathField()
    val placement = placeStackedLimits(base, upper, lower, style, base.italicCorrectionPx, node.range)
    decision(
        "OpenTypeMathOperatorLimits",
        node.range,
        "style" to style,
        "upperStyle" to upper?.style,
        "lowerStyle" to lower?.style,
        "upperLimitGapMinPx" to placement.upperGapMin,
        "upperLimitBaselineRiseMinPx" to placement.upperBaselineRiseMin,
        "lowerLimitGapMinPx" to placement.lowerGapMin,
        "lowerLimitBaselineDropMinPx" to placement.lowerBaselineDropMin,
        "upperOuterPaddingPx" to placement.upperOuterPadding,
        "lowerOuterPaddingPx" to placement.lowerOuterPadding,
        "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
        "upperShiftPx" to placement.upperShift,
        "lowerShiftPx" to placement.lowerShift,
        "actualUpperGapPx" to placement.actualUpperGap,
        "actualUpperBaselineRisePx" to placement.actualUpperRise,
        "actualLowerGapPx" to placement.actualLowerGap,
        "actualLowerBaselineDropPx" to placement.actualLowerDrop,
        "operatorItalicCorrectionPx" to base.italicCorrectionPx,
        "upperCenterOffsetPx" to placement.halfItalicCorrection,
        "lowerCenterOffsetPx" to -placement.halfItalicCorrection,
        "logicalWidthPx" to placement.logicalWidth,
        "logicalWidthPolicy" to "max-unskewed-operator-and-limits",
        "operatorWidthPx" to base.box.width,
        "upperWidthPx" to upper?.box?.width,
        "lowerWidthPx" to lower?.box?.width,
        "operatorX" to placement.baseX,
        "upperX" to placement.upperX,
        "lowerX" to placement.lowerX,
    )
    return LaidNode(
        node = node,
        box = placement.box,
        atomClass = MathAtomClass.Operator,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutOverUnder(
    node: MathOverUnder,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val base = layoutNode(node.base, style, alphabetOverride)
        .let { it.copy(box = it.box.completedTeXBox()) }
    val annotationStyle = when (node.kind) {
        MathOverUnderKind.Underset -> style.subscript()
        MathOverUnderKind.Overset, MathOverUnderKind.StackRel -> style.superscript()
    }
    val annotation = layoutNode(node.annotation, annotationStyle, alphabetOverride)
        .completedTeXMathField()
    val upper = annotation.takeIf { node.kind != MathOverUnderKind.Underset }
    val lower = annotation.takeIf { node.kind == MathOverUnderKind.Underset }
    // amsmath overset/underset suppress operator slant; stackrel retains TeX mathop skew.
    val italicCorrection = if (node.kind == MathOverUnderKind.StackRel) base.italicCorrectionPx else 0f
    val placement = placeStackedLimits(base, upper, lower, style, italicCorrection, node.range)
    decision(
        "TeXOverUnderNoad",
        node.range,
        "kind" to node.kind,
        "atomClass" to node.atomClass,
        "style" to style,
        "annotationStyle" to annotationStyle,
        "commandRange" to node.commandRange,
        "baseRange" to node.base.range,
        "annotationRange" to node.annotation.range,
        "upperLimitGapMinPx" to placement.upperGapMin,
        "upperLimitBaselineRiseMinPx" to placement.upperBaselineRiseMin,
        "lowerLimitGapMinPx" to placement.lowerGapMin,
        "lowerLimitBaselineDropMinPx" to placement.lowerBaselineDropMin,
        "upperOuterPaddingPx" to placement.upperOuterPadding,
        "lowerOuterPaddingPx" to placement.lowerOuterPadding,
        "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
        "upperShiftPx" to placement.upperShift,
        "lowerShiftPx" to placement.lowerShift,
        "actualUpperGapPx" to placement.actualUpperGap,
        "actualLowerGapPx" to placement.actualLowerGap,
        "baseItalicCorrectionPx" to base.italicCorrectionPx,
        "usedItalicCorrectionPx" to italicCorrection,
        "logicalWidthPx" to placement.logicalWidth,
        "baseX" to placement.baseX,
        "annotationX" to (placement.upperX ?: placement.lowerX),
        "geometryKernel" to "OpenTypeMathUpperLowerLimitConstants",
        "baseShiftPolicy" to if (node.kind == MathOverUnderKind.StackRel) {
            "TeXMathOpRelationBase"
        } else {
            "AmsmathSuppressBaseShift"
        },
    )
    return LaidNode(
        node = node,
        box = placement.box,
        atomClass = node.atomClass,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.layoutForMeasurement(
    node: MathNode,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): MeasurementLayoutNode {
    val decisionStart = decisions.size
    val diagnosticStart = diagnostics.size
    val constructionGroupStart = nextConstructionPaintGroupId
    val laid = layoutNode(node, style, alphabetOverride).completedTeXMathField()
    val measurementDecisions = decisions.subList(decisionStart, decisions.size).toList()
    val measurementDiagnostics = diagnostics.subList(diagnosticStart, diagnostics.size).toList()
    decisions.subList(decisionStart, decisions.size).clear()
    diagnostics.subList(diagnosticStart, diagnostics.size).clear()
    nextConstructionPaintGroupId = constructionGroupStart
    return MeasurementLayoutNode(laid, measurementDiagnostics, measurementDecisions)
}

internal fun MathLayoutPass.layoutExtensibleArrow(
    node: MathExtensibleArrow,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val upperStyle = style.superscript()
    val lowerStyle = style.subscript()
    val upper = layoutNode(node.above, upperStyle, alphabetOverride)
        .completedTeXMathField()
    val lower = node.below?.let { below ->
        layoutNode(below, lowerStyle, alphabetOverride).completedTeXMathField()
    }
    // amsmath measures both labels in a fresh scriptstyle box, even when the arrow itself is
    // nested in Script/ScriptScript. The measurement is not painted and must not leak its
    // decisions or construction ownership into the production result.
    val measuredUpper = layoutForMeasurement(node.above, MathStyle.Script, alphabetOverride)
    val measuredLower = node.below?.let { layoutForMeasurement(it, MathStyle.Script, alphabetOverride) }
    (measuredUpper.diagnostics + measuredLower?.diagnostics.orEmpty()).forEach { diagnostic ->
        if (diagnostic !in diagnostics) diagnostics += diagnostic
    }

    val arrowStyle = MathStyle.Display
    val arrowSize = fontSize(arrowStyle)
    val measurementMu = fontSize(MathStyle.Script) / TEX_MU_PER_EM
    val measureLeftMu = if (node.identity == MathExtensibleArrowIdentity.Right) 5f else 9f
    val measureRightMu = if (node.identity == MathExtensibleArrowIdentity.Right) 9f else 5f
    val measuredLabelWidth = max(
        measuredUpper.node.box.width,
        measuredLower?.node?.box?.width ?: 0f,
    )
    val labelTargetWidth = measuredLabelWidth + (measureLeftMu + measureRightMu) * measurementMu

    val arrowHeadCandidates = constructionBaseCandidates(
        scalarString(node.identity.arrowHeadScalar), arrowSize, node.commandRange,
    )
    val relbarCandidates = constructionBaseCandidates(AMSMATH_RELBAR, arrowSize, node.commandRange)
    val faceEvidence = arrowHeadCandidates.firstNotNullOfOrNull { head ->
        val headGlyph = head.run.glyphs.singleOrNull()
        if (head.run.missingGlyph || headGlyph == null) return@firstNotNullOfOrNull null
        relbarCandidates.firstOrNull { bar ->
            val barGlyph = bar.run.glyphs.singleOrNull()
            !bar.run.missingGlyph && barGlyph != null && barGlyph.faceId == headGlyph.faceId
        }?.let { bar -> AmsmathArrowFaceEvidence(headGlyph.faceId, head, bar) }
    }
    if (faceEvidence == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "${node.identity.debugName} requires one math face containing both the arrow head and U+2212 relbar",
            node.commandRange,
        )
        return LaidNode(
            node = node,
            box = emptyBox(node.range),
            atomClass = MathAtomClass.Relation,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }

    val headRun = faceEvidence.head.run
    val relbarRun = faceEvidence.relbar.run
    val headGlyph = headRun.glyphs.single()
    val relbarGlyph = relbarRun.glyphs.single()
    val fillMu = arrowSize / TEX_MU_PER_EM
    val endpointOverlap = 7f * fillMu
    val leaderInnerOverlap = 2f * fillMu
    val leftRun = if (node.identity == MathExtensibleArrowIdentity.Left) headRun else relbarRun
    val rightRun = if (node.identity == MathExtensibleArrowIdentity.Right) headRun else relbarRun
    val leftGlyph = leftRun.glyphs.single()
    val rightGlyph = rightRun.glyphs.single()
    val naturalFillWidth = leftRun.width + rightRun.width - 2f * endpointOverlap
    val leaderBoxWidth = relbarRun.width - 2f * leaderInnerOverlap
    if (naturalFillWidth < 0f || leaderBoxWidth <= 0f) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.InvalidExtensibleArrowFill,
            "${node.identity.debugName} cannot form positive amsmath leader geometry from the selected glyph advances",
            node.commandRange,
        )
    }
    val targetWidth = max(naturalFillWidth.coerceAtLeast(0f), labelTargetWidth)
    val resolvedTargetWidth = validatedResolvedDimension(
        sourceText = "extensibleArrowTargetWidthPx",
        resolvedPx = targetWidth,
        range = node.commandRange,
    ) ?: return LaidNode(
        node = node,
        box = emptyBox(node.range),
        atomClass = MathAtomClass.Relation,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
    val leaderGlueWidth = (resolvedTargetWidth - naturalFillWidth).coerceAtLeast(0f)
    val requestedLeaderCount = if (leaderBoxWidth > 0f) {
        floor((leaderGlueWidth / leaderBoxWidth).toDouble())
    } else {
        0.0
    }
    val requestedLeaderCountLong = if (
        requestedLeaderCount.isFinite() && requestedLeaderCount <= Long.MAX_VALUE.toDouble()
    ) {
        requestedLeaderCount.toLong()
    } else {
        Long.MAX_VALUE
    }
    if (!consumeExtenders(requestedLeaderCountLong, node.commandRange)) {
        return LaidNode(
            node = node,
            box = emptyBox(node.range),
            atomClass = MathAtomClass.Relation,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }
    val leaderCount = requestedLeaderCountLong.toInt()
    val centeredLeaderRemainder = if (leaderCount > 0) {
        (leaderGlueWidth - leaderCount * leaderBoxWidth) / 2f
    } else {
        leaderGlueWidth / 2f
    }
    val leaderGlueStart = leftRun.width - endpointOverlap
    val leaderOrigins = List(leaderCount) { index ->
        leaderGlueStart + centeredLeaderRemainder + index * leaderBoxWidth - leaderInnerOverlap
    }
    val rightOrigin = resolvedTargetWidth - rightRun.width
    val group = MathConstructionPaintGroup(
        id = nextConstructionPaintGroupId++,
        kind = MathConstructionPaintKind.ExtensibleArrow,
        shapeKind = MathConstructionShapeKind.Assembly,
        sourceRange = node.commandRange,
        outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
        faceId = faceEvidence.faceId,
    )
    fun placement(glyph: MeasuredMathGlyph, x: Float): MathGlyphPlacement = MathGlyphPlacement(
        glyphId = glyph.glyphId,
        x = x,
        baselineY = 0f,
        advance = glyph.advance,
        inkBounds = glyph.inkBounds.translated(x, 0f),
        fontSizePx = arrowSize,
        sourceRange = node.commandRange,
        style = arrowStyle,
        constructionGroupId = group.id,
        faceId = glyph.faceId,
        fontClass = glyph.fontClass,
        requestedWeight = glyph.requestedWeight,
        resolvedWeight = glyph.resolvedWeight,
        fallbackReason = glyph.fallbackReason,
    )
    val arrowGlyphs = buildList {
        add(placement(leftGlyph, 0f))
        leaderOrigins.forEach { add(placement(relbarGlyph, it)) }
        add(placement(rightGlyph, rightOrigin))
    }
    val outlinesReplayable = faceEvidence.head.outlineCapability == MathConstructionOutlineCapability.Replayable &&
        faceEvidence.relbar.outlineCapability == MathConstructionOutlineCapability.Replayable
    if (!outlinesReplayable) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingConstructionOutlineEvidence,
            "The math font adapter cannot replay the complete ${node.identity.debugName} arrow fill",
            node.commandRange,
        )
    }
    val arrowInkBox = geometryExtents(
        targetWidth,
        arrowGlyphs,
        emptyList(),
        node.commandRange,
        listOf(group),
    )
    // amsmath smashes every relbar vertically; only the terminal arrow head contributes the
    // nucleus height/depth. Painted relbar ink remains in inkBounds and in the semantic union.
    val arrowBox = arrowInkBox.copy(
        ascent = headRun.ascent,
        descent = headRun.descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = headRun.ascent,
            descent = headRun.descent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = setOf(MathTeXCleanBoxEvidence.GlyphOutline),
        ),
    )
    val arrowBase = LaidNode(
        node = node,
        box = arrowBox,
        atomClass = MathAtomClass.Operator,
        italicCorrectionPx = 0f,
        style = arrowStyle,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
    fun paddedLimit(limit: LaidNode?, limitStyle: MathStyle): LaidNode? = limit?.let {
        val threeMu = 3f * fontSize(limitStyle) / TEX_MU_PER_EM
        val leftKern = if (node.identity == MathExtensibleArrowIdentity.Left) threeMu else 0f
        val rightKern = if (node.identity == MathExtensibleArrowIdentity.Right) threeMu else 0f
        it.copy(box = it.box.withHorizontalKerns(leftKern, rightKern))
    }
    val paddedUpper = paddedLimit(upper, upperStyle)
    val paddedLower = paddedLimit(lower, lowerStyle)
    val placement = placeStackedLimits(arrowBase, paddedUpper, paddedLower, style, 0f, node.range)
    decision(
        "AmsmathXeTeXExtensibleArrow",
        node.range,
        "identity" to node.identity,
        "atomClass" to node.atomClass,
        "style" to style,
        "arrowMeasurementStyle" to arrowStyle,
        "labelMeasurementStyle" to MathStyle.Script,
        "upperStyle" to upperStyle,
        "lowerStyle" to if (lower == null) null else lowerStyle,
        "commandRange" to node.commandRange,
        "upperRange" to node.above.range,
        "lowerRange" to node.belowRange,
        "arrowFontSizePx" to arrowSize,
        "faceId" to faceEvidence.faceId,
        "arrowHeadScalar" to unicodeLabel(node.identity.arrowHeadScalar),
        "arrowHeadGlyphId" to headGlyph.glyphId,
        "relbarScalar" to "U+2212",
        "relbarGlyphId" to relbarGlyph.glyphId,
        "measuredUpperWidthPx" to measuredUpper.node.box.width,
        "measuredLowerWidthPx" to measuredLower?.node?.box?.width,
        "measuredUpperGlyphs" to measuredUpper.node.box.glyphs.joinToString(",") {
            "${it.glyphId}@${it.fontSizePx}:${it.advance}"
        },
        "measurementScriptPlacements" to
            (measuredUpper.decisions + measuredLower?.decisions.orEmpty())
                .filter { it.name == "OpenTypeMathScriptPlacement" }
                .joinToString("|") { it.details.toString() },
        "measurementMuPx" to measurementMu,
        "measurementLeftPaddingMu" to measureLeftMu,
        "measurementRightPaddingMu" to measureRightMu,
        "labelTargetWidthPx" to labelTargetWidth,
        "naturalFillWidthPx" to naturalFillWidth,
        "targetWidthPx" to targetWidth,
        "targetPolicy" to "AmsmathExtArrowMeasureScriptLabelsAndDisplayArrowFill",
        "fillMuPx" to fillMu,
        "endpointOverlapMu" to 7,
        "leaderInnerOverlapMu" to 2,
        "leaderBoxWidthPx" to leaderBoxWidth,
        "leaderGlueWidthPx" to leaderGlueWidth,
        "leaderCount" to leaderCount,
        "leaderCenteredRemainderPx" to centeredLeaderRemainder,
        "leaderOriginsPx" to leaderOrigins.joinToString(","),
        "rightEndpointOriginPx" to rightOrigin,
        "fillPolicy" to "AmsmathArrowfillCLeadersRelbar",
        "relbarVerticalPolicy" to "AmsmathMathSmash",
        "upperLimitGapMinPx" to placement.upperGapMin,
        "lowerLimitGapMinPx" to placement.lowerGapMin,
        "upperBaselineRiseMinPx" to placement.upperBaselineRiseMin,
        "lowerBaselineDropMinPx" to placement.lowerBaselineDropMin,
        "upperOuterPaddingPx" to placement.upperOuterPadding,
        "lowerOuterPaddingPx" to placement.lowerOuterPadding,
        "outerLimitPaddingPolicy" to "XeTeXBigOpSpacing5FromOpenTypeMATHStackGapMin",
        "upperShiftPx" to placement.upperShift,
        "lowerShiftPx" to placement.lowerShift,
        "upperX" to placement.upperX,
        "lowerX" to placement.lowerX,
        "logicalWidthPx" to placement.box.width,
        "logicalAscentPx" to placement.box.ascent,
        "logicalDescentPx" to placement.box.descent,
        "paintGroupId" to group.id,
        "outlineReplayable" to outlinesReplayable,
    )
    return LaidNode(
        node = node,
        box = placement.box,
        atomClass = MathAtomClass.Relation,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.placeStackedLimits(
    base: LaidNode,
    upper: LaidNode?,
    lower: LaidNode?,
    style: MathStyle,
    italicCorrectionPx: Float,
    range: SourceRange,
): StackedLimitsPlacement {
    val upperGapMin = scale(constants.upperLimitGapMin, style)
    val upperBaselineRiseMin = scale(constants.upperLimitBaselineRiseMin, style)
    val lowerGapMin = scale(constants.lowerLimitGapMin, style)
    val lowerBaselineDropMin = scale(constants.lowerLimitBaselineDropMin, style)
    // XeTeX maps legacy `big_op_spacing5` to OpenType MATH StackGapMin. This is
    // logical padding outside the top/bottom limit, not a translation of painted content.
    val outerLimitPadding = scale(constants.stackGapMin, style)
    val upperOuterPadding = if (upper == null) 0f else outerLimitPadding
    val lowerOuterPadding = if (lower == null) 0f else outerLimitPadding
    val upperShift = upper?.let {
        base.box.ascent + max(upperBaselineRiseMin, it.box.descent + upperGapMin)
    }
    val lowerShift = lower?.let {
        base.box.descent + max(lowerBaselineDropMin, it.box.ascent + lowerGapMin)
    }
    val halfItalicCorrection = italicCorrectionPx / 2f
    val logicalWidth = maxOf(base.box.width, upper?.box?.width ?: 0f, lower?.box?.width ?: 0f)
    val baseX = (logicalWidth - base.box.width) / 2f
    val upperX = upper?.let { (logicalWidth - it.box.width) / 2f + halfItalicCorrection }
    val lowerX = lower?.let { (logicalWidth - it.box.width) / 2f - halfItalicCorrection }
    val shiftedBase = base.box.translated(baseX, 0f)
    val shiftedUpper = upper?.let { it.box.translated(upperX!!, -upperShift!!) }
    val shiftedLower = lower?.let { it.box.translated(lowerX!!, lowerShift!!) }
    val unpaddedBox = geometryExtentsPreservingLogicalChildren(
        logicalWidth,
        shiftedBase.glyphs + shiftedUpper?.glyphs.orEmpty() + shiftedLower?.glyphs.orEmpty(),
        shiftedBase.rules + shiftedUpper?.rules.orEmpty() + shiftedLower?.rules.orEmpty(),
        range,
        buildList {
            add(base.box to 0f)
            upper?.let { add(it.box to -upperShift!!) }
            lower?.let { add(it.box to lowerShift!!) }
        },
        hostTextRuns = shiftedBase.hostTextRuns + shiftedUpper?.hostTextRuns.orEmpty() +
            shiftedLower?.hostTextRuns.orEmpty(),
    )
    val box = unpaddedBox.copy(
        ascent = unpaddedBox.ascent + upperOuterPadding,
        descent = unpaddedBox.descent + lowerOuterPadding,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = unpaddedBox.texCleanBoxMetrics.ascent + upperOuterPadding,
            descent = unpaddedBox.texCleanBoxMetrics.descent + lowerOuterPadding,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = unpaddedBox.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    return StackedLimitsPlacement(
        box = box,
        base = base,
        upper = upper,
        lower = lower,
        upperGapMin = upperGapMin,
        upperBaselineRiseMin = upperBaselineRiseMin,
        lowerGapMin = lowerGapMin,
        lowerBaselineDropMin = lowerBaselineDropMin,
        upperOuterPadding = upperOuterPadding,
        lowerOuterPadding = lowerOuterPadding,
        upperShift = upperShift,
        lowerShift = lowerShift,
        halfItalicCorrection = halfItalicCorrection,
        logicalWidth = logicalWidth,
        baseX = baseX,
        upperX = upperX,
        lowerX = lowerX,
    )
}
