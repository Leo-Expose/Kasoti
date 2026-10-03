package dev.kasoti.android.platform

import android.content.Context
import android.util.Log
import dev.kasoti.android.field.FieldLog
import dev.kasoti.android.field.FieldLogEntry
import dev.kasoti.android.ml.BlazeFaceAnchor
import dev.kasoti.android.ml.BlazeFaceDecoder
import dev.kasoti.android.ml.BlazeFaceGeometry
import dev.kasoti.android.ml.BlazeFaceInput
import dev.kasoti.android.ml.BlazeFaceModel
import dev.kasoti.crypto.Digest
import dev.kasoti.face.Embedding
import dev.kasoti.face.FaceMath
import dev.kasoti.fusion.FindingCode
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory
import org.tensorflow.lite.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Hash-pinned model loading for Android (BUILD.md §4, invariant I12, RT-E8).
 *
 * ## The attack
 *
 * An attacker who can write one file inside the app's data directory swaps `emb_v1.tflite` for
 * their own weights. The app then "works perfectly" — embeddings are produced, the chain
 * verifies, the audit is clean — while every verdict is the attacker's. Signature verification
 * does not help, because the attacker controls the *inputs* to it. Only a digest pinned at
 * build time helps.
 *
 * ## Fail-closed
 *
 * A mismatch is an exception, never a warning and never a fallback to a default model. The
 * caller decides what a missing model means, and it is never "treat it as a pass": a handset
 * whose weights have been swapped produces `face = null`, which makes GREEN unreachable
 * (FUSION.md §7) and surfaces `SYS_CAPTURE_FAILED`. `Dev.kasoti.android.field.EvidenceAssembler`
 * is where that `null` is produced, and it is why an unbound model cannot be mistaken for a
 * matching face.
 *
 * ## Duplicated from `:platform` on purpose
 *
 * `:platform`'s `ModelLoader` works on a `java.nio.file.Path` and is a `jvmMain` class, so an
 * Android module cannot resolve it (see `AndroidImaging`'s note on the missing `androidMain`).
 * The pin logic, the constant-time comparison and the `ModelErrorCode` vocabulary are the same
 * as `:platform`'s; when this moves to `:platform/androidMain` the file should be *replaced*
 * rather than copied, and `ModelPinParityTest` exists to make the two agree until then.
 */
class ModelPinLoader(
    private val digest: Digest,
    private val log: FieldLog = FieldLog.NOOP,
) {

    /** The four things that can go wrong, as a closed vocabulary (see `:platform`'s enum). */
    enum class ModelErrorCode {
        HASH_MISMATCH,
        MISSING,
        TOO_SMALL,
        BAD_PIN,
    }

    class ModelRefusal(
        val code: ModelErrorCode,
        val modelName: String,
        val expectedSha256: String?,
        val actualSha256: String?,
    ) : Exception("model '$modelName' refused: $code")

    /**
     * A model that has *proved* its identity.
     *
     * The type is the enforcement mechanism: [bytes] can only be reached through a successful
     * [load], so there is no way to hand unverified bytes to an interpreter.
     */
    class VerifiedModel(
        val name: String,
        val sha256: String,
        private val file: File,
        /** `@param name@sha256:<64 hex>` — the tag every diary record carries (DESIGN.md §3). */
        val modelTag: String,
        val sizeBytes: Long,
    ) {
        private var interpreter: InterpreterApi? = null

        /**
         * The interpreter, created once and reused.
         *
         * Created lazily so that a screen which never needs the model (a macro-only demo, a
         * permission card) never pays for it, and so that a refused model never reaches an
         * interpreter at all.
         *
         * The return type is `InterpreterApi`, not the concrete `org.tensorflow.lite.Interpreter`.
         * LiteRT's `litert-api:1.0.1` **deleted** the concrete class and kept only the interface;
         * it survives in `tensorflow-lite-api:2.13.0`, which is exactly the duplicate-runtime
         * dependency this module no longer resolves. `Interpreter` extended `InterpreterApi` in
         * TFLite 2.x, so widening the declared type to the supertype is behaviour-preserving:
         * every call made below (`runForMultipleInputsOutputs`, `getInputTensor`,
         * `getOutputTensorCount`, `close`) is declared on the interface.
         */
        fun interpreter(numThreads: Int = DEFAULT_THREADS): InterpreterApi =
            synchronized(this) {
                interpreter ?: run {
                    // Explicit setters, not property assignment. `Options` exposes
                    // `setNumThreads(int)` / `setUseXNNPACK(boolean)` and no getters, so Kotlin
                    // creates no synthetic property for them, and `apply { numThreads = numThreads }`
                    // silently resolved the left-hand side to this function's own parameter: a
                    // no-op self-assignment that compiled to nothing and left the interpreter on
                    // TFLite's default thread count. The XNNPACK line had the same shape. Both are
                    // load-bearing — the thread count is what NFR-P1's budget is sized for, and
                    // DESIGN.md §1's "no delegate that could change outputs" is a statement about
                    // what is *enabled*, which is only true if the flag is actually set.
                    val options = InterpreterApi.Options()
                    options.setNumThreads(numThreads)
                    // XNNPACK is the CPU delegate and is the only one enabled by default
                    // (DESIGN.md §1: a GPU delegate "must not change outputs beyond the tolerance
                    // test", and that test does not exist yet, so nothing is enabled that could
                    // break bit-comparability across devices).
                    options.setUseXNNPACK(true)
                    // `InterpreterFactory().create(buffer, options)` is the LiteRT spelling of the
                    // old `Interpreter(buffer, options)` constructor: the concrete class had a
                    // public constructor, the factory is how `InterpreterApi` instances are made.
                    val created = InterpreterFactory().create(loadMappedBuffer(), options)
                    interpreter = created
                    created
                }
            }

        fun close() = synchronized(this) {
            interpreter?.close()
            interpreter = null
        }

        private fun loadMappedBuffer(): MappedByteBuffer {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }

    /**
     * @param pin the build-time pin. `sha256` is a compile-time constant in the shipped binary
     *   in production; here it comes from [ModelPin], which is where the release build's values
     *   are written by `scripts/provision.sh`.
     */
    fun load(pin: ModelPin): VerifiedModel {
        requireWellFormedPin(pin)
        if (!pin.file.isFile) throw ModelRefusal(ModelErrorCode.MISSING, pin.name, pin.sha256, null)
        val size = pin.file.length()
        if (size < pin.minimumBytes) throw ModelRefusal(ModelErrorCode.TOO_SMALL, pin.name, pin.sha256, null)

        // Read fully, then hash. A streamed hash of a file being rewritten underneath us either
        // matches or does not; the failure mode we are avoiding is a half-old half-new mixture
        // that happens to match nothing and is indistinguishable from tampering.
        val bytes = Files.readAllBytes(pin.file.toPath())
        val actual = hex(digest.sha256(bytes))
        if (!dev.kasoti.crypto.constantTimeEquals(
                actual.toByteArray(Charsets.US_ASCII),
                pin.sha256.toByteArray(Charsets.US_ASCII),
            )
        ) {
            log.write(
                FieldLogEntry.of(
                    kind = FieldLogEntry.Kind.SYSTEM,
                    codes = listOf(FindingCode.SYS_MODEL_MISMATCH),
                    detail = "model '${pin.name}' hash mismatch",
                ),
            )
            Log.e(TAG, "model '${pin.name}' refused: expected ${pin.sha256} got $actual")
            throw ModelRefusal(ModelErrorCode.HASH_MISMATCH, pin.name, pin.sha256, actual)
        }
        Log.i(TAG, "model '${pin.name}' verified: $actual")
        return VerifiedModel(
            name = pin.name,
            sha256 = actual,
            file = pin.file,
            modelTag = "${pin.name}@sha256:$actual",
            sizeBytes = size,
        )
    }

    private fun requireWellFormedPin(pin: ModelPin) {
        if (pin.sha256.length != SHA256_HEX_LENGTH || !pin.sha256.all { it in "0123456789abcdef" }) {
            throw ModelRefusal(ModelErrorCode.BAD_PIN, pin.name, pin.sha256, null)
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4])
            append(DIGITS[v and 0x0F])
        }
    }

    companion object {
        const val SHA256_HEX_LENGTH = 64
        private const val DIGITS = "0123456789abcdef"
        private const val TAG = "KASOTI"

        /**
         * Four threads.
         *
         * BUILD.md §4 names it, and the budget in NFR-P1 (face detect + embed < 400 ms on
         * 4×A53) is what four is sized for. More threads on a big phone buys a little latency
         * and costs battery on every one of the hundred screenings a shift, and NFR-B1 caps
         * battery at 3% per 100.
         */
        const val DEFAULT_THREADS = 4
    }
}

