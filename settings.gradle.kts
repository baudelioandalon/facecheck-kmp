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

rootProject.name = "facecheck-kmp"

// The published artifact: org.borealnetwork:facecheck-kmp
include(":facecheck-kmp")

// Compile-checked documentation. It is not published, but it *is* built by CI,
// which is the only way a README example stays true after an API change.
include(":samples:android-quickstart")
include(":samples:immersive-ui")
