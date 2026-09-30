package dev.kasoti.android.field

import dev.kasoti.factory.DocAggregator
import dev.kasoti.factory.GrayImage
import dev.kasoti.factory.DocProcess
import dev.kasoti.factory.MacroGate
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.UvState
import dev.kasoti.threshold.ThresholdRegistry

/**
 * The macro step: two patches, one operator action (FR-C3).
 *
 * ## Why the two patches are zones and not two crops
 *
 * A genuine document is printed in one pass, so its photo zone and its text zone come from the
 * same process and agree. A paste-up, a reprint on a different device, or a glue-swapped photo
 * produces a pair that *disagrees* — and that disagreement, not either label on its own, is the
 * signal `R-PROC-02` reads. So the classifier is run twice and the two readings are compared;
 * a single patch cannot distinguish "this is inkjet" from "this is a photocopied inkjet", which
 * are different findings.
 *
 * ## The clip is not optional
 *
 * Without the clip the patch is dominated by the lens's own optics — moiré, aberrations,
 * chromatic fringes at the sensor — and the halftone lattice the spectrum features look for is
 * *below* that noise floor. So [MacroEvidence.clipUsed] is carried into fusion, where
 * `TrackMatrix` reads it: no clip means the MACRO layer is `ABSENT`, not `PRESENT`, and for a
 * passport that makes GREEN unreachable. The app does not decide to be lenient here; it
 * reports what it measured and `:core` applies the fail-closed rule.
 */
class MacroStage(
    private val classifier: ProcessClassifier?,
    private val registry: ThresholdRegistry,
    private val quality: CaptureQuality,
    private val patchSize: Int = PATCH_SIZE,
) {

    /** Which patch is being taken. Mirrors `dev.kasoti.ui.MacroZoneSlot` without importing `:ui`. */
    enum class Slot { PHOTO_ZONE, TEXT_ZONE }

    /**
     * One patch's result, in the app's own vocabulary.
     *
     * [label] is `:core`'s *fusion* [dev.kasoti.fusion.ProcessLabel], not the classifier's
     * `dev.kasoti.factory.ProcessLabel`. `:core` declares two enums of the same name — one in
     * `fusion` (what a verdict reads) and one in `factory` (what the model emits) — and the
     * mapping is a lookup, not an identity. Getting it wrong is silent: a mistyped conversion
     * would compile, would return the right *arity*, and would mislabel every document. So it
     * is a `when` over all seven entries with a total function and its own test.
     */
    data class Patch(
        val slot: Slot,
        val label: dev.kasoti.fusion.ProcessLabel,
        val margin: Float,
        val sharpness: CaptureQuality.Sharpness,
        val image: GrayImage?,
    )

    data class Stage(
        val photoZone: Patch?,
        val textZone: Patch?,
        val clipUsed: Boolean,
        val uv: CaptureQuality.UvReading,
    ) {
        val complete: Boolean get() = photoZone != null && textZone != null

        /** The step may advance only when both patches are focused. */
        val canAdvance: Boolean
            get() = complete && photoZone?.sharpness?.passed == true && textZone?.sharpness?.passed == true

        val blockingCodes: List<dev.kasoti.fusion.FindingCode>
            get() = listOfNotNull(photoZone?.sharpness?.code, textZone?.sharpness?.code)

        /**
         * The evidence, or `null` when the step did not complete.
         *
         * `null` is a real answer: an incomplete macro step means the layer did not run, and
         * `:core` needs `null` (layer ABSENT) rather than a made-up reading.
         */
        fun evidence(): MacroEvidence? {
            val photo = photoZone ?: return null
            val text = textZone ?: return null
            return MacroEvidence(
                photoZoneLabel = photo.label,
                photoZoneMargin = photo.margin,
                textZoneLabel = text.label,
                textZoneMargin = text.margin,
                uvState = uvToState(uv),
                clipUsed = clipUsed,
            )
        }

        /**
         * The three UV readings → `:core`'s two UV states.
         *
         * `NOT_OBSERVED` — torch present but not switched on, or the response never sampled —
         * maps to `ABSENT`, not `UNSUPPORTED`. The distinction is the whole point of FR-F2's
         * three-state answer: `UNSUPPORTED` is a statement about the *device* and reads as
         * "this build cannot do UV, the check is waived", whereas "we did not run it" is a
         * statement about *this case* and must not silently waive anything. UV is auxiliary on
         * every track where it applies, so neither choice can reach GREEN on its own — but the
         * difference is visible in the layer table, and an operator reading the table is
         * entitled to know which of the two happened.
         */
        private fun uvToState(reading: CaptureQuality.UvReading): UvState = when (reading) {
            CaptureQuality.UvReading.PRESENT -> UvState.PRESENT
            CaptureQuality.UvReading.ABSENT, CaptureQuality.UvReading.NOT_OBSERVED -> UvState.ABSENT
            CaptureQuality.UvReading.UNSUPPORTED -> UvState.UNSUPPORTED
        }
    }


    /**
     * Classify one patch.
     *
     * Focus is measured **before** classification and the classifier is not consulted when the
     * patch is out of focus. A blurred patch's spectrum still produces a confident-looking
     * margin — the FFT has no opinion about whether the lattice it found was ever there — and
     * `R-PROC-01`/`R-PROC-02` are hard REDs built on that margin. Measuring focus first is
     * what stops a shaky macro shot from being read as a forged document.
     */
    fun take(slot: Slot, image: GrayImage, clipUsed: Boolean, uv: CaptureQuality.UvReading, existing: Stage): Stage {
        val normalised = if (image.width == patchSize && image.height == patchSize) {
            image
        } else {
            dev.kasoti.android.field.Resize.toSquare(image, patchSize)
        }
        val sharpness = quality.evaluateMacroSharpness(FrameMetrics.blurVariance(normalised))
        val patch = Patch(
            slot = slot,
            label = classify(normalised),
            margin = if (sharpness.passed) marginOf(normalised) else 0f,
            sharpness = sharpness,
            image = normalised,
        )
        return when (slot) {
            Slot.PHOTO_ZONE -> existing.copy(photoZone = patch, clipUsed = clipUsed, uv = uv)
            Slot.TEXT_ZONE -> existing.copy(textZone = patch, clipUsed = clipUsed, uv = uv)
        }
    }

    /**
     * @return the fusion-facing label, or `UNKNOWN` when no model is bound **or** the model was
     *   not confident enough.
     *
     * `UNKNOWN` rather than a guess. `ProcessLabel.UNKNOWN` is a first-class outcome in `:core`
     * precisely so a patch that cannot be classified degrades to AMBER/`A-WORN-01` instead of
     * being forced into the nearest class. A build with no trained weights must land there, and so
     * must a patch whose margin is under `MACRO_MARGIN_AMBER` — printing "OFFSET" from either
     * would be showing a classifier that does not exist, or one that is guessing.
     *
     * Routed through [MacroGate] so the desktop console and this app apply the same floor to the
     * same scores. Two implementations of a safety rule is one more than there should be, and the
     * floor already lives in one place: the registry.
     */
    private fun classify(image: GrayImage): dev.kasoti.fusion.ProcessLabel {
        val model = classifier ?: return dev.kasoti.fusion.ProcessLabel.UNKNOWN
        return fusionLabel(MacroGate.read(model, image, registry).label)
    }

    private fun marginOf(image: GrayImage): Float =
        classifier?.let { MacroGate.read(it, image, registry).patch.margin } ?: 0f

    /**
     * The document-level zone agreement (FR-F1's second half), for the console and the bundle.
     *
     * `null` when there is nothing to aggregate. Note that this is *diagnostic*: the verdict
     * reads [MacroEvidence] through `:core`'s own `MacroReading`, which applies the same
     * margin floors again. Reporting both would mean two implementations of the same rule, and
     * a console that disagreed with the phone about whether zones matched.
     */
    fun aggregate(stage: Stage): DocProcess? {
        val model = classifier ?: return null
        val photo = stage.photoZone?.image ?: return null
        val text = stage.textZone?.image ?: return null
        return DocAggregator.aggregate(model.classify(photo), model.classify(text), registry)
    }

    companion object {
        /** DATA.md §3: 256×256 patches, so a live capture and a corpus import are the same input. */
        const val PATCH_SIZE = 256

        /**
         * The classifier's `dev.kasoti.factory.ProcessLabel` → fusion's.
         *
         * A total `when` on purpose. Both enums have seven entries with identical names today;
         * the day one gains an entry, this stops compiling and the build fails rather than
         * someone's document being labelled by whatever a cast happened to produce.
         */
        fun fusionLabel(label: dev.kasoti.factory.ProcessLabel): dev.kasoti.fusion.ProcessLabel =
            when (label) {
                dev.kasoti.factory.ProcessLabel.OFFSET -> dev.kasoti.fusion.ProcessLabel.OFFSET
                dev.kasoti.factory.ProcessLabel.INKJET -> dev.kasoti.fusion.ProcessLabel.INKJET
                dev.kasoti.factory.ProcessLabel.LASER -> dev.kasoti.fusion.ProcessLabel.LASER
                dev.kasoti.factory.ProcessLabel.DYESUB -> dev.kasoti.fusion.ProcessLabel.DYESUB
                dev.kasoti.factory.ProcessLabel.SCREEN -> dev.kasoti.fusion.ProcessLabel.SCREEN
                dev.kasoti.factory.ProcessLabel.PHOTOCOPY -> dev.kasoti.fusion.ProcessLabel.PHOTOCOPY
                dev.kasoti.factory.ProcessLabel.UNKNOWN -> dev.kasoti.fusion.ProcessLabel.UNKNOWN
            }
    }
}

