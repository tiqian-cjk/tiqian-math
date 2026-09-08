package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import org.tiqian.math.core.MathEquationTagPlacement
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathTextOrigin
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
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TiqianMathRenderTest {
    @Test
    fun formulaPreparerBuildsAReplayableInlineFormulaOutsideComposition() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val formula = createTiqianMathFormulaPreparer(face).prepare(
                source = "a+b=c",
                mode = MathMode.Inline,
                fontSizePx = 24f,
                density = Density(1f),
            )
            val layout = assertNotNull(formula.layoutResult)
            assertEquals("a+b=c", layout.source)
            assertTrue(assertNotNull(formula.presentationMetrics()).widthPx > 0f)
        }
    }

    @Test
    fun declaredOperatorIsOneSourceFragmentForHostSelection() {
        val source = "a+\\operatorname{arg max}+b"
        val operator = "\\operatorname{arg max}"
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val layout = assertNotNull(
                createTiqianMathFormulaPreparer(face).prepare(
                    source = source,
                    mode = MathMode.Inline,
                    fontSizePx = 24f,
                    density = Density(1f),
                ).layoutResult,
            )

            val operatorFragment = layout.fragments.single { fragment ->
                source.substring(fragment.sourceRange.start, fragment.sourceRange.endExclusive) == operator
            }
            assertEquals(
                SourceRange(source.indexOf(operator), source.indexOf(operator) + operator.length),
                operatorFragment.sourceRange,
            )
        }
    }

    @Test
    fun formulaPreparerUsesCallerDensityForLatexAbsoluteDimensions() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val preparer = createTiqianMathFormulaPreparer(face)
            val source = "\\boxed{\\cancel{x+1}}+\\begin{array}{c}a\\\\\\hline b\\end{array}"
            val oneX = assertNotNull(
                preparer.prepare(
                    source = source,
                    fontSizePx = 48f,
                    density = Density(1f),
                ).layoutResult,
            )
            val threeX = assertNotNull(
                preparer.prepare(
                    source = source,
                    fontSizePx = 48f,
                    density = Density(3f),
                ).layoutResult,
            )
            var directThreeX: MathLayoutResult? = null
            ImageComposeScene(width = 640, height = 300, density = Density(3f)) {
                TiqianMath(
                    source = source,
                    fontSizePx = 48f,
                    fontFace = face,
                    onMathLayout = { directThreeX = it },
                )
            }.use { it.render() }
            val direct = assertNotNull(directThreeX)
            fun MathLayoutResult.cancelThickness() = box.rules.single {
                it.paintRole == org.tiqian.math.core.MathRulePaintRole.Cancellation
            }.lineSegment?.thickness

            val oneXThickness = assertNotNull(oneX.cancelThickness())
            assertEquals(0.4f * 96f / 72.27f, oneXThickness, 0.001f)
            assertEquals(oneXThickness * 3f, assertNotNull(threeX.cancelThickness()), 0.001f)
            listOf(
                "AmsmathBoxedNoad" to listOf("fboxSeparationPx", "fboxRuleThicknessPx"),
                "LatexCancelStroke" to listOf(
                    "cancelLineThicknessPx",
                    "cancelPicturePointPx",
                    "cancelMinimumWidthPx",
                    "cancelMinimumTotalHeightPx",
                    "cancelWideMinimumWidthPx",
                    "cancelTallMinimumHeightPx",
                    "cancelLineExtensionPx",
                ),
                "TeXMathTable" to listOf("arrayRuleThicknessPx"),
            ).forEach { (decisionName, fields) ->
                val oneXDecision = oneX.decisions.single { it.name == decisionName }
                val threeXDecision = threeX.decisions.single { it.name == decisionName }
                fields.forEach { field ->
                    assertEquals(
                        oneXDecision.details.getValue(field).toFloat() * 3f,
                        threeXDecision.details.getValue(field).toFloat(),
                        0.001f,
                        "$decisionName.$field",
                    )
                }
            }
            assertEquals(
                oneX.box.glyphs.map { it.fontSizePx },
                threeX.box.glyphs.map { it.fontSizePx },
                "density changes the absolute cancel stroke, not an already-resolved font size",
            )
            val absoluteDecisionNames = setOf("AmsmathBoxedNoad", "LatexCancelStroke", "TeXMathTable")
            assertEquals(
                threeX.decisions.filter { it.name in absoluteDecisionNames },
                direct.decisions.filter { it.name in absoluteDecisionNames },
                "direct Compose and background preparation must resolve identical absolute dimensions",
            )
            assertEquals(threeX.box.rules, direct.box.rules, "layout and replay geometry must remain identical")
        }
    }

    @Test
    fun formulaCanvasRasterizesTheDensityResolvedCancelThickness() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val preparer = createTiqianMathFormulaPreparer(face)
            fun strokeCoverage(densityValue: Float): Float {
                val formula = preparer.prepare(
                    source = "\\cancel{{\\color{white}x+1}}",
                    fontSizePx = 48f,
                    density = Density(densityValue),
                )
                assertNotNull(formula.layoutResult)
                return ImageComposeScene(width = 220, height = 120, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMathFormulaCanvas(formula)
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    var coverage = 0f
                    for (y in 0 until pixels.height) {
                        for (x in 0 until pixels.width) {
                            coverage += 1f - pixels[x, y].red
                        }
                    }
                    coverage
                }
            }

            val oneXCoverage = strokeCoverage(1f)
            val threeXCoverage = strokeCoverage(3f)
            assertTrue(oneXCoverage > 0f, "density-one cancellation stroke must remain visible")
            assertTrue(
                threeXCoverage > oneXCoverage * 2.5f,
                "3x replay must rasterize a materially thicker stroke: 1x=$oneXCoverage 3x=$threeXCoverage",
            )
        }
    }

    @Test
    fun composeTextBackendReplaysNestedHostTextWithoutAnInjectedProvider() {
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 520, height = 180, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMath(
                    source = "x+\\text{中文 العربية}+原文+\\frac{1}{\\text{段落}}+y^{\\text{上标}}",
                    style = TextStyle(fontSize = 32.sp),
                    softWrap = false,
                    onMathLayout = { observed = it },
                )
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            val result = assertNotNull(observed)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertEquals(5, result.box.hostTextRuns.size)
            assertTrue(result.box.hostTextRuns.any { it.baselineY > 0f }, "fraction text keeps its shifted baseline")
            assertTrue(result.box.hostTextRuns.any { it.baselineY < 0f }, "script text keeps its shifted baseline")
            assertTrue(result.box.glyphs.none { it.hostTextDecision != null })
            val darkPixels = (0 until pixels.height).sumOf { y ->
                (0 until pixels.width).count { x ->
                    val pixel = pixels[x, y]
                    pixel.red < 0.3f && pixel.green < 0.3f && pixel.blue < 0.3f
                }
            }
            assertTrue(darkPixels > 300, "opaque Compose text and math glyphs must both be visible: $darkPixels")
        }
    }

    @Test
    fun cancellationNegationAndBoldTextReplayFromTheSharedLayoutPlan() {
        var observed: MathLayoutResult? = null
        SkiaMathFontFamily.loadBundledLete().use { family ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("remaining-compose-text-bold"),
                LeteSansMath.loadBoldBytes(),
                MathFontWeight.Bold,
            ).use { text ->
                ImageComposeScene(width = 320, height = 120, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = "\\cancel{x+1}+\\not\\equiv+\\textbf{1}",
                            fontSizePx = 32f,
                            fontFace = family,
                            textRunProvider = text,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val result = assertNotNull(observed)
                    assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                    val cancellation = result.box.rules.single {
                        it.paintRole == org.tiqian.math.core.MathRulePaintRole.Cancellation
                    }
                    assertNotNull(cancellation.lineSegment)
                    assertTrue(result.decisions.any { it.name == "TeXNotRelation" })
                    assertTrue(result.box.glyphs.any { it.faceId == MathFaceId("remaining-compose-text-bold") })
                    val darkPixels = (0 until pixels.height).sumOf { y ->
                        (0 until pixels.width).count { x ->
                            val pixel = pixels[x, y]
                            pixel.red < 0.3f && pixel.green < 0.3f && pixel.blue < 0.3f
                        }
                    }
                    assertTrue(darkPixels > 150, "shared glyph and stroked-rule replay must be visible: $darkPixels")
                }
            }
        }
    }

    @Test
    fun negationOverlayAndExplicitKernReplayAsMeasuredGlyphs() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            fun render(source: String): Pair<MathLayoutResult, Int> {
                var observed: MathLayoutResult? = null
                return ImageComposeScene(width = 220, height = 100, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            fontSizePx = 32f,
                            fontFace = face,
                            softWrap = false,
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val darkPixels = (0 until pixels.height).sumOf { y ->
                        (0 until pixels.width).count { x -> pixels[x, y].red < 0.45f }
                    }
                    assertNotNull(observed) to darkPixels
                }
            }

            listOf(
                Triple("\\not p", "p", "valid unicode-math overlay"),
                Triple("\\not\\!p", "\\!p", "explicit-kern article compatibility"),
            ).forEach { (source, control, label) ->
                val (result, overlayPixels) = render(source)
                val (_, controlPixels) = render(control)
                assertTrue(result.diagnostics.isEmpty(), "$label: ${result.debugDump}")
                assertEquals(1, result.decisions.count { it.name == "TeXNotRelation" }, label)
                val overlay = result.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }
                assertTrue(abs(overlay.inkBounds.bottom) <= 0.001f, "$label: $overlay")
                assertTrue(
                    overlayPixels >= controlPixels + 12,
                    "$label U+0338 replay must independently add visible raster coverage: " +
                        "overlay=$overlayPixels control=$controlPixels",
                )
            }
        }
    }

    @Test
    fun basicCommandExtensionsReplayFromTheSharedLayoutResult() {
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            ImageComposeScene(width = 720, height = 220, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = "a\\pmod b+\\sum_{\\substack{i=1\\\\j=2}}^n+" +
                            "\\overrightarrow{AB}+\\coprod_i^n+" +
                            "\\begin{smallmatrix}a&b\\\\c&d\\end{smallmatrix}",
                        fontSizePx = 32f,
                        fontFace = face,
                        softWrap = false,
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val result = assertNotNull(observed)
                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                assertTrue(result.decisions.any { it.name == "AmsmathModulo" })
                assertTrue(result.decisions.any {
                    it.name == "TeXMathTable" && it.details["environment"] == "Substack"
                })
                assertTrue(result.decisions.any {
                    it.name == "TeXMathTable" && it.details["environment"] == "SmallMatrix"
                })
                assertTrue(result.decisions.any { it.name == "OpenTypeMathAccent" })
                val darkPixels = (0 until pixels.height).sumOf { y ->
                    (0 until pixels.width).count { x -> pixels[x, y].red < 0.5f }
                }
                assertTrue(darkPixels > 500, "all extension glyphs must be replayed: $darkPixels")
            }
        }
    }

    @Test
    fun mathJaxBboxBackgroundIsReplayedBeforeGlyphsAndForegroundBorder() {
        var observed: MathLayoutResult? = null
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            ImageComposeScene(width = 180, height = 110, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = "\\bbox[#CAF,10px,border:2px solid blue]{x}",
                        fontSizePx = 32f,
                        fontFace = face,
                        softWrap = false,
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val result = assertNotNull(observed)
                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                val background = result.box.rules.single {
                    it.paintRole == org.tiqian.math.core.MathRulePaintRole.BackgroundFill
                }
                assertEquals(org.tiqian.math.core.MathPaintLayer.Background, background.paintLayer)
                assertEquals(4, result.box.rules.count {
                    it.paintRole == org.tiqian.math.core.MathRulePaintRole.Border
                })
                var backgroundPixels = 0
                var borderPixels = 0
                var darkGlyphPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red > 0.65f && pixel.blue > 0.8f && pixel.green in 0.5f..0.8f) backgroundPixels += 1
                    if (pixel.blue > 0.75f && pixel.red < 0.2f && pixel.green < 0.2f) borderPixels += 1
                    if (pixel.red < 0.15f && pixel.green < 0.15f && pixel.blue < 0.15f) darkGlyphPixels += 1
                }
                assertTrue(backgroundPixels > 100, "background remains visible behind the glyph: $backgroundPixels")
                assertTrue(borderPixels > 20, "foreground border is replayed: $borderPixels")
                assertTrue(darkGlyphPixels > 5, "math glyph is not covered by the background: $darkGlyphPixels")
            }
        }
    }

    @Test
    fun legacySingleFaceRememberApiPreservesTheSurroundingWeightRequest() {
        var observed: MathLayoutResult? = null
        ImageComposeScene(width = 180, height = 100, density = Density(1f)) {
            val face = rememberMathFontFace(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(checkNotNull(System.getProperty("tiqianLeteSourceRegularFont"))),
                ),
            )
            TiqianMath(
                source = "x+1",
                style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                fontFace = face,
                onMathLayout = { observed = it },
            )
        }.use { it.render() }
        val glyphs = assertNotNull(observed).box.glyphs
        assertTrue(glyphs.all { it.requestedWeight == MathFontWeight.Bold })
        assertTrue(glyphs.all { it.resolvedWeight == MathFontWeight.Regular })
        assertTrue(glyphs.all { it.fallbackReason == MathFontFallbackReason.RequestedWeightUnavailable })
    }

    @Test
    fun surroundingTextStyleWeightSelectsLeteBoldAndForwardsWeightToExplicitHostTextProvider() {
        var regular: MathLayoutResult? = null
        var bold: MathLayoutResult? = null
        SkiaMathFontFamily.loadBundledLete().use { regularFamily ->
            val boldFamily = regularFamily.selectWeight(MathFontWeight.Bold) as SkiaMathFontFamily
            ComposeTestHostTextProvider(
                SkiaMathFontFace(
                    LeteSansMath.load(),
                    MathFaceId("compose-test-host-text"),
                    resolvedWeight = MathFontWeight.Bold,
                    requestedWeight = MathFontWeight.Bold,
                ),
            ).use { provider ->
                ImageComposeScene(width = 520, height = 180, density = Density(1f)) {
                    TiqianMath(
                        "x+\\aleph_0",
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal),
                        fontFace = regularFamily,
                        onMathLayout = { regular = it },
                    )
                    TiqianMath(
                        "x+\\text{中文}+x^{中文2}+\\aleph_0",
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                        fontFace = boldFamily,
                        textRunProvider = provider,
                        onMathLayout = { bold = it },
                    )
                }.use { it.render() }
                val regularGlyphs = assertNotNull(regular).box.glyphs
                val boldGlyphs = assertNotNull(bold).box.glyphs
                assertTrue(regularGlyphs.any { it.faceId.value == "lete-sans-math-regular" })
                assertTrue(boldGlyphs.any { it.faceId.value == "lete-sans-math-bold" })
                val hostGlyphs = boldGlyphs.filter { it.faceId == provider.faceId }
                assertTrue(hostGlyphs.isNotEmpty())
                assertTrue(hostGlyphs.all { it.requestedWeight == MathFontWeight.Bold })
                assertTrue(hostGlyphs.any { it.fontSizePx < 32f }, "script text is shaped at the actual MATH script size")
                assertTrue(boldGlyphs.any {
                    it.faceId.value == "lete-sans-math-regular" &&
                        it.fallbackReason == MathFontFallbackReason.MissingGlyphInRequestedWeight
                }, "Lete Bold's missing aleph falls back to Lete Regular without changing the formula weight request")
            }
        }
    }

    @Test
    fun automaticComposeTextProviderReplaysFullwidthPunctuationInsideMath() {
        val source = "C_1=1-C，C_2=C-\\frac14"
        var layout: MathLayoutResult? = null
        ImageComposeScene(width = 520, height = 120, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMath(
                    source = source,
                    style = TextStyle(fontSize = 32.sp),
                    onMathLayout = { layout = it },
                )
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            var dark = 0
            repeat(pixels.height) { y ->
                repeat(pixels.width) { x ->
                    val pixel = pixels[x, y]
                    if (pixel.red < 0.4f && pixel.green < 0.4f && pixel.blue < 0.4f) dark++
                }
            }
            assertTrue(dark > 100, "the complete formula, including host punctuation, must be painted")
        }
        val result = assertNotNull(layout)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(result.box.hostTextRuns.any {
            it.sourceRange == SourceRange(7, 8)
        })
    }

    @Test
    fun collidingHostFaceIdUsesOnlyFallbackBeforeAnyMathPaint() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            ComposeTestHostTextProvider(
                SkiaMathFontFace(
                    LeteSansMath.load(),
                    MathFaceId("lete-sans-math-regular"),
                ),
            ).use { provider ->
                var captured: org.tiqian.math.layout.MathFormulaCapabilityResult.FallbackRequired? = null
                ImageComposeScene(width = 100, height = 70, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMathOrFallback(
                            source = "\\text{中}",
                            fontFace = math,
                            textRunProvider = provider,
                            fallback = {
                                captured = it
                                Canvas(Modifier.size(50.dp, 30.dp)) { drawRect(Color.Magenta) }
                            },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    var magenta = 0
                    var dark = 0
                    repeat(pixels.height) { y ->
                        repeat(pixels.width) { x ->
                            val pixel = pixels[x, y]
                            if (pixel.red > 0.8f && pixel.blue > 0.8f && pixel.green < 0.2f) magenta++
                            if (pixel.red < 0.3f && pixel.green < 0.3f && pixel.blue < 0.3f) dark++
                        }
                    }
                    assertEquals(50 * 30, magenta)
                    assertEquals(0, dark, "conflicting face ownership must not paint Tiqian glyphs")
                }
                val fallback = assertNotNull(captured)
                assertTrue(fallback.diagnostics.any {
                    it.code == org.tiqian.math.core.DiagnosticCode.ReplayFaceOwnershipConflict
                })
            }
        }
    }

    @Test
    fun explicitTeXNullDelimiterSpaceReachesTheFractionNoad() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 180, height = 140, density = Density(1f)) {
                TiqianMath(
                    source = "\\frac{a}{b}",
                    fontSizePx = 32f,
                    nullDelimiterSpacePx = 1.5940225f,
                    fontFace = face,
                    onMathLayout = { observed = it },
                )
            }.use { scene ->
                scene.render()
                val decision = assertNotNull(observed).decisions.single {
                    it.name == "TeXFractionNullDelimiters"
                }
                assertNear(1.5940225f, decision.details.getValue("leftSpacePx").toFloat())
                assertNear(1.5940225f, decision.details.getValue("rightSpacePx").toFloat())
            }
        }
    }

    @Test
    fun explicitTeXScriptSpaceReachesTheSharedSideScriptKernel() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 180, height = 140, density = Density(1f)) {
                TiqianMath(
                    source = "\\int_0^1",
                    fontSizePx = 32f,
                    scriptSpacePx = 0.66417605f,
                    fontFace = face,
                    onMathLayout = { observed = it },
                )
            }.use { scene ->
                scene.render()
                val decision = assertNotNull(observed).decisions.single {
                    it.name == "OpenTypeMathScriptPlacement"
                }
                assertNear(0.66417605f, decision.details.getValue("spaceAfterScriptPx").toFloat())
                assertEquals("ExplicitTeXScriptSpace", decision.details["spaceAfterScriptPolicy"])
            }
        }
    }

    @Test
    fun actualRendererReplaysEachRadicalAsOneCachedConstructionPath() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 420, height = 280, density = Density(1f)) {
                Box(Modifier.fillMaxSize()) {
                    TiqianMath(
                        source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}",
                        modifier = Modifier.padding(12.dp),
                        mode = MathMode.Display,
                        fontSizePx = 48f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val firstPixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertEquals(2, layout.box.constructionPaintGroups.size)
                var maximumAlpha = 0f
                for (y in 0 until firstPixels.height) for (x in 0 until firstPixels.width) {
                    maximumAlpha = maxOf(maximumAlpha, firstPixels[x, y].alpha)
                }
                assertTrue(maximumAlpha in 0.45f..0.53f, "one translucent paint pass prevents overlap darkening")
                val afterFirst = face.constructionOutlineCacheStats()
                assertEquals(2, afterFirst.builds)
                assertEquals(2, afterFirst.entries)

                layout.box.constructionPaintGroups.forEach { group ->
                    face.constructionOutline(layout.box, group)
                }
                val afterReplay = face.constructionOutlineCacheStats()
                assertEquals(afterFirst.builds, afterReplay.builds, "replay performs no PathOps rebuild")
                assertTrue(afterReplay.hits >= afterFirst.hits + 2, "each construction path is replayed from cache")
            }
        }
    }

    @Test
    fun explicitTeXColorsReplayAcrossGlyphRulesAndConstructionUnion() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            ImageComposeScene(width = 520, height = 240, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = "{\\color{red}\\boxed{x}}+{\\color{blue}\\sqrt{\\frac{a}{b}}}",
                        modifier = Modifier.padding(20.dp),
                        fontSizePx = 48f,
                        fontFace = face,
                        color = Color.Black,
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                var redPixels = 0
                var bluePixels = 0
                var darkPixels = 0
                repeat(pixels.height) { y ->
                    repeat(pixels.width) { x ->
                        val pixel = pixels[x, y]
                        if (pixel.red > 0.65f && pixel.green < 0.35f && pixel.blue < 0.35f) redPixels++
                        if (pixel.blue > 0.65f && pixel.red < 0.35f && pixel.green < 0.35f) bluePixels++
                        if (pixel.red < 0.35f && pixel.green < 0.35f && pixel.blue < 0.35f) darkPixels++
                    }
                }
                assertTrue(redPixels > 20, "boxed glyph and four rules use the explicit red paint: $redPixels")
                assertTrue(bluePixels > 20, "radical construction, rule, and fraction use the explicit blue paint: $bluePixels")
                assertTrue(darkPixels > 5, "the unscoped binary operator inherits the host formula color: $darkPixels")
            }

            val layout = assertNotNull(observed)
            assertTrue(layout.box.constructionPaintGroups.all {
                it.paintColor == org.tiqian.math.core.MathPaintColor(0, 0, 255)
            })
            assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
        }
    }

    @Test
    fun actualRendererReplaysContentDrivenDelimitersAsCachedConstructionPaths() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            val source = "\\left\\langle a\\middle|\\frac{\\frac{\\frac{x}{y}}{y}}{y}\\right\\rangle"
            ImageComposeScene(width = 520, height = 420, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.padding(16.dp),
                        mode = MathMode.Display,
                        fontSizePx = 40f,
                        nullDelimiterSpacePx = 1.2f * 96f / 72.27f,
                        delimiterFactor = 901,
                        delimiterShortfallPx = 5f * 96f / 72.27f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertEquals(3, layout.box.constructionPaintGroups.count {
                    it.kind == org.tiqian.math.core.MathConstructionPaintKind.Delimiter
                })
                assertTrue(layout.decisions.any { it.name == "TeXContentDrivenDelimitedGroup" })
                var maximumAlpha = 0f
                var darkPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red < 0.8f) darkPixels++
                    maximumAlpha = maxOf(maximumAlpha, 1f - pixel.red)
                }
                assertTrue(darkPixels > 300, "delimiter formula was actually rasterized")
                assertTrue(maximumAlpha in 0.45f..0.53f, "assembly overlap is union-painted once")
                val stats = face.constructionOutlineCacheStats()
                assertEquals(layout.box.constructionPaintGroups.size, stats.builds.toInt())
                layout.box.constructionPaintGroups.forEach { face.constructionOutline(layout.box, it) }
                assertTrue(face.constructionOutlineCacheStats().hits >= layout.box.constructionPaintGroups.size)
            }
        }
    }

    @Test
    fun actualRendererReplaysFixedSizeDelimiterVariantsAndAssembliesFromLayoutOwnership() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            val source = "\\bigl(x\\bigr)+\\Bigl[x\\Bigr]+\\biggl\\{x\\biggr\\}+\\Bigg\\uparrow x\\Bigg\\Downarrow"
            ImageComposeScene(width = 760, height = 260, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.padding(16.dp),
                        fontSizePx = 32f,
                        delimiterFactor = 901,
                        delimiterShortfallPx = 5f * 96f / 72.27f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertEquals(8, layout.decisions.count { it.name == "TeXFixedSizeDelimiter" })
                assertEquals(8, layout.box.constructionPaintGroups.count {
                    it.kind == org.tiqian.math.core.MathConstructionPaintKind.Delimiter
                })
                assertTrue(layout.decisions.filter { it.name == "TeXFixedSizeDelimiter" }.any {
                    it.details["construction"] == "Assembly"
                })
                var maximumAlpha = 0f
                var darkPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.red < 0.8f) darkPixels++
                    maximumAlpha = maxOf(maximumAlpha, 1f - pixel.red)
                }
                assertTrue(darkPixels > 350, "fixed delimiters were actually rasterized")
                assertTrue(maximumAlpha in 0.45f..0.53f, "construction overlap is union-painted once")
                val stats = face.constructionOutlineCacheStats()
                assertTrue(stats.builds >= layout.box.constructionPaintGroups.size)
                val hitsBeforeReplay = stats.hits
                layout.box.constructionPaintGroups.forEach { face.constructionOutline(layout.box, it) }
                assertTrue(face.constructionOutlineCacheStats().hits >= hitsBeforeReplay + 8)
            }
        }
    }

    @Test
    fun actualRendererReplaysTextOperatorAccentsAndRuleDecorationsFromOneLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("compose-standalone-text"),
                LeteSansMath.loadBytes(),
            ).use { textProvider ->
                var observed: MathLayoutResult? = null
                val source = "\\text{rate }+\\operatorname{rank}_A+\\vec{abcdefghijklmno}+" +
                    "\\overline{x+y}+\\underline{\\frac{a}{b}}"
                ImageComposeScene(width = 900, height = 260, density = Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMath(
                            source = source,
                            modifier = Modifier.padding(16.dp),
                            mode = MathMode.Display,
                            fontSizePx = 40f,
                            fontFace = face,
                            textRunProvider = textProvider,
                            color = Color.Black.copy(alpha = 0.5f),
                            onMathLayout = { observed = it },
                        )
                    }
                }.use { scene ->
                    val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                    val layout = assertNotNull(observed)
                    assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                    assertTrue(layout.box.glyphs.any { it.faceId == textProvider.faceId })
                    assertTrue(layout.decisions.any { it.name == "TeXEmbeddedText" })
                    assertTrue(layout.decisions.any { it.name == "TeXDeclaredOperatorName" })
                    assertTrue(layout.decisions.any { it.name == "OpenTypeMathAccent" })
                    assertEquals(2, layout.decisions.count { it.name == "OpenTypeMathRuleDecoration" })
                    val accentGroups = layout.box.constructionPaintGroups.filter {
                        it.kind == org.tiqian.math.core.MathConstructionPaintKind.Accent
                    }
                    assertTrue(accentGroups.isNotEmpty(), "wide vector should exercise horizontal assembly replay")
                    var maximumAlpha = 0f
                    var painted = 0
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                        val alpha = 1f - pixels[x, y].red
                        maximumAlpha = maxOf(maximumAlpha, alpha)
                        if (alpha > 0.15f) painted++
                    }
                    assertTrue(painted > 500, "all extended structures were rasterized")
                    assertTrue(maximumAlpha in 0.45f..0.53f, "assembly overlap is union-painted once")
                    val stats = face.constructionOutlineCacheStats()
                    assertTrue(stats.entries >= accentGroups.size)
                }
            }
        }
    }

    @Test
    fun actualRendererReplaysGrowingBracesDisplayFractionsAndMathopFromOneLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var observed: MathLayoutResult? = null
            val source = "\\overbrace{a+b+c+d+e}^{n}+\\underbrace{abcdefghijklmno}_{k}+" +
                "\\tfrac{a}{b}+\\dfrac{a}{b}+\\cfrac[l]{x}{bbbb}+\\mathop{rank}_0^1"
            ImageComposeScene(width = 1500, height = 420, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMath(
                        source = source,
                        modifier = Modifier.padding(18.dp),
                        mode = MathMode.Display,
                        fontSizePx = 40f,
                        fontFace = face,
                        color = Color.Black.copy(alpha = 0.5f),
                        onMathLayout = { observed = it },
                    )
                }
            }.use { scene ->
                val pixels = scene.render().toComposeImageBitmap().toPixelMap()
                val layout = assertNotNull(observed)
                assertTrue(layout.diagnostics.isEmpty(), layout.diagnostics.toString())
                assertEquals(2, layout.decisions.count { it.name == "TeXBraceOperatorNoad" })
                assertEquals(2, layout.decisions.count {
                    it.name == "OpenTypeMathAccent" && it.details["identity"]?.endsWith("brace") == true
                })
                assertTrue(layout.decisions.any {
                    it.name == "TeXFractionCommand" && it.details["origin"] == "TextFraction"
                })
                assertTrue(layout.decisions.any {
                    it.name == "TeXFractionCommand" && it.details["origin"] == "DisplayFraction"
                })
                assertTrue(layout.decisions.any {
                    it.name == "TeXFractionCommand" && it.details["origin"] == "ContinuedFraction"
                })
                assertTrue(layout.decisions.any { it.name == "TeXMathOperatorNoad" })
                assertTrue(layout.decisions.any { it.name == "OpenTypeMathOperatorLimits" })
                val braceGroups = layout.box.constructionPaintGroups.filter {
                    it.kind == org.tiqian.math.core.MathConstructionPaintKind.Accent
                }
                assertTrue(braceGroups.size >= 2, "wide braces use replayable construction groups")
                var maximumAlpha = 0f
                var painted = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val alpha = 1f - pixels[x, y].red
                    maximumAlpha = maxOf(maximumAlpha, alpha)
                    if (alpha > 0.15f) painted++
                }
                assertTrue(painted > 1000, "all common extension structures were rasterized")
                assertTrue(maximumAlpha in 0.45f..0.53f, "brace assembly overlap is union-painted once")
            }
        }
    }
}

internal fun pixelSignature(
    pixels: androidx.compose.ui.graphics.PixelMap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): List<Int> = buildList((right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)) {
    for (y in top until bottom) {
        for (x in left until right) add(pixels[x, y].toArgb())
    }
}

internal fun inkSignature(
    pixels: androidx.compose.ui.graphics.PixelMap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Set<Int> = buildSet {
    for (y in top until bottom) {
        for (x in left until right) {
            if (pixels[x, y].red < 0.9f) add((y - top) * (right - left) + x - left)
        }
    }
}
