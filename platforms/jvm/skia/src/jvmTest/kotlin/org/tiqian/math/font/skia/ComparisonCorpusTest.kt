package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonCorpusTest {
    @Test
    fun texXeTeXAndOpenTypeComparisonCorpusRunsThroughBothFaces() {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/corpus/tex-xetex-geometry.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 4) { "invalid comparison corpus row: $line" }
                    CorpusCase(
                        columns[0],
                        columns[1],
                        ComparisonOracle.from(columns[2]),
                        ComparisonInvariant.from(columns[3]),
                    )
                }.toList()
            }
        assertEquals(
            setOf(
                "ordinary-sub-mlist",
                "tight-spacing",
                "cramped-superscript",
                "script-binomial",
                "math-kern",
                "tall-binomial-fixed",
                "operator-auto",
                "operator-integral",
                "radical-noad",
                "content-delimiter",
            ),
            corpus.map { it.id }.toSet(),
        )

        listOf(LeteSansMath.load(), StixTwoMath.load()).forEach { font ->
            SkiaMathFontFace(font).use { face ->
                val engine = MathLayoutEngine(face)
                corpus.forEach { case ->
                    val result = engine.layout(case.source, MathLayoutOptions(fontSizePx = 44f))
                    when (case.invariant) {
                        ComparisonInvariant.OrdinarySubMlistBoundary -> {
                            assertEquals(ComparisonOracle.TeXOrdinarySubMlist, case.oracle)
                            assertEquals(3, result.fragments.size, case.toString())
                            assertTrue(result.breakOpportunities.isEmpty(), case.toString())
                            assertTrue(result.decisions.any { it.name == "TeXOrdSubMlist" }, case.toString())
                            assertTrue(result.decisions.any {
                                it.name == "TeXBinaryAtomReclassification" &&
                                    it.details["from"] == "Binary" && it.details["to"] == "Ordinary"
                            }, case.toString())
                        }
                        ComparisonInvariant.TightBinaryGlueSuppressed -> {
                            assertEquals(ComparisonOracle.XeTeXMlistToHlistSpacingTable, case.oracle)
                            val binary = result.decisions.filter { it.name == "TeXMathAtomSpacing" && it.range.start in 4..5 }
                            assertEquals(2, binary.size, case.toString())
                            assertTrue(binary.all { it.details["table"] == "tight" && it.details["kind"] == "None" }, case.toString())
                        }
                        ComparisonInvariant.CrampedNestedSuperscript -> {
                            assertEquals(ComparisonOracle.XeTeXSuperscriptStyleFormula, case.oracle)
                            val z = result.box.glyphs.first { it.sourceRange == SourceRange(case.source.indexOf('z'), case.source.indexOf('z') + 1) }
                            assertEquals(MathStyle.ScriptScriptCramped, z.style, case.toString())
                        }
                        ComparisonInvariant.ScriptBinomialFixedTarget -> {
                            assertEquals(ComparisonOracle.LaTeX2eXeTeXGenfracFixedTargets, case.oracle)
                            assertFalse(result.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort }, case.toString())
                            assertTrue(result.decisions.filter { it.name == "BinomialDelimiter" }.all {
                                it.details["targetEmFactor"] == "1.45" &&
                                    it.details["delimiterStyle"] == "Text" &&
                                    it.details["stackCoverageRequired"] == "false"
                            }, case.toString())
                            assertTrue(result.decisions.any { it.name == "TeXBinomialFractionNoadPacking" })
                        }
                        ComparisonInvariant.FinalMathKernParticipates -> {
                            assertEquals(ComparisonOracle.OpenTypeMathKern, case.oracle)
                            assertTrue(result.decisions.any {
                                it.name == "OpenTypeMathKern" && it.details["strategy"] == "two-correction-heights"
                            }, case.toString())
                        }
                        ComparisonInvariant.TallBinomialUsesFixedTarget -> {
                            assertEquals(ComparisonOracle.TectonicXeTeXBoxTrace, case.oracle)
                            val tall = result.decisions.filter { it.name == "BinomialDelimiter" }
                            val simple = engine.layout("\\binom{n}{k}", MathLayoutOptions(fontSizePx = 44f))
                                .decisions.filter { it.name == "BinomialDelimiter" }
                            assertEquals(simple.map { it.details["construction"] }, tall.map { it.details["construction"] })
                            assertTrue(tall.all {
                                it.details["targetEmFactor"] == "1.0" &&
                                    it.details["coversStackTop"] == "false" &&
                                    it.details["coversStackBottom"] == "false"
                            }, case.toString())
                        }
                        ComparisonInvariant.OperatorAutoDisplayLimits -> {
                            assertEquals(ComparisonOracle.TeXMakeOpAndOpenTypeNary, case.oracle)
                            assertEquals(
                                "NoLimits",
                                result.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            val display = engine.layout(case.source, MathLayoutOptions(MathMode.Display, 44f))
                            assertEquals(
                                "Limits",
                                display.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            val operator = display.decisions.first { it.name == "TeXOperatorNoad" }
                            assertTrue(operator.details["construction"] != "BaseGlyph", case.toString())
                            assertNear(
                                operator.details.getValue("axisY").toFloat(),
                                operator.details.getValue("outlineCenterAfter").toFloat(),
                                case,
                            )
                            assertTrue(display.decisions.any { it.name == "OpenTypeMathOperatorLimits" })
                        }
                        ComparisonInvariant.IntegralDefaultNoLimits -> {
                            assertEquals(ComparisonOracle.PlainTeXIntegralNoLimits, case.oracle)
                            val display = engine.layout(case.source, MathLayoutOptions(MathMode.Display, 44f))
                            assertEquals(
                                "NoLimits",
                                display.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                                case.toString(),
                            )
                            assertTrue(display.decisions.any { it.name == "TeXOperatorSideScripts" })
                            val forced = engine.layout(
                                "\\int\\limits_i^n",
                                MathLayoutOptions(MathMode.Inline, 44f),
                            )
                            assertEquals(
                                "Limits",
                                forced.decisions.first { it.name == "TeXOperatorLimitsPolicy" }
                                    .details["effectivePolicy"],
                            )
                        }
                        ComparisonInvariant.RadicalCrampedDegreeAndMathGeometry -> {
                            assertEquals(ComparisonOracle.TeXMakeRadicalAndOpenTypeMath, case.oracle)
                            val noad = result.decisions.first { it.name == "TeXRadicalNoad" }
                            val geometry = result.decisions.first { it.name == "OpenTypeMathRadical" }
                            assertEquals("TextCramped", noad.details["radicandStyle"], case.toString())
                            assertEquals("ScriptScript", noad.details["degreeStyle"], case.toString())
                            assertEquals("Ordinary", noad.details["atomClass"], case.toString())
                            assertNear(
                                geometry.details.getValue("radicalRuleThicknessPx").toFloat(),
                                geometry.details.getValue("ruleBottom").toFloat() -
                                    geometry.details.getValue("ruleTop").toFloat(),
                                case,
                            )
                            assertTrue(
                                geometry.details.getValue("actualRadicalGapPx").toFloat() + 0.03f >=
                                    geometry.details.getValue("radicalVerticalGapPx").toFloat(),
                                case.toString(),
                            )
                            assertTrue(result.decisions.any { it.name == "OpenTypeRadicalConstruction" })
                        }
                        ComparisonInvariant.ContentDrivenDelimiterTargetAndPacking -> {
                            assertEquals(ComparisonOracle.TectonicXeTeXMakeLeftRightTrace, case.oracle)
                            val group = result.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }
                            val target = group.details.getValue("targetPx").toFloat()
                            assertEquals("UnbreakableContentDrivenFencedInnerNoad", group.details["groupBreakPolicy"])
                            assertEquals("false", group.details["internalBreaksExported"])
                            val delimiters = result.decisions.filter { it.name == "TeXContentDrivenDelimiter" }
                            assertEquals(3, delimiters.size)
                            assertTrue(delimiters.all {
                                abs(it.details.getValue("targetPx").toFloat() - target) <= 0.03f &&
                                    it.details["targetPolicy"] == "XeTeXMakeLeftRightAxisFactorShortfall"
                            }, case.toString())
                        }
                    }
                }
            }
        }
    }
}