/**
 * A build-time pin for one model artifact.
 *
 * The intended production shape is a `const val` inside the release build, generated by
 * provisioning — a pin that ships *beside* the thing it pins is not a pin. [ModelPin] is the
 * test- and provisioning-time shape: the same four fields, resolved from the app's own
 * `filesDir`, so the same loader and the same refusal codes are exercised without a generated
 * file.
 */
data class ModelPin(
    val name: String,
    val file: File,
    val sha256: String,
    /**
     * A floor on plausible size.
     *
     * A hash check on a 40-byte file is a hash check on a truncated file, and a truncated
     * TFLite model is not always rejected by the interpreter. The floors are set well below the
     * real sizes (DESIGN.md §6: blazeface ~230 KB, emb_v1 ≤ 5 MB) so a partial copy is caught
     * before the interpreter sees it.
     */
    val minimumBytes: Long = 1L,
)

/**
 * A face embedding produced by the TFLite model, in `:core`'s vocabulary.
 *
 * The `modelTag` travels with the vector because the diary's comparability law
 * (DESIGN.md §3) says embeddings are comparable *only* across identical model bytes,
 * alignment and normalisation — and the tag is the only thing that can enforce that at
 * read time rather than at review time.
 */
class TfliteFaceModels(
    private val detector: TfliteFaceDetector,
    private val embedder: TfliteEmbeddingModel,
) {

    /**
     * Detect, align, embed and L2-normalise, in that order (DESIGN.md §5).
     *
     * @param crop interleaved 8-bit RGB of the source frame, `width * height * 3` bytes.
     * @param width the frame's width in pixels. Required rather than inferred, because the frame
     *   is a `ByteBuffer` and nothing about it states its own shape — and a detector that guessed
     *   would produce boxes in the wrong units that still look like boxes.
     * @param quality the capture's quality score, carried for the caller's pre-match gate. It is
     *   deliberately **not** consulted here: a quality gate that can be bypassed by not passing the
     *   argument is not a gate, and `dev.kasoti.core`'s `QualityGate` is where the thresholds live
     *   (AGENTS.md §2).
     * @return `null` when no face was found, or when the detector could not look. Not a zero
     *   vector: `FaceMath.cosine` returns 0f for a zero-length input, and a zero vector would
     *   therefore compare as "completely different" rather than "not measured" — which is exactly
     *   the false-accusation path the quality gate exists to prevent.
     */
    fun embed(crop: ByteBuffer, width: Int, height: Int, quality: Float): Embedding? {
        val aligned = detector.align(crop, width, height) ?: return null
        val raw = embedder.embed(aligned) ?: return null
        return Embedding.of(FaceMath.l2Normalize(raw), embedder.modelTag)
    }

    fun close() {
        detector.close()
        embedder.close()
    }
}

