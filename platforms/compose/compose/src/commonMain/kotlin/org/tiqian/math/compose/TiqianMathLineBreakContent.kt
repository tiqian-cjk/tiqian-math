package org.tiqian.math.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.SubcomposeLayout
import org.tiqian.math.core.MathBrokenLayout
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathFormulaCapabilityClassifier
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.breakIntoLines

/** A Ready result can acquire blocking diagnostics when the host width becomes known. */
internal fun lineBreakFailure(
    result: MathLayoutResult,
    broken: MathBrokenLayout?,
): MathFormulaCapabilityResult.FallbackRequired? =
    if (broken == null || broken.diagnostics.isEmpty()) null else {
        MathFormulaCapabilityClassifier.classify(result, broken.diagnostics)
    }

/** Select the host fallback before composing a canvas for a constraint-dependent break. */
@Composable
internal fun InlineTiqianMath(
    result: MathLayoutResult,
    modifier: Modifier,
    requestedLineHeightPx: Float?,
    color: Color,
    softWrap: Boolean,
    face: MathComposeFontFace,
    textRunProvider: MathTextRunProvider?,
    onMathLayout: (MathLayoutResult) -> Unit,
    fallback: (@Composable (MathFormulaCapabilityResult.FallbackRequired) -> Unit)?,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val broken = if (softWrap && constraints.hasBoundedWidth && result.fragments.size > 1) {
            result.breakIntoLines(constraints.maxWidth.toFloat().coerceAtLeast(1f))
        } else {
            null
        }
        val failure = lineBreakFailure(result, broken)
        val measurable = if (failure != null) {
            val errorPresentation = fallback ?: throw MathFormulaStrictException(failure)
            subcompose(InlineFormulaSlot.Fallback) {
                Box(propagateMinConstraints = true) { errorPresentation(failure) }
            }.single()
        } else {
            val plan = if (broken == null) {
                RenderPlan.unbroken(result, requestedLineHeightPx)
            } else {
                RenderPlan.broken(result, broken, requestedLineHeightPx)
            }
            subcompose(InlineFormulaSlot.Math) {
                SideEffect { onMathLayout(result) }
                FixedTiqianMathPlan(plan, Modifier, color, face, textRunProvider)
            }.single()
        }
        val child = measurable.measure(constraints)
        val baseline = child[FirstBaseline]
        layout(
            child.width,
            child.height,
            alignmentLines = if (baseline == AlignmentLine.Unspecified) emptyMap() else mapOf(FirstBaseline to baseline),
        ) {
            child.place(0, 0)
        }
    }
}

private enum class InlineFormulaSlot { Math, Fallback }
