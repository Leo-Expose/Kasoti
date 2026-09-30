package dev.kasoti.android.ml

import dev.kasoti.crypto.Hex
import dev.kasoti.platform.crypto.JcaDigest
import dev.kasoti.platform.ml.tflite.AnchorDecoder
import dev.kasoti.platform.ml.tflite.BlazeFaceAnchors
import dev.kasoti.platform.ml.tflite.BlazeFaceContract
import dev.kasoti.platform.ml.tflite.FaceAnchor
import dev.kasoti.platform.ml.tflite.TfliteBlazeFaceDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Does the Android detector's decode actually equal the desktop one?
 *
 * ## What this establishes, and what it does not
 *
 * DESIGN.md §3 requires Android and desktop to produce the same outputs from the same bytes, and
 * until now nothing in this repository could check it: the Android module is excluded from the
 * build when no SDK is present, and `:platform`'s desktop implementation is not reachable from an
 * Android module (it is a KMP `jvmMain` source set, and there is no `platform/src/androidMain`).
 *
 * So the two implementations of the decode are duplicates by necessity, and this test is what
 * stops the duplication becoming drift. It loads `:platform`'s *compiled classes* onto a bare JVM
 * and runs both decoders over the same bytes. `app-android/tools/verify-offline.sh` is what makes
 * that possible, and it is also the only thing standing between this module and never being
 * compiled again.
 *
 * **It is not a parity test.** Parity means a real Android device producing the desktop's numbers.
 * There is no such device and no SDK, so the Android *binding* — `TfliteFaceDetector`, the
 * `Interpreter` calls, the tensor allocation — remains uncompiled and unverified. What is verified
 * here is the half of the path that decides what the model's numbers mean, against the half the
 * desktop already runs, on the detector's real output.
 *
 * ## The fixture
 *
 * `eval/fixtures/face/raw_outputs/` holds the real `blazeface_short.tflite` output for two
 * committed images, captured by the working `:platform` binding, together with the decode the
 * desktop produces from it. Those bytes are the known answer: without them every assertion here
 * would be "both implementations agree with each other", which is a tautology and not a check.
 */
class BlazeFaceDesktopParityTest {

    // --- the model's interface must be stated once, not twice -----------------------------------------

    @Test
    fun `the contract constants are the same on both sides`() {
        assertEquals(BlazeFaceContract.NAME, BlazeFaceModel.NAME)
        assertEquals(BlazeFaceContract.SHA256, BlazeFaceModel.SHA256, "one hash to pin, both platforms (D1)")
        assertEquals(BlazeFaceContract.LICENSE, BlazeFaceModel.LICENSE)
        assertEquals(BlazeFaceContract.MINIMUM_BYTES.toInt(), BlazeFaceModel.MINIMUM_BYTES.toInt())
        assertEquals(BlazeFaceContract.INPUT_SIDE, BlazeFaceModel.INPUT_SIDE)
        assertEquals(BlazeFaceContract.INPUT_CHANNELS, BlazeFaceModel.INPUT_CHANNELS)
        assertEquals(BlazeFaceContract.INPUT_DTYPE, BlazeFaceModel.INPUT_DTYPE)
        assertEquals(BlazeFaceContract.INPUT_MIN, BlazeFaceModel.INPUT_MIN)
        assertEquals(BlazeFaceContract.INPUT_MAX, BlazeFaceModel.INPUT_MAX)
        assertEquals(BlazeFaceContract.ANCHORS, BlazeFaceModel.ANCHORS)
        assertEquals(BlazeFaceContract.ANCHOR_STRIDE, BlazeFaceModel.ANCHOR_STRIDE)
        assertEquals(BlazeFaceContract.BOX_X_CENTER, BlazeFaceModel.BOX_X_CENTER)
        assertEquals(BlazeFaceContract.BOX_Y_CENTER, BlazeFaceModel.BOX_Y_CENTER)
        assertEquals(BlazeFaceContract.BOX_WIDTH, BlazeFaceModel.BOX_WIDTH)
        assertEquals(BlazeFaceContract.BOX_HEIGHT, BlazeFaceModel.BOX_HEIGHT)
        assertEquals(BlazeFaceContract.KEYPOINT_SCORES, BlazeFaceModel.KEYPOINT_SCORES)
        assertEquals(BlazeFaceContract.KEYPOINTS, BlazeFaceModel.KEYPOINTS)
        assertEquals(BlazeFaceContract.KEYPOINT_COUNT, BlazeFaceModel.KEYPOINT_COUNT)
        assertEquals(BlazeFaceContract.OUTPUT_KEYPOINTS, BlazeFaceModel.OUTPUT_KEYPOINTS)
        assertEquals(BlazeFaceContract.REGRESSORS_TENSOR, BlazeFaceModel.REGRESSORS_TENSOR)
        assertEquals(BlazeFaceContract.CLASSIFICATORS_TENSOR, BlazeFaceModel.CLASSIFICATORS_TENSOR)
        assertEquals(BlazeFaceContract.HAS_INNER_SOFTMAX, BlazeFaceModel.HAS_INNER_SOFTMAX, "no SOFTMAX in the graph")
        assertEquals(TfliteBlazeFaceDetector.MIN_SCORE, BlazeFaceModel.MIN_SCORE)
        assertEquals(AnchorDecoder.IOU_THRESHOLD, BlazeFaceModel.IOU_THRESHOLD)
        assertEquals(AnchorDecoder.MAX_DETECTIONS, BlazeFaceModel.MAX_DETECTIONS)
        assertEquals(BlazeFaceAnchors.NUM_LAYERS, BlazeFaceModel.NUM_LAYERS)
        assertContentEquals(BlazeFaceAnchors.STRIDES, BlazeFaceModel.STRIDES, "8, 16, 16, 16")
        assertEquals(BlazeFaceAnchors.ANCHOR_OFFSET, BlazeFaceModel.ANCHOR_OFFSET)
        assertEquals(BlazeFaceAnchors.COUNT, BlazeFaceModel.ANCHORS)
    }