private data class CorpusCase(
    val id: String,
    val source: String,
    val oracle: ComparisonOracle,
    val invariant: ComparisonInvariant,
)

private enum class ComparisonOracle(val corpusText: String) {
    TeXOrdinarySubMlist("TeX ordinary sub-mlist noad"),
    XeTeXMlistToHlistSpacingTable("XeTeX mlist_to_hlist 8x8 spacing table"),
    XeTeXSuperscriptStyleFormula("XeTeX clean_box superscript style formula"),
    LaTeX2eXeTeXGenfracFixedTargets("LaTeX2e XeTeX genfrac fixed style targets"),
    OpenTypeMathKern("OpenType MATH MathKern two-height algorithm"),
    TectonicXeTeXBoxTrace("Tectonic 0.17.0 XeTeX box trace"),
    TeXMakeOpAndOpenTypeNary("TeX make_op displaylimits; OpenType MATH n-ary variants"),
    PlainTeXIntegralNoLimits("plain.tex integral nolimits; TeX op noad"),
    TeXMakeRadicalAndOpenTypeMath("TeX make_radical; LaTeX2e root; OpenType MATH radicals"),
    TectonicXeTeXMakeLeftRightTrace("Tectonic 0.17.0 XeTeX make_left_right box trace"),
    ;

    companion object {
        fun from(text: String): ComparisonOracle = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison oracle: $text")
    }
}

private enum class ComparisonInvariant(val corpusText: String) {
    OrdinarySubMlistBoundary("group is one Ord atom and edge Bin becomes Ord internally"),
    TightBinaryGlueSuppressed("binary glue is suppressed in ScriptCramped"),
    CrampedNestedSuperscript("nested denominator superscripts remain ScriptScriptCramped"),
    ScriptBinomialFixedTarget("ScriptCramped uses 1.45 script em target with text-style delimiter selection"),
    FinalMathKernParticipates("parsed corner kerns participate in final script x"),
    TallBinomialUsesFixedTarget("tall content does not alter the fixed text-style binomial delimiter target"),
    OperatorAutoDisplayLimits("Auto uses stacked limits only in display style and the operator follows the math axis"),
    IntegralDefaultNoLimits("integrals default to side scripts while an explicit limits modifier overrides"),
    RadicalCrampedDegreeAndMathGeometry(
        "radicand is cramped, degree is scriptscript, and named MATH radical geometry is used",
    ),
    ContentDrivenDelimiterTargetAndPacking(
        "all left middle right delimiters share the completed clean-box TeX target and remain unbreakable",
    ),
    ;

    companion object {
        fun from(text: String): ComparisonInvariant = entries.singleOrNull { it.corpusText == text }
            ?: error("unreviewed comparison invariant: $text")
    }
}

private fun assertNear(expected: Float, actual: Float, case: CorpusCase) {
    assertTrue(abs(expected - actual) <= 0.03f, "$case expected=$expected actual=$actual")
}
