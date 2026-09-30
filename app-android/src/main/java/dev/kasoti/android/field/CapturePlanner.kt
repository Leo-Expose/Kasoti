package dev.kasoti.android.field

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrackMatrix

/**
 * One capture slot, in the app's own vocabulary.
 *
 * Mirrors `dev.kasoti.ui.CaptureStepId` rather than importing it. `:ui` is presentation and
 * `:app-android` is field logic; both are allowed to depend on `:core` and neither on the
 * other, so a shared enum would either create a cycle or push a decision type into a module
 * that is not its owner. The mapping is one line in the view layer, and it is the *only*
 * place the two vocabularies meet.
 */
enum class CaptureStep {
    /** Full-page capture. Always required. */
    DOCUMENT,

    /** MRZ band. Required when the track matrix says MATH is load-bearing. */
    MRZ,

    /** Two macro patches under the clip shroud. */
    MACRO,

    /** The 1 s face clip. */
    FACE,
}

/**
 * The step list for one document, from the track matrix (FR-C1's "≤4 steps").
 *
 * ## The ordering is the cascade (DESIGN.md principle 2)
 *
 * Cheapest and most decisive first. `DOCUMENT` produces the routing signals; `MRZ` and `FACE`
 * both need a subject, so they follow; `MACRO` needs a steady hand and a clip, so it goes last
 * — putting the most physically demanding step at the end of a queue where people are tired is
 * how the last 10% of a shift is spent fighting the camera.
 *
 * ## Why a step is ever *dropped*
 *
 * An Aadhaar's MRZ is a Verhoeff check, not a machine-readable zone, and demanding an MRZ
 * capture from it would produce `G_OCRLOW` on every single genuine card. So the required set
 * is read from `dev.kasoti.fusion.TrackMatrix` — the same table `:core` uses to decide which
 * layers are load-bearing — rather than from a second opinion held here. One table, one truth:
 * a step is required exactly when the layer it feeds is load-bearing for that track.
 */
object CapturePlanner {

    /**
     * @param track the routed track. `Track.UNKNOWN` plans the full flow, because an
     *   unrecognised document must still be *looked at* before it is escalated; skipping
     *   straight to AMBER would be escalating a document nobody has examined.
     */
    fun plan(track: Track): List<CaptureStep> {
        val steps = mutableListOf(CaptureStep.DOCUMENT)

        if (track == Track.UNKNOWN) {
            // `TrackMatrix` declares nothing load-bearing for an unrecognised family, so the
            // generic rule below would plan a single step and the case would go straight to
            // GREY with nothing to look at. That is *worse* than useless: DEMO.md §5's line
            // for a judge's printout that breaks the router is "watch what honest software
            // does", and honest software looks at the document before escalating it. The face
            // step is the one capture that needs no document-family knowledge.
            steps += CaptureStep.FACE
            check(steps.size <= MAX_STEPS)
            return steps
        }

        if (isLoadBearing(track, dev.kasoti.fusion.Layer.MATH)) steps += CaptureStep.MRZ
        if (isLoadBearing(track, dev.kasoti.fusion.Layer.MACRO)) steps += CaptureStep.MACRO
        if (isLoadBearing(track, dev.kasoti.fusion.Layer.FACE)) steps += CaptureStep.FACE

        check(steps.size <= MAX_STEPS) {
            "FR-C1 caps the stranger flow at $MAX_STEPS steps; track $track planned ${steps.size}"
        }
        return steps
    }

    private fun isLoadBearing(track: Track, layer: dev.kasoti.fusion.Layer): Boolean =
        TrackMatrix.bearingOf(track, layer) == dev.kasoti.fusion.Bearing.LOAD_BEARING

    /**
     * The reason a planned step is required, in `:core`'s vocabulary.
     *
     * Surfaced so the capture screen can say *why* it is asking for a second patch rather than
     * the operator reading a three-step wizard as three arbitrary demands. The codes are
     * `A_MISSING_LAYER` plus the layer, which is exactly what `:core` will emit if the step is
     * skipped.
     */
    fun requirements(track: Track): List<StepRequirement> = plan(track).map { step ->
        val layer = when (step) {
            CaptureStep.DOCUMENT -> dev.kasoti.fusion.Layer.QUALITY
            CaptureStep.MRZ -> dev.kasoti.fusion.Layer.MATH
            CaptureStep.MACRO -> dev.kasoti.fusion.Layer.MACRO
            CaptureStep.FACE -> dev.kasoti.fusion.Layer.FACE
        }
        StepRequirement(
            step = step,
            layer = layer,
            required = isLoadBearing(track, layer),
            code = if (isLoadBearing(track, layer)) FindingCode.A_MISSING_LAYER else null,
        )
    }

    data class StepRequirement(
        val step: CaptureStep,
        val layer: dev.kasoti.fusion.Layer,
        val required: Boolean,
        val code: FindingCode?,
    )

    /** FR-C1: "Guided stranger flow ≤4 steps". Asserted in [plan], not just documented. */
    const val MAX_STEPS = 4
}
