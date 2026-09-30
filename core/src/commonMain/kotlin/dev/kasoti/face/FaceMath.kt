package dev.kasoti.face

import dev.kasoti.fusion.FindingCode
import kotlin.math.sqrt

/**
 * A face embedding produced by the platform model (DESIGN.md §5).
 *
 * Deliberately opaque: `:core` does the maths, the model runs in `:platform`, and the two
 * agree only because the *same bytes* are loaded on both. The model identity travels with
 * the data so a diary written by one generation can never be silently compared against
 * another (invariant I1).
 */
class Embedding(val values: FloatArray, val modelTag: String) {
    val dimension: Int get() = values.size

    companion object {
        const val DIM = 128

        fun of(values: FloatArray, modelTag: String): Embedding {
            require(values.size == DIM) { "expected $DIM dimensions, got ${values.size}" }
            return Embedding(values, modelTag)
        }
    }
}

/** Cosine similarity in `[-1, 1]`. */
object FaceMath {

    /**
     * @return cosine similarity, or `0f` for a zero-length input. Both inputs are expected
     *   L2-normalised already; this does not normalise again because that would hide a
     *   normalisation bug in the pipeline rather than surface it.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    /** In-place-safe L2 normalisation; a zero vector is returned unchanged. */
    fun l2Normalize(values: FloatArray): FloatArray {
        var acc = 0.0
        for (v in values) acc += v.toDouble() * v
        val norm = sqrt(acc)
        if (norm <= 0.0) return values.copyOf()
        val out = FloatArray(values.size)
        for (i in values.indices) out[i] = (values[i] / norm).toFloat()
        return out
    }
}

/** Raw measurements a capture produces, before any gating decision. */
data class FaceSample(
    /** Variance of the Laplacian; the standard cheap focus measure. */
    val blurVariance: Float,
    /** Fraction of pixels above the glare threshold. */
    val glareRatio: Float,
    val brightness: Float,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val occluded: Boolean = false,
)

/**
 * Result of the pre-match quality gate (DESIGN.md §5, FUSION.md §4).
 *
 * Gating happens *before* matching, not after. A blurry or badly-posed capture produces a
 * similarity number that means nothing; running the match anyway and then explaining away a
 * low score is how a system ends up accusing someone of wearing a disguise when the real
 * problem was the sun (FUSION.md A-GREY3 exists precisely to stop that pattern).
 */
data class GateResult(
    val passed: Boolean,
    val causes: List<FindingCode> = emptyList(),
    /** 0..1 combined quality used to temper the match threshold. */
    val score: Float,
) {
    companion object {
        val PASS = GateResult(passed = true, score = 1f)
    }
}

object QualityGate {

    /**
     * @param thresholds blur/glare/brightness/pose values from the registry.
     */
    fun evaluate(
        sample: FaceSample,
        blurMin: Float,
        glareMax: Float,
        brightnessMin: Float,
        brightnessMax: Float,
        yawMax: Float,
        pitchMax: Float,
    ): GateResult {
        val causes = mutableListOf<FindingCode>()

        if (sample.blurVariance < blurMin) causes += FindingCode.G_BLUR
        if (sample.glareRatio > glareMax) causes += FindingCode.G_GLARE
        if (sample.brightness < brightnessMin) causes += FindingCode.G_DARK
        if (sample.brightness > brightnessMax) causes += FindingCode.G_GLARE
        if (kotlin.math.abs(sample.yawDegrees) > yawMax) causes += FindingCode.G_POSE
        if (kotlin.math.abs(sample.pitchDegrees) > pitchMax) causes += FindingCode.G_POSE
        if (sample.occluded) causes += FindingCode.G_OCCLUDE

        if (causes.isEmpty()) return GateResult.PASS

        // The score is the worst offender, not the average: a perfectly-lit but heavily
        // occluded face is not "70% good enough" to compare.
        val worst = minOf(
            (sample.blurVariance / blurMin).coerceAtMost(1f),
            1f - (sample.glareRatio / glareMax).coerceIn(0f, 1f),
            (sample.brightness / brightnessMin).coerceAtMost(1f),
            (1f - kotlin.math.abs(sample.yawDegrees) / yawMax).coerceAtLeast(0f),
            (1f - kotlin.math.abs(sample.pitchDegrees) / pitchMax).coerceAtLeast(0f),
        )
        return GateResult(passed = false, causes = causes, score = worst.coerceIn(0f, 1f))
    }
}

