package dev.kasoti.eval.suites

import dev.kasoti.factory.GrayImage
import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessLabel
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.factory.SvmModel

/**
 * The Kotlin half of the NumPy↔Kotlin feature cross-check.
 *
 * `eval/tools/extract_features.py` reimplements this pipeline in NumPy so the training
 * pipeline can compute features without a JVM. Duplicated maths is only tolerable if the two
 * copies are *shown* to agree, so this object emits the Kotlin vectors for the same
 * synthetic recipes `eval/tools/patches.py` generates, and `cross_check.py` compares the two
 * with a stated tolerance.
 *
 * The recipes are generated, not read from disk (DATA.md §1: no patch-shaped media in the
 * repository), which is what lets the two implementations be handed byte-identical input
 * without shipping an image.
 *
 * The one genuine divergence is the FFT: `:core` accumulates its twiddle recurrence in
 * single precision and NumPy's transform is double precision internally, so the spectral
 * features agree to a relative tolerance rather than exactly. LBP, Sobel and the standard
 * deviation are integer comparisons and a single division, and those agree exactly. Both
 * facts are encoded in the tolerances below rather than left to a comment.
 */
object CrosscheckVectors {

    /** Absolute tolerance for the exactly-reproducible features. */
    const val EXACT_TOLERANCE = 0.0

    /**
     * Relative tolerance for the FFT-derived features.
     *
     * Single- versus double-precision accumulation over a 256-point transform gives roughly
     * 1e-7 per op and ~1e-6 relative end to end; 1e-5 leaves an order of magnitude of
     * headroom for a different BLAS without letting a genuine reimplementation difference
     * through. If a check ever fails here, the printout says how far off it was.
     */
    const val FFT_RELATIVE_TOLERANCE = 1e-5

    data class Vector(
        val id: String,
        val label: String,
        val recipe: String,
        val seed: Long,
        val size: Int,
        val featureNames: List<String>,
        val features: DoubleArray,
    ) {
        /** One JSON object per line, matching `extract_features.py`'s output exactly. */
        fun toJsonLine(): String = buildString {
            append("{\"id\":\"").append(id).append("\",\"label\":\"").append(label)
            append("\",\"recipe\":\"").append(recipe)
            append("\",\"seed\":").append(seed)
            append(",\"size\":").append(size)
            append(",\"featureNames\":[")
            append(featureNames.joinToString(",") { "\"$it\"" })
            append("],\"features\":[")
            append(features.joinToString(",") { "%.10g".format(it) })
            append("]}")
        }

        override fun equals(other: Any?): Boolean =
            other is Vector && id == other.id && features.contentEquals(other.features)

        override fun hashCode(): Int = 31 * id.hashCode() + features.contentHashCode()
    }

    /**
     * The feature *definitions* are `:core`'s. The classifier is only a vehicle for
     * [dev.kasoti.factory.ProcessClassifier.extract], which is a pure function of the image,
     * so an untrained model is used deliberately: it lets the extraction path run without
     * shipping weights, and the weights play no part in the vector.
     */
    private val extractor = ProcessClassifier(
        SvmModel(
            version = "crosscheck-extractor-only",
            labels = ProcessLabel.entries,
            weights = Array(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
            bias = FloatArray(ProcessLabel.entries.size),
            featureNames = featureNames(),
            trainingRunId = "crosscheck",
        ),
    )

    /**
     * The feature names in `MacroFeatures.toVector()` order.
     *
     * These are the names `train_svm.py` writes into the model file. They are stated here
     * rather than derived from the Kotlin data class because the class does not carry them,
     * and a model whose `featureNames` do not match the vector order is a model that has
     * silently learned a permutation.
     */
    fun featureNames(): List<String> = listOf(
        "peak_frequency", "peakiness", "band_energy", "high_frequency_energy",
        "spectral_slope", "std_dev", "edge_density",
    ) + (0 until MacroFeatures.LBP_BINS).map { "lbp_%02d".format(it) }

    /** Every recipe, in `ProcessLabel` declaration order. */
    fun vectors(): List<Vector> = ProcessLabel.entries.map { label ->
        val image: GrayImage = SpectrumPatch.forLabel(label)
        val vector = extractor.extract(image).toVector()
        Vector(
            id = "syn-${label.name.lowercase()}",
            label = label.name,
            recipe = label.name,
            seed = SpectrumPatch.SEED,
            size = SpectrumPatch.SIZE,
            featureNames = featureNames(),
            features = DoubleArray(vector.size) { vector[it].toDouble() },
        )
    }

    /** The same vectors, one per line, for `cross_check.py`. */
    fun jsonLines(): String = vectors().joinToString("\n") { it.toJsonLine() } + "\n"

    /**
     * A self-check the harness runs on every smoke suite.
     *
     * Two invariants that are cheap and that a reimplementation bug would break first: every
     * feature is finite, and the LBP histogram sums to 1. A NaN reaching the model file would
     * be a weight multiplied by nothing, and it is far cheaper to catch it here.
     */
    fun selfCheck(): List<String> {
        val problems = mutableListOf<String>()
        for (vector in vectors()) {
            vector.features.forEachIndexed { index, value ->
                if (!value.isFinite()) {
                    problems += "${vector.id}.${vector.featureNames[index]} is $value"
                }
            }
            val lbpSum = vector.features.drop(MacroFeatures.NUMERIC_FEATURES).sum()
            if (kotlin.math.abs(lbpSum - 1.0) > 1e-5) {
                problems += "${vector.id} LBP histogram sums to $lbpSum, expected 1.0"
            }
        }
        return problems
    }

    /**
     * The reference measurements, exposed so a reader can see what the pipeline produced
     * without running the Python side. Deliberately not asserted here — the assertion is
     * `cross_check.py`'s job, and duplicating it would make one of the two quietly
     * authoritative.
     */
    fun summaryLines(): List<String> = vectors().map { vector ->
        "%-8s peak=%6.1f peakiness=%9.3f band=%.4f high=%.4f slope=%+.3f edge=%.4f lbpΣ=%.6f".format(
            vector.label,
            vector.features[0] * SpectrumPatch.SIZE,
            vector.features[1],
            vector.features[2],
            vector.features[3],
            vector.features[4],
            vector.features[6],
            vector.features.drop(7).sum(),
        )
    }
}
