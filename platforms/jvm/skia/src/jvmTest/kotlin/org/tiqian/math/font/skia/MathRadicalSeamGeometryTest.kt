package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRulePlacement
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathConstructionOutlineEvidence
import org.tiqian.math.layout.MathConstructionOutlineUnavailableReason
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun

class MathRadicalSeamGeometryTest {
    @Test
    fun selectedVariantAndAssemblyNeverBorrowAvailableBaseOutlineEvidence() =
        withSeamFaces { label, face ->
            val mixed = MissingSelectedConstructionOutlineFace(face)
            radicalCases().filter { it.kind != MathConstructionShapeKind.BaseGlyph }.forEach { case ->
                val result = MathLayoutEngine(mixed).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val construction = result.decisions.first {
                    it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                }
                val geometry = result.radicalGeometryDecision()
                assertEquals(case.kind.toString(), construction.details["construction"])
                assertEquals(
                    "Available(GlyphOutlineCrossSection)",
                    construction.details["baseOutlineEvidence"],
                )
                assertTrue(
                    construction.details.getValue("componentOutlineEvidences")
                        .contains("Unavailable(GlyphOutlineUnavailable)"),
                    construction.details.toString(),
                )
                assertEquals(
                    "Unavailable(GlyphOutlineUnavailable)",
                    geometry.details["radicalTopStrokeEvidence"],
                )
                assertEquals(
                    "GlyphOutlineUnavailable",
                    geometry.details["radicalTopStrokeEvidenceFailure"],
                )
                assertEquals(
                    "SelectedConstructionOutlineBoundsAndLogicalAdvanceFallback",
                    geometry.details["overbarAnchorPolicy"],
                )
                assertEquals("[Outline]", geometry.details["radicalGlyphBoundsSources"])
                assertTrue(
                    result.diagnostics.any {
                        it.code == DiagnosticCode.MissingConstructionOutlineEvidence && it.range.start == 0
                    },
                    "$label/${case.label} must diagnose selected-construction evidence loss:\n${result.debugDump}",
                )
                assertNear(
                    -geometry.float("radicalGlyphAscentPx"),
                    geometry.float("radicalTopStrokeTopPx"),
                    "$label/${case.label} fallback top comes from the selected construction box",
                )
                assertNear(
                    geometry.float("radicalBoxAdvancePx"),
                    geometry.float("radicalTopStrokeRightPx"),
                    "$label/${case.label} fallback right comes from the selected construction advance",
                )
            }
        }

    @Test
    fun shiftedFontReportedConstructionBoundsReproduceTheHistoricalSeamFailure() =
        withSeamFaces { label, face ->
            val legacy = ShiftedFontReportedConstructionFace(face)
            seamFontSizes().forEach { fontSizePx ->
                radicalCases().forEach { case ->
                    val result = MathLayoutEngine(legacy).layout(
                        case.source,
                        MathLayoutOptions(MathMode.Display, fontSizePx),
                    )
                    val seam = face.radicalSeamGeometry(result.box, result.outerRadicalGroup())
                    val geometry = result.radicalGeometryDecision()
                    assertEquals(case.kind, result.outerRadicalGroup().shapeKind)
                    assertTrue(
                        result.diagnostics.any { it.code == DiagnosticCode.MissingConstructionOutlineEvidence },
                        result.debugDump,
                    )
                    assertEquals(
                        "Unavailable(AdapterDoesNotProvideOutlineEvidence)",
                        geometry.details["radicalTopStrokeEvidence"],
                    )
                    assertEquals(
                        "ReportedBoundsAndLogicalAdvanceFallback",
                        geometry.details["overbarAnchorPolicy"],
                    )
                    assertEquals("[FontReported]", geometry.details["radicalGlyphBoundsSources"])
                    assertEquals(
                        if (case.kind == MathConstructionShapeKind.Assembly) {
                            "MathAssemblyOrthogonalAdvanceAllPartRecords"
                        } else {
                            "MeasuredMathRunLogicalWidthIndependentOfBoundsSource"
                        },
                        geometry.details["radicalLogicalAdvancePolicy"],
                    )
                    assertFalse(geometry.details.getValue("overbarAnchorPolicy").contains("Actual"))
                    assertTrue(
                        !seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$fontSizePx legacy font-reported bounds must reproduce the seam bug: $seam",
                    )
                    assertTrue(
                        abs(seam.centerlineErrorPx) > legacy.reportedBoundsShiftPx(fontSizePx) / 2f,
                        "$label/${case.label}/$fontSizePx synthetic reported-bounds shift must displace the seam: $seam",
                    )
                    println("SEAM-BEFORE face=$label kind=${case.label} size=$fontSizePx $seam")
                }
            }
        }

