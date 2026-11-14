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
        // libsignal-android is published to Signal's own Maven repository
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "WiChat"
include(":app")
