package org.tiqian.math.font.generator

import java.nio.file.Files
import java.nio.file.Path
import org.tiqian.math.font.opentype.LeteSansMathPrebakedData
import org.tiqian.math.font.opentype.OpenTypeMathReader
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader
import org.tiqian.math.font.tooling.OpenTypeMathMetadataBaker

fun main(args: Array<String>) {
    require(args.size == 5) {
        "Usage: lete-runtime-metadata <regular layout OTF> <regular runtime OTF> " +
            "<bold layout OTF> <bold runtime OTF> <output directory>"
    }
    val output = Path.of(args[4]).toAbsolutePath().normalize()
    Files.createDirectories(output)
    listOf(
        Triple(args[0], args[1], LeteSansMathPrebakedData.RegularFileStem),
        Triple(args[2], args[3], LeteSansMathPrebakedData.BoldFileStem),
    ).forEach { (layoutPath, runtimePath, fileStem) ->
        val layoutBytes = Files.readAllBytes(Path.of(layoutPath))
        val runtimeBytes = Files.readAllBytes(Path.of(runtimePath))
        val baked = OpenTypeMathMetadataBaker.bake(
            layoutBytes,
            runtimeBytes,
        )
        val marker = ByteArray(0)
        check(
            OpenTypeMathReader().read(layoutBytes).copy(bytes = marker) ==
                VerifiedOpenTypeMathSnapshotLoader.load(runtimeBytes, baked.snapshotBytes).copy(bytes = marker),
        ) { "Compiled $fileStem snapshot does not reproduce the layout font metadata" }
        Files.write(output.resolve("$fileStem.tqmath"), baked.snapshotBytes)
    }
}
