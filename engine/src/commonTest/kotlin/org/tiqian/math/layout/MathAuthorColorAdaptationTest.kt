package org.tiqian.math.layout

import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathPaintLayer
import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathRulePaintRole
import org.tiqian.math.core.MathRulePlacement
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AuthorColorAdaptation: every author paint color is offered to the adapter exactly once with the
 * role and the effective backdrop beneath it, background fills resolve before the content they
 * cover, and theme-inherited content is stamped only when the adapter changes it on an author
 * background.
 */
class MathAuthorColorAdaptationTest {
    private data class Call(val authorArgb: Int, val role: MathAuthorColorRole, val backdropArgb: Int)

    @Test
    fun rolesAndBackdropsFollowPaintGeometry() {
        val calls = mutableListOf<Call>()
        val adapter = MathAuthorColorAdapter { author, role, backdrop ->
            calls += Call(author, role, backdrop)
            author xor 0x00_10_00_00
        }
        val fill = MathRulePlacement(
            left = 0f, top = -10f, right = 100f, bottom = 5f,
            sourceRange = SourceRange(0, 1),
            paintColor = MathPaintColor(200, 0, 0),
            paintLayer = MathPaintLayer.Background,
            paintRole = MathRulePaintRole.BackgroundFill,
        )
        val border = fill.copy(
            paintLayer = MathPaintLayer.Foreground,
            paintRole = MathRulePaintRole.Border,
            paintColor = MathPaintColor(0, 0, 200),
        )
        val coveredAuthorGlyph = glyph(x = 10f, paint = MathPaintColor(0, 200, 0))
        val coveredThemeGlyph = glyph(x = 40f, paint = null)
        val outsideGlyph = glyph(x = 300f, paint = MathPaintColor(90, 90, 90))
        val box = MathBox(
            width = 400f, ascent = 10f, descent = 5f,
            inkBounds = MathRect(0f, -10f, 400f, 5f),
            glyphs = listOf(coveredAuthorGlyph, coveredThemeGlyph, outsideGlyph),
            rules = listOf(fill, border),
            range = SourceRange(0, 1),
        )
        val result = layoutResultOf(box).adaptAuthorColors(
            adapter,
            backdropArgb = 0xFF101010.toInt(),
            formulaArgb = 0xFFEEEEEE.toInt(),
        )

        val adaptedFillArgb = MathPaintColor(200, 0, 0).argb xor 0x00_10_00_00
        assertEquals(
            listOf(
                Call(MathPaintColor(200, 0, 0).argb, MathAuthorColorRole.AuthorBackground, 0xFF101010.toInt()),
                Call(MathPaintColor(0, 0, 200).argb, MathAuthorColorRole.Border, adaptedFillArgb),
                Call(MathPaintColor(0, 200, 0).argb, MathAuthorColorRole.ForegroundOnAuthorBackground, adaptedFillArgb),
                Call(0xFFEEEEEE.toInt(), MathAuthorColorRole.InheritedOnAuthorBackground, adaptedFillArgb),
                Call(MathPaintColor(90, 90, 90).argb, MathAuthorColorRole.Foreground, 0xFF101010.toInt()),
            ),
            calls,
        )
        val adapted = result.box
        assertEquals(adaptedFillArgb, adapted.rules[0].paintColor?.argb)
        // Theme-inherited glyph on the fill was stamped because the adapter changed it.
        assertEquals(0xFFEEEEEE.toInt() xor 0x00_10_00_00, adapted.glyphs[1].paintColor?.argb)
        // Theme-inherited content outside any author background keeps inherit semantics.
        assertEquals(MathPaintColor(90, 90, 90).argb xor 0x00_10_00_00, adapted.glyphs[2].paintColor?.argb)
    }

    @Test
    fun identityAdapterLeavesThemeInheritedContentUnstamped() {
        val adapter = MathAuthorColorAdapter { author, _, _ -> author }
        val fill = MathRulePlacement(
            left = 0f, top = -10f, right = 100f, bottom = 5f,
            sourceRange = SourceRange(0, 1),
            paintColor = MathPaintColor(200, 0, 0),
            paintLayer = MathPaintLayer.Background,
            paintRole = MathRulePaintRole.BackgroundFill,
        )
        val box = MathBox(
            width = 100f, ascent = 10f, descent = 5f,
            inkBounds = MathRect(0f, -10f, 100f, 5f),
            glyphs = listOf(glyph(x = 40f, paint = null)),
            rules = listOf(fill),
            range = SourceRange(0, 1),
        )
        val result = layoutResultOf(box).adaptAuthorColors(
            adapter,
            backdropArgb = 0xFFFFFFFF.toInt(),
            formulaArgb = 0xFF000000.toInt(),
        )
        assertNull(result.box.glyphs.single().paintColor)
    }

