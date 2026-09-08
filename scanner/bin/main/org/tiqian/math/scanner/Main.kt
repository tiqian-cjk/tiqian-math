package org.tiqian.math.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.tiqian.math.core.MathFaceId
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.font.skia.SkiaMathFontFamily
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.SkiaMathTextRunProvider
import org.tiqian.math.font.skia.formulaCapabilityEngine
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathComposeFontFace
import org.tiqian.math.layout.MathLayoutOptions

fun main(args: Array<String>) {
    val arguments = ScannerArguments.parse(args)
    val sources = MathFormulaCorpusInput.read(arguments.input, arguments.inputFormat)
    val textProvider = arguments.textFont?.let { path ->
        SkiaMathTextRunProvider.fromBytes(
            faceId = MathFaceId("scanner-explicit-host-text"),
            fontBytes = Files.readAllBytes(path),
            resolvedWeight = arguments.textFontWeight,
        )
    }
    val report = try {
        fun scan(face: MathComposeFontFace) =
            MathFormulaCorpusScanner(
                capabilityEngine = face.formulaCapabilityEngine(textProvider),
                options = MathLayoutOptions(
                    mode = arguments.mode,
                    fontSizePx = arguments.fontSizePx,
                    displayWidthPx = arguments.displayWidthPx,
                ),
                maxSamplesPerCategory = arguments.maxSamples,
            ).scan(sources)

        when (arguments.font) {
            ScannerFont.Lete -> SkiaMathFontFamily.loadBundledLete().use(::scan)
            ScannerFont.Stix -> SkiaMathFontFace(StixTwoMath.load()).use(::scan)
        }
    } finally {
        textProvider?.close()
    }
    val json = report.toJson() + "\n"
    arguments.output?.writeText(json) ?: print(json)
}

private enum class ScannerFont { Lete, Stix }

private data class ScannerArguments(
    val input: Path,
    val output: Path?,
    val font: ScannerFont,
    val mode: MathMode,
    val fontSizePx: Float,
    val maxSamples: Int,
    val inputFormat: MathFormulaCorpusInputFormat,
    val textFont: Path?,
    val textFontWeight: MathFontWeight,
    val displayWidthPx: Float?,
) {
    companion object {
        fun parse(args: Array<String>): ScannerArguments {
            var input: Path? = null
            var output: Path? = null
            var font = ScannerFont.Lete
            var mode = MathMode.Inline
            var fontSizePx = 24f
            var maxSamples = 3
            var inputFormat = MathFormulaCorpusInputFormat.Auto
            var textFont: Path? = null
            var textFontWeight = MathFontWeight.Regular
            var displayWidthPx: Float? = null
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                when {
                    argument == "--output" -> output = Path.of(args.valueAfter(index++))
                    argument.startsWith("--output=") -> output = Path.of(argument.substringAfter('='))
                    argument.startsWith("--font=") -> font = when (argument.substringAfter('=').lowercase()) {
                        "lete" -> ScannerFont.Lete
                        "stix" -> ScannerFont.Stix
                        else -> error("--font must be lete or stix")
                    }
                    argument.startsWith("--mode=") -> mode = when (argument.substringAfter('=').lowercase()) {
                        "inline" -> MathMode.Inline
                        "display" -> MathMode.Display
                        else -> error("--mode must be inline or display")
                    }
                    argument.startsWith("--font-size=") ->
                        fontSizePx = argument.substringAfter('=').toFloat().also { require(it > 0f) }
                    argument.startsWith("--display-width=") ->
                        displayWidthPx = argument.substringAfter('=').toFloat().also { require(it > 0f) }
                    argument.startsWith("--max-samples=") ->
                        maxSamples = argument.substringAfter('=').toInt().also { require(it >= 0) }
                    argument.startsWith("--input-format=") -> inputFormat = when (argument.substringAfter('=').lowercase()) {
                        "auto" -> MathFormulaCorpusInputFormat.Auto
                        "lines" -> MathFormulaCorpusInputFormat.OneFormulaPerLine
                        "zhihu-json" -> MathFormulaCorpusInputFormat.ZhihuFormulaIndexJson
                        else -> error("--input-format must be auto, lines, or zhihu-json")
                    }
                    argument == "--text-font" -> textFont = Path.of(args.valueAfter(index++))
                    argument.startsWith("--text-font=") -> textFont = Path.of(argument.substringAfter('='))
                    argument.startsWith("--text-font-weight=") ->
                        textFontWeight = argument.substringAfter('=').toInt().let(MathFontWeight::nearest)
                    argument.startsWith("--") -> error("Unknown option: $argument")
                    input == null -> input = Path.of(argument)
                    else -> error("Only one input file is accepted")
                }
                index += 1
            }
            return ScannerArguments(
                input = requireNotNull(input) {
                    "Usage: math-formula-scanner <one-formula-per-line file> " +
                        "[--output=report.json] [--font=lete|stix] [--mode=inline|display] " +
                        "[--font-size=24] [--max-samples=3] " +
                        "[--display-width=800] " +
                        "[--input-format=auto|lines|zhihu-json] " +
                        "[--text-font=/path/to/font.otf] [--text-font-weight=400]"
                },
                output = output,
                font = font,
                mode = mode,
                fontSizePx = fontSizePx,
                maxSamples = maxSamples,
                inputFormat = inputFormat,
                textFont = textFont,
                textFontWeight = textFontWeight,
                displayWidthPx = displayWidthPx,
            )
        }

        private fun Array<String>.valueAfter(index: Int): String =
            getOrNull(index + 1) ?: error("${get(index)} requires a value")
    }
}
