package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathHorizontalConstructionRequest
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.OpenTypeMathException
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.AccentAttachmentEvidence
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.resolveTopAccentAttachment(
    faceId: MathFaceId,
    glyphId: UShort,
    fontSizePx: Float,
    fallbackAdvancePx: Float,
    range: SourceRange,
    role: String,
): AccentAttachmentEvidence = try {
    val font = mathFontForFaceOrNull(faceId)
        ?: return AccentAttachmentEvidence(fallbackAdvancePx / 2f, "TextFaceAdvanceCenter")
    val ignoredDevice = font.topAccentAttachmentDeviceAdjustments[glyphId]
    AccentAttachmentEvidence(
        font.topAccentAttachment(glyphId, fontSizePx, fallbackAdvancePx),
        if (ignoredDevice != null) {
            "XeTeXHarfBuzzZeroPpemMathTopAccentAttachment"
        } else if (glyphId in font.topAccentAttachments) {
            "MathTopAccentAttachment"
        } else {
            "OpenTypeAdvanceCenterFallback"
        },
        ignoredDevice,
    )
} catch (failure: OpenTypeMathException) {
    diagnostics += MathDiagnostic(
        failure.diagnosticCode,
        "$role top-accent attachment cannot consume its device/variation adjustment: ${failure.message}",
        range,
    )
    AccentAttachmentEvidence(fallbackAdvancePx / 2f, "ExplicitUnsupportedDeviceAdjustmentCenterRecovery")
}

