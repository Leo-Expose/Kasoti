# KASOTI — Build & Run (BUILD.md)

## 1. Prereqs
- JDK 17 (Temurin). Gradle needs it: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` (or your
  Temurin 17) before anything in §3 will run — **including the `scripts/*.sh`, which shell out to
  Gradle**: without it `sh scripts/pii_scrubber_test.sh` fails with a bare `What went wrong: 27`
  (measured 2026-10-03), which reads like a privacy failure and is not one. Android SDK
  (**`compileSdk 35`**, build-tools **35.0.0**; `minSdk 26` / `targetSdk 34`), CMake/NDK only if a
  dep needs it (goal: none) — **and note the SDK is still optional at configure time**, see §2.
- Tesseract ≥5.3 + `eng` + `osd` traineddata on desktop dev machines (`apt/brew/choco install tesseract`); tessdata path in `local.properties` (`tess.data=`).
- One low-end Android test device (2–3 GB RAM, Android 10+) + one mid device. USB debugging on.
  ⚠️ **CHANGED 2026-10-03: an Android SDK IS now present** (`local.properties` `sdk.dir=`,
  `ANDROID_HOME=/opt/android-sdk`) and `:app-android` compiles and packages. **Neither *device*
  has been obtained**, and no `adb` is on `PATH` — so nothing has been installed or run on
  hardware. See `docs/STATUS.md` R-C.
- Disk: ~15 GB (SDK + Gradle). Network needed ONLY for first dependency fetch; after that,
  airplane-build must succeed. The `ci.yml` `offline-proof` job attempts this weekly with
  `--offline`, and it **has never run** — and it is warm-cache only (STATUS R-K). For the record,
  the other CI job (`verify`) **has** run, twice, and failed both times at the first step on
  `./gradlew: Permission denied`; see §3a and STATUS R-J.

Tested-matrix (append your row):
| OS | JDK | outcome | date | commit |
|---|---|---|---|---|
| Ubuntu 24.04, Temurin 17.0.20.1, **no Android SDK** | 17.0.20.1 | **691 Gradle tests, 0 failures** · `:core:jvmTest` 302/0 · `:platform:jvmTest` 137/0 · `:app-desktop:test` 169/0 · `:eval:test` 39/0 · `:ui:test` 44/0 · `:app-desktop:installDist` ok + packaged launcher runs · `:app-desktop:run` ok · `:eval:run smoke` 11 pass/1 skip/0 fail (exit 4) · `ktlintCheck` 3 840 findings · `detekt` 1 309 — **superseded by the row below** | 2026-09-30 | `9a04f40` |
| Ubuntu, fresh `git clone` of the same commit, no Android SDK | 17.0.20.1 | **`sh gradlew` → `BUILD SUCCESSFUL`, 691 tests, 0 failures, 1 skipped** (self-skip: detector `.tflite` is git-ignored; `sh scripts/fetch_models.sh` then runs it). **`./gradlew` → `permission denied`** (§3a) — **test counts superseded by the row below** | 2026-09-30 | `9a04f40` |
| **Ubuntu 24.04, Temurin 17.0.20.1, Android SDK present** (`local.properties` `sdk.dir=`, `ANDROID_HOME=/opt/android-sdk`) | 17.0.20.1 | **896 Gradle tests, 0 failures, 0 skipped** · `:core:jvmTest` **459**/0 · `:platform:jvmTest` 137/0 · `:app-desktop:test` **206**/0 · `:eval:test` 39/0 · `:ui:test` **55**/0 — `sh gradlew … --rerun-tasks`, 20 tasks executed · `:app-desktop:installDist` ok, packaged launcher runs, model reported *"(shipped inside this installation)"* · **`:app-android:assembleDebug` ok — 2 APKs, arm64-v8a 33.39 MB / armeabi-v7a 26.56 MB** · **`:app-android:assembleRelease` ok — 22.97 MB / 16.14 MB** · **`:app-android:checkApkSize` ok — 4 artefacts, all within the 35.0 MB budget** · **`app-android/tools/verify-offline.sh` exit 0, 201/201** · `scripts/check_no_network_in_core.sh` green over **93** files · **`scripts/pii_scrubber_test.sh` exit 0, 7 suites / 111 tests** · `scripts/check_no_magic_thresholds.sh` red, **175** lines · `ktlintCheck --continue` **4 267** · `detekt` **1 349** · **`:app-android:bundleRelease` FAILS (no `.aab`)** · **`:app-android:test` FAILS, 160 compile errors** · `scripts/airplane_install_test.sh` exit 1 (bundle-manifest placeholders) | **2026-10-03** | **`9a04f40` + dirty working tree** |
| — | — | `:app-android` on a **device** | never run — no `adb` on PATH, no handset. Compilation is not execution | — |

