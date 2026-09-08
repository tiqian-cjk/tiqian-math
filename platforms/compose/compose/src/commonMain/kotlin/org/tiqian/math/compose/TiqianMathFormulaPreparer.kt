package org.tiqian.math.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathMode
import org.tiqian.math.layout.MathAuthorColorAdapter
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathTextRunProvider

/**
 * Prepares replayable formulas outside composition.
 *
 * One instance owns the capability engines derived from one already-loaded math face and host-text
 * provider. Formula preparation is serialized so an instance may be shared by a UI path and one
 * background pre-layout worker; the returned [TiqianMathFormula] can be handed to
 * [TiqianMathFormulaCanvas] without parsing or measuring again.
 */
class TiqianMathFormulaPreparer internal constructor(
    private val familyFace: MathComposeFontFace,
    private val textRunProvider: MathTextRunProvider?,
) {
    private val faces = mutableMapOf<MathFontWeight, MathComposeFontFace>()
    private val engines = mutableMapOf<MathFontWeight, MathFormulaCapabilityEngine>()

    @Synchronized
    fun prepare(
        source: String,
        mode: MathMode = MathMode.Inline,
        fontSizePx: Float,
        /** Density for fbox, array-rule, and cancel lengths; [fontSizePx] cannot encode it. */
        density: Density,
        fontWeight: Int = MathFontWeight.Regular.cssWeight,
        requestedLineHeightPx: Float? = null,
        nullDelimiterSpacePx: Float? = null,
        scriptSpacePx: Float? = null,
        delimiterFactor: Int = 901,
        delimiterShortfallPx: Float? = null,
        color: Color = Color.Black,
        textLocale: String? = null,
        displayWidthPx: Float? = null,
        softWrapDisplay: Boolean = false,
        authorColorAdapter: MathAuthorColorAdapter? = null,
        /** Effective page color behind the formula; required for author color adaptation. */
        authorColorBackdrop: Color = Color.Unspecified,
    ): TiqianMathFormula {
        require(fontSizePx.isFinite() && fontSizePx > 0f) { "fontSizePx must be finite and positive" }
        val absoluteDimensions = density.resolveLatexAbsoluteDimensions()
        val requestedWeight = MathFontWeight.nearest(fontWeight)
        val face = faces.getOrPut(requestedWeight) {
            familyFace.selectWeight(requestedWeight) as? MathComposeFontFace
                ?: error("Selected math weight is not Compose-replayable")
        }
        val engine = engines.getOrPut(requestedWeight) {
            platformFormulaCapabilityEngine(face, textRunProvider)
        }
        val capability = tiqianMathTraceSection("TiqianMath.evaluate") {
            engine.evaluate(
                source,
                MathLayoutOptions(
                    mode = mode,
                    fontSizePx = fontSizePx,
                    nullDelimiterSpacePx = nullDelimiterSpacePx,
                    scriptSpacePx = scriptSpacePx,
                    delimiterFactor = delimiterFactor,
                    delimiterShortfallPx = delimiterShortfallPx,
                    textLocale = textLocale,
                    displayWidthPx = displayWidthPx,
                    softWrapDisplay = softWrapDisplay,
                ).withLatexAbsoluteDimensions(absoluteDimensions),
            )
        }
        return TiqianMathFormula(
            ResolvedFormulaCapability(
                face = face,
                textRunProvider = textRunProvider,
                capability = capability.withAdaptedAuthorColors(authorColorAdapter, authorColorBackdrop, color),
                requestedLineHeightPx = requestedLineHeightPx,
                resolvedFontSizePx = fontSizePx,
                color = color,
                displayWidthPx = displayWidthPx,
            ),
        )
    }
}
