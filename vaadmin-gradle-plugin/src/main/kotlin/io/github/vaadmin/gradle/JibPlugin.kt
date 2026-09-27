package io.github.vaadmin.gradle

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.springframework.boot.gradle.dsl.SpringBootExtension
import com.google.cloud.tools.jib.gradle.JibExtension as GoogleJibExtension
import com.google.cloud.tools.jib.gradle.JibPlugin as GoogleJibPlugin

abstract class JibImageExtension {
    /**
     * The image to publish, without a tag, e.g. `registry.example.com/my-app`. It is always published
     * under `build.docker.tag` from the generated `build-info.properties`, so the image carries the tag
     * the application reports about itself. `-Pvaadmin.jib.image=...` overrides it for one build, e.g.
     * to push to a local test registry.
     */
    abstract val image: Property<String>

    /**
     * Additional tags; none by default. No `latest`: a moving tag is not what a deployment should
     * pull, and a registry with immutable tags refuses it. Add it with `tags.add("latest")` where it is
     * wanted.
     */
    abstract val tags: SetProperty<String>

    /** `eclipse-temurin:<version>-jre`, following the Java toolchain, or the JVM running Gradle when none is set. */
    abstract val baseImage: Property<String>

    /**
     * The target architecture, independent of the machine building the image; `amd64` by default.
     * `-Pvaadmin.jib.architecture=arm64` for one build, e.g. a local image on Apple Silicon.
     */
    abstract val architecture: Property<String>

    abstract val user: Property<String>

    /** `/workspace` by default, where Paketo-built images run, so existing mounts keep working. */
    abstract val workingDirectory: Property<String>

    /**
     * Ports the image declares with `EXPOSE`; none by default. The declaration is documentation only —
     * compose and Kubernetes publish ports without it — and the port the application listens on is
     * decided at runtime, so the plugin does not guess it.
     */
    abstract val ports: ListProperty<String>

    /** Allows plain-HTTP registries, e.g. a local test registry; `-Pvaadmin.jib.allowInsecureRegistries=true` for one build. */
    abstract val allowInsecureRegistries: Property<Boolean>
}

/**
 * Configures Jib to build the image of a Spring Boot application, and of a Vaadin one when the
 * `com.vaadin` plugin is applied.
 *
 * Jib builds from Gradle's outputs, not from `bootJar`, so this repeats the decisions Spring Boot and
 * Vaadin make on the way to the jar and checks that the result matches it:
 * - the image gets `productionRuntimeClasspath`, as `bootJar` does, not `runtimeClasspath`, which
 *   carries `developmentOnly` dependencies such as DevTools;
 * - a Vaadin image gets the production frontend bundle, which the Vaadin plugin builds only on the
 *   way to the jar.
 *
 * No JVM flags are set in the image: memory settings belong beside the memory limit in the deploy
 * configuration.
 */
class JibPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(BuildInfoPlugin::class.java)
        val buildInfo = project.extensions.getByType(BuildInfoExtension::class.java)
        // What the application will report about itself; the plugin's own values only when the
        // generated file lacks them.
        val imageTag = BuildInfoPlugin.generated(project, BuildInfoPlugin.DOCKER_TAG_KEY).orElse(buildInfo.imageTag)
        val revision = BuildInfoPlugin.generated(project, BuildInfoPlugin.GIT_HASH_FULL_KEY).orElse(buildInfo.gitHashFull)
        val extension =
            project.extensions.create(EXTENSION_NAME, JibImageExtension::class.java).apply {
                tags.convention(emptySet())
                baseImage.convention(javaMajorVersion(project).map { "eclipse-temurin:$it-jre" })
                architecture.convention("amd64")
                user.convention("1000")
                workingDirectory.convention("/workspace")
                ports.convention(emptyList())
                allowInsecureRegistries.convention(false)
            }

        SpringBoot.whenApplied(project) {
            project.pluginManager.apply(GoogleJibPlugin::class.java)
            configureJib(project, extension, imageTag, revision)
            registerClasspathCheck(project)
        }
        project.pluginManager.withPlugin(VAADIN_PLUGIN_ID) {
            registerVaadinBundleCheck(project)
        }
    }

    private fun configureJib(
        project: Project,
        extension: JibImageExtension,
        imageTag: Provider<String>,
        revision: Provider<String>,
    ) {
        val jib = project.extensions.getByType(GoogleJibExtension::class.java)
        jib.configurationName.set(SpringBoot.PRODUCTION_RUNTIME_CLASSPATH)
        // The tag and labels are read from build-info.properties when Jib runs.
        project.tasks.named { it in JIB_TASKS }.configureEach { dependsOn(SpringBoot.BUILD_INFO_TASK) }

        // Most of Jib's extension takes plain values, so they are copied once the build script has
        // run. The tags and labels stay lazy.
        project.afterEvaluate {
            jib.from {
                image = extension.baseImage.get()
                platforms {
                    platform {
                        architecture =
                            project.providers
                                .gradleProperty(ARCHITECTURE_PROPERTY)
                                .orElse(extension.architecture)
                                .get()
                        os = "linux"
                    }
                }
            }
            val target = project.providers.gradleProperty(IMAGE_PROPERTY).orElse(extension.image)
            jib.to {
                // With no tag in it, Jib would publish the image as `latest`.
                target.orNull?.let { requireUntagged(it) }
                if (target.isPresent) setImage(target.zip(imageTag) { image, tag -> "$image:$tag" })
                setTags(extension.tags)
            }
            jib.container {
                project.extensions.getByType(SpringBootExtension::class.java).mainClass.orNull?.let { mainClass = it }
                user = extension.user.get()
                workingDirectory = extension.workingDirectory.get()
                ports = extension.ports.get()
                jvmFlags = emptyList()
                // The base image's own title and description would otherwise describe the image.
                labels.put("org.opencontainers.image.title", project.name)
                labels.put("org.opencontainers.image.description", project.description ?: "")
                labels.put("org.opencontainers.image.revision", revision)
                labels.put("org.opencontainers.image.version", imageTag)
            }
            jib.setAllowInsecureRegistries(
                project.providers
                    .gradleProperty(INSECURE_PROPERTY)
                    .map { it.toBoolean() }
                    .orElse(extension.allowInsecureRegistries)
                    .get(),
            )
        }
    }

    private fun registerClasspathCheck(project: Project) {
        val check =
            project.tasks.register(VERIFY_CLASSPATH_TASK, VerifyImageClasspath::class.java) {
                group = "verification"
                description = "Checks that the image's libraries match bootJar's."
                bootJar.set(project.tasks.named(SpringBoot.BOOT_JAR_TASK, AbstractArchiveTask::class.java).flatMap { it.archiveFile })
                imageClasspath.from(project.configurations.named(SpringBoot.PRODUCTION_RUNTIME_CLASSPATH))
            }
        project.tasks.named { it in JIB_TASKS }.configureEach { dependsOn(check) }
    }

    private fun registerVaadinBundleCheck(project: Project) {
        val mainResources =
            project.layout.dir(
                project.provider {
                    project.extensions
                        .getByType(SourceSetContainer::class.java)
                        .getByName("main")
                        .output.resourcesDir!!
                },
            )
        val check =
            project.tasks.register(VERIFY_VAADIN_TASK, VerifyVaadinProductionBundle::class.java) {
                group = "verification"
                description = "Checks that the resources hold a Vaadin production bundle."
                dependsOn(VAADIN_BUILD_FRONTEND_TASK)
                resourcesDir.set(mainResources)
            }
        project.tasks.named { it in JIB_TASKS }.configureEach { dependsOn(check) }
    }

    private fun requireUntagged(image: String) {
        val name = image.substringAfterLast('/')
        if (':' in name || '@' in name) {
            throw GradleException("vaadminJib.image '$image' carries a tag or digest; the tag comes from build-info.properties.")
        }
    }

    private fun javaMajorVersion(project: Project): Provider<String> =
        project.provider {
            project.extensions
                .findByType(JavaPluginExtension::class.java)
                ?.toolchain
                ?.languageVersion
                ?.orNull
                ?.asInt()
                ?.toString()
                ?: JavaVersion.current().majorVersion
        }

    companion object {
        const val EXTENSION_NAME = "vaadminJib"
        const val IMAGE_PROPERTY = "vaadmin.jib.image"
        const val INSECURE_PROPERTY = "vaadmin.jib.allowInsecureRegistries"
        const val ARCHITECTURE_PROPERTY = "vaadmin.jib.architecture"
        const val VERIFY_CLASSPATH_TASK = "vaadminVerifyImageClasspath"
        const val VERIFY_VAADIN_TASK = "vaadminVerifyVaadinBundle"
        const val VAADIN_PLUGIN_ID = "com.vaadin"
        const val VAADIN_BUILD_FRONTEND_TASK = "vaadinBuildFrontend"
        val JIB_TASKS = setOf("jib", "jibDockerBuild", "jibBuildTar")
    }
}
