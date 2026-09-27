package io.github.vaadmin.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.springframework.boot.gradle.tasks.buildinfo.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.google.cloud.tools.jib.gradle.JibExtension as GoogleJibExtension

class PluginsTest {
    private fun springBootProject(vararg pluginIds: String): Project =
        ProjectBuilder.builder().build().also { project ->
            project.pluginManager.apply("java")
            project.pluginManager.apply("org.springframework.boot")
            pluginIds.forEach { project.pluginManager.apply(it) }
        }

    private fun Project.evaluated(): Project = also { (it as ProjectInternal).evaluate() }

    @Test
    fun `application applies build-info and jib`() {
        val project = springBootProject("io.github.vaadmin.application")

        assertTrue(project.pluginManager.hasPlugin("io.github.vaadmin.build-info"))
        assertTrue(project.pluginManager.hasPlugin("io.github.vaadmin.jib"))
        assertTrue(project.pluginManager.hasPlugin("com.google.cloud.tools.jib"))
    }

    @Test
    fun `jib packages what bootJar packages`() {
        val project = springBootProject("io.github.vaadmin.jib").evaluated()

        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        assertEquals("productionRuntimeClasspath", jib.configurationName.get())
    }

    @Test
    fun `jib targets amd64 and sets no JVM flags`() {
        val project = springBootProject("io.github.vaadmin.jib")
        project.extensions.getByType(JibImageExtension::class.java).image.set("registry.example.com/app")
        project.evaluated()

        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        val platform = jib.from.platforms.get().single()
        assertEquals("amd64", platform.architecture)
        assertEquals("linux", platform.os)
        assertTrue(jib.to.image.orEmpty().startsWith("registry.example.com/app:"), "to.image is ${jib.to.image}")
        assertTrue(jib.container.jvmFlags.isEmpty())
        assertEquals("/workspace", jib.container.workingDirectory)
        assertEquals("1000", jib.container.user)
        assertTrue(jib.container.ports.isEmpty())
        assertEquals("eclipse-temurin:${org.gradle.api.JavaVersion.current().majorVersion}-jre", jib.from.image)
    }

    @Test
    fun `the base image follows the Java toolchain`() {
        val project = springBootProject("io.github.vaadmin.jib")
        project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java).toolchain.languageVersion
            .set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25))
        project.evaluated()

        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        assertEquals("eclipse-temurin:25-jre", jib.from.image)
    }

    @Test
    fun `jib publishes the image under the build-info tag only`() {
        val project = springBootProject("io.github.vaadmin.jib")
        project.extensions.getByType(JibImageExtension::class.java).image.set("registry.example.com/app")
        project.extensions.getByType(BuildInfoExtension::class.java).time.set(BuildInfoPlugin.parseTime("20260927_120000"))
        project.evaluated()

        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        assertEquals("registry.example.com/app:20260927_120000", jib.to.image)
        assertTrue(jib.to.tags.isEmpty())
    }

    @Test
    fun `jib takes the tag and version label from the generated build info`() {
        val project = springBootProject("io.github.vaadmin.jib")
        project.extensions.getByType(JibImageExtension::class.java).image.set("registry.example.com/app")
        project.extensions.getByType(BuildInfoExtension::class.java).time.set(BuildInfoPlugin.parseTime("20260927_120000"))
        project.evaluated()
        val buildInfo = project.tasks.getByName("bootBuildInfo") as BuildInfo
        buildInfo.destinationDir.file("build-info.properties").get().asFile.apply {
            parentFile.mkdirs()
            writeText("build.docker.tag=20260101_000000\nbuild.git.hashFull=abc123\n")
        }

        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        assertEquals("registry.example.com/app:20260101_000000", jib.to.image)
        assertEquals("20260101_000000", jib.container.labels.get()["org.opencontainers.image.version"])
        assertEquals("abc123", jib.container.labels.get()["org.opencontainers.image.revision"])
    }

    @Test
    fun `jib tasks run bootBuildInfo first`() {
        val project = springBootProject("io.github.vaadmin.jib").evaluated()

        val dependencies = project.tasks.getByName("jib").taskDependencies.getDependencies(null).map { it.name }
        assertTrue("bootBuildInfo" in dependencies, "jib depends on $dependencies")
    }

    @Test
    fun `the build time accepts a tag or an ISO instant, and the tag follows it`() {
        assertEquals(java.time.Instant.parse("2026-09-27T12:04:47Z"), BuildInfoPlugin.parseTime("20260927_120447"))
        assertEquals(java.time.Instant.parse("2026-09-27T12:04:47Z"), BuildInfoPlugin.parseTime("2026-09-27T12:04:47Z"))
        assertEquals("20260927_120447", BuildInfoPlugin.formatTag(java.time.Instant.parse("2026-09-27T12:04:47Z")))
        kotlin.test.assertFailsWith<org.gradle.api.GradleException> { BuildInfoPlugin.parseTime("yesterday") }
    }

    @Test
    fun `jib tasks run the classpath check first`() {
        val project = springBootProject("io.github.vaadmin.jib").evaluated()

        val dependencies = project.tasks.getByName("jib").taskDependencies.getDependencies(null).map { it.name }
        assertTrue(JibPlugin.VERIFY_CLASSPATH_TASK in dependencies, "jib depends on $dependencies")
    }

    @Test
    fun `the Vaadin bundle check is registered only with the Vaadin plugin`() {
        val project = springBootProject("io.github.vaadmin.jib").evaluated()

        assertNull(project.tasks.findByName(JibPlugin.VERIFY_VAADIN_TASK))
    }

    @Test
    fun `build-info adds git details and the image tag to bootBuildInfo`() {
        val project = springBootProject("io.github.vaadmin.build-info").evaluated()

        val buildInfo = assertNotNull(project.tasks.findByName("bootBuildInfo")) as BuildInfo
        val keys = buildInfo.properties.additional.keySet().get()
        assertEquals(setOf("git.hash", "git.hashFull", "git.branchName", "git.lastTag", "time", "docker.tag"), keys)
    }

    @Test
    fun `plugins wait for Spring Boot and work in any order`() {
        val project =
            ProjectBuilder.builder().build().also {
                it.pluginManager.apply("io.github.vaadmin.application")
                it.pluginManager.apply("java")
                it.pluginManager.apply("org.springframework.boot")
            }
        project.evaluated()

        assertTrue(project.pluginManager.hasPlugin("com.google.cloud.tools.jib"))
        assertNotNull(project.tasks.findByName("bootBuildInfo"))
    }
}
