package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.OpenTypeMathFont
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.Companion.GEOMETRY_EPSILON_PX
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

/** Uses the composable TeX box metric produced with the radicand; no content guessing. */
private fun MathLayoutPass.refineRadicalCleanBox(box: MathBox, node: MathRadical): MathBox {
    val clean = box.texCleanBoxMetrics
    // Replay exact outlines for the radical's painted radicand without deriving its TeX box
    // from that flattened union. The already completed clean metric remains authoritative.
    val paintedGlyphs = box.glyphs.map { placement ->
        if (mathFontForFaceOrNull(placement.faceId) == null) return@map placement
        val glyph = measureGlyphOutlineForFace(
            placement.faceId,
            placement.glyphId,
            placement.fontSizePx,
            placement.style,
            placement.sourceRange,
        ).glyphs.singleOrNull() ?: return@map placement
        placement.copy(inkBounds = glyph.inkBounds.translated(placement.x, placement.baselineY))
    }
    val painted = geometryExtents(
        box.width,
        paintedGlyphs,
        box.rules,
        box.range,
        box.constructionPaintGroups,
        box.hostTextRuns,
    )
    val refined = painted.copy(
        ascent = clean.ascent,
        descent = clean.descent,
        texCleanBoxMetrics = clean,
    )
    val exactOutlineBoundsAvailable =
        MathTeXCleanBoxEvidence.FontReportedGlyphBounds !in clean.evidence
    val cleanMinusPaintedInkAbove = clean.ascent - (-box.inkBounds.top).coerceAtLeast(0f)
    val cleanMinusPaintedInkBelow = clean.descent - box.inkBounds.bottom.coerceAtLeast(0f)
    decision(
        "TeXRadicalCleanBox",
        node.radicand.range,
        "policy" to clean.policy,
        "evidence" to clean.evidence,
        "exactGlyphOutlineBoundsAvailable" to exactOutlineBoundsAvailable,
        "logicalAdvanceBeforePx" to box.width,
        "logicalAdvanceAfterPx" to refined.width,
        "logicalAscentBeforePx" to box.ascent,
        "logicalDescentBeforePx" to box.descent,
        "inkTopBeforePx" to box.inkBounds.top,
        "inkBottomBeforePx" to box.inkBounds.bottom,
        "cleanAscentPx" to refined.ascent,
        "cleanDescentPx" to refined.descent,
        "cleanHeightPx" to refined.height,
        "inkTopAfterPx" to refined.inkBounds.top,
        "inkBottomAfterPx" to refined.inkBounds.bottom,
        "cleanMinusPaintedInkAbovePx" to cleanMinusPaintedInkAbove,
        "cleanMinusPaintedInkBelowPx" to cleanMinusPaintedInkBelow,
        "completedChildBoxMetricsPreserved" to
            (clean.policy == MathTeXCleanBoxPolicy.CompletedLayoutBox),
    )
    return refined
}

