package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.layout.MathLayoutPass.Companion.binaryLeftCanceller
import org.tiqian.math.layout.MathLayoutPass.Companion.binaryRightCanceller
import org.tiqian.math.layout.MathLayoutPass.HorizontalItem
import org.tiqian.math.layout.MathLayoutPass.HorizontalLayout
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride
import org.tiqian.math.layout.MathLayoutPass.PendingHorizontalItem

internal fun MathLayoutPass.layoutList(
    list: MathList,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride? = null,
): HorizontalLayout {
    val raw = flattenListChildren(list, style, alphabetOverride)
    val classes = raw.map { it.laid.atomClass }.toMutableList()
    val noadIndices = raw.indices.filter { raw[it].participatesInNoadSpacing }
    for (position in noadIndices.indices) {
        val index = noadIndices[position]
        val previousIndex = noadIndices.getOrNull(position - 1)
        val previous = previousIndex?.let(classes::get)
        val current = classes[index]
        if (previous == MathAtomClass.Binary && current in binaryRightCanceller) {
            classes[checkNotNull(previousIndex)] = MathAtomClass.Ordinary
        }
        val resolvedPrevious = previousIndex?.let(classes::get)
        if (current == MathAtomClass.Binary && (resolvedPrevious == null || resolvedPrevious in binaryLeftCanceller)) {
            classes[index] = MathAtomClass.Ordinary
        }
    }
    noadIndices.lastOrNull()?.let { last ->
        if (classes[last] == MathAtomClass.Binary) classes[last] = MathAtomClass.Ordinary
    }
    raw.indices.forEach { index ->
        if (raw[index].laid.atomClass != classes[index]) {
            decision(
                "TeXBinaryAtomReclassification",
                raw[index].node.range,
                "from" to raw[index].laid.atomClass,
                "to" to classes[index],
                "listRange" to "${list.range.start}..${list.range.endExclusive}",
            )
        }
    }

    // TeX spacing and italic-correction ownership need the nearest real noad on either side.
    // Resolve both directions once: repeated lastOrNull/firstOrNull scans make a flat n-noad
    // formula quadratic even though the neighboring noads are a purely linear relation.
    val previousNoadIndex = IntArray(raw.size) { -1 }
    var previousNoad = -1
    raw.indices.forEach { index ->
        previousNoadIndex[index] = previousNoad
        if (raw[index].participatesInNoadSpacing) previousNoad = index
    }
    val nextNoadIndex = IntArray(raw.size) { -1 }
    var nextNoad = -1
    for (index in raw.indices.reversed()) {
        nextNoadIndex[index] = nextNoad
        if (raw[index].participatesInNoadSpacing) nextNoad = index
    }

    val spacedItems = raw.mapIndexed { index, item ->
        val leftIndex = previousNoadIndex[index].takeIf { it >= 0 }
        val leftClass = leftIndex?.let(classes::get)
        val rightClass = classes[index]
        val glue = when {
            !item.participatesInNoadSpacing || leftClass == null -> MathGlueAdjustment.Zero
            // FullwidthClauseSeparatorCarriesOwnSpace: the fullwidth glyph already ends with its
            // blank half, so the Punctuation pair glue is not stacked on top of it.
            (raw[checkNotNull(leftIndex)].node as? MathText)?.isCjkClauseSeparator == true ->
                MathGlueAdjustment.Zero
            // Pair glue lives in the size context of its right atom, matching MathJax's
            // scaled-mstyle spacing and TeX's mu-follows-current-font model.
            else -> {
                val ambientSizeScale = userSizeScale
                if (item.sizeScale != null) userSizeScale = item.sizeScale
                try {
                    atomGlue(leftClass, rightClass, item.laid.style, item.node.range)
                } finally {
                    userSizeScale = ambientSizeScale
                }
            }
        }
        item.copy(glueBefore = glue, atomClass = rightClass)
    }
    val items = spacedItems.mapIndexed { index, item ->
        val rightClass = nextNoadIndex[index].takeIf { it >= 0 }?.let(classes::get)
        val correction = item.laid.italicCorrectionPx.coerceAtLeast(0f)
        if (correction > 0f) {
            decision(
                "OpenTypeItalicCorrectionBoundary",
                item.node.range,
                "rightClass" to rightClass,
                "correctionPx" to correction,
                "owner" to when (item.node) {
                    is MathList -> "compatible-ord-run-final-glyph"
                    is MathOperator -> "operator-noad"
                    else -> "character-noad"
                },
                "policy" to "nucleus-owned-not-next-atom-classified",
            )
        }
        item.copy(trailingItalicCorrectionPx = correction)
    }
    var x = 0f
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    items.forEach { item ->
        x += item.leadingKernPx
        x += item.glueBefore.naturalPx
        val shifted = item.laid.box.translated(x, 0f)
        glyphs += shifted.glyphs
        hostTextRuns += shifted.hostTextRuns
        rules += shifted.rules
        x += item.laid.box.width + item.trailingItalicCorrectionPx
    }
    val box = geometryExtentsPreservingLogicalChildren(
        x.coerceAtLeast(0f),
        glyphs,
        rules,
        list.range,
        items.map { it.laid.box to 0f },
        hostTextRuns = hostTextRuns,
    )
    val atomClass = items.singleOrNull()?.atomClass ?: MathAtomClass.Ordinary
    return HorizontalLayout(
        LaidNode(
            list,
            box,
            atomClass,
            0f,
            style,
            items.singleOrNull()?.laid?.scriptBaseKind ?: ScriptBaseKind.CompoundBox,
        ),
        items,
    )
}

