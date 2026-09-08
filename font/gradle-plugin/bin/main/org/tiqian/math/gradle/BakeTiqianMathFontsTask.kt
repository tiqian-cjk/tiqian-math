package org.tiqian.math.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight
import org.tiqian.math.font.opentype.PackagedMathFontManifestName
import org.tiqian.math.font.opentype.PackagedMathFontResourceRoot
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFaceManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathFamilyManifest
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.requirePackagedMathFontId
import org.tiqian.math.font.tooling.OpenTypeMathMetadataBaker

abstract class MathFontBakeInput @Inject constructor() {
    @get:Input
    abstract val familyId: Property<String>

    @get:Input
    abstract val faceId: Property<String>

    @get:Input
    abstract val fontClass: Property<MathFontClass>

    @get:Input
    abstract val weight: Property<MathFontWeight>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty
}
@CacheableTask
abstract class BakeTiqianMathFontsTask @Inject constructor(
    private val objects: ObjectFactory,
) : DefaultTask() {
    @get:Nested
    abstract val fonts: ListProperty<MathFontBakeInput>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    fun register(family: TiqianMathFontFamily, face: TiqianMathFontFace) {
        val input = objects.newInstance(MathFontBakeInput::class.java)
        input.familyId.set(family.name)
        input.faceId.set(face.name)
        input.fontClass.set(family.fontClass)
        input.weight.set(face.weight)
        input.source.set(face.source)
        fonts.add(input)
    }

    @TaskAction
    fun bake() {
        val declarations = fonts.get()
        require(declarations.isNotEmpty()) { "tiqianMathFonts must declare at least one font face" }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()

        declarations.groupBy { it.familyId.get() }.toSortedMap().forEach { (familyId, faces) ->
            requirePackagedMathFontId(familyId, "family id")
            val classes = faces.map { it.fontClass.get() }.distinct()
            require(classes.size == 1) { "math family $familyId cannot mix serif and sans-serif faces" }
            val weights = faces.map { it.weight.get() }
            require(weights.distinct().size == weights.size) {
                "math family $familyId cannot declare more than one face for the same weight"
            }
            val familyDirectory = output.resolve("$PackagedMathFontResourceRoot/$familyId")
            check(familyDirectory.mkdirs() || familyDirectory.isDirectory) {
                "could not create $familyDirectory"
            }
            val manifestFaces = faces.sortedBy { it.faceId.get() }.map { face ->
                val faceId = face.faceId.get()
                requirePackagedMathFontId(faceId, "face id")
                val fontBytes = face.source.get().asFile.readBytes()
                val baked = OpenTypeMathMetadataBaker.bake(fontBytes)
                val fontFileName = "$faceId.otf"
                val snapshotFileName = "$faceId.tqmath"
                familyDirectory.resolve(fontFileName).writeBytes(fontBytes)
                familyDirectory.resolve(snapshotFileName).writeBytes(baked.snapshotBytes)
                PackagedOpenTypeMathFaceManifest(
                    faceId = faceId,
                    weight = face.weight.get(),
                    fontSha256 = baked.fontSha256,
                    fontFileName = fontFileName,
                    snapshotFileName = snapshotFileName,
                )
            }
            familyDirectory.resolve(PackagedMathFontManifestName).writeBytes(
                PackagedOpenTypeMathManifestCodec.encode(PackagedOpenTypeMathFamilyManifest(
                    familyId = familyId,
                    fontClass = classes.single(),
                    faces = manifestFaces,
                )),
            )
        }
    }
}
