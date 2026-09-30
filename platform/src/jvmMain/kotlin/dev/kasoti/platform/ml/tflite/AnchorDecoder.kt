package dev.kasoti.platform.ml.tflite

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The 896 anchor centres `blaze_face_short_range.tflite` predicts against.
 *
 * ## Where every number here comes from
 *
 * The anchor grid is **not in the model file** — the model's `TFLITE_METADATA` is descriptive
 * only (producer "MediaPipe", tensor descriptions, no `face_detector` options block), and the
 * graph has 164 ops and no anchor generator. Anchors are a *decoder* parameter, so the
 * authority is the MediaPipe graph configured for this exact model plus the two calculators it
 * uses. Nothing here is reconstructed from memory or from a general SSD convention.
 *
 * From `mediapipe/modules/face_detection/face_detection_short_range.pbtxt` (the graph for
 * `blaze_face_short_range.tflite`, the same 128×128 model):
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
 * From `mediapipe/calculators/tflite/ssd_anchors_calculator.cc` `GenerateAnchors`:
 * `x_center = (x + anchor_offset_x) / feature_map_width`, `y_center` likewise,
 * `scale(i) = min + (max − min)·i/(n−1)`, and **consecutive layers sharing a stride are merged**,
 * each contributing `aspect_ratios.size() + 1` anchors per cell (the `+1` being the
 * `interpolated_scale_aspect_ratio`).
 *
 * That merge is the part that is easy to get wrong, and getting it wrong is what first made this
 * file's predecessor produce 768 anchors instead of 896:
 *
 *  - layers 0 (stride 8) is its own group: feature map ⌈128/8⌉ = 16×16 = 256 cells,
 *    1 aspect ratio + 1 interpolated = **2 anchors/cell** → 512
 *  - layers 1, 2, 3 (all stride 16) merge into one group: feature map ⌈128/16⌉ = 8×8 = 64 cells,
 *    3 merged layers × (1 + 1) = **6 anchors/cell** → 384
 *  - 512 + 384 = **896**, which is exactly the `num_boxes: 896` the graph declares. The count is
 *    therefore a *check* on this derivation, not a coincidence, and
 *    `BlazeFaceAnchorsTest` asserts it.
 *
 * ## The part that is NOT verified
 *
 * `min_scale` / `max_scale` / `fixed_anchor_size` are **not** in the graph. The values used here
 * (0.1484375, 0.75, and `fixed_anchor_size = true`) come from MediaPipe's own short-range
 * fallback block in `mediapipe/tasks/cc/vision/face_detector/face_detector_graph.cc`, which
 * configures the 128-px short-range model. The legacy `FaceDetection` calculator that the older
 * graph used is no longer in the MediaPipe tree, so its values could not be read.
 *
 * This is a real limitation and it is worth being precise about what it does and does not
 * affect. Because `fixed_anchor_size = true` sets every anchor's `w` and `h` to 1.0, the
 * per-layer **scales cancel out of the decode entirely** — they are computed by
 * `GenerateAnchors` and then discarded. So the uncertainty is confined to *box geometry*, and it
 * does **not** touch:
 *  - the confidence score, its sigmoid, or the ranking,
 *  - which anchors survive NMS,
 *  - whether a face is detected at all.
 *
 * Detection, scoring and suppression are the parts that decide `UNAVAILABLE` vs "a face was
 * found", and those are unaffected. Box *coordinates* are the part to re-derive against a real
 * face image when `D-FACE` has consented crops — the same blocker as the input-range question
 * in [BlazeFaceContract]. See `docs/spikes/01-face-model.md` §8.
 */
object BlazeFaceAnchors {

    const val NUM_LAYERS = 4
    val STRIDES = intArrayOf(8, 16, 16, 16)
    const val INTERPOLATED_SCALE_ASPECT_RATIO = 1.0f
    const val MIN_SCALE = 0.1484375f
    const val MAX_SCALE = 0.75f
    const val ANCHOR_OFFSET = 0.5f
    const val FIXED_ANCHOR_SIZE = true

    /** The graph's declared `num_boxes` and the regressor tensor's middle dimension. */
    const val COUNT = 896

    /** (x_center, y_center) for all 896 anchors, in the generator's own emission order. */
    private class Centres(val x: FloatArray, val y: FloatArray)