## 2. Scaffold (done)
Kotlin Multiplatform module map, enforced in `settings.gradle.kts` (README's layout is the
target shape). Gradle version catalog pins ALL versions. Dependency verification
(`gradle/verification-metadata.xml`, `verify-metadata` on) is **still outstanding** —
the file does not exist (STATUS R-H).

**Module inclusion is conditional, and this matters more than it looks.**
`settings.gradle.kts:44-65` always includes `:core`, `:platform`, `:eval`, `:app-desktop` and
`:ui`, and includes `:app-android` **only** when an Android SDK is discoverable
(`local.properties` → `sdk.dir=`, or `ANDROID_HOME`/`ANDROID_SDK_ROOT`). On a machine with no
SDK `:app-android` is not in the build at all, so `:core:jvmTest` and `:eval:run` stay runnable.
⚠️ **The gate is still real, but as of 2026-10-03 an SDK *is* present on the dev box**
(`local.properties` `sdk.dir=`, `ANDROID_HOME`/`ANDROID_SDK_ROOT` = `/opt/android-sdk`), so
`sh gradlew projects` lists **all six** projects and `:app-android` compiles and packages. The gate
still does what it is for — it is what lets CI runners and desktop-only machines skip the module
without an "SDK location not found" failure. **`:ui` is not gated** — it is plain `kotlin-jvm` and
always builds (`settings.gradle.kts:48-50`); `sh gradlew :ui:test` works with no SDK.

> Earlier revisions of this file said `settings.gradle.kts` includes
> `:core,:platform,:ui,:app-android,:app-desktop,:eval` unconditionally, and `.github/workflows/ci.yml`
> and `ui/README.md` both repeat the false version of the `:ui` half. The `:ui` half is now
> corrected here. The two files outside `docs/` are reported, not edited.

## 3. Commands
Re-measured **2026-10-03** in the source checkout (branch `main`, HEAD `9a04f40` + a heavily
dirty working tree) **with an Android SDK present**; rows not re-run are labelled.
`docs/STATUS.md` §1 has the full results with provenance, §0 has the reviewer-facing table.
⚠️ Use `--rerun-tasks`, not `--rerun` — see the note below the block.

### 3a. ⚠️ Read this first: on a fresh clone, type `sh gradlew`, not `./gradlew`

Git records **all 340 tracked files as mode `100644`** — including `gradlew` and every
`scripts/*.sh` — because this repository is authored on an **exFAT** volume that cannot hold the
exec bit (R-Q). Your working tree looks fine; a fresh clone does not:

```
$ git ls-files -s | awk '{print $1}' | sort | uniq -c
      340 100644          # not one 100755
$ ls -l gradlew           # in a fresh clone
-rw-r--r-- 1 runner runner 8733 gradlew
$ ./gradlew --version
bash: ./gradlew: Permission denied
```

