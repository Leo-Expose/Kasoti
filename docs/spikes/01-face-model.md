# Spike 01 — Face model selection: which weights, and can we run them on the desktop at all?

- **Status:** DECIDED — including the embedder licensing decision (§7 item 5) and a second, post-decision sourcing round that obtained nothing (§7 item 6)
- **Date:** 2026-09-30 (licensing decision and second sourcing round added the same day, by the project lead)
- **Owner:** project lead (solo-maintained, `HANDOFF.md` §2; there is no face/vision team to own it, and §7's "leads pick" resolves to the single maintainer)
- **Sanctioned by:** DESIGN.md §6 ("M0 model spike (timeboxed 2 days, decision matrix in EVAL.md)"), AGENTS.md §7
- **Answers:** "There is currently no face detection/embedding model at all, so the face layer reports `UNAVAILABLE`." → **now half-answered.** The detector is obtained, hash-pinned and running. The embedder licence is **decided**; the embedder itself is still unobtainable after two search rounds, and no weights were ever fabricated.
- **Timebox used:** 1 day of the 2 allowed.

---

## 1. Question

DESIGN.md §6 names three embedding candidates and one detector, and asks for a score on
`license-ok × dual-format(TFLite both) × LFW-subset acc × on-device latency × size`
(EVAL.md §5 adds `alignment simplicity`). Two questions, in order:

1. **Weights.** Which embedding checkpoint and which detector can we *actually obtain*, under a
   licence that permits redistribution in a repo and a shipped binary?
2. **Runtime.** DESIGN D1 says "TFLite everywhere, same bytes, hash-pinned", and BUILD.md §4
   says the desktop uses "TFLite Java API (`tensorflow-lite` + `tensorflow-lite-support` + natives
   for win/linux/mac)". Does that runtime exist for a desktop JVM?

Question 2 turned out to be the harder one, and it is the reason this spike is longer than the
weights question.

**A note on what this spike is not.** It is not a bake-off of three models we all fetched and
timed. Two of the three named candidates no longer exist at their stated source, and the third's
training data is not permissively licensed. The honest output is one obtained artefact, five
documented rejections, and one hard platform finding. AGENTS.md §8 forbids inventing model
weights, keys or APIs, and this spike is the sanctioned place to record a rejection
(AGENTS.md §7: "Undecided at timebox → leads pick; document why").

---

## 2. Options considered

### 2.1 Embedding (the `emb_v1.tflite` slot)

| ID | Candidate | Source checked | Verdict |
|---|---|---|---|
| **A** | MobileFaceNet-TFLite port (DESIGN §6 candidate A) | `github.com/sirius-ai/MobileFaceNet_TF` | **REJECTED — training data not permissively licensed, and no TFLite artefact** |
| **B** | GhostFaceNet (candidate B) | `github.com/DXG-INF/GhostFaceNet` | **REJECTED — repository gone (HTTP 404)** |
| **B′** | GhostFaceNet, alternate mirror | `github.com/HamadYA/GhostFaceNets` | **REJECTED — MIT code, but weights are `.h5` (not TFLite), 512-d (not 128-d), trained on MS1MV2/3** |
| **C** | EdgeFace-XS (candidate C) | `github.com/deepghs/edgeface` | **REJECTED — repository gone (HTTP 404)** |
| **D** | MediaPipe Face Embedder (128-d, TFLite, Apache-2.0, the obvious "Google already solved this" answer) | `storage.googleapis.com/mediapipe-models/face_embedder/…` | **REJECTED — never published.** Anonymous listing of the `mediapipe-models` bucket has no `face_embedder/` prefix; 21 prefixes exist and it is not one of them. Five guessed paths under that bucket all 404. |
| **E** | InsightFace `buffalo_l` (ArcFace, the most capable widely-used embedder) | `github.com/deepinsight/insightface` | **REJECTED — explicitly non-commercial, and licensing now requires an email** |
| **F** | OpenCV Zoo SFace (Apache-2.0 repo, 128-d) | `github.com/opencv/openv_zoo` | **REJECTED — ONNX only (≈37 MB), not TFLite; training data is MS1MV2** |

