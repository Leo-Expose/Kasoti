package dev.kasoti.evalmetrics

import dev.kasoti.fusion.NumberFormat

/**
 * Order statistics for the latency numbers EVAL.md §4 insists on.
 *
 * EVAL.md requires *medians* and *p95 on named devices*, not means, because a mean over a
 * 1 s and a 14 s outlier is neither: a single GC pause or one cold start on a low-end post
 * device moves the mean by seconds and the p95 not at all. The tail is what a border
 * operator feels.
 *
 * [percentile] uses linear interpolation between order statistics, which is the same
 * convention `numpy.percentile` defaults to. It is stated here because the alternative
 * conventions disagree by a whole sample at small `n`, and a latency table that changes shape
 * when someone re-reads it with a different library is not a baseline.
 */
object Percentiles {

    /** @param q 0..1, inclusive. */
    fun percentile(samples: List<Double>, q: Double): Double {
        require(q in 0.0..1.0) { "quantile out of range: $q" }
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = q * (sorted.size - 1)
        val lower = position.toInt().coerceIn(0, sorted.size - 1)
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fun median(samples: List<Double>): Double = percentile(samples, 0.5)

    fun p95(samples: List<Double>): Double = percentile(samples, 0.95)

    /**
     * @return a one-line summary, or `null` when there are no samples — an absent measurement
     *   and a measurement of zero must never look the same in a published table.
     */
    fun summarise(label: String, samples: List<Double>): Distribution? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return Distribution(
            label = label,
            count = samples.size,
            min = sorted.first(),
            median = median(sorted),
            p95 = p95(sorted),
            max = sorted.last(),
            mean = samples.average(),
        )
    }
}

/**
 * Per-stage latency on one named device.
 *
 * [buildFingerprint] is part of the record, not decoration: a median from a different build
 * is a different measurement, and EVAL.md §4 makes the device and build nameable for exactly
 * that reason.
 */
data class Distribution(
    val label: String,
    val count: Int,
    val min: Double,
    val median: Double,
    val p95: Double,
    val max: Double,
    val mean: Double,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "count" to MetricJson.Num(count.toDouble()),
        "min_s" to MetricJson.Num(min),
        "median_s" to MetricJson.Num(median),
        "p95_s" to MetricJson.Num(p95),
        "max_s" to MetricJson.Num(max),
        "mean_s" to MetricJson.Num(mean),
    )

    fun toMarkdown(): String = "| $label | $count | ${NumberFormat.fixed(min, 3)} | " +
        "${NumberFormat.fixed(median, 3)} | ${NumberFormat.fixed(p95, 3)} | ${NumberFormat.fixed(max, 3)} |"
}

data class StageLatency(
    val device: String,
    val buildFingerprint: String,
    val stage: String,
    val distribution: Distribution,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "device" to MetricJson.Str(device),
        "build" to MetricJson.Str(buildFingerprint),
        "stage" to MetricJson.Str(stage),
        "seconds" to distribution.toJson(),
    )
}
