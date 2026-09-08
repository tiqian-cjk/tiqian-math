package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.breakIntoLines
import org.tiqian.math.layout.breakResponsiveDisplayLines
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathResourceLimitsLayoutTest {
    @Test
    fun breakpointPreflightMatchesEachModesActualInternalBoundaries() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            // source, inline boundary count, display boundary count
            val cases = listOf(Triple("=a", 1, 0), Triple("a=", 0, 1), Triple("a,=b", 2, 1))
            for ((source, inlineCount, displayCount) in cases) {
                for (mode in MathMode.entries) {
                    val count = if (mode == MathMode.Inline) inlineCount else displayCount
                    for (limit in 0..2) {
                        val engine = MathLayoutEngine(
                            face,
                            resourceLimits = MathResourceLimits.Default.copy(maximumBreakpointCount = limit),
                        )
                        val result = engine.layout(source, MathLayoutOptions(mode = mode))
                        val broken = if (mode == MathMode.Inline) result.breakIntoLines(100f)
                            else result.breakResponsiveDisplayLines(100f)
                        val expectedRejection = count > limit
                        val label = "$source/$mode/limit=$limit"
                        assertEquals(expectedRejection, result.diagnostics.any {
                            it.code == DiagnosticCode.BreakpointCountLimitExceeded
                        }, "preflight: $label")
                        assertEquals(expectedRejection, broken.diagnostics.any {
                            it.code == DiagnosticCode.BreakpointCountLimitExceeded
                        }, "breaker: $label")
                    }
                }
            }
        }
    }

    @Test
    fun reparsesPreparedFormulaWhenEnginePolicyChanges() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val prepared = MathLayoutEngine(face).prepare("xy")
            val strict = MathLayoutEngine(
                face,
                resourceLimits = MathResourceLimits.Default.copy(maximumTokenCount = 1),
            )

            val result = strict.layout(prepared)

            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.TokenCountLimitExceeded })
        }
    }

    @Test
    fun revalidatesRuleAndRowDimensionsAfterUnitConversion() {
        val limits = MathResourceLimits.Default.copy(maximumResolvedDimensionPx = 96f)
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = MathLayoutEngine(face, resourceLimits = limits)

            val exact = engine.layout("\\rule{1in}{1px}")
            assertFalse(exact.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val rule = engine.layout("\\rule{2in}{1px}")
            assertTrue(rule.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val negativeRaise = engine.layout("\\rule[-2in]{1px}{1px}")
            assertTrue(negativeRaise.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val conversionOverflow = MathLayoutEngine(face).layout(
                "\\rule{340282346638528859811704183484516925440em}{1px}",
            )
            assertTrue(conversionOverflow.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val row = engine.layout(
                "\\begin{aligned}a&=b\\\\[2in]c&=d\\end{aligned}",
            )
            assertTrue(row.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })
            assertTrue(rule.box.width.isFinite() && rule.box.height.isFinite())
            assertTrue(conversionOverflow.box.width.isFinite() && conversionOverflow.box.height.isFinite())
            assertTrue(row.box.width.isFinite() && row.box.height.isFinite())
        }
    }

    @Test
    fun revalidatesAggregatedAndDerivedDimensions() {
        val limits = MathResourceLimits.Default.copy(maximumResolvedDimensionPx = 25f)
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = MathLayoutEngine(face, resourceLimits = limits)

            val negation = engine.layout("\\not\\!\\!\\!\\!\\!\\!\\!p")
            assertTrue(negation.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val declaredSize = engine.layout("\\Huge x")
            assertTrue(declaredSize.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val explicitMu = engine.layout("\\qquad x")
            assertTrue(explicitMu.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })

            val lowLimit = limits.copy(maximumResolvedDimensionPx = 10f)
            val lowLimitEngine = MathLayoutEngine(face, resourceLimits = lowLimit)
            val fallback = lowLimitEngine.layout("x")
            assertTrue(fallback.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })
            assertTrue(fallback.fontSizePx <= lowLimit.maximumResolvedDimensionPx)

            val cancel = lowLimitEngine.layout(
                "\\cancel{x}",
                MathLayoutOptions(fontSizePx = 10f, cancelPicturePointPx = 10f),
            )
            assertTrue(cancel.diagnostics.any { it.code == DiagnosticCode.InvalidResolvedDimension })
            assertTrue(cancel.box.width.isFinite() && cancel.box.height.isFinite())
        }
    }
}
