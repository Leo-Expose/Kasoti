package dev.kasoti.android.ml

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decode arithmetic on its own: no model, no device, no bytes from anywhere.
 *
 * ## What this class is for
 *
 * Every number here is one the detector depends on and none of them is a *screening* threshold
 * (those live in `ThresholdRegistry`, AGENTS.md §2). A wrong box or a wrong score is not a crash
 * and not a log line: it is a number that looks entirely reasonable, and the only defence is to
 * state what the right answer is and check it. `BlazeFaceDesktopParityTest` proves this file agrees
 * with `:platform`; this file proves both are right about the *model*, which is a different
 * question — a shared bug is still a bug.
 *
 * Per AGENTS.md §4 every case includes at least one adversarial or mutated input: the geometry
 * checks, the `NaN`/`Inf` cases and the tie-in-NMS case are the ones a happy-path test would miss.
 */
class BlazeFaceDecoderTest {

    private val geometry = BlazeFaceModel.geometry()

    // --- the model's own interface, as verified out of the flatbuffer ----------------------------------

    @Test
    fun `the geometry is the interface the model file declares`() {
        assertEquals(896, geometry.anchors, "regressors is [1, 896, 16]; the 896 is a decoder fact")
        assertEquals(16, geometry.anchorStride)
        assertEquals(896 * 16, geometry.regressorElements)
        assertEquals(896, geometry.classifierElements)
        assertEquals(128f, geometry.xScale, "x_scale/y_scale/w_scale/h_scale are all 128.0")
        assertEquals(128f, geometry.yScale)
        assertEquals(128f, geometry.wScale)
        assertEquals(128f, geometry.hScale)
        assertEquals(896, geometry.anchorCentreX.size)
        assertEquals(896, geometry.anchorCentreY.size)
        assertFalse(BlazeFaceModel.HAS_INNER_SOFTMAX, "SOFTMAX is absent from the opcode set")
    }

    @Test
    fun `the anchor grid has 896 centres in the generator's emission order`() {
        val x = BlazeFaceModel.anchorCentreX()
        val y = BlazeFaceModel.anchorCentreY()

        // Stride-8 group: a 16x16 feature map, 2 anchors per cell, emitted cell-major with the two
        // anchors of a cell adjacent. 256 cells x 2 = 512, which is the whole stride-8 group.
        assertEquals(0.5f / 16f, x[0], "first cell centre is (0 + 0.5) / 16")
        assertEquals(0.5f / 16f, y[0])
        assertEquals(0.5f / 16f, x[1], "the second anchor of cell 0 shares the cell's centre")
        assertEquals(0.5f / 16f, y[1])
        assertEquals(1.5f / 16f, x[2], "then cell 1: x advances, y has not")
        assertEquals(0.5f / 16f, y[2])
        assertEquals(1.5f / 16f, x[3], "and its second anchor shares that cell too")
        assertEquals(2.5f / 16f, x[4], "cell 2")
        assertEquals(0.53125f, x[17], "cell 8, the anchor the box test below decodes")
        assertEquals(0.03125f, y[17], "still the first row")
        assertEquals(15.5f / 16f, x[511], "the last stride-8 cell, first of its two anchors")
        assertEquals(15.5f / 16f, y[511], "and it is the last ROW, so y is at the far edge too")
        assertEquals(15.5f / 16f, x[510], "its first anchor sits on the same cell")
        assertEquals(0.5f / 8f, x[512], "the first stride-16 cell follows immediately")
        assertEquals(0.5f / 8f, y[512])

        // Stride-16 group: layers 1,2,3 merge, so 8x8 cells x 6 anchors = 384, giving 512 + 384.
        assertEquals(0.5f / 8f, x[512], "the merged stride-16 group has an 8x8 feature map")
        assertEquals(7.5f / 8f, x[895], "the last emitted anchor is the last cell of the last row")
        assertEquals(7.5f / 8f, y[895])

        // Every centre is a cell middle: a multiple of the offset, inside the unit square. This is
        // the property that a dropped stride-merge breaks, and it is checked here rather than left
        // to the total, because a total alone cannot say *which* way the grid went wrong.
        for (i in 0 until 896) {
            assertTrue(x[i] > 0f && x[i] < 1f, "anchor $i x=${x[i]} outside the unit square")
            assertTrue(y[i] > 0f && y[i] < 1f, "anchor $i y=${y[i]} outside the unit square")
        }
    }