So on a clean checkout, use `sh gradlew …` and `sh scripts/<name>.sh …`, or run
`git update-index --chmod=+x gradlew scripts/*.sh app-android/tools/*.sh` once first. This is
also what broke CI: both recorded runs died with `./gradlew: Permission denied` (exit 126) at the
very first step (`docs/STATUS.md` R-J). Everything below is written as `sh gradlew` for that
reason; substitute `./gradlew` freely once the exec bit is committed.

```bash
# core + eval (no device, no SDK needed) — ALL VERIFIED WORKING
sh gradlew :core:jvmTest                        # 459 tests, 0 failures
sh gradlew :core:allTests                       # = :core:jvmTest; JVM is the only target
sh gradlew :ui:test                             # 55 tests  (not SDK-gated)
sh gradlew :platform:jvmTest                    # 137 tests
sh gradlew :app-desktop:test                    # 206 tests
sh gradlew :eval:test                           # 39 tests
# all five together: 896 tests, 0 failures, 0 skipped. Counts measured 2026-10-03 with
#   sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test --rerun-tasks
#   and read from <module>/build/test-results/**/TEST-*.xml. 20 tasks, all executed.
sh gradlew :app-desktop:run                     # headless text console; prints usage, exit 0
sh gradlew :eval:run --args="smoke"             # 11 pass / 1 skip. EXITS 4, not 0
sh gradlew :eval:run --args="full"              # 13 pass / 6 skip. EXITS 4, not 0
sh gradlew :eval:run --args="parity"            # 1 gate, skipped, no device. EXITS 4, not 0
# ⚠️ the three :eval:run rows above were last run 2026-09-29/30 and were NOT re-run on 2026-10-03.
#    Cite the run id you actually produced; see docs/STATUS.md §3 and §4.7.

# lint — BOTH TASKS EXIST AND BOTH ARE RED. Advisory, detached from `check`. See STATUS §4.3.
sh gradlew ktlintCheck --continue               # exit 1 — 4 267 violations (WITHOUT
                                                #   --continue it under-reports: 3 130)
sh gradlew detekt                               # exit 1 — 1 349 weighted issues

# desktop distribution — WORKS. The old task name below was wrong, not the capability.
sh gradlew :app-desktop:installDist             # -> app-desktop/build/install/kasoti/
app-desktop/build/install/kasoti/bin/kasoti help # the packaged launcher runs
sh gradlew :app-desktop:distZip                 # also exists
sh gradlew :app-desktop:distTar                 # also exists

# android — REQUIRE an SDK in local.properties or ANDROID_HOME. As of 2026-10-03 an SDK IS
# present on the dev box, `:app-android` IS in the build, and these all work.
sh gradlew projects                             # lists all SIX projects incl. :app-android
sh gradlew :app-android:assembleDebug           # 2 APKs: arm64-v8a 33.39 MB, armeabi-v7a 26.56 MB
sh gradlew :app-android:assembleRelease         # 2 APKs: arm64-v8a 22.97 MB, armeabi-v7a 16.14 MB
sh gradlew :app-android:checkApkSize            # the 35 MB gate; 4 artefacts, all within budget
sh gradlew :app-android:installDebug            # needs a DEVICE; none has ever been attached
# ⛔ sh gradlew :app-android:bundleRelease      # FAILS on AGP 8.9.2 with ABI splits on:
#                                               #   :app-android:buildReleasePreBundle
#                                               #   > Sequence contains more than one matching element.
#                                               #   => NO .aab, NO Play Store bundle. APK-only.
# ⛔ sh gradlew :app-android:test               # FAILS: 160 compile errors, all in
#                                               #   ml/BlazeFaceDesktopParityTest.kt (150) and
#                                               #   ml/BlazeFaceInputTest.kt (10), which import
#                                               #   :platform's JVM classes. Pre-existing since
#                                               #   06bd654. verify-offline.sh covers those suites.

# offline proof
sh app-android/tools/verify-offline.sh          # exit 0 — 201/201, no SDK needed
sh scripts/airplane_install_test.sh             # exit 1: finds the APKs, then refuses on the 2
                                                #   unpopulated hashes in bundle_manifest.sample.txt
sh scripts/airplane_install_test.sh --skip-bundle  # exit 2 (INCOMPLETE) — reaches step D, no adb
```

