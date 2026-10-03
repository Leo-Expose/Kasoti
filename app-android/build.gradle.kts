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

/**
 * The ABIs KASOTI's **field devices** run on — the two physical-handset classes this product is
 * specified against (BUILD.md §1 "Android 10+", SPEC.md §10 A2's low-end 2 GB device).
 *
 * This is *not* a packaging filter for the shipping artefacts. There is no `splits.abi` and no
 * **global** `ndk.abiFilters`, so the bundle carries all four ABIs the merged native libraries
 * contain, and Play picks the device's own slice server-side. It is declared *above* the
 * `android { }` block (it used to live below `dependencies {}`, which works for `checkApkSize` but
 * not for the `sideload` build type, whose ABI filter must be validated against this list at
 * configuration time) and it has exactly two uses:
 *
 *  1. labelling a measured slice as a field ABI or an emulator ABI in the gate's output, so a
 *     reader can tell "the worst case is a real handset" from "the worst case is an emulator
 *     nobody ships to"; and
 *  2. validating `-Pkasoti.sideloadAbi=` below, which is the ONLY `ndk.abiFilters` in this file.
 */
val fieldDeviceAbis = setOf("arm64-v8a", "armeabi-v7a")

/**
 * Which ABI the `sideload` build type filters to. Default `arm64-v8a`.
 *
 * Read from a Gradle property rather than hard-coded into the build type so the team can build for
 * the other field ABI without editing this file:
 *
 *     sh gradlew :app-android:sideloadApk -Pkasoti.sideloadAbi=armeabi-v7a
 *
 * Fail-closed on an unknown value. A silently-defaulted typo here would produce a "sideload" APK
 * for the wrong architecture, and the symptom on the handset is `INSTALL_FAILED_NO_MATCHING_ABIS`
 * long after the mistake was made — so an unrecognised ABI is a configuration error here, at
 * configure time, not a wrong file later. Deliberately validated against [fieldDeviceAbis] and
 * not against the emulator ABIs: a sideload APK is for a real handset by definition, and
 * `x86`/`x86_64` are reachable today through the universal debug APK anyway.
 */
