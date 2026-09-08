package org.tiqian.math.font.generator

import java.nio.file.Files
import java.nio.file.Path
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.font.opentype.LeteSansMathPrebakedData
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFaceManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFamilyManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.tooling.OpenTypeMathMetadataBaker

private data class BundledFace(
    val fileName: String,
    val expectedSha256: String,
)

fun main(args: Array<String>) {
    require(args.size in 1..2) { "Usage: metadata-generator <tiqian-math repository root> [--verify]" }
    val verifyOnly = args.getOrNull(1)?.also { require(it == "--verify") } != null
    val root = Path.of(args.first()).toAbsolutePath().normalize()
    val jvmFonts = root.resolve("engine/src/jvmMain/resources/org/tiqian/math/fonts")
    val androidFonts = root.resolve("platforms/android/font/src/main/assets/org/tiqian/math/fonts")
    val faces = listOf(
        BundledFace(LeteSansMathPrebakedData.RegularFileStem, LeteSansMathPrebakedData.RegularSourceSha256),
        BundledFace(LeteSansMathPrebakedData.BoldFileStem, LeteSansMathPrebakedData.BoldSourceSha256),
    )

    faces.forEach { face ->
        val otfPath = jvmFonts.resolve("${face.fileName}.otf")
        val fontBytes = Files.readAllBytes(otfPath)
        val androidFontBytes = Files.readAllBytes(androidFonts.resolve("${face.fileName}.otf"))
        require(fontBytes.contentEquals(androidFontBytes)) {
            "JVM and Android bundled font bytes differ for ${face.fileName}"
        }
        val baked = OpenTypeMathMetadataBaker.bake(fontBytes)
        val digest = baked.fontSha256
        require(digest == face.expectedSha256) {
            "${face.fileName} SHA-256 changed: expected ${face.expectedSha256}, found $digest"
        }
        val snapshot = baked.snapshotBytes
        val outputName = "${face.fileName}.tqmath"
        listOf(jvmFonts.resolve(outputName), androidFonts.resolve(outputName)).forEach { output ->
            if (verifyOnly) {
                require(Files.exists(output) && Files.readAllBytes(output).contentEquals(snapshot)) {
                    "$output is stale; run ./gradlew :font:metadata-generator:run"
                }
            } else {
                Files.write(output, snapshot)
            }
        }
        println("${face.fileName}: sha256=$digest metadataBytes=${snapshot.size} verified=$verifyOnly")
    }

    val stixDirectory = root.resolve("font/stix/src/commonMain/resources/org/tiqian/math/host-fonts/stix")
    val stixFont = Files.readAllBytes(stixDirectory.resolve("regular.otf"))
    val stix = OpenTypeMathMetadataBaker.bake(stixFont)
    val expectedStixSha256 = "95bc2729e41faf93b0bcae9e96c4dc4da45855067fd0581e621e30734fe8d90b"
    require(stix.fontSha256 == expectedStixSha256) {
        "STIXTwoMath-Regular SHA-256 changed: expected $expectedStixSha256, found ${stix.fontSha256}"
    }
    val stixManifest = PackagedOpenTypeMathManifestCodec.encode(
        PackagedOpenTypeMathFamilyManifest(
            familyId = "stix",
            fontClass = MathFontClass.Serif,
            faces = listOf(
                PackagedOpenTypeMathFaceManifest(
                    faceId = "regular",
                    weight = MathFontWeight.Regular,
                    fontSha256 = stix.fontSha256,
                    fontFileName = "regular.otf",
                    snapshotFileName = "regular.tqmath",
                ),
            ),
        ),
    )
    mapOf(
        stixDirectory.resolve("regular.tqmath") to stix.snapshotBytes,
        stixDirectory.resolve("manifest.tqfont") to stixManifest,
    ).forEach { (output, expected) ->
        if (verifyOnly) {
            require(Files.exists(output) && Files.readAllBytes(output).contentEquals(expected)) {
                "$output is stale; run ./gradlew :font:metadata-generator:run"
            }
        } else {
            Files.write(output, expected)
        }
    }
    println("STIXTwoMath-Regular: sha256=${stix.fontSha256} metadataBytes=${stix.snapshotBytes.size} verified=$verifyOnly")
}
