package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.OpenTypeMathException
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.Companion.GEOMETRY_EPSILON_PX
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride
import org.tiqian.math.layout.MathLayoutPass.PlacedVerticalConstruction

internal fun MathLayoutPass.layoutOperatorName(
    node: MathOperatorName,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    if (node.origin == MathOperatorNameOrigin.OperatorNameCommand) {
        val textBox = layoutTextSegments(node.nameSegments.orEmpty(), style, node.range)
        decision(
            "TeXDeclaredOperatorName",
            node.range,
            "name" to node.name,
            "origin" to node.origin,
            "nameRange" to node.nameRange,
            "atomClass" to MathAtomClass.Operator,
            "limitsPolicy" to node.limitsPolicy,
            "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
            "shaping" to "SingleUprightTextRunPerSourceSegment",
        )
        return LaidNode(
            node = node,
            box = textBox,
            atomClass = MathAtomClass.Operator,
            italicCorrectionPx = 0f,
            style = style,
            scriptBaseKind = ScriptBaseKind.CompoundBox,
        )
    }
    val box = layoutBuiltInOperatorWord(node.name, node.commandRange, style, alphabetOverride)
    decision(
        "TeXMathOperatorName",
        node.range,
        "name" to node.name,
        "limitsPolicy" to node.limitsPolicy,
        "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
        "commandRange" to node.commandRange,
        "modifierRange" to node.limitsModifierRange,
        "atomClass" to MathAtomClass.Operator,
    )
    return LaidNode(
        node = node,
        box = box.copy(range = node.range),
        atomClass = MathAtomClass.Operator,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

/** Upright operators-family word whose generated glyphs all retain the originating command range. */
private fun MathLayoutPass.layoutBuiltInOperatorWord(
    name: String,
    commandRange: SourceRange,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): MathBox {
    val letters = name.map { ch ->
        MathSymbol(
            sourceText = ch.toString(),
            identity = MathSymbolIdentity.LatinLetter(ch),
            atomClass = MathAtomClass.Ordinary,
            family = MathFamily.Operators,
            familyBinding = MathFamilyBinding.Fixed,
            alphabet = MathAlphabet.Roman,
            range = commandRange,
        )
    }
    return layoutList(MathList(letters, commandRange), style, alphabetOverride).laid.box
}

internal fun MathLayoutPass.layoutOperatorNoad(
    node: MathOperatorNoad,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val nucleus = layoutNode(node.nucleus, style, alphabetOverride).completedTeXMathField()
    decision(
        "TeXMathOperatorNoad",
        node.range,
        "commandRange" to node.commandRange,
        "nucleusRange" to node.nucleus.range,
        "limitsPolicy" to node.limitsPolicy,
        "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
        "modifierRange" to node.limitsModifierRange,
        "atomClass" to MathAtomClass.Operator,
        "nucleusPolicy" to "XeTeXCleanBoxSubMlistCurrentStyle",
        "italicCorrectionPx" to 0f,
    )
    return LaidNode(
        node = node,
        box = nucleus.box.copy(range = node.range),
        atomClass = MathAtomClass.Operator,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutModulo(
    node: MathModulo,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val size = fontSize(style)
    val mu = size / 18f
    val tight = style.level == MathStyleLevel.Script || style.level == MathStyleLevel.ScriptScript
    val name = layoutBuiltInOperatorWord("mod", node.commandRange, style, alphabetOverride).completedTeXBox()

    fun delimiter(symbol: MathNamedSymbol): MathBox {
        val synthetic = MathSymbol(
            sourceText = when (symbol) {
                MathNamedSymbol.LeftParenthesis -> "("
                MathNamedSymbol.RightParenthesis -> ")"
                else -> error("unexpected modulo delimiter $symbol")
            },
            identity = MathSymbolIdentity.Named(symbol),
            atomClass = if (symbol == MathNamedSymbol.LeftParenthesis) MathAtomClass.Opening else MathAtomClass.Closing,
            family = MathFamily.Operators,
            familyBinding = MathFamilyBinding.Fixed,
            range = node.commandRange,
        )
        return layoutSymbol(synthetic, style, alphabetOverride).box.completedTeXBox()
    }

    val leadingMu: Float
    val trailingMu: Float
    val children = mutableListOf<Pair<MathBox, Float>>()
    var x = 0f
    when (node.kind) {
        MathModuloKind.Binary -> {
            // plain/amsmath cancel the surrounding medmuskip in non-tight styles and leave
            // exactly 5mu on each side. The list kernel still owns Bin classification/breaking.
            leadingMu = if (tight) 5f else 1f
            trailingMu = if (tight) 5f else 1f
            children += name to x
            x += name.width + trailingMu * mu
        }
        MathModuloKind.Plain -> {
            leadingMu = if (style.level == MathStyleLevel.Display) 18f else 12f
            trailingMu = 6f
            children += name to x
            x += name.width + trailingMu * mu
        }
        MathModuloKind.Parenthesized -> {
            leadingMu = if (style.level == MathStyleLevel.Display) 18f else 8f
            trailingMu = 0f
            val left = delimiter(MathNamedSymbol.LeftParenthesis)
            val right = delimiter(MathNamedSymbol.RightParenthesis)
            val argument = layoutNode(checkNotNull(node.argument), style, alphabetOverride)
                .completedTeXMathField().box
            children += left to x
            x += left.width
            children += name to x
            x += name.width + 6f * mu
            children += argument to x
            x += argument.width
            children += right to x
            x += right.width
        }
    }
    val glyphs = children.flatMap { (box, offset) -> box.translated(offset, 0f).glyphs }
    val rules = children.flatMap { (box, offset) -> box.translated(offset, 0f).rules }
    val hostTextRuns = children.flatMap { (box, offset) -> box.translated(offset, 0f).hostTextRuns }
    val box = geometryExtentsPreservingLogicalChildren(
        width = x,
        glyphs = glyphs,
        rules = rules,
        range = node.range,
        children = children.map { it.first to 0f },
        hostTextRuns = hostTextRuns,
    )
    decision(
        "AmsmathModulo",
        node.range,
        "kind" to node.kind,
        "style" to style,
        "tightSpacingTable" to tight,
        "muPx" to mu,
        "leadingMu" to leadingMu,
        "trailingMu" to trailingMu,
        "nameFamily" to MathFamily.Operators,
        "nameAlphabet" to MathAlphabet.Roman,
        "nameFaceIds" to name.glyphs.joinToString(",") { it.faceId.toString() },
        "argumentRange" to node.argument?.range,
        "atomClass" to node.atomClass,
        "spacingPolicy" to when (node.kind) {
            MathModuloKind.Binary -> "PlainTeXBmodFiveMuAfterMedmuskipCancellation"
            MathModuloKind.Plain -> "AmsmathModDisplay18MuText12MuThenSixMu"
            MathModuloKind.Parenthesized -> "AmsmathPmodDisplay18MuText8MuThenSixMuInsideParentheses"
        },
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = node.atomClass,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
        horizontalKernPx = leadingMu * mu,
    )
}

internal fun MathLayoutPass.layoutBraceNoad(
    node: MathBraceNoad,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val identity = if (node.kind == MathBraceKind.Over) {
        MathAccentIdentity.OverBrace
    } else {
        MathAccentIdentity.UnderBrace
    }
    val accent = layoutAccent(
        MathAccent(
            identity = identity,
            commandRange = node.commandRange,
            base = node.base,
            range = node.range,
        ),
        style,
        alphabetOverride,
    )
    decision(
        "TeXBraceOperatorNoad",
        node.range,
        "kind" to node.kind,
        "commandRange" to node.commandRange,
        "baseRange" to node.base.range,
        "limitsPolicy" to node.limitsPolicy,
        "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
        "modifierRange" to node.limitsModifierRange,
        "accentIdentity" to identity,
        "atomClass" to MathAtomClass.Operator,
        "constructionPolicy" to "XeTeXGrowingTopOrBottomMathAccentWrappedInLimitsOpNoad",
    )
    return accent.copy(
        node = node,
        box = accent.box.copy(range = node.range),
        atomClass = MathAtomClass.Operator,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutOperator(
    node: MathOperator,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride? = null,
): LaidNode {
    alphabetOverride?.version?.let { version ->
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedMathAlphabet,
            "The selected formula-wide math face has no $version LargeSymbols math version for ${node.identity.debugName}",
            node.commandRange,
        )
        decision(
            "TeXMathVersionCapability",
            node.commandRange,
            "version" to version,
            "identity" to node.identity.debugName,
            "family" to MathFamily.LargeSymbols,
            "capability" to "UnsupportedNoFormulaWideBoldLargeSymbolsFace",
        )
    }
    val size = fontSize(style)
    val resolved = glyphSource.resolveOperator(
        MathOperatorGlyphRequest(
            identity = node.identity,
            style = style,
            sourceRange = node.commandRange,
            resourceLimits = limitsForNextConstruction(),
        ),
        size,
    )
    val operatorFaceId = resolved.run.glyphs.firstOrNull()?.faceId ?: glyphSource.faceId
    val operatorMathFont = mathFontForFace(operatorFaceId)
    if (resolved.run.missingGlyph) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no LargeSymbols glyph for ${node.identity.debugName}",
            node.commandRange,
        )
    }

    // Keep the shaped run's paint bounds. Only native outline geometry participates in make_op.
    val normalGeometryRun = operatorOutlineRun(resolved.run, size, style, node.commandRange)
    val display = style.level == MathStyleLevel.Display && node.identity.growsInDisplayStyle
    val normalGlyphExtent = normalGeometryRun.glyphs.maxOfOrNull { it.inkBounds.height } ?: 0f
    // XeTeX make_op uses the larger of DisplayOperatorMinHeight and 5/4 of the
    // normal native glyph's exact height+depth as the variant-selection target.
    val displayOperatorMinHeight = if (display) {
        operatorMathFont.scaleDesignUnits(operatorMathFont.constants.displayOperatorMinHeight, size)
    } else {
        0f
    }
    val normalGlyphFiveQuarters = if (display) normalGlyphExtent * 5f / 4f else 0f
    val targetHeight = max(displayOperatorMinHeight, normalGlyphFiveQuarters)
    val construction = if (display) {
        resolved.constructionBaseGlyphId?.let {
            selectVerticalConstruction(
                baseGlyphId = it,
                normalRun = normalGeometryRun,
                targetHeight = targetHeight,
                size = size,
                style = style,
                range = node.commandRange,
                assemblyPolicy = MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
            )
        }
    } else {
        null
    }
    val assemblyValidation = construction?.assemblyValidation
        ?: resolved.constructionBaseGlyphId?.let(operatorMathFont::verticalAssemblyValidation)
    val rawBox = if (construction != null) {
        operatorConstructionBox(construction, node, style, size, operatorFaceId)
    } else {
        measuredRunBox(resolved.run, node.commandRange, style, size)
    }
    val axisY = -operatorMathFont.scaleDesignUnits(operatorMathFont.constants.axisHeight, size)
    val inkCenterBefore = (rawBox.inkBounds.top + rawBox.inkBounds.bottom) / 2f
    // Use signed glyph bounds: MathBox extents include the baseline even for a glyph wholly
    // above it. Construction placements already carry outline bounds and their part offsets.
    val geometryTop = if (construction != null) {
        rawBox.glyphs.minOfOrNull { it.inkBounds.top } ?: 0f
    } else {
        normalGeometryRun.glyphs.minOfOrNull { it.inkBounds.top + it.baselineOffsetPx } ?: 0f
    }
    val geometryBottom = if (construction != null) {
        rawBox.glyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f
    } else {
        normalGeometryRun.glyphs.maxOfOrNull { it.inkBounds.bottom + it.baselineOffsetPx } ?: 0f
    }
    val hasOutlineGeometry = if (construction != null) {
        construction.components.all { component ->
            measureGlyphOutlineForFace(operatorFaceId, component.glyphId, size, style, node.commandRange)
                .boundsSource == MathGlyphBoundsSource.Outline
        }
    } else {
        normalGeometryRun.boundsSource == MathGlyphBoundsSource.Outline
    }
    // Backends without outlines retain their existing box-based fallback.
    val outlineCenterBefore = if (hasOutlineGeometry) (geometryTop + geometryBottom) / 2f else inkCenterBefore
    val centerShift = axisY - outlineCenterBefore
    val centeredPlacements = rawBox.glyphs.map { placement ->
        placement.copy(
            baselineY = placement.baselineY + centerShift,
            inkBounds = placement.inkBounds.translated(0f, centerShift),
        )
    }
    val box = geometryExtents(rawBox.width, centeredPlacements, rawBox.rules, node.range)
    val achievedAdvance = construction?.let {
        operatorMathFont.scaleDesignUnits(it.advanceMeasurement, size)
    } ?: if (hasOutlineGeometry) geometryBottom - geometryTop else rawBox.inkBounds.height
    // XeTeX exhausts the variant ladder and keeps the last available glyph when the
    // suggested target is not reached. Unlike radicals and delimiters, this is a complete
    // operator selection, not a missing rendering capability.
    val suggestedTargetReached = !display || achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight
    val exhaustedVariantLadder = display && !suggestedTargetReached

    val finalGlyphId = when (construction?.kind) {
        MathConstructionKind.BaseGlyph,
        MathConstructionKind.Variant ->
            construction.components.singleOrNull()?.glyphId
        MathConstructionKind.Assembly -> null
        null -> resolved.run.glyphs.lastOrNull()?.glyphId
    }
    val italicCorrectionSource = if (
        construction?.kind == MathConstructionKind.Assembly
    ) {
        "GlyphAssembly"
    } else if (finalGlyphId in operatorMathFont.italicCorrectionDeviceAdjustments) {
        "XeTeXHarfBuzzZeroPpemMathItalicsCorrection"
    } else {
        "MathItalicsCorrectionInfo"
    }
    val italicCorrection = construction?.assemblyItalicCorrection?.let {
        operatorMathFont.scaleDesignUnits(it, size)
    } ?: finalGlyphId?.let {
        operatorMathFont.italicCorrection(it, size)
    } ?: 0f
    decision(
        "TeXOperatorNoad",
        node.range,
        "sourceText" to node.sourceText,
        "commandRange" to node.commandRange,
        "identity" to node.identity.debugName,
        "growsInDisplayStyle" to node.identity.growsInDisplayStyle,
        "atomClass" to node.atomClass,
        "family" to node.family,
        "baseScalar" to unicodeLabel(node.identity.baseScalar),
        "backendScalar" to unicodeLabel(resolved.backendScalar),
        "style" to style,
        "fontSizePx" to size,
        "constructionBaseGlyphId" to resolved.constructionBaseGlyphId,
        "glyphIds" to box.glyphs.joinToString(",") { it.glyphId.toString() },
        "construction" to (construction?.kind ?: "BaseGlyph"),
        "constructionPolicy" to when {
            exhaustedVariantLadder && construction != null ->
                "XeTeXMakeOpLargestAvailableBelowSuggestedTarget"
            exhaustedVariantLadder -> "XeTeXMakeOpNormalGlyphAfterExhaustedVariantLadder"
            else -> construction?.constructionPolicy
        },
        "assemblyValid" to assemblyValidation?.valid,
        "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
        "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
        "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
        "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
        "displayOperatorMinHeightPx" to displayOperatorMinHeight,
        "normalGlyphExtentPx" to normalGlyphExtent,
        "normalGlyphFiveQuartersPx" to normalGlyphFiveQuarters,
        "variantSelectionTargetPx" to targetHeight,
        "variantSelectionTargetPolicy" to "XeTeXMakeOpMaxDisplayOperatorMinHeightAndFiveQuartersNormalGlyph",
        "achievedAdvancePx" to achievedAdvance,
        "reachesTarget" to suggestedTargetReached,
        "suggestedTargetReached" to suggestedTargetReached,
        "selectionComplete" to true,
        "exhaustedVariantLadder" to exhaustedVariantLadder,
        "axisY" to axisY,
        "inkCenterBefore" to inkCenterBefore,
        "outlineCenterBefore" to outlineCenterBefore,
        "outlineCenterAfter" to (outlineCenterBefore + centerShift),
        "normalGlyphBoundsSource" to normalGeometryRun.boundsSource,
        "operatorCenterBoundsSource" to if (hasOutlineGeometry) MathGlyphBoundsSource.Outline else MathGlyphBoundsSource.FontReported,
        "centerShiftPx" to centerShift,
        "inkCenterAfter" to (box.inkBounds.top + box.inkBounds.bottom) / 2f,
        "italicCorrectionPx" to italicCorrection,
        "italicCorrectionSource" to italicCorrectionSource,
        "italicCorrectionIgnoredDeviceAdjustment" to
            finalGlyphId?.let(operatorMathFont.italicCorrectionDeviceAdjustments::get),
        "limitsPolicy" to node.limitsPolicy,
        "limitsPolicyExplicit" to node.hasExplicitLimitsPolicy,
        "limitsModifierRange" to node.limitsModifierRange,
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Operator,
        italicCorrectionPx = italicCorrection,
        style = style,
        scriptBaseKind = if (
            (construction?.kind != null && construction.kind != MathConstructionKind.BaseGlyph) ||
            box.glyphs.singleOrNull()?.glyphId in operatorMathFont.extendedShapeGlyphs
        ) {
            ScriptBaseKind.ExtendedShape
        } else {
            ScriptBaseKind.Character
        },
    )
}

/** Refine only geometry, retaining shaping, paint bounds and fallback behavior in the original run. */
private fun MathLayoutPass.operatorOutlineRun(
    run: MeasuredMathRun,
    size: Float,
    style: MathStyle,
    range: SourceRange,
): MeasuredMathRun {
    if (run.glyphs.isEmpty() || run.boundsSource == MathGlyphBoundsSource.Outline) return run
    var boundsSource = MathGlyphBoundsSource.Outline
    val glyphs = run.glyphs.map { glyph ->
        val measured = measureGlyphOutlineForFace(glyph.faceId, glyph.glyphId, size, style, range)
        val outline = measured.glyphs.singleOrNull()
        if (measured.boundsSource == MathGlyphBoundsSource.Outline && outline != null) {
            glyph.copy(inkBounds = outline.inkBounds)
        } else {
            boundsSource = MathGlyphBoundsSource.FontReported
            glyph
        }
    }
    if (boundsSource != MathGlyphBoundsSource.Outline) return run
    return run.copy(
        glyphs = glyphs,
        ascent = glyphs.maxOf { (-(it.inkBounds.top + it.baselineOffsetPx)).coerceAtLeast(0f) },
        descent = glyphs.maxOf { (it.inkBounds.bottom + it.baselineOffsetPx).coerceAtLeast(0f) },
        boundsSource = boundsSource,
    )
}

internal fun MathLayoutPass.measuredRunBox(
    run: MeasuredMathRun,
    range: SourceRange,
    style: MathStyle,
    size: Float,
): MathBox {
    val placements = run.glyphs.map { glyph ->
        MathGlyphPlacement(
            glyphId = glyph.glyphId,
            x = glyph.x,
            baselineY = glyph.baselineOffsetPx,
            advance = glyph.advance,
            inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
            fontSizePx = size,
            sourceRange = range,
            style = style,
            faceId = glyph.faceId,
            fontClass = glyph.fontClass,
            requestedWeight = glyph.requestedWeight,
            resolvedWeight = glyph.resolvedWeight,
            fallbackReason = glyph.fallbackReason,
        )
    }
    return geometryExtents(run.width, placements, emptyList(), range)
}

/** One normal-glyph-first entry point shared by operators, radicals, and delimiters. */
internal fun MathLayoutPass.selectVerticalConstruction(
    baseGlyphId: UShort,
    normalRun: MeasuredMathRun,
    targetHeight: Float,
    size: Float,
    style: MathStyle,
    range: SourceRange,
    assemblyPolicy: MathVerticalAssemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
): MathVerticalConstruction? {
    val resolvedTargetHeight = validatedResolvedDimension(
        sourceText = "verticalConstructionTargetPx",
        resolvedPx = targetHeight,
        range = range,
    ) ?: return null
    val faceId = normalRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
    val mathFont = mathFontForFace(faceId)
    return try {
        mathFont.verticalConstruction(
            request = MathVerticalConstructionRequest(
                baseGlyphId = baseGlyphId,
                targetSizePx = resolvedTargetHeight,
                fontSizePx = size,
                normalGlyphHeightPx = normalRun.glyphs.maxOfOrNull { it.inkBounds.height } ?: 0f,
                normalGlyphAdvanceWidthPx = normalRun.width,
                assemblyPolicy = assemblyPolicy,
                resourceLimits = limitsForNextConstruction(),
            ),
            glyphVerticalExtentPx = { glyphId ->
                measureGlyphOutlineForFace(faceId, glyphId, size, style, range)
                    .glyphs.singleOrNull()?.inkBounds?.height
                    ?: measureGlyphForFace(faceId, glyphId, size, style, range)
                        .let { it.ascent + it.descent }
            },
        ) { glyphId ->
            measureGlyphForFace(faceId, glyphId, size, style, range).width
        }
    } catch (failure: OpenTypeMathException) {
        recordConstructionFailure(failure, range)
        null
    }?.takeIf { consumeConstructionExtenders(it, range) }
}

private fun MathLayoutPass.operatorConstructionBox(
    construction: MathVerticalConstruction,
    node: MathOperator,
    style: MathStyle,
    size: Float,
    faceId: MathFaceId,
): MathBox {
    val componentRuns = construction.components.map { component ->
        component to measureGlyphOutlineForFace(faceId, component.glyphId, size, style, node.commandRange)
    }
    val placed = placeVerticalConstruction(
        construction = construction,
        componentRuns = componentRuns,
        size = size,
        style = style,
        sourceRange = node.commandRange,
        centerComponentsHorizontally = false,
    )
    decision(
        "OpenTypeOperatorConstruction",
        node.range,
        "kind" to construction.kind,
        "componentGlyphIds" to construction.components.joinToString(",") { it.glyphId.toString() },
        "componentOffsetsDesignUnits" to construction.components.joinToString(",") { it.offset.toString() },
        "advanceMeasurementDesignUnits" to construction.advanceMeasurement,
        "extenderRepetitions" to construction.extenderRepetitions,
        "connectorOverlapsDesignUnits" to construction.connectorOverlaps,
        "assemblyItalicCorrectionDesignUnits" to construction.assemblyItalicCorrection,
        "constructionPolicy" to construction.constructionPolicy,
        "assemblyValid" to construction.assemblyValidation?.valid,
        "assemblyInvalidReasons" to construction.assemblyValidation?.invalidReasons,
        "assemblyValidationPolicy" to construction.assemblyValidation?.validationPolicy,
        "assemblySpecificationDivergence" to construction.assemblyValidation?.specificationDivergence,
        "assemblyCheckedConnectionCount" to construction.assemblyValidation?.checkedConnectionCount,
        "uniformConnectorOverlapDesignUnits" to construction.uniformConnectorOverlap,
        "componentHorizontalOriginsPx" to placed.componentHorizontalOriginsPx.joinToString(","),
        "componentBottomOriginsPx" to placed.componentBottomOriginsPx.joinToString(","),
        "componentBaselineOriginsPx" to placed.componentBaselineOriginsPx.joinToString(","),
        "placementOrigin" to placed.placementOrigin,
        "placementPolicy" to placed.placementPolicy,
    )
    return geometryExtents(placed.width, placed.glyphs, emptyList(), node.range)
}

/**
 * A vertical assembly keeps one font-space x origin for every part, as required by the
 * OpenType orthogonal alignment contract. MathML Core's bottom-to-top advance coordinate
 * is converted independently to each glyph baseline; no per-part LSB cancellation is
 * allowed. A ready-made variant retains normal baseline shaping.
 */
internal fun MathLayoutPass.placeVerticalConstruction(
    construction: MathVerticalConstruction,
    componentRuns: List<Pair<MathGlyphComponent, MeasuredMathRun>>,
    componentOutlineEvidences: List<MathConstructionOutlineEvidence>? = null,
    size: Float,
    style: MathStyle,
    sourceRange: SourceRange,
    centerComponentsHorizontally: Boolean,
): PlacedVerticalConstruction {
    val width = construction.orthogonalAdvancePx
    val assembly = construction.kind == MathConstructionKind.Assembly
    val horizontalOrigins = mutableListOf<Float>()
    val bottomOrigins = mutableListOf<Float>()
    val baselineOrigins = mutableListOf<Float>()
    val topStrokeCandidates = mutableListOf<MathConstructionOutlineEvidence.Available>()
    val placements = componentRuns.flatMapIndexed { componentIndex, (component, run) ->
        val componentFont = run.glyphs.firstOrNull()?.faceId?.let(glyphSource::mathFontFor) ?: glyphSource.mathFont
        val componentBottomY = -componentFont.scaleDesignUnits(component.offset, size)
        if (assembly) bottomOrigins += componentBottomY
        val runOriginX = when {
            assembly -> 0f
            centerComponentsHorizontally -> (width - run.width) / 2f
            else -> 0f
        }
        val runBaselineY = if (assembly) {
            val glyph = run.glyphs.singleOrNull()
            if (glyph == null) 0f else componentBottomY - glyph.inkBounds.bottom
        } else {
            0f
        }
        val outlineEvidence = componentOutlineEvidences?.getOrNull(componentIndex)
        if (outlineEvidence is MathConstructionOutlineEvidence.Available) {
            topStrokeCandidates += outlineEvidence.copy(
                topStroke = MathConstructionTopStroke(
                    topPx = outlineEvidence.topStroke.topPx + runBaselineY,
                    bottomPx = outlineEvidence.topStroke.bottomPx + runBaselineY,
                    rightPx = outlineEvidence.topStroke.rightPx + runOriginX,
                ),
            )
        }
        run.glyphs.map { glyph ->
            val componentX = when {
                assembly -> glyph.x
                centerComponentsHorizontally -> (width - run.width) / 2f + glyph.x
                else -> glyph.x
            }
            val baselineY = if (assembly) {
                componentBottomY - glyph.inkBounds.bottom
            } else {
                0f
            }
            horizontalOrigins += componentX
            baselineOrigins += baselineY
            MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = componentX,
                baselineY = baselineY,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(componentX, baselineY),
                fontSizePx = size,
                sourceRange = sourceRange,
                style = style,
                faceId = glyph.faceId,
                fontClass = glyph.fontClass,
                requestedWeight = glyph.requestedWeight,
                resolvedWeight = glyph.resolvedWeight,
                fallbackReason = glyph.fallbackReason,
            )
        }
    }
    val constructionInkTop = placements.minOfOrNull { it.inkBounds.top } ?: 0f
    val constructionInkBottom = placements.maxOfOrNull { it.inkBounds.bottom } ?: 0f
    val allOutlineEvidenceAvailable = componentOutlineEvidences != null &&
        componentOutlineEvidences.size == componentRuns.size &&
        componentOutlineEvidences.all { it is MathConstructionOutlineEvidence.Available }
    val topStrokeEvidence = if (allOutlineEvidenceAvailable) {
        topStrokeCandidates.minByOrNull { it.topStroke.topPx }
    } else {
        null
    }
    val outlineEvidenceFailure = componentOutlineEvidences
        ?.filterIsInstance<MathConstructionOutlineEvidence.Unavailable>()
        ?.firstOrNull()
        ?.reason
    return PlacedVerticalConstruction(
        width = width,
        glyphs = placements,
        boxAscentPx = (-constructionInkTop).coerceAtLeast(0f),
        boxDescentPx = constructionInkBottom.coerceAtLeast(0f),
        topStrokeEvidence = topStrokeEvidence,
        outlineEvidenceFailure = outlineEvidenceFailure,
        componentHorizontalOriginsPx = horizontalOrigins,
        componentBottomOriginsPx = bottomOrigins,
        componentBaselineOriginsPx = baselineOrigins,
        placementOrigin = if (assembly) "shared-font-x/bottom" else "normal-glyph-baseline",
        placementPolicy = if (assembly) {
            "MathMLCore5.3.1SharedFontOriginBottom"
        } else {
            "NormalGlyphShaping"
        },
    )
}
