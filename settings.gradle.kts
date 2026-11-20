pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
        // LiveKit Android pins a commit of audioswitch that is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeModule("com.github.davidliu", "audioswitch") }
        }
    }
}

rootProject.name = "StudyHub"
include(":app-user")
include(":app-admin")
include(":backend")
