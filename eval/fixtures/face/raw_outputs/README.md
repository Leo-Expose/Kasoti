# `eval/fixtures/face/raw_outputs/` — the detector's real output, as a known answer

These files are the `blazeface_short.tflite` **output tensors** for two committed input images, plus
the decode the desktop binding produces from them. `raw_outputs.json` is the normative record.

## Why this exists

The face fixture that was already here — `no_face_synthetic_256.png` — asserts **zero detections**.
That is a weak assertion, and it is weak in the specific way that matters here: two broken
detectors both return zero. The four defects in the Android detector
(`detector_reference.json`, finding `finding_android_detector_cannot_run_this_model`) would each
have been invisible against a zero-detection fixture, because a face-free image has no detection to
get wrong.

These two cases close that gap. `noface` is the same face-free image as before, and pins that the
near-miss scores are probabilities under the floor rather than the negative numbers a missing
sigmoid would have produced. `fires` is a synthetic image that makes the real model fire, so the
sigmoid, the anchor placement, the box, all five keypoints and NMS survivor selection are all
exercised against a number both platforms must produce.

## What was NOT verified by this

**No Android code ran.** These tensors were produced by `:platform`'s
`dev.kasoti.platform.ml.tflite.TfliteBlazeFaceDetector` against the real model on a real JVM. That
makes them the *known answer* the Android decode is checked against. It does not make them a parity
result: the Android binding (`TfliteFaceDetector`) is still compiled only against hand-written API
stubs, never against the real SDK. See `app-android/tools/verify-offline.sh`.

**These boxes are not validated box geometry.** The `fires` box is 1.85 frame-widths wide, i.e.
larger than the frame, because the anchor-size parameters (`min_scale` 0.1484375, `max_scale` 0.75,
`fixed_anchor_size`) come from MediaPipe's Tasks-library fallback block and have never been compared
against a real face — `D-FACE` is empty. What is pinned here is that two platforms decode the same
numbers the same way. It is not a claim that the numbers are right.

**The input range is not settled by this either.** `fires` fires under both `[-1,1]` and `[0,1]`,
so these bytes cannot separate them. One consented crop can (spike 01 §8 item 4).

## Files

| File | What it is |
|---|---|
| `raw_outputs.json` | **Normative.** Tensor geometry, decode parameters, the input contract, and the expected decode of each case, plus the SHA-256 of every file. |
| `noface_regressors.f32` | `regressors` tensor for `no_face_synthetic_256.png`: 14,336 float32. |
| `noface_classificators.f32` | `classificators` tensor for the same: 896 float32. |
| `fires_synthetic_256.png` | The synthetic image, 256×256. No person and no biometric data. |
| `fires_regressors.f32` | `regressors` tensor for it: 14,336 float32. |
| `fires_classificators.f32` | `classificators` tensor for it: 896 float32. |

`.f32` is little-endian IEEE-754 float32, row-major, **no header and no padding**, so
`elementCount = byteCount / 4`. The `regressors` layout is `[896][16]` flattened — 4 box values, 2
keypoint scores, then 10 keypoint coordinates for 5 points. The `classificators` layout is `[896][1]`,
one **logit** per anchor.

## The numbers, so nobody has to derive them

| | `noface` | `fires` |
|---|---|---|
| anchors decoded | 896 | 896 |
| survivors after NMS (IoU 0.3, max 10) | 5 | 3 |
| survivors at or above `min_score` 0.5 | **0** | **1** |
| top raw logit | −1.3418 | +0.9191 |
| top score after sigmoid | 0.2072 | **0.7149** |

`fires` is the case that makes the missing sigmoid visible as a number rather than a principle. Its
top anchor's raw logit is `0.9191`. Read as a confidence that is "91% sure"; squashed it is `0.7149`,
"71% sure". The difference is not cosmetic — a floor set anywhere between 0.5 and 0.8 separates the
two readings, and the detector's own floor is 0.5 while the model card's own reported operating
point implies higher. Read raw, the top survivor also becomes a *larger* number than the second,
so the ranking survives; but the score reported to a quality gate or a fusion rule does not.

## Reproducing `fires_synthetic_256.png`

Committed rather than regenerated, and the recipe is here so it *can* be. A binary nothing can
reproduce is a binary nobody can audit.

```python
W = H = 256
px = bytearray(W * H * 3)
cx = cy = W / 2
rx, ry = 70.0, 95.0
for y in range(H):
    for x in range(W):
        i = (y * W + x) * 3
        dx, dy = (x - cx) / rx, (y - cy) / ry
        inside = dx * dx + dy * dy <= 1.0
        r, g, b = (214, 176, 152) if inside else (200, 170, 150)
        for s in (-1, 1):                       # two dark eye blobs
            ex, ey = cx + s * 26.0, cy - 28.0
            exx, eyy = (x - ex) / 18.0, (y - ey) / 11.0
            if inside and exx * exx + eyy * eyy <= 1.0:
                r, g, b = 30, 26, 24
        mdx, mdy = (x - cx) / 34.0, (y - (cy + 48.0)) / 7.0   # a mouth bar
        if inside and mdx * mdx + mdy * mdy <= 1.0:
            r, g, b = 120, 60, 62
        px[i], px[i + 1], px[i + 2] = r, g, b
Image.frombytes('RGB', (W, H), bytes(px)).save(path)
```

SHA-256: `ecc7be06cc69d401525db3cb7c63e00016c83ed5e757444161572051c931d57e`

It is an ellipse with three dark marks. It is not a person, it is not a photograph, and it contains
no biometric data — which is why it is acceptable to commit when `D-FACE` is empty and using a real
face here would be exactly the consent failure THREAT_MODEL §8 is about. It is in the fixture set
only because the detector happens to respond to it, which is what makes it useful for testing the
decode and useless for measuring anything.

## Regenerating the tensors

From a checkout with the model fetched and a `:platform` build:

```bash
./scripts/fetch_models.sh scripts/models_manifest.tsv
./gradlew :platform:jvmTest        # exercises the desktop detector on both inputs
```

The tensors themselves were captured with a throwaway JVM probe, which is not in the repo, because a
permanent "dump the tensors" tool would be a way to regenerate a fixture without regenerating it
correctly. If you change either input image, the tensors and the whole of `raw_outputs.json` must be
regenerated together — `BlazeFaceDesktopParityTest` checks the recorded digests and the recorded
decode against the committed bytes, so a partial update fails loudly rather than quietly.
