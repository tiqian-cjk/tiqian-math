package org.tiqian.math.gradle

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.tiqian.math.font.opentype.PackagedOpenTypeMathManifestCodec
import org.tiqian.math.font.opentype.VerifiedOpenTypeMathSnapshotLoader

class TiqianMathFontsPluginTest {
    @Test
    fun prebakesStixIntoJvmHostResourcesIncrementally() {
        val repository = Path.of(checkNotNull(System.getProperty("tiqianMathRepositoryRoot")))
        val project = Files.createTempDirectory("tiqian-math-font-host")
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"font-host\"\n")
        Files.writeString(project.resolve("build.gradle.kts"), """
            import org.tiqian.math.core.MathFontClass
            import org.tiqian.math.core.MathFontWeight

            plugins {
                java
                id("org.tiqian.math.fonts")
            }

            tiqianMathFonts {
                family("stix") {
                    fontClass.set(MathFontClass.Serif)
                    face("regular") {
                        source.set(layout.projectDirectory.file("fonts/STIXTwoMath-Regular.otf"))
                        weight.set(MathFontWeight.Regular)
                    }
                }
            }
        """.trimIndent())
        val fonts = project.resolve("fonts").createDirectories()
        Files.copy(
            stixFont(repository),
            fonts.resolve("STIXTwoMath-Regular.otf"),
        )

        val first = runner(project, "processResources").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":bakeTiqianMathFonts")?.outcome)
        val packaged = project.resolve("build/resources/main/org/tiqian/math/host-fonts/stix")
        assertTrue(packaged.resolve("regular.otf").exists())
        assertTrue(packaged.resolve("regular.tqmath").exists())
        val manifest = packaged.resolve("manifest.tqfont").readText()
        assertTrue(manifest.contains("95bc2729e41faf93b0bcae9e96c4dc4da45855067fd0581e621e30734fe8d90b"))
        assertTrue(manifest.contains("face\tregular\t400"))
        val decoded = PackagedOpenTypeMathManifestCodec.decode(manifest.encodeToByteArray())
        val regular = decoded.faces.single()
        val loaded = VerifiedOpenTypeMathSnapshotLoader.load(
            Files.readAllBytes(packaged.resolve(regular.fontFileName)),
            Files.readAllBytes(packaged.resolve(regular.snapshotFileName)),
            regular.fontSha256,
        )
        assertEquals(1000, loaded.unitsPerEm)
        assertEquals(258, loaded.constants.axisHeight)

        val second = runner(project, "processResources").build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":bakeTiqianMathFonts")?.outcome)
    }

    @Test
    fun mergesPrebakedStixIntoAndroidHostAssets() {
        val repository = Path.of(checkNotNull(System.getProperty("tiqianMathRepositoryRoot")))
        val project = Files.createTempDirectory("tiqian-math-font-android-host")
        Files.writeString(project.resolve("settings.gradle.kts"), """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "font-host"
        """.trimIndent())
        Files.writeString(project.resolve("build.gradle.kts"), """
            import org.tiqian.math.core.MathFontClass
            import org.tiqian.math.core.MathFontWeight

            plugins {
                id("com.android.application") version "9.3.1"
                id("org.tiqian.math.fonts")
            }

            android {
                namespace = "org.tiqian.math.testhost"
                compileSdk = 37
                defaultConfig { minSdk = 23 }
            }

            tiqianMathFonts {
                family("stix") {
                    fontClass.set(MathFontClass.Serif)
                    face("regular") {
                        source.set(layout.projectDirectory.file("fonts/STIXTwoMath-Regular.otf"))
                        weight.set(MathFontWeight.Regular)
                    }
                }
            }
        """.trimIndent())
        val source = project.resolve("src/main").createDirectories()
        Files.writeString(source.resolve("AndroidManifest.xml"), "<manifest />\n")
        val fonts = project.resolve("fonts").createDirectories()
        Files.copy(
            stixFont(repository),
            fonts.resolve("STIXTwoMath-Regular.otf"),
        )

        val result = runner(project, "mergeDebugAssets").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":bakeTiqianMathFonts")?.outcome)
        val merged = project.resolve("build/intermediates/assets/debug/mergeDebugAssets/org/tiqian/math/host-fonts/stix")
        assertTrue(merged.resolve("regular.otf").exists())
        assertTrue(merged.resolve("regular.tqmath").exists())
        assertTrue(merged.resolve("manifest.tqfont").exists())
    }

    @Test
    fun packagesPrebakedStixIntoAndroidMultiplatformResources() {
        val repository = Path.of(checkNotNull(System.getProperty("tiqianMathRepositoryRoot")))
        val project = Files.createTempDirectory("tiqian-math-font-android-kmp-host")
        Files.writeString(project.resolve("settings.gradle.kts"), """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "font-kmp-host"
        """.trimIndent())
        Files.writeString(project.resolve("build.gradle.kts"), """
            import org.tiqian.math.core.MathFontClass
            import org.tiqian.math.core.MathFontWeight

            plugins {
                kotlin("multiplatform") version "2.3.20"
                id("com.android.kotlin.multiplatform.library") version "9.3.1"
                id("org.tiqian.math.fonts")
            }

            kotlin {
                jvm()
                android {
                    namespace = "org.tiqian.math.testhost"
                    compileSdk = 37
                    minSdk = 23
                }
            }

            tiqianMathFonts {
                family("stix") {
                    fontClass.set(MathFontClass.Serif)
                    face("regular") {
                        source.set(layout.projectDirectory.file("fonts/STIXTwoMath-Regular.otf"))
                        weight.set(MathFontWeight.Regular)
                    }
                }
            }
        """.trimIndent())
        val fonts = project.resolve("fonts").createDirectories()
        Files.copy(
            stixFont(repository),
            fonts.resolve("STIXTwoMath-Regular.otf"),
        )

        val result = runner(project, "assembleAndroidMain", "jvmProcessResources").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":bakeTiqianMathFonts")?.outcome)
        val aar = project.resolve("build/outputs/aar/font-kmp-host.aar")
        val packagedEntries = ZipFile(aar.toFile()).use { outer ->
            val classes = outer.getInputStream(outer.getEntry("classes.jar")).use { it.readBytes() }
            ZipInputStream(ByteArrayInputStream(classes)).use { inner ->
                buildSet {
                    var entry = inner.nextEntry
                    while (entry != null) {
                        add(entry.name)
                        entry = inner.nextEntry
                    }
                }
            }
        }
        assertTrue("org/tiqian/math/host-fonts/stix/regular.otf" in packagedEntries)
        assertTrue("org/tiqian/math/host-fonts/stix/regular.tqmath" in packagedEntries)
        assertTrue("org/tiqian/math/host-fonts/stix/manifest.tqfont" in packagedEntries)
        val jvmResources = project.resolve("build/processedResources/jvm/main/org/tiqian/math/host-fonts/stix")
        assertTrue(jvmResources.resolve("regular.otf").exists())
    }

    private fun runner(project: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withArguments(*arguments, "--stacktrace")
        .withPluginClasspath()

    private fun stixFont(repository: Path): Path = repository.resolve(
        "font/stix/src/commonMain/resources/org/tiqian/math/host-fonts/stix/regular.otf",
    )
}
