package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.tiqian.math.core.MathRect
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine

class StructuralGeometrySnapshotTest {
    @Test
    fun snapshotExcludesReportedPaintAndHostReserveButDetectsPlacementChanges() {
        for (font in listOf(LeteSansMath.load(), StixTwoMath.load())) SkiaMathFontFace(font).use { face ->
            val result = MathLayoutEngine(face).layout("\\int_0^1+\\frac{a}{b}")
            val expected = structuralGeometrySnapshot(result)
            fun MathRect.padded() = MathRect(left - 2f, top - 3f, right + 4f, bottom + 5f)
            val painted = result.copy(
                box = result.box.copy(
                    inkBounds = result.box.inkBounds.padded(),
                    glyphs = result.box.glyphs.map { it.copy(inkBounds = it.inkBounds.padded()) },
                ),
                lineMetrics = result.lineMetrics.copy(
                    logicalAscentPx = result.lineMetrics.logicalAscentPx + 3f,
                    logicalDescentPx = result.lineMetrics.logicalDescentPx + 5f,
                ),
            )
            assertEquals(expected, structuralGeometrySnapshot(painted))
            assertPaintBoundsContained(painted)
            val shifted = result.copy(box = result.box.copy(glyphs = result.box.glyphs.mapIndexed { index, glyph ->
                if (index == 0) glyph.copy(baselineY = glyph.baselineY + 0.01f) else glyph
            }))
            assertFailsWith<AssertionError> { assertStructuralGeometryEquals(expected, structuralGeometrySnapshot(shifted)) }
            val movedRule = result.copy(box = result.box.copy(rules = result.box.rules.map { it.copy(top = it.top - 0.01f) }))
            assertFailsWith<AssertionError> { assertStructuralGeometryEquals(expected, structuralGeometrySnapshot(movedRule)) }
        }
    }

    @Test
    fun onlyDecimalMeasurementsHaveSmallAbsoluteTolerance() {
        assertStructuralGeometryEquals("glyph=42 x=1.000000 baseline=-2.0", "glyph=42 x=1.000001 baseline=-2.000001")
        assertFailsWith<AssertionError> { assertStructuralGeometryEquals("glyph=42 x=1.0", "glyph=43 x=1.0") }
        assertFailsWith<AssertionError> { assertStructuralGeometryEquals("baseline=1.0", "baseline=1.01") }
        assertFailsWith<AssertionError> { assertStructuralGeometryEquals("style=Script", "style=Text") }
    }
}