⚠️ **`--rerun` is not `--rerun-tasks`.** `--rerun` is a *per-task* option: a trailing `--rerun`
re-runs only the **last** task on the line and leaves the rest `UP-TO-DATE`, so it silently
reports a stale result for four of the five modules. Measured 2026-10-03: `--rerun` re-ran
`:eval:test` only (the other four printed `UP-TO-DATE`); `--rerun-tasks` executed all 20 tasks.

⚠️ **`ktlintCheck` needs `--continue`.** Without it Gradle stops at the first failing task and
prints 3 130 of 4 267 violations. With `--continue`, all 35 ktlint Check tasks run and 12 fail.

⚠️ **Installing the right APK matters now that there is no universal one.** ABI splits ship
`arm64-v8a` (any phone from ~2017) and `armeabi-v7a` (low-end 32-bit ARM) only; `x86`/`x86_64`
are **not** shipped, so there is no emulator build. Installing the wrong ABI gives
`INSTALL_FAILED_NO_MATCHING_ABIS`, and there is no `app-android-debug.apk` by any name.

**Exit code 4 is not a broken build.** `:eval:run` returns 4 = INCOMPLETE whenever a required
suite is skipped, and the device gates (`L-GATE-02`, `P-GATE-01`, `F-GATE-01`, `F-GATE-03`,
`MACRO-CAL`) are *always* skipped without a device and a calibration file. A smoke run that
returned 0 would mean a device was attached, which it is not.

**About the two commands this file used to advertise.** The previous revision said
`:app-desktop:packageDistributionForCurrentOS` was "never implemented" and that therefore
"desktop packaging is unbuilt". **The task name was wrong; the capability was not.** That task
has never existed, but `:app-desktop` applies the standard `application` plugin, so
`installDist`, `distZip` and `distTar` do exist and work — verified 2026-09-30, and the packaged
launcher executes. Two things remain true and are stated rather than hidden:

- `bundleOffline` is still **not** a task in this tree. `.github/workflows/ci.yml:645-648`
  probes for it with `./gradlew -q help --task bundleOffline` and prints "`bundleOffline` is not a
  Gradle task in this tree yet." when it is missing, which is the honest form. ⚠️ **do not confuse
  this with `:app-android:bundleRelease`**, which *is* a real task and is **broken** (AGP 8.9.2 ×
  ABI splits). Two different facts, previously conflated in this file: a task that does not exist,
  and a task that fails.
- A distribution used to **lose the macro model** — the lookup used to be repo-relative, so a
  packaged console silently abstained on the print-process layer and could return a different
  verdict from a checkout console on the same document. The model now ships inside the
  distribution and the launcher prints which file it resolved and where from. `docs/STATUS.md`
  §4.5 has the before/after table and the verification.

The `ROADMAP.md` M2-d3 tripwire fallback (`run` from a checkout) therefore **did not have to be
taken**; packaging built.

Model provisioning: `scripts/models_manifest.tsv` holds hash-pinned URLs;
`scripts/fetch_models.sh <manifest>` verifies SHA-256 before installing, and `--record`
prints a digest without installing. The committed inventory is `eval/models/manifest.json`.
**Model binaries are git-ignored** (`.gitignore:26,44`). Apps load from bundled assets ONLY.

## 4. TFLite notes

> ⚠️ **Corrected 2026-09-30.** This section used to say the desktop runs "TFLite Java API
> (`tensorflow-lite` + `tensorflow-lite-support` + natives for win/linux/mac)". **That is false**,
> and it was false for every OS, not just the two that turn out to be missing. The short version:
> **there is no first-party TFLite JVM artifact at all**, and the substitute that does exist ships
> desktop natives for **two of four** desktop targets. The full probe, the raw evidence and the
> rejected alternative are in `docs/spikes/01-face-model.md` §4; this section is the operational
> summary and every number in it was re-probed on 2026-09-30.

