# KASOTI — Build & Run (BUILD.md)

## 1. Prereqs
- JDK 17 (Temurin). Gradle needs it: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` (or your
  Temurin 17) before anything in §3 will run. Android SDK (API 34, build-tools), CMake/NDK only
  if a dep needs it (goal: none) — **and note the SDK is optional at configure time**, see §2.
- Tesseract ≥5.3 + `eng` + `osd` traineddata on desktop dev machines (`apt/brew/choco install tesseract`); tessdata path in `local.properties` (`tess.data=`).
- One low-end Android test device (2–3 GB RAM, Android 10+) + one mid device. USB debugging on.
  **Neither device, nor any Android SDK, has been obtained** — see `docs/STATUS.md` R-C.
- Disk: ~15 GB (SDK + Gradle). Network needed ONLY for first dependency fetch; after that,
  airplane-build must succeed. `ci.yml` attempts this weekly with `--offline`, but that job
  **has never run** and is warm-cache only (STATUS R-J, R-K).

Tested-matrix (append your row):
| OS | JDK | outcome | date | who |
|---|---|---|---|---|
| Ubuntu 24.04 (kernel 6.18, 12 cpu) | Temurin 17.0.20.1 | `:core:jvmTest` 270/0 · `:ui:test` 44/0 · `:platform:jvmTest` 137/0 · `:app-desktop:test` 169/0 · `:app-desktop:run` ok · `:eval:run smoke` 11 pass/1 skip/0 fail (exit 4) · `full` 13/6/0 (exit 4) | 2026-09-29 | maintainer |
| — | — | `:app-android:*` | never run — no SDK | — |

## 2. Scaffold (done)
Kotlin Multiplatform module map, enforced in `settings.gradle.kts` (README's layout is the
target shape). Gradle version catalog pins ALL versions. Dependency verification
(`gradle/verification-metadata.xml`, `verify-metadata` on) is **still outstanding** —
the file does not exist (STATUS R-H).

**Module inclusion is conditional, and this matters more than it looks.**
`settings.gradle.kts:44-65` always includes `:core`, `:platform`, `:eval`, `:app-desktop` and
`:ui`, and includes `:app-android` **only** when an Android SDK is discoverable
(`local.properties` → `sdk.dir=`, or `ANDROID_HOME`/`ANDROID_SDK_ROOT`). On a desktop-only
machine `:app-android` is not in the build at all, so `:core:jvmTest` and `:eval:run` stay
runnable. **`:ui` is not gated** — it is plain `kotlin-jvm` and always builds
(`settings.gradle.kts:48-50`); `./gradlew :ui:test` works with no SDK.

> Earlier revisions of this file said `settings.gradle.kts` includes
> `:core,:platform,:ui,:app-android,:app-desktop,:eval` unconditionally, and `.github/workflows/ci.yml`
> and `ui/README.md` both repeat the false version of the `:ui` half. The `:ui` half is now
> corrected here. The two files outside `docs/` are reported, not edited.

## 3. Commands
Every command below was checked with `./gradlew <task> --dry-run` or actually run on
2026-09-29 (STATUS §1 has the results).

```bash
# core + eval (no device, no SDK needed) — ALL VERIFIED WORKING
./gradlew :core:jvmTest                        # 270 tests, 0 failures
./gradlew :core:allTests                       # = :core:jvmTest; JVM is the only target
./gradlew :ui:test                             # 44 tests  (not SDK-gated)
./gradlew :platform:jvmTest                    # 137 tests
./gradlew :app-desktop:test                    # 169 tests
./gradlew :app-desktop:run                     # headless text console; prints usage, exit 0
./gradlew :eval:run --args="smoke"             # ~60 s. 11 pass / 1 skip. EXITS 4, not 0
./gradlew :eval:run --args="full"              # 13 pass / 6 skip. EXITS 4, not 0
./gradlew :eval:run --args="parity"            # 1 gate, skipped, no device. EXITS 4, not 0

# android — REQUIRE an SDK in local.properties or ANDROID_HOME. NEVER RUN.
./gradlew :app-android:installDebug            # + logcat tag KASOTI
./gradlew :app-android:assembleDebug           # what airplane_install_test.sh asks for
./gradlew :app-android:bundleRelease           # size check (APK ≤35 MB gate in CI)

