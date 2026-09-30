package dev.kasoti.eval.output

import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.RunRecord
import dev.kasoti.evalmetrics.MetricJson
import dev.kasoti.evalmetrics.MetricSink

/**
 * The bridge between the harness's run model and `dev.kasoti.evalmetrics.MetricSink`.
 *
 * The sink in `:core` is the numbers writer: it owns the counter/measure/bucket split, the
 * histogram bins and the `metrics.json` shape, so a counter can never be added to a rate and
 * a caveat travels with the numbers it qualifies. This object does not duplicate any of that
 * — it projects the harness's [RunRecord] onto the sink and reads the sink's snapshot back
 * for embedding in the output.
 *
 * What `:core`'s sink does not model, and this layer therefore owns, is the run *governance*:
 * split discipline (EVAL.md §8), calibration (EVAL.md §7), skipped-versus-passed suites, and
 * the EVAL.md §6 report template. Those are properties of a run, not of a measurement, so
 * they are serialised as a sibling `governance` object rather than inside the sink's JSON.
 *
 * Both halves end up in `metrics.json` — under `evalmetrics` and `governance` — and
 * `summary.md` is rendered from the same two. One file, two concerns, no third copy of any
 * number.
 */
class EvalMetricsBridge(
    runId: String,
    suite: String,
    commit: String,
    devices: List<String>,
    split: String,
) {

    val sink: MetricSink = MetricSink(
        runId = runId,
        suite = suite,
        commit = commit,
        devices = devices,
        split = split,
    )

    /**
     * Project a finished run onto the sink.
     *
     * Counters go to counters and everything else to measures, so the JSON keeps the two
     * apart. Notes are the harness's caveats verbatim, which is how "this number is
     * synthetic" reaches the artefact rather than only the console.
     */
    fun absorb(record: RunRecord): EvalMetricsBridge {
        for (metric in record.metrics) {
            val value = metric.value ?: continue
            if (metric.unit in COUNT_UNITS) sink.count(metric.name, value.toLong()) else sink.measure(metric.name, value)
        }
        for (table in record.tables) {
            table.rowLabels.forEachIndexed { row, label ->
                table.cells.getOrNull(row)?.forEachIndexed { column, value ->
                    val columnLabel = table.columnLabels.getOrElse(column) { column.toString() }
                    sink.bucket(table.name, "$label/$columnLabel", value)
                }
            }
        }
        for (histogram in record.histograms) replay(histogram)
        for (note in record.notes) sink.note(note)
        for (note in record.suites.mapNotNull { it.skipReason }) sink.note("suite skipped: $note")
        for (failure in record.failures.take(MAX_FAILURE_NOTES)) {
            sink.note("failure ${failure.caseId}: ${failure.reason}")
        }
        return this
    }

    /**
     * The sink's histogram takes observations, not counts, and its edges are lower bounds
     * with an explicit overflow bin. A harness histogram is already binned, so the mapping is
     * a replay: each bin's midpoint is observed once per count, and the total overflow bin
     * absorbs the top bin. The counts therefore come out identical, and the same source data
     * is readable in both shapes.
     */
    private fun replay(histogram: Histogram) {
        val bins = histogram.bins
        if (bins.isEmpty()) return
        val edges = buildList {
            add(bins.first().lowerInclusive)
            bins.dropLast(1).forEach { add(it.upperExclusive) }
        }
        if (edges.isEmpty()) return
        val target = sink.histogram(histogram.name, edges)
        for (bin in bins) {
            val midpoint = (bin.lowerInclusive + bin.upperExclusive) / 2.0
            repeat(bin.count) { target.observe(midpoint) }
        }
    }

    /** The `evalmetrics` half of `metrics.json`, produced by `:core`'s own writer. */
    fun toJson(): MetricJson.Obj = sink.toJson()

    /** The `evalmetrics` half of `summary.md`. */
    fun toMarkdown(): String = sink.summaryMarkdown()

    private companion object {
        /**
         * Units whose metrics are exact tallies. A "rate" metric never carries one of these
         * units, and a "row" metric is never a rate — the split is by unit so it cannot be
         * got wrong by a value that happens to be integral.
         */
        val COUNT_UNITS = setOf("rows", "cases", "patches", "pairs", "scripts", "fields", "runs", "points", "buckets", "entries")

        const val MAX_FAILURE_NOTES = 50
    }
}