    @Test
    fun `the anchor grid is bit-identical on both sides`() {
        val mine = BlazeFaceModel.anchorCentres()
        assertEquals(896, mine.x.size)
        // Not "close": the grid is the index arithmetic behind every box, so a single-ulp drift
        // would be a systematic offset in all 896 decoded boxes rather than a visible error.
        assertTrue(mine.x.contentEquals(BlazeFaceAnchors.centerX()), "anchor x centres drifted")
        assertTrue(mine.y.contentEquals(BlazeFaceAnchors.centerY()), "anchor y centres drifted")
    }

    // --- the decode, on the detector's real output -----------------------------------------------------

    @Test
    fun `the face-free fixture decodes to the desktop result and finds no face`() {
        val (regressors, classificators) = tensors("noface")
        assertSameAsDesktop(regressors, classificators, "noface")
        // The reference expectation, restated so the test is not merely "both agree": the smooth
        // gradient contains no face and no survivor clears the model's own 0.5 floor.
        val kept = BlazeFaceDecoder.nonMaximumSuppression(BlazeFaceDecoder.decode(regressors, classificators))
        assertEquals(5, kept.size, "five survivors survive suppression; every one is below the floor")
        assertNull(BlazeFaceDecoder.bestFace(kept), "and so the face-free fixture reports no face")
        assertTrue(classificators.all { it < 0f }, "every raw logit is negative here")
        assertTrue(
            kept.all { it.score < 0.5f },
            "read raw, these 'confidences' would all be negative numbers; squashed, they are " +
                "probabilities under the floor",
        )
    }

    @Test
    fun `the firing fixture decodes to the desktop result and finds one face`() {
        val (regressors, classificators) = tensors("fires")
        assertSameAsDesktop(regressors, classificators, "fires")
        val kept = BlazeFaceDecoder.nonMaximumSuppression(BlazeFaceDecoder.decode(regressors, classificators))
        assertEquals(3, kept.size)
        val best = assertNotNull(BlazeFaceDecoder.bestFace(kept), "the top survivor clears 0.5")
        // The sigmoid, stated as a number rather than as a property. The top anchor's raw logit is
        // 0.919; read without a sigmoid that is a *different* score, and it would pass a 0.5 floor
        // while 0.7149 passes 0.5 and fails 0.8 — the floor stops meaning what its name says.
        val rawTopLogit = classificators.max()
        assertEquals(0.9190993f, rawTopLogit, 1e-6f, "the top raw logit in these bytes")
        assertEquals(0.7148585f, BlazeFaceDecoder.sigmoid(rawTopLogit), 1e-6f)
        assertEquals(0.7148585f, best.score, 1e-6f, "the reported score is the squashed logit")
        assertTrue(rawTopLogit > best.score, "the raw logit is NOT the score: $rawTopLogit vs ${best.score}")
        assertEquals(5, best.keypoints.size, "five keypoints, decoded against the anchor grid")
    }