### 4a. Why the old claim was wrong — the four checks

| Check | Result (probed 2026-09-30) |
|---|---|
| `org.tensorflow:tensorflow-lite:2.17.0` on Maven Central | **A 1,411-byte jar that is a copy of its own POM.** `file` reports it as ASCII text; the bytes are `<distributionManagement><relocation>` → `com.google.ai.edge.litert:litert:1.0.1`. Zero classes. `sha256 bfb9a2e2…`, 1,411 B. |
| `com.google.ai.edge.litert:litert:1.0.1` | `<packaging>aar</packaging>`. Android. |
| Earlier versions | `2.16.1`, `2.15.0`, `2.14.0`, `2.13.0` are all `<packaging>aar</packaging>`; **no `.jar` is published for any of them** (HTTP 404). There is no desktop jar to fall back to. |
| A per-OS classifier | **None exists.** `linux-x64`, `windows-x64`, `macos-x64`, `macos-arm64`, `linux-x86_64`, `osx-x86_64`, `linux-aarch64` — all HTTP 404 for `2.17.0`. The `<os>` classifier this section used to tell you to "check" is not a thing. |
| The AAR's own natives on a desktop JVM | **Impossible: they link Bionic, not glibc.** `readelf -d` on `jni/{arm64-v8a,armeabi-v7a,x86_64,x86}/libtensorflowlite_jni.so` inside the real AAR (`litert-1.0.1.aar`, 6,487,444 B) shows `NEEDED libc.so, liblog.so, libdl.so, libm.so` and **no `libc.so.6` and no `ld-linux-x86-64.so.2`**. Those unversioned sonames are Bionic's; a desktop JVM resolves the versioned glibc ones. `file` reports each as "for Android 21, built by NDK r25b". |

### 4b. What the build actually binds, and where it runs

`:platform` binds **`ai.djl.tflite`** (Apache-2.0), which republishes the genuine
`org.tensorflow.lite.*` Java API as a plain jar and publishes desktop JNI separately.

- `ai.djl.tflite:tflite-engine:0.27.0` — 28 classes including `org/tensorflow/lite/{Interpreter,
  Tensor, TensorFlowLite, DataType}.class`, the same package and class names as the Android AAR.
  `javap -c` on the `org/tensorflow/lite/*` classes shows **zero** `ai/djl` references, so the
  dependency flows one way and the TFLite side is self-contained (`isTransitive = false`).
- `ai.djl.tflite:tflite-native-cpu:2.6.2` — desktop JNI, per-classifier:

| Target | Native | Size | On-device inference |
|---|---|---|---|
| `linux-x86_64` | `native/lib/libtensorflowlite_jni.so` | 3,382,784 B | **works** — NEEDs `libstdc++.so.6`, `libc.so.6`, `ld-linux-x86-64.so.2` (glibc) |
| `macos-x86_64` (Intel Mac) | `native/lib/libtensorflowlite_jni.dylib` | 7,073,184 B | **works** — Mach-O **x86_64** |
| **`windows-x86_64`** | ❌ HTTP 404 | — | **review-only, no on-device inference** |
| **`macos-aarch64` (Apple Silicon)** | ❌ HTTP 404 | — | **review-only, no on-device inference** |
| `linux-aarch64`, `osx-arm64` | ❌ HTTP 404 | — | review-only, no on-device inference |

**No version bump fixes this.** `tflite-native-cpu` has published exactly two versions, `2.4.1`
and `2.6.2`, and each publishes exactly `linux-x86_64` and `osx-x86_64` and nothing else. The two
gaps are upstream, permanent on this route, and a build decision rather than a research one.
`platform/build.gradle.kts` reads the classifier off the host OS and skips the dependency entirely
on an unlisted platform, so a Windows or Apple-Silicon checkout still **configures, compiles and
tests** — it just reports `UNAVAILABLE` rather than failing to resolve.

