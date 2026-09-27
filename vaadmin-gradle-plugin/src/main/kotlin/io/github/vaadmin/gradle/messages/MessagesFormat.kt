package io.github.vaadmin.gradle.messages

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Sorts the message bundles in place, like `ktlintFormat`: only bundles whose order changes are
 * rewritten, and the change is reviewed in version control. Also warns about values shared by several
 * keys.
 */
@UntrackedTask(because = "Rewrites its inputs in place")
abstract class MessagesFormat : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundles: ConfigurableFileCollection

    @TaskAction
    fun format() {
        bundles.files.filter { it.isFile }.sorted().forEach { file ->
            val parsed = MessageBundles.parse(file)
            MessageBundles.logDuplicateValues(file, parsed, logger)
            if (MessageBundles.isFormatted(parsed)) {
                logger.lifecycle("${file.name}: already sorted")
            } else {
                MessageBundles.write(MessageBundles.sort(parsed), file)
                logger.lifecycle("${file.name}: sorted")
            }
        }
    }
}