/**
 * 128-d embedding from `emb_v1.tflite` (DESIGN.md §5).
 *
 * ## The input dtype here is UNVERIFIED, and deliberately so
 *
 * The aligned crop is 112×112 RGB and the *sizes* are the model's own contract, not tunables —
 * they are baked into the weights' input shape, and passing anything else makes the interpreter
 * throw on a device the operator is holding.
 *
 * Whether those 112×112×3 values arrive as **unsigned bytes** (as [inputBuffer] currently produces)
 * or as **FLOAT32 in `[-1, 1]`** is a property of weights that do not exist. `emb_v1.tflite` was
 * not obtained (spike 01 §2.1: every candidate is deleted, non-commercial-research-only, or
 * unpublished) and none were fabricated. So this is left as it is, self-consistent end to end
 * inside this file, with a comment on it — and it is **not** to be read across from the detector's
 * input path, which *is* verified: `blazeface_short.tflite`'s input is FLOAT32 `[1, 128, 128, 3]` in
 * `[-1, 1]`, read out of the flatbuffer and asserted by `TfliteFaceDetector.verifyShapes`. Two
 * different models, two different contracts, and the only one we can check is the one we have.
 * When an embedder is licensed this becomes a one-line change plus a shape assertion.
 */
class TfliteEmbeddingModel(private val model: ModelPinLoader.VerifiedModel) {

    val modelTag: String get() = model.modelTag

    private val output = Array(1) { Array(1) { FloatArray(Embedding.DIM) } }

    fun embed(input: ByteBuffer): FloatArray? = try {
        input.rewind()
        model.interpreter().run(input, output)
        output[0][0].copyOf()
    } catch (_: RuntimeException) {
        // A shape mismatch or a truncated model. `null` means "not measured"; the caller turns
        // that into `face = null` and an unresolved face layer, which is fail-closed.
        null
    }

    fun close() = model.close()