    @Test
    fun realOutlineSeamEdgesCentersAndThicknessMatchForBothFontsAndAllConstructionKinds() =
        withSeamFaces { label, face ->
            seamFontSizes().forEach { fontSizePx ->
                radicalCases().forEach { case ->
                    val result = MathLayoutEngine(face).layout(
                        case.source,
                        MathLayoutOptions(MathMode.Display, fontSizePx),
                    )
                    val group = result.outerRadicalGroup()
                    assertEquals(case.kind, group.shapeKind, "$label/${case.label}/$fontSizePx")
                    val constructionDecision = result.decisions.first {
                        it.name == "OpenTypeRadicalConstruction" && it.range.start == 0
                    }
                    assertEquals(
                        MathGlyphBoundsSource.Outline.toString(),
                        constructionDecision.details["baseGlyphBoundsSource"],
                    )
                    assertEquals(
                        listOf(MathGlyphBoundsSource.Outline).toString(),
                        constructionDecision.details["componentBoundsSources"],
                    )
                    assertEquals(
                        "Available(GlyphOutlineCrossSection)",
                        constructionDecision.details["baseOutlineEvidence"],
                    )
                    val componentEvidence = constructionDecision.details
                        .getValue("componentOutlineEvidences")
                    assertFalse(componentEvidence.contains("Unavailable"), componentEvidence)
                    assertTrue(componentEvidence.contains("Available(GlyphOutlineCrossSection)"), componentEvidence)
                    val geometry = result.radicalGeometryDecision()
                    assertEquals(
                        "Available(GlyphOutlineCrossSection)",
                        geometry.details["radicalTopStrokeEvidence"],
                    )
                    assertEquals("GlyphOutlineCrossSection", geometry.details["radicalTopStrokeEvidenceSource"])
                    assertEquals("FontAdapterTopStrokeTopAndRight", geometry.details["overbarAnchorPolicy"])
                    assertEquals("FontAdapterTopStrokeRight", geometry.details["overbarLeftPolicy"])
                    assertEquals("[Outline]", geometry.details["radicalGlyphBoundsSources"])
                    assertEquals(
                        if (case.kind == MathConstructionShapeKind.Assembly) {
                            "MathAssemblyOrthogonalAdvanceAllPartRecords"
                        } else {
                            "MeasuredMathRunLogicalWidthIndependentOfBoundsSource"
                        },
                        geometry.details["radicalLogicalAdvancePolicy"],
                    )
                    assertTrue(result.diagnostics.none {
                        it.code == DiagnosticCode.MissingConstructionOutlineEvidence
                    }, result.debugDump)
                    assertNear(
                        geometry.float("ruleTop"),
                        geometry.float("radicalPaintOriginY") + geometry.float("radicalTopStrokeTopPx"),
                    )
                    assertNear(
                        geometry.float("ruleLeft"),
                        geometry.float("radicalX") + geometry.float("radicalTopStrokeRightPx"),
                    )
                    val seam = face.radicalSeamGeometry(result.box, group)
                    assertEquals("ActualGlyphOutlineSeamCrossSection", seam.policy)
                    assertTrue(
                        abs(seam.topEdgeErrorPx) <= seam.coordinateAlignmentTolerancePx,
                        "$label/${case.label}/$fontSizePx mathematical top-edge mismatch: $seam",
                    )
                    assertTrue(
                        seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$fontSizePx real outline seam mismatch: $seam\n${result.debugDump}",
                    )
                    assertTrue(
                        seam.horizontalOverlapPx >= 0f,
                        "$label/${case.label}/$fontSizePx glyph top stroke reaches the overbar: $seam",
                    )
                    println("SEAM-GEOMETRY face=$label kind=${case.label} size=$fontSizePx $seam")
                }
            }
        }

