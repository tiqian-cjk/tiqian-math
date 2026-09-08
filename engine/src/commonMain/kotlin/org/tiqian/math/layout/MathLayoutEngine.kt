package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.MathDeviceAdjustment
import org.tiqian.math.font.opentype.MathVerticalConstruction
import org.tiqian.math.font.opentype.OpenTypeMathConstants
import org.tiqian.math.font.opentype.OpenTypeMathException
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.parser.MathFormulaParser
import org.tiqian.math.parser.MathParser
import kotlin.math.max

data class MathLayoutOptions(
    val mode: MathMode = MathMode.Inline,
    val fontSizePx: Float = 24f,
    /** Primarily for embedding in an existing TeX-style context; null derives from [mode]. */
    val initialStyle: MathStyle? = null,
    /**
     * Formula-scoped equivalent of TeX's `\nulldelimiterspace`; fixed across math styles.
     * Null keeps the plain-TeX 1.2pt-to-10pt proportion at the formula's text size.
     */
    val nullDelimiterSpacePx: Float? = null,
    /**
     * Formula-scoped equivalent of TeX's `\scriptspace`; fixed across math styles.
     * Null keeps plain TeX/XeTeX's 0.5pt value. The OpenType MATH `SpaceAfterScript`
     * constant remains available to non-TeX dialects, but is not XeTeX's default.
     */
    val scriptSpacePx: Float? = null,
    /** Plain-TeX `\delimiterfactor`; 901 means roughly 90.1% of the axis-symmetric span. */
    val delimiterFactor: Int = 901,
    /** Formula-scoped `\delimitershortfall` in pixels; null keeps the plain-TeX 5pt/10pt ratio. */
    val delimiterShortfallPx: Float? = null,
    /** BCP-47 locale forwarded only to host-owned upright text atoms. */
    val textLocale: String? = null,
    /** TeX `\arraycolsep` for each side of a matrix/array cell; null is 0.5em. */
    val arrayColumnSeparationPx: Float? = null,
    /** LaTeX `\fboxsep`; null is the standard 3pt converted to CSS pixels. */
    val fboxSeparationPx: Float? = null,
    /** LaTeX `\fboxrule`; null is the standard 0.4pt converted to CSS pixels. */
    val fboxRuleThicknessPx: Float? = null,
    /** LaTeX `\arrayrulewidth`; null is the standard 0.4pt converted to CSS pixels. */
    val arrayRuleThicknessPx: Float? = null,
    /** cancel.sty's default `\thinlines` width; null is LaTeX's standard 0.4pt. */
    val cancelLineThicknessPx: Float? = null,
    /** Completed display-row width used to right-align explicit amsmath equation tags. */
    val displayWidthPx: Float? = null,
    /** Whether a completed display equation may break at the engine's legal math breakpoints. */
    val softWrapDisplay: Boolean = false,
    /** Pixel length of cancel.sty's 1pt picture unit; null is 96/72.27. */
    val cancelPicturePointPx: Float? = null,
) {
    init {
        require(fontSizePx.isFinite() && fontSizePx > 0f) { "math font size must be finite and positive" }
        require(nullDelimiterSpacePx == null || nullDelimiterSpacePx.isFinite() && nullDelimiterSpacePx >= 0f) {
            "null delimiter space must be finite and non-negative"
        }
        require(scriptSpacePx == null || scriptSpacePx.isFinite() && scriptSpacePx >= 0f) {
            "script space must be finite and non-negative"
        }
        require(delimiterFactor > 0) { "delimiter factor must be positive" }
        require(delimiterShortfallPx == null || delimiterShortfallPx.isFinite() && delimiterShortfallPx >= 0f) {
            "delimiter shortfall must be finite and non-negative"
        }
        require(arrayColumnSeparationPx == null || arrayColumnSeparationPx.isFinite() && arrayColumnSeparationPx >= 0f) {
            "array column separation must be finite and non-negative"
        }
        require(fboxSeparationPx == null || fboxSeparationPx.isFinite() && fboxSeparationPx >= 0f) {
            "fbox separation must be finite and non-negative"
        }
        require(fboxRuleThicknessPx == null || fboxRuleThicknessPx.isFinite() && fboxRuleThicknessPx >= 0f) {
            "fbox rule thickness must be finite and non-negative"
        }
        require(arrayRuleThicknessPx == null || arrayRuleThicknessPx.isFinite() && arrayRuleThicknessPx >= 0f) {
            "array rule thickness must be finite and non-negative"
        }
        require(cancelLineThicknessPx == null || cancelLineThicknessPx.isFinite() && cancelLineThicknessPx >= 0f) {
            "cancel line thickness must be finite and non-negative"
        }
        require(displayWidthPx == null || displayWidthPx.isFinite() && displayWidthPx > 0f) {
            "display width must be finite and positive"
        }
        require(cancelPicturePointPx == null || cancelPicturePointPx.isFinite() && cancelPicturePointPx > 0f) {
            "cancel picture point size must be finite and positive"
        }
    }
}

class MathPreparedFormula internal constructor(
    val parseResult: MathParseResult,
    internal val resourceLimits: MathResourceLimits,
) {
    val source: String get() = parseResult.source
    val diagnostics: List<MathDiagnostic> get() = parseResult.diagnostics
}

/** Parse-once production pipeline; analysis callers may still use [MathLayoutEngine.layout]. */
interface MathFormulaProductionPipeline {
    fun prepare(source: String): MathPreparedFormula

    fun layout(
        prepared: MathPreparedFormula,
        options: MathLayoutOptions = MathLayoutOptions(),
    ): MathLayoutResult
}

class MathLayoutEngine(
    internal val glyphSource: MathFontFace,
    private val parser: MathFormulaParser = MathParser(),
    internal val textRunProvider: MathTextRunProvider? = null,
    val resourceLimits: MathResourceLimits = MathResourceLimits.Default,
) : MathFormulaProductionPipeline {
    override fun prepare(source: String): MathPreparedFormula {
        if (source.length > resourceLimits.maximumSourceLength) {
            return MathPreparedFormula(
                parseResult = MathParseResult(
                    source = source,
                    root = MathList(emptyList(), SourceRange.Empty),
                    diagnostics = listOf(
                        mathResourceLimitDiagnostic(
                            code = DiagnosticCode.SourceLengthLimitExceeded,
                            resource = "sourceLength",
                            actual = source.length,
                            limit = resourceLimits.maximumSourceLength,
                            range = SourceRange(0, source.length),
                        ),
                    ),
                ),
                resourceLimits = resourceLimits,
            )
        }
        val parsed = parser.parse(source, resourceLimits)
        val resourceDiagnostic = inspectMathAstResources(parsed.root, resourceLimits)
        return MathPreparedFormula(
            parseResult = if (resourceDiagnostic == null) {
                parsed
            } else {
                parsed.copy(
                    root = MathList(emptyList(), SourceRange.Empty),
                    diagnostics = (parsed.diagnostics + resourceDiagnostic).distinct(),
                )
            },
            resourceLimits = resourceLimits,
        )
    }

    fun layout(source: String, options: MathLayoutOptions = MathLayoutOptions()): MathLayoutResult =
        layout(prepare(source), options)

    override fun layout(
        prepared: MathPreparedFormula,
        options: MathLayoutOptions,
    ): MathLayoutResult {
        // A prepared formula is bounded. Reparse on deliberate cross-engine reuse rather
        // than allowing a wider preparation budget to bypass this engine's token limits.
        val bounded = if (prepared.resourceLimits == resourceLimits) prepared else prepare(prepared.source)
        return MathLayoutPass(glyphSource, textRunProvider, resourceLimits)
            .layout(bounded.parseResult, options)
    }
}

