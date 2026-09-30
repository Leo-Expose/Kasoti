package dev.kasoti.evalmetrics

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Verdict

/**
 * One scripted diary scenario and how the engine answered it (EVAL.md §4, D-SCEN).
 *
 * The expected column is the script's *intent*, and a script may also pin the codes so a
 * scenario cannot pass for the wrong reason: a script that expects AMBER and gets AMBER for
 * an unrelated finding has not tested the rule it was written for.
 */
data class ScenarioOutcome(
    val id: String,
    val expected: Verdict,
    val actual: Verdict,
    val expectedCodes: Set<FindingCode> = emptySet(),
    val codes: Set<FindingCode> = emptySet(),
    val note: String = "",
) {
    val verdictMatched: Boolean get() = expected == actual

    val codesMatched: Boolean get() = expectedCodes.isEmpty() || expectedCodes.all { it in codes }

    val passed: Boolean get() = verdictMatched && codesMatched

    fun toJson(): MetricJson.Obj = jsonOf(
        "id" to MetricJson.Str(id),
        "expected" to MetricJson.Str(expected.name),
        "actual" to MetricJson.Str(actual.name),
        "expectedCodes" to MetricJson.Arr(expectedCodes.sorted().map { MetricJson.Str(it.name) }),
        "codes" to MetricJson.Arr(codes.sorted().map { MetricJson.Str(it.name) }),
        "passed" to MetricJson.Bool(passed),
        "note" to MetricJson.Str(note),
    )
}

data class DiaryRuleMetrics(val outcomes: List<ScenarioOutcome>) {
    val total: Int get() = outcomes.size

    val passed: Int get() = outcomes.count { it.passed }

    val failed: Int get() = total - passed

    val passRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total

    val failures: List<ScenarioOutcome> get() = outcomes.filterNot { it.passed }

    fun toJson(): MetricJson.Obj = jsonOf(
        "total" to MetricJson.Num(total.toDouble()),
        "passed" to MetricJson.Num(passed.toDouble()),
        "failed" to MetricJson.Num(failed.toDouble()),
        "passRate" to MetricJson.Num(passRate),
        "failures" to MetricJson.Arr(failures.map { it.toJson() }),
    )
}

/** One ranked gallery result, as the diary search returned it. */
data class RetrievalHit(val eventId: String, val score: Double)

/**
 * A 1:N probe: who we were looking for, and what the gallery returned in rank order.
 *
 * The search itself belongs to the diary package; scoring belongs here so the same ranking
 * arithmetic is used by the harness and by the tests.
 */
data class RetrievalProbe(
    val id: String,
    val truthEventId: String,
    val hits: List<RetrievalHit>,
)

data class RetrievalOutcome(
    val id: String,
    /** 1-based, or `Int.MAX_VALUE` when the truth is not in the returned page at all. */
    val rankOfTruth: Int,
    val topScore: Double,
    val runnerUpScore: Double,
    val deltaRequired: Double,
) {
    val gap: Double get() = topScore - runnerUpScore

    val rank1: Boolean get() = rankOfTruth == 1

    /**
     * DESIGN.md §5's margin rule, applied to the metric: a rank-1 hit that the runner-up
     * nearly matches is not an identification, so it is counted separately. Reporting only
     * rank-1 would let a gallery of near-duplicates score perfectly.
     */
    val actionable: Boolean get() = rank1 && gap >= deltaRequired

    fun toJson(): MetricJson.Obj = jsonOf(
        "id" to MetricJson.Str(id),
        "rankOfTruth" to MetricJson.Num(rankOfTruth.toDouble()),
        "topScore" to MetricJson.Num(topScore),
        "runnerUpScore" to MetricJson.Num(runnerUpScore),
        "gap" to MetricJson.Num(gap),
        "rank1" to MetricJson.Bool(rank1),
        "actionable" to MetricJson.Bool(actionable),
    )
}

data class RetrievalMetrics(
    val outcomes: List<RetrievalOutcome>,
    val gallerySize: Int,
) {
    val probes: Int get() = outcomes.size

    val rank1Rate: Double get() = rate { it.rank1 }

    val actionableRate: Double get() = rate { it.actionable }

    val meanRank: Double get() = if (probes == 0) 0.0 else outcomes.map { it.rankOfTruth.toDouble() }.average()

    val medianRank: Double get() = Percentiles.median(outcomes.map { it.rankOfTruth.toDouble() })

    private fun rate(predicate: (RetrievalOutcome) -> Boolean): Double =
        if (probes == 0) 0.0 else outcomes.count(predicate).toDouble() / probes

    fun toJson(): MetricJson.Obj = jsonOf(
        "gallerySize" to MetricJson.Num(gallerySize.toDouble()),
        "probes" to MetricJson.Num(probes.toDouble()),
        "rank1Rate" to MetricJson.Num(rank1Rate),
        "actionableRate" to MetricJson.Num(actionableRate),
        "meanRank" to MetricJson.Num(meanRank),
        "medianRank" to MetricJson.Num(medianRank),
    )
}

object DiaryMetrics {

    fun scenarios(outcomes: List<ScenarioOutcome>): DiaryRuleMetrics = DiaryRuleMetrics(outcomes)

    /**
     * Rank-1 retrieval over a synthetic gallery.
     *
     * @param deltaRequired the `delta_margin` the deployment runs at, so "actionable" means
     *   the same thing here as it does in fusion.
     */
    fun retrieval(
        probes: List<RetrievalProbe>,
        gallerySize: Int,
        deltaRequired: Double,
    ): RetrievalMetrics = RetrievalMetrics(
        outcomes = probes.map { probe -> score(probe, deltaRequired) },
        gallerySize = gallerySize,
    )

    private fun score(probe: RetrievalProbe, deltaRequired: Double): RetrievalOutcome {
        val ranked = probe.hits.sortedByDescending { it.score }
        val top = ranked.firstOrNull()?.score ?: 0.0
        val runnerUp = ranked.getOrNull(1)?.score ?: 0.0
        val rank = ranked.indexOfFirst { it.eventId == probe.truthEventId }
        return RetrievalOutcome(
            id = probe.id,
            rankOfTruth = if (rank < 0) Int.MAX_VALUE else rank + 1,
            topScore = top,
            runnerUpScore = runnerUp,
            deltaRequired = deltaRequired,
        )
    }
}
