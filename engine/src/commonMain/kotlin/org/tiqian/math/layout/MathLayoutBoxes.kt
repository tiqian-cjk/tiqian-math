package org.tiqian.math.layout

import org.tiqian.math.core.*
import kotlin.math.floor
import kotlin.math.max
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_TALL_SLOPES
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_TALL_WIDTH_FACTORS
import org.tiqian.math.layout.MathLayoutPass.Companion.CANCEL_WIDE_SLOPES
import org.tiqian.math.layout.MathLayoutPass.Companion.CENTIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.CSS_PIXELS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_LINE_EXTENSION_PT
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_MAXIMUM_DERIVED_POINT_FACTOR
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_MINIMUM_TOTAL_HEIGHT_PT
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_MINIMUM_WIDTH_PT
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_TALL_MINIMUM_HEIGHT_PT
import org.tiqian.math.layout.MathLayoutPass.Companion.DEFAULT_CANCEL_WIDE_MINIMUM_WIDTH_PT
import org.tiqian.math.layout.MathLayoutPass.Companion.MILLIMETERS_PER_INCH
import org.tiqian.math.layout.MathLayoutPass.Companion.NEGATED_RELATION_SCALARS
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_MU_PER_EM
import org.tiqian.math.layout.MathLayoutPass.AccentAttachmentEvidence
import org.tiqian.math.layout.MathLayoutPass.CancelStrokeGeometry
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

private const val NOT_ACCENT_SCALAR = 0x0338

internal fun MathLayoutPass.layoutExplicitSpace(node: MathExplicitSpace, style: MathStyle): LaidNode {
    val unvalidatedAdvance = node.mu * fontSize(style) / 18f
    val advance = validatedResolvedDimension(
        sourceText = "${node.mu}mu",
        resolvedPx = unvalidatedAdvance,
        range = node.range,
    ) ?: 0f
    val width = advance.coerceAtLeast(0f)
    decision(
        "TeXExplicitMathSpace",
        node.range,
        "command" to node.command,
        "mu" to node.mu,
        "style" to style,
        "fontSizePx" to fontSize(style),
        "advancePx" to unvalidatedAdvance,
        "acceptedAdvancePx" to advance,
        "boxWidthPx" to width,
        "policy" to if (advance < 0f) "TeXFixedSignedMuKern" else "NamedTeXMuSkip",
    )
    return LaidNode(
        node = node,
        box = MathBox(
            width = width,
            ascent = 0f,
            descent = 0f,
            inkBounds = MathRect(0f, 0f, 0f, 0f),
            glyphs = emptyList(),
            rules = emptyList(),
            range = node.range,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = 0f,
                descent = 0f,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = setOf(MathTeXCleanBoxEvidence.Empty),
            ),
        ),
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
        horizontalKernPx = advance.coerceAtMost(0f),
    )
}