    @Test
    fun layoutConsumesLocalTopStrokeEvidenceWithoutChangingLogicalAdvance() =
        withSeamFaces { label, face ->
            val source = "\\sqrt[3]{x}"
            val size = 32f
            val range = SourceRange(0, 5)
            val normalRun = face.shapeConstructionBase("√", size, range)
            val outlined = face.shapeOutlineConstructionBase("√", size, range)
            assertNear(normalRun.width, outlined.run.width)

            val baseline = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Display, size))
            val delta = face.mathFont.scaleDesignUnits(
                face.mathFont.constants.radicalRuleThickness,
                size,
            )
            val shiftedFace = TopStrokeOverrideFace(face, topDeltaPx = delta, rightDeltaPx = delta)
            val shifted = MathLayoutEngine(shiftedFace).layout(source, MathLayoutOptions(MathMode.Display, size))
            val before = baseline.radicalGeometryDecision()
            val after = shifted.radicalGeometryDecision()
            assertEquals("GlyphOutlineCrossSection", before.details["radicalTopStrokeEvidenceSource"])
            assertEquals(
                "SyntheticShiftedGlyphOutlineCrossSection",
                after.details["radicalTopStrokeEvidenceSource"],
            )
            assertNear(baseline.box.width, shifted.box.width)
            assertNear(before.float("radicalBoxAdvancePx"), after.float("radicalBoxAdvancePx"))
            assertEquals(before.details["radicalGlyphBoundsSources"], after.details["radicalGlyphBoundsSources"])
            assertEquals(
                "MeasuredMathRunLogicalWidthIndependentOfBoundsSource",
                after.details["radicalLogicalAdvancePolicy"],
            )
            assertNear(
                before.float("radicalPaintOriginY") - delta,
                after.float("radicalPaintOriginY"),
                "$label local top anchor moves only paint origin",
            )
            assertNear(
                before.float("ruleLeft") + delta,
                after.float("ruleLeft"),
                "$label local right anchor moves only overbar start",
            )
        }

    @Test
    fun independentOutlineOracleRejectsMovedAndResizedOverbars() =
        withSeamFaces { label, face ->
            radicalCases().forEach { case ->
                val result = MathLayoutEngine(face).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val group = result.outerRadicalGroup()
                val rule = result.box.rules.single { it.constructionGroupId == group.id }
                val thickness = rule.bottom - rule.top
                val mutations = listOf(
                    "up" to rule.copy(top = rule.top - thickness, bottom = rule.bottom - thickness),
                    "down" to rule.copy(top = rule.top + thickness, bottom = rule.bottom + thickness),
                    "thick" to rule.copy(top = rule.top - thickness / 2f, bottom = rule.bottom + thickness / 2f),
                    "thin" to rule.copy(top = rule.top + thickness / 4f, bottom = rule.bottom - thickness / 4f),
                )
                mutations.forEach { (mutation, mutatedRule) ->
                    val seam = face.radicalSeamGeometry(result.box.withRule(group, mutatedRule), group)
                    assertTrue(
                        !seam.edgesAndThicknessMatch,
                        "$label/${case.label}/$mutation must be rejected by the raw-outline oracle: $seam",
                    )
                    when (mutation) {
                        "up", "down" -> assertTrue(
                            abs(seam.centerlineErrorPx) > seam.coordinateAlignmentTolerancePx,
                        )
                        "thick", "thin" -> assertTrue(
                            abs(seam.thicknessErrorPx) > seam.strokeThicknessTolerancePx,
                        )
                    }
                }
            }
        }

    @Test
    fun finalUnionRasterHasTheSameHorizontalStrokeBandOnBothSidesOfTheSeam() =
        withSeamFaces { label, face ->
            radicalCases().forEach { case ->
                val result = MathLayoutEngine(face).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val group = result.outerRadicalGroup()
                listOf(0f, .25f, .5f, .75f).forEach { phase ->
                    val sample = sampleFinalUnion(face, result.box, group, phase, deviceScale = 8)
                    assertTrue(
                        abs(sample.left.top - sample.right.top) <= 1,
                        "$label/${case.label}/phase=$phase top raster edge: $sample",
                    )
                    assertTrue(
                        abs(sample.left.bottom - sample.right.bottom) <= 1,
                        "$label/${case.label}/phase=$phase bottom raster edge: $sample",
                    )
                    assertTrue(
                        abs(sample.left.thickness - sample.right.thickness) <= 1,
                        "$label/${case.label}/phase=$phase raster thickness: $sample",
                    )
                }
            }
        }

    @Test
    fun finalUnionAtOneAndTwoXHasStableAlphaProfilesAcrossTheSeam() =
        withSeamFaces { label, face ->
            radicalCases().forEach { case ->
                val result = MathLayoutEngine(face).layout(
                    case.source,
                    MathLayoutOptions(MathMode.Display, 32f),
                )
                val group = result.outerRadicalGroup()
                listOf(1, 2).forEach { deviceScale ->
                    listOf(0f, .25f, .5f, .75f).forEach { devicePhasePx ->
                        val sample = sampleFinalUnionAlphaProfiles(
                            face = face,
                            box = result.box,
                            group = group,
                            devicePhasePx = devicePhasePx,
                            deviceScale = deviceScale,
                        )
                        val alphaQuantizationPx = 4f / (255f * deviceScale)
                        val overbarUniformityTolerancePx = alphaQuantizationPx
                        assertTrue(
                            sample.atSeam.coveragePx >=
                                minOf(sample.left.coveragePx, sample.right.coveragePx) - alphaQuantizationPx &&
                                sample.atSeam.coveragePx <=
                                maxOf(sample.left.coveragePx, sample.right.coveragePx) + alphaQuantizationPx,
                            "$label/${case.label}/${deviceScale}x/phase=$devicePhasePx " +
                                "seam coverage has a gap or local darkening: $sample",
                        )
                        assertTrue(
                            sample.atSeam.values.indices.all { row ->
                                val lower = minOf(sample.left.values[row], sample.right.values[row]) -
                                    4f / 255f
                                val upper = maxOf(sample.left.values[row], sample.right.values[row]) +
                                    4f / 255f
                                sample.atSeam.values[row] in lower..upper
                            },
                            "$label/${case.label}/${deviceScale}x/phase=$devicePhasePx " +
                                "row alpha jumps outside the two-sided seam envelope: $sample",
                        )
                        assertTrue(
                            sample.nearOverbar.l1DistancePx(sample.right) <= overbarUniformityTolerancePx,
                            "$label/${case.label}/${deviceScale}x/phase=$devicePhasePx " +
                                "overlap locally thickens final union: $sample",
                        )
                    }
                }
            }
        }
}

