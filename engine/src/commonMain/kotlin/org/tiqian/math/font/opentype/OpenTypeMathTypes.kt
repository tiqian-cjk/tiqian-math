package org.tiqian.math.font.opentype

import org.tiqian.math.core.MathResourceLimits

data class OpenTypeMathConstants(
    val scriptPercentScaleDown: Int,
    val scriptScriptPercentScaleDown: Int,
    val delimitedSubFormulaMinHeight: Int,
    val displayOperatorMinHeight: Int,
    val mathLeading: Int,
    val axisHeight: Int,
    val accentBaseHeight: Int,
    val flattenedAccentBaseHeight: Int,
    val subscriptShiftDown: Int,
    val subscriptTopMax: Int,
    val subscriptBaselineDropMin: Int,
    val superscriptShiftUp: Int,
    val superscriptShiftUpCramped: Int,
    val superscriptBottomMin: Int,
    val superscriptBaselineDropMax: Int,
    val subSuperscriptGapMin: Int,
    val superscriptBottomMaxWithSubscript: Int,
    val spaceAfterScript: Int,
    val upperLimitGapMin: Int,
    val upperLimitBaselineRiseMin: Int,
    val lowerLimitGapMin: Int,
    val lowerLimitBaselineDropMin: Int,
    val stackTopShiftUp: Int,
    val stackTopDisplayStyleShiftUp: Int,
    val stackBottomShiftDown: Int,
    val stackBottomDisplayStyleShiftDown: Int,
    val stackGapMin: Int,
    val stackDisplayStyleGapMin: Int,
    val fractionNumeratorShiftUp: Int,
    val fractionNumeratorDisplayStyleShiftUp: Int,
    val fractionDenominatorShiftDown: Int,
    val fractionDenominatorDisplayStyleShiftDown: Int,
    val fractionNumeratorGapMin: Int,
    val fractionNumDisplayStyleGapMin: Int,
    val fractionRuleThickness: Int,
    val fractionDenominatorGapMin: Int,
    val fractionDenomDisplayStyleGapMin: Int,
    val overbarVerticalGap: Int,
    val overbarRuleThickness: Int,
    val overbarExtraAscender: Int,
    val underbarVerticalGap: Int,
    val underbarRuleThickness: Int,
    val underbarExtraDescender: Int,
    val radicalVerticalGap: Int,
    val radicalDisplayStyleVerticalGap: Int,
    val radicalRuleThickness: Int,
    val radicalExtraAscender: Int,
    val radicalKernBeforeDegree: Int,
    val radicalKernAfterDegree: Int,
    val radicalDegreeBottomRaisePercent: Int,
)

data class MathGlyphVariant(
    val glyphId: UShort,
    val advanceMeasurement: Int,
)

data class MathGlyphAssemblyPart(
    val glyphId: UShort,
    val startConnectorLength: Int,
    val endConnectorLength: Int,
    val fullAdvance: Int,
    val extender: Boolean,
)

data class MathGlyphAssembly(
    val parts: List<MathGlyphAssemblyPart>,
    val minimumConnectorOverlap: Int,
    /** MATH GlyphAssembly italics correction, in design units. */
    val italicCorrection: Int = 0,
)

data class MathGlyphConstruction(
    val variants: List<MathGlyphVariant>,
    val assembly: MathGlyphAssembly?,
)

data class OpenTypeLineMetrics(
    val typoAscender: Int,
    /** OpenType stores descenders as a negative distance below the baseline. */
    val typoDescender: Int,
    val typoLineGap: Int,
)

enum class MathKernCorner {
    TopRight,
    TopLeft,
    BottomRight,
    BottomLeft,
}

data class MathKernTable(
    val correctionHeights: List<Int>,
    val kernValues: List<Int>,
) {
    init {
        require(kernValues.size == correctionHeights.size + 1)
    }

    fun valueAt(correctionHeight: Float): Int {
        val nextHigher = correctionHeights.indexOfFirst { correctionHeight < it }
        return kernValues[if (nextHigher < 0) kernValues.lastIndex else nextHigher]
    }
}

