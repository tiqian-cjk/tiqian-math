package org.tiqian.math.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MathLayoutResultTest {
    @Test
    fun debugDumpIsRenderedLazilyOncePerResultAndUsesCopiedGeometry() {
        var renderCount = 0
        val renderer = MathLayoutDebugDumpRenderer { result ->
            renderCount += 1
            "source=${result.source} width=${result.box.width.toInt()}"
        }
        val result = MathLayoutResult(
            source = "x",
            mode = MathMode.Inline,
            initialStyle = MathStyle.Text,
            box = box(width = 10f),
            fragments = emptyList(),
            breakOpportunities = emptyList(),
            diagnostics = emptyList(),
            lineMetrics = MathFormulaLineMetrics(8f, 2f, 0f, 0f, 8f, 2f, 8f, 2f),
            decisions = emptyList(),
            debugDumpRenderer = renderer,
            fontSizePx = 24f,
            resourceLimits = MathResourceLimits.Default,
        )

        assertEquals(0, renderCount)
        assertEquals("source=x width=10", result.debugDump)
        assertEquals("source=x width=10", result.debugDump)
        assertEquals(1, renderCount)

        val copied = result.copy(source = "y", box = box(width = 12f))
        assertEquals(1, renderCount)
        assertEquals("source=y width=12", copied.debugDump)
        assertEquals(2, renderCount)
    }

    private fun box(width: Float) = MathBox(
        width = width,
        ascent = 8f,
        descent = 2f,
        inkBounds = MathRect(0f, -8f, width, 2f),
        glyphs = emptyList(),
        rules = emptyList(),
        range = SourceRange(0, 1),
    )
}
