# Building the image — `io.github.vaadmin.application`

`io.github.vaadmin.application` applies two plugins: `io.github.vaadmin.build-info`, which adds git details and an
image tag (`yyyyMMdd_HHmmss`, UTC) to Spring Boot's `build-info.properties`, and `io.github.vaadmin.jib`, which
configures [Jib](https://github.com/GoogleContainerTools/jib) to build the application's image.

`io.github.vaadmin.jib` repeats what Spring Boot and Vaadin decide on the way to `bootJar`, because Jib
builds from Gradle's outputs instead:

- the image gets `productionRuntimeClasspath`, as `bootJar` does, so `developmentOnly` dependencies
  stay out, and `vaadminVerifyImageClasspath` fails when the image's libraries differ from the jar's;
- a Vaadin image gets the production frontend bundle: the image tasks depend on `vaadinBuildFrontend`,
  and `vaadminVerifyVaadinBundle` fails when the resources do not hold a production build;
- the image targets `linux/amd64` whatever machine builds it;
- the image is tagged, and labelled, from the `build-info.properties` that `bootBuildInfo` wrote
  (`build.docker.tag`, `build.git.hashFull`), so it always carries the version the application reports
  about itself in Actuator or its UI — even when the build script sets those properties itself;
- no JVM flags are set in the image. Memory settings belong beside the memory limit in the deploy
  configuration.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("org.springframework.boot") version "4.0.1"
    id("io.github.vaadmin.application") version "0.1.0"
}

vaadminJib {
    image = "registry.example.com/my-app"
}
```

Then `./gradlew jib` builds and pushes, using the credentials in the Docker configuration.

The plugins use Spring Boot's plugin classes without bundling them, so they have to be applied where
those classes are visible: in the same `plugins { }` block as `org.springframework.boot`, in a
subproject of the project applying it, or from a convention plugin that depends on both. Anywhere else
the build fails with a message saying so.

| `vaadminJib` property     | Default                                                                                                                                                                    |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `image`                   | — (required), without a tag; always published under `build.docker.tag`. `-Pvaadmin.jib.image=...` overrides it for one build                                               |
| `tags`                    | none — additional tags only; no `latest`, since a moving tag breaks immutable-tag registries. `tags.add("latest")` where wanted                                            |
| `baseImage`               | `eclipse-temurin:<version>-jre`, following the Java toolchain, or the JVM running Gradle without one                                                                       |
| `architecture`            | `amd64`                                                                                                                                                                    |
| `user`                    | `1000` — on the Temurin base, Ubuntu's `ubuntu` account; to revisit with the choice of base image. The working directory is not writable for it: write to mounts or `/tmp` |
| `workingDirectory`        | `/workspace`, where Paketo-built images run, so existing mounts keep working                                                                                               |
| `ports`                   | none — `EXPOSE` is documentation only, and the listening port is decided at runtime                                                                                        |
| `allowInsecureRegistries` | `false`; `-Pvaadmin.jib.allowInsecureRegistries=true` for one build                                                                                                        |

Per-build overrides, as Gradle properties named after the setting they override:

| Property                              | Overrides                                                                                  | Example use                                                                                |
|---------------------------------------|--------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `vaadmin.buildInfo.time`              | the build time, and so `build.time` and the image tag; `yyyyMMdd_HHmmss` (UTC) or ISO-8601 | retrying a failed push under the same tag — the image comes out identical, digest included |
| `vaadmin.jib.image`                   | `vaadminJib.image`                                                                         | pushing to a local test registry                                                           |
| `vaadmin.jib.architecture`            | `vaadminJib.architecture`                                                                  | `arm64` for an image to run locally on Apple Silicon                                       |
| `vaadmin.jib.allowInsecureRegistries` | `vaadminJib.allowInsecureRegistries`                                                       | a plain-HTTP local registry                                                                |

## Known limitations

- **Every build gets a new tag** unless `-Pvaadmin.buildInfo.time` fixes it: the tag is the build's start
  time.
- **Not configuration-cache safe.** The build time is taken while the build is configured, so with
  Gradle's configuration cache a later build of changed code can reuse an earlier tag.
- **`vaadinBuildFrontend` runs on every build**, about 14 s for a mid-sized application: Vaadin's plugin declares
  no outputs, so Gradle never sees it as up to date. The production bundle itself is reused.
- **Vaadin's Gradle plugin uses `Project.getProperties`**, deprecated and failing in Gradle 10, when it
  creates its tasks.