**"Review-only" has to mean something.** `TfliteRuntime.availability()` distinguishes "no published
native for this OS" (`platformSupported = false`) from "the native is here and failed to load", and
`DesktopFaceLayer` reports `NO_RUNTIME_FOR_PLATFORM` / `err.face.no_runtime_for_platform`. A GREEN
1:1 face verdict is unreachable on those machines. It is **not** a build failure and **not** a
silent "no face detected" — the two must never look the same to a `FusionEngine` deciding GREEN.

The only route that would close the two gaps is **ONNX Runtime JVM**, whose main jar is 139,129,141 B at 1.22.0 (measured) and which
has first-class win/linux/osx natives. It needs the model in **ONNX**, not TFLite — a different file per platform,
which breaks D1's "same bytes, one hash to pin" (DESIGN §9) and needs its own spike. Not taken;
recorded in RISKS.md R-T and `docs/STATUS.md` R-T rather than hidden.

### 4c. Android

`org.tensorflow:tensorflow-lite` + the XNNPACK delegate, 4 threads by default
(`TfliteFaceDetector.DEFAULT_THREADS`, matching the desktop).

- **XNNPACK is the only delegate enabled, and "no GPU/NNAPI delegate" is now *enforced* rather than
  assumed.** It was previously written as `apply { useXNNPACK = true }`, and TFLite's `Options`
  exposes `setUseXNNPACK(boolean)` with no getter, so Kotlin synthesised no property: the
  left-hand side resolved to the enclosing function's own `numThreads` parameter and both lines were
  no-op self-assignments. DESIGN §1's "nothing enabled that could change outputs" was therefore not
  enforced anywhere, and the thread count was silently TFLite's default. Fixed to explicit
  `setNumThreads(numThreads)` / `setUseXNNPACK(true)`.
- **FP16 is not enabled and is not planned.** The weights are float16 *on disk*; the tensors are
  unquantised float32. Those are different things and the old wording conflated them.
- **The `parityTest` this section used to name does not exist.** What exists is
  `BlazeFaceDesktopParityTest` in `app-android`, run by `app-android/tools/verify-offline.sh` on a
  bare JVM: it loads `:platform`'s compiled classes and requires the Android decode to be
  **bit-identical** to the desktop one on the real detector output committed in
  `eval/fixtures/face/raw_outputs/`. That is a check of two *decode implementations*, not of two
  devices. ⚠️ **CHANGED 2026-10-03:** there **is** now an Android SDK, so `TfliteFaceDetector`
  and its `TfliteFace.kt` binding compile through AGP against the **real** LiteRT AAR rather than
  only against hand-written stubs. There is still **no device**, so device parity is unverified
  and unclaimed. EVAL.md §4's ±1e-3 similarity tolerance still has no device to apply it to.

### 4d. Model files

`blazeface_short.tflite` (Apache-2.0 verified on the model's own model card, 229,746 B, SHA-256
`b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f`, a digest **we measured** because
Google publishes no `.sha256` sidecar) and `emb_v1.tflite` — **the latter does not exist and could
not be obtained**: seven candidates, every one deleted, non-commercial-research-only or
unpublished, and none was fabricated. The embedding slot is empty **by decision**; see
`docs/spikes/01-face-model.md` §7 and `docs/STATUS.md` §2.1.

The loader refuses a mismatched hash and is fail-closed: a mismatch is an exception, never a warning
and never a fallback to a default model (message key `err.model_hash`, invariant I12, RT-E8).
`eval/models/svm_print_v1_synthetic.json` is a **SYNTHETIC** classifier, not D-MACRO, and is never
gate-eligible.

## 5. Tesseract notes (desktop P0)
`Tess4J` + config: `--psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<` for MRZ line crops; VIZ lines default `eng`. Deskew via quad-crop (no OpenCV). Manual MRZ entry ALWAYS available beside OCR result (console-appropriate fallback; officer-confirmed flag logged).

