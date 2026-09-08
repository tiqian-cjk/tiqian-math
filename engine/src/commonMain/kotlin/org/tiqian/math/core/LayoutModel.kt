package org.tiqian.math.core

data class MathRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun translated(dx: Float, dy: Float): MathRect = MathRect(
        left + dx,
        top + dy,
        right + dx,
        bottom + dy,
    )
}

/** Renderer-independent sRGB paint evidence retained by the immutable layout result. */
data class MathPaintColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int = 255,
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    }

    val argb: Int get() =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /** Applies the host formula alpha without replacing this explicit TeX color's RGB. */
    fun modulatedArgb(formulaArgb: Int): Int {
        val formulaAlpha = formulaArgb ushr 24 and 0xff
        val resolvedAlpha = (alpha * formulaAlpha + 127) / 255
        return (resolvedAlpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

data class MathGlyphPlacement(
    val glyphId: UShort,
    val x: Float,
    /** Baseline position relative to the formula baseline; down is positive. */
    val baselineY: Float,
    val advance: Float,
    val inkBounds: MathRect,
    val fontSizePx: Float,
    val sourceRange: SourceRange,
    val style: MathStyle,
    /** Non-null when this glyph participates in one semantic outline construction. */
    val constructionGroupId: Int? = null,
    val faceId: MathFaceId = MathFaceId.LegacySingleFace,
    val fontClass: MathFontClass? = MathFontClass.Serif,
    val requestedWeight: MathFontWeight = MathFontWeight.Regular,
    val resolvedWeight: MathFontWeight = MathFontWeight.Regular,
    /** Present only for MATH-family glyph selection. */
    val fallbackReason: MathFontFallbackReason? = MathFontFallbackReason.RequestedFace,
    /** Present only for glyphs measured by a host text provider. */
    val hostTextDecision: MathHostTextFaceDecision? = null,
    /** Null inherits the host formula color; non-null is an explicit TeX color declaration. */
    val paintColor: MathPaintColor? = null,
)

/**
 * One host-shaped text box. Its internal bidi, fallback and glyph ownership stay with the host;
 * math layout owns only the TeX box geometry and the placement of the replayed result.
 */
data class MathHostTextPlacement(
    val runId: MathHostTextRunId,
    val x: Float,
    /** Baseline position relative to the formula baseline; down is positive. */
    val baselineY: Float,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val inkBounds: MathRect,
    val sourceRange: SourceRange,
    val requestedWeight: MathFontWeight,
    val paintColor: MathPaintColor? = null,
)

data class MathRulePlacement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceRange: SourceRange,
    /** Non-null when this rule must be painted with its attached construction outline. */
    val constructionGroupId: Int? = null,
    val paintColor: MathPaintColor? = null,
    val paintLayer: MathPaintLayer = MathPaintLayer.Foreground,
    val paintRole: MathRulePaintRole = MathRulePaintRole.MathRule,
    /** Non-null replaces rectangle fill with one replayable stroked line in the same bounds. */
    val lineSegment: MathLineSegment? = null,
)

enum class MathPaintLayer { Background, Foreground }

enum class MathRulePaintRole { MathRule, BackgroundFill, Border, Cancellation }

data class MathLineSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val thickness: Float,
)

enum class MathConstructionPaintKind {
    Radical,
    Delimiter,
    Accent,
    ExtensibleArrow,
}

enum class MathConstructionShapeKind {
    BaseGlyph,
    Variant,
    Assembly,
}

enum class MathConstructionOutlinePolicy {
    /** Rendering fails explicitly if any required glyph outline cannot be obtained. */
    RequireOutlineUnion,
}

/** Semantic paint ownership retained beyond the otherwise flat glyph/rule replay lists. */
data class MathConstructionPaintGroup(
    val id: Int,
    val kind: MathConstructionPaintKind,
    val shapeKind: MathConstructionShapeKind,
    val sourceRange: SourceRange,
    val outlinePolicy: MathConstructionOutlinePolicy,
    val faceId: MathFaceId = MathFaceId.LegacySingleFace,
    val paintColor: MathPaintColor? = null,
)

enum class MathTeXCleanBoxPolicy {
    /** Height/depth are the exact available glyph-outline and rule union. */
    GlyphOutlineUnion,

    /** Height/depth are the completed TeX box assembled from positioned child boxes. */
    CompletedLayoutBox,
}

enum class MathTeXCleanBoxEvidence {
    GlyphOutline,
    FontReportedGlyphBounds,
    HostTextRunMetrics,
    RuleGeometry,
    CompletedChildBox,
    Empty,
}

/**
 * Replayable TeX `clean_box` height/depth, kept independently from host line reserve and
 * painted ink. Composite noads carry their completed child-box metrics instead of being
 * reverse-engineered later from flattened glyphs.
 */
data class MathTeXCleanBoxMetrics(
    val ascent: Float,
    val descent: Float,
    val policy: MathTeXCleanBoxPolicy,
    val evidence: Set<MathTeXCleanBoxEvidence>,
) {
    init {
        require(ascent >= 0f) { "clean-box ascent must not be negative" }
        require(descent >= 0f) { "clean-box descent must not be negative" }
        require(evidence.isNotEmpty()) { "clean-box evidence must not be empty" }
    }

    val height: Float get() = ascent + descent
}

