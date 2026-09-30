package dev.kasoti.android.field

import dev.kasoti.factory.GrayImage
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.face.FaceSample
import dev.kasoti.face.GateResult
import dev.kasoti.face.QualityGate
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * Raw measurements off a frame or a patch, before any gate.
 *
 * Deliberately a plain record of numbers with no verdict attached, so the same struct can come
 * from a live preview, a still capture, a macro patch, or a demo fixture — and so a threshold
 * change shows up as a changed [dev.kasoti.face.GateResult] rather than as a changed input type.
 */
data class CaptureMeasurements(
    /** Variance of the Laplacian over the region of interest. The focus/blur measure. */
    val blurVariance: Float,
    /** Fraction of pixels above the specular-highlight threshold. */
    val glareRatio: Float,
    /** Mean luma, 0..255. */
    val brightness: Float,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val occluded: Boolean = false,
) {
    fun toFaceSample(): FaceSample = FaceSample(
        blurVariance = blurVariance,
        glareRatio = glareRatio,
        brightness = brightness,
        yawDegrees = yawDegrees,
        pitchDegrees = pitchDegrees,
        occluded = occluded,
    )
}

/**
 * The capture-quality gate (FR-C1, SPEC principle 4, FUSION.md §4).
 *
 * This is the single most important class in the app, and the reason is a failure mode rather
 * than a feature: **a bad capture produces a low face similarity, and a low face similarity
 * looks exactly like an impersonation.** Run the match first and the honest answer to "why is
 * this RED?" becomes "the sun was behind you", which is not a thing anyone can say to a member
 * of the public. Gating first makes the bad-capture case produce a *retake instruction* — and
 * FUSION.md's `A-GREY3` rule exists precisely to stop the alternative abuse, a refusal dressed
 * up as a retake.
 *
 * Every threshold is read from the [ThresholdRegistry] (FR-R3). There is no numeric literal in
 * this file, so re-tuning an operating point is a registry change with a review trail rather
 * than a code edit (AGENTS.md §2).
 *
 * **Ordering contract, enforced by [EvidenceAssembler] and not merely by this class:** nothing
 * downstream may read a similarity number until [evaluateDocument] has returned. That is the
 * property, and it is structural there.
 */
class CaptureQuality(private val registry: ThresholdRegistry) {

    /**
     * The face-capture gate. Pose is only meaningful for a face, so the pose causes are added
     * by the caller when landmarks are actually available rather than being assumed to be zero.
     */
    fun evaluateDocument(m: CaptureMeasurements, hasPose: Boolean = false): QualityReport {
        val gate = evaluate(m, hasPose)
        return QualityReport(
            passed = gate.passed,
            causes = gate.causes,
            blurScore = m.blurVariance,
            glareRatio = m.glareRatio,
            brightness = m.brightness,
            yawDegrees = m.yawDegrees,
            pitchDegrees = m.pitchDegrees,
            occluded = m.occluded,
        )
    }

    /**
     * The document-page gate.
     *
     * Same thresholds as the face gate for blur, glare and brightness, because a document and a
     * face are read by the same operator under the same sun through the same lens — and a
     * second, subtly different set of numbers is how a document path ends up accepting the
     * captures a face path rejects. Pose and occlusion do not apply to a page.
     */
    fun evaluateDocumentPage(m: CaptureMeasurements): QualityReport {
        val gate = evaluate(m, hasPose = false)
        return QualityReport(
            passed = gate.passed,
            causes = gate.causes,
            blurScore = m.blurVariance,
            glareRatio = m.glareRatio,
            brightness = m.brightness,
            occluded = m.occluded,
        )
    }

    private fun evaluate(m: CaptureMeasurements, hasPose: Boolean): GateResult = QualityGate.evaluate(
        sample = FaceSample(
            blurVariance = m.blurVariance,
            glareRatio = m.glareRatio,
            brightness = m.brightness,
            // With no landmark the honest value is "not measured", and `QualityGate` treats
            // 0° as perfectly straight, so the pose checks pass and only the measurable causes
            // can fire. Substituting a large angle to mean "unknown" would fail every capture
            // on a device whose detector did not load.
            yawDegrees = if (hasPose) m.yawDegrees else 0f,
            pitchDegrees = if (hasPose) m.pitchDegrees else 0f,
            occluded = m.occluded,
        ),
        blurMin = registry[ThresholdName.Q_BLUR].toFloat(),
        glareMax = registry[ThresholdName.Q_GLARE].toFloat(),
        brightnessMin = registry[ThresholdName.Q_BRIGHT_MIN].toFloat(),
        brightnessMax = registry[ThresholdName.Q_BRIGHT_MAX].toFloat(),
        yawMax = registry[ThresholdName.Q_POSE_YAW].toFloat(),
        pitchMax = registry[ThresholdName.Q_POSE_PITCH].toFloat(),
    )