    companion object {
        const val INPUT_SIDE = 112
        const val INPUT_CHANNELS = 3

        /** `ByteBuffer` factory: unsigned bytes, RGB, row-major, exactly the model's layout. */
        fun inputBuffer(): ByteBuffer = ByteBuffer
            .allocateDirect(INPUT_SIDE * INPUT_SIDE * INPUT_CHANNELS)
            .order(ByteOrder.nativeOrder())
    }
}

/**
 * Face detection and 5-point alignment from `blazeface_short.tflite` (DESIGN.md §5).
 *
 * ## This class is a binding, and nothing else
 *
 * Everything that decides what the model's numbers *mean* lives in `dev.kasoti.android.ml`:
 * `BlazeFaceModel` (the model's own interface, read out of the flatbuffer) and `BlazeFaceDecoder`
 * (sigmoid, anchor placement, box/keypoint decode, IoU, NMS). What is left here is the part that
 * genuinely needs a device — allocating the output tensors, calling the interpreter, and turning
 * `Bitmap`/byte buffers into what the interpreter wants.
 *
 * That split is not tidiness, it is how the four defects this class used to have became checkable
 * on a machine with no Android SDK:
 *
 *  1. **The output destination was `[1, 160, 1]`.** `MAX_DETECTIONS * BOX_STRIDE` is not the
 *     tensor's shape; the regressors tensor is `[1, 896, 16]` and the classificators tensor is
 *     `[1, 896, 1]`, and TFLite compares a destination's inferred shape against the tensor's, so
 *     this threw before inference ran. Both are now allocated from [BlazeFaceModel.ANCHORS] and
 *     [BlazeFaceModel.ANCHOR_STRIDE], and [verifyShapes] refuses a model whose tensors disagree.
 *  2. **The raw logit was used as a confidence.** The graph has no `SOFTMAX`
 *     ([BlazeFaceModel.HAS_INNER_SOFTMAX] is `false`), so `classificators` is a logit and the
 *     sigmoid belongs to the caller. [BlazeFaceDecoder.sigmoid] now applies it, and the floor
 *     [BlazeFaceModel.MIN_SCORE] again means what it says.
 *  3. **A single-output `run` was used on a two-output model.** `run(Object, Object)` picks its
 *     output accessor from the *input*'s type: it writes output 0 and then fails on output 1's
 *     shape. [detect] now uses `runForMultipleInputsOutputs` with the explicit index map
 *     [BlazeFaceModel.OUTPUT_INDEX_REGRESSORS] / [BlazeFaceModel.OUTPUT_INDEX_CLASSIFICATORS].
 *  4. **There was no anchor decode.** The regression values are anchor-relative offsets, not pixel
 *     coordinates, so reading a keypoint out of the raw row returned a number near zero. The decode
 *     in `dev.kasoti.android.ml` places every value against the 896-anchor grid, and this class
 *     scales the result from normalised input units to source pixels.
 *
 * A fifth defect was found while fixing the other four, and it is the reason
 * `dev.kasoti.android.ml.BlazeFaceInput` exists: the frame was handed to the interpreter as a
 * `ByteBuffer` of interleaved 0..255 bytes, but the model's input edge is FLOAT32 `[1, 128, 128, 3]`
 * in `[-1, 1]`, and it needs the frame resized to 128×128 first. The binding never did either.
 *
 * ## Fail-closed
 *
 * Every failure mode below — a shape the model does not have, a tensor that is not FLOAT32, an
 * interpreter that throws, a decode that cannot produce finite geometry — returns `null`, which is
 * "not measured". It is never a box, never a zero score and never a fallback default, because the
 * three must stay distinguishable to a fusion engine deciding GREEN (FUSION.md §7).
 *
 * ## Threading
 *
 * Not thread-safe, for the same reason `:platform`'s is not: a TFLite `Interpreter` is not, and a
 * screening session is single-capture. [close] is idempotent.
 */
class TfliteFaceDetector(private val model: ModelPinLoader.VerifiedModel) {

    /**
     * A detection, in both the model's normalised units and the caller's pixels.
     *
     * [anchor] is kept rather than only [box] because the keypoints are what the alignment crop is
     * built from and re-deriving them from the box would be a guess.
     */
    data class Detection(
        val anchor: BlazeFaceAnchor,
        val box: Box,
        /** Five landmark points in the source crop, in model order. */
        val landmarks: List<Point>,
        /** Detector confidence in [0,1], squashed from the logit. */
        val score: Float,
    )

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class Point(val x: Float, val y: Float)