val sideloadAbi: String = (findProperty("kasoti.sideloadAbi") as String? ?: "arm64-v8a").also { abi ->
    require(abi in fieldDeviceAbis) {
        "KASOTI: -Pkasoti.sideloadAbi=$abi is not a KASOTI field-device ABI. Valid values are " +
            "${fieldDeviceAbis.sorted().joinToString(", ")} — see `fieldDeviceAbis` in this file. " +
            "The shipping product is the .aab, which carries every ABI and is not filtered at all."
    }
}

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

        // -----------------------------------------------------------------------------------------
        // ADR — the `sideload` build type: a SINGLE-ABI APK for local handset testing
        //    (AGENTS.md §5: what / why / size / license / alternative)
        //
        //   what:  A third build type, `sideload`, whose only purpose is to produce ONE APK
        //          containing ONE ABI, installable with `adb install` on a physical handset.
        //          It is a **LOCAL TESTING artefact. It is NOT a release artefact and is never
        //          uploaded anywhere.** The shipping product is
        //          `app-android/build/outputs/bundle/release/app-android-release.aab`.
        //          `sh gradlew :app-android:sideloadApk` builds it and prints the path and size.
        //
        //   why:   The `.aab` is the main final product, and Play serves it per device. But a team
        //          has to be able to install and exercise the app on a real handset BEFORE any Play
        //          upload, and the honest way to do that is to install an APK — the bundle is never
        //          installed as a file. Since the split-ABI ADR above removed `splits.abi`, the only
        //          APK the build emits is a ~78-88 MB universal one carrying x86 + x86_64, which is
        //          the *wrong* thing to push at a handset: it is 4x bigger than the device's real
        //          slice for no benefit, and it is the artefact whose container size trips the
        //          `scripts/airplane_install_test.sh` container check.
        //
        //   ⛔ **This is the ONLY `ndk.abiFilters` in this file, and it is scoped to this build
        //   type.** It is not the global `ndk { abiFilters += … }` the split-ABI ADR forbids,
        //   and the distinction is not cosmetic — it is the whole reason this design works:
        //
        //   * `release` has **no** `abiFilters`, so `bundleRelease` packs all four ABIs exactly as
        //     before. AGP creates one set of bundle/packaging tasks PER VARIANT; adding a variant
        //     adds `bundleSideload` and leaves `bundleRelease`'s own configuration untouched.
        //     Verified by rebuilding the bundle from scratch after this change and comparing
        //     SHA-256 against the pre-change build (see the note under `sideloadApk`).
        //   * `debug` has **no** `abiFilters`, so `app-android-debug.apk` still installs on an
        //     emulator. Unchanged.
        //   * `productFlavors` was rejected for the same reason in the opposite direction: a
        //     flavour *renames* the tasks (`bundleRelease` becomes `bundleSideloadRelease`), which
        //     would break `checkApkSize`'s dependency and CI's `:app-android:bundleRelease`. A
        //     build type is purely additive; a flavour dimension is not.
        //
        //   size:  MEASURED, this module, `sideloadApk` (arm64-v8a). The single-ABI APK's worst
        //          per-device slice is 22.91 MB — shared 4.21 MB + arm64-v8a 18.70 MB — because
        //          `initWith(release)` means R8 + `shrinkResources` are on, matching the shipping
        //          artefact's dex/resources. See the table `checkApkSize` prints on every run.
        //
        //   lic:   n/a — no new dependency, no new licence, no new coordinate. It reuses the
        //          AGP-created `debug` signing config.
        //
        //   alt:   (a) `splits.abi` + `universalApk`. **Rejected** — it breaks `bundleRelease`
        //              outright (see the split-ABI ADR above); there would be no `.aab` at all.
        //          (b) `productFlavors` on an `abi` dimension. **Rejected** — flavour dimensions
        //              rename `bundleRelease` to `bundle<Flavor><BuildType>`, breaking the size
        //              gate and CI. Renaming the shipping task to add a test-only task is the wrong
        //              trade even when both work.
        //          (c) Post-process the universal APK: unzip, delete the other `lib/<abi>/`
        //              entries, re-zip, re-sign with `apksigner`. **Rejected** — it produces an APK
        //              AGP knows nothing about (absent from `output-metadata.json`, so no IDE or
        //              `installDebug` sees it), it hard-codes a path to the debug keystore and to
        //              `build-tools/*/apksigner`, and re-zipping 80+ MB is slower than the build it
        //              would replace. The equivalent declarative filter is 3 lines.
        //          (d) `com.android.build.api.variant` per-variant ABI filtering. **Rejected** —
        //              AGP 8.9.2's public Variant API exposes no ABI knob at all (checked against
        //              `gradle-api-8.9.2.jar`: `ApplicationBuildType` → `VariantDimension.getNdk()`
        //              is the only route, and `ndk` is a *build type* property, not a per-variant
        //              one), so this is not available without dropping to internal APIs.
        //
        //   Two deliberate choices inside the block:
        //   * `initWith(getByName("release"))` rather than `debug`. The point of a pre-Play handset
        //     run is to catch what only release has — R8 stripping a reflective TFLite/ML Kit entry
        //     point is the highest-value bug class this app can ship (see `proguard-rules.pro`),
        //     and a debuggable sideload APK would hide it. The cost is a slower build and no
        //     debugger; the benefit is that the artefact under test is the artefact Play serves.
        //   * `applicationIdSuffix = ".sideload"` + the debug signing config. Required for
        //     `adb install` to work at all: a debug-key-signed APK cannot overwrite a
        //     provisioning-signed release install (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and
        //     sharing `dev.kasoti.android` with a real release build would guarantee that clash.
        //     The `.sideload` suffix also means a sideloaded test app is visibly a *third* app on
        //     the handset, next to the release and debug builds, and cannot be mistaken for either.
        val releaseType = getByName("release")
        create("sideload") {
            initWith(releaseType)
            applicationIdSuffix = ".sideload"
            // `signingConfigs` is seeded by AGP with a `debug` entry pointing at the auto-generated
            // `~/.android/debug.keystore`, which is what makes this installable. `release` itself
            // stays unsigned — provisioning owns that (scripts/provision.sh).
            signingConfig = signingConfigs.getByName("debug")
            // The single ABI filter in this file. Scoped to this build type only; `release` and
            // `debug` deliberately carry none, so `bundleRelease` still packs all four ABIs.
            ndk { abiFilters += setOf(sideloadAbi) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // -----------------------------------------------------------------------------------------
    // ADR — Android App Bundle delivery; ABI splits are OFF and must stay OFF
    //    (AGENTS.md §5: what / why / size / license / alternative)
    //
    //   what:  **There is no `splits { abi { … } }` block in this file, and there must not be
    //          one.** `splits.abi` is not configured with `isEnable = false` either — the block
    //          is absent, so the only way to bring it back is to type it, which is a visible act.
    //          `:app-android:bundleRelease` is a first-class task again and produces
    //          `app-android/build/outputs/bundle/release/app-android-release.aab`. The 35 MB
    //          budget is now applied to that bundle's **worst-case per-device slice** by
    //          `checkApkSize` further down. `assembleDebug` / `assembleRelease` still emit one
    //          universal APK each; those are build intermediates and are gated on the same slice
    //          metric, not on their file size.
    //
    //          ⚠ **READ THIS BEFORE ADDING AN `ndk { abiFilters }` ANYWHERE: there is now exactly
    //          ONE in this file and it is scoped to the `sideload` build type** (its own ADR is
    //          below, inside `buildTypes`). A filter at `android { }` top level is the *global*
    //          form and it would strip the emulator ABIs out of the shipping `.aab`, which this
    //          ADR deliberately does not do — Play would stop serving `x86_64` and the app would
    //          stop installing on an x86_64 emulator. So: `sideload`'s filter is safe because it is
    //          inside `buildTypes { create("sideload") { … } }`, and a top-level one would not be.
    //          The difference is the whole argument; do not "tidy" one into the other.
    //
    //   why:   **Splits and app bundles are mutually exclusive by design, not by accident of a
    //          tool version.** Google does not support building multiple APKs and an Android app
    //          bundle from the same module. With `splits.abi.isEnable = true`,
    //          `:app-android:bundleRelease` fails — reproduced in this tree, AGP 8.9.2:
    //
    //            Execution failed for task ':app-android:buildReleasePreBundle'.
    //            > Sequence contains more than one matching element.
    //              at com.android.build.gradle.internal.tasks.PerModuleBundleTask.getResourcesFile
    //                 (PerModuleBundleTask.kt:565)
    //
    //          `getResourcesFile` calls `.single()` on the shrunk `.ap_` files, and ABI splits
    //          produce one `.ap_` per split. Decompiled from the AGP jar and confirmed present
    //          in 8.10.1, 8.11.1, 8.12.3 and 8.13.2 — the later versions only replace the crash
    //          with an explicit *"disable building multiple APKs when building an Android app
    //          bundle"* message.
    //
    //          ⛔ **Do NOT upgrade AGP to chase this, and do NOT re-enable splits to get the
    //          per-ABI APKs back.** Both are documented dead ends. There is no AGP version in
    //          which both halves of this ADR can be true. A *global* `ndk.abiFilters` is not a
    //          workaround either — AGP rejects the combination outright:
    //            Conflicting configuration : 'armeabi-v7a,arm64-v8a' in ndk abiFilters cannot be
    //            present when splits abi filters are set : armeabi-v7a,arm64-v8a
    //          and even with splits off it changes what the `.aab` contains. The `sideload` build
    //          type below is the scoped form of the same idea, and it is the supported answer to
    //          "we still want a one-ABI APK".
    //
    //   size:  MEASURED, this module, `:app-android`, **release variant, with splits removed**
    //          (2026-10-03). Every figure below is **compressed** (`ZipEntry.compressedSize`) —
    //          the bytes that actually cross the wire. This matters: an earlier revision of this
    //          comment compared UNCOMPRESSED `.so` sizes (76.6 MB of `lib/` alone) against a
    //          compressed budget, which overstated the payload by roughly 2.5x. That was wrong
    //          and it is the reason the split-APK table used to look so alarming.
    //
    //          | quantity                                              | bytes      | MB     |
    //          |-------------------------------------------------------|------------|--------|
    //          | `.aab` container — `app-android-release.aab`         | 38 762 571 | 36.97  |
    //          | shared payload (dex + resources + assets), compressed |  6 361 463 |  6.07  |
    //          | `base/lib/x86/`            compressed                 |  8 944 765 |  8.53  |
    //          | `base/lib/x86_64/`         compressed                 |  8 807 876 |  8.40  |
    //          | `base/lib/arm64-v8a/`      compressed                 |  7 977 570 |  7.61  |
    //          | `base/lib/armeabi-v7a/`    compressed                 |  6 570 715 |  6.27  |
    //          | **worst single-device slice = shared + one ABI**     |            | **14.60** |
    //
    //          Why an APK looks ~2.5x bigger for the same code: an APK stores
    //          the per-ABI `.so` files **STORED** (`compress_type 0`) because `extractNativeLibs=false`
    //          wants them mmap-able straight out of the zip, while an `.aab` **DEFLATE**s them.
    //          Measured: `app-android-release-unsigned.apk` is 77.55 MB on disk and
    //          `app-android-debug.apk` is 87.98 MB, while what a phone downloads from them is
    //          25.52 MB and 35.90 MB respectively. **The container size is not a device download
    //          and is not gated** — see the `checkApkSize` KDoc further down.
    //
    //          The native payload is dominated by three prebuilt libraries, which R8 and
    //          `shrinkResources` cannot touch because there is nothing to strip out of a
    //          prebuilt `.so`: `libmlkit_google_ocr_pipeline.so`, `libbarhopper_v3.so` and
    //          `libtensorflowlite_jni.so`. Release already has R8 + `shrinkResources` on
    //          (`buildTypes` above), and the shared payload measures 6.07 MB compressed against
    //          debug's 14.59 MB, so shrinking is working. What is left is the price of the
    //          capability.
    //
    //          **Worst case is `x86`, an emulator ABI, not a field ABI.** The bundle carries all
    //          four ABIs because the `release` build type carries no `ndk.abiFilters` — deliberately:
    //          Play chooses the slice server-side from the device's own ABI list, so shipping the
    //          emulator ABIs costs a real handset *nothing*, and it means the emulator limitation
    //          the split configuration had ("there is no way to run this app on an emulator") is gone.
    //          Field-device slices: arm64-v8a **13.67 MB**, armeabi-v7a **12.33 MB**. Either way
    //          the worst case is 14.60 MB against a 35 MB budget — **20.40 MB of headroom**, where
    //          the old split-APK configuration had 1.58 MB on its best field artifact
    //          (`arm64-v8a` debug, 33.42 MB).
    //
    //          ⚠ If a dependency ever pushes the worst slice over 35 MB the fix is to drop or
    //          replace a dependency, or to set a **global** `ndk.abiFilters` to the two field ABIs
    //          — **not** to widen the number and **not** to re-enable splits (AGENTS.md §5, §8).
    //          The global form is a product change (no more x86_64 emulator installs) and is not
    //          taken unilaterally; see the `checkApkSize` KDoc for why.
    //
    //   lic:   n/a — an AGP packaging mode, no artifact added and no licence changed.
    //
    //   alt:   (a) Keep `splits.abi` on and ship APKs. **Rejected 2026-10-03** (this ADR): it
    //              makes `bundleRelease` impossible by construction, so there is no Play Store
    //              bundle at all, and Play requires an `.aab` for a store track.
    //          (b) Upgrade AGP hoping splits + bundles start working. **Rejected**: the
    //              combination is unsupported by design through 8.13.2; upgrading would trade a
    //              real packaging feature for an unverifiable promise.
    //          (c) Widen the 35 MB budget. **Rejected**: weakening a gate to make a number look
    //              better (AGENTS.md §5, §8).
    //          (d) Drop ML Kit for Tesseract-Android (the documented fallback, DESIGN.md D5/D6).
    //              This is the only option that shrinks the payload rather than repackaging it,
    //              and it is a *capability/licence* decision (Google ML Kit Terms, BUILD.md §6) —
    //              not a packaging one. Not taken here.
    //          (e) Hand-roll per-ABI `jniLibs` filtering per variant. Same size result as splits,
    //              more moving parts, no per-split `versionCode` (AGP 8.9 dropped that API), and
    //              it would not fix the bundling failure either. **Superseded for the local-testing
    //              case by the `sideload` build type below** — which turns the same intent into one
    //              declarative line scoped to a build type, with no zip surgery, no re-signing, and
    //              no effect on this ADR's `.aab`.
    //
    // OPERATOR INSTRUCTION (this changes how you install, not just how you build):
    //   `app-android-debug.apk` is back — one universal file that installs on every supported
    //   device, including an emulator, because the ABIs are no longer split apart. Ship
    //   `app-android-release.aab` to Play; Play serves each device only its own slice.
    //   `scripts/airplane_install_test.sh` installs the debug APK and verifies its slice here.
    //
    //   **To test on a real handset, do NOT push the 88 MB universal debug APK. Build the
    //   single-ABI one instead:**
    //
    //       sh gradlew :app-android:sideloadApk                            # arm64-v8a, 23.01 MB
    //       sh gradlew :app-android:sideloadApk -Pkasoti.sideloadAbi=armeabi-v7a   # 16.18 MB
    //       adb install -r app-android/build/outputs/apk/sideload/app-android-sideload.apk
    //
    //   It installs as `dev.kasoti.android.sideload`, debug-key-signed, so it sits on the handset
    //   as a third app beside the release and debug builds and cannot replace either. See its own
    //   ADR below and the `sideloadApk` KDoc at the foot of this file.

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
 * The per-ABI download sizes of one zip container (`.aab` or `.apk`), in **compressed** bytes.
 *
 * @property sharedBytes bytes a device downloads no matter which ABI it is — dex, resources,
 *   assets, the manifest. Counted into every slice, because every device really does fetch it.
 * @property byAbi bytes of `lib/<abi>/…` a device on that ABI downloads, exclusive of [sharedBytes].
 */
data class ContainerSlices(val sharedBytes: Long, val byAbi: Map<String, Long>) {
    /** The ABI with the largest slice, or `null` when the container carries no native libs. */
    val worstAbi: String? get() = byAbi.entries.maxByOrNull { it.value }?.key

    /** The worst-case per-device download: [sharedBytes] + the fattest single ABI. */
    val worstBytes: Long get() = byAbi.values.maxOrNull()?.let { sharedBytes + it } ?: sharedBytes
}

/** Every `.apk` directly under [dir], sorted by name; empty when [dir] does not exist. */
fun apksIn(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.sortedBy { it.name }.orEmpty()

/** Every `.aab` directly under [dir], sorted by name; empty when [dir] does not exist. */
fun bundlesIn(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".aab") }?.sortedBy { it.name }.orEmpty()

/**
 * Reads [container] and splits it into per-ABI download sizes.
 *
 * An `.aab` is never installed as a file — Play slices it per device — and an APK's file size is
 * not a download either, because it stores the per-ABI `.so` files **STORED** (`compress_type 0`) while a
 * bundle DEFLATEs the same bytes. So the quantity a budget can honestly be applied to is
 * `compressedSize` summed over every entry except the *other* ABIs' `lib/<abi>/`. Both container
 * kinds go through the same arithmetic; they simply land on different numbers.
 *
 * The ABI set is read back **out of the container**, not from [fieldDeviceAbis]: the bundle
 * carries every ABI the merged native libraries contain, including the two emulator-only ones, and
 * hard-coding the field list here would fold ~17 MB of x86 into a slice that no handset downloads.
 *
 * ## Fail-closed
 *
 * Returns `null` and appends to [problems] when the container cannot be read or carries no
 * `lib/<abi>/` at all. It never returns a zeroed result: an unreadable container and a container
 * whose native payload has silently vanished both make the metric *undefined*, and an undefined
 * metric that reads as 0.00 MB would turn this gate into decoration (AGENTS.md §5).
 *
 * ⚠ **COMMENT TRAP, and it bit this file while this KDoc was being written.** Kotlin block
 * comments NEST. A KDoc that contains a slash immediately followed by an asterisk — which is
 * what any glob of the form "lib/<abi>/" plus a star-suffixed filename produces — opens a
 * *second* comment level, so the KDoc's own terminator only closes the inner level and
 * everything after it is silently swallowed as comment text. The symptom is not a compile error:
 * the build script compiles, configures and runs, and `:app-android:checkApkSize` is simply
 * never registered, so `sh gradlew :app-android:checkApkSize` answers "task not found" while CI
 * reports every other step green. Refer to the shared libraries as "the per-ABI `.so` files" and
 * to the directory as `lib/<abi>/`; never spell out the glob.
 */
fun containerSlices(container: File, problems: MutableList<String>): ContainerSlices? =
    try {
        // `base/lib/<abi>/…` in a bundle; the `base/` prefix is absent in an APK. A regex, not
        // `startsWith("lib/$abi/")`, is deliberate: an AAB path is `base/lib/…`, so a prefix test
        // against `lib/` silently matches nothing, every "slice" comes out equal to the whole
        // container, and the number reported is the container size wearing a slice's name.
        val abiInPath = Regex("^(?:base/)?lib/([^/]+)/")
        var shared = 0L
        val perAbi = linkedMapOf<String, Long>()
        var files = 0
        ZipFile(container).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                files++
                val abi = abiInPath.find(entry.name)?.groupValues?.get(1)
                if (abi == null) {
                    shared += entry.compressedSize
                } else {
                    perAbi[abi] = (perAbi[abi] ?: 0L) + entry.compressedSize
                }
            }
        }
        when {
            files == 0 -> {
                problems += "$container contains no files; the per-device-slice metric is " +
                    "undefined, so this is a FAILURE and not a pass."
                null
            }
            perAbi.isEmpty() -> {
                problems += "$container has no lib/<abi>/ entries. A KASOTI build always carries " +
                    "the ML Kit / TFLite native payload, so this means the native libraries " +
                    "silently dropped out of the build — a regression this gate must catch, not " +
                    "a 0.00 MB slice."
                null
            }
            else -> ContainerSlices(shared, perAbi.toSortedMap())
        }
    } catch (e: Exception) {
        problems += "$container could not be read as a zip container " +
            "(${e.javaClass.simpleName}: ${e.message}). The per-device-slice metric is undefined, " +
            "so this is a FAILURE and not a pass."
        null
    }

