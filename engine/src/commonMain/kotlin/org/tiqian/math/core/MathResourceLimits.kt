package org.tiqian.math.core

/**
 * Safety budgets for one untrusted TeX formula request.
 *
 * Per-field documentation defines the exact accounting boundary.
 */
data class MathResourceLimits(
    /** UTF-16 code units in one formula source. */
    val maximumSourceLength: Int = 65536,
    /** Non-End tokens in either the raw or expanded stream. */
    val maximumTokenCount: Int = 20000,
    /** Nodes retained by the final AST, including its root [MathList]. */
    val maximumNodeCount: Int = 20000,
    /** Active parser frames or final AST levels, whichever reaches the limit first. */
    val maximumRecursionDepth: Int = 128,
    /** Legal internal breaks in any one line-breaking list; terminal boundaries are excluded. */
    val maximumBreakpointCount: Int = 1024,
    /** Extenders materialized by one layout attempt, including font candidates and probes. */
    val maximumExtenderCount: Int = 4096,
    /** Maximum absolute physical length accepted after resolving a TeX unit to layout pixels. */
    val maximumResolvedDimensionPx: Float = 65536f,
) {
    init {
        require(maximumSourceLength in 1..ABSOLUTE_MAXIMUM_SOURCE_LENGTH) {
            "maximumSourceLength must be between 1 and $ABSOLUTE_MAXIMUM_SOURCE_LENGTH"
        }
        require(maximumTokenCount in 1..ABSOLUTE_MAXIMUM_TOKEN_COUNT) {
            "maximumTokenCount must be between 1 and $ABSOLUTE_MAXIMUM_TOKEN_COUNT"
        }
        require(maximumNodeCount in 1..ABSOLUTE_MAXIMUM_NODE_COUNT) {
            "maximumNodeCount must be between 1 and $ABSOLUTE_MAXIMUM_NODE_COUNT"
        }
        require(maximumRecursionDepth in 1..ABSOLUTE_MAXIMUM_RECURSION_DEPTH) {
            "maximumRecursionDepth must be between 1 and $ABSOLUTE_MAXIMUM_RECURSION_DEPTH"
        }
        require(maximumBreakpointCount in 0..ABSOLUTE_MAXIMUM_BREAKPOINT_COUNT) {
            "maximumBreakpointCount must be between 0 and $ABSOLUTE_MAXIMUM_BREAKPOINT_COUNT"
        }
        require(maximumExtenderCount in 0..ABSOLUTE_MAXIMUM_EXTENDER_COUNT) {
            "maximumExtenderCount must be between 0 and $ABSOLUTE_MAXIMUM_EXTENDER_COUNT"
        }
        require(
            maximumResolvedDimensionPx.isFinite() &&
                maximumResolvedDimensionPx > 0f &&
                maximumResolvedDimensionPx <= ABSOLUTE_MAXIMUM_RESOLVED_DIMENSION_PX,
        ) {
            "maximumResolvedDimensionPx must be finite, positive, and no greater than " +
                ABSOLUTE_MAXIMUM_RESOLVED_DIMENSION_PX
        }
    }

    companion object {
        private const val ABSOLUTE_MAXIMUM_SOURCE_LENGTH = 1_000_000
        private const val ABSOLUTE_MAXIMUM_TOKEN_COUNT = 250_000
        private const val ABSOLUTE_MAXIMUM_NODE_COUNT = 500_000
        private const val ABSOLUTE_MAXIMUM_RECURSION_DEPTH = 512
        private const val ABSOLUTE_MAXIMUM_BREAKPOINT_COUNT = 2_048
        private const val ABSOLUTE_MAXIMUM_EXTENDER_COUNT = 65_536
        private const val ABSOLUTE_MAXIMUM_RESOLVED_DIMENSION_PX = 1_000_000f

        val Default = MathResourceLimits()
    }
}

internal fun mathResourceLimitDiagnostic(
    code: DiagnosticCode,
    resource: String,
    actual: Number,
    limit: Number,
    range: SourceRange,
): MathDiagnostic = MathDiagnostic(
    code = code,
    message = "Math resource $resource=$actual exceeds limit $limit",
    range = range,
)

