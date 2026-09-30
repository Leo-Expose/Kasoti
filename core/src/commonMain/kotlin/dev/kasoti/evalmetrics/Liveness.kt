package dev.kasoti.evalmetrics

/**
 * Liveness error rates (EVAL.md §4: APCER and BPCER on D-SPOOF).
 *
 * Both are stated as *error* rates over their own population, because they trade against
 * each other and a single "liveness accuracy" hides that: lowering the acceptance threshold
 * improves APCER and destroys BPCER. Reporting the pair is the only way a reviewer can see
 * where the operating point sits.
 *
 * A rate with no population behind it is reported as 0.0 *and* flagged [publishable] false.
 * That combination is deliberate: JSON has no "undefined", and a bare 0.0 next to a gate
 * would read as a pass.
 */
data class LivenessMetrics(
    val threshold: Double,
    /** Attack presentations accepted as bona fide, over all attack presentations. */
    val apcer: Double,
    /** Bona fide presentations rejected as attacks, over all bona fide presentations. */
    val bpcer: Double,
    val attacks: Int,
    val bonaFide: Int,
) {
    val publishable: Boolean get() = attacks > 0 && bonaFide > 0

    fun toJson(): MetricJson.Obj = jsonOf(
        "threshold" to MetricJson.Num(threshold),
        "apcer" to MetricJson.Num(apcer),
        "bpcer" to MetricJson.Num(bpcer),
        "attacks" to MetricJson.Num(attacks.toDouble()),
        "bonaFide" to MetricJson.Num(bonaFide.toDouble()),
        "publishable" to MetricJson.Bool(publishable),
    )
}

object Liveness {

    /**
     * @param attackScores liveness score for each attack presentation; accepted as bona fide
     *   when it is at or above [threshold].
     * @param bonaFideScores the same for genuine presentations.
     */
    fun evaluate(
        attackScores: List<Double>,
        bonaFideScores: List<Double>,
        threshold: Double,
    ): LivenessMetrics {
        require(threshold.isFinite()) { "liveness threshold is not finite" }
        val attacks = attackScores.count { it >= threshold }
        val rejected = bonaFideScores.count { it < threshold }
        return LivenessMetrics(
            threshold = threshold,
            apcer = ratio(attacks, attackScores.size),
            bpcer = ratio(rejected, bonaFideScores.size),
            attacks = attackScores.size,
            bonaFide = bonaFideScores.size,
        )
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
}
