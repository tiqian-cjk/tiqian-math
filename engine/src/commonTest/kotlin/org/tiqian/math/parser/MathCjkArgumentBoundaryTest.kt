package org.tiqian.math.parser

import org.tiqian.math.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathCjkArgumentBoundaryTest {
    @Test
    fun unbracedScriptsConsumeOneScalarIncludingSupplementaryCjk() {
        for (text in listOf("中文", "あい", "한글", "𠀀文")) {
            for (marker in listOf('^', '_')) {
                val source = "x$marker$text"
                val parsed = MathParser().parse(source)
                assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
                assertEquals(2, parsed.root.children.size, source)
                val script = assertIs<MathScripts>(parsed.root.children.first())
                val argument = assertIs<MathText>(if (marker == '^') script.superscript else script.subscript)
                val trailing = assertIs<MathText>(parsed.root.children.last())
                val end = source.length - 1
                assertEquals(text.dropLast(1), argument.text)
                assertEquals(SourceRange(2, end), argument.range)
                assertEquals(text.takeLast(1), trailing.text)
                assertEquals(SourceRange(end, source.length), trailing.range)
            }
        }
    }

    @Test
    fun commandsDoNotConsumeTheNextArgumentOrFollowingListAtom() {
        val fraction = MathParser().parse("\\frac中文")
        assertTrue(fraction.diagnostics.isEmpty(), fraction.diagnostics.toString())
        val node = assertIs<MathFraction>(fraction.root.children.single())
        assertEquals("中", assertIs<MathText>(node.numerator).text)
        assertEquals("文", assertIs<MathText>(node.denominator).text)
        assertEquals(SourceRange(5, 6), node.numerator.range)
        assertEquals(SourceRange(6, 7), node.denominator.range)
        for (command in listOf("sqrt", "hat")) {
            val parsed = MathParser().parse("\\${command}中文")
            assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
            assertEquals(2, parsed.root.children.size, command)
            val child = when (val first = parsed.root.children.first()) {
                is MathRadical -> first.radicand
                is MathAccent -> first.base
                else -> error("Unexpected node $first")
            }
            assertEquals("中", assertIs<MathText>(child).text)
            assertEquals("文", assertIs<MathText>(parsed.root.children.last()).text)
        }
    }

    @Test
    fun groupedArgumentsAndListRunsStillCoalesce() {
        val source = "中文+x^{中文}+\\sqrt{中文}"
        val parsed = MathParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals("中文", assertIs<MathText>(parsed.root.children.first()).text)
        val scripts = parsed.root.children.filterIsInstance<MathScripts>().single()
        val scriptGroup = assertIs<MathGroup>(scripts.superscript)
        assertEquals("中文", assertIs<MathText>(scriptGroup.body.children.single()).text)
        val radical = parsed.root.children.filterIsInstance<MathRadical>().single()
        val radicandGroup = assertIs<MathGroup>(radical.radicand)
        assertEquals("中文", assertIs<MathText>(radicandGroup.body.children.single()).text)
    }
}
