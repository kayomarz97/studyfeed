// build-logic is an INCLUDED BUILD, not buildSrc. That is deliberate, and was learned the hard way.
//
// As buildSrc, this jar lands on the root buildscript classpath — a PARENT classloader of the one
// the app's `plugins {}` block resolves AGP into. `compileOnly` AGP then fails at apply time
// (NoClassDefFoundError: com/android/build/api/variant/Variant), and `implementation` AGP fails
// differently: AGP lands on the classpath twice and Gradle refuses the versioned plugin request
// ("already on the classpath with an unknown version"). An included build is resolved through
// pluginManagement alongside AGP itself, so both share one scope and `compileOnly` is correct.
// This is also the structure Google's own android/gradle-recipes uses for this exact kind of plugin.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Same catalog as the main build: the AGP the guard compiles against can never drift from the
    // AGP that builds the app.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
