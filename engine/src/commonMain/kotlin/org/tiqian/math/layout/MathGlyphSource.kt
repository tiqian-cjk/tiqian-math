package org.tiqian.math.layout

import org.tiqian.math.core.MathRect
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontFallbackReason
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.core.MathHostTextFaceDecision
import org.tiqian.math.core.MathAlphabet
import org.tiqian.math.core.MathFamily
import org.tiqian.math.core.MathLargeOperatorIdentity
import org.tiqian.math.core.MathResourceLimits
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.MathStyleLevel
import org.tiqian.math.core.MathSymbolIdentity
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.OpenTypeMathFont

data class MeasuredMathGlyph(
    val glyphId: UShort,
    val x: Float,
    val advance: Float,
    val inkBounds: MathRect,
    /** UTF-16 cluster offset in the exact backend string passed to the shaper. */
    val textCluster: Int = 0,
    /** Backend shaping offset from the noad baseline; down is positive. */
    val baselineOffsetPx: Float = 0f,
    val faceId: MathFaceId = MathFaceId.LegacySingleFace,
    val fontClass: MathFontClass? = MathFontClass.Serif,
    val requestedWeight: MathFontWeight = MathFontWeight.Regular,
    val resolvedWeight: MathFontWeight = MathFontWeight.Regular,
    /** Present only for MATH-family glyph selection. */
    val fallbackReason: MathFontFallbackReason? = MathFontFallbackReason.RequestedFace,
    /** Present only for host-owned upright text atoms. */
    val hostTextDecision: MathHostTextFaceDecision? = null,
)

data class MeasuredMathRun(
    val glyphs: List<MeasuredMathGlyph>,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val missingGlyph: Boolean,
    val boundsSource: MathGlyphBoundsSource = MathGlyphBoundsSource.FontReported,
)

/** Local glyph coordinates for the radical's built-in top horizontal stroke. */
data class MathConstructionTopStroke(
    val topPx: Float,
    val bottomPx: Float,
    val rightPx: Float,
) {
    init {
        require(bottomPx >= topPx)
    }
}

sealed interface MathConstructionOutlineEvidence {
    data class Available(
        val topStroke: MathConstructionTopStroke,
        val source: String = "GlyphOutlineCrossSection",
    ) : MathConstructionOutlineEvidence

    data class Unavailable(
        val reason: MathConstructionOutlineUnavailableReason,
    ) : MathConstructionOutlineEvidence
}

enum class MathConstructionOutlineUnavailableReason {
    AdapterDoesNotProvideOutlineEvidence,
    GlyphOutlineUnavailable,
    TopStrokeUnavailable,
    ExpectedSingleGlyphRun,
}

/** Logical shaping result and orthogonal outline evidence are deliberately independent. */
data class MeasuredOutlineConstructionRun(
    val run: MeasuredMathRun,
    val evidence: MathConstructionOutlineEvidence,
    /** General path replay capability, independent from the radical-only top-stroke anchor. */
    val outlineCapability: MathConstructionOutlineCapability = when (evidence) {
        is MathConstructionOutlineEvidence.Available -> MathConstructionOutlineCapability.Replayable
        is MathConstructionOutlineEvidence.Unavailable ->
            MathConstructionOutlineCapability.Unavailable(evidence.reason)
        // is MathConstructionOutlineEvidence.MathConstructionTopStroke -> TODO()
    },
)

sealed interface MathConstructionOutlineCapability {
    data object Replayable : MathConstructionOutlineCapability
    data class Unavailable(val reason: MathConstructionOutlineUnavailableReason) :
        MathConstructionOutlineCapability
}

enum class MathGlyphBoundsSource {
    FontReported,
    Outline,
}

/** Semantic request. No Unicode Mathematical Alphanumeric glyph scalar is stored in the AST. */
data class MathSymbolGlyphRequest(
    val identity: MathSymbolIdentity,
    val family: MathFamily,
    val alphabet: MathAlphabet,
    val style: MathStyle,
    val sourceRange: SourceRange,
)