private data class RadicalCase(
    val label: String,
    val source: String,
    val kind: MathConstructionShapeKind,
)

private fun radicalCases(): List<RadicalCase> = listOf(
    RadicalCase("base", "\\sqrt[3]{x}", MathConstructionShapeKind.BaseGlyph),
    RadicalCase("variant", "\\sqrt{\\frac{a}{b}}", MathConstructionShapeKind.Variant),
    RadicalCase(
        "assembly",
        "\\sqrt{" + (1..12).fold("x") { radicand, _ -> "\\frac{$radicand}{y}" } + "}",
        MathConstructionShapeKind.Assembly,
    ),
)

private fun seamFontSizes(): List<Float> = listOf(24f, 32f, 48f)

private fun org.tiqian.math.core.MathLayoutResult.outerRadicalGroup(): MathConstructionPaintGroup =
    box.constructionPaintGroups.first {
        it.kind == MathConstructionPaintKind.Radical && it.sourceRange.start == 0
    }

private fun MathBox.withRule(
    group: MathConstructionPaintGroup,
    replacement: MathRulePlacement,
): MathBox = copy(
    rules = rules.map { rule ->
        if (rule.constructionGroupId == group.id) replacement else rule
    },
)

private data class RasterBand(val top: Int, val bottom: Int) {
    val thickness: Int get() = bottom - top + 1
}

