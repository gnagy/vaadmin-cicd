# vaadmin-cicd

Build and release tooling for Spring Boot and Vaadin applications: Gradle plugins that build the
container image with Jib, and shell scripts that release it. Unreleased; published to Maven Local only. Requires Gradle 9 and Spring Boot 4.

## Gradle plugins — `vaadmin-gradle-plugin/`

| Plugin id                       | What it does                                                                                                                          |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `io.github.vaadmin.application` | Applies the two below; adds nothing of its own                                                                                        |
| `io.github.vaadmin.build-info`  | Adds git details and an image tag (`yyyyMMdd_HHmmss`, UTC) to Spring Boot's `build-info.properties`                                   |
| `io.github.vaadmin.jib`         | Configures Jib for a Spring Boot application, and for a Vaadin one when `com.vaadin` is applied                                       |
| `io.github.vaadmin.messages`    | Optional, not applied by `application`: `messagesFormat` sorts message bundles in place, `messagesCheck` fails when one is not sorted |

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

The repository root is a composite build including `vaadmin-gradle-plugin`; its `clean`, `build` and
`test` run in every included build. Publish the plugin to Maven Local through its own build:

```bash
./gradlew :vaadmin-gradle-plugin:publishToMavenLocal
```

### Known limitations

- **Every build gets a new tag** unless `-Pvaadmin.buildInfo.time` fixes it: the tag is the build's start
  time.
- **Not configuration-cache safe.** The build time is taken while the build is configured, so with
  Gradle's configuration cache a later build of changed code can reuse an earlier tag.
- **`vaadinBuildFrontend` runs on every build**, about 14 s for a mid-sized application: Vaadin's plugin declares
  no outputs, so Gradle never sees it as up to date. The production bundle itself is reused.
- **Vaadin's Gradle plugin uses `Project.getProperties`**, deprecated and failing in Gradle 10, when it
  creates its tasks.

### Message bundles — `io.github.vaadmin.messages`

Two tasks in the style of `ktlintFormat` and `ktlintCheck`:

- `messagesFormat` sorts each bundle in place — entries by key within each comment-delimited group,
  and the groups by their first key. Only bundles whose order changes are rewritten, so review the
  result with `git diff`. It also warns about values shared by several keys.
- `messagesCheck` fails when a bundle is not sorted, and prints nothing else.

Neither runs with the build — they are maintenance tasks, for running by hand, in CI or before a
commit. To check with `check`, add `tasks.check { dependsOn("messagesCheck") }`.

```kotlin
plugins {
    id("io.github.vaadmin.messages") version "0.1.0"
}

vaadminMessages {
    // default: src/main/resources/messages*.properties
    bundles.setFrom(fileTree("src/main/resources/vaadin-i18n") { include("translations*.properties") })
}
```

A rewritten bundle is re-escaped as well as reordered — `!` and `:` in values become `\!` and `\:`, and
whitespace after `=` is dropped. `java.util.Properties` reads both forms the same, so the values do not
change, but the first diff is larger than the reordering alone.

## Release scripts — `scripts/`

Plain Bash, configured by environment variables, so the same scripts run from a shell, mise, an agent or
a CI pipeline. Each carries a `#MISE description` header, so the directory can be included as mise
tasks. Run them from the application's directory.

| Script           | What it does                                                                                  |
|------------------|-----------------------------------------------------------------------------------------------|
| `preflight`      | Fails when the infra repo has incoming changes; asks before releasing uncommitted app changes |
| `bump-image-tag` | Rewrites `image: <image>:<tag>` lines in compose files and Kubernetes manifests               |
| `release`        | `preflight`, the Gradle build and push, `bump-image-tag`                                      |

| Variable               | Used by                     | Meaning                                                             |
|------------------------|-----------------------------|---------------------------------------------------------------------|
| `VAADMIN_INFRA_DIR`    | all                         | The repo holding the deployment files                               |
| `VAADMIN_IMAGE`        | `bump-image-tag`            | The image without a tag                                             |
| `VAADMIN_IMAGE_FILES`  | `bump-image-tag`            | Space-separated files to update, relative to `VAADMIN_INFRA_DIR`    |
| `VAADMIN_TAG`          | `bump-image-tag`            | The tag; by default `build.docker.tag` from the last build          |
| `VAADMIN_ALLOW_DIRTY`  | `preflight`                 | `1` to release uncommitted changes without asking                   |
| `VAADMIN_GRADLE_TASKS` | `release`                   | The build and push tasks; default `jib`                             |
| `DRY_RUN`              | `release`, `bump-image-tag` | `1` to skip the build and print the changes instead of writing them |

## Agent skills — `skills/`

Claude Code skills for the same applications, invoked by hand only (`disable-model-invocation`). Link a
skill's directory into `~/.claude/skills/` to use it.

| Skill               | What it does                                                                                                                                                                                                                                                                             |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jvm-memory-sizing` | Reads a running application's memory figures from Actuator, resident and native memory included when the application registers them, and proposes JVM flags sized against the deploy config's memory limit, plus a row for the project's measurement log. Changes nothing without asking |
