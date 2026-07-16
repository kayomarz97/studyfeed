// StudyFeed — Gradle settings.
//
// The repo root is the Gradle root, but Gradle owns ONLY the app: `compiler/` (Python, uv) and
// `spec/` are not Gradle modules and must never be included here. The three parts are joined by
// the pack format alone (§12 decoupling) — not by a build.

pluginManagement {
    // The offline-guarantees guard. Included (not buildSrc) so its plugin resolves in the same
    // classloader scope as AGP — see build-logic/settings.gradle.kts for why that matters.
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
    // Fail if any module declares its own repositories: one place to audit where code comes from.
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

rootProject.name = "studyfeed"
include(":app")