    /**
     * Align the largest detection onto the embedding model's input.
     *
     * @return `null` when nothing was detected, or when the box is too small to be a usable
     *   face crop. A face smaller than [MIN_FACE_SIDE] in the source frame cannot carry 112×112
     *   of real detail, and upscaling it to that size would manufacture detail the pixels do
     *   not have.
     */
    fun align(frame: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        val detection = detect(frame, width, height) ?: return null
        if (detection.box.width < MIN_FACE_SIDE || detection.box.height < MIN_FACE_SIDE) return null
        val similarity = similarityFrom(detection.landmarks)
        val bytes = frame.drain()
        return TfliteEmbeddingModel.inputBuffer().also { aligned ->
            warp(aligned, bytes, width, height, similarity)
        }
    }

    /**
     * The highest-scoring detection whose score clears [BlazeFaceModel.MIN_SCORE], or `null`.
     *
     * @param frame interleaved 8-bit RGB, `width * height * 3` bytes, row-major. Any size: the
     *   resize to the model's 128×128 edge happens here, not in the caller, because getting it
     *   wrong is invisible — the interpreter accepts whatever it is handed and returns a tensor of
     *   the right shape regardless.
     * @return `null` for "no face in frame" *and* for "could not look". The two are the same value
     *   here by design (AGENTS.md §4: fail-closed, no silent default-pass); a caller that needs the
     *   distinction watches [model] for a loaded model and treats `null` as "not measured".
     */
    fun detect(frame: ByteBuffer, width: Int, height: Int): Detection? =
        detectAll(frame, width, height).firstOrNull()

    /**
     * Every surviving detection, highest score first.
     *
     * The same decode-and-suppress as `:platform`'s `TfliteBlazeFaceDetector`, in the same order:
     * decode all 896 anchors, suppress, then apply the score floor. Returns `emptyList()` on any
     * failure, for the reason [detect] gives.
     */
    fun detectAll(frame: ByteBuffer, width: Int, height: Int): List<Detection> {
        val tensors = rawTensors(frame, width, height) ?: return emptyList()
        val (regressors, classificators) = tensors
        val kept = BlazeFaceDecoder.nonMaximumSuppression(
            BlazeFaceDecoder.decode(regressors, classificators, geometry),
        )
        return kept.map { Detection(it, it.toBox(width, height), it.toLandmarks(width, height), it.score) }
    }

    /**
     * Run inference and hand back the two output tensors exactly as the model wrote them.
     *
     * Split out from the decode so there is exactly one place that touches the interpreter, and so
     * a test can exercise the decode on committed bytes without one (that is what
     * `BlazeFaceDesktopParityTest` does).
     */
    private fun rawTensors(frame: ByteBuffer, width: Int, height: Int): Pair<FloatArray, FloatArray>? = try {
        val interpreter = shapeCheckedInterpreter()
        val input = inputBufferOf(frame, width, height)

        // Both destinations carry the leading batch dimension, because TFLite compares the
        // destination's inferred shape against the *tensor's*: the tensors are `[1, 896, 16]` and
        // `[1, 896, 1]`, and a `[896, 16]` destination is rejected even though the element count
        // matches. Exactly three nesting levels — an extra `Array(1)` of `FloatArray`s infers as
        // `[1, 896, 16, 1]` and is also rejected.
        val regressors = Array(1) {
            Array(BlazeFaceModel.ANCHORS) { FloatArray(BlazeFaceModel.ANCHOR_STRIDE) }
        }
        val classificators = Array(1) {
            Array(BlazeFaceModel.ANCHORS) { FloatArray(1) }
        }

        // The multi-output path is not optional for a two-output model. See
        // `docs/spikes/01-face-model.md` §4 trap 2.
        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(input),
            mapOf(
                BlazeFaceModel.OUTPUT_INDEX_REGRESSORS to regressors,
                BlazeFaceModel.OUTPUT_INDEX_CLASSIFICATORS to classificators,
            ),
        )

