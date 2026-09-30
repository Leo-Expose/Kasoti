package dev.kasoti.platform.ml.tflite

import dev.kasoti.face.Embedding
import dev.kasoti.platform.imaging.RgbImage
import dev.kasoti.platform.ml.EmbeddingModel
import dev.kasoti.platform.ml.ModelLoader
import java.nio.file.Path

/**
 * Why a face layer could not be built, as a closed vocabulary.
 *
 * A closed set rather than free text, for the same reason as `ModelErrorCode`: every value here
 * is a *configuration* fact an operator has to act on, and it must not be phrased differently on
 * every machine. `docs/STATUS.md` carries the prose.
 */
enum class FaceLayerUnavailable(val messageKey: String) {
    /**
     * No TFLite JNI native exists for this OS/arch. Expected on Windows x86-64 and macOS
     * aarch64 — BUILD.md §4's prescribed response is "review-only, no on-device inference".
     */
    NO_RUNTIME_FOR_PLATFORM("err.face.no_runtime_for_platform"),

    /** No TFLite native on the classpath at all — a build/provisioning miss, not a capability. */
    RUNTIME_MISSING("err.face.runtime_missing"),

    /** The model file is not where the manifest said it would be. */
    MODEL_MISSING("err.model_missing"),

    /** The bytes are present but are not the pinned model. RT-E8. */
    MODEL_MISMATCH("err.model_hash"),

    /**
     * The embedder slot is empty.
     *
     * This is the current, intended state: no permissively-licensed 128-d TFLite face-embedding
     * checkpoint could be obtained, and none was fabricated. See `docs/spikes/01-face-model.md`
     * §2.1 for the seven candidates and why each is unusable.
     */
    EMBEDDER_UNAVAILABLE("err.face.embedder_unavailable"),
}

/**
 * The desktop face layer: a detector that may or may not be present, and an embedder that is
 * currently never present.
 *
 * ## Why the two are separate nullable fields rather than one object
 *
 * They fail for different reasons and the caller must react differently. A missing *detector* on
 * Windows means "no on-device inference on this machine" — a build-shape fact. A missing
 * *embedder* is the project's current state on every platform and is a **licensing** fact. Folding
 * them into one `available: Boolean` would report the second as the first, and a reader of
 * `docs/STATUS.md` would conclude the Windows desktop issue is the only outstanding blocker. It
 * is not; it is the smaller of the two.
 *
 * ## The rule this class exists to enforce
 *
 * **`embed()` returns `null` when it did not measure.** Never a zero vector, never a
 * plausible-looking one.
 *
 * `FaceMath.cosine` returns `0f` for a zero-length input, so a zero embedding would compare as
 * "completely different" rather than "not measured", and a caller that treated a low score as
 * "did not match" would report an accusation that was never evidence. That is the exact
 * false-accusation path the pre-match quality gate in `:core` exists to prevent, and a fabricated
 * vector would walk straight past it. `null` propagates to `face = null`, which makes GREEN
 * unreachable (FUSION.md §7) and surfaces a specific retake/observe finding.
 */