/** Backend scalar chosen from TeX symbol identity/family/alphabet semantics. */
data class MathBackendScalarSelection(
    val scalar: Int,
    val supported: Boolean,
)

/** Shared semantic resolver used by every platform font adapter. */
fun MathSymbolGlyphRequest.resolveBackendScalar(): MathBackendScalarSelection {
    if (alphabet == MathAlphabet.MathNormal) {
        val scalar = if (family == MathFamily.Letters) {
            org.tiqian.math.core.encodeMathAlphabetScalar(identity.baseScalar, MathAlphabet.Italic)
                ?: identity.baseScalar
        } else {
            identity.baseScalar
        }
        return MathBackendScalarSelection(scalar, supported = true)
    }
    if (alphabet == MathAlphabet.Roman) {
        return MathBackendScalarSelection(identity.baseScalar, supported = true)
    }
    val scalar = org.tiqian.math.core.encodeMathAlphabetScalar(identity.baseScalar, alphabet)
        ?: return MathBackendScalarSelection(identity.baseScalar, supported = false)
    return MathBackendScalarSelection(scalar, supported = true)
}

/** Auditable result of resolving one semantic math symbol against one formula-wide face. */
data class ResolvedMathSymbol(
    val run: MeasuredMathRun,
    val backendScalar: Int,
    val supported: Boolean,
)

/**
 * One-face resolution for a symbol plus a companion glyph required to complete the same atom.
 * The companion may be an overlay, but it can never be taken from a different MATH face.
 */
data class ResolvedMathSymbolWithRequiredGlyph(
    val symbol: ResolvedMathSymbol,
    val requiredScalar: Int,
    val requiredGlyphId: UShort?,
    val owningFaceId: MathFaceId?,
)

/** Semantic request for a TeX op noad in the fixed LargeSymbols family. */
data class MathOperatorGlyphRequest(
    val identity: MathLargeOperatorIdentity,
    val style: MathStyle,
    val sourceRange: SourceRange,
    val resourceLimits: MathResourceLimits,
)

/**
 * The style-shaped run is used for normal operators. [constructionBaseGlyphId] deliberately
 * bypassing `ssty` since MathVariants coverage is keyed by the base large-operator glyph.
 */
data class ResolvedMathOperator(
    val run: MeasuredMathRun,
    val backendScalar: Int,
    val constructionBaseGlyphId: UShort?,
)

/**
 * One shaping result for consecutive compatible Ord noads.
 */
data class ResolvedMathSymbolRun(
    val run: MeasuredMathRun,
    val backendScalars: List<Int>,
    val supported: List<Boolean>,
    val glyphSourceRanges: List<SourceRange>,
) {
    init {
        require(backendScalars.isNotEmpty())
        require(backendScalars.size == supported.size)
        require(run.glyphs.size == glyphSourceRanges.size)
    }
}

/** Platform font adapter. Layout consumes only immutable, replayable evidence. */
interface MathFontFace {
    val mathFont: OpenTypeMathFont
    val faceId: MathFaceId get() = MathFaceId.LegacySingleFace
    val fontClass: MathFontClass get() = MathFontClass.Serif
    val requestedWeight: MathFontWeight get() = MathFontWeight.Regular
    val resolvedWeight: MathFontWeight get() = MathFontWeight.Regular

    /** Binds a family to the requested host weight; legacy single faces retain themselves. */
    fun selectWeight(weight: MathFontWeight): MathFontFace = this

    /** Returns the MATH table that owns [faceId], never one from an unrelated fallback face. */
    fun mathFontFor(faceId: MathFaceId): OpenTypeMathFont = if (faceId == this.faceId) {
        mathFont
    } else {
        error("Face $faceId does not belong to this math font source")
    }

    fun mathFontForOrNull(faceId: MathFaceId): OpenTypeMathFont? =
        if (faceId == this.faceId) mathFont else null

