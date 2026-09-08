package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathVerticalConstruction
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.Companion.GEOMETRY_EPSILON_PX
import org.tiqian.math.layout.MathLayoutPass.Companion.LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_ASCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_DESCENT_EM
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutFraction(node: MathFraction, style: MathStyle, alphabetOverride: MathAlphabetOverride?): LaidNode {
    val fractionStyle = node.styleOverride?.let(::styleForLevel) ?: style
    val numerator = refineFractionChildBox(
        layoutNode(node.numerator, fractionStyle.fractionNumerator(), alphabetOverride).box,
        node,
        "numerator",
    ).let { if (node.numeratorStrut) applyContinuedFractionNumeratorStrut(it, node, fractionStyle) else it }
    val denominator = refineFractionChildBox(
        layoutNode(node.denominator, fractionStyle.fractionDenominator(), alphabetOverride).box,
        node,
        "denominator",
    )
    val display = fractionStyle.level == MathStyleLevel.Display
    val stack = layoutFractionStack(node, fractionStyle, numerator, denominator, display)
    val fractionNoad = if (node.origin == MathFractionOrigin.GeneralizedChoose) {
        // TeX's primitive \choose noad owns real delimiters directly. Null delimiter boxes
        // belong to \over/\atop and to the inner noad used by LaTeX's \binom wrapper.
        stack
    } else {
        addNullFractionDelimiters(stack, node)
    }
    val withDelimiters = if (node.hasParentheses) {
        addFractionParentheses(fractionNoad, stack, node, fractionStyle)
    } else {
        fractionNoad
    }
    decision(
        "TeXFractionCommand",
        node.range,
        "origin" to node.origin,
        "commandRange" to node.commandRange,
        "outerStyle" to style,
        "fractionStyle" to fractionStyle,
        "styleOverride" to node.styleOverride,
        "numeratorAlignment" to node.numeratorAlignment,
        "alignmentRange" to node.alignmentRange,
        "numeratorStrut" to node.numeratorStrut,
        "retainRightNullDelimiterSpace" to node.retainRightNullDelimiterSpace,
    )
    return LaidNode(
        node,
        withDelimiters,
        MathAtomClass.Ordinary,
        0f,
        style,
        ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.applyContinuedFractionNumeratorStrut(
    box: MathBox,
    node: MathFraction,
    style: MathStyle,
): MathBox {
    val size = fontSize(style)
    val minimumAscent = TEX_ARRAY_STRUT_ASCENT_EM * size
    val minimumDescent = TEX_ARRAY_STRUT_DESCENT_EM * size
    val ascent = max(box.ascent, minimumAscent)
    val descent = max(box.descent, minimumDescent)
    val result = box.copy(
        ascent = ascent,
        descent = descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = max(box.texCleanBoxMetrics.ascent, minimumAscent),
            descent = max(box.texCleanBoxMetrics.descent, minimumDescent),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = box.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "AmsmathContinuedFractionNumeratorStrut",
        node.range,
        "style" to style,
        "fontSizePx" to size,
        "strutAscentEm" to TEX_ARRAY_STRUT_ASCENT_EM,
        "strutDescentEm" to TEX_ARRAY_STRUT_DESCENT_EM,
        "minimumAscentPx" to minimumAscent,
        "minimumDescentPx" to minimumDescent,
        "inputAscentPx" to box.ascent,
        "inputDescentPx" to box.descent,
        "outputAscentPx" to result.ascent,
        "outputDescentPx" to result.descent,
        "policy" to "AmsmathCfracTextSizeStrut",
    )
    return result
}

/** Consumes the child's already completed TeX box; no flattened-ink reconstruction. */
private fun MathLayoutPass.refineFractionChildBox(box: MathBox, node: MathFraction, role: String): MathBox {
    val clean = box.texCleanBoxMetrics
    val refined = box.copy(ascent = clean.ascent, descent = clean.descent)
    decision(
        "TeXFractionChildBoxMetrics",
        node.range,
        "role" to role,
        "policy" to "CompletedChildTeXCleanBoxMetrics",
        "cleanBoxPolicy" to clean.policy,
        "cleanBoxEvidence" to clean.evidence,
        "logicalAdvanceBeforePx" to box.width,
        "logicalAdvanceAfterPx" to refined.width,
        "inkTopBeforePx" to box.inkBounds.top,
        "inkBottomBeforePx" to box.inkBounds.bottom,
        "inkTopAfterPx" to refined.inkBounds.top,
        "inkBottomAfterPx" to refined.inkBounds.bottom,
        "completedAscentBeforePx" to box.ascent,
        "completedDescentBeforePx" to box.descent,
        "cleanAscentPx" to clean.ascent,
        "cleanDescentPx" to clean.descent,
    )
    return refined
}

private fun MathLayoutPass.addNullFractionDelimiters(stack: MathBox, node: MathFraction): MathBox {
    val shiftedStack = stack.translated(nullDelimiterSpacePx, 0f)
    val rightSpace = if (node.retainRightNullDelimiterSpace) nullDelimiterSpacePx else 0f
    decision(
        "TeXFractionNullDelimiters",
        node.range,
        "leftSpacePx" to nullDelimiterSpacePx,
        "rightSpacePx" to rightSpace,
        "parameter" to "nullDelimiterSpacePx",
        "styleInvariant" to true,
        "rightSpacePolicy" to if (node.retainRightNullDelimiterSpace) {
            "TeXFractionNullDelimiterSpace"
        } else {
            "AmsmathCfracTrailingNullDelimiterSpaceCancellation"
        },
    )
    return geometryExtentsPreservingLogicalChildren(
        width = stack.width + nullDelimiterSpacePx + rightSpace,
        glyphs = shiftedStack.glyphs,
        rules = shiftedStack.rules,
        range = node.range,
        children = listOf(stack to 0f),
        hostTextRuns = shiftedStack.hostTextRuns,
    )
}

/** Shared OpenType MATH vertical kernel for barred fractions and ruleless binomial stacks. */
private fun MathLayoutPass.layoutFractionStack(
    node: MathFraction,
    style: MathStyle,
    numerator: MathBox,
    denominator: MathBox,
    display: Boolean,
): MathBox {
    val contentWidth = max(numerator.width, denominator.width)
    val numeratorX = when (node.numeratorAlignment) {
        MathFractionAlignment.Center -> (contentWidth - numerator.width) / 2f
        MathFractionAlignment.Left -> 0f
        MathFractionAlignment.Right -> contentWidth - numerator.width
    }
    val denominatorX = (contentWidth - denominator.width) / 2f
    val axisY = -scale(constants.axisHeight, style)
    var numeratorShift: Float
    var denominatorShift: Float
    val rules: List<MathRulePlacement>

    if (node.kind == FractionKind.Barred) {
        val thickness = scale(constants.fractionRuleThickness, style)
        val ruleTop = axisY - thickness / 2f
        val ruleBottom = axisY + thickness / 2f
        numeratorShift = scale(
            if (display) constants.fractionNumeratorDisplayStyleShiftUp else constants.fractionNumeratorShiftUp,
            style,
        )
        denominatorShift = scale(
            if (display) constants.fractionDenominatorDisplayStyleShiftDown else constants.fractionDenominatorShiftDown,
            style,
        )
        val numeratorGap = scale(
            if (display) constants.fractionNumDisplayStyleGapMin else constants.fractionNumeratorGapMin,
            style.fractionNumerator(),
        )
        val denominatorGap = scale(
            if (display) constants.fractionDenomDisplayStyleGapMin else constants.fractionDenominatorGapMin,
            style.fractionDenominator(),
        )
        numeratorShift = max(numeratorShift, numerator.descent + numeratorGap - ruleTop)
        denominatorShift = max(denominatorShift, denominator.ascent + denominatorGap + ruleBottom)
        val actualNumeratorGap = ruleTop - (-numeratorShift + numerator.descent)
        val actualDenominatorGap = (denominatorShift - denominator.ascent) - ruleBottom
        rules = listOf(MathRulePlacement(0f, ruleTop, contentWidth, ruleBottom, node.range))
        decision(
            "OpenTypeMathFractionStack",
            node.range,
            "kind" to "barred",
            "style" to style,
            "axisPx" to -axisY,
            "ruleThicknessPx" to thickness,
            "numeratorShiftPx" to numeratorShift,
            "denominatorShiftPx" to denominatorShift,
            "numeratorGapMinPx" to numeratorGap,
            "denominatorGapMinPx" to denominatorGap,
            "gapConstantScalePolicy" to "XeTeXPostCleanBoxChildMathFontSize",
            "actualNumeratorGapPx" to actualNumeratorGap,
            "actualDenominatorGapPx" to actualDenominatorGap,
            "numeratorAlignment" to node.numeratorAlignment,
            "numeratorX" to numeratorX,
            "denominatorX" to denominatorX,
        )
    } else {
        // TeX Rule 15c uses num1 in display and num3 otherwise, together with denom1/2,
        // then enforces stack clearance. XeTeX's same-OTF trace maps that asymmetry to
        // FractionNumeratorDisplayStyleShiftUp in display, StackTopShiftUp otherwise,
        // and the corresponding FractionDenominator shift. OpenType's generic
        // StackTopDisplay/StackBottom shifts do not reproduce TeX's `\atop` box.
        numeratorShift = scale(
            if (display) constants.fractionNumeratorDisplayStyleShiftUp else constants.stackTopShiftUp,
            style,
        )
        denominatorShift = scale(
            if (display) constants.fractionDenominatorDisplayStyleShiftDown else constants.fractionDenominatorShiftDown,
            style,
        )
        val minimumGap = scale(
            if (display) constants.stackDisplayStyleGapMin else constants.stackGapMin,
            style.fractionDenominator(),
        )
        val actualGap = (denominatorShift - denominator.ascent) - (-numeratorShift + numerator.descent)
        val missingGap = (minimumGap - actualGap).coerceAtLeast(0f)
        numeratorShift += missingGap / 2f
        denominatorShift += missingGap / 2f
        val finalGap = (denominatorShift - denominator.ascent) - (-numeratorShift + numerator.descent)
        rules = emptyList()
        decision(
            "OpenTypeMathFractionStack",
            node.range,
            "kind" to "ruleless",
            "style" to style,
            "numeratorShiftPx" to numeratorShift,
            "denominatorShiftPx" to denominatorShift,
            "gapMinPx" to minimumGap,
            "gapConstantScalePolicy" to "XeTeXPostCleanBoxChildMathFontSize",
            "symmetricGapCorrectionPx" to missingGap / 2f,
            "actualGapPx" to finalGap,
            "shiftPolicy" to "TeXRule15cNum1Num3Denom1Denom2WithOpenTypeStackGap",
            "numeratorAlignment" to node.numeratorAlignment,
            "numeratorX" to numeratorX,
            "denominatorX" to denominatorX,
        )
    }

    val shiftedNumerator = numerator.translated(numeratorX, -numeratorShift)
    val shiftedDenominator = denominator.translated(denominatorX, denominatorShift)
    return geometryExtentsPreservingLogicalChildren(
        width = contentWidth,
        glyphs = shiftedNumerator.glyphs + shiftedDenominator.glyphs,
        rules = shiftedNumerator.rules + shiftedDenominator.rules + rules,
        range = node.range,
        children = listOf(
            numerator to -numeratorShift,
            denominator to denominatorShift,
        ),
        hostTextRuns = shiftedNumerator.hostTextRuns + shiftedDenominator.hostTextRuns,
    )
}

private fun MathLayoutPass.addFractionParentheses(
    fractionNoad: MathBox,
    stack: MathBox,
    node: MathFraction,
    style: MathStyle,
): MathBox {
    val generalizedChoose = node.origin == MathFractionOrigin.GeneralizedChoose
    val delimiterSourceRange = if (generalizedChoose) node.commandRange else node.range
    val targetReferenceSize = fontSize(style)
    val targetEmFactor = if (generalizedChoose) null else when (style.level) {
        MathStyleLevel.Display -> LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM
        MathStyleLevel.Text -> LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM
        MathStyleLevel.Script -> LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM
        MathStyleLevel.ScriptScript -> LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM
    }
    val delimitedSubFormulaMinHeightPx = scale(constants.delimitedSubFormulaMinHeight, style)
    val chooseNonDisplayTargetPx = 1.5f * targetReferenceSize
    val targetHeight = if (generalizedChoose) {
        if (style.level == MathStyleLevel.Display) {
            delimitedSubFormulaMinHeightPx
        } else {
            minOf(chooseNonDisplayTargetPx, delimitedSubFormulaMinHeightPx)
        }
    } else {
        targetReferenceSize * checkNotNull(targetEmFactor)
    }
    val chooseTargetParameter = if (!generalizedChoose) null else if (style.level == MathStyleLevel.Display) {
        "delim1"
    } else {
        "delim2"
    }
    val delimitedSubFormulaMinHeightUsed = generalizedChoose &&
        (style.level == MathStyleLevel.Display || delimitedSubFormulaMinHeightPx <= chooseNonDisplayTargetPx)

    // LaTeX2e's XeTeX genfrac fallback creates each delimiter in an inner text-style
    // formula around a style-selected, zero-width vcenter. The OpenType MATH table has
    // no fraction delim1/delim2 constants, so these named fallback factors are the same
    // ones used by amsmath (2.39/1/1.45/1.35 em). This is deliberately separate from
    // the content-driven \left/\right policy and from DelimitedSubFormulaMinHeight.
    // XeTeX's primitive fraction noad asks var_delimiter at cur_size: display/text use
    // the text math font, while script and scriptscript use their corresponding math font.
    // LaTeX2e's \binom XeTeX fallback instead builds its delimiters in an inner text-style box.
    val delimiterStyle = if (generalizedChoose) style else MathStyle.Text
    val delimiterFontSize = fontSize(delimiterStyle)
    val axisY = -scale(constants.axisHeight, delimiterStyle)

    fun construction(baseRun: MeasuredMathRun): MathVerticalConstruction? {
        val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
        val selected = baseGlyphId?.let {
            selectVerticalConstruction(
                baseGlyphId = it,
                normalRun = baseRun,
                targetHeight = targetHeight,
                size = delimiterFontSize,
                style = delimiterStyle,
                range = delimiterSourceRange,
            )
        }
        return selected
    }

    fun chooseDelimiter(text: String): Pair<MeasuredOutlineConstructionRun, MathVerticalConstruction?> {
        val candidates = constructionBaseCandidates(text, delimiterFontSize, delimiterSourceRange)
            .filter { !it.run.missingGlyph && it.run.glyphs.size == 1 }
            .map { it to construction(it.run) }
        return candidates.firstOrNull { it.second?.reachesTarget == true }
            ?: candidates.firstOrNull()
            ?: (glyphSource.shapeOutlineConstructionBase(text, delimiterFontSize, delimiterSourceRange) to null)
    }
    val (leftBaseMeasurement, leftConstruction) = chooseDelimiter("(")
    val (rightBaseMeasurement, rightConstruction) = chooseDelimiter(")")
    listOf("left" to leftConstruction, "right" to rightConstruction).forEach { (side, selected) ->
        if (selected == null) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingMathConstruction,
                "The $side parenthesis has no MATH construction covering ${targetHeight}px",
                delimiterSourceRange,
            )
        }
    }
    fun delimiterBox(
        side: String,
        construction: MathVerticalConstruction?,
        baseMeasurement: MeasuredOutlineConstructionRun,
    ): MathBox {
        val baseRun = baseMeasurement.run
        val baseGlyphId = baseRun.glyphs.singleOrNull()?.glyphId
        val delimiterFaceId = baseRun.glyphs.singleOrNull()?.faceId ?: glyphSource.faceId
        val delimiterMathFont = mathFontForFace(delimiterFaceId)
        val assemblyValidation = construction?.assemblyValidation
            ?: baseGlyphId?.let(delimiterMathFont::verticalAssemblyValidation)
        val componentMeasurements = construction?.components?.map { component ->
            component to measureConstructionGlyphForFace(
                delimiterFaceId,
                component.glyphId,
                delimiterFontSize,
                delimiterStyle,
                delimiterSourceRange,
            )
        }
        val placedConstruction = construction?.let {
            placeVerticalConstruction(
                construction = it,
                componentRuns = componentMeasurements.orEmpty().map { measurement ->
                    measurement.first to measurement.second.run
                },
                componentOutlineEvidences = componentMeasurements.orEmpty().map { it.second.evidence },
                size = delimiterFontSize,
                style = delimiterStyle,
                sourceRange = delimiterSourceRange,
                centerComponentsHorizontally = true,
            )
        }
        val rawPlacements = if (placedConstruction == null) {
            baseRun.glyphs.map { glyph ->
                MathGlyphPlacement(
                    glyphId = glyph.glyphId,
                    x = glyph.x,
                    baselineY = glyph.baselineOffsetPx,
                    advance = glyph.advance,
                    inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
                    fontSizePx = delimiterFontSize,
                    sourceRange = delimiterSourceRange,
                    style = delimiterStyle,
                    faceId = glyph.faceId,
                    fontClass = glyph.fontClass,
                    requestedWeight = glyph.requestedWeight,
                    resolvedWeight = glyph.resolvedWeight,
                    fallbackReason = glyph.fallbackReason,
                )
            }
        } else {
            placedConstruction.glyphs
        }
        val inkTop = rawPlacements.minOfOrNull { it.inkBounds.top } ?: 0f
        val inkBottom = rawPlacements.maxOfOrNull { it.inkBounds.bottom } ?: 0f
        val centerShift = axisY - (inkTop + inkBottom) / 2f
        val shiftedPlacements = rawPlacements.map { placement ->
            placement.copy(
                baselineY = placement.baselineY + centerShift,
                inkBounds = placement.inkBounds.translated(0f, centerShift),
            )
        }
        val outlineMeasurements = if (construction == null) {
            listOf(baseMeasurement)
        } else {
            componentMeasurements.orEmpty().map { it.second }
        }
        val outlineAvailable = outlineMeasurements.isNotEmpty() &&
            outlineMeasurements.all { it.outlineCapability == MathConstructionOutlineCapability.Replayable }
        if (generalizedChoose && !outlineAvailable && shiftedPlacements.isNotEmpty()) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingConstructionOutlineEvidence,
                "The math font adapter cannot replay the $side parenthesis construction",
                delimiterSourceRange,
            )
        }
        val paintGroup = shiftedPlacements.takeIf { generalizedChoose && it.isNotEmpty() }?.let {
            MathConstructionPaintGroup(
                id = nextConstructionPaintGroupId++,
                kind = MathConstructionPaintKind.Delimiter,
                shapeKind = when (construction?.kind) {
                    MathConstructionKind.Assembly -> MathConstructionShapeKind.Assembly
                    MathConstructionKind.Variant -> MathConstructionShapeKind.Variant
                    MathConstructionKind.BaseGlyph, null -> MathConstructionShapeKind.BaseGlyph
                },
                sourceRange = delimiterSourceRange,
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
                faceId = delimiterFaceId,
            )
        }
        val placements = shiftedPlacements.map { placement ->
            placement.copy(constructionGroupId = paintGroup?.id)
        }
        val advance = placedConstruction?.width ?: baseRun.width
        val box = geometryExtents(
            advance,
            placements,
            emptyList(),
            delimiterSourceRange,
            constructionPaintGroups = listOfNotNull(paintGroup),
        )
        val achievedAdvance = construction?.let {
            delimiterMathFont.scaleDesignUnits(it.advanceMeasurement, delimiterFontSize)
        } ?: baseRun.ascent + baseRun.descent
        val inkHeight = box.inkBounds.height
        val coversStackTop = box.inkBounds.top <= stack.inkBounds.top + GEOMETRY_EPSILON_PX
        val coversStackBottom = box.inkBounds.bottom + GEOMETRY_EPSILON_PX >= stack.inkBounds.bottom
        if ((construction != null && !construction.reachesTarget) ||
            achievedAdvance + GEOMETRY_EPSILON_PX < targetHeight
        ) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MathVariantTooShort,
                "$side parenthesis construction does not cover the ${if (generalizedChoose) "\\choose" else "binomial"} target",
                delimiterSourceRange,
                DiagnosticSeverity.Warning,
            )
        }
        decision(
            if (generalizedChoose) "GeneralizedChooseDelimiter" else "BinomialDelimiter",
            node.range,
            "side" to side,
            "style" to style,
            "delimiterStyle" to delimiterStyle,
            "baseGlyphId" to baseGlyphId,
            "construction" to (construction?.kind ?: "BaseGlyph"),
            "fractionOrigin" to node.origin,
            "targetPolicy" to if (generalizedChoose) {
                "XeTeXPrimitiveFractionDelim1Delim2OpenTypeMapping"
            } else {
                "LaTeX2eXeTeXGenfracFixedStyleTarget"
            },
            "targetSource" to if (generalizedChoose) {
                "xetex-math.c-make_fraction-and-xetex-XeTeXOTMath.cpp-get_native_mathsy_param"
            } else {
                "amsmath-genfrac-XeTeX-fallback-no-OpenType-delim1-delim2"
            },
            "targetEmFactor" to targetEmFactor,
            "chooseTargetParameter" to chooseTargetParameter,
            "delimitedSubFormulaMinHeightPx" to delimitedSubFormulaMinHeightPx,
            "chooseNonDisplayTargetPx" to if (generalizedChoose) chooseNonDisplayTargetPx else null,
            "targetReferenceFontSizePx" to targetReferenceSize,
            "delimiterFontSizePx" to delimiterFontSize,
            "delimiterAxisPolicy" to if (generalizedChoose) {
                "XeTeXCurrentMathSizeVarDelimiterAxis"
            } else {
                "InnerTextStyleVarDelimiterAxis"
            },
            "axisY" to axisY,
            "boundsSource" to (componentMeasurements?.joinToString(",") { it.second.run.boundsSource.toString() }
                ?: baseRun.boundsSource.toString()),
            "outlineEvidenceAvailable" to outlineAvailable,
            "constructionPaintGroupId" to paintGroup?.id,
            "delimitedSubFormulaMinHeightUsed" to delimitedSubFormulaMinHeightUsed,
            "targetPx" to targetHeight,
            "achievedAdvancePx" to achievedAdvance,
            "reachesTarget" to (achievedAdvance + GEOMETRY_EPSILON_PX >= targetHeight),
            "inkHeightPx" to inkHeight,
            "stackTopPx" to stack.inkBounds.top,
            "stackBottomPx" to stack.inkBounds.bottom,
            "delimiterTopPx" to box.inkBounds.top,
            "delimiterBottomPx" to box.inkBounds.bottom,
            "stackCoverageRequired" to false,
            "coversStackTop" to coversStackTop,
            "coversStackBottom" to coversStackBottom,
            "extenderRepetitions" to construction?.extenderRepetitions,
            "connectorOverlaps" to construction?.connectorOverlaps,
            "placementOrigin" to (placedConstruction?.placementOrigin ?: "normal-glyph-baseline"),
            "placementPolicy" to (placedConstruction?.placementPolicy ?: "NormalGlyphShaping"),
            "constructionPolicy" to (construction?.constructionPolicy ?: if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2FailureAfterInvalidAssembly"
            } else null),
            "assemblyValid" to assemblyValidation?.valid,
            "assemblyInvalidReasons" to assemblyValidation?.invalidReasons,
            "assemblyValidationPolicy" to assemblyValidation?.validationPolicy,
            "assemblySpecificationDivergence" to assemblyValidation?.specificationDivergence,
            "assemblyCheckedConnectionCount" to assemblyValidation?.checkedConnectionCount,
            "uniformConnectorOverlapDesignUnits" to construction?.uniformConnectorOverlap,
            "orthogonalAdvancePx" to construction?.orthogonalAdvancePx,
        )
        return box
    }

    val leftBox = delimiterBox("left", leftConstruction, leftBaseMeasurement)
    val rightBox = delimiterBox("right", rightConstruction, rightBaseMeasurement)
    if (generalizedChoose) {
        // A primitive \choose fraction noad owns real left/right delimiters directly; unlike
        // LaTeX's genfrac wrapper, there are no synthetic null delimiters or cancellation kerns.
        val stackX = leftBox.width
        val rightX = stackX + stack.width
        val shiftedStack = stack.translated(stackX, 0f)
        val shiftedRight = rightBox.translated(rightX, 0f)
        decision(
            "TeXGeneralizedChooseFractionNoadPacking",
            node.range,
            "leftDelimiterX" to 0f,
            "leftDelimiterAdvancePx" to leftBox.width,
            "stackX" to stackX,
            "stackAdvancePx" to stack.width,
            "rightDelimiterX" to rightX,
            "rightDelimiterAdvancePx" to rightBox.width,
            "totalAdvancePx" to (rightX + rightBox.width),
            "nullDelimiterSpaceUsed" to false,
            "policy" to "XeTeXPrimitiveFractionRealDelimiterPacking",
        )
        return geometryExtentsPreservingLogicalChildren(
            rightX + rightBox.width,
            leftBox.glyphs + shiftedStack.glyphs + shiftedRight.glyphs,
            leftBox.rules + shiftedStack.rules + shiftedRight.rules,
            node.range,
            listOf(
                leftBox to 0f,
                stack to 0f,
                rightBox to 0f,
            ),
            hostTextRuns = leftBox.hostTextRuns + shiftedStack.hostTextRuns + shiftedRight.hostTextRuns,
        )
    }
    // The primitive fraction noad contains a null delimiter box on both sides. LaTeX2e's
    // XeTeX genfrac wrapper places real delimiters outside it and cancels those two spaces
    // with explicit negative kerns. The visible stack therefore starts at leftBox.width and
    // the right delimiter starts at leftBox.width + stack.width; ink never changes advance.
    val fractionNoadX = leftBox.width - nullDelimiterSpacePx
    val stackX = fractionNoadX + nullDelimiterSpacePx
    val rightX = fractionNoadX + fractionNoad.width - nullDelimiterSpacePx
    val shiftedFractionNoad = fractionNoad.translated(fractionNoadX, 0f)
    val shiftedRight = rightBox.translated(rightX, 0f)
    decision(
        "TeXBinomialFractionNoadPacking",
        node.range,
        "leftDelimiterX" to 0f,
        "leftDelimiterAdvancePx" to leftBox.width,
        "leftNullDelimiterSpacePx" to nullDelimiterSpacePx,
        "leftCancellationKernPx" to -nullDelimiterSpacePx,
        "fractionNoadX" to fractionNoadX,
        "fractionNoadAdvancePx" to fractionNoad.width,
        "stackX" to stackX,
        "stackAdvancePx" to stack.width,
        "rightNullDelimiterSpacePx" to nullDelimiterSpacePx,
        "rightCancellationKernPx" to -nullDelimiterSpacePx,
        "rightDelimiterX" to rightX,
        "rightDelimiterAdvancePx" to rightBox.width,
        "totalAdvancePx" to (rightX + rightBox.width),
        "policy" to "TeXFractionNullDelimiterCancellationNoInkCollisionKern",
    )
    return geometryExtentsPreservingLogicalChildren(
        rightX + rightBox.width,
        leftBox.glyphs + shiftedFractionNoad.glyphs + shiftedRight.glyphs,
        leftBox.rules + shiftedFractionNoad.rules + shiftedRight.rules,
        node.range,
        listOf(
            leftBox to 0f,
            fractionNoad to 0f,
            rightBox to 0f,
        ),
        hostTextRuns = leftBox.hostTextRuns + shiftedFractionNoad.hostTextRuns + shiftedRight.hostTextRuns,
    )
}