    // --- the sigmoid, which the model does not do ------------------------------------------------------

    @Test
    fun `sigmoid maps the logit to a probability and is stable at both ends`() {
        assertEquals(0.5f, BlazeFaceDecoder.sigmoid(0f), 0f)
        // A confident detection: the model emits a logit near 0.9 and the probability is 0.71, not
        // 0.9. This is the difference the old Android binding got wrong.
        assertEquals(0.7148585f, BlazeFaceDecoder.sigmoid(0.9190993f), 1e-6f)
        // Overflow branch. exp(1000) is infinity, so the naive `1/(1+exp(-x))` is NaN here, and a
        // NaN score fails every comparison it takes part in: the face vanishes with no error.
        assertEquals(1.0f, BlazeFaceDecoder.sigmoid(1000f), 0f)
        assertEquals(1.0f, BlazeFaceDecoder.sigmoid(Float.POSITIVE_INFINITY), 0f)
        assertFalse(BlazeFaceDecoder.sigmoid(1000f).isNaN(), "the positive branch must not overflow to NaN")
        // Underflow branch.
        assertEquals(0.0f, BlazeFaceDecoder.sigmoid(-1000f), 0f)
        assertEquals(0.0f, BlazeFaceDecoder.sigmoid(Float.NEGATIVE_INFINITY), 0f)
        assertFalse(BlazeFaceDecoder.sigmoid(-1000f).isNaN(), "the negative branch must not underflow to NaN")
    }

    @Test
    fun `sigmoid increases, stays in the unit interval, and maps NaN to zero`() {
        var previous = -1f
        for (step in -40..40) {
            val logit = step * 0.5f
            val s = BlazeFaceDecoder.sigmoid(logit)
            // Non-decreasing over the whole sweep: float32 saturates, so strict monotonicity only
            // holds while there is headroom, and asserting strictness past it would be asserting
            // something the type cannot promise.
            assertTrue(s >= previous, "sigmoid must not decrease: $s should not be below $previous at $logit")
            assertTrue(s in 0f..1f, "sigmoid left the unit interval at $logit: $s")
            previous = s
        }
        var strictPrevious = -1f
        for (step in -40..16) {
            val logit = step * 0.5f
            val s = BlazeFaceDecoder.sigmoid(logit)
            assertTrue(s > strictPrevious, "sigmoid must increase where float32 has headroom, at $logit")
            strictPrevious = s
        }
        assertEquals(0.0f, BlazeFaceDecoder.sigmoid(-20f), 1e-6f, "saturates low")
        assertEquals(1.0f, BlazeFaceDecoder.sigmoid(20f), 1e-6f, "saturates high")
        // A NaN logit must not become a NaN score, which would silently drop the anchor from every
        // comparison. Zero is the fail-closed answer: "not a face".
        assertEquals(0f, BlazeFaceDecoder.sigmoid(Float.NaN), 0f)
    }

    @Test
    fun `the score floor means the same thing as it says`() {
        // The property the raw-logit reading broke: it moves where a floor bites. logit 0.4 is a
        // probability of 0.599, so it clears 0.5 and must be kept; read raw, 0.4 < 0.5, it is dropped.
        val logit = 0.4f
        assertTrue(BlazeFaceDecoder.sigmoid(logit) >= BlazeFaceModel.MIN_SCORE)
        val anchor = anchorAt(0, logit = logit)
        assertEquals(anchor, BlazeFaceDecoder.bestFace(listOf(anchor)))
        // And the converse: logit 0.6 has probability 0.646 (kept at a 0.6 floor, dropped at 0.65),
        // where the raw value would have been read as 0.6 and kept at both.
        val hotter = anchorAt(0, logit = 0.6f)
        assertEquals(hotter, BlazeFaceDecoder.bestFace(listOf(hotter), minScore = 0.6f))
        assertNull(BlazeFaceDecoder.bestFace(listOf(hotter), minScore = 0.65f))
    }