private fun MathLayoutPass.flattenListChildren(
    list: MathList,
    initialStyle: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): List<HorizontalItem> = layoutPendingItems(
    flattenPendingListChildren(list, initialStyle, alphabetOverride),
)

private fun MathLayoutPass.flattenPendingListChildren(
    list: MathList,
    initialStyle: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): List<PendingHorizontalItem> {
    var currentStyle = initialStyle
    var currentAlphabetOverride = alphabetOverride
    var currentPaintColor: MathPaintColor? = null
    var currentSizeScale: Float? = null
    return buildList {
        list.children.forEach { child ->
            if (child is MathStyleDeclaration) {
                val nextStyle = styleForLevel(child.requestedLevel)
                decision(
                    "TeXMathStyleDeclaration",
                    child.range,
                    "from" to currentStyle,
                    "to" to nextStyle,
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                )
                currentStyle = nextStyle
            } else if (child is MathAlphabetDeclaration) {
                currentAlphabetOverride = MathAlphabetOverride(child.family, child.alphabet)
                decision(
                    "TeXMathAlphabetDeclaration",
                    child.range,
                    "family" to child.family,
                    "alphabet" to child.alphabet,
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                    "policy" to "LegacyTeXListDeclaration",
                )
            } else if (child is MathVersionDeclaration) {
                currentAlphabetOverride = (currentAlphabetOverride ?: MathAlphabetOverride()).copy(
                    version = child.version,
                )
                decision(
                    "TeXMathVersionDeclaration",
                    child.range,
                    "version" to child.version,
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                    "scopePolicy" to "TeXDeclarationUntilCurrentMathListGroupEnd",
                    "versionPolicy" to "UnicodeMathBoldVersionCompatibilityForLegacyBf",
                )
            } else if (child is MathSizeDeclaration) {
                val resolvedFontSizePx = baseFontSizePx * child.scale
                currentSizeScale = if (
                    validatedResolvedDimension(
                        sourceText = "\\${child.sourceName} font size",
                        resolvedPx = resolvedFontSizePx,
                        range = child.range,
                    ) != null
                ) {
                    child.scale
                } else {
                    1f
                }
                decision(
                    "LatexSizeDeclaration",
                    child.range,
                    "sourceName" to child.sourceName,
                    "scale" to child.scale,
                    "resolvedFontSizePx" to resolvedFontSizePx,
                    "acceptedScale" to currentSizeScale,
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                    "scopePolicy" to "TeXDeclarationUntilCurrentMathListGroupEnd",
                    "scalePolicy" to "AbsoluteLatexTenPointClassRatios",
                )
            } else if (child is MathColorDeclaration) {
                currentPaintColor = child.color
                decision(
                    "XColorMathDeclaration",
                    child.range,
                    "sourceName" to child.sourceName,
                    "commandRange" to child.commandRange,
                    "nameRange" to child.nameRange,
                    "resolvedArgb" to child.color.argb.toUInt().toString(16).padStart(8, '0'),
                    "listRange" to "${list.range.start}..${list.range.endExclusive}",
                    "scopePolicy" to "TeXDeclarationUntilCurrentMathListGroupEnd",
                    "resolutionPolicy" to "XColorBaseNamesHtmlHexTripletsAndCssSvgKeywords",
                )
            } else {
                addAll(
                    flattenPendingHorizontal(
                        child,
                        currentStyle,
                        currentAlphabetOverride,
                        currentPaintColor,
                        currentSizeScale,
                    ),
                )
            }
        }
    }
}

private fun MathLayoutPass.flattenPendingHorizontal(
    node: MathNode,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
    paintColor: MathPaintColor?,
    sizeScale: Float?,
): List<PendingHorizontalItem> = when (node) {
    is MathAlphabetScope -> {
        val override = MathAlphabetOverride(node.family, node.alphabet)
        decision(
            "TeXMathAlphabetScope",
            node.range,
            "family" to node.family,
            "alphabet" to node.alphabet,
            "appliesTo" to MathFamilyBinding.Variable,
        )
        when (val body = node.body) {
            is MathGroup -> flattenPendingListChildren(body.body, style, override).map {
                it.copy(paintColor = it.paintColor ?: paintColor, sizeScale = it.sizeScale ?: sizeScale)
            }
            is MathList -> flattenPendingListChildren(body, style, override).map {
                it.copy(paintColor = it.paintColor ?: paintColor, sizeScale = it.sizeScale ?: sizeScale)
            }
            else -> listOf(PendingHorizontalItem(body, style, override, paintColor, sizeScale))
        }
    }
    else -> listOf(PendingHorizontalItem(node, style, alphabetOverride, paintColor, sizeScale))
}

