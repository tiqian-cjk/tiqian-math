package org.tiqian.math.font.skia

import org.tiqian.math.core.MathMode
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reviewed platform snapshot: raster ink bounds are platform-specific even for identical fonts.
 * Outline/oracle invariant tests remain the authority for platform-independent correctness.
 */
class GeometryGoldenTest {
    @Test
    fun twoFontGeometryAndDecisionSnapshotMatchesReviewedGolden() {
        val goldenName = "geometry-v3-${platformId()}.txt"
        val actual = buildString {
            appendLine("geometry-v3")
            listOf(
                "Lete Sans Math" to LeteSansMath.load(),
                "STIX Two Math" to StixTwoMath.load(),
            ).forEach { (label, font) -> appendFace(label, font) }
        }.normalizedGoldenText()
        if (System.getenv("TIQIAN_UPDATE_GOLDEN") == "1") {
            File("src/jvmTest/resources/goldens/$goldenName").writeText("$actual\n")
            // The classpath still contains the previous resource until processTestResources runs.
            // This explicit capture mode does not claim that the new snapshot has been reviewed.
            println("Captured $goldenName; review it and rerun without TIQIAN_UPDATE_GOLDEN to verify.")
            return
        }
        val expected = checkNotNull(javaClass.getResourceAsStream("/goldens/$goldenName")) {
            "Missing reviewed platform golden: $goldenName. Capture explicitly with " +
                "TIQIAN_UPDATE_GOLDEN=1 ./gradlew :platforms:jvm:skia:jvmTest " +
                "--tests 'org.tiqian.math.font.skia.GeometryGoldenTest' --rerun-tasks, " +
                "review the generated file against geometry-v2.txt and the outline/oracle tests, " +
                "then rerun without TIQIAN_UPDATE_GOLDEN."
        }.bufferedReader().use { it.readText() }.normalizedGoldenText()
        assertEquals(expected, actual, goldenName)
    }