private data class RasterSeamSample(val left: RasterBand, val right: RasterBand)

private data class AlphaProfile(
    val values: List<Float>,
    val deviceScale: Int,
) {
    val coveragePx: Float = values.sum() / deviceScale

    fun l1DistancePx(other: AlphaProfile): Float =
        values.zip(other.values).sumOf { (left, right) -> abs(left - right).toDouble() }.toFloat() /
            deviceScale
}

private data class AlphaProfileSeamSample(
    val left: AlphaProfile,
    val atSeam: AlphaProfile,
    val nearOverbar: AlphaProfile,
    val right: AlphaProfile,
    val leftWorldX: Float,
    val seamWorldX: Float,
    val nearOverbarWorldX: Float,
    val rightWorldX: Float,
)

private fun sampleFinalUnion(
    face: SkiaMathFontFace,
    box: MathBox,
    group: MathConstructionPaintGroup,
    phase: Float,
    deviceScale: Int,
): RasterSeamSample {
    val seam = face.radicalSeamGeometry(box, group)
    val outline = assertIs<MathConstructionOutlineResult.Available>(face.constructionOutline(box, group)).path
    val bounds = outline.bounds
    val padding = 4
    val originX = floor(bounds.left).toInt() - padding
    val originY = floor(bounds.top).toInt() - padding
    val width = (ceil(bounds.right).toInt() - originX + padding) * deviceScale
    val height = (ceil(bounds.bottom).toInt() - originY + padding) * deviceScale
    val surface = Surface.makeRasterN32Premul(width, height)
    val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
    val paint = Paint().apply { color = Color.BLACK }
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        surface.canvas.save().also {
            surface.canvas.scale(deviceScale.toFloat(), deviceScale.toFloat())
            surface.canvas.translate(-originX + phase, -originY + phase)
            surface.canvas.drawPath(outline, paint)
            surface.canvas.restoreToCount(it)
        }
        assertTrue(surface.readPixels(bitmap, 0, 0))
        val designUnitPx = box.glyphs.first { it.constructionGroupId == group.id }.fontSizePx /
            face.mathFont.unitsPerEm
        val leftWorldX = minOf(seam.glyphStrokeRightPx, seam.overbarLeftPx) - 4f * designUnitPx
        val rightWorldX = seam.overbarLeftPx + seam.overbar.thicknessPx
        val leftColumn = floor((leftWorldX - originX + phase) * deviceScale).toInt().coerceIn(0, width - 1)
        val rightColumn = floor((rightWorldX - originX + phase) * deviceScale).toInt().coerceIn(0, width - 1)
        val searchTop = floor(
            (minOf(seam.glyphStroke.topPx, seam.overbar.topPx) - originY + phase - 2f) * deviceScale,
        ).toInt()
            .coerceIn(0, height - 1)
        val searchBottom = ceil(
            (maxOf(seam.glyphStroke.bottomPx, seam.overbar.bottomPx) - originY + phase + 2f) * deviceScale,
        ).toInt()
            .coerceIn(0, height - 1)
        return RasterSeamSample(
            left = bitmap.alphaBand(leftColumn, searchTop, searchBottom),
            right = bitmap.alphaBand(rightColumn, searchTop, searchBottom),
        )
    } finally {
        paint.close()
        bitmap.close()
        surface.close()
    }
}