data class MathBox(
    /** TeX logical advance. Ink may overhang either horizontal edge. */
    val width: Float,
    /** TeX box ascent/descent used by recursive math layout; ink may occupy a smaller extent. */
    val ascent: Float,
    val descent: Float,
    val inkBounds: MathRect,
    val glyphs: List<MathGlyphPlacement>,
    val rules: List<MathRulePlacement>,
    val range: SourceRange,
    val constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
    val texCleanBoxMetrics: MathTeXCleanBoxMetrics = MathTeXCleanBoxMetrics(
        ascent = ascent,
        descent = descent,
        policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
        evidence = setOf(MathTeXCleanBoxEvidence.FontReportedGlyphBounds),
    ),
    val hostTextRuns: List<MathHostTextPlacement> = emptyList(),
) {
    init {
        require(width >= 0f) { "box width must not be negative" }
        require(ascent >= 0f) { "box ascent must not be negative" }
        require(descent >= 0f) { "box descent must not be negative" }
    }

    val height: Float get() = ascent + descent
    val visualLeft: Float get() = minOf(0f, inkBounds.left)
    val visualRight: Float get() = maxOf(width, inkBounds.right)
    val visualWidth: Float get() = visualRight - visualLeft
}

enum class MathBreakKind {
    PunctuationTrailing,
    BinaryOperatorTrailing,
    RelationTrailing,
    BinaryOperatorLeading,
    RelationLeading,
}

/** Named line-breaking dialect; inline TeX and responsive display intentionally differ. */
enum class MathLineBreakPolicy {
    /** Existing Markdown-inline contract: a visible binary/relation operator ends the line. */
    InlineTrailingOperators,

    /** Electronic-display extension: break before explicit binary/relation operators. */
    ResponsiveDisplayLeadingOperators,
}

/** Semantic continuation anchor selected without inspecting rendered pixels. */
enum class MathContinuationAlignment {
    None,
    FirstRelation,
    FirstBinaryOperator,
}

enum class MathGlueKind {
    None,
    Thin,
    Medium,
    Thick,
}

enum class MathAdjustmentPriority(val order: Int) {
    Punctuation(0),
    Relation(1),
    BinaryOperator(2),
    Other(3),
    None(Int.MAX_VALUE),
}

/** Host policy for resolving the finite TeX glue carried by inline fragments. */
enum class MathLineAdjustmentMode {
    /** Preserve natural glue unless shrinking is required to fit the requested width. */
    Fit,

    /** Also stretch non-final lines, exhausting lower [MathAdjustmentPriority.order] first. */
    Justify,
}

data class MathGlueAdjustment(
    val kind: MathGlueKind,
    val naturalPx: Float,
    val minimumPx: Float,
    val maximumPx: Float,
    val shrinkPx: Float,
    val stretchPx: Float,
    val priority: MathAdjustmentPriority,
) {
    init {
        require(minimumPx <= naturalPx && naturalPx <= maximumPx)
        require(shrinkPx == naturalPx - minimumPx)
        require(stretchPx == maximumPx - naturalPx)
    }

    companion object {
        val Zero = MathGlueAdjustment(
            MathGlueKind.None,
            0f,
            0f,
            0f,
            0f,
            0f,
            MathAdjustmentPriority.None,
        )
    }
}

data class MathBreakOpportunity(
    val afterFragmentIndex: Int,
    val sourceOffset: Int,
    val kind: MathBreakKind,
    val discardedTrailingGlue: MathGlueAdjustment,
    val priority: MathAdjustmentPriority,
    val operatorStaysOnPreviousLine: Boolean = true,
)

data class MathInlineFragment(
    val index: Int,
    val sourceRange: SourceRange,
    /** TeX atom class of this fragment, so a consumer can bind delimiters/punctuation into groups. */
    val atomClass: MathAtomClass,
    /** Visible geometry only: no leading or trailing mathematical glue. */
    val box: MathBox,
    /** Fixed signed TeX kern applied before this fragment; it is never stretched or discarded. */
    val leadingKernPx: Float,
    /** Fixed italic correction owned by this character noad or compatible Ord run. */
    val trailingItalicCorrectionPx: Float,
    /** Named TeX glue and host adjustment capacity following this fragment. */
    val trailingGlue: MathGlueAdjustment,
    val breakAfter: MathBreakOpportunity?,
) {
    val trailingAdvancePx: Float get() = trailingItalicCorrectionPx + trailingGlue.naturalPx
}

data class MathLineFragmentPlacement(
    val fragmentIndex: Int,
    val x: Float,
    val resolvedTrailingAdvancePx: Float,
)

