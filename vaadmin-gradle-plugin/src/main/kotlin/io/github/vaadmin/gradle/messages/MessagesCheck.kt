package io.github.vaadmin.gradle.messages

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when a message bundle is not sorted, like `ktlintCheck`. Duplicate values are left to
 * `messagesFormat`, so the check stays quiet when it passes.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class MessagesCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val unsorted =
            bundles.files
                .filter { it.isFile }
                .sorted()
                .filterNot { MessageBundles.isFormatted(MessageBundles.parse(it)) }
        if (unsorted.isNotEmpty()) {
            throw GradleException("Message bundles not sorted: ${unsorted.joinToString { it.name }}. Run messagesFormat.")
        }
    }
}
