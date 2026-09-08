package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathResourceLimits
import kotlin.math.abs
import kotlin.math.ceil

data class OpenTypeMathFont(
    val bytes: ByteArray,
    val unitsPerEm: Int,
    val lineMetrics: OpenTypeLineMetrics,
    /** OS/2.sxHeight in design units when the font provides the version-2 metric. */
    val xHeight: Int? = null,
    val constants: OpenTypeMathConstants,
    val italicCorrections: Map<UShort, Int>,
    val italicCorrectionDeviceAdjustments: Map<UShort, MathDeviceAdjustment> = emptyMap(),
    val unsupportedItalicCorrectionVariationAdjustments: Set<UShort> = emptySet(),
    val extendedShapeGlyphs: Set<UShort>,
    val mathKernInfo: Map<UShort, MathGlyphKernInfo>,
    val verticalConstructions: Map<UShort, MathGlyphConstruction>,
    val topAccentAttachments: Map<UShort, Int> = emptyMap(),
    val topAccentAttachmentDeviceAdjustments: Map<UShort, MathDeviceAdjustment> = emptyMap(),
    val unsupportedTopAccentAttachmentVariationAdjustments: Set<UShort> = emptySet(),
    val horizontalConstructions: Map<UShort, MathGlyphConstruction> = emptyMap(),
    /** Unicode scalar to the font's base cmap glyph. */
    val characterGlyphs: Map<Int, UShort> = emptyMap(),
    /** Base glyph to ordered OpenType `ssty` alternates (feature values 1, 2, ...). */
    val scriptStyleAlternates: Map<UShort, List<UShort>> = emptyMap(),
) {
    fun scaleDesignUnits(value: Int, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun scaleDesignUnits(value: Float, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun glyphForScalar(scalar: Int, scriptStyleLevel: Int = 0): UShort? {
        val base = characterGlyphs[scalar] ?: return null
        if (scriptStyleLevel <= 0) return base
        val alternates = scriptStyleAlternates[base].orEmpty()
        return alternates.getOrNull(scriptStyleLevel - 1) ?: alternates.lastOrNull() ?: base
    }

    fun italicCorrection(glyphId: UShort, fontSizePx: Float): Float {
        if (glyphId in unsupportedItalicCorrectionVariationAdjustments) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "MathItalicsCorrectionInfo[$glyphId] requires an unsupported VariationIndex adjustment",
            )
        }
        return scaleDesignUnits(italicCorrections[glyphId] ?: 0, fontSizePx)
    }

    fun mathKern(
        glyphId: UShort,
        corner: MathKernCorner,
        correctionHeightPx: Float,
        fontSizePx: Float,
    ): Float {
        val table = mathKernInfo[glyphId]?.table(corner) ?: return 0f
        val heightDesignUnits = correctionHeightPx * unitsPerEm / fontSizePx
        return scaleDesignUnits(table.valueAt(heightDesignUnits), fontSizePx)
    }

    fun topAccentAttachment(
        glyphId: UShort,
        fontSizePx: Float,
        fallbackAdvancePx: Float,
    ): Float {
        if (glyphId in unsupportedTopAccentAttachmentVariationAdjustments) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "MathTopAccentAttachment[$glyphId] requires an unsupported VariationIndex adjustment",
            )
        }
        return topAccentAttachments[glyphId]?.let { scaleDesignUnits(it, fontSizePx) }
            ?: fallbackAdvancePx / 2f
    }

    fun horizontalConstruction(
        request: MathHorizontalConstructionRequest,
        glyphGrowthExtentPx: (UShort) -> Float,
        glyphOrthogonalExtentPx: (UShort) -> Float,
    ): MathVerticalConstruction? {
        val resourceLimits = request.resourceLimits
        val target = resolvedConstructionDesignUnits(
            valuePx = request.targetSizePx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "horizontal construction target",
        )
        val normalGlyphAdvance = resolvedConstructionDesignUnits(
            valuePx = request.normalGlyphWidthPx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "horizontal normal glyph width",
        )
        val construction = horizontalConstructions[request.baseGlyphId] ?: return null
        val firstAtOrAbove = construction.variants.indexOfFirst { it.advanceMeasurement >= target }
        if (firstAtOrAbove >= 0) {
            // XeTeX make_math_accent keeps the largest variant whose advance does not exceed
            // the clean nucleus width. The first wider variant terminates the search without
            // being selected.
            val variant = construction.variants.take(firstAtOrAbove + 1)
                .lastOrNull { it.advanceMeasurement <= target }
            if (variant == null || variant.glyphId == request.baseGlyphId) {
                return MathVerticalConstruction(
                    kind = MathConstructionKind.BaseGlyph,
                    components = listOf(MathGlyphComponent(request.baseGlyphId, 0f)),
                    advanceMeasurement = normalGlyphAdvance,
                    reachesTarget = request.normalGlyphWidthPx >= request.targetSizePx,
                    constructionPolicy = "XeTeXMathAccentNormalGlyphBeforeFirstWiderVariant",
                    orthogonalAdvancePx = request.normalGlyphOrthogonalExtentPx,
                )
            }
            return MathVerticalConstruction(
                kind = MathConstructionKind.Variant,
                components = listOf(MathGlyphComponent(variant.glyphId, 0f)),
                advanceMeasurement = variant.advanceMeasurement.toFloat(),
                reachesTarget = variant.advanceMeasurement >= target,
                constructionPolicy = "XeTeXMathAccentLargestVariantNotWiderThanNucleus",
                orthogonalAdvancePx = glyphOrthogonalExtentPx(variant.glyphId),
            )
        }
        val assembly = construction.assembly
        val validation = assembly?.let(::validateAssembly)
        if (assembly != null && validation?.valid == true) {
            return assembleXeTeX(
                assembly = assembly,
                validation = validation,
                target = target,
                fontSizePx = request.fontSizePx,
                glyphVerticalExtentPx = glyphGrowthExtentPx,
                glyphAdvanceWidthPx = glyphOrthogonalExtentPx,
                resourceLimits = resourceLimits,
            ).copy(
                constructionPolicy = "Tectonic0.17.0XeTeXHorizontalAccentAssemblyStretchGlue",
            )
        }
        val last = construction.variants.lastOrNull() ?: return null
        return MathVerticalConstruction(
            kind = MathConstructionKind.Variant,
            components = listOf(MathGlyphComponent(last.glyphId, 0f)),
            advanceMeasurement = last.advanceMeasurement.toFloat(),
            reachesTarget = last.advanceMeasurement >= target,
            assemblyValidation = validation,
            constructionPolicy = if (validation?.valid == false) {
                "XeTeXMathAccentLastVariantAfterInvalidAssembly"
            } else {
                "XeTeXMathAccentLastVariantWithoutAssembly"
            },
            orthogonalAdvancePx = glyphOrthogonalExtentPx(last.glyphId),
        )
    }

    fun verticalConstruction(
        request: MathVerticalConstructionRequest,
        glyphVerticalExtentPx: ((UShort) -> Float)? = null,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction? {
        val resourceLimits = request.resourceLimits
        val target = resolvedConstructionDesignUnits(
            valuePx = request.targetSizePx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "vertical construction target",
        )
        val normalGlyphAdvance = resolvedConstructionDesignUnits(
            valuePx = request.normalGlyphHeightPx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "vertical normal glyph height",
        )
        if (request.normalGlyphHeightPx >= request.targetSizePx) {
            return MathVerticalConstruction(
                kind = MathConstructionKind.BaseGlyph,
                components = listOf(MathGlyphComponent(request.baseGlyphId, 0f)),
                advanceMeasurement = normalGlyphAdvance,
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2NormalGlyph",
                orthogonalAdvancePx = request.normalGlyphAdvanceWidthPx,
            )
        }
        val construction = verticalConstructions[request.baseGlyphId] ?: return null
        construction.variants.firstOrNull { it.advanceMeasurement >= target }?.let { variant ->
            return MathVerticalConstruction(
                MathConstructionKind.Variant,
                listOf(MathGlyphComponent(variant.glyphId, 0f)),
                variant.advanceMeasurement.toFloat(),
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2Variant",
                orthogonalAdvancePx = glyphAdvanceWidthPx(variant.glyphId),
            )
        }
        val assembly = construction.assembly
        val assemblyValidation = assembly?.let(::validateAssembly)
        if (assembly != null && assemblyValidation?.valid == true) {
            return when (request.assemblyPolicy) {
                MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap ->
                    assemble(
                        assembly,
                        assemblyValidation,
                        target,
                        glyphAdvanceWidthPx,
                        resourceLimits,
                    )
                MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue ->
                    assembleXeTeX(
                        assembly = assembly,
                        validation = assemblyValidation,
                        target = target,
                        fontSizePx = request.fontSizePx,
                        glyphVerticalExtentPx = checkNotNull(glyphVerticalExtentPx) {
                            "XeTeX assembly requires exact glyph vertical extents"
                        },
                        glyphAdvanceWidthPx = glyphAdvanceWidthPx,
                        resourceLimits = resourceLimits,
                    )
            }
        }
        val last = construction.variants.lastOrNull() ?: return null
        return MathVerticalConstruction(
            MathConstructionKind.Variant,
            listOf(MathGlyphComponent(last.glyphId, 0f)),
            last.advanceMeasurement.toFloat(),
            reachesTarget = last.advanceMeasurement >= target,
            assemblyValidation = assemblyValidation,
            constructionPolicy = if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2LastVariantAfterInvalidAssembly"
            } else {
                "MathMLCore5.3.2LastVariant"
            },
            orthogonalAdvancePx = glyphAdvanceWidthPx(last.glyphId),
        )
    }

    /**
     * Checks whether a selection plan exists within the request budget, without materializing
     * assembly parts.
     */
    fun verticalConstructionAvailable(request: MathVerticalConstructionRequest): Boolean {
        val resourceLimits = request.resourceLimits
        val target = resolvedConstructionDesignUnits(
            valuePx = request.targetSizePx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "vertical construction target",
        )
        resolvedConstructionDesignUnits(
            valuePx = request.normalGlyphHeightPx,
            fontSizePx = request.fontSizePx,
            resourceLimits = resourceLimits,
            label = "vertical normal glyph height",
        )
        if (request.normalGlyphHeightPx >= request.targetSizePx) return true

        val construction = verticalConstructions[request.baseGlyphId] ?: return false
        if (construction.variants.any { it.advanceMeasurement >= target }) return true

        val assembly = construction.assembly
        val validation = assembly?.let(::validateAssembly)
        if (assembly != null && validation?.valid == true) {
            when (request.assemblyPolicy) {
                MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap ->
                    mathMlExtenderRepetitions(assembly, validation, target, resourceLimits)
                MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue ->
                    xeTeXExtenderRepetitions(assembly, validation, target, resourceLimits)
            }
            return true
        }
        return false
    }

    fun verticalAssemblyValidation(baseGlyphId: UShort): MathGlyphAssemblyValidation? =
        verticalConstructions[baseGlyphId]?.assembly?.let(::validateAssembly)

    private fun validateAssembly(assembly: MathGlyphAssembly): MathGlyphAssemblyValidation {
        val extenders = assembly.parts.filter { it.extender }
        val extenderAdvance = extenders.sumOf { it.fullAdvance.toLong() }
        val nonOverlappingAdvance = extenderAdvance -
            assembly.minimumConnectorOverlap.toLong() * extenders.size
        // Only connector ends that can meet another part are constrained. The bottom
        // non-extender's outer start and top non-extender's outer end are not connections;
        // real Lete/STIX constructions intentionally store zero there.
        val adjacentConnections = assembly.parts.zipWithNext()
        val shortAdjacentConnector = adjacentConnections.any { (lower, upper) ->
            lower.endConnectorLength < assembly.minimumConnectorOverlap ||
                upper.startConnectorLength < assembly.minimumConnectorOverlap
        }
        // An extender can be repeated next to itself even when it appears only once in the
        // source part list, so both of its connector ends participate in a possible join.
        val shortExtenderSelfConnector = extenders.any {
            it.startConnectorLength < assembly.minimumConnectorOverlap ||
                it.endConnectorLength < assembly.minimumConnectorOverlap
        }
        val reasons = buildSet {
            if (extenders.isEmpty()) add(MathGlyphAssemblyInvalidReason.NoExtender)
            if (nonOverlappingAdvance <= 0L) {
                add(MathGlyphAssemblyInvalidReason.NonPositiveExtenderGrowth)
            }
            if (shortAdjacentConnector || shortExtenderSelfConnector) {
                add(MathGlyphAssemblyInvalidReason.ConnectorShorterThanMinimumOverlap)
            }
        }
        return MathGlyphAssemblyValidation(
            valid = reasons.isEmpty(),
            invalidReasons = reasons,
            extenderCount = extenders.size,
            nonExtenderCount = assembly.parts.size - extenders.size,
            extenderNonOverlappingAdvance = nonOverlappingAdvance,
            checkedConnectionCount = adjacentConnections.size + extenders.size,
            validationPolicy = "TiqianOpenTypeTerminalConnectorCompatibility",
            specificationDivergence =
                "MathMLCore5.3.1RequiresEveryTerminalConnectorAtLeastMinimum",
        )
    }

    private fun assemble(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        glyphAdvanceWidthPx: (UShort) -> Float,
        resourceLimits: MathResourceLimits,
    ): MathVerticalConstruction {
        check(validation.valid)
        val rMin = mathMlExtenderRepetitions(assembly, validation, target, resourceLimits)
        val minimumOverlap = assembly.minimumConnectorOverlap.toFloat()
        val sequence = buildList {
            assembly.parts.forEach { part ->
                if (!part.extender) add(part) else repeat(rMin) { add(part) }
            }
        }
        check(sequence.isNotEmpty())

        val connectionMaxima = sequence.zipWithNext { lower, upper ->
            minOf(lower.endConnectorLength, upper.startConnectorLength).toFloat()
        }
        val zeroOverlapSize = sequence.sumOf { it.fullAdvance }.toFloat()
        // MathML Core 5.3.1 o_max: one overlap for all connections, bounded by both
        // the target-derived theoretical value and every participating connector pair.
        val oMax = if (sequence.size <= 1) {
            0f
        } else {
            val oMaxTheoretical = (zeroOverlapSize - target) / (sequence.size - 1)
            minOf(oMaxTheoretical, connectionMaxima.min()).coerceAtLeast(minimumOverlap)
        }
        val overlaps = List(connectionMaxima.size) { oMax }

        var offset = 0f
        val components = sequence.mapIndexed { index, part ->
            MathGlyphComponent(part.glyphId, offset).also {
                if (index < sequence.lastIndex) {
                    offset += part.fullAdvance - overlaps[index]
                }
            }
        }
        val actualAdvance = offset + sequence.last().fullAdvance
        return MathVerticalConstruction(
            MathConstructionKind.Assembly,
            components,
            actualAdvance,
            reachesTarget = actualAdvance + ASSEMBLY_REACH_EPSILON_DESIGN_UNITS >= target,
            connectorOverlaps = overlaps,
            extenderRepetitions = rMin,
            assemblyItalicCorrection = assembly.italicCorrection,
            assemblyValidation = validation,
            constructionPolicy = "MathMLCore5.3.1UniformOverlap",
            uniformConnectorOverlap = oMax,
            // MathML Core 5.3.1 defines the vertical assembly's orthogonal width from
            // every part record, including an extender skipped when rMin is zero.
            orthogonalAdvancePx = assembly.parts.maxOf { glyphAdvanceWidthPx(it.glyphId) },
        )
    }

    /** Resolves MathML Core r_min and checks the extender budget before list materialization. */
    private fun mathMlExtenderRepetitions(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        resourceLimits: MathResourceLimits,
    ): Int {
        check(validation.valid)
        val nonExtenderAdvance = assembly.parts.sumOf { part ->
            if (part.extender) 0L else part.fullAdvance.toLong()
        }
        val repetitionsNumerator = target.toDouble() - nonExtenderAdvance.toDouble() +
            assembly.minimumConnectorOverlap.toDouble() * (validation.nonExtenderCount - 1)
        val minimumRepetitions = if (validation.nonExtenderCount == 0) 1L else 0L
        val calculatedRepetitions = ceil(
            repetitionsNumerator / validation.extenderNonOverlappingAdvance.toDouble(),
        ).coerceAtLeast(minimumRepetitions.toDouble())
        if (!calculatedRepetitions.isFinite() || calculatedRepetitions > Int.MAX_VALUE.toDouble()) {
            throw extenderLimitException(Long.MAX_VALUE, resourceLimits.maximumExtenderCount)
        }
        val repetitions = calculatedRepetitions.toLong()
        val extenderCount = repetitions * validation.extenderCount.toLong()
        checkExtenderLimit(extenderCount, resourceLimits.maximumExtenderCount)
        return repetitions.toInt()
    }

    /**
     * Replays Tectonic 0.17.0/XeTeX `build_opentype_assembly`: extender repetition is selected
     * from OpenType full advances at minimum overlap, then TeX glue starts at each connection's
     * maximum overlap and stretches toward the font minimum. The completed vbox therefore uses
     * exact native-glyph height/depth rather than treating `fullAdvance` as a glyph bbox.
     */
    private fun assembleXeTeX(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        fontSizePx: Float,
        glyphVerticalExtentPx: (UShort) -> Float,
        glyphAdvanceWidthPx: (UShort) -> Float,
        resourceLimits: MathResourceLimits,
    ): MathVerticalConstruction {
        check(validation.valid)
        val repetitions = xeTeXExtenderRepetitions(
            assembly = assembly,
            validation = validation,
            target = target,
            resourceLimits = resourceLimits,
        )

        val sequence = buildList {
            assembly.parts.forEach { part ->
                if (part.extender) repeat(repetitions) { add(part) } else add(part)
            }
        }
        check(sequence.isNotEmpty())
        val glyphExtents = sequence.map { part ->
            resolvedConstructionDesignUnits(
                valuePx = glyphVerticalExtentPx(part.glyphId),
                fontSizePx = fontSizePx,
                resourceLimits = resourceLimits,
                label = "assembly glyph extent",
            )
        }
        val maximumOverlaps = mutableListOf<Float>()
        val minimumOverlaps = mutableListOf<Float>()
        var previousEndConnector = 0f
        sequence.forEachIndexed { index, part ->
            val maximumOverlap = minOf(part.startConnectorLength.toFloat(), previousEndConnector)
            if (index > 0) {
                maximumOverlaps += maximumOverlap
                minimumOverlaps += minOf(maximumOverlap, assembly.minimumConnectorOverlap.toFloat())
            }
            previousEndConnector = part.endConnectorLength.toFloat()
        }
        val naturalAdvance = glyphExtents.sum() - maximumOverlaps.sum()
        val stretchCapacity = maximumOverlaps.zip(minimumOverlaps).sumOf { (maximum, minimum) ->
            (maximum - minimum).toDouble()
        }.toFloat()
        val appliedStretch = (target - naturalAdvance).coerceIn(0f, stretchCapacity)
        val glueRatio = if (stretchCapacity > 0f) appliedStretch / stretchCapacity else 0f
        val actualOverlaps = maximumOverlaps.zip(minimumOverlaps).map { (maximum, minimum) ->
            maximum - glueRatio * (maximum - minimum)
        }

        var offset = 0f
        val components = sequence.mapIndexed { index, part ->
            MathGlyphComponent(part.glyphId, offset).also {
                if (index < sequence.lastIndex) {
                    offset += glyphExtents[index] - actualOverlaps[index]
                }
            }
        }
        val actualAdvance = naturalAdvance + appliedStretch
        return MathVerticalConstruction(
            kind = MathConstructionKind.Assembly,
            components = components,
            advanceMeasurement = actualAdvance,
            reachesTarget = actualAdvance + ASSEMBLY_REACH_EPSILON_DESIGN_UNITS >= target,
            connectorOverlaps = actualOverlaps,
            extenderRepetitions = repetitions,
            assemblyItalicCorrection = assembly.italicCorrection,
            assemblyValidation = validation,
            constructionPolicy = "Tectonic0.17.0XeTeXBuildOpenTypeAssemblyStretchGlue",
            uniformConnectorOverlap = null,
            orthogonalAdvancePx = assembly.parts.maxOf { glyphAdvanceWidthPx(it.glyphId) },
            assemblyNaturalAdvance = naturalAdvance,
            assemblyStretchCapacity = stretchCapacity,
            assemblyAppliedStretch = appliedStretch,
            assemblyGlyphExtents = glyphExtents,
            assemblyMaximumConnectorOverlaps = maximumOverlaps,
            assemblyMinimumConnectorOverlaps = minimumOverlaps,
        )
    }

    /**
     * Closed-form XeTeX repetition search at minimum connector overlap.
     *
     * Repetition zero is structurally special because extender records disappear, and is only
     * valid when a non-extender remains. From one repetition onward, every additional round
     * contributes the validated non-overlapping extender advance exactly once. Exact integer
     * design-unit sums avoid the old Float
     * accumulation boundary drift, keep selection O(part count), and let the selected extender
     * total be checked before the O(output size) sequence is materialized.
     */
    private fun xeTeXExtenderRepetitions(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        resourceLimits: MathResourceLimits,
    ): Int {
        val zeroRepetitionAdvance = xeTeXMinimumOverlapAdvance(assembly, repetitions = 0)
        val repetitions = if (
            validation.nonExtenderCount > 0 &&
            zeroRepetitionAdvance.toDouble() >= target.toDouble()
        ) {
            0L
        } else {
            val oneRepetitionAdvance = xeTeXMinimumOverlapAdvance(assembly, repetitions = 1)
            if (oneRepetitionAdvance.toDouble() >= target.toDouble()) {
                1L
            } else {
                val additional = ceil(
                    (target.toDouble() - oneRepetitionAdvance.toDouble()) /
                        validation.extenderNonOverlappingAdvance.toDouble(),
                )
                if (!additional.isFinite() || additional > Int.MAX_VALUE.toDouble()) {
                    throw extenderLimitException(Long.MAX_VALUE, resourceLimits.maximumExtenderCount)
                }
                1L + additional.toLong()
            }
        }
        val extenderCount = repetitions * validation.extenderCount.toLong()
        checkExtenderLimit(extenderCount, resourceLimits.maximumExtenderCount)
        if (repetitions > Int.MAX_VALUE.toLong()) {
            throw extenderLimitException(extenderCount, resourceLimits.maximumExtenderCount)
        }
        return repetitions.toInt()
    }

    private fun xeTeXMinimumOverlapAdvance(
        assembly: MathGlyphAssembly,
        repetitions: Int,
    ): Long {
        var advance = 0L
        var previousEndConnector = 0
        assembly.parts.forEach { part ->
            val count = if (part.extender) repetitions else 1
            if (count == 0) return@forEach
            val firstOverlap = minOf(
                part.startConnectorLength,
                assembly.minimumConnectorOverlap,
                previousEndConnector,
            )
            advance += part.fullAdvance.toLong() - firstOverlap.toLong()
            if (count > 1) {
                val repeatedOverlap = minOf(
                    part.startConnectorLength,
                    assembly.minimumConnectorOverlap,
                    part.endConnectorLength,
                )
                advance += (count - 1).toLong() *
                    (part.fullAdvance.toLong() - repeatedOverlap.toLong())
            }
            previousEndConnector = part.endConnectorLength
        }
        return advance
    }

    private companion object {
        const val ASSEMBLY_REACH_EPSILON_DESIGN_UNITS = 0.01f
    }

    private fun resolvedConstructionDesignUnits(
        valuePx: Float,
        fontSizePx: Float,
        resourceLimits: MathResourceLimits,
        label: String,
    ): Float {
        if (
            !valuePx.isFinite() || valuePx < 0f ||
            valuePx > resourceLimits.maximumResolvedDimensionPx ||
            !fontSizePx.isFinite() || fontSizePx <= 0f ||
            fontSizePx > resourceLimits.maximumResolvedDimensionPx
        ) {
            throw OpenTypeMathException(
                DiagnosticCode.InvalidResolvedDimension,
                "$label is outside the finite resource dimension range",
            )
        }
        val resolved = valuePx * unitsPerEm / fontSizePx
        val maximumDesignUnits = resourceLimits.maximumResolvedDimensionPx * unitsPerEm
        if (!resolved.isFinite() || abs(resolved) > maximumDesignUnits) {
            throw OpenTypeMathException(
                DiagnosticCode.InvalidResolvedDimension,
                "$label resolved to $resolved design units, exceeding finite limit $maximumDesignUnits",
            )
        }
        return resolved
    }

    private fun checkExtenderLimit(actual: Long, limit: Int) {
        if (actual > limit.toLong()) throw extenderLimitException(actual, limit)
    }

    private fun extenderLimitException(actual: Long, limit: Int): OpenTypeMathException =
        OpenTypeMathException(
            DiagnosticCode.ExtenderCountLimitExceeded,
            "Math resource extenderCount=$actual exceeds limit $limit",
            resourceActual = actual,
        )
}

class OpenTypeMathException(
    val diagnosticCode: DiagnosticCode,
    message: String,
    internal val resourceActual: Long? = null,
) : IllegalArgumentException(message)
