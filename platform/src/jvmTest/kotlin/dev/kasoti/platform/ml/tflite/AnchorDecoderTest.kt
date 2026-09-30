package dev.kasoti.platform.ml.tflite

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The anchor grid and the SSD decode.
 *
 * These tests are the *reason* the decoder can be trusted at all, because the anchor layout is
 * not in the model file — it is a decoder parameter sourced from MediaPipe's graph and calculators
 * (see [BlazeFaceAnchors]'s header for the citations). Nothing here needs a model, a device or a
 * face, so it runs on every checkout including CI with an empty `eval/models/`.
 */
class AnchorDecoderTest {

    // --- the grid ---------------------------------------------------------------

    /**
     * The load-bearing assertion of the whole detector.
     *
     * The MediaPipe graph for this model declares `num_boxes: 896`, and the regressor tensor is
     * `[1, 896, 16]`. The anchor grid is generated from `num_layers: 4` and
     * `strides: 8 16 16 16` by an algorithm that **merges consecutive equal strides** — the
     * detail that is easy to miss and that yields 768 anchors if you drop it. If this test ever
     * fails, the grid no longer matches the model and every index in the decode is wrong; the
     * detector is then wrong in a way that produces plausible-looking boxes, so it must fail
     * loudly rather than degrade.
     */
    @Test
    fun `anchor grid produces exactly the 896 boxes the graph declares`() {
        assertEquals(BlazeFaceContract.ANCHORS, BlazeFaceAnchors.centerX().size)
        assertEquals(BlazeFaceContract.ANCHORS, BlazeFaceAnchors.centerY().size)
        assertEquals(896, BlazeFaceContract.ANCHORS)
    }

    @Test
    fun `stride-8 group comes first and is a 16x16 grid at two anchors per cell`() {
        // 16x16 cells x 2 anchors = 512 anchors occupy indices 0..511.
        // `GenerateAnchors` emits y-major, then x, then anchor: so cell (0,0) is indices 0 and 1
        // (same centre, two scales) and cell (0,1) is indices 2 and 3.
        assertEquals(0.03125f, BlazeFaceAnchors.centerX()[0], 1e-6f)   // (0 + 0.5) / 16
        assertEquals(0.03125f, BlazeFaceAnchors.centerY()[0], 1e-6f)
        assertEquals(0.03125f, BlazeFaceAnchors.centerX()[1], 1e-6f)   // same cell, 2nd anchor
        assertEquals(0.09375f, BlazeFaceAnchors.centerX()[2], 1e-6f)   // (1 + 0.5) / 16
        assertEquals(0.09375f, BlazeFaceAnchors.centerX()[3], 1e-6f)   // same cell, 2nd anchor
        assertEquals(BlazeFaceAnchors.centerX()[0], BlazeFaceAnchors.centerX()[1], 1e-6f)
        assertEquals(BlazeFaceAnchors.centerX()[2], BlazeFaceAnchors.centerX()[3], 1e-6f)
    }

    @Test
    fun `the three stride-16 layers merge into one 8x8 grid at six anchors per cell`() {
        // 512 anchors of the stride-8 group precede the merged group.
        assertEquals(512, MERGED_GROUP_START)
        // ⌈128/16⌉ = 8, so the first cell centre of the merged group is (0 + 0.5) / 8.
        assertEquals(0.0625f, BlazeFaceAnchors.centerX()[MERGED_GROUP_START], 1e-6f)
        // 6 anchors per cell, so cell (0,1) starts 6 later and is one eighth across.
        assertEquals(0.1875f, BlazeFaceAnchors.centerX()[MERGED_GROUP_START + 6], 1e-6f)
        // ... and cell (0,0)'s 6th anchor is the last of that cell.
        assertEquals(0.0625f, BlazeFaceAnchors.centerX()[MERGED_GROUP_START + 5], 1e-6f)
    }

    @Test
    fun `every anchor centre lies inside the frame`() {
        val xs = BlazeFaceAnchors.centerX()
        val ys = BlazeFaceAnchors.centerY()
        for (i in xs.indices) {
            assertTrue(xs[i] > 0f && xs[i] < 1f, "x centre out of frame at $i: ${xs[i]}")
            assertTrue(ys[i] > 0f && ys[i] < 1f, "y centre out of frame at $i: ${ys[i]}")
        }
    }

    /**
     * The interpolated scale is the geometric mean of a layer and the next, and 1.0 past the last
     * layer — the `sqrt(scale * scale_next)` line in `GenerateAnchors`. `fixed_anchor_size` then
     * discards these, so this test guards the *derivation* rather than any output.
     */
    @Test
    fun `interpolated scales are geometric means and the last one pairs with 1_0`() {
        val s0 = BlazeFaceAnchors.scaleAt(0)
        val s1 = BlazeFaceAnchors.scaleAt(1)
        val s2 = BlazeFaceAnchors.scaleAt(2)
        val s3 = BlazeFaceAnchors.scaleAt(3)
        assertEquals(0.1484375f, s0, 1e-6f)
        assertEquals(0.75f, s3, 1e-6f)
        // Layer 0 contributes [s0, sqrt(s0*s1)].
        assertEquals(s0, BlazeFaceAnchors.scaleOf(0), 1e-6f)
        assertEquals(kotlin.math.sqrt(s0 * s1), BlazeFaceAnchors.scaleOf(1), 1e-6f)
        // The merged group contributes 3 layers x 2 = 6, starting at cell (0,0) of the 8x8 grid.
        assertEquals(s1, BlazeFaceAnchors.scaleOf(MERGED_GROUP_START), 1e-6f)
        assertEquals(kotlin.math.sqrt(s3 * 1.0f), BlazeFaceAnchors.scaleOf(MERGED_GROUP_START + 5), 1e-6f)
        assertTrue(s0 < s1 && s1 < s2 && s2 < s3, "scales must increase with layer index")
    }

    // --- the sigmoid ------------------------------------------------------------

    @Test
    fun `sigmoid maps the logit range into 0 to 1`() {
        assertEquals(0.5f, AnchorDecoder.sigmoid(0f), 1e-6f)
        assertEquals(0.7310586f, AnchorDecoder.sigmoid(1f), 1e-5f)
        assertEquals(0.2689414f, AnchorDecoder.sigmoid(-1f), 1e-5f)
        assertEquals(0.0f, AnchorDecoder.sigmoid(-100f), 1e-7f)
        assertTrue(AnchorDecoder.sigmoid(100f) > 0.999f)
    }

    /**
     * The overflow trap. A naive `1/(1+exp(-x))` returns `NaN` for a large positive logit, and a
     * `NaN` score fails every comparison it takes part in — so a confident face silently vanishes
     * with no error anywhere. The stable branch is the whole reason this function exists.
     */
    @Test
    fun `sigmoid never returns NaN for an extreme logit`() {
        for (logit in listOf(0f, 1f, 40f, 88f, 89f, 100f, 1e9f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
            val s = AnchorDecoder.sigmoid(logit)
            assertTrue(s.isFinite(), "sigmoid($logit) was not finite: $s")
            assertTrue(s in 0f..1f, "sigmoid($logit) out of range: $s")
        }
    }

    @Test
    fun `sigmoid treats a NaN logit as no detection rather than propagating it`() {
        assertEquals(0f, AnchorDecoder.sigmoid(Float.NaN), 0f)
    }

    // --- the decode -------------------------------------------------------------

    @Test
    fun `a zero-offset anchor decodes to its own centre with unit size`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        // logit 0 -> score 0.5, above the graph's 0.0 floor but the test reads the list directly.
        val decoded = AnchorDecoder.decode(regressors, logits)
        assertEquals(BlazeFaceContract.ANCHORS, decoded.size)
        val first = decoded[0]
        assertEquals(BlazeFaceAnchors.centerX()[0], first.centerX, 1e-6f)
        assertEquals(BlazeFaceAnchors.centerY()[0], first.centerY, 1e-6f)
        // exp(0) * anchor.w(=1.0) = 1.0 in normalised units: the full frame.
        assertEquals(1f, first.width, 1e-6f)
        assertEquals(1f, first.height, 1e-6f)
        assertEquals(5, first.keypoints.size, "the raw TFLite gives 5 keypoints, not 6")
    }

    @Test
    fun `a centre offset is scaled by one over x_scale, which is 128`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        // x_raw = 128 -> x_raw / 128 = 1.0, so the centre moves by exactly one full unit.
        regressors[0][BlazeFaceContract.BOX_X_CENTER] = 128f
        val decoded = AnchorDecoder.decode(regressors, logits)
        assertEquals(BlazeFaceAnchors.centerX()[0] + 1f, decoded[0].centerX, 1e-6f)
    }

    @Test
    fun `a size offset is exponentiated`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        // w_raw = 128 -> exp(128/128) = e, times anchor.w = 1.0.
        regressors[0][BlazeFaceContract.BOX_WIDTH] = 128f
        val decoded = AnchorDecoder.decode(regressors, logits)
        assertEquals(kotlin.math.E.toFloat(), decoded[0].width, 1e-5f)
    }

    @Test
    fun `keypoints are decoded relative to their own anchor centre`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        regressors[0][BlazeFaceContract.KEYPOINTS + 4] = 128f // keypoint 2, x
        val decoded = AnchorDecoder.decode(regressors, logits)
        val kp = decoded[0].keypoints[2]
        assertEquals(BlazeFaceAnchors.centerX()[0] + 1f, kp.x, 1e-6f)
        assertEquals(BlazeFaceAnchors.centerY()[0], kp.y, 1e-6f)
    }

    /** An adversarial input: a huge size offset must not produce an infinite or NaN box. */
    @Test
    fun `an absurd regressor is dropped rather than producing a non-finite box`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        regressors[0][BlazeFaceContract.BOX_WIDTH] = Float.MAX_VALUE
        regressors[0][BlazeFaceContract.BOX_HEIGHT] = Float.MAX_VALUE
        val decoded = AnchorDecoder.decode(regressors, logits)
        assertTrue(decoded.none { !it.width.isFinite() || !it.height.isFinite() })
    }

    @Test
    fun `a NaN regressor does not poison the result`() {
        val regressors = zeroTensors()
        val logits = zeroTensors()
        regressors[3][BlazeFaceContract.BOX_X_CENTER] = Float.NaN
        val decoded = AnchorDecoder.decode(regressors, logits)
        assertTrue(decoded.none { !it.centerX.isFinite() || !it.centerY.isFinite() })
    }

    @Test
    fun `a wrong anchor count is refused outright`() {
        val tooFew = Array(10) { FloatArray(BlazeFaceContract.ANCHOR_STRIDE) }
        val threw = runCatching { AnchorDecoder.decode(tooFew, tooFew) }.exceptionOrNull()
        if (threw == null) fail("expected a refusal for a ${tooFew.size}-anchor tensor")
    }

    // --- NMS --------------------------------------------------------------------

    @Test
    fun `suppression collapses overlapping duplicates onto the best one`() {
        val strong = anchor(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.2f, score = 0.9f)
        val near = anchor(cx = 0.51f, cy = 0.5f, w = 0.2f, h = 0.2f, score = 0.8f)
        val kept = AnchorDecoder.nonMaximumSuppression(listOf(near, strong))
        assertEquals(1, kept.size)
        assertEquals(0.9f, kept[0].score, 1e-6f, "the highest score must survive")
    }

    @Test
    fun `distant faces are all kept`() {
        val anchors = listOf(
            anchor(0.2f, 0.2f, 0.15f, 0.15f, 0.9f),
            anchor(0.8f, 0.2f, 0.15f, 0.15f, 0.8f),
            anchor(0.2f, 0.8f, 0.15f, 0.15f, 0.7f),
        )
        assertEquals(3, AnchorDecoder.nonMaximumSuppression(anchors).size)
    }

    /** The model card's own cap: "Produces only up to a given limit (e.g. 10) of detections". */
    @Test
    fun `at most ten detections come back`() {
        val anchors = (0 until 40).map { i ->
            anchor(0.02f * i, 0.5f, 0.01f, 0.01f, 0.5f + i / 100f)
        }
        val kept = AnchorDecoder.nonMaximumSuppression(anchors)
        assertEquals(AnchorDecoder.MAX_DETECTIONS, kept.size)
        assertTrue(kept.size <= 10)
    }

    @Test
    fun `iou is zero for disjoint boxes and one for identical ones`() {
        val a = anchor(0.2f, 0.2f, 0.1f, 0.1f, 0.5f)
        val b = anchor(0.8f, 0.8f, 0.1f, 0.1f, 0.5f)
        assertEquals(0f, AnchorDecoder.iou(a, b), 0f)
        assertEquals(1f, AnchorDecoder.iou(a, a), 1e-6f)
        // Corner-touching boxes share no area.
        val c = anchor(0.3f, 0.3f, 0.1f, 0.1f, 0.5f)
        assertEquals(0f, AnchorDecoder.iou(a, c), 0f)
    }

    @Test
    fun `iou of two unit boxes overlapping by a quarter in each axis is 4 over 15`() {
        val a = anchor(0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
        val b = anchor(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        // intersection 0.25 x 0.25 = 0.0625; union 0.25 + 0.25 - 0.0625 = 0.4375
        assertEquals(0.0625f / 0.4375f, AnchorDecoder.iou(a, b), 1e-5f)
    }

    @Test
    fun `a score of exactly the floor is kept and just below it is not`() {
        val exactly = anchor(0.5f, 0.5f, 0.2f, 0.2f, TfliteBlazeFaceDetector.MIN_SCORE)
        val just = anchor(0.5f, 0.5f, 0.2f, 0.2f, TfliteBlazeFaceDetector.MIN_SCORE - 1e-4f)
        val kept = AnchorDecoder.nonMaximumSuppression(listOf(exactly, just))
        assertTrue(kept.any { abs(it.score - TfliteBlazeFaceDetector.MIN_SCORE) < 1e-6f })
        assertTrue(kept.none { it.score < TfliteBlazeFaceDetector.MIN_SCORE })
    }

    // --- helpers ----------------------------------------------------------------

    private companion object {
        /** 512 anchors of the stride-8 group precede the merged stride-16 group. */
        const val MERGED_GROUP_START = 512

        fun zeroTensors(): Array<FloatArray> =
            Array(BlazeFaceContract.ANCHORS) { FloatArray(BlazeFaceContract.ANCHOR_STRIDE) }

        fun anchor(cx: Float, cy: Float, w: Float, h: Float, score: Float) = FaceAnchor(
            centerX = cx,
            centerY = cy,
            width = w,
            height = h,
            score = score,
            keypoints = List(BlazeFaceContract.KEYPOINT_COUNT) { KeyPoint(cx, cy) },
        )
    }
}
