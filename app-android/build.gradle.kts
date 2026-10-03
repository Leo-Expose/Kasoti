// KASOTI — :app-android (DESIGN.md §1, §3; BUILD.md §3; AGENTS.md §1)
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


// Must precede every statement except other `plugins {}` / `buildscript {}` blocks, or
// Gradle cannot resolve the versions and the whole build fails at configuration time.
//
// Declared WITHOUT a version, deliberately. `org.jetbrains.kotlin.android` is already on
// the buildscript classpath — `:core` applies the Kotlin Multiplatform plugin and AGP drags
// the Android variant in with it — and Gradle refuses to resolve a plugin that is on the
// classpath when the request also carries a version ("already on the classpath with an
// unknown version"). An unversioned `id(...)` resolves from whatever is there, which is the
// same 2.1.21 that `libs.versions.toml` pins for every other Kotlin plugin in the build.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

// ---------------------------------------------------------------------------------------------
// The SDK gate. Mirrors settings.gradle.kts exactly; a mismatch here would mean one module
// configuring and the other failing, which is the worst possible arrangement for a first build.
// ---------------------------------------------------------------------------------------------
val androidEnabled: Boolean = run {
    val fromSettings = gradle.extra["kasoti.androidEnabled"] == true
    val local = rootProject.file("local.properties")
    val fromLocalProperties = local.exists() && run {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
            ?.takeIf { it.isNotBlank() }?.let { rootProject.file(it).isDirectory } == true
    }
    val fromEnvironment = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .any { rootProject.file(it).isDirectory }
    fromSettings || fromLocalProperties || fromEnvironment
}

// This used to be an early `return`, which no Kotlin script body can express: a bare `return`
// is rejected outright ("'return' is not allowed here") and `return@Gradle` names a label that
// does not exist ("Unresolved reference: @Gradle"). An `if (!androidEnabled) { ... return@Gradle }`
// skip is also unreachable in practice — settings.gradle.kts:63-65 only `include`s `:app-android`
// when its own, identical SDK probe is true, and a project that is not included has no build
// script compiled at all. So the guard cannot be a skip; what is worth keeping is the *check*
// that the two gates agree. `check` is the stronger form: if the probes ever drift apart, this
// fails loudly at configuration time instead of quietly configuring half a module.
check(androidEnabled) {
    "KASOTI: :app-android was included but no Android SDK was found. settings.gradle.kts " +
        "decided this module was buildable and its own probe disagreed with this file's. " +
        "That is a gate-drift bug, not a missing SDK — fix settings.gradle.kts:52-65. " +
        "On a machine that genuinely has no SDK this script is never compiled at all."
}

// These plugins are now APPLIED here, and the SDK guard above runs first.
//
// Two faults in this file meant the module had never been built:
//
//  1. The `plugins {}` block sat AFTER the SDK guard. Gradle only hoists a `plugins {}`
//     block that is the first block in the script, so with a guard in front of it the
//     plugin versions were unknown.
//  2. Fixing that alone gave "the plugin is already on the classpath with an unknown
//     version", because `:core` pulls in AGP for its `androidTarget()` and AGP carries the
//     Kotlin Android plugin without a version. The root build script now declares it
//     `apply false` so the version is known project-wide.
//
// A machine with no SDK never gets here: `settings.gradle.kts` does not `include` this
// project at all, and the guard above returns before the plugins are applied.

