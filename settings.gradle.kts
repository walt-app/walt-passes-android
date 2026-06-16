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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "walt-passes-android"

include(":passes-core")
include(":passes-pdf-core")
include(":passes-isolation")
include(":passes-image-decode")
include(":passes-pdf")
include(":passes-barcode-core")
include(":passes-barcode")
include(":passes-storage")
include(":passes-ui-core")
include(":passes-ui")
include(":passes-pdf-ui")