/**
 * Bilinear resize of a grayscale image, `:core` types only.
 *
 * `:platform`'s `ImageIoImaging.resize` is the JVM/ImageIO one and is not reachable from an
 * Android module, and `:core` has no imaging. Half-pixel centres, for the same reason
 * `ImageIoImaging.resize` uses them: corner mapping shifts the image by a quarter pixel per
 * step, and a low-frequency artefact of that kind is precisely what the macro spectrum
 * features would report as evidence.
 *
 * The intended home is `:platform/androidMain` alongside the Android bitmap adapter; it lives
 * here for now because this module is the only one owned by the field-app work, and it is
 * marked as such rather than pretending to be the right place.
 */
object Resize {

    fun toSquare(image: GrayImage, size: Int): GrayImage {
        require(size > 0) { "target size must be positive, got $size" }
        if (image.width == size && image.height == size) return image
        val out = FloatArray(size * size)
        val scaleX = image.width.toFloat() / size
        val scaleY = image.height.toFloat() / size
        for (y in 0 until size) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            val y0 = kotlin.math.floor(sy).toInt().coerceIn(0, image.height - 1)
            val y1 = (y0 + 1).coerceAtMost(image.height - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            for (x in 0 until size) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val x0 = kotlin.math.floor(sx).toInt().coerceIn(0, image.width - 1)
                val x1 = (x0 + 1).coerceAtMost(image.width - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val top = image[x0, y0] + (image[x1, y0] - image[x0, y0]) * fx
                val bottom = image[x0, y1] + (image[x1, y1] - image[x0, y1]) * fx
                out[y * size + x] = top + (bottom - top) * fy
            }
        }
        return GrayImage(size, size, out)
    }
}
