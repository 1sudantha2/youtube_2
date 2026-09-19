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
        // Only required when you opt in to the *direct* NewPipeExtractor dependency
        // (see app/build.gradle.kts -> the commented-out `newpipeExtractor` block).
        // The shipped code talks to NewPipeExtractor through reflection, so a stock
        // build resolves 100% of its artifacts from google() + mavenCentral().
        maven("https://repo.newpipe.teamnewpipe.de/releases/") { name = "NewPipe" }
    }
}

rootProject.name = "You-Tube"
include(":app")
