package org.tiqian.math.font.generator

import java.nio.file.Files
import java.nio.file.Path
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFaceManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFamilyManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader
import org.tiqian.math.font.tooling.OpenTypeMathMetadataBaker

fun main(args: Array<String>) {
    require(args.size == 3) {
        "Usage: stix-runtime-metadata <layout OTF> <runtime OTF> <output directory>"
    }
    val layoutFontPath = Path.of(args[0]).toAbsolutePath().normalize()
    val runtimeFontPath = Path.of(args[1]).toAbsolutePath().normalize()
    val output = Path.of(args[2]).toAbsolutePath().normalize()
    val layoutBytes = Files.readAllBytes(layoutFontPath)
    val runtimeBytes = Files.readAllBytes(runtimeFontPath)
    val baked = OpenTypeMathMetadataBaker.bake(layoutBytes, runtimeBytes)
    val marker = ByteArray(0)
    check(
        OpenTypeMathReader().read(layoutBytes).copy(bytes = marker) ==
            VerifiedOpenTypeMathSnapshotLoader.load(runtimeBytes, baked.snapshotBytes).copy(bytes = marker),
    ) { "Compiled STIX snapshot does not reproduce the layout font metadata" }
    val manifest = PackagedOpenTypeMathManifestCodec.encode(
        PackagedOpenTypeMathFamilyManifest(
            familyId = "stix",
            fontClass = MathFontClass.Serif,
            faces = listOf(
                PackagedOpenTypeMathFaceManifest(
                    faceId = "regular",
                    weight = MathFontWeight.Regular,
                    fontSha256 = baked.fontSha256,
                    fontFileName = "regular.otf",
                    snapshotFileName = "regular.tqmath",
                ),
            ),
        ),
    )
    Files.createDirectories(output)
    Files.write(output.resolve("regular.tqmath"), baked.snapshotBytes)
    Files.write(output.resolve("manifest.tqfont"), manifest)
}
