import org.gradle.api.tasks.Sync

plugins {
    id("com.android.library")
}

val leteRegularLayoutFont = providers.gradleProperty("tiqianLeteRegularLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_LAYOUT_FONT"))
val leteRegularRuntimeFont = providers.gradleProperty("tiqianLeteRegularRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_RUNTIME_FONT"))
val leteBoldLayoutFont = providers.gradleProperty("tiqianLeteBoldLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_LAYOUT_FONT"))
val leteBoldRuntimeFont = providers.gradleProperty("tiqianLeteBoldRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_RUNTIME_FONT"))
val compiledMathResourcesEnabled = providers.gradleProperty("tiqianCompiledMathResources")
    .map(String::toBoolean)
    .getOrElse(false)
val compiledLeteInputs = listOf(
    leteRegularLayoutFont,
    leteRegularRuntimeFont,
    leteBoldLayoutFont,
    leteBoldRuntimeFont,
)
val compiledLeteEnabled = compiledMathResourcesEnabled && compiledLeteInputs.all { it.isPresent }
require(!compiledMathResourcesEnabled || compiledLeteInputs.all { it.isPresent }) {
    "Compiled Lete packaging requires both layout and runtime faces."
}
val preparedLeteAssetsDirectory = objects.directoryProperty().convention(
    layout.buildDirectory.dir("generated/leteRuntimeAssets"),
)
val preparedLeteAssets = if (compiledLeteEnabled) {
    val metadataDirectory = rootProject.layout.buildDirectory.dir("generated/leteRuntimeMetadata")
    tasks.register<Sync>("prepareLeteRuntimeAssets") {
        inputs.dir(metadataDirectory)
        from(layout.projectDirectory.dir("src/main/assets")) {
            exclude("org/tiqian/math/fonts/*.otf")
            exclude("org/tiqian/math/fonts/*.tqmath")
        }
        from(leteRegularRuntimeFont) {
            into("org/tiqian/math/fonts")
            rename { "LeteSansMath-Regular.otf" }
        }
        from(leteBoldRuntimeFont) {
            into("org/tiqian/math/fonts")
            rename { "LeteSansMath-Bold.otf" }
        }
        from(metadataDirectory) {
            into("org/tiqian/math/fonts")
        }
        into(preparedLeteAssetsDirectory)
        doFirst {
            listOf("LeteSansMath-Regular.tqmath", "LeteSansMath-Bold.tqmath").forEach { name ->
                require(metadataDirectory.get().file(name).asFile.isFile) {
                    "Missing compiled Lete metadata $name; run :font:metadata-generator:bakeLeteRuntimeMetadata first."
                }
            }
        }
    }
} else {
    null
}

android {
    namespace = "org.tiqian.math.font.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

preparedLeteAssets?.let { prepared ->
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        sourceSets.named("main") {
            assets.setSrcDirs(listOf(preparedLeteAssetsDirectory))
        }
    }
    // The asset source directory is a plain path, so every task that reads it
    // depends on the prepared tree explicitly: the asset merges, and the lint
    // model and analysis tasks that the demo application's release lint pulls in.
    tasks.matching { task ->
        (task.name.startsWith("merge") && task.name.endsWith("Assets")) ||
            task.name.contains("lint", ignoreCase = true)
    }.configureEach {
        dependsOn(prepared)
    }
}

dependencies {
    api(project(":engine"))

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(kotlin("test"))
}