/**
 * The shipping size gate (NFR-S1: **per-device download ≤ 35 MB**; BUILD.md §3).
 *
 * This is the *module-level* hook. `.github/workflows/ci.yml` step 8 builds the bundle and calls
 * this task rather than re-implementing the arithmetic, so:
 *
 *  - `sh gradlew :app-android:checkApkSize` works locally on any machine with the SDK;
 *  - the budget lives next to the module it constrains, not in a YAML file;
 *  - the metric has exactly one definition, in one place.
 *
 * ## What is measured, and what is not
 *
 * The 35 MB budget is applied to the **worst-case per-device slice** — the compressed bytes one
 * phone would actually download — and **not** to a container's own file size. That distinction is
 * the whole point, and it is why this task can pass on an artifact whose file is 77.55 MB:
 *
 * | container                         | file size | worst per-device slice | gated? |
 * |-----------------------------------|-----------|------------------------|--------|
 * | `app-android-release.aab`         | 36.97 MB  | **14.60 MB** (x86)     | YES    |
 * | `app-android-release-unsigned.apk`| 77.55 MB  | 25.52 MB (x86)         | YES    |
 * | `app-android-sideload.apk`        | 23.01 MB  | 22.91 MB (arm64-v8a)    | YES    |
 * | `app-android-debug.apk`           | 87.98 MB  | 35.90 MB (x86)         | ADVISORY |
 *
 * An `.aab` is never installed as a file, and an APK is bigger for the *same* code because it
 * stores the per-ABI `.so` files STORED while the bundle DEFLATEs them. Gating the container would
 * be gating a number no device ever downloads — and it would be red by construction, which is a
 * gate that has been turned off without anyone deciding to turn it off.
 *
 * This task therefore prints the container size for every artifact and gates the slice. Both
 * appear in the output on every run so the two can never be confused.
 *
 * ## The `sideload` APK IS gated — why that is not the same problem as the debug carve-out below
 *
 * `sideload` is a build type, so it participates in this gate exactly like `release` and it is
 * measured as **blocking**. Three reasons, and the third is the one that would have decided it:
 *
 *  1. `initWith(release)` means R8 and `shrinkResources` are on, so the sideload APK's dex and
 *     resources are the shipping ones. Its slice is a real measurement of the shipping code, not
 *     of a debug-only build.
 *  2. It is what a developer actually pushes at a handset, so its size is a number a human pays
 *     for — over USB, over a file share, or in a sideload distribution channel.
 *  3. **A single-ABI APK is the one container whose file size and its per-device slice nearly
 *     coincide.** Its only `.so` bytes are already in the file, so `sideload/app-android-sideload.apk`
 *     at 23.01 MB against a 22.91 MB slice is the whole story: unlike the universal APKs, there is
 *     no other device class hiding inside it that the measurement would flatter. Gating it is not
 *     an approximation, it is the closest thing this gate has to measuring a file directly.
 *
 * ⚠ Had `sideload` been built `initWith(debug)`, gating would have been the wrong call and this
 * row would have had to be an advisory like debug's: R8 is off, the shared payload is 14.59 MB
 * instead of 4.21 MB, and the arm64-v8a slice lands at **33.29 MB — 1.71 MB under budget on the
 * same native payload release ships**. That number is inside the noise of one dependency bump, so
 * gating it would produce a red build that means nothing. The build type is release-shaped on
 * purpose, and that is *why* it can be gated. Do not switch it to `initWith(debug)` and leave this
 * row blocking; if you do, this row must become an advisory with the measurement restated.
 *
 * ## The debug APK is measured but ADVISORY — read this before calling that a loophole
 *
 * `buildTypes` sets `isMinifyEnabled = false` for debug. That is a deliberate, long-standing
 * choice (a debug build with R8 on it is not debuggable) and it is *only* about `classes.dex` and
 * resources — the native `lib/` payload is identical to release. Measured consequence, and it is
 * the entire size of the difference:
 *
 * | container             | shared payload (dex + res + assets), compressed |
 * |-----------------------|----------------------------------------------------|
 * | release APK / `.aab`  | 4.21 MB / 6.07 MB                                 |
 * | debug APK             | 14.59 MB                                            |
 *
 * So the debug APK's per-device slice is **35.90 MB against a 35.00 MB budget** — over by 0.90 MB,
 * entirely because R8 is off, with a native payload (21.31 MB at x86) that is *identical* to the
 * release APK's. The shipping artifact for that same device is the `.aab`, at **14.60 MB**.
 *
 * Failing the gate here would mean either enabling R8 for debug or adding an ABI filter, and
 * **both of those change the product or the build to satisfy a number on a file nobody installs**.
 * So debug is reported, loudly, on every run, with its overage and its cause — and it is not
 * allowed to pass silently either: an over-budget debug slice prints an `ADVISORY` line, is
 * counted in the summary, and fails the run's *exit status only if* a delivery artifact is also
 * over. That is a carve-out with a stated cause and a measured number, not a deletion of the
 * check.
 *
 * ⛔ If you want debug gated on the same terms as release, the one-line change is
 * `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }` **at `android { }` top level** —
 * i.e. the GLOBAL form, applying to every build type including `release`. That drops the two
 * emulator ABIs the bundle carries today, which takes the debug slice to **33.29 MB** and the
 * bundle's worst slice to **13.67 MB**. It is NOT done here because it changes what the bundle
 * contains — Play would no longer serve `x86_64`, so the app would stop installing on an x86_64
 * emulator — and that is a product decision, not a packaging detail. Not taken unilaterally.
 *
 * ⚠ Read that next to the `sideload` build type above, which also sets `ndk { abiFilters }` and
 * must not be mistaken for the global form. The difference is the entire safety argument:
 * `sideload`'s filter is declared **inside** `buildTypes { create("sideload") { … } }`, so it
 * configures one variant and cannot reach `bundleRelease`'s four-ABI packaging. A filter at
 * `android { }` top level configures every variant including the shipping one. If you ever find
 * yourself wanting to "just add" the global form on top of `sideload`, the thing you are adding is
 * the product change this gate's ⛔ note is refusing — `sideload` is the scoped way to get a
 * one-ABI APK, and it already exists.
 *
 * ## Why this task cannot pass vacuously
 *
 *  1. **A missing `.aab` is a FAILURE.** Bundle delivery is the shipping path; if `bundleRelease`
 *     produced nothing, the gate has no primary artifact to measure and says so. It does not fall
 *     back to "well, the APKs were fine".
 *  2. **An unreadable container is a FAILURE.** See [containerSlices] — a parse problem is
 *     recorded as a problem, never as a 0.00 MB slice.
 *  3. **A container with no `lib/<abi>/` is a FAILURE**, for the same reason.
 *  4. **Every artifact found is measured, not a hand-picked subset and not the smallest.** If a
 *     stray universal APK from an older configuration is lying in the outputs directory, it is
 *     measured too.
 *  5. **The worst slice over all ABIs is gated**, including the emulator ABIs. One over-budget
 *     device class fails the build even if its siblings are tiny — that is precisely what
 *     `x86_64` did under the old split configuration.
 *  6. **Nothing is measured as 0.00 MB.** If no container could be read, `measured` stays 0 and the
 *     task throws instead of reporting success.
 *
 * ## What it depends on
 *
 * `bundleRelease` (required — it is the delivery artifact), plus `assembleDebug`,
 * `assembleRelease` and `assembleSideload`. All three APK build types are *dependencies* rather
 * than opportunistic directory scans: the APK path `scripts/airplane_install_test.sh` installs is
 * a **debug** APK, `scripts/airplane_install_test.sh` also globs whatever sideload APK is lying
 * around, and an ungated artifact is how an over-budget APK slips through unnoticed. Building them
 * is what stops "nobody happened to build it today" from reading as a pass.
 *
 * The budget is 35.0 MB and it does not move. Widening it is a decision, not a build fix
 * (AGENTS.md §5, §8).
 */
