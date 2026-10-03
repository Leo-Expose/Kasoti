# KASOTI — :app-android  (DESIGN.md §1, §3 · BUILD.md §3 · AGENTS.md §1)

The CameraX field app: the guided stranger flow, the macro microscope moment, the verdict
card, the trust lane, demo mode, and the wiring to `:core` that makes all of those decisions.

---

## 1. Verification status — READ FIRST

**✅ This module COMPILES AND PACKAGES as of 2026-10-03. It has still never been RUN.**

`sh gradlew :app-android:assembleDebug` and `:assembleRelease` both succeed, so everything under
`src/main/java/dev/kasoti/android/{capture,platform,view}`, every resource file and every manifest
line **has** now been through `aapt2`, `d8`, `R8` and the Compose compiler. `bundleRelease` succeeds
too, and `sh gradlew :app-android:checkApkSize` reports all four containers within the 35.0 MB
worst-case per-device-slice budget.

**Why it never compiled before, in one line:** `app-android/build.gradle.kts` line 1 began with
`#`. `#` is a **Groovy** comment marker; in a Kotlin script (`.kts`) the Kotlin script compiler
parses it as **source**, so the module never configured. Exactly one `#` existed in the file, on
that line. Nothing about the module's code, resources, manifest or dependencies was wrong.

**What is still true, and what "compiles" does not tell you:**

- **No device, no `adb`, no run.** The app has never been installed or launched. CameraX, ML Kit
  OCR, TFLite inference, Compose rendering, the Keystore and the on-device wipe are **compiled,
  not exercised.** Compilation proves signatures; only a run proves behaviour.
- **There is no `sideloadApk` that has ever been installed.** The task exists and builds a
  one-ABI, debug-key-signed APK for exactly this purpose (see §1a), but nothing in this repo has
  pushed one to a handset, so the run half of the claim is still unmade.
- ⚠️ **CORRECTED 2026-10-03: this used to read "There is no unit-test coverage for the
  capture/ML path at the Android layer. `:app-android:test` does not compile — 160 errors, all in
  `src/test/java/dev/kasoti/android/ml/BlazeFaceDesktopParityTest.kt` (150) and
  `…/ml/BlazeFaceInputTest.kt` (10)".** That defect is **fixed**: both files were **moved** to
  `platform/src/jvmTest/kotlin/dev/kasoti/android/ml/`, where `:platform`'s **JVM** classes are
  visible, and `sh gradlew :app-android:test` is BUILD SUCCESSFUL — 20 suites, **142 unique tests
  per build-type variant** (426 executions across debug/release/sideload), 0 failures. What is
  **still** true and is what this bullet now means: the two parity suites are **Android-module
  tests that live in `:platform`**, so they are no longer counted as `:app-android` coverage, and
  the **capture/ML-path coverage that does live here has still never executed on hardware** —
  8 test files compile and pass on a JVM, which is not the same claim as the camera, ML Kit or
  TFLite paths having run. `app-android/tools/verify-offline.sh` still runs all of them on a bare
  JVM (204/204).

### 1a. Getting an APK onto a real handset

The shipping product is the **Android App Bundle**:

```
sh gradlew :app-android:bundleRelease
#   -> app-android/build/outputs/bundle/release/app-android-release.aab   (36.97 MB)
```

An `.aab` is never installed as a file — Play slices it per device — so it cannot be used for a
pre-Play handset run. `assembleDebug` / `assembleRelease` *do* emit installable APKs, but they are
**universal**: `arm64-v8a` + `armeabi-v7a` + `x86` + `x86_64` in one 88 MB / 78 MB file. For a
handset you want one ABI:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