# offline proof
./scripts/airplane_install_test.sh             # exits 2 today: no APK/AAB exists
```

**Exit code 4 is not a broken build.** `:eval:run` returns 4 = INCOMPLETE whenever a required
suite is skipped, and the device gates (`L-GATE-02`, `P-GATE-01`, `F-GATE-01`, `F-GATE-03`,
`MACRO-CAL`) are *always* skipped without a device and a calibration file. A smoke run that
returned 0 would mean a device was attached, which it is not. Note this also means
`ci.yml`'s `verify` job **fails** at the eval-smoke step, because that step is not
`continue-on-error` (STATUS R-J).

**Two commands this file used to advertise do not exist** and have been corrected here:
- `:app-desktop:packageDistributionForCurrentOS` — never implemented. Desktop packaging is
  unbuilt; `run` from a checkout is the `ROADMAP.md` M2-d3 tripwire fallback.
- `bundleOffline` — never an AGP task in this tree. `ci.yml:265-274` already probes for it
  and says "not implemented" when it is missing, which is the honest form.

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
  devices. There is still no Android SDK and no device, so device parity is unverified and
  unclaimed, and `TfliteFaceDetector` is compiled only against hand-written API stubs.
  EVAL.md §4's ±1e-3 similarity tolerance still has no device to apply it to.

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
**never compiled or run**) · Tesseract + Tess4J (Apache) · UIDAI QR keys (**UNVERIFIED, not
obtained**). Full register with sources, digests and provenance states: `THIRD_PARTY.md`.

## 7. Troubleshooting (grow this table, don't DM fixes)
| Symptom | Cause → fix |
|---|---|
| TFLite-JVM `UnsatisfiedLinkError` on `nativeRuntimeVersion()` | Two distinct causes, and they look identical. (a) **The native was never loaded**: `TensorFlowLite`'s static initialiser swallows the `System.loadLibrary` failure and `init()` then throws. Call `System.load()` on the extracted path, as `TfliteRuntime` does. (b) **A one-sided `ai.djl.tflite` version bump**: the two jars are not version-locked by any POM, so a JNI-ABI break reads as (a). Check `libs.versions.toml` before touching either. In both cases `TfliteRuntime.availability()` reports `available = false` and the OS is review-only (R-U, R-T) |
| `DataType error: cannot resolve DataType of java.util.HashMap` | You used the two-argument `run(Object, Object)`. A multi-output model must use `runForMultipleInputsOutputs(Object[], Map<Integer, Object>)`; the two-arg form picks its output accessor from the *input*'s type |
| Desktop TFLite reports `available = false` on Windows or Apple Silicon | Expected. No upstream native exists for `windows-x86_64` or `osx-aarch64` and a version bump will not create one. That platform is "review-only, no on-device inference" (R-T) |
| ML Kit downloads at runtime | using unbundled artifact → switch to bundled model dep; airplane test catches it (never run — no device) |
| Tess4J can't find libtesseract | install system tesseract + set `jna.library.path`/PATH; verify `tesseract --version` |
| Colors differ phone-vs-desktop macro | run calibration card routine (DATA.md §5); never eyeball-tune thresholds. Without it, the harness **refuses** macro metrics (EVAL.md §7) |
| Diary merge dupes | same device-id on two phones → re-provision device id (SYNC.md §6) |
| Gradle OOM | `org.gradle.jvmargs=-Xmx4g` in `gradle.properties` |
| `:eval:run` exits 4 and CI goes red | Expected without a device (see §3). A skipped suite is a first-class state, never a pass |
| "Task 'ktlintCheck' not found" / "Task 'detekt' not found" | Neither plugin is wired: ktlint has a version entry in `libs.versions.toml` but no plugin/library row, and detekt is declared at the root with `apply false`. `ci.yml` detects both and says "stub". This is a **stated SPEC NFR (NFR-M1) that is currently unmet** (STATUS §4.3) |
| Every Gradle run prints "Kotlin Gradle plugin was loaded multiple times" | `:app-desktop`, `:core` and `:eval` each pin the version explicitly. Harmless today; remove the explicit versions and put the plugin on the root with `apply false` when convenient (R-M) |
| "the model did not load" in the desktop console | The console's model lookup is repo-relative (`SvmModelFile.DEFAULT_PATH`). `app-desktop/build.gradle.kts` sets `workingDir = rootProject.projectDir` for exactly this; a `java -jar` launch without that working directory reproduces the bug |
| TFLite throws a shape error on the first device run | Nearly always a destination whose shape does not match the tensor. `regressors` is `[1, 896, 16]` and `classificators` is `[1, 896, 1]`, **with the leading batch dimension** — a `[896, 16]` destination is rejected even though the element count matches, and an extra nesting level infers as `[1, 896, 16, 1]` and is also rejected. `TfliteFaceDetector.verifyShapes` checks this before the first inference and is the thing to read |
| `ClassNotFoundException: org.tensorflow.lite.Interpreter` on a **desktop** JVM | You resolved `org.tensorflow:tensorflow-lite` and think you have a runtime. You do not: the 2.17.0 jar is 1,411 bytes of POM relocation text with no classes (§4a). The desktop dependency is `ai.djl.tflite:tflite-engine`, which supplies the same class names |
| `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope` from a hand-run `kotlinc` | The Kotlin compiler CLI needs `kotlinx-coroutines-core-jvm` on its *own* classpath, not just on `-cp`. `app-android/tools/verify-offline.sh` shows the working invocation |
