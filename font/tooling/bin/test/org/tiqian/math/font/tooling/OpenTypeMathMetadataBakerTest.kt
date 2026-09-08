package org.tiqian.math.font.tooling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OpenTypeMathMetadataBakerTest {
    @Test
    fun reproducesCheckedInLeteSnapshot() {
        val root = Path.of(checkNotNull(System.getProperty("tiqianMathRepositoryRoot")))
        val resources = root.resolve("engine/src/jvmMain/resources/org/tiqian/math/fonts")
        val baked = OpenTypeMathMetadataBaker.bake(
            Files.readAllBytes(resources.resolve("LeteSansMath-Regular.otf")),
        )

        assertEquals("ead643895be03f42f6fa201fb1176323f60dd330d4109387bac90bdf980fcf3e", baked.fontSha256)
        assertContentEquals(
            Files.readAllBytes(resources.resolve("LeteSansMath-Regular.tqmath")),
            baked.snapshotBytes,
        )
    }
}