private fun sampleFinalUnionAlphaProfiles(
    face: SkiaMathFontFace,
    box: MathBox,
    group: MathConstructionPaintGroup,
    devicePhasePx: Float,
    deviceScale: Int,
): AlphaProfileSeamSample {
    val seam = face.radicalSeamGeometry(box, group)
    val outline = assertIs<MathConstructionOutlineResult.Available>(face.constructionOutline(box, group)).path
    val bounds = outline.bounds
    val fontSizePx = box.glyphs.first { it.constructionGroupId == group.id }.fontSizePx
    val designUnitPx = fontSizePx / face.mathFont.unitsPerEm
    val ruleThicknessPx = seam.overbar.thicknessPx
    val phasePx = devicePhasePx / deviceScale
    val paddingPx = 2f * ruleThicknessPx
    val originX = floor(bounds.left - paddingPx).toInt()
    val originY = floor(bounds.top - paddingPx).toInt()
    val width = ceil((bounds.right - originX + paddingPx) * deviceScale).toInt()
    val height = ceil((bounds.bottom - originY + paddingPx) * deviceScale).toInt()
    val surface = Surface.makeRasterN32Premul(width, height)
    val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
    val paint = Paint().apply { color = Color.BLACK }
    try {
        surface.canvas.clear(Color.TRANSPARENT)
        surface.canvas.save().also {
            surface.canvas.scale(deviceScale.toFloat(), deviceScale.toFloat())
            surface.canvas.translate(-originX + phasePx, -originY + phasePx)
            surface.canvas.drawPath(outline, paint)
            surface.canvas.restoreToCount(it)
        }
        assertTrue(surface.readPixels(bitmap, 0, 0))
        val leftWorldX = seam.glyphStrokeRightPx - 4f * designUnitPx
        val seamWorldX = seam.overbarLeftPx
        val nearOverbarWorldX = seam.overbarLeftPx + ruleThicknessPx / 2f
        val rightWorldX = seam.overbarLeftPx + 2f * ruleThicknessPx
        val searchTop = floor(
            (minOf(seam.glyphStroke.topPx, seam.overbar.topPx) - originY + phasePx - paddingPx) *
                deviceScale,
        ).toInt().coerceIn(0, height - 1)
        val searchBottom = ceil(
            (maxOf(seam.glyphStroke.bottomPx, seam.overbar.bottomPx) - originY + phasePx + paddingPx) *
                deviceScale,
        ).toInt().coerceIn(0, height - 1)
        fun profileAt(worldX: Float): AlphaProfile {
            val deviceX = (worldX - originX + phasePx) * deviceScale - .5f
            val leftColumn = floor(deviceX).toInt().coerceIn(0, width - 1)
            val rightColumn = (leftColumn + 1).coerceIn(0, width - 1)
            val rightWeight = (deviceX - floor(deviceX)).coerceIn(0f, 1f)
            return AlphaProfile(
                values = (searchTop..searchBottom).map { y ->
                    bitmap.getAlphaf(leftColumn, y) * (1f - rightWeight) +
                        bitmap.getAlphaf(rightColumn, y) * rightWeight
                },
                deviceScale = deviceScale,
            )
        }
        return AlphaProfileSeamSample(
            left = profileAt(leftWorldX),
            atSeam = profileAt(seamWorldX),
            nearOverbar = profileAt(nearOverbarWorldX),
            right = profileAt(rightWorldX),
            leftWorldX = leftWorldX,
            seamWorldX = seamWorldX,
            nearOverbarWorldX = nearOverbarWorldX,
            rightWorldX = rightWorldX,
        )
    } finally {
        paint.close()
        bitmap.close()
        surface.close()
    }
}