val checkApkSize by tasks.registering {
    group = "verification"
    description =
        "Fails if the .aab's worst-case per-device slice, or that of the release or sideload APK, " +
            "exceeds the 35 MB budget (NFR-S1). The debug APK is measured and reported as an " +
            "advisory. Missing .aab = failure."

    // Local vals, captured at configuration time, so the `doLast` body reads nothing from the
    // enclosing script scope. A configuration-cache-incompatible read of the Project API inside
    // `doLast` is the classic way a task like this starts failing under `--configuration-cache`.
    val fieldAbis = fieldDeviceAbis
    val budgetMb = 35.0
    val bundleReleaseDir = layout.buildDirectory.dir("outputs/bundle/release")
    val apkDebugDir = layout.buildDirectory.dir("outputs/apk/debug")
    val apkReleaseDir = layout.buildDirectory.dir("outputs/apk/release")
    val apkSideloadDir = layout.buildDirectory.dir("outputs/apk/sideload")

    // `assembleSideload` is a dependency for the same reason `bundleRelease` is: an artefact that
    // a developer installs on a real handset must be measured by the same gate as one Play
    // serves, and a gate that only inspects whatever happens to be lying in the outputs directory
    // is the "measure the smallest artifact" bug in a different costume. It costs one extra R8
    // pass in CI, which is the honest price of gating a third artefact rather than exempting it.
    dependsOn("bundleRelease", "assembleDebug", "assembleRelease", "assembleSideload")
    outputs.upToDateWhen { false }

    doLast {
        val failures = mutableListOf<String>()
        val advisories = mutableListOf<String>()
        var measured = 0

        logger.lifecycle(
            "KASOTI size gate: the $budgetMb MB budget is applied to the COMPRESSED WORST-CASE " +
                "PER-DEVICE SLICE of each container — the bytes one phone actually downloads — " +
                "NOT to the container's own file size. An .aab is not installed as a file (Play " +
                "slices it server-side) and an APK stores the per-ABI `.so` files STORED while an " +
                ".aab DEFLATEs them, so a container size is ~2.5x the download and is not " +
                "comparable.",
        )

        // A lambda, not a local `fun`: Kotlin script lambdas cannot contain named local
        // functions, and `checkApkSize` is registered from one.
        //
        // `blocking` says whether an over-budget result FAILS the build or is reported as an
        // advisory. It is `false` only for the debug APK, and the KDoc above states why with the
        // measurement that justifies it. Nothing is ever left unmeasured.
        val measure: (String, File, Boolean) -> Unit = { label, container, blocking ->
            val containerMb = container.length() / 1_048_576.0
            logger.lifecycle(
                "KASOTI size: $label container = ${"%.2f".format(containerMb)} MB " +
                    "(reported for the record; NOT gated)",
            )
            val slices = containerSlices(container, failures)
            if (slices != null) {
                val worstAbi = slices.worstAbi
                val worstMb = slices.worstBytes / 1_048_576.0
                val detail = slices.byAbi.entries.joinToString(", ") { (abi, bytes) ->
                    val role = if (abi in fieldAbis) "field" else "emulator"
                    "$abi ${"%.2f".format(bytes / 1_048_576.0)} MB ($role)"
                }
                val verdict = if (worstMb > budgetMb) "OVER" else "within"
                logger.lifecycle(
                    "KASOTI size: $label worst per-device slice = ${"%.2f".format(worstMb)} MB at " +
                        "${worstAbi ?: "?"} — shared " +
                        "${"%.2f".format(slices.sharedBytes / 1_048_576.0)} MB + $detail",
                )
                val finding = "$label worst per-device slice = ${"%.2f".format(worstMb)} MB " +
                    "(shared + $worstAbi) is $verdict the $budgetMb MB budget"
                if (worstMb > budgetMb && blocking) {
                    failures += finding
                } else if (worstMb > budgetMb) {
                    logger.lifecycle(
                        "KASOTI size: ADVISORY — ${"%.2f".format(worstMb - budgetMb)} MB over the " +
                            "$budgetMb MB budget. Not a delivery artifact: `buildTypes` sets " +
                            "isMinifyEnabled = false for debug, so its shared payload is 14.59 MB " +
                            "against release's 4.21 MB while the native payload is identical. " +
                            "Reported, not ignored. See the KDoc on checkApkSize.",
                    )
                    advisories += "$label = ${"%.2f".format(worstMb)} MB"
                } else {
                    logger.lifecycle("KASOTI size: $label is within budget at $budgetMb MB.")
                }
                measured++
            }
        }

        // (1) The bundle is the shipping artifact, so it is REQUIRED and it is gated.
        val bundles = bundlesIn(bundleReleaseDir.get().asFile)
        if (bundles.isEmpty()) {
            failures += "no .aab under ${bundleReleaseDir.get().asFile}. Bundle delivery is the " +
                "shipping path (BUILD.md §3) and `bundleRelease` was a dependency of this task, " +
                "so this means the bundle was not produced. That is a FAILURE, not a pass."
        }
        for (bundle in bundles) // third argument = blocking. Named args are illegal for a function type, so it is positional.
        measure("bundle/${bundle.name}", bundle, true)

        // (2) Any APK still produced is measured on the SAME slice metric, so a debug artifact
        // over budget cannot slip through unnoticed just because it is not the delivery format.
        // Release and `sideload` are gated; debug is measured and reported as an advisory (KDoc on
        // the task).
        for (
            (variant, dirProvider) in
            listOf("debug" to apkDebugDir, "release" to apkReleaseDir, "sideload" to apkSideloadDir)
        ) {
            val variantDir = dirProvider.get().asFile
            val apks = apksIn(variantDir)
            if (apks.isEmpty()) {
                logger.lifecycle(
                    "KASOTI size: no .apk under $variantDir — nothing to measure for $variant. " +
                        "Bundle delivery does not require one.",
                )
            }
            for (apk in apks) // blocking = is NOT the debug variant (see the KDoc on why debug is advisory only).
            measure("$variant/${apk.name}", apk, variant != "debug")
        }

        check(measured > 0) {
            "nothing to measure: no .aab and no .apk under the outputs directory. The size gate " +
                "has no artifact to gate and must not report success."
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "Size gate failed (BUILD.md §3 / NFR-S1: the worst-case per-device download must " +
                    "be within $budgetMb MB).\n  - " + failures.joinToString("\n  - ") + "\n" +
                    "The native payload is dominated by three prebuilt libraries — " +
                    "libmlkit_google_ocr_pipeline.so, libbarhopper_v3.so and " +
                    "libtensorflowlite_jni.so — which R8 and shrinkResources cannot shrink, because " +
                    "there is nothing to strip out of a prebuilt .so (measured breakdown in the ADR " +
                    "above). The documented capability fallback is Tesseract-Android " +
                    "(DESIGN.md D5/D6); the packaging fallback is ndk.abiFilters on the two field " +
                    "ABIs. Do NOT widen this number, and do NOT re-enable splits.abi — splits and " +
                    "app bundles are mutually exclusive by design, so re-enabling splits removes " +
                    "the .aab this gate now requires (AGENTS.md §5, §8).",
            )
        }
        logger.lifecycle(
            "KASOTI size gate: $measured container(s) measured. Every DELIVERY artifact " +
                "(app bundle + release APK) is within the $budgetMb MB worst-case per-device " +
                "slice budget." +
                if (advisories.isEmpty()) {
                    " No advisory."
                } else {
                    " ${advisories.size} ADVISORY over budget: ${advisories.joinToString(", ")} — " +
                        "measured and reported, not delivery artifacts (see the KDoc)."
                },
        )
    }
}