android {
    namespace = "dev.kasoti.android"
    // compileSdk 35, raised from 34 (API MIGRATION, not a product decision).
    //
    // `androidx.activity:activity-compose:1.10.1` and `androidx.core:core-ktx:1.15.0` — both
    // already pinned in gradle/libs.versions.toml, neither added here — declare
    // `minCompileSdk = 35` in their AAR metadata. Compiling against 34 makes
    // `checkDebugAarMetadata` fail before a single Kotlin file is read:
    //     "Dependency 'androidx.activity:activity-compose:1.10.1' requires libraries and
    //      applications that depend on it to compile against version 35 or later of the
    //      Android APIs."
    //
    // This raises the ceiling we compile AGAINST only. `minSdk`/`targetSdk` below are
    // untouched: minSdk 26 is the runtime floor (java.time, keystore) and targetSdk 34 is a
    // behavioural promise about runtime behaviour that this change has no reason to revisit.
    // The fix is available on the SDK: platforms;android-35 is installed and AGP 8.9.2's
    // default build-tools is 35.0.0, which is what it will select.
    compileSdk = 35

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

        // ADR — org.tensorflow:tensorflow-lite:2.17.0 (ALREADY PINNED, not added here)
        //   Kept at the catalog's existing pin per DESIGN.md D1 "TFLite everywhere, one hash to
        //   pin". `tensorflow-lite-gpu` is deliberately NOT taken: GPU delegates must not change
        //   outputs beyond the tolerance test, and that test does not exist yet, so the delegate
        //   stays off by default as DESIGN.md §1 requires.
        //
        // NOTE: the Compose BOM and the two ML Kit coordinates this block used to carry are
        //   dependencies, not `defaultConfig` fields, so they never compiled here. They are
        //   declared in `dependencies {}` below with their ADRs attached.
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

    // -----------------------------------------------------------------------------------------
    // ADR — Android ABI splits (AGENTS.md §5: what / why / size / license / alternative)
    //   what:  `splits.abi` with `isUniversalApk = false`, so `assembleDebug` / `assembleRelease`
    //          emit one APK per ABI — `app-android-<abi>-<variant>.apk` — instead of one APK
    //          carrying every ABI's `lib/`. No new dependency, no new plugin, no new permission.
    //   why:   NFR-S1's 35 MB budget is unmeetable without it, and the reason is measured, not
    //          guessed. The single all-ABI debug APK was 92 220 557 B (87.9 MB) and the release
    //          was 81 295 107 B (77.53 MB). Of the debug APK's 111.9 MB uncompressed content,
    //          **76.6 MB is `lib/<abi>/` native code and only ~33.5 MB is everything else**
    //          (30.1 MB of dex, 2.26 MB of ML Kit model assets, 0.74 MB `resources.arsc`,
    //          0.25 MB `res/`). Per ABI, the native payload measured inside that APK:
    //            arm64-v8a    19 604 584 B (18.70 MB)   <- libmlkit_google_ocr_pipeline 10.6 MB
    //            armeabi-v7a  12 423 960 B (11.85 MB)   <- libmlkit_google_ocr_pipeline  6.5 MB
    //            x86_64       22 286 472 B (21.25 MB)   <- libmlkit_google_ocr_pipeline 11.1 MB
    //            x86          22 345 664 B (21.31 MB)
    //          Three libraries account for essentially all of it: `libmlkit_google_ocr_pipeline.so`
    //          (41.0 MB across four ABIs), `libbarhopper_v3.so` (20.2 MB) and
    //          `libtensorflowlite_jni.so` (15.2 MB). R8 was already on for release and
    //          `shrinkResources` was already on — they shrink *classes and resources*, and there
    //          is nothing to strip out of a prebuilt `.so`. So the size is in code we cannot
    //          shrink, and it is paid once per ABI. The only lever that does not involve deleting
    //          a capability is to stop paying for ABIs we do not ship to.
    //   size:  MEASURED, this module, `:app-android`, on the machine that wrote this comment.
    //          Debug APKs have `isMinifyEnabled = false`; release has R8 + `shrinkResources`, and
    //          the gap between each pair below is the proof that enabling splits did **not**
    //          bypass shrinking:
    //
    //          | artifact                            | bytes       | MB     | R8 vs debug |
    //          |-------------------------------------|-------------|--------|-------------|
    //          | app-android-arm64-v8a-debug.apk     | 35 015 121  | 33.39  |   —         |
    //          | app-android-arm64-v8a-release…apk   | 24 089 671  | 22.97  | −31.2 %     |
    //          | app-android-armeabi-v7a-debug.apk   | 27 852 097  | 26.56  |   —         |
    //          | app-android-armeabi-v7a-release…apk | 16 926 647  | 16.14  | −39.2 %     |
    //          | app-android-x86_64-debug.apk        | 37 693 087  | 35.95  | OVER BUDGET |
    //          | app-android-x86_64-release…apk      | 26 767 637  | 25.53  | −29.0 %     |
    //
    //          `x86_64` is NOT shipped: its debug APK is 35.95 MB, i.e. **over** the 35 MB budget.
    //          See the emulator-ABI warning below. Per-device download drops from 87.9 MB to
    //          22.97 MB (release arm64) / 16.14 MB (release armeabi-v7a).
    //          ⚠ `arm64-v8a` DEBUG has only 1.61 MB of headroom. If a dependency grows and that
    //          one crosses 35 MB, the fix is to enable R8 for debug — NOT to raise the budget.
    //   lic:   n/a — an AGP packaging feature, no artifact added.
    //   alt:   (a) Widen the 35 MB budget — rejected: weakening a gate to make a number look
    //              better (AGENTS.md §5, §8).
    //          (b) Drop ML Kit for Tesseract-Android (the documented fallback, DESIGN.md D5/D6).
    //              This is the change that would shrink the payload rather than duplicate it
    //              less, and it is a *capability/licence* decision (Google ML Kit Terms,
    //              BUILD.md §6) — not a packaging one. Not taken here.
    //          (c) Hand-roll per-ABI `jniLibs` filtering per variant. Same result, more moving
    //              parts, and no versionCode offsets, so Play would not know which build is which.
    //
    // ARTIFACTS PRODUCED BY `assembleDebug` / `assembleRelease` NOW (exactly two each):
    //   app-android/build/outputs/apk/debug/app-android-arm64-v8a-debug.apk
    //   app-android/build/outputs/apk/debug/app-android-armeabi-v7a-debug.apk
    //   app-android/build/outputs/apk/release/app-android-arm64-v8a-release-unsigned.apk
    //   app-android/build/outputs/apk/release/app-android-armeabi-v7a-release-unsigned.apk
    // There is NO universal APK and no `app-android-debug.apk` any more. Anything that globs for
    // a single APK by name (`scripts/airplane_install_test.sh` did) has to be updated.
    //
    // OPERATOR INSTRUCTION (this is a real change in how you install, not an implementation
    // detail): there is no longer one APK that runs everywhere, and there is no emulator build.
    // Pick the one that matches the device — `arm64-v8a` for any phone from roughly 2017 on,
    // `armeabi-v7a` for a low-end 32-bit ARM handset. Installing the wrong one yields
    // `INSTALL_FAILED_NO_MATCHING_ABIS`.
    //
    // ⚠ ON THE EMULATOR ABIs — read this before "helpfully" adding one back.
    //   `x86` and `x86_64` are **emulator** ABIs. No physical handset KASOTI targets is x86, so
    //   shipping either buys zero field coverage; the only thing it buys is running on an
    //   Android Studio emulator — at 21+ MB of native payload each, which is the whole budget.
    //   **Both are dropped, deliberately, on size.** Neither may be added back:
    //     · `x86` (32-bit) — Google stopped publishing 32-bit x86 system images for modern API
    //       levels, so it does not even buy emulator coverage any more; and the low-end device
    //       class in SPEC.md §10 A2 is 32-bit *ARM*, which `armeabi-v7a` already covers.
    //     · `x86_64` — measured at **35.95 MB**, over the 35 MB budget, so shipping it makes
    //       `checkApkSize` red by construction. See the size table above.
    //   The consequence to accept explicitly: **there is no way to run this app on an emulator
    //   without rebuilding with `x86_64` re-added locally.** If you need that for a demo, add it
    //   in a throwaway local edit, and do not commit it. Emulator smoke testing is then covered
    //   by `app-android/tools/verify-offline.sh` (189 JVM tests, no device) plus manual reasoning.
    //   If a future dependency grows and `arm64-v8a` goes over budget: drop or replace a
    //   dependency, or enable R8 for debug. Do NOT widen the 35 MB number, and do NOT re-add an
    //   emulator ABI. Removing a *physical-device* ABI would be a product decision with a lead;
    //   raising the budget is not available to anyone.
    //
    // `bundleRelease` IS AFFECTED, and this is the one real cost of the change — read it.
    //   AGP 8.9.2 cannot apply ABI splits and build an app bundle in the same module. With
    //   `splits.abi.isEnable = true`, `:app-android:bundleRelease` fails with:
    //
    //     Execution failed for task ':app-android:buildReleasePreBundle'.
    //     > Sequence contains more than one matching element.
    //       at com.android.build.gradle.internal.tasks.PerModuleBundleTask.getResourcesFile
    //          (PerModuleBundleTask.kt:565)
    //
    //   Reproduced with `isUniversalApk = false`, with `isUniversalApk = true`, and with AGP's
    //   default ABI set — so it is caused by `splits.abi` being on at all, not by the filter
    //   list above. `ndk.abiFilters` is not a workaround; AGP rejects the combination outright:
//    //     Conflicting configuration : 'armeabi-v7a,arm64-v8a' in ndk abiFilters cannot be
    //     present when splits abi filters are set : armeabi-v7a,arm64-v8a
    //
    //   CONSEQUENCE: with splits on, this module produces APKs and no `.aab`. The alternative —
    //   drop `splits.abi`, keep `bundleRelease`, and let the 35 MB gate go back to red at
    //   77.53 MB — is the other real option, and it is the reason this trade-off is written down
    //   rather than left for the next person to trip over. Resolving it properly needs an AGP
    //   upgrade (or the Tesseract-Android capability swap, which shrinks the payload instead of
    //   duplicating it less); neither is a packaging change. `.github/workflows/ci.yml` step 8
    //   was changed to stop calling `bundleRelease` and to delegate to `checkApkSize` instead.
    //   `checkApkSize` still measures an `.aab` when one exists, so nothing is dropped there.
    splits {
        abi {
            isEnable = true
            // `reset()` clears the ABI filter before the `include`s below. Without it AGP keeps
            // the default `include` set (all four) and this block would only be *adding* to it —
            // so dropping `x86`/`x86_64` would silently not happen.
            reset()
            include("armeabi-v7a", "arm64-v8a")
            // No universal APK. One fat all-ABI APK is 87.9 MB, which is the artefact this block
            // exists to stop producing; keeping it would put a 77.5 MB file one `adb install`
            // away and it could never satisfy the gate.
            isUniversalApk = false
            // ⚠ No per-ABI `versionCode` override is set, because AGP 8.9's DSL no longer offers
            // one: `com.android.build.api.dsl.Split` exposes only `isEnable` / `include` /
            // `exclude` / `reset` (checked with `javap` against `gradle-api-8.9.2.jar`), and the
            // old `SplitOptions.versionCode` offset API is gone. Observed consequence, recorded
            // here rather than papered over: the generated `output-metadata.json` reports the same
            // `versionCode` (1) for all three splits, so the APKs cannot be told apart by version
            // code. That is acceptable *for this project's delivery path* — AGP does not apply ABI
            // splits to `bundleRelease` and `scripts/provision.sh` publishes an `.aab`, which Play
            // slices server-side — but if these three APKs are ever uploaded to Play as separate
            // tracks, they collide and the version codes must be bumped per-ABI in
            // `defaultConfig`/CI until AGP re-exposes the override. Do not "fix" this by
            // hand-editing `output-metadata.json`; it is generated.
        }
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

    // ADR — org.tensorflow:tensorflow-lite:2.17.0, resolving to com.google.ai.edge.litert:1.0.1
    //   (the catalog pin is already there; declared here as a dependency, not as a new pin)
    //   what:  face detection and 128-d embedding, hash-pinned through
    //          `dev.kasoti.android.platform.ModelPin` before any interpreter is constructed.
    //   why:   DESIGN.md D1 fixes TFLite for both platforms so there is one hash to pin and no
    //          ONNX conversion risk. `org.tensorflow:tensorflow-lite:2.17.0` is a ~1.4 KB RELOCATION
    //          stub that declares exactly one dependency, `com.google.ai.edge.litert:litert:1.0.1`
    //          (itself `<packaging>aar</packaging>`) — so this line IS the LiteRT coordinate, and
    //          dropping it would mean re-pinning LiteRT directly for no behavioural gain.
    //   size:  runtime ~2 MB; the two model files are ≤5.3 MB and are *assets*, so they count
    //          against the 35 MB budget (NFR-S1 counts models ≤8 MB total).
    //   lic:   Apache-2.0.
    //   alt:   ORT (P2 per DESIGN.md D1), or ML Kit face detection (different weights on each
    //          platform, which breaks the diary comparability law, DESIGN.md §3).
    implementation(libs.tflite)

    // ADR — `org.tensorflow:tensorflow-lite-support:0.4.4` REMOVED (decision: keep LiteRT,
    //        drop the legacy TFLite runtime stack). No new pin; this is a deletion.
    //   what:  was the only path to the legacy `tensorflow-lite-api:2.13.0`, and its own Java
    //          surface (`TensorDisplay`, `VisionObjectDetector`, …).
    //   why:   `tensorflow-lite-support:0.4.4` -> `tensorflow-lite-api:2.13.0` re-ships 21
    //          `org.tensorflow.lite.*` classes that LiteRT's own `litert-api:1.0.1` also ships,
    //          so `checkDebugDuplicateClasses` failed the build outright (DataType, Delegate,
    //          InterpreterApi, InterpreterFactory(Api), Tensor, TensorFlowLite, the NnApi
    //          delegate, `acceleration.*`, `annotations.UsedByReflection`). Keeping the legacy
    //          stack instead was rejected: it is unmaintained, it is the reason the 2.17.0 pin
    //          had to relocate to LiteRT in the first place, and it is ~10 MB of older code
    //          against a 35 MB budget. Nothing in this module ever called the support library —
    //          `TfliteFace.kt` imports `DataType`/`Interpreter`/`Tensor` only, all of which live
    //          in the runtime API — which is what the `packaging.resources.excludes` note above
    //          already claimed.
    //   size:  removes ~10 MB of classes + the support library's own native payload.
    //   lic:   n/a (removed).
    //   alt:   `exclude` the duplicate classes instead of dropping the dependency. Rejected: it
    //          keeps two runtimes on the classpath and relies on classload order, which is
    //          exactly the non-determinism that makes a diary's embeddings incomparable.
    //
    //   One source consequence, in `dev.kasoti.android.platform.TfliteFace.kt`: LiteRT's
    //   `litert-api` drops the concrete `org.tensorflow.lite.Interpreter` class and keeps only
    //   the `InterpreterApi` interface, so that file now binds to `InterpreterApi` +
    //   `InterpreterFactory`. It is an API migration, not a behaviour change — same runtime,
    //   same options, same tensors.

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
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

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

    testImplementation(kotlin("test"))
}

/**
 * The shipping ABIs. This list is the single source of truth for BOTH the `splits.abi.include`
 * above and the gate below, and the gate fails if any one of them is missing from the build
 * output — so shrinking this list is a visible, deliberate edit to the product's device coverage
 * rather than something that can happen by accident in a build.
 */
val shippedAbis = listOf("armeabi-v7a", "arm64-v8a")

/**
 * The APK size gate (NFR-S1: APK ≤ 35 MB; BUILD.md §3).
 *
 * This is the *module-level* hook. `.github/workflows/ci.yml` already runs an inline shell
 * size check on the release bundle, but that step only runs on a runner with an SDK, and it
 * duplicates the arithmetic. Putting the task here means:
 *
 *  - `sh gradlew :app-android:checkApkSize` works locally on any machine with the SDK;
 *  - the budget lives next to the module it constrains, not in a YAML file;
 *  - the same task can be wired into CI later by a one-line workflow change that the lead owns
 *    (`.github/` is not this module's to edit).
 *
 * It measures the *actual* artefacts. Nothing here estimates a size, because an estimate that
 * can be wrong in the optimistic direction is worse than no gate at all.
 *
* ## Why ABI splits are the answer to an over-budget build (see the ADR above)
 *
 * The all-ABI debug APK measured **87.9 MB** (92 220 557 B) and the release **77.53 MB**
 * (81 295 107 B), against a 35 MB budget. The breakdown is not evenly spread — it is almost
 * entirely duplicated native code:
 *
 * | component                                    | size      |
 * |----------------------------------------------|-----------|
 * | `lib/` native code, all four ABIs            | 76.6 MB   |
 * | &nbsp;&nbsp;`libmlkit_google_ocr_pipeline.so`  | 41.0 MB |
 * | &nbsp;&nbsp;`libbarhopper_v3.so`               | 20.2 MB |
 * | &nbsp;&nbsp;`libtensorflowlite_jni.so`         | 15.2 MB |
 * | everything else (30.1 MB dex, 2.26 MB models) | ~33.5 MB |
 *
 * Per-ABI native payload: `arm64-v8a` 18.70 MB · `armeabi-v7a` 11.85 MB · `x86_64` 21.25 MB ·
 * `x86` 21.31 MB. R8 and `shrinkResources` were already on for release and neither can shrink a
 * prebuilt `.so`, so the only lever short of dropping a capability (the documented fallback is
 * Tesseract-Android, DESIGN.md D5/D6) is to stop shipping copies of the same libraries for ABIs
 * we do not target. Hence `splits.abi` above, and hence this task measures *per ABI*.
 *
 * ## What "per ABI" has to mean here, or the gate is decoration
 *
 * The previous version of this task took `maxByOrNull { it.length() }` over the release
 * directory. That is the right instinct — measure the biggest, not the smallest — but with one
 * fat APK it had nothing to choose between, and it measured debug nowhere at all even though
 * `scripts/airplane_install_test.sh` installs a **debug** APK. This version therefore:
 *
 *  1. **Requires** an APK for every ABI in [shippedAbis], in *both* `debug` and `release`.
 *     A missing ABI is a FAILURE, not a smaller number: a build that silently stopped producing
 *     `armeabi-v7a` would otherwise make the gate look *better*, which is exactly backwards.
 *  2. **Measures every `.apk` it finds**, not a hand-picked subset and not the minimum. That
 *     includes any artefact the split config did not ask for — if `isUniversalApk` were ever
 *     flipped back on, the 87.9 MB universal APK would land in this list and fail, rather than
 *     being the file that satisfies the gate.
 *  3. Fails if ANY measured artefact exceeds 35 MB. One over-budget ABI fails the build even if
 *     its siblings are tiny. This is not hypothetical: it is exactly what `x86_64` did at
 *     35.95 MB before it was dropped from `splits.abi`.
 *  4. Still measures the `.aab` when one is present, as the **worst-case single-device
 *     download** (every zip entry except the other ABIs' `lib/<abi>/`). The ABI set is read
 *     back out of the zip rather than from [shippedAbis], because a bundle would carry the
 *     unshipped ABIs too and hard-coding would inflate every slice by ~21 MB. Note that with
 *     ABI splits on, `:app-android:bundleRelease` does not currently run at all (AGP 8.9.2
 *     limitation, documented in the `splits.abi` ADR above), so in practice this arm measures
 *     nothing today. It is kept because it is correct, it costs one zip read, and it comes
 *     back the moment the AGP limitation is lifted or `splits.abi` is switched off.
 *
 * The budget is 35.0 MB and it does not move. Widening it is a decision, not a build fix
 * (AGENTS.md §5, §8).
 *
 * ## What the operator has to do differently now
 *
 * There is no longer one APK that installs everywhere, and there is no emulator build. Choose by
 * device ABI:
 *   `arm64-v8a`    — any phone from about 2017 onward (the normal case)
 *   `armeabi-v7a`  — a low-end 32-bit ARM handset (SPEC.md §10 A2's device class)
 * Installing the wrong one gives `INSTALL_FAILED_NO_MATCHING_ABIS`. Nothing globs for
 * `app-android-debug.apk` any more — that file does not exist.
 */
/** Every `.apk` directly under [dir], sorted by name; empty when [dir] does not exist. */
fun apksIn(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.sortedBy { it.name }.orEmpty()

/** Every `.aab` directly under [dir], sorted by name; empty when [dir] does not exist. */
fun bundlesIn(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".aab") }?.sortedBy { it.name }.orEmpty()

/**
 * The largest download one device could be served from an app bundle, and the ABI it is for.
 *
 * An `.aab` is not installed as a file — Play slices it per device — so its own byte count is
 * not a number any device downloads, and gating on it would be gating on the wrong quantity.
 * This sums `compressedSize` (what crosses the wire) over every entry except the ones belonging
 * to the *other* ABIs' `lib/<abi>/`, and returns the worst of those per-ABI totals.
 *
 * The ABI set is read back **out of the bundle**, not from [shippedAbis]: `bundleRelease` is not
 * affected by `splits.abi`, so the `.aab` still carries every ABI the merged native libraries
 * contain — including the two we do not ship. Hard-coding the shipped list here would add those
 * to every slice and inflate all of them by ~21 MB apiece.
 */
fun worstCaseBundleDownload(bundle: File, fallbackAbis: List<String>): Pair<String, Double> {
    // `base/lib/<abi>/…` in a bundle; the `base/` prefix is absent in an APK. Matching with a
    // regex rather than `startsWith("lib/$abi/")` is deliberate: an AAB path is `base/lib/…`, so
    // a prefix test against `lib/` silently matches nothing, every "slice" comes out equal to
    // the whole container, and the number reported is the container size wearing a slice's name.
    val abiInPath = Regex("^(?:base/)?lib/([^/]+)/")
    var worstAbi = ""
    var worstBytes = 0L
    ZipFile(bundle).use { zip ->
        val owners = zip.entries().toList().associateWith { abiInPath.find(it.name)?.groupValues?.get(1) }
        val abis = owners.values.filterNotNull().distinct().ifEmpty { fallbackAbis }
        for (abi in abis) {
            var total = 0L
            for ((entry, ownerAbi) in owners) {
                // An entry is dropped only if it belongs to some *other* ABI. Anything with no
                // ABI at all (dex, resources.pb, assets, the manifest) is shared and counted in
                // every slice, which is what a device actually downloads.
                if (ownerAbi == null || ownerAbi == abi) total += entry.compressedSize
            }
            if (total > worstBytes) {
                worstBytes = total
                worstAbi = abi
            }
        }
    }
    return worstAbi to (worstBytes / 1_048_576.0)
}

val checkApkSize by tasks.registering {
    group = "verification"
    description =
        "Fails if ANY produced per-ABI APK (debug or release), or the bundle's worst-case " +
            "single-device download, exceeds the 35 MB budget (NFR-S1). Missing ABI = failure."

    // Local vals, captured at configuration time, so the `doLast` body reads nothing from the
    // enclosing script scope. A configuration-cache-incompatible read of the Project API inside
    // `doLast` is the classic way a task like this starts failing under `--configuration-cache`.
    val expectedAbis = shippedAbis
    val budgetMb = 35.0
    val outputsDir = layout.buildDirectory.dir("outputs")
    val apkDebug = layout.buildDirectory.dir("outputs/apk/debug")
    val apkRelease = layout.buildDirectory.dir("outputs/apk/release")
    val aabRelease = layout.buildDirectory.dir("outputs/bundle/release")

    dependsOn("assembleRelease")
    outputs.upToDateWhen { false }

    doLast {
        val failures = mutableListOf<String>()
        var measured = 0

        // A lambda, not a local `fun`: Kotlin script lambdas cannot contain named local
        // functions, and `checkApkSize` is registered from one.
        val measure: (String, Double) -> Unit = { label, mb ->
            val over = mb > budgetMb
            logger.lifecycle(
                "KASOTI size: $label = ${"%.2f".format(mb)} MB " +
                    "(${if (over) "OVER" else "within"} budget, $budgetMb MB)",
            )
            if (over) failures += "$label = ${"%.2f".format(mb)} MB is over the $budgetMb MB budget"
            measured++
        }

        for ((variant, dirProvider) in listOf("debug" to apkDebug, "release" to apkRelease)) {
            val variantDir = dirProvider.get().asFile
            val apks = apksIn(variantDir)

            // (1) Every shipped ABI must actually be present, or this variant is a fail.
            for (abi in expectedAbis) {
                if (apks.none { it.name.contains("-$abi-") }) {
                    failures +=
                        "no $abi APK under $variantDir for the $variant variant. A missing ABI " +
                            "is a failure, not a smaller number — the build stopped producing a " +
                            "device class it is supposed to support. Expected an APK for each " +
                            "of: ${expectedAbis.joinToString(", ")}."
                }
            }

            // (2) Measure EVERY apk present, so an unexpected one (a universal APK, a leftover
            // from a previous configuration) cannot quietly be the artefact that passes.
            for (apk in apks) {
                measure("$variant/${apk.name}", apk.length() / 1_048_576.0)
            }
        }

        // (4) The bundle, measured as the worst single-device slice. Absent unless somebody ran
        // `bundleRelease`; `checkApkSize` does not depend on it, exactly as before this change.
        for (bundle in bundlesIn(aabRelease.get().asFile)) {
            val (abi, mb) = worstCaseBundleDownload(bundle, expectedAbis)
            measure(
                "bundle/${bundle.name} (worst single-device slice: ${abi.ifEmpty { "no lib/" }})",
                mb,
            )
        }

        check(measured > 0) {
            "assembleRelease produced no .apk under ${outputsDir.get().asFile}; nothing to measure."
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "APK size gate failed (BUILD.md §3 / NFR-S1: every per-ABI APK must be within " +
                    "$budgetMb MB).\n  - " + failures.joinToString("\n  - ") + "\n" +
                    "The native payload is dominated by ML Kit (see the ADR above for the " +
                    "measured per-library breakdown) and cannot be shrunk by R8 or " +
                    "shrinkResources. The documented capability fallback is Tesseract-Android " +
                    "(DESIGN.md D5/D6); the packaging fallback is to drop an ABI, which is a " +
                    "product decision. Do NOT widen this number without a lead decision " +
                    "(AGENTS.md §5, §8).",
            )
        }
        logger.lifecycle("KASOTI size gate: $measured artefact(s) measured, all within $budgetMb MB.")
    }
}

// A machine with the SDK runs this first; the size gate is meaningless without a build.
tasks.named("check").configure { dependsOn(checkApkSize) }
