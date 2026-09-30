package dev.kasoti.android.ml

/**
 * The `blazeface_short.tflite` interface, transcribed from the model file itself, plus the anchor
 * grid its outputs are decoded against.
 *
 * ## Why this file is pure Kotlin, outside the TFLite binding
 *
 * Every number here is the model's *own* shape, and the shapes are what the decode arithmetic
 * indexes. `dev.kasoti.android.platform.TfliteFaceDetector` is the binding that owns the
 * `Interpreter` and the tensors; it cannot be compiled or tested on a machine with no Android SDK,
 * which is currently every machine that reviews this code. Keeping the interface and the geometry
 * in a file with no `android.*` and no `org.tensorflow.*` import means the values can be asserted
 * against `:platform`'s compiled `BlazeFaceContract`/`BlazeFaceAnchors` on a bare JVM
 * (`app-android/src/test/java/dev/kasoti/android/ml/BlazeFaceDesktopParityTest.kt`).
 *
 * `:platform` is a KMP module whose actuals live in a `jvmMain` source set, and an Android module
 * cannot resolve that, so the duplication is structural rather than a preference. The test that
 * holds the two in step is what stops it becoming drift.
 *
 * Nothing in this file is a tunable. AGENTS.md §2 makes an unregistered magic number a review fail,
 * and these are the alternative: a named, constructed, cross-checked value instead of a bare `896`
 * or `128` in the middle of inference code, where it would be indistinguishable from a threshold.
 * These mirror `:platform`'s `BlazeFaceContract` and `BlazeFaceAnchors`; both spellings of every
 * value live in `eval/fixtures/face/manifest.json`, which is the normative reference.
 */
object BlazeFaceModel {

    const val NAME = "blazeface_short.tflite"

    /** Apache-2.0, stated on the model's own model card. See `docs/spikes/01-face-model.md` §3. */
    const val LICENSE = "Apache-2.0"

    /**
     * SHA-256 of the float16/1 artefact, measured by us on 2026-09-30.
     *
     * Google publishes no `.sha256` sidecar for this model (the URL 404s), so this is a pin of
     * bytes we actually downloaded, not a digest copied from a publisher's manifest. The versioned
     * `/1/` path is pinned rather than the `latest` alias Google's docs link to; a pin against a
     * moving alias is not a pin (RT-E8).
     */
    const val SHA256 = "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f"

    const val SHA256_SOURCE_URL =
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/" +
            "float16/1/blaze_face_short_range.tflite"

    /** A floor on the artefact, so a truncated download is caught before the interpreter sees it. */
    const val MINIMUM_BYTES = 200_000L

    // --- input edge ---------------------------------------------------------------------------------

    /** 128×128×3, NHWC. Matches DESIGN.md §6's "~230 KB" and the card's "resized to 128x128". */
    const val INPUT_SIDE = 128
    const val INPUT_CHANNELS = 3

    /** The input tensor has **no** quantisation parameters: raw float, not 0..255 bytes. */
    const val INPUT_DTYPE = "FLOAT32"

    /**
     * The caller's input range: `[-1.0, 1.0]`.
     *
     * **Not verified, and this is load-bearing.** The graph contains no input-normalisation op —
     * the first consumer of `input` is `CONV_2D`, and the two preceding `DEQUANTIZE`s are of
     * float16 *weights*, not of the image. The authority is the model card: "RGB image ... resized
     * to 128x128 pixels, represented as a 128x128x3 array of float values in the range
     * `[-1.0, 1.0]`".
     *
     * What *was* measured (spike 01 §3.2): feeding `0..255` drives the regressor output to
     * absmax ≈ 4.2e6 versus ≈ 1.6e2 for the normalised encodings, so `0..255` is excluded by
     * measurement. The remaining fork against `[0,1]` is a one-unit DC offset per channel and
     * cannot be separated without an image the detector actually fires on, which needs a consented
     * crop (`D-FACE` is empty).
     *
     * The cost of a wrong pick is bounded: both platforms apply the same transform, so DESIGN.md
     * §3's comparability law still holds and a wrong pick costs accuracy, never correctness — it
     * cannot make a wrong face match a right one.
     */
    const val INPUT_MIN = -1.0f
    const val INPUT_MAX = 1.0f

