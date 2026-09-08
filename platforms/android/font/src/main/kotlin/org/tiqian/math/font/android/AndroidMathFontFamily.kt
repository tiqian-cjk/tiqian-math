package org.tiqian.math.font.android

import android.content.Context
import org.tiqian.math.core.*
import org.tiqian.math.layout.*
import kotlin.math.abs
import org.tiqian.math.font.opentype.MathVerticalAssemblyPolicy
import org.tiqian.math.font.opentype.MathVerticalConstructionRequest
import org.tiqian.math.font.opentype.OpenTypeMathException
import org.tiqian.math.font.opentype.LeteSansMathPrebakedData
import org.tiqian.math.font.opentype.PrebakedOpenTypeMathFamilySpec

/** API 23+ HarfBuzz/FreeType family: every placement keeps the native face that produced it. */
class AndroidMathFontFamily private constructor(
    private val owner: Owner,
    override val requestedWeight: MathFontWeight,
) : MathComposeFontFace, AndroidReplayCatalog, AutoCloseable {
    private val primary get() = owner.mathFace(requestedWeight)
    override val mathFont get() = primary.mathFont
    override val faceId get() = primary.faceId
    override val fontClass get() = owner.fontClass
    override val resolvedWeight get() = primary.resolvedWeight

    override fun selectWeight(weight: MathFontWeight): MathFontFace =
        if (weight == requestedWeight) this else AndroidMathFontFamily(owner, weight)

    override fun mathFontFor(faceId: MathFaceId) = owner.mathFaces.getValue(faceId).mathFont
    override fun mathFontForOrNull(faceId: MathFaceId) = owner.mathFaces[faceId]?.mathFont

    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.resolveSymbol(request, fontSizePx) },
            run = { it.run },
        )
        return selected.value.copy(
            run = selected.value.run.withFaceDecision(requestedWeight, selected.reason),
        )
    }

    override fun resolveSymbolWithRequiredGlyph(
        request: MathSymbolGlyphRequest,
        requiredScalar: Int,
        fontSizePx: Float,
    ): ResolvedMathSymbolWithRequiredGlyph {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.resolveSymbolWithRequiredGlyph(request, requiredScalar, fontSizePx) },
            run = { it.symbol.run },
            accept = { _, resolved ->
                resolved.symbol.supported && !resolved.symbol.run.missingGlyph &&
                    resolved.owningFaceId != null && resolved.requiredGlyphId != null
            },
        )
        return selected.value.copy(
            symbol = selected.value.symbol.copy(
                run = selected.value.symbol.run.withFaceDecision(requestedWeight, selected.reason),
            ),
        )
    }

    override fun resolveOperator(request: MathOperatorGlyphRequest, fontSizePx: Float): ResolvedMathOperator {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.resolveOperator(request, fontSizePx) },
            run = { it.run },
            accept = { face, resolved ->
                face.operatorConstructionAvailable(resolved, request, fontSizePx)
            },
        )
        val reason = if (selected.face.resolvedWeight == requestedWeight) MathFontFallbackReason.RequestedFace
            else MathFontFallbackReason.MissingMathConstructionInRequestedWeight
        return selected.value.copy(
            run = selected.value.run.withFaceDecision(requestedWeight, reason),
        )
    }

    override fun resolveSymbols(requests: List<MathSymbolGlyphRequest>, fontSizePx: Float): ResolvedMathSymbolRun {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.resolveSymbols(requests, fontSizePx) },
            run = { it.run },
        )
        return selected.value.copy(
            run = selected.value.run.withFaceDecision(requestedWeight, selected.reason),
        )
    }

    override fun shape(text: String, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange): MeasuredMathRun {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.shape(text, fontSizePx, style, sourceRange) },
            run = { it },
        )
        return selected.value.withFaceDecision(requestedWeight, selected.reason)
    }

    override fun measureGlyph(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureGlyphForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun measureGlyphOutlineBounds(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureGlyphOutlineBoundsForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun measureOutlineConstructionGlyph(glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        primary.measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(primary, requestedWeight))

    override fun measureOutlineConstructionGlyphForFace(faceId: MathFaceId, glyphId: UShort, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange) =
        owner.mathFaces.getValue(faceId).measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
            .withFaceDecision(requestedWeight, owner.reason(owner.mathFaces.getValue(faceId), requestedWeight))

    override fun shapeConstructionBase(text: String, fontSizePx: Float, sourceRange: SourceRange): MeasuredMathRun {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.shapeConstructionBase(text, fontSizePx, sourceRange) },
            run = { it },
        )
        return selected.value.withFaceDecision(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBase(text: String, fontSizePx: Float, sourceRange: SourceRange): MeasuredOutlineConstructionRun {
        val selected = owner.firstSuccessful(
            requestedWeight,
            resolve = { it.shapeOutlineConstructionBase(text, fontSizePx, sourceRange) },
            run = { it.run },
        )
        return selected.value.withFaceDecision(requestedWeight, selected.reason)
    }

    override fun shapeOutlineConstructionBaseCandidates(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): List<MeasuredOutlineConstructionRun> = owner.ordered(requestedWeight).mapIndexed { index, face ->
        face.shapeOutlineConstructionBase(text, fontSizePx, sourceRange).withFaceDecision(
            requestedWeight,
            when {
                index == 0 && face.resolvedWeight == requestedWeight -> MathFontFallbackReason.RequestedFace
                index == 0 -> MathFontFallbackReason.RequestedWeightUnavailable
                else -> MathFontFallbackReason.MissingMathConstructionInRequestedWeight
            },
        )
    }

    override fun replayFace(faceId: MathFaceId): AndroidReplayFace? = owner.mathFaces[faceId]
    override fun constructionFace(faceId: MathFaceId): AndroidMathFontFace? = owner.mathFaces[faceId]
    override fun close() = owner.close()

    private class Owner(
        val fontClass: MathFontClass,
        val mathFaces: Map<MathFaceId, AndroidMathFontFace>,
    ) : AutoCloseable {
        fun mathFace(weight: MathFontWeight) = mathFaces.values.minBy { abs(it.resolvedWeight.cssWeight - weight.cssWeight) }
        fun hasWeight(weight: MathFontWeight) = mathFaces.values.any { it.resolvedWeight == weight }
        fun ordered(weight: MathFontWeight) = mathFaces.values.sortedBy { abs(it.resolvedWeight.cssWeight - weight.cssWeight) }
        fun reason(face: AndroidMathFontFace, requested: MathFontWeight) = when {
            face.resolvedWeight == requested -> MathFontFallbackReason.RequestedFace
            hasWeight(requested) -> MathFontFallbackReason.MissingGlyphInRequestedWeight
            else -> MathFontFallbackReason.RequestedWeightUnavailable
        }
        fun <T> firstSuccessful(
            weight: MathFontWeight,
            resolve: (AndroidMathFontFace) -> T,
            run: (T) -> MeasuredMathRun,
            accept: (AndroidMathFontFace, T) -> Boolean = { _, value -> !run(value).missingGlyph },
        ): Selected<T> {
            val ordered = ordered(weight)
            var first: Selected<T>? = null
            ordered.forEach { face ->
                val resolved = resolve(face)
                val selected = Selected(face, resolved, reason(face, weight))
                if (first == null) first = selected
                if (accept(face, resolved)) return selected
            }
            return checkNotNull(first)
        }
        override fun close() {
            mathFaces.values.forEach(AndroidMathFontFace::close)
        }
    }

    private data class Selected<T>(
        val face: AndroidMathFontFace,
        val value: T,
        val reason: MathFontFallbackReason,
    )

    companion object {
        const val LeteRegularAsset = "org/tiqian/math/fonts/${LeteSansMathPrebakedData.RegularFileStem}.otf"
        const val LeteBoldAsset = "org/tiqian/math/fonts/${LeteSansMathPrebakedData.BoldFileStem}.otf"
        const val LeteRegularSnapshotAsset = "org/tiqian/math/fonts/${LeteSansMathPrebakedData.RegularFileStem}.tqmath"
        const val LeteBoldSnapshotAsset = "org/tiqian/math/fonts/${LeteSansMathPrebakedData.BoldFileStem}.tqmath"
        fun fromSpec(spec: MathFontFamilySpec): AndroidMathFontFamily {
            val mathFaces = spec.faces.associate { face ->
                face.faceId to AndroidMathFontFace.fromBytes(
                    face.fontBytes, face.faceId, face.fontClass, face.weight, face.weight,
                )
            }
            return AndroidMathFontFamily(Owner(spec.fontClass, mathFaces), MathFontWeight.Regular)
        }

        fun fromPrebakedSpec(spec: PrebakedOpenTypeMathFamilySpec): AndroidMathFontFamily {
            val mathFaces = spec.faces.associate { face ->
                face.faceId to AndroidMathFontFace.fromPrebakedBytes(
                    face.fontBytes,
                    face.snapshotBytes,
                    face.faceId,
                    face.fontClass,
                    face.weight,
                    face.expectedSha256,
                )
            }
            return AndroidMathFontFamily(Owner(spec.fontClass, mathFaces), MathFontWeight.Regular)
        }

        fun loadBundledLete(context: Context): AndroidMathFontFamily {
            val assets = context.applicationContext.assets
            fun bytes(path: String) = assets.open(path).use { it.readBytes() }
            val regular = AndroidMathFontFace.fromPrebakedBytes(
                bytes(LeteRegularAsset), bytes(LeteRegularSnapshotAsset),
                MathFaceId("lete-sans-math-regular"), MathFontClass.SansSerif, MathFontWeight.Regular,
            )
            val bold = AndroidMathFontFace.fromPrebakedBytes(
                bytes(LeteBoldAsset), bytes(LeteBoldSnapshotAsset),
                MathFaceId("lete-sans-math-bold"), MathFontClass.SansSerif, MathFontWeight.Bold,
            )
            val faces = listOf(regular, bold).associateBy { it.faceId }
            return AndroidMathFontFamily(Owner(MathFontClass.SansSerif, faces), MathFontWeight.Regular)
        }
    }
}

private fun MeasuredMathRun.withFaceDecision(requested: MathFontWeight, reason: MathFontFallbackReason) =
    if (glyphs.all { it.requestedWeight == requested && it.fallbackReason == reason }) this
    else copy(glyphs = glyphs.map { it.copy(requestedWeight = requested, fallbackReason = reason) })

private fun MeasuredOutlineConstructionRun.withFaceDecision(requested: MathFontWeight, reason: MathFontFallbackReason) =
    copy(run = run.withFaceDecision(requested, reason))

private fun AndroidMathFontFace.operatorConstructionAvailable(
    resolved: ResolvedMathOperator,
    request: MathOperatorGlyphRequest,
    fontSizePx: Float,
): Boolean {
    if (resolved.run.missingGlyph) return false
    if (request.style.level != MathStyleLevel.Display) return true
    val glyphId = resolved.constructionBaseGlyphId ?: return false
    val target = mathFont.scaleDesignUnits(mathFont.constants.displayOperatorMinHeight, fontSizePx)
    val normal = measureGlyphOutlineBounds(glyphId, fontSizePx, request.style, request.sourceRange)
    val constructionRequest = MathVerticalConstructionRequest(
        baseGlyphId = glyphId,
        targetSizePx = target,
        fontSizePx = fontSizePx,
        normalGlyphHeightPx = normal.glyphs.maxOfOrNull { it.inkBounds.height }
            ?: normal.ascent + normal.descent,
        normalGlyphAdvanceWidthPx = normal.width,
        assemblyPolicy = MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap,
        resourceLimits = request.resourceLimits,
    )
    return try {
        mathFont.verticalConstructionAvailable(constructionRequest)
    } catch (failure: OpenTypeMathException) {
        when (failure.diagnosticCode) {
            DiagnosticCode.ExtenderCountLimitExceeded,
            DiagnosticCode.InvalidResolvedDimension,
            -> false
            else -> throw failure
        }
    }
}
