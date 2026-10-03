# `eval/fixtures/face/` — the face-layer reference set

Purpose: make **"do Android and desktop agree?"** a mechanical assertion rather than an
investigation. DESIGN.md §3 requires the two platforms to produce the same outputs from the same
bytes (EVAL.md §4: tolerance ±1e-3 on similarity), and nothing in this repository has ever
checked that, because there has been no model to check.

**Parity is not verified and is not claimed.** ⚠️ **Corrected 2026-10-03: this used to say "There
is no Android SDK and no device."** An SDK **is** present now and `:app-android` compiles — but
**there is still no device**, and nothing has run. So the claim is unchanged in substance and
changed only in the reason: the blocker moved from "cannot compile" to "nothing to compile *on*".
What exists here is the *normative reference* — the pins and the expected outputs — so that the
first person with a device runs a test rather than writing one.

---

## What is in here, and what is not

| Artefact | Status | Notes |
|---|---|---|
| `manifest.json` | ✅ present | The normative pins: model digest, tensor shapes, anchor count, decode parameters, input encoding. Everything both platforms must agree on. |
| `no_face_synthetic_256.png` | ✅ present | A deterministic synthetic image containing **no face**. Normative expectation: **0 detections**. |
| `detector_reference.json` | ✅ present | The exact detector output for that image on desktop, plus the measured timings. |
| `raw_outputs/` | ✅ present | The detector's **real output tensors** for two committed inputs, plus the desktop decode of them. This is the known answer the Android decode is checked against — see below. |
| Aligned 112×112 face crops | ❌ **absent** | Needs a consented face capture. `D-FACE` is empty (STATUS.md R-F). |
| **Reference embedding vectors** | ❌ **absent, and cannot be produced** | There is no embedding model. Seven candidates rejected on licence grounds; none was fabricated. See `docs/spikes/01-face-model.md` §2.1. |
| Per-bucket bias numbers on our data | ❌ absent | `Detection.perBucket` is implemented and tested but has never been run on real data. Vendor numbers are in the spike §6.2 and are **not** a substitute. |

The absence of the embedding vectors is not an oversight to be tidied away in a later PR. It
follows from the licensing outcome and it stays until someone obtains a licence or trains a model.

---

## `raw_outputs/` — the non-zero case

`no_face_synthetic_256.png` alone can only ever assert **zero detections**, and a broken detector
also returns zero. That weakness is exactly what let four defects sit in the Android detector
unnoticed. So `raw_outputs/` adds the other half:

| Case | Input | Normative expectation |
|---|---|---|
| `noface` | the same face-free image as above | 896 anchors decoded, 5 survive NMS, **0** clear the 0.5 floor |
| `fires` | a drawn ellipse (`fires_synthetic_256.png`, no person) that makes the real model fire | 896 decoded, 3 survive NMS, **1** clears the floor at score **0.7149** |

The `fires` case is what makes the missing sigmoid visible as a number: its top anchor's raw logit
is **0.9191**, and read as a confidence that is "91% sure" where the truth is "71% sure".

**These tensors are the model's own output, captured by the working `:platform` binding, and the
expected decode was computed by that same binding.** They are the known answer, not a parity result:
the Android binding is compiled only against hand-written API stubs and has never met the real SDK.
Full caveats, the byte layout, the recipe for the synthetic image and the regeneration procedure
are in `raw_outputs/README.md`.

The test that consumes them is
`app-android/src/test/java/dev/kasoti/android/ml/BlazeFaceDesktopParityTest.kt`, run by
`app-android/tools/verify-offline.sh` on a bare JVM. It loads `:platform`'s compiled classes and
requires the Android decode to be **bit-identical** to the desktop one on these bytes and on
adversarial tensors.

---

## How to read `detector_reference.json`

The three assertions it supports, in increasing strength:

1. **Parity of the contract.** Both platforms must load the same `modelTag`, run the same input
   encoding, and produce the same tensor shapes. Checked by `BlazeFaceContractTest` on desktop
   today, and by `TfliteFaceDetector`/`ModelPinParityTest` on Android when there is a device.
2. **Parity of the decision.** For the synthetic image, both platforms must report zero
   detections. Weak on its own — both could be broken the same way — but it catches the
   overwhelmingly common real bug, which is one platform having no model at all and reporting
   "no face" where the other reports a face. *That is precisely the failure this set exists to
   catch*, and it is why the file also pins the model digest: a platform that silently loaded a
   different model would still return 0 detections here and still be wrong.
3. **Parity of the values.** `raw_outputs/` now does this for the **decode**: 8 of 896 anchors and
   their scores, boxes and keypoints are pinned from a real firing case, and the Android decode is
   required to reproduce them bit for bit. What is still missing is a check against ground truth —
   that the decoded keypoints actually land on a real face's eyes and mouth. That is blocked on
   consent, not on engineering.

---

## Reproducing `no_face_synthetic_256.png`

It is committed so it does not have to be regenerated, and the recipe is here so it *can* be —
a binary that nothing can reproduce is a binary nobody can audit.

```python
W = H = 256
px = bytearray(W * H * 3)
for y in range(H):
    for x in range(W):
        i = (y * W + x) * 3
        px[i + 0] = (x * 255) // (W - 1)   # horizontal ramp
        px[i + 1] = (y * 255) // (H - 1)   # vertical ramp
        px[i + 2] = ((x // 16 + y // 16) % 2) * 40 + 100   # 16px block dither
Image.frombytes('RGB', (W, H), bytes(px)).save(path)
```

SHA-256: `2d86b539469beceda9aaf3ac75ba886d0769330bb0906329890cd9801e908857`

No person's face appears in this file, deliberately. Using a real face here would be the consent
failure THREAT_MODEL §8 is about, and no consented capture exists yet. A synthetic image is
enough for assertions 1 and 2 above.

---

## Adding a real crop later

When `D-FACE` has consented material, add per case:

```
eval/fixtures/face/crops/<case-id>/doc_aligned_112.png      # the printed document photo
eval/fixtures/face/crops/<case-id>/live_aligned_112.png      # the live capture
eval/fixtures/face/crops/<case-id>/expected.json             # boxes, keypoints, embeddings
```

`expected.json` must record, for each side: the `modelTag`, the detection box and five keypoints,
and the 128-d embedding with enough digits to be a ±1e-3 check rather than a rounded summary. A
`cosine` field is useful for humans and useless as a gate; store the vector.

Split by **identity**, never by frame (EVAL.md §2). A crop set that puts two frames of the same
person on opposite sides of the split inflates every number in the report.