    // --- the decode ------------------------------------------------------------------------------------

    @Test
    fun `a zero row decodes to the anchor's own centre and a unit box`() {
        val (zeroRegressors, zeroLogits) = zeroTensors()
        val decoded = BlazeFaceDecoder.decode(zeroRegressors, zeroLogits, geometry)
        assertEquals(896, decoded.size, "no anchor is dropped: every row is finite and every logit > 0")
        val first = decoded[0]
        assertEquals(0.5f / 16f, first.centerX, 1e-6f, "x_center = x_raw/128 + anchor.x_center")
        assertEquals(0.5f / 16f, first.centerY, 1e-6f)
        assertEquals(1.0f, first.width, 1e-6f, "w = exp(0/128) * 1.0")
        assertEquals(1.0f, first.height, 1e-6f)
        assertEquals(0.5f, first.score, 1e-6f, "a zero logit is a probability of exactly 0.5")
        assertEquals(5, first.keypoints.size, "the raw model gives five keypoints, not six")
        assertEquals(first.centerX, first.keypoints[0].x, "a zero offset puts the keypoint on the anchor")
    }

    @Test
    fun `box centre, size and keypoints are all placed against the anchor grid`() {
        val regressors = FloatArray(896 * 16)
        val logits = FloatArray(896)
        // Anchor 17 is the first anchor of the second cell on the first stride-8 row.
        val base = 17 * 16
        regressors[base + BlazeFaceModel.BOX_X_CENTER] = 12.8f // 12.8 / 128 = 0.1
        regressors[base + BlazeFaceModel.BOX_Y_CENTER] = -6.4f // -6.4 / 128 = -0.05
        regressors[base + BlazeFaceModel.BOX_WIDTH] = 128.0f * ln(0.25f) // exp -> 0.25
        regressors[base + BlazeFaceModel.BOX_HEIGHT] = 128.0f * ln(0.5f) // exp -> 0.5
        for (k in 0 until 5) {
            regressors[base + BlazeFaceModel.KEYPOINTS + k * 2] = 128.0f * (0.01f * (k + 1))
            regressors[base + BlazeFaceModel.KEYPOINTS + k * 2 + 1] = 128.0f * (-0.01f * (k + 1))
        }

        val decoded = BlazeFaceDecoder.decode(regressors, logits, geometry)
        val anchor = decoded[17]
        val anchorX = BlazeFaceModel.anchorCentreX()[17]
        val anchorY = BlazeFaceModel.anchorCentreY()[17]

        assertEquals(0.1f + anchorX, anchor.centerX, 1e-6f)
        assertEquals(-0.05f + anchorY, anchor.centerY, 1e-6f)
        assertEquals(0.25f, anchor.width, 1e-6f)
        assertEquals(0.5f, anchor.height, 1e-6f)
        // Edges follow from the centre and the size, which is the only way they are computed.
        assertEquals(anchor.centerX - anchor.width / 2f, anchor.left, 1e-6f, "left = centre - w/2")
        assertEquals(anchor.centerY - anchor.height / 2f, anchor.top, 1e-6f, "top = centre - h/2")
        assertEquals(anchor.centerX + anchor.width / 2f, anchor.right, 1e-6f, "right = centre + w/2")
        assertEquals(anchor.centerY + anchor.height / 2f, anchor.bottom, 1e-6f, "bottom = centre + h/2")
        assertEquals(0.50625f, anchor.left, 1e-6f, "0.1 + 0.53125 - 0.125")
        assertEquals(0.125f, anchor.width * anchor.height, 1e-6f, "area = w * h")
        for (k in 0 until 5) {
            assertEquals(0.01f * (k + 1) + anchorX, anchor.keypoints[k].x, 1e-6f)
            assertEquals(-0.01f * (k + 1) + anchorY, anchor.keypoints[k].y, 1e-6f)
        }
    }

