package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.breakIntoLines

class MathExplicitSpaceLayoutTest {
    @Test
    fun articleOperatorSpaceIsDiscardedAtWrapButRetainedInsideALine() {
        val source = "\\frac{I}{H}\\;\\approx\\;\\frac{1.8\\times10^{-3}}{7.7}\\;\\approx\\;2.3\\times10^{-4}."
        listOf(LeteSansMath.load(), StixTwoMath.load()).forEach { bytes ->
            SkiaMathFontFace(bytes).use { face ->
                for (spaces in listOf("\\;", "\\;\\,")) {
                    val result = MathLayoutEngine(face).layout(source.replace("\\approx\\;", "\\approx$spaces"),
                        MathLayoutOptions(fontSizePx = 36f))
                    val operator = result.fragments.first { it.atomClass == MathAtomClass.Relation }
                    val next = result.fragments[operator.index + 1]
                    assertTrue(next.box.rules.isNotEmpty(), "A wrap after approx must start at the fraction, not a space box")
                    assertTrue(operator.sourceRange.endExclusive == next.sourceRange.start)
                    assertEquals(operator.trailingGlue, operator.breakAfter!!.discardedTrailingGlue)
                    assertTrue(operator.trailingGlue.naturalPx >= 10f)
                    val replayWidth = result.fragments.sumOf {
                        (it.leadingKernPx + it.box.width + it.trailingAdvancePx).toDouble()
                    }.toFloat()
                    assertEquals(result.box.width, replayWidth, 0.002f)
                    val firstLineWidth = result.fragments.take(operator.index + 1).sumOf {
                        (it.leadingKernPx + it.box.width + it.trailingAdvancePx).toDouble()
                    }.toFloat() - operator.trailingGlue.naturalPx
                    val broken = result.breakIntoLines(firstLineWidth + 0.01f)
                    assertEquals(next.index, broken.lines[1].fragments.first().fragmentIndex)
                    assertEquals(0f, broken.lines[1].fragments.first().x, 0.001f)
                }
            }
        }
    }

    @Test
    fun negativeThinSpaceIsAFixedSignedThreeMuKernInBothRealFonts() {
        listOf(LeteSansMath.load(), StixTwoMath.load()).forEach { bytes ->
            SkiaMathFontFace(bytes).use { face ->
                val size = 36f
                val plain = MathLayoutEngine(face).layout("xy", MathLayoutOptions(fontSizePx = size))
                val kerned = MathLayoutEngine(face).layout("x\\!y", MathLayoutOptions(fontSizePx = size))
                assertTrue(kerned.diagnostics.isEmpty(), kerned.diagnostics.toString())
                assertEquals(-size / 6f, kerned.fragments[1].leadingKernPx, 0.001f)
                assertEquals(plain.box.width - size / 6f, kerned.box.width, 0.02f)
                assertEquals(
                    plain.box.glyphs.last().x - size / 6f,
                    kerned.box.glyphs.last().x,
                    0.02f,
                )
                val decision = kerned.decisions.single {
                    it.name == "TeXExplicitMathSpace" && it.details["command"] == "\\!"
                }
                assertEquals("TeXFixedSignedMuKern", decision.details["policy"])
                assertEquals((-size / 6f).toString(), decision.details["advancePx"])
            }
        }
    }

    @Test
    fun explicitGlueDoesNotBecomeANoadOrPreventLeadingBinRepair() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("\\quad+x", MathLayoutOptions(fontSizePx = 36f))
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val repair = result.decisions.single { it.name == "TeXBinaryAtomReclassification" }
            assertEquals("Binary", repair.details["from"])
            assertEquals("Ordinary", repair.details["to"])
            assertEquals(MathAtomClass.Ordinary, result.fragments[1].atomClass)
            assertTrue(result.decisions.single { it.name == "TeXExplicitMathSpace" }
                .details["advancePx"]!!.toFloat() > 0f)
        }
    }

    @Test
    fun explicitGlueBetweenOrdAndBinPreservesTheRealNoadAdjacency() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val result = MathLayoutEngine(face).layout("a\\quad+b", MathLayoutOptions(fontSizePx = 36f))
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertEquals(MathAtomClass.Binary, result.fragments[2].atomClass)
            assertTrue(result.fragments[2].trailingGlue.naturalPx > 0f)
        }
    }
}
