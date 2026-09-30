# `:platform` — the only module with expect/actual (DESIGN.md §1, §3)

Thin adapters over JCA, ImageIO and Tess4J. Every judgement — thresholds, verdicts, feature
maths — stays in `:core` so it can be tested without a device and without a display.

`:core` has zero dependencies and zero `expect/actual`, so it declares the crypto contracts
as interfaces (`dev.kasoti.crypto.Primitives`) and this module supplies the actuals.

## What is here

| Package | What | Notes |
|---|---|---|
| `dev.kasoti.platform.crypto` | `JcaDigest`, `JcaHmac`, `JcaSignatureVerifier` | JCA only. AGENTS.md §5 forbids hand-rolled crypto. |
| `dev.kasoti.platform.imaging` | `ImageIoImaging`, `RgbImage`, `RgbQuad` | decode → raw samples, bilinear resize, quad deskew. No OpenCV (D4). |
| `dev.kasoti.platform.ocr` | `OcrEngine`, `Tess4JOcrEngine`, `ManualOcrEngine`, `OcrEngines` | Tess4J optional at runtime; manual entry is the P0 fallback (D5). |
| `dev.kasoti.platform.ml` | `ModelLoader`, `PinnedModel`, `LoadedModel`, `EmbeddingModel` | hash pinning (I12 / RT-E8). |
| `dev.kasoti.platform.ml.tflite` | `TfliteRuntime`, `BlazeFaceContract`, `BlazeFaceAnchors`, `AnchorDecoder`, `TfliteBlazeFaceDetector`, `DesktopFaceLayer` | desktop TFLite binding (ADR-3). The **detector works**; the **embedder is deliberately empty** — see ADR-3. |
| `dev.kasoti.platform.collector` | `MacroCollector`, `MacroProcess`, `MacroLight` | the D-MACRO corpus writer (DATA.md §3). |

## ADR-1 — Tess4J (`net.sourceforge.tess4j:tess4j:5.13.0`)

- **What**: the desktop MRZ OCR engine, configured `--psm 6` + `tessedit_char_whitelist=A-Z0-9<`.
- **Why**: BUILD.md §5 and D5 make Tesseract the P0 desktop OCR. The whitelist is not cosmetic —
  without it a speck of dust becomes a rejected character and every downstream check digit
  shifts by one, producing a *failed check digit on a valid passport*.
- **Size**: ~1.6 MB of jars; pulls JNA + leptonica transitively. Counted against the desktop
  distribution budget, not the APK (APK uses ML Kit — D6).
- **License**: Apache-2.0 (Tesseract and Tess4J both).
- **Alternative**: RapidOCR (P2), or a bundled ONNX model. Rejected for P0 because it adds a
  model-download story to a build that must work offline (AGENTS.md §3).
- **Risk accepted**: `libtesseract` is a *system* dependency. `Tess4JOcrEngine.availability()`
  probes it, and `OcrEngines` falls back to `ManualOcrEngine`, so nothing on the verdict path
  requires Tesseract to be installed.

## ADR-2 — no TensorFlow dependency *(SUPERSEDED 2026-09-30 by ADR-3)*

- **What**: `:platform.ml` ships the hash-pinned loader and the `EmbeddingModel` interface.
- **Why**: DESIGN D1 settles inference on TFLite, but the interpreter binding belongs to the
  vision track, and a half-wired `Interpreter` is worse than none.
- **Size**: `tensorflow-lite` + platform natives would be several MB across three OSes.
- **License**: Apache-2.0.
- **Alternative**: ONNX Runtime — rejected by D1 (conversion risk, two hashes to pin).
- **Superseded because** spike 01 showed the premise was wrong in a way that mattered: the
  binding was not "someone else's track", it was **missing entirely**, because no TFLite desktop
  JVM artefact exists at all. ADR-3 picks the least-bad substitute. The shape of this ADR held —
  no full TensorFlow, no `app-android` change — but the reason "the vision track owns it" was
  waiting for someone else, and nobody was coming.

## ADR-3 — desktop TFLite via `ai.djl.tflite`, and an embedder that stays empty

**What.** Two dependencies, bound in `:platform` only, never `api`:

| Coordinate | Version | Provides |
|---|---|---|
| `ai.djl.tflite:tflite-engine` | 0.27.0 | the genuine `org.tensorflow.lite.*` Java API — `Interpreter`, `Tensor`, `DataType`, `NativeInterpreterWrapper` |
| `ai.djl.tflite:tflite-native-cpu` | 2.6.2 | desktop `libtensorflowlite_jni.so` / `.dylib` (classifier per OS) |

**Why.** There is **no first-party TFLite JVM artefact**, measured 2026-09-30
(`docs/spikes/01-face-model.md` §4, full method there):