internal fun MathLayoutPass.layoutNegation(
    node: MathNegation,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val base = node.base as? MathSymbol
    val negatedScalar = if (node.interveningSpaces.isEmpty()) {
        base?.identity?.baseScalar?.let(NEGATED_RELATION_SCALARS::get)
    } else {
        null
    }
    if (negatedScalar != null) {
        val synthetic = MathSymbol(
            sourceText = "\\not${base?.sourceText.orEmpty()}",
            identity = MathSymbolIdentity.Literal(negatedScalar),
            atomClass = checkNotNull(base).atomClass,
            family = MathFamily.Symbols,
            familyBinding = MathFamilyBinding.Fixed,
            range = node.range,
        )
        val laid = layoutSymbol(synthetic, style, alphabetOverride)
        decision(
            "TeXNotRelation",
            node.range,
            "commandRange" to node.commandRange,
            "baseIdentity" to base.identity.debugName,
            "baseScalar" to unicodeLabel(base.identity.baseScalar),
            "precomposedScalar" to unicodeLabel(negatedScalar),
            "atomClass" to base.atomClass,
            "interveningSpaceCount" to 0,
            "policy" to "XeTeXUnicodeMathPrecomposedNegatedRelation",
        )
        return laid.copy(node = node)
    }

    if (node.base is MathExplicitSpace) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.UnsupportedNegatedSymbol,
            "Only the real-article \\not\\!<atom> compatibility form treats a spacing command as leading kern",
            node.base.range,
        )
        val laid = layoutNode(node.base, style.cramped(), alphabetOverride)
        decision(
            "TeXNotRelation",
            node.range,
            "commandRange" to node.commandRange,
            "baseKind" to node.base::class.simpleName,
            "interveningSpaceCount" to 0,
            "policy" to "UnsupportedSpacingCommandAsNegatedAtom",
        )
        return laid.copy(node = node)
    }

    val nucleusStyle = style.cramped()
    val size = fontSize(style)
    val unvalidatedInterveningAdvancePx = node.interveningSpaces.sumOf {
        (it.mu * size / TEX_MU_PER_EM).toDouble()
    }.toFloat()
    val interveningAdvancePx = validatedResolvedDimension(
        sourceText = "\\not intervening mu spaces",
        resolvedPx = unvalidatedInterveningAdvancePx,
        range = node.commandRange,
    ) ?: 0f
    val resolvedSymbolWithOverlay = base?.let { symbol ->
        val request = symbolRequest(symbol, nucleusStyle, alphabetOverride)
        request to glyphSource.resolveSymbolWithRequiredGlyph(
            request = request,
            requiredScalar = NOT_ACCENT_SCALAR,
            fontSizePx = size,
        )
    }
    val laidTarget = if (resolvedSymbolWithOverlay == null) {
        layoutNode(node.base, nucleusStyle, alphabetOverride)
    } else {
        layoutResolvedSymbol(
            node = checkNotNull(base),
            style = nucleusStyle,
            request = resolvedSymbolWithOverlay.first,
            resolved = resolvedSymbolWithOverlay.second.symbol,
        )
    }
        .withNativeOutlineBoxForSideScriptPlacement()
        .completedTeXMathField()
    val laidBase = if (interveningAdvancePx == 0f) {
        laidTarget
    } else {
        laidTarget.copy(
            box = laidTarget.box.translated(interveningAdvancePx, 0f).copy(
                width = (laidTarget.box.width + interveningAdvancePx).coerceAtLeast(0f),
            ),
        )
    }
    val nucleusFaceIds = laidTarget.box.glyphs.map { it.faceId }.distinct()
    val overlayFaceId = nucleusFaceIds.singleOrNull()
    val overlayMathFont = overlayFaceId?.let(::mathFontForFaceOrNull)
    val overlayGlyphId = if (resolvedSymbolWithOverlay == null) {
        val scriptStyleLevel = when (style.level) {
            MathStyleLevel.Display, MathStyleLevel.Text -> 0
            MathStyleLevel.Script -> 1
            MathStyleLevel.ScriptScript -> 2
        }
        overlayMathFont?.glyphForScalar(NOT_ACCENT_SCALAR, scriptStyleLevel)
    } else {
        resolvedSymbolWithOverlay.second.requiredGlyphId.takeIf {
            resolvedSymbolWithOverlay.second.owningFaceId == overlayFaceId
        }
    }
    if (overlayFaceId == null || overlayMathFont == null || overlayGlyphId == null) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.MissingGlyph,
            "The negated atom must have one owning MATH face with a replayable U+0338 overlay glyph",
            node.commandRange,
        )
        decision(
            "TeXNotRelation",
            node.range,
            "commandRange" to node.commandRange,
            "baseKind" to node.base::class.simpleName,
            "precomposedScalar" to null,
            "overlayScalar" to "U+0338",
            "nucleusFaceIds" to nucleusFaceIds.joinToString(","),
            "interveningSpaceCount" to node.interveningSpaces.size,
            "policy" to "MissingSameFaceOpenTypeNegationOverlayGlyph",
        )
        return laidBase.copy(node = node)
    }

    val overlayMeasurement = measureGlyphOutlineForFace(
        overlayFaceId,
        overlayGlyphId,
        size,
        style,
        node.commandRange,
    )
    val measuredOverlay = overlayMeasurement.glyphs.single()
    val overlayAttachmentEvidence = resolveTopAccentAttachment(
        measuredOverlay.faceId,
        measuredOverlay.glyphId,
        size,
        measuredOverlay.advance,
        node.commandRange,
        "negation overlay",
    )
    val baseGlyph = laidBase.box.singleGlyphOrNull()
    val baseAttachmentEvidence = if (baseGlyph == null) {
        AccentAttachmentEvidence(
            interveningAdvancePx + laidTarget.box.width / 2f,
            "CompletedNucleusLogicalCenterAfterExplicitKern",
        )
    } else {
        resolveTopAccentAttachment(
            baseGlyph.faceId,
            baseGlyph.glyphId,
            baseGlyph.fontSizePx,
            baseGlyph.advance,
            node.base.range,
            "negated nucleus",
        ).let { it.copy(valuePx = baseGlyph.x + it.valuePx) }
    }
    val overlayX = baseAttachmentEvidence.valuePx - overlayAttachmentEvidence.valuePx
    // XeTeX's `mathaccentoverlay` box trace places the slash so its glyph bottom meets the
    // nucleus baseline. This consumes the selected font outline instead of a visual offset.
    val overlayBaselineY = -measuredOverlay.inkBounds.bottom
    val positionedOverlay = MathGlyphPlacement(
        glyphId = measuredOverlay.glyphId,
        x = overlayX,
        baselineY = overlayBaselineY,
        advance = measuredOverlay.advance,
        inkBounds = measuredOverlay.inkBounds.translated(overlayX, overlayBaselineY),
        fontSizePx = size,
        sourceRange = node.commandRange,
        style = style,
        faceId = measuredOverlay.faceId,
        fontClass = measuredOverlay.fontClass,
        requestedWeight = measuredOverlay.requestedWeight,
        resolvedWeight = measuredOverlay.resolvedWeight,
        fallbackReason = measuredOverlay.fallbackReason,
    )
    val glyphs = laidBase.box.glyphs + positionedOverlay
    val geometry = geometryExtents(
        width = laidBase.box.width,
        glyphs = glyphs,
        rules = laidBase.box.rules,
        range = node.range,
        constructionPaintGroups = laidBase.box.constructionPaintGroups,
        hostTextRuns = laidBase.box.hostTextRuns,
    )
    val overlayAscent = (-positionedOverlay.inkBounds.top).coerceAtLeast(0f)
    val overlayDescent = positionedOverlay.inkBounds.bottom.coerceAtLeast(0f)
    val box = geometry.copy(
        width = laidBase.box.width,
        ascent = max(laidBase.box.ascent, overlayAscent),
        descent = max(laidBase.box.descent, overlayDescent),
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = max(laidBase.box.texCleanBoxMetrics.ascent, overlayAscent),
            descent = max(laidBase.box.texCleanBoxMetrics.descent, overlayDescent),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = geometry.texCleanBoxMetrics.evidence +
                laidBase.box.texCleanBoxMetrics.evidence + MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    val resultAtomClass = if (node.interveningSpaces.isEmpty()) {
        // unicode-math's overlay fallback is a math accent box. XeTeX therefore completes it as
        // an ordinary atom; precomposed negated relations above retain their relation class.
        MathAtomClass.Ordinary
    } else {
        // The real-article compatibility bridge keeps the class of the atom following the
        // preserved explicit kern. This is deliberately distinct from valid unicode-math `\\not`.
        laidTarget.atomClass
    }
    decision(
        "TeXNotRelation",
        node.range,
        "commandRange" to node.commandRange,
        "baseKind" to node.base::class.simpleName,
        "baseIdentity" to base?.identity?.debugName,
        "baseScalar" to base?.identity?.baseScalar?.let(::unicodeLabel),
        "precomposedScalar" to null,
        "overlayScalar" to "U+0338",
        "overlayGlyphId" to measuredOverlay.glyphId,
        "overlayFaceId" to measuredOverlay.faceId,
        "overlayRequestedWeight" to measuredOverlay.requestedWeight,
        "overlayResolvedWeight" to measuredOverlay.resolvedWeight,
        "overlayFallbackReason" to measuredOverlay.fallbackReason,
        "nucleusFaceIds" to nucleusFaceIds.joinToString(","),
        "nucleusRequestedWeight" to laidTarget.box.glyphs.firstOrNull()?.requestedWeight,
        "nucleusResolvedWeight" to laidTarget.box.glyphs.firstOrNull()?.resolvedWeight,
        "nucleusFallbackReason" to laidTarget.box.glyphs.firstOrNull()?.fallbackReason,
        "overlayAdvancePx" to measuredOverlay.advance,
        "interveningSpaceCount" to node.interveningSpaces.size,
        "interveningSpaceCommands" to node.interveningSpaces.joinToString(",") { it.command },
        "interveningSpaceMu" to node.interveningSpaces.joinToString(",") { it.mu.toString() },
        "interveningSpaceRanges" to node.interveningSpaces.joinToString(",") {
            "${it.range.start}..${it.range.endExclusive}"
        },
        "interveningAdvancePx" to interveningAdvancePx,
        "style" to style,
        "nucleusStyle" to nucleusStyle,
        "baseAttachmentPx" to baseAttachmentEvidence.valuePx,
        "baseAttachmentPolicy" to baseAttachmentEvidence.policy,
        "overlayAttachmentPx" to overlayAttachmentEvidence.valuePx,
        "overlayAttachmentPolicy" to overlayAttachmentEvidence.policy,
        "overlayX" to overlayX,
        "overlayBaselineY" to overlayBaselineY,
        "verticalPlacementPolicy" to "XeTeXMathAccentOverlayGlyphBottomAtNucleusBaseline",
        "logicalAdvancePx" to box.width,
        "atomClass" to resultAtomClass,
        "atomClassPolicy" to if (node.interveningSpaces.isEmpty()) {
            "XeTeXMathAccentCompletedAsOrdinary"
        } else {
            "RetainTargetAtomClassAfterArticleCompatibilityComposition"
        },
        "policy" to if (node.interveningSpaces.isEmpty()) {
            "XeTeXUnicodeMathNotAccentOverlayOrdinaryAtom"
        } else {
            "ArticleNotNegativeThinKernOpenTypeOverlayCompatibility"
        },
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = resultAtomClass,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutCancel(
    node: MathCancel,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val content = layoutNode(node.body, style, alphabetOverride).completedTeXMathField().box
    val clean = content.texCleanBoxMetrics
    val totalHeight = clean.height
    val acceptedPicturePointPx = validatedResolvedDimension(
        sourceText = "cancel.sty maximum derived picture length",
        resolvedPx = DEFAULT_CANCEL_MAXIMUM_DERIVED_POINT_FACTOR * cancelPicturePointPx,
        range = node.commandRange,
    )?.let { cancelPicturePointPx } ?: 0f
    val geometry = cancelStrokeGeometry(
        content.width,
        clean.ascent,
        clean.descent,
        acceptedPicturePointPx,
    )
    val halfThickness = cancelLineThicknessPx / 2f
    val line = MathLineSegment(
        startX = geometry.startX,
        startY = geometry.startY,
        endX = geometry.endX,
        endY = geometry.endY,
        thickness = cancelLineThicknessPx,
    )
    val stroke = MathRulePlacement(
        left = minOf(geometry.startX, geometry.endX) - halfThickness,
        top = minOf(geometry.startY, geometry.endY) - halfThickness,
        right = maxOf(geometry.startX, geometry.endX) + halfThickness,
        bottom = maxOf(geometry.startY, geometry.endY) + halfThickness,
        sourceRange = node.commandRange,
        paintRole = MathRulePaintRole.Cancellation,
        lineSegment = line,
    )
    val painted = geometryExtents(
        width = content.width,
        glyphs = content.glyphs,
        rules = content.rules + stroke,
        range = node.range,
        constructionPaintGroups = content.constructionPaintGroups,
        hostTextRuns = content.hostTextRuns,
    )
    val strokeTop = minOf(geometry.startY, geometry.endY) - cancelLineThicknessPx / 2f
    val strokeBottom = maxOf(geometry.startY, geometry.endY) + cancelLineThicknessPx / 2f
    val ascent = max(content.ascent, -strokeTop)
    val descent = max(content.descent, strokeBottom)
    val box = painted.copy(
        width = content.width,
        ascent = ascent,
        descent = descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = max(clean.ascent, -strokeTop),
            descent = max(clean.descent, strokeBottom),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = clean.evidence + MathTeXCleanBoxEvidence.RuleGeometry +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    decision(
        "LatexCancelStroke",
        node.range,
        "commandRange" to node.commandRange,
        "style" to style,
        "contentWidthPx" to content.width,
        "contentAscentPx" to clean.ascent,
        "contentDescentPx" to clean.descent,
        "contentTotalHeightPx" to totalHeight,
        "shapeClass" to geometry.shapeClass,
        "slope" to "${geometry.slopeX}:${geometry.slopeY}",
        "lineHorizontalExtentPx" to (geometry.endX - geometry.startX),
        "lineVerticalExtentPx" to (geometry.startY - geometry.endY),
        "cancelLineThicknessPx" to cancelLineThicknessPx,
        "cancelPicturePointPx" to cancelPicturePointPx,
        "acceptedCancelPicturePointPx" to acceptedPicturePointPx,
        "cancelMinimumWidthPx" to (DEFAULT_CANCEL_MINIMUM_WIDTH_PT * acceptedPicturePointPx),
        "cancelMinimumTotalHeightPx" to (DEFAULT_CANCEL_MINIMUM_TOTAL_HEIGHT_PT * acceptedPicturePointPx),
        "cancelWideMinimumWidthPx" to (DEFAULT_CANCEL_WIDE_MINIMUM_WIDTH_PT * acceptedPicturePointPx),
        "cancelTallMinimumHeightPx" to (DEFAULT_CANCEL_TALL_MINIMUM_HEIGHT_PT * acceptedPicturePointPx),
        "cancelLineExtensionPx" to (DEFAULT_CANCEL_LINE_EXTENSION_PT * acceptedPicturePointPx),
        "classificationWidthPx" to geometry.classificationWidth,
        "classificationTotalHeightPx" to geometry.classificationHeight,
        "logicalWidthPx" to box.width,
        "logicalAscentPx" to box.ascent,
        "logicalDescentPx" to box.descent,
        "horizontalRoomPolicy" to "CancelPackageDefaultOverlapKeepsArgumentAdvance",
        "absoluteDimensionPolicy" to "MathLayoutOptionsResolvedPixels",
        "geometryPolicy" to "CancelSty2.2QuantizedPictureSlopeWithResolvedAbsoluteDimensions",
    )
    return LaidNode(
        node,
        box,
        MathAtomClass.Ordinary,
        0f,
        style,
        ScriptBaseKind.CompoundBox,
    )
}

private fun cancelStrokeGeometry(
    width: Float,
    ascent: Float,
    descent: Float,
    acceptedPicturePointPx: Float,
): CancelStrokeGeometry {
    val totalHeight = ascent + descent
    val minimumWidth = DEFAULT_CANCEL_MINIMUM_WIDTH_PT * acceptedPicturePointPx
    val minimumTotalHeight = DEFAULT_CANCEL_MINIMUM_TOTAL_HEIGHT_PT * acceptedPicturePointPx
    val wideMinimumWidth = DEFAULT_CANCEL_WIDE_MINIMUM_WIDTH_PT * acceptedPicturePointPx
    val tallMinimumHeight = DEFAULT_CANCEL_TALL_MINIMUM_HEIGHT_PT * acceptedPicturePointPx
    val lineExtension = DEFAULT_CANCEL_LINE_EXTENSION_PT * acceptedPicturePointPx
    val classificationWidth = max(width, minimumWidth)
    val classificationHeight = max(totalHeight, minimumTotalHeight)
    val centerX = width / 2f
    val centerY = (descent - ascent) / 2f
    val shapeClass: String
    val slopeX: Int
    val slopeY: Int
    val horizontalExtent: Float
    if (classificationHeight < classificationWidth) {
        shapeClass = "Wide"
        val preExtensionWidth = max(classificationWidth, wideMinimumWidth)
        val slopeCase = floor(classificationHeight * 5f / preExtensionWidth).toInt().coerceIn(0, 4)
        val slope = CANCEL_WIDE_SLOPES[slopeCase]
        slopeX = slope.first
        slopeY = slope.second
        horizontalExtent = preExtensionWidth + lineExtension
    } else {
        shapeClass = "Tall"
        val extendedHeight = max(classificationHeight, tallMinimumHeight) + lineExtension
        val slopeCase = floor(classificationWidth * 5f / extendedHeight).toInt().coerceIn(0, 4)
        val slope = CANCEL_TALL_SLOPES[slopeCase]
        slopeX = slope.first
        slopeY = slope.second
        val factor = CANCEL_TALL_WIDTH_FACTORS[slopeCase]
        horizontalExtent = factor * extendedHeight
    }
    val verticalExtent = horizontalExtent * slopeY / slopeX
    return CancelStrokeGeometry(
        startX = centerX - horizontalExtent / 2f,
        startY = centerY + verticalExtent / 2f,
        endX = centerX + horizontalExtent / 2f,
        endY = centerY - verticalExtent / 2f,
        slopeX = slopeX,
        slopeY = slopeY,
        shapeClass = shapeClass,
        classificationWidth = classificationWidth,
        classificationHeight = classificationHeight,
    )
}

internal fun MathLayoutPass.layoutGroup(
    node: MathGroup,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val horizontal = layoutList(node.body, style, alphabetOverride)
    return completeGroupLayout(node, style, horizontal)
}

private fun MathLayoutPass.completeGroupLayout(
    node: MathGroup,
    style: MathStyle,
    horizontal: MathLayoutPass.HorizontalLayout,
): LaidNode {
    decision(
        "TeXOrdSubMlist",
        node.range,
        "outerClass" to MathAtomClass.Ordinary,
        "innerClasses" to horizontal.items.joinToString(",") { it.atomClass.name },
        "innerStyles" to horizontal.items.joinToString(",") { it.laid.style.toString() },
        "innerBreaksExported" to false,
        "scriptBaseKind" to ScriptBaseKind.CompoundBox,
    )
    return horizontal.laid.copy(
        node = node,
        box = horizontal.laid.box.copy(range = node.range),
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = style,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutBoxed(
    node: MathBoxed,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val contentStyle = styleForLevel(MathStyleLevel.Display)
    val inset = fboxSeparationPx + fboxRuleThicknessPx
    val responsiveGroup = node.body as? MathGroup
    val responsiveWidth = displayWidthPx?.minus(2f * inset)?.coerceAtLeast(1f)
    val preparesResponsiveContent =
        formulaMode == MathMode.Display &&
        style.level == MathStyleLevel.Display &&
        softWrapDisplay &&
        responsiveWidth != null &&
        responsiveGroup != null
    val responsiveHorizontal = if (preparesResponsiveContent) {
        layoutList(responsiveGroup.body, contentStyle, alphabetOverride)
    } else {
        null
    }
    val ordinaryContent = if (responsiveHorizontal != null) {
        completeGroupLayout(checkNotNull(responsiveGroup), contentStyle, responsiveHorizontal)
            .completedTeXMathField().box
    } else {
        layoutNode(node.body, contentStyle, alphabetOverride).completedTeXMathField().box
    }
    val responsiveFragments = responsiveHorizontal?.let { inlineFragments(it) }.orEmpty()
    val breakResolution = if (
        responsiveHorizontal != null &&
        responsiveFragments.size > 1 &&
        ordinaryContent.visualWidth > checkNotNull(responsiveWidth)
    ) {
        resolveResponsiveDisplayBreak(
            fragments = responsiveFragments,
            lineMetrics = formulaLineMetrics(responsiveHorizontal.laid.box, contentStyle),
            maxWidthPx = responsiveWidth,
            defaultContinuationIndentPx = DISPLAY_CONTINUATION_INDENT_EM * fontSize(contentStyle),
            displayRowJotPx = DISPLAY_ROW_JOT_EM * fontSize(contentStyle),
            resourceLimits = resourceLimits,
        )
    } else {
        null
    }
    if (breakResolution != null) {
        diagnostics += breakResolution.layout.diagnostics.filterNot(diagnostics::contains)
    }
    val broken = breakResolution?.layout
    val content = if (
        broken != null &&
        (broken.lines.size > 1 || ordinaryContent.visualWidth > checkNotNull(responsiveWidth))
    ) {
        taggedDisplayBodyLastBaselineY = maxOf(
            taggedDisplayBodyLastBaselineY,
            broken.lines.last().baselineFromTop - broken.lines.first().baselineFromTop,
        )
        replayResponsiveDisplayBody(
            fragments = responsiveFragments,
            broken = broken,
            viewportWidthPx = checkNotNull(responsiveWidth),
            range = checkNotNull(responsiveGroup).body.range,
            // Pin only when a tagged completion is in flight to drain the pinned clauses; an
            // untagged boxed field keeps its clause in the frame, which scrolls as one unit.
            pinClausesToViewport = taggedDisplayReplayExpected,
        ).also { wrapped ->
            // Pending pinned clauses were recorded in the frame-interior viewport; shift them by
            // the frame inset so they anchor in the outer display viewport.
            if (taggedDisplayPendingPinnedClauses.isNotEmpty()) {
                val shifted = taggedDisplayPendingPinnedClauses.map { it.copy(logicalX = it.logicalX + inset) }
                taggedDisplayPendingPinnedClauses.clear()
                taggedDisplayPendingPinnedClauses += shifted
            }
            decision(
                "BoxedResponsiveDisplayLineBreak",
                node.range,
                *responsiveWrapDecisionDetails(checkNotNull(breakResolution), responsiveFragments, contentStyle),
                "availableOuterWidthPx" to displayWidthPx,
                "frameInsetEachSidePx" to inset,
                "contentViewportWidthPx" to responsiveWidth,
                "unbrokenVisualWidthPx" to ordinaryContent.visualWidth,
                "wrappedContentWidthPx" to wrapped.width,
                "framePolicy" to "SingleFrameAroundCompletedMultilineMathField",
                "policy" to "ResponsiveDisplayBoxedContentLeadingOperators",
            )
        }
    } else {
        ordinaryContent
    }
    val width = content.width + 2f * inset
    val ascent = content.ascent + inset
    val descent = content.descent + inset
    val shiftedContent = content.translated(inset, 0f)
    val rules = listOf(
        MathRulePlacement(0f, -ascent, width, -ascent + fboxRuleThicknessPx, node.commandRange),
        MathRulePlacement(0f, descent - fboxRuleThicknessPx, width, descent, node.commandRange),
        MathRulePlacement(0f, -ascent, fboxRuleThicknessPx, descent, node.commandRange),
        MathRulePlacement(width - fboxRuleThicknessPx, -ascent, width, descent, node.commandRange),
    )
    val painted = geometryExtents(
        width = width,
        glyphs = shiftedContent.glyphs,
        rules = shiftedContent.rules + rules,
        range = node.range,
        constructionPaintGroups = shiftedContent.constructionPaintGroups,
        hostTextRuns = shiftedContent.hostTextRuns,
    )
    val box = painted.copy(
        ascent = ascent,
        descent = descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = content.texCleanBoxMetrics.ascent + inset,
            descent = content.texCleanBoxMetrics.descent + inset,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = content.texCleanBoxMetrics.evidence +
                MathTeXCleanBoxEvidence.CompletedChildBox + MathTeXCleanBoxEvidence.RuleGeometry,
        ),
    )
    decision(
        "AmsmathBoxedNoad",
        node.range,
        "commandRange" to node.commandRange,
        "terminalRowSeparatorRange" to node.terminalRowSeparator?.separatorRange,
        "terminalRowSeparatorPolicy" to if (node.terminalRowSeparator == null) {
            "None"
        } else {
            "IgnoreEmptyFinalMarkdownDisplayRowInsideOutermostBox"
        },
        "outerStyle" to style,
        "contentStyle" to contentStyle,
        "fboxSeparationPx" to fboxSeparationPx,
        "fboxRuleThicknessPx" to fboxRuleThicknessPx,
        "contentWidthPx" to content.width,
        "contentAscentPx" to content.ascent,
        "contentDescentPx" to content.descent,
        "logicalWidthPx" to width,
        "logicalAscentPx" to ascent,
        "logicalDescentPx" to descent,
        "atomClass" to MathAtomClass.Ordinary,
        "scriptBaseKind" to ScriptBaseKind.CompoundBox,
        "policy" to "AmsmathFboxDisplayStyleWithTeXFboxsepAndFboxrule",
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

internal fun MathLayoutPass.layoutBbox(
    node: MathBbox,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val content = layoutNode(node.body, style, alphabetOverride).completedTeXMathField().box
    val styleSizePx = fontSize(style)
    val paddingPx = node.options.padding?.let { resolveBboxDimension(it, styleSizePx) } ?: 0f
    val border = node.options.border
    val borderWidthPx = if (border?.style == MathBboxBorderStyle.Solid) {
        resolveBboxDimension(border.width, styleSizePx)
    } else {
        0f
    }
    val inset = paddingPx + borderWidthPx
    val width = content.width + 2f * inset
    val ascent = content.ascent + inset
    val descent = content.descent + inset
    val shiftedContent = content.translated(inset, 0f)
    val decorations = buildList {
        node.options.background?.let { background ->
            add(
                MathRulePlacement(
                    left = 0f,
                    top = -ascent,
                    right = width,
                    bottom = descent,
                    sourceRange = background.range,
                    paintColor = background.color,
                    paintLayer = MathPaintLayer.Background,
                    paintRole = MathRulePaintRole.BackgroundFill,
                ),
            )
        }
        if (borderWidthPx > 0f && border != null) {
            val color = border.color?.color
            add(
                MathRulePlacement(
                    0f, -ascent, width, -ascent + borderWidthPx, border.range,
                    paintColor = color,
                    paintRole = MathRulePaintRole.Border,
                ),
            )
            add(
                MathRulePlacement(
                    0f, descent - borderWidthPx, width, descent, border.range,
                    paintColor = color,
                    paintRole = MathRulePaintRole.Border,
                ),
            )
            add(
                MathRulePlacement(
                    0f, -ascent, borderWidthPx, descent, border.range,
                    paintColor = color,
                    paintRole = MathRulePaintRole.Border,
                ),
            )
            add(
                MathRulePlacement(
                    width - borderWidthPx, -ascent, width, descent, border.range,
                    paintColor = color,
                    paintRole = MathRulePaintRole.Border,
                ),
            )
        }
    }
    val painted = geometryExtents(
        width = width,
        glyphs = shiftedContent.glyphs,
        rules = decorations + shiftedContent.rules,
        range = node.range,
        constructionPaintGroups = shiftedContent.constructionPaintGroups,
        hostTextRuns = shiftedContent.hostTextRuns,
    )
    val extraEvidence = if (decorations.isEmpty()) emptySet() else setOf(MathTeXCleanBoxEvidence.RuleGeometry)
    val box = painted.copy(
        ascent = ascent,
        descent = descent,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = content.texCleanBoxMetrics.ascent + inset,
            descent = content.texCleanBoxMetrics.descent + inset,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = content.texCleanBoxMetrics.evidence +
                MathTeXCleanBoxEvidence.CompletedChildBox + extraEvidence,
        ),
    )
    decision(
        "MathJaxBboxExtension",
        node.range,
        "commandRange" to node.commandRange,
        "optionsRange" to node.optionsRange,
        "outerStyle" to style,
        "styleFontSizePx" to styleSizePx,
        "paddingSource" to node.options.padding?.sourceText,
        "paddingPx" to paddingPx,
        "backgroundSource" to node.options.background?.sourceName,
        "backgroundArgb" to node.options.background?.color?.argb?.toUInt()?.toString(16),
        "borderWidthSource" to border?.width?.sourceText,
        "borderWidthPx" to borderWidthPx,
        "borderStyle" to border?.style,
        "borderColorSource" to border?.color?.sourceName,
        "contentWidthPx" to content.width,
        "contentAscentPx" to content.ascent,
        "contentDescentPx" to content.descent,
        "logicalWidthPx" to width,
        "logicalAscentPx" to ascent,
        "logicalDescentPx" to descent,
        "paintOrder" to "BackgroundFillThenMathThenForegroundBorder",
        "atomClass" to MathAtomClass.Ordinary,
        "scriptBaseKind" to ScriptBaseKind.CompoundBox,
        "policy" to "MathJaxBboxMpaddedAndSafeBorderSubset",
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

internal fun MathLayoutPass.layoutRuleBox(node: MathRuleBox, style: MathStyle): LaidNode {
    val em = fontSize(style)
    val width = resolveBboxDimension(node.width, em).coerceAtLeast(0f)
    val height = resolveBboxDimension(node.height, em).coerceAtLeast(0f)
    val raise = node.raise?.let { resolveBboxDimension(it, em) } ?: 0f
    val top = -(raise + height)
    val bottom = -raise
    val rule = MathRulePlacement(
        left = 0f,
        top = top,
        right = width,
        bottom = bottom,
        sourceRange = node.range,
    )
    val ascent = (-top).coerceAtLeast(0f)
    val descent = bottom.coerceAtLeast(0f)
    val box = MathBox(
        width = width,
        ascent = ascent,
        descent = descent,
        inkBounds = MathRect(0f, top, width, bottom),
        glyphs = emptyList(),
        rules = listOf(rule),
        range = node.range,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = ascent,
            descent = descent,
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = setOf(MathTeXCleanBoxEvidence.RuleGeometry),
        ),
    )
    decision(
        "LatexRuleBox",
        node.range,
        "widthPx" to width,
        "heightPx" to height,
        "raisePx" to raise,
        "dimensionPolicy" to "MathJaxBboxDimension",
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

internal fun MathLayoutPass.layoutLap(
    node: MathLap,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val content = layoutNode(node.body, style, alphabetOverride).completedTeXMathField().box
    val shifted = if (node.kind == MathLapKind.Left) content.translated(-content.width, 0f) else content
    val box = shifted.copy(width = 0f, range = node.range)
    decision(
        "LatexLapBox",
        node.range,
        "kind" to node.kind,
        "contentWidthPx" to content.width,
        "widthPolicy" to "ZeroLogicalWidthKeepsInkOverhang",
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

private fun MathLayoutPass.resolveBboxDimension(dimension: MathBboxDimension, emSizePx: Float): Float {
    val unvalidatedPixels = when (dimension.unit) {
        MathBboxDimensionUnit.Point -> dimension.value * CSS_PIXELS_PER_INCH / 72f
        MathBboxDimensionUnit.Em -> dimension.value * emSizePx
        MathBboxDimensionUnit.Ex -> {
            val xHeight = glyphSource.mathFont.xHeight
            if (xHeight == null) {
                diagnostics += MathDiagnostic(
                    DiagnosticCode.MissingBboxXHeight,
                    "The selected math font has no OS/2 sxHeight required by bbox ex units",
                    dimension.range,
                )
                0f
            } else {
                dimension.value * glyphSource.mathFont.scaleDesignUnits(xHeight, emSizePx)
            }
        }
        MathBboxDimensionUnit.Mu -> dimension.value * emSizePx / 18f
        MathBboxDimensionUnit.Pixel -> dimension.value
        MathBboxDimensionUnit.Inch -> dimension.value * CSS_PIXELS_PER_INCH
        MathBboxDimensionUnit.Centimeter -> dimension.value * CSS_PIXELS_PER_INCH / CENTIMETERS_PER_INCH
        MathBboxDimensionUnit.Millimeter -> dimension.value * CSS_PIXELS_PER_INCH / MILLIMETERS_PER_INCH
    }
    decision(
        "MathJaxBboxDimension",
        dimension.range,
        "source" to dimension.sourceText,
        "value" to dimension.value,
        "unit" to dimension.unit,
        "styleFontSizePx" to emSizePx,
        "fontXHeightDesignUnits" to glyphSource.mathFont.xHeight,
        "resolvedPx" to unvalidatedPixels,
        "policy" to "MathJaxBboxCss96DpiEmMuAndOpenTypeOs2XHeight",
    )
    return validatedResolvedDimension(
        sourceText = dimension.sourceText,
        resolvedPx = unvalidatedPixels,
        range = dimension.range,
    ) ?: 0f
}
