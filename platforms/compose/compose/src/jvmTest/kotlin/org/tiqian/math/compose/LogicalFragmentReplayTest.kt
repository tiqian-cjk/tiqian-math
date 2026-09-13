package org.tiqian.math.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.use
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LogicalFragmentReplayTest {
    @Test fun splitSignedKernReplaysOriginalGlyphCoordinatesAndAdvance() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("a=\\!b", MathLayoutOptions(fontSizePx = 24f))
            var x = 0f
            val glyphs = listOf(0..0, 1..1, 2..3).flatMap { range ->
                val plan = RenderPlan.fragmentRangeLogical(result, range, 0f)
                val positioned = plan.boxes.flatMap { box -> box.box.glyphs.map { glyph ->
                    glyph.copy(x = x + box.x + glyph.x, baselineY = box.baselineFromTop - plan.firstBaseline + glyph.baselineY)
                } }
                x += plan.width
                positioned
            }
            assertEquals(result.box.width, x, 0.001f)
            assertEquals(result.box.glyphs.size, glyphs.size)
            result.box.glyphs.zip(glyphs).forEach { (expected, actual) ->
                assertEquals(expected.glyphId, actual.glyphId)
                assertEquals(expected.sourceRange, actual.sourceRange)
                assertEquals(expected.x, actual.x, 0.001f)
                assertEquals(expected.baselineY, actual.baselineY, 0.001f)
            }
        }
    }

    @Test fun splitCanvasMatchesWholeFormulaRaster() {
        fun render(split: Boolean) = ImageComposeScene(width = 180, height = 90) {
            val formula = rememberTiqianMathFormula("a=\\!b", fontSizePx = 24f, color = Color.Black)
            Box {
                if (split) {
                    var x = 20f
                    for (range in listOf(0..0, 1..1, 2..3)) {
                        val metrics = formula.logicalFragmentMetrics(range)!!
                        val origin = x
                        TiqianMathLogicalFragmentCanvas(formula, range, Modifier.graphicsLayer {
                            translationX = origin
                            translationY = 50f - metrics.baselineFromTopPx
                        })
                        x += metrics.widthPx
                    }
                } else {
                    val metrics = formula.presentationMetrics()!!
                    TiqianMathFormulaCanvas(formula, Modifier.graphicsLayer {
                        translationX = 20f
                        translationY = 50f - metrics.baselineFromTopPx
                    })
                }
            }
        }.use { it.render(0L).toComposeImageBitmap().toPixelMap() }
        val whole = render(false)
        val split = render(true)
        var difference = 0f
        var ink = 0f
        for (y in 0 until whole.height) for (x in 0 until whole.width) {
            difference += kotlin.math.abs(whole[x, y].alpha - split[x, y].alpha)
            ink += whole[x, y].alpha
        }
        assertTrue(ink > 30f)
        assertTrue(difference < 1f, "split canvas alpha difference=$difference")
    }
}
