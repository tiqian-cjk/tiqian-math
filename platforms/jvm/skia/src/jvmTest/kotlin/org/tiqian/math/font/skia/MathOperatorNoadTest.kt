package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLargeOperatorIdentity
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphAssemblyPart
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphVariant
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathOperatorNoadTest {
    @Test
    fun sideScriptBaselineDropsUseTheAbsoluteTargetScriptStyleScale() =
        withOperatorFaces { label, face ->
            val size = 32f
            listOf(MathStyle.Text, MathStyle.Script).forEach { style ->
                val result = MathLayoutEngine(face).layout(
                    "\\int_0^1",
                    MathLayoutOptions(MathMode.Inline, size, initialStyle = style),
                )
                val placement = result.decisions.single { it.name == "OpenTypeMathScriptPlacement" }
                val superscriptStyle = style.superscript()
                val subscriptStyle = style.subscript()
                val superscriptSize = when (superscriptStyle.level) {
                    org.tiqian.math.core.MathStyleLevel.Script ->
                        size * face.mathFont.constants.scriptPercentScaleDown / 100f
                    org.tiqian.math.core.MathStyleLevel.ScriptScript ->
                        size * face.mathFont.constants.scriptScriptPercentScaleDown / 100f
                    else -> error("unexpected superscript style $superscriptStyle")
                }
                val subscriptSize = when (subscriptStyle.level) {
                    org.tiqian.math.core.MathStyleLevel.Script ->
                        size * face.mathFont.constants.scriptPercentScaleDown / 100f
                    org.tiqian.math.core.MathStyleLevel.ScriptScript ->
                        size * face.mathFont.constants.scriptScriptPercentScaleDown / 100f
                    else -> error("unexpected subscript style $subscriptStyle")
                }
                val expectedSupDrop = face.mathFont.scaleDesignUnits(
                    face.mathFont.constants.superscriptBaselineDropMax,
                    superscriptSize,
                )
                val expectedSubDrop = face.mathFont.scaleDesignUnits(
                    face.mathFont.constants.subscriptBaselineDropMin,
                    subscriptSize,
                )
                val caseLabel = "$label/$style"

                assertEquals(superscriptStyle.toString(), placement.details["superscriptBaselineDropStyle"], caseLabel)
                assertEquals(subscriptStyle.toString(), placement.details["subscriptBaselineDropStyle"], caseLabel)
                assertNear(expectedSupDrop, placement.float("superscriptBaselineDropMaxPx"), "$caseLabel sup_drop")
                assertNear(expectedSubDrop, placement.float("subscriptBaselineDropMinPx"), "$caseLabel sub_drop")
                val baselineDropApplied = placement.details.getValue("baselineDropApplied").toBoolean()
                val expectedSupShift = buildList {
                    add(placement.float("standardSuperscriptShiftUpPx"))
                    add(placement.float("superscriptInkDescentPx") + placement.float("superscriptBottomMinPx"))
                    if (baselineDropApplied) add(placement.float("baseInkAscentPx") - expectedSupDrop)
                }.max()
                val expectedSubShift = buildList {
                    add(placement.float("standardSubscriptShiftDownPx"))
                    add(placement.float("subscriptInkAscentPx") - placement.float("subscriptTopMaxPx"))
                    if (baselineDropApplied) add(placement.float("baseInkDescentPx") + expectedSubDrop)
                }.max()
                assertNear(expectedSupShift, placement.float("superscriptShiftBeforePairGapPx"), "$caseLabel sup shift")
                assertNear(expectedSubShift, placement.float("subscriptShiftBeforePairGapPx"), "$caseLabel sub shift")
            }
        }

    @Test
    fun plainTexPoliciesDisplayVariantsAxisAndLimitGeometryHoldForBothRealFonts() =
        withOperatorFaces { label, face ->
            val engine = MathLayoutEngine(face)
            val inlineSum = engine.layout("\\sum_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val displaySum = engine.layout("\\sum_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val inlineProduct = engine.layout("\\prod_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val displayProduct = engine.layout("\\prod_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val forcedInline = engine.layout("\\sum\\limits_i^n", MathLayoutOptions(MathMode.Inline, 44f))
            val forcedDisplaySide = engine.layout("\\sum\\nolimits_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val defaultIntegral = engine.layout("\\int_i^n", MathLayoutOptions(MathMode.Display, 44f))
            val forcedContour = engine.layout("\\oint\\limits_i^n", MathLayoutOptions(MathMode.Inline, 44f))

            listOf(
                inlineSum,
                displaySum,
                inlineProduct,
                displayProduct,
                forcedInline,
                forcedDisplaySide,
                defaultIntegral,
                forcedContour,
            ).forEach { result ->
                assertTrue(
                    result.diagnostics.isEmpty(),
                    "$label/${result.source}: ${result.diagnostics}\n${result.debugDump}",
                )
                val op = result.operatorDecision()
                assertEquals("Operator", op.details["atomClass"], "$label/${result.source}")
                assertEquals("LargeSymbols", op.details["family"], "$label/${result.source}")
                assertNear(
                    op.float("axisY"),
                    op.float("outlineCenterAfter"),
                    "$label/${result.source} operator outline center follows the math axis",
                )
            }

            assertPolicy(inlineSum, "NoLimits", "auto-non-display", label)
            assertPolicy(displaySum, "Limits", "auto-display", label)
            assertPolicy(inlineProduct, "NoLimits", "auto-non-display", label)
            assertPolicy(displayProduct, "Limits", "auto-display", label)
            assertPolicy(forcedInline, "Limits", "explicit-postfix-modifier", label)
            assertPolicy(forcedDisplaySide, "NoLimits", "explicit-postfix-modifier", label)
            assertPolicy(defaultIntegral, "NoLimits", "plain-tex-operator-default", label)
            assertPolicy(forcedContour, "Limits", "explicit-postfix-modifier", label)

            assertTrue(inlineSum.decisions.any { it.name == "TeXOperatorSideScripts" }, label)
            assertTrue(defaultIntegral.decisions.any { it.name == "TeXOperatorSideScripts" }, label)
            assertTrue(displaySum.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
            assertTrue(forcedInline.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)
            assertTrue(forcedContour.decisions.any { it.name == "OpenTypeMathOperatorLimits" }, label)

            val inlineOperator = inlineSum.operatorDecision()
            assertEquals("BaseGlyph", inlineOperator.details["construction"], label)
            assertNear(0f, inlineOperator.float("displayOperatorMinHeightPx"), label)
            val displayOperator = displaySum.operatorDecision()
            assertTrue(displayOperator.details["construction"] != "BaseGlyph", "$label: $displayOperator")
            val reachesDisplayTarget = displayOperator.details.getValue("reachesTarget").toBoolean()
            assertEquals(
                reachesDisplayTarget,
                displayOperator.float("achievedAdvancePx") + EPSILON >=
                    displayOperator.float("variantSelectionTargetPx"),
                "$label target result is derived from the font's variant data",
            )
            assertEquals("true", displayOperator.details["selectionComplete"], label)
            assertEquals((!reachesDisplayTarget).toString(), displayOperator.details["exhaustedVariantLadder"], label)
            assertTrue(displaySum.diagnostics.none { it.code == DiagnosticCode.MathVariantTooShort }, label)
            assertEquals(SourceRange(0, 4), displayOperator.range, "$label operator source range")
            assertEquals(SourceRange(0, 11), forcedInline.operatorDecision().range, "$label modifier range is retained")
            assertEquals(
                "SourceRange(start=4, endExclusive=11)",
                forcedInline.operatorDecision().details["limitsModifierRange"],
                "$label exact limits modifier range is auditable",
            )
            assertTrue(
                forcedInline.box.glyphs.any { it.sourceRange == SourceRange(0, 4) },
                "$label operator glyph maps only to the command token",
            )

            assertLimitConstantsAndHalfItalicCorrection(displaySum, label)
            assertLimitConstantsAndHalfItalicCorrection(forcedContour, label)
        }

    @Test
    fun extremeFixtureProvesEachNamedLimitConstantControlsItsOwnConstraint() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val zero = delegate.mathFont.constants.copy(
                upperLimitGapMin = 0,
                upperLimitBaselineRiseMin = 0,
                lowerLimitGapMin = 0,
                lowerLimitBaselineDropMin = 0,
            )
            val size = 48f
            val extreme = 4000
            val expected = delegate.mathFont.scaleDesignUnits(extreme, size)
            val baseline = limitDecision(delegate, zero, size)
            val upperGap = limitDecision(delegate, zero.copy(upperLimitGapMin = extreme), size)
            val upperRise = limitDecision(delegate, zero.copy(upperLimitBaselineRiseMin = extreme), size)
            val lowerGap = limitDecision(delegate, zero.copy(lowerLimitGapMin = extreme), size)
            val lowerDrop = limitDecision(delegate, zero.copy(lowerLimitBaselineDropMin = extreme), size)

            assertNear(expected, upperGap.float("actualUpperGapPx"), "extreme UpperLimitGapMin")
            assertNear(expected, upperRise.float("actualUpperBaselineRisePx"), "extreme UpperLimitBaselineRiseMin")
            assertNear(expected, lowerGap.float("actualLowerGapPx"), "extreme LowerLimitGapMin")
            assertNear(expected, lowerDrop.float("actualLowerBaselineDropPx"), "extreme LowerLimitBaselineDropMin")
            assertTrue(upperGap.float("upperShiftPx") > baseline.float("upperShiftPx"))
            assertTrue(upperRise.float("upperShiftPx") > baseline.float("upperShiftPx"))
            assertTrue(lowerGap.float("lowerShiftPx") > baseline.float("lowerShiftPx"))
            assertTrue(lowerDrop.float("lowerShiftPx") > baseline.float("lowerShiftPx"))
        }
    }

    @Test
    fun glyphAssemblyItalicCorrectionDrivesStackedLimitSkew() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val baseGlyph = assertNotNull(
                delegate.resolveOperator(
                    MathOperatorGlyphRequest(
                        MathLargeOperatorIdentity.Sum,
                        MathStyle.Display,
                        SourceRange(0, 4),
                        MathResourceLimits.Default,
                    ),
                    size,
                ).constructionBaseGlyphId,
            )
            val assemblyCorrection = 777
            val construction = MathGlyphConstruction(
                variants = emptyList(),
                assembly = MathGlyphAssembly(
                    parts = listOf(
                        MathGlyphAssemblyPart(baseGlyph, 100, 200, 900, false),
                        MathGlyphAssemblyPart(baseGlyph, 200, 200, 900, true),
                        MathGlyphAssemblyPart(baseGlyph, 200, 100, 900, false),
                    ),
                    minimumConnectorOverlap = 100,
                    italicCorrection = assemblyCorrection,
                ),
            )
            val overriddenFont = delegate.mathFont.copy(
                constants = delegate.mathFont.constants.copy(displayOperatorMinHeight = 2500),
                verticalConstructions = delegate.mathFont.verticalConstructions + (baseGlyph to construction),
            )
            val result = MathLayoutEngine(OperatorOverrideFace(delegate, overriddenFont)).layout(
                "\\sum\\limits_a^b",
                MathLayoutOptions(MathMode.Display, size),
            )

            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val operator = result.operatorDecision()
            val limits = result.limitDecision()
            val expectedCorrection = overriddenFont.scaleDesignUnits(assemblyCorrection, size)
            assertEquals("Assembly", operator.details["construction"])
            assertEquals("GlyphAssembly", operator.details["italicCorrectionSource"])
            assertNear(expectedCorrection, operator.float("italicCorrectionPx"), "assembly italic correction")
            assertNear(expectedCorrection / 2f, limits.float("upperCenterOffsetPx"), "assembly upper half skew")
            assertNear(-expectedCorrection / 2f, limits.float("lowerCenterOffsetPx"), "assembly lower half skew")
            assertTrue(result.decisions.any {
                it.name == "OpenTypeOperatorConstruction" &&
                    it.details["assemblyItalicCorrectionDesignUnits"] == assemblyCorrection.toString()
            })
        }
    }

    @Test
    fun displayedLimitSkewChangesOnlyVisualExtentsNotLogicalWidth() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = MathLayoutEngine(face)
            val narrow = engine.layout(
                "\\int\\limits_0^1",
                MathLayoutOptions(MathMode.Display, 48f),
            )
            val wide = engine.layout(
                "\\int\\limits_{abcdefgh}^{abcdefgh}",
                MathLayoutOptions(MathMode.Display, 48f),
            )

            listOf("narrow" to narrow, "wide" to wide).forEach { (label, result) ->
                val limits = result.limitDecision()
                val expectedLogicalWidth = maxOf(
                    limits.float("operatorWidthPx"),
                    limits.float("upperWidthPx"),
                    limits.float("lowerWidthPx"),
                )
                assertTrue(limits.float("operatorItalicCorrectionPx") > 0f, "$label fixture needs non-zero IC")
                assertNear(expectedLogicalWidth, result.box.width, "$label TeX limit width ignores skew")
                assertNear(
                    result.box.ascent,
                    result.box.texCleanBoxMetrics.ascent,
                    "$label completed limit ascent is its recursive clean-box ascent",
                )
                assertNear(
                    result.box.descent,
                    result.box.texCleanBoxMetrics.descent,
                    "$label completed limit descent is its recursive clean-box descent",
                )

                val operatorCenter = limits.float("operatorX") + limits.float("operatorWidthPx") / 2f
                val upperCenter = limits.float("upperX") + limits.float("upperWidthPx") / 2f
                val lowerCenter = limits.float("lowerX") + limits.float("lowerWidthPx") / 2f
                assertNear(
                    limits.float("operatorItalicCorrectionPx") / 2f,
                    upperCenter - operatorCenter,
                    "$label upper limit keeps +IC/2 skew",
                )
                assertNear(
                    -limits.float("operatorItalicCorrectionPx") / 2f,
                    lowerCenter - operatorCenter,
                    "$label lower limit keeps -IC/2 skew",
                )
                if (label == "wide") {
                    assertTrue(result.box.inkBounds.left < -EPSILON, result.debugDump)
                    assertTrue(result.box.inkBounds.right > result.box.width + EPSILON, result.debugDump)
                }
            }
        }
    }

    @Test
    fun displayOperatorTargetIncludesFiveQuartersOfNormalGlyphLikeXeTeX() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val range = SourceRange(0, 4)
            val operatorGlyph = assertNotNull(
                delegate.resolveOperator(
                    MathOperatorGlyphRequest(
                        MathLargeOperatorIdentity.Sum,
                        MathStyle.Display,
                        range,
                        MathResourceLimits.Default,
                    ),
                    size,
                ).constructionBaseGlyphId,
            )
            val unrelatedVariant = delegate.shapeConstructionBase("(", size, range).glyphs.single().glyphId
            val overridden = delegate.mathFont.copy(
                constants = delegate.mathFont.constants.copy(displayOperatorMinHeight = 1),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    operatorGlyph to MathGlyphConstruction(
                        variants = listOf(MathGlyphVariant(unrelatedVariant, 10_000)),
                        assembly = null,
                    )
                ),
            )
            val result = MathLayoutEngine(OperatorOverrideFace(delegate, overridden)).layout(
                "\\sum",
                MathLayoutOptions(MathMode.Display, size),
            )
            val decision = result.operatorDecision()
            assertEquals("Variant", decision.details["construction"])
            assertEquals("MathMLCore5.3.2Variant", decision.details["constructionPolicy"])
            assertTrue(result.box.glyphs.none { it.glyphId == operatorGlyph })
            assertTrue(result.box.glyphs.any { it.glyphId == unrelatedVariant })
            assertNear(
                decision.float("normalGlyphExtentPx") * 1.25f,
                decision.float("variantSelectionTargetPx"),
                "XeTeX make_op 5/4 normal glyph target",
            )
        }
    }

    @Test
    fun heterogeneousAssemblyGlyphBottomsFollowAdvanceOffsets() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val range = SourceRange(0, 4)
            val operatorGlyph = assertNotNull(
                delegate.resolveOperator(
                    MathOperatorGlyphRequest(
                        MathLargeOperatorIdentity.Sum,
                        MathStyle.Display,
                        range,
                        MathResourceLimits.Default,
                    ),
                    size,
                ).constructionBaseGlyphId,
            )
            val parenthesisGlyph = delegate.shapeConstructionBase("(", size, range).glyphs.single().glyphId
            val operatorMetrics = delegate.measureGlyphOutlineBounds(
                operatorGlyph, size, MathStyle.Display, range,
            ).glyphs.single()
            val parenthesisMetrics = delegate.measureGlyphOutlineBounds(
                parenthesisGlyph, size, MathStyle.Display, range,
            ).glyphs.single()
            assertTrue(operatorGlyph != parenthesisGlyph)
            assertTrue(
                abs(operatorMetrics.inkBounds.bottom - parenthesisMetrics.inkBounds.bottom) > EPSILON,
                "fixture glyph bottoms must differ",
            )
            assertTrue(
                abs(operatorMetrics.inkBounds.height - parenthesisMetrics.inkBounds.height) > EPSILON,
                "fixture glyph heights must differ",
            )

            val assembly = MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(operatorGlyph, 100, 300, 1_000, false),
                    MathGlyphAssemblyPart(parenthesisGlyph, 300, 100, 1_000, true),
                ),
                minimumConnectorOverlap = 100,
                italicCorrection = 0,
            )
            val overriddenFont = delegate.mathFont.copy(
                constants = delegate.mathFont.constants.copy(displayOperatorMinHeight = 1_800),
                verticalConstructions = delegate.mathFont.verticalConstructions + (
                    operatorGlyph to MathGlyphConstruction(emptyList(), assembly)
                ),
            )
            val result = MathLayoutEngine(OperatorOverrideFace(delegate, overriddenFont)).layout(
                "\\sum\\limits_a^b",
                MathLayoutOptions(MathMode.Display, size),
            )
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val construction = assertNotNull(
                overriddenFont.verticalConstructionForTest(
                    delegate,
                    operatorGlyph,
                    result.operatorDecision().float("variantSelectionTargetPx"),
                    size,
                    assemblyPolicy = org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue,
                ),
            )

            val componentPlacements = result.box.glyphs.filter { it.sourceRange == range }
            val lowerPlacement = componentPlacements.single { it.glyphId == operatorGlyph }
            val upperPlacement = componentPlacements.single { it.glyphId == parenthesisGlyph }
            val expectedOriginDelta = -overriddenFont.scaleDesignUnits(
                construction.components[1].offset - construction.components[0].offset,
                size,
            )
            assertNear(
                expectedOriginDelta,
                upperPlacement.inkBounds.bottom - lowerPlacement.inkBounds.bottom,
                "component bottom distance comes from the assembly advance offset",
            )
            val expectedBaselineDelta = expectedOriginDelta -
                (parenthesisMetrics.inkBounds.bottom - operatorMetrics.inkBounds.bottom)
            assertNear(
                expectedBaselineDelta,
                upperPlacement.baselineY - lowerPlacement.baselineY,
                "different glyph bottoms require different baseline conversion",
            )
            assertNear(
                overriddenFont.scaleDesignUnits(construction.advanceMeasurement, size),
                result.operatorDecision().float("achievedAdvancePx"),
                "reported construction advance stays tied to the assembly offsets",
            )
            val constructionDecision = result.decisions.first { it.name == "OpenTypeOperatorConstruction" }
            assertEquals("shared-font-x/bottom", constructionDecision.details["placementOrigin"])
            assertEquals(
                "MathMLCore5.3.1SharedFontOriginBottom",
                constructionDecision.details["placementPolicy"],
            )
            assertEquals(
                "Tectonic0.17.0XeTeXBuildOpenTypeAssemblyStretchGlue",
                constructionDecision.details["constructionPolicy"],
            )
            assertEquals("true", constructionDecision.details["assemblyValid"])
            assertEquals("0.0,0.0", constructionDecision.details["componentHorizontalOriginsPx"])
            assertTrue(constructionDecision.details["componentBottomOriginsPx"].orEmpty().contains(','))
        }
    }

    private fun assertPolicy(result: org.tiqian.math.core.MathLayoutResult, policy: String, reason: String, label: String) {
        val decision = result.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
        assertEquals(policy, decision.details["effectivePolicy"], "$label/${result.source}")
        assertEquals(reason, decision.details["reason"], "$label/${result.source}")
    }

    private fun assertLimitConstantsAndHalfItalicCorrection(
        result: org.tiqian.math.core.MathLayoutResult,
        label: String,
    ) {
        val limits = result.limitDecision()
        assertAtLeast(limits.float("actualUpperGapPx"), limits.float("upperLimitGapMinPx"), "$label upper gap")
        assertAtLeast(
            limits.float("actualUpperBaselineRisePx"),
            limits.float("upperLimitBaselineRiseMinPx"),
            "$label upper baseline rise",
        )
        assertAtLeast(limits.float("actualLowerGapPx"), limits.float("lowerLimitGapMinPx"), "$label lower gap")
        assertAtLeast(
            limits.float("actualLowerBaselineDropPx"),
            limits.float("lowerLimitBaselineDropMinPx"),
            "$label lower baseline drop",
        )
        val operatorCenter = limits.float("operatorX") + limits.float("operatorWidthPx") / 2f
        val upperCenter = limits.float("upperX") + limits.float("upperWidthPx") / 2f
        val lowerCenter = limits.float("lowerX") + limits.float("lowerWidthPx") / 2f
        assertNear(
            limits.float("upperCenterOffsetPx"),
            upperCenter - operatorCenter,
            "$label upper center uses half italic correction",
        )
        assertNear(
            limits.float("lowerCenterOffsetPx"),
            lowerCenter - operatorCenter,
            "$label lower center uses minus half italic correction",
        )
    }

    private fun limitDecision(
        delegate: SkiaMathFontFace,
        constants: OpenTypeMathConstants,
        size: Float,
    ): MathLayoutDecision = MathLayoutEngine(
        OperatorOverrideFace(delegate, delegate.mathFont.copy(constants = constants)),
    ).layout(
        "\\sum\\limits_a^b",
        MathLayoutOptions(MathMode.Display, size),
    ).limitDecision()
}

private class OperatorOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
) : MathFontFace {
    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol =
        delegate.resolveSymbol(request, fontSizePx)

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator =
        delegate.resolveOperator(request, fontSizePx)

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = delegate.resolveSymbols(requests, fontSizePx)

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.shape(text, fontSizePx, style, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
}

private fun org.tiqian.math.core.MathLayoutResult.operatorDecision(): MathLayoutDecision =
    decisions.first { it.name == "TeXOperatorNoad" }

private fun org.tiqian.math.core.MathLayoutResult.limitDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathOperatorLimits" }

private fun MathLayoutDecision.float(name: String): Float =
    checkNotNull(details[name]) { "$name is absent from $this" }.toFloat()

private inline fun withOperatorFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= EPSILON, "$message: expected=$expected actual=$actual")
}

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + EPSILON >= minimum, "$message: actual=$actual minimum=$minimum")
}

private const val EPSILON = 0.04f
