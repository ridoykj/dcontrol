pluginManagement {
    includeBuild("build-logic")
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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dcontrol-client"

include(":app")
include(":core:network")
include(":core:websocket")
include(":core:designsystem")
include(":core:permissions")
include(":feature:pairing")
include(":feature:connection")
include(":core:storage")
include(":core:database")
include(":core:telephony")
include(":feature:files")
include(":feature:notifications")
include(":feature:clipboard")
include(":feature:deviceinfo")
include(":feature:contacts")
include(":feature:calllog")
include(":feature:sms")
include(":core:accessibility")
include(":feature:remotecontrol")
include(":feature:screen")
include(":feature:findphone")
include(":core:rtc")
include(":feature:camera")