    // --- output tensors ----------------------------------------------------------------------------

    const val INPUT_TENSOR = "input"

    /** `[1, 896, 16]`: 4 box + 2 keypoint scores + 10 keypoint coordinates (5 points × xy). */
    const val REGRESSORS_TENSOR = "regressors"
    const val REGRESSORS_SHAPE = "regressors[1, 896, 16]"

    /** `[1, 896, 1]`: one **logit** per anchor. */
    const val CLASSIFICATORS_TENSOR = "classificators"
    const val CLASSIFICATORS_SHAPE = "classificators[1, 896, 1]"

    /**
     * Subgraph output order, read out of the flatbuffer's `subgraphs[0].outputs` vector: `175`
     * (`regressors`) then `174` (`classificators`).
     *
     * Not cosmetic. `Interpreter.run(Object, Object)` picks its output accessor from the *input*'s
     * type and writes output 0, then fails on output 1's shape; a two-output model must go through
     * `runForMultipleInputsOutputs` with an explicit index map, and the indices are these.
     */
    const val OUTPUT_INDEX_REGRESSORS = 0
    const val OUTPUT_INDEX_CLASSIFICATORS = 1

    /**
     * `SOFTMAX` is absent from the model's opcode set.
     *
     * Verified by walking the operator-code table in the flatbuffer: `ADD, CONCATENATION, CONV_2D,
     * DEPTHWISE_CONV_2D, DEQUANTIZE, MAX_POOL_2D, PAD, RELU, RESHAPE` — nine distinct ops, and
     * `SOFTMAX` (builtin code 25) is not among them. So `classificators` is a **logit** and the
     * caller applies the sigmoid.
     *
     * Two distinct wrong readings, and the second is the dangerous one because nothing reports an
     * error. Softmaxing a single logit returns 1.0 for every anchor, so every anchor becomes a
     * certain detection. Reading the logit as if it were already a probability leaves a confident
     * face at ≈0.5 and, worse, inverts where a score floor bites: in the committed `fires` fixture
     * the top anchor's logit is 0.919 (read raw, clears a 0.5 floor) while its probability is
     * 0.7149 (clears 0.5, fails 0.8). The floor stops meaning what its name says.
     */
    const val HAS_INNER_SOFTMAX = false

    // --- anchor-row layout -------------------------------------------------------------------------

    const val ANCHORS = 896
    const val ANCHOR_STRIDE = 16

    /** Offsets into a 16-float anchor row, named so the indexing cannot be silently misread. */
    const val BOX_X_CENTER = 0
    const val BOX_Y_CENTER = 1
    const val BOX_WIDTH = 2
    const val BOX_HEIGHT = 3

    /** Two values: position score then presence score. The graph does not consume them here. */
    const val KEYPOINT_SCORES = 4

    const val KEYPOINTS = 6
    const val KEYPOINT_COUNT = 5

    /**
     * FIVE keypoints, not the six the model card describes.
     *
     * The card documents the Tasks-library output, which synthesises a sixth point. The raw TFLite
     * gives right eye, left eye, nose, mouth-right, mouth-left.
     */
    const val OUTPUT_KEYPOINTS = KEYPOINT_COUNT

    // --- decode parameters -------------------------------------------------------------------------

    /** `x_scale = y_scale = w_scale = h_scale = 128.0` from the graph. */
    const val VARIANCE_SCALE = 128.0f

    /** The graph's own `min_score_thresh: 0.5`. */
    const val MIN_SCORE = 0.5f

    /**
     * Greedy-NMS IoU threshold, and the model's documented cap of 10 detections.
     *
     * Properties of the anchor layout and the model card's own limit, **not** screening thresholds
     * — those live in `ThresholdRegistry` and nowhere else (AGENTS.md §2, FR-R3). 0.3 suits
     * BlazeFace's dense small-anchor layout: the stride-8 group puts anchor centres on a 16×16
     * grid, so neighbours around one face overlap heavily and a higher threshold would let the same
     * face through several times.
     */
    const val IOU_THRESHOLD = 0.3f
    const val MAX_DETECTIONS = 10

