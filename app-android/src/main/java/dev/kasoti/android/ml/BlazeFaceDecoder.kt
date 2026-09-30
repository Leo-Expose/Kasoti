package dev.kasoti.android.ml

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** A keypoint in the same normalised [0,1] input-image units as [BlazeFaceAnchor]. */
data class BlazeFaceKeyPoint(val x: Float, val y: Float)

/**
 * One decoded anchor: a face the detector claims.
 *
 * [score] is a **probability**, squashed from the raw logit by [BlazeFaceDecoder.sigmoid] because
 * [BlazeFaceModel.HAS_INNER_SOFTMAX] is false. A caller that reads the raw `classificators` tensor
 * gets ≈0.5 for a face squarely in frame.
 *
 * Deliberately the same shape as `:platform`'s `FaceAnchor`, field for field, so the two can be
 * compared element-wise by `BlazeFaceDesktopParityTest` rather than by a hand-written field
 * mapping that could itself drift.
 */
data class BlazeFaceAnchor(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val keypoints: List<BlazeFaceKeyPoint>,
) {
    val left: Float get() = centerX - width / 2f
    val top: Float get() = centerY - height / 2f
    val right: Float get() = centerX + width / 2f
    val bottom: Float get() = centerY + height / 2f
    val area: Float get() = width * height
}

/**
 * Anchor decoding and non-maximum suppression, in pure Kotlin.
 *
 * ## Why this is not a library call
 *
 * DESIGN.md D4 rules OpenCV out of the build, and the rule is not a preference: a second
 * implementation of the same maths is a second set of bugs, and this maths is a few dozen lines.
 * More importantly, **NMS is part of the detector's contract**. If desktop and Android keep
 * different survivors from the same 896 anchors, the two platforms find different faces from
 * identical bytes and every downstream box, crop and threshold comparison inherits the difference.
 * Keeping it here — pure, testable against synthetic tensors and the committed fixture bytes, with
 * no model and no device — is the only way to make that checkable rather than hopeful.
 *
 * ## Units
 *
 * Normalised [0,1] input-image units throughout, exactly as MediaPipe's
 * `tensors_to_detections_calculator.cc` leaves them (`lines 822-831` and `864-867` of that file
 * are the authority for the formulas below). The caller scales to source-image pixels, so there is
 * exactly one place where a rounding choice could break cross-platform agreement.
 */
object BlazeFaceDecoder {

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
     * With `fixed_anchor_size = true` every `anchor.w` and `anchor.h` is 1.0, so those multiplies
     * degenerate. They are written here as the `1.0f` they are rather than omitted, because that is
     * what the source does and because a future `fixed_anchor_size = false` must be a one-token
     * change rather than a rewrite.
     *
     * @param regressors the `regressors` tensor flattened row-major: `anchors * anchorStride`.
     * @param classificators the `classificators` tensor flattened: one **logit** per anchor.
     *
     * @throws IllegalArgumentException if either length disagrees with the geometry, or if an
     *   anchor's geometry is not finite. A shape mismatch means every index below is wrong, and
     *   silently reading a shorter array would produce a plausible box from the wrong anchor. The
     *   binding catches it and reports "not measured", which is fail-closed (AGENTS.md §4).
     */
    fun decode(
        regressors: FloatArray,
        classificators: FloatArray,
        geometry: BlazeFaceGeometry,
    ): List<BlazeFaceAnchor> {
        require(regressors.size == geometry.regressorElements) {
            "regressors must hold ${geometry.regressorElements} floats for " +
                "${geometry.anchors} anchors x ${geometry.anchorStride}, got ${regressors.size}"
        }
        require(classificators.size == geometry.classifierElements) {
            "classificators must hold ${geometry.classifierElements} floats " +
                "(one logit per anchor), got ${classificators.size}"
        }
        val out = ArrayList<BlazeFaceAnchor>(geometry.anchors)
        for (i in 0 until geometry.anchors) {
            val base = i * geometry.anchorStride

            val w = expScale(
                raw = regressors[base + BlazeFaceModel.BOX_WIDTH],
                invScale = 1.0f / geometry.wScale,
            )
            val h = expScale(
                raw = regressors[base + BlazeFaceModel.BOX_HEIGHT],
                invScale = 1.0f / geometry.hScale,
            )
            if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

            val centerX = offset(
                regressors[base + BlazeFaceModel.BOX_X_CENTER],
                1.0f / geometry.xScale,
                geometry.anchorCentreX[i],
            )
            val centerY = offset(
                regressors[base + BlazeFaceModel.BOX_Y_CENTER],
                1.0f / geometry.yScale,
                geometry.anchorCentreY[i],
            )
            if (!centerX.isFinite() || !centerY.isFinite()) continue

            val keypoints = ArrayList<BlazeFaceKeyPoint>(BlazeFaceModel.KEYPOINT_COUNT)
            for (k in 0 until BlazeFaceModel.KEYPOINT_COUNT) {
                val kx = offset(
                    regressors[base + BlazeFaceModel.KEYPOINTS + k * 2],
                    1.0f / geometry.xScale,
                    geometry.anchorCentreX[i],
                )
                val ky = offset(
                    regressors[base + BlazeFaceModel.KEYPOINTS + k * 2 + 1],
                    1.0f / geometry.yScale,
                    geometry.anchorCentreY[i],
                )
                if (!kx.isFinite() || !ky.isFinite()) {
                    // A malformed keypoint makes the alignment downstream a guess, and a guessed
                    // alignment produces a plausible embedding of the wrong crop. Drop the anchor.
                    keypoints.clear()
                    break
                }
                keypoints += BlazeFaceKeyPoint(kx, ky)
            }
            if (keypoints.size != BlazeFaceModel.KEYPOINT_COUNT) continue

            out += BlazeFaceAnchor(
                centerX = centerX,
                centerY = centerY,
                width = w,
                height = h,
                // Squashed here, not by the model: there is no `SOFTMAX` in the graph.
                score = sigmoid(classificators[i]),
                keypoints = keypoints,
            )
        }
        return out
    }