    /**
     * The macro sharpness gate (FR-C3's sharpness bar).
     *
     * Separate from [evaluate] for one reason: the failure code is `G_FOCUS`, not `G_BLUR`.
     * The operator needs a different instruction — "clip is out of focus, re-seat the clip and
     * hold still" — because the remedy is physical and specific, and a generic "image is
     * blurry" sends them to wipe the lens, which does not help a hair of macro focus.
     *
     * Bar is `Q_BLUR`, the same number the face gate uses. Reusing it is deliberate: the patch
     * and the face are captured through the same optics, and a second sharpness threshold
     * would be a second thing to tune with no data behind it.
     */
    fun evaluateMacroSharpness(blurVariance: Float): Sharpness {
        val bar = registry[ThresholdName.Q_BLUR].toFloat()
        val passed = blurVariance >= bar
        return Sharpness(
            blurVariance = blurVariance,
            /** 0..1 against the bar, so the bar renders full at the threshold and never past it. */
            fraction = (blurVariance / bar).coerceIn(0f, 1f),
            passed = passed,
            code = if (passed) null else FindingCode.G_FOCUS,
        )
    }

    /**
     * UV availability (FR-F2).
     *
     * A three-state answer, never a boolean. A phone with no UV torch and a phone whose torch
     * was simply not switched on are different operational stories, and conflating them either
     * manufactures a "no UV" finding on a capable device or hides the lack of capability
     * behind a reassuring green light. `UNSUPPORTED` is the honest default.
     */
    fun uvState(torchAvailable: Boolean, torchOn: Boolean, uVResponseObserved: Boolean?): UvReading = when {
        uVResponseObserved != null ->
            if (uVResponseObserved) UvReading.PRESENT else UvReading.ABSENT
        !torchAvailable -> UvReading.UNSUPPORTED
        !torchOn -> UvReading.NOT_OBSERVED
        else -> UvReading.UNSUPPORTED
    }

    data class Sharpness(
        val blurVariance: Float,
        val fraction: Float,
        val passed: Boolean,
        val code: FindingCode?,
    )

    enum class UvReading { PRESENT, ABSENT, NOT_OBSERVED, UNSUPPORTED }
}

/**
 * Measure focus and illumination off a grayscale crop.
 *
 * Three cheap statistics, all of which `:core` needed but which `:core` deliberately does not
 * have: `:core` is a pure library with no image type of its own, and the moment it grew one it
 * would stop being testable against synthetic patterns. These are ~40 lines of integer
 * arithmetic with no dependencies, which is the whole of the cost.
 *
 * The numbers are computed on a region of interest rather than the whole frame, because the
 * shutter's own sharpness and the operator's fingers at the edge of the frame are not evidence
 * about the document.
 */
object FrameMetrics {

    /**
     * Variance of the Laplacian — the standard cheap focus measure (DESIGN.md §5).
     *
     * A 3x3 Laplacian on normalised 0..1 samples produces a raw variance in the low
     * hundredths: a hard vertical step edge over a 64x64 crop measures about 0.032, because
     * only two pixel columns have a non-zero Laplacian at all. `ThresholdRegistry.Q_BLUR` is
     * expressed in a 30..400 band with a default of 100, so the raw variance is multiplied by
     * [VARIANCE_SCALE] to land in that band.
     *
     * Getting this wrong would move the gate without anybody re-tuning it, and the failure is
     * silent in the dangerous direction: too large a scale and every macro patch reads as
     * perfectly focused, so the `G_FOCUS` retake never fires. The agreement is therefore
     * asserted in `CaptureQualityTest` against the registry rather than left to a comment.
     */
    const val VARIANCE_SCALE = 10_000f

    fun blurVariance(image: GrayImage): Float {
        val w = image.width
        val h = image.height
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until (h - 1)) {
            for (x in 1 until (w - 1)) {
                val l = laplacian(image, x, y)
                sum += l
                sumSq += l * l
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return (variance * VARIANCE_SCALE).toFloat()
    }

    private fun laplacian(image: GrayImage, x: Int, y: Int): Double {
        val c = image[x, y]
        val n = image[x, y - 1]
        val s = image[x, y + 1]
        val w = image[x - 1, y]
        val e = image[x + 1, y]
        return (4.0 * c - n - s - w - e)
    }

    /**
     * Fraction of pixels above the specular-highlight level, in 0..1.
     *
     * Specular highlights are *brightness saturations caused by a reflection*, and they are
     * what destroys an MRZ read. A uniformly bright document is a different problem and gets a
     * different cause (`G_DARK`'s counterpart on the bright side), so the threshold is set
     * near the top of the range rather than at "noticeably bright".
     */
    fun glareRatio(image: GrayImage, level: Float = GLARE_LEVEL): Float {
        var hits = 0
        for (p in image.pixels) if (p >= level) hits++
        return hits.toFloat() / image.pixels.size
    }

    /** Mean luma, 0..255, which is what `Q_BRIGHT_MIN`/`Q_BRIGHT_MAX` are expressed in. */
    fun brightness(image: GrayImage): Float = image.pixels.sum().toFloat() / image.pixels.size * 255f

    /**
     * 0.94 normalised ≈ 240/255.
     *
     * A framebuffer-white card and a specular highlight of a bulb both sit above 240. The
     * exact value is a property of the sensor and the glass rather than of the person being
     * screened, and the ratio is compared against `Q_GLARE` (default 0.12) — so what matters
     * is that the level be *above the paper* and *at the highlight*, which any level in
     * [0.92, 0.97] satisfies. It is named, documented, and asserted in the tests, rather than
     * being a bare literal.
     */
    const val GLARE_LEVEL = 0.94f
}