    // --- the anchor grid ---------------------------------------------------------------------------

    /**
     * The 896 anchor centres `blaze_face_short_range.tflite` predicts against.
     *
     * ## Where the number comes from, and the part that is easy to get wrong
     *
     * The anchor grid is **not in the model file**. The model's `TFLITE_METADATA` is descriptive
     * only, and the graph has 164 ops with no anchor generator, so the grid is a *decoder* parameter
     * whose authority is the MediaPipe graph configured for this exact model,
     * `mediapipe/modules/face_detection/face_detection_short_range.pbtxt`:
     *
     * ```
     *   tensor_width: 128   tensor_height: 128
     *   num_layers: 4       strides: 8  16  16  16
     *   interpolated_scale_aspect_ratio: 1.0
     *   num_boxes: 896
     *   x_scale: 128.0      y_scale: 128.0      w_scale: 128.0      h_scale: 128.0
     *   min_score_thresh: 0.5
     * ```
     *
     * and `mediapipe/calculators/tflite/ssd_anchors_calculator.cc` `GenerateAnchors`:
     * `x_center = (x + anchor_offset_x) / feature_map_width` (likewise `y`), `scale(i) = min + (max −
     * min)·i/(n−1)`, and **consecutive layers sharing a stride are merged**, each merged layer
     * contributing `aspect_ratios.size() + 1` anchors per cell (the `+1` being the
     * `interpolated_scale_aspect_ratio`).
     *
     * That merge is the detail that is easy to miss, and missing it is what first made an earlier
     * version of this decoder produce 768 anchors instead of 896:
     *
     *  - layer 0 (stride 8) is its own group: feature map ⌈128/8⌉ = 16×16 = 256 cells,
     *    1 aspect ratio + 1 interpolated = **2 anchors/cell** → 512
     *  - layers 1, 2, 3 (all stride 16) merge into one group: feature map ⌈128/16⌉ = 8×8 = 64
     *    cells, 3 merged layers × (1 + 1) = **6 anchors/cell** → 384
     *  - 512 + 384 = **896**, which is exactly the `num_boxes` the graph declares *and* the
     *    regressor tensor's middle dimension. The count is therefore a *check* on this derivation
     *    rather than a coincidence.
     *
     * ## The part that is NOT verified
     *
     * `min_scale` / `max_scale` / `fixed_anchor_size` are **not** in the graph. The values behind
     * this decoder (0.1484375, 0.75, `fixed_anchor_size = true`) come from MediaPipe's own
     * short-range fallback block in
     * `mediapipe/tasks/cc/vision/face_detector/face_detector_graph.cc`.
     *
     * That limitation is worth being precise about, because `fixed_anchor_size = true` sets every
     * anchor's `w` and `h` to 1.0, which means the per-layer **scales cancel out of the decode
     * entirely** — they are computed by `GenerateAnchors` and then discarded, which is why no scale
     * is carried in [BlazeFaceGeometry] at all. So the uncertainty is confined to *box geometry*,
     * and it touches none of the score, its sigmoid, the ranking, which anchors survive NMS, or
     * whether a face is detected at all.
     *
     * Detection, scoring and suppression are what decide `UNAVAILABLE` versus "a face was found",
     * and those are unaffected. Box *coordinates* are the part to re-derive against a real face
     * when `D-FACE` has consented crops — the same blocker as the input-range fork above. See
     * `docs/spikes/01-face-model.md` §8 item 4.
     */
    fun anchorCentres(): AnchorCentres {
        val x = FloatArray(ANCHORS)
        val y = FloatArray(ANCHORS)
        var out = 0
        var layer = 0
        // Faithful to `GenerateAnchors`: walk the layers, merging consecutive equal strides into one
        // group, and emit every cell of the group's feature map once per anchor the merged group
        // contributes.
        while (layer < NUM_LAYERS) {
            var last = layer
            while (last < STRIDES.size && STRIDES[last] == STRIDES[layer]) last++
            val featureMap = ceilDiv(INPUT_SIDE, STRIDES[layer])
            val anchorsPerCell = (last - layer) * 2 // 1 aspect ratio + 1 interpolated, per layer
            for (cellY in 0 until featureMap) {
                for (cellX in 0 until featureMap) {
                    repeat(anchorsPerCell) {
                        require(out < ANCHORS) {
                            "anchor generation produced more than $ANCHORS anchors; the stride " +
                                "grouping no longer matches the graph's num_boxes (spike 01 §3.1)"
                        }
                        x[out] = (cellX + ANCHOR_OFFSET) / featureMap
                        y[out] = (cellY + ANCHOR_OFFSET) / featureMap
                        out++
                    }
                }
            }
            layer = last
        }
        check(out == ANCHORS) {
            "anchor generation produced $out anchors, graph declares $ANCHORS (spike 01 §3.1)"
        }
        return AnchorCentres(x, y)
    }

