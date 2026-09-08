package org.tiqian.math.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class MathFormulaCorpusInputFormat {
    Auto,
    OneFormulaPerLine,
    ZhihuFormulaIndexJson,
}

/** Reads scanner input without normalizing or rewriting an individual formula source. */
internal object MathFormulaCorpusInput {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(path: Path, format: MathFormulaCorpusInputFormat): List<String> {
        val resolvedFormat = when (format) {
            MathFormulaCorpusInputFormat.Auto -> detect(path)
            else -> format
        }
        return when (resolvedFormat) {
            MathFormulaCorpusInputFormat.Auto -> error("Auto input format must be resolved before reading")
            MathFormulaCorpusInputFormat.OneFormulaPerLine -> Files.readAllLines(path)
            MathFormulaCorpusInputFormat.ZhihuFormulaIndexJson -> readZhihuFormulaIndex(path)
        }
    }

    private fun detect(path: Path): MathFormulaCorpusInputFormat {
        val fileName = path.fileName?.toString()?.lowercase().orEmpty()
        return if (fileName.endsWith(".json")) {
            MathFormulaCorpusInputFormat.ZhihuFormulaIndexJson
        } else {
            MathFormulaCorpusInputFormat.OneFormulaPerLine
        }
    }

    private fun readZhihuFormulaIndex(path: Path): List<String> {
        val root = json.parseToJsonElement(Files.readString(path))
        require(root is JsonArray) {
            "Zhihu formula corpus input must be a top-level JSON array: $path"
        }
        return root.mapIndexed { index, element ->
            require(element is JsonObject) {
                "Zhihu formula corpus entry[$index] must be a JSON object: $path"
            }
            val latex = element["latex"]
            require(latex is JsonPrimitive && latex.isString) {
                "Zhihu formula corpus entry[$index].latex must be a JSON string: $path"
            }
            latex.content
        }
    }
}
