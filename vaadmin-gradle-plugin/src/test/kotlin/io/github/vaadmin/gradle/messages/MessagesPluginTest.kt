package io.github.vaadmin.gradle.messages

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesPluginTest {
    private val unsorted =
        """
        # views
        view.b=B
        view.a=A

        # entities
        entity.x=X
        """.trimIndent() + "\n"

    private val sorted =
        """
        # entities
        entity.x=X

        # views
        view.a=A
        view.b=B
        """.trimIndent() + "\n"

    private fun project(): Project =
        ProjectBuilder.builder().build().also {
            it.pluginManager.apply("java")
            it.pluginManager.apply("io.github.vaadmin.messages")
            it.file("src/main/resources").mkdirs()
            it.file("src/main/resources/messages.properties").writeText(unsorted)
        }

    @Test
    fun `messagesFormat sorts the bundle in place`() {
        val project = project()

        (project.tasks.getByName("messagesFormat") as MessagesFormat).format()

        assertEquals(sorted, project.file("src/main/resources/messages.properties").readText())
    }

    @Test
    fun `messagesFormat leaves a sorted bundle untouched`() {
        val project = project()
        val bundle = project.file("src/main/resources/messages.properties")
        bundle.writeText("# entities\nentity.x : X\n")

        (project.tasks.getByName("messagesFormat") as MessagesFormat).format()

        assertEquals("# entities\nentity.x : X\n", bundle.readText())
    }

    @Test
    fun `messagesCheck fails on an unsorted bundle and passes once it is sorted`() {
        val project = project()
        val check = project.tasks.getByName("messagesCheck") as MessagesCheck

        kotlin.test.assertFailsWith<org.gradle.api.GradleException> { check.check() }
        (project.tasks.getByName("messagesFormat") as MessagesFormat).format()
        check.check()
    }

    @Test
    fun `nothing runs with the build`() {
        val project = project()

        val checkDependencies = project.tasks.getByName("check").taskDependencies.getDependencies(null).map { it.name }
        val buildDependencies = project.tasks.getByName("build").taskDependencies.getDependencies(null).map { it.name }
        assertTrue(checkDependencies.none { it.contains("Messages") }, "check depends on $checkDependencies")
        assertTrue(buildDependencies.none { it.contains("Messages") }, "build depends on $buildDependencies")
    }
}
