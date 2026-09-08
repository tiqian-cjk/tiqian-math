package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.layout.MathLayoutPass.Companion.AMSMATH_BIG_SIZE_SCALE
import org.tiqian.math.layout.MathLayoutPass.Companion.GEOMETRY_EPSILON_PX
import org.tiqian.math.layout.MathLayoutPass.DelimiterTargetEvidence
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutDelimited(
    node: MathDelimited,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val segments = mutableListOf<MathList>()
    val middles = mutableListOf<MathMiddleDelimiter>()
    var segmentStart = node.body.range.start
    var pending = mutableListOf<MathNode>()
    fun finishSegment(endExclusive: Int) {
        val range = if (pending.isEmpty()) {
            SourceRange(segmentStart, segmentStart)
        } else {
            pending.first().range.cover(pending.last().range)
        }
        segments += MathList(pending.toList(), range)
        pending = mutableListOf()
        segmentStart = endExclusive
    }
    node.body.children.forEach { child ->
        if (child is MathMiddleDelimiter) {
            finishSegment(child.range.start)
            middles += child
            segmentStart = child.range.endExclusive
        } else {
            pending += child
        }
    }
    finishSegment(node.body.range.endExclusive)

    val segmentLayouts = segments.map { segment ->
        layoutList(segment, style, alphabetOverride)
    }
    val segmentTrailingPaintColors = segments.map { segment ->
        segment.children.filterIsInstance<MathColorDeclaration>().lastOrNull()?.color
    }
    val innerCleanAscent = segmentLayouts.maxOfOrNull { it.laid.box.texCleanBoxMetrics.ascent } ?: 0f
    val innerCleanDescent = segmentLayouts.maxOfOrNull { it.laid.box.texCleanBoxMetrics.descent } ?: 0f
    val delimiterSize = fontSize(style)
    val axisHeight = glyphSource.mathFont.scaleDesignUnits(constants.axisHeight, delimiterSize)
    val distanceBelowAxis = innerCleanDescent + axisHeight
    val distanceAboveAxis = innerCleanAscent - axisHeight
    val maxAxisDistance = maxOf(distanceBelowAxis, distanceAboveAxis).coerceAtLeast(0f)
    val factorTarget = maxAxisDistance * delimiterFactor / 500f
    val shortfallTarget = 2f * maxAxisDistance - delimiterShortfallPx
    val target = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f)
    val targetEvidence = DelimiterTargetEvidence(
        innerCleanAscentPx = innerCleanAscent,
        innerCleanDescentPx = innerCleanDescent,
        axisHeightPx = axisHeight,
        maxAxisDistancePx = maxAxisDistance,
        factor = delimiterFactor,
        shortfallPx = delimiterShortfallPx,
        factorTargetPx = factorTarget,
        shortfallTargetPx = shortfallTarget,
        targetPx = target,
    )

    val left = layoutDelimiter(node.left, style, targetEvidence)
    val middleLayouts = middles.mapIndexed { index, middle ->
        layoutDelimiter(middle.delimiter, style, targetEvidence).let { box ->
            segmentTrailingPaintColors[index]?.let(box::withInheritedPaintColor) ?: box
        }
    }
    val right = layoutDelimiter(node.right, style, targetEvidence).let { box ->
        segmentTrailingPaintColors.lastOrNull()?.let(box::withInheritedPaintColor) ?: box
    }
    val delimiterBoxes = listOf(left) + middleLayouts + right
    val completedAscent = maxOf(
        innerCleanAscent,
        delimiterBoxes.maxOfOrNull { it.ascent } ?: 0f,
    )
    val completedDescent = maxOf(
        innerCleanDescent,
        delimiterBoxes.maxOfOrNull { it.descent } ?: 0f,
    )
    var x = 0f
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val paintGroups = mutableListOf<MathConstructionPaintGroup>()
    fun append(box: MathBox) {
        val shifted = box.translated(x, 0f)
        glyphs += shifted.glyphs
        hostTextRuns += shifted.hostTextRuns
        rules += shifted.rules
        paintGroups += shifted.constructionPaintGroups
        x += box.width
    }
    append(left)
    segmentLayouts.forEachIndexed { index, segment ->
        append(segment.laid.box)
        middleLayouts.getOrNull(index)?.let(::append)
    }
    append(right)
    val paintedGeometry = geometryExtents(
        width = x,
        glyphs = glyphs,
        rules = rules,
        range = node.range,
        constructionPaintGroups = paintGroups,
        hostTextRuns = hostTextRuns,
    )
    val box = paintedGeometry.copy(
        ascent = completedAscent,
        descent = completedDescent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = completedAscent,
            descent = completedDescent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = paintedGeometry.texCleanBoxMetrics.evidence +
                segmentLayouts.flatMap { it.laid.box.texCleanBoxMetrics.evidence } +
                delimiterBoxes.flatMap { it.texCleanBoxMetrics.evidence } +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "TeXContentDrivenDelimitedGroup",
        node.range,
        "atomClass" to MathAtomClass.Inner,
        "sizePolicy" to node.sizePolicy,
        "innerCleanAscentPx" to innerCleanAscent,
        "innerCleanDescentPx" to innerCleanDescent,
        "axisHeightPx" to axisHeight,
        "maxAxisDistancePx" to maxAxisDistance,
        "delimiterFactor" to delimiterFactor,
        "delimiterShortfallPx" to delimiterShortfallPx,
        "factorTargetPx" to factorTarget,
        "shortfallTargetPx" to shortfallTarget,
        "targetPx" to target,
        "middleCount" to middles.size,
        "groupBreakPolicy" to "UnbreakableContentDrivenFencedInnerNoad",
        "internalBreaksExported" to false,
        "packingPolicy" to "TeXLeftMiddleRightNoInternalMathGlue",
        "delimiterPaintStatePolicy" to "XeTeXColorStateCoversFollowingMiddleOrRightDelimiterOnly",
        "completedBoxPolicy" to "XeTeXCompletedDelimiterNoadMaxOfInnerCleanAndDelimiterLogicalExtents",
        "completedLogicalAscentPx" to completedAscent,
        "completedLogicalDescentPx" to completedDescent,
        "logicalAdvancePx" to box.width,
        "cleanAscentPx" to box.texCleanBoxMetrics.ascent,
        "cleanDescentPx" to box.texCleanBoxMetrics.descent,
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Inner,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

/**
 * Replays amsmath's `\bBigg@`: the request is measured in a fresh textstyle math list,
 * independently of both the surrounding style and surrounding content. Each candidate math
 * face derives the request from its own exact `(` glyph box so fallback never mixes MATH
 * metrics or construction ownership across faces.
 */
internal fun MathLayoutPass.layoutFixedDelimiter(
    node: MathFixedDelimiter,
    entryStyle: MathStyle,
): LaidNode {
    val measurementStyle = MathStyle.Text
    val measurementSize = fontSize(measurementStyle)
    val mathStrutCandidates = constructionBaseCandidates("(", measurementSize, node.range)
    val targetsByFace = mathStrutCandidates.mapNotNull { measurement ->
        val glyph = measurement.run.glyphs.singleOrNull() ?: return@mapNotNull null
        if (measurement.run.missingGlyph) return@mapNotNull null
        val mathFont = mathFontForFace(glyph.faceId)
        val mathStrutAscent = (-glyph.inkBounds.top).coerceAtLeast(0f)
        val mathStrutDescent = glyph.inkBounds.bottom.coerceAtLeast(0f)
        val bigSize = AMSMATH_BIG_SIZE_SCALE * (mathStrutAscent + mathStrutDescent)
        val requestedExtent = node.size.amsmathFactor * bigSize
        val axisHeight = mathFont.scaleDesignUnits(mathFont.constants.axisHeight, measurementSize)
        val vcenterAscent = requestedExtent / 2f + axisHeight
        val vcenterDescent = (requestedExtent / 2f - axisHeight).coerceAtLeast(0f)
        val maxAxisDistance = requestedExtent / 2f
        val factorTarget = requestedExtent * delimiterFactor / 1000f
        val shortfallTarget = requestedExtent - delimiterShortfallPx
        glyph.faceId to DelimiterTargetEvidence(
            innerCleanAscentPx = vcenterAscent,
            innerCleanDescentPx = vcenterDescent,
            axisHeightPx = axisHeight,
            maxAxisDistancePx = maxAxisDistance,
            factor = delimiterFactor,
            shortfallPx = delimiterShortfallPx,
            factorTargetPx = factorTarget,
            shortfallTargetPx = shortfallTarget,
            targetPx = maxOf(factorTarget, shortfallTarget).coerceAtLeast(0f),
            targetPolicy = "AmsmathFixedVCenterThenXeTeXDelimiterFactorShortfall",
            fixedSize = node.size,
            amsmathFactor = node.size.amsmathFactor,
            mathStrutAscentPx = mathStrutAscent,
            mathStrutDescentPx = mathStrutDescent,
            bigSizePx = bigSize,
            requestedExtentPx = requestedExtent,
            vcenterAscentPx = vcenterAscent,
            vcenterDescentPx = vcenterDescent,
        )
    }.toMap()
    val fallbackTarget = targetsByFace[glyphSource.faceId]
        ?: targetsByFace.values.firstOrNull()
        ?: DelimiterTargetEvidence(
            innerCleanAscentPx = 0f,
            innerCleanDescentPx = 0f,
            axisHeightPx = 0f,
            maxAxisDistancePx = 0f,
            factor = delimiterFactor,
            shortfallPx = delimiterShortfallPx,
            factorTargetPx = 0f,
            shortfallTargetPx = 0f,
            targetPx = 0f,
            targetPolicy = "AmsmathFixedVCenterUnavailableMathStrut",
            fixedSize = node.size,
            amsmathFactor = node.size.amsmathFactor,
        )
    if (targetsByFace.isEmpty()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no exact ( glyph for amsmath fixed delimiter sizing",
            node.range,
        )
    }
    val delimiterBox = layoutDelimiter(
        spec = node.delimiter,
        style = measurementStyle,
        target = fallbackTarget,
        decisionName = "TeXFixedSizeDelimiter",
        groupBreakPolicy = "SingleFixedSizeDelimiterAtom",
        invisibleAdvancePx = 0f,
        targetForFace = targetsByFace::get,
        extraDecisionDetails = mapOf(
            "entryStyle" to entryStyle,
            "measurementStyle" to measurementStyle,
            "role" to node.role,
            "atomClass" to node.atomClass,
            "contentDriven" to false,
        ),
    )
    val selectedFaceId = delimiterBox.glyphs.firstOrNull()?.faceId
    val selectedTarget = selectedFaceId?.let(targetsByFace::get) ?: fallbackTarget
    val completedAscent = maxOf(delimiterBox.ascent, selectedTarget.vcenterAscentPx ?: 0f)
    val completedDescent = maxOf(delimiterBox.descent, selectedTarget.vcenterDescentPx ?: 0f)
    val box = delimiterBox.copy(
        ascent = completedAscent,
        descent = completedDescent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = completedAscent,
            descent = completedDescent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = delimiterBox.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "AmsmathFixedDelimiterNoad",
        node.range,
        "command" to node.size.commandStem,
        "role" to node.role,
        "atomClass" to node.atomClass,
        "entryStyle" to entryStyle,
        "measurementStyle" to measurementStyle,
        "measurementFontSizePx" to measurementSize,
        "constructionFaceId" to selectedFaceId,
        "mathStrutAscentPx" to selectedTarget.mathStrutAscentPx,
        "mathStrutDescentPx" to selectedTarget.mathStrutDescentPx,
        "bigSizeScale" to AMSMATH_BIG_SIZE_SCALE,
        "bigSizePx" to selectedTarget.bigSizePx,
        "amsmathFactor" to node.size.amsmathFactor,
        "requestedExtentPx" to selectedTarget.requestedExtentPx,
        "vcenterAscentPx" to selectedTarget.vcenterAscentPx,
        "vcenterDescentPx" to selectedTarget.vcenterDescentPx,
        "targetPx" to selectedTarget.targetPx,
        "targetPolicy" to selectedTarget.targetPolicy,
        "logicalAdvancePx" to box.width,
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "contentDriven" to false,
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = node.atomClass,
        italicCorrectionPx = 0f,
        style = entryStyle,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutDelimiter(
    spec: MathDelimiterSpec,
    style: MathStyle,
    target: DelimiterTargetEvidence,
    decisionName: String = "TeXContentDrivenDelimiter",
    groupBreakPolicy: String = "UnbreakableContentDrivenFencedInnerNoad",
    invisibleAdvancePx: Float = nullDelimiterSpacePx,
    targetForFace: ((MathFaceId) -> DelimiterTargetEvidence?)? = null,
    extraDecisionDetails: Map<String, Any?> = emptyMap(),
): MathBox {
    val identity = spec.identity
    if (identity == null || identity == MathDelimiterIdentity.Invisible) {
        val nullSpace = if (identity == MathDelimiterIdentity.Invisible) invisibleAdvancePx else 0f
        decision(
            decisionName,
            spec.range,
            "sourceSpelling" to spec.sourceText,
            "commandRange" to spec.commandRange,
            "delimiterRange" to spec.delimiterRange,
            "scalar" to null,
            "identity" to (identity?.debugName ?: "unsupported"),
            "side" to spec.side,
            "style" to style,
            "fontSizePx" to fontSize(style),
            "innerCleanAscentPx" to target.innerCleanAscentPx,
            "innerCleanDescentPx" to target.innerCleanDescentPx,
            "axisHeightPx" to target.axisHeightPx,
            "delimiterFactor" to target.factor,
            "delimiterShortfallPx" to target.shortfallPx,
            "factorTargetPx" to target.factorTargetPx,
            "shortfallTargetPx" to target.shortfallTargetPx,
            "targetPx" to target.targetPx,
            "targetPolicy" to target.targetPolicy,
            "delimitedSubFormulaMinHeightUsed" to false,
            "construction" to "None",
            "glyphIds" to "",
            "componentOffsetsDesignUnits" to "",
            "achievedAdvancePx" to 0f,
            "centerShiftPx" to 0f,
            "logicalAdvancePx" to 0f,
            "nullDelimiterSpacePx" to nullSpace,
            "packedAdvancePx" to nullSpace,
            "capability" to if (identity == MathDelimiterIdentity.Invisible) {
                "SupportedInvisibleDelimiter"
            } else {
                "UnsupportedDelimiterRecovery"
            },
            "fallback" to false,
            "groupBreakPolicy" to groupBreakPolicy,
            *extraDecisionDetails.map { it.key to it.value }.toTypedArray(),
        )
        return emptyBox(spec.range).copy(width = nullSpace)
    }

    val size = fontSize(style)
    val text = scalarString(checkNotNull(identity.scalar))
    val delimiterCandidates = constructionBaseCandidates(text, size, spec.range)
        .mapNotNull { measurement ->
            val glyph = measurement.run.glyphs.singleOrNull() ?: return@mapNotNull null
            if (measurement.run.missingGlyph) return@mapNotNull null
            val candidateTarget = if (targetForFace == null) {
                target
            } else {
                targetForFace(glyph.faceId) ?: return@mapNotNull null
            }
            Triple(measurement, candidateTarget, selectVerticalConstruction(
                baseGlyphId = glyph.glyphId,
                normalRun = measurement.run,
                targetHeight = candidateTarget.targetPx,
                size = size,
                style = style,
                range = spec.range,
                assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            ))
        }
    val selectedDelimiter = delimiterCandidates.firstOrNull { it.third?.reachesTarget == true }
        ?: delimiterCandidates.firstOrNull()
    val baseMeasurement = selectedDelimiter?.first
        ?: glyphSource.shapeOutlineConstructionBase(text, size, spec.range)
    val effectiveTarget = selectedDelimiter?.second
        ?: baseMeasurement.run.glyphs.singleOrNull()?.faceId?.let { targetForFace?.invoke(it) }
        ?: target
    val baseRun = baseMeasurement.run
    val constructionFaceId = baseRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
    val constructionMathFont = mathFontForFace(constructionFaceId)
    val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
    if (baseRun.missingGlyph || baseGlyphId == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no ${identity.debugName} delimiter glyph",
            spec.delimiterRange,
        )
    }
    val construction = selectedDelimiter?.third ?: baseGlyphId?.let {
        selectVerticalConstruction(
            baseGlyphId = it,
            normalRun = baseRun,
            targetHeight = effectiveTarget.targetPx,
            size = size,
            style = style,
            range = spec.range,
            assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
        )
    }
    val componentMeasurements = construction?.components?.map { component ->
        component to measureConstructionGlyphForFace(
            constructionFaceId,
            component.glyphId,
            size,
            style,
            spec.range,
        )
    }
    val placed = construction?.let {
        placeVerticalConstruction(
            construction = it,
            componentRuns = componentMeasurements.orEmpty().map { measurement ->
                measurement.first to measurement.second.run
            },
            componentOutlineEvidences = componentMeasurements.orEmpty().map { it.second.evidence },
            size = size,
            style = style,
            sourceRange = spec.range,
            centerComponentsHorizontally = false,
        )
    }
    val baseBox = measuredRunBox(baseRun, spec.range, style, size)
    val rawBox = if (placed == null) {
        baseBox
    } else {
        geometryExtents(placed.width, placed.glyphs, emptyList(), spec.range)
    }
    val achievedAdvance = construction?.let {
        constructionMathFont.scaleDesignUnits(it.advanceMeasurement, size)
    } ?: rawBox.inkBounds.height
    val axisY = -effectiveTarget.axisHeightPx
    val centeringAscent = if (construction?.kind == MathConstructionKind.Assembly) {
        achievedAdvance
    } else {
        (-rawBox.inkBounds.top).coerceAtLeast(0f)
    }
    val centeringDescent = if (construction?.kind == MathConstructionKind.Assembly) {
        0f
    } else {
        rawBox.inkBounds.bottom.coerceAtLeast(0f)
    }
    val centerShift = (centeringAscent - centeringDescent) / 2f - effectiveTarget.axisHeightPx
    val shiftedGlyphs = rawBox.glyphs.map { glyph ->
        glyph.copy(
            baselineY = glyph.baselineY + centerShift,
            inkBounds = glyph.inkBounds.translated(0f, centerShift),
        )
    }
    val outlineMeasurements = if (construction == null) {
        listOf(baseMeasurement)
    } else {
        componentMeasurements.orEmpty().map { it.second }
    }
    val outlineAvailable = outlineMeasurements.isNotEmpty() &&
        outlineMeasurements.all { it.outlineCapability == MathConstructionOutlineCapability.Replayable }
    if (!outlineAvailable && shiftedGlyphs.isNotEmpty()) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingConstructionOutlineEvidence,
            "The math font adapter cannot provide replayable outline evidence for ${identity.debugName}",
            spec.range,
        )
    }
    val paintGroup = shiftedGlyphs.takeIf { it.isNotEmpty() }?.let {
        MathConstructionPaintGroup(
            id = nextConstructionPaintGroupId++,
            kind = MathConstructionPaintKind.Delimiter,
            shapeKind = when (construction?.kind) {
                MathConstructionKind.Assembly -> MathConstructionShapeKind.Assembly
                MathConstructionKind.Variant -> MathConstructionShapeKind.Variant
                MathConstructionKind.BaseGlyph, null -> MathConstructionShapeKind.BaseGlyph
            },
            sourceRange = spec.range,
            outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            faceId = constructionFaceId,
        )
    }
    val groupedGlyphs = shiftedGlyphs.map { glyph ->
        glyph.copy(constructionGroupId = paintGroup?.id)
    }
    val visual = geometryExtents(
        width = rawBox.width,
        glyphs = groupedGlyphs,
        rules = emptyList(),
        range = spec.range,
        constructionPaintGroups = listOfNotNull(paintGroup),
    )
    val logicalAscent = if (construction?.kind == MathConstructionKind.Assembly) {
        (achievedAdvance - centerShift).coerceAtLeast(0f)
    } else {
        visual.ascent
    }
    val logicalDescent = if (construction?.kind == MathConstructionKind.Assembly) {
        centerShift.coerceAtLeast(0f)
    } else {
        visual.descent
    }
    val box = visual.copy(
        ascent = logicalAscent,
        descent = logicalDescent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = logicalAscent,
            descent = logicalDescent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = visual.texCleanBoxMetrics.evidence,
        ),
    )
    val reachesTarget = achievedAdvance + GEOMETRY_EPSILON_PX >= effectiveTarget.targetPx
    if (construction == null && !reachesTarget) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingMathConstruction,
            "${identity.debugName} has no MATH construction covering ${effectiveTarget.targetPx}px",
            spec.range,
        )
    } else if (construction != null && !construction.reachesTarget) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MathVariantTooShort,
            "${identity.debugName} MATH construction does not reach the TeX delimiter target",
            spec.range,
            DiagnosticSeverity.Warning,
        )
    }
    val assemblyValidation = construction?.assemblyValidation
        ?: baseGlyphId?.let(constructionMathFont::verticalAssemblyValidation)
    decision(
        decisionName,
        spec.range,
        "sourceSpelling" to spec.sourceText,
        "commandRange" to spec.commandRange,
        "delimiterRange" to spec.delimiterRange,
        "scalar" to unicodeLabel(checkNotNull(identity.scalar)),
        "identity" to identity.debugName,
        "side" to spec.side,
        "style" to style,
        "fontSizePx" to size,
        "innerCleanAscentPx" to effectiveTarget.innerCleanAscentPx,
        "innerCleanDescentPx" to effectiveTarget.innerCleanDescentPx,
        "axisHeightPx" to effectiveTarget.axisHeightPx,
        "delimiterFactor" to effectiveTarget.factor,
        "delimiterShortfallPx" to effectiveTarget.shortfallPx,
        "factorTargetPx" to effectiveTarget.factorTargetPx,
        "shortfallTargetPx" to effectiveTarget.shortfallTargetPx,
        "targetPx" to effectiveTarget.targetPx,
        "targetPolicy" to effectiveTarget.targetPolicy,
        "fixedSize" to effectiveTarget.fixedSize,
        "amsmathFactor" to effectiveTarget.amsmathFactor,
        "mathStrutAscentPx" to effectiveTarget.mathStrutAscentPx,
        "mathStrutDescentPx" to effectiveTarget.mathStrutDescentPx,
        "bigSizePx" to effectiveTarget.bigSizePx,
        "requestedExtentPx" to effectiveTarget.requestedExtentPx,
        "vcenterAscentPx" to effectiveTarget.vcenterAscentPx,
        "vcenterDescentPx" to effectiveTarget.vcenterDescentPx,
        "delimitedSubFormulaMinHeightUsed" to false,
        "baseGlyphId" to baseGlyphId,
        "constructionFaceId" to constructionFaceId,
        "fontClass" to baseRun.glyphs.firstOrNull()?.fontClass,
        "requestedWeight" to baseRun.glyphs.firstOrNull()?.requestedWeight,
        "resolvedWeight" to baseRun.glyphs.firstOrNull()?.resolvedWeight,
        "fallbackReason" to baseRun.glyphs.firstOrNull()?.fallbackReason,
        "construction" to (construction?.kind ?: "NormalGlyphFallback"),
        "constructionPolicy" to construction?.constructionPolicy,
        "glyphIds" to groupedGlyphs.joinToString(",") { it.glyphId.toString() },
        "componentOffsetsDesignUnits" to construction?.components?.joinToString(",") { it.offset.toString() },
        "componentBaselineOriginsPx" to placed?.componentBaselineOriginsPx?.joinToString(","),
        "componentBottomOriginsPx" to placed?.componentBottomOriginsPx?.joinToString(","),
        "componentHorizontalOriginsPx" to placed?.componentHorizontalOriginsPx?.joinToString(","),
        "connectorOverlapsDesignUnits" to construction?.connectorOverlaps,
        "extenderRepetitions" to construction?.extenderRepetitions,
        "achievedAdvancePx" to achievedAdvance,
        "reachesTarget" to reachesTarget,
        "centeringMetric" to if (construction?.kind == MathConstructionKind.Assembly) {
            "XeTeXAssemblyNominalAdvance"
        } else {
            "ExactGlyphBoundingBox"
        },
        "centerShiftPx" to centerShift,
        "logicalAdvancePx" to box.width,
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "inkTopPx" to box.inkBounds.top,
        "inkBottomPx" to box.inkBounds.bottom,
        "outlineEvidence" to outlineMeasurements.joinToString(",") { it.evidence.evidenceLabel() },
        "outlineCapability" to outlineMeasurements.joinToString(",") { it.outlineCapability.toString() },
        "capability" to if (outlineAvailable) "ReplayableOutlineUnion" else "OutlineEvidenceUnavailable",
        "fallback" to (construction == null),
        "assemblyValid" to assemblyValidation?.valid,
        "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
        "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
        "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
        "assemblyPolicy" to MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
        "groupBreakPolicy" to groupBreakPolicy,
        *extraDecisionDetails.map { it.key to it.value }.toTypedArray(),
    )
    return box
}