internal fun MathLayoutPass.layoutRadical(
    node: MathRadical,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val radicandStyle = style.cramped()
    val radicand = refineRadicalCleanBox(
        layoutNode(node.radicand, radicandStyle, alphabetOverride).box,
        node,
    )
    val degreeStyle = MathStyle.ScriptScript
    val degree = node.degree?.let { layoutNode(it, degreeStyle, alphabetOverride).box }
    val size = fontSize(style)
    data class RadicalFaceCandidate(
        val measurement: MeasuredOutlineConstructionRun,
        val faceId: MathFaceId,
        val mathFont: OpenTypeMathFont,
        val gapMin: Float,
        val ruleThickness: Float,
        val extraAscender: Float,
        val targetHeight: Float,
        val baseBox: MathBox,
        val construction: MathVerticalConstruction?,
    )
    val faceCandidates = constructionBaseCandidates(
        RADICAL_SIGN,
        size,
        node.commandRange,
    ).mapNotNull { measurement ->
        val run = measurement.run
        val glyph = run.glyphs.singleOrNull() ?: return@mapNotNull null
        if (run.missingGlyph) return@mapNotNull null
        val faceMathFont = mathFontForFace(glyph.faceId)
        val faceConstants = faceMathFont.constants
        val candidateGap = faceMathFont.scaleDesignUnits(
            if (style.level == MathStyleLevel.Display) {
                faceConstants.radicalDisplayStyleVerticalGap
            } else {
                faceConstants.radicalVerticalGap
            },
            size,
        )
        val candidateRule = faceMathFont.scaleDesignUnits(faceConstants.radicalRuleThickness, size)
        val candidateExtra = faceMathFont.scaleDesignUnits(faceConstants.radicalExtraAscender, size)
        val candidateTarget = radicand.height + candidateGap + candidateRule
        val candidateBox = measuredRunBox(run, node.commandRange, style, size)
        RadicalFaceCandidate(
            measurement,
            glyph.faceId,
            faceMathFont,
            candidateGap,
            candidateRule,
            candidateExtra,
            candidateTarget,
            candidateBox,
            selectVerticalConstruction(
                baseGlyphId = glyph.glyphId,
                normalRun = run,
                targetHeight = candidateTarget,
                size = size,
                style = style,
                range = node.commandRange,
                assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            ),
        )
    }
    val selectedFace = faceCandidates.firstOrNull { it.construction?.reachesTarget == true }
        ?: faceCandidates.firstOrNull()
    val baseMeasurement = selectedFace?.measurement
        ?: glyphSource.shapeOutlineConstructionBase(RADICAL_SIGN, size, node.commandRange)
    val baseRun = baseMeasurement.run
    val constructionFaceId = selectedFace?.faceId
        ?: baseRun.glyphs.singleOrNull()?.faceId
        ?: glyphSource.faceId
    val constructionMathFont = selectedFace?.mathFont ?: mathFontForFace(constructionFaceId)
    val constructionConstants = constructionMathFont.constants
    val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
    if (baseRun.missingGlyph || baseGlyphId == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no radical sign glyph",
            node.commandRange,
        )
    }

    val gapMin = selectedFace?.gapMin ?: constructionMathFont.scaleDesignUnits(
        if (style.level == MathStyleLevel.Display) constructionConstants.radicalDisplayStyleVerticalGap
        else constructionConstants.radicalVerticalGap,
        size,
    )
    val ruleThickness = selectedFace?.ruleThickness
        ?: constructionMathFont.scaleDesignUnits(constructionConstants.radicalRuleThickness, size)
    val extraAscender = selectedFace?.extraAscender
        ?: constructionMathFont.scaleDesignUnits(constructionConstants.radicalExtraAscender, size)
    // XeTeX make_radical selects the delimiter from clean_box height + depth. A leaf native
    // math glyph contributes its exact glyph bbox to that box, while a compound nucleus
    // contributes the already-completed logical box (including an inner radical's reserve).
    // The painted subtree ink union is deliberately not a substitute for clean_box.
    val targetHeight = selectedFace?.targetHeight ?: radicand.height + gapMin + ruleThickness
    val baseRadical = selectedFace?.baseBox ?: measuredRunBox(baseRun, node.commandRange, style, size)
    val baseGlyphHeight = baseRadical.inkBounds.height
    val construction = selectedFace?.construction ?: baseGlyphId?.let {
        selectVerticalConstruction(it, baseRun, targetHeight, size, style, node.commandRange,
            MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue)
    }
    val baseGlyphCoversTarget = construction?.kind == MathConstructionKind.BaseGlyph
    val constructionMeasurements = construction?.components?.map { component ->
        component to measureConstructionGlyphForFace(
            constructionFaceId,
            component.glyphId,
            size,
            style,
            node.commandRange,
        )
    }
    val constructionRuns = constructionMeasurements?.map { (component, measurement) ->
        component to measurement.run
    }
    val placedConstruction = if (construction == null || constructionRuns == null) {
        null
    } else {
        placeVerticalConstruction(
            construction = construction,
            componentRuns = constructionRuns,
            componentOutlineEvidences = constructionMeasurements.map { it.second.evidence },
            size = size,
            style = style,
            sourceRange = node.commandRange,
            centerComponentsHorizontally = false,
        )
    }
    val assemblyValidation = construction?.assemblyValidation
        ?: baseGlyphId?.let(constructionMathFont::verticalAssemblyValidation)
    val assemblyTable = baseGlyphId?.let {
        constructionMathFont.verticalConstructions[it]?.assembly
    }
    val achievedAdvance = construction?.let {
        constructionMathFont.scaleDesignUnits(it.advanceMeasurement, size)
    } ?: baseGlyphHeight
    val constructionExcess = (achievedAdvance - targetHeight).coerceAtLeast(0f)
    val actualClearance = gapMin + constructionExcess / 2f
    val rawRadical = if (placedConstruction == null) {
        baseRadical
    } else {
        geometryExtents(
            placedConstruction.width,
            placedConstruction.glyphs,
            emptyList(),
            node.commandRange,
        )
    }
    val radicalGlyphAscent = when (construction?.kind) {
        // GlyphAssembly advance is the nominal stretch/selection extent. Its actual box
        // ascent comes from the union of the same outlines that the renderer paints and can
        // protrude beyond that nominal extent. Selection advance and paint bounds stay
        // separate; the latter anchors the radical's top stroke to the rule top.
        MathConstructionKind.Assembly -> placedConstruction!!.boxAscentPx
        MathConstructionKind.BaseGlyph,
        MathConstructionKind.Variant -> constructionRuns!!.single().second.ascent
        null -> baseRun.ascent
    }
    val radicalGlyphDescent = when (construction?.kind) {
        MathConstructionKind.Assembly -> placedConstruction!!.boxDescentPx
        MathConstructionKind.BaseGlyph,
        MathConstructionKind.Variant -> constructionRuns!!.single().second.descent
        null -> baseRun.descent
    }
    val radicalGlyphBlockSize = radicalGlyphAscent + radicalGlyphDescent
    val radicalBoundsSources = when (construction?.kind) {
        MathConstructionKind.Assembly -> constructionRuns.orEmpty().map { it.second.boundsSource }.distinct()
        MathConstructionKind.BaseGlyph,
        MathConstructionKind.Variant -> listOf(constructionRuns!!.single().second.boundsSource)
        null -> listOf(baseRun.boundsSource)
    }
    val allRadicalBoundsAreOutline = radicalBoundsSources.isNotEmpty() &&
        radicalBoundsSources.all { it == MathGlyphBoundsSource.Outline }
    val radicalLogicalAdvancePolicy = if (construction?.kind == MathConstructionKind.Assembly) {
        "MathAssemblyOrthogonalAdvanceAllPartRecords"
    } else {
        "MeasuredMathRunLogicalWidthIndependentOfBoundsSource"
    }
    val topStrokeEvidence = if (construction == null) {
        baseMeasurement.evidence as? MathConstructionOutlineEvidence.Available
    } else {
        placedConstruction?.topStrokeEvidence
    }
    val outlineEvidenceFailure = if (construction == null) {
        (baseMeasurement.evidence as? MathConstructionOutlineEvidence.Unavailable)?.reason
    } else {
        placedConstruction?.outlineEvidenceFailure
    }
    val outlineEvidenceAvailable = topStrokeEvidence != null
    val topStroke = topStrokeEvidence?.topStroke
    val constructionLabel = construction?.kind?.toString() ?: "Unavailable"
    val selectionStep = when (construction?.kind) {
        MathConstructionKind.BaseGlyph -> "NormalGlyphHeight"
        MathConstructionKind.Variant -> "MathGlyphVariantRecord"
        MathConstructionKind.Assembly -> "GlyphAssembly"
        null -> "NoCoveringConstruction"
    }
    if (!baseGlyphCoversTarget && construction == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingMathConstruction,
            "The radical sign has no MATH construction covering ${targetHeight}px",
            node.commandRange,
        )
    } else if (!construction.reachesTarget) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MathVariantTooShort,
            "The radical MATH construction does not cover the required radicand height",
            node.commandRange,
            DiagnosticSeverity.Warning,
        )
    }
    if (!outlineEvidenceAvailable) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingConstructionOutlineEvidence,
            "The math font adapter cannot provide replayable radical top-stroke outline evidence: " +
                outlineEvidenceFailure,
            node.commandRange,
        )
    }

    // XeTeX make_radical adds half of the selected delimiter's positive nominal excess to
    // the minimum clearance, then shifts the delimiter box by -(clean height + clearance).
    // Outline top-stroke evidence remains a paint-only anchor below.
    val ruleBottomInB = -radicand.ascent - actualClearance
    val ruleTopInB = ruleBottomInB - ruleThickness
    val radicalTopStrokeTopPx = topStroke?.topPx ?: -radicalGlyphAscent
    val radicalTopStrokeBottomPx = topStroke?.bottomPx ?: (-radicalGlyphAscent + ruleThickness)
    val radicalTopStrokeRightPx = topStroke?.rightPx ?: rawRadical.width
    val radicalBaselineInB = ruleTopInB - radicalTopStrokeTopPx
    val radicalInkTopInB = radicalBaselineInB + rawRadical.inkBounds.top
    // Tectonic 0.17.0/XeTeX rewrites the OpenType delimiter box to height=rule and
    // depth=nominalAdvance-rule, shifts it by ruleBottom, and builds overbar as
    // kern(rule), rule(rule), kern(clearance), cleanBox. This is the TeX logical box;
    // painted outline overhang remains solely in MathBox visual bounds.
    val texDelimiterBoxHeight = ruleThickness
    val texDelimiterBoxDepth = (achievedAdvance - ruleThickness).coerceAtLeast(0f)
    val texDelimiterBoxShift = ruleBottomInB
    val texDelimiterAscentInB = (texDelimiterBoxHeight - texDelimiterBoxShift).coerceAtLeast(0f)
    val texDelimiterDescentInB = (texDelimiterBoxDepth + texDelimiterBoxShift).coerceAtLeast(0f)
    val overbarLeadingReserve = ruleThickness
    val overbarAscentInB = radicand.ascent + actualClearance + ruleThickness + overbarLeadingReserve
    val unindexedAscent = max(overbarAscentInB, texDelimiterAscentInB)
    val unindexedDescent = max(radicand.descent, texDelimiterDescentInB).coerceAtLeast(0f)
    val actualGap = -radicand.ascent - ruleBottomInB
    val radicandXInB = rawRadical.width
    val radicalInB = rawRadical.translated(0f, radicalBaselineInB)
    val radicandInB = radicand.translated(radicandXInB, 0f)
    val constructionPaintGroup = MathConstructionPaintGroup(
        id = nextConstructionPaintGroupId++,
        kind = MathConstructionPaintKind.Radical,
        shapeKind = when (construction?.kind) {
            MathConstructionKind.Assembly -> MathConstructionShapeKind.Assembly
            MathConstructionKind.Variant -> MathConstructionShapeKind.Variant
            MathConstructionKind.BaseGlyph, null -> MathConstructionShapeKind.BaseGlyph
        },
        sourceRange = node.commandRange,
        outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
        faceId = constructionFaceId,
    )
    val groupedRadicalInB = radicalInB.copy(
        glyphs = radicalInB.glyphs.map {
            it.copy(constructionGroupId = constructionPaintGroup.id)
        },
        constructionPaintGroups = radicalInB.constructionPaintGroups + constructionPaintGroup,
    )
    val ruleInB = MathRulePlacement(
        left = radicalTopStrokeRightPx,
        top = ruleTopInB,
        right = radicandXInB + radicand.width,
        bottom = ruleBottomInB,
        sourceRange = node.commandRange,
        constructionGroupId = constructionPaintGroup.id,
    )
    val unindexedGeometry = geometryExtents(
        width = radicandXInB + radicand.width,
        glyphs = groupedRadicalInB.glyphs + radicandInB.glyphs,
        rules = groupedRadicalInB.rules + radicandInB.rules + ruleInB,
        range = node.range,
        constructionPaintGroups =
            groupedRadicalInB.constructionPaintGroups + radicandInB.constructionPaintGroups,
        hostTextRuns = groupedRadicalInB.hostTextRuns + radicandInB.hostTextRuns,
    )
    val unindexedBox = unindexedGeometry.copy(
        ascent = unindexedAscent,
        descent = unindexedDescent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = unindexedAscent,
            descent = unindexedDescent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = unindexedGeometry.texCleanBoxMetrics.evidence +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )

    // unicode-math's XeTeX root wrapper supplies signed MATH kerns and raises the degree by
    // (height(B) - depth(B)) * RadicalDegreeBottomRaisePercent. Horizontal TeX clamping is
    // independent and remains unchanged.
    val kernBeforeDegree = if (degree == null) 0f else constructionMathFont.scaleDesignUnits(
        constructionConstants.radicalKernBeforeDegree,
        size,
    )
    val kernAfterDegree = if (degree == null) 0f else constructionMathFont.scaleDesignUnits(
        constructionConstants.radicalKernAfterDegree,
        size,
    )
    val degreeHorizontalPlacement = degree?.let {
        resolveRadicalDegreeHorizontalPlacement(
            degreeWidthPx = it.width,
            kernBeforeDegreePx = kernBeforeDegree,
            kernAfterDegreePx = kernAfterDegree,
        )
    }
    val adjustedKernAfterDegree = degreeHorizontalPlacement?.adjustedKernAfterDegreePx ?: 0f
    val degreeX = degreeHorizontalPlacement?.degreeX
    val unindexedX = degreeHorizontalPlacement?.radicalX ?: 0f
    val logicalWidth = unindexedX + unindexedBox.width
    val degreeRaisePercent = constructionConstants.radicalDegreeBottomRaisePercent
    val degreeRaiseReferencePx = if (degree == null) null else unindexedBox.ascent - unindexedBox.descent
    val degreeRaisePx = if (degree == null) {
        null
    } else {
        degreeRaiseReferencePx!! * degreeRaisePercent / 100f
    }
    val degreeBaselineY = if (degree == null) {
        null
    } else {
        -degreeRaisePx!!
    }
    val shiftedUnindexed = unindexedBox.translated(unindexedX, 0f)
    val shiftedDegree = degree?.translated(degreeX!!, degreeBaselineY!!)
    val shiftedRadical = groupedRadicalInB.translated(unindexedX, 0f)
    val shiftedRadicand = radicandInB.translated(unindexedX, 0f)
    val coversRadicandBottom =
        shiftedRadical.inkBounds.bottom + GEOMETRY_EPSILON_PX >= shiftedRadicand.inkBounds.bottom
    if (!coversRadicandBottom) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MathVariantTooShort,
            "The radical construction does not visually cover the radicand depth",
            node.commandRange,
            DiagnosticSeverity.Warning,
        )
    }
    val inkBox = geometryExtentsPreservingLogicalChildren(
        width = logicalWidth,
        glyphs = shiftedUnindexed.glyphs + shiftedDegree?.glyphs.orEmpty(),
        rules = shiftedUnindexed.rules + shiftedDegree?.rules.orEmpty(),
        range = node.range,
        children = buildList {
            add(unindexedBox to 0f)
            degree?.let { add(it to degreeBaselineY!!) }
        },
        hostTextRuns = shiftedUnindexed.hostTextRuns + shiftedDegree?.hostTextRuns.orEmpty(),
    )
    val box = inkBox
    val rule = shiftedUnindexed.rules.last()
    val radicalX = unindexedX
    val radicandX = unindexedX + radicandXInB
    val degreeLogicalBottomY = if (degree == null) null else degreeBaselineY!! + degree.descent
    val degreeInkBottomY = shiftedDegree?.inkBounds?.bottom

    decision(
        "TeXRadicalNoad",
        node.range,
        "sourceText" to node.sourceText,
        "commandRange" to node.commandRange,
        "degreeRange" to node.degreeRange,
        "radicandRange" to node.radicand.range,
        "atomClass" to node.atomClass,
        "style" to style,
        "radicandStyle" to radicandStyle,
        "degreeStyle" to if (degree == null) null else degreeStyle,
        "scriptBaseKind" to ScriptBaseKind.CompoundBox,
        "italicCorrectionPx" to 0f,
    )
    decision(
        "OpenTypeRadicalConstruction",
        node.commandRange,
        "baseGlyphId" to baseGlyphId,
        "constructionFaceId" to constructionFaceId,
        "fontClass" to baseRun.glyphs.firstOrNull()?.fontClass,
        "requestedWeight" to baseRun.glyphs.firstOrNull()?.requestedWeight,
        "resolvedWeight" to baseRun.glyphs.firstOrNull()?.resolvedWeight,
        "fallbackReason" to baseRun.glyphs.firstOrNull()?.fallbackReason,
        "construction" to constructionLabel,
        "componentGlyphIds" to construction?.components?.joinToString(",") { it.glyphId.toString() },
        "componentOffsetsDesignUnits" to construction?.components?.joinToString(",") { it.offset.toString() },
        "connectorOverlapsDesignUnits" to construction?.connectorOverlaps,
        "extenderRepetitions" to construction?.extenderRepetitions,
        "assemblyMinimumConnectorOverlapDesignUnits" to assemblyTable?.minimumConnectorOverlap,
        "assemblySourcePartRecords" to assemblyTable?.parts?.joinToString(";") {
            "${it.glyphId}:${it.startConnectorLength}:${it.endConnectorLength}:" +
                "${it.fullAdvance}:${it.extender}"
        },
        "constructionPolicy" to (construction?.constructionPolicy ?: if (assemblyValidation?.valid == false) {
            "MathMLCore5.3.2FailureAfterInvalidAssembly"
        } else null),
        "assemblyValid" to assemblyValidation?.valid,
        "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
        "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
        "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
        "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
        "uniformConnectorOverlapDesignUnits" to construction?.uniformConnectorOverlap,
        "assemblyNaturalAdvanceDesignUnits" to construction?.assemblyNaturalAdvance,
        "assemblyStretchCapacityDesignUnits" to construction?.assemblyStretchCapacity,
        "assemblyAppliedStretchDesignUnits" to construction?.assemblyAppliedStretch,
        "assemblyGlyphExtentsDesignUnits" to construction?.assemblyGlyphExtents,
        "assemblyMaximumConnectorOverlapsDesignUnits" to
            construction?.assemblyMaximumConnectorOverlaps,
        "assemblyMinimumConnectorOverlapsDesignUnits" to
            construction?.assemblyMinimumConnectorOverlaps,
        "orthogonalAdvancePx" to construction?.orthogonalAdvancePx,
        "selectionStep" to selectionStep,
        "selectionPolicy" to "MathMLCore5.3.2NormalGlyphFirst",
        "targetMetric" to "TeXCleanBoxHeightPlusGapAndRule",
        "cleanBoxPolicy" to "XeTeXMakeRadicalCleanBoxCrampedStyle",
        "cleanRadicandAscentPx" to radicand.ascent,
        "cleanRadicandDescentPx" to radicand.descent,
        "cleanRadicandHeightPx" to radicand.height,
        "radicandInkHeightPx" to radicand.inkBounds.height,
        "radicandLogicalHeightPx" to radicand.height,
        "baseGlyphHeightPx" to baseGlyphHeight,
        "baseGlyphBoundsSource" to baseRun.boundsSource,
        "componentBoundsSources" to constructionRuns?.map { it.second.boundsSource }?.distinct(),
        "baseOutlineEvidence" to baseMeasurement.evidence.evidenceLabel(),
        "componentOutlineEvidences" to constructionMeasurements?.map { it.second.evidence.evidenceLabel() },
        "baseGlyphCoversTarget" to baseGlyphCoversTarget,
        "targetHeightPx" to targetHeight,
        "achievedAdvancePx" to achievedAdvance,
        "reachesTarget" to (achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight),
        "constructionBoxAscentPx" to placedConstruction?.boxAscentPx,
        "constructionBoxDescentPx" to placedConstruction?.boxDescentPx,
        "constructionBoxHeightPx" to placedConstruction?.let { it.boxAscentPx + it.boxDescentPx },
        "constructionExtentPolicy" to if (construction?.kind == MathConstructionKind.Assembly) {
            if (allRadicalBoundsAreOutline) {
                "NominalAdvanceForSelectionActualPlacedOutlineBoundsForBox"
            } else {
                "NominalAdvanceForSelectionReportedBoundsFallbackForBox"
            }
        } else {
            if (allRadicalBoundsAreOutline) "ShapedOutlineBounds" else "ShapedReportedBoundsFallback"
        },
        "componentBottomOriginsPx" to placedConstruction?.componentBottomOriginsPx?.joinToString(","),
        "componentBaselineOriginsPx" to placedConstruction?.componentBaselineOriginsPx?.joinToString(","),
        "componentHorizontalOriginsPx" to placedConstruction?.componentHorizontalOriginsPx?.joinToString(","),
        "placementOrigin" to (placedConstruction?.placementOrigin ?: "normal-glyph-baseline"),
        "placementPolicy" to (placedConstruction?.placementPolicy ?: "NormalGlyphShaping"),
    )
    decision(
        "OpenTypeMathRadical",
        node.range,
        "style" to style,
        "radicandStyle" to radicandStyle,
        "degreeStyle" to if (degree == null) null else degreeStyle,
        "unindexedBoxPolicy" to "XeTeXMakeRadicalCleanBoxNominalDelimiterAndOverbar",
        "degreePlacementPolicy" to if (degree == null) {
            null
        } else {
            "UnicodeMathXeTeXRootHeightMinusDepthRaise"
        },
        "degreePlacementSpecificationDivergence" to if (degree == null) {
            null
        } else {
            "unicode-math-xetex-r@@t;NotLuaTeXOrMathMLBlockSizeMapping"
        },
        "radicalVerticalGapPx" to gapMin,
        "minimumRadicalGapPx" to gapMin,
        "constructionExcessPx" to constructionExcess,
        "constructionExcessMetric" to "SelectedOpenTypeConstructionAdvanceMinusStretchTarget",
        "clearancePolicy" to "MinimumGapPlusHalfPositiveConstructionExcess",
        "clearanceSpecificationDivergence" to
            "TeXMakeRadicalClearanceWithOpenTypeConstructionAdvance;NotMathMLCore3.3.3.2Clearance",
        "actualRadicalGapPx" to actualGap,
        "radicalRuleThicknessPx" to ruleThickness,
        "radicalExtraAscenderPx" to extraAscender,
        "radicalExtraAscenderUsed" to false,
        "overbarLeadingReservePx" to overbarLeadingReserve,
        "overbarLeadingReservePolicy" to "XeTeXOverbarLeadingRuleThickness",
        "overbarLeadingReserveSpecificationDivergence" to
            "Tectonic0.17.0DoesNotConsumeOpenTypeMATH.RadicalExtraAscender",
        "radicalKernBeforeDegreePx" to kernBeforeDegree,
        "radicalKernAfterDegreePx" to kernAfterDegree,
        "usedRadicalKernBeforeDegreePx" to degreeHorizontalPlacement?.rawKernBeforeDegreePx,
        "radicalDegreeAfterKernClampLowerBoundPx" to
            degreeHorizontalPlacement?.afterKernClampLowerBoundPx,
        "adjustedRadicalKernAfterDegreePx" to adjustedKernAfterDegree,
        "degreeHorizontalPlacementPolicy" to if (degree == null) {
            null
        } else {
            "TeXMakeRadicalSignedBeforeAndWidthPlusBeforeAfterClamp"
        },
        "radicalDegreeBottomRaisePercent" to degreeRaisePercent,
        "degreeRaiseReferencePx" to degreeRaiseReferencePx,
        "degreeRaiseReferenceAscentPx" to if (degree == null) null else unindexedBox.ascent,
        "degreeRaiseReferenceDescentPx" to if (degree == null) null else unindexedBox.descent,
        "degreeRaiseReferenceMetric" to if (degree == null) {
            null
        } else {
            "UnindexedRadicalBoxHeightMinusDepth"
        },
        "degreeRaiseReferencePolicy" to if (degree == null) {
            null
        } else {
            "unicode-math-xetex-r@@t-times-OpenTypeMATH.RadicalDegreeBottomRaisePercent"
        },
        "radicalGlyphAscentPx" to radicalGlyphAscent,
        "radicalGlyphDescentPx" to radicalGlyphDescent,
        "radicalGlyphBlockSizePx" to radicalGlyphBlockSize,
        "radicalGlyphBoxMetricSource" to if (construction?.kind == MathConstructionKind.Assembly) {
            if (allRadicalBoundsAreOutline) "PlacedAssemblyOutlineBounds" else "PlacedAssemblyReportedBoundsFallback"
        } else {
            if (allRadicalBoundsAreOutline) {
                "ShapedConstructionOutlineBounds"
            } else {
                "ShapedConstructionReportedBoundsFallback"
            }
        },
        "radicalGlyphBoundsSources" to radicalBoundsSources,
        "radicalBoxAdvancePx" to rawRadical.width,
        "radicalLogicalAdvancePolicy" to radicalLogicalAdvancePolicy,
        "radicalPaintOriginY" to radicalBaselineInB,
        "radicalTopStrokeEvidence" to (topStrokeEvidence?.evidenceLabel()
            ?: "Unavailable($outlineEvidenceFailure)"),
        "radicalTopStrokeEvidenceSource" to topStrokeEvidence?.source,
        "radicalTopStrokeEvidenceFailure" to outlineEvidenceFailure,
        "radicalTopStrokeTopPx" to radicalTopStrokeTopPx,
        "radicalTopStrokeBottomPx" to radicalTopStrokeBottomPx,
        "radicalTopStrokeRightPx" to radicalTopStrokeRightPx,
        "overbarAnchorPolicy" to if (outlineEvidenceAvailable) {
            "FontAdapterTopStrokeTopAndRight"
        } else if (allRadicalBoundsAreOutline) {
            "SelectedConstructionOutlineBoundsAndLogicalAdvanceFallback"
        } else {
            "ReportedBoundsAndLogicalAdvanceFallback"
        },
        "overbarThicknessSource" to "OpenTypeMATH.RadicalRuleThickness",
        "overbarLeftPolicy" to if (outlineEvidenceAvailable) {
            "FontAdapterTopStrokeRight"
        } else {
            "RadicalBoxAdvanceFallback"
        },
        "targetHeightPx" to targetHeight,
        "achievedAdvancePx" to achievedAdvance,
        "texDelimiterBoxHeightPx" to texDelimiterBoxHeight,
        "texDelimiterBoxDepthPx" to texDelimiterBoxDepth,
        "texDelimiterBoxShiftPx" to texDelimiterBoxShift,
        "texDelimiterContributedAscentPx" to texDelimiterAscentInB,
        "texDelimiterContributedDescentPx" to texDelimiterDescentInB,
        "radicandAscentPx" to radicand.ascent,
        "radicandDescentPx" to radicand.descent,
        "unindexedAscentPx" to unindexedBox.ascent,
        "unindexedDescentPx" to unindexedBox.descent,
        "unindexedBlockSizePx" to unindexedBox.height,
        "unindexedX" to unindexedX,
        "degreeWidthPx" to degree?.width,
        "degreeAscentPx" to degree?.ascent,
        "degreeDescentPx" to degree?.descent,
        "degreeRaisePx" to degreeRaisePx,
        "degreeBaselineY" to degreeBaselineY,
        "degreeLogicalBottomY" to degreeLogicalBottomY,
        "degreeInkBottomY" to degreeInkBottomY,
        "degreeX" to degreeX,
        "radicalX" to radicalX,
        "radicandX" to radicandX,
        "radicandWidthPx" to radicand.width,
        "radicalInkTopPx" to shiftedRadical.inkBounds.top,
        "radicalInkBottomPx" to shiftedRadical.inkBounds.bottom,
        "radicandInkTopPx" to shiftedRadicand.inkBounds.top,
        "radicandInkBottomPx" to shiftedRadicand.inkBounds.bottom,
        "coversRadicandBottom" to coversRadicandBottom,
        "ruleLeft" to rule.left,
        "ruleTop" to rule.top,
        "ruleRight" to rule.right,
        "ruleBottom" to rule.bottom,
        "logicalWidthPx" to logicalWidth,
        "visualLeftPx" to box.visualLeft,
        "visualRightPx" to box.visualRight,
        "reservedTopPx" to -unindexedBox.ascent,
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