data class MathBrokenLine(
    val fragments: List<MathLineFragmentPlacement>,
    /** Sum of logical advances and resolved internal glue. */
    val logicalWidth: Float,
    val inkBounds: MathRect,
    val visualLeft: Float,
    val visualRight: Float,
    val width: Float,
    val inkAscent: Float,
    val inkDescent: Float,
    val ascent: Float,
    val descent: Float,
    val baselineFromTop: Float,
    /** True when a retained unbroken line exceeds the host constraint. */
    val unbreakableOverflow: Boolean,
    /** Logical x in the display viewport. Responsive display lines consume this directly. */
    val horizontalOffsetPx: Float = 0f,
    /** Boundary that starts this line, absent for the first line. */
    val breakKind: MathBreakKind? = null,
    /** True for a clause continuation (depth-0 punctuation break), e.g. a domain condition. */
    val isClause: Boolean = false,
    /**
     * Engine-decided PinnedClauseLikeTag outcome: this clause line anchors to the viewport while
     * the rest of the block scrolls. Renderers consume this flag; they never re-derive it.
     */
    val pinned: Boolean = false,
)

data class MathBrokenLayout(
    val lines: List<MathBrokenLine>,
    val width: Float,
    val height: Float,
    val policy: MathLineBreakPolicy = MathLineBreakPolicy.InlineTrailingOperators,
    val targetWidthPx: Float = width,
    val continuationAlignment: MathContinuationAlignment = MathContinuationAlignment.None,
    /** Shared painted operator anchor actually used by every continuation line. */
    val continuationAnchorPx: Float = 0f,
    /** Diagnostics produced by line breaking itself, separate from formula layout diagnostics. */
    val diagnostics: List<MathDiagnostic> = emptyList(),
)

data class MathFormulaLineMetrics(
    val fontAscentPx: Float,
    val fontDescentPx: Float,
    val fontLineGapPx: Float,
    val mathLeadingPx: Float,
    val inkAscentPx: Float,
    val inkDescentPx: Float,
    val logicalAscentPx: Float,
    val logicalDescentPx: Float,
) {
    val logicalHeightPx: Float get() = logicalAscentPx + logicalDescentPx

    fun forInk(inkAscent: Float, inkDescent: Float): MathFormulaLineMetrics = copy(
        inkAscentPx = inkAscent,
        inkDescentPx = inkDescent,
        logicalAscentPx = maxOf(fontAscentPx + fontLineGapPx, inkAscent + mathLeadingPx),
        logicalDescentPx = maxOf(fontDescentPx, inkDescent),
    )
}

data class MathLayoutDecision(
    val name: String,
    val range: SourceRange,
    val details: Map<String, String>,
)

/** Semantic placement of an explicit equation tag inside a completed display formula. */
enum class MathEquationTagPlacement {
    SameLineRight,
    ShiftedBelowRight,
    CenteredBesideMultiline,
}

/**
 * A clause line (domain condition) pinned to the display viewport like a tag: when the body
 * scrolls as one block, the clause stays anchored at the viewport's right edge instead of
 * traveling with the scrolled content.
 */
data class MathPinnedClauseReplay(
    val box: MathBox,
    val sourceRange: SourceRange,
    val logicalX: Float,
    val baselineY: Float,
)

/** One independently replayable tag anchored to the display viewport, not to scroll content. */
data class MathEquationTagReplay(
    val box: MathBox,
    val sourceRange: SourceRange,
    val logicalX: Float,
    val baselineY: Float,
    val placement: MathEquationTagPlacement,
)

/**
 * Separates completed body and tag geometry into independently replayable paint regions.
 * A frontend can clip or move the body without reconstructing tag placement.
 */
data class MathTaggedDisplayReplay(
    val body: MathBox,
    val bodyLogicalX: Float,
    val viewportWidthPx: Float,
    val tags: List<MathEquationTagReplay>,
    /** Clause lines pinned to the viewport; empty when every line travels with the body. */
    val pinnedClauses: List<MathPinnedClauseReplay> = emptyList(),
) {
    init {
        require(viewportWidthPx >= 0f)
        require(tags.isNotEmpty())
    }
}

/**
 * Builds the human-readable layout dump on first access. Structured [MathLayoutDecision] data
 * remains eager and authoritative; production callers that never inspect [MathLayoutResult.debugDump]
 * avoid the large diagnostic string and its transient formatting allocations.
 */
fun interface MathLayoutDebugDumpRenderer {
    fun render(result: MathLayoutResult): String
}

data class MathLayoutResult(
    val source: String,
    val mode: MathMode,
    val initialStyle: MathStyle,
    val box: MathBox,
    val fragments: List<MathInlineFragment>,
    val breakOpportunities: List<MathBreakOpportunity>,
    val diagnostics: List<MathDiagnostic>,
    val lineMetrics: MathFormulaLineMetrics,
    val decisions: List<MathLayoutDecision>,
    val debugDumpRenderer: MathLayoutDebugDumpRenderer,
    val taggedDisplayReplay: MathTaggedDisplayReplay? = null,
    /** Formula text-style em in layout pixels; script styles derive from this value. */
    val fontSizePx: Float,
    /** Formula-wide safety policy reused by downstream operations such as line breaking. */
    val resourceLimits: MathResourceLimits,
) {
    /** Human-readable diagnostic projection, memoized only when a caller requests it. */
    val debugDump: String by lazy { debugDumpRenderer.render(this) }
}