    /**
     * [decode] against the pinned model's own geometry, which is the only geometry this project
     * ships. A convenience for callers that are not generalising, and the same two-argument shape
     * `:platform`'s `AnchorDecoder.decode` has, so the two read alike side by side.
     */
    fun decode(regressors: FloatArray, classificators: FloatArray): List<BlazeFaceAnchor> =
        decode(regressors, classificators, BlazeFaceModel.geometry())

    /**
     * The highest-scoring survivor at or above [BlazeFaceModel.MIN_SCORE], or `null`.
     *
     * The floor is applied **after** suppression, on the survivors, in that order — which is what
     * `:platform`'s `TfliteBlazeFaceDetector` does and therefore what parity requires. Applying it
     * before suppression would change which anchors compete with each other, which is a different
     * function rather than a different implementation of the same one.
     */
    fun bestFace(
        anchors: List<BlazeFaceAnchor>,
        minScore: Float = BlazeFaceModel.MIN_SCORE,
    ): BlazeFaceAnchor? = anchors.firstOrNull { it.score >= minScore }

    /** Decode, suppress, and take the best survivor: the whole post-inference path in one call. */
    fun detectBest(
        regressors: FloatArray,
        classificators: FloatArray,
        geometry: BlazeFaceGeometry = BlazeFaceModel.geometry(),
        minScore: Float = BlazeFaceModel.MIN_SCORE,
    ): BlazeFaceAnchor? = bestFace(nonMaximumSuppression(decode(regressors, classificators, geometry)), minScore)

    /** `raw / scale * 1.0 + anchorCentre`, with the `fixed_anchor_size` multiply made explicit. */
    private fun offset(raw: Float, invScale: Float, centre: Float): Float = raw * invScale * 1.0f + centre

    /**
     * `exp(raw / scale)` with the clamp that keeps `exp` finite.
     *
     * The clamp applies **after** the division: clamping the raw value would make the effective
     * scale wrong by a factor of the variance scale for every anchor. A `NaN` input returns `NaN`
     * rather than being clamped, so the caller's finite check drops that anchor — fail-closed and
     * correct, and much easier to reason about when the bound is explicit and named.
     */
    private fun expScale(raw: Float, invScale: Float): Float {
        val scaled = (raw * invScale).toDouble()
        if (scaled.isNaN()) return Float.NaN
        return exp(scaled.coerceIn(-MAX_LOG_SCALE, MAX_LOG_SCALE)).toFloat()
    }

    /**
     * Squash a detector logit to a probability.
     *
     * Numerically stable branch on the sign: `exp` of a large positive number overflows to
     * infinity, the subtraction then yields `NaN`, and a `NaN` score fails every comparison it
     * takes part in — so the face silently disappears from the results with no error anywhere. The
     * same sigmoid is at `tensors_to_detections_calculator.cc:445`.
     */
    fun sigmoid(logit: Float): Float {
        if (logit.isNaN()) return 0f
        if (logit >= 0f) return (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
        val z = exp(logit.toDouble())
        return (z / (1.0 + z)).toFloat()
    }

    /**
     * Greedy non-maximum suppression, highest score first, at most [maxDetections] survivors.
     *
     * `sortedByDescending` is a stable sort, so anchors with equal scores keep their emission order
     * and the result is deterministic — which is what makes this function comparable between
     * platforms at all. An unstable sort would be equally correct in isolation and would make the
     * two platforms disagree on a tie.
     */
    fun nonMaximumSuppression(
        anchors: List<BlazeFaceAnchor>,
        iouThreshold: Float = BlazeFaceModel.IOU_THRESHOLD,
        maxDetections: Int = BlazeFaceModel.MAX_DETECTIONS,
    ): List<BlazeFaceAnchor> {
        if (anchors.isEmpty()) return emptyList()
        val kept = ArrayList<BlazeFaceAnchor>(min(anchors.size, maxDetections))
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
    fun iou(a: BlazeFaceAnchor, b: BlazeFaceAnchor): Float {
        val interW = (min(a.right, b.right) - max(a.left, b.left)).toDouble()
        val interH = (min(a.bottom, b.bottom) - max(a.top, b.top)).toDouble()
        if (interW <= 0.0 || interH <= 0.0) return 0f
        val intersection = interW * interH
        val union = (a.area + b.area).toDouble() - intersection
        if (union <= 0.0) return 0f
        return (intersection / union).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Bound on `exp`'s argument, so `w` and `h` top out at `e^8 ≈ 2981` in normalised units.
     *
     * A deliberately absurd bound: a face box thousands of frame-widths wide is not a detection
     * suppression should reason about, it is a diverged regressor. Clamping rather than dropping
     * keeps the anchor's score and keypoints available, which is what the alignment crop needs.
     */
    private const val MAX_LOG_SCALE = 8.0
}
