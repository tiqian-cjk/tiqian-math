package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/**
 * Reviewed Tectonic 0.17.0 / XeTeX box traces for an inline 24bp formula rasterized at 96 dpi.
 * 24bp is exactly 32 device pixels; the math face was initialized at that size so MATH constants
 * and the ScriptScript face use the same scale as the formula. The trace reproducers under
 * `preview/tectonic` load these exact repository OTFs. Tectonic supplies glyph IDs, kerns, box
 * advances, and origins. The additional outline probes are independently calculated with
 * fontTools BoundsPen from the repository OTFs, not from platform raster bounds:
 * degree x bounds are 37..530 (Lete) / 59..494 (STIX) at 17.6 px per 1000 units;
 * radical x minima are 16 / 18 at 32 px per 1000 units. Add the traced origins below.
 */
class TectonicRadicalDegreeOracleTest {
    @Test
    fun sameFontInlineDegreeAndRadicalOriginsMatchTectonic() {
        val cases = listOf(
            TectonicOracle(
                label = "Lete Sans Math",
                face = SkiaMathFontFace(LeteSansMath.load()),
                degreeGlyphId = 3051u,
                radicalGlyphId = 557u,
                radicandGlyphId = 3625u,
                kernBeforePx = 8.832f,
                degreeAdvancePx = 10.208f,
                kernAfterPx = -12.8f,
                radicalOriginPx = 6.24f,
                degreeOutlineLeftPx = 9.4832f,
                degreeOutlineRightPx = 18.16f,
                radicalTopStrokeRightPx = 27.488f,
                degreeToRadicalOutlineGapPx = -11.408f,
            ),
            TectonicOracle(
                label = "STIX Two Math",
                face = SkiaMathFontFace(StixTwoMath.load()),
                degreeGlyphId = 4525u,
                radicalGlyphId = 1657u,
                radicandGlyphId = 3323u,
                kernBeforePx = 2.08f,
                degreeAdvancePx = 9.8736f,
                kernAfterPx = -10.72f,
                radicalOriginPx = 1.2336f,
                degreeOutlineLeftPx = 3.1184f,
                degreeOutlineRightPx = 10.7744f,
                radicalTopStrokeRightPx = 27.7616f,
                degreeToRadicalOutlineGapPx = -8.9648f,
            ),
        )

        cases.forEach { oracle ->
            oracle.face.use { face ->
                val result = MathLayoutEngine(face).layout(
                    SOURCE,
                    MathLayoutOptions(MathMode.Inline, FONT_SIZE_PX),
                )
                val geometry = result.decisions.single { it.name == "OpenTypeMathRadical" }
                val degreeGlyph = result.box.glyphs.single { it.sourceRange == DEGREE_RANGE }
                val radicalGlyph = result.box.glyphs.single { it.sourceRange == COMMAND_RANGE }
                val radicandGlyph = result.box.glyphs.single { it.sourceRange == RADICAND_RANGE }
                val degreeResolution = result.decisions.single {
                    it.name == "TeXMathSymbolResolution" && it.range == DEGREE_RANGE
                }
                val degreeFontSize = FONT_SIZE_PX *
                    face.mathFont.constants.scriptScriptPercentScaleDown / 100f
                val directDegreeRun = face.shape(
                    "3",
                    degreeFontSize,
                    MathStyle.ScriptScript,
                    DEGREE_RANGE,
                )

                val topStrokeX = geometry.float("radicalX") + geometry.float("radicalTopStrokeRightPx")
                val degreeOutline = face.placedOutlineBounds(degreeGlyph)
                val radicalOutline = face.placedOutlineBounds(radicalGlyph)
                val degreeToRadicalOutlineGap = radicalOutline.left - degreeOutline.right
                println(
                    "tectonic-oracle=${oracle.label} " +
                        "degreeOutline=$degreeOutline radicalOrigin=${geometry.float("radicalX")} " +
                        "topStrokeX=$topStrokeX degreeToRadicalOutlineGap=$degreeToRadicalOutlineGap " +
                        "degreeGlyphAdvance=${degreeGlyph.advance} degreeItalicCorrection=" +
                        degreeResolution.details["italicCorrectionPx"],
                )

                assertEquals(oracle.degreeGlyphId, degreeGlyph.glyphId, "${oracle.label} degree glyph")
                assertEquals(oracle.radicalGlyphId, radicalGlyph.glyphId, "${oracle.label} radical glyph")
                assertEquals(oracle.radicandGlyphId, radicandGlyph.glyphId, "${oracle.label} radicand glyph")
                assertNear(
                    oracle.degreeAdvancePx,
                    directDegreeRun.width,
                    "${oracle.label} subpixel shaper run advance",
                )
                assertNear(
                    directDegreeRun.width,
                    degreeGlyph.advance,
                    "${oracle.label} final glyph advance stays equal to the shaper run",
                )
                assertNear(oracle.kernBeforePx, geometry.float("degreeX"), "${oracle.label} degree origin")
                assertNear(oracle.degreeAdvancePx, geometry.float("degreeWidthPx"), "${oracle.label} degree advance")
                assertNear(oracle.kernAfterPx, geometry.float("adjustedRadicalKernAfterDegreePx"), "${oracle.label} after kern")
                assertNear(oracle.radicalOriginPx, geometry.float("radicalX"), "${oracle.label} radical origin")
                assertNear(oracle.kernBeforePx, degreeGlyph.x, "${oracle.label} placed degree origin")
                assertNear(oracle.radicalOriginPx, radicalGlyph.x, "${oracle.label} placed radical origin")
                assertNear(oracle.degreeOutlineLeftPx, degreeOutline.left, "${oracle.label} degree outline left")
                assertNear(oracle.degreeOutlineRightPx, degreeOutline.right, "${oracle.label} degree outline right")
                assertNear(
                    oracle.radicalTopStrokeRightPx,
                    topStrokeX,
                    "${oracle.label} radical top-stroke right edge",
                )
                assertNear(
                    oracle.degreeToRadicalOutlineGapPx,
                    degreeToRadicalOutlineGap,
                    "${oracle.label} degree-to-radical outline distance",
                )
                assertEquals(
                    "TeXMakeRadicalSignedBeforeAndWidthPlusBeforeAfterClamp",
                    geometry.details["degreeHorizontalPlacementPolicy"],
                )
            }
        }
    }

