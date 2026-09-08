package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathKernCorner
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutScripts(node: MathScripts, style: MathStyle, alphabetOverride: MathAlphabetOverride?): LaidNode =
    layoutScriptsWithBase(node, layoutNode(node.base, style, alphabetOverride), style, alphabetOverride)

internal fun MathLayoutPass.layoutScriptsWithBase(
    node: MathScripts,
    rawBase: LaidNode,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
    horizontalPolicy: SideScriptHorizontalPolicy = SideScriptHorizontalPolicy.OrdinaryNucleus,
): LaidNode {
    val base = rawBase.withNativeOutlineBoxForSideScriptPlacement()
    val superscript = node.superscript
        ?.let { layoutNode(it, style.superscript(), alphabetOverride) }
        ?.withNativeOutlineBoxForSideScriptPlacement()
    val subscript = node.subscript
        ?.let { layoutNode(it, style.subscript(), alphabetOverride) }
        ?.withNativeOutlineBoxForSideScriptPlacement()
    val standardSuperscriptShift = scale(
        if (style.cramped) constants.superscriptShiftUpCramped else constants.superscriptShiftUp,
        style,
    )
    val standardSubscriptShift = scale(constants.subscriptShiftDown, style)
    val appliesBaselineDrop = base.scriptBaseKind != ScriptBaseKind.Character
    val basePaintedMetrics = base.box.sideScriptVerticalMetrics()
    val superscriptPaintedMetrics = superscript?.box?.sideScriptVerticalMetrics()
    val subscriptPaintedMetrics = subscript?.box?.sideScriptVerticalMetrics()
    val baseVerticalMetrics = base.box.texCleanSideScriptVerticalMetrics()
    val superscriptVerticalMetrics = superscript?.box?.texCleanSideScriptVerticalMetrics()
    val subscriptVerticalMetrics = subscript?.box?.texCleanSideScriptVerticalMetrics()
    val verticalConstraints = SideScriptVerticalConstraints(
        superscriptShiftUpPx = standardSuperscriptShift,
        subscriptShiftDownPx = standardSubscriptShift,
        superscriptBottomMinPx = scale(constants.superscriptBottomMin, style),
        superscriptBaselineDropMaxPx = scale(constants.superscriptBaselineDropMax, style.superscript()),
        subscriptTopMaxPx = scale(constants.subscriptTopMax, style),
        subscriptBaselineDropMinPx = scale(constants.subscriptBaselineDropMin, style.subscript()),
        subSuperscriptGapMinPx = scale(constants.subSuperscriptGapMin, style),
        superscriptBottomMaxWithSubscriptPx = scale(constants.superscriptBottomMaxWithSubscript, style),
    )
    val verticalPlacement = resolveSideScriptVerticalPlacement(
        base = baseVerticalMetrics,
        superscript = superscriptVerticalMetrics,
        subscript = subscriptVerticalMetrics,
        appliesBaselineDrop = appliesBaselineDrop,
        constraints = verticalConstraints,
    )
    val superscriptShift = verticalPlacement.superscriptShiftPx
    val subscriptShift = verticalPlacement.subscriptShiftPx

    val superscriptKern = superscript?.let { superscriptMathKern(base, it, superscriptShift, node.range) } ?: 0f
    val subscriptKern = subscript?.let { subscriptMathKern(base, it, subscriptShift, node.range) } ?: 0f
    // XeTeX appends the fixed `\scriptspace` to each completed script field. Adding the
    // same fixed amount after the maximum script edge is algebraically identical and avoids
    // duplicating logical padding when both scripts are present.
    val spaceAfterScript = scriptSpacePx
    val superscriptFieldWidth = superscript?.completedTeXScriptFieldWidth()
    val subscriptFieldWidth = subscript?.completedTeXScriptFieldWidth()
    val horizontalPlacement = resolveSideScriptHorizontalPlacement(
        baseWidthPx = base.box.width,
        italicCorrectionPx = base.italicCorrectionPx,
        superscriptWidthPx = superscriptFieldWidth,
        subscriptWidthPx = subscriptFieldWidth,
        superscriptKernPx = superscriptKern,
        subscriptKernPx = subscriptKern,
        spaceAfterScriptPx = spaceAfterScript,
        policy = horizontalPolicy,
    )
    val superscriptX = horizontalPlacement.superscriptXPx
    val subscriptX = horizontalPlacement.subscriptXPx
    val glyphs = buildList {
        addAll(base.box.glyphs)
        superscript?.let { addAll(it.box.translated(checkNotNull(superscriptX), -superscriptShift).glyphs) }
        subscript?.let { addAll(it.box.translated(checkNotNull(subscriptX), subscriptShift).glyphs) }
    }
    val rules = buildList {
        addAll(base.box.rules)
        superscript?.let { addAll(it.box.translated(checkNotNull(superscriptX), -superscriptShift).rules) }
        subscript?.let { addAll(it.box.translated(checkNotNull(subscriptX), subscriptShift).rules) }
    }
    val hostTextRuns = buildList {
        addAll(base.box.hostTextRuns)
        superscript?.let {
            addAll(it.box.translated(checkNotNull(superscriptX), -superscriptShift).hostTextRuns)
        }
        subscript?.let {
            addAll(it.box.translated(checkNotNull(subscriptX), subscriptShift).hostTextRuns)
        }
    }
    val width = horizontalPlacement.logicalWidthPx
    decision(
        "OpenTypeMathScriptPlacement",
        node.range,
        "style" to style,
        "superscriptStyle" to superscript?.style,
        "subscriptStyle" to subscript?.style,
        "superscriptShiftPx" to superscriptShift,
        "subscriptShiftPx" to subscriptShift,
        "superscriptKernPx" to superscriptKern,
        "subscriptKernPx" to subscriptKern,
        "horizontalPlacementPolicy" to horizontalPlacement.policy,
        "baseOriginalLogicalWidthPx" to horizontalPlacement.originalBaseWidthPx,
        "superscriptLogicalWidthPx" to superscriptFieldWidth,
        "subscriptLogicalWidthPx" to subscriptFieldWidth,
        "superscriptTerminalItalicCorrectionPx" to superscript?.italicCorrectionPx?.coerceAtLeast(0f),
        "subscriptTerminalItalicCorrectionPx" to subscript?.italicCorrectionPx?.coerceAtLeast(0f),
        "childLogicalAdvancePolicy" to "XeTeXCleanScriptFieldIncludingTerminalItalicCorrection",
        "italicCorrectionDeltaPx" to horizontalPlacement.italicCorrectionDeltaPx,
        "operatorWidthReductionPx" to horizontalPlacement.operatorWidthReductionPx,
        "nucleusLogicalWidthPx" to horizontalPlacement.nucleusLogicalWidthPx,
        "superscriptItalicDeltaPx" to horizontalPlacement.superscriptItalicDeltaPx,
        "superscriptXPx" to superscriptX,
        "subscriptXPx" to subscriptX,
        "logicalWidthPx" to width,
        "spaceAfterScriptPx" to spaceAfterScript,
        "spaceAfterScriptPolicy" to scriptSpacePolicy,
        "baseKind" to base.scriptBaseKind,
        "superscriptKind" to superscript?.scriptBaseKind,
        "subscriptKind" to subscript?.scriptBaseKind,
        "baselineDropApplied" to appliesBaselineDrop,
        "verticalPlacementMetricPolicy" to
            "XeTeXNativeGlyphOutlineOrCompletedChildBoxForOrdinarySideScripts",
        "logicalReservePolicy" to "ExactOutlinePlacementWithCompletedChildBoxes",
        "baseLogicalAscentPx" to baseVerticalMetrics.logicalAscentPx,
        "baseLogicalDescentPx" to baseVerticalMetrics.logicalDescentPx,
        "baseInkTopPx" to baseVerticalMetrics.inkTopPx,
        "baseInkBottomPx" to baseVerticalMetrics.inkBottomPx,
        "baseInkAscentPx" to baseVerticalMetrics.inkAscentPx,
        "baseInkDescentPx" to baseVerticalMetrics.inkDescentPx,
        "basePaintedInkTopPx" to basePaintedMetrics.inkTopPx,
        "basePaintedInkBottomPx" to basePaintedMetrics.inkBottomPx,
        "superscriptLogicalAscentPx" to superscriptVerticalMetrics?.logicalAscentPx,
        "superscriptLogicalDescentPx" to superscriptVerticalMetrics?.logicalDescentPx,
        "superscriptInkTopPx" to superscriptVerticalMetrics?.inkTopPx,
        "superscriptInkBottomPx" to superscriptVerticalMetrics?.inkBottomPx,
        "superscriptInkAscentPx" to superscriptVerticalMetrics?.inkAscentPx,
        "superscriptInkDescentPx" to superscriptVerticalMetrics?.inkDescentPx,
        "superscriptPaintedInkTopPx" to superscriptPaintedMetrics?.inkTopPx,
        "superscriptPaintedInkBottomPx" to superscriptPaintedMetrics?.inkBottomPx,
        "subscriptLogicalAscentPx" to subscriptVerticalMetrics?.logicalAscentPx,
        "subscriptLogicalDescentPx" to subscriptVerticalMetrics?.logicalDescentPx,
        "subscriptInkTopPx" to subscriptVerticalMetrics?.inkTopPx,
        "subscriptInkBottomPx" to subscriptVerticalMetrics?.inkBottomPx,
        "subscriptInkAscentPx" to subscriptVerticalMetrics?.inkAscentPx,
        "subscriptInkDescentPx" to subscriptVerticalMetrics?.inkDescentPx,
        "subscriptPaintedInkTopPx" to subscriptPaintedMetrics?.inkTopPx,
        "subscriptPaintedInkBottomPx" to subscriptPaintedMetrics?.inkBottomPx,
        "standardSuperscriptShiftUpPx" to standardSuperscriptShift,
        "standardSubscriptShiftDownPx" to standardSubscriptShift,
        "superscriptBottomMinPx" to verticalConstraints.superscriptBottomMinPx,
        "superscriptBaselineDropMaxPx" to verticalConstraints.superscriptBaselineDropMaxPx,
        "superscriptBaselineDropStyle" to style.superscript(),
        "subscriptTopMaxPx" to verticalConstraints.subscriptTopMaxPx,
        "subscriptBaselineDropMinPx" to verticalConstraints.subscriptBaselineDropMinPx,
        "subscriptBaselineDropStyle" to style.subscript(),
        "subSuperscriptGapMinPx" to verticalConstraints.subSuperscriptGapMinPx,
        "superscriptBottomMaxWithSubscriptPx" to
            verticalConstraints.superscriptBottomMaxWithSubscriptPx,
        "superscriptShiftBeforePairGapPx" to verticalPlacement.superscriptShiftBeforePairGapPx,
        "subscriptShiftBeforePairGapPx" to verticalPlacement.subscriptShiftBeforePairGapPx,
        "pairGapBeforeAdjustmentPx" to verticalPlacement.pairGapBeforeAdjustmentPx,
        "pairGapDeficitPx" to verticalPlacement.pairGapDeficitPx,
        "superscriptPairGapMovePx" to verticalPlacement.superscriptPairGapMovePx,
        "subscriptPairGapMovePx" to verticalPlacement.subscriptPairGapMovePx,
        "finalInkGapPx" to verticalPlacement.finalInkGapPx,
        "superscriptInkBottomAfterShiftPx" to superscriptVerticalMetrics?.let {
            -superscriptShift + it.inkBottomPx
        },
        "subscriptInkTopAfterShiftPx" to subscriptVerticalMetrics?.let {
            subscriptShift + it.inkTopPx
        },
        "texCleanBoxPlacementPolicy" to "SamePlacementAsPaintedGlyphs",
        "texCleanBoxSuperscriptShiftPx" to superscriptShift,
        "texCleanBoxSubscriptShiftPx" to subscriptShift,
        "boxKernPolicy" to "single-glyph-corners-else-zero",
    )
    val scriptBox = geometryExtentsPreservingLogicalChildren(
        width,
        glyphs,
        rules,
        node.range,
        buildList {
            add(base.box to 0f)
            superscript?.let { add(it.box to -superscriptShift) }
            subscript?.let { add(it.box to subscriptShift) }
        },
        hostTextRuns = hostTextRuns,
    )
    return LaidNode(
        node = node,
        box = scriptBox,
        atomClass = base.atomClass,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.superscriptMathKern(
    base: LaidNode,
    script: LaidNode,
    shift: Float,
    range: SourceRange,
): Float {
    if (base.scriptBaseKind == ScriptBaseKind.CompoundBox || script.scriptBaseKind == ScriptBaseKind.CompoundBox) {
        decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "box-zero", "kernPx" to 0f)
        return 0f
    }
    val baseGlyph = base.box.singleGlyphOrNull()
    val scriptGlyph = script.box.singleGlyphOrNull()
    if (baseGlyph == null || scriptGlyph == null) {
        decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "box-zero", "kernPx" to 0f)
        return 0f
    }
    if (baseGlyph.faceId != scriptGlyph.faceId) {
        decision("OpenTypeMathKern", range, "kind" to "superscript", "strategy" to "cross-face-zero", "kernPx" to 0f)
        return 0f
    }
    val mathFont = mathFontForFaceOrNull(baseGlyph.faceId) ?: return 0f
    val first = mathFont.mathKern(
        baseGlyph.glyphId,
        MathKernCorner.TopRight,
        shift - script.box.inkBounds.bottom,
        baseGlyph.fontSizePx,
    ) + mathFont.mathKern(
        scriptGlyph.glyphId,
        MathKernCorner.BottomLeft,
        -script.box.inkBounds.bottom,
        scriptGlyph.fontSizePx,
    )
    val second = mathFont.mathKern(
        baseGlyph.glyphId,
        MathKernCorner.TopRight,
        -base.box.inkBounds.top,
        baseGlyph.fontSizePx,
    ) + mathFont.mathKern(
        scriptGlyph.glyphId,
        MathKernCorner.BottomLeft,
        -shift - base.box.inkBounds.top,
        scriptGlyph.fontSizePx,
    )
    val kern = minOf(first, second)
    decision(
        "OpenTypeMathKern",
        range,
        "kind" to "superscript",
        "strategy" to "two-correction-heights",
        "candidate1Px" to first,
        "candidate2Px" to second,
        "kernPx" to kern,
    )
    return kern
}

