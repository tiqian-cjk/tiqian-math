package org.tiqian.math.scanner

import java.util.Locale
import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.SourceRange
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathLayoutOptions

data class MathFormulaScanDiagnostic(
    val code: DiagnosticCode,
    val range: SourceRange,
)

data class MathFormulaScanSample(
    val source: String,
    val diagnostics: List<MathFormulaScanDiagnostic>,
)

enum class MathFormulaFailureStage { TokenizerMacroParser, LayoutFont, RenderPreflight }

data class MathFormulaScanReport(
    val total: Int,
    val ready: Int,
    val fallbackRequired: Int,
    val readyRate: Double,
    val byCategory: Map<MathFormulaCapabilityCategory, Int>,
    val byFailureStage: Map<MathFormulaFailureStage, Int>,
    val byDiagnosticCode: Map<DiagnosticCode, Int>,
    val byCommand: Map<String, Int>,
    val byUnsupportedCommand: Map<String, Int>,
    val samplesByCategory: Map<MathFormulaCapabilityCategory, List<MathFormulaScanSample>>,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"total\": ").append(total).append(",\n")
        append("  \"ready\": ").append(ready).append(",\n")
        append("  \"fallbackRequired\": ").append(fallbackRequired).append(",\n")
        append("  \"readyRate\": ").append(String.format(Locale.ROOT, "%.6f", readyRate)).append(",\n")
        append("  \"byCategory\": ")
        appendCountMap(byCategory.mapKeys { it.key.name })
        append(",\n")
        append("  \"byFailureStage\": ")
        appendCountMap(byFailureStage.mapKeys { it.key.name })
        append(",\n")
        append("  \"byDiagnosticCode\": ")
        appendCountMap(byDiagnosticCode.mapKeys { it.key.name })
        append(",\n")
        append("  \"byCommand\": ")
        appendCountMap(byCommand)
        append(",\n")
        append("  \"byUnsupportedCommand\": ")
        appendCountMap(byUnsupportedCommand)
        append(",\n")
        append("  \"samplesByCategory\": {")
        if (samplesByCategory.isNotEmpty()) append('\n')
        samplesByCategory.entries.forEachIndexed { categoryIndex, (category, samples) ->
            append("    ")
            appendJsonString(category.name)
            append(": [")
            if (samples.isNotEmpty()) append('\n')
            samples.forEachIndexed { sampleIndex, sample ->
                append("      {\"source\": ")
                appendJsonString(sample.source)
                append(", \"diagnostics\": [")
                sample.diagnostics.forEachIndexed { diagnosticIndex, diagnostic ->
                    if (diagnosticIndex > 0) append(", ")
                    append("{\"code\": ")
                    appendJsonString(diagnostic.code.name)
                    append(", \"range\": {\"start\": ")
                    append(diagnostic.range.start)
                    append(", \"endExclusive\": ")
                    append(diagnostic.range.endExclusive)
                    append("}}")
                }
                append("]}")
                if (sampleIndex != samples.lastIndex) append(',')
                append('\n')
            }
            append("    ]")
            if (categoryIndex != samplesByCategory.size - 1) append(',')
            append('\n')
        }
        append("  }\n")
        append('}')
    }

    private fun StringBuilder.appendCountMap(counts: Map<String, Int>) {
        append('{')
        counts.toSortedMap().entries.forEachIndexed { index, (name, count) ->
            if (index > 0) append(", ")
            appendJsonString(name)
            append(": ").append(count)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

class MathFormulaCorpusScanner(
    private val capabilityEngine: MathFormulaCapabilityEngine,
    private val options: MathLayoutOptions = MathLayoutOptions(),
    private val maxSamplesPerCategory: Int = 3,
) {
    init {
        require(maxSamplesPerCategory >= 0) { "sample limit must not be negative" }
    }

    private val commandPattern = Regex("\\\\[A-Za-z]+")

    fun scan(sources: Iterable<String>): MathFormulaScanReport {
        var total = 0
        var ready = 0
        var fallbackRequired = 0
        val categories = mutableMapOf<MathFormulaCapabilityCategory, Int>()
        val failureStages = mutableMapOf<MathFormulaFailureStage, Int>()
        val diagnosticCodes = mutableMapOf<DiagnosticCode, Int>()
        val commands = mutableMapOf<String, Int>()
        val unsupportedCommands = mutableMapOf<String, Int>()
        val samples = mutableMapOf<MathFormulaCapabilityCategory, MutableList<MathFormulaScanSample>>()

        sources.forEach { source ->
            total += 1
            commandPattern.findAll(source).forEach { commands.increment(it.value) }
            when (val result = capabilityEngine.evaluate(source, options)) {
                is MathFormulaCapabilityResult.Ready -> {
                    ready += 1
                    countDiagnostics(source, result.diagnostics, diagnosticCodes, unsupportedCommands)
                }
                is MathFormulaCapabilityResult.FallbackRequired -> {
                    fallbackRequired += 1
                    countDiagnostics(source, result.diagnostics, diagnosticCodes, unsupportedCommands)
                    result.diagnostics.map(::failureStage).toSet().forEach { failureStages.increment(it) }
                    result.reasons.forEach { reason ->
                        categories.increment(reason.category)
                        val categorySamples = samples.getOrPut(reason.category) { mutableListOf() }
                        if (categorySamples.size < maxSamplesPerCategory) {
                            categorySamples += MathFormulaScanSample(
                                source = source,
                                diagnostics = reason.diagnostics.map { diagnostic ->
                                    MathFormulaScanDiagnostic(diagnostic.code, diagnostic.range)
                                },
                            )
                        }
                    }
                }
            }
        }

        return MathFormulaScanReport(
            total = total,
            ready = ready,
            fallbackRequired = fallbackRequired,
            readyRate = if (total == 0) 0.0 else ready.toDouble() / total,
            byCategory = categories.toSortedMap(compareBy(MathFormulaCapabilityCategory::name)),
            byFailureStage = failureStages.toSortedMap(compareBy(MathFormulaFailureStage::name)),
            byDiagnosticCode = diagnosticCodes.toSortedMap(compareBy(DiagnosticCode::name)),
            byCommand = commands.toSortedMap(),
            byUnsupportedCommand = unsupportedCommands.toSortedMap(),
            samplesByCategory = samples
                .mapValues { it.value.toList() }
                .toSortedMap(compareBy(MathFormulaCapabilityCategory::name)),
        )
    }

    private fun failureStage(diagnostic: MathDiagnostic): MathFormulaFailureStage = when (diagnostic.code) {
        DiagnosticCode.MissingConstructionOutlineEvidence,
        DiagnosticCode.MissingGlyphOutlineEvidence,
        DiagnosticCode.InvalidConstructionPaintOwnership,
        DiagnosticCode.ReplayFaceOwnershipConflict,
        -> MathFormulaFailureStage.RenderPreflight

        DiagnosticCode.MissingGlyph,
        DiagnosticCode.MissingTextRunProvider,
        DiagnosticCode.NonReplayableHostTextRun,
        DiagnosticCode.UnsupportedHostTextShaping,
        DiagnosticCode.InvalidHostTextRunEvidence,
        DiagnosticCode.MissingMathTable,
        DiagnosticCode.MalformedFont,
        DiagnosticCode.UnsupportedMathDeviceAdjustment,
        DiagnosticCode.MissingMathConstruction,
        DiagnosticCode.MathVariantTooShort,
        DiagnosticCode.UnsupportedMathAlphabet,
        DiagnosticCode.BreakpointCountLimitExceeded,
        DiagnosticCode.ExtenderCountLimitExceeded,
        DiagnosticCode.InvalidResolvedDimension,
        -> MathFormulaFailureStage.LayoutFont

        else -> MathFormulaFailureStage.TokenizerMacroParser
    }

    private fun countDiagnostics(
        source: String,
        diagnostics: List<MathDiagnostic>,
        diagnosticCodes: MutableMap<DiagnosticCode, Int>,
        unsupportedCommands: MutableMap<String, Int>,
    ) {
        diagnostics.forEach { diagnostic ->
            diagnosticCodes.increment(diagnostic.code)
            if (diagnostic.code == DiagnosticCode.UnknownCommand || diagnostic.code == DiagnosticCode.UnsupportedCommand) {
                val range = diagnostic.range
                val spelling = if (range.start <= source.length && range.endExclusive <= source.length) {
                    source.substring(range.start, range.endExclusive)
                } else {
                    "<invalid-range>"
                }
                unsupportedCommands.increment(spelling)
            }
        }
    }

    private fun <K> MutableMap<K, Int>.increment(key: K) {
        this[key] = getOrDefault(key, 0) + 1
    }
}
