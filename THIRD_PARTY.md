# THIRD_PARTY — licences, provenance and hash-pin register

Required by docs/BUILD.md §6 and referenced from AGENTS.md §5 ("new dependencies
without a 5-line ADR = review fail", "hand-typed accuracy numbers must cite an
eval run id"). **This file is the single place where a component's licence and
provenance live.** If you add a dependency, add a row here *in the same PR* as
the 5-line ADR, or the PR is incomplete.

Last reviewed: **2026-09-29** (re-audited against `gradle/libs.versions.toml` line by line; the
ML Kit and Compose rows were missing and are now present). Re-review before the finals purchase
and before any release build.

> ⚠️ **This register describes a project that has never been released and, on the Android side,
> never been compiled.** "verified" in the Hash-pin/Provenance columns means *we can name a
> source and re-fetch it* — it does not mean the component has run. See §5.6.

---

## How to read the status columns

| Column | Meaning |
|---|---|
| **Hash-pin** | `pinned` = exact version/digest recorded and verified at build or load · `version-pinned` = exact version in `gradle/libs.versions.toml`, digest not verified · `none` = nothing pins it · `n/a` | 
| **Provenance** | `verified` = we obtained it from a source we can name and re-fetch · `UNVERIFIED` = we have not obtained it yet, or we cannot name a source we have confirmed. **An UNVERIFIED row must never be shipped.** |

**Rules this file enforces**

1. Nothing ships with an `UNVERIFIED` licence row (THREAT_MODEL §8, BUILD.md §6).
2. Nothing ships with `Hash-pin: none` if it ends up on a device.
3. A row may not be marked `verified` on the strength of a URL someone typed
   from memory (AGENTS.md §8). If you cannot re-fetch it, it is UNVERIFIED.
4. Model binaries are never committed. The **real** pin is
   `scripts/models_manifest.tsv` — one line per artefact, `<sha256> <https url>
   <repo-relative destination>` — fetched and hash-verified by
   `scripts/fetch_models.sh`, with the committed inventory in
   `eval/models/manifest.json`. ⚠️ `scripts/bundle_manifest.sample.txt` is
   **not** that manifest: it is an unpopulated template (`REPLACE_ME` placeholders)
   that `verify_bundle.sh` correctly refuses, so `verify_bundle.sh` is **red** and
   stays red until someone populates it. An earlier revision of this rule pointed at
   the sample as if it were the pin; it is not, and treating it as one would have
   meant a "verified" bundle that verified nothing.

**Scope of this register, re-checked 2026-09-29.** Every entry in
`gradle/libs.versions.toml` now has a row below — including the ML Kit and Compose
rows that were previously missing, which matters because this file claims to be *the*
place a component's licence and provenance live. What it does **not** cover: transitive
dependencies (§5.2), and the fact that `:app-android` has never been compiled, so
"declared" is not "shipped" for the Android rows.

---

## 1. Build and runtime dependencies from `gradle/libs.versions.toml`

Everything in this section is exact-version-pinned in
`gradle/libs.versions.toml`. Transitive dependencies of these artefacts are
**not** individually listed here; see the gap noted in §5.

### Kotlin / JetBrains

| Component | Version | Licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|---|
| Kotlin Multiplatform plugin / stdlib | 2.1.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | version-pinned | verified |
| kotlin-jvm plugin | 2.1.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | version-pinned | verified |
| kotlin-serialization plugin | 2.1.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | version-pinned | verified |
| compose-compiler plugin (`org.jetbrains.kotlin.plugin.compose`) | 2.1.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | version-pinned | verified |

### JetBrains libraries

| Component | Version | Licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | version-pinned | verified |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | version-pinned | verified |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization | version-pinned | verified |
| `org.jetbrains.compose` (Compose Multiplatform) | 1.8.0 | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform | version-pinned | verified — **declared, NOT applied**: no module uses the CMP plugin (D7 was not taken; `:ui` is plain `kotlin-jvm`). Carries the "unused catalogue entry" note in §5.4 |

### Compose / Android UI — `:app-android` only, **never compiled**

⚠️ **Read the state of this whole sub-table before trusting any of it.** `:app-android` has
never been built — no Android SDK, no device (`docs/STATUS.md` R-C). These coordinates resolve
and their licences are as stated, but *no artefact has been produced from them and none of
their size contributions to the APK budget have been measured.* "verified" below means the
coordinate and its licence are right, not that the code has ever run.

| Component | Version | Licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|---|
| `androidx.compose:compose-bom` | 2024.12.01 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.compose.ui:ui` | (BOM-managed) | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.compose.material3:material3` | (BOM-managed) | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.compose.ui:ui-tooling` | (BOM-managed) | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | version-pinned | verified |
| `com.google.mlkit:text-recognition` | 16.0.1 | **Google ML Kit Terms** — not an SPDX id, so it is *not* the same as the Apache-2.0 above. **Bundled-model variant only** | https://developers.google.com/ml-kit | version-pinned | verified coordinate · **⚠️ bundled-model offline use under the Terms is UNVERIFIED, and the runtime behaviour has never been exercised** |
| `com.google.mlkit:barcode-scanning` | 17.3.0 | **Google ML Kit Terms** — as above | https://developers.google.com/ml-kit | version-pinned | as above |

> **The two banned coordinates.** `com.google.android.gms:play-services-mlkit-*` downloads a
> model at runtime and would break invariant I4 and the airplane-install proof (BUILD.md §4).
> They are not in the catalogue and must not be added. "BUNDLED" above is the requirement, and
> it is enforced by the coordinate choice rather than by a check — a check would be better and
> does not exist.
>
> **ML Kit's licence is the one to watch.** It is a Google ToS, not a permissive SPDX licence, and
> a Terms review for bundled offline use is a real open item for anyone shipping this
> (`docs/STATUS.md` R-C means the ToS has never been tested against a built binary).

### Android / Google

| Component | Version | Licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|---|
| Android Gradle Plugin (`com.android.application`, `com.android.library`) | 8.9.2 | Apache-2.0 | https://developer.android.com/studio/releases/gradle-plugin | version-pinned | verified |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.core:core-ktx` | 1.15.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.camera:camera-core` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.camera:camera-camera2` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.camera:camera-lifecycle` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |
| `androidx.camera:camera-view` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx | version-pinned | verified |

### Inference / OCR / static analysis

| Component | Version | Licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|---|
| `org.tensorflow:tensorflow-lite` | 2.17.0 | Apache-2.0 | https://www.tensorflow.org/lite | version-pinned | **verified — but Android-only; see the note below. Not usable on a desktop JVM.** |
| `org.tensorflow:tensorflow-lite-gpu` | 2.17.0 | Apache-2.0 | https://www.tensorflow.org/lite | version-pinned | verified |
| `org.tensorflow:tensorflow-lite-support` | 0.4.4 | Apache-2.0 | https://www.tensorflow.org/lite | version-pinned | verified |
| `ai.djl.tflite:tflite-engine` | 0.27.0 | Apache-2.0 | https://repo1.maven.org/maven2/ai/djl/tflite/tflite-engine/ | version-pinned | verified — republishes the genuine `org.tensorflow.lite.*` Java API. Spike 01 §4 |
| `ai.djl.tflite:tflite-native-cpu` | 2.6.2 · classifiers `linux-x86_64`, `osx-x86_64` | Apache-2.0 | https://repo1.maven.org/maven2/ai/djl/tflite/tflite-native-cpu/ | version-pinned | verified — desktop `libtensorflowlite_jni`. Spike 01 §4 |
| `net.sourceforge.tess4j:tess4j` | 5.13.0 | Apache-2.0 | https://github.com/sirchjai/tess4j | version-pinned | verified |
| detekt (`io.gitlab.arturbosch.detekt`) + `detekt-formatting` | 1.23.8 | Apache-2.0 | https://github.com/detekt/detekt | version-pinned | verified |

> ### `org.tensorflow:tensorflow-lite` is Android-only — this row is now misleading
>
> Measured 2026-09-30, method and raw output in `docs/spikes/01-face-model.md` §4. The `2.17.0`
> artefact on Maven Central is a **1,411-byte relocation stub** whose POM redirects to
> `com.google.ai.edge.litert:litert:1.0.1`, which is `<packaging>aar</packaging>`. Every earlier
> version is AAR-only with **no `.jar` published at all**, and no `<os>` classifier exists for any
> version (linux-x64, windows-x64, macos-x64, macos-arm64 all HTTP 404). The Android `.so` files
> inside the AAR link Bionic and cannot be loaded by a desktop JVM, so they are not a substitute.
>
> **There is no first-party TFLite desktop JVM runtime.** `BUILD.md §4`, which claims
> "natives for win/linux/mac", is wrong. It is owned by another track and was deliberately not
> edited from this task. Desktop inference goes through the two `ai.djl.tflite` rows above, bound
> in `:platform` only, with the 5-line ADR in `platform/build.gradle.kts`.
>
> **Platform coverage is incomplete and a version bump will not fix it.** Upstream has published
> only `linux-x86_64` and `osx-x86_64`, for both of its two versions (2.4.1, 2.6.2).
> `windows-x86_64`, `osx-aarch64` and `osx-arm64` return HTTP 404. Those platforms are
> "review-only, no on-device inference" — the response `BUILD.md §4` itself prescribes. The
> alternative is `com.microsoft.onnxruntime:onnxruntime` (Apache-2.0, 139 MB jar, first-class
> win/linux/osx natives), which needs the model in ONNX and so breaks D1's "same bytes on both
> platforms". Rejected for now, recorded so that it stays a decision rather than an oversight.
>
> **The `tflite-engine` 0.27.0 / `tflite-native-cpu` 2.6.2 pairing is not version-locked by DJL.**
> Nothing in either POM ties them together, the jars are dated 2024-03-28 and 2022-01-12
> respectively, and a one-sided bump is a JNI-ABI break that surfaces as a misleading
> `UnsatisfiedLinkError` on `TensorFlowLite.nativeRuntimeVersion()`. Both versions are pinned in
> `libs.versions.toml` and recorded in this row for that reason. `ai.djl:api` is deliberately *not*
> pulled: `jdeps` shows the `org.tensorflow.lite.*` classes we call reference no `ai.djl` class, and
> real inference was verified with it absent from the classpath.

> **Build-tool gaps, stated plainly.** `ktlint` 12.3.0 is declared as a version
> in `gradle/libs.versions.toml` but **no ktlint plugin or library entry uses
> it**, so `ktlintCheck` is not a Gradle task today. Either wire the plugin
> (with an ADR) or delete the dead version entry (AGENTS.md §5: "dead flags
> older than one milestone"). The detekt plugin is declared at the root with
> `apply false`, so no module creates the `detekt` task either. CI reports both
> as stubs rather than pretending they lint.

---

## 2. System / build-machine dependencies (not shipped)

| Component | Licence | Source | Hash-pin | Provenance | Note |
|---|---|---|---|---|---|
| JDK 17 (Temurin) | GPL-2.0 + Classpath Exception | https://adoptium.net | n/a (toolchain) | verified | BUILD.md §1; CI pins Temurin |
| Gradle (via wrapper) | Apache-2.0 | distribution in `gradle/wrapper/` | **wrapper checksum recorded in the repo — verify it is present** | verified | |
| Tesseract (native library, ≥5.3) + `eng`/`osd` traineddata | Apache-2.0 | https://github.com/tesseract-ocr/tesseract | none | verified | installed per-machine (BUILD.md §1); **not vendored**, so not in the shipped artefact |
| `pdflatex` (TeX Live) + `lmodern` | GPL-2.0+ / GUST Font License (lmodern is LPPL) | https://tug.org/texlive | n/a (toolchain) | verified | used only to build `hardware/calibration_card.pdf`; ships as a PDF, no TeX runtime needed |

---

## 3. Model weights and key material — **THE SHIPPING RISK**

These are the components that actually execute on the device. As of **2026-09-30** the
**detector** row is closed and the **embedder** row is not. Every remaining row is honest about
that. Full method, citations and the decision matrix are in `docs/spikes/01-face-model.md`.

| Component | Expected licence | Source | Hash-pin | Provenance |
|---|---|---|---|---|
| `blazeface_short.tflite` (face detector) | **Apache-2.0 — VERIFIED** on the model's own model card. Redistribution in a repo or a shipped binary is permitted; no rider, no click-through, no per-user registration. | **OBTAINED.** `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite` — 229,746 bytes | **SHA-256 pinned, digest measured by us**: `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` | **verified** |
| `blaze_face_full_range.tflite` (fetched, **not adopted**) | Apache-2.0 | `…/face_detector/blaze_face_full_range/float16/1/…` — 1,083,786 bytes | SHA-256 `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b` | verified (recorded only; **not in the fetch manifest**, nothing downloads it) |
| `emb_v1.tflite` (embedding model) | **UNVERIFIED / NOT OBTAINED.** See below — this is no longer a "we have not got round to it" gap. | not obtained | **none** | **UNVERIFIED — 7 candidates evaluated, all rejected on licence grounds** |
| `emb_v1.sha256` (upstream hash note) | n/a | **does not exist.** Google publishes no `.sha256` sidecar for the BlazeFace model (sidecar URL returns HTTP 404), so our pin is of bytes we measured ourselves. | n/a | n/a |
| `uidai_qr_keys.json` (QR verification key slots) | see below | **see below** | **none** | **UNVERIFIED / NOT OBTAINED** |
| `svm_print_v1_synthetic.json` (macro classifier) | **ours** — code in `eval/tools/`, trained by `eval/tools/train_svm.py` | `eval/tools/synth_macros.py` (in-repo) | **SHA-256 pinned** in `eval/models/manifest.json`: `b72e4bf05971082bb8d0359c4e2c4efcab3bb25e3edde4da0c8ae56431bef2f1`, 13,718 B | **verified — with a mandatory label** |
| `svm_print_v1.json` (the D-MACRO model) | ours | — | — | **DOES NOT EXIST.** No D-MACRO media; `eval/data/macro/manifest.csv` has 0 rows. This is the row the demo would actually use, and it is absent |

> ### `svm_print_v1_synthetic.json` — a licence-clean model that must never be quoted as an accuracy result
>
> It is our own artefact, trained on textures **generated by `eval/tools/synth_macros.py`**, not
> on print substrate. Its training run id is `train-20260929T200155Z-SYNTHETIC-s20260932` and it
> reports macro-F1 0.8469 on its own synthetic report split. **Those numbers are a property of the
> generator, not of printers.** The harness resolves it as `SYNTHETIC FALLBACK`, labels the gate
> `MACRO-CLS` "NOT the D-MACRO gate of SPEC.md §7 and not gate-eligible", and requires zero class
> support in the report split before it will emit a model at all.
>
> It is committed deliberately: its absence is how a demo silently abstains to `UNKNOWN` on
> every patch and looks like a broken feature rather than a missing one. Do not delete it to make
> the directory look clean, and do not let a slide reach for its F1.

### `blazeface_short.tflite` — provenance detail

| Field | Value |
|---|---|
| Licence | **Apache-2.0**, stated on the model's own model card |
| Model card | `https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf` |
| Authors / date | Valentin Bazarevsky, Google. Card dated 2021-06-09 |
| Paper | arXiv:2006.10204 — *BlazeFace: Sub-millisecond Neural Face Detection on Mobile GPUs*, CVPR-W VR 2019 |
| Training data | "consented images of people using a mobile AR application captured with smartphone cameras in various 'in the wild' conditions" |
| Format | TFLite, 128×128×3 input, two outputs — Android **and** desktop from the same file (DESIGN D1) |
| Digest provenance | **Measured, not copied.** `./scripts/fetch_models.sh --record` reproduces `b4578f35…` from the URL above. Google publishes no sidecar, so this is a pin of an artefact we actually fetched. The **versioned** `/1/` path is pinned, never the `/latest/` alias, which can be repointed under us. |
| Fetched by | `./scripts/fetch_models.sh scripts/models_manifest.tsv` → `eval/models/blazeface_short.tflite` (git-ignored, hash-verified before install) |

> **Stated-scope caveat — carries to any release narrative.** The model card lists *"any form of
> surveillance or identity recognition"* under **out-of-scope applications**, and says the model
> "is not intended for human life-critical decisions". Apache-2.0 grants the rights and nothing in
> the licence prohibits our use, but the authors documented the opposite boundary to ours. This is
> a **lead decision**, recorded in `docs/spikes/01-face-model.md` §6.1 and `docs/STATUS.md`. It is
> not a licensing blocker; it is a scope statement the team has to answer for.

### `emb_v1.tflite` — why it is empty, and why that is the correct state

`BUILD.md §6` said "embed-model port (**verify!**)". Verified. The answer is that **no
permissively-licensed, redistributable, 128-d TFLite face-embedding checkpoint could be found,
fetched and hash-pinned.** The reason is uniform across every candidate and is not a reading
problem: the face-recognition checkpoint ecosystem is trained on MS-Celeb-1M, MS1M/MS1MV2/MS1MV3,
VGGFace2 and CASIA-WebFace, all of which are distributed for non-commercial research use.

| Candidate | Why it fails |
|---|---|
| `sirius-ai/MobileFaceNet_TF` | Apache-2.0 **code**, and ships no TFLite file at all (frozen graphdef only). Checkpoints trained on MS1M-refine-v2 / Refined-MS1M / VGGFace2 per its own README — non-commercial data. |
| `DXG-INF/GhostFaceNet` | Repository is **gone**: HTTP 404 on github.com and on `raw.githubusercontent.com` for `main` and `master`. |
| `HamadYA/GhostFaceNets` (the mirror `serengil/deepface` points at) | MIT code, weights are Keras `.h5` (no TFLite), head is `emb_shape=512` (`:core`'s `Embedding.DIM` is 128), trained on MS1MV2/MS1MV3. |
| `deepghs/edgeface` | Repository is **gone**: HTTP 404, both branches, both hosts. |
| MediaPipe Face Embedder | The obvious "Google already solved this" answer — and **the weights are not published.** Anonymous listing of the `mediapipe-models` bucket returns 21 prefixes; there is no `face_embedder/` among them. Five guessed paths all 404. The task is documented at `ai.google.dev`; the weights are not downloadable. |
| `deepinsight/insightface` `buffalo_l` | README, verbatim: *"The training data containing the annotation (and the models trained with these data) are available for non-commercial research purposes only."* And dated 2025-11-24: *"For open-sourced face recognition models (e.g., buffalo_l package), please contact recognition-oss-pack@insightface.ai for licensing."* Not redistributable, and now behind an email. |
| `opencv/opencv_zoo` SFace | Apache-2.0 repo and 128-d, but ONNX, ~37 MB against DESIGN §6's ≤5 MB, and MS1MV2-derived. |

**No weights were fabricated, approximated, synthesised or substituted, and no placeholder
embedding exists anywhere in the codebase.** The consequence is stated plainly: the face layer
can **locate** a face but cannot **compare** one, so `face = null` → face layer `UNAVAILABLE` →
a GREEN 1:1 verdict is unreachable. That is fail-closed by design (FUSION.md §7, AGENTS.md §2
"no silent default-pass"), and it is the correct outcome rather than a defect.

Closing this row is a **permissions decision for the lead**, not a fetching task: obtain a
licence, or train a 128-d embedder on data with clear training rights, or ship detector-only and
say so. Escalated as the top item in `docs/STATUS.md §7` and `docs/spikes/01-face-model.md` §8.

### `uidai_qr_keys.json` — explicit note

> **Provenance: UNVERIFIED. The file does not exist in this repository and its
> source has not been confirmed.**
>
> DESIGN.md §118 lists it as coming from "public impls/docs". That is a note to
> follow up, **not** a source. No URL is recorded here because no source has
> been confirmed by anyone on this team, and inventing one would violate
> AGENTS.md §8 ("never invent model weights/keys/APIs").
>
> Required before any bundling (BUILD.md §6, THREAT_MODEL §6):
> 1. Obtain the key material through a channel the team can actually re-fetch
>    and attribute.
> 2. Record that exact provenance here — source, retrieval date, licence/terms
>    that permit bundling for this use.
> 3. Pin the file's SHA-256 in `scripts/bundle_manifest.sample.txt`.
> 4. Wire the rotation story (THREAT_MODEL §6: expired key ⇒ QR layer goes
>    **AMBER + "keys stale"**, fail-open loudly to a human, never a silent pass
>    and never a silent RED).
>
> **Until 1–4 are done, the signed-QR layer cannot be claimed as working.**
> `SecureQr` in `:core` is test-key logic (DATA.md §7: "TEST keypair signs
> valid payloads; prod-key slots stay EMPTY").

---

## 4. Fonts, print masters and calibration card

| Component | Licence | Hash-pin | Provenance |
|---|---|---|---|
| `hardware/calibration_card.pdf` (our own work, built from `hardware/calibration_card.tex`) | ours | in-repo | verified |
| Latin Modern (`lmodern`, TeX Live) — used to typeset the card | GUST Font License / LPPL — redistributable with conditions | n/a | verified |
| An **OCR-B-metric font** for the SPECIMEN MRZ band | **UNVERIFIED** — not yet chosen. It must be licensed for print **and** redistribution, or the artwork cannot live in a public repo. | none | **UNVERIFIED** |
| SPECIMEN card artwork itself | not produced — see `hardware/print_targets.md` | n/a | n/a |

---

## 5. Known gaps in this register

These are open, and they are the reason this file says "review before release"
rather than "done":

1. **No Gradle dependency verification.** BUILD.md §2 asks for
   `gradle/verification-metadata.xml` with `verify-metadata` on. **Confirmed absent**
   (re-checked 2026-09-29). So a swapped transitive artefact would not be caught — this is
   the AT-12 supply-chain residual, and it is the cheapest item on this list. Enable it
   (`./gradlew --write-verification-metadata sha256 ...`) as a dedicated change, with the
   hash file reviewed.
2. **Transitive dependencies are not itemised.** The table above lists only
   what `libs.versions.toml` names directly. A full register needs the resolved
   graph (a `dependencies` report per configuration) checked into `eval/runs/`
   at a known commit. **This interacts with item 1**: until the graph is
   enumerated, there is nothing for verification metadata to pin either.
3. **No `LICENSE` file at the repository root.** `docs/README.md` states the
   intended terms ("prototype for SIH demonstration and research evaluation")
   but there is no file. A repo with third-party Apache-2.0 code and no licence
   of its own is a real problem for anyone who forks it. Decision needed.
4. **Unused catalogue entries.** `org.jetbrains.compose` and its Compose compiler
   plugin alias are declared but applied by no module, and `tflite-gpu` /
   `tflite-support` are declared while the shipping path is CPU/XNNPACK. Carrying
   unreviewed, unbuilt dependencies is what AGENTS.md §5's "dead flags older than
   one milestone" is about. Either use them or delete the rows.
5. **Two licence states are unverified for a reason that will not go away by
   reading harder.** `emb_v1.tflite` has no permissively-licensed candidate (§3),
   and ML Kit's Terms for bundled offline use have never been checked against a
   built binary because there is no binary. **No release build is defensible
   until both are resolved**, and the first is a permissions decision, not a
   research task.
6. **Nothing Android has been compiled.** Every `:app-android` row above
   (Compose, ML Kit, CameraX) is a correctly-stated coordinate whose licence is
   as written — but no artefact has been built from them, no APK size has been
   measured, and ML Kit's ToS has not been exercised in a shipped binary.
   `docs/STATUS.md` R-C.

---

## 6. How to add a row

Copy the shape below into the right table, fill every column, and put the ADR
link in the PR description (AGENTS.md §5: what / why / size / licence /
alternative).

```
| `group:artifact` | x.y.z | <SPDX id> | <re-fetchable URL> | version-pinned | verified |
```

If you cannot fill **licence** and **source** truthfully, the row is `UNVERIFIED`
and the component does not ship. That is the intended outcome, not a failure of
the register.