sh gradlew :app-android:sideloadApk                              # arm64-v8a, 23.01 MB
sh gradlew :app-android:sideloadApk -Pkasoti.sideloadAbi=armeabi-v7a   # 16.18 MB
# the task prints the absolute path and size; install it with:
adb install -r app-android/build/outputs/apk/sideload/app-android-sideload.apk
adb shell monkey -p dev.kasoti.android.sideload -c android.intent.category.LAUNCHER 1
```

| | |
|---|---|
| What it is | a **LOCAL TESTING artefact**. Never uploaded to Play or anywhere else. |
| The shipping product | `app-android/build/outputs/bundle/release/app-android-release.aab` |
| Path | `app-android/build/outputs/apk/sideload/app-android-sideload.apk` |
| Size | 23.01 MB (arm64-v8a) · 16.18 MB (armeabi-v7a) — measured 2026-10-03 |
| Package | `dev.kasoti.android.sideload` — a **third** app on the handset, beside release and debug |
| Signing | the auto-generated **debug** key, so `adb install` works. It cannot overwrite a provisioning-signed release install (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), by design. |
| Build type | `initWith(release)` — R8 + `shrinkResources` **on**, so this is the closest thing to what Play serves. A pre-Play run should catch an R8-stripped TFLite/ML Kit entry point, which a debuggable APK would hide. |
| Gate | **Yes, blocking.** `checkApkSize` depends on `assembleSideload` and measures this APK's worst-case per-device slice (22.91 MB at arm64-v8a) against the same 35 MB budget. |

How it works: the ABI filter is a `ndk { abiFilters }` declared **inside the `sideload` build
type**, never at `android { }` top level. The `release` build type carries no filter, so
`bundleRelease` still packs all four ABIs — verified by rebuilding the bundle from scratch either
side of the change and getting the same 38 762 571 bytes and the same
`sha256 66bfee7c92a27e1c40c6fa758b6416a71ae34c4cea6b510e21e1451156adadab`. The reasoning and the
rejected alternatives (splits, a flavour dimension, per-variant filtering via the AGP Variant API,
zip surgery + re-signing) are in the ADRs in `build.gradle.kts`, next to the block they justify.

Use the universal `app-android-debug.apk` when you want an **emulator** — the sideload task
deliberately refuses `x86`/`x86_64` (`-Pkasoti.sideloadAbi=x86_64` fails at configure time naming
the two valid values), because a sideload APK is for a handset and the emulator ABIs are one
`assembleDebug` away anyway.

Independently of all that, the SDK-free harness still runs, with the pinned Kotlin 2.1.21 compiler
against the real `:core` classes:

```
$ sh app-android/tools/verify-offline.sh
==> compiling :ui (main)
==> compiling :app-android field layer + JcaCrypto
==> compiling tests
==> running tests
[       201 tests started         ]
[       201 tests successful      ]
[         0 tests failed          ]
```

⚠️ That is 201 as of 2026-10-03 (it read 154, then 189, in earlier revisions — the count moves as
suites are added). ⚠️ Its **tier-2 closing message is now stale**: it says `aapt2`, `d8`, `R8`,
Compose, dependency resolution "and the real SDK/TFLite signatures are all still unverified",
which `assembleDebug` has since disproved. The 201/201 result is real; that summary line is not.
It is not edited by the documentation pass because it lives in a tooling script.

That covers `app-android/.../field/**` (the whole cascade: router, MRZ extraction and the one
sanctioned OCR repair, date/format/drift maths, the quality gate, the macro stage, the quad
proposer, the trust lane, the demo catalogue, the I7 diary guard, the log scrubber, the clock and
skew) plus `ui/**` (the presentation state, the step machine, the verdict presenter, the
reducer). It is also, transitively, a check that **every `:core` API this module calls exists
with the signature used here** — a wrong import or a mistyped parameter is a compile error in
that harness, not a runtime surprise on a phone.

What it is **not**: a build of the app. `./app-android/tools/verify-offline.sh --list` prints
exactly which files are inside and outside the check.

### What a person with the SDK must run

```bash
# 0. prerequisites (BUILD.md §1): JDK 17 (Temurin), Android SDK platform 35 + build-tools 35.0.0.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"        # or: cp local.properties.example local.properties

# ⚠️ `sh gradlew`, not `./gradlew`, on a fresh clone — git records every tracked file as
#    mode 100644, so ./gradlew returns `permission denied` (docs/STATUS.md R-Q, R-J).

# 1. the repository must still build without Android (the settings gate, AGENTS.md §1)
sh gradlew :core:jvmTest
sh gradlew :eval:run --args="smoke"
sh scripts/check_no_network_in_core.sh

# 2. the Android module — ALL VERIFIED WORKING 2026-10-03
sh gradlew projects                              # 6 projects incl. :app-android
sh gradlew :app-android:assembleDebug            # 1 universal APK: app-android-debug.apk (87.98 MB)
sh gradlew :app-android:assembleRelease          # 1 universal APK: -unsigned.apk (77.55 MB)
sh gradlew :app-android:bundleRelease            # OK -> app-android-release.aab (36.97 MB). THE PRODUCT.
sh gradlew :app-android:sideloadApk              # 1-ABI sideload APK, arm64-v8a, 23.01 MB
                                                #   (LOCAL TESTING ONLY — see §1a)
sh gradlew :app-android:checkApkSize             # NFR-S1 gate: 4 containers; gates the worst
                                                #   per-device SLICE, not the file size
sh gradlew :app-android:lint
sh gradlew :app-android:testDebugUnitTest       # OK — 142 tests (also testReleaseUnitTest and
                                                #   testSideloadUnitTest: 426 executions total)

# 3. a device (SPEC §10 A2: one low-end 2 GB Android 10+, one mid) — NEVER DONE
sh gradlew :app-android:installDebug             # no adb, no device: this has never run
adb install -r app-android/build/outputs/apk/sideload/app-android-sideload.apk   # never run either
adb logcat -s KASOTI                             # the scrubbed log; see field/FieldLog.kt

# 4. the gates
sh scripts/airplane_install_test.sh              # exit 1 — NOT the bundle hashes: step A's container
                                                #   check flags every universal APK and the .aab by
                                                #   FILE size, which the ADR above says is not a
                                                #   download. --skip-bundle -> exit 2 at step D.
sh scripts/verify_bundle.sh                      # model hashes (I12) — correctly refuses
# ⛔ sh gradlew bundleOffline                    # was never a task in this tree
```

**The build-time unknowns below are now RESOLVED** — each one was a guess from memory and each one
turned out to compile:

| Was "likely to need fixing" | Outcome, measured 2026-10-03 |
|---|---|
| **`:core` had no `android { }` block** (expected `Namespace not specified`) | ✅ **Fixed.** `core/build.gradle.kts` and `platform/build.gradle.kts` both carry a real `android { }` block (`compileSdk = 35`, `minSdk = 26`) |
| `compileSdk` 34 too low for androidx | ✅ **Fixed.** Raised to **35** — `activity-compose:1.10.1` and `core-ktx:1.15.0` declare `minCompileSdk = 35`. An API migration; `minSdk 26` / `targetSdk 34` unchanged |
| ML Kit artifact coordinates (`text-recognition:16.0.1`, `barcode-scanning:17.3.0`) | ✅ **Resolve and package.** The *licence* question (bundled-model offline use under the Google ML Kit Terms) is **still UNVERIFIED**, and no OCR call has ever run |
| Compose BOM `2024.12.01` | ✅ **Resolves and compiles**, including the Material3 lambda-progress overload |
| CameraX `ResolutionSelector` / `ResolutionStrategy` API | ✅ **Compiles** against CameraX 1.4.1 |
| `Material3 LinearProgressIndicator(progress = { … })` | ✅ **Compiles** |
| R8 stripping a TFLite entry point | ⚠️ **Unverifiable without a device.** `proguard-rules.pro` is written defensively; a face model could still stop working in release only, and only a run would show it |
| `MainActivity`'s composable wiring | ✅ **Compiles.** Whether `CameraSurface` is *called* from `KasotiScreen` at runtime is still unexercised |

Still open, and not fixable by compiling:

| Open | Where | Why |
|---|---|---|
| **`MacroStage` classifier binding** | `capture/CameraController.kt` | The SVM weights loader is **not implemented**: `MacroStage` is constructed with `classifier = null`, so every patch classifies `UNKNOWN` and the macro layer abstains. Compiles; wrong at runtime by construction. See §6 |
| **`:app-android:test` does not compile** | `ml/BlazeFaceDesktopParityTest.kt`, `ml/BlazeFaceInputTest.kt` | They import `:platform`'s JVM classes from an Android test source set. `verify-offline.sh` runs both suites on a bare JVM instead |
| **No `.aab` had ever been built** | build config | **Resolved 2026-10-03.** `splits.abi` was the cause and it is gone; `bundleRelease` now succeeds and produces the 36.97 MB shipping bundle. Delivery is bundle-first, APK-second (the sideload APK in §1a is the local-testing path) |

### What is unit-tested (201 tests via `verify-offline.sh`)

| Area | Tests | The property each one pins |
|---|---|---|
| **Quality gate before match** | `EvidenceAssemblerGateTest` (7) | A failed gate refuses the match outright, so `R-FACE-01` is unreachable from a bad frame; a missing embedding is a named capture failure, never a fabricated score; a bad frame with an expired document keeps the proof as *carried over* and still says GREY. **This is the most important test in the module** (SPEC principle 4, FUSION.md §1). |
| Capture quality & frame metrics | `CaptureQualityTest` (11) | Blur/glare/brightness/pose causes; pose is only checked when landmarks exist; macro focus is `G_FOCUS` not `G_BLUR`; the `blurVariance` scale still lands inside the `Q_BLUR` policy band. |
| MRZ extraction | `EvidenceAssemblerGateTest` (7) | The `0↔O` repair fires **only** when the check digit proves it; a mutated check digit is *not* repaired away; `1↔I` is never touched; OCR noise between the two rows is survivable. |
| Maths layer | `MathLayerTest` (7) | Per-field attribution; an expected-but-absent MRZ is *unchecked*, not fine; a VIZ typo is drift (AMBER), not `R-MATH-01` (RED); a passport number is not run through the voter validator. |
| Track router | `TrackRouterTest` (7) | TD3 ⇒ passport by name; a bad Verhoeff digit does not route to Aadhaar; a shape hint alone never yields a confident route; nothing at all is `UNKNOWN` + `SYS_UNSUPPORTED_TRACK`. |
| Quad crop | `QuadTest` (9) | A centred bright card is found; a uniform frame and a too-small region are refused rather than invented; a bowtie is `NOT_CONVEX` not `TOO_SMALL`; ID-1 and passport aspects both accepted; dark-on-light works as well as light-on-dark. |
| Capture planner | `CapturePlannerTest` (6) | ≤4 steps for every track in the enum; an Aadhaar drops the MRZ step; a booklet with no MRZ still keeps the chip requirement. |
| Trust lane | `TrustLaneTest` (11) | Enrolment needs GREEN + PIN + consent; an AMBER cannot be enrolled; a **demo verdict can never be enrolled into a real store**; revocation is PIN-gated and instant; the re-check draw is recorded on the fast path too. |
| Invariant I7 | `DiaryGuardTest` (6) | A demo crossing is refused and never reaches the diary; a demo record arriving *in a bundle* is held back, not merged; export refuses a demo-sourced record. |
| Demo catalogue | `DemoCatalogueTest` (7) | **Every scenario produces the verdict DEMO.md scripts it produces**, at the shipped thresholds; every demo verdict carries the demo flag; the generated MRZ parses clean; one-tap reset clears the counters and turns demo mode off. |
| Macro stage | `MacroStageTest` (7) | No model ⇒ `UNKNOWN`, never a guess; a blurred patch is measured but gets no margin an `R-PROC` rule could read; the step cannot advance until both patches are focused; the `ProcessLabel` mapping is total. |
| QR layer | `QrLayerTest` (5) | An unsigned payload never reports a valid signature; an unsigned disagreement is an AMBER with both values shown; an unknown key is not a pass; a missing key file degrades to empty. |
| Log scrubber | `FieldLogTest` (3) | One greppable line per event; the detail is length-capped and long digit runs are stripped. |
| Clock & skew | `SkewReportTest` (4) | Beyond the bar is a *warning*, never a block; no reference point is `UNVERIFIED`, which is not the same as agreeing. |
| Calendar arithmetic | `CalendarArithmeticTest` (4) | The epoch-day inverse round-trips across the range, including leap days. |
| Quality bridge | `QualityBridgeTest` (6) | The `:core` ⇄ `:ui` round trip is lossless where it decides and lossy only where it must be. |
| `:ui` presentation | **58** tests | See `ui/README.md`: verdict tones, the GREY-is-not-an-accusation lexicon, the ≤4-step machine, the permission state machine, the reducer. (55 earlier on 2026-10-03, 44 on 2026-09-30.) Both `sh gradlew :ui:test` and `verify-offline.sh` run these — `:ui` is **not** SDK-gated, so it is in the build with or without an SDK |

### What is NOT tested and NOT implemented

- Every Android API call (CameraX, ML Kit, TFLite, Compose, Keystore).
- The SVM classifier. `MacroStage` is bound with `classifier = null`; there is no loader for
  `svm_print_v1.json` yet, so the macro layer abstains and every patch reads `UNKNOWN`.
- NFC (FR-N1 is P1, explicitly out of this build's scope).
- The face embedding is wired end to end but unexercised: no model file exists in the repo
  (`eval/models/` is gitignored, BUILD.md §3) and there is no parity test against the desktop
  alignment (`parityTest`, BUILD.md §4).
- Sync/export/import (FR-S1), the watchlist QR loop (FR-S2), the case-bundle writer, the
  audit-chain writer. `:core` has all of it; the app does not call it yet.
- The supervisor PIN keypad. `TrustLane.enrol` takes the PIN as a string so the *reducer* is
  testable, and the release wiring must bind a Keystore-backed `SupervisorPin`; until
  provisioning has run, every PIN is refused (`AppGraph.UnprovisionedPin`), which is the correct
  direction for a shortcut.
- The `ComposeFieldView` object in this module is a thin wrapper; the real binding is
  `KasotiScreen` + `FieldStrings` + `palette()` in `view/ComposeFieldView.kt`.

---

## 2. Why the Android actuals live here and not in `:platform`

DESIGN.md §1 says `:platform` is the *only* module allowed to hold `expect`/`actual`. That is
right, and it is not yet true: `:platform` has a `jvmMain` whose actuals are written against
`java.awt` / `ImageIO`, so an Android module **cannot resolve `:platform` at all** — there is no
Android variant for Gradle to pick.

This work does not own `:platform`, and adding an `androidMain` source set to it needs a
`namespace`/`compileSdk` in its build file plus an ownership handover. So the Android
implementations live in `dev.kasoti.android.platform.*` and each carries a note saying where it
belongs. Moving them is a package rename and a module dependency; no logic moves, and the logic
is what the unit tests cover.

| Here | Belongs in | Note |
|---|---|---|
| `platform/JcaCrypto.kt` | `:platform/androidMain` | The only file here with **no** `android.*` import, which is why the offline harness compiles it. `MessageDigest` is not an Android API. |
| `platform/AndroidImaging.kt` | `:platform/androidMain` | `Bitmap` ⇄ `GrayImage`, resize, quad rectification, rotation. |
| `platform/MlKitEngines.kt` | `:platform/androidMain` | Bundled ML Kit text + barcode, the QR envelope reader, the VIZ field reader. |
| `platform/TfliteFace.kt` | `:platform/androidMain` | Hash-pinned loading (I12) and the two face models. |
| `capture/CameraController.kt` | `:app-android` (stays) | CameraX policy — the macro focus lock and the live quality frame are app behaviour, not platform I/O. |
| `field/Resize.kt` | `:core` (or `:platform`) | Bilinear grayscale resize; `:core` has no imaging type, `:platform` has no Android variant. |
| `field/CalendarArithmetic.kt` | `:core` | The inverse of `IsoDate.toEpochDay()`. `:core` is the natural home; this is a workaround for not owning it. |

---

## 3. The `ui` decision, stated explicitly

The task asked which of two shapes to take for `ui`. **This repo took the lower-risk one:
`ui` is a plain `kotlin-jvm` module of pure-Kotlin presentation state holders plus a documented
view interface. It has no Compose dependency, no Android dependency and no platform dependency
at all.**

The reasoning, in full, is in `ui/build.gradle.kts`. In short:

1. A Compose Multiplatform module in `:ui` could not be compiled here, so it would be entirely
   unverified — and the Compose Multiplatform *plugin* resolves at configuration time for the
   whole build, so an unresolvable Compose artefact would break `sh gradlew :core:jvmTest` for
   anyone with no interest in UI work. (The companion claim that `:ui` needs an SDK is **false**
   and is corrected in item 2 below.)
2. The logic is what needs testing, and the logic is what `ui/src/test` tests: **55** tests that
   run on a bare JVM with no graphics stack, covering which step is next, whether a failed quality
   gate blocks, what a finding is called in Hindi, and whether GREY renders as an accusation.
   ⚠️ Point 1 above said `:ui` is "a module nobody can run without an SDK". That was **wrong when
   written and is wrong now**: `settings.gradle.kts` does **not** gate `:ui` on the SDK, it is a
   plain `kotlin-jvm` module, and `sh gradlew :ui:test` runs with or without an SDK. The real
   reason for not making it Compose was the configuration-time plugin resolution, which stands.
3. The cost is real and worth naming: the Compose binding is a *separate* piece of work.
   `:app-android` ships one; a desktop agent writes a second. Both are ~200 lines of leaf code
   that cannot contain a verdict decision, because the decision lives in `VerdictPresenter`.

The migration is one mechanical PR: `kotlin.multiplatform` + `androidTarget()` + `jvm()`, add the
two plugin aliases, wrap the renders in `@Composable`, delete the two `FieldView`
implementations. **No file under `dev.kasoti.ui.state` changes.**

---

## 4. Architecture, in one picture

```
  MainActivity ── onEvent(UiEvent) ──▶ FlowController.reduce ──▶ AppState ──▶ KasotiScreen
       │                                    (dev.kasoti.ui, 44 unit tests)          │
       │                                                                             ▼
       └── camera.takeStill ──▶ AndroidImaging ──▶ field/{CaptureQuality, MrzExtractor,   │
                                        │            TrackRouter, MathLayer, MacroStage,
                                        ▼            AutoQuadDetector, Quad}
                                 MlKitOcrEngine
                                 TfliteFaceModels
                                        │
                                        ▼
                            EvidenceAssembler ── gate() ──▶ gatedMatch() ──▶ FusionEngine.decide
                                        │              (a failed gate CANNOT produce a match)
                                        ▼
                            VerdictPresenter ──▶ VerdictScreen ──▶ palette(tone) + VoiceReadout
```

The arrow that matters is the one through `gatedMatch()`: a face match is not a `FloatArray`
parameter, it is a `GatedMatch` that cannot be constructed from a failed quality gate. See
`field/EvidenceAssembler.kt` and SPEC principle 4.

---

## 5. The demo runbook (DEMO.md §3)

`DemoCatalogue` has one fixture per beat, and `DemoCatalogueTest` asserts that **each produces
the verdict DEMO.md says it produces** at the shipped thresholds — that is the rehearsal check,
and it is a unit test rather than a checklist item.

| DEMO.md moment | Scenario | Mechanism |
|---|---|---|
| 0:00–0:20 genuine specimen | `genuine-passport` | every load-bearing passport layer supplied ⇒ GREEN |
| 0:20–1:00 genuine vs inkjet | `genuine-offset-macro`, `inkjet-printout` | both zones agree; the *label* is the story |
| 1:00–1:40 forged inkjet twin | `forged-inkjet-twin` | `R-PROC_02`: zones disagree, both margins above the RED floor ⇒ RED |
| 1:40–2:20 the alias | `alias-ramesh-suresh` | `R-ALIAS_01`: hit over `T_ALIAS_HI`, different name, gap corroborated |
| optional watchlist | `watchlist-hit` | `A_WL_01` ⇒ AMBER |
| §5 "the printout breaks the router" | `router-unsupported` | `SYS_UNSUPPORTED_TRACK` ⇒ AMBER. Honest software. |
| §5 the GREY recovery | `blurry-retake` | quality gate fails on a frame that already showed an expiry ⇒ GREY + carried proof |

Demo mode: seeded (not random — a red case that lands on green because of an unlucky draw is a
demo that needs a second take), one-tap reset that clears the counters *and* turns demo mode off,
a diagonal `DEMO` watermark drawn over everything, and `DiaryGuard` refusing every demo write.

**The fixtures are evidence, not recordings.** Each builds an `Evidence` and lets the real
`FusionEngine` decide. There are no stored verdicts and no binary fixtures: the MRZ is generated
at run time by `dev.kasoti.mrz.MrzBuilder` from a name and a date, so a change to the century
rule changes the demo with it instead of leaving a stale string on stage. And no volunteer's
document image is committed to the repo (DATA.md §1).

---

## 6. Known gaps, recorded where a judge would trip over them

`DemoCatalogue.KNOWN_GAPS` holds the same list in code. Two are worth repeating here:

**The Aadhaar "PVC honesty rule" is not implemented in `:core`.** SPEC FR-F1 and FUSION.md §7
both say an Aadhaar's macro layer carries a PVC honesty rule — a PVC card should read as the
substrate it is, and an inkjet reading on one is evidence of a copy. `:core`'s
`AmberRules.process` has no such rule: it fires `A_PROC_01` on a zone *mismatch*, a *SCREEN*
hint or a *worn* pair, and otherwise confirms `MACRO_OK`. **Two confident, agreeing INKJET zones
on a genuine Aadhaar therefore clear.** The app could paper over that with a hard-coded "PVC
cards are never inkjet", and deliberately does not: no `:core` test, no eval fixture and no
FUSION row backs such a rule, and inventing one at the app layer is exactly the hand-typed claim
the eval protocol exists to prevent. The fix belongs in `:core` as an expected-substrate table
with an eval fixture.

**The classifier is not trained.** With no `svm_print_v1.json` bound, every patch classifies
`UNKNOWN` with a zero margin, `DocAggregator` abstains, and the macro layer contributes nothing.
The app prints `UNKNOWN` rather than `OFFSET`, because a demo that printed a process label from
an untrained model would be showing a classifier that does not exist — and DEMO.md §5's
recovery line for exactly that is "honest software says so".

---

## 7. No network (invariant I4)

Structural, not a promise:

- **No `INTERNET` permission in the manifest.** The OS enforces it; a transitive dependency that
  tries to open a socket fails.
- `android:usesCleartextTraffic="false"` plus `res/xml/network_security_config.xml`, which trusts
  only system CAs and permits no cleartext anywhere.
- No HTTP client, no `URL`, no `Socket` anywhere in this module.
- The app-visible claim is on the idle screen: *"KASOTI does not use the network."*
- **Extend the CI gate to this module.** `scripts/check_no_network_in_core.sh` covers `:core`
  only and is not this module's file to edit. Whoever owns `.github/` should add a line running
  the same grep over `app-android/src/main`. The banned set: `java.net`, `javax.net`,
  `okhttp`, `ktor-client`, `okio`, `HttpURLConnection`, `URLConnection`, `Socket`,
  `android.net.ConnectivityManager` (the *manager* is banned; `NetworkCallback` for a local
  hotspot is not needed by anything here).

---

## 8. Size budget (NFR-S1, per-device download ≤ 35 MB)

`:app-android:checkApkSize` is the module-level hook. It depends on `bundleRelease`,
`assembleDebug`, `assembleRelease` **and `assembleSideload`**, measures every container it finds
under `build/outputs`, and **fails** above 35 MB. It measures the artefact; nothing in the module
estimates a size, because an estimate that can be wrong in the optimistic direction is worse than
no gate.

⚠️ **It gates the per-device slice, not the file.** The budget is applied to the *compressed
worst-case per-device slice* — the bytes one phone actually downloads — and the container's own
file size is printed but **not** gated. That distinction is not pedantry: an `.aab` is never
installed as a file (Play slices it server-side), and an APK stores the per-ABI `.so` files
**STORED** while the `.aab` DEFLATEs the same bytes, so the universal APK is ~2.5x its own
download. Measured 2026-10-03:

| container | file size | worst per-device slice | gated? |
|---|---|---|---|
| `app-android-release.aab` (**the product**) | 36.97 MB | **14.60 MB** (x86) | YES |
| `app-android-release-unsigned.apk` | 77.55 MB | 25.52 MB (x86) | YES |
| `app-android-sideload.apk` (arm64-v8a) | 23.01 MB | 22.91 MB (arm64-v8a) | YES |
| `app-android-debug.apk` | 87.98 MB | 35.90 MB (x86) | ADVISORY |

The debug row is the one carve-out, and it is a carve-out with a stated cause and a number rather
than a deleted check: `isMinifyEnabled = false` for debug means its shared payload is 14.59 MB
against release's 4.21 MB while the native payload is identical, so it lands 0.90 MB over on a
file nobody ships. It is reported loudly on every run and counted in the summary. Full reasoning
in the `checkApkSize` KDoc in `build.gradle.kts`.

The sideload APK is **gated, not exempted** — and it can be, because `initWith(release)` gives it
the shipping dex and resources (22.91 MB) and because a single-ABI APK is the one container whose
file size and per-device slice nearly coincide. Had it been built debug-shaped its slice would be
33.29 MB, inside 1.71 MB of the budget on the same native payload release ships, and gating that
would have produced a red build meaning nothing. That trade is written down in the KDoc rather than
decided silently.

`.github/workflows/ci.yml` calls this task when a runner has an SDK. ⚠️ Because `assembleSideload`
is now one of its dependencies, that step builds one extra R8-minified APK; the CI file is owned
elsewhere and was not touched here.

The arithmetic that matters, from DESIGN.md §6: ML Kit text ~10 MB, ML Kit barcode ~3 MB,
TFLite runtime ~2 MB, Compose ~2 MB, CameraX ~2 MB, the two model files ≤ 5.3 MB. That is ~24 MB
before R8, which is why `release` enables `minifyEnabled` and `shrinkResources`. **If the gate
fails, the documented fallback is Tesseract-Android** (D5/D6) — ML Kit is the single largest
item and the one to cut first.

---

## 9. Threshold registry (FR-R3, AGENTS.md §2)

`AppGraph.registry` is `ThresholdRegistry.defaults()`. There is **no numeric literal that decides
anything** in the field layer: every pass/fail boundary is read from the registry by name, so
re-tuning an operating point is a versioned file change with a review trail.

Two deliberate exceptions, both documented at their definition and neither of which *decides*:

- `field/CaptureQuality.kt`'s `FrameMetrics.VARIANCE_SCALE` and `GLARE_LEVEL` are *display/calibration*
  constants that put the raw measurements into the units `Q_BLUR` is expressed in. Getting them
  wrong would move a gate, so `CaptureQualityTest` asserts the scale still lands inside the
  `Q_BLUR` policy band.
- `ui/CaptureStep.kt`'s display references, asserted against `ThresholdRegistry.defaults()` by
  `QualityMeterRegistryAgreementTest`'s sibling case in `CaptureStepTest`.

---

## 10. Licence and provenance (BUILD.md §6)

| Component | Licence | Status |
|---|---|---|
| AndroidX CameraX / Compose / Activity | Apache-2.0 | fine |
| ML Kit (bundled) | Google ML Kit Terms | **verify before public distribution** — offline bundled use is the licensed mode |
| TensorFlow Lite | Apache-2.0 | fine |
| `emb_v1.tblite` weights | **unresolved** | DESIGN.md §6 leaves `owner: ___`. Not in the repo. Do not ship. |
| `blazeface_short.tflite` | **verify the port** (MediaPipe is Apache-2.0) | not in the repo |
| `uidai_qr_keys.json` | **provenance note required** | A5; not in the repo |

`THIRD_PARTY.md` is the file these belong in and is not this module's to edit.