        val flatRegressors = FloatArray(BlazeFaceModel.ANCHORS * BlazeFaceModel.ANCHOR_STRIDE)
        for (i in 0 until BlazeFaceModel.ANCHORS) {
            System.arraycopy(regressors[0][i], 0, flatRegressors, i * BlazeFaceModel.ANCHOR_STRIDE, BlazeFaceModel.ANCHOR_STRIDE)
        }
        val flatClassificators = FloatArray(BlazeFaceModel.ANCHORS)
        for (i in 0 until BlazeFaceModel.ANCHORS) {
            flatClassificators[i] = classificators[0][i][0]
        }
        flatRegressors to flatClassificators
    } catch (_: RuntimeException) {
        // A shape the model does not have, a truncated flatbuffer, a refused byte order. `null`
        // means "not measured"; the caller turns that into `face = null` and an unresolved face
        // layer, which is fail-closed.
        null
    }

    /**
     * The model's input edge, built from the frame by the *tested* pure transform.
     *
     * `BlazeFaceInput` is one implementation of the resize and the `[-1, 1]` encoding, asserted
     * equal to `:platform`'s on the same bytes, because DESIGN.md §3's comparability law is about
     * the two platforms computing the *same* pixels and not about each re-deriving it.
     */
    private fun inputBufferOf(frame: ByteBuffer, width: Int, height: Int): ByteBuffer {
        val floats = BlazeFaceInput.modelInput(frame.drain(), width, height)
        return ByteBuffer
            .allocateDirect(floats.size * BlazeFaceInput.FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer ->
                for (value in floats) buffer.putFloat(value)
                buffer.rewind()
            }
    }

    @Volatile
    private var shapesVerified = false

    /**
     * The interpreter, with the model's interface checked once.
     *
     * [verifyShapes] is a *second*, independent check on top of the SHA-256 pin, and it catches the
     * class of error the pin cannot: a hand-written or placeholder pin that happens to match
     * whatever file someone downloaded. The shapes are the model's interface; if they differ, every
     * index in the decode is wrong and the right answer is to refuse. It runs once rather than per
     * capture because it is a property of the model, not of the frame.
     */
    private fun shapeCheckedInterpreter(): InterpreterApi {
        val interpreter = model.interpreter()
        if (!shapesVerified) {
            synchronized(this) {
                if (!shapesVerified) {
                    verifyShapes(interpreter)
                    shapesVerified = true
                }
            }
        }
        return interpreter
    }

    /**
     * The similarity transform from the five landmarks onto the reference 112×112 layout.
     *
     * A similarity (rotation + uniform scale + translation) rather than a full affine fit, and
     * that is a deliberate constraint rather than a simplification: a full affine would let a
     * landmark error scale one axis and shear the face, producing a different embedding for the
     * same person — and the diary would then be full of pairs that should have matched. Two
     * landmarks plus a scale estimate are enough to pin a similarity, and a similarity is what
     * the training pipeline normalised for.
     *
     * The landmarks arrive in source pixels from [detect], so no rescaling happens here.
     */
    private fun similarityFrom(landmarks: List<Point>): Similarity {
        if (landmarks.size < MIN_LANDMARKS) return Similarity.IDENTITY
        // Landmarks 0 (right eye) and 1 (left eye) give rotation and scale; the nose (2) and the
        // mouth corners (3, 4) give the vertical reference. The indices are the model's.
        val right = landmarks[0]
        val left = landmarks[1]
        val dx = left.x - right.x
        val dy = left.y - right.y
        val eyeDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (eyeDistance < 1e-3f) return Similarity.IDENTITY
        val angle = kotlin.math.atan2(dy, dx)
        val scale = REFERENCE_EYE_DISTANCE / eyeDistance
        val centre = Point((left.x + right.x) / 2f, (left.y + right.y) / 2f)
        return Similarity(scale, kotlin.math.cos(angle), kotlin.math.sin(angle), centre)
    }

    private class Similarity(val scale: Float, val cos: Float, val sin: Float, val centre: Point) {
        fun apply(x: Float, y: Float): Pair<Float, Float> {
            val ox = (x - centre.x) * scale
            val oy = (y - centre.y) * scale
            return (ox * cos - oy * sin) to (ox * sin + oy * cos)
        }

        companion object {
            val IDENTITY = Similarity(1f, 1f, 0f, Point(0f, 0f))
        }
    }

    private fun warp(out: ByteBuffer, src: ByteArray, width: Int, height: Int, matrix: Similarity) {
        val side = TfliteEmbeddingModel.INPUT_SIDE
        val centreX = side / 2f
        val centreY = side / 2f
        for (y in 0 until side) {
            for (x in 0 until side) {
                val (mx, my) = inverse(matrix, x - centreX, y - centreY)
                val sx = (mx / matrix.scale + matrix.centre.x).toInt()
                val sy = (my / matrix.scale + matrix.centre.y).toInt()
                val inside = sx in 0 until width && sy in 0 until height
                val base = (sy * width + sx) * TfliteEmbeddingModel.INPUT_CHANNELS
                for (c in 0 until TfliteEmbeddingModel.INPUT_CHANNELS) {
                    out.put((if (inside) src[base + c].toInt() and 0xFF else 0).toByte())
                }
            }
        }
        out.rewind()
    }

    private fun inverse(m: Similarity, rx: Float, ry: Float): Pair<Float, Float> =
        (rx * m.cos + ry * m.sin) to (-rx * m.sin + ry * m.cos)

    /**
     * Read a caller's buffer without moving its position.
     *
     * The old code called `rewind()` and then read, which silently depended on the caller's
     * position being disposable. A capture buffer that a caller also wants to reuse is a real
     * shape, and a detector that consumes it is a bug that only shows up under concurrency.
     */
    private fun ByteBuffer.drain(): ByteArray {
        val position = position()
        val bytes = ByteArray(remaining())
        get(bytes)
        position(position)
        return bytes
    }

    fun close() = model.close()

    companion object {
        /**
         * The reference eye distance in the 112×112 crop, as a fraction of the side.
         *
         * This is the kind of constant that quietly breaks the diary comparability law if two
         * platforms disagree by a few percent: the embeddings are still 128-dimensional, still
         * L2-normalised, and still cosine-comparable, but they are now embeddings of slightly
         * different crops. There is no desktop counterpart to check it against yet — the desktop
         * 5-point alignment ships *with* the embedder, and the embedder does not exist (spike 01
         * §2.1) — so this is recorded as an open parity item rather than presented as verified.
         */
        const val REFERENCE_EYE_DISTANCE = 0.42f * TfliteEmbeddingModel.INPUT_SIDE

        /** Boxes smaller than this in the source frame cannot carry 112×112 of real detail. */
        const val MIN_FACE_SIDE = 24

        private const val MIN_LANDMARKS = 2

        /**
         * Four threads, matching `:platform`'s `TfliteBlazeFaceDetector.DEFAULT_THREADS`.
         *
         * BUILD.md §4 names it, and the NFR-P1 detect+embed budget is sized for it. More threads on
         * a big phone buys a little latency and costs battery on every one of a shift's hundreds of
         * screenings. **The budget has not been measured on the named low-end device and is not
         * claimed** (EVAL.md §8 forbids cherry-picked devices).
         */
        const val DEFAULT_THREADS = ModelPinLoader.DEFAULT_THREADS

        /**
         * Fail fast if the file on disk is not the model this code was written against.
         *
         * The SHA-256 pin already guarantees identity, so this is an *independent* second check.
         * It also documents, in code, the two things that were wrong before: there is **one input**
         * of FLOAT32 `[1, 128, 128, 3]`, and there are **two outputs**, `regressors [1, 896, 16]`
         * first and `classificators [1, 896, 1]` second. Getting either wrong throws at the shape
         * comparison, which is the good case — the bad case is a shape that happens to be accepted.
         *
         * @throws IllegalArgumentException if any of that is not what the model has.
         */
        fun verifyShapes(interpreter: InterpreterApi) {
            require(interpreter.getInputTensorCount() == 1) {
                "expected 1 input tensor, model has ${interpreter.getInputTensorCount()}"
            }
            val input: Tensor = interpreter.getInputTensor(0)
            require(input.dataType() == DataType.FLOAT32) {
                "expected a FLOAT32 input, model has ${input.dataType()}"
            }
            require(
                input.shape().contentEquals(
                    intArrayOf(
                        1,
                        BlazeFaceModel.INPUT_SIDE,
                        BlazeFaceModel.INPUT_SIDE,
                        BlazeFaceModel.INPUT_CHANNELS,
                    ),
                ),
            ) {
                "expected input [1, ${BlazeFaceModel.INPUT_SIDE}, ${BlazeFaceModel.INPUT_SIDE}, " +
                    "${BlazeFaceModel.INPUT_CHANNELS}], model has ${input.shape().contentToString()}"
            }
            require(interpreter.getOutputTensorCount() == 2) {
                "expected 2 output tensors, model has ${interpreter.getOutputTensorCount()}"
            }
            require(
                interpreter.getOutputTensor(BlazeFaceModel.OUTPUT_INDEX_REGRESSORS).shape().contentEquals(
                    intArrayOf(1, BlazeFaceModel.ANCHORS, BlazeFaceModel.ANCHOR_STRIDE),
                ),
            ) {
                "expected regressors [1, ${BlazeFaceModel.ANCHORS}, ${BlazeFaceModel.ANCHOR_STRIDE}], " +
                    "model has ${interpreter.getOutputTensor(BlazeFaceModel.OUTPUT_INDEX_REGRESSORS).shape().contentToString()}"
            }
            require(
                interpreter.getOutputTensor(BlazeFaceModel.OUTPUT_INDEX_CLASSIFICATORS).shape().contentEquals(
                    intArrayOf(1, BlazeFaceModel.ANCHORS, 1),
                ),
            ) {
                "expected classificators [1, ${BlazeFaceModel.ANCHORS}, 1], " +
                    "model has ${interpreter.getOutputTensor(BlazeFaceModel.OUTPUT_INDEX_CLASSIFICATORS).shape().contentToString()}"
            }
        }
    }
}

