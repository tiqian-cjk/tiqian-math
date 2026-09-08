package org.tiqian.math.layout

import org.tiqian.math.core.*
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutAlphabetScopeNode(node: MathAlphabetScope, style: MathStyle): LaidNode {
    val override = MathAlphabetOverride(node.family, node.alphabet)
    decision(
        "TeXMathAlphabetScope",
        node.range,
        "family" to node.family,
        "alphabet" to node.alphabet,
        "appliesTo" to MathFamilyBinding.Variable,
    )
    return layoutScopedBody(node, node.body, style, override)
}

internal fun MathLayoutPass.layoutMathVersionScopeNode(node: MathVersionScope, style: MathStyle): LaidNode {
    val override = MathAlphabetOverride(version = node.version)
    decision(
        "TeXMathVersionScope",
        node.range,
        "version" to node.version,
        "appliesTo" to "AllMathAtoms",
        "glyphPolicy" to "FamilySpecificUnicodeMathAlphabetOrExplicitUnsupportedCapability",
    )
    return layoutScopedBody(node, node.body, style, override)
}

private fun MathLayoutPass.layoutScopedBody(
    scopeNode: MathNode,
    body: MathNode,
    style: MathStyle,
    override: MathAlphabetOverride,
): LaidNode {
    val horizontal = when (body) {
        is MathGroup -> layoutList(body.body, style, override)
        is MathList -> layoutList(body, style, override)
        else -> null
    }
    return if (horizontal != null) {
        val single = horizontal.items.singleOrNull()
        if (single?.node is MathSymbol) single.laid.copy(
            node = scopeNode,
            box = single.laid.box.copy(range = scopeNode.range),
        ) else horizontal.laid.copy(
            node = scopeNode,
            box = horizontal.laid.box.copy(range = scopeNode.range),
            atomClass = single?.atomClass ?: MathAtomClass.Ordinary,
            italicCorrectionPx = 0f,
            scriptBaseKind = single?.laid?.scriptBaseKind ?: ScriptBaseKind.CompoundBox,
        )
    } else {
        layoutNode(body, style, override).copy(node = scopeNode)
    }
}

internal fun MathLayoutPass.layoutSymbol(
    node: MathSymbol,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val size = fontSize(style)
    val request = symbolRequest(node, style, alphabetOverride)
    val resolved = glyphSource.resolveSymbol(request, size)
    return layoutResolvedSymbol(node, style, request, resolved)
}

internal fun MathLayoutPass.layoutResolvedSymbol(
    node: MathSymbol,
    style: MathStyle,
    request: MathSymbolGlyphRequest,
    resolved: ResolvedMathSymbol,
): LaidNode {
    val size = fontSize(style)
    val run = resolved.run
    if (!resolved.supported) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedMathAlphabet,
            "The selected formula-wide math face cannot resolve ${node.identity.debugName} " +
                "in ${request.family}/${request.alphabet}",
            node.range,
        )
    }
    if (run.missingGlyph) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The selected formula-wide math face has no ${request.family}/${request.alphabet} glyph " +
                "for ${node.identity.debugName}",
            node.range,
        )
    }
    val lastGlyph = run.glyphs.lastOrNull()
    val symbolMathFont = lastGlyph?.let { mathFontForFaceOrNull(it.faceId) }
    val italicCorrection = lastGlyph?.let { symbolMathFont?.italicCorrection(it.glyphId, size) } ?: 0f
    decision(
        "TeXMathSymbolResolution",
        node.range,
        "sourceText" to node.sourceText,
        "identity" to node.identity.debugName,
        "baseScalar" to unicodeLabel(node.identity.baseScalar),
        "atomClass" to node.atomClass,
        "familyBinding" to node.familyBinding,
        "declaredFamily" to node.family,
        "declaredAlphabet" to node.alphabet,
        "resolvedFamily" to request.family,
        "resolvedAlphabet" to request.alphabet,
        "backendScalar" to unicodeLabel(resolved.backendScalar),
        "glyphIds" to run.glyphs.joinToString(",") { it.glyphId.toString() },
        "faceIds" to run.glyphs.joinToString(",") { it.faceId.toString() },
        "fontClass" to run.glyphs.firstOrNull()?.fontClass,
        "requestedWeight" to run.glyphs.firstOrNull()?.requestedWeight,
        "resolvedWeight" to run.glyphs.firstOrNull()?.resolvedWeight,
        "fallbackReason" to run.glyphs.firstOrNull()?.fallbackReason,
        "italicCorrectionPx" to italicCorrection,
        "shaping" to "single-noad",
    )
    val placements = run.glyphs.map { glyph ->
        MathGlyphPlacement(
            glyphId = glyph.glyphId,
            x = glyph.x,
            baselineY = glyph.baselineOffsetPx,
            advance = glyph.advance,
            inkBounds = glyph.inkBounds.translated(glyph.x, glyph.baselineOffsetPx),
            fontSizePx = size,
            sourceRange = node.range,
            style = style,
            faceId = glyph.faceId,
            fontClass = glyph.fontClass,
            requestedWeight = glyph.requestedWeight,
            resolvedWeight = glyph.resolvedWeight,
            fallbackReason = glyph.fallbackReason,
        )
    }
    return LaidNode(
        node = node,
        box = geometryExtents(run.width, placements, emptyList(), node.range),
        atomClass = node.atomClass,
        italicCorrectionPx = italicCorrection,
        style = style,
        scriptBaseKind = when {
            placements.size != 1 -> ScriptBaseKind.CompoundBox
            mathFontForFaceOrNull(placements.single().faceId)
                ?.extendedShapeGlyphs?.contains(placements.single().glyphId) == true -> ScriptBaseKind.ExtendedShape
            else -> ScriptBaseKind.Character
        },
    )
}