internal fun MathLayoutPass.layoutAccent(
    node: MathAccent,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val isBottom = node.identity.placement == MathAccentPlacement.Bottom
    val nucleusStyle = style.cramped()
    // XeTeX clean_box uses the exact native glyph bbox for a character nucleus and the
    // already-completed TeX box for a compound nucleus. Reuse the same placement kernel as
    // ordinary side scripts so accent clearance and replayed glyph bounds cannot diverge.
    val base = layoutNode(node.base, nucleusStyle, alphabetOverride)
        .withNativeOutlineBoxForSideScriptPlacement()
    val size = fontSize(style)
    val accentText = scalarString(node.identity.scalar)
    val normal = glyphSource.shapeConstructionBase(accentText, size, node.commandRange)
    val normalGlyph = normal.glyphs.singleOrNull()
    if (normal.missingGlyph || normalGlyph == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no ${node.identity.debugName} accent glyph",
            node.commandRange,
        )
        return base.copy(node = node, box = base.box.copy(range = node.range))
    }
    val constructionFaceId = normalGlyph.faceId
    val constructionMathFont = mathFontForFace(constructionFaceId)
    val normalOutline = measureGlyphOutlineForFace(
        constructionFaceId,
        normalGlyph.glyphId,
        size,
        style,
        node.commandRange,
    )
    val normalMeasuredGlyph = normalOutline.glyphs.single()
    val normalWidth = normalMeasuredGlyph.inkBounds.width.coerceAtLeast(normal.width)
    val targetWidth = base.box.width
    val resolvedTargetWidth = validatedResolvedDimension(
        sourceText = "horizontalConstructionTargetPx",
        resolvedPx = targetWidth,
        range = node.commandRange,
    )
    val hasHorizontalConstruction = normalGlyph.glyphId in constructionMathFont.horizontalConstructions
    var constructionBlockedByResource = resolvedTargetWidth == null
    val construction = if ((node.identity.wide || hasHorizontalConstruction) && resolvedTargetWidth != null) {
        try {
            constructionMathFont.horizontalConstruction(
                request = MathHorizontalConstructionRequest(
                    baseGlyphId = normalGlyph.glyphId,
                    targetSizePx = resolvedTargetWidth,
                    fontSizePx = size,
                    normalGlyphWidthPx = normalWidth,
                    normalGlyphOrthogonalExtentPx = normalMeasuredGlyph.inkBounds.height,
                    resourceLimits = limitsForNextConstruction(),
                ),
                glyphGrowthExtentPx = { glyphId ->
                    measureGlyphOutlineForFace(
                        constructionFaceId, glyphId, size, style, node.commandRange,
                    ).glyphs.single().inkBounds.width
                },
            ) { glyphId ->
                measureGlyphOutlineForFace(
                    constructionFaceId, glyphId, size, style, node.commandRange,
                )
                    .glyphs.single().inkBounds.height
            }
        } catch (failure: OpenTypeMathException) {
            constructionBlockedByResource =
                failure.diagnosticCode == DiagnosticCode.ExtenderCountLimitExceeded ||
                failure.diagnosticCode == DiagnosticCode.InvalidResolvedDimension
            recordConstructionFailure(failure, node.commandRange)
            null
        }?.let { candidate ->
            if (consumeConstructionExtenders(candidate, node.commandRange)) {
                candidate
            } else {
                constructionBlockedByResource = true
                null
            }
        }
    } else {
        null
    }
    val selected = construction ?: org.tiqian.math.font.opentype.MathVerticalConstruction(
        kind = MathConstructionKind.BaseGlyph,
        components = listOf(MathGlyphComponent(normalGlyph.glyphId, 0f)),
        advanceMeasurement = normalWidth * constructionMathFont.unitsPerEm / size,
        reachesTarget = !node.identity.wide,
        constructionPolicy = if (node.identity.wide) {
            "VisibleNormalAccentAfterMissingHorizontalConstruction"
        } else {
            "TeXFixedMathAccentNormalGlyph"
        },
        orthogonalAdvancePx = normalMeasuredGlyph.inkBounds.height,
    ).also { fallback ->
        if (node.identity.wide && !constructionBlockedByResource) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingMathConstruction,
                "${node.identity.debugName} has no horizontal MATH construction covering ${targetWidth}px",
                node.commandRange,
            )
        }
    }
    // TeX uses the largest font-provided accent variant when a horizontal assembly is absent.
    // Unlike a radical or delimiter, an accent is allowed to leave base overhang; the exact
    // under-coverage remains auditable in the decision instead of becoming formula fallback.
    val groupId = if (selected.kind == MathConstructionKind.Assembly) nextConstructionPaintGroupId++ else null
    val accentGlyphs = selected.components.map { component ->
        val measured = measureGlyphOutlineForFace(
            constructionFaceId,
            component.glyphId,
            size,
            style,
            node.commandRange,
        ).glyphs.single()
        val componentX = constructionMathFont.scaleDesignUnits(component.offset, size)
        MathGlyphPlacement(
            glyphId = component.glyphId,
            x = componentX,
            baselineY = 0f,
            advance = measured.advance,
            inkBounds = measured.inkBounds.translated(componentX, 0f),
            fontSizePx = size,
            sourceRange = node.commandRange,
            style = style,
            constructionGroupId = groupId,
            faceId = measured.faceId,
            fontClass = measured.fontClass,
            requestedWeight = measured.requestedWeight,
            resolvedWeight = measured.resolvedWeight,
            fallbackReason = measured.fallbackReason,
        )
    }
    val achievedWidth = constructionMathFont.scaleDesignUnits(selected.advanceMeasurement, size)
    val accentAttachmentEvidence = if (selected.components.size == 1) {
        val placement = accentGlyphs.single()
        resolveTopAccentAttachment(
            placement.faceId, placement.glyphId, size, achievedWidth, node.commandRange, "accent",
        ).let { it.copy(valuePx = placement.x + it.valuePx) }
    } else {
        AccentAttachmentEvidence(achievedWidth / 2f, "AssemblyLogicalCenter")
    }
    val accentAttachment = accentAttachmentEvidence.valuePx
    val baseGlyph = base.box.singleGlyphOrNull()
    val baseAttachmentEvidence = if (isBottom) {
        AccentAttachmentEvidence(base.box.width / 2f, "XeTeXBottomAccentNucleusLogicalCenter")
    } else if (baseGlyph != null) {
        resolveTopAccentAttachment(
            baseGlyph.faceId, baseGlyph.glyphId, baseGlyph.fontSizePx, baseGlyph.advance, node.base.range, "base",
        ).let { it.copy(valuePx = baseGlyph.x + it.valuePx) }
    } else {
        AccentAttachmentEvidence(base.box.width / 2f, "CompoundBoxLogicalCenter")
    }
    val baseAttachment = baseAttachmentEvidence.valuePx
    val accentX = baseAttachment - accentAttachment
    val accentBaseHeight = scale(constants.accentBaseHeight, style)
    val baseInkAscent = (-base.box.inkBounds.top).coerceAtLeast(0f)
    val baseCleanAscent = base.box.texCleanBoxMetrics.ascent
    val baseCleanDescent = base.box.texCleanBoxMetrics.descent
    val accentBaselineY = if (isBottom) {
        baseCleanDescent
    } else {
        -(baseCleanAscent - accentBaseHeight).coerceAtLeast(0f)
    }
    val positionedAccent = accentGlyphs.map { glyph ->
        glyph.copy(
            x = glyph.x + accentX,
            baselineY = accentBaselineY,
            inkBounds = glyph.inkBounds.translated(accentX, accentBaselineY),
        )
    }
    val groups = buildList {
        addAll(base.box.constructionPaintGroups)
        if (groupId != null) {
            add(
                MathConstructionPaintGroup(
                    id = groupId,
                    kind = MathConstructionPaintKind.Accent,
                    shapeKind = MathConstructionShapeKind.Assembly,
                    sourceRange = node.commandRange,
                    outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
                    faceId = constructionFaceId,
                ),
            )
        }
    }
    val glyphs = base.box.glyphs + positionedAccent
    val inkGeometry = geometryExtents(
        width = base.box.width,
        glyphs = glyphs,
        rules = base.box.rules,
        range = node.range,
        constructionPaintGroups = groups,
        hostTextRuns = base.box.hostTextRuns,
    )
    val accentTop = positionedAccent.minOfOrNull { it.inkBounds.top } ?: 0f
    val accentBottom = positionedAccent.maxOfOrNull { it.inkBounds.bottom } ?: 0f
    val cleanAscent = if (isBottom) base.box.texCleanBoxMetrics.ascent else max(baseCleanAscent, -accentTop)
    val cleanDescent = if (isBottom) max(baseCleanDescent, accentBottom) else baseCleanDescent
    val box = inkGeometry.copy(
        ascent = if (isBottom) base.box.ascent else max(base.box.ascent, -accentTop),
        descent = if (isBottom) max(base.box.descent, accentBottom) else base.box.descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = cleanAscent,
            descent = cleanDescent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = inkGeometry.texCleanBoxMetrics.evidence +
                base.box.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "OpenTypeMathAccent",
        node.range,
        "identity" to node.identity.debugName,
        "wide" to node.identity.wide,
        "placement" to node.identity.placement,
        "style" to style,
        "nucleusStyle" to nucleusStyle,
        "nucleusBoxPolicy" to "XeTeXNativeGlyphOutlineOrCompletedChildBox",
        "baseWidthPx" to base.box.width,
        "baseInkAscentPx" to baseInkAscent,
        "baseCleanAscentPx" to baseCleanAscent,
        "accentBaseHeightPx" to accentBaseHeight,
        "verticalPlacementPolicy" to if (isBottom) {
            "XeTeXBottomMathAccentAfterCompletedNucleusDepth"
        } else {
            "XeTeXMakeMathAccentMinCleanBoxHeightAndAccentBaseHeight"
        },
        "flattenedAccentBaseHeightPx" to scale(constants.flattenedAccentBaseHeight, style),
        "baseAttachmentPx" to baseAttachment,
        "baseAttachmentPolicy" to baseAttachmentEvidence.policy,
        "baseAttachmentIgnoredDeviceAdjustment" to baseAttachmentEvidence.ignoredDeviceAdjustment,
        "accentAttachmentPx" to accentAttachment,
        "accentAttachmentPolicy" to accentAttachmentEvidence.policy,
        "accentAttachmentIgnoredDeviceAdjustment" to accentAttachmentEvidence.ignoredDeviceAdjustment,
        "accentX" to accentX,
        "accentBaselineY" to accentBaselineY,
        "construction" to selected.kind,
        "constructionPolicy" to selected.constructionPolicy,
        "glyphIds" to selected.components.joinToString(",") { it.glyphId.toString() },
        "componentOffsetsDesignUnits" to selected.components.joinToString(",") { it.offset.toString() },
        "connectorOverlapsDesignUnits" to selected.connectorOverlaps,
        "targetWidthPx" to targetWidth,
        "achievedWidthPx" to achievedWidth,
        "reachesTarget" to selected.reachesTarget,
        "coveragePolicy" to if (selected.reachesTarget) "CoversTarget" else "TeXLargestAvailableAccentVariantAllowsBaseOverhang",
        "assemblyValidation" to selected.assemblyValidation,
        "paintPolicy" to if (groupId == null) "DirectSingleGlyphReplay" else "SemanticOutlineUnion",
        "logicalAdvancePx" to box.width,
        "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
        "cleanDescentPx" to box.texCleanBoxMetrics.descent,
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutRuleDecoration(
    node: MathRuleDecoration,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val isOver = node.kind == MathRuleDecorationKind.Overline
    // TeX make_overline cleans its nucleus in cramped style, while make_under keeps
    // the current style. This distinction matters for fractions and scripts nested
    // inside the decoration; it is not a renderer-side visual adjustment.
    val nucleusStyle = if (isOver) style.cramped() else style
    val base = layoutNode(node.base, nucleusStyle, alphabetOverride)
    val gap = scale(if (isOver) constants.overbarVerticalGap else constants.underbarVerticalGap, style)
    val thickness = scale(if (isOver) constants.overbarRuleThickness else constants.underbarRuleThickness, style)
    val extra = scale(if (isOver) constants.overbarExtraAscender else constants.underbarExtraDescender, style)
    val rule = if (isOver) {
        val bottom = base.box.inkBounds.top - gap
        MathRulePlacement(0f, bottom - thickness, base.box.width, bottom, node.commandRange)
    } else {
        val top = base.box.inkBounds.bottom + gap
        MathRulePlacement(0f, top, base.box.width, top + thickness, node.commandRange)
    }
    val geometry = geometryExtents(
        base.box.width,
        base.box.glyphs,
        base.box.rules + rule,
        node.range,
        base.box.constructionPaintGroups,
        base.box.hostTextRuns,
    )
    val logicalAscent = if (isOver) max(base.box.ascent, -rule.top + extra) else base.box.ascent
    val logicalDescent = if (isOver) base.box.descent else max(base.box.descent, rule.bottom + extra)
    val cleanAscent = if (isOver) max(base.box.texCleanBoxMetrics.ascent, -rule.top + extra) else base.box.texCleanBoxMetrics.ascent
    val cleanDescent = if (isOver) base.box.texCleanBoxMetrics.descent else max(base.box.texCleanBoxMetrics.descent, rule.bottom + extra)
    val box = geometry.copy(
        ascent = logicalAscent,
        descent = logicalDescent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            cleanAscent,
            cleanDescent,
            MathTeXCleanBoxPolicy.CompletedLayoutBox,
            geometry.texCleanBoxMetrics.evidence + base.box.texCleanBoxMetrics.evidence +
                MathTeXCleanBoxEvidence.CompletedChildBox + MathTeXCleanBoxEvidence.RuleGeometry,
        ),
    )
    decision(
        "OpenTypeMathRuleDecoration",
        node.range,
        "kind" to node.kind,
        "style" to style,
        "nucleusStyle" to nucleusStyle,
        "baseInkTopPx" to base.box.inkBounds.top,
        "baseInkBottomPx" to base.box.inkBounds.bottom,
        "verticalGapPx" to gap,
        "ruleThicknessPx" to thickness,
        "extraReservePx" to extra,
        "ruleLeftPx" to rule.left,
        "ruleTopPx" to rule.top,
        "ruleRightPx" to rule.right,
        "ruleBottomPx" to rule.bottom,
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
        "cleanDescentPx" to box.texCleanBoxMetrics.descent,
        "geometryPolicy" to if (isOver) "OpenTypeOverbarInkGapRuleExtraAscender" else "OpenTypeUnderbarInkGapRuleExtraDescender",
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}
