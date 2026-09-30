package dev.kasoti.fusion

/**
 * What to do about a layer that never ran (AGENTS.md §4, "fail closed").
 *
 * Absence is not a pass. A passport whose chip was never read has not been authenticated by
 * the chip, and a report that quietly omits the chip line is indistinguishable from one where
 * the chip said yes — so absence is surfaced, ranked, and it puts a floor under the verdict.
 *
 * The remedy differs by layer, and the difference matters to the person in front of the
 * camera. A missing macro clip or an unreadable zone is fixed by another capture, so it is a
 * retake (GREY). A missing chip or an unused face layer is not: re-aiming the camera will not
 * make a comparison happen, so it escalates to a human (AMBER).
 */
internal object CoverageRules {

    data class Coverage(
        /** Retake remedies — a GREY cause from FUSION.md §4. */
        val retakes: List<Finding>,
        /** Checks that could not be completed at all — an AMBER escalation. */
        val escalations: List<Finding>,
    )

    fun evaluate(layers: LayerPresence, case: Evidence): Coverage {
        val retakes = mutableListOf<Finding>()
        val escalations = mutableListOf<Finding>()

        for (layer in Layer.entries) {
            if (layers.bearingOf(layer) != Bearing.LOAD_BEARING) continue
            if (layers.isClear(layer)) continue

            when (layer) {
                Layer.MACRO -> retakes += Finding(
                    FindingCode.G_NOCLIP,
                    Severity.GREY,
                    "capture/macro#${layers.ref(layer)}",
                    "the macro clip is required for this track and is missing",
                )

                Layer.MATH -> retakes += Finding(
                    FindingCode.G_OCRLOW,
                    Severity.GREY,
                    "capture/mrz#${layers.ref(layer)}",
                    "the machine-readable zone is required for this track and was not read",
                )

                else -> escalations += Finding(
                    FindingCode.A_MISSING_LAYER,
                    Severity.AMBER,
                    "evidence/layers#${layer.name.lowercase()}=${layers.statusOf(layer).name}",
                    "required check ${layer.name.lowercase()} did not produce a usable result " +
                        "(${layers.statusOf(layer).name}); it is not treated as a pass",
                )
            }
        }

        if (!case.quality.passed) {
            // Already handled by the gate itself; the coverage findings would only restate it.
            return Coverage(retakes = retakes, escalations = emptyList())
        }
        return Coverage(retakes = retakes, escalations = escalations)
    }
}
