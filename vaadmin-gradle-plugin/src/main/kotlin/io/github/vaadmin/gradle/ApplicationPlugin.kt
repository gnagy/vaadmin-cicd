package io.github.vaadmin.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies the plugins a vaadmin application usually wants. It only composes: applying it is the same
 * as applying each plugin it lists.
 */
class ApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(BuildInfoPlugin::class.java)
        project.pluginManager.apply(JibPlugin::class.java)
    }
}
