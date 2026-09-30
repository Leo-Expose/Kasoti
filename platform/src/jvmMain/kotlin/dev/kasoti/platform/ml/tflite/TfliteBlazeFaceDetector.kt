package dev.kasoti.platform.ml.tflite

import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.platform.imaging.RgbImage
import dev.kasoti.platform.ml.LoadedModel
import dev.kasoti.platform.ml.ModelLoader
import dev.kasoti.platform.ml.PinnedModel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the detector found, in normalised [0,1] frame coordinates.
 *
 * Normalised rather than pixels because the capture resolution is wildly different across the
 * eval corpus and the two desktop entry points (webcam vs file import) — a fraction of the frame
 * transfers unchanged where a pixel count does not.
 */
data class FaceDetection(
    val box: NormBox,
    /** Five keypoints, in the model's own order: right eye, left eye, nose, mouth R, mouth L. */
    val keypoints: List<KeyPoint>,
    /** Detection confidence in [0,1], already squashed from the logit. */
    val score: Float,
) {
    /** The side of the box as a fraction of the shorter frame edge. */
    val sizeFraction: Float get() = minOf(box.width, box.height)
}

data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Fraction of the box lying inside the frame — the model card's ≥70% requirement. */
    fun visibleFraction(): Float {
        val w = (minOf(right, 1f) - maxOf(left, 0f)).coerceAtLeast(0f)
        val h = (minOf(bottom, 1f) - maxOf(top, 0f)).coerceAtLeast(0f)
        val area = width * height
        return if (area <= 0f) 0f else (w * h) / area
    }
}

/** Per-run detector measurements. Reported, never thresholded against. */
data class DetectorTiming(
    val inputMillis: Float,
    val inferenceNanos: Long,
) {
    val inferenceMillis: Float get() = inferenceNanos / 1_000_000.0f
}

/**
 * BlazeFace short-range on the desktop JVM, over a hash-pinned model file.
 *
 * ## Fail-closed, twice over
 *
 * The bytes are verified against [PinnedModel.sha256] by [ModelLoader] *before* an interpreter
 * is constructed, so swapped weights never reach inference (invariant I12, RT-E8). And a missing
 * native runtime, a missing model file or a model whose tensor shapes disagree with
 * [BlazeFaceContract] all surface as [unavailable] rather than as a silent "no face detected" —
 * because "no face in frame" and "we could not look" must never look the same to a
 * [dev.kasoti.fusion.FusionEngine] that is trying to decide GREEN.
 *
 * ## Threading
 *
 * Not thread-safe. [TFLite] [Interpreter] is not, and a screening session is single-capture
 * anyway. [close] is idempotent.
 */
