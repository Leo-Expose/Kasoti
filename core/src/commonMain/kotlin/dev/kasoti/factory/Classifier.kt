package dev.kasoti.factory

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * Linear multiclass print-process model (FR-F1).
 *
 * Deliberately a *linear* model over [MacroFeatures.TOTAL] engineered features: the whole
 * point of the design is that the shipped artefact is ~50 KB of JSON that any reviewer can
 * read, that it trains in seconds on a laptop, and that the decision boundary stays
 * explainable to a judge ("the dot lattice pitch is outside the offset range"). A small
 * convolutional net would score better on paper and be far worse at every one of those.
 *
 * Weights are trained offline (`eval/tools/train_svm.py`) and exported to
 * `svm_print_v1.json`; nothing is fitted at runtime.
 */
class SvmModel(
    val version: String,
    val labels: List<ProcessLabel>,
    val weights: Array<FloatArray>,
    val bias: FloatArray,
    val featureNames: List<String>,
    val trainingRunId: String,
) {
    init {
        require(weights.size == labels.size) { "one weight vector per class" }
        require(bias.size == labels.size) { "one bias per class" }
        require(weights.all { it.size == MacroFeatures.TOTAL }) {
            "each weight vector must have ${MacroFeatures.TOTAL} entries, got ${weights.firstOrNull()?.size}"
        }
        require(featureNames.size == MacroFeatures.TOTAL) { "feature names must match the vector width" }
        require(labels.size == ProcessLabel.entries.size) { "every process class needs a weight vector" }
    }

    /** Raw (uncalibrated) decision scores, one per class. */
    fun scores(features: MacroFeatures): Map<ProcessLabel, Float> {
        val x = features.toVector()
        val out = LinkedHashMap<ProcessLabel, Float>()
        for (i in labels.indices) {
            var s = bias[i]
            val w = weights[i]
            for (j in x.indices) s += w[j] * x[j]
            out[labels[i]] = s
        }
        return out
    }
}

/**
 * Classifies a patch and reports a margin (FR-F1).
 *
 * The margin is the gap between the best and second-best class. It is the single most
 * important output here: a patch whose top two classes are nearly tied carries almost no
 * information, and the fusion rules must be able to see that and abstain rather than act on
 * a coin flip (FUSION.md §5, A-WORN-01).
 */
class ProcessClassifier(private val model: SvmModel) {

    fun extract(image: GrayImage): MacroFeatures {
        val spectrum = Spectrum.radialProfile(image)
        return MacroFeatures(
            peakFrequency = spectrum.peakFrequency,
            peakiness = spectrum.peakiness,
            bandEnergy = spectrum.bandEnergy,
            highFrequencyEnergy = spectrum.highFrequencyEnergy,
            spectralSlope = spectrum.spectralSlope,
            stdDev = image.stdDev,
            edgeDensity = Edges.density(image),
            lbpBins = Lbp.histogram(image),
        )
    }

    fun classify(image: GrayImage): PatchResult = classify(extract(image))

    fun classify(features: MacroFeatures): PatchResult {
        val raw = model.scores(features)
        // Softmax gives a probability-like reading of the linear scores; the margin is
        // computed on the softmax so it is comparable across patches with different score
        // scales, which raw margins are not.
        val max = raw.values.maxOrNull() ?: 0f
        val exps = raw.mapValues { (_, v) -> kotlin.math.exp((v - max).toDouble()) }
        val total = exps.values.sum()
        val probs = exps.mapValues { (_, v) -> (v / total).toFloat() }

        val ranked = probs.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val runnerUp = ranked.getOrNull(1)?.value ?: 0f
        val margin = best.value - runnerUp

        return PatchResult(
            label = best.key,
            margin = margin,
            scores = probs,
            features = features,
            halftone = features.toHalftone(),
        )
    }

    private fun MacroFeatures.toHalftone(): HalftoneProfile = HalftoneProfile(
        peakFrequency = peakFrequency,
        peakiness = peakiness,
        bandEnergy = bandEnergy,
        highFrequencyEnergy = highFrequencyEnergy,
        spectralSlope = spectralSlope,
    )
}

/**
 * Combines the photo zone and the text zone into a document-level reading (FR-F1).
 *
 * This is where paste attacks and cheap reprints show up: a genuine document is printed in
 * one pass, so both zones come from the same process. A photo that has been swapped in, or a
 * card reprinted on a different device, produces a zone pair that disagrees. When the
 * margins are low in either zone the disagreement is not evidence of anything, so the
 * result is [DocProcess.Agreement.UNCERTAIN] rather than a mismatch.
 */
object DocAggregator {

    // Declared before the patch that uses them: Kotlin initialises object properties in
    // declaration order, so a forward reference here would read an uninitialised field.
    private val EMPTY_FEATURES = MacroFeatures(
        peakFrequency = 0f,
        peakiness = 1f,
        bandEnergy = 0f,
        highFrequencyEnergy = 0f,
        spectralSlope = 0f,
        stdDev = 0f,
        edgeDensity = 0f,
        lbpBins = FloatArray(MacroFeatures.LBP_BINS),
    )

    private val EMPTY_HALFTONE = HalftoneProfile(0f, 1f, 0f, 0f, 0f)

    private val UNKNOWN_PATCH = PatchResult(
        label = ProcessLabel.UNKNOWN,
        margin = 0f,
        scores = mapOf(ProcessLabel.UNKNOWN to 1f),
        features = EMPTY_FEATURES,
        halftone = EMPTY_HALFTONE,
    )

    private fun empty() = DocProcess(
        photoZone = UNKNOWN_PATCH,
        textZone = UNKNOWN_PATCH,
        agreement = DocProcess.Agreement.NO_EVIDENCE,
    )

    fun aggregate(
        photoZone: PatchResult?,
        textZone: PatchResult?,
        registry: ThresholdRegistry,
    ): DocProcess {
        if (photoZone == null && textZone == null) {
            return empty()
        }
        if (photoZone == null || textZone == null) {
            val present = photoZone ?: textZone!!
            return DocProcess(present, present, DocProcess.Agreement.UNCERTAIN)
        }

        val red = registry[ThresholdName.MACRO_MARGIN_RED].toFloat()
        val amber = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
        val bothConfident = photoZone.margin >= red && textZone.margin >= red
        val atLeastOneConfident = photoZone.margin >= amber || textZone.margin >= amber

        val agreement = when {
            photoZone.label == textZone.label && bothConfident -> DocProcess.Agreement.MATCH
            photoZone.label != textZone.label && atLeastOneConfident -> DocProcess.Agreement.MISMATCH
            else -> DocProcess.Agreement.UNCERTAIN
        }
        return DocProcess(photoZone, textZone, agreement)
    }

}

/** Sum of absolute values of a score map; small helper kept for report readability. */
internal fun Map<ProcessLabel, Float>.totalAbs(): Float = values.sumOf { kotlin.math.abs(it.toDouble()) }.toFloat()