internal fun MathLayoutPass.symbolRequest(
    node: MathSymbol,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): MathSymbolGlyphRequest = MathSymbolGlyphRequest(
    identity = node.identity,
    family = when {
        alphabetOverride?.version != null -> node.family
        node.familyBinding == MathFamilyBinding.Variable -> alphabetOverride?.family ?: node.family
        else -> node.family
    },
    alphabet = when (alphabetOverride?.version) {
        MathVersion.Bold -> when (node.family) {
            MathFamily.Letters -> MathAlphabet.BoldItalic
            MathFamily.Operators, MathFamily.Symbols, MathFamily.LargeSymbols -> MathAlphabet.Bold
        }
        null -> if (node.familyBinding == MathFamilyBinding.Variable) {
            alphabetOverride?.alphabet ?: node.alphabet
        } else {
            node.alphabet
        }
    },
    style = style,
    sourceRange = node.range,
)

internal fun MathLayoutPass.layoutText(node: MathText, style: MathStyle): LaidNode {
    val box = layoutTextSegments(node.segments, style, node.range, node.origin)
    decision(
        "TeXEmbeddedText",
        node.range,
        "commandRange" to node.commandRange,
        "contentRange" to node.contentRange,
        "text" to node.text,
        "origin" to node.origin,
        "segmentCount" to node.segments.size,
        "segmentRequestedWeights" to node.segments.joinToString(",") {
            (it.requestedWeight ?: glyphSource.requestedWeight).toString()
        },
        "spaceCount" to node.text.count { it.isWhitespace() || it == '\u00A0' },
        "style" to style,
        "fontSizePx" to fontSize(style),
        "shaping" to "TextRunNotMathNoadSequence",
        "measurementPaintSource" to if (box.hostTextRuns.isEmpty()) {
            "HostGlyphTextRunProvider"
        } else {
            "HostOpaqueTextBoxReplay"
        },
        "textLocale" to textLocale,
        "faceIds" to box.glyphs.map { it.faceId }.distinct().joinToString(","),
        "requestedWeights" to box.glyphs.map { it.requestedWeight }.distinct().joinToString(","),
        "resolvedWeights" to box.glyphs.map { it.resolvedWeight }.distinct().joinToString(","),
        "mathFallbackReasons" to box.glyphs.mapNotNull { it.fallbackReason }.distinct().joinToString(","),
        "hostRoles" to box.glyphs.mapNotNull { it.hostTextDecision?.hostRole }.distinct().joinToString(","),
        "hostFontKeys" to box.glyphs.mapNotNull { it.hostTextDecision?.fontKey }.distinct().joinToString(","),
        "hostSelectionReasons" to box.glyphs.mapNotNull { it.hostTextDecision?.selectionReason }.distinct().joinToString(","),
        "hostSubstitutionReasons" to box.glyphs.mapNotNull { it.hostTextDecision?.substitutionReason }.distinct().joinToString(","),
        "hostCapabilityIssues" to box.glyphs.mapNotNull { it.hostTextDecision?.capabilityIssue?.code }.distinct().joinToString(","),
        "hostTextBoxIds" to box.hostTextRuns.joinToString(",") { it.runId.value },
        "baselinePolicy" to if (box.hostTextRuns.isEmpty()) {
            "HostRunBaselineWithPerGlyphShapingOffsets"
        } else {
            "HostTextBoxBaseline"
        },
        "glyphBaselineOffsetsPx" to box.glyphs.joinToString(",") { it.baselineY.toString() },
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "inkTopPx" to box.inkBounds.top,
        "inkBottomPx" to box.inkBounds.bottom,
        "atomClassPolicy" to if (node.isCjkClauseSeparator) {
            "CjkClauseSeparatorPunctuationAtom"
        } else {
            "HostTextOrdinaryAtom"
        },
    )
    return LaidNode(
        node = node,
        box = box,
        // CjkClauseSeparatorPunctuationAtom: a lone fullwidth clause separator keeps host-text
        // rendering but classifies as Punctuation so it carries a trailing break opportunity.
        atomClass = if (node.isCjkClauseSeparator) MathAtomClass.Punctuation else MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutTextSegments(
    segments: List<MathTextSegment>,
    style: MathStyle,
    range: SourceRange,
    origin: MathTextOrigin? = null,
): MathBox {
    val size = fontSize(style)
    var x = 0f
    var hostLogicalAscent = 0f
    var hostLogicalDescent = 0f
    val placements = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    segments.forEach { segment ->
        val run = if (origin == null) {
            // Declared operator names remain an operators-family math run, not host prose.
            glyphSource.shapeText(segment.text, size, segment.range)
        } else {
            val provider = textRunProvider
            if (provider == null) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingTextRunProvider,
                    "Text atom '${segment.text}' requires an injected host MathTextRunProvider",
                    segment.range,
                )
                return@forEach
            }
            when (val result = provider.shapeTextAtom(
                MathTextRunRequest(
                    text = segment.text,
                    sourceRange = segment.range,
                    fontSizePx = size,
                    requestedWeight = segment.requestedWeight ?: glyphSource.requestedWeight,
                    locale = textLocale,
                    origin = origin,
                ),
            )) {
                is MathTextRunProviderResult.Ready -> result.run
                is MathTextRunProviderResult.ReadyBox -> {
                    validateHostTextBox(segment, result.box)?.let { invalid ->
                        diagnostics += invalid
                        return@forEach
                    }
                    val box = result.box
                    hostLogicalAscent = max(hostLogicalAscent, box.ascent)
                    hostLogicalDescent = max(hostLogicalDescent, box.descent)
                    hostTextRuns += MathHostTextPlacement(
                        runId = box.runId,
                        x = x,
                        baselineY = 0f,
                        width = box.width,
                        ascent = box.ascent,
                        descent = box.descent,
                        inkBounds = box.inkBounds.translated(x, 0f),
                        sourceRange = segment.range,
                        requestedWeight = segment.requestedWeight ?: glyphSource.requestedWeight,
                    )
                    x += box.width
                    return@forEach
                }
                is MathTextRunProviderResult.CapabilityIssue -> {
                    diagnostics += result.issue.asDiagnostic()
                    return@forEach
                }
            }
        }
        if (origin != null) {
            validateHostTextRun(segment, run)?.let { invalid ->
                diagnostics += invalid
                return@forEach
            }
            hostLogicalAscent = max(hostLogicalAscent, run.ascent)
            hostLogicalDescent = max(hostLogicalDescent, run.descent)
            val invalidGlyph = run.glyphs.firstOrNull { glyph ->
                val host = glyph.hostTextDecision
                glyph.fallbackReason != null || host == null || host.faceId != glyph.faceId ||
                    host.requestedWeight != glyph.requestedWeight ||
                    host.resolvedWeight != glyph.resolvedWeight ||
                    host.clusterRangeUtf16.start != glyph.textCluster ||
                    host.sourceRange.start != segment.range.start + glyph.textCluster ||
                    host.sourceRange.endExclusive > segment.range.endExclusive
            }
            if (invalidGlyph != null) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.InvalidHostTextRunEvidence,
                    "Host text glyph ${invalidGlyph.glyphId} does not carry a matching structured host face decision",
                    segment.range,
                )
            }
        }
        if (run.missingGlyph) {
            diagnostics += MathDiagnostic(
                DiagnosticCode.MissingGlyph,
                "The selected formula-wide face cannot shape embedded text '${segment.text}'",
                segment.range,
            )
        }
        val clusterBoundaries = (run.glyphs.map { it.textCluster } + segment.text.length)
            .distinct()
            .sorted()
        run.glyphs.forEach { glyph ->
            val sourceRange = textClusterSourceRange(
                segment,
                glyph.textCluster,
                clusterBoundaries.firstOrNull { it > glyph.textCluster },
            )
            placements += MathGlyphPlacement(
                glyphId = glyph.glyphId,
                x = x + glyph.x,
                baselineY = glyph.baselineOffsetPx,
                advance = glyph.advance,
                inkBounds = glyph.inkBounds.translated(x + glyph.x, glyph.baselineOffsetPx),
                fontSizePx = size,
                sourceRange = sourceRange,
                style = style,
                faceId = glyph.faceId,
                fontClass = glyph.fontClass,
                requestedWeight = glyph.requestedWeight,
                resolvedWeight = glyph.resolvedWeight,
                fallbackReason = glyph.fallbackReason,
                hostTextDecision = glyph.hostTextDecision?.copy(sourceRange = sourceRange),
            )
        }
        x += run.width
    }
    val geometry = geometryExtents(
        width = x,
        glyphs = placements,
        rules = emptyList(),
        range = range,
        hostTextRuns = hostTextRuns,
    )
    return if (origin == null) {
        geometry
    } else {
        geometry.copy(
            ascent = hostLogicalAscent,
            descent = hostLogicalDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = hostLogicalAscent,
                descent = hostLogicalDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = setOf(MathTeXCleanBoxEvidence.HostTextRunMetrics),
            ),
        )
    }
}