private fun Bitmap.alphaBand(x: Int, searchTop: Int, searchBottom: Int): RasterBand {
    val rows = (searchTop..searchBottom).filter { y -> getAlphaf(x, y) >= .1f }
    require(rows.isNotEmpty()) { "No final union coverage at x=$x in $searchTop..$searchBottom" }
    return RasterBand(rows.first(), rows.last())
}

private inline fun withSeamFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

/**
 * Models the old adapter contract without depending on the host rasterizer's bbox padding.
 * Shift exact bounds up by 1/8 em while leaving paths, advances and bbox heights unchanged.
 * This preserves construction selection but deliberately supplies a wrong fallback anchor.
 * Construction measurements report unavailable outline evidence; ordinary glyphs retain
 * the delegate's measurements so the test only corrupts the construction fallback anchor.
 */
private class ShiftedFontReportedConstructionFace(
    private val delegate: SkiaMathFontFace,
) : MathFontFace {
    override val mathFont = delegate.mathFont

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

    override fun shapeConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)
        .run.withShiftedReportedBounds(fontSizePx)

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

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = MeasuredOutlineConstructionRun(
        run = delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
            .withShiftedReportedBounds(fontSizePx),
        evidence = MathConstructionOutlineEvidence.Unavailable(
            MathConstructionOutlineUnavailableReason.AdapterDoesNotProvideOutlineEvidence,
        ),
    )

    fun reportedBoundsShiftPx(fontSizePx: Float): Float = fontSizePx / 8f

    private fun MeasuredMathRun.withShiftedReportedBounds(fontSizePx: Float): MeasuredMathRun {
        val shift = reportedBoundsShiftPx(fontSizePx)
        val shiftedGlyphs = glyphs.map { glyph ->
            glyph.copy(inkBounds = glyph.inkBounds.translated(0f, -shift))
        }
        return copy(
            glyphs = shiftedGlyphs,
            ascent = (-(shiftedGlyphs.minOfOrNull { it.inkBounds.top } ?: 0f)).coerceAtLeast(0f),
            descent = (shiftedGlyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f).coerceAtLeast(0f),
            boundsSource = MathGlyphBoundsSource.FontReported,
        )
    }
}

private class TopStrokeOverrideFace(
    private val delegate: SkiaMathFontFace,
    private val topDeltaPx: Float,
    private val rightDeltaPx: Float,
) : MathFontFace by delegate {
    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate
        .shapeOutlineConstructionBase(text, fontSizePx, sourceRange)
        .shiftedTopStroke()

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate
        .measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
        .shiftedTopStroke()

    private fun MeasuredOutlineConstructionRun.shiftedTopStroke(): MeasuredOutlineConstructionRun = copy(
        evidence = when (val current = evidence) {
            is MathConstructionOutlineEvidence.Available -> current.copy(
                topStroke = current.topStroke.copy(
                    topPx = current.topStroke.topPx + topDeltaPx,
                    bottomPx = current.topStroke.bottomPx + topDeltaPx,
                    rightPx = current.topStroke.rightPx + rightDeltaPx,
                ),
                source = "SyntheticShiftedGlyphOutlineCrossSection",
            )
            is MathConstructionOutlineEvidence.Unavailable -> current
        },
    )
}

private class MissingSelectedConstructionOutlineFace(
    private val delegate: SkiaMathFontFace,
) : MathFontFace by delegate {
    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate
        .measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
        .copy(
            evidence = MathConstructionOutlineEvidence.Unavailable(
                MathConstructionOutlineUnavailableReason.GlyphOutlineUnavailable,
            ),
        )
}

private fun MathLayoutResult.radicalGeometryDecision(): MathLayoutDecision =
    decisions.first { it.name == "OpenTypeMathRadical" && it.range.start == 0 }

private fun MathLayoutDecision.float(key: String): Float = details.getValue(key).toFloat()

private fun assertNear(
    expected: Float,
    actual: Float,
    label: String = "",
    epsilon: Float = .02f,
) = assertTrue(
    abs(expected - actual) <= epsilon,
    "$label expected=$expected actual=$actual epsilon=$epsilon",
)