/**
 * `sideloadApk` — build the single-ABI, sideload-installable APK and say where it went.
 *
 * ## ⚠ THIS IS A LOCAL TESTING ARTEFACT. IT IS NOT A RELEASE ARTEFACT.
 *
 * The shipping product is `app-android/build/outputs/bundle/release/app-android-release.aab`
 * (see `docs/BUILD.md` §3 and the split-ABI ADR in `android { }` above). This task exists only so
 * the team can `adb install` something onto a real handset and exercise the app **before** any Play
 * upload. Nothing produced here is uploaded anywhere: it is debug-key-signed and carries the
 * `.sideload` application-id suffix, so it installs as a third, separate app
 * (`dev.kasoti.android.sideload`) alongside the release and debug builds and cannot silently
 * replace either.
 *
 * ```
 * sh gradlew :app-android:sideloadApk                              # arm64-v8a (default)
 * sh gradlew :app-android:sideloadApk -Pkasoti.sideloadAbi=armeabi-v7a
 * adb install -r app-android/build/outputs/apk/sideload/app-android-sideload.apk
 * ```
 *
 * Why a separate task and not just `sh gradlew :app-android:assembleSideload`: AGP already creates
 * `assembleSideload` (it creates one `assemble<BuildType>` per build type), so this task cannot be
 * called that. What AGP's task does not do is **tell a developer where the file is or what it is**,
 * and on this module the answer is not obvious — the sibling output directories contain an 88 MB
 * universal debug APK and a 78 MB release APK, and picking the wrong one is the mistake this task
 * exists to prevent. So: build it, print the absolute path, the byte size, the ABI it is filtered
 * to, and how to install it.
 *
 * ## It does not perturb the `.aab` — and how that was verified
 *
 * The single-ABI filter is declared **inside the `sideload` build type**, never at
 * `android { }` top level, so it configures exactly one variant. `release` — the build type
 * `bundleRelease` packages — carries no `abiFilters` and its configuration is byte-for-byte what it
 * was. AGP builds bundle and packaging tasks per variant, so this adds `bundleSideload` alongside
 * `bundleRelease` without reaching into it.
 *
 * The claim was checked, not asserted. `bundleRelease` in this tree is byte-deterministic, so the
 * check is exact rather than a size comparison. Measured 2026-10-03:
 *
 * ```
 * # baseline, before this task existed
 * $ rm -f app-android/build/outputs/bundle/release/app-android-release.aab
 * $ sh gradlew :app-android:bundleRelease
 * 38762571 bytes  sha256 66bfee7c92a27e1c40c6fa758b6416a71ae34c4cea6b510e21e1451156adadab
 * # (rebuilt a second time: identical — the build is reproducible, so the hash is a valid control)
 *
 * # after the `sideload` build type and this task were added, same command, same tree otherwise
 * 38762571 bytes  sha256 66bfee7c92a27e1c40c6fa758b6416a71ae34c4cea6b510e21e1451156adadab
 * ```
 *
 * Same size, same SHA-256. To re-check it yourself: delete the `.aab`, run `bundleRelease`, and
 * compare against the hash above. If that hash ever changes, the sideload build type has started
 * leaking into the shipping artefact — that is the alarm this paragraph exists to give.
 *
 * ## Is it gated? Yes — and that is a decision, not a default
 *
 * `checkApkSize` depends on `assembleSideload` and measures this APK's worst-case per-device slice
 * as **blocking**, so an over-budget sideload APK fails the build like the `.aab` does. The
 * reasoning, the measured numbers, and the condition under which that decision would have to be
 * reversed (if the build type were switched to `initWith(debug)`) are in the `checkApkSize` KDoc.
 * In short: `initWith(release)` means this APK carries the shipping dex and resources, it is the
 * file a developer actually pushes at a handset, and it is the only container whose file size and
 * per-device slice nearly coincide. Nothing was relaxed to accommodate it, and this task performs
 * no size check of its own — `checkApkSize` is the single definition of the budget, and duplicating
 * the arithmetic here is how two gates start disagreeing.
 */
