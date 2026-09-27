package io.github.vaadmin.gradle.messages

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.language.base.plugins.LifecycleBasePlugin

abstract class MessagesExtension {
    /**
     * The message bundles to format; by default `src/main/resources/messages*.properties`, Spring Boot's
     * default basename in every locale. Replace with `bundles.setFrom(...)`.
     */
    abstract val bundles: ConfigurableFileCollection
}

/**
 * Maintenance tasks for message bundles, in the style of `ktlintFormat` and `ktlintCheck`:
 * `messagesFormat` sorts them in place and reports values shared by several keys, `messagesCheck`
 * fails when one is not sorted. Neither runs with the build; to check with `check`, add
 * `tasks.check { dependsOn("messagesCheck") }`.
 */
class MessagesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension =
            project.extensions.create(EXTENSION_NAME, MessagesExtension::class.java).apply {
                bundles.from(project.fileTree("src/main/resources") { include("messages*.properties") })
            }

        project.tasks.register(FORMAT_TASK, MessagesFormat::class.java) {
            group = "formatting"
            description = "Sorts the message bundles in place."
            bundles.from(extension.bundles)
        }
        project.tasks.register(CHECK_TASK, MessagesCheck::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that the message bundles are sorted."
            bundles.from(extension.bundles)
        }
    }

    companion object {
        const val EXTENSION_NAME = "vaadminMessages"
        const val FORMAT_TASK = "messagesFormat"
        const val CHECK_TASK = "messagesCheck"
    }
}