private fun MathLayoutPass.layoutPendingItems(pending: List<PendingHorizontalItem>): List<HorizontalItem> {
    var index = 0
    while (index < pending.size) {
        val first = pending[index]
        val firstSymbol = first.node as? MathSymbol
        if (firstSymbol?.atomClass != MathAtomClass.Ordinary) {
            index += 1
            continue
        }
        val request = symbolRequest(firstSymbol, first.style, first.alphabetOverride)
        var endExclusive = index + 1
        while (endExclusive < pending.size) {
            val candidate = pending[endExclusive]
            val symbol = candidate.node as? MathSymbol ?: break
            if (
                symbol.atomClass != MathAtomClass.Ordinary ||
                candidate.style != first.style ||
                candidate.paintColor != first.paintColor ||
                candidate.sizeScale != first.sizeScale
            ) break
            val candidateRequest = symbolRequest(symbol, candidate.style, candidate.alphabetOverride)
            if (candidateRequest.family != request.family || candidateRequest.alphabet != request.alphabet) break
            endExclusive += 1
        }
        if (endExclusive - index >= 2) {
            decision(
                "XeTeXNativeMathOrdNoadSequence",
                pending[index].node.range.cover(pending[endExclusive - 1].node.range),
                "noadCount" to (endExclusive - index),
                "family" to request.family,
                "alphabet" to request.alphabet,
                "style" to first.style,
                "shapingPolicy" to "OneNativeMathGlyphFieldPerSourceNoad",
                "italicCorrectionPolicy" to "EachCompletedNoadOwnsItsCorrection",
            )
        }
        index = endExclusive
    }
    return pending.map { it.layoutIndividually() }
}

internal fun MathLayoutPass.atomGlue(
    left: MathAtomClass,
    right: MathAtomClass,
    rightStyle: MathStyle,
    range: SourceRange,
): MathGlueAdjustment {
    val tight = rightStyle.level == MathStyleLevel.Script || rightStyle.level == MathStyleLevel.ScriptScript
    val kind = TeXMathSpacing.kind(left, right, tight)
    val priority = adjustmentPriority(left, right)
    val mu = fontSize(rightStyle) / 18f
    // TeX's thinmuskip/thickmuskip are shrink-free, but an inline formula justified inside a CJK
    // line lets its relation and punctuation spaces fully compress (shrink to zero, like binary's
    // medmuskip) as well as stretch, so a break-trailing space is equally shrinkable and
    // discardable. Deliberate deviation from TeX; see MathGeometryAuditTest.
    val glue = when (kind) {
        MathGlueKind.None -> MathGlueAdjustment.Zero
        MathGlueKind.Thin -> if (priority == MathAdjustmentPriority.Punctuation) {
            glue(kind, 3f * mu, 0f, 6f * mu, priority)
        } else {
            glue(kind, 3f * mu, 3f * mu, 3f * mu, priority)
        }
        MathGlueKind.Medium -> glue(kind, 4f * mu, 0f, 6f * mu, priority)
        MathGlueKind.Thick -> glue(kind, 5f * mu, 0f, 10f * mu, priority)
    }
    decision(
        "TeXMathAtomSpacing",
        range,
        "left" to left,
        "right" to right,
        "style" to rightStyle,
        "table" to if (tight) "tight" else "display-text",
        "kind" to kind,
        "naturalPx" to glue.naturalPx,
        "minimumPx" to glue.minimumPx,
        "maximumPx" to glue.maximumPx,
        "priority" to glue.priority,
    )
    return glue
}

internal fun MathLayoutPass.glue(
    kind: MathGlueKind,
    natural: Float,
    minimum: Float,
    maximum: Float,
    priority: MathAdjustmentPriority,
): MathGlueAdjustment = MathGlueAdjustment(
    kind,
    natural,
    minimum,
    maximum,
    natural - minimum,
    maximum - natural,
    priority,
)

internal fun MathLayoutPass.adjustmentPriority(left: MathAtomClass, right: MathAtomClass?): MathAdjustmentPriority = when {
    left == MathAtomClass.Punctuation -> MathAdjustmentPriority.Punctuation
    left == MathAtomClass.Relation || right == MathAtomClass.Relation -> MathAdjustmentPriority.Relation
    left == MathAtomClass.Binary || right == MathAtomClass.Binary -> MathAdjustmentPriority.BinaryOperator
    else -> MathAdjustmentPriority.Other
}
