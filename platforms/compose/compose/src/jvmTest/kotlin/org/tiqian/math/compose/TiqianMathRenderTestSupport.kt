package org.tiqian.math.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class MeasureSnapshot(val width: Int, val height: Int, val firstBaseline: Int)
internal data class MeasuredFormula(val measurement: MeasureSnapshot, val layout: MathLayoutResult)

@Composable
internal fun MeasureProbe(
    onMeasured: (MeasureSnapshot) -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        val baseline = placeable[FirstBaseline]
        onMeasured(MeasureSnapshot(placeable.width, placeable.height, baseline))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun measureInCompose(
    source: String,
    style: TextStyle,
    density: Density,
    modifier: Modifier,
): MeasuredFormula {
    var measured: MeasureSnapshot? = null
    var result: MathLayoutResult? = null
    ImageComposeScene(width = 400, height = 240, density = density) {
        CompositionLocalProvider(LocalTextStyle provides style) {
            MeasureProbe(onMeasured = { measured = it }) {
                TiqianMath(source, modifier = modifier, onMathLayout = { result = it })
            }
        }
    }.use { it.render() }
    return MeasuredFormula(assertNotNull(measured), assertNotNull(result))
}

internal data class InlineHostChildMeasurement(
    val height: Int,
    val firstBaseline: Int,
    val topInRoot: Int,
)

internal data class InlineHostMeasurement(
    val children: List<InlineHostChildMeasurement>,
    val rowHeight: Int,
    val rowTopInRoot: Int,
)

@OptIn(ExperimentalComposeUiApi::class)
internal fun measureInlineHost(source: String, style: TextStyle): InlineHostMeasurement {
    val children = arrayOfNulls<InlineHostChildMeasurement>(3)
    var rowHeight: Int? = null
    var rowTopInRoot: Int? = null

    fun Modifier.observeChild(index: Int): Modifier = onGloballyPositioned { coordinates ->
        children[index] = InlineHostChildMeasurement(
            height = coordinates.size.height,
            firstBaseline = coordinates[FirstBaseline],
            topInRoot = coordinates.positionInRoot().y.roundToInt(),
        )
    }

    ImageComposeScene(width = 500, height = 240, density = Density(1f)) {
        CompositionLocalProvider(LocalTextStyle provides style) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.onGloballyPositioned { coordinates ->
                    rowHeight = coordinates.size.height
                    rowTopInRoot = coordinates.positionInRoot().y.roundToInt()
                }) {
                    BasicText("正文", modifier = Modifier.alignByBaseline().observeChild(0), style = style)
                    TiqianMath(source, modifier = Modifier.alignByBaseline().observeChild(1))
                    BasicText("继续", modifier = Modifier.alignByBaseline().observeChild(2), style = style)
                }
            }
        }
    }.use { it.render() }
    return InlineHostMeasurement(
        children = children.map { assertNotNull(it) },
        rowHeight = assertNotNull(rowHeight),
        rowTopInRoot = assertNotNull(rowTopInRoot),
    )
}

internal data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun darkPixelBounds(pixels: androidx.compose.ui.graphics.PixelMap): PixelBounds {
    var left = pixels.width
    var top = pixels.height
    var right = -1
    var bottom = -1
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val color = pixels[x, y]
        if (color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
    }
    assertTrue(right >= left && bottom >= top, "expected raster ink")
    return PixelBounds(left, top, right, bottom)
}

internal fun darkRowBands(pixels: androidx.compose.ui.graphics.PixelMap, width: Int, height: Int): List<IntRange> {
    val active = (0 until height).filter { y ->
        (0 until width).any { x ->
            val color = pixels[x, y]
            color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f
        }
    }
    if (active.isEmpty()) return emptyList()
    val bands = mutableListOf<IntRange>()
    var start = active.first()
    var previous = start
    active.drop(1).forEach { row ->
        if (row > previous + 1) {
            bands += start..previous
            start = row
        }
        previous = row
    }
    bands += start..previous
    return bands
}

internal fun assertRuleRasterMatchesPlacement(
    pixels: androidx.compose.ui.graphics.PixelMap,
    ruleLeft: Float,
    ruleRight: Float,
    ruleTop: Float,
    ruleBottom: Float,
) {
    val left = floor(ruleLeft).toInt().coerceAtLeast(0)
    val right = ceil(ruleRight).toInt().coerceAtMost(pixels.width - 1)
    val top = floor(ruleTop).toInt().coerceAtLeast(0)
    val bottom = ceil(ruleBottom).toInt().coerceAtMost(pixels.height - 1)
    val dark = (top..bottom).sumOf { y ->
        (left..right).count { x ->
            val color = pixels[x, y]
            color.red < 0.35f && color.green < 0.35f && color.blue < 0.35f
        }
    }
    assertTrue(
        dark >= (right - left).coerceAtLeast(1),
        "rendered radical rule follows engine placement: dark=$dark rect=$left,$top..$right,$bottom",
    )
}

internal fun assertNear(expected: Float, actual: Float) {
    assertTrue(abs(expected - actual) <= 0.03f, "expected $expected, got $actual")
}

internal class ComposeTestHostTextProvider(
    private val face: SkiaMathFontFace,
) : MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
    val faceId: MathFaceId get() = face.faceId

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult {
        val replacement = buildString { repeat(request.text.length) { append('x') } }
        val run = face.shape(replacement, request.fontSizePx, MathStyle.Text, request.sourceRange)
        return MathTextRunProviderResult.Ready(run.copy(glyphs = run.glyphs.map { glyph ->
            glyph.copy(
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = face.resolvedWeight,
                fallbackReason = null,
                hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(
                        request.sourceRange.start + glyph.textCluster,
                        (request.sourceRange.start + glyph.textCluster + 1).coerceAtMost(request.sourceRange.endExclusive),
                    ),
                    clusterRangeUtf16 = SourceRange(glyph.textCluster, (glyph.textCluster + 1).coerceAtMost(request.text.length)),
                    hostRole = request.origin.name,
                    faceId = face.faceId,
                    fontKey = "compose-test-host",
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = "ComposeTestHostSelection",
                ),
            )
        }))
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = face.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = face.close()
}