    private val centres: Centres by lazy { build() }

    /** All 896 `x_center` values, in the generator's own emission order. */
    fun centerX(): FloatArray = centres.x

    /** All 896 `y_center` values, in the generator's own emission order. */
    fun centerY(): FloatArray = centres.y

    /**
     * One scale per anchor, retained only so the derivation is inspectable and testable.
     *
     * `fixed_anchor_size = true` means the decode ignores these (every anchor's `w`/`h` is
     * forced to 1.0), but keeping them lets a test assert the interpolation arithmetic, and keeps
     * the door open for a future `fixed_anchor_size = false` path without re-deriving anything.
     */
    private val LAYER_SCALES: FloatArray by lazy { buildScales() }

    fun scaleOf(index: Int): Float = LAYER_SCALES[index]

    private fun build(): Centres {
        val xs = FloatArray(COUNT)
        val ys = FloatArray(COUNT)
        var out = 0
        var layer = 0
        // Faithful to `GenerateAnchors`: walk layers, merging consecutive equal strides into one
        // group, and emit every cell of the group's feature map for every anchor the merged
        // group contributes.
        while (layer < NUM_LAYERS) {
            var last = layer
            while (last < STRIDES.size && STRIDES[last] == STRIDES[layer]) last++
            val featureMap = ceilDiv(BlazeFaceContract.INPUT_SIDE, STRIDES[layer])
            val anchorsPerCell = (last - layer) * 2 // 1 aspect ratio + 1 interpolated, per layer
            for (y in 0 until featureMap) {
                for (x in 0 until featureMap) {
                    repeat(anchorsPerCell) {
                        require(out < COUNT) {
                            "anchor generation produced more than $COUNT anchors; the stride " +
                                "grouping in BlazeFaceAnchors no longer matches the graph's " +
                                "num_boxes: 896 (spike 01 §3.1)"
                        }
                        xs[out] = (x + ANCHOR_OFFSET) / featureMap
                        ys[out] = (y + ANCHOR_OFFSET) / featureMap
                        out++
                    }
                }
            }
            layer = last
        }
        check(out == COUNT) {
            "anchor generation produced $out anchors, graph declares $COUNT (spike 01 §3.1)"
        }
        return Centres(xs, ys)
    }

    private fun buildScales(): FloatArray {
        val out = FloatArray(COUNT)
        var outIndex = 0
        var layer = 0
        while (layer < NUM_LAYERS) {
            var last = layer
            while (last < STRIDES.size && STRIDES[last] == STRIDES[layer]) last++
            val perLayer = buildScalesForMergedGroup(layer, last)
            val featureMap = ceilDiv(BlazeFaceContract.INPUT_SIDE, STRIDES[layer])
            val cells = featureMap * featureMap
            for (c in 0 until cells) {
                for (s in perLayer) out[outIndex++] = s
            }
            layer = last
        }
        return out
    }

    /**
     * The scales one merged stride group contributes, in emission order: for each merged layer,
     * the layer's own scale then the geometric mean of it and the next layer's scale (1.0 past
     * the last layer).
     */
    private fun buildScalesForMergedGroup(fromLayer: Int, toLayerExclusive: Int): FloatArray {
        val result = ArrayList<Float>((toLayerExclusive - fromLayer) * 2)
        for (l in fromLayer until toLayerExclusive) {
            val scale = scaleAt(l)
            result += scale
            val next = if (l == STRIDES.size - 1) 1.0f else scaleAt(l + 1)
            result += sqrt(scale * next)
        }
        return result.toFloatArray()
    }

