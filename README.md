# vaadmin-cicd

Build and release tooling for Spring Boot and Vaadin applications: Gradle plugins that build the container
image with [Jib](https://github.com/GoogleContainerTools/jib), shell scripts that release it, and an agent skill
for sizing the JVM that runs it.

```kotlin
plugins {
    id("org.springframework.boot") version "4.0.1"
    id("io.github.vaadmin.application") version "0.1.0"
}

vaadminJib {
    image = "registry.example.com/my-app"
}
```

`./gradlew jib` then builds the image from Spring Boot's own model — the jar's classpath, the production Vaadin
bundle — tags it with the version the application reports about itself, and pushes it with the credentials in
the Docker configuration. No Docker daemon, no buildpacks.

## Why

Spring Boot's `bootBuildImage` builds with Cloud Native Buildpacks: it needs a Docker daemon, runs under
emulation on an ARM machine building for amd64, and adds a memory calculator and helpers the application may
not want. Jib builds straight from Gradle's outputs, fast and reproducibly, but knows nothing of what Spring
Boot and Vaadin decide on the way to `bootJar`. These plugins close that gap, and check that the image holds
what the jar would.

**Status: early.** Used by one application; unreleased, and published to Maven Local only. Requires Gradle 9
and Spring Boot 4.

## What is inside

| Part                                             | What it does                                                                                              |
|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| [`io.github.vaadmin.application`](docs/jib.md)   | Applies `build-info` and `jib`                                                                            |
| [`io.github.vaadmin.build-info`](docs/jib.md)    | Adds git details and an image tag (`yyyyMMdd_HHmmss`, UTC) to Spring Boot's `build-info.properties`       |
| [`io.github.vaadmin.jib`](docs/jib.md)           | Configures Jib for a Spring Boot application, and for a Vaadin one when `com.vaadin` is applied           |
| [`io.github.vaadmin.messages`](docs/messages.md) | Optional: `messagesFormat` sorts message bundles in place, `messagesCheck` fails when one is not sorted   |
| [Release scripts](docs/scripts.md)               | `preflight`, `bump-image-tag` and `release`: build, push, and point the deployment files at the tag       |
| [Agent skills](docs/skills.md)                   | `jvm-memory-sizing`: JVM flags sized against the container's memory limit, from what the application uses |

## Using it from a checkout

The repository root is a composite build including `vaadmin-gradle-plugin`. Publish the plugins to Maven Local:

```bash
./gradlew :vaadmin-gradle-plugin:publishToMavenLocal
```

and let the application find them there:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

An application can instead `includeBuild` the `vaadmin-gradle-plugin` directory from its own settings.

## License

[Apache License 2.0](LICENSE).