    private fun StringBuilder.appendFace(label: String, font: OpenTypeMathFont) {
        appendLine("face=$label upm=${font.unitsPerEm} line=${font.lineMetrics.typoAscender}/${font.lineMetrics.typoDescender}/${font.lineMetrics.typoLineGap}")
        SkiaMathFontFace(font).use { face ->
            val engine = MathLayoutEngine(face)
            listOf(
                GoldenCase("symbols", "x+\\mathrm{x}+\\alpha+\\Gamma+2", MathMode.Inline, 40f),
                GoldenCase("group", "a{+}b", MathMode.Inline, 40f),
                GoldenCase("style", "{\\scriptstyle x+y}z", MathMode.Inline, 40f),
                GoldenCase("tight", "x_{k-1}", MathMode.Inline, 40f),
                GoldenCase("cramped", "\\frac{a}{x^{y^z}}", MathMode.Inline, 40f),
                GoldenCase(
                    "side-script-ink",
                    "\\sqrt{x}_{\\sqrt{y}}^{\\sqrt{z}}",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase("script-binomial", "\\frac{a}{\\binom{n}{k}}", MathMode.Inline, 40f),
                GoldenCase("operator-inline", "\\sum_i^n+\\int_0^1", MathMode.Inline, 40f),
                GoldenCase("operator-display", "\\sum_i^n+\\int\\limits_0^1", MathMode.Display, 40f),
                GoldenCase(
                    "operator-limit-skew",
                    "\\int\\limits_{abcdefgh}^{abcdefgh}",
                    MathMode.Display,
                    40f,
                ),
                GoldenCase("radical-inline", "\\sqrt[3]{x^2+1}", MathMode.Inline, 40f),
                GoldenCase(
                    "radical-display",
                    "\\sqrt{\\frac{a+b}{\\sqrt{x}}}",
                    MathMode.Display,
                    40f,
                ),
                GoldenCase(
                    "content-delimiters",
                    "\\left\\langle a\\middle|\\frac{b}{c}\\right\\rangle",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "fixed-delimiters",
                    "\\bigl(x\\bigr)+\\Bigl[x\\Bigr]+\\Bigg\\uparrow x\\Bigg\\Downarrow",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "extensible-arrow",
                    "X\\xrightarrow[k-1]{p_k}Y",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "over-under",
                    "a\\overset{u}{=}b+\\underset{d}{x}+\\stackrel{s}{=}",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "common-fractions",
                    "\\dfrac{a}{b}+\\cfrac[l]{x}{bbbb}",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "primitive-mathop",
                    "\\mathop{abc}_0^1+{\\displaystyle\\mathop{x+y}_0^1}",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "growing-braces",
                    "\\overbrace{a+b+c+d+e}^{n}+\\underbrace{x+y}_{k}",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "scoped-color",
                    "{\\color{red}a+{\\color{blue}b}+c}+d",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase(
                    "boxed",
                    "{\\color{violet}\\boxed{\\frac{a}{b}}}+x",
                    MathMode.Inline,
                    40f,
                ),
                GoldenCase("adjustment", "a,b=c+d", MathMode.Inline, 40f),
            ).forEach { case ->
                val result = engine.layout(case.source, MathLayoutOptions(case.mode, case.size))
                appendLine(
                    "case=${case.id} logical=${result.box.width.fmt()} visual=${result.box.visualLeft.fmt()}..${result.box.visualRight.fmt()} " +
                        "ink=${result.box.inkBounds.left.fmt()},${result.box.inkBounds.top.fmt()},${result.box.inkBounds.right.fmt()},${result.box.inkBounds.bottom.fmt()} " +
                        "safe=${result.lineMetrics.logicalAscentPx.fmt()}/${result.lineMetrics.logicalDescentPx.fmt()} " +
                        "fragments=${result.fragments.size} breaks=${result.breakOpportunities.size} diagnostics=${result.diagnostics.map { it.code }}",
                )
                when (case.id) {
                    "symbols" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "TeXMathSymbolResolution" }
                            .joinToString(",") {
                                "${it.details["identity"]}:${it.details["declaredFamily"]}/${it.details["familyBinding"]}/" +
                                    "${it.details["declaredAlphabet"]}->${it.details["resolvedFamily"]}/" +
                                    "${it.details["resolvedAlphabet"]}/${it.details["backendScalar"]}/${it.details["glyphIds"]}"
                            },
                    )
                    "group" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXOrdSubMlist" || it.name == "TeXBinaryAtomReclassification"
                        }.joinToString(",") { "${it.name}:${it.range.start}..${it.range.endExclusive}:${it.details}" },
                    )
                    "style" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXMathStyleDeclaration" || it.name == "TeXOrdSubMlist"
                        }.joinToString(",") { "${it.name}:${it.range.start}..${it.range.endExclusive}:${it.details}" },
                    )
                    "tight" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "TeXMathAtomSpacing" }
                            .joinToString(",") { "${it.range.start}..${it.range.endExclusive}:${it.details["table"]}/${it.details["kind"]}" },
                    )
                    "cramped" -> appendLine(
                        "  evidence=" + listOf('x', 'y', 'z').joinToString(",") { character ->
                            val offset = case.source.indexOf(character)
                            val glyph = result.box.glyphs.first { it.sourceRange == SourceRange(offset, offset + 1) }
                            "$character:${glyph.style}@${glyph.baselineY.fmt()}"
                        },
                    )
                    "side-script-ink" -> {
                        val scripts = result.decisions.first { it.name == "OpenTypeMathScriptPlacement" }
                        appendLine(
                            "  evidence=policy=${scripts.details["verticalPlacementMetricPolicy"]}/" +
                                "${scripts.details["logicalReservePolicy"]} " +
                                "kinds=${scripts.details["baseKind"]}/" +
                                "${scripts.details["superscriptKind"]}/${scripts.details["subscriptKind"]} " +
                                "base=${scripts.details["baseLogicalAscentPx"]}/" +
                                "${scripts.details["baseLogicalDescentPx"]}:" +
                                "${scripts.details["baseInkTopPx"]}/${scripts.details["baseInkBottomPx"]} " +
                                "sup=${scripts.details["superscriptLogicalAscentPx"]}/" +
                                "${scripts.details["superscriptLogicalDescentPx"]}:" +
                                "${scripts.details["superscriptInkTopPx"]}/${scripts.details["superscriptInkBottomPx"]} " +
                                "sub=${scripts.details["subscriptLogicalAscentPx"]}/" +
                                "${scripts.details["subscriptLogicalDescentPx"]}:" +
                                "${scripts.details["subscriptInkTopPx"]}/${scripts.details["subscriptInkBottomPx"]} " +
                                "shifts=${scripts.details["superscriptShiftPx"]}/" +
                                "${scripts.details["subscriptShiftPx"]} " +
                                "gap=${scripts.details["pairGapBeforeAdjustmentPx"]}/" +
                                "${scripts.details["finalInkGapPx"]}",
                        )
                    }
                    "script-binomial" -> {
                        val delimiters = result.decisions.filter { it.name == "BinomialDelimiter" }
                        val packing = result.decisions.single { it.name == "TeXBinomialFractionNoadPacking" }
                        appendLine(
                            "  evidence=" + delimiters.joinToString(",") {
                                "${it.details["side"]}:${it.details["baseGlyphId"]}/${it.details["construction"]}/" +
                                    "${it.details["targetPolicy"]}/${it.details["delimiterStyle"]}/" +
                                    "${it.details["targetEmFactor"]}*" +
                                    "${it.details.getValue("targetReferenceFontSizePx").toFloat().fmt()}=" +
                                    "${it.details.getValue("targetPx").toFloat().fmt()}/" +
                                    "${it.details.getValue("achievedAdvancePx").toFloat().fmt()}"
                            } +
                                " packing=${packing.details.getValue("stackX").toFloat().fmt()}/" +
                                "${packing.details.getValue("rightDelimiterX").toFloat().fmt()}/" +
                                "${packing.details.getValue("totalAdvancePx").toFloat().fmt()}/" +
                                packing.details["policy"],
                        )
                    }
                    "operator-inline", "operator-display" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXOperatorNoad" ||
                                it.name == "TeXOperatorLimitsPolicy" ||
                                it.name == "OpenTypeMathOperatorLimits" ||
                                it.name == "TeXOperatorSideScripts"
                        }.joinToString(",") { decision ->
                            when (decision.name) {
                                "TeXOperatorNoad" ->
                                    "op:${decision.details["identity"]}/${decision.details["style"]}/" +
                                        "${decision.details["construction"]}/" +
                                        "${decision.details.getValue("displayOperatorMinHeightPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("achievedAdvancePx").toFloat().fmt()}/" +
                                        "axis=${decision.details.getValue("outlineCenterAfter").toFloat().fmt()}/" +
                                        "ic=${decision.details.getValue("italicCorrectionPx").toFloat().fmt()}/" +
                                        decision.details["italicCorrectionSource"]
                                "TeXOperatorLimitsPolicy" ->
                                    "policy:${decision.details["identity"]}/${decision.details["declaredPolicy"]}->" +
                                        "${decision.details["effectivePolicy"]}/${decision.details["reason"]}"
                                "OpenTypeMathOperatorLimits" ->
                                    "limits:${decision.details.getValue("actualUpperGapPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualUpperBaselineRisePx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualLowerGapPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualLowerBaselineDropPx").toFloat().fmt()}"
                                else -> "side:${decision.details["identity"]}/${decision.details["style"]}"
                            }
                        },
                    )
                    "operator-limit-skew" -> {
                        val limits = result.decisions.first { it.name == "OpenTypeMathOperatorLimits" }
                        appendLine(
                            "  evidence=policy=${limits.details["logicalWidthPolicy"]} " +
                                "widths=${limits.details.getValue("operatorWidthPx").toFloat().fmt()}/" +
                                "${limits.details.getValue("upperWidthPx").toFloat().fmt()}/" +
                                "${limits.details.getValue("lowerWidthPx").toFloat().fmt()} " +
                                "x=${limits.details.getValue("operatorX").toFloat().fmt()}/" +
                                "${limits.details.getValue("upperX").toFloat().fmt()}/" +
                                "${limits.details.getValue("lowerX").toFloat().fmt()} " +
                                "ic=${limits.details.getValue("operatorItalicCorrectionPx").toFloat().fmt()}",
                        )
                    }
                    "common-fractions" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXFractionCommand" ||
                                it.name == "AmsmathContinuedFractionNumeratorStrut" ||
                                it.name == "TeXFractionNullDelimiters"
                        }.joinToString(",") { "${it.name}:${it.details}" },
                    )
                    "primitive-mathop" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXMathOperatorNoad" ||
                                it.name == "TeXOperatorLimitsPolicy" ||
                                it.name == "OpenTypeMathOperatorLimits"
                        }.joinToString(",") { "${it.name}:${it.details}" },
                    )
                    "growing-braces" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXBraceOperatorNoad" ||
                                (it.name == "OpenTypeMathAccent" && it.details["identity"]?.endsWith("brace") == true) ||
                                it.name == "OpenTypeMathOperatorLimits"
                        }.joinToString(",") { "${it.name}:${it.details}" },
                    )
                    "radical-inline", "radical-display" -> appendLine(
                        "  evidence=" + result.decisions.filter {
                            it.name == "TeXRadicalNoad" ||
                                it.name == "OpenTypeRadicalConstruction" ||
                                it.name == "OpenTypeMathRadical"
                        }.joinToString(",") { decision ->
                            when (decision.name) {
                                "TeXRadicalNoad" ->
                                    "noad:${decision.range.start}..${decision.range.endExclusive}/" +
                                        "${decision.details["style"]}/${decision.details["radicandStyle"]}/" +
                                        "${decision.details["degreeStyle"]}/${decision.details["scriptBaseKind"]}"
                                "OpenTypeRadicalConstruction" ->
                                    "construction:${decision.details["construction"]}/" +
                                        "${decision.details["selectionStep"]}/" +
                                        "${decision.details["targetMetric"]}/" +
                                        "clean=${decision.details["cleanRadicandAscentPx"]}+" +
                                        "${decision.details["cleanRadicandDescentPx"]}/" +
                                        "ink=${decision.details["radicandInkHeightPx"]}/" +
                                        "base=${decision.details["baseGlyphCoversTarget"]}/" +
                                        "${decision.details.getValue("targetHeightPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("achievedAdvancePx").toFloat().fmt()}/" +
                                        "${decision.details["componentGlyphIds"]}/${decision.details["componentOffsetsDesignUnits"]}"
                                else ->
                                    "geometry:${decision.details["unindexedBoxPolicy"]}/" +
                                        "${decision.details.getValue("radicalVerticalGapPx").toFloat().fmt()}/" +
                                        "excess=${decision.details.getValue("constructionExcessPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("actualRadicalGapPx").toFloat().fmt()}/" +
                                        "${decision.details["clearancePolicy"]}/" +
                                        "${decision.details.getValue("radicalRuleThicknessPx").toFloat().fmt()}/" +
                                        "extra=${decision.details.getValue("radicalExtraAscenderPx").toFloat().fmt()}/" +
                                        "${decision.details["radicalExtraAscenderUsed"]}/" +
                                        "delimiter=${decision.details["texDelimiterBoxHeightPx"]}+" +
                                        "${decision.details["texDelimiterBoxDepthPx"]}@" +
                                        "${decision.details["texDelimiterBoxShiftPx"]}/" +
                                        "rule=${decision.details["ruleTop"]}..${decision.details["ruleBottom"]}/" +
                                        "${decision.details.getValue("radicalKernBeforeDegreePx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalKernAfterDegreePx").toFloat().fmt()}/" +
                                        "horizontal=${decision.details["degreeHorizontalPlacementPolicy"]}/" +
                                        "used=${decision.details["usedRadicalKernBeforeDegreePx"]}/" +
                                        "lower=${decision.details["radicalDegreeAfterKernClampLowerBoundPx"]}/" +
                                        "after=${decision.details.getValue("adjustedRadicalKernAfterDegreePx").toFloat().fmt()}/" +
                                        "x=${decision.details["degreeX"]}/${decision.details["radicalX"]}/" +
                                        "${decision.details["radicalDegreeBottomRaisePercent"]}/" +
                                        "raise=${decision.details["degreeRaiseReferencePx"]}(" +
                                        "${decision.details["degreeRaiseReferenceAscentPx"]}+" +
                                        "${decision.details["degreeRaiseReferenceDescentPx"]})/" +
                                        "${decision.details["degreeRaiseReferenceMetric"]}/" +
                                        "${decision.details["degreeRaisePx"]}/" +
                                        "bottom=${decision.details["degreeLogicalBottomY"]}/" +
                                        "${decision.details["degreeInkBottomY"]}/" +
                                        "B=${decision.details.getValue("unindexedAscentPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("unindexedDescentPx").toFloat().fmt()}/" +
                                        "degreeBaseline=${decision.details["degreeBaselineY"]}/" +
                                        "${decision.details.getValue("logicalWidthPx").toFloat().fmt()}/" +
                                        "seam=${decision.details["radicalTopStrokeEvidence"]}/" +
                                        "${decision.details["radicalTopStrokeEvidenceSource"]}/" +
                                        "${decision.details.getValue("radicalTopStrokeTopPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalTopStrokeBottomPx").toFloat().fmt()}/" +
                                        "${decision.details.getValue("radicalTopStrokeRightPx").toFloat().fmt()}->" +
                                        "${decision.details.getValue("ruleLeft").toFloat().fmt()}/" +
                                        "advance=${decision.details.getValue("radicalBoxAdvancePx").toFloat().fmt()}/" +
                                        "bounds=${decision.details["radicalGlyphBoundsSources"]}/" +
                                        "logical=${decision.details["radicalLogicalAdvancePolicy"]}"
                            }
                        },
                    )
                    "content-delimiters" -> {
                        val group = result.decisions.single { it.name == "TeXContentDrivenDelimitedGroup" }
                        val delimiters = result.decisions.filter { it.name == "TeXContentDrivenDelimiter" }
                        appendLine(
                            "  evidence=group=" +
                                "${group.details["innerCleanAscentPx"]}+${group.details["innerCleanDescentPx"]}/" +
                                "axis=${group.details["axisHeightPx"]}/" +
                                "targets=${group.details["factorTargetPx"]},${group.details["shortfallTargetPx"]}->" +
                                "${group.details["targetPx"]}/${group.details["groupBreakPolicy"]} " +
                                delimiters.joinToString(",") { delimiter ->
                                    "${delimiter.details["side"]}:${delimiter.details["sourceSpelling"]}/" +
                                        "${delimiter.details["scalar"]}/${delimiter.details["construction"]}/" +
                                        "${delimiter.details["glyphIds"]}@${delimiter.details["componentBaselineOriginsPx"]}/" +
                                        "${delimiter.details["achievedAdvancePx"]}/" +
                                        "${delimiter.details["centerShiftPx"]}/" +
                                        "${delimiter.details["logicalAdvancePx"]}/" +
                                        "${delimiter.details["capability"]}"
                                },
                        )
                    }
                    "fixed-delimiters" -> {
                        val delimiters = result.decisions.filter { it.name == "TeXFixedSizeDelimiter" }
                        val noads = result.decisions.filter { it.name == "AmsmathFixedDelimiterNoad" }
                        appendLine(
                            "  evidence=" + delimiters.joinToString(",") { delimiter ->
                                "${delimiter.details["role"]}:${delimiter.details["sourceSpelling"]}/" +
                                    "${delimiter.details["fixedSize"]}/${delimiter.details["construction"]}/" +
                                    "${delimiter.details["glyphIds"]}@${delimiter.details["componentBaselineOriginsPx"]}/" +
                                    "${delimiter.details["requestedExtentPx"]}->${delimiter.details["targetPx"]}/" +
                                    "${delimiter.details["logicalAdvancePx"]}/${delimiter.details["capability"]}"
                            } + " noads=" + noads.joinToString(",") { noad ->
                                "${noad.details["measurementStyle"]}:" +
                                    "${noad.details["mathStrutAscentPx"]}+${noad.details["mathStrutDescentPx"]}/" +
                                    "${noad.details["bigSizePx"]}*${noad.details["amsmathFactor"]}/" +
                                    "${noad.details["vcenterAscentPx"]}+${noad.details["vcenterDescentPx"]}/" +
                                    noad.details["targetPolicy"]
                            },
                        )
                    }
                    "extensible-arrow" -> {
                        val arrow = result.decisions.single { it.name == "AmsmathXeTeXExtensibleArrow" }
                        appendLine(
                            "  evidence=${arrow.details["identity"]}/" +
                                "head=${arrow.details["arrowHeadGlyphId"]}/" +
                                "relbar=${arrow.details["relbarGlyphId"]}/" +
                                "leaders=${arrow.details["leaderCount"]}@${arrow.details["leaderOriginsPx"]}/" +
                                "${arrow.details["fillPolicy"]}/" +
                                "target=${arrow.details.getValue("targetWidthPx").toFloat().fmt()}/" +
                                "up=${arrow.details["upperShiftPx"]}/down=${arrow.details["lowerShiftPx"]}/" +
                                "outer=${arrow.details["upperOuterPaddingPx"]},${arrow.details["lowerOuterPaddingPx"]}/" +
                                "box=${arrow.details["logicalWidthPx"]},${arrow.details["logicalAscentPx"]}," +
                                "${arrow.details["logicalDescentPx"]}",
                        )
                    }
                    "over-under" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "TeXOverUnderNoad" }
                            .joinToString(",") { stack ->
                                "${stack.details["kind"]}/${stack.details["atomClass"]}/" +
                                    "${stack.details["annotationStyle"]}/" +
                                    "${stack.details["actualUpperGapPx"]}/${stack.details["actualLowerGapPx"]}/" +
                                    "${stack.details["logicalWidthPx"]}/${stack.details["geometryKernel"]}"
                            },
                    )
                    "scoped-color" -> appendLine(
                        "  evidence=" + result.decisions.filter { it.name == "XColorMathDeclaration" }
                            .joinToString(",") { color ->
                                "${color.range.start}..${color.range.endExclusive}:" +
                                    "${color.details["sourceName"]}/${color.details["resolvedArgb"]}/" +
                                    "${color.details["scopePolicy"]}/${color.details["resolutionPolicy"]}"
                            } + " glyphPaint=" + result.box.glyphs.joinToString(",") {
                                "${it.sourceRange.start}:${it.paintColor?.argb?.toUInt()?.toString(16) ?: "host"}"
                            },
                    )
                    "boxed" -> {
                        val boxed = result.decisions.single { it.name == "AmsmathBoxedNoad" }
                        appendLine(
                            "  evidence=styles=${boxed.details["outerStyle"]}->${boxed.details["contentStyle"]}/" +
                                "fbox=${boxed.details["fboxSeparationPx"]}+${boxed.details["fboxRuleThicknessPx"]}/" +
                                "content=${boxed.details["contentWidthPx"]},${boxed.details["contentAscentPx"]}," +
                                "${boxed.details["contentDescentPx"]}/" +
                                "box=${boxed.details["logicalWidthPx"]},${boxed.details["logicalAscentPx"]}," +
                                "${boxed.details["logicalDescentPx"]}/rules=${result.box.rules.size}/" +
                                "paint=${result.box.rules.map { it.paintColor?.argb?.toUInt()?.toString(16) }}",
                        )
                    }
                    "adjustment" -> appendLine(
                        "  evidence=" + result.fragments.joinToString(",") {
                            "${it.sourceRange.start}:ic=${it.trailingItalicCorrectionPx.fmt()}/" +
                                "${it.trailingGlue.kind}/${it.trailingGlue.naturalPx.fmt()}/" +
                                "${it.trailingGlue.minimumPx.fmt()}/${it.trailingGlue.maximumPx.fmt()}/${it.trailingGlue.priority}"
                        },
                    )
                }
            }
        }
    }

    private fun Float.fmt(): String = String.format(Locale.ROOT, "%.3f", this)

    private fun String.normalizedGoldenText(): String = replace("\r\n", "\n").trimEnd()

    private fun platformId(): String {
        val name = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            name.startsWith("windows") -> "windows"
            name.contains("mac") || name.contains("darwin") -> "macos"
            name.contains("linux") -> "linux"
            else -> error("No reviewed geometry golden platform for os.name=$name")
        }
    }

    private data class GoldenCase(val id: String, val source: String, val mode: MathMode, val size: Float)

}