    /** The geometry this model decodes with. Built fresh; the arrays are not shared or mutable. */
    fun geometry(): BlazeFaceGeometry {
        val centres = anchorCentres()
        return BlazeFaceGeometry(
            anchors = ANCHORS,
            anchorStride = ANCHOR_STRIDE,
            xScale = VARIANCE_SCALE,
            yScale = VARIANCE_SCALE,
            wScale = VARIANCE_SCALE,
            hScale = VARIANCE_SCALE,
            anchorCentreX = centres.x,
            anchorCentreY = centres.y,
        )
    }

    /** All 896 anchor `x` values, in the generator's own emission order. */
    fun anchorCentreX(): FloatArray = anchorCentres().x

    /** All 896 anchor `y` values, in the generator's own emission order. */
    fun anchorCentreY(): FloatArray = anchorCentres().y

    /**
     * The two centre arrays as one value, so a caller that wants both does not generate the grid
     * twice — and, more to the point, cannot end up pairing the `x` of one generation with the
     * `y` of another.
     */
    data class AnchorCentres(val x: FloatArray, val y: FloatArray) {
        init {
            require(x.size == y.size) { "anchor centre arrays must be the same length" }
        }
    }

    const val NUM_LAYERS = 4
    val STRIDES = intArrayOf(8, 16, 16, 16)
    const val ANCHOR_OFFSET = 0.5f

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
}

/**
 * Tensor geometry for one SSD-style detector: how many anchors, how many floats per anchor, the
 * variance scales, and where each anchor's centre sits.
 *
 * A parameter rather than a constant so the decode can be pointed at a different detector without
 * editing the arithmetic — and so a test can hand it a deliberately wrong geometry and check that
 * the failure is loud rather than a plausible box from the wrong anchor. [BlazeFaceModel.geometry]
 * is the only geometry this project ships.
 */
class BlazeFaceGeometry(
    val anchors: Int,
    val anchorStride: Int,
    val xScale: Float,
    val yScale: Float,
    val wScale: Float,
    val hScale: Float,
    val anchorCentreX: FloatArray,
    val anchorCentreY: FloatArray,
) {
    init {
        require(anchors > 0) { "anchors must be positive, got $anchors" }
        require(anchorStride > 0) { "anchor stride must be positive, got $anchorStride" }
        require(anchorCentreX.size == anchors && anchorCentreY.size == anchors) {
            "anchor centre arrays must both hold $anchors entries, got " +
                "${anchorCentreX.size} and ${anchorCentreY.size}"
        }
        require(xScale > 0f && yScale > 0f && wScale > 0f && hScale > 0f) {
            "variance scales must be positive, got x=$xScale y=$yScale w=$wScale h=$hScale"
        }
    }

    /** Floats in the whole `regressors` tensor: `anchors * anchorStride`. */
    val regressorElements: Int get() = anchors * anchorStride

    /** Floats in the whole `classificators` tensor: one logit per anchor. */
    val classifierElements: Int get() = anchors
}
