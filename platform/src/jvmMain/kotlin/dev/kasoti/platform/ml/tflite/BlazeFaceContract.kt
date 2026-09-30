package dev.kasoti.platform.ml.tflite

/**
 * The `blazeface_short.tflite` interface, transcribed from the model file itself.
 *
 * Every number here was read out of the artefact's TFLite flatbuffer — tensor records,
 * quantisation parameters, and the operator list walked in graph order. None of it is from
 * documentation, a blog post or memory (AGENTS.md §8). Method and raw output are recorded in
 * `docs/spikes/01-face-model.md` §3.1.
 *
 * These are the model's *own* shape, not tunables. They live in a named object rather than
 * inline because the alternative is a bare `112` or `896` in the middle of inference code,
 * where it is indistinguishable from a threshold — and AGENTS.md §2 makes a magic number a
 * review fail. Nothing in this object may be tuned without changing the weights.
 */
object BlazeFaceContract {

    const val NAME = "blazeface_short.tflite"

    /**
     * SHA-256 of the float16/1 artefact, measured by us on 2026-09-30.
     *
     * Google publishes no `.sha256` sidecar for this model (the URL 404s), so this is a pin of
     * bytes we actually downloaded, not a digest copied from a publisher's manifest. The
     * versioned `/1/` path is pinned rather than the `latest` alias Google's docs link to;
     * the two were byte-identical when checked, but `latest` can be repointed under us and a
     * pin against a moving alias is not a pin (RT-E8).
     */
    const val SHA256 = "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f"

    const val SHA256_SOURCE_URL =
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite"

    /** Apache-2.0, per the model's own model card. See spike 01 §3. */
    const val LICENSE = "Apache-2.0"

    /**
     * The detector's input edge: 128×128×3, NHWC, float32.
     *
     * Matches DESIGN.md §6's "~230 KB" and the model card's "resized to 128x128 pixels".
     */
    const val INPUT_SIDE = 128
    const val INPUT_CHANNELS = 3

    /** `input` is FLOAT32 with **no** quantisation parameters. Raw float, not 0..255 bytes. */
    const val INPUT_DTYPE = "FLOAT32"

    /**
     * 896 anchors.
     *
     * This is a property of the decoder, not a knob: the regressor tensor is
     * `[1, 896, 16]` and each 16-float row is one anchor. A different anchor count would mean a
     * different model.
     */
    const val ANCHORS = 896

    /**
     * Floats per anchor: `4 box + 2 keypoint scores + 10 keypoint coordinates`.
     *
     * Offsets into a 16-float anchor row, named so the indexing arithmetic in [Anchor] cannot be
     * silently misread:
     *  - `BOX_Y_CENTER .. BOX_Y_MAX` — the four box values
     *  - `KEYPOINT_SCORES` — two values, position score then presence score
     *  - `KEYPOINTS` — five points, two floats each
     */
    const val ANCHOR_STRIDE = 16
    const val BOX_X_CENTER = 0
    const val BOX_Y_CENTER = 1
    const val BOX_WIDTH = 2
    const val BOX_HEIGHT = 3
    const val KEYPOINT_SCORES = 4
    const val KEYPOINT_COUNT = 5
    const val KEYPOINTS = 6

    /**
     * The model emits **five** keypoints, not the six its model card describes.
     *
     * The card documents the Tasks-library output, which synthesises a sixth point. The raw
     * TFLite gives right eye, left eye, nose, mouth-right, mouth-left. `TfliteFaceDetector` in
     * `app-android` already uses 5 and is correct; this constant exists so the desktop side
     * cannot disagree.
     */
    const val OUTPUT_KEYPOINTS = KEYPOINT_COUNT

    const val REGRESSORS_TENSOR = "regressors"
    const val CLASSIFICATORS_TENSOR = "classificators"

    /**
     * `SOFTMAX` is absent from the model's opcode set.
     *
     * Verified by walking the operator codes: `ADD, CONCATENATION, CONV_2D, DEPTHWISE_CONV_2D,
     * DEQUANTIZE, MAX_POOL_2D, PAD, RELU, RESHAPE`. So `classificators` is a **logit** and the
     * caller applies the sigmoid. Reading it as a probability, or softmaxing it, flattens a
     * confident detection to about 0.5 — which would look like "the model never fires" rather
     * than like a bug, and is the kind of error that survives review.
     */
    val HAS_INNER_SOFTMAX: Boolean = false

    /** A sane floor on the artefact, so a truncated download is caught before the interpreter. */
    const val MINIMUM_BYTES = 200_000

    /**
     * The caller's input range: `[-1.0, 1.0]`.
     *
     * **Not verified, and this is load-bearing.** The graph contains no input-normalisation op —
     * the first consumer of `input` is `CONV_2D`, and the two preceding `DEQUANTIZE`s are of
     * float16 *weights*. So the caller must choose the range, and the model card is the only
     * authority: "RGB image ... resized to 128x128 pixels, represented as a 128x128x3 array of
     * float values in the range `[-1.0, 1.0]`".
     *
     * What *was* measured (spike 01 §3.2): feeding `0..255` drives the regressor output to
     * `absmax ≈ 4.2e6` versus `≈ 1.6e2` for the normalised encodings, so `0..255` is excluded
     * by measurement. The remaining fork against `[0,1]` is a one-unit DC offset per channel and
     * cannot be separated without an image the detector actually fires on, which needs a
     * consented crop (`D-FACE` is empty).
     *
     * The cost of a wrong pick is bounded: both platforms apply the same transform, so the
     * comparability law (DESIGN.md §3) still holds and a wrong pick costs accuracy, never
     * correctness. `BlazeFaceContractTest` pins the structural facts and leaves the fork
     * explicitly open.
     */
    const val INPUT_MIN = -1.0f
    const val INPUT_MAX = 1.0f

    /** 255.0 — the divisor implied by the `[0,255]` byte domain the decoder produces. */
    const val BYTE_SCALE = 255.0f

    /**
     * Operating envelope from the model card, for the quality gate to reason about.
     *
     * Not thresholds: these are the conditions under which Google says the model's own reported
     * numbers were gathered and beyond which it should be expected to degrade. A capture
     * outside this envelope is not evidence of "no face present" — it is evidence that the
     * detector was asked to do something it was not evaluated for. See spike 01 §6.4.
     */
    object OperatingEnvelope {
        /** "Face bounding box sides should be at least 20% of the corresponding image sides." */
        const val MIN_BOX_FRACTION_OF_IMAGE = 0.20f

        /** "Face roll and pitch (tilt) angles should be not more than 45 degrees." */
        const val MAX_TILT_DEGREES = 45f

        /** "The yaw (pan) angle should not exceed 90 degrees." */
        const val MAX_YAW_DEGREES = 90f

        /** "At least 70% of the face bounding box should lie inside the input image." */
        const val MIN_BOX_VISIBLE_FRACTION = 0.70f
    }
}