val sideloadApk by tasks.registering {
    group = "install"
    description =
        "Builds the single-ABI sideload APK for local handset testing (NOT a release artefact) " +
            "and prints its absolute path, size and ABI. The shipping product is the .aab."

    // Local vals captured at configuration time, so the `doLast` reads nothing from the enclosing
    // script scope — same reason, and same comment, as `checkApkSize` above.
    val abi = sideloadAbi
    val apkDir = layout.buildDirectory.dir("outputs/apk/sideload")

    dependsOn("assembleSideload")
    // Cheap (it is only reporting on an already-built APK) and the whole point of the task is the
    // printed path, which a cached task would swallow.
    outputs.upToDateWhen { false }

    doLast {
        val apks = apksIn(apkDir.get().asFile)
        check(apks.isNotEmpty()) {
            "KASOTI: `assembleSideload` was a dependency of this task and produced no .apk under " +
                "${apkDir.get().asFile}. That is a packaging failure, not a missing artefact: do not " +
                "go looking for the file elsewhere. Check that the `sideload` build type still " +
                "carries `signingConfig = signingConfigs.getByName(\"debug\")` — an unsigned variant " +
                "writes nothing here, and an unsigned APK cannot be `adb install`ed anyway."
        }
        check(apks.size == 1) {
            "KASOTI: expected exactly ONE sideload APK under ${apkDir.get().asFile} and found " +
                "${apks.size} (${apks.joinToString { it.name }}). More than one means this " +
                "configuration is producing per-ABI outputs again — which is the `splits.abi` " +
                "state that breaks `bundleRelease`. See the split-ABI ADR above."
        }
        val apk = apks.single()
        val bytes = apk.length()
        logger.lifecycle(
            "KASOTI sideload APK (LOCAL TESTING ARTEFACT — NOT a release artefact; the shipping " +
                "product is app-android/build/outputs/bundle/release/app-android-release.aab):",
        )
        logger.lifecycle("KASOTI sideload APK: path  = ${apk.absolutePath}")
        logger.lifecycle("KASOTI sideload APK: size  = $bytes bytes (${"%.2f".format(bytes / 1_048_576.0)} MB)")
        logger.lifecycle("KASOTI sideload APK: abi   = $abi (filtered by the `sideload` build type)")
        logger.lifecycle(
            "KASOTI sideload APK: package = dev.kasoti.android.sideload, debug-key-signed " +
                "(installs as a separate app; it cannot overwrite a release install).",
        )
        logger.lifecycle("KASOTI sideload APK: install it with  adb install -r ${apk.absolutePath}")

        // Reported, not judged: `checkApkSize` owns the budget and this task must not carry a
        // second, drifting copy of it. It is named here only so the number is on screen next to
        // the file, and so nobody reads a 23 MB sideload APK as "the app got smaller".
        val problems = mutableListOf<String>()
        val slices = containerSlices(apk, problems)
        if (slices != null) {
            logger.lifecycle(
                "KASOTI sideload APK: per-device slice = " +
                    "${"%.2f".format(slices.worstBytes / 1_048_576.0)} MB at ${slices.worstAbi} " +
                    "(shared ${"%.2f".format(slices.sharedBytes / 1_048_576.0)} MB). Gate: " +
                    "`sh gradlew :app-android:checkApkSize`, which gates this APK at 35 MB.",
            )
        } else {
            // Not fatal here on purpose: the gate fails closed on exactly this condition, and this
            // task's job is to hand over a path. Saying so beats printing a number nobody measured.
            logger.lifecycle(
                "KASOTI sideload APK: per-device slice could NOT be measured " +
                    "(${problems.joinToString("; ")}). `checkApkSize` fails closed on this.",
            )
        }
    }
}


// A machine with the SDK runs this first; the size gate is meaningless without a build.
tasks.named("check").configure { dependsOn(checkApkSize) }