private fun MathLayoutPass.validateHostTextBox(
    segment: MathTextSegment,
    box: MathHostTextBox,
): MathDiagnostic? {
    fun invalid(message: String) = MathDiagnostic(
        DiagnosticCode.InvalidHostTextRunEvidence,
        message,
        segment.range,
    )
    val bounds = box.inkBounds
    if (!box.width.isFinite() || box.width < 0f ||
        !box.ascent.isFinite() || box.ascent < 0f ||
        !box.descent.isFinite() || box.descent < 0f ||
        !bounds.left.isFinite() || !bounds.top.isFinite() ||
        !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
        bounds.right < bounds.left || bounds.bottom < bounds.top
    ) {
        return invalid("Host text box has invalid logical or ink geometry")
    }
    return null
}

private fun MathLayoutPass.validateHostTextRun(
    segment: MathTextSegment,
    run: MeasuredMathRun,
): MathDiagnostic? {
    fun invalid(message: String) = MathDiagnostic(
        DiagnosticCode.InvalidHostTextRunEvidence,
        message,
        segment.range,
    )
    if (!run.width.isFinite() || run.width < 0f ||
        !run.ascent.isFinite() || run.ascent < 0f ||
        !run.descent.isFinite() || run.descent < 0f
    ) {
        return invalid("Host text run has non-finite or negative logical metrics")
    }
    run.glyphs.forEach { glyph ->
        val bounds = glyph.inkBounds
        if (!glyph.x.isFinite() || !glyph.advance.isFinite() || glyph.advance < 0f ||
            !glyph.baselineOffsetPx.isFinite() ||
            !bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            bounds.right < bounds.left || bounds.bottom < bounds.top
        ) {
            return invalid("Host text glyph ${glyph.glyphId} has invalid placement or ink metrics")
        }
        if (glyph.textCluster !in segment.text.indices) {
            return invalid("Host text glyph ${glyph.glyphId} has out-of-range UTF-16 cluster ${glyph.textCluster}")
        }
        val host = glyph.hostTextDecision
            ?: return invalid("Host text glyph ${glyph.glyphId} is missing structured face evidence")
        val cluster = host.clusterRangeUtf16
        if (cluster.start != glyph.textCluster || cluster.endExclusive <= cluster.start ||
            cluster.endExclusive > segment.text.length
        ) {
            return invalid("Host text glyph ${glyph.glyphId} has an invalid cluster range $cluster")
        }
        if (host.faceId != glyph.faceId || host.requestedWeight != glyph.requestedWeight ||
            host.resolvedWeight != glyph.resolvedWeight || glyph.fallbackReason != null
        ) {
            return invalid("Host text glyph ${glyph.glyphId} has inconsistent face or weight evidence")
        }
        if (host.sourceRange.start < segment.range.start ||
            host.sourceRange.endExclusive > segment.range.endExclusive || host.sourceRange.isEmpty
        ) {
            return invalid("Host text glyph ${glyph.glyphId} has an out-of-range source mapping")
        }
        host.capabilityIssue?.let { return it.asDiagnostic() }
    }
    return null
}