class DesktopFaceLayer private constructor(
    val detector: TfliteBlazeFaceDetector?,
    val embedder: EmbeddingModel?,
    val unavailableReasons: List<FaceLayerUnavailable>,
) : AutoCloseable {

    /** True when a face can be *located*. Says nothing about whether one can be *compared*. */
    val canDetect: Boolean get() = detector != null

    /** True when a face can be *compared* to a reference. Currently `false` everywhere. */
    val canEmbed: Boolean get() = embedder != null

    /**
     * True only when a 1:1 face verdict is arithmetically possible.
     *
     * The one boolean `FusionEngine` should ask before it claims a face check ran. It is `false`
     * today, and that is the correct, fail-closed answer rather than a defect.
     */
    val canCompareFaces: Boolean get() = canDetect && canEmbed

    /**
     * Locate a face. **There is no embedding path yet, and there is deliberately no stub for one.**
     *
     * The obvious-looking implementation — detect, build a 1×1 or centre-cropped
     * `GrayImage`, hand it to the embedder — is exactly the fabrication this project forbids. A
     * `GrayImage` that is not a 112×112 5-point-aligned crop produces a real-looking embedding
     * from the wrong pixels, and a plausible similarity number is far more dangerous than a
     * missing one, because it survives review. The 5-point alignment that DESIGN.md §5 requires
     * ships *with* the embedder, so that both platforms can be compared against each other
     * (spike 01 §8 item 1).
     *
     * @return the detection, or `null` when no face cleared the detector's score floor. Note this
     *   is `null` for "no face in frame" and the layer's [canDetect]/[unavailableReasons] are how
     *   a caller tells that apart from "this machine cannot look".
     */
    fun detect(frame: RgbImage): FaceDetection? {
        val found = detector?.detect(frame) ?: return null
        check(found.keypoints.size == BlazeFaceContract.OUTPUT_KEYPOINTS) {
            "expected ${BlazeFaceContract.OUTPUT_KEYPOINTS} keypoints, got ${found.keypoints.size}"
        }
        return found
    }

    /**
     * Always `null`, on every platform, until an embedder is licensed and pinned.
     *
     * Present so that the call site that needs an embedding has something honest to call, and so
     * that the absence is a single greppable line rather than a gap in a class diagram. The
     * Android half reports the same state through `TfliteFaceModels.embed(...): Embedding?`
     * returning `null` from `app-android/src/main/java/dev/kasoti/android/platform/TfliteFace.kt`.
     */
    fun embed(frame: RgbImage): Embedding? {
        // Referencing `frame` is deliberate: it keeps the signature honest about what a real
        // implementation will need, and makes an accidental silent-ignore impossible to ship.
        check(frame.width > 0 && frame.height > 0) { "cannot embed an empty frame" }
        return null
    }

    override fun close() {
        detector?.close()
    }

    companion object {

        /**
         * Build from a manifest-installed model directory.
         *
         * Never throws for a *missing* capability: a missing runtime or a missing file yields a
         * layer with [canDetect] `false` and a stated reason. It does still throw
         * [dev.kasoti.platform.ml.ModelLoadException] on a hash mismatch, because that is
         * tampering rather than a missing capability, and swallowing it would hide RT-E8.
         */
        fun open(loader: ModelLoader, modelDir: Path): DesktopFaceLayer {
            val reasons = mutableListOf<FaceLayerUnavailable>()

            val runtime = TfliteRuntime.availability
            if (!runtime.platformSupported) {
                reasons += FaceLayerUnavailable.NO_RUNTIME_FOR_PLATFORM
            } else if (!runtime.available) {
                reasons += FaceLayerUnavailable.RUNTIME_MISSING
            }

            val detector = if (runtime.available) {
                val file = modelDir.resolve(BlazeFaceContract.NAME)
                when {
                    !java.nio.file.Files.isRegularFile(file) -> {
                        reasons += FaceLayerUnavailable.MODEL_MISSING
                        null
                    }
                    else -> try {
                        TfliteBlazeFaceDetector.open(loader, file)
                    } catch (e: dev.kasoti.platform.ml.ModelLoadException) {
                        if (e.code == dev.kasoti.platform.ml.ModelErrorCode.MISSING) {
                            reasons += FaceLayerUnavailable.MODEL_MISSING
                        } else if (e.code == dev.kasoti.platform.ml.ModelErrorCode.TOO_SMALL) {
                            reasons += FaceLayerUnavailable.MODEL_MISSING
                        } else {
                            reasons += FaceLayerUnavailable.MODEL_MISMATCH
                        }
                        null
                    }
                }
            } else {
                null
            }

            // The embedder slot. Empty by decision, not by oversight — spike 01 §2.1.
            reasons += FaceLayerUnavailable.EMBEDDER_UNAVAILABLE

            return DesktopFaceLayer(detector, null, reasons.toList())
        }

        /** A layer with nothing in it, for tests and for a build with no models fetched. */
        fun empty(reason: FaceLayerUnavailable = FaceLayerUnavailable.EMBEDDER_UNAVAILABLE) =
            DesktopFaceLayer(null, null, listOf(reason))
    }
}