## 6. Licenses (owner: maintainer; verify before bundling)
BlazeFace short-range detector (**Apache-2.0 — VERIFIED on the model's own model card**;
obtained, hash-pinned) · embed-model slot (**EMPTY — 7 candidates rejected on licence
grounds, `docs/spikes/01-face-model.md` §2.1; do not assume one exists) · DJL TFLite runtime
(Apache-2.0) · TFLite (Apache) · ML Kit bundled (Google ToS — bundled-model offline use;
**compiled since 2026-10-03, never *run***) · Tesseract + Tess4J (Apache) · UIDAI QR keys (**UNVERIFIED, not
obtained**). Full register with sources, digests and provenance states: `THIRD_PARTY.md`.

## 7. Troubleshooting (grow this table, don't DM fixes)
| Symptom | Cause → fix |
|---|---|
| TFLite-JVM `UnsatisfiedLinkError` on `nativeRuntimeVersion()` | Two distinct causes, and they look identical. (a) **The native was never loaded**: `TensorFlowLite`'s static initialiser swallows the `System.loadLibrary` failure and `init()` then throws. Call `System.load()` on the extracted path, as `TfliteRuntime` does. (b) **A one-sided `ai.djl.tflite` version bump**: the two jars are not version-locked by any POM, so a JNI-ABI break reads as (a). Check `libs.versions.toml` before touching either. In both cases `TfliteRuntime.availability()` reports `available = false` and the OS is review-only (R-U, R-T) |
| `DataType error: cannot resolve DataType of java.util.HashMap` | You used the two-argument `run(Object, Object)`. A multi-output model must use `runForMultipleInputsOutputs(Object[], Map<Integer, Object>)`; the two-arg form picks its output accessor from the *input*'s type |
| Desktop TFLite reports `available = false` on Windows or Apple Silicon | Expected. No upstream native exists for `windows-x86_64` or `osx-aarch64` and a version bump will not create one. That platform is "review-only, no on-device inference" (R-T) |
| ML Kit downloads at runtime | using unbundled artifact → switch to bundled model dep. The dep **is** the bundled `com.google.mlkit:text-recognition`, so this should not happen — but the airplane test that would catch it has **never run on a device** (it reaches step D and stops: no `adb`). Treat it as uncaught, not as absent |
| `:app-android:buildReleasePreBundle` → `Sequence contains more than one matching element` | AGP 8.9.2 cannot apply `splits.abi` and build an app bundle in the same module. Reproduced with `isUniversalApk` both `false` and `true`. **There is no `.aab` and no Play Store bundle; delivery is APK-only.** Do not "fix" it by dropping `splits.abi` — that returns the release artefact to 77.53 MB, over the 35 MB budget. It needs an AGP upgrade, or the Tesseract-Android capability swap |
| `:app-android:test` → 160 compile errors in `ml/BlazeFace*.kt` | Those two files import `:platform`'s **JVM** classes (`dev.kasoti.platform.*`, `BlazeFaceAnchors`, `RgbImage`, `ImageIoImaging`) from an **Android** test source set, which cannot see them. Pre-existing since `06bd654`. `app-android/tools/verify-offline.sh` compiles and runs exactly these suites on a bare JVM (201/201), so the assertions are covered — by the script, not by Gradle |
| A `scripts/*.sh` fails with a bare `What went wrong: 27` | `JAVA_HOME` is not set, so Gradle ran on the default JDK 27. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` and re-run. Measured 2026-10-03: `sh scripts/pii_scrubber_test.sh` fails this way without it and exits 0 with it |
| `ktlintCheck` reports far fewer violations than expected | You omitted `--continue`. Gradle stops at the first failing task: 3 130 instead of 4 267 (measured 2026-10-03). Also note a `…/src/`-scoped count misses the 9 violations in `.kts` build scripts |
| Tess4J can't find libtesseract | install system tesseract + set `jna.library.path`/PATH; verify `tesseract --version` |
| Colors differ phone-vs-desktop macro | run calibration card routine (DATA.md §5); never eyeball-tune thresholds. Without it, the harness **refuses** macro metrics (EVAL.md §7) |
| Diary merge dupes | same device-id on two phones → re-provision device id (SYNC.md §6) |
| Gradle OOM | `org.gradle.jvmargs=-Xmx4g` in `gradle.properties` |
| `:eval:run` exits 4 and CI goes red | Expected without a device (see §3). A skipped suite is a first-class state, never a pass |
| `./gradlew: Permission denied`, or `exit code 126` from CI | **The exec bit is not committed.** Git records all 340 tracked files as `100644` (exFAT authoring volume, R-Q), so on any fresh clone `gradlew` and every `scripts/*.sh` are non-executable. Locally the tree always looks right, which is why this hides. Use `sh gradlew …` / `sh scripts/<name>.sh …`, or fix it properly with `git update-index --chmod=+x gradlew scripts/*.sh app-android/tools/*.sh` on a non-exFAT filesystem. **This is why both CI runs are red** (R-J) |
| `ktlintCheck` / `detekt` **fail with style findings** | Expected. Both tasks **exist** and are wired to `:core :platform :app-desktop :eval :ui` with 5-line ADRs in the root `build.gradle.kts`; every Gradle run prints a banner saying so. **`:app-android` is deliberately NOT in the linted list**, so its 32 Kotlin files are unlinted. They are red on day one — **4 267 ktlint violations** (`sh gradlew ktlintCheck --continue`) and **1 349 detekt weighted issues**, of which **1 004 are `MagicNumber`** — and are deliberately **detached from `check`** so they report without blocking. A previous revision of this table said "Neither plugin is wired" and "Task not found"; that was false at commit `9a04f40`. NFR-M1 ("detekt+ktlint clean") is genuinely unmet. Clearing it is the `TODO(M2,@build)` in `AGENTS.md` §1; do **not** reach green by disabling a rule or committing a baseline. ⚠️ `fusion/thresholds.v1.json` **now exists** (49 thresholds, §R-O) but is **untracked**, and detekt's `MagicNumber` count rose rather than fell — the registry was the diagnosis, not yet the cure |
| Every Gradle run prints "Kotlin Gradle plugin was loaded multiple times" | `:app-desktop`, `:core` and `:eval` each pin the version explicitly. Harmless today; remove the explicit versions and put the plugin on the root with `apply false` when convenient (R-M) |
| "the model did not load" in the desktop console | The console's model lookup is repo-relative (`SvmModelFile.DEFAULT_PATH`). `app-desktop/build.gradle.kts` sets `workingDir = rootProject.projectDir` for exactly this; a `java -jar` launch without that working directory reproduces the bug |
| TFLite throws a shape error on the first device run | Nearly always a destination whose shape does not match the tensor. `regressors` is `[1, 896, 16]` and `classificators` is `[1, 896, 1]`, **with the leading batch dimension** — a `[896, 16]` destination is rejected even though the element count matches, and an extra nesting level infers as `[1, 896, 16, 1]` and is also rejected. `TfliteFaceDetector.verifyShapes` checks this before the first inference and is the thing to read |
| `ClassNotFoundException: org.tensorflow.lite.Interpreter` on a **desktop** JVM | You resolved `org.tensorflow:tensorflow-lite` and think you have a runtime. You do not: the 2.17.0 jar is 1,411 bytes of POM relocation text with no classes (§4a). The desktop dependency is `ai.djl.tflite:tflite-engine`, which supplies the same class names |
| `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope` from a hand-run `kotlinc` | The Kotlin compiler CLI needs `kotlinx-coroutines-core-jvm` on its *own* classpath, not just on `-cp`. `app-android/tools/verify-offline.sh` shows the working invocation |
