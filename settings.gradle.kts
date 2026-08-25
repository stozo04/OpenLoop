// RepositoriesMode / FAIL_ON_PROJECT_REPOS are still @Incubating in Gradle 9 but are the
// documented Android template — keep the IDE's "unstable API" note from counting as a finding.
@Suppress("UnstableApiUsage")
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenLoop"
include(":app")
include(":baselineprofile")
