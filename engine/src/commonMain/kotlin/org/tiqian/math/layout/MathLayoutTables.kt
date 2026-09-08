package org.tiqian.math.layout

import org.tiqian.math.core.*
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ALIGNED_ROW_GAP_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ALIGNED_PAIR_GAP_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_COLUMN_SEPARATION_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_INTERCOLUMN_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_ASCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_ARRAY_STRUT_DESCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_CASES_STRUT_ASCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_CASES_STRUT_DESCENT_EM
import org.tiqian.math.layout.MathLayoutPass.Companion.TEX_SMALL_MATRIX_LINE_SKIP_EM
import org.tiqian.math.layout.MathLayoutPass.LaidNode
import org.tiqian.math.layout.MathLayoutPass.MathAlphabetOverride

internal fun MathLayoutPass.layoutTable(
    node: MathTable,
    style: MathStyle,
    alphabetOverride: MathAlphabetOverride?,
    prelaidCells: Map<MathTableCell, MathBox> = emptyMap(),
): LaidNode {
    val substack = node.environment == MathTableEnvironment.Substack
    val smallMatrix = node.environment == MathTableEnvironment.SmallMatrix
    val gathered = node.environment == MathTableEnvironment.Gathered
    val preservesEntryStyle = node.environment in setOf(
        MathTableEnvironment.Aligned,
        MathTableEnvironment.Split,
    )
    // SingleRowAlignmentFlattensForResponsiveBreaking: a one-row align has nothing to align its
    // & against — the tab only fences the content into unbreakable cells. When the row cannot
    // fit the electronic viewport, the cells flatten into one list and take the responsive
    // break, whose relation anchor reproduces the align-at-= intent across the broken lines.
    // Multi-row alignments are genuine alignment groups and always keep table layout.
    if (preservesEntryStyle && softWrapDisplay && formulaMode == MathMode.Display &&
        node.rows.size == 1 && prelaidCells.isEmpty()
    ) {
        val viewport = displayWidthPx
        val row = node.rows.single()
        if (viewport != null && row.cells.isNotEmpty()) {
            val joined = MathList(
                children = row.cells.flatMap { it.body.children },
                range = node.range,
            )
            val overwide = probeLayout {
                layoutList(joined, style, alphabetOverride)
                    .laid.box.visualWidth > viewport + DISPLAY_GEOMETRY_EPSILON_PX
            }
            if (overwide) {
                taggedDisplayReplayExpected = row.tag != null
                val horizontal = layoutList(joined, style, alphabetOverride)
                val body = resolveSoftWrappedDisplayBody(
                    horizontal = horizontal,
                    style = style,
                    range = node.range,
                    targetWidthPx = viewport,
                    decisionName = "SingleRowAlignmentLineBreak",
                    recordTaggedDisplayBaseline = row.tag != null,
                    pinClausesToViewport = row.tag != null,
                )
                val completed = row.tag?.let { tag ->
                    completeTaggedEquationBox(
                        body = body,
                        tag = tag,
                        style = style,
                        range = node.range,
                        layoutRole = "SingleRowAlignment",
                        centeredBesideMultiline = false,
                        responsiveBodyViewportWidthPx = viewport,
                    )
                } ?: body
                return LaidNode(
                    node = node,
                    box = completed.copy(range = node.range),
                    atomClass = MathAtomClass.Ordinary,
                    italicCorrectionPx = 0f,
                    style = style,
                    scriptBaseKind = ScriptBaseKind.CompoundBox,
                )
            }
        }
    }
    val cellStyle = if (substack || smallMatrix) {
        MathStyle.Script
    } else if (gathered) {
        MathStyle.Display
    } else if (preservesEntryStyle) {
        style
    } else {
        when (style.level) {
            MathStyleLevel.Display, MathStyleLevel.Text -> MathStyle.Text
            MathStyleLevel.Script -> MathStyle.Script
            MathStyleLevel.ScriptScript -> MathStyle.ScriptScript
        }
    }
    val rowLayouts = node.rows.map { row ->
        row.cells.mapIndexed { column, cell ->
            prelaidCells[cell]?.let { return@mapIndexed it }
            val horizontal = layoutList(cell.body, cellStyle, alphabetOverride)
            val needsAlignedRelationAnchor = preservesEntryStyle && column % 2 == 1
            val preambleGlue = if (needsAlignedRelationAnchor) {
                horizontal.items.firstOrNull()?.let { first ->
                    atomGlue(MathAtomClass.Ordinary, first.atomClass, first.laid.style, cell.range).naturalPx
                } ?: 0f
            } else {
                0f
            }
            if (preambleGlue > 0f) {
                decision(
                    "TeXAlignedRightColumnPreamble",
                    cell.range,
                    "column" to column,
                    "firstAtomClass" to horizontal.items.first().atomClass,
                    "leadingGluePx" to preambleGlue,
                    "policy" to "AmsmathAlignedEmptyOrdBeforeRightColumn",
                )
                horizontal.laid.box.translated(preambleGlue, 0f).copy(
                    width = horizontal.laid.box.width + preambleGlue,
                    range = cell.range,
                )
            } else {
                horizontal.laid.box
            }
        }
    }
    val rowTagLayouts = node.rows.map { row -> row.tag?.let { layoutEquationTagBox(it, style) } }
    val columnCount = maxOf(
        node.columnAlignments.size,
        rowLayouts.maxOfOrNull { it.size } ?: 0,
    )
    val alignments = List(columnCount) { column ->
        node.columnAlignments.getOrNull(column) ?: when (node.environment) {
            MathTableEnvironment.Aligned,
            MathTableEnvironment.Split,
            -> if (column % 2 == 0) MathTableColumnAlignment.Right else MathTableColumnAlignment.Left
            MathTableEnvironment.Cases -> MathTableColumnAlignment.Left
            MathTableEnvironment.Gathered -> MathTableColumnAlignment.Center
            else -> MathTableColumnAlignment.Center
        }
    }
    val columnWidths = List(columnCount) { column ->
        rowLayouts.maxOfOrNull { row -> row.getOrNull(column)?.width ?: 0f } ?: 0f
    }
    val size = fontSize(cellStyle)
    val cases = node.environment == MathTableEnvironment.Cases
    val strutAscentEm = if (cases) TEX_CASES_STRUT_ASCENT_EM else TEX_ARRAY_STRUT_ASCENT_EM
    val strutDescentEm = if (cases) TEX_CASES_STRUT_DESCENT_EM else TEX_ARRAY_STRUT_DESCENT_EM
    val minimumRowAscent = if (substack || smallMatrix) 0f else strutAscentEm * size
    val minimumRowDescent = if (substack || smallMatrix) 0f else strutDescentEm * size
    val rowAdditionalSpacingPx = node.rows.map { row ->
        row.additionalSpacing?.let { resolveTeXDimension(it, size) } ?: 0f
    }
    val rowAscents = rowLayouts.mapIndexed { rowIndex, row ->
        maxOf(
            minimumRowAscent,
            row.maxOfOrNull { it.texCleanBoxMetrics.ascent } ?: 0f,
            rowTagLayouts[rowIndex]?.texCleanBoxMetrics?.ascent ?: 0f,
        )
    }
    val rowDescents = rowLayouts.mapIndexed { rowIndex, row ->
        val optionalStrutExtension = if (preservesEntryStyle) 0f else rowAdditionalSpacingPx[rowIndex]
        maxOf(
            minimumRowDescent + optionalStrutExtension,
            row.maxOfOrNull { it.texCleanBoxMetrics.descent } ?: 0f,
            rowTagLayouts[rowIndex]?.texCleanBoxMetrics?.descent ?: 0f,
        )
    }
    val arrayColumnSeparation = explicitArrayColumnSeparationPx ?: TEX_ARRAY_COLUMN_SEPARATION_EM * size
    val columnGaps = List((columnCount - 1).coerceAtLeast(0)) { boundary ->
        when (node.environment) {
            MathTableEnvironment.Matrix,
            MathTableEnvironment.SmallMatrix,
            MathTableEnvironment.ParenthesizedMatrix,
            MathTableEnvironment.BracketedMatrix,
            MathTableEnvironment.Determinant,
            MathTableEnvironment.Array,
            -> if (smallMatrix) {
                5f * fontSize(style) / 18f
            } else {
                arrayColumnSeparation * 2f
            }

            MathTableEnvironment.Aligned,
            MathTableEnvironment.Split,
            -> if (boundary % 2 == 1) TEX_ALIGNED_PAIR_GAP_EM * size else 0f

            MathTableEnvironment.Substack -> 0f

            else -> TEX_ARRAY_INTERCOLUMN_EM * size
        }
    }
    // DisplayRowJot: in the electronic-reading extension (softWrapDisplay) display alignment
    // rows share one inter-row leading whether the engine broke the lines or the author wrote
    // `\\`. Without it the tectonic-measured TeX gap stands, and matrices, substacks and small
    // matrices always keep their TeX-exact spacing.
    val alignedRowGapEm = if (softWrapDisplay) DISPLAY_ROW_JOT_EM else TEX_ALIGNED_ROW_GAP_EM
    val rowGapEm = if (preservesEntryStyle && node.rows.size > 1) alignedRowGapEm else 0f
    val baseRowGap = if (substack) {
        scale(constants.stackGapMin, MathStyle.Script)
    } else if (smallMatrix) {
        TEX_SMALL_MATRIX_LINE_SKIP_EM * fontSize(style)
    } else if (gathered && node.rows.size > 1) {
        alignedRowGapEm * fontSize(style)
    } else {
        rowGapEm * size
    }
    val rowGaps = List((node.rows.size - 1).coerceAtLeast(0)) { rowIndex ->
        baseRowGap + if (preservesEntryStyle) rowAdditionalSpacingPx[rowIndex] else 0f
    }
    val trailingExplicitRowSpacing = if (preservesEntryStyle) {
        rowAdditionalSpacingPx.lastOrNull() ?: 0f
    } else {
        0f
    }
    val horizontalRulesByBoundary = node.horizontalRules.groupBy { it.boundaryIndex }
    val horizontalRuleTotalHeight = node.horizontalRules.size * arrayRuleThicknessPx
    val outerPadding = when {
        node.environment == MathTableEnvironment.Array -> arrayColumnSeparation
        smallMatrix -> 3f * fontSize(style) / 18f
        else -> 0f
    }
    val bodyWidth = outerPadding * 2f + columnWidths.sum() + columnGaps.sum()
    val bodyHeight = rowAscents.zip(rowDescents).sumOf { (ascent, descent) ->
        (ascent + descent).toDouble()
    }.toFloat() + rowGaps.sum() + trailingExplicitRowSpacing + horizontalRuleTotalHeight
    val axisHeight = scale(constants.axisHeight, style)
    val bodyTop = -axisHeight - bodyHeight / 2f
    var rowTop = bodyTop
    val glyphs = mutableListOf<MathGlyphPlacement>()
    val hostTextRuns = mutableListOf<MathHostTextPlacement>()
    val rules = mutableListOf<MathRulePlacement>()
    val paintGroups = mutableListOf<MathConstructionPaintGroup>()
    val positionedChildren = mutableListOf<Pair<MathBox, Float>>()
    val rowBaselines = mutableListOf<Float>()
    val rowLogicalExtents = mutableListOf<ClosedFloatingPointRange<Float>>()
    fun placeHorizontalRules(boundaryIndex: Int) {
        horizontalRulesByBoundary[boundaryIndex].orEmpty().forEach { horizontalRule ->
            rules += MathRulePlacement(
                left = 0f,
                top = rowTop,
                right = bodyWidth,
                bottom = rowTop + arrayRuleThicknessPx,
                sourceRange = horizontalRule.commandRange,
            )
            decision(
                "LaTeXArrayHorizontalRule",
                horizontalRule.commandRange,
                "environment" to node.environment,
                "boundaryIndex" to boundaryIndex,
                "leftPx" to 0f,
                "rightPx" to bodyWidth,
                "topPx" to rowTop,
                "bottomPx" to (rowTop + arrayRuleThicknessPx),
                "thicknessPx" to arrayRuleThicknessPx,
                "parameter" to "arrayRuleThicknessPx",
                "policy" to "LaTeXArrayHlineFullPreambleWidth",
            )
            rowTop += arrayRuleThicknessPx
        }
    }
    rowLayouts.forEachIndexed { rowIndex, row ->
        placeHorizontalRules(rowIndex)
        val baselineY = rowTop + rowAscents[rowIndex]
        rowBaselines += baselineY
        var columnLeft = outerPadding
        var rowLogicalLeft = Float.POSITIVE_INFINITY
        var rowLogicalRight = Float.NEGATIVE_INFINITY
        row.forEachIndexed { column, cell ->
            val offset = when (alignments[column]) {
                MathTableColumnAlignment.Left -> 0f
                MathTableColumnAlignment.Center -> (columnWidths[column] - cell.width) / 2f
                MathTableColumnAlignment.Right -> columnWidths[column] - cell.width
            }
            val cellLeft = columnLeft + offset
            rowLogicalLeft = minOf(rowLogicalLeft, cellLeft)
            rowLogicalRight = maxOf(rowLogicalRight, cellLeft + cell.width)
            val shifted = cell.translated(cellLeft, baselineY)
            glyphs += shifted.glyphs
            hostTextRuns += shifted.hostTextRuns
            rules += shifted.rules
            paintGroups += shifted.constructionPaintGroups
            positionedChildren += cell to baselineY
            columnLeft += columnWidths[column] + columnGaps.getOrElse(column) { 0f }
        }
        rowLogicalExtents += if (rowLogicalLeft.isFinite() && rowLogicalRight.isFinite()) {
            rowLogicalLeft..rowLogicalRight
        } else {
            0f..0f
        }
        rowTop += rowAscents[rowIndex] + rowDescents[rowIndex] +
            rowGaps.getOrElse(rowIndex) { 0f }
    }
    placeHorizontalRules(node.rows.size)
    val bodyBottom = bodyTop + bodyHeight
    val paintedBody = geometryExtents(
        bodyWidth,
        glyphs,
        rules,
        node.range,
        paintGroups,
        hostTextRuns,
    )
    val axisCenteredBodyBox = paintedBody.copy(
        ascent = (-bodyTop).coerceAtLeast(0f),
        descent = bodyBottom.coerceAtLeast(0f),
        texCleanBoxMetrics = MathTeXCleanBoxMetrics(
            ascent = (-bodyTop).coerceAtLeast(0f),
            descent = bodyBottom.coerceAtLeast(0f),
            policy = MathTeXCleanBoxPolicy.CompletedLayoutBox,
            evidence = positionedChildren.flatMap { it.first.texCleanBoxMetrics.evidence }.toSet() +
                MathTeXCleanBoxEvidence.CompletedChildBox,
        ),
    )
    // Baseline exposure applies to the top-level inline line regardless of explicit style: the
    // default text style and an author's \displaystyle are the two uncramped Text/Display styles a
    // top-level inline line ever carries. Genuine script nesting (Script/ScriptScript) and cramped
    // nestings (under a radical, in a fraction) stay axis-vcentered — they are not the inline line.
    val usesSingleRowInlineBaseline =
        node.environment == MathTableEnvironment.Aligned &&
            formulaMode == MathMode.Inline &&
            (style == MathStyle.Text || style == MathStyle.Display) &&
            node.rows.size == 1 &&
            // A tagged row is a display-equation construct; its tag is completed against the
            // axis-centered frame below, so rebasing the body out from under it would misplace the
            // tag. Such a row keeps the axis-centered box.
            node.rows.first().tag == null &&
            // Defensive: a degenerate empty row produces no baseline — keep axis-centered, not crash.
            rowBaselines.isNotEmpty()
    val bodyBox = if (usesSingleRowInlineBaseline) {
        val rowBaseline = rowBaselines.first()
        val rebasedAscent = (axisCenteredBodyBox.ascent + rowBaseline).coerceAtLeast(0f)
        val rebasedDescent = (axisCenteredBodyBox.descent - rowBaseline).coerceAtLeast(0f)
        decision(
            "SingleRowInlineAlignmentBaseline",
            node.range,
            "rowBaselineBeforePx" to rowBaseline,
            "appliedShiftPx" to -rowBaseline,
            "ascentAfterPx" to rebasedAscent,
            "descentAfterPx" to rebasedDescent,
            "policy" to "ExposeOnlyRowBaselineToInlineHost",
        )
        axisCenteredBodyBox.translated(0f, -rowBaseline).copy(
            ascent = rebasedAscent,
            descent = rebasedDescent,
            texCleanBoxMetrics = axisCenteredBodyBox.texCleanBoxMetrics.copy(
                ascent = rebasedAscent,
                descent = rebasedDescent,
            ),
        )
    } else {
        axisCenteredBodyBox
    }
    val fenced = wrapTableDelimiters(node, bodyBox, style)
    val completed = if (node.rows.any { it.tag != null }) {
        completeTaggedRows(
            body = fenced,
            rows = node.rows,
            tagBoxes = rowTagLayouts,
            rowBaselines = rowBaselines,
            rowLogicalExtents = rowLogicalExtents,
            range = node.range,
            layoutRole = "AlignmentRows",
        )
    } else {
        fenced
    }
    decision(
        "TeXMathTable",
        node.range,
        "environmentName" to node.environmentName,
        "environment" to node.environment,
        "rowCount" to node.rows.size,
        "columnCount" to columnCount,
        "columnAlignments" to alignments.joinToString(","),
        "columnWidthsPx" to columnWidths.joinToString(","),
        "rowAscentsPx" to rowAscents.joinToString(","),
        "rowDescentsPx" to rowDescents.joinToString(","),
        "rowMetricPolicy" to "MaxOfTeXCleanCellBoxAndEnvironmentStrut",
        "cellStyle" to cellStyle,
        "axisHeightPx" to axisHeight,
        "arrayStrutAscentEm" to strutAscentEm,
        "arrayStrutDescentEm" to strutDescentEm,
        "interColumnPolicy" to if (preservesEntryStyle) {
            "ZeroWithinPairAndTwoEmBetweenEquationPairs"
        } else {
            "ArrayColumnSeparationOrEnvironmentGap"
        },
        "arrayColumnSeparationPx" to arrayColumnSeparation,
        "columnGapsPx" to columnGaps.joinToString(","),
        "rowGapEm" to rowGapEm,
        "baseRowGapPx" to baseRowGap,
        "substackRowGapParameter" to if (substack) "fontdimen10-scriptfont-symbols/StackGapMin" else null,
        "smallMatrixOuterPaddingMu" to if (smallMatrix) 3f else null,
        "smallMatrixInterColumnMu" to if (smallMatrix) 5f else null,
        "smallMatrixLineSkipEm" to if (smallMatrix) TEX_SMALL_MATRIX_LINE_SKIP_EM else null,
        "rowAdditionalSpacingPx" to rowAdditionalSpacingPx.joinToString(","),
        "rowGapsPx" to rowGaps.joinToString(","),
        "trailingExplicitRowSpacingPx" to trailingExplicitRowSpacing,
        "rowSpacingPolicy" to if (preservesEntryStyle && softWrapDisplay) {
            "DisplayRowJotInterRowGlue"
        } else if (preservesEntryStyle) {
            "AmsmathExtraInterRowGlue"
        } else {
            "LaTeXArrayPreviousRowStrutDepthExtension"
        },
        "horizontalRuleCount" to node.horizontalRules.size,
        "horizontalRuleBoundaries" to node.horizontalRules.joinToString(",") { it.boundaryIndex.toString() },
        "arrayRuleThicknessPx" to arrayRuleThicknessPx,
        "horizontalRulePolicy" to "LaTeXArrayHlineFullPreambleWidth",
        "bodyWidthPx" to bodyWidth,
        "bodyAscentPx" to bodyBox.ascent,
        "bodyDescentPx" to bodyBox.descent,
        "baselinePolicy" to if (usesSingleRowInlineBaseline) {
            "SingleRowInlineAlignmentBaseline"
        } else {
            "AxisCenteredVcenter"
        },
        "logicalAdvancePx" to completed.width,
        "groupBreakPolicy" to "UnbreakableTeXTableInnerNoad",
        "policy" to when {
            substack -> "AmsmathSubarrayScriptStyleFontdimen10BaselineSkipAndAxisCenteredVcenter"
            smallMatrix -> "AmsmathSmallMatrixScriptStyleThreeMuOuterFiveMuColumnsAndLineSkip"
            gathered -> "AmsmathGatheredDisplayStyleCenteredRowsAndArrayStrut"
            else -> "LaTeXEnvironmentSpecificStyleArrayStrutAndAxisCenteredVcenter"
        },
    )
    return LaidNode(
        node,
        completed,
        MathAtomClass.Inner,
        0f,
        style,
        ScriptBaseKind.CompoundBox,
    )
}

