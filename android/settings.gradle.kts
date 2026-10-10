pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "pdanext"
include(":app")
// The relay engine is plain Kotlin/JVM so it can be tested off-device (see ../core).
include(":core")
project(":core").projectDir = file("../core")