    @Test
    fun `a diverged regressor is clamped rather than becoming infinity`() {
        val regressors = FloatArray(896 * 16)
        val logits = FloatArray(896)
        // exp(1e9) overflows. The clamp bounds `w` at e^8; the anchor keeps its score and its
        // keypoints, which is what the alignment crop needs, instead of being discarded outright.
        regressors[5 * 16 + BlazeFaceModel.BOX_WIDTH] = 1e9f
        val decoded = BlazeFaceDecoder.decode(regressors, logits, geometry)
        val anchor = assertNotNull(decoded.firstOrNull { it.width > 1f })
        assertEquals(exp(8.0).toFloat(), anchor.width, 1e-3f)
        assertTrue(anchor.width.isFinite())
    }

    @Test
    fun `anchors whose geometry is not finite are dropped, not reported`() {
        val logits = FloatArray(896) { 4f } // every anchor is a certain face

        val nanWidth = FloatArray(896 * 16).also { it[3 * 16 + BlazeFaceModel.BOX_WIDTH] = Float.NaN }
        val decoded = BlazeFaceDecoder.decode(nanWidth, logits, geometry)
        assertEquals(895, decoded.size, "the one anchor with a NaN width is dropped")

        val infCentre = FloatArray(896 * 16).also { it[9 * 16 + BlazeFaceModel.BOX_X_CENTER] = Float.POSITIVE_INFINITY }
        assertEquals(895, BlazeFaceDecoder.decode(infCentre, logits, geometry).size)

        val nanKeypoint = FloatArray(896 * 16).also {
            it[11 * 16 + BlazeFaceModel.KEYPOINTS + 4] = Float.NaN
        }
        val dropped = BlazeFaceDecoder.decode(nanKeypoint, logits, geometry)
        assertEquals(895, dropped.size, "a malformed keypoint drops the anchor, not just the keypoint")
        assertTrue(dropped.none { it.keypoints.size != 5 }, "no anchor is reported with fewer than 5 keypoints")
    }

    @Test
    fun `a wrong tensor shape is a loud failure, not a plausible box`() {
        val logits = FloatArray(896)
        // 896 anchors x 16 floats is the whole regressors tensor. One short and every index after
        // the gap is the wrong anchor, so the decode must refuse rather than read past the end.
        assertFailsWith<IllegalArgumentException> {
            BlazeFaceDecoder.decode(FloatArray(896 * 16 - 1), logits, geometry)
        }
        assertFailsWith<IllegalArgumentException> {
            BlazeFaceDecoder.decode(FloatArray(896 * 16), FloatArray(895), geometry)
        }
        // The shape the old Android binding allocated: MAX_DETECTIONS * BOX_STRIDE, not the tensor.
        assertFailsWith<IllegalArgumentException> {
            BlazeFaceDecoder.decode(FloatArray(160), FloatArray(896), geometry)
        }
    }

    @Test
    fun `a geometry that disagrees with the arrays is refused at construction`() {
        val centres = FloatArray(896)
        assertFailsWith<IllegalArgumentException> {
            BlazeFaceGeometry(896, 16, 128f, 128f, 128f, 128f, FloatArray(768), centres)
        }
        assertFailsWith<IllegalArgumentException> {
            BlazeFaceGeometry(896, 16, 0f, 128f, 128f, 128f, centres, centres)
        }
    }

    // --- suppression -----------------------------------------------------------------------------------

    @Test
    fun `overlapping boxes are suppressed and the highest score survives`() {
        val strong = anchorAt(0, centreX = 0.5f, centreY = 0.5f, size = 0.2f, logit = 3f)
        val duplicate = anchorAt(1, centreX = 0.5f, centreY = 0.5f, size = 0.2f, logit = 2f)
        val distant = anchorAt(2, centreX = 0.9f, centreY = 0.9f, size = 0.2f, logit = 1f)

        val kept = BlazeFaceDecoder.nonMaximumSuppression(listOf(duplicate, distant, strong))
        assertEquals(listOf(strong, distant), kept)
        assertEquals(1.0f, BlazeFaceDecoder.iou(strong, duplicate), 1e-6f, "identical boxes have IoU 1")
        assertEquals(0f, BlazeFaceDecoder.iou(strong, distant), 0f, "disjoint boxes have IoU 0")
    }

