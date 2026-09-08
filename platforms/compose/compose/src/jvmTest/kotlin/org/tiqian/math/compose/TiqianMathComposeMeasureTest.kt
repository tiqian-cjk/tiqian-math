package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFamily
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaReplayCatalog
import org.tiqian.math.font.skia.SkiaReplayFace
import org.tiqian.math.font.skia.SkiaMathTextRunProvider
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathStyle
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.breakIntoLines
import org.tiqian.math.layout.breakResponsiveDisplayLines
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TiqianMathComposeMeasureTest {
    @Test
    fun renderPlanMeasuresInkOverhangAndUsesSafeLogicalBaseline() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("x", MathLayoutOptions(MathMode.Inline, 40f))
            val plan = RenderPlan.unbroken(result)
            assertNear(result.box.visualWidth, plan.width)
            assertNear(-result.box.visualLeft, plan.boxes.single().x)
            assertNear(result.lineMetrics.logicalAscentPx, plan.firstBaseline)
            assertNear(result.lineMetrics.logicalHeightPx, plan.height)
            assertTrue(plan.firstBaseline > result.box.ascent, "host baseline includes logical font safety")
            result.box.glyphs.forEach { glyph ->
                val left = plan.boxes.single().x + glyph.inkBounds.left
                val right = plan.boxes.single().x + glyph.inkBounds.right
                val top = plan.firstBaseline + glyph.inkBounds.top
                val bottom = plan.firstBaseline + glyph.inkBounds.bottom
                assertTrue(left >= -0.02f && right <= plan.width + 0.02f, "horizontal ink is measured")
                assertTrue(top >= -0.02f && bottom <= plan.height + 0.02f, "vertical ink is inside safe line extents")
            }
        }
    }

    @Test
    fun composeMeasuresAndDrawsTheEngineOwnedGlyphAndRulePlacements() {
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 300, height = 180) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMath(
                    source = "x_1^2+\\frac{a+b}{\\binom{n}{k}}=y_2^3",
                    modifier = Modifier.width(180.dp),
                    fontSizePx = 30f,
                    onMathLayout = { observed = it },
                )
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            var dark = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f) dark++
                }
            }
            val layout = assertNotNull(observed)
            assertTrue(layout.box.glyphs.isNotEmpty())
            assertTrue(layout.box.rules.isNotEmpty())
            assertTrue(layout.breakOpportunities.isNotEmpty())
            assertTrue(dark > 250, "expected replayed glyph/rule ink, got $dark dark pixels")
        }
    }

    @Test
    fun localTextStyleDensityFontScaleAndPaddingReachActualComposeMeasurement() {
        val density = Density(density = 2f, fontScale = 1.5f)
        val style = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, color = Color.Black)
        val plain = measureInCompose("x", style, density, Modifier)
        val padded = measureInCompose("x", style, density, Modifier.padding(4.dp))

        assertNear(60f, plain.layout.box.glyphs.single().fontSizePx)
        assertEquals(84, plain.measurement.height, "28sp consumes density=2 and fontScale=1.5")
        assertTrue(plain.measurement.firstBaseline in 1 until plain.measurement.height)
        assertEquals(plain.measurement.width + 16, padded.measurement.width)
        assertEquals(plain.measurement.height + 16, padded.measurement.height)
        assertEquals(plain.measurement.firstBaseline + 8, padded.measurement.firstBaseline)
    }

    @Test
    fun realInlineTextHostAlignsBaselinesAndReservesEveryChildExtent() {
        val style = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, color = Color.Black)
        val simple = measureInlineHost("x", style)
        val tall = measureInlineHost("\\frac{x}{y}", style)

        for (measurement in listOf(simple, tall)) {
            val children = measurement.children
            children.forEach { child ->
                assertTrue(child.firstBaseline in 0..child.height, "missing or invalid baseline: $measurement")
            }
            val placedBaselines = children.map { it.topInRoot + it.firstBaseline }
            assertEquals(1, placedBaselines.distinct().size, "actual placed baselines must align: $measurement")

            // System text and math fonts may split the same line height differently.
            // Verify the real Row's measured extent against all children's baseline needs.
            val requiredAscent = children.maxOf { it.firstBaseline }
            val requiredDescent = children.maxOf { it.height - it.firstBaseline }
            assertEquals(requiredAscent + requiredDescent, measurement.rowHeight, "row extent: $measurement")
            assertEquals(measurement.rowTopInRoot, children.minOf { it.topInRoot }, "top placement: $measurement")
            assertEquals(
                measurement.rowTopInRoot + measurement.rowHeight,
                children.maxOf { it.topInRoot + it.height },
                "bottom placement: $measurement",
            )
        }
        assertEquals(40, simple.children[1].height, "simple symbol fits requested text line height")
        assertTrue(tall.children[1].height > simple.children[1].height, "fraction expands by intrinsic safe geometry")
        assertTrue(tall.rowHeight > simple.rowHeight)
    }

    @Test
    fun actualComposeMultilineBoundsBaselinePaddingAndRasterAreConsistent() {
        val style = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, color = Color.Black)
        var measured: MeasureSnapshot? = null
        var observed: MathLayoutResult? = null
        val scene = ImageComposeScene(width = 140, height = 260, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                CompositionLocalProvider(LocalTextStyle provides style) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "a+b+c+d+e+f",
                            modifier = Modifier.width(120.dp).padding(8.dp),
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
        }
        scene.use {
            val pixels = it.render().toComposeImageBitmap().toPixelMap()
            val snapshot = assertNotNull(measured)
            val layout = assertNotNull(observed)
            val broken = layout.breakIntoLines(104f)
            assertTrue(broken.lines.size > 1)
            assertEquals(120, snapshot.width)
            assertTrue(snapshot.height > 2 * 38, "multiple actual Compose lines are measured")
            assertTrue(snapshot.firstBaseline >= 8 && snapshot.firstBaseline < snapshot.height - 8)

            val ink = darkPixelBounds(pixels)
            assertTrue(
                ink.left >= 8 && ink.right <= snapshot.width - 8,
                "horizontal padding and overhang are measured: ink=$ink size=$snapshot",
            )
            assertTrue(ink.top >= 8 && ink.bottom < snapshot.height - 8, "vertical ink is not cropped")
            assertTrue(darkRowBands(pixels, snapshot.width, snapshot.height).size >= 2, "raster contains multiple separated math lines")
        }
    }

    @Test
    fun fractionalDensityDisplayFitKeepsZeroScrollRange() {
        // NoScrollForSubpixelExcess: constraints -> Dp -> px round trips at fractional densities
        // leave sub-pixel noise on the plan width; a fitting formula must still report zero
        // horizontal scroll range instead of a phantom scrollable pixel.
        val source = "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{a+b}{c+d}=y_2^3"
        listOf(2.625f, 2.75f, 3.5f).forEach { densityValue ->
            val scrollState = androidx.compose.foundation.ScrollState(0)
            ImageComposeScene(width = 1080, height = 900, density = Density(densityValue)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.padding(horizontal = 7.dp),
                        mode = MathMode.Display,
                        fontSizePx = 32f * densityValue,
                        displayScrollState = scrollState,
                        softWrap = true,
                    )
                }
            }.use { scene ->
                scene.render()
                assertEquals(0, scrollState.maxValue, "phantom scroll range at density $densityValue")
            }
        }
    }

    @Test
    fun responsiveDisplayMeasuresAndRastersLeadingOperatorLinesWithoutScrolling() {
        val source = "E_k=(n-1)E_{k-1}+E_{k-2}+\\frac{a+b}{c+d}=y_2^3"
        val scrollState = androidx.compose.foundation.ScrollState(0)
        var measured: MeasureSnapshot? = null
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 240, height = 320, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                MeasureProbe(onMeasured = { measured = it }) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.width(200.dp),
                        mode = MathMode.Display,
                        fontSizePx = 32f,
                        displayScrollState = scrollState,
                        softWrap = true,
                        onMathLayout = { observed = it },
                    )
                }
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            val layout = assertNotNull(observed)
            val broken = layout.breakResponsiveDisplayLines(200f)
            val snapshot = assertNotNull(measured)
            assertTrue(broken.lines.size > 1)
            assertEquals(0, scrollState.maxValue, "legal display breaks avoid horizontal scrolling")
            assertTrue(snapshot.height >= broken.height.toInt())
            assertTrue(darkRowBands(pixels, snapshot.width, snapshot.height).size >= 2)
        }
    }

    @Test
    fun explicitDisplayRowsReachComposeMeasureAndReplayWithoutSyntheticLineBreaking() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            val scene = ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "\\begin{align*}a&=b\\\\[.2cm]c&=\\frac{d}{e}\\end{align*}",
                            modifier = Modifier.padding(12.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, color = Color.Black),
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
            scene.use {
                val pixels = it.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertTrue(layout.decisions.any { decision ->
                    decision.name == "MarkdownMathDisplayEnvironment" &&
                        decision.details["layoutRole"] == "DisplayAlignment"
                })
                assertTrue(layout.decisions.any { decision -> decision.name == "TeXExplicitRowSpacing" })
                assertTrue(snapshot.height > 80, "explicit rows contribute their completed TeX box")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.top >= 12, "display wrapper respects Compose padding: $ink")
                assertTrue(darkRowBands(pixels, 360, 260).size >= 2, "both explicit rows are replayed")
            }
        }
    }

    @Test
    fun topLevelMarkdownDisplayRowsReachTheSameComposeReplayPath() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            val scene = ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "a=b\\\\[.2cm]c=\\frac{d}{e}",
                            modifier = Modifier.padding(12.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, color = Color.Black),
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }
            scene.use {
                val pixels = it.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertTrue(layout.decisions.any { decision ->
                    decision.name == "MarkdownExplicitDisplayRows" &&
                        decision.details["rowCount"] == "2"
                })
                assertTrue(snapshot.height > 80, "explicit top-level rows affect actual Compose measure")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.top >= 12, "raw display rows respect padding: $ink")
                assertTrue(darkRowBands(pixels, 360, 260).size >= 2, "both raw display rows are replayed")
            }
        }
    }

    @Test
    fun displayOperatorLimitsReachActualComposeMeasureBaselineAndRaster() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 360, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(fontSize = 40.sp, lineHeight = 48.sp, color = Color.Black),
                    ) {
                        MeasureProbe(onMeasured = { measured = it }) {
                            TiqianMath(
                                source = "\\sum_i^n+\\int\\limits_0^1",
                                modifier = Modifier.padding(10.dp),
                                mode = MathMode.Display,
                                fontFace = face,
                                onMathLayout = { observed = it },
                            )
                        }
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.decisions.count { it.name == "TeXOperatorNoad" } == 2)
                assertTrue(layout.decisions.count { it.name == "OpenTypeMathOperatorLimits" } == 2)
                assertTrue(snapshot.firstBaseline in 10 until snapshot.height - 10)
                assertTrue(snapshot.height > 48, "stacked limits expand actual Compose height")
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 10 && ink.right < snapshot.width - 10, "operator ink is not horizontally cropped")
                assertTrue(ink.top >= 10 && ink.bottom < snapshot.height - 10, "operator limits are not vertically cropped")
            }
        }
    }

    @Test
    fun indexedNestedRadicalRulesReachActualComposeMeasureBaselineAndRaster() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 440, height = 360, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(fontSize = 42.sp, lineHeight = 50.sp, color = Color.Black),
                    ) {
                        MeasureProbe(onMeasured = { measured = it }) {
                            TiqianMath(
                                source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}",
                                modifier = Modifier.padding(12.dp),
                                mode = MathMode.Display,
                                fontFace = face,
                                onMathLayout = { observed = it },
                            )
                        }
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertEquals(2, layout.decisions.count { it.name == "TeXRadicalNoad" })
                assertEquals(2, layout.decisions.count { it.name == "OpenTypeRadicalConstruction" })
                assertEquals(3, layout.box.rules.size, "two radical rules and one fraction rule are replayed")
                assertTrue(snapshot.firstBaseline in 12 until snapshot.height - 12)
                assertTrue(snapshot.height > 50, "nested radical expands actual Compose height")

                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 12 && ink.right < snapshot.width - 12, "radical ink is not horizontally cropped")
                assertTrue(ink.top >= 12 && ink.bottom < snapshot.height - 12, "radical ink is not vertically cropped")
                val outerRule = layout.box.rules.minBy { it.top }
                val contentWidth = snapshot.width - 24f
                val displayCenterOffset = ((contentWidth - layout.box.visualWidth) / 2f).coerceAtLeast(0f)
                assertRuleRasterMatchesPlacement(
                    pixels = pixels,
                    ruleLeft = 12f + displayCenterOffset - layout.box.visualLeft + outerRule.left,
                    ruleRight = 12f + displayCenterOffset - layout.box.visualLeft + outerRule.right,
                    ruleTop = snapshot.firstBaseline + outerRule.top,
                    ruleBottom = snapshot.firstBaseline + outerRule.bottom,
                )
            }
        }
    }

    @Test
    fun extensibleArrowAndOverUnderStacksReplayTheSharedLayoutWithoutCropping() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var measured: MeasureSnapshot? = null
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 640, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MeasureProbe(onMeasured = { measured = it }) {
                        TiqianMath(
                            source = "X\\xrightarrow[k-1]{p_k}Y+\\overset{u}{=}+\\underset{d}{x}",
                            modifier = Modifier.padding(14.dp),
                            mode = MathMode.Display,
                            fontFace = face,
                            style = TextStyle(fontSize = 34.sp, lineHeight = 58.sp, color = Color.Black),
                            onMathLayout = { observed = it },
                        )
                    }
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val snapshot = assertNotNull(measured)
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertEquals(1, layout.decisions.count { it.name == "AmsmathXeTeXExtensibleArrow" })
                assertEquals(2, layout.decisions.count { it.name == "TeXOverUnderNoad" })
                assertEquals(
                    1,
                    layout.box.constructionPaintGroups.count {
                        it.kind == org.tiqian.math.core.MathConstructionPaintKind.ExtensibleArrow
                    },
                )
                assertTrue(snapshot.firstBaseline in 14 until snapshot.height - 14)
                val ink = darkPixelBounds(pixels)
                assertTrue(ink.left >= 14 && ink.right < snapshot.width - 14, "arrow/stack ink is not horizontally cropped")
                assertTrue(ink.top >= 14 && ink.bottom < snapshot.height - 14, "arrow/stack ink is not vertically cropped")
            }
        }
    }
}