    @Test
    fun `the committed expectation is reproduced from the committed bytes`() {
        // Reads the expected survivors out of raw_outputs.json rather than restating them here, so
        // that editing the fixture's recorded answer without changing the bytes fails this test and
        // editing the bytes without updating the record fails it too.
        for (case in CASES) {
            val (regressors, classificators) = tensors(case)
            val decoded = BlazeFaceDecoder.decode(regressors, classificators)
            assertEquals(
                expectedInt(case, "decoded_anchors"),
                decoded.size,
                "$case: the number of decoded anchors is a property of the bytes and the geometry",
            )
            val kept = BlazeFaceDecoder.nonMaximumSuppression(decoded)
            val expected = expectedSurvivors(case)
            assertEquals(expected.size, kept.size, "$case: survivor count")
            assertEquals(expected.size, expectedInt(case, "survivors_after_nms"), "$case: recorded survivor count")

            expected.forEachIndexed { rank, want ->
                val got = kept[rank]
                assertEquals(want.score, got.score, 1e-6f, "$case survivor $rank: score")
                assertEquals(want.box[0], got.left, 1e-6f, "$case survivor $rank: box left")
                assertEquals(want.box[1], got.top, 1e-6f, "$case survivor $rank: box top")
                assertEquals(want.box[2], got.right, 1e-6f, "$case survivor $rank: box right")
                assertEquals(want.box[3], got.bottom, 1e-6f, "$case survivor $rank: box bottom")
                assertEquals(5, want.keypoints.size)
                want.keypoints.forEachIndexed { k, point ->
                    assertEquals(point[0], got.keypoints[k].x, 1e-6f, "$case survivor $rank keypoint $k x")
                    assertEquals(point[1], got.keypoints[k].y, 1e-6f, "$case survivor $rank keypoint $k y")
                }
            }
            assertEquals(
                expectedInt(case, "above_min_score"),
                if (BlazeFaceDecoder.bestFace(kept) == null) 0 else 1,
                "$case: how many survivors clear the 0.5 floor",
            )
        }
    }

    @Test
    fun `the fixture bytes are the bytes the record pins`() {
        // A fixture nobody checks is a fixture that can rot silently. The digests in
        // raw_outputs.json are the same pins `detector_reference.json` and `manifest.json` use.
        for (case in CASES) {
            for (tensor in listOf("regressors", "classificators")) {
                val file = File(FIXTURE_DIR, "${case}_${tensor}.f32")
                assertTrue(file.isFile, "missing ${file.name}; see raw_outputs/README.md")
                val digest = Hex.encode(JcaDigest().sha256(file.readBytes()))
                assertEquals(
                    expectedString(case, tensor, "sha256"),
                    digest,
                    "${file.name} has changed. The recorded decode describes these exact bytes, so " +
                        "either regenerate the tensors and the whole record together, or revert.",
                )
            }
        }
    }

    // --- the decode, on adversarial input --------------------------------------------------------------

    @Test
    fun `the two decoders agree on pathological tensors`() {
        val geometry = BlazeFaceModel.geometry()
        val cases = listOf(
            "all zero" to (FloatArray(896 * 16) to FloatArray(896)),
            "all NaN" to (FloatArray(896 * 16) { Float.NaN } to FloatArray(896) { Float.NaN }),
            "all +Inf" to (FloatArray(896 * 16) { Float.POSITIVE_INFINITY } to FloatArray(896) { Float.POSITIVE_INFINITY }),
            "all -Inf" to (FloatArray(896 * 16) { Float.NEGATIVE_INFINITY } to FloatArray(896) { Float.NEGATIVE_INFINITY }),
            "mixed signs" to (
                FloatArray(896 * 16) { i -> if (i % 2 == 0) 1e30f else -1e30f } to
                    FloatArray(896) { i -> if (i % 3 == 0) 1e30f else -1e30f }
                ),
            "negative widths" to (
                FloatArray(896 * 16) { i -> if (i % 16 == BlazeFaceModel.BOX_WIDTH) -1f else 0f } to
                    FloatArray(896) { 0.5f }
                ),
            "exactly tied scores" to (
                FloatArray(896 * 16) { i -> if (i % 16 < BlazeFaceModel.KEYPOINTS) 128.0f * (i % 16) else 0f } to
                    FloatArray(896) { 0.25f }
                ),
        )
        for ((name, tensors) in cases) {
            assertSameAsDesktop(tensors.first, tensors.second, name)
        }
    }

