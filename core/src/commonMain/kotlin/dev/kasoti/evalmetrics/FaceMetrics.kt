package dev.kasoti.evalmetrics

import dev.kasoti.face.Detection
import dev.kasoti.face.DetectionCurve
import dev.kasoti.face.PairLabel
import dev.kasoti.face.PairTrial

/**
 * One point on the TAR/FAR curve, at a requested FAR budget.
 *
 * The threshold is the *deployment* threshold, so a report that publishes TAR without it
 * cannot be acted on: an operating point is a threshold, not a rate.
 */
data class OperatingPoint(
    val farTarget: Double,
    val threshold: Float,
    val tar: Double,
    val far: Double,
    val frr: Double,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "farTarget" to MetricJson.Num(farTarget),
        "threshold" to MetricJson.Num(threshold.toDouble()),
        "tar" to MetricJson.Num(tar),
        "far" to MetricJson.Num(far),
        "frr" to MetricJson.Num(frr),
    )
}

/** TAR/FRR/FAR for one stratum — EVAL.md §4 requires gender x age-band x lighting. */
data class BucketTally(
    val bucket: String,
    val threshold: Float,
    val tar: Double,
    val far: Double,
    val frr: Double,
    val genuine: Int,
    val impostor: Int,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "bucket" to MetricJson.Str(bucket),
        "threshold" to MetricJson.Num(threshold.toDouble()),
        "tar" to MetricJson.Num(tar),
        "far" to MetricJson.Num(far),
        "frr" to MetricJson.Num(frr),
        "genuine" to MetricJson.Num(genuine.toDouble()),
        "impostor" to MetricJson.Num(impostor.toDouble()),
    )
}

data class FaceEvaluation(
    val sweep: List<OperatingPoint>,
    /** The strictest budget in [sweep]; this is the point a deployment would run at. */
    val operatingPoint: OperatingPoint,
    val perBucket: List<BucketTally>,
    val genuineTrials: Int,
    val impostorTrials: Int,
) {
    val frrAtOperatingPoint: Double get() = operatingPoint.frr

    /** Highest FRR in any bucket: the one that must be read aloud at review. */
    val worstBucketFrR: Double? get() = perBucket.maxOfOrNull { it.frr }

    /** Lowest FRR in any bucket: the baseline the worst is compared against. */
    val bestBucketFrR: Double? get() = perBucket.minOfOrNull { it.frr }

    /**
     * FUSION.md §6: a worst bucket worse than twice the best has to be read aloud at review
     * and either fixed with an AMBER-bias rule or documented. Reporting the ratio is the
     * minimum; this flag is what stops it being quietly filed.
     */
    val bucketGapRatio: Double?
        get() {
            val worst = worstBucketFrR ?: return null
            val best = bestBucketFrR ?: return null
            return if (best > 0.0) worst / best else if (worst > 0.0) Double.POSITIVE_INFINITY else 1.0
        }

    val bucketGapExceedsPolicy: Boolean get() = bucketGapRatio?.let { it > 2.0 } ?: false

    fun toJson(): MetricJson.Obj = jsonOf(
        "genuineTrials" to MetricJson.Num(genuineTrials.toDouble()),
        "impostorTrials" to MetricJson.Num(impostorTrials.toDouble()),
        "sweep" to MetricJson.Arr(sweep.map { it.toJson() }),
        "operatingPoint" to operatingPoint.toJson(),
        "frrAtOperatingPoint" to MetricJson.Num(frrAtOperatingPoint),
        "perBucket" to MetricJson.Arr(perBucket.map { it.toJson() }),
        "worstBucketFrR" to MetricJson.Num(worstBucketFrR ?: 0.0),
        "bestBucketFrR" to MetricJson.Num(bestBucketFrR ?: 0.0),
        "bucketGapExceedsPolicy" to MetricJson.Bool(bucketGapExceedsPolicy),
    )
}

/**
 * TAR@FAR sweep and operating-point selection (EVAL.md §4, FUSION.md §5).
 *
 * The curve itself comes from [Detection.roc] rather than a second implementation: the
 * thresholds are the observed similarities, which is the only resolution 400-odd pairs can
 * honestly support, and re-deriving the curve here would risk two different answers to the
 * same question.
 */
object FaceMetrics {

    /** EVAL.md §4 names 1% and 0.1%; the operating point is taken at the strictest. */
    val DEFAULT_FAR_TARGETS = listOf(0.01, 0.001)

    fun evaluate(
        trials: List<PairTrial>,
        farTargets: List<Double> = DEFAULT_FAR_TARGETS,
    ): FaceEvaluation {
        require(farTargets.isNotEmpty() && farTargets.all { it in 0.0..1.0 }) { "FAR budgets must be rates" }
        val curve = Detection.roc(trials)
        val sweep = farTargets.sortedDescending().map { target ->
            val threshold = curve.thresholdAt(target.toFloat())
            val tar = curve.tarAt(target.toFloat())
            OperatingPoint(
                farTarget = target,
                threshold = threshold,
                tar = tar.toDouble(),
                far = farAt(curve, threshold),
                frr = (1.0 - tar).toDouble(),
            )
        }
        val operating = sweep.minByOrNull { it.farTarget } ?: sweep.first()
        return FaceEvaluation(
            sweep = sweep,
            operatingPoint = operating,
            perBucket = perBucket(trials, operating.threshold),
            genuineTrials = trials.count { it.label == PairLabel.GENUINE },
            impostorTrials = trials.count { it.label == PairLabel.IMPOSTOR },
        )
    }

    /**
     * FAR at a curve threshold.
     *
     * [DetectionCurve] keeps FAR as a parallel list rather than a lookup, so the point is
     * matched on its own threshold. The thresholds are the observed similarities, so the
     * exact point always exists; a miss means the curve was empty.
     */
    private fun farAt(curve: DetectionCurve, threshold: Float): Double =
        curve.points.firstOrNull { it.threshold == threshold }?.far?.toDouble() ?: 0.0

    private fun perBucket(trials: List<PairTrial>, threshold: Float): List<BucketTally> =
        trials.groupBy { it.bucket }.map { (bucket, group) ->
            val genuine = group.count { it.label == PairLabel.GENUINE }
            val impostor = group.count { it.label == PairLabel.IMPOSTOR }
            val rates = Detection.perBucket(group, threshold)[bucket] ?: (0f to 0f)
            BucketTally(
                bucket = bucket,
                threshold = threshold,
                tar = rates.first.toDouble(),
                far = rates.second.toDouble(),
                frr = (1.0 - rates.first).toDouble(),
                genuine = genuine,
                impostor = impostor,
            )
        }.sortedBy { it.bucket }
}