private fun MathLayoutPass.subscriptMathKern(
    base: LaidNode,
    script: LaidNode,
    shift: Float,
    range: SourceRange,
): Float {
    if (base.scriptBaseKind == ScriptBaseKind.CompoundBox || script.scriptBaseKind == ScriptBaseKind.CompoundBox) {
        decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "box-zero", "kernPx" to 0f)
        return 0f
    }
    val baseGlyph = base.box.singleGlyphOrNull()
    val scriptGlyph = script.box.singleGlyphOrNull()
    if (baseGlyph == null || scriptGlyph == null) {
        decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "box-zero", "kernPx" to 0f)
        return 0f
    }
    if (baseGlyph.faceId != scriptGlyph.faceId) {
        decision("OpenTypeMathKern", range, "kind" to "subscript", "strategy" to "cross-face-zero", "kernPx" to 0f)
        return 0f
    }
    val mathFont = mathFontForFaceOrNull(baseGlyph.faceId) ?: return 0f
    val first = mathFont.mathKern(
        baseGlyph.glyphId,
        MathKernCorner.BottomRight,
        -shift - script.box.inkBounds.top,
        baseGlyph.fontSizePx,
    ) + mathFont.mathKern(
        scriptGlyph.glyphId,
        MathKernCorner.TopLeft,
        -script.box.inkBounds.top,
        scriptGlyph.fontSizePx,
    )
    val second = mathFont.mathKern(
        baseGlyph.glyphId,
        MathKernCorner.BottomRight,
        -base.box.inkBounds.bottom,
        baseGlyph.fontSizePx,
    ) + mathFont.mathKern(
        scriptGlyph.glyphId,
        MathKernCorner.TopLeft,
        shift - base.box.inkBounds.bottom,
        scriptGlyph.fontSizePx,
    )
    val kern = minOf(first, second)
    decision(
        "OpenTypeMathKern",
        range,
        "kind" to "subscript",
        "strategy" to "two-correction-heights",
        "candidate1Px" to first,
        "candidate2Px" to second,
        "kernPx" to kern,
    )
    return kern
}