class TfliteBlazeFaceDetector private constructor(
    private val loaded: LoadedModel,
    private val interpreter: Interpreter,
) : AutoCloseable {

    val modelTag: String get() = loaded.modelTag
    val sha256: String get() = loaded.sha256

    private var closed = false

    /**
     * Detect the most prominent face in [frame].
     *
     * @return `null` when nothing cleared [MIN_SCORE], which is a *positive* result — the model
     *   ran and reported no face. Distinct from [unavailable] and from an exception, and the
     *   caller must keep the three apart.
     */
    fun detect(frame: RgbImage): FaceDetection? = detectWithTiming(frame)?.first

    /**
     * Detect the most prominent face in [frame], with per-stage timings.
     *
     * @return `null` when the model itself is unavailable. Otherwise a pair whose first element
     *   is `null` when nothing cleared [MIN_SCORE] — a *positive* result: the model ran and
     *   reported no face. The caller must keep "no face", "could not look" and "refused weights"
     *   apart, because only the first two are ever true simultaneously.
     */
    fun detectWithTiming(frame: RgbImage): Pair<FaceDetection?, DetectorTiming> {
        val side = BlazeFaceContract.INPUT_SIDE
        val resized = ImageIoImaging().resize(frame, side, side)

        val startInput = System.nanoTime()
        val input = encodeInput(resized)
        val inputMillis = (System.nanoTime() - startInput) / 1_000_000.0f

        // Allocated with the leading batch dimension, because TFLite compares the destination
        // object's inferred shape against the *tensor's* shape: the tensors are `[1, 896, 16]`
        // and `[1, 896, 1]`, and a `[896, 16]` destination is rejected even though the element
        // count matches. Exactly three nesting levels — an extra `Array(1)` of `FloatArray`s
        // infers as `[1, 896, 16, 1]` and is also rejected.
        val regressors = Array(1) {
            Array(BlazeFaceContract.ANCHORS) { FloatArray(BlazeFaceContract.ANCHOR_STRIDE) }
        }
        val scores = Array(1) {
            Array(BlazeFaceContract.ANCHORS) { FloatArray(1) }
        }

        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(input),
            mapOf(0 to regressors, 1 to scores),
        )
        val nativeNanos = interpreter.getLastNativeInferenceDurationNanoseconds()
        val timing = DetectorTiming(inputMillis, nativeNanos)

        val kept = AnchorDecoder.nonMaximumSuppression(
            AnchorDecoder.decode(
                Array<FloatArray>(BlazeFaceContract.ANCHORS) { regressors[0][it] },
                Array<FloatArray>(BlazeFaceContract.ANCHORS) { scores[0][it] },
            ),
        )
        val best = kept.firstOrNull { it.score >= MIN_SCORE }
            ?: return null to timing

        return FaceDetection(
            box = NormBox(best.left, best.top, best.right, best.bottom),
            keypoints = best.keypoints,
            score = best.score,
        ) to timing
    }

    /**
     * Encode an RGB frame as the model's `[-1, 1]` float input.
     *
     * The normalisation is a model contract, not a tunable — see
     * [BlazeFaceContract.INPUT_MIN] for why it is `[-1,1]` and what is and is not verified about
     * it. The mapping is `(v / 127.5) - 1`, which is monotone and, importantly, identical on both
     * platforms once written here: the comparability law (DESIGN.md §3) depends on the two
     * platforms agreeing on this line of code, not on each re-deriving it.
     */
    private fun encodeInput(image: RgbImage): ByteBuffer {
        val side = BlazeFaceContract.INPUT_SIDE
        val buffer = ByteBuffer
            .allocateDirect(side * side * BlazeFaceContract.INPUT_CHANNELS * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        var i = 0
        while (i < buffer.capacity()) {
            val pixelIndex = i / (FLOAT_BYTES * BlazeFaceContract.INPUT_CHANNELS)
            val channel = (i / FLOAT_BYTES) % BlazeFaceContract.INPUT_CHANNELS
            val v = (image.pixels[pixelIndex * RgbImage.CHANNELS + channel].toInt() and 0xFF) / 255f
            buffer.putFloat((v * 2f) - 1f)
            i += FLOAT_BYTES
        }
        buffer.rewind()
        return buffer
    }

    /** This detector always ran; a model that never loaded is represented by `null` instead. */
    val unavailable: Boolean get() = false

    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    companion object {
        private const val FLOAT_BYTES = 4

        /**
         * The graph's own `min_score_thresh: 0.5`.
         *
         * A property of the detector's output distribution — a score below it is not a face — and
         * **not** a screening threshold, which live in `ThresholdRegistry` and nowhere else
         * (AGENTS.md §2, FR-R3). The screening decision that uses a face score is a different
         * number, in a different file, and the two must never be merged.
         */
        const val MIN_SCORE = 0.5f

        /**
         * Build a detector from a hash-pinned file, or `null` if it cannot be built.
         *
         * Returning `null` rather than throwing is deliberate: "this machine cannot run
         * inference" is an expected, documented state on Windows and Apple-Silicon Macs, and it
         * must degrade to a face layer reporting `UNAVAILABLE` — not to a crash in a screening
         * session. A *hash mismatch* is different and still throws [dev.kasoti.platform.ml.ModelLoadException],
         * because that is tampering, not a missing capability.
         */
        fun open(
            loader: ModelLoader,
            modelFile: java.nio.file.Path,
        ): TfliteBlazeFaceDetector {
            val availability = TfliteRuntime.availability
            check(availability.available) {
                "TFLite runtime unavailable: ${availability.detail}"
            }
            val pin = PinnedModel(
                name = BlazeFaceContract.NAME,
                sha256 = BlazeFaceContract.SHA256,
                minimumBytes = BlazeFaceContract.MINIMUM_BYTES,
            )
            val loaded = loader.load(pin, modelFile)
            val interpreter = Interpreter(
                verifiedBufferOf(loaded),
                Interpreter.Options().setNumThreads(DEFAULT_THREADS),
            )
            verifyShapes(interpreter)
            return TfliteBlazeFaceDetector(loaded, interpreter)
        }

        /**
         * Copy the *verified* bytes into a direct buffer for the interpreter.
         *
         * Deliberately not `MappedByteBuffer.map(...)` on the file. Mapping would be faster, but
         * it re-reads the file after the hash was taken, which reopens exactly the window
         * `ModelLoader` exists to close: a file rewritten between the digest and the map is
         * interpreted without ever having been hashed. A ~225 KB copy is not worth that.
         */
        private fun verifiedBufferOf(loaded: LoadedModel): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(loaded.bytes.size)
            buffer.put(loaded.bytes)
            buffer.rewind()
            return buffer
        }

        /**
         * Four threads, matching `app-android`'s `TfliteFaceDetector`/`ModelPinLoader`.
         *
         * BUILD.md §4 names it and NFR-P1's detect+embed budget is sized for it. **The budget
         * has not been measured** on the named low-end device and is not claimed (spike 01 §5.2).
         */
        const val DEFAULT_THREADS = 4

        /**
         * Fail fast if the file on disk is not the model this code was written against.
         *
         * The SHA-256 pin already guarantees identity, so this is a *second*, independent check
         * that catches a class of error the pin cannot: a hand-written or placeholder pin that
         * happens to match whatever file someone downloaded. Shapes are the model's interface;
         * if they differ, every index below is wrong and the right answer is to refuse.
         */
        fun verifyShapes(interpreter: Interpreter) {
            require(interpreter.getInputTensorCount() == 1) {
                "expected 1 input tensor, model has ${interpreter.getInputTensorCount()}"
            }
            val input: Tensor = interpreter.getInputTensor(0)
            require(input.dataType() == DataType.FLOAT32) {
                "expected a FLOAT32 input, model has ${input.dataType()}"
            }
            require(input.shape().contentEquals(intArrayOf(1, BlazeFaceContract.INPUT_SIDE, BlazeFaceContract.INPUT_SIDE, BlazeFaceContract.INPUT_CHANNELS))) {
                "expected input [1, ${BlazeFaceContract.INPUT_SIDE}, ${BlazeFaceContract.INPUT_SIDE}, " +
                    "${BlazeFaceContract.INPUT_CHANNELS}], model has ${input.shape().contentToString()}"
            }
            require(interpreter.getOutputTensorCount() == 2) {
                "expected 2 output tensors, model has ${interpreter.getOutputTensorCount()}"
            }
            require(
                interpreter.getOutputTensor(0).shape()
                    .contentEquals(intArrayOf(1, BlazeFaceContract.ANCHORS, BlazeFaceContract.ANCHOR_STRIDE)),
            ) {
                "expected regressors [1, ${BlazeFaceContract.ANCHORS}, ${BlazeFaceContract.ANCHOR_STRIDE}], " +
                    "model has ${interpreter.getOutputTensor(0).shape().contentToString()}"
            }
            require(
                interpreter.getOutputTensor(1).shape()
                    .contentEquals(intArrayOf(1, BlazeFaceContract.ANCHORS, 1)),
            ) {
                "expected classificators [1, ${BlazeFaceContract.ANCHORS}, 1], " +
                    "model has ${interpreter.getOutputTensor(1).shape().contentToString()}"
            }
        }
    }
}
