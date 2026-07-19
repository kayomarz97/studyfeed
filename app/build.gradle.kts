import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // NO org.jetbrains.kotlin.android here — AGP 9.0+ has BUILT-IN Kotlin support and applying
    // kotlin-android alongside it is a hard error ("not compatible with the new DSL").
    // Built-in Kotlin replaces kotlin-android ONLY; the Compose compiler is still its own plugin.
    alias(libs.plugins.compose.compiler)
    // KSP2 — Room's annotation processor (A1). kapt is a HARD ERROR under AGP 9's built-in Kotlin and
    // KSP1 doesn't support Kotlin 2.4.10 / AGP 9.3.0, so KSP2 is the only supported path, not a choice.
    alias(libs.plugins.ksp)
    // The offline/no-background guard (buildSrc). Not optional, not skippable: it hangs itself off
    // every assemble task, so there is no way to build an APK that violates §8 A0.
    id("studyfeed.offline-guarantees")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Release signing (BUILD_PROMPT §11d — the keystore that can cost the exam).
//
// The keystore and its passwords live OUTSIDE this repo and are never committed. Their location
// is machine-local, so it is NOT hardcoded here: this file is shared across every build machine.
// Resolution order:
//   1. env var  STUDYFEED_KEYSTORE_PROPERTIES  = absolute path to keystore.properties
//   2. Gradle property `studyfeed.keystoreProperties` (put it in ~/.gradle/gradle.properties,
//      which is user-level and never committed)
//   3. absent -> release signing is skipped with a warning, and `assembleDebug` still works.
//
// (3) matters: a fresh clone must build without the secret. A build that only works on one
// machine is a build that will fail at the worst possible moment.
// ─────────────────────────────────────────────────────────────────────────────────────────────
val keystorePropertiesPath: String? =
    System.getenv("STUDYFEED_KEYSTORE_PROPERTIES")
        ?: providers.gradleProperty("studyfeed.keystoreProperties").orNull

val keystoreProperties: Properties? = keystorePropertiesPath
    ?.let { file(it) }
    ?.takeIf { it.isFile }
    ?.let { propsFile -> Properties().apply { propsFile.inputStream().use { load(it) } } }

android {
    namespace = "dev.kayomarz.studyfeed"
    compileSdk = 36

    defaultConfig {
        // PERMANENT. Changing this after progress exists on the phone reads as a different app:
        // install-side-by-side, and the old progress.db is stranded. See §11d.
        applicationId = "dev.kayomarz.studyfeed"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-a0"

        // Instrumented tests (A1). AndroidJUnitRunner ships in androidx.test:runner, which is an
        // androidTestImplementation dep — it is NOT on the app's runtime classpath and never reaches
        // the shipped APK, so the offline guard's denylist is unaffected.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "StudyFeed: no keystore.properties found -> release builds will be UNSIGNED. " +
                        "Set STUDYFEED_KEYSTORE_PROPERTIES or -Pstudyfeed.keystoreProperties. See app/README.md."
                )
            }
        }
    }

    // Compile with the JBR (JDK 21) but emit JVM 17 bytecode. A jvmToolchain(17) would be the
    // other option, but Gradle can only see one JDK on this machine (the JBR), so it would try to
    // auto-provision a second one over the network — pointless when 21 compiles 17 bytecode fine.
    // 17 also matches java-fsrs's floor. minSdk 26 has native java.time, so no core-library
    // desugaring is needed for FSRS's Instant handling.
    //
    // This also sets Kotlin's target: under AGP's built-in Kotlin, kotlin.compilerOptions.jvmTarget
    // DEFAULTS to android.compileOptions.targetCompatibility, so there is deliberately no
    // `kotlin { compilerOptions { jvmTarget = ... } }` block — it would just restate this and give
    // the two a chance to drift apart.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // FSRS-6 scheduling. Pure-Java MIT lib; its only transitives are Jackson (serialization,
    // NOT networking) — the denylist in buildSrc must not false-positive on those.
    implementation(libs.fsrs)

    // Room — progress_<EXAM>.db ONLY (plan D1). The content pack is read with raw SQLite and ATTACHed
    // on a dedicated read-only connection (plan D4), because ATTACH is per-connection and Room POOLS
    // connections: a pooled ATTACH is invisible to the next query. Do not "simplify" the pack onto
    // Room — that is the bug D4 exists to avoid. NOTE: no room-ktx (merged into room-runtime at 2.7.0).
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)

    // Instrumented tests: SQLite/Room behaviour has no JVM implementation, so it can only be observed
    // on a real Android image. Pure logic stays in JVM tests (plan D7); these cover the I/O.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    // KSP is configured PER SOURCE SET: `ksp(...)` above covers `main` ONLY. Without this line, a
    // @Database declared in an androidTest fails at RUNTIME, not build time, with a baffling
    // "ClassNotFoundException: ..._Impl" — the processor simply never ran for this source set.
    kspAndroidTest(libs.room.compiler)
}
