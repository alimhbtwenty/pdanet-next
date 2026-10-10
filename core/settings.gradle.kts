// Standalone build for the relay engine: `gradle -p core test`.
// Inside the Android build this file is ignored; android/settings.gradle.kts includes :core.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "core"
