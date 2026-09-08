package org.tiqian.math.layout

import kotlin.math.abs
import org.tiqian.math.core.SourceRange
import org.tiqian.math.core.invalidResolvedDimensionDiagnostic

/** Validates unit conversion before the value enters box geometry. */
internal fun MathLayoutPass.validatedResolvedDimension(
    sourceText: String,
    resolvedPx: Float,
    range: SourceRange,
): Float? {
    if (resolvedPx.isFinite() && abs(resolvedPx) <= resourceLimits.maximumResolvedDimensionPx) {
        return resolvedPx
    }
    diagnostics += invalidResolvedDimensionDiagnostic(
        sourceText = sourceText,
        resolvedPx = resolvedPx,
        maximumAbsolutePx = resourceLimits.maximumResolvedDimensionPx,
        range = range,
    )
    return null
}