private fun MathLayoutPass.textClusterSourceRange(
    segment: MathTextSegment,
    cluster: Int,
    nextCluster: Int?,
): SourceRange {
    if (segment.text.length != segment.range.length) return segment.range
    val start = (segment.range.start + cluster).coerceIn(segment.range.start, segment.range.endExclusive)
    val end = (segment.range.start + (nextCluster ?: segment.text.length))
        .coerceIn(start, segment.range.endExclusive)
    return SourceRange(start, end)
}

/**
 * TexLogoComposition: `\TeX` / `\LaTeX` compose upright Operators-family letters with the
 * latex.ltx kerns — TeX: `T \kern-.1667em \lower.5ex E \kern-.125em X`; LaTeX prefixes
 * `L \kern-.36em` a script-size `A` raised so its top meets T's cap height, then `\kern-.15em`.
 * The ex lowering uses the font's OS/2 x-height like bbox `ex` units.
 */
internal fun MathLayoutPass.layoutTexLogo(
    node: MathTexLogo,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val em = fontSize(style)
    fun letter(ch: Char, scale: Float): MathBox {
        val ambient = userSizeScale
        userSizeScale = ambient * scale
        return try {
            val symbol = MathSymbol(
                sourceText = ch.toString(),
                identity = MathSymbolIdentity.LatinLetter(ch),
                atomClass = MathAtomClass.Ordinary,
                family = MathFamily.Operators,
                familyBinding = MathFamilyBinding.Fixed,
                alphabet = MathAlphabet.Roman,
                range = node.commandRange,
            )
            layoutList(MathList(listOf(symbol), node.commandRange), style, alphabetOverride).laid.box
        } finally {
            userSizeScale = ambient
        }
    }

    val t = letter('T', 1f)
    val e = letter('E', 1f)
    val x = letter('X', 1f)
    val exHeightPx = glyphSource.mathFont.xHeight
        ?.let { glyphSource.mathFont.scaleDesignUnits(it, em) }
        ?: (CM_EX_HEIGHT_EM * em)

    val placed = mutableListOf<MathBox>()
    var pen = 0f
    if (node.kind == MathTexLogoKind.Latex) {
        val l = letter('L', 1f)
        val a = letter('A', 0.7f)
        placed += l.translated(pen, 0f)
        pen += l.width - 0.36f * em
        val aRaise = -(t.ascent - a.ascent)
        placed += a.translated(pen, aRaise)
        pen += a.width - 0.15f * em
    }
    placed += t.translated(pen, 0f)
    pen += t.width - 0.1667f * em
    placed += e.translated(pen, 0.5f * exHeightPx)
    pen += e.width - 0.125f * em
    placed += x.translated(pen, 0f)
    pen += x.width

    val box = geometryExtents(
        width = pen,
        glyphs = placed.flatMap { it.glyphs },
        rules = placed.flatMap { it.rules },
        range = node.range,
        constructionPaintGroups = placed.flatMap { it.constructionPaintGroups },
        hostTextRuns = placed.flatMap { it.hostTextRuns },
    )
    decision(
        "TexLogoComposition",
        node.range,
        "kind" to node.kind.sourceName,
        "kernPolicy" to "LatexLtxKernAndRaiseRatios",
        "exSource" to if (glyphSource.mathFont.xHeight != null) "FontOs2XHeight" else "FallbackComputerModernExRatio",
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

/** Computer Modern's ex height (fontdimen5) as an em ratio, the classic fallback. */
private const val CM_EX_HEIGHT_EM = 0.4306f
