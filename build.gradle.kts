// StudyFeed — root build. Declares plugin versions once; applies nothing itself.
plugins {
    alias(libs.plugins.android.application) apply false
    // No kotlin-android: AGP 9's built-in Kotlin compiles it. See gradle/libs.versions.toml.
    alias(libs.plugins.compose.compiler) apply false
}