/** The 896-anchor grid, built once. `BlazeFaceModel.geometry()` is pure, so this is free. */
private val geometry: BlazeFaceGeometry = BlazeFaceModel.geometry()

/**
 * Normalised input-image units to source-crop pixels.
 *
 * One place, so there is exactly one scaling choice on the path from the model's output to the
 * alignment crop. The multiply is by the *source* extent rather than by the model's 128: the decode
 * is expressed in fractions of the input image, and the input image is the whole frame.
 */
private fun BlazeFaceAnchor.toBox(width: Int, height: Int): TfliteFaceDetector.Box = TfliteFaceDetector.Box(
    left = left * width,
    top = top * height,
    right = right * width,
    bottom = bottom * height,
)

/** The five decoded keypoints in source-crop pixels, in the model's own order. */
private fun BlazeFaceAnchor.toLandmarks(width: Int, height: Int): List<TfliteFaceDetector.Point> =
    keypoints.map { TfliteFaceDetector.Point(it.x * width, it.y * height) }

/**
 * Model files on disk, in the app's own storage.
 *
 * Assets are copied to `filesDir` on first run rather than read from the APK every time: a
 * memory-mapped interpreter needs a real file, and reading the same ~5 MB from a compressed
 * asset on every screening would put a measurable cost on every one of a shift's screenings.
 * The copy happens once and its destination is app-scoped, which needs no storage permission.
 */
