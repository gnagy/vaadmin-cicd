package io.github.vaadmin.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.springframework.boot.gradle.dsl.SpringBootExtension
import org.springframework.boot.gradle.tasks.buildinfo.BuildInfo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Properties

abstract class BuildInfoExtension {
    /**
     * The build's time: by default when the build started. `-Pvaadmin.buildInfo.time=...` sets it for one
     * build — as a tag, `yyyyMMdd_HHmmss` in UTC, or an ISO-8601 instant — e.g. to retry a failed push
     * under the same tag. It is written as `build.time`, and the image tag is derived from it.
     */
    abstract val time: Property<Instant>

    /** The image tag this build publishes: [time] as `yyyyMMdd_HHmmss` in UTC. */
    val imageTag: Provider<String>
        get() = time.map { BuildInfoPlugin.formatTag(it) }

    /** The full git commit hash, empty outside a git working tree. */
    abstract val gitHashFull: Property<String>
}

/**
 * Adds git details and the image tag to Spring Boot's `build-info.properties`, so a running
 * application can say what it was built from, and the release scripts can read the tag back.
 *
 * Keys, under `build.`: `git.hash`, `git.hashFull`, `git.branchName`, `git.lastTag`, `time`, `docker.tag`.
 */
class BuildInfoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val startTime = Instant.now()
        val extension = project.extensions.create(EXTENSION_NAME, BuildInfoExtension::class.java)
        extension.time.convention(
            project.providers
                .gradleProperty(TIME_PROPERTY)
                .map { parseTime(it) }
                .orElse(startTime),
        )
        extension.gitHashFull.convention(git(project, "rev-parse", "HEAD"))

        SpringBoot.whenApplied(project) {
            // `springBoot { buildInfo() }` registers the task and fails when called twice, so it is
            // called only if the build script has not already.
            project.afterEvaluate {
                if (project.tasks.findByName(SpringBoot.BUILD_INFO_TASK) == null) {
                    project.extensions.getByType(SpringBootExtension::class.java).buildInfo()
                }
            }
            // Whoever registers it, the properties are added — and anything the build script sets
            // on the same task still wins, since its configuration runs after this.
            project.tasks.withType(BuildInfo::class.java).configureEach {
                properties.additional.apply {
                    put("git.hash", git(project, "rev-parse", "--short", "HEAD"))
                    put("git.hashFull", extension.gitHashFull)
                    put("git.branchName", git(project, "rev-parse", "--abbrev-ref", "HEAD").map { if (it == "HEAD") "" else it })
                    put("git.lastTag", git(project, "describe", "--tags", "--abbrev=0"))
                    put("time", extension.time.map { DateTimeFormatter.ISO_INSTANT.format(it) })
                    put("docker.tag", extension.imageTag)
                }
            }
        }
    }

    /** The trimmed output of a git command, or an empty string when it fails. */
    private fun git(
        project: Project,
        vararg args: String,
    ): Provider<String> =
        project.providers
            .exec {
                workingDir = project.rootDir
                commandLine("git", *args)
                isIgnoreExitValue = true
            }.standardOutput.asText
            .map { it.trim() }

    companion object {
        const val EXTENSION_NAME = "vaadminBuildInfo"
        const val TIME_PROPERTY = "vaadmin.buildInfo.time"
        const val DOCKER_TAG_KEY = "build.docker.tag"
        const val GIT_HASH_FULL_KEY = "build.git.hashFull"
        private val TAG_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        internal fun formatTag(time: Instant): String = TAG_FORMAT.format(time.atZone(ZoneOffset.UTC))

        /** A build time given as a tag, `yyyyMMdd_HHmmss` in UTC, or as an ISO-8601 instant. */
        internal fun parseTime(value: String): Instant =
            try {
                LocalDateTime.parse(value, TAG_FORMAT).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                try {
                    Instant.parse(value)
                } catch (_: DateTimeParseException) {
                    throw GradleException("$TIME_PROPERTY=$value is neither yyyyMMdd_HHmmss nor an ISO-8601 instant.")
                }
            }

        /**
         * A property of the `build-info.properties` that `bootBuildInfo` wrote, read when the provider
         * is evaluated; no value while the file or the key is missing. It carries no task dependency —
         * Gradle refuses to read a task's output before the task has run — so a consumer depends on
         * `bootBuildInfo` itself, as the Jib tasks do.
         */
        internal fun generated(
            project: Project,
            key: String,
        ): Provider<String> =
            project.provider {
                (project.tasks.findByName(SpringBoot.BUILD_INFO_TASK) as? BuildInfo)
                    ?.destinationDir
                    ?.get()
                    ?.file("build-info.properties")
                    ?.asFile
                    ?.takeIf { it.isFile }
                    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
                    ?.getProperty(key)
            }
    }
}
