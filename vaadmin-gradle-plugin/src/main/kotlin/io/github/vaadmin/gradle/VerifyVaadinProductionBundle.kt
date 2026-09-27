package io.github.vaadmin.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when the resources Jib would copy do not hold a Vaadin production bundle.
 *
 * `vaadinBuildFrontend` writes the bundle and a `flow-build-info.json` with `"productionMode": true`
 * into the main resources. A development build in the IDE replaces the file, and an image built from
 * those resources would start in development mode, looking for tooling the image does not have.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class VerifyVaadinProductionBundle : DefaultTask() {
    @get:Internal
    abstract val resourcesDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val buildInfo = resourcesDir.file("META-INF/VAADIN/config/flow-build-info.json").get().asFile
        if (!buildInfo.isFile) {
            throw GradleException("No Vaadin production bundle in ${resourcesDir.get().asFile}: $buildInfo is missing.")
        }
        if (!PRODUCTION_MODE.containsMatchIn(buildInfo.readText())) {
            throw GradleException("$buildInfo is not a production build, although vaadinBuildFrontend ran before this check.")
        }
    }

    private companion object {
        val PRODUCTION_MODE = Regex("\"productionMode\"\\s*:\\s*true")
    }
}