internal fun invalidResolvedDimensionDiagnostic(
    sourceText: String,
    resolvedPx: Float,
    maximumAbsolutePx: Float,
    range: SourceRange,
): MathDiagnostic = MathDiagnostic(
    code = DiagnosticCode.InvalidResolvedDimension,
    message = if (!resolvedPx.isFinite()) {
        "Math dimension $sourceText resolved to a non-finite pixel value"
    } else {
        "Math dimension $sourceText resolved to $resolvedPx px, exceeding absolute limit $maximumAbsolutePx px"
    },
    range = range,
)

/** Returns the first exact AST node/depth budget violation. */
internal fun inspectMathAstResources(
    root: MathNode,
    limits: MathResourceLimits,
): MathDiagnostic? {
    data class PendingNode(val node: MathNode, val depth: Int)

    val pending = mutableListOf(PendingNode(root, 1))
    var nodeCount = 1
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeAt(pending.lastIndex)
        node.forEachChild { child ->
            val childDepth = depth + 1
            if (childDepth > limits.maximumRecursionDepth) {
                return mathResourceLimitDiagnostic(
                    code = DiagnosticCode.RecursionDepthLimitExceeded,
                    resource = "recursionDepth",
                    actual = childDepth,
                    limit = limits.maximumRecursionDepth,
                    range = child.range,
                )
            }
            nodeCount += 1
            if (nodeCount > limits.maximumNodeCount) {
                return mathResourceLimitDiagnostic(
                    code = DiagnosticCode.AstNodeCountLimitExceeded,
                    resource = "nodeCount",
                    actual = nodeCount,
                    limit = limits.maximumNodeCount,
                    range = child.range,
                )
            }
            pending += PendingNode(child, childDepth)
        }
    }
    return null
}

private inline fun MathNode.forEachChild(block: (MathNode) -> Unit) {
    when (val node = this) {
        is MathList -> for (child in node.children) block(child)
        is MathGroup -> block(node.body)
        is MathBoxed -> {
            block(node.body)
            val terminalRowSeparator = node.terminalRowSeparator
            if (terminalRowSeparator != null) block(terminalRowSeparator)
        }
        is MathBbox -> block(node.body)
        is MathOperatorNoad -> block(node.nucleus)
        is MathModulo -> {
            val argument = node.argument
            if (argument != null) block(argument)
        }
        is MathAccent -> block(node.base)
        is MathBraceNoad -> block(node.base)
        is MathRuleDecoration -> block(node.base)
        is MathOverUnder -> {
            block(node.annotation)
            block(node.base)
        }
        is MathExtensibleArrow -> {
            block(node.above)
            val below = node.below
            if (below != null) block(below)
        }
        is MathNegation -> {
            block(node.base)
            for (space in node.interveningSpaces) block(space)
        }
        is MathLap -> block(node.body)
        is MathCancel -> block(node.body)
        is MathTable -> for (row in node.rows) {
            for (cell in row.cells) block(cell.body)
            val tag = row.tag
            if (tag != null) block(tag)
        }
        is MathDisplayEnvironment -> {
            block(node.body)
            val tag = node.tag
            if (tag != null) block(tag)
        }
        is MathTaggedEquation -> {
            block(node.body)
            block(node.tag)
        }
        is MathDisplayRows -> for (row in node.rows) {
            block(row.body)
            val tag = row.tag
            if (tag != null) block(tag)
        }
        is MathScripts -> {
            block(node.base)
            val superscript = node.superscript
            if (superscript != null) block(superscript)
            val subscript = node.subscript
            if (subscript != null) block(subscript)
        }
        is MathFraction -> {
            block(node.numerator)
            block(node.denominator)
        }
        is MathRadical -> {
            val degree = node.degree
            if (degree != null) block(degree)
            block(node.radicand)
        }
        is MathAlphabetScope -> block(node.body)
        is MathVersionScope -> block(node.body)
        is MathDelimited -> block(node.body)

        is MathColorDeclaration,
        is MathTexLogo,
        is MathSizeDeclaration,
        is MathSymbol,
        is MathOperator,
        is MathText,
        is MathOperatorName,
        is MathRuleBox,
        is MathExplicitSpace,
        is MathEquationTag,
        is MathExplicitRowBreak,
        is MathStyleDeclaration,
        is MathAlphabetDeclaration,
        is MathVersionDeclaration,
        is MathErrorNode,
        is MathFixedDelimiter,
        is MathMiddleDelimiter,
        -> Unit
    }
}
