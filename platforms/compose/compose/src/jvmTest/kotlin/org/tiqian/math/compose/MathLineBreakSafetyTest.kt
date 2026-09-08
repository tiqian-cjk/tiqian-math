package org.tiqian.math.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaMathFormulaRenderPreflight
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaProductionPipeline
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathPreparedFormula
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MathLineBreakSafetyTest {
    @Test
    fun inlineBreakFailureDrawsOnlyHostFallback() = assertLateFallback(MathMode.Inline)

    @Test
    fun displayBreakFailureDrawsOnlyHostFallback() = assertLateFallback(MathMode.Display)

    @Test
    fun inlineBreakFailureThrowsInStrictMode() = assertLateStrictFailure(MathMode.Inline)

    @Test
    fun displayBreakFailureThrowsInStrictMode() = assertLateStrictFailure(MathMode.Display)

    @Test
    fun ordinaryUnbreakableOverflowStillDrawsInBothModes() {
        for (mode in MathMode.entries) {
            SkiaMathFontFace(LeteSansMath.load()).use { face ->
                var layoutCalls = 0
                var fallbackCalls = 0
                val engine = lateBreakpointLimitEngine(face)
                ImageComposeScene(width = 120, height = 80, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMathCapabilityBoundaryForTest(
                            source = "abcdefgh",
                            fontFace = face,
                            capabilityEngine = engine,
                            strict = false,
                            mode = mode,
                            displayWidthPx = 30f,
                            modifier = Modifier.size(30.dp, 70.dp),
                            onMathLayout = { layoutCalls += 1 },
                            fallback = { fallbackCalls += 1 },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    assertTrue((0 until pixels.height).any { y ->
                        (0 until pixels.width).any { x ->
                            val pixel = pixels[x, y]
                            pixel.red < 0.35f && pixel.green < 0.35f && pixel.blue < 0.35f
                        }
                    }, "$mode must still paint ordinary overflow")
                }
                assertTrue(layoutCalls > 0, mode.name)
                assertEquals(0, fallbackCalls, mode.name)
            }
        }
    }

    private fun assertLateFallback(mode: MathMode) {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = lateBreakpointLimitEngine(face)
            var layoutCalls = 0
            var captured: MathFormulaCapabilityResult.FallbackRequired? = null
            ImageComposeScene(width = 120, height = 80, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMathCapabilityBoundaryForTest(
                        source = "a=b",
                        fontFace = face,
                        capabilityEngine = engine,
                        strict = false,
                        mode = mode,
                        displayWidthPx = 80f,
                        onMathLayout = { layoutCalls += 1 },
                        fallback = { failure ->
                            captured = failure
                            Canvas(Modifier.size(50.dp, 30.dp)) { drawRect(Color.Magenta) }
                        },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                var magenta = 0
                var dark = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red > 0.9f && pixel.blue > 0.9f && pixel.green < 0.1f) magenta += 1
                    if (pixel.red < 0.35f && pixel.green < 0.35f && pixel.blue < 0.35f) dark += 1
                }
                assertEquals(50 * 30, magenta, mode.name)
                assertEquals(0, dark, "$mode paints no partial formula after break rejection")
            }
            assertEquals(0, layoutCalls, "rejected breaks must not notify successful layout")
            assertResourceEvidence(assertNotNull(captured))
        }
    }

    private fun assertLateStrictFailure(mode: MathMode) {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = lateBreakpointLimitEngine(face)
            var layoutCalls = 0
            val failure = assertFailsWith<MathFormulaStrictException> {
                ImageComposeScene(width = 120, height = 80, density = Density(1f)) {
                    TiqianMathCapabilityBoundaryForTest(
                        source = "a=b",
                        fontFace = face,
                        capabilityEngine = engine,
                        strict = true,
                        mode = mode,
                        displayWidthPx = 80f,
                        onMathLayout = { layoutCalls += 1 },
                        fallback = { error("strict mode must not invoke fallback") },
                    )
                }.use { it.render() }
            }
            assertEquals(0, layoutCalls, mode.name)
            assertResourceEvidence(failure.fallback)
        }
    }

    private fun assertResourceEvidence(failure: MathFormulaCapabilityResult.FallbackRequired) {
        assertEquals("a=b", failure.source)
        assertEquals(MathFormulaCapabilityCategory.ResourceLimitExceeded, failure.reasons.single().category)
        assertEquals(DiagnosticCode.BreakpointCountLimitExceeded, failure.diagnostics.single().code)
        assertEquals(SourceRange(0, 3), failure.diagnostics.single().range)
    }
}

/** Keep initial preflight Ready, then let the real width-dependent breaker reject its budget. */
private fun lateBreakpointLimitEngine(face: SkiaMathFontFace): MathFormulaCapabilityEngine {
    val delegate = MathLayoutEngine(face)
    val pipeline = object : MathFormulaProductionPipeline {
        override fun prepare(source: String): MathPreparedFormula = delegate.prepare(source)

        override fun layout(prepared: MathPreparedFormula, options: MathLayoutOptions): MathLayoutResult {
            val result = delegate.layout(prepared, options)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            return result.copy(resourceLimits = result.resourceLimits.copy(maximumBreakpointCount = 0))
        }
    }
    val engine = MathFormulaCapabilityEngine(pipeline, SkiaMathFormulaRenderPreflight(face))
    // Verify that the test cannot accidentally pass through the earlier error boundary.
    for (mode in MathMode.entries) {
        assertIs<MathFormulaCapabilityResult.Ready>(engine.evaluate("a=b", MathLayoutOptions(mode = mode)))
    }
    return engine
}