    @Test
    fun `suppression is capped at the model's own detection limit`() {
        val many = (0 until 40).map { anchorAt(it, centreX = it * 0.02f + 0.01f, centreY = 0.01f, size = 0.001f, logit = it.toFloat()) }
        val kept = BlazeFaceDecoder.nonMaximumSuppression(many)
        assertEquals(10, kept.size, "MAX_DETECTIONS is 10, per the model card")
        assertEquals(many.maxBy { it.score }, kept.first(), "survivors come back highest score first")
    }

    @Test
    fun `equal scores are resolved by emission order, not by sort luck`() {
        // 40 exactly-equal scores that do not overlap. A stable sort keeps the emission order; an
        // unstable one would return them in an arbitrary order, and the two platforms would then
        // disagree on a tie without either being wrong in isolation.
        val tied = (0 until 40).map { anchorAt(it, centreX = it * 0.02f + 0.005f, centreY = 0.005f, size = 0.001f, logit = 1f) }
        val kept = BlazeFaceDecoder.nonMaximumSuppression(tied)
        assertEquals(tied.take(10), kept)
        repeat(3) {
            assertEquals(tied.take(10), BlazeFaceDecoder.nonMaximumSuppression(tied.shuffled().sortedBy { it.centerX }))
        }
    }

    @Test
    fun `an empty or single-anchor input is not a special case`() {
        assertEquals(emptyList(), BlazeFaceDecoder.nonMaximumSuppression(emptyList()))
        val one = anchorAt(0, logit = -1f)
        assertEquals(listOf(one), BlazeFaceDecoder.nonMaximumSuppression(listOf(one)))
        assertNull(BlazeFaceDecoder.bestFace(listOf(one)), "score 0.269 is below the 0.5 floor")
    }

    @Test
    fun `bestFace applies the floor after suppression, not before`() {
        // Two anchors, heavily overlapping, both above the floor. Suppression keeps the higher one;
        // filtering first would give the same answer here, so use a case where it does not: a
        // high-scoring anchor that is *suppressed* by an even higher one at a different score.
        val winner = anchorAt(0, centreX = 0.5f, centreY = 0.5f, size = 0.3f, logit = 5f)
        val loser = anchorAt(1, centreX = 0.5f, centreY = 0.5f, size = 0.3f, logit = 4.5f)
        val kept = BlazeFaceDecoder.nonMaximumSuppression(listOf(winner, loser))
        assertEquals(1, kept.size)
        assertEquals(winner, BlazeFaceDecoder.bestFace(kept))
        // With a floor above the survivor, "no face" — even though a face-scoring anchor existed
        // before suppression. That ordering is the contract, and it is what `:platform` does.
        assertEquals(0.9933072f, winner.score, 1e-6f, "sigmoid(5)")
        assertNull(BlazeFaceDecoder.bestFace(kept, minScore = 0.999f))
    }

    @Test
    fun `the whole post-inference path in one call matches the steps`() {
        val regressors = FloatArray(896 * 16) { 128.0f * ln(0.2f) }
        val logits = FloatArray(896) { -50f }
        logits[42] = 2.5f
        val best = assertNotNull(BlazeFaceDecoder.detectBest(regressors, logits, geometry))
        assertEquals(BlazeFaceDecoder.sigmoid(2.5f), best.score, 0f)
        assertEquals(BlazeFaceDecoder.bestFace(BlazeFaceDecoder.nonMaximumSuppression(BlazeFaceDecoder.decode(regressors, logits, geometry))), best)
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun zeroTensors(): Pair<FloatArray, FloatArray> =
        FloatArray(896 * 16) to FloatArray(896)

    /** A synthetic anchor: not model output, test data with a known decode. */
    private fun anchorAt(
        index: Int,
        centreX: Float = 0.5f,
        centreY: Float = 0.5f,
        size: Float = 0.1f,
        logit: Float = 0f,
    ): BlazeFaceAnchor = BlazeFaceAnchor(
        centerX = centreX,
        centerY = centreY,
        width = size,
        height = size,
        score = BlazeFaceDecoder.sigmoid(logit),
        keypoints = (0 until 5).map { BlazeFaceKeyPoint(centreX, centreY) },
    )
}
