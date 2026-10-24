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
include(":ui:design")
include(":core:compat")
include(":core:download")
include(":core:minecraft")
include(":core:instance")
include(":core:launcher-bridge")
include(":core:runtime")
include(":core:auth")
include(":core:auth-yggdrasil")
include(":core:skin")
include(":core:web")
include(":core:update")
include(":core:setup")
include(":core:debug-api")