    /** `CalculateScale(min_scale, max_scale, stride_index, num_strides)`. */
    fun scaleAt(index: Int): Float {
        val n = STRIDES.size
        if (n == 1) return (MIN_SCALE + MAX_SCALE) * 0.5f
        return MIN_SCALE + (MAX_SCALE - MIN_SCALE) * index / (n - 1f)
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
}

/**
 * One decoded anchor: a face the detector claims.
 *
 * [score] is a *probability*, squashed from the raw logit — the model has no `SOFTMAX`
 * ([BlazeFaceContract.HAS_INNER_SOFTMAX] is `false`). A caller that reads the raw
 * `classificators` tensor gets ≈0.5 for a face squarely in frame.
 */
data class FaceAnchor(
    /** Centre-relative box, in normalised [0,1] input-image units. */
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val keypoints: List<KeyPoint>,
) {
    val left: Float get() = centerX - width / 2f
    val top: Float get() = centerY - height / 2f
    val right: Float get() = centerX + width / 2f
    val bottom: Float get() = centerY + height / 2f
    val area: Float get() = width * height
}

/** A keypoint in the same normalised [0,1] input-image units as [FaceAnchor]. */
data class KeyPoint(val x: Float, val y: Float)

/**
 * Anchor decoding and non-maximum suppression, in pure Kotlin.
 *
 * ## Why this is not a library call
 *
 * DESIGN.md D4 rules OpenCV out of the build, and the rule is not a preference: a second
 * implementation of the same maths is a second set of bugs, and this maths is a few dozen lines.
 * More importantly, **NMS is part of the detector's contract**. If the desktop and Android keep
 * different survivors from the same 896 anchors, the two platforms find different faces from
 * identical bytes, and every downstream box, crop and threshold comparison inherits the
 * difference. Keeping it here — pure, testable with synthetic tensors, no model and no device —
 * is the only way to make that checkable rather than hopeful.
 *
 * ## Units
 *
 * Normalised [0,1] input-image units throughout, exactly as MediaPipe's
 * `tensors_to_detections_calculator.cc` leaves them (`lines 822-831` and `864-867` of that file
 * are the authority for the formulas below). The caller scales to source-image pixels, so there
 * is exactly one place where a rounding choice could break cross-platform agreement.
 */
object AnchorDecoder {

    /**
     * The SSD decode, transcribed from MediaPipe's `tensors_to_detections_calculator.cc`.
     *
     * ```
     * x_center = x_raw / x_scale * anchor.w + anchor.x_center
     * y_center = y_raw / y_scale * anchor.h + anchor.y_center
     * w        = exp(w_raw / w_scale) * anchor.w
     * h        = exp(h_raw / h_scale) * anchor.h
     * kx       = kx_raw / x_scale * anchor.w + anchor.x_center
     * ky       = ky_raw / y_scale * anchor.h + anchor.y_center
     * ```
     *
     * With `fixed_anchor_size = true` every `anchor.w` and `anchor.h` is 1.0, so the multiply
     * degenerates — kept in the expression anyway, because that is what the source does and
     * because a future `fixed_anchor_size = false` must not be a rewrite.
     *
     * @param regressors the `regressors` tensor, `[COUNT][ANCHOR_STRIDE]`, unmodified.
     * @param logits the `classificators` tensor, `[COUNT][1]`. Squashed here, not by the model.
     */
    fun decode(regressors: Array<FloatArray>, logits: Array<FloatArray>): List<FaceAnchor> {
        require(regressors.size == BlazeFaceContract.ANCHORS) {
            "expected ${BlazeFaceContract.ANCHORS} anchors, got ${regressors.size}"
        }
        val anchorX = BlazeFaceAnchors.centerX()
        val anchorY = BlazeFaceAnchors.centerY()
        val out = ArrayList<FaceAnchor>(regressors.size)
        for (i in 0 until regressors.size) {
            val row = regressors[i]
            if (row.size < BlazeFaceContract.ANCHOR_STRIDE) continue
            val logit = logits.getOrNull(i)?.firstOrNull() ?: continue
            val score = sigmoid(logit)
            if (score <= 0f) continue

            val w = expScale(row[BlazeFaceContract.BOX_WIDTH])
            val h = expScale(row[BlazeFaceContract.BOX_HEIGHT])
            if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

            val centerX = row[BlazeFaceContract.BOX_X_CENTER] * ANCHOR_EXTENT + anchorX[i]
            val centerY = row[BlazeFaceContract.BOX_Y_CENTER] * ANCHOR_EXTENT + anchorY[i]
            if (!centerX.isFinite() || !centerY.isFinite()) continue

            val keypoints = (0 until BlazeFaceContract.KEYPOINT_COUNT).map { k ->
                KeyPoint(
                    x = row[BlazeFaceContract.KEYPOINTS + k * 2] * ANCHOR_EXTENT + anchorX[i],
                    y = row[BlazeFaceContract.KEYPOINTS + k * 2 + 1] * ANCHOR_EXTENT + anchorY[i],
                )
            }

            out += FaceAnchor(
                centerX = centerX,
                centerY = centerY,
                width = w,
                height = h,
                score = score,
                keypoints = keypoints,
            )
        }
        return out
    }