object ModelStore {

    fun file(context: Context, name: String): File = java.io.File(context.filesDir, MODELS_DIR + "/" + name)

    /** @return `true` when the model is present and the copy completed. Never throws. */
    fun ensureCopied(context: Context, assetPath: String, name: String): Boolean = try {
        val target = file(context, name)
        target.parentFile?.mkdirs()
        if (!target.isFile || target.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        target.isFile && target.length() > 0L
    } catch (_: java.io.IOException) {
        false
    }

    const val MODELS_DIR = "models"

    /** Asset paths, matching `scripts/fetch_models.sh`'s manifest layout. */
    const val EMB_ASSET = "models/emb_v1.tflite"
    const val DETECT_ASSET = "models/blazeface_short.tflite"

    /**
     * The macro classifier weights (DESIGN.md §6). D-MACRO.
     *
     * Checked before [SVM_SYNTHETIC_ASSET], never after: a build that still bundles the synthetic
     * weights must not quietly keep classifying with them once a real model exists. What a build
     * bundles is a packaging decision, and the eval gate is where that is recorded —
     * `eval/models/manifest.json` says which file is which, and the model card beside each says
     * what it may not be used for.
     */
    const val SVM_ASSET = "models/svm_print_v1.json"

    /**
     * The committed synthetic weights, so a demo build classifies instead of abstaining on every
     * patch. A model fitted on generated textures; never gate-eligible (SPEC.md §7), and the
     * `synthetic` field inside the file is what carries that, not the filename.
     */
    const val SVM_SYNTHETIC_ASSET = "models/svm_print_v1_synthetic.json"

    /** The UIDAI QR key ring. A missing file degrades to "cannot verify", never to "verified". */
    const val QR_KEYS_ASSET = "models/uidai_qr_keys.json"
}

/**
 * The [Digest] used for the model pin, for the model pin.
 *
 * Delegates to [JcaDigest] rather than building one inline: `dev.kasoti.crypto.Digest` is a plain
 * Kotlin `interface`, not a `fun interface`, so `Digest { bytes -> … }` is not a SAM conversion and
 * does not compile. `JcaDigest` is the module's existing JCA-backed implementation and the one
 * `:platform`'s `JcaDigest` mirrors, so there is now one implementation of the pin's hash on the
 * Android side rather than two that could disagree.
 */
fun defaultDigest(): Digest = JcaDigest()