/** Per-call mutable state; a public engine can safely serve concurrent layout requests. */
internal class MathLayoutPass(
    internal val glyphSource: MathFontFace,
    internal val textRunProvider: MathTextRunProvider?,
    internal val resourceLimits: MathResourceLimits,
) {
    internal val diagnostics = mutableListOf<MathDiagnostic>()
    internal val decisions = mutableListOf<MathLayoutDecision>()
    private var generatedExtenderCount = 0L
    internal var baseFontSizePx: Float = 24f
    internal var nullDelimiterSpacePx: Float = 2.88f
    internal var scriptSpacePx: Float = DEFAULT_SCRIPT_SPACE_PT * TEX_POINT_TO_PX
    internal var scriptSpacePolicy: String = "PlainTeXXeTeXScriptSpace"
    internal var delimiterFactor: Int = 901
    internal var delimiterShortfallPx: Float = 12f
    internal var textLocale: String? = null
    internal var explicitArrayColumnSeparationPx: Float? = null
    internal var fboxSeparationPx: Float = DEFAULT_FBOX_SEPARATION_PT * TEX_POINT_TO_PX
    internal var fboxRuleThicknessPx: Float = DEFAULT_FBOX_RULE_THICKNESS_PT * TEX_POINT_TO_PX
    internal var arrayRuleThicknessPx: Float = DEFAULT_ARRAY_RULE_THICKNESS_PT * TEX_POINT_TO_PX
    internal var cancelPicturePointPx: Float = TEX_POINT_TO_PX
    internal var cancelLineThicknessPx: Float = DEFAULT_CANCEL_LINE_THICKNESS_PT * TEX_POINT_TO_PX
    internal var formulaMode: MathMode = MathMode.Inline
    internal var displayWidthPx: Float? = null
    internal var softWrapDisplay: Boolean = false
    internal var taggedDisplayReplay: MathTaggedDisplayReplay? = null
    internal var taggedDisplayBodyLastBaselineY: Float = 0f

    /**
     * LatexSizeDeclaration: absolute em multiplier active for the subtree being laid; every size
     * (glyphs, glue, rules, host text) resolves through [fontSize], so the whole subtree scales.
     */
    internal var userSizeScale: Float = 1f

    /**
     * Clause lines lifted out of a scrolled body, waiting for the tagged completion that anchors
     * them. Producers may only run while [taggedDisplayReplayExpected] is set by the completion
     * that will drain the list — a produced-but-undrained clause would silently vanish from
     * paint. The pass is per-layout, so the list never crosses formulas.
     */
    internal val taggedDisplayPendingPinnedClauses = mutableListOf<MathPinnedClauseReplay>()
    internal var taggedDisplayReplayExpected: Boolean = false

    /**
     * Runs a measurement-only layout and rolls back ordinary explanation/replay side effects.
     * Resource consumption is intentionally monotonic across probes: candidate work was really
     * materialized, so it consumes the formula-wide safety budget and any resulting resource
     * diagnostic survives the rollback.
     */
    internal fun <T> probeLayout(block: () -> T): T {
        val decisionMark = decisions.size
        val diagnosticMark = diagnostics.size
        val paintGroupMark = nextConstructionPaintGroupId
        val lastBaselineMark = taggedDisplayBodyLastBaselineY
        val replayMark = taggedDisplayReplay
        val pinnedMark = taggedDisplayPendingPinnedClauses.size
        try {
            return block()
        } finally {
            val resourceDiagnostics = diagnostics
                .subList(diagnosticMark, diagnostics.size)
                .filter { it.isLayoutResourceLimitDiagnostic() }
                .distinct()
            decisions.subList(decisionMark, decisions.size).clear()
            diagnostics.subList(diagnosticMark, diagnostics.size).clear()
            diagnostics += resourceDiagnostics.filterNot(diagnostics::contains)
            nextConstructionPaintGroupId = paintGroupMark
            taggedDisplayBodyLastBaselineY = lastBaselineMark
            taggedDisplayReplay = replayMark
            taggedDisplayPendingPinnedClauses.subList(pinnedMark, taggedDisplayPendingPinnedClauses.size).clear()
        }
    }
    internal var nextConstructionPaintGroupId: Int = 1
    private val outlineGlyphMeasurements = mutableMapOf<OutlineGlyphMeasurementKey, MeasuredMathRun>()

    /**
     * Keep legacy/decorated single-face adapters virtual while allowing a real family to resolve
     * a non-primary face. Kotlin interface delegation otherwise forwards the new family methods
     * past test/host wrappers that intentionally override the primary face's evidence.
     */
    internal fun mathFontForFace(faceId: MathFaceId): OpenTypeMathFont =
        if (faceId == glyphSource.faceId) glyphSource.mathFont else glyphSource.mathFontFor(faceId)

    internal fun mathFontForFaceOrNull(faceId: MathFaceId): OpenTypeMathFont? =
        if (faceId == glyphSource.faceId) glyphSource.mathFont else glyphSource.mathFontForOrNull(faceId)

    internal fun limitsForNextConstruction(): MathResourceLimits {
        val remaining = (resourceLimits.maximumExtenderCount - generatedExtenderCount)
            .coerceAtLeast(0L)
            .toInt()
        return resourceLimits.copy(maximumExtenderCount = remaining)
    }

    internal fun consumeConstructionExtenders(
        construction: MathVerticalConstruction,
        range: SourceRange,
    ): Boolean {
        val extenderRecords = construction.assemblyValidation?.extenderCount ?: 0
        val count = construction.extenderRepetitions.toLong() * extenderRecords.toLong()
        return consumeExtenders(count, range)
    }

    private fun saturatingNonNegativeAdd(left: Long, right: Long): Long = when {
        right < 0L -> Long.MAX_VALUE
        left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        else -> left + right
    }

    private fun MathDiagnostic.isLayoutResourceLimitDiagnostic(): Boolean = when (code) {
        DiagnosticCode.BreakpointCountLimitExceeded,
        DiagnosticCode.ExtenderCountLimitExceeded,
        DiagnosticCode.InvalidResolvedDimension,
        -> true
        else -> false
    }

    internal fun consumeExtenders(count: Long, range: SourceRange): Boolean {
        val maximum = resourceLimits.maximumExtenderCount.toLong()
        val exceedsLimit = count < 0L || count > maximum - generatedExtenderCount
        if (exceedsLimit) {
            diagnostics += mathResourceLimitDiagnostic(
                code = DiagnosticCode.ExtenderCountLimitExceeded,
                resource = "extenderCount",
                actual = saturatingNonNegativeAdd(generatedExtenderCount, count),
                limit = resourceLimits.maximumExtenderCount,
                range = range,
            )
            return false
        }
        generatedExtenderCount += count
        return true
    }

    internal fun recordConstructionFailure(failure: OpenTypeMathException, range: SourceRange) {
        diagnostics += if (failure.diagnosticCode == DiagnosticCode.ExtenderCountLimitExceeded) {
            mathResourceLimitDiagnostic(
                code = failure.diagnosticCode,
                resource = "extenderCount",
                actual = saturatingNonNegativeAdd(
                    generatedExtenderCount,
                    failure.resourceActual ?: (resourceLimits.maximumExtenderCount + 1L),
                ),
                limit = resourceLimits.maximumExtenderCount,
                range = range,
            )
        } else {
            MathDiagnostic(
                code = failure.diagnosticCode,
                message = failure.message ?: "OpenType math construction exceeded its resource limit",
                range = range,
            )
        }
    }

    internal fun measureGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredMathRun = if (faceId == glyphSource.faceId) {
        glyphSource.measureGlyph(glyphId, size, style, range)
    } else {
        glyphSource.measureGlyphForFace(faceId, glyphId, size, style, range)
    }

    internal fun measureGlyphOutlineForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredMathRun {
        val key = OutlineGlyphMeasurementKey(faceId, glyphId, size.toRawBits(), style)
        return outlineGlyphMeasurements.getOrPut(key) {
            if (faceId == glyphSource.faceId) {
                glyphSource.measureGlyphOutlineBounds(glyphId, size, style, range)
            } else {
                glyphSource.measureGlyphOutlineBoundsForFace(faceId, glyphId, size, style, range)
            }
        }
    }

    internal fun measureConstructionGlyphForFace(
        faceId: MathFaceId,
        glyphId: UShort,
        size: Float,
        style: MathStyle,
        range: SourceRange,
    ): MeasuredOutlineConstructionRun = if (faceId == glyphSource.faceId) {
        glyphSource.measureOutlineConstructionGlyph(glyphId, size, style, range)
    } else {
        glyphSource.measureOutlineConstructionGlyphForFace(faceId, glyphId, size, style, range)
    }

    internal fun constructionBaseCandidates(
        text: String,
        size: Float,
        range: SourceRange,
    ): List<MeasuredOutlineConstructionRun> {
        val candidates = glyphSource.shapeOutlineConstructionBaseCandidates(text, size, range)
        return if (candidates.size <= 1) {
            listOf(glyphSource.shapeOutlineConstructionBase(text, size, range))
        } else {
            candidates
        }
    }

    fun layout(parsed: MathParseResult, options: MathLayoutOptions): MathLayoutResult {
        val source = parsed.source
        val requestRange = parsed.root.range
        baseFontSizePx = validatedResolvedDimension(
            "fontSizePx",
            options.fontSizePx,
            requestRange,
        ) ?: minOf(24f, resourceLimits.maximumResolvedDimensionPx)
        formulaMode = options.mode
        displayWidthPx = options.displayWidthPx?.let {
            validatedResolvedDimension("displayWidthPx", it, requestRange)
        }
        softWrapDisplay = options.softWrapDisplay
        nullDelimiterSpacePx = validatedResolvedDimension(
            "nullDelimiterSpacePx",
            options.nullDelimiterSpacePx ?: baseFontSizePx * DEFAULT_NULL_DELIMITER_SPACE_EM,
            requestRange,
        ) ?: 0f
        scriptSpacePx = validatedResolvedDimension(
            "scriptSpacePx",
            options.scriptSpacePx ?: DEFAULT_SCRIPT_SPACE_PT * TEX_POINT_TO_PX,
            requestRange,
        ) ?: 0f
        scriptSpacePolicy = if (options.scriptSpacePx == null) {
            "PlainTeXXeTeXScriptSpace"
        } else {
            "ExplicitTeXScriptSpace"
        }
        delimiterFactor = options.delimiterFactor
        delimiterShortfallPx = validatedResolvedDimension(
            "delimiterShortfallPx",
            options.delimiterShortfallPx ?: baseFontSizePx * DEFAULT_DELIMITER_SHORTFALL_EM,
            requestRange,
        ) ?: 0f
        textLocale = options.textLocale
        explicitArrayColumnSeparationPx = options.arrayColumnSeparationPx?.let {
            validatedResolvedDimension("arrayColumnSeparationPx", it, requestRange)
        }
        fboxSeparationPx = validatedResolvedDimension(
            "fboxSeparationPx",
            options.fboxSeparationPx ?: DEFAULT_FBOX_SEPARATION_PT * TEX_POINT_TO_PX,
            requestRange,
        ) ?: 0f
        fboxRuleThicknessPx = validatedResolvedDimension(
            "fboxRuleThicknessPx",
            options.fboxRuleThicknessPx ?: DEFAULT_FBOX_RULE_THICKNESS_PT * TEX_POINT_TO_PX,
            requestRange,
        ) ?: 0f
        arrayRuleThicknessPx = validatedResolvedDimension(
            "arrayRuleThicknessPx",
            options.arrayRuleThicknessPx ?: DEFAULT_ARRAY_RULE_THICKNESS_PT * TEX_POINT_TO_PX,
            requestRange,
        ) ?: 0f
        cancelLineThicknessPx = validatedResolvedDimension(
            "cancelLineThicknessPx",
            options.cancelLineThicknessPx ?: DEFAULT_CANCEL_LINE_THICKNESS_PT * TEX_POINT_TO_PX,
            requestRange,
        ) ?: 0f
        cancelPicturePointPx = validatedResolvedDimension(
            "cancelPicturePointPx",
            options.cancelPicturePointPx ?: TEX_POINT_TO_PX,
            requestRange,
        ) ?: minOf(
            TEX_POINT_TO_PX,
            resourceLimits.maximumResolvedDimensionPx / DEFAULT_CANCEL_MAXIMUM_DERIVED_POINT_FACTOR,
        )
        diagnostics += parsed.diagnostics
        val initialStyle = options.initialStyle ?: MathStyle.initial(options.mode)
        val breakableRoot = unwrapWholeFormulaGroups(parsed.root)
        if (breakableRoot !== parsed.root) {
            decision(
                "WholeFormulaGroupTransparentForBreaking",
                parsed.root.range,
                "policy" to "OutermostWholeFormulaBracesUnwrappedToRestoreTopLevelBoundaries",
            )
        }
        val horizontal = layoutList(breakableRoot, initialStyle)
        val fragments = inlineFragments(horizontal)
        val breaks = fragments.mapNotNull { it.breakAfter }
        // Preflight the mode's actual breaker: display operators lead the next line,
        // while inline operators trail the preceding line.
        val breakPolicy = when (options.mode) {
            MathMode.Inline -> MathLineBreakPolicy.InlineTrailingOperators
            MathMode.Display -> MathLineBreakPolicy.ResponsiveDisplayLeadingOperators
        }
        val internalBreakpointCount = mathBreakpointCount(fragments, breakPolicy)
        if (internalBreakpointCount > resourceLimits.maximumBreakpointCount) {
            diagnostics += mathResourceLimitDiagnostic(
                code = DiagnosticCode.BreakpointCountLimitExceeded,
                resource = "breakpointCount",
                actual = internalBreakpointCount,
                limit = resourceLimits.maximumBreakpointCount,
                range = parsed.root.range,
            )
        }
        val lineMetrics = formulaLineMetrics(horizontal.laid.box, initialStyle)
        decision(
            "Os2TypographicMathLineExtents",
            parsed.root.range,
            "fontAscentPx" to lineMetrics.fontAscentPx,
            "fontDescentPx" to lineMetrics.fontDescentPx,
            "fontLineGapPx" to lineMetrics.fontLineGapPx,
            "mathLeadingPx" to lineMetrics.mathLeadingPx,
            "inkAscentPx" to lineMetrics.inkAscentPx,
            "inkDescentPx" to lineMetrics.inkDescentPx,
            "texBoxAscentPx" to horizontal.laid.box.ascent,
            "texBoxDescentPx" to horizontal.laid.box.descent,
            "hostReservePolicy" to "MaxOfTypographicLineTeXBoxAndPaintInk",
            "logicalAscentPx" to lineMetrics.logicalAscentPx,
            "logicalDescentPx" to lineMetrics.logicalDescentPx,
        )
        val resultDiagnostics = diagnostics.distinct()
        val resultDecisions = decisions.toList()
        val dumpMetadata = MathLayoutDebugDumpMetadata(
            unitsPerEm = glyphSource.mathFont.unitsPerEm,
            axisHeight = constants.axisHeight,
            fractionRuleThickness = constants.fractionRuleThickness,
            scriptPercentScaleDown = constants.scriptPercentScaleDown,
            scriptScriptPercentScaleDown = constants.scriptScriptPercentScaleDown,
        )
        return MathLayoutResult(
            source = source,
            mode = options.mode,
            initialStyle = initialStyle,
            box = horizontal.laid.box,
            fragments = fragments,
            breakOpportunities = breaks,
            diagnostics = resultDiagnostics,
            lineMetrics = lineMetrics,
            decisions = resultDecisions,
            debugDumpRenderer = DefaultMathLayoutDebugDumpRenderer(dumpMetadata),
            taggedDisplayReplay = taggedDisplayReplay,
            fontSizePx = baseFontSizePx,
            resourceLimits = resourceLimits,
        )
    }

    internal val constants: OpenTypeMathConstants get() = glyphSource.mathFont.constants

    internal fun layoutNode(
        node: MathNode,
        style: MathStyle,
        alphabetOverride: MathAlphabetOverride? = null,
    ): LaidNode = when (node) {
        is MathList -> layoutList(node, style, alphabetOverride).laid
        is MathGroup -> layoutGroup(node, style, alphabetOverride)
        is MathBoxed -> layoutBoxed(node, style, alphabetOverride)
        is MathBbox -> layoutBbox(node, style, alphabetOverride)
        is MathSymbol -> layoutSymbol(node, style, alphabetOverride)
        is MathOperator -> layoutOperator(node, style, alphabetOverride)
        is MathOperatorName -> layoutOperatorName(node, style, alphabetOverride)
        is MathOperatorNoad -> layoutOperatorNoad(node, style, alphabetOverride)
        is MathModulo -> layoutModulo(node, style, alphabetOverride)
        is MathText -> layoutText(node, style)
        is MathAccent -> layoutAccent(node, style, alphabetOverride)
        is MathBraceNoad -> layoutBraceNoad(node, style, alphabetOverride)
        is MathRuleDecoration -> layoutRuleDecoration(node, style, alphabetOverride)
        is MathOverUnder -> layoutOverUnder(node, style, alphabetOverride)
        is MathExtensibleArrow -> layoutExtensibleArrow(node, style, alphabetOverride)
        is MathNegation -> layoutNegation(node, style, alphabetOverride)
        is MathCancel -> layoutCancel(node, style, alphabetOverride)
        is MathRuleBox -> layoutRuleBox(node, style)
        is MathLap -> layoutLap(node, style, alphabetOverride)
        is MathExplicitSpace -> layoutExplicitSpace(node, style)
        is MathTable -> layoutTable(node, style, alphabetOverride)
        is MathDisplayEnvironment -> layoutDisplayEnvironment(node, alphabetOverride)
        is MathDisplayRows -> layoutDisplayRows(node, alphabetOverride)
        is MathTaggedEquation -> layoutTaggedEquation(node, alphabetOverride)
        is MathEquationTag -> layoutMisplacedEquationTag(node, style)
        is MathExplicitRowBreak -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathScripts -> when (val base = node.base) {
            is MathOperator -> layoutOperatorScripts(node, base, style, alphabetOverride)
            is MathOperatorName -> layoutOperatorNameScripts(node, base, style, alphabetOverride)
            is MathOperatorNoad -> layoutOperatorNoadScripts(node, base, style, alphabetOverride)
            is MathBraceNoad -> layoutBraceNoadScripts(node, base, style, alphabetOverride)
            else -> layoutScripts(node, style, alphabetOverride)
        }
        is MathFraction -> layoutFraction(node, style, alphabetOverride)
        is MathRadical -> layoutRadical(node, style, alphabetOverride)
        is MathFixedDelimiter -> layoutFixedDelimiter(node, style)
        is MathDelimited -> layoutDelimited(node, style, alphabetOverride)
        is MathMiddleDelimiter -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Inner,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathStyleDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathTexLogo -> layoutTexLogo(node, style, alphabetOverride)
        is MathSizeDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathColorDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathAlphabetDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathVersionDeclaration -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
        is MathAlphabetScope -> layoutAlphabetScopeNode(node, style)
        is MathVersionScope -> layoutMathVersionScopeNode(node, style)
        is MathErrorNode -> LaidNode(
            node,
            emptyBox(node.range),
            MathAtomClass.Ordinary,
            0f,
            style,
            ScriptBaseKind.CompoundBox,
        )
    }

    internal fun MathHostTextCapabilityIssue.asDiagnostic(): MathDiagnostic = MathDiagnostic(
        code = when (code) {
            MathHostTextCapabilityIssueCode.NonReplayableHostTextRun,
            MathHostTextCapabilityIssueCode.PlatformMultiFaceStringDraw,
            -> DiagnosticCode.NonReplayableHostTextRun

            MathHostTextCapabilityIssueCode.UnsupportedBidirectionalText,
            MathHostTextCapabilityIssueCode.UnsupportedComplexScript,
            -> DiagnosticCode.UnsupportedHostTextShaping

            MathHostTextCapabilityIssueCode.InvalidHostTextRunEvidence ->
                DiagnosticCode.InvalidHostTextRunEvidence
        },
        message = message,
        range = sourceRange,
    )


    /**
     * XeTeX native character noads use the glyph's exact bounding box in `make_scripts`.
     * Replace the replayed leaf box itself so the constraint calculation, painted baseline,
     * completed MathBox, and recursive clean-box metric all share one placement. Compound boxes
     * already carry their completed TeX metrics and must not be flattened back to glyph ink.
     */
    internal fun LaidNode.withNativeOutlineBoxForSideScriptPlacement(): LaidNode {
        if (scriptBaseKind != ScriptBaseKind.Character || box.glyphs.isEmpty()) {
            return copy(box = box.completedTeXBox())
        }
        val evidence = mutableSetOf<MathTeXCleanBoxEvidence>()
        val outlineGlyphs = box.glyphs.map { placement ->
            if (mathFontForFaceOrNull(placement.faceId) == null) {
                evidence += MathTeXCleanBoxEvidence.GlyphOutline
                return@map placement
            }
            val measured = measureGlyphOutlineForFace(
                placement.faceId,
                placement.glyphId,
                placement.fontSizePx,
                placement.style,
                placement.sourceRange,
            )
            evidence += if (measured.boundsSource == MathGlyphBoundsSource.Outline) {
                MathTeXCleanBoxEvidence.GlyphOutline
            } else {
                MathTeXCleanBoxEvidence.FontReportedGlyphBounds
            }
            val glyph = measured.glyphs.singleOrNull() ?: return@map placement
            placement.copy(inkBounds = glyph.inkBounds.translated(placement.x, placement.baselineY))
        }
        val left = outlineGlyphs.minOfOrNull { it.inkBounds.left } ?: 0f
        val top = outlineGlyphs.minOfOrNull { it.inkBounds.top } ?: 0f
        val right = outlineGlyphs.maxOfOrNull { it.inkBounds.right } ?: 0f
        val bottom = outlineGlyphs.maxOfOrNull { it.inkBounds.bottom } ?: 0f
        val logicalAdvance = outlineGlyphs.maxOfOrNull { it.x + it.advance } ?: box.width
        val ascent = (-top).coerceAtLeast(0f)
        val descent = bottom.coerceAtLeast(0f)
        return copy(
            box = box.copy(
                width = logicalAdvance,
                ascent = ascent,
                descent = descent,
                inkBounds = MathRect(left, top, right, bottom),
                glyphs = outlineGlyphs,
                texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                    ascent = ascent,
                    descent = descent,
                    policy = MathTeXCleanBoxPolicy.GlyphOutlineUnion,
                    evidence = evidence,
                ),
            ),
        )
    }

    internal fun MathBox.completedTeXBox(): MathBox = copy(
        ascent = texCleanBoxMetrics.ascent,
        descent = texCleanBoxMetrics.descent,
    )

    /** XeTeX `clean_box` completes a math field with its terminal italic correction. */
    internal fun LaidNode.completedTeXMathField(): LaidNode = copy(
        box = box.completedTeXBox().copy(width = box.width + italicCorrectionPx.coerceAtLeast(0f)),
        italicCorrectionPx = 0f,
    )

    /** XeTeX `clean_box` retains a character field's terminal italic correction. */
    internal fun LaidNode.completedTeXScriptFieldWidth(): Float =
        box.width + italicCorrectionPx.coerceAtLeast(0f)

    internal fun PendingHorizontalItem.layoutIndividually(): HorizontalItem {
        val ambientSizeScale = userSizeScale
        if (sizeScale != null) userSizeScale = sizeScale
        val laid = try {
            layoutNode(node, style, alphabetOverride).let { result ->
                if (paintColor == null) result else result.copy(box = result.box.withInheritedPaintColor(paintColor))
            }
        } finally {
            userSizeScale = ambientSizeScale
        }
        return HorizontalItem(
            node = node,
            laid = laid,
            glueBefore = MathGlueAdjustment.Zero,
            atomClass = MathAtomClass.Ordinary,
            participatesInNoadSpacing = node !is MathExplicitSpace,
            leadingKernPx = laid.horizontalKernPx,
            sizeScale = sizeScale,
        )
    }

    internal fun fontSize(style: MathStyle): Float = userSizeScale * when (style.level) {
        MathStyleLevel.Display, MathStyleLevel.Text -> baseFontSizePx
        MathStyleLevel.Script -> baseFontSizePx * constants.scriptPercentScaleDown / 100f
        MathStyleLevel.ScriptScript -> baseFontSizePx * constants.scriptScriptPercentScaleDown / 100f
    }

    internal fun scale(designUnits: Int, style: MathStyle): Float =
        glyphSource.mathFont.scaleDesignUnits(designUnits, fontSize(style))

    internal fun styleForLevel(level: MathStyleLevel): MathStyle = when (level) {
        MathStyleLevel.Display -> MathStyle.Display
        MathStyleLevel.Text -> MathStyle.Text
        MathStyleLevel.Script -> MathStyle.Script
        MathStyleLevel.ScriptScript -> MathStyle.ScriptScript
    }

    internal fun formulaLineMetrics(box: MathBox, style: MathStyle): MathFormulaLineMetrics {
        val size = fontSize(style)
        val metrics = glyphSource.mathFont.lineMetrics
        val fontAscent = glyphSource.mathFont.scaleDesignUnits(metrics.typoAscender, size).coerceAtLeast(0f)
        val fontDescent = (-glyphSource.mathFont.scaleDesignUnits(metrics.typoDescender, size)).coerceAtLeast(0f)
        val lineGap = glyphSource.mathFont.scaleDesignUnits(metrics.typoLineGap, size).coerceAtLeast(0f)
        val mathLeading = scale(constants.mathLeading, style).coerceAtLeast(0f)
        val inkAscent = (-box.inkBounds.top).coerceAtLeast(0f)
        val inkDescent = box.inkBounds.bottom.coerceAtLeast(0f)
        val hostContentAscent = max(box.ascent, inkAscent)
        val hostContentDescent = max(box.descent, inkDescent)
        return MathFormulaLineMetrics(
            fontAscentPx = fontAscent,
            fontDescentPx = fontDescent,
            fontLineGapPx = lineGap,
            mathLeadingPx = mathLeading,
            inkAscentPx = inkAscent,
            inkDescentPx = inkDescent,
            logicalAscentPx = max(fontAscent + lineGap, hostContentAscent + mathLeading),
            logicalDescentPx = max(fontDescent, hostContentDescent),
        )
    }

    internal fun geometryExtents(
        width: Float,
        glyphs: List<MathGlyphPlacement>,
        rules: List<MathRulePlacement>,
        range: SourceRange,
        constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
        hostTextRuns: List<MathHostTextPlacement> = emptyList(),
    ): MathBox {
        val cleanEvidence = mutableSetOf<MathTeXCleanBoxEvidence>()
        var hasGlyph = false
        var glyphLeft = 0f
        var glyphTop = 0f
        var glyphRight = 0f
        var glyphBottom = 0f
        var cleanGlyphTop = 0f
        var cleanGlyphBottom = 0f
        glyphs.forEach { placement ->
            if (!hasGlyph) {
                glyphLeft = placement.inkBounds.left
                glyphTop = placement.inkBounds.top
                glyphRight = placement.inkBounds.right
                glyphBottom = placement.inkBounds.bottom
            } else {
                glyphLeft = minOf(glyphLeft, placement.inkBounds.left)
                glyphTop = minOf(glyphTop, placement.inkBounds.top)
                glyphRight = maxOf(glyphRight, placement.inkBounds.right)
                glyphBottom = maxOf(glyphBottom, placement.inkBounds.bottom)
            }

            val cleanBounds = if (mathFontForFaceOrNull(placement.faceId) == null) {
                cleanEvidence += MathTeXCleanBoxEvidence.GlyphOutline
                placement.inkBounds
            } else {
                val measured = measureGlyphOutlineForFace(
                    placement.faceId,
                    placement.glyphId,
                    placement.fontSizePx,
                    placement.style,
                    placement.sourceRange,
                )
                cleanEvidence += if (measured.boundsSource == MathGlyphBoundsSource.Outline) {
                    MathTeXCleanBoxEvidence.GlyphOutline
                } else {
                    MathTeXCleanBoxEvidence.FontReportedGlyphBounds
                }
                measured.glyphs.singleOrNull()?.inkBounds
                    ?.translated(placement.x, placement.baselineY)
                    ?: placement.inkBounds
            }
            if (!hasGlyph) {
                cleanGlyphTop = cleanBounds.top
                cleanGlyphBottom = cleanBounds.bottom
                hasGlyph = true
            } else {
                cleanGlyphTop = minOf(cleanGlyphTop, cleanBounds.top)
                cleanGlyphBottom = maxOf(cleanGlyphBottom, cleanBounds.bottom)
            }
        }
        var hasHostText = false
        var hostTextLeft = 0f
        var hostTextTop = 0f
        var hostTextRight = 0f
        var hostTextBottom = 0f
        hostTextRuns.forEach { placement ->
            if (!hasHostText) {
                hostTextLeft = placement.inkBounds.left
                hostTextTop = placement.inkBounds.top
                hostTextRight = placement.inkBounds.right
                hostTextBottom = placement.inkBounds.bottom
                hasHostText = true
            } else {
                hostTextLeft = minOf(hostTextLeft, placement.inkBounds.left)
                hostTextTop = minOf(hostTextTop, placement.inkBounds.top)
                hostTextRight = maxOf(hostTextRight, placement.inkBounds.right)
                hostTextBottom = maxOf(hostTextBottom, placement.inkBounds.bottom)
            }
        }
        if (hostTextRuns.isNotEmpty()) cleanEvidence += MathTeXCleanBoxEvidence.HostTextRunMetrics
        var hasRule = false
        var ruleLeft = 0f
        var ruleTop = 0f
        var ruleRight = 0f
        var ruleBottom = 0f
        rules.forEach { rule ->
            if (!hasRule) {
                ruleLeft = rule.left
                ruleTop = rule.top
                ruleRight = rule.right
                ruleBottom = rule.bottom
                hasRule = true
            } else {
                ruleLeft = minOf(ruleLeft, rule.left)
                ruleTop = minOf(ruleTop, rule.top)
                ruleRight = maxOf(ruleRight, rule.right)
                ruleBottom = maxOf(ruleBottom, rule.bottom)
            }
        }
        if (rules.isNotEmpty()) cleanEvidence += MathTeXCleanBoxEvidence.RuleGeometry
        if (glyphs.isEmpty() && rules.isEmpty() && hostTextRuns.isEmpty()) {
            cleanEvidence += MathTeXCleanBoxEvidence.Empty
        }
        var left = minOf(if (hasGlyph) glyphLeft else 0f, if (hasRule) ruleLeft else 0f)
        var top = minOf(if (hasGlyph) glyphTop else 0f, if (hasRule) ruleTop else 0f)
        var right = maxOf(if (hasGlyph) glyphRight else 0f, if (hasRule) ruleRight else 0f)
        var bottom = maxOf(if (hasGlyph) glyphBottom else 0f, if (hasRule) ruleBottom else 0f)
        var cleanTop = minOf(if (hasGlyph) cleanGlyphTop else 0f, if (hasRule) ruleTop else 0f)
        var cleanBottom = maxOf(if (hasGlyph) cleanGlyphBottom else 0f, if (hasRule) ruleBottom else 0f)
        if (hasHostText) {
            if (hasGlyph || hasRule) {
                left = minOf(left, hostTextLeft)
                top = minOf(top, hostTextTop)
                right = maxOf(right, hostTextRight)
                bottom = maxOf(bottom, hostTextBottom)
                cleanTop = minOf(cleanTop, hostTextTop)
                cleanBottom = maxOf(cleanBottom, hostTextBottom)
            } else {
                left = hostTextLeft
                top = hostTextTop
                right = hostTextRight
                bottom = hostTextBottom
                cleanTop = hostTextTop
                cleanBottom = hostTextBottom
            }
        }
        val ascent = (-top).coerceAtLeast(0f)
        val descent = bottom.coerceAtLeast(0f)
        return MathBox(
            width,
            ascent,
            descent,
            MathRect(left, top, right, bottom),
            glyphs,
            rules,
            range,
            constructionPaintGroups.distinctBy { it.id },
            MathTeXCleanBoxMetrics(
                ascent = (-cleanTop).coerceAtLeast(0f),
                descent = cleanBottom.coerceAtLeast(0f),
                policy = MathTeXCleanBoxPolicy.GlyphOutlineUnion,
                evidence = cleanEvidence,
            ),
            hostTextRuns,
        )
    }

    /**
     * Compose visual bounds come from glyph/rule ink, while recursive TeX layout must retain
     * logical box extents such as RadicalExtraAscender even when no ink occupies that reserve.
     */
    internal fun geometryExtentsPreservingLogicalChildren(
        width: Float,
        glyphs: List<MathGlyphPlacement>,
        rules: List<MathRulePlacement>,
        range: SourceRange,
        children: List<Pair<MathBox, Float>>,
        constructionPaintGroups: List<MathConstructionPaintGroup> = emptyList(),
        hostTextRuns: List<MathHostTextPlacement> = emptyList(),
    ): MathBox {
        val paintGroups = (constructionPaintGroups + children.flatMap { it.first.constructionPaintGroups })
            .distinctBy { it.id }
        val inkBox = geometryExtents(width, glyphs, rules, range, paintGroups, hostTextRuns)
        val logicalAscent = children.maxOfOrNull { (box, baselineY) ->
            (box.ascent - baselineY).coerceAtLeast(0f)
        } ?: 0f
        val logicalDescent = children.maxOfOrNull { (box, baselineY) ->
            (box.descent + baselineY).coerceAtLeast(0f)
        } ?: 0f
        // Children are the completed TeX box list. Flattened paint must not supersede the
        // child's clean-box metric; side scripts now place these same child boxes directly.
        val cleanAscent = children.maxOfOrNull { (box, baselineY) ->
            (box.texCleanBoxMetrics.ascent - baselineY).coerceAtLeast(0f)
        } ?: inkBox.texCleanBoxMetrics.ascent
        val cleanDescent = children.maxOfOrNull { (box, baselineY) ->
            (box.texCleanBoxMetrics.descent + baselineY).coerceAtLeast(0f)
        } ?: inkBox.texCleanBoxMetrics.descent
        return inkBox.copy(
            // TeX box height/depth come from the positioned child box list. Painted ink may
            // overhang that box and remains available through inkBounds/visual extents; it must
            // not silently become recursive noad reserve or host line-height reserve.
            ascent = logicalAscent,
            descent = logicalDescent,
            texCleanBoxMetrics = MathTeXCleanBoxMetrics(
                ascent = cleanAscent,
                descent = cleanDescent,
                policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
                evidence = inkBox.texCleanBoxMetrics.evidence +
                    children.flatMap { it.first.texCleanBoxMetrics.evidence } +
                    MathTeXCleanBoxEvidence.CompletedChildBox,
            ),
        )
    }

    internal fun emptyBox(range: SourceRange): MathBox = MathBox(
        0f,
        0f,
        0f,
        MathRect(0f, 0f, 0f, 0f),
        emptyList(),
        emptyList(),
        range,
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            0f,
            0f,
            MathTeXCleanBoxPolicy.GlyphOutlineUnion,
            setOf(MathTeXCleanBoxEvidence.Empty),
        ),
    )

    internal fun decision(name: String, range: SourceRange, vararg details: Pair<String, Any?>) {
        decisions += MathLayoutDecision(name, range, details.associate { it.first to it.second.toString() })
    }

    internal data class LaidNode(
        val node: MathNode,
        val box: MathBox,
        val atomClass: MathAtomClass,
        val italicCorrectionPx: Float,
        val style: MathStyle,
        val scriptBaseKind: ScriptBaseKind,
        val horizontalKernPx: Float = 0f,
    )

    private data class OutlineGlyphMeasurementKey(
        val faceId: MathFaceId,
        val glyphId: UShort,
        val fontSizeBits: Int,
        val style: MathStyle,
    )

    internal data class StackedLimitsPlacement(
        val box: MathBox,
        val base: LaidNode,
        val upper: LaidNode?,
        val lower: LaidNode?,
        val upperGapMin: Float,
        val upperBaselineRiseMin: Float,
        val lowerGapMin: Float,
        val lowerBaselineDropMin: Float,
        val upperOuterPadding: Float,
        val lowerOuterPadding: Float,
        val upperShift: Float?,
        val lowerShift: Float?,
        val halfItalicCorrection: Float,
        val logicalWidth: Float,
        val baseX: Float,
        val upperX: Float?,
        val lowerX: Float?,
    ) {
        val actualUpperGap: Float?
            get() = upper?.let { upperShift!! - base.box.ascent - it.box.descent }
        val actualUpperRise: Float?
            get() = upperShift?.minus(base.box.ascent)
        val actualLowerGap: Float?
            get() = lower?.let { lowerShift!! - base.box.descent - it.box.ascent }
        val actualLowerDrop: Float?
            get() = lowerShift?.minus(base.box.descent)

    }

    internal data class AccentAttachmentEvidence(
        val valuePx: Float,
        val policy: String,
        val ignoredDeviceAdjustment: MathDeviceAdjustment? = null,
    )

    internal data class MeasurementLayoutNode(
        val node: LaidNode,
        val diagnostics: List<MathDiagnostic>,
        val decisions: List<MathLayoutDecision>,
    )

    internal data class AmsmathArrowFaceEvidence(
        val faceId: MathFaceId,
        val head: MeasuredOutlineConstructionRun,
        val relbar: MeasuredOutlineConstructionRun,
    )

    internal data class DelimiterTargetEvidence(
        val innerCleanAscentPx: Float,
        val innerCleanDescentPx: Float,
        val axisHeightPx: Float,
        val maxAxisDistancePx: Float,
        val factor: Int,
        val shortfallPx: Float,
        val factorTargetPx: Float,
        val shortfallTargetPx: Float,
        val targetPx: Float,
        val targetPolicy: String = "XeTeXMakeLeftRightAxisFactorShortfall",
        val fixedSize: MathFixedDelimiterSize? = null,
        val amsmathFactor: Float? = null,
        val mathStrutAscentPx: Float? = null,
        val mathStrutDescentPx: Float? = null,
        val bigSizePx: Float? = null,
        val requestedExtentPx: Float? = null,
        val vcenterAscentPx: Float? = null,
        val vcenterDescentPx: Float? = null,
    )

    internal data class PlacedVerticalConstruction(
        val width: Float,
        val glyphs: List<MathGlyphPlacement>,
        val boxAscentPx: Float,
        val boxDescentPx: Float,
        val topStrokeEvidence: MathConstructionOutlineEvidence.Available?,
        val outlineEvidenceFailure: MathConstructionOutlineUnavailableReason?,
        val componentHorizontalOriginsPx: List<Float>,
        val componentBottomOriginsPx: List<Float>,
        val componentBaselineOriginsPx: List<Float>,
        val placementOrigin: String,
        val placementPolicy: String,
    )

    internal data class HorizontalItem(
        val node: MathNode,
        val laid: LaidNode,
        val glueBefore: MathGlueAdjustment,
        val atomClass: MathAtomClass,
        val leadingKernPx: Float = 0f,
        val trailingItalicCorrectionPx: Float = 0f,
        /** False for explicit glue/kern nodes, which never become TeX noads or alter Bin repair. */
        val participatesInNoadSpacing: Boolean = true,
        /** The size declaration active over this atom; the pair glue before it uses this scale. */
        val sizeScale: Float? = null,
    )

    internal data class PendingHorizontalItem(
        val node: MathNode,
        val style: MathStyle,
        val alphabetOverride: MathAlphabetOverride?,
        val paintColor: MathPaintColor?,
        /** Absolute size override from a LaTeX size declaration; null inherits the ambient size. */
        val sizeScale: Float? = null,
    )

    internal data class HorizontalLayout(
        val laid: LaidNode,
        val items: List<HorizontalItem>,
    )

    internal data class MathAlphabetOverride(
        val family: MathFamily? = null,
        val alphabet: MathAlphabet? = null,
        val version: MathVersion? = null,
    )

    internal data class CancelStrokeGeometry(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val slopeX: Int,
        val slopeY: Int,
        val shapeClass: String,
        val classificationWidth: Float,
        val classificationHeight: Float,
    )

    internal data class OperatorLimitsSemantics(
        val identity: String,
        val declaredPolicy: MathLimitsPolicy,
        val explicit: Boolean,
        val modifierRange: SourceRange?,
        val sideScriptHorizontalPolicy: SideScriptHorizontalPolicy,
        val sideScriptGeometry: String,
    )

    internal companion object {
        const val GEOMETRY_EPSILON_PX = 0.02f
        const val TEX_MU_PER_EM = 18f
        const val LATEX_XETEX_GENFRAC_DISPLAY_DELIMITER_EM = 2.39f
        const val LATEX_XETEX_GENFRAC_TEXT_DELIMITER_EM = 1f
        const val LATEX_XETEX_GENFRAC_SCRIPT_DELIMITER_EM = 1.45f
        const val LATEX_XETEX_GENFRAC_SCRIPT_SCRIPT_DELIMITER_EM = 1.35f
        // Reviewed amsmath/XeTeX showbox policy: matrix/aligned rows use a 0.7em/0.3em strut,
        // cases applies arraystretch=1.2, and aligned inserts its named inter-row separation.
        const val TEX_ARRAY_STRUT_ASCENT_EM = 0.7f
        const val TEX_ARRAY_STRUT_DESCENT_EM = 0.3f
        const val TEX_CASES_STRUT_ASCENT_EM = 0.84f
        const val TEX_CASES_STRUT_DESCENT_EM = 0.36f
        const val TEX_ALIGNED_ROW_GAP_EM = 1f / 6f
        const val TEX_SMALL_MATRIX_LINE_SKIP_EM = 3f / 32f
        const val TEX_ALIGNED_PAIR_GAP_EM = 2f
        const val TEX_ARRAY_INTERCOLUMN_EM = 1f
        const val TEX_ARRAY_COLUMN_SEPARATION_EM = 0.5f
        const val CSS_PIXELS_PER_INCH = 96f
        const val TEX_POINTS_PER_INCH = 72.27f
        const val BIG_POINTS_PER_INCH = 72f
        const val CENTIMETERS_PER_INCH = 2.54f
        const val MILLIMETERS_PER_INCH = 25.4f
        const val TEX_POINT_TO_PX = CSS_PIXELS_PER_INCH / TEX_POINTS_PER_INCH
        const val AMSMATH_BIG_SIZE_SCALE = 1.2f
        const val DEFAULT_SCRIPT_SPACE_PT = 0.5f
        const val DEFAULT_FBOX_SEPARATION_PT = 3f
        const val DEFAULT_FBOX_RULE_THICKNESS_PT = 0.4f
        const val DEFAULT_ARRAY_RULE_THICKNESS_PT = 0.4f
        const val DEFAULT_CANCEL_LINE_THICKNESS_PT = 0.4f
        const val DEFAULT_CANCEL_MINIMUM_WIDTH_PT = 2f
        const val DEFAULT_CANCEL_MINIMUM_TOTAL_HEIGHT_PT = 6f
        const val DEFAULT_CANCEL_WIDE_MINIMUM_WIDTH_PT = 8f
        const val DEFAULT_CANCEL_TALL_MINIMUM_HEIGHT_PT = 8f
        const val DEFAULT_CANCEL_LINE_EXTENSION_PT = 2f
        const val DEFAULT_CANCEL_MAXIMUM_DERIVED_POINT_FACTOR = 8f
        const val BIG_POINT_TO_PX = CSS_PIXELS_PER_INCH / BIG_POINTS_PER_INCH

        val CANCEL_WIDE_SLOPES = listOf(6 to 1, 4 to 1, 2 to 1, 4 to 3, 1 to 1)
        val CANCEL_TALL_SLOPES = listOf(1 to 6, 1 to 4, 1 to 2, 3 to 4, 1 to 1)
        val CANCEL_TALL_WIDTH_FACTORS = listOf(0.16f, 0.25f, 0.5f, 0.75f, 1f)

        val NEGATED_RELATION_SCALARS = mapOf(
            0x003D to 0x2260,
            0x003C to 0x226E,
            0x003E to 0x226F,
            0x2190 to 0x219A,
            0x2192 to 0x219B,
            0x2194 to 0x21AE,
            0x21D0 to 0x21CD,
            0x21D2 to 0x21CF,
            0x21D4 to 0x21CE,
            0x2208 to 0x2209,
            0x220B to 0x220C,
            0x2223 to 0x2224,
            0x2225 to 0x2226,
            0x223C to 0x2241,
            0x2243 to 0x2244,
            0x2245 to 0x2247,
            0x2248 to 0x2249,
            0x2261 to 0x2262,
            0x2264 to 0x2270,
            0x2265 to 0x2271,
            0x2282 to 0x2284,
            0x2283 to 0x2285,
            0x2286 to 0x2288,
            0x2287 to 0x2289,
        )

        val binaryLeftCanceller = setOf(
            MathAtomClass.Binary,
            MathAtomClass.Opening,
            MathAtomClass.Relation,
            MathAtomClass.Operator,
            MathAtomClass.Punctuation,
        )
        val binaryRightCanceller = setOf(
            MathAtomClass.Relation,
            MathAtomClass.Closing,
            MathAtomClass.Punctuation,
        )

    }
}

private const val DEFAULT_NULL_DELIMITER_SPACE_EM = 0.12f
private const val DEFAULT_DELIMITER_SHORTFALL_EM = 0.5f
