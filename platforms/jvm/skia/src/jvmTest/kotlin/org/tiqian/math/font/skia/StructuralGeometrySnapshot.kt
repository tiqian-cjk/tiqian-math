package org.tiqian.math.font.skia

import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathLayoutResult

/** Shared layout truth only. Reported paint bounds and host safety padding are not golden data. */
internal fun structuralGeometrySnapshot(result: MathLayoutResult): String = buildString {
    val box = result.box
    appendLine("logical=${box.width.geometryNumber()} clean=${box.texCleanBoxMetrics.ascent.geometryNumber()}/${box.texCleanBoxMetrics.descent.geometryNumber()} " +
        "fragments=${result.fragments.size} breaks=${result.breakOpportunities.size} diagnostics=${result.diagnostics.map { it.code }}")
    box.glyphs.forEach { glyph ->
        appendLine("  glyph=${glyph.glyphId} face=${glyph.faceId} style=${glyph.style} size=${glyph.fontSizePx.geometryNumber()} " +
            "source=${glyph.sourceRange.start}..${glyph.sourceRange.endExclusive} " +
            "x=${glyph.x.geometryNumber()} baseline=${glyph.baselineY.geometryNumber()} advance=${glyph.advance.geometryNumber()} " +
            "group=${glyph.constructionGroupId}")
    }
    box.rules.forEach { rule ->
        appendLine("  rule=${rule.left.geometryNumber()},${rule.top.geometryNumber()},${rule.right.geometryNumber()},${rule.bottom.geometryNumber()} " +
            "source=${rule.sourceRange.start}..${rule.sourceRange.endExclusive} group=${rule.constructionGroupId} " +
            "role=${rule.paintRole} layer=${rule.paintLayer} line=${rule.lineSegment}")
    }
    box.constructionPaintGroups.forEach { group ->
        appendLine("  construction=${group.id}/${group.kind}/${group.shapeKind} face=${group.faceId} " +
            "source=${group.sourceRange.start}..${group.sourceRange.endExclusive} policy=${group.outlinePolicy}")
    }
    result.fragments.forEach { fragment ->
        appendLine("  fragment=${fragment.index}/${fragment.atomClass} source=${fragment.sourceRange.start}..${fragment.sourceRange.endExclusive} " +
            "width=${fragment.box.width.geometryNumber()} clean=${fragment.box.texCleanBoxMetrics.ascent.geometryNumber()}/${fragment.box.texCleanBoxMetrics.descent.geometryNumber()} " +
            "kern=${fragment.leadingKernPx.geometryNumber()} ic=${fragment.trailingItalicCorrectionPx.geometryNumber()} glue=${fragment.trailingGlue}")
    }
    result.breakOpportunities.forEach { appendLine("  break=$it") }
}.trimEnd()

private fun Float.geometryNumber(): String = String.format(Locale.ROOT, "%.6f", this)

/** Common containment assertions replace OS-specific paint-bound snapshots. */
internal fun assertPaintBoundsContained(result: MathLayoutResult) {
    val box = result.box
    val ink = box.inkBounds
    val epsilon = 0.001f
    assertTrue(result.lineMetrics.logicalAscentPx + epsilon >= -ink.top, result.source)
    assertTrue(result.lineMetrics.logicalDescentPx + epsilon >= ink.bottom, result.source)
    assertTrue(box.visualLeft <= ink.left + epsilon, result.source)
    assertTrue(box.visualRight + epsilon >= ink.right, result.source)
    box.glyphs.forEach { glyph ->
        assertTrue(glyph.inkBounds.left + epsilon >= ink.left && glyph.inkBounds.right <= ink.right + epsilon, result.source)
        assertTrue(glyph.inkBounds.top + epsilon >= ink.top && glyph.inkBounds.bottom <= ink.bottom + epsilon, result.source)
    }
}

// Only decimal measurements receive tolerance. Integer IDs/ranges and all textual structure
// remain exact; identifiers such as policy version numbers are not numeric measurements.
private val decimalMeasurement = Regex("(?<![A-Za-z0-9_.])[-+]?\\d+\\.\\d+(?:[Ee][+-]?\\d+)?(?![A-Za-z0-9_.])")

internal fun assertStructuralGeometryEquals(expected: String, actual: String) {
    assertEquals(decimalMeasurement.replace(expected, "<measurement>"), decimalMeasurement.replace(actual, "<measurement>"))
    val before = decimalMeasurement.findAll(expected).toList()
    val after = decimalMeasurement.findAll(actual).toList()
    before.zip(after).forEach { (left, right) ->
        assertTrue(abs(left.value.toDouble() - right.value.toDouble()) <= 0.001,
            "Geometry measurement at offset ${left.range.first}: expected ${left.value}, actual ${right.value}")
    }
}
