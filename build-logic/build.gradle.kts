// Build logic for StudyFeed. Currently one thing: the offline/no-background guard (§8 A0).
//
// It lives here rather than inline in app/build.gradle.kts so it stays readable as the app grows
// through A1–A7, and so the enforcement is a real typed Gradle plugin — the same shape Google's
// own AGP recipes use — rather than a script fragment nobody dares touch.
plugins {
    `kotlin-dsl`
}

// Repositories are declared in settings.gradle.kts (dependencyResolutionManagement), not here.

dependencies {
    // The stable AGP Variant/Artifacts API surface (AndroidComponentsExtension, SingleArtifact).
    // compileOnly is correct HERE — unlike under buildSrc; see the note in settings.gradle.kts.
    // This build is resolved through pluginManagement in the same classloader scope as AGP itself,
    // so AGP is present at apply time and must not be bundled a second time.
    compileOnly(libs.android.gradle.api)
}

gradlePlugin {
    plugins {
        register("offlineGuarantees") {
            id = "studyfeed.offline-guarantees"
            implementationClass = "dev.kayomarz.studyfeed.build.OfflineGuaranteesPlugin"
        }
    }
}
