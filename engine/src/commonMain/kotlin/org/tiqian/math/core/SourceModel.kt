package org.tiqian.math.core

/** UTF-16 offsets into the original Markdown math source. */
data class SourceRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "source range start must not be negative" }
        require(endExclusive >= start) { "source range end must not precede start" }
    }

    val length: Int get() = endExclusive - start
    val isEmpty: Boolean get() = length == 0

    fun cover(other: SourceRange): SourceRange = SourceRange(
        start = minOf(start, other.start),
        endExclusive = maxOf(endExclusive, other.endExclusive),
    )

    companion object {
        val Empty = SourceRange(0, 0)
    }
}

enum class DiagnosticSeverity {
    Warning,
    Error,
}

enum class DiagnosticCode {
    TrailingEscape,
    InvalidParameterMarker,
    InvalidRuleDimension,
    MissingMacroArgument,
    MacroExpansionDepthExceeded,
    MacroExpansionBudgetExceeded,
    RecursiveMacro,
    UnexpectedClosingGroup,
    UnclosedGroup,
    MissingScriptBase,
    MissingScriptArgument,
    DuplicateSubscript,
    DuplicateSuperscript,
    MissingCommandArgument,
    MissingRadicalDegree,
    UnclosedRadicalDegree,
    MissingRadicalRadicand,
    UnclosedRadicalRadicand,
    MissingExtensibleArrowLabel,
    UnclosedExtensibleArrowBelow,
    InvalidContinuedFractionAlignment,
    UnclosedContinuedFractionAlignment,
    UnknownColorName,
    InvalidExtensibleArrowFill,
    MisplacedLimitsModifier,
    UnknownCommand,
    UnsupportedCommand,
    UnsupportedMathAlphabet,
    MissingGlyph,
    MissingTextRunProvider,
    NonReplayableHostTextRun,
    UnsupportedHostTextShaping,
    InvalidHostTextRunEvidence,
    ReplayFaceOwnershipConflict,
    MissingMathTable,
    MalformedFont,
    UnsupportedMathDeviceAdjustment,
    MissingMathConstruction,
    MathVariantTooShort,
    MissingConstructionOutlineEvidence,
    MissingGlyphOutlineEvidence,
    InvalidConstructionPaintOwnership,
    MissingDelimiterAfterLeft,
    MissingDelimiterAfterMiddle,
    MissingDelimiterAfterRight,
    MissingDelimiterAfterFixedSizeCommand,
    UnexpectedRightDelimiter,
    MiddleOutsideDelimitedGroup,
    MissingRightDelimiter,
    UnsupportedDelimiter,
    MissingEnvironmentName,
    UnsupportedEnvironment,
    MissingEnvironmentEnd,
    MismatchedEnvironmentEnd,
    MisplacedDisplayEnvironment,
    MissingArrayColumnSpecification,
    InvalidArrayColumnSpecification,
    InvalidRowSpacing,
    UnexpectedAlignmentTab,
    UnexpectedRowSeparator,
    ExplicitMultilineRequiresDisplay,
    MissingEquationTagArgument,
    MultipleEquationTags,
    MisplacedEquationTag,
    MissingEquationTagDisplayWidth,
    UnclosedBboxOptions,
    InvalidBboxOption,
    DuplicateBboxOption,
    UnsupportedBboxStyle,
    MissingBboxXHeight,
    MissingGeneralizedFractionNumerator,
    MissingGeneralizedFractionDenominator,
    AmbiguousGeneralizedFraction,
    MissingNegatedAtom,
    UnsupportedNegatedSymbol,
    MisplacedHorizontalRule,
    SourceLengthLimitExceeded,
    TokenCountLimitExceeded,
    AstNodeCountLimitExceeded,
    RecursionDepthLimitExceeded,
    BreakpointCountLimitExceeded,
    ExtenderCountLimitExceeded,
    InvalidResolvedDimension,
}

data class MathDiagnostic(
    val code: DiagnosticCode,
    val message: String,
    val range: SourceRange,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
)

enum class MathMode {
    Inline,
    Display,
}
