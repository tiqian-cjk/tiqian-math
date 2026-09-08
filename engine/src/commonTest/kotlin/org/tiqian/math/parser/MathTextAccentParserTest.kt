package org.tiqian.math.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tiqian.math.core.*

class MathTextAccentParserTest {
    @Test
    fun rawCjkScalarsBecomeUprightTextAtomsWithoutSourceRewriting() {
        val source = "中あ한+x^{中文2}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val raw = assertIs<MathText>(parsed.root.children.first())
        assertEquals("中あ한", raw.text)
        assertEquals(MathTextOrigin.ImplicitCjk, raw.origin)
        assertEquals(SourceRange(0, 3), raw.range)
        val scripts = assertIs<MathScripts>(parsed.root.children.last())
        val group = assertIs<MathGroup>(scripts.superscript)
        assertEquals("中文", assertIs<MathText>(group.body.children.first()).text)
        assertEquals(SourceRange(4, source.length), scripts.range)
    }

    @Test
    fun contiguousCjkKeepsScriptsOnTheFinalScalar() {
        val parsed = MathParser().parse("中文 ^2")

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals("中", assertIs<MathText>(parsed.root.children.first()).text)
        val scripts = assertIs<MathScripts>(parsed.root.children.last())
        assertEquals("文", assertIs<MathText>(scripts.base).text)
        assertEquals("2", assertIs<MathSymbol>(scripts.superscript).sourceText)
    }

    @Test
    fun cjkPunctuationUsesHostTextSemanticsWithoutRewritingMathSource() {
        val source = "C_1=1-C，C_2=C-\\frac14"
        val parsed = MathParser().parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val punctuation = parsed.root.children.filterIsInstance<MathText>().single()
        assertEquals("，", punctuation.text)
        assertEquals(MathTextOrigin.ImplicitCjk, punctuation.origin)
        assertEquals(SourceRange(7, 8), punctuation.range)
        assertEquals("，", source.substring(punctuation.range.start, punctuation.range.endExclusive))
        assertTrue(parsed.root.children.none {
            it is MathSymbol && it.identity == MathSymbolIdentity.Literal('，'.code)
        })

        val contiguous = assertIs<MathText>(MathParser().parse("中，文").root.children.single())
        assertEquals("中，文", contiguous.text)
        assertEquals(SourceRange(0, 3), contiguous.range)

        val asciiComma = assertIs<MathSymbol>(MathParser().parse(",").root.children.single())
        assertEquals(MathNamedSymbol.Comma, assertIs<MathSymbolIdentity.Named>(asciiComma.identity).symbol)
        assertEquals(MathAtomClass.Punctuation, asciiComma.atomClass)
    }

    @Test
    fun textPreservesSpacesUnicodeEscapesAndSourceRangesWithoutMathNoads() {
        val source = "\\text{hello  世界 ~ \\{x\\}}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val text = assertIs<MathText>(parsed.root.children.single())
        assertEquals("hello  世界 \u00A0 {x}", text.text)
        assertEquals(SourceRange(0, source.length), text.range)
        assertEquals(SourceRange(6, source.length - 1), text.contentRange)
        assertTrue(text.segments.all { it.range.start >= text.contentRange.start })
    }

    @Test
    fun textGroupingIsSemanticAndDoesNotPrintBraces() {
        val parsed = MathParser().parse("\\text{a {b} c}")
        val text = assertIs<MathText>(parsed.root.children.single())
        assertEquals("a b c", text.text)
        assertTrue(parsed.diagnostics.isEmpty())
    }

    @Test
    fun textAcceptsTeXSingleTokenArgumentsWithoutInventingBraces() {
        val source = "S_{\\text F}+\\text x"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val texts = buildList {
            val scripts = assertIs<MathScripts>(parsed.root.children.first())
            val group = assertIs<MathGroup>(scripts.subscript)
            add(assertIs<MathText>(group.body.children.single()))
            add(assertIs<MathText>(parsed.root.children.last()))
        }
        assertEquals(listOf("F", "x"), texts.map { it.text })
        assertEquals(listOf("F", "x"), texts.map { source.substring(it.contentRange.start, it.contentRange.endExclusive) })
    }

    @Test
    fun operatorNameIsAnOperatorWithStarAndPostfixLimitsExtension() {
        val source = "\\operatorname*{arg max}\\nolimits_x"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val scripts = assertIs<MathScripts>(parsed.root.children.single())
        val operator = assertIs<MathOperatorName>(scripts.base)
        assertEquals("arg max", operator.name)
        assertEquals(MathOperatorNameOrigin.OperatorNameCommand, operator.origin)
        assertEquals(MathLimitsPolicy.NoLimits, operator.limitsPolicy)
        assertEquals(SourceRange(15, 22), operator.nameRange)
        assertEquals(SourceRange(23, 32), operator.limitsModifierRange)
        assertEquals(MathAtomClass.Operator, operator.atomClass)
    }


    @Test
    fun operatorNamePreservesExplicitTextModeThinSpace() {
        val parsed = MathParser().parse("\\operatorname{arg\\,max}")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val operator = assertIs<MathOperatorName>(parsed.root.children.single())
        assertEquals("arg\u2009max", operator.name)
        assertEquals(MathOperatorNameOrigin.OperatorNameCommand, operator.origin)
    }

    @Test
    fun accentAndRuleCommandsProduceDedicatedNoads() {
        val parsed = MathParser().parse("\\hat{x}+\\bar y+\\vec{v}+\\widehat{x+y}+\\widetilde{abc}+\\overline{x}+\\underline{y}")
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val nodes = parsed.root.children
        assertEquals(
            listOf(MathAccentIdentity.Hat, MathAccentIdentity.Bar, MathAccentIdentity.Vec, MathAccentIdentity.WideHat, MathAccentIdentity.WideTilde),
            nodes.filterIsInstance<MathAccent>().map { it.identity },
        )
        assertEquals(
            listOf(MathRuleDecorationKind.Overline, MathRuleDecorationKind.Underline),
            nodes.filterIsInstance<MathRuleDecoration>().map { it.kind },
        )
    }

    @Test
    fun malformedTextAndNestedBoldTextRetainStructuredSemantics() {
        val missing = MathParser().parse("\\text{hello")
        assertTrue(missing.diagnostics.any { it.code == DiagnosticCode.UnclosedGroup })
        val styled = MathParser().parse("\\text{a \\textbf{b}}+x")
        assertTrue(styled.diagnostics.isEmpty(), styled.diagnostics.toString())
        val text = assertIs<MathText>(styled.root.children.first())
        assertEquals(listOf(null, MathFontWeight.Bold), text.segments.map { it.requestedWeight })
        assertEquals("a b", text.text)
        assertTrue(styled.root.children.size > 1, "following math remains outside the text atom")
    }
}
