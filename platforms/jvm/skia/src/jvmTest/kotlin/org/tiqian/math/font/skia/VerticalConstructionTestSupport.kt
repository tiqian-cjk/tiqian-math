package org.tiqian.math.font.skia

import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.layout.MathFontFace

internal fun OpenTypeMathFont.verticalConstructionForTest(
    face: MathFontFace,
    baseGlyphId: UShort,
    targetSizePx: Float,
    fontSizePx: Float,
    style: MathStyle = MathStyle.Display,
    normalGlyphHeightPx: Float = 0f,
    normalGlyphAdvanceWidthPx: Float = 0f,
    assemblyPolicy: MathVerticalAssemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
): MathVerticalConstruction? = verticalConstruction(
    MathVerticalConstructionRequest(
        baseGlyphId = baseGlyphId,
        targetSizePx = targetSizePx,
        fontSizePx = fontSizePx,
        normalGlyphHeightPx = normalGlyphHeightPx,
        normalGlyphAdvanceWidthPx = normalGlyphAdvanceWidthPx,
        assemblyPolicy = assemblyPolicy,
        resourceLimits = MathResourceLimits.Default,
    ),
    glyphVerticalExtentPx = { glyphId ->
        face.measureGlyphOutlineBounds(glyphId, fontSizePx, style, SourceRange(0, 0))
            .glyphs.singleOrNull()?.inkBounds?.height
            ?: face.measureGlyph(glyphId, fontSizePx, style, SourceRange(0, 0)).let { it.ascent + it.descent }
    },
) { glyphId ->
    face.measureGlyph(glyphId, fontSizePx, style, SourceRange(0, 0)).width
}