- `org.tensorflow:tensorflow-lite:2.17.0` on Maven Central is a **1,411-byte relocation stub** to
  `com.google.ai.edge.litert:litert:1.0.1`, which is `<packaging>aar</packaging>`.
- Every earlier version is AAR-only, with **no `.jar` published at all**.
- No `<os>` classifier exists for any version: `linux-x64`, `windows-x64`, `macos-x64`,
  `macos-arm64` all HTTP 404.
- The Android `.so` files inside the AAR link **Bionic**, so a desktop JVM cannot load them.

So DESIGN D1's "TFLite everywhere, same bytes, one hash" is unreachable through the vendor
route, and `BUILD.md §4`'s claim of "natives for win/linux/mac" is simply wrong.

**Size.** Engine ~30 KB. Native 3,382,784 B (`linux-x86_64`) or 7,073,184 B (`osx-x86_64`).
Counted against the **desktop** distribution; the APK is untouched. `ai.djl:api` is deliberately
not pulled — `jdeps` shows the `org.tensorflow.lite.*` classes we call reference no `ai.djl`
class, and real inference was verified with it absent from the classpath.

**Licence.** Apache-2.0, both. No model weights ship in either jar.

**Alternative — and the honest cost.** `windows-x86_64` and `osx-aarch64` have **no published
native** (404, for both of upstream's two versions), so those platforms are "review-only, no
on-device inference" — the response `BUILD.md §4` itself prescribes, implemented in
`TfliteRuntime.availability()`. This will not improve by bumping a version. The way out is
`com.microsoft.onnxruntime:onnxruntime` (Apache-2.0, 139 MB jar, first-class win/linux/osx
natives), which needs the model in ONNX and so trades D1's "same bytes" for a per-platform
format. Rejected for now, recorded so it stays a decision.

**Three traps, all found by running it, all documented at the call site.**

1. `TensorFlowLite` loads its native from a static initialiser that **swallows the failure**, so a
   missing library surfaces as
   `UnsatisfiedLinkError: org.tensorflow.lite.TensorFlowLite.nativeRuntimeVersion()` — which
   reads like a JNI ABI mismatch and is not one; the symbol is present, the library was never
   loaded. `TfliteRuntime` calls `System.load()` itself and reports `available = false`.
2. The only public multi-output entry point is
   `runForMultipleInputsOutputs(Object[], Map<Integer,Object>)`. The two-argument
   `run(Object,Object)` picks its *output* accessor from the *input*'s type, so a `Map` of
   outputs with a `ByteBuffer` input fails with `DataType error: cannot resolve DataType of
   java.util.HashMap`. BlazeFace has two outputs, so this is not optional.
3. Output arrays must have **exactly three nesting levels** matching the tensor shape
   (`[1, 896, 16]`), not `[896, 16]` and not `[1, 896, 16, 1]`. Both are rejected on shape.

**What is real and what is not.** Real: the detector runs, on the hash-pinned Apache-2.0
BlazeFace weights, on both platforms from the same file, and is deterministic across repeated runs
on identical bytes (asserted in `DesktopFaceLayerTest`). Not real: **there is no embedder.** Seven
candidates were evaluated and all rejected on licence grounds
(`docs/spikes/01-face-model.md` §2.1); none was fabricated. `DesktopFaceLayer.canCompareFaces` is
therefore `false` on every platform, `embed()` returns `null`, and a GREEN 1:1 verdict stays
unreachable — fail-closed, by design. There is deliberately no stub that could return a
plausible-looking vector: a real number from the wrong pixels is more dangerous than a missing
one, because it survives review.

**Not verified, and stated as such.** The detector's `[-1,1]` input encoding is the model card's
stated contract, not a measured one; `0..255` is excluded by measurement but `[0,1]` cannot be
separated from `[-1,1]` without a consented face crop. The anchor *sizes* are sourced from
MediaPipe's Tasks-library fallback and remain unverified — though `fixed_anchor_size = true` makes
them cancel out of the decode, so detection, scoring and suppression are unaffected and only box
geometry is at stake. **Android parity is not claimed**: no SDK, no device. What exists instead is
a normative reference in `eval/fixtures/face/` that makes the comparison mechanical. That
reference also records a **high-severity finding against the Android detector**, which cannot run
this model as written (wrong output array shape, no sigmoid, single-output `run`, no anchor
decode) — with line numbers, in `eval/fixtures/face/detector_reference.json`. It is recorded, not
fixed: `app-android/` belongs to the android track.

## Why the conformance tests matter

`JcaDigestTest` pins FIPS 180-4 and `JcaHmacTest` pins RFC 4231. `:core` cannot test its own
hashing — it has none. A stub returning 32 zero bytes would satisfy every `:core` test
perfectly and silently destroy the audit chain and every model pin. The seam is only worth
having if the thing on the other side of it is checked against the published vectors.