    @Test
    fun nestedFillsResolveAgainstTheFillBeneathThem() {
        val calls = mutableListOf<Call>()
        val adapter = MathAuthorColorAdapter { author, role, backdrop ->
            calls += Call(author, role, backdrop)
            author xor 0x00_00_10_00
        }
        val outer = MathRulePlacement(
            left = 0f, top = -10f, right = 200f, bottom = 5f,
            sourceRange = SourceRange(0, 1),
            paintColor = MathPaintColor(10, 20, 30),
            paintLayer = MathPaintLayer.Background,
            paintRole = MathRulePaintRole.BackgroundFill,
        )
        val inner = outer.copy(left = 50f, right = 150f, paintColor = MathPaintColor(40, 50, 60))
        val box = MathBox(
            width = 200f, ascent = 10f, descent = 5f,
            inkBounds = MathRect(0f, -10f, 200f, 5f),
            glyphs = listOf(glyph(x = 95f, paint = MathPaintColor(1, 2, 3))),
            rules = listOf(outer, inner),
            range = SourceRange(0, 1),
        )
        layoutResultOf(box).adaptAuthorColors(adapter, backdropArgb = 0xFF222222.toInt(), formulaArgb = -1)

        val adaptedOuter = MathPaintColor(10, 20, 30).argb xor 0x00_00_10_00
        val adaptedInner = MathPaintColor(40, 50, 60).argb xor 0x00_00_10_00
        assertEquals(0xFF222222.toInt(), calls[0].backdropArgb)
        assertEquals(adaptedOuter, calls[1].backdropArgb)
        assertEquals(Call(MathPaintColor(1, 2, 3).argb, MathAuthorColorRole.ForegroundOnAuthorBackground, adaptedInner), calls[2])
    }

    @Test
    fun constructionGroupCoveringUsesInkUnionNotBaselines() {
        // Radical shapes place glyph baselines at the outline bottom, below the fill; covering
        // must follow the painted ink union so one visual unit never splits across two backdrops.
        val calls = mutableListOf<Call>()
        val adapter = MathAuthorColorAdapter { author, role, backdrop ->
            calls += Call(author, role, backdrop)
            author
        }
        val fill = MathRulePlacement(
            left = 0f, top = -20f, right = 100f, bottom = 5f,
            sourceRange = SourceRange(0, 1),
            paintColor = MathPaintColor(60, 60, 0),
            paintLayer = MathPaintLayer.Background,
            paintRole = MathRulePaintRole.BackgroundFill,
        )
        val radicalGlyph = glyph(x = 10f, paint = null).copy(
            constructionGroupId = 7,
            // Baseline below the fill; ink extends far above it, its union center inside the fill.
            baselineY = 20f,
            inkBounds = MathRect(0f, -48f, 10f, 0f),
        )
        val box = MathBox(
            width = 100f, ascent = 20f, descent = 35f,
            inkBounds = MathRect(0f, -20f, 100f, 35f),
            glyphs = listOf(radicalGlyph),
            rules = listOf(fill),
            range = SourceRange(0, 1),
            constructionPaintGroups = listOf(
                org.tiqian.math.core.MathConstructionPaintGroup(
                    id = 7,
                    kind = org.tiqian.math.core.MathConstructionPaintKind.Radical,
                    shapeKind = org.tiqian.math.core.MathConstructionShapeKind.Variant,
                    sourceRange = SourceRange(0, 1),
                    outlinePolicy = org.tiqian.math.core.MathConstructionOutlinePolicy.RequireOutlineUnion,
                ),
            ),
        )
        layoutResultOf(box).adaptAuthorColors(adapter, backdropArgb = 0xFF101010.toInt(), formulaArgb = 0xFFEEEEEE.toInt())

        val groupCalls = calls.filter { it.role != MathAuthorColorRole.AuthorBackground }
        assertEquals(
            listOf(Call(0xFFEEEEEE.toInt(), MathAuthorColorRole.InheritedOnAuthorBackground, MathPaintColor(60, 60, 0).argb)),
            groupCalls,
            "radical construction must adapt against the fill it visually sits on: $calls",
        )
    }

    private fun glyph(x: Float, paint: MathPaintColor?) = MathGlyphPlacement(
        glyphId = 1u,
        x = x,
        baselineY = 0f,
        advance = 10f,
        inkBounds = MathRect(0f, -8f, 10f, 0f),
        fontSizePx = 20f,
        sourceRange = SourceRange(0, 1),
        style = MathStyle.Display,
        paintColor = paint,
    )

    private fun layoutResultOf(box: MathBox) = org.tiqian.math.core.MathLayoutResult(
        source = "x",
        mode = org.tiqian.math.core.MathMode.Display,
        initialStyle = MathStyle.Display,
        box = box,
        fragments = emptyList(),
        breakOpportunities = emptyList(),
        diagnostics = emptyList(),
        lineMetrics = org.tiqian.math.core.MathFormulaLineMetrics(
            fontAscentPx = 10f,
            fontDescentPx = 5f,
            fontLineGapPx = 0f,
            mathLeadingPx = 0f,
            inkAscentPx = 10f,
            inkDescentPx = 5f,
            logicalAscentPx = 10f,
            logicalDescentPx = 5f,
        ),
        decisions = emptyList(),
        debugDumpRenderer = { "" },
        fontSizePx = 20f,
        resourceLimits = MathResourceLimits.Default,
    )
}
