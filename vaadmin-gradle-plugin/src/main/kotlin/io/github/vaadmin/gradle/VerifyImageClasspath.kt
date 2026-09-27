package io.github.vaadmin.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * Fails when the libraries Jib would put in the image differ from what `bootJar` packages.
 *
 * Jib assembles the image from Gradle's outputs rather than from the jar, so the two can drift — the
 * usual way is development-only dependencies reaching the image. Two differences are expected and
 * ignored: `bootJar` leaves out starter jars, which carry no classes, and adds
 * `spring-boot-jarmode-tools`, which only the jar needs.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class VerifyImageClasspath : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bootJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val imageClasspath: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val jarLibraries =
            ZipFile(bootJar.get().asFile).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("BOOT-INF/lib/") }
                    .map { it.name.substringAfterLast('/') }
                    .toSet()
            }
        val imageLibraries = imageClasspath.files.filter { it.isFile && it.name.endsWith(".jar") }
        val imageNames = imageLibraries.map { it.name }.toSet()

        val onlyInImage = imageLibraries.filter { it.name !in jarLibraries && !isStarter(it) }.map { it.name }
        val onlyInJar = jarLibraries.filter { it !in imageNames && !it.startsWith("spring-boot-jarmode-tools") }

        if (onlyInImage.isNotEmpty() || onlyInJar.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The image's libraries differ from bootJar's.")
                    if (onlyInImage.isNotEmpty()) appendLine("  Only in the image: ${onlyInImage.sorted()}")
                    if (onlyInJar.isNotEmpty()) appendLine("  Only in the jar: ${onlyInJar.sorted()}")
                },
            )
        }
        logger.info("Image libraries match bootJar: ${jarLibraries.size} jars")
    }

    private fun isStarter(file: File): Boolean =
        JarFile(file).use { it.manifest?.mainAttributes?.getValue("Spring-Boot-Jar-Type") == "dependencies-starter" }
}
