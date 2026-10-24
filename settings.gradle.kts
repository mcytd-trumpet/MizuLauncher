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

rootProject.name = "MizuLauncher"

include(":app")
include(":core:download")
include(":core:minecraft")
include(":core:instance")
include(":core:launcher-bridge")
include(":core:runtime")
include(":core:auth")
include(":core:update")
