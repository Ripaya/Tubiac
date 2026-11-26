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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Tubiac"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:extension-api")
include(":core:extension-loader")
include(":core:anilist")
include(":feature:home")
include(":feature:shelf")
include(":feature:browse")
include(":feature:detail")
include(":feature:reader")
include(":feature:downloads")
include(":feature:settings")
