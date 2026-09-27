plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "io.github.vaadmin"
version = "0.1.0"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// Bytecode for the oldest JVM Gradle 9 runs on, whichever JDK builds the plugin.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.google.cloud.tools:jib-gradle-plugin:3.5.4")
    // Provided by the application's build, which applies Spring Boot's plugin itself.
    compileOnly("org.springframework.boot:spring-boot-gradle-plugin:4.0.1")

    testImplementation(kotlin("test"))
}

// Tests run against what the plugin compiles against, including the compileOnly Spring Boot plugin.
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

gradlePlugin {
    plugins {
        create("application") {
            id = "io.github.vaadmin.application"
            implementationClass = "io.github.vaadmin.gradle.ApplicationPlugin"
            displayName = "vaadmin application"
            description = "Applies the vaadmin build-info and jib plugins"
        }
        create("buildInfo") {
            id = "io.github.vaadmin.build-info"
            implementationClass = "io.github.vaadmin.gradle.BuildInfoPlugin"
            displayName = "vaadmin build info"
            description = "Adds git details and an image tag to Spring Boot's build info"
        }
        create("jib") {
            id = "io.github.vaadmin.jib"
            implementationClass = "io.github.vaadmin.gradle.JibPlugin"
            displayName = "vaadmin jib"
            description = "Configures Jib for Spring Boot and Vaadin applications"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Also flags task classes that leave caching undeclared.
tasks.validatePlugins {
    enableStricterValidation = true
}
