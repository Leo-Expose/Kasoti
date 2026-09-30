# KASOTI — :app-android (DESIGN.md §1, §3; BUILD.md §3; AGENTS.md §1)
//
// THE MODULE IS OPTIONAL AT CONFIGURE TIME.
//
// `settings.gradle.kts` only `include`s this project when an Android SDK is discoverable, and
// sets `gradle.extra["kasoti.androidEnabled"]` to match. This file repeats that guard so a
// machine with no SDK gets a clear *skip* rather than AGP's "SDK location not found" — the
// whole point of the gate is that `./gradlew :core:jvmTest` and `:eval:run` stay runnable on a
// desktop-only machine and in CI (see .github/workflows/ci.yml, which reports which world it is
// in rather than pretending the Android steps passed).
//
// ---------------------------------------------------------------------------
// VERIFICATION STATUS — read this before trusting anything below
// ---------------------------------------------------------------------------
// This module was written on a machine with NO Android SDK, so it has **never been compiled**.
// Nothing in `src/main/java/dev/kasoti/android/{capture,platform,view}`, no resource file, and
// no manifest line has been through aapt2, d8, R8 or the Compose compiler. See README.md for
// the exact commands a machine with the SDK must run, and for the list of specific things that
// are expected to need fixing.
//
// What *was* verified: everything under `src/main/java/dev/kasoti/android/field`, everything
// under `src/main/java/dev/kasoti/android/ml`, and both test trees — 145 unit tests in this
// module (110 field + 35 ml), compiled with the pinned Kotlin 2.1.21 compiler against the real
// `:core` classes. That code imports no `android.*` type and never will; that is the design,
// not an accident, and it is what makes those tests runnable on a bare JVM. Run it with
//     ./app-android/tools/verify-offline.sh
// which reports 189 tests rather than 145 because the same tier also compiles and runs `:ui`'s
// 44 — that script covers both modules. This figure used to say "104", which was stale in the
// other direction: it predated the `ml` test tree and the field suite it was measuring.
// ---------------------------------------------------------------------------
//
// DEPENDENCY POLICY
//   * No Play-Services model download. ML Kit is the **bundled** artifact
//     (`com.google.mlkit:text-recognition`), never `com.google.android.gms:play-services-*`.
//     An unbundled artifact makes the OCR layer download a model on first use, which breaks
//     invariant I4 and the airplane-install proof (BUILD.md §4 names this exact trap).
//   * No OpenCV (DESIGN.md D4). The needed ops are `:core` maths plus the thin bitmap adapter
//     in `platform/AndroidImaging`.
//   * No network client of any kind, and the release build sets `android:usesCleartextTraffic`
//     off plus a `networkSecurityConfig` that denies cleartext. See the CI grep note in README.
//   * Every added coordinate carries a 5-line ADR next to it here, per AGENTS.md §5.

import java.util.Properties

// ---------------------------------------------------------------------------------------------
// The SDK gate. Mirrors settings.gradle.kts exactly; a mismatch here would mean one module
// configuring and the other failing, which is the worst possible arrangement for a first build.
// ---------------------------------------------------------------------------------------------
val androidEnabled: Boolean = run {
    val fromSettings = gradle.extra["kasoti.androidEnabled"] == true
    val local = rootProject.file("local.properties")
    val fromLocalProperties = local.exists() && run {
        java.util.Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
            ?.takeIf { it.isNotBlank() }?.let { rootProject.file(it).isDirectory } == true
    }
    val fromEnvironment = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .any { rootProject.file(it).isDirectory }
    fromSettings || fromLocalProperties || fromEnvironment
}