    @Test
    fun `the two sigmoids agree over the whole range a logit can take`() {
        for (step in -800..800) {
            val logit = step * 0.25f
            assertEquals(AnchorDecoder.sigmoid(logit), BlazeFaceDecoder.sigmoid(logit), 0f, "logit $logit")
        }
        // The corners, where a naive implementation overflows to NaN and the face silently
        // disappears with no error anywhere.
        for (logit in listOf(-1e30f, -1e7f, -88f, 88f, 1e7f, 1e30f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
            val mine = BlazeFaceDecoder.sigmoid(logit)
            assertEquals(AnchorDecoder.sigmoid(logit), mine, 0f, "logit $logit")
            assertTrue(!mine.isNaN(), "logit $logit produced NaN, which fails every comparison")
        }
    }

    @Test
    fun `the two suppressors and the two IoUs agree`() {
        val anchors = (0 until 60).map { i ->
            BlazeFaceAnchor(
                centerX = (i % 10) * 0.1f + 0.05f,
                centerY = (i / 10) * 0.1f + 0.05f,
                width = 0.05f + (i % 3) * 0.01f,
                height = 0.05f + (i % 5) * 0.01f,
                // Deliberately repeated scores, so the ordering is decided by tie-breaking and the
                // two implementations have to break ties the same way.
                score = (i % 4) * 0.25f,
                keypoints = listOf(BlazeFaceKeyPoint(0.5f, 0.5f)),
            )
        }
        val mirrored = anchors.map {
            FaceAnchor(
                it.centerX, it.centerY, it.width, it.height, it.score,
                listOf(dev.kasoti.platform.ml.tflite.KeyPoint(0.5f, 0.5f)),
            )
        }
        val mine = BlazeFaceDecoder.nonMaximumSuppression(anchors)
        val theirs = AnchorDecoder.nonMaximumSuppression(mirrored)
        assertEquals(theirs.size, mine.size)
        assertEquals(theirs.map { it.score }, mine.map { it.score })
        assertEquals(theirs.map { it.left }, mine.map { it.left })
        for (a in mirrored.indices) {
            for (b in mirrored.indices) {
                assertEquals(
                    AnchorDecoder.iou(mirrored[a], mirrored[b]),
                    BlazeFaceDecoder.iou(anchors[a], anchors[b]),
                    0f,
                    "IoU($a,$b)",
                )
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** Load a case's raw tensors, checking the recorded element counts first. */
    private fun tensors(case: String): Pair<FloatArray, FloatArray> {
        val regressors = readFloats("$case" + "_regressors.f32", expectedInt(case, "regressors", "elements"))
        val classificators = readFloats("$case" + "_classificators.f32", expectedInt(case, "classificators", "elements"))
        return regressors to classificators
    }

    private fun readFloats(name: String, expectedElements: Int): FloatArray {
        val bytes = File(FIXTURE_DIR, name).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        assertEquals(expectedElements, out.size, "$name: recorded element count")
        assertEquals(out.size * 4, bytes.size, "$name: no header, no padding, little-endian float32")
        return out
    }

    /** Run both decoders over the same bytes and require the same answer, field for field. */
    private fun assertSameAsDesktop(regressors: FloatArray, classificators: FloatArray, label: String) {
        val desktopRows = Array(BlazeFaceContract.ANCHORS) { i ->
            val base = i * BlazeFaceContract.ANCHOR_STRIDE
            regressors.copyOfRange(base, base + BlazeFaceContract.ANCHOR_STRIDE)
        }
        val desktopLogits = Array(BlazeFaceContract.ANCHORS) { floatArrayOf(classificators[it]) }
        val desktop = AnchorDecoder.nonMaximumSuppression(AnchorDecoder.decode(desktopRows, desktopLogits))
        val android = BlazeFaceDecoder.nonMaximumSuppression(
            BlazeFaceDecoder.decode(regressors, classificators, BlazeFaceModel.geometry()),
        )
        assertEquals(desktop.size, android.size, "$label: the two platforms kept a different number of anchors")
        desktop.forEachIndexed { i, want ->
            val got = android[i]
            assertEquals(want.score, got.score, 0f, "$label[$i]: score must be bit-identical, not close")
            assertEquals(want.centerX, got.centerX, 0f, "$label[$i]: centre x")
            assertEquals(want.centerY, got.centerY, 0f, "$label[$i]: centre y")
            assertEquals(want.width, got.width, 0f, "$label[$i]: width")
            assertEquals(want.height, got.height, 0f, "$label[$i]: height")
            assertEquals(want.keypoints.size, got.keypoints.size, "$label[$i]: keypoint count")
            want.keypoints.forEachIndexed { k, point ->
                assertEquals(point.x, got.keypoints[k].x, 0f, "$label[$i]: keypoint $k x")
                assertEquals(point.y, got.keypoints[k].y, 0f, "$label[$i]: keypoint $k y")
            }
        }
    }

    // A targeted scan of raw_outputs.json rather than a JSON parse: the file is a human-readable
    // record whose value is that you can read it, and :core's JSON API is not this module's
    // dependency for a test constant. Every field the tests need is on a line of its own in a fixed
    // shape, and a missing or reshaped field fails here rather than silently matching nothing.

    private fun record(): String = File(FIXTURE_DIR, "raw_outputs.json").readText()

    private fun expectedInt(vararg path: String): Int {
        val text = record()
        var cursor = 0
        path.forEach { segment ->
            val key = if (segment in path.dropLast(1) && !segment.startsWith("_")) "\"$segment\"" else "\"$segment\""
            val at = text.indexOf(key, cursor).takeIf { it >= 0 }
                ?: error("raw_outputs.json has no \"$segment\" after offset $cursor: $path")
            cursor = at + key.length
        }
        val colon = text.indexOf(':', cursor).takeIf { it >= 0 && it - cursor < 400 }
            ?: error("raw_outputs.json: no value for $path")
        val match = Regex("-?[0-9]+(?:\\.[0-9]+)?").find(text, colon) ?: error("raw_outputs.json: no number for $path")
        return match.value.toInt()
    }

    /**
     * A string field, found by walking the record's own nesting rather than by searching from the
     * top: `raw_outputs.json` also pins the model digest under a different case's block, and a
     * naive search would compare the regressors file against the model.
     */
    private fun expectedString(case: String, tensor: String, field: String): String {
        val text = record()
        val caseAt = text.indexOf("\"$case\": {").takeIf { it >= 0 }
            ?: error("raw_outputs.json has no case \"$case\"")
        val tensorAt = text.indexOf("\"$tensor\": {", caseAt).takeIf { it >= 0 }
            ?: error("raw_outputs.json case \"$case\" has no \"$tensor\" block")
        val needle = "\"$field\": \""
        val at = text.indexOf(needle, tensorAt).takeIf { it >= 0 }
            ?: error("raw_outputs.json has no $needle inside $case/$tensor")
        val start = at + needle.length
        return text.substring(start, text.indexOf('"', start))
    }

    private data class Survivor(val score: Float, val box: List<Float>, val keypoints: List<List<Float>>)

    /**
     * The recorded survivors for a case, one per line of `raw_outputs.json`, numbers in the fixed
     * order `rank, score, left, top, right, bottom, cx, cy, w, h, kp0x, kp0y, …`.
     */
    private fun expectedSurvivors(case: String): List<Survivor> {
        val text = record()
        val caseAt = text.indexOf("\"$case\": {").takeIf { it >= 0 }
            ?: error("raw_outputs.json has no case \"$case\"")
        val start = text.indexOf("\"survivors\": [", caseAt)
        var cursor = text.indexOf('\n', start) + 1
        val out = ArrayList<Survivor>()
        while (true) {
            val line = text.substring(cursor, text.indexOf('\n', cursor)).trim()
            cursor = text.indexOf('\n', cursor) + 1
            if (line.startsWith("]")) break
            val numbers = NUMBER.findAll(line).map { it.value.toFloat() }.toList()
            check(numbers.size == 20) {
                "expected 20 numbers on a survivor line for $case (rank, score, box, centre, size, " +
                    "5 keypoints), got ${numbers.size}: $line"
            }
            out += Survivor(
                score = numbers[1],
                box = numbers.subList(2, 6),
                keypoints = (0 until 5).map { k -> listOf(numbers[10 + k * 2], numbers[11 + k * 2]) },
            )
        }
        return out
    }

    private companion object {
        val CASES = listOf("noface", "fires")
        val NUMBER = Regex("-?[0-9]+\\.?[0-9]*(?:[eE][-+]?[0-9]+)?")
        val FIXTURE_DIR: File = run {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return@run File(dir, "eval/fixtures/face/raw_outputs")
                dir = dir.parentFile
            }
            error("could not locate the Gradle root from ${File("").absoluteFile}")
        }
    }
}