    private fun SkiaMathFontFace.placedOutlineBounds(glyph: MathGlyphPlacement): MathRect =
        checkNotNull(glyphPath(glyph.glyphId, glyph.fontSizePx)).use { outline ->
            check(!outline.isEmpty) { "Glyph ${glyph.glyphId} has no outline" }
            val bounds = outline.computeTightBounds()
            MathRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                .translated(glyph.x, glyph.baselineY)
        }

    private fun org.tiqian.math.core.MathLayoutDecision.float(key: String): Float =
        checkNotNull(details[key]).toFloat()

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= 0.02f, "$message: expected $expected, got $actual")
    }

    private data class TectonicOracle(
        val label: String,
        val face: SkiaMathFontFace,
        val degreeGlyphId: UShort,
        val radicalGlyphId: UShort,
        val radicandGlyphId: UShort,
        val kernBeforePx: Float,
        val degreeAdvancePx: Float,
        val kernAfterPx: Float,
        val radicalOriginPx: Float,
        val degreeOutlineLeftPx: Float,
        val degreeOutlineRightPx: Float,
        val radicalTopStrokeRightPx: Float,
        val degreeToRadicalOutlineGapPx: Float,
    )

    private companion object {
        const val SOURCE = "\\sqrt[3]{X}"
        const val FONT_SIZE_PX = 32f
        val COMMAND_RANGE = SourceRange(0, 5)
        val DEGREE_RANGE = SourceRange(6, 7)
        val RADICAND_RANGE = SourceRange(9, 10)
    }
}