if (!androidEnabled) {
    logger.lifecycle(
        "KASOTI: no Android SDK found — :app-android is not configured. " +
            "This is expected on a desktop-only machine. See app-android/README.md.",
    )
    return@Gradle
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.kasoti.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.kasoti.android"
        // API 26 for `java.time` and for a keystore that will not fall back to something weaker.
        // BUILD.md §1 names "Android 10+" as the target device class, and SPEC §10 A2 assumes a
        // low-end 2 GB device; minSdk 26 keeps desugaring off the critical path.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ADR — androidx.compose:compose-bom:2024.12.01
        //   what:  the version set for the Jetpack Compose runtime the field app renders with.
        //   why:   `androidx.activity:activity-compose` is already pinned at 1.10.1, so the
        //          repo's own choice is a Compose surface; this supplies the rest of it.
        //   size:  ~2 MB AAR-equivalent of classes, no native payload. Counts against the 35 MB
        //          APK budget (NFR-S1) — `checkApkSize` measures it, it is not estimated here.
        //   lic:   Apache-2.0, same as the rest of AndroidX.
        //   alt:   classic Android Views + XML. More code for the same screen, worse for the
        //          `dev.kasoti.ui.FieldView` seam, and it would be a second design system.
        implementation(platform(libs.androidx.compose.bom))

        // ADR — ML Kit Text Recognition v2 (bundled), com.google.mlkit:text-recognition:16.0.1
        //   what:  on-device Latin + Devanagari OCR for the MRZ and the visual zone (DESIGN.md D6).
        //   why:   the *bundled* artifact is the only one that works with no network (I4, FR-R2).
        //          The Play-Services variant would download a model on first use and fail the
        //          airplane-install proof (BUILD.md §4).
        //   size:  ~10 MB — the single largest item in the APK budget, and the reason
        //          `checkApkSize` is a hard gate rather than an advisory.
        //   lic:   Google ML Kit Terms; offline bundled use is the licensed mode. Verify before
        //          any public distribution (BUILD.md §6).
        //   alt:   Tesseract-Android (Tess4J-android), already evaluated for desktop as D5. Kept
        //          as the documented fallback if the size gate or the ToS review goes badly.
        implementation(libs.mlkit.text.recognition)

        // ADR — ML Kit Barcode Scanning (bundled), com.google.mlkit:barcode-scanning:17.3.0
        //   what:  reads the Aadhaar secure QR and the watchlist QR (FR-Q1, FR-S2).
        //   why:   bundled for the same reason as OCR — a runtime download would break I4.
        //   size:  ~3 MB.
        //   lic:   Google ML Kit Terms, same review.
        //   alt:   ZXing (Apache-2.0, ~1 MB) for the *read* path only. Cheaper, but the
        //          formatted-QR paths Aadhaar uses are the reason ML Kit is here.
        implementation(libs.mlkit.barcode.scanning)

        // ADR — org.tensorflow:tensorflow-lite:2.17.0 (ALREADY PINNED, not added here)
        //   Kept at the catalog's existing pin per DESIGN.md D1 "TFLite everywhere, one hash to
        //   pin". `tensorflow-lite-gpu` is deliberately NOT taken: GPU delegates must not change
        //   outputs beyond the tolerance test, and that test does not exist yet, so the delegate
        //   stays off by default as DESIGN.md §1 requires.
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 is on because the 35 MB budget is not comfortable: ML Kit plus two TFLite
            // models plus Compose leaves little headroom, and shrinkResources removes the
            // localisation tables for the languages we do not ship.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config is declared. A release build is unsigned until provisioning
            // supplies one (scripts/provision.sh); committing a keystore is forbidden by
            // .gitignore, and shipping a debug-signed "release" would be worse.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                // TFLite's own native libs are already tiny; excluding the duplicate copies
                // shipped by `-support` saves about 1 MB and is safe: we use the runtime API
                // only, never the support library's Java surface.
                "META-INF/tensorflow_lite_version.txt",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Android unit tests run on the JVM. `isReturnDefaultValues` makes an un-stubbed android.*
    // method return null/0 instead of throwing, so a test that accidentally reaches a platform
    // class fails an assertion rather than the whole task — which is the difference between
    // "this test is red" and "this module does not build".
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    lint {
        // The `NewerVersionAvailable` and `GradleDependency` checks are informational for a
        // prototype with a pinned catalog; everything else is on, and `abortOnError` stays true
        // because a lint failure that cannot fail the build is not a gate (AGENTS.md §5).
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ADR — project(":core")
    //   The whole point of the module. Every verdict is `:core`'s; this module supplies only
    //   camera bytes, platform I/O and pixels.
    //   ⚠ BLOCKER for the `:core` owner: `:core`'s `androidTarget()` is enabled by the same
    //   settings gate, but `core/build.gradle.kts` has no `android { }` block, so AGP 8 will
    //   fail with "Namespace not specified". Add
    //       android { namespace = "dev.kasoti.core"; compileSdk = 34 }
    //   to `core/build.gradle.kts`. See README.md §"Known blockers".
    implementation(project(":core"))

    // ADR — project(":ui")
    //   The shared, framework-free presentation state (`dev.kasoti.ui`), consumed by the
    //   `ComposeFieldView` binding. `:ui` is a plain `kotlin-jvm` module and resolves into an
    //   Android consumer as an ordinary jar.
    implementation(project(":ui"))

    // ADR — kotlinx-coroutines-android:1.10.2 (version already pinned; artifact added)
    //   what:  the Android Main dispatcher, so capture work leaves the main thread.
    //   why:   CameraX analyzers and TFLite inference are both blocking; running them on the
    //          main thread is an ANR (NFR-R1 requires ANR-free capture).
    //   size:  ~0.2 MB (one class of note: the JVM artifact is already on the classpath).
    //   lic:   Apache-2.0.
    //   alt:  `kotlinx-coroutines-core` plus a hand-rolled main-thread executor. More code and
    //          more ways to get it subtly wrong.
    implementation(libs.kotlinx.coroutines.android)

    // Already pinned in gradle/libs.versions.toml; listed here so the wiring is visible in one
    // place rather than implied.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    // CameraX 1.4.1 (already pinned). `camera-view` carries PreviewView; the app's own
    // `CameraController` owns the ImageCapture/ImageAnalysis use cases and the macro focus
    // lock, because FR-C3's fixed-focus behaviour is app policy, not a CameraX default.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ADR — org.tensorflow:tensorflow-lite:2.17.0 + tensorflow-lite-support:0.4.4
    //   (both already pinned in the catalog; declared here as dependencies, not as new pins)
    //   what:  face detection and 128-d embedding, hash-pinned through
    //          `dev.kasoti.android.platform.ModelPin` before any interpreter is constructed.
    //   why:   DESIGN.md D1 fixes TFLite for both platforms so there is one hash to pin and no
    //          ONNX conversion risk.
    //   size:  runtime ~2 MB; the two model files are ≤5.3 MB and are *assets*, so they count
    //          against the 35 MB budget (NFR-S1 counts models ≤8 MB total).
    //   lic:   Apache-2.0.
    //   alt:   ORT (P2 per DESIGN.md D1), or ML Kit face detection (different weights on each
    //          platform, which breaks the diary comparability law, DESIGN.md §3).
    implementation(libs.tflite)
    implementation(libs.tflite.support)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test"))
}

/**
 * The APK size gate (NFR-S1: APK ≤ 35 MB; BUILD.md §3).
 *
 * This is the *module-level* hook. `.github/workflows/ci.yml` already runs an inline shell
 * size check on the release bundle, but that step only runs on a runner with an SDK, and it
 * duplicates the arithmetic. Putting the task here means:
 *
 *  - `./gradlew :app-android:checkApkSize` works locally on any machine with the SDK;
 *  - the budget lives next to the module it constrains, not in a YAML file;
 *  - the same task can be wired into CI later by a one-line workflow change that the lead owns
 *    (`.github/` is not this module's to edit).
 *
 * It measures the *actual* artefact. Nothing here estimates a size, because an estimate that
 * can be wrong in the optimistic direction is worse than no gate at all.
 */
val checkApkSize by tasks.registering {
    group = "verification"
    description = "Fails if the release APK or bundle exceeds the 35 MB budget (NFR-S1)."

    val budgetMb = 35.0
    val outputs = layout.buildDirectory.dir("outputs")
    val apkRelease = layout.buildDirectory.dir("outputs/apk/release")
    val aabRelease = layout.buildDirectory.dir("outputs/bundle/release")

    dependsOn("assembleRelease")
    outputs.upToDateWhen { false }

    doLast {
        val artefacts = listOfNotNull(
            apkRelease.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.maxByOrNull { it.length() },
            aabRelease.get().asFile.listFiles { f -> f.name.endsWith(".aab") }?.maxByOrNull { it.length() },
        )
        check(artefacts.isNotEmpty()) {
            "assembleRelease produced no .apk or .aab under ${outputs.get().asFile}; nothing to measure."
        }
        var overBudget = false
        for (artefact in artefacts) {
            val mb = artefact.length() / 1_048_576.0
            val verdict = if (mb > budgetMb) "OVER" else "within"
            logger.lifecycle("KASOTI size: ${artefact.name} = ${"%.2f".format(mb)} MB ($verdict, budget $budgetMb MB)")
            if (mb > budgetMb) overBudget = true
        }
        if (overBudget) {
            throw GradleException(
                "APK/bundle exceeds the $budgetMb MB budget (BUILD.md §3). " +
                    "ML Kit is the largest item; the documented fallback is Tesseract-Android " +
                    "(DESIGN.md D5/D6). Do not widen this number without a lead decision.",
            )
        }
    }
}

// A machine with the SDK runs this first; the size gate is meaningless without a build.
tasks.named("check").configure { dependsOn(checkApkSize) }