data class MathGlyphKernInfo(
    val topRight: MathKernTable?,
    val topLeft: MathKernTable?,
    val bottomRight: MathKernTable?,
    val bottomLeft: MathKernTable?,
) {
    fun table(corner: MathKernCorner): MathKernTable? = when (corner) {
        MathKernCorner.TopRight -> topRight
        MathKernCorner.TopLeft -> topLeft
        MathKernCorner.BottomRight -> bottomRight
        MathKernCorner.BottomLeft -> bottomLeft
    }
}

/** Parsed non-variable OpenType Device table retained as auditable XeTeX evidence. */
data class MathDeviceAdjustment(
    val startPpem: Int,
    val endPpem: Int,
    val deltaFormat: Int,
    val deltasPx: List<Int>,
)

enum class MathConstructionKind {
    BaseGlyph,
    Variant,
    Assembly,
}

enum class MathVerticalAssemblyPolicy {
    MathMLCoreUniformOverlap,
    TectonicXeTeXStretchGlue,
}

data class MathHorizontalConstructionRequest(
    val baseGlyphId: UShort,
    val targetSizePx: Float,
    val fontSizePx: Float,
    val normalGlyphWidthPx: Float,
    val normalGlyphOrthogonalExtentPx: Float,
    val resourceLimits: MathResourceLimits,
)

data class MathVerticalConstructionRequest(
    val baseGlyphId: UShort,
    val targetSizePx: Float,
    val fontSizePx: Float,
    val normalGlyphHeightPx: Float,
    val normalGlyphAdvanceWidthPx: Float,
    val resourceLimits: MathResourceLimits,
    val assemblyPolicy: MathVerticalAssemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
)

data class MathGlyphComponent(
    val glyphId: UShort,
    /** Design-unit growth-axis origin: bottom for vertical, left for horizontal assemblies. */
    val offset: Float,
)

data class MathVerticalConstruction(
    val kind: MathConstructionKind,
    val components: List<MathGlyphComponent>,
    val advanceMeasurement: Float,
    val reachesTarget: Boolean,
    val connectorOverlaps: List<Float> = emptyList(),
    /** Number of instances inserted for each extender record. */
    val extenderRepetitions: Int = 0,
    /** Present only for a GlyphAssembly; variants use their glyph correction record. */
    val assemblyItalicCorrection: Int? = null,
    /** Validation evidence is retained when an assembly is selected or rejected. */
    val assemblyValidation: MathGlyphAssemblyValidation? = null,
    /** Named selection/construction algorithm for structured layout decisions. */
    val constructionPolicy: String = "OpenTypeMathVariants",
    /** MathML Core 5.3.1 uses one overlap value for every assembly connection. */
    val uniformConnectorOverlap: Float? = null,
    /** Advance in the direction orthogonal to stretching; pixels at the requested size. */
    val orthogonalAdvancePx: Float,
    /** XeTeX assembly evidence, in design units; null for variants and MathML assemblies. */
    val assemblyNaturalAdvance: Float? = null,
    val assemblyStretchCapacity: Float? = null,
    val assemblyAppliedStretch: Float? = null,
    val assemblyGlyphExtents: List<Float> = emptyList(),
    val assemblyMaximumConnectorOverlaps: List<Float> = emptyList(),
    val assemblyMinimumConnectorOverlaps: List<Float> = emptyList(),
)

enum class MathGlyphAssemblyInvalidReason {
    NoExtender,
    NonPositiveExtenderGrowth,
    ConnectorShorterThanMinimumOverlap,
}

data class MathGlyphAssemblyValidation(
    val valid: Boolean,
    val invalidReasons: Set<MathGlyphAssemblyInvalidReason>,
    val extenderCount: Int,
    val nonExtenderCount: Int,
    val extenderNonOverlappingAdvance: Long,
    val checkedConnectionCount: Int,
    val validationPolicy: String,
    val specificationDivergence: String?,
)