    /**
     * `exp(raw / w_scale) * anchor.w` with `anchor.w = 1.0` and `w_scale = 128.0`.
     *
     * The clamp keeps `exp` finite. Without it a large regressor gives `exp(∞) = ∞` and the
     * anchor is dropped by an `isFinite` check above — which is *fail-closed and correct*, but
     * it is much easier to reason about when the bound is explicit and named. Note the clamp
     * applies **after** the division: clamping the raw value would make the effective scale
     * wrong by a factor of 128 for every anchor.
     */
    private fun expScale(raw: Float): Float {
        val scaled = (raw * ANCHOR_EXTENT).toDouble()
        if (scaled.isNaN()) return Float.NaN
        return exp(scaled.coerceIn(-MAX_LOG_SCALE, MAX_LOG_SCALE)).toFloat()
    }

    /**
     * Squash a detector logit to a probability.
     *
     * Numerically stable branch on the sign: `exp` of a large positive number overflows to
     * infinity, the subtraction then yields `NaN`, and a `NaN` score fails every comparison it
     * takes part in — so the face silently disappears from the results with no error anywhere.
     * The same sigmoid is at `tensors_to_detections_calculator.cc:445`.
     */
    fun sigmoid(logit: Float): Float {
        if (logit.isNaN()) return 0f
        if (logit >= 0f) {
            return (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
        }
        val z = exp(logit.toDouble())
        return (z / (1.0 + z)).toFloat()
    }

    /**
     * Greedy non-maximum suppression, highest score first.
     *
     * The IoU threshold is 0.3, matching the graph's dense small-anchor layout: BlazeFace's
     * stride-8 group puts anchor centres on a 16×16 grid, so neighbouring anchors around one face
     * overlap heavily and a higher threshold would let the same face through several times.
     *
     * It is a property of the anchor layout, not a screening threshold, so it belongs with the
     * model constants rather than in `ThresholdRegistry` — but it is named and documented because
     * a *screening* threshold ending up in this file would be a bug (AGENTS.md §2).
     */
    fun nonMaximumSuppression(
        anchors: List<FaceAnchor>,
        iouThreshold: Float = IOU_THRESHOLD,
        maxDetections: Int = MAX_DETECTIONS,
    ): List<FaceAnchor> {
        if (anchors.isEmpty()) return emptyList()
        val kept = ArrayList<FaceAnchor>(min(anchors.size, maxDetections))
        for (candidate in anchors.sortedByDescending { it.score }) {
            if (kept.size >= maxDetections) break
            if (kept.none { iou(candidate, it) > iouThreshold }) kept += candidate
        }
        return kept
    }

    /**
     * Intersection over union, in normalised units. Zero for boxes that do not overlap.
     *
     * Accumulated in `Double`: these are products and sums over up to 896 anchors, and a negative
     * intersection from single-precision rounding would flip a suppression decision.
     */
    fun iou(a: FaceAnchor, b: FaceAnchor): Float {
        val interW = (min(a.right, b.right) - max(a.left, b.left)).toDouble()
        val interH = (min(a.bottom, b.bottom) - max(a.top, b.top)).toDouble()
        if (interW <= 0.0 || interH <= 0.0) return 0f
        val intersection = interW * interH
        val union = (a.area + b.area).toDouble() - intersection
        if (union <= 0.0) return 0f
        return (intersection / union).toFloat().coerceIn(0f, 1f)
    }

    /**
     * `1 / x_scale` with `x_scale = 128.0` from the graph, and `anchor.w = 1.0`.
     *
     * Named so the decode reads as the source reads, and so the value is greppable next to
     * [BlazeFaceContract.INPUT_SIDE] — they are the same 128 for different reasons (one is the
     * tensor edge, the other the SSD variance scale) and a reader should be able to check both.
     */
    private const val ANCHOR_EXTENT = 1.0f / 128.0f

    const val IOU_THRESHOLD = 0.3f

    /** Matches the model card's "Produces only up to a given limit (e.g. 10) of detections". */
    const val MAX_DETECTIONS = 10

    private const val MAX_LOG_SCALE = 8.0
}
