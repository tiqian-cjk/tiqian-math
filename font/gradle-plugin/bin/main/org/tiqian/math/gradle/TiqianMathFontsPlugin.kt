package org.tiqian.math.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

class TiqianMathFontsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "tiqianMathFonts",
            TiqianMathFontsExtension::class.java,
            project.objects,
        )
        val bake = project.tasks.register("bakeTiqianMathFonts", BakeTiqianMathFontsTask::class.java) { task ->
            task.group = "build"
            task.description = "Prebakes host-selected OpenType MATH fonts."
            task.outputDirectory.convention(project.layout.buildDirectory.dir("generated/tiqianMathFonts"))
        }

        extension.families.all { family ->
            family.faces.all { face -> bake.configure { task -> task.register(family, face) } }
        }

        project.tasks.withType(ProcessResources::class.java).configureEach { task ->
            task.from(bake.flatMap { it.outputDirectory })
        }

        project.pluginManager.withPlugin("com.android.application") {
            AndroidAssetsWiring.wireApplication(project, bake)
        }
        project.pluginManager.withPlugin("com.android.library") {
            AndroidAssetsWiring.wireLibrary(project, bake)
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            AndroidAssetsWiring.wireKotlinMultiplatform(project, bake)
        }
    }
}
