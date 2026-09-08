package org.tiqian.math.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ApplicationVariantBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import com.android.build.api.variant.LibraryVariantBuilder
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.api.variant.VariantBuilder
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

internal object AndroidAssetsWiring {
    fun wireApplication(project: Project, bake: TaskProvider<BakeTiqianMathFontsTask>) = wire(
        project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java),
        bake,
    )

    fun wireLibrary(project: Project, bake: TaskProvider<BakeTiqianMathFontsTask>) = wire(
        project.extensions.getByType(LibraryAndroidComponentsExtension::class.java),
        bake,
    )

    fun wireKotlinMultiplatform(project: Project, bake: TaskProvider<BakeTiqianMathFontsTask>) = wire(
        project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java),
        bake,
    )

    private fun wire(
        components: ApplicationAndroidComponentsExtension,
        bake: TaskProvider<BakeTiqianMathFontsTask>,
    ) = wire<ApplicationVariantBuilder, ApplicationVariant>(components, bake)

    private fun wire(
        components: LibraryAndroidComponentsExtension,
        bake: TaskProvider<BakeTiqianMathFontsTask>,
    ) = wire<LibraryVariantBuilder, LibraryVariant>(components, bake)

    private fun wire(
        components: KotlinMultiplatformAndroidComponentsExtension,
        bake: TaskProvider<BakeTiqianMathFontsTask>,
    ) {
        components.onVariants { variant ->
            checkNotNull(variant.sources.resources) {
                "Android multiplatform variant ${variant.name} does not expose Java resources"
            }.addGeneratedSourceDirectory(
                bake,
                BakeTiqianMathFontsTask::outputDirectory,
            )
        }
    }

    private fun <B : VariantBuilder, V : Variant> wire(
        components: AndroidComponentsExtension<*, B, V>,
        bake: TaskProvider<BakeTiqianMathFontsTask>,
    ) {
        components.onVariants { variant ->
            checkNotNull(variant.sources.assets) {
                "Android variant ${variant.name} does not expose an assets source set"
            }.addGeneratedSourceDirectory(bake, BakeTiqianMathFontsTask::outputDirectory)
        }
    }
}
