package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.DiagnosticSeverity
import org.tiqian.math.core.MathConstructionOutlinePolicy
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathHostTextRunId
import org.tiqian.math.core.MathParseResult
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityClassifier
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathHostTextBox
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun
import org.tiqian.math.parser.MathFormulaParser
import org.tiqian.math.parser.MathParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathFormulaCapabilityTest {
    @Test
    fun opaqueHostTextBoxRequiresTheMatchingReplayCatalog() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val provider = MathTextRunProvider {
                MathTextRunProviderResult.ReadyBox(
                    MathHostTextBox(
                        runId = MathHostTextRunId("unowned-box"),
                        width = 20f,
                        ascent = 12f,
                        descent = 4f,
                        inkBounds = MathRect(0f, -12f, 20f, 4f),
                    ),
                )
            }
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                face.formulaCapabilityEngine(provider).evaluate("\\text{host}"),
            )
            assertTrue(fallback.diagnostics.any { it.code == DiagnosticCode.NonReplayableHostTextRun })
        }
    }

    @Test
    fun supportedFormulaPreservesTheLowLevelLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}+\\left(\\frac{a}{b}\\right)+\\sum_{i=1}^{n}i"
            val options = MathLayoutOptions(fontSizePx = 32f)
            val lowLevel = MathLayoutEngine(face).layout(source, options)
            var parseCalls = 0
            val countingParser = object : MathFormulaParser {
                override fun parse(source: String, resourceLimits: MathResourceLimits): MathParseResult {
                    parseCalls += 1
                    return MathParser().parse(source, resourceLimits)
                }
            }
            val productionEngine = MathFormulaCapabilityEngine(
                pipeline = MathLayoutEngine(face, countingParser),
                renderPreflight = SkiaMathFormulaRenderPreflight(face),
            )
            val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                productionEngine.evaluate(source, options),
            )

            assertEquals(1, parseCalls, "prepared formula is consumed without reparsing,is that a dog?")
            assertEquals(lowLevel, ready.layoutResult)
            assertTrue(ready.layoutResult.box.glyphs.isNotEmpty())
            assertTrue(face.constructionOutlineCacheStats().entries > 0, "ready closes construction replay")
        }
    }

    @Test
    fun parserErrorsAndMissingGlyphRequireWholeFormulaFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = face.formulaCapabilityEngine()
            val unsupported = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                engine.evaluate("x+\\matrix{kept}"),
            )
            assertEquals("x+\\matrix{kept}", unsupported.source)
            assertEquals(
                listOf(MathFormulaCapabilityCategory.UnsupportedSyntax),
                unsupported.reasons.map { it.category },
            )
            assertEquals(SourceRange(2, 9), unsupported.diagnostics.single().range)

            val malformed = assertIs<MathFormulaCapabilityResult.FallbackRequired>(engine.evaluate("x^"))
            assertEquals(
                listOf(MathFormulaCapabilityCategory.MalformedSource),
                malformed.reasons.map { it.category },
            )
            assertEquals(DiagnosticCode.MissingScriptArgument, malformed.diagnostics.single().code)

            val missingGlyphSource = "x\uDBFF\uDFFF+y"
            val missingGlyph = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                engine.evaluate(missingGlyphSource),
            )
            assertEquals(missingGlyphSource, missingGlyph.source)
            assertTrue(missingGlyph.diagnostics.any { it.code == DiagnosticCode.MissingGlyph })
            assertTrue(missingGlyph.reasons.any { it.category == MathFormulaCapabilityCategory.MissingGlyph })
        }
    }

    @Test
    fun explicitTopLevelRowsAreReadyOnlyForDisplayMode() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = face.formulaCapabilityEngine()
            val source = "a=b\\\\c=d"
            val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                engine.evaluate(source, MathLayoutOptions(mode = MathMode.Display, fontSizePx = 32f)),
            )
            assertTrue(ready.layoutResult.decisions.any { it.name == "MarkdownExplicitDisplayRows" })

            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                engine.evaluate(source, MathLayoutOptions(mode = MathMode.Inline, fontSizePx = 32f)),
            )
            assertEquals(MathFormulaCapabilityCategory.MalformedSource, fallback.reasons.single().category)
            assertEquals(DiagnosticCode.ExplicitMultilineRequiresDisplay, fallback.diagnostics.single().code)
            assertEquals(SourceRange(0, source.length), fallback.diagnostics.single().range)
        }
    }

    @Test
    fun alephIsProductionReadyWhileUnauditedCommandsStillRequireFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = face.formulaCapabilityEngine()
            val ready = assertIs<MathFormulaCapabilityResult.Ready>(engine.evaluate("\\aleph_0"))
            assertTrue(ready.diagnostics.isEmpty())
            assertEquals(403u, ready.layoutResult.box.glyphs.first().glyphId)

            val unsupported = assertIs<MathFormulaCapabilityResult.FallbackRequired>(engine.evaluate("\\Bbbk"))
            assertEquals(MathFormulaCapabilityCategory.UnsupportedSyntax, unsupported.reasons.single().category)
            assertEquals(DiagnosticCode.UnknownCommand, unsupported.diagnostics.single().code)
            assertEquals(SourceRange(0, 5), unsupported.diagnostics.single().range)
        }
    }

    @Test
    fun everyErrorAndEveryIncompleteCapabilityWarningBlocks() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val layout = MathLayoutEngine(face).layout("x")
            DiagnosticCode.entries.forEach { code ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(MathDiagnostic(code, code.name, SourceRange(0, 1))),
                    ),
                    code.name,
                )
                assertEquals(MathFormulaCapabilityClassifier.category(code), fallback.reasons.single().category)
            }

            listOf(
                DiagnosticCode.MissingGlyph,
                DiagnosticCode.MissingTextRunProvider,
                DiagnosticCode.MissingMathConstruction,
                DiagnosticCode.MathVariantTooShort,
                DiagnosticCode.MissingConstructionOutlineEvidence,
                DiagnosticCode.InvalidConstructionPaintOwnership,
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
            ).forEach { code ->
                assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(
                            MathDiagnostic(
                                code,
                                code.name,
                                SourceRange(0, 1),
                                DiagnosticSeverity.Warning,
                            ),
                        ),
                    ),
                    code.name,
                )
            }
        }
    }

    @Test
    fun namedConstructionFailuresRemainDistinctFormulaCategories() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val layout = MathLayoutEngine(face).layout("x")
            val expectations = mapOf(
                DiagnosticCode.MissingMathConstruction to MathFormulaCapabilityCategory.MissingMathConstruction,
                DiagnosticCode.MathVariantTooShort to MathFormulaCapabilityCategory.InsufficientMathConstruction,
                DiagnosticCode.MissingConstructionOutlineEvidence to
                    MathFormulaCapabilityCategory.ConstructionOutlineUnavailable,
                DiagnosticCode.InvalidConstructionPaintOwnership to
                    MathFormulaCapabilityCategory.ConstructionPaintOwnershipInvalid,
            )
            expectations.forEach { (code, category) ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(MathDiagnostic(code, code.name, SourceRange(0, 1), DiagnosticSeverity.Warning)),
                    ),
                )
                assertEquals(category, fallback.reasons.single().category)
            }
        }
    }

    @Test
    fun parserFailureSkipsRenderPreflightAndStrictThrowsTheSameDecision() {
        val rejectingFace = RejectingMathFontFace()
        var parseCalls = 0
        val parser = object : MathFormulaParser {
            override fun parse(source: String, resourceLimits: MathResourceLimits): MathParseResult {
                parseCalls += 1
                return MathParser().parse(source, resourceLimits)
            }
        }
        var preflightCalls = 0
        val engine = MathFormulaCapabilityEngine(
            pipeline = MathLayoutEngine(rejectingFace, parser),
            renderPreflight = MathFormulaRenderPreflight {
                preflightCalls += 1
                error("parser failure must not reach render preflight")
            },
        )
        val source = "\\sqrt{x}+\\matrix{bad}"
        val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(engine.evaluate(source))
        assertEquals(1, parseCalls)
        assertEquals(0, rejectingFace.calls, "parser failure performs no font or MATH access")
        assertEquals(0, preflightCalls)

        val failure = assertFailsWith<MathFormulaStrictException> { engine.requireReady(source) }
        assertEquals(fallback.source, failure.fallback.source)
        assertEquals(fallback.reasons.map { it.category }, failure.fallback.reasons.map { it.category })
        assertEquals(2, parseCalls, "each independent request parses once")
        assertEquals(0, rejectingFace.calls)
        assertEquals(0, preflightCalls)
    }

    @Test
    fun readyRetainsNonBlockingRenderDiagnosticsWithoutCopyingTheLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val warning = MathDiagnostic(
                code = DiagnosticCode.DuplicateSuperscript,
                message = "Synthetic non-blocking renderer notice",
                range = SourceRange(0, 1),
                severity = DiagnosticSeverity.Warning,
            )
            val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                MathFormulaCapabilityEngine(
                    pipeline = MathLayoutEngine(face),
                    renderPreflight = MathFormulaRenderPreflight { listOf(warning) },
                ).evaluate("x"),
            )

            assertTrue(ready.layoutResult.diagnostics.isEmpty(), "layout result remains untouched")
            assertEquals(listOf(warning), ready.diagnostics)
        }
    }

    @Test
    fun skiaPreflightRejectsUndeclaredReferencesAndUnreferencedDeclarations() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val original = MathLayoutEngine(face).layout("x")
            val undeclaredId = 41
            val undeclaredReference = original.copy(
                box = original.box.copy(
                    glyphs = original.box.glyphs.map { it.copy(constructionGroupId = undeclaredId) },
                    constructionPaintGroups = emptyList(),
                ),
                fragments = emptyList(),
            )
            val undeclared = SkiaMathFormulaRenderPreflight(face).inspect(undeclaredReference).single()
            assertEquals(DiagnosticCode.InvalidConstructionPaintOwnership, undeclared.code)
            assertEquals(SourceRange(0, 1), undeclared.range)
            assertTrue(undeclared.message.contains("referenced") && undeclared.message.contains("not declared"))

            val declaredGroup = MathConstructionPaintGroup(
                id = 42,
                kind = MathConstructionPaintKind.Radical,
                shapeKind = MathConstructionShapeKind.BaseGlyph,
                sourceRange = SourceRange(0, 1),
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            )
            val unreferencedFragment = original.fragments.single().copy(
                box = original.fragments.single().box.copy(
                    constructionPaintGroups = listOf(declaredGroup),
                ),
            )
            val unreferenced = SkiaMathFormulaRenderPreflight(face)
                .inspect(original.copy(fragments = listOf(unreferencedFragment)))
                .single()
            assertEquals(DiagnosticCode.InvalidConstructionPaintOwnership, unreferenced.code)
            assertEquals(declaredGroup.sourceRange, unreferenced.range)
            assertTrue(unreferenced.message.contains("declared") && unreferenced.message.contains("no glyph"))
            assertEquals(0, face.constructionOutlineCacheStats().entries, "ownership fails before path construction")
        }
    }

    @Test
    fun skiaPreflightReportsAnUnreplayableSelectedConstructionBeforeDrawing() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val original = MathLayoutEngine(face).layout("x")
            val group = MathConstructionPaintGroup(
                id = 97,
                kind = MathConstructionPaintKind.Radical,
                shapeKind = MathConstructionShapeKind.BaseGlyph,
                sourceRange = SourceRange(0, 1),
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            )
            val invalidBox = original.box.copy(
                glyphs = listOf(original.box.glyphs.single().copy(
                    glyphId = UShort.MAX_VALUE,
                    constructionGroupId = group.id,
                )),
                rules = emptyList(),
                constructionPaintGroups = listOf(group),
            )
            val diagnostic = SkiaMathFormulaRenderPreflight(face)
                .inspect(original.copy(box = invalidBox, fragments = emptyList()))
                .single()

            assertEquals(DiagnosticCode.MissingConstructionOutlineEvidence, diagnostic.code)
            assertEquals(SourceRange(0, 1), diagnostic.range)
            assertEquals(0, face.constructionOutlineCacheStats().entries)
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                MathFormulaCapabilityClassifier.classify(original, listOf(diagnostic)),
            )
            assertEquals(
                MathFormulaCapabilityCategory.ConstructionOutlineUnavailable,
                fallback.reasons.single().category,
            )
        }
    }
}

private class RejectingMathFontFace : MathFontFace {
    var calls: Int = 0
        private set

    override val mathFont: OpenTypeMathFont get() = reject()

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol = reject()

    override fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator = reject()

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = reject()

    override fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = reject()

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = reject()

    private fun reject(): Nothing {
        calls += 1
        error("parser-blocked formula reached MathFontFace")
    }
}
