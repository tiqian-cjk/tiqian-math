package org.tiqian.math.layout

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathAtomClass
import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathBreakOpportunity
import org.tiqian.math.core.MathFormulaLineMetrics
import org.tiqian.math.core.MathGlueAdjustment
import org.tiqian.math.core.MathInlineFragment
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.MathConstructionKind
import org.tiqian.math.font.opentype.MathGlyphAssemblyValidation
import org.tiqian.math.font.opentype.MathGlyphComponent
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.OpenTypeMathFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathResourceLimitsJvmTest {
    @Test
    fun rejectedResponsiveBreakCompletesWithoutQuadraticFallback() {
        val fragments = List(20_000) { index -> breakableFragment(index) }

        val resolution = assertCompletesWithinTwoSeconds {
            resolveResponsiveDisplayBreak(
                fragments = fragments,
                lineMetrics = LINE_METRICS,
                maxWidthPx = 100f,
                defaultContinuationIndentPx = 0f,
                resourceLimits = MathResourceLimits.Default.copy(maximumBreakpointCount = 1),
            )
        }

        assertEquals(
            DiagnosticCode.BreakpointCountLimitExceeded,
            resolution.layout.diagnostics.single().code,
        )
        assertEquals(1, resolution.layout.lines.size)
        assertEquals(fragments.size, resolution.layout.lines.single().fragments.size)
    }

    @Test
    fun extenderInstancesAccumulateAcrossConstructionsInOneLayoutPass() {
        val pass = MathLayoutPass(
            glyphSource = UnusedMathFontFace,
            textRunProvider = null,
            resourceLimits = MathResourceLimits.Default.copy(maximumExtenderCount = 3),
        )
        val construction = repeatedExtenderConstruction(repetitions = 1, extenderRecords = 2)

        assertTrue(pass.consumeConstructionExtenders(construction, SourceRange(0, 1)))
        assertFalse(pass.consumeConstructionExtenders(construction, SourceRange(1, 2)))

        val diagnostic = pass.diagnostics.single()
        assertEquals(DiagnosticCode.ExtenderCountLimitExceeded, diagnostic.code)
        assertEquals(SourceRange(1, 2), diagnostic.range)
        assertTrue(diagnostic.message.contains("extenderCount=4"), diagnostic.message)

        assertTrue(pass.consumeExtenders(1, SourceRange(2, 3)))
    }

    @Test
    fun formulaWideExtenderAdditionSaturatesInsteadOfOverflowingLong() {
        val pass = MathLayoutPass(
            glyphSource = UnusedMathFontFace,
            textRunProvider = null,
            resourceLimits = MathResourceLimits.Default.copy(maximumExtenderCount = 1),
        )
        assertTrue(pass.consumeExtenders(1, SourceRange(0, 1)))

        assertFalse(pass.consumeExtenders(Long.MAX_VALUE, SourceRange(1, 2)))

        val diagnostic = pass.diagnostics.single()
        assertEquals(DiagnosticCode.ExtenderCountLimitExceeded, diagnostic.code)
        assertEquals(SourceRange(1, 2), diagnostic.range)
        assertTrue(diagnostic.message.contains("extenderCount=${Long.MAX_VALUE}"), diagnostic.message)
    }

    @Test
    fun measurementProbeConsumesTheMaterializationBudget() {
        val pass = MathLayoutPass(
            glyphSource = UnusedMathFontFace,
            textRunProvider = null,
            resourceLimits = MathResourceLimits.Default.copy(maximumExtenderCount = 2),
        )

        pass.probeLayout {
            assertTrue(pass.consumeExtenders(2, SourceRange(0, 1)))
        }

        assertEquals(emptyList(), pass.diagnostics)
        assertFalse(pass.consumeExtenders(1, SourceRange(1, 2)))
        assertEquals(DiagnosticCode.ExtenderCountLimitExceeded, pass.diagnostics.single().code)
    }

    @Test
    fun resourceFailureInsideProbeSurvivesOrdinaryDiagnosticRollback() {
        val pass = MathLayoutPass(
            glyphSource = UnusedMathFontFace,
            textRunProvider = null,
            resourceLimits = MathResourceLimits.Default.copy(maximumExtenderCount = 0),
        )

        pass.probeLayout {
            assertFalse(pass.consumeExtenders(1, SourceRange(0, 1)))
        }

        assertEquals(DiagnosticCode.ExtenderCountLimitExceeded, pass.diagnostics.single().code)
    }

    private fun breakableFragment(index: Int): MathInlineFragment = MathInlineFragment(
        index = index,
        sourceRange = SourceRange(index, index + 1),
        atomClass = MathAtomClass.Relation,
        box = MathBox(
            width = 1f,
            ascent = 1f,
            descent = 0f,
            inkBounds = MathRect(0f, -1f, 1f, 0f),
            glyphs = emptyList(),
            rules = emptyList(),
            range = SourceRange(index, index + 1),
        ),
        leadingKernPx = 0f,
        trailingItalicCorrectionPx = 0f,
        trailingGlue = MathGlueAdjustment.Zero,
        breakAfter = MathBreakOpportunity(
            afterFragmentIndex = index,
            sourceOffset = index + 1,
            kind = MathBreakKind.RelationTrailing,
            discardedTrailingGlue = MathGlueAdjustment.Zero,
            priority = MathAdjustmentPriority.Relation,
        ),
    )

    private fun repeatedExtenderConstruction(
        repetitions: Int,
        extenderRecords: Int,
    ): MathVerticalConstruction = MathVerticalConstruction(
        kind = MathConstructionKind.Assembly,
        components = List(repetitions * extenderRecords) { index ->
            MathGlyphComponent(glyphId = (index + 1).toUShort(), offset = index.toFloat())
        },
        advanceMeasurement = 1f,
        reachesTarget = true,
        extenderRepetitions = repetitions,
        assemblyValidation = MathGlyphAssemblyValidation(
            valid = true,
            invalidReasons = emptySet(),
            extenderCount = extenderRecords,
            nonExtenderCount = 0,
            extenderNonOverlappingAdvance = 1L,
            checkedConnectionCount = 0,
            validationPolicy = "test",
            specificationDivergence = null,
        ),
        orthogonalAdvancePx = 1f,
    )

    private companion object {
        val LINE_METRICS = MathFormulaLineMetrics(
            fontAscentPx = 1f,
            fontDescentPx = 0f,
            fontLineGapPx = 0f,
            mathLeadingPx = 0f,
            inkAscentPx = 1f,
            inkDescentPx = 0f,
            logicalAscentPx = 1f,
            logicalDescentPx = 0f,
        )
    }
}

private object UnusedMathFontFace : MathFontFace {
    override val mathFont: OpenTypeMathFont get() = unused()

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol = unused()

    override fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator = unused()

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = unused()

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = unused()

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = unused()

    private fun unused(): Nothing = error("font access is outside this resource-accounting test")
}

private fun <T> assertCompletesWithinTwoSeconds(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "math-resource-fallback-timeout").apply { isDaemon = true }
    }
    return try {
        executor.submit<T> { block() }.get(2, TimeUnit.SECONDS)
    } finally {
        executor.shutdownNow()
    }
}