/**
 * Quality-adjusted similarity (FR-H1).
 *
 * A marginally-blurry capture of the right person and a sharp capture of the wrong person
 * can produce the same raw similarity. Tempering the score by the worse of the two capture
 * qualities means a poor capture is biased towards *inconclusive* (AMBER) rather than
 * towards a confident RED — the failure mode that would make the system feel like it
 * accuses people for the sake of bad lighting.
 */
fun fuseMatchScore(
    similarity: Float,
    docQuality: Float,
    liveQuality: Float,
): Float {
    val quality = minOf(docQuality.coerceIn(0f, 1f), liveQuality.coerceIn(0f, 1f))
    // At full quality the score is unchanged; it degrades to 60% of raw at zero quality,
    // which is enough to pull marginal cases into the AMBER band without hiding strong ones.
    val temper = 0.6f + 0.4f * quality
    return (similarity * temper).coerceIn(-1f, 1f)
}

/** One labelled pair for FAR/FRR estimation (EVAL.md §4). */
data class PairTrial(
    val label: PairLabel,
    val similarity: Float,
    /** Stratum for per-bucket reporting: gender × age band × lighting. */
    val bucket: String = "default",
)

enum class PairLabel { GENUINE, IMPOSTOR }

/** A ROC-style curve plus the operating points EVAL.md requires. */
data class DetectionCurve(
    val points: List<Point>,
    val farAtThreshold: List<Float>,
    val frrAtThreshold: List<Float>,
) {
    data class Point(val threshold: Float, val far: Float, val frr: Float, val tar: Float)

    val thresholds: List<Float> get() = points.map { it.threshold }

    /** TAR at the requested FAR operating points (EVAL.md §4 uses 1% and 0.1%). */
    fun tarAt(farTarget: Float): Float {
        val feasible = points.filter { it.far <= farTarget }
        return feasible.maxByOrNull { it.tar }?.tar ?: 0f
    }

    /** The threshold we would deploy at the requested FAR. */
    fun thresholdAt(farTarget: Float): Float =
        points.filter { it.far <= farTarget }.maxByOrNull { it.tar }?.threshold ?: 1f
}

object Detection {

    /**
     * Build the ROC from labelled trials.
     *
     * Thresholds are the observed similarities, which is the correct empirical choice here:
     * inventing a regular grid would let the curve claim a resolution the sample size cannot
     * support, and with 400-odd pairs that would overstate the operating point.
     */
    fun roc(trials: List<PairTrial>): DetectionCurve {
        if (trials.isEmpty()) return DetectionCurve(emptyList(), emptyList(), emptyList())
        val impostors = trials.filter { it.label == PairLabel.IMPOSTOR }.map { it.similarity }
        val genuines = trials.filter { it.label == PairLabel.GENUINE }.map { it.similarity }
        if (impostors.isEmpty() || genuines.isEmpty()) {
            return DetectionCurve(emptyList(), emptyList(), emptyList())
        }

        val sorted = (impostors + genuines).distinct().sortedDescending()
        val points = sorted.map { t ->
            val far = impostors.count { it >= t }.toFloat() / impostors.size
            val tar = genuines.count { it >= t }.toFloat() / genuines.size
            DetectionCurve.Point(threshold = t, far = far, frr = 1f - tar, tar = tar)
        }
        return DetectionCurve(
            points = points,
            farAtThreshold = points.map { it.far },
            frrAtThreshold = points.map { it.frr },
        )
    }

    /** Per-bucket TAR at a fixed threshold; empty buckets are omitted, never zero-filled. */
    fun perBucket(trials: List<PairTrial>, threshold: Float): Map<String, Pair<Float, Float>> =
        trials.groupBy { it.bucket }.mapValues { (_, group) ->
            val tar = group.count { it.label == PairLabel.GENUINE && it.similarity >= threshold }.toFloat() /
                group.count { it.label == PairLabel.GENUINE }.coerceAtLeast(1)
            val far = group.count { it.label == PairLabel.IMPOSTOR && it.similarity >= threshold }.toFloat() /
                group.count { it.label == PairLabel.IMPOSTOR }.coerceAtLeast(1)
            tar to far
        }
}
