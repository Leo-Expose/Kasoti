# STATUS — what actually works right now

**Living document.** Rewritten often; if your change flips any line here, update it
in the same change. Last measured **2026-09-29T20:13Z** against commit `6db63d9` on a
**clean** tree, on Ubuntu with Temurin/JDK 17.0.20.1 and **no Android SDK**. Every row below
was executed in that session; the two run ids in §3 are the runs that produced those numbers.

> ⚠️ **The tree started moving again during this docs audit.** At 20:22–20:26Z (i.e. *after* the
> measurements above) a concurrent change began modifying
> `app-android/src/main/java/dev/kasoti/android/platform/TfliteFace.kt`, adding new
> `app-android/.../ml/BlazeFace{Model,Decoder,Input}.kt` + tests, and writing
> `eval/fixtures/face/raw_outputs/`. That work is **uncommitted, unverified, and uncompilable
> here** (no SDK), and it looks like an attempt at the four Android-detector defects in §2.1
> finding 4 and R-C. Two consequences, stated plainly:
> 1. Everything measured in §1 and §3 above is against **commit `6db63d9` with a clean tree**,
>    and remains true of that commit. Re-measure before quoting anything against `HEAD`.
> 2. **Do not treat §2.1 finding 4's four defects as permanent.** They were open and documented
>    at the measurement time; whether the in-flight change fixes them is a question for a run
>    with an SDK, not for this file. Re-check `TfliteFace.kt` against
>    `eval/fixtures/face/detector_reference.json` before repeating the claim to anyone.

Authoritative docs are in `docs/`; this file only says what is *true today*, not
what is planned. Plan lives in `docs/ROADMAP.md`.

---

## 1. Commands that work right now