internal fun MathLayoutPass.layoutDisplayEnvironment(
    node: MathDisplayEnvironment,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val displayStyle = MathStyle.Display
    val body = layoutNode(node.body, displayStyle, alphabetOverride)
    val explicitTag = node.tag
    val box = if (explicitTag == null) {
        body.box.copy(range = node.range)
    } else {
        completeTaggedEquationBox(
            body = body.box,
            tag = explicitTag,
            style = displayStyle,
            range = node.range,
            layoutRole = "SingleDisplayEnvironment",
            centeredBesideMultiline = node.body.isSplitDisplayBody(),
            shiftedTagMustClearCompletedBody = node.body.isCompletedBoxedField(),
        )
    }
    decision(
        "MarkdownMathDisplayEnvironment",
        node.range,
        "environment" to node.kind.sourceName,
        "layoutRole" to if (node.kind.alignment) "DisplayAlignment" else "SingleDisplayEquation",
        "entryStyle" to displayStyle,
        "sourceRequestsNumbering" to node.kind.sourceRequestsNumbering,
        "numberingPolicy" to "SuppressedByMarkdownFormulaHost",
        "explicitTag" to (node.tag != null),
        "atomClass" to "NoneAtDocumentLevel",
        "groupBreakPolicy" to "ExplicitRowsOnly",
        "policy" to "AmsmathDisplayWrapperWithIntrinsicFormulaBox",
    )
    return LaidNode(
        node = node,
        box = box,
        atomClass = MathAtomClass.Ordinary,
        italicCorrectionPx = 0f,
        style = displayStyle,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

private fun MathNode.isSplitDisplayBody(): Boolean = when (this) {
    is MathTable -> environment == MathTableEnvironment.Split
    is MathList -> children.singleOrNull()?.isSplitDisplayBody() == true
    is MathGroup -> body.isSplitDisplayBody()
    else -> false
}

private fun MathNode.isCompletedBoxedField(): Boolean = when (this) {
    is MathBoxed -> true
    is MathList -> children.singleOrNull()?.isCompletedBoxedField() == true
    is MathGroup -> body.isCompletedBoxedField()
    else -> false
}

/**
 * OperatorJunctionRowsRejoin: TeX has no automatic display line breaking, so an author's `\\`
 * inside a single formula is a print-width workaround — recognizable by a binary or relation
 * operator at the junction (the previous row trails with one, or the next row leads with one).
 * Under the electronic-reading extension such rows are rejoined into one logical formula and
 * re-broken responsively for the actual viewport; the junction's row separator and explicit
 * `\\[dim]` spacing are print hints subsumed by that re-break. A junction without an explicit
 * operator separates genuinely parallel formulas and is always preserved, as is any junction
 * after a tagged row — the tag completes its formula.
 */
private fun joinedRowCovers(joinedRows: List<MathDisplayRow>, row: MathDisplayRow): Boolean =
    joinedRows.any { joined ->
        joined.range.start <= row.range.start && joined.range.endExclusive >= row.range.endExclusive &&
            (joined.range.start < row.range.start || joined.range.endExclusive > row.range.endExclusive)
    }

private fun rejoinOperatorJunctionRows(authorRows: List<MathDisplayRow>): List<MathDisplayRow> {
    fun MathList.edgeOperator(last: Boolean): Boolean {
        val meaningful = children.filter { it !is MathStyleDeclaration && it !is MathAlphabetDeclaration }
        val edge = if (last) meaningful.lastOrNull() else meaningful.firstOrNull()
        if (edge !is MathSymbol) return false
        // A trailing comma is a clause junction: the author split one formula from its condition
        // for print width; rejoined, the comma becomes a responsive clause boundary again.
        if (last && edge.atomClass == MathAtomClass.Punctuation) return true
        return edge.atomClass == MathAtomClass.Binary || edge.atomClass == MathAtomClass.Relation
    }

    val joined = mutableListOf<MathDisplayRow>()
    authorRows.forEach { row ->
        val previous = joined.lastOrNull()
        val operatorJunction = previous != null && previous.tag == null &&
            (previous.body.edgeOperator(last = true) || row.body.edgeOperator(last = false))
        if (operatorJunction) {
            joined[joined.lastIndex] = MathDisplayRow(
                body = MathList(
                    children = checkNotNull(previous).body.children + row.body.children,
                    range = SourceRange(previous.body.range.start, row.body.range.endExclusive),
                ),
                rowSeparatorRange = row.rowSeparatorRange,
                additionalSpacing = row.additionalSpacing,
                range = SourceRange(previous.range.start, row.range.endExclusive),
                tag = row.tag,
            )
        } else {
            joined += row
        }
    }
    return joined
}

internal fun MathLayoutPass.layoutDisplayRows(
    node: MathDisplayRows,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    if (formulaMode != MathMode.Display) {
        diagnostics += MathDiagnostic(
            DiagnosticCode.ExplicitMultilineRequiresDisplay,
            "Top-level \\\\ rows require display math mode",
            node.range,
        )
    }
    val authorRows = node.rows
    // Rejoin is a repair, not a normalization: author rows stand whenever they all fit the
    // viewport, and rejoin fires only when some author row cannot. The probe layout below is
    // measurement-only — its decisions and diagnostics are rolled back, and the surviving
    // pipeline lays whichever row set wins exactly once.
    val joinedRows = if (softWrapDisplay && authorRows.size > 1) {
        rejoinOperatorJunctionRows(authorRows)
    } else {
        authorRows
    }
    val rows = if (joinedRows.size < authorRows.size) {
        val viewport = displayWidthPx
        val someAuthorRowOverflows = viewport != null && probeLayout {
            // Only rows inside a joinable run can change the outcome; rows outside every run wrap
            // internally the same way whether or not the runs rejoin.
            val runMembers = authorRows.filter { joinedRowCovers(joinedRows, it) }.ifEmpty { authorRows }
            runMembers.any { row ->
                layoutList(row.body, MathStyle.Display, alphabetOverride)
                    .laid.box.visualWidth > viewport + DISPLAY_GEOMETRY_EPSILON_PX
            }
        }
        if (someAuthorRowOverflows) joinedRows else authorRows
    } else {
        authorRows
    }
    var carriedStyle: MathStyleDeclaration? = null
    var carriedAlphabet: MathAlphabetDeclaration? = null
    val tableRows = rows.map { row ->
        val inherited = listOfNotNull(carriedStyle, carriedAlphabet)
        val breakableBody = unwrapWholeFormulaGroups(row.body)
        val syntheticBody = MathList(
            children = inherited + breakableBody.children,
            range = row.body.range,
        )
        row.body.children.forEach { child ->
            when (child) {
                is MathStyleDeclaration -> carriedStyle = child
                is MathAlphabetDeclaration -> carriedAlphabet = child
                else -> Unit
            }
        }
        MathTableRow(
            cells = listOf(MathTableCell(syntheticBody, range = row.body.range)),
            rowSeparatorRange = row.rowSeparatorRange,
            additionalSpacing = row.additionalSpacing,
            range = row.range,
            tag = row.tag,
        )
    }
    val syntheticTable = MathTable(
        environmentName = "markdown-display-rows",
        environment = MathTableEnvironment.Aligned,
        rows = tableRows,
        columnAlignments = listOf(MathTableColumnAlignment.Center),
        beginCommandRange = SourceRange(node.range.start, node.range.start),
        beginNameRange = SourceRange(node.range.start, node.range.start),
        endCommandRange = null,
        endNameRange = null,
        range = node.range,
    )
    val prelaidCells = mutableMapOf<MathTableCell, MathBox>()
    var responsiveRowCount = 0
    tableRows.forEachIndexed { rowIndex, tableRow ->
        val cell = tableRow.cells.single()
        // The single tagged row is completed by completeTaggedEquationBox below, which drains
        // pinned clauses; only under that guarantee may nested producers pin.
        taggedDisplayReplayExpected = rows.size == 1 && rows[rowIndex].tag != null
        val horizontal = layoutList(cell.body, MathStyle.Display, alphabetOverride)
        val unbroken = horizontal.laid.box
        val viewportWidth = displayWidthPx
        val sourceRow = rows[rowIndex]
        val sourceTag = sourceRow.tag
        // An overwide row wraps within itself regardless of a row tag: the author's row
        // structure is preserved, and completeTaggedRows already clears a shifted tag below the
        // completed table bottom, which covers a row that became a multi-line block.
        val mayWrapThisRow = softWrapDisplay && viewportWidth != null &&
            unbroken.visualWidth > viewportWidth + DISPLAY_GEOMETRY_EPSILON_PX
        val resolved = if (mayWrapThisRow) {
            responsiveRowCount++
            resolveSoftWrappedDisplayBody(
                horizontal = horizontal,
                style = MathStyle.Display,
                range = sourceRow.body.range,
                targetWidthPx = viewportWidth,
                decisionName = if (sourceTag == null) {
                    "ExplicitDisplayRowLineBreak"
                } else {
                    "TaggedDisplayBodyLineBreak"
                },
                recordTaggedDisplayBaseline = sourceTag != null,
                // Only a single-row display owns the tagged replay that can host pinned clauses;
                // rows inside a multi-row table keep their clause lines in the scrolled body.
                pinClausesToViewport = rows.size == 1 && sourceTag != null,
            )
        } else {
            unbroken
        }
        if (mayWrapThisRow && rows.size == 1 && sourceTag != null) {
            val completed = completeTaggedEquationBox(
                body = resolved,
                tag = sourceTag,
                style = MathStyle.Display,
                range = node.range,
                layoutRole = "MarkdownExplicitDisplayRows",
                centeredBesideMultiline = false,
                responsiveBodyViewportWidthPx = viewportWidth,
                shiftedTagMustClearCompletedBody = sourceRow.body.isCompletedBoxedField(),
            )
            explicitDisplayRowsDecision(node, rows.size, responsiveRowCount)
            return LaidNode(
                node = node,
                box = completed.copy(range = node.range),
                atomClass = MathAtomClass.Ordinary,
                italicCorrectionPx = 0f,
                style = MathStyle.Display,
                scriptBaseKind = ScriptBaseKind.CompoundBox,
            )
        }
        prelaidCells[cell] = resolved
    }
    val table = layoutTable(
        syntheticTable,
        MathStyle.Display,
        alphabetOverride,
        prelaidCells,
    )
    explicitDisplayRowsDecision(node, rows.size, responsiveRowCount)
    return table.copy(
        node = node,
        box = table.box.copy(range = node.range),
        atomClass = MathAtomClass.Ordinary,
        style = MathStyle.Display,
        scriptBaseKind = ScriptBaseKind.CompoundBox,
    )
}

private fun MathLayoutPass.explicitDisplayRowsDecision(
    node: MathDisplayRows,
    layoutRowCount: Int,
    responsiveRowCount: Int,
) {
    decision(
        "MarkdownExplicitDisplayRows",
        node.range,
        "authorRowCount" to node.rows.size,
        "rowCount" to layoutRowCount,
        "rowJoinPolicy" to if (layoutRowCount < node.rows.size) {
            "OperatorJunctionRowsRejoin"
        } else {
            "AuthorRowsPreserved"
        },
        "entryMode" to formulaMode,
        "entryStyle" to MathStyle.Display,
        "rowAlignment" to "CenteredIndependentlyAtMaximumAdvance",
        "rowKernel" to "AmsmathAlignedStrutAndBaselineGap",
        "styleDeclarationPolicy" to "ContainingListDeclarationsCarryAcrossRows",
        "trailingSeparatorCreatesVisibleRow" to false,
        "responsiveRowCount" to responsiveRowCount,
        "groupBreakPolicy" to if (responsiveRowCount > 0) {
            "ExplicitRowsWithResponsiveLegalBreaksInsideOverwideRows"
        } else {
            "ExplicitRowsOnlyNoAutomaticInternalBreak"
        },
        "dialect" to "MarkdownDisplayKaTeXCompatibilityExtension",
    )
}

internal fun MathLayoutPass.layoutTaggedEquation(
    node: MathTaggedEquation,
    alphabetOverride: MathAlphabetOverride?,
): LaidNode {
    val style = MathStyle.Display
    val tagBox = layoutEquationTagBox(node.tag, style)
    taggedDisplayReplayExpected = true
    val bodyLayout = layoutList(node.body, style, alphabetOverride)
    val body = resolveSoftWrappedDisplayBody(
        horizontal = bodyLayout,
        style = style,
        range = node.body.range,
        targetWidthPx = displayWidthPx,
        decisionName = "TaggedDisplayBodyLineBreak",
        recordTaggedDisplayBaseline = true,
        pinClausesToViewport = true,
    )
    val box = completeTaggedEquationBox(
        body = body,
        tag = node.tag,
        tagBox = tagBox,
        style = style,
        range = node.range,
        layoutRole = "TopLevelMarkdownDisplay",
        centeredBesideMultiline = false,
        responsiveBodyViewportWidthPx = displayWidthPx.takeIf { softWrapDisplay },
        shiftedTagMustClearCompletedBody = node.body.isCompletedBoxedField(),
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

/**
 * Automatic display wrapping is a Tiqian presentation extension, not TeX source rewriting.
 * Binary/relation operators begin continuation lines. An indivisible segment may still be wider
 * than the viewport and is left intact for overflow replay.
 */
