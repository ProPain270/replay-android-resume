import org.gradle.api.initialization.resolve.RepositoriesMode

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

rootProject.name = "AndroidLibretroPlatform"

include(":app")
include(":runtime-api")
include(":runtime-native")
include(":runtime-content")
include(":runtime-saves")
include(":bundled-core-sameboy")
include(":bundled-core-mgba")
include(":bundled-core-snes9x")
