package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator

class MathOperatorOutlineGeometryTest {
    @Test
    fun reportedBoundsDoNotChangeVariantsOrSideScriptGeometry() = withFaces { label, face ->
        val changedFace = ChangedOperatorBoundsFace(face)
        val options = listOf(
            MathLayoutOptions(MathMode.Inline, 32f),
            MathLayoutOptions(MathMode.Display, 32f),
            MathLayoutOptions(MathMode.Inline, 32f, initialStyle = MathStyle.Script),
            MathLayoutOptions(MathMode.Inline, 32f, initialStyle = MathStyle.ScriptScript),
        )
        options.forEach { option ->
            listOf("\\int^1", "\\sum\\nolimits_0^1").forEach { source ->
                val expected = MathLayoutEngine(face).layout(source, option)
                val actual = MathLayoutEngine(changedFace).layout(source, option)
                val context = "$label/$option/$source"
                assertEquals(expected.diagnostics, actual.diagnostics, context)
                assertNear(expected.box.width, actual.box.width, "$context width")
                assertNear(expected.box.texCleanBoxMetrics.ascent, actual.box.texCleanBoxMetrics.ascent, "$context ascent")
                assertNear(expected.box.texCleanBoxMetrics.descent, actual.box.texCleanBoxMetrics.descent, "$context descent")
                assertEquals(expected.box.glyphs.map { it.glyphId }, actual.box.glyphs.map { it.glyphId }, context)
                expected.box.glyphs.zip(actual.box.glyphs).forEach { (before, after) ->
                    assertEquals(before.faceId, after.faceId, context)
                    assertEquals(before.sourceRange, after.sourceRange, context)
                    assertEquals(before.style, after.style, context)
                    assertNear(before.advance, after.advance, "$context advance")
                    assertNear(before.x, after.x, "$context x")
                    assertNear(before.baselineY, after.baselineY, "$context baseline")
                }
                val before = expected.operatorDetails()
                val after = actual.operatorDetails()
                assertEquals(before["construction"], after["construction"], context)
                listOf("normalGlyphExtentPx", "variantSelectionTargetPx", "achievedAdvancePx").forEach { key ->
                    assertNear(before.getValue(key).toFloat(), after.getValue(key).toFloat(), "$context/$key")
                }
                assertEquals("Outline", after["operatorCenterBoundsSource"], context)
            }
        }
    }

    @Test
    fun operatorKeepsPaintBoundsAndCentersItsActualOutline() = withFaces { label, face ->
        val changedFace = ChangedOperatorBoundsFace(face)
        val result = MathLayoutEngine(changedFace).layout("\\int", MathLayoutOptions(MathMode.Inline, 32f))
        val placement = result.box.glyphs.single()
        val reported = checkNotNull(changedFace.lastRun).glyphs.single()
        val expectedPaint = reported.inkBounds.translated(placement.x, placement.baselineY)
        assertEquals(expectedPaint, placement.inkBounds, "$label paint bounds are retained")
        val outline = face.measureGlyphOutlineBounds(
            placement.glyphId, placement.fontSizePx, placement.style, placement.sourceRange,
        ).glyphs.single().inkBounds
        val placedOutlineCenter = (outline.top + outline.bottom) / 2f + placement.baselineY
        val axis = result.operatorDetails().getValue("axisY").toFloat()
        assertNear(axis, placedOutlineCenter, "$label native contour is centered")
        val paintCenter = (placement.inkBounds.top + placement.inkBounds.bottom) / 2f
        assertTrue(abs(paintCenter - axis) > 1f, "$label fixture must distinguish paint and outline centers")
    }

    @Test
    fun missingOutlineRetainsTheReportedBoxFallback() = withFaces { label, face ->
        val fallback = ChangedOperatorBoundsFace(face, provideOutline = false)
        val result = MathLayoutEngine(fallback).layout("\\int", MathLayoutOptions(MathMode.Inline, 32f))
        val details = result.operatorDetails()
        assertEquals("FontReported", details["normalGlyphBoundsSource"], label)
        assertEquals("FontReported", details["operatorCenterBoundsSource"], label)
        // The synthetic glyph is wholly above the baseline: its legacy box is [-20, 0].
        assertNear(-10f, details.getValue("inkCenterBefore").toFloat(), "$label old box center")
        assertNear(
            details.getValue("axisY").toFloat() + 10f,
            result.box.glyphs.single().baselineY,
            "$label fallback keeps the old baseline-inclusive centering",
        )
    }

    private fun MathLayoutResult.operatorDetails(): Map<String, String> =
        decisions.single { it.name == "TeXOperatorNoad" }.details

    private fun withFaces(block: (String, SkiaMathFontFace) -> Unit) {
        listOf("Lete Sans Math" to LeteSansMath.load(), "STIX Two Math" to StixTwoMath.load())
            .forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
    }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= 0.002f, "$message: expected $expected, got $actual")
    }
}

/** Change backend paint measurements without changing the font, shaping, or real outlines. */
private class ChangedOperatorBoundsFace(
    private val delegate: SkiaMathFontFace,
    private val provideOutline: Boolean = true,
) : MathFontFace by delegate {
    var lastRun: MeasuredMathRun? = null
        private set

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator {
        val resolved = delegate.resolveOperator(request, fontSizePx)
        val run = changedBounds(resolved.run)
        lastRun = run
        return resolved.copy(run = run)
    }

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = if (provideOutline) {
        delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
    } else {
        changedBounds(delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange))
    }

    private fun changedBounds(run: MeasuredMathRun): MeasuredMathRun {
        val glyphs = run.glyphs.map { glyph ->
            glyph.copy(inkBounds = glyph.inkBounds.copy(
                top = if (provideOutline) glyph.inkBounds.top - 19f else -20f,
                bottom = if (provideOutline) glyph.inkBounds.bottom + 7f else -10f,
            ))
        }
        return run.copy(
            glyphs = glyphs,
            ascent = glyphs.maxOfOrNull { (-it.inkBounds.top).coerceAtLeast(0f) } ?: 0f,
            descent = glyphs.maxOfOrNull { it.inkBounds.bottom.coerceAtLeast(0f) } ?: 0f,
            boundsSource = MathGlyphBoundsSource.FontReported,
        )
    }
}
