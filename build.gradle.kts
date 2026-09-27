// Lifecycle tasks at the root, run in every included build. Publishing is not among them — not every
// build publishes — so it is invoked per build, e.g. `./gradlew :vaadmin-gradle-plugin:publishToMavenLocal`.
mapOf(
    "clean" to "build",
    "build" to "build",
    "test" to "verification",
).forEach { (name, taskGroup) ->
    tasks.register(name) {
        group = taskGroup
        description = "Runs $name in every included build."
        dependsOn(gradle.includedBuilds.map { it.task(":$name") })
    }
}