**A second, focused sourcing round (2026-09-30, after the licensing decision in §7 item 5;
the decision itself is §7 item 5 and the round's outcome is §7 item 6).**
Run because the decision made a research-licensed embedder *acceptable for the prototype*, which
reopens the question — the objection to InsightFace's `buffalo_l` was its licence, and a
research-only licence is now in scope. Four candidates not on the list above were checked for the
two things that decide it: the **actual licence text**, and whether a **TFLite conversion is
possible**. All four fail.

| ID | Candidate | Source checked | Verdict |
|---|---|---|---|
| **G** | PINTO0309 model zoo `290_AdaFace` (AdaFace ir18/ir50/ir101 → TFLite) | `github.com/PINTO0309/PINTO_model_zoo/290_AdaFace` | **REJECTED** |
| **H** | ONNX Model Zoo ArcFace ResNet-100 | `github.com/onnx/models/vision/body_analysis/arcface` | **REJECTED** |
| **I** | `estebanuri/face_recognition` — a real `facenet.tflite` | `github.com/estebanuri/face_recognition` | **REJECTED** |
| **D′** | MediaPipe Face Embedder, re-probed | `storage.googleapis.com/mediapipe-models` | **REJECTED — still unpublished** |

**G — detail, and the closest call.** This is the one that looked like a hit, and it is worth
recording *why* it is not, because the failure is the same failure as candidate A wearing different
clothes. PINTO's zoo is genuinely MIT-licensed and actively maintained, and the per-model `LICENSE`
in `290_AdaFace/` says **MIT, Copyright (c) 2022 Minchul Kim** — the AdaFace author himself. A
TFLite conversion is *possible*: PINTO ships `convert_script.txt` and the OpenVINO→TF path, and the
`url.txt` points at OpenVINO's open model zoo. Three things stop it:

1. **The MIT covers a conversion, not the weights.** The same README says, verbatim: *"My model
   conversion scripts are released under the MIT license, but the license of the source model
   itself is subject to the license of the provider repository."* A permissively-licensed *file*
   trained on non-permissively-licensed *data* is not a permissively licensed model — candidate A's
   argument, and it does not stop applying because the file was converted by someone else.
2. **The upstream is gone.** `github.com/minchul/cvlface_adaface` now returns **404 / Not Found**
   (both `main` and `master`; the GitHub API reports no default branch), and
   `openvinotoolkit/open_model_zoo/2021.4.1/models/public/face-recognition-adaface-ir-101-webface12k/`
   **404s**. So the authority for the weights' terms is a page that no longer exists, which is the
   same "unmaintained model" problem that killed B and C.
3. **The dimension is wrong anyway.** AdaFace ir101 emits **512-d**, and its WebFace12k training
   data is derived from WebFace260M/MS1MV3 — non-commercial research.

**H — detail.** `onnx/models` is Apache-2.0 and PINTO's per-model `LICENSE` in
`175_face-recognition-resnet100-arcface-onnx/` reproduces the Apache-2.0 text. A TFLite conversion
is possible and PINTO provides the script. But the **path itself is dead**:
`onnx/models/vision/body_analysis/arcface` is `404 Not Found` on `main` and `master` reports
*"No commit found for the ref"*. It is **512-d**, it is ONNX (~37 MB against DESIGN §6's "≤5 MB"),
and it is MS1MV2-derived. Even a clean re-probe of a live Apache-2.0 path would not have given a
128-d TFLite file.

**I — detail, and the only genuine TFLite artefact found in the whole search.** This repository
really does ship `android/models/facenet.tflite` (**23,639,848 B** = 22.5 MB) and
`facenet_hiroki.tflite` (22,950,240 B), and it is a real Android+TFLite MobileFaceNet/FaceNet
implementation. It fails on three independent counts:

1. **There is no licence.** The GitHub API reports no licence, and `LICENSE`, `LICENSE.md`,
   `LICENSE.txt`, `COPYING` and `license` **all return HTTP 404**. Absent a licence, default
   copyright applies: all rights reserved. This is worse than a restrictive licence, because there
   is nothing to comply with and nothing to point a judge at.
2. **22.5 MB is 4.5× the DESIGN §6 budget** of ≤5 MB, and it counts against the 35 MB APK gate
   (NFR-S1) alongside ML Kit's ~10 MB.
3. It is VGGFace2-derived, and the README links a Medium article rather than a licence or a model
   card. FaceNet/VGGFace2 is non-commercial-research data.

**D′ — detail, re-probed on 2026-09-30 because this is the candidate most likely to have appeared
in the meantime.** Anonymous listing of the `mediapipe-models` bucket still returns **exactly 21
prefixes** and `face_embedder` is **not** one of them. The listing is the interesting part:
`audio_embedder`, `image_embedder` and `text_embedder` are all published, and there is no
`face_embedder`. Google ships embedders for audio, images and text and has not shipped one for
faces. Five guessed paths under that bucket 404.

**What the second round changes, and what it does not.** It does not change the headline result:
**no 128-d, TFLite, licence-compatible face-embedding artefact is obtainable.** What it does
change is the *shape* of the evidence. The first round established that the obvious candidates are
closed. This one establishes that the long tail is closed too, and that the two repositories the
ecosystem was pointing at a year ago (`minchul/cvlface_adaface`, `onnx/models/…/arcface`) are now
dead — which turns "we looked and could not find one" into "the upstream references have rotted
while we were looking", and is the reason option (a) in §8 is now the only route with a cost
attached rather than a fetch.

**Nothing was fabricated, approximated or synthesised.** No weights were created, no weights were
converted, and no placeholder vector is produced anywhere in `:core` or `:platform`.

**A — detail.** `sirius-ai/MobileFaceNet_TF` is genuinely Apache-2.0 (LICENSE fetched and read,
`master` branch). It is the only candidate whose *code* licence checks out cleanly. It is still
rejected, for two independent reasons. First, it ships **no TFLite file** — only a frozen
`arch/pretrained_model/` graphdef — so adopting it means *we* would produce the artefact, and
AGENTS.md §8 is about not passing off something we made as something we obtained. Second, and
decisively, its own README says the checkpoints were trained on **MS1M-refine-v2 / Refined-MS1M /
VGGFace2 / InsightFace datasets**. Those are research datasets with non-commercial
redistribution terms. A permissively-licensed *file* trained on non-permissively-licensed *data*
is not a permissively licensed model, and this project will not be the one to discover that
distinction in front of a judge.

**B/B′ — detail.** `DXG-INF/GhostFaceNet` (the repository the GhostFaceNet paper's artefacts
were published from) returns HTTP 404 on github.com and 404 on `raw.githubusercontent.com` for both
`main` and `master`. A dead upstream is an unmaintained model, which AGENTS.md §5's spirit and
BUILD.md §6 both rule out. The surviving mirror `HamadYA/GhostFaceNets` is MIT and is the repo
`serengil/deepface` itself points at for GhostFaceNet, but: the weights are Keras `.h5` releases
(again a conversion we would perform, not an artefact we obtained); the default head is
`emb_shape=512`, so it cannot feed `:core`'s `Embedding.DIM = 128` without a projection layer we
would have to train; and the accuracy table in its README is explicitly **MS1MV2/MS1MV3**.

**C — detail.** `deepghs/edgeface` also returns HTTP 404 on github.com and on `raw.githubusercontent.com`
for both branches. Same verdict as B: no fetchable artefact, so nothing to hash-pin.

**D — detail.** This is the rejection that most deserves to be written down, because it is the
one where a plausible answer exists. Google publishes MediaPipe face *detection*,
*landmarker* and *stylizer* weights under `mediapipe-models`, all Apache-2.0, all TFLite, all
with published model cards — and a face *embedder* was shipped in the Tasks library and is
documented at `ai.google.dev/edge/mediapipe/solutions/vision/face_embedder`. But the weights are
not in the bucket. The bucket's 21 top-level prefixes are `audio_classifier`, `audio_embedder`,
`face_detector`, `face_landmarker`, `face_stylizer`, `gesture_recognizer`, `hand_landmarker`,
`holistic_landmarker`, `image_classifier`, `image_embedder`, `image_generator`,
`image_segmenter`, `interactive_segmenter`, `interactive_segmenter_v2`, `language_detector`,
`object_detector`, `pose_landmarker`, `text_classifier`, `text_embedder`, `text_proofreader`,
`text_summarizer`. There is no `face_embedder`. Google documents the task; Google does not
publish the weights.

**E — detail.** From `deepinsight/insightface`'s own README, quoted: *"The training data
containing the annotation (and the models trained with these data) are available for
non-commercial research purposes only. Both manual-downloading models from our github repo and
auto-downloading models with our python-library follow the above license policy."* And, dated
2025-11-24: *"For open-sourced face recognition models (e.g., buffalo_l package), please contact
recognition-oss-pack@insightface.ai for licensing."* So the best-known embedder in the ecosystem
is not merely non-redistributable, it is now behind a sales email. This is not a licence
ambiguity we can resolve with a favourable reading; it is a closed door.

**F — detail.** `opencv/opencv_zoo` is Apache-2.0 and its SFace model is 128-d, which is
dimensionally perfect. It is ONNX, and roughly 37 MB, against DESIGN §6's "≤5 MB" and the
APK budget. Its weights are MS1MV2-derived. Rejected on all three counts.

**The pattern, stated plainly.** Every candidate fails for the *same* underlying reason, and it
is not a licensing oversight that careful reading fixes: **the face-recognition checkpoint
ecosystem is trained on MS-Celeb-1M, MS1M/MS1MV2/MS1MV3, VGGFace2 and CASIA-WebFace, and every
one of those datasets is distributed for non-commercial research use.** The dominant publisher
has confirmed this in writing and moved its weights behind a licence request. There is no
permissively-licensed, redistributable, 128-d face-embedding checkpoint that we can find,
fetch, hash-pin and ship.

That is the single most important output of this spike, and it is a licensing-and-consent
result, not a software result. It is the reason §6 below is a legal/permissions question for
the lead rather than a fetch task for an engineer.

### 2.2 Detector (the `blazeface_short.tflite` slot)

DESIGN §6 and BUILD.md §4 both name BlazeFace-short, and unlike the embedder it is exactly
where the design says it is, in the format the design requires, under a licence that permits
what we need. It is adopted. Details in §3.

### 2.3 Runtime (not named as a candidate, and the one that was actually broken)

BUILD.md §4's claim that the desktop can use "TFLite Java API (`tensorflow-lite` +
`tensorflow-lite-support` + natives for win/linux/mac)" is **not true for any OS**. See §4.

---

## 3. Adopted artefact: `blazeface_short.tflite`

| Field | Value |
|---|---|
| **Name** | `blazeface_short.tflite` |
| **Source URL** | `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite` |
| **Size** | 229,746 bytes (224 KB — matches the model card's "224KB" and DESIGN §6's "~230 KB") |
| **SHA-256** | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` |
| **Licence** | **Apache-2.0**, stated on the model's own model card |
| **Model card** | `https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf` |
| **Authors / date** | Valentin Bazarevsky, Google (card authors Yury Kartynnik, Artsiom Ablavatski); card dated 2021-06-09 |
| **Paper** | Bazarevsky et al., *BlazeFace: Sub-millisecond Neural Face Detection on Mobile GPUs*, CVPR-W VR 2019 — arXiv:2006.10204 |
| **Training data** | "consented images of people using a mobile AR application captured with smartphone cameras in various 'in the wild' conditions" |
| **Redistribution** | **Permitted.** Apache-2.0, no model-specific rider, no click-through, no per-user registration. |
| **Provenance caveat** | Google publishes **no `.sha256` sidecar** (verified: the `.sha256` URL 404s). The pin above was *measured by us* on 2026-09-30 from the bytes at the URL above, and it is a pin of an observed artefact, not a pin copied from a publisher's manifest. |

**Byte-stability check.** Google's docs point at the `…/float16/latest/…` path, which is a moving
alias. We pinned the versioned `/1/` path. The two were byte-identical when checked
(`cmp` clean, same SHA-256), but **only the versioned path is pinned**, because `latest` can be
repointed under us and a pin against a moving alias is not a pin (the same argument as
`ModelLoader`'s, and RT-E8).

**Also fetched, not adopted:** `blaze_face_full_range` float16/1, 1,083,786 bytes, SHA-256
`3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`. Recorded in
`THIRD_PARTY.md` so the 4.7× size cost is a known quantity if short-range ever turns out to
miss at long range. Not in the fetch manifest; nothing downloads it.

### 3.1 Model interface, read out of the model itself

Not from documentation or memory — parsed out of the TFLite flatbuffer:

| Property | Value | How it was established |
|---|---|---|
| Input | `input`, FLOAT32, `[1, 128, 128, 3]` | tensor record in the model file |
| Input quantisation | **none** — the tensor is unquantised float | no `QuantizationParameters` on the input tensor |
| Input pre-processing | **none inside the graph.** The first op that consumes `input` is `op[2] CONV_2D`. `op[0]` and `op[1]` are `DEQUANTIZE` of float16 *weights* (tensors 2 and 1), not of the image. | operator list walked in graph order |
| Outputs | `regressors` FLOAT32 `[1, 896, 16]`, `classificators` FLOAT32 `[1, 896, 1]` | tensor records |
| Opcodes present | `ADD, CONCATENATION, CONV_2D, DEPTHWISE_CONV_2D, DEQUANTIZE, MAX_POOL_2D, PAD, RELU, RESHAPE` | operator-code table |
| **`SOFTMAX` is absent** | → the `classificators` tensor is a *logit*. The confidence score needs a sigmoid applied by the caller, **not** by the model. | operator-code table |

Two consequences worth stating, because both are the kind of thing that gets silently wrong:

- **896 anchors × 16 floats.** The 16 is `4 box (x,y,w,h) + 2 keypoint scores + 10 keypoint
  coordinates (5 points × xy)`. Note the **5** keypoints, not the **6** the model card
  describes: the card documents the *Tasks-library* output, which synthesises a sixth point.
  The raw TFLite gives five (right eye, left eye, nose, mouth-right, mouth-left).
  `TfliteFaceDetector` in `app-android` already uses `LANDMARK_COUNT = 5` and is correct.
- **The score is a logit.** Applying a softmax to it (or reading it as a probability) would
  flatten a confident detection to ≈0.5. It is a sigmoid input.

### 3.2 Input range — settled partially, honestly

Because the graph does not normalise, the caller must, and the range is a real fork:

| Input encoding | `regressors` magnitude (synthetic probe, `absmax`) | Verdict |
|---|---|---|
| `0..255` | **4,248,223** | **Wrong, proven.** ~4 orders of magnitude out. The network is being driven far outside its training distribution. |
| `0..1` | 160.9 | Plausible. |
| `-1..1` | 166.0 | Plausible, and this is what the model card specifies. |

So `0..255` is *excluded by measurement*. The remaining fork — `[0,1]` versus `[-1,1]` — is a
one-unit DC offset per channel, and it cannot be separated without an image the detector
actually fires on. We have no consented face image (`D-FACE` is empty; see §6).

**Decision:** encode the model card's stated contract, `[-1.0, 1.0]`, as a single named
constant in `BlazeFaceContract`, cited to the model card. Do **not** present this as verified.
`BlazeFaceContractTest` asserts the structural facts that *are* verifiable (shape, dtype, no
input quantisation, 896 anchors, 16 floats, no in-graph `SOFTMAX`), and a fixture-gated test
that would settle `[0,1]` vs `[-1,1]` is present but **skipped with a stated reason** until a
consented crop exists. Both encodings differ only by a constant, so a wrong pick costs
accuracy, not correctness — it cannot make a wrong face match a right one, because the
comparability law (DESIGN §3) pins the *same* transform on both platforms.

---

## 4. Runtime finding: there is no TFLite JVM artefact, and BUILD.md §4 is wrong

BUILD.md §4's troubleshooting row already anticipated a missing native for *one* OS. The
reality is worse and worth recording in full.

| Check | Result |
|---|---|
| `org.tensorflow:tensorflow-lite:2.17.0` on Maven Central | **A 1,411-byte relocation stub**, not a jar. Its POM contains `<relocation>` → `com.google.ai.edge.litert:litert:1.0.1`. |
| `com.google.ai.edge.litert:litert:1.0.1` (Google Maven) | `<packaging>aar</packaging>`. Android. |
| `org.tensorflow:tensorflow-lite:2.16.1` and earlier | `<packaging>aar</packaging>`, and **no `.jar` is published at all**. |
| `tensorflow-lite-2.17.0-<os>.jar` for linux-x64 / windows-x64 / macos-x64 / macos-arm64 | **404 for all four.** |
| `…-linux-x64.jar` … `-win-x64.jar` … `-osx-x64.jar` … `-osx-arm64.jar` for `com.microsoft.onnxruntime:onnxruntime:1.22.0` | also 404 (wrong classifier names) — the *main* `onnxruntime` jar is 139,129,141 B at 1.22.0 (measured 2026-09-30) and does bundle desktop natives, so ORT-JVM is a real alternative, just not this one |

So: **TFLite is Android-only on Maven.** There is no first-party TFLite desktop JVM runtime to
depend on. The Android `.so` files inside the AAR cannot be substituted on a desktop JVM
either — they link Bionic, not glibc. Re-probed 2026-09-30, with the Bionic claim reduced to
bytes: the 2.17.0 "jar" is **1,411 bytes of ASCII that is a verbatim copy of its own POM**
(`file` reports ASCII text; `sha256 bfb9a2e2…`), `2.16.1`/`2.15.0`/`2.14.0`/`2.13.0` are all
`<packaging>aar</packaging>` with **no `.jar` published at all**, and every plausible `<os>`
classifier 404s (`linux-x64`, `windows-x64`, `macos-x64`, `macos-arm64`, `linux-x86_64`,
`osx-x86_64`, `linux-aarch64`). For the Bionic half, `readelf -d` on the natives inside the real
AAR (`com.google.ai.edge.litert:litert:1.0.1` → `litert-1.0.1.aar`, 6,487,444 B,
`jni/{arm64-v8a,armeabi-v7a,x86_64,x86}/libtensorflowlite_jni.so`) gives
`NEEDED libc.so, liblog.so, libdl.so, libm.so` and **no `libc.so.6` and no
`ld-linux-x86-64.so.2`** — the unversioned sonames are Bionic's, and `file` reports each as "for
Android 21, built by NDK r25b". A desktop glibc JVM resolves the versioned names and cannot load
them. The same `readelf` on the `ai.djl` desktop native gives the opposite answer
(`libstdc++.so.6`, `libc.so.6`, `ld-linux-x86-64.so.2`), which is the difference between the two
rows of the coverage table below.

**Adopted workaround, with its costs stated:** `ai.djl.tflite`, Apache-2.0, which
republishes the genuine TFLite Java API plus desktop JNI natives.

| Component | Version | What it gives | Notes |
|---|---|---|---|
| `ai.djl.tflite:tflite-engine` | 0.27.0 | `org.tensorflow.lite.Interpreter`, `NativeInterpreterWrapper`, `Tensor`, `TensorFlowLite`, `DataType` — the real TFLite Java API, same package and class names as the Android AAR | Apache-2.0. 28 classes. `jdeps` shows `org.tensorflow.lite.*` references **no** `ai.djl` class (dependency flows one way), so `ai.djl:api` is declared but not required at runtime. Verified by running inference with it absent from the classpath. |
| `ai.djl.tflite:tflite-native-cpu` | 2.6.2, classifier `linux-x86_64` | `native/lib/libtensorflowlite_jni.so` (3,382,784 B) | Apache-2.0. `tflite.properties` → `version=2.6.2-20220112` |
| `ai.djl.tflite:tflite-native-cpu` | 2.6.2, classifier `osx-x86_64` | `native/lib/libtensorflowlite_jni.dylib` (7,073,184 B) | Apache-2.0 |

**Coverage — this is where BUILD.md §4's tripwire fires, and it fires twice:**

| Target | Desktop TFLite native | Consequence |
|---|---|---|
| linux-x86_64 | ✅ available | inference works |
| macos-x86_64 (Intel) | ✅ available | inference works |
| **windows-x86_64** | ❌ **404** | **review-only, no on-device inference** |
| **macos-aarch64 (Apple Silicon)** | ❌ **404** | **review-only, no on-device inference** |
| *linux-aarch64, osx-arm64* | ❌ **404** | *review-only, no on-device inference* |

Only two native versions exist (`2.4.1`, `2.6.2`) and both publish only those two classifiers.
This is not a resolvable gap in this task. BUILD.md §4's prescribed response — that OS "ships
'review-only, no on-device inference' clearly labeled" — is what `TfliteRuntime.availability()`
now reports, and §6 of this document flags it as a lead decision.

**Three traps in this binding, all found by running it, all documented in the source:**

1. **The native library must be loaded explicitly.** `TensorFlowLite`'s static initialiser
   swallows the `System.loadLibrary("tensorflowlite_jni")` failure and then `init()` throws
   `UnsatisfiedLinkError: 'java.lang.String org.tensorflow.lite.TensorFlowLite.nativeRuntimeVersion()'`.
   The symbol is *present* in the `.so` — nothing is missing, the library simply was never
   loaded. `TfliteRuntime` calls `System.load()` on the path it extracted and reports failure as
   `available = false`. Anyone reimplementing this will otherwise lose a day to a misleading error.
2. **The only public multi-output entry point is
   `runForMultipleInputsOutputs(Object[], Map<Integer, Object>)`.** The two-argument
   `run(Object, Object)` picks its *output* accessor from the *input*'s type, so passing a
   `Map` of outputs alongside a `ByteBuffer` input fails with
   `DataType error: cannot resolve DataType of java.util.HashMap`, and passing a single
   `float[][][]` silently writes output 0 into it and then fails on output 1's shape. BlazeFace
   has two outputs, so the multi-output path is not optional.
3. **The Java/native pairing is not version-locked by DJL.** The engine jar is dated 2024-03-28
   and the native is 2022-01-12. They work together — verified by real inference on the real
   model, §5 — but nothing in the POMs ties them together, so a future version bump of one side
   is a JNI-ABI break that manifests as `UnsatisfiedLinkError`. Both pins are in
   `libs.versions.toml` and both go in `THIRD_PARTY.md` for that reason.

---

## 5. Method and measurements

### 5.1 What was actually done

1. Resolved every candidate's repository/URL over the network; recorded HTTP status and
   licence text **from the source**, not from memory. `github.com` and
   `raw.githubusercontent.com` were both checked for each repository and both `main` and
   `master` where relevant, because "the repo is gone" is a claim that needs two probes.
2. Listed the `mediapipe-models` GCS bucket anonymously to establish, positively, that no
   `face_embedder/` prefix exists.
3. Downloaded `blaze_face_short_range` float16/1, hashed it, parsed its TFLite flatbuffer with a
   purpose-built reader (operator codes in graph order, tensor records, quantisation
   parameters) to establish the interface from the artefact.
4. Built a throwaway JVM probe against the real model with the real runtime, to establish the
   input range by measurement and to time it.
5. Ran the whole thing through Gradle in `:platform` afterwards. The probe was scaffolding and
   is not in the repo.

### 5.2 Latency

| Measurement | Value | Provenance |
|---|---|---|
| Google, CPU latency, Pixel 6 | **2.94 ms** | `ai.google.dev/edge/mediapipe/solutions/vision/face_detector`, "Task benchmarks", last updated 2026-08-17 |
| Google, GPU latency, Pixel 6 | 7.41 ms | same |
| Google, model card | ~275 FPS on Pixel 2 single-core CPU with XNNPACK | model card |
| **Measured here, 4 threads, synthetic 128×128 input** | **p50 ≈ 10.8 ms, p95 ≈ 20.1 ms** | our probe, on the dev box, not a phone |

The measured figure is from a desktop x86-64 dev machine and **must not be quoted as a device
number** (EVAL.md §4: "per-stage median/p95 on NAMED devices (build fingerprint recorded)";
EVAL.md §8: no cherry-picked devices). It is recorded here only to show the runtime is
functioning and to size the desktop path. **NFR-P1's "<400 ms detect + embed" has not been
measured on the named low-end device and is not claimed.**

The detector's real cost is a small fraction of that budget; the missing embedder is not, which
is why §6 item 1 is the top item.

### 5.3 Scorecard (EVAL.md §5: 0–2 each, highest total wins, ties → smaller)

Axes: `license-ok · TFLite-both · LFW-sub sanity · on-device p50 · size · alignment simplicity`.
Cells that cannot be scored are marked `n/a` rather than guessed; a candidate with an `n/a` on
`license-ok` is disqualified regardless of its total.

**Embedding (`emb_v1.tflite`)**

| | A MobileFaceNet | B GhostFaceNet | B′ GhostFaceNet mirror | C EdgeFace-XS | D MediaPipe embedder | E buffalo_l | F SFace |
|---|---|---|---|---|---|---|---|
| license-ok | **0** — Apache-2.0 code, non-commercial training data (MS1M/VGGFace2) | **0** — upstream 404 | **0** — MIT code, MS1MV2/3 weights | **0** — upstream 404 | **0** — no weights published to fetch | **0** — "non-commercial research purposes only"; licensing by email since 2025-11-24 | **0** — Apache-2.0 repo, MS1MV2 weights |
| TFLite-both | **0** — no TFLite file | **0** — n/a, no artefact | **0** — `.h5` | **0** — n/a | **0** — n/a | **0** — ONNX | **0** — ONNX |
| LFW-sub sanity | 0 — cannot measure, cannot ship | 0 | 0 — paper/MS1MV numbers are not our measurement | 0 | 0 | 0 | 0 |
| on-device p50 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| size | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| alignment simplicity | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total** | **0** | **0** | **0** | **0** | **0** | **0** | **0** |
| **Verdict** | REJECT | REJECT | REJECT | REJECT | REJECT | REJECT | REJECT |

**Detector (`blazeface_short.tflite`)**

| Axis | BlazeFace short-range | BlazeFace full-range | YuNet (opencv_zoo) | MediaPipe/YuNet-via-MLKit fallback (DESIGN §3) |
|---|---|---|---|---|
| license-ok | **2** — Apache-2.0 on the model card, redistribution expressly fine, consented training data | **2** | **2** | **2** |
| TFLite-both | **2** — TFLite, runs on both via the runtime in §4 | **2** | **0** — ONNX | **0** — a Java API, not a TFLite file, so Android and desktop would be running *different detectors* |
| LFW-sub sanity | **1** — published per-slice recall/AP (§6.2), but not measured by us and not on our data | 1 | 1 | 1 |
| on-device p50 | **2** — 2.94 ms CPU on Pixel 6 (Google) | 2 | 2 | 1 |
| size | **2** — 224 KB, ~0.7% of the 35 MB APK gate | **0** — 1,083,786 B (4.7×) | 1 | 2 |
| alignment simplicity | **2** — 5 keypoints, symmetric, directly fittable; NMS is 40 lines of Kotlin (D4) | 2 | 1 | 2 |
| **Total** | **11** | **9** | **7** | **9** |

**BlazeFace short-range wins at 11/12.** It is also the artefact DESIGN §6 and BUILD.md §4
already named, so adopting it changes no design decision — which is the ideal outcome for a
spike, and is recorded as such rather than dressed up as a discovery.

---

## 6. What this model is and is not good for

This section exists because AGENTS.md §2 requires a per-bucket bias story, THREAT_MODEL AT-14
(bias harm) depends on it being *known* rather than discovered by a judge, and EVAL.md §4
requires per-bucket reporting. All numbers below are **from Google's model card** and are
labelled as such; none is our measurement, and EVAL.md §1 requires any metric that leaves our
harness to cite a run id, which these cannot.

### 6.1 The stated-scope mismatch, stated first because it is the most serious item here

The model card, under "Out-of-scope applications", verbatim:

> Any form of surveillance or identity recognition is explicitly out of scope and not enabled
> by this technology.

We are building identity screening. Apache-2.0 grants the rights, and nothing in the licence
prohibits this use — but the model authors have documented the opposite of our use case as their
intended boundary. Under a 224 KB model file that is the difference between "we chose a
component" and "we adopted a component that says it is not for this." Any judge, and any
deployment partner, is entitled to that fact. It is recorded in `docs/STATUS.md` and must
appear in the release narrative.

This is **not** a reason to reject BlazeFace — the alternative is no detector at all, and
DESIGN §6 chose it. It is a reason the lead must make a deliberate decision rather than
inheriting a silent one, and a reason the detector is scoped to *finding* a face with the
subject's knowledge, not to any autonomous identification.

The card also says the model "is not intended for human life-critical decisions" and that the
primary intended application is "entertainment and assistive technologies". A border-screening
verdict is arguably life-critical for the person being screened. Recorded, not resolved.

### 6.2 Documented per-slice performance (Google's model card, not ours)

Recall:

| Slice | Recall | Spread |
|---|---|---|
| Perceived gender | 98.4% | 98.2% (feminine) vs 98.5% (masculine) — **0.3 pp gap** |
| Skin tone (5 groups, Fitzpatrick I+II collapsed) | 98.1% | **94.7% – 100% — 5.3 pp gap** |
| Geographic subregion (17 UN geoscheme regions, EU excluded from "Europe") | 99.1% | **92.5% – 100% — 7.5 pp gap** |

Average precision:

| Slice | AP | Spread |
|---|---|---|
| Perceived gender | 99.9% | 99.7% – 100% |
| Skin tone | 99.7% | 98.6% – 100% |
| Geographic subregion | 95.1% | **94.9% – 95.2%** |

Read plainly, and without softening: **the skin-tone spread (5.3 pp) and the subregion spread
(7.5 pp) are the numbers that matter, not the gender number.** A 0.3 pp gender gap and a 5.3 pp
skin-tone gap from the same model is itself a finding — the model is markedly more consistent
across perceived gender than across skin tone. For a project screening people at a land border,
the subregion spread is the most directly relevant: the 92.5% floor is a specific set of
regions where the detector measurably misses more faces, and we do not know which regions, only
that the range is that wide.

### 6.3 What the card does **not** cover, and EVAL.md §4 requires

| EVAL.md §4 required bucket | Covered by the vendor evaluation? |
|---|---|
| gender | ✅ yes (perceived, annotator-assigned, binary) |
| **age band** | ❌ **no age-bucket numbers anywhere in the card** |
| lighting | ⚠️ training included varied lighting; **no per-lighting-bucket numbers** |

Two of our three required axes have no vendor numbers, and the missing one is **age** — which
for a border-screening tool is not a marginal gap. `Detection.perBucket` in `:core` is built and
tested for exactly this and has **never been run on real data**, because `D-FACE` is empty.

### 6.4 Confidence limits on the vendor numbers

- Evaluation sets are 720 / 800 / 350 images, and the card states **"The datasets I–III are not
  disjoint."** A few hundred overlapping in-distribution images.
- "All samples are picked from the same source as the training samples." These are in-domain
  numbers. Our domain is a passport photo on a document, held at arm's length, by a stranger, in
  a queue — measurably different.
- The card defines precision/recall "as point estimates as well as posterior probability
  distribution characteristics", and the summary we read reports **point estimates only**. The
  95% credible intervals are named but not printed in the model card. On 350–800 samples the
  intervals are wide.
- Face size: the operating domain is **box side ≥20% of the image side**; the evaluation sets use
  **≥15%**. Both our document-photo and live-capture cases need to be checked against this, and
  nothing in our pipeline currently is.
- Pose: roll/pitch ≤45°, yaw ≤90°; faces "too far away (further than 2 metres)" are out of scope.

### 6.5 Demo watermark / identity

Nothing here is a face *recogniser*. The model localises; it does not identify, and it cannot.
Per DESIGN §3, the detector may differ per platform without breaking the comparability law —
which is why the detector being reusable across platforms matters less than the embedder being
byte-identical across platforms.

---

## 7. Decision

**Date:** 2026-09-30. **Owner:** face/vision track. **Status: DECIDED, partially blocked.**

1. **Adopt `blazeface_short.tflite`** (BlazeFace short-range, float16/1) as the face **detector**
   on both platforms. Hash-pinned to
   `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f`, fetched via
   `scripts/models_manifest.tsv` and verified by `scripts/fetch_models.sh`. **Licence verified
   (Apache-2.0), provenance verified, redistribution permitted.** TFLite, so D1's "same bytes,
   one hash" holds. This clears the first half of R-A in `docs/STATUS.md`.
2. **Adopt `ai.djl.tflite:tflite-engine:0.27.0` + `tflite-native-cpu:2.6.2`** as the desktop
   TFLite runtime, because no first-party TFLite JVM artefact exists. Bound only in `:platform`,
   non-transitively, with the 5-line ADR AGENTS.md §5 requires. **Windows x86-64 and Apple
   Silicon Mac have no native and are review-only.** This contradicted BUILD.md §4's claim of
   win/linux/mac support. ~~BUILD.md is owned by another track and is not edited by this
   spike~~ — **superseded 2026-09-30: `BUILD.md` §4 has now been rewritten, and the correction is
   consistent across `BUILD.md` §4, `DESIGN.md` §1/D1/§3, `ROADMAP.md` M2.2 + the M2-d3 tripwire,
   `RISKS.md` R4, this file, and `docs/STATUS.md` R-T.** What that rewrite adds to the record here
   is the raw evidence: the 2.17.0 "jar" is 1,411 bytes of verbatim POM relocation text; earlier
   versions publish no `.jar` at all; no `<os>` classifier exists for any version; and
   `readelf -d` on the AAR's own natives shows `NEEDED libc.so, liblog.so, libdl.so, libm.so` with
   no `libc.so.6` and no `ld-linux-x86-64.so.2`, which is the Bionic/glibc split stated as bytes
   rather than as a claim.
3. **`emb_v1.tflite` is NOT filled.** Every candidate in DESIGN §6 A/B/C was rejected, and so
   were three additional candidates found during the search (§2.1), all on licence grounds. **No
   weights were fabricated, approximated or synthesised, and no placeholder vector is produced
   anywhere.** The embedding seam stays exactly as it was; `face = null` → face layer
   `UNAVAILABLE` → GREEN unreachable, which is fail-closed and is the correct behaviour
   (FUSION.md §7, AGENTS.md §2 "GREY/fail-closed on low quality — no silent default-pass").
4. **The `emb_v1` decision is escalated, not closed.** It is a permissions question, not a
   fetching question. §8. **Now closed as a licensing decision — item 5.**

5. **LICENSING DECISION — a research-licensed embedder is ACCEPTABLE FOR THE PROTOTYPE ONLY.**
   **Date:** 2026-09-30. **Owner:** project lead (the project is solo-maintained, `HANDOFF.md` §2;
   §7's "leads pick" resolves to the single maintainer, and this is that decision written down
   rather than inherited silently). **Self-reviewed, not peer-reviewed** — there is no second
   reviewer, so this records who decided and on what basis, which is the honest form.

   **The reasoning, in full.** `docs/README.md` §License states the deliverable is a *"prototype
   for SIH demonstration and research evaluation. Not for operational deployment without MHA
   legal/technical clearance"*. That is a **non-commercial research context**, and it is the
   context the licence question turns on. So a checkpoint whose terms are *"non-commercial research
   purposes only"* is **inside** our terms for the prototype, and the blocker recorded in §2.1 —
   which was never "we cannot find good weights", it was "we cannot find weights we may
   redistribute" — becomes solvable **for this deliverable**. InsightFace's own text is the
   clearest case: the training data and the models trained on it are available for
   *"non-commercial research purposes only"*, which describes exactly what this prototype is.

   **Three consequences, stated as obligations and not as intentions:**

   1. **The embedder may NOT ship in an operational deployment.** It is admissible for the SIH
      prototype and for research evaluation, and inadmissible the moment this is deployed as a
      screening tool. `THREAT_MODEL.md` §8 already carries the operational-deployment disclaimer
      (MHA authorisation, DPDP review, CERT-In audit, PKD/registry feeds, longitudinal bias study,
      officer training and grievance redress); this decision adds one more item to that list and
      does not replace any of them. Any future change of deliverable **re-opens this decision
      automatically** — it was made for a prototype, and it does not travel.
   2. **Licence obligations attach to redistribution.** "Non-commercial research" is a *use*
      permission, not a *redistribution* permission, and those are different rights. Whatever we
      obtain must be accompanied by: the publisher's exact licence text verbatim, the training-data
      provenance, any click-through or registration requirement, and an attribution/notice file.
      `THIRD_PARTY.md` is the place it goes. Redistribution is a *separate* question from use and
      is **not** granted by this decision — if the chosen licence forbids redistribution, the
      artefact stays out of the repo and is provisioned to `~/.kasoti/provisioning` like a secret.
      That is a decision to take when there is a candidate, not now.
   3. **The known gaps must be disclosed, not discovered.** Concretely, and all of it is already
      written down in §6 and mirrored in `docs/STATUS.md` §2.1 so that it is a stated position
      rather than something a judge finds:
      - the vendor's own **out-of-scope statement** — the detector's model card lists *"any form of
        surveillance or identity recognition"* as explicitly out of scope, and says the model is
        *"not intended for human life-critical decisions"* (§6.1). Apache-2.0 grants the rights;
        the authors documented the opposite boundary;
      - the measured **skin-tone recall gap of 5.3 pp** (98.1% average, 94.7–100% across five
        Fitzpatrick-derived groups) and the **geographic-subregion gap of 7.5 pp** (99.1% average,
        92.5–100% across 17 UN geoscheme regions) (§6.2) — and the finding that the same model is
        consistent across perceived gender (0.3 pp) while varying across skin tone, which for a land
        border is the relevant axis;
      - the **age-band gap**: the card reports gender, skin tone and geography and **no age buckets
        at all**, while EVAL.md §4 requires gender × age-band × lighting (§6.3);
      - the **confidence limits** on all of it: 720/800/350-image evaluation sets stated as *not
        disjoint*, all in-domain with training, and point estimates without the 95% intervals the
        card names (§6.4). **None of these are our measurements** and EVAL.md §1 forbids quoting
        them without a run id we cannot supply.
      The same disclosure duty applies to the embedder, and extends to it: a research-licensed
      embedder is trained on the same MS1M/VGGFace2 lineage, so it carries the same provenance
      question and the same fairness caveats as the detector, and any published bias figure for it
      is the vendor's, not ours.

   **What this decision does NOT do.** It does not weaken a gate, and it does not make GREEN
   reachable. Until an embedder is actually obtained, hash-pinned and tested, `face = null` → face
   layer `UNAVAILABLE` → **GREEN 1:1 remains unreachable**, fail-closed (FUSION.md §7,
   AGENTS.md §2 "GREY/fail-closed on low quality — no silent default-pass"). A licensing decision
   is not an implementation.

6. **SOURCING ATTEMPT after the decision — no embedder obtained, seam left clean.** Because item 5
   made a research licence admissible, the search was re-run (§2.1's second round, four new
   candidates, each checked for its actual licence text and for whether a TFLite conversion is
   possible). **Outcome: nothing obtained. All four rejected** — AdaFace-via-PINTO (MIT on the
   conversion, upstream 404, 512-d, WebFace260M/MS1MV3 data), ONNX Model Zoo ArcFace (Apache-2.0
   path itself 404, 512-d, ONNX, MS1MV2), `estebanuri/face_recognition` (a real 22.5 MB
   `facenet.tflite`, but the repository has **no licence file at all** and it is 4.5× the size
   budget), and the MediaPipe Face Embedder (still unpublished; the `mediapipe-models` bucket has 21
   prefixes, `audio_embedder`/`image_embedder`/`text_embedder` among them, and no `face_embedder`).
   **No weights were fabricated, approximated, synthesised or converted.** `emb_v1.tflite` remains
   empty, `TfliteFaceModels.embed(...)` and `DesktopFaceLayer.embed(...)` still return `null`, and
   the seam is a clean labelled `null` with a documented reason — which is the correct state, not a
   gap to be tidied away later.

### What this spike does *not* unblock

- **GREEN still requires a 1:1 face match**, so the headline gap the task set out to close is
  **half closed**: the face layer can now localise a face but cannot yet compare one to
  another. The honest summary is "detector solved, embedder licence decided and still
  unobtainable", and any report that says otherwise is wrong.
- **Parity is not verified.** No Android SDK, no device. What exists instead is a normative
  desktop reference: `eval/fixtures/face/manifest.json` pins the model digest and the input
  contract so that the moment a device exists, "do Android and desktop agree?" becomes a
  mechanical assertion rather than an investigation. No parity claim is made.
- **No reference embedding vectors exist**, because no embedder exists. `eval/fixtures/face/`
  ships the schema, a detector reference and — as of 2026-09-30 — the detector's real output
  tensors for two committed inputs under `raw_outputs/`, and says so in its own README.

---

## 8. Open — for the lead, not for an engineer

1. ~~**The embedder needs a licensing decision.**~~ **CLOSED 2026-09-30** — §7 item 5. The
   decision: a research-licensed embedder is acceptable **for the prototype**, with three binding
   consequences recorded there (no operational deployment; licence obligations attach to
   redistribution and are a *separate* right from use; the vendor scope caveats and the measured
   skin-tone and regional recall gaps must be disclosed, not discovered). **It is a licensing
   decision, not an implementation** — `emb_v1.tflite` is still empty.
   **What remains open is the cost, not the permission.** The options, honestly restated: (a)
   request a licence from InsightFace or another publisher, which since 2025-11-24 means an email
   to `recognition-oss-pack@insightface.ai` and an unknown price; (b) train our own 128-d embedder
   on data with clear training rights — a multi-week data project with its own consent burden, and
   it would need `D-FACE`; (c) ship detector-only and say so in every narrative. **What is no
   longer on the list is "find a better-cased candidate": a second, focused search on 2026-09-30
   (§2.1) checked four more and found nothing, and two of the ecosystem's reference repositories
   had gone 404 while we were looking.
2. **The scope mismatch in §6.1 needs an owner's signature**, not a footnote. §7 item 5 commits to
   disclosing it; what is still missing is a human signature on the disclosure itself, and that
   does not become less true because the licence is Apache-2.0.
3. **Windows and Apple Silicon desktop inference is unresolvable via this route.** Either accept
   review-only on those platforms, or fund an ORT-JVM path (main jar 139,129,141 B at 1.22.0; `onnxruntime` has
   first-class desktop natives) — which trades D1's "same bytes" for a different format per
   platform, and would need its own spike.
4. **The input range `[-1,1]` vs `[0,1]` is unresolved** and needs one consented face crop to
   settle. Cost: one image.
