package dev.kasoti.factory

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * A patch's reading, with the abstention decision attached.
 *
 * [label] is what the system acts on and [patch] is what the model actually said. They differ
 * whenever the model was not confident enough, and keeping both means a report can show the
 * operator "the model said OFFSET at margin 0.09, so the system is not claiming OFFSET" instead
 * of collapsing the two into a single lie.
 */
data class MacroReading(
    val patch: PatchResult,
    /** [PatchResult.label] if the margin cleared the floor, otherwise [ProcessLabel.UNKNOWN]. */
    val label: ProcessLabel,
    val abstained: Boolean,
    /** True when the model was not consulted at all — no weights bound. */
    val noModel: Boolean,
) {
    val margin: Float get() = patch.margin
}

/**
 * The abstention rule, in one place, for both platforms.
 *
 * ## Why this is here and not in each app
 *
 * `ProcessClassifier.classify` returns the argmax. That is a measurement, and on its own it is
 * the wrong thing to show a person: a patch that is 40% offset and 40% photocopy has an argmax,
 * and fusion's `MACRO_MARGIN_*` floors exist precisely because acting on an argmax without its
 * margin is how a worn card becomes an accusation. The floors already live in
 * [ThresholdRegistry]; what was missing was one implementation of "margin below the floor means
 * UNKNOWN", and two implementations of a safety rule is one more than there should be.
 *
 * ## The floor
 *
 * [ThresholdName.MACRO_MARGIN_AMBER], the lower of the two macro margins, is the floor. Below it
 * a patch cannot support even an AMBER-grade process reading, so `UNKNOWN` is returned and
 * fusion's `A-WORN-01` path takes it. Above it but below `MACRO_MARGIN_RED` the label is
 * reported, but `DocAggregator` will not build a MATCH or a MISMATCH out of it — that second
 * gate is [DocAggregator]'s and deliberately not duplicated here.
 *
 * ## No model is also abstention
 *
 * With no weights there is no measurement, so [noModel] is set and the label is `UNKNOWN` with a
 * zero margin. Not an exception: an app with no model provisioned must still complete a screening
 * on the other layers, and a throw here would take the whole cascade down over a missing asset.
 */
object MacroGate {

    fun read(
        classifier: ProcessClassifier?,
        image: GrayImage,
        registry: ThresholdRegistry,
    ): MacroReading = read(classifier, classifier?.extract(image), registry)

    fun read(
        classifier: ProcessClassifier?,
        features: MacroFeatures?,
        registry: ThresholdRegistry,
    ): MacroReading {
        if (classifier == null || features == null) return noModel()
        val floor = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
        val patch = classifier.classify(features)
        val confident = patch.margin >= floor && patch.label != ProcessLabel.UNKNOWN
        return MacroReading(
            patch = patch,
            label = if (confident) patch.label else ProcessLabel.UNKNOWN,
            abstained = !confident,
            noModel = false,
        )
    }

    /**
     * The `UNKNOWN` reading for a patch nothing has been said about.
     *
     * Built the same way `DocAggregator.EMPTY` is, so a missing model and an unreadable patch
     * produce the same object and a caller cannot tell them apart by accident. A caller that
     * *needs* to tell them apart reads [noModel].
     */
    fun noModel(): MacroReading = MacroReading(
        patch = PatchResult(
            label = ProcessLabel.UNKNOWN,
            margin = 0f,
            scores = mapOf(ProcessLabel.UNKNOWN to 1f),
            features = MacroFeatures(
                peakFrequency = 0f,
                peakiness = 1f,
                bandEnergy = 0f,
                highFrequencyEnergy = 0f,
                spectralSlope = 0f,
                stdDev = 0f,
                edgeDensity = 0f,
                lbpBins = FloatArray(MacroFeatures.LBP_BINS),
            ),
            halftone = HalftoneProfile(0f, 1f, 0f, 0f, 0f),
        ),
        label = ProcessLabel.UNKNOWN,
        abstained = true,
        noModel = true,
    )
}