Measured on Ubuntu, Temurin/JDK 17.0.20.1, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`,
no Android SDK present (so `:app-android` is not in the build — see
`settings.gradle.kts:52-65`; **`:ui` *is* in the build regardless of the SDK**).

| Command | Result, measured 2026-09-29T20:13Z (see caveat) |
|---|---|
| `./gradlew :core:jvmTest` | ✅ **BUILD SUCCESSFUL** — **270 tests, 0 failures, 0 errors, 0 skipped** across 23 test classes (re-run with `--rerun-tasks`, not read off an up-to-date cache) |
| `./gradlew :core:allTests` | ✅ exists; the JVM target is the only one configured, so it is `:core:jvmTest` under another name |
| `./gradlew :ui:test` | ✅ **BUILD SUCCESSFUL** — 44 tests, 0 failures |
| `./gradlew :platform:jvmTest` | ✅ **BUILD SUCCESSFUL** — 137 tests, 0 failures |
| `./gradlew :app-desktop:test` | ✅ **BUILD SUCCESSFUL** — 169 tests, 0 failures |
| `./gradlew :app-desktop:run` | ✅ runs, prints the console usage banner, exit 0 (a headless text UI, not a GUI) |
| `./gradlew :eval:run --args="smoke"` | ⚠️ **runs, exits 4 = INCOMPLETE** — 11 gates pass, 1 skipped, 0 failed. Exit 4 is the *correct* code: the device gate cannot run on a host (see §3) |
| `./gradlew :eval:run --args="full"` | ⚠️ **runs, exits 4 = INCOMPLETE** — 13 pass, 6 skipped, 0 failed. **Not a signed-off run**; see §3 |
| `./gradlew :eval:run --args="parity"` | ⚠️ **runs, exits 4 = INCOMPLETE** — 1 gate, skipped: no device attached. Loud on purpose |
| `./scripts/check_no_network_in_core.sh` | ✅ **green — 75 Kotlin files scanned, zero banned constructs** (exit 0) |
| `./scripts/check_no_magic_thresholds.sh` | ❌ **red (exit 1) — 171 source lines** carry unregistered numeric literals in `:core` product code |
| `./scripts/pii_scrubber_test.sh` | ❌ **red (exit 1) — "PII scrubber NOT IMPLEMENTED YET" in `:core`** (correct behaviour, see §4). Note: `:app-desktop` *does* have a `LogScrubber` + tests — it is simply not in `:core`/`:platform`/`eval/src`, which is where this gate looks (§4.1) |
| `./scripts/fetch_models.sh` | ✅ **green (exit 0) — 1 model present and SHA-256 verified** (`blazeface_short.tflite`, 229,746 B, `b4578f35…`), measured 2026-09-30. `--record` independently reproduces the pinned digest. See §2.1. |
| `./scripts/fetch_models.sh scripts/models_manifest.sample.tsv` | ✅ **green by refusing** — the sample's `REPLACE_ME` fields abort before any network access. The refusal path still works. |
| `./scripts/verify_bundle.sh` | ✅ **works, correctly red (exit 1)** — refuses the unpopulated `bundle_manifest.sample.txt` rather than reporting a green "verified" bundle |
| `./scripts/provision.sh` | ✅ works (exit 0); 0600 secret, never printed, refuses to write inside the repo |
| `./scripts/purge_volunteer_data.sh <id> --dry-run` | ✅ works (exit 0) — reports "already clean" and re-runs smoke. Exit 2 with no argument is the usage error, by design |
| `./gradlew ktlintCheck` | ⛔ **not runnable — the task does not exist** (see §4.3) |
| `./gradlew detekt` | ⛔ **not runnable — the task does not exist** (see §4.3) |
| `./gradlew :app-android:installDebug` | ⛔ **not runnable here** — no Android SDK, so `:app-android` is not in the build at all |
| `./gradlew :app-android:bundleRelease` | ⛔ same |
| `./gradlew :app-desktop:packageDistributionForCurrentOS` | ⛔ **does not exist** — desktop packaging has not been built (see §4.5) |
| `./gradlew bundleOffline` | ⛔ **does not exist** — it was never an AGP task here, and the app has never been assembled |
| `./scripts/airplane_install_test.sh` | ✅ **works; exits 2** — "no APK/AAB build output found". Honest: the no-network install proof has **never** been run |

> **On the concurrency caveat that used to stand here.** It no longer applies: the tree was
> clean for this measurement. The build-cache pack error it described
> (`Entry 'tree-classpathSnapshot' closed at …`) is a two-daemons-sharing-`build/` artefact;
> if it reappears, serialise the build or use `--no-build-cache`. **Do not "fix" it in
> product code.**

> **A standing build warning, not noise:** every Gradle invocation prints *"The Kotlin
> Gradle plugin was loaded multiple times in different subprojects"*. `:app-desktop`,
> `:core` and `:eval` each pin the plugin version explicitly. Harmless today, fatal later —
> see R-M.

---

## 2. What is implemented

### `:core` (pure Kotlin, no expect/actual) — substantial

270 tests, 0 failures (`:core:jvmTest`, 2026-09-29T20:13Z).

| Area | State |
|---|---|
| MRZ — ICAO 9303 TD1/TD3 parse, 7-3-1 check digits, builder, seeded 10k mutate corpus | ✅ implemented + tested |
| Checks — Verhoeff, date/format validators, VIZ↔MRZ fuzzy match | ✅ implemented + tested. **The Verhoeff P-table defect is CLOSED**: `core/.../checks/Verhoeff.kt` generates the D/P/INV tables from the dihedral-group definition, `ChecksTest.kt:27-61` pins the generated tables against the published ones, and `ChecksTest.kt:65-67` asserts the published `236 → 3` worked example. `U-GATE-01` passes 19/19 (§3) |
| QR — secure-QR parse + verify over key slots, with rotation/expiry states | ✅ implemented + tested (TEST keys only — see R-B) |
| Diary — alias rule, impossible travel, facilitator rule, file-backed repo, ULID ids | ✅ implemented + tested (56 tests) |
| Fusion — `Finding` vocabulary, RED/AMBER/GREY rules, coverage rules, track matrix, verdict report | ✅ implemented + tested |
| Face math — cosine similarity, quality gates, detection curves, per-bucket | ✅ implemented + tested. `Detection.perBucket` has **never been run on real data** (no D-FACE) |
| Macro/factory — grayscale, spectrum (FFT radial peak), features (LBP-59), classifier | ✅ implemented + tested |
| Eval metrics — classification, FAR/FRR, mutate-catch, latency, JSON sink | ✅ implemented + tested |
| Audit — hash chain over decisions | ⚠️ **implemented, NOT tested** — `AuditChain.kt` has no test file in any module (`rg -l AuditChain` over every test source set returns nothing). `DESIGN.md` §8 I6 claims otherwise; corrected there |
| i18n — message-key catalogue (Eng + Hin) | ✅ implemented + tested |
| Threshold registry — versioned, floor/ceiling enforced, canonical serialisation | ✅ implemented + tested; **`fusion/thresholds.v1.json` does NOT exist yet** (R-O). 34 thresholds, all at their **untuned** defaults |

### `:ui` (plain `kotlin-jvm` presentation state — **not** Compose)
44 tests, 0 failures. Pure presentation state + a `FieldView` contract + a reducer.
**There is no Compose binding and no `@Composable` anywhere in the tree**; the two
candidate renderers live in modules that need an SDK, so neither has been compiled.
`ui/README.md` §1 states the reasoning and the migration path. `DESIGN.md` D7's
"CMP shared UI" was therefore **not** taken — corrected in `DESIGN.md` §1.

### `:app-desktop` (headless post-console)
169 tests, 0 failures. Text CLI over `:core` + `:platform` with commands `screen`,
`override`, `verify`, `export`, `wipe`, `macro`, `sample`. Case-bundle export, shift
report and a `LogScrubber` with its own adversarial tests exist. **Packaging does not**
— `:app-desktop:packageDistributionForCurrentOS` is not a task, so "clean-machine
install" (SPEC §6 M2) is unproven.

### `:platform` (expect/actual — **JVM implementations only**)

`src/` contains `jvmMain` and `jvmTest` and nothing else. 137 tests, 0 failures.

JCA crypto primitives · RGB/gray imaging + ImageIO · OCR engine interface with a
manual fallback and a Tess4J implementation · model loader with hash checking ·
embedding-model interface · macro vocabulary · **desktop TFLite detector**
(`ml/tflite/`: `TfliteRuntime`, `TfliteBlazeFaceDetector`, `AnchorDecoder`,
`BlazeFaceContract`, `DesktopFaceLayer`).

**There are no Android `actual`s**, so the Android target of `:core` has never been
compiled and `:app-android` carries its own copies of the platform seams instead
(R-P).

### 2.1 Face layer — measured 2026-09-30 (spike 01, `docs/spikes/01-face-model.md`)

**The face layer is half built, and which half is not the half anyone would guess.**

| | State |
|---|---|
| **Detector** | ✅ **WORKS.** `blazeface_short.tflite` (BlazeFace short-range, float16/1) obtained from Google, **Apache-2.0 verified on the model's own model card**, 229,746 B, SHA-256 pinned to a digest **we measured** (`b4578f35…`, because Google publishes no `.sha256` sidecar). Runs on desktop JVM; deterministic across repeated runs on identical bytes. Fetch with `./scripts/fetch_models.sh`; provenance in `THIRD_PARTY.md` §3. |
| **Embedding** | ❌ **LICENCE DECIDED, WEIGHTS STILL NOT OBTAINED.** A research-licensed embedder is **acceptable for the prototype only** (`docs/README.md` §License: a SIH demonstration and research evaluation is a non-commercial research context) — decision, consequences and date in `docs/spikes/01-face-model.md` §7 item 5. A **second** sourcing round after that decision checked four further candidates (AdaFace-via-PINTO, ONNX Model Zoo ArcFace, `estebanuri/face_recognition`, MediaPipe Face Embedder) and obtained nothing; two of the ecosystem's reference repos had gone 404 in the meantime. `face = null` → layer `UNAVAILABLE` → **GREEN 1:1 unreachable**. Fail-closed, by design. **The decision is a permission, not an implementation.** |
| **Android parity** | ⚠️ **PARTIAL, AND THE PART IS NAMED.** No SDK, no device, so **device parity is still not claimed**. What *is* now checked mechanically, on a bare JVM: the Android detector's **decode** (sigmoid, 896-anchor grid, box and keypoint placement, IoU, NMS) and its **input preparation** (half-pixel-centre bilinear resize + the model's `[-1,1]` encoding) are bit-identical to `:platform`'s, asserted by `BlazeFaceDesktopParityTest` / `BlazeFaceInputTest` against the detector's **real output tensors** committed in `eval/fixtures/face/raw_outputs/`. The TFLite **binding** is compiled only against hand-written API stubs. |
| **Desktop latency** | ⚠️ `blazeface_short` native inference **p50 ≈ 10.8 ms, p95 ≈ 20.1 ms** on the dev box, 4 threads, synthetic 128×128 input — our own probe, in `docs/spikes/01-face-model.md` §5.2, and **not** reproducible from the repo (the probe was scaffolding and is not committed, per spike §5.1 step 5). Google publishes **2.94 ms CPU on Pixel 6**. **NFR-P1's "<400 ms detect + embed" is NOT measured on a named device and is NOT claimed** (EVAL.md §8 forbids cherry-picked devices; §4 requires NAMED devices). *(An earlier revision of this line quoted "6.7–14.6 ms p50-ish" from somewhere with no run id and no provenance; it has been removed rather than restated — AGENTS.md §5.)* |

**Why there is no embedder — the one finding that matters most.** Every face-recognition
checkpoint ecosystem is trained on MS-Celeb-1M / MS1M / VGGFace2 / CASIA-WebFace, all
non-commercial-research datasets. `deepinsight/insightface` states this in its own README and
since 2025-11-24 requires an **email** to license even its open-sourced models. Two of
DESIGN §6's three named candidates (`DXG-INF/GhostFaceNet`, `deepghs/edgeface`) are **404 — the
repositories are gone**. Google's MediaPipe Face Embedder task is documented but **its weights
are not published** — the `mediapipe-models` bucket has 21 prefixes and none is
`face_embedder/`. **No weights were fabricated, approximated or synthesised, and no code path in
`:platform` can produce an embedding.**

**The licensing half is now closed and the fetching half is not.** As project lead I decided on
2026-09-30 that a **research-licensed embedder is acceptable for the prototype**, on the grounds
that `docs/README.md` §License defines the deliverable as a SIH demonstration and research
evaluation — a non-commercial research context — which is exactly what a "non-commercial research
purposes only" checkpoint licence permits. Three consequences are recorded as obligations, not
intentions, in `docs/spikes/01-face-model.md` §7 item 5:

1. **the embedder may not ship in an operational deployment** (the prototype-only grant does not
   travel; `THREAT_MODEL.md` §8 already carries the operational disclaimer);
2. **licence obligations attach to redistribution, and redistribution is a separate right from
   use** — so a candidate that permits research *use* may still forbid *redistribution*, in which
   case the artefact is provisioned outside the repo like a secret and never committed;
3. **the vendor scope caveats and the measured skin-tone (5.3 pp) and regional (7.5 pp) recall gaps
   must be disclosed rather than discovered by a judge** — the two paragraphs immediately below are
   that disclosure, and the duty extends to the embedder, which comes from the same MS1M/VGGFace2
   lineage and carries the same provenance question.

A second, focused search then ran *because* the decision made a research licence admissible. Four
further candidates were checked for their actual licence text and for whether a TFLite conversion is
possible, and **all four were rejected**: AdaFace-via-PINTO (MIT on the *conversion*; upstream
`minchul/cvlface_adaface` **404**, WebFace260M/MS1MV3 data, 512-d), ONNX Model Zoo ArcFace
(Apache-2.0 text, but `onnx/models/vision/body_analysis/arcface` is itself **404**, 512-d, ONNX,
MS1MV2), `estebanuri/face_recognition` (a genuine 22.5 MB `facenet.tflite` — and the repository has
**no licence file of any kind**, all rights reserved, at 4.5× the ≤5 MB budget), and the MediaPipe
Face Embedder (still unpublished; the bucket publishes `audio_embedder`, `image_embedder` and
`text_embedder` and no face one). **Nothing was converted, fabricated, approximated or synthesised,
and the seam is left as a clean documented `null`.** Full evidence: `docs/spikes/01-face-model.md`
§2.1 and §7 item 6.

**Two things a judge will ask, recorded before they ask.**

1. **Stated scope mismatch.** The model card lists *"any form of surveillance or identity
   recognition"* as explicitly **out of scope**, and says the model "is not intended for human
   life-critical decisions". Apache-2.0 permits our use; the authors documented the opposite
   boundary. Lead decision, §5 and spike §6.1.
2. **Documented per-slice bias (Google's numbers, not ours — EVAL.md §1 requires a run id we
   cannot supply).** Recall 98.4% across perceived gender (98.2 vs 98.5 — a **0.3 pp** gap), but
   **98.1% across skin tones spanning 94.7–100% (5.3 pp)** and **99.1% across 17 geographic
   subregions spanning 92.5–100% (7.5 pp)**. The skin-tone and subregion spreads are the numbers
   that matter for a land border, and the same model being consistent across gender while varying
   across skin tone is itself the finding. **There are NO age-bucket numbers at all**, and
   EVAL.md §4 requires gender × age-band × lighting — so two of our three required axes have no
   vendor coverage and no coverage of our own. Vendor eval sets are 720/800/350 images, stated as
   **not disjoint**, from the same source as training. `Detection.perBucket` is implemented and
   tested and has **never been run on real data**. See spike §6.2–6.4.

**Two things this work found that are not about the model.**

3. **`BUILD.md §4` was wrong about the desktop TFLite runtime, and is now corrected.**
   There is no first-party TFLite JVM artefact. Re-probed 2026-09-30 and confirmed:
   `org.tensorflow:tensorflow-lite:2.17.0`'s "jar" is **1,411 bytes of ASCII that is a verbatim
   copy of its own POM** — a `<relocation>` to `com.google.ai.edge.litert:litert:1.0.1`, which is
   `<packaging>aar</packaging>`; `2.16.1`/`2.15.0`/`2.14.0`/`2.13.0` are all AAR with **no `.jar`
   published at all**; and **no `<os>` classifier exists** (`linux-x64`, `windows-x64`, `macos-x64`,
   `macos-arm64`, `linux-x86_64`, `osx-x86_64`, `linux-aarch64` all 404). The AAR's own natives
   cannot be reused either: `readelf -d` on the `.so` files inside the real AAR shows
   `NEEDED libc.so, liblog.so, libdl.so, libm.so` and **no `libc.so.6` / `ld-linux-x86-64.so.2`** —
   Bionic sonames, and `file` reports them "for Android 21, built by NDK r25b". A desktop JVM
   resolves the versioned glibc names.
   The build runs on **`ai.djl.tflite`** (Apache-2.0), which republishes the genuine
   `org.tensorflow.lite.*` classes — `javap -c` shows the TFLite side references **zero** `ai.djl`
   classes, so the dependency flows one way — plus desktop JNI published separately. That has
   natives for **`linux-x86_64` (3,382,784 B) and `osx-x86_64` (7,073,184 B, Mach-O x86_64) only**,
   and `tflite-native-cpu` has published exactly two versions (`2.4.1`, `2.6.2`) with exactly those
   two classifiers. **`windows-x86_64` and Apple-Silicon Mac are review-only, no on-device
   inference, and no version bump fixes it.** The old §7 troubleshooting row that told you to
   "check the `<os>` classifier" was pointing at a thing that does not exist.
   Corrected consistently in `BUILD.md` §4 (rewritten), `DESIGN.md` §1/D1/§3, `ROADMAP.md` M2.2 and
   the M2-d3 tripwire, and the spike §4. New build structural risk: see R-T below.
4. **The Android detector could not run this model as written. FIXED 2026-09-30** — and it was
   worse than the four reported defects. The reported four were all real and all verified against
   the model and the desktop binding: output array allocated as `[1, 160, 1]` against a
   `[1, 896, 16]` tensor; the raw logit used as a confidence with **no sigmoid** although the model
   has no `SOFTMAX`; a single-output `run` for a two-output model; and no anchor decode before
   reading keypoints. **Five more were found while fixing them**, and one of those is the
   uncomfortable one:

   - the frame was handed to the interpreter as **interleaved `0..255` bytes at the source
     resolution**, where the model's input edge is FLOAT32 `[1, 128, 128, 3]` in `[-1, 1]` needing
     a resize first — and **nothing fails loudly at the call site**, so the detector was scoring
     the wrong pixels at the wrong scale;
   - `apply { numThreads = numThreads; useXNNPACK = true }` **set nothing**: TFLite's `Options`
     exposes setters and no getters, so Kotlin synthesised no property and the left-hand sides
     resolved to the enclosing function's own parameter. The thread count was TFLite's default and
     DESIGN §1's "no delegate enabled" was unenforced;
   - `TfliteFaceModels.embed` called `align(crop)` with too few arguments, so **the file did not
     compile**;
   - a `/*` inside a KDoc glob made the file **lexically invalid** (Kotlin does not allow a nested
     comment opener), surfacing 30 lines from its cause;
   - `defaultDigest()` used `Digest { … }`, a SAM conversion that does not exist, because
     `:core`'s `Digest` is a plain `interface`. **The file did not compile.**

   The last three are the concrete cost of a module that has never been compiled, and none of them
   is visible by reading the code. `app-android/tools/verify-offline.sh` now has a **tier 2** that
   compiles `TfliteFace.kt` against hand-written stubs of the four `android.*`/`org.tensorflow.*`
   types it touches, which is how those were found. Full record, with the verification method and
   the seven mutation checks, in `eval/fixtures/face/detector_reference.json`
   → `android_detector_defects_fixed`.

   ⚠️ **The binding is still not built against a real SDK, and the stub signatures are our reading
   of the real APIs.** What *is* verified: the decode and the input preparation are bit-identical
   to `:platform`'s, on the detector's real committed output and on adversarial tensors, with seven
   deliberate mutations each turning the suite red. What is **not**: that a device produces those
   numbers.

### `:eval`

The harness works. Eight suites exist (`mrz`, `qr`, `diary`, `unit-parity`, `latency`,
`macro`, `face`, `parity`); `smoke` runs the first five. Exit codes are distinct on purpose
(0 pass · 2 gate failed · 3 INVALID/report-split tuning · 4 INCOMPLETE/skipped) — see
`eval/README.md`. **Every run available today exits 4**, because a skipped device gate makes
a run INCOMPLETE. That is the designed behaviour, not a failure, and `summary.md` carries an
`INCOMPLETE` banner saying so.

### Tooling, scripts, hardware, CI (this area)

| Thing | State |
|---|---|
| `.github/workflows/ci.yml` | ✅ written, 285 lines, 2 jobs (`verify`, weekly `offline-proof`), parses as YAML. ⚠️ **it has never executed** — no git remote, no runner (R-J). Two things in it are also factually wrong and are reported, not fixed, because `.github/**` is out of this change's write scope: it says `settings.gradle.kts` gates `:ui` on the SDK (it does not — `settings.gradle.kts:48-50` always includes it), and it quotes "~94 lines" of magic thresholds where the measurement is 171 (§4.2) |
| `scripts/check_no_network_in_core.sh` | ✅ works, **green**, exit 0 |
| `scripts/check_no_magic_thresholds.sh` | ✅ works, **red** (171 lines, exit 1) — correctly, see §4.2 |
| `scripts/pii_scrubber_test.sh` | ✅ works, **red by design** (exit 1) until a scrubber exists in `:core` — see §4.1 |
| `scripts/verify_bundle.sh` | ✅ works (exit 1); correctly refuses the unpopulated sample manifest |
| `scripts/fetch_models.sh` | ✅ works (exit 0); correctly refuses the unpopulated sample manifest, no network touched |
| `scripts/provision.sh` | ✅ works (exit 0); 0600 secret, never printed, refuses to write inside the repo, warns loudly on the exFAT volume (R-Q) |
| `scripts/purge_volunteer_data.sh` | ✅ works (exit 0 with an id, 2 on usage); scoped to `eval/data/`, `eval/runs/`, `eval/fixtures/`, re-runs smoke |
| `scripts/airplane_install_test.sh` | ✅ works; **exits 2** — "no APK/AAB build output found", honestly. Its own message wrongly says `settings.gradle.kts` gates `:ui` on the SDK (it does not) — reported, not fixed |
| `scripts/bundle_manifest.sample.txt` | ⚠️ **stale.** Still says `blazeface_short.tflite licence: UNVERIFIED`, which `THIRD_PARTY.md` §3 has since verified as Apache-2.0. `scripts/**` is out of this change's write scope, so it is reported here. The real pin is `scripts/models_manifest.tsv` (SHA-256) + `eval/models/manifest.json` |
| `hardware/calibration_card.pdf` | ✅ **exists and compiles** (pdflatex available on this host; 299,935 B, 2 pages) |
| `hardware/clip_bom.md` | ⚠️ written, but **costs are indicative estimates and the stack lands at ~₹445/clip, over the ₹300 claim**. **No clip has ever been built.** R-G |
| `hardware/print_targets.md` | ⚠️ **a specification, not artwork** — no SPECIMEN design files exist. R-L |
| `eval/data/` | ✅ **exists** — manifests, split declarations (`manifests/datasets.json`), the anti-gaming ledger (`threshold_provenance.json`) and the calibration slot, all committed. ⚠️ **zero media, zero rows** in every `manifest.csv`. No D-MACRO, no D-FACE, no D-PASTE, no D-SPOOF. R-E |
| `eval/models/` | ✅ `blazeface_short.tflite` (fetched, hash-pinned) + `svm_print_v1_synthetic.json` (**SYNTHETIC — trained by `eval/tools/synth_macros.py`, explicitly NOT D-MACRO and never gate-eligible**; the harness labels it `SYNTHETIC FALLBACK` at every use) |
| `eval/fixtures/face/` | ✅ `detector_reference.json` (the normative desktop reference + the four Android defects), `manifest.json`, and a synthetic no-face image. **No reference embeddings exist** — there is no embedder |
| `THIRD_PARTY.md`, `CONTRIBUTING.md`, `docs/STATUS.md` | ✅ written; `THIRD_PARTY.md` now lists every `libs.versions.toml` entry including ML Kit and Compose |

---

## 3. Eval status — cited, not hand-typed

Two runs exist from this measurement, both on a **clean** tree at commit `6db63d9`:

- **`smoke` → run-id `eval-20260929-smoke-a1c7`** — `./gradlew :eval:run --args="smoke"`,
  12 gates: **11 pass, 1 skipped, 0 failed**, status INCOMPLETE, exit 4.
  Full detail in `eval/runs/eval-20260929-smoke-a1c7/summary.md`.
- **`full` → run-id `eval-20260929-full-fd65`** — `./gradlew :eval:run --args="full"`,
  19 gates: **13 pass, 6 skipped, 0 failed**, status INCOMPLETE, exit 4.
  Full detail in `eval/runs/eval-20260929-full-fd65/summary.md`.

**Both suites were re-run to confirm the numbers are reproducible, not one-offs.** The re-runs
(`eval-20260929-smoke-a290`, `eval-20260929-full-8b0b`) produced **identical** gate results
byte-for-byte on the same commit. `--run-id=auto` derives the id from date + suite + content, so
*your* re-run will get a different id and the same numbers — quote the id you actually produced,
and check `ls eval/runs/<id>/` before you put it in a slide.

> **Correction.** The run id an earlier revision of this file cited for the smoke result,
> `eval-20260929-smoke-c2f4`, **does not exist in `eval/runs/`** and never did. It was a
> hand-typed id, which is precisely what AGENTS.md §5 forbids. The two ids above are the
> directories the numbers were read out of.

### `smoke` gates — `eval-20260929-smoke-a1c7`

| Gate | Status |
|---|---|
| `M-GATE-01` MRZ mutate-catch (detectable mutants) | ✅ pass — 6725/6725 |
| `M-GATE-02` MRZ false positives on valid docs | ✅ pass — 0/2529 |
| `M-GATE-03` MRZ structurally blind cases published | ✅ pass — 746/10000 reported, not hidden |
| `Q-GATE-01` QR tamper-catch | ✅ pass — 245/245 |
| `Q-GATE-02` QR accept rate on genuine signed fixtures | ✅ pass — 100/100 |
| `Q-GATE-03` QR↔print wrong-person catch | ✅ pass — 100/100 |
| `D-GATE-01` diary planted scripts | ✅ pass — 23/23 |
| `D-GATE-02` diary stays GREEN on benign weeks | ✅ pass — 17/17 |
| `D-GATE-03` diary rank-1 over distractors | ✅ pass — 10 probes, 10 001 distractors |
| `U-GATE-01` harness↔`:core` parity | ✅ **pass — 19/19 agree** |
| `L-GATE-01` latency stages within budget | ✅ pass (host JVM) |
| `L-GATE-02` device latency ceiling | ⏭️ **SKIPPED, not passed** — device gate; this host run is not a named device |

**`U-GATE-01` is green, and the defect it found is closed.** The harness originally caught a
genuine `:core` bug: `Verhoeff` implemented `c = D[c][digit]` with no P (permutation) table,
which is a different function, not a faster one. `Verhoeff.kt` now **generates** all three
tables (D, P, INV) from the D5 group definition, re-derives their invariants in an `init`
block so a corrupted generator fails at class-load, and exposes them for a test that pins
them against the published tables (`ChecksTest.kt:27-61`) plus the published `236 → 3`
worked example (`ChecksTest.kt:65-67`). `FormatValidators.validateAadhaar` is downstream of
it and is therefore fixed too. **The Verhoeff path may be quoted as verified.** The harness
suite still carries the wording of the old finding, but it is inside an
`if (mismatches > 0)` branch and no longer renders — see §4.6.

### The other six gates, in `full` — `eval-20260929-full-fd65`

`full` adds `macro`, `face` and `parity`. Of the six extra gates: `MACRO-FEAT` and
`F-GATE-02` pass; `MACRO-CAL`, `MACRO-CLS`, `F-GATE-01`, `F-GATE-03`, `P-GATE-01` are all
**skipped, with reasons, in the run record** — never passed:

- `MACRO-CAL` — refused: no `device_calib.json` for `TEST-HARNESS-JVM`. **This is the
  correct refusal** (EVAL.md §7, ≤7 days old). No macro or face number may be quoted.
- `MACRO-CLS` — synthetic model, n=7 held-out specimens, 3/4 ambiguous specimens abstain
  below 0.15. **Not the D-MACRO gate of SPEC.md §7 and not gate-eligible**; the run says so.
- `F-GATE-01`, `F-GATE-03` — no D-FACE data, and no embedder to make a vector even if there
  were. EVAL.md §4 forbids reporting a zero-filled bucket table, so the buckets are absent.
- `P-GATE-01` — no device attached.

**Calibration:** both runs reported `calib REFUSED` — the correct refusal, for the same
reason. **No macro or face accuracy number exists anywhere in this project** (EVAL.md §7).

**What the passing numbers are and are not.** Every green gate above is logic tested on
**generated** fixtures: the MRZ corpus is generated from a fixed seed, the QR fixtures are
signed by a per-JVM harness stub over a TEST keypair, and the diary scenarios are scripts.
That is a real result about the code, and it is not a result about print substrate, real
faces, or a real signed Aadhaar QR. `qr` in particular certifies `:core`'s key selection and
tamper detection, **not** the production JCA binding.

---

## 4. Known-red gates, and why they are red

These are **documented, not worked around**. Nothing below should be made green
by weakening a check (AGENTS.md §8).

1. **PII scrubber — red, exit 1.** No scrubber exists in `:core`, `:platform` or `eval/src`,
   which is where `scripts/pii_scrubber_test.sh` looks. The script fails with an explicit
   "not implemented yet" message and refuses to pass. **Owner:** maintainer. Expected shape:
   `core/.../log/PiiScrubber.kt` + adversarial tests + an eval fixture.
   **Nuance, stated because it is easy to get wrong in either direction:** a working
   `LogScrubber` + `LogScrubberTest` **do** exist — in `:app-desktop`
   (`app-desktop/src/main/kotlin/dev/kasoti/desktop/LogScrubber.kt`, 169 tests green in that
   module). So "we have no PII control" is false; "the gate is red" is true. Until the
   scrubber is in `:core` and every log/cache/crash field is routed through it, we cannot
   honestly claim "no PII in logs" for the shared path.
2. **Magic thresholds — red, 171 lines, exit 1.** The check is *correct*; the codebase has not
   been triaged. Many hits are legitimate (hash multipliers, ASCII codes, MRZ field offsets,
   date arithmetic — `IsoInstant.kt:68,76,86,87` is four of them). Each needs either a
   registered threshold or a justified allowlist rule. **Owner:** maintainer. Do **not** fix
   this by widening the allowlist until the build goes green. (`.github/workflows/ci.yml:119`
   still says "~94 lines"; that number is stale — `.github/**` is not this change's scope.)
3. **Lint — not runnable, and not fixable from a docs change.** `ktlintCheck` needs the
   `org.jlleitschuh.gradle.ktlint` plugin, which is not in `gradle/libs.versions.toml`
   (a `ktlint = "12.3.0"` version entry exists but nothing uses it). `detekt` is declared at
   the root (`build.gradle.kts:2`) with `apply false`, so no module creates the task.
   Verified: `./gradlew ktlintCheck --dry-run` and `./gradlew detekt --dry-run` both fail
   with "task not found". CI detects both and says "stub" rather than pretending.
   **Owner:** maintainer, in `gradle/libs.versions.toml` + `build.gradle.kts`, with a 5-line
   ADR per AGENTS.md §5 — **deliberately not added from a docs edit or a workflow file**.
   Note this is also a *stated* SPEC NFR: NFR-M1 says "detekt+ktlint clean", and it is
   currently neither.
4. **APK / bundle size — not runnable.** Needs an Android SDK and a built `:app-android`.
   No size number may be quoted until it runs. `scripts/airplane_install_test.sh` is
   the honest form of this: it exits 2 and says there is nothing to install.
5. **Desktop packaging — does not exist.** `:app-desktop:packageDistributionForCurrentOS`
   (advertised in `BUILD.md` §3 and `ROADMAP.md` M2.7) is not a Gradle task, and
   `bundleOffline` is not either. `:app-desktop:run` works from a checkout, which is the
   `ROADMAP.md` M2-d3 tripwire fallback; "clean-machine install" (SPEC §6 M2) is therefore
   unproven. **Owner:** maintainer.
6. **Dead finding text in `:eval`.** `UnitParitySuite.kt:233-244` still carries the
   "OUTSTANDING :core DEFECT — Verhoeff" note, and `eval/README.md` §"Known state" still
   describes the gate as red. Both are stale: the note sits inside
   `if (mismatches > 0)` and no longer renders, and the gate passes 19/19. `eval/src/**` is
   not in this change's write scope, so the Kotlin text is reported here and the README is
   corrected — see §4.1 of the report. **Owner:** maintainer; it is a two-line deletion.

---

## 5. Open risks and deferred items

Ordered by how much they can hurt us, not by how hard they are.

### Blockers for the finals narrative

**Owner column: there is one owner.** The project is solo-maintained
(`docs/HANDOFF.md` §2). The old owners — android ×2, vision, vision+all — do not exist,
and an unowned blocker is an unowned blocker.

| # | Item | Why it hurts | Owner |
|---|---|---|---|
| R-A | ~~**No model weights.**~~ **HALF CLOSED 2026-09-30** — the **detector** is obtained, Apache-2.0-verified, hash-pinned and running (§2.1, spike 01). **`emb_v1.tflite` remains unobtainable**: 7 candidates rejected on licence grounds, then a **licensing decision** (a research-licensed embedder is acceptable **for the prototype** only — no operational deployment, redistribution obligations attach, vendor scope + 5.3 pp skin-tone / 7.5 pp regional recall gaps must be disclosed) and a **second search round** under that decision that checked 4 more candidates and obtained nothing. Nothing fabricated, converted or synthesised. | Face 1:1 is a ● load-bearing layer on every track (FUSION.md §7). The detector half works, so there is a pipeline to demo and `verify_bundle.sh` has something to verify — but a GREEN 1:1 verdict is **still unreachable**, because there is nothing to compare a face against. The licensing question is closed; what remains is a **price tag** (email InsightFace, or train our own embedder on data with clear rights) and the option of shipping detector-only and saying so. | **maintainer** (was vision) |
| R-B | **`uidai_qr_keys.json` provenance is UNVERIFIED / not obtained.** | Signed-QR is ● for the Aadhaar track. Until a confirmed source, a licence that permits bundling, and a pinned hash exist, **we cannot claim the signed-QR layer works against real material**. `:core` only ever exercises TEST keys, and the harness signs with a per-JVM stub verifier — the Q gates certify key selection, not the production binding. | maintainer |
| R-C | **No Android APK has ever been built. `:app-android` has never been compiled.** 34 Kotlin files and 5 test files exist and have never run. | There is no airplane-mode proof, no size number, and no M1 exit. **Everything about the field device is currently a claim.** SPEC §6 M1, NFR-S1, NFR-R1 (50 crash-free runs) and NFR-B1 all hang off this. | maintainer |
| R-D | **No calibration card run on a real device.** | EVAL.md §7 refuses macro/face numbers without ≤7-day calibration. The card now exists as a PDF (`hardware/calibration_card.pdf`, compiles) — nobody has run the routine, so every run reports `calib REFUSED`. | maintainer |
| R-E | **`eval/data/` now exists but contains zero media.** The manifests, `datasets.json` and `threshold_provenance.json` are committed; every `manifest.csv` has a header and **0 rows**. | The macro SVM cannot be trained or tuned on real substrate; `D-MACRO` needs ≥600 patches at M0. The only model in `eval/models/` is **SYNTHETIC** (`svm_print_v1_synthetic.json`, macro-F1 0.8469 on its own report split) and is explicitly never gate-eligible. **Every green gate in §3 is logic tested on generated fixtures, not on real print substrate.** | maintainer |
| R-F | **Zero real-people data has been collected.** | Consent records, `D-FACE` pairs and the deletion drill have never been exercised end to end. `purge_volunteer_data.sh` is written and dry-run tested against a synthetic tree, but never against real rows. | maintainer |

### Structural / process

| # | Item | Note |
|---|---|---|
| R-G | **₹300 clip claim is currently ~₹445 by the BOM, and no clip has been built.** | `hardware/clip_bom.md` prices are indicative estimates, not quotes. Decision needed: cut scope honestly (~₹345–350 with a named trade-off), or change the headline. Do not delete BOM rows to make it read ₹300. `DEMO.md` and `QA_BANK.md` have been corrected to stop saying "₹300" as fact. |
| R-H | **No Gradle dependency verification** (`gradle/verification-metadata.xml`). | Confirmed absent. `BUILD.md` §2 requires `verify-metadata`. A swapped transitive artefact is currently undetected — this is the AT-12 supply-chain residual. |
| R-I | **No `LICENSE` file at the repo root.** | Apache-2.0 code is vendored and we publish our own terms only in prose. Decision needed. |
| R-J | **No CI has ever run.** | `.github/workflows/ci.yml` is written and YAML-valid, but there is **no git remote configured** and no runner. Treat CI as unproven until the first green run. ⚠️ And it *would* not be green: `:eval:run --args="smoke"` is not `continue-on-error`, and every smoke run exits 4. The `verify` job fails at the eval-smoke step by design. *(Same fact as R-S — kept separate because the actions differ: R-S is "add a remote", R-J is "make the job pass".)* |
| R-K | **Offline proof is warm-cache only.** | The weekly job warms the cache then builds `--offline`. A cold-machine offline build is **not** proved and needs a persistent self-hosted runner. |
| R-L | **`FusionEngine` coverage rules and track matrix** were written but have not been exercised against the four real SPECIMEN tracks, because the SPECIMEN cards do not exist (`hardware/print_targets.md` is a specification and says so in its own first line; the artwork is unowned). | |
| R-M | **Kotlin Gradle plugin loaded multiple times** (`:app-desktop`, `:core`, `:eval` all pin the version explicitly). | Warning printed on **every** Gradle invocation; not fatal today, will become one. |
| R-Q | **The repo lives on an exFAT volume that ignores `chmod`.** | Re-measured 2026-09-29: `findmnt -T .` → `fstat` confirms `fmask=0022,dmask=0022` on `exfat`, and every file in the tree reports mode `755` (`stat -c '%A' AGENTS.md` → `-rwxr-xr-x`). Three consequences: (1) **any 0600 secret written inside the checkout is world-readable** — `scripts/provision.sh` detects this, prints a loud security warning, and refuses the repo by default; (2) git records mode `755` for every file, so the exec bit carries no information in this clone; (3) any "restrict this file" instruction in a design doc is unenforceable here. Provision to `~/.kasoti/provisioning` or a real filesystem. |
| R-R | **`:ui` is not the Compose Multiplatform module `DESIGN.md` D7 chose.** | It is a plain `kotlin-jvm` module with a `FieldView` interface and **no `@Composable` anywhere**. No renderer has been compiled, because both candidates live in SDK-gated modules. The logic is tested (44 tests); the binding is not. This is a deliberate, documented trade (spike decision log, `HANDOFF.md` §7) and a real gap in coverage of the UI. |
| R-T | **Desktop TFLite has no native for Windows x86-64 or Apple-Silicon Mac.** `ai.djl.tflite:tflite-native-cpu` publishes only `linux-x86_64` and `osx-x86_64`, for both of its two versions; the other two return HTTP 404. | Those platforms are "review-only, no on-device inference" (BUILD.md §4's own prescription, implemented in `TfliteRuntime`). It will not improve by bumping a version. The alternative is ONNX Runtime JVM (main jar 139,129,141 B at 1.22.0 — measured; win/linux/osx natives) which needs the model in ONNX and breaks D1's "same bytes on both platforms". **BUILD.md §4 has now been corrected** — it previously claimed natives for win/linux/mac. |
| R-U | **`ai.djl.tflite` Java↔native pairing is not version-locked.** `tflite-engine:0.27.0` (dated 2024-03-28) and `tflite-native-cpu:2.6.2` (dated 2022-01-12) are tied together by nothing in either POM. | A one-sided version bump is a JNI-ABI break that surfaces as `UnsatisfiedLinkError: TensorFlowLite.nativeRuntimeVersion()` — which reads like an ABI mismatch and is not one, so it will be misdiagnosed. Both versions are pinned in `libs.versions.toml` and recorded in `THIRD_PARTY.md` §1. Nobody should bump one side alone. |
| R-S | **No git remote is configured.** | `git remote -v` is empty. CI cannot run, no PR review is possible, and "CI is green" is currently unsayable. Also blocks the `CONTRIBUTING.md` §1 PR workflow, which is therefore intent rather than practice. |
| R-V | **`logs/verification-metadata.xml` and the lint plugins are the same class of gap, unfixed.** | `THIRD_PARTY.md` §1's own "build-tool gaps" note and §4.3 above are the same finding recorded twice. Listed once here to keep a single owner. |

### Known quality debt

| # | Item | Note |
|---|---|---|
| R-N | Corpus/debug helpers live in `commonMain` | `mrz/MrzCorpus.kt` generates the 10k mutate corpus from production code rather than from `:eval`, and the diary test-support helpers have been consolidating. These are test scaffolding in the shipping module. Move them when convenient; do not "fix" them by deleting the corpus. Owner: `:core`. |
| R-O | `fusion/thresholds.v1.json` still does not exist | `ThresholdRegistry.kt` is implemented and versioned in code (34 thresholds, floors/ceilings enforced, canonical serialisation) and is exercised by tests, but AGENTS.md §2 and DESIGN.md §1 require a **file** as the source of truth. Until it exists, every operating point lives in a Kotlin enum and the "versioned registry" claim is half-true. **All 34 defaults are untuned** — they are the values a human typed, not values a split chose. |
| R-P | `:platform` has only JVM implementations | `platform/src/` contains `jvmMain` and `jvmTest` and nothing else. No Android `actual`s exist, so the Android target of `:core` has never been compiled, and `core/src/` has only `commonMain`/`commonTest` (no `androidMain`). `:app-android` therefore carries its own copies of the platform seams. |

---

## 6. What is explicitly deferred (and should stay deferred)

- **NFC / chip reading (P1).** SPEC FR-N1 is stub-first. A SPECIMEN passport must
  be printed with a dead contact pad, not a live one (`hardware/print_targets.md`).
- **Tilt liveness viewer.** Experimental, off the critical path (QA_BANK).
- **Tesseract packaging for Windows.** P1 / M2 per BUILD.md §3. (There is no Windows TFLite
  native either — R-T — so Windows desktop is review-only on two counts.)
- **HTTPS sync transport.** File/USB/QR is sufficient for the demo (SYNC.md §5).
- **`:app-desktop` distribution packaging.** `:app-desktop:packageDistributionForCurrentOS`
  and `bundleOffline` are advertised in `BUILD.md` §3 and do not exist; `run` from a checkout
  is the `ROADMAP.md` M2-d3 tripwire fallback and is what works (§4.5).
- **STLS server.** ₹0/server is a feature, not a gap.

---

## 7. Next five things, in the order they unblock the most

1. **The embedder: the licence is decided, the weights are not, and now it is a *price tag*
   rather than a permission (R-A).** The decision is made and written down — a research-licensed
   embedder is acceptable **for the prototype**, with three binding consequences (no operational
   deployment; redistribution obligations attach and are a *separate* right from use; the vendor
   scope caveats and the measured 5.3 pp skin-tone / 7.5 pp regional recall gaps must be
   disclosed, not discovered). `docs/spikes/01-face-model.md` §7 item 5. A second search round
   after that decision found nothing (§7 item 6), so "look harder" is no longer on the list.
   What remains: (a) email InsightFace — since 2025-11-24 even open-sourced models route through
   `recognition-oss-pack@insightface.ai`, at an unknown price; (b) train our own 128-d embedder on
   data with clear training rights, a multi-week data project with its own consent burden; or
   (c) ship detector-only and say so in every narrative. **Until one of those happens,
   `emb_v1.tflite` stays empty and GREEN 1:1 stays unreachable** — the decision moved the
   permission, not the implementation.
2. **Install an Android SDK and build `:app-android` once** (R-C). This is still the single
   biggest *unmeasured* surface: no APK, no size number, no airplane proof, no M1 exit. The
   **nine** detector defects are fixed (§2.1 item 4) and the decode is checked against desktop,
   so the remaining work is genuinely "compile and run": `assembleDebug` against the *real* SDK
   and TFLite AAR rather than the hand-written stubs, then
   `./scripts/airplane_install_test.sh` on a device in airplane mode. Expect the first build to
   need fixes — the stubs are our reading of the API, and a real SDK disagrees with a reading
   more often than anyone expects.
3. **Print the SPECIMEN sets from `hardware/print_targets.md` and start
   `D-MACRO` collection** (R-E). Data collection has the longest lead time in the
   project; everything else can be parallelised around it. `eval/data/macro/manifest.csv`
   currently has a header and zero rows.
4. **Run the calibration-card routine on both named devices and store
   `device_calib.json`** (R-D). It depends on step 2.
5. **Write `PiiScrubber` in `:core`** + adversarial tests, and move the `:app-desktop`
   `LogScrubber`'s rules into the shared module; then flip that CI gate to blocking
   (§4.1). Note this is also what makes AGENTS.md §4's "no PII in logs" checkable rather
   than aspirational.

**Three cheap items that unblock verification rather than features**, listed here because they
are near-free and each closes a specific "we do not know" that currently reads as a risk:

- **One consented face crop** settles two open questions at once: the detector's `[-1,1]` vs
  `[0,1]` input encoding, and the unverified anchor-size parameters. Both are recorded as
  unresolved in `eval/fixtures/face/manifest.json` and `detector_reference.json` precisely so
  that this is a visible, closable item rather than a silent assumption. Cost: one image.
- **`app-android/tools/verify-offline.sh` runs on any machine with no SDK, in two tiers** — 189
  unit tests plus a stub-compile of `TfliteFace.kt`. It is the only thing standing between
  `:app-android`'s platform-free code and never being compiled at all, and it is how the three
  "this file does not compile" defects in §2.1 item 4 were found. Anyone touching
  `app-android` should run it; a green run is cheap and a red one is specific.
- **A git remote** (R-S). Without one, CI cannot run, PR review is impossible, and the
  `CONTRIBUTING.md` workflow is fiction. It also unblocks the first honest CI run, which is
  the only way the `verify` job's red eval-smoke step gets a decision.

---

## 8. How to keep this file honest

- Every number here comes from a command run on the date in the header, and the eval numbers
  cite a **run-id** whose directory exists under `eval/runs/`. No hand-typed metrics
  (AGENTS.md §5). When a cited run-id is not on disk, the number is not evidence — delete it.
- If a command in §1 stops working, fix the row — and fix `AGENTS.md` §1 if it
  is the same command, because a stale command list costs more than a stale
  comment.
- If something in §5 is resolved, delete it or move it to §2. A risk register
  that only grows is a risk register nobody reads.
- **Do not mark something green because it *should* be.** Every red row in §4 has been
  re-measured, not remembered. Two of the three "risks" this file was previously asked to
  close — magic thresholds and the PII scrubber — are **still red** (171 lines; no
  `:core` scrubber). Only the Verhoeff finding has actually closed.