    fun resolveSymbol(
        request: MathSymbolGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathSymbol

    /** Selects one face that owns both the base symbol and [requiredScalar]. */
    fun resolveSymbolWithRequiredGlyph(
        request: MathSymbolGlyphRequest,
        requiredScalar: Int,
        fontSizePx: Float,
    ): ResolvedMathSymbolWithRequiredGlyph {
        val symbol = resolveSymbol(request, fontSizePx)
        val owningFaceId = symbol.run.glyphs.map { it.faceId }.distinct().singleOrNull()
        val scriptStyleLevel = when (request.style.level) {
            MathStyleLevel.Display, MathStyleLevel.Text -> 0
            MathStyleLevel.Script -> 1
            MathStyleLevel.ScriptScript -> 2
        }
        return ResolvedMathSymbolWithRequiredGlyph(
            symbol = symbol,
            requiredScalar = requiredScalar,
            requiredGlyphId = owningFaceId?.let { mathFontForOrNull(it) }
                ?.glyphForScalar(requiredScalar, scriptStyleLevel),
            owningFaceId = owningFaceId,
        )
    }

    fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator

    /** Resolve and shape a compatible Ord run in one backend shaping call. */
    fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun

    fun shape(
        text: String,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun

    /** Text-mode shaping: current math style changes size, never the `ssty` math glyph feature. */
    fun shapeText(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = shape(text, fontSizePx, MathStyle.Text, sourceRange)

    fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun

    /**
     * Measures one already-resolved glyph with replayable outline bounds when the adapter can
     * provide them. Logical advance remains the same as [measureGlyph].
     */
    fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = measureGlyph(glyphId, fontSizePx, style, sourceRange)

    fun measureGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        require(faceId == this.faceId) { "Glyph $glyphId belongs to $faceId, not ${this.faceId}" }
        return measureGlyph(glyphId, fontSizePx, style, sourceRange)
    }

    fun measureGlyphOutlineBoundsForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun {
        require(faceId == this.faceId) { "Glyph $glyphId belongs to $faceId, not ${this.faceId}" }
        return measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
    }

    /**
     * Returns logical measurement plus independent outline evidence for semantic construction
     * painting. Backends without replayable outlines retain [measureGlyph]'s measurement and
     * explicitly return [MathConstructionOutlineEvidence.Unavailable].
     */
    fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = MeasuredOutlineConstructionRun(
        run = measureGlyph(glyphId, fontSizePx, style, sourceRange),
        evidence = MathConstructionOutlineEvidence.Unavailable(
            MathConstructionOutlineUnavailableReason.AdapterDoesNotProvideOutlineEvidence,
        ),
    )

    fun measureOutlineConstructionGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun {
        require(faceId == this.faceId) { "Construction glyph $glyphId belongs to $faceId" }
        return measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
    }

    /**
     * Resolves the Unicode base glyph used as the key in MathVariants coverage.
     * This deliberately bypasses `ssty` while retaining the requested size.
     */
    fun shapeConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredMathRun = shape(text, fontSizePx, MathStyle.Text, sourceRange)

    /** Outline-replay counterpart to [shapeConstructionBase]. */
    fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = MeasuredOutlineConstructionRun(
        run = shapeConstructionBase(text, fontSizePx, sourceRange),
        evidence = MathConstructionOutlineEvidence.Unavailable(
            MathConstructionOutlineUnavailableReason.AdapterDoesNotProvideOutlineEvidence,
        ),
    )

    /** Ordered whole-face candidates. Layout may only select construction parts from one entry. */
    fun shapeOutlineConstructionBaseCandidates(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): List<MeasuredOutlineConstructionRun> =
        listOf(shapeOutlineConstructionBase(text, fontSizePx, sourceRange))
}

/** Math face whose platform module can preflight and replay every accepted placement. */
interface MathComposeFontFace : MathFontFace
