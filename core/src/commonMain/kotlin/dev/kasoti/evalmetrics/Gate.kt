package dev.kasoti.evalmetrics

import dev.kasoti.fusion.NumberFormat

/**
 * One SPEC.md §7 ship-blocker, with the number that decides it.
 *
 * [measured] is a rendered string rather than a number because the gates are not all the same
 * kind of quantity — a rate, a count, a median in seconds — and a table that tried to type
 * them uniformly would either round away the decision or pretend a median is a rate. The
 * [pass] flag is what code and CI read; the string is what a reviewer reads.
 */
data class Gate(
    val name: String,
    val bar: String,
    val measured: String,
    val pass: Boolean,
    val source: String? = null,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "name" to MetricJson.Str(name),
        "bar" to MetricJson.Str(bar),
        "measured" to MetricJson.Str(measured),
        "pass" to MetricJson.Bool(pass),
        "source" to (source?.let { MetricJson.Str(it) } ?: MetricJson.Null),
    )
}

/** The §7 table, rendered straight from harness output so the two cannot disagree. */
data class GateTable(val gates: List<Gate>) {
    val failing: List<Gate> get() = gates.filterNot { it.pass }

    val allPassed: Boolean get() = failing.isEmpty()

    fun toJson(): MetricJson.Obj = jsonOf(
        "allPassed" to MetricJson.Bool(allPassed),
        "gates" to MetricJson.Arr(gates.map { it.toJson() }),
    )

    fun toMarkdown(): String = buildString {
        appendLine("| Gate | Bar | Measured | Result |")
        appendLine("|---|---|---|---|")
        gates.forEach { gate ->
            appendLine("| ${gate.name} | ${gate.bar} | ${gate.measured} | ${if (gate.pass) "PASS" else "FAIL"} |")
        }
    }
}

/**
 * The SPEC.md §7 gates, built from harness measurements.
 *
 * Each factory states the bar it enforced so the table is self-describing, and none of them
 * invents a threshold: the bars that are policy live in [dev.kasoti.threshold.ThresholdRegistry]
 * and arrive as arguments, and the bars that are SPEC numbers are named in the KDoc. What no
 * gate does is pass on a missing measurement — an unmeasurable gate fails and says so.
 */
object SpecGates {

    /** SPEC.md §7: "MRZ mutate-catch (10k corpus) | 100%". */
    fun mrzMutateCatch(report: MutateCatchReport): Gate = Gate(
        name = "MRZ mutate-catch",
        bar = "100% of detectable mutants",
        measured = if (report.publishable) {
            "${NumberFormat.percent(report.catchRate, 3)} (${report.caught}/${report.detectable})"
        } else {
            "not measured: no detectable mutants in the corpus"
        },
        pass = report.publishable && report.catchRate >= 1.0 && report.precise,
        source = "EVAL.md §2 D-MRZ",
    )

    /**
     * SPEC.md §7: "Macro offset-vs-inkjet | publish; target >=90%, floor for RED-use >=85%
     * else AMBER-only".
     *
     * @param recallFloor the 0.85 RED-use floor, supplied by the caller because it is a SPEC
     *   number rather than a registry threshold.
     */
    fun macroRedUseFloor(metrics: ClassificationMetrics, label: String, recallFloor: Double): Gate = Gate(
        name = "Macro $label recall for RED-use",
        bar = ">= ${NumberFormat.percent(recallFloor, 0)}",
        measured = "${NumberFormat.percent(metrics.recallOf(label), 2)} " +
            "(min over classes ${NumberFormat.percent(metrics.minRecall, 2)})",
        pass = metrics.meetsRecallFloor(recallFloor),
        source = "SPEC.md §7",
    )

    /** SPEC.md §7: "Face TAR@FAR | publish operating point; floor FRR <=5% @ FAR 0.1%". */
    fun faceFrrFloor(evaluation: FaceEvaluation, maxFrR: Double): Gate {
        val point = evaluation.operatingPoint
        val measured = "TAR ${NumberFormat.percent(point.tar, 2)} @ FAR " +
            "${NumberFormat.percent(point.far, 3)} (target ${NumberFormat.percent(point.farTarget, 3)}), " +
            "FRR ${NumberFormat.percent(point.frr, 2)}, threshold ${NumberFormat.fixed(point.threshold.toDouble(), 4)}"
        return Gate(
            name = "Face FRR at the operating point",
            bar = "FRR <= ${NumberFormat.percent(maxFrR, 0)} at FAR <= 0.1%",
            measured = measured,
            pass = evaluation.impostorTrials > 0 && evaluation.genuineTrials > 0 &&
                point.far <= point.farTarget && point.frr <= maxFrR,
            source = "EVAL.md §4",
        )
    }

    /** SPEC.md §7: "Wall-clock medians | publish per device; stranger <=15 s hard ceiling". */
    fun wallClockMedian(device: String, distribution: Distribution?, ceilingSeconds: Double): Gate = Gate(
        name = "Wall-clock stranger check on $device",
        bar = "median <= ${NumberFormat.fixed(ceilingSeconds, 1)} s",
        measured = distribution?.let { "median ${NumberFormat.fixed(it.median, 2)} s (p95 ${NumberFormat.fixed(it.p95, 2)} s, n=${it.count})" }
            ?: "not measured",
        pass = distribution != null && distribution.median <= ceilingSeconds,
        source = "EVAL.md §4",
    )

    /** SPEC.md §7: "Crash-free | 50/50 E2E per platform". */
    fun crashFree(runs: Int, crashFree: Int, required: Int = 50): Gate = Gate(
        name = "Crash-free end-to-end runs",
        bar = "$required/$required",
        measured = "$crashFree/$runs",
        pass = runs >= required && crashFree == runs,
    )

    /**
     * FUSION.md §6: a worst bucket more than twice the best must be called out, so it is a
     * gate-shaped finding even though it is not in the §7 table.
     */
    fun bucketGap(evaluation: FaceEvaluation): Gate = Gate(
        name = "Face per-bucket FRR spread",
        bar = "worst <= 2x best",
        measured = buildString {
            val worst = evaluation.worstBucketFrR
            val best = evaluation.bestBucketFrR
            if (worst == null || best == null) {
                append("not measured: no per-bucket breakdown")
            } else {
                append("worst ${NumberFormat.percent(worst, 2)}, best ${NumberFormat.percent(best, 2)}")
            }
        },
        pass = !evaluation.bucketGapExceedsPolicy,
        source = "FUSION.md §6",
    )

    /** The mutate-catch blind-spot count, published so the 100% gate cannot hide behind it. */
    fun blindSpots(report: MutateCatchReport): Gate = Gate(
        name = "MRZ structurally blind cases",
        bar = "publish the count and the reasons",
        measured = "${report.blindCount} of ${report.total} rows " +
            "(${NumberFormat.percent(report.blindRate, 2)}): " +
            (report.blindReasons.entries.joinToString("; ") { "${it.value} x ${it.key}" }.ifEmpty { "none" }),
        pass = report.blindCases.all { it.reason.isNotBlank() },
        source = "EVAL.md §4",
    )
}
