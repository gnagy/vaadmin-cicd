package io.github.vaadmin.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Where these plugins meet Spring Boot's Gradle plugin.
 *
 * Spring Boot's plugin is a `compileOnly` dependency: the application's build provides it, in its own
 * version. That needs both plugins loaded where they can see each other — the same `plugins { }`
 * block, Spring Boot's plugin in a parent project, or a convention plugin depending on both.
 */
internal object SpringBoot {
    const val PLUGIN_ID = "org.springframework.boot"

    /** What `bootJar` packages: the runtime classpath without `developmentOnly` dependencies. */
    const val PRODUCTION_RUNTIME_CLASSPATH = "productionRuntimeClasspath"

    const val BOOT_JAR_TASK = "bootJar"
    const val BUILD_INFO_TASK = "bootBuildInfo"

    /** Runs [action] once Spring Boot's plugin is applied, with a clear message when its classes are out of reach. */
    fun whenApplied(
        project: Project,
        action: () -> Unit,
    ) {
        project.pluginManager.withPlugin(PLUGIN_ID) {
            try {
                action()
            } catch (e: NoClassDefFoundError) {
                throw GradleException(
                    "The io.github.vaadmin plugins cannot see Spring Boot's Gradle plugin classes. Apply them alongside " +
                        "org.springframework.boot: in the same plugins { } block, below the project applying Spring Boot, " +
                        "or from a convention plugin that depends on both.",
                    e,
                )
            }
        }
    }
}
