package dev.kasoti.evalmetrics

import dev.kasoti.fusion.NumberFormat

/**
 * Where a run's numbers go (EVAL.md §1, §3).
 *
 * The sink exists so the two outputs are produced from one data structure and cannot drift:
 * `metrics.json` is the artefact a metric dispute is settled against (AGENTS.md §9, "harness
 * wins over memory") and `summary.md` is what a human pastes into a ticket. A hand-written
 * summary is how the ticket and the JSON start telling different stories.
 *
 * Three kinds of number are kept apart on purpose. [count] is an exact tally, [measure] is a
 * real-valued observation, and [bucket] is a measure indexed by a stratum (EVAL.md §4 requires
 * gender x age-band x lighting breakdowns). Keeping them in separate maps means a rate can
 * never be added to a tally by accident, and the summary can sort them into the right tables.
 */
class MetricSink(
    val runId: String,
    val suite: String,
    val commit: String = "unknown",
    val devices: List<String> = emptyList(),
    val split: String = "unknown",
) {
    private val counters = LinkedHashMap<String, Long>()
    private val measures = LinkedHashMap<String, Double>()
    private val buckets = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    private val histograms = LinkedHashMap<String, Histogram>()
    private val notes = mutableListOf<String>()

    /** Exact tally. Returns `this` so a harness can chain a run's worth of metrics. */
    fun count(name: String, by: Long = 1L): MetricSink = apply {
        counters[name] = (counters[name] ?: 0L) + by
    }

    fun measure(name: String, value: Double): MetricSink = apply {
        require(value.isFinite()) { "metric $name is not finite: $value" }
        measures[name] = value
    }

    fun bucket(metric: String, bucket: String, value: Double): MetricSink = apply {
        require(value.isFinite()) { "bucket $metric/$bucket is not finite: $value" }
        buckets.getOrPut(metric) { LinkedHashMap() }[bucket] = value
    }

    /** @return the open histogram, so samples can be observed as they arrive. */
    fun histogram(name: String, edges: List<Double>): Histogram = histograms.getOrPut(name) {
        Histogram(name, edges)
    }

    /**
     * A caveat that must travel with the numbers.
     *
     * EVAL.md §4 requires the blind-spot counts and EVAL.md §6 requires a known-limits
     * paragraph; notes are how a computed metric says what it could not see. They are rendered
     * in both outputs, so a caveat cannot exist in a PR description but not in the artefact.
     */
    fun note(text: String): MetricSink = apply { notes += text }

    fun snapshot(): Snapshot = Snapshot(
        runId = runId,
        suite = suite,
        commit = commit,
        devices = devices,
        split = split,
        counters = LinkedHashMap(counters),
        measures = LinkedHashMap(measures),
        buckets = buckets.mapValues { (_, inner) -> LinkedHashMap(inner) },
        histograms = histograms.mapValues { (_, histogram) -> histogram.copyOf() },
        notes = notes.toList(),
    )

    fun toJson(): MetricJson.Obj = snapshot().toJson()

    fun metricsJson(): String = MetricJsonWriter.write(toJson()) + "\n"

    fun summaryMarkdown(): String = snapshot().toMarkdown()
}

/** An immutable copy of a sink, so a run's numbers can be summarised without being editable. */
data class Snapshot(
    val runId: String,
    val suite: String,
    val commit: String,
    val devices: List<String>,
    val split: String,
    val counters: Map<String, Long>,
    val measures: Map<String, Double>,
    val buckets: Map<String, Map<String, Double>>,
    val histograms: Map<String, Histogram>,
    val notes: List<String>,
) {
    fun toJson(): MetricJson.Obj = MetricJson.Obj(
        linkedMapOf(
            "runId" to MetricJson.Str(runId),
            "suite" to MetricJson.Str(suite),
            "commit" to MetricJson.Str(commit),
            "split" to MetricJson.Str(split),
            "devices" to MetricJson.Arr(devices.map { MetricJson.Str(it) }),
            "counters" to MetricJson.Obj(counters.mapValues { MetricJson.Num(it.value.toDouble()) }),
            "measures" to MetricJson.Obj(measures.mapValues { MetricJson.Num(it.value) }),
            "buckets" to MetricJson.Obj(
                buckets.mapValues { (_, inner) ->
                    MetricJson.Obj(inner.mapValues { MetricJson.Num(it.value) })
                },
            ),
            "histograms" to MetricJson.Obj(histograms.mapValues { it.value.toJson() }),
            "notes" to MetricJson.Arr(notes.map { MetricJson.Str(it) }),
        ),
    )

    fun toMarkdown(): String = buildString {
        appendLine("# KASOTI eval summary")
        appendLine()
        appendLine("- run-id: `$runId`")
        appendLine("- suite: $suite")
        appendLine("- commit: $commit")
        appendLine("- split: $split")
        appendLine("- devices: ${if (devices.isEmpty()) "none recorded" else devices.joinToString()}")
        appendLine()

        if (counters.isNotEmpty()) {
            appendLine("## Counters")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            counters.forEach { (name, value) -> appendLine("| $name | $value |") }
            appendLine()
        }

        if (measures.isNotEmpty()) {
            appendLine("## Measures")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            measures.forEach { (name, value) -> appendLine("| $name | ${NumberFormat.fixed(value, 6)} |") }
            appendLine()
        }

        if (buckets.isNotEmpty()) {
            appendLine("## Per-bucket")
            appendLine()
            appendLine("| Metric | Bucket | Value |")
            appendLine("|---|---|---|")
            buckets.forEach { (metric, inner) ->
                inner.forEach { (bucket, value) ->
                    appendLine("| $metric | ${cell(bucket)} | ${NumberFormat.fixed(value, 6)} |")
                }
            }
            appendLine()
        }

        histograms.forEach { (name, histogram) ->
            appendLine("## Histogram: $name")
            appendLine()
            appendLine("| Bin | Range | Count |")
            appendLine("|---|---|---|")
            histogram.rows().forEach { appendLine("| ${it.index} | ${it.range} | ${it.count} |") }
            appendLine()
        }

        if (notes.isNotEmpty()) {
            appendLine("## Known limits")
            appendLine()
            notes.forEach { appendLine("- ${cell(it)}") }
            appendLine()
        }
    }

    /** Pipes and line breaks would break the table they are pasted into. */
    private fun cell(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch == '|' -> append("\\|")
                ch == '\n' || ch == '\r' -> append(' ')
                ch < ' ' -> append(' ')
                else -> append(ch)
            }
        }
    }
}

/**
 * Fixed-bin counts.
 *
 * Edges are lower bounds, so `edges = [0.1, 0.3]` gives three bins: `<0.1`, `0.1..0.3`, `>=0.3`.
 * The overflow bin is real and reported rather than discarded — a pile of samples past the
 * last edge is a finding about the operating point, and silently dropping it is how a
 * threshold gets tuned on a truncated distribution.
 */
class Histogram(
    val name: String,
    val edges: List<Double>,
    private val counts: LongArray = LongArray(edges.size + 1),
) {

    init {
        require(edges.isNotEmpty()) { "a histogram needs at least one edge" }
        require(edges.all { it.isFinite() }) { "edges must be finite" }
        require(edges.zipWithNext().all { (a, b) -> a < b }) { "edges must be strictly increasing" }
        require(counts.size == edges.size + 1) { "one count per bin" }
    }

    val bins: Int get() = counts.size


    fun observe(value: Double): Histogram = apply {
        require(value.isFinite()) { "observation of $value into $name" }
        val index = edges.indexOfFirst { value < it }
        counts[if (index < 0) edges.size else index]++
    }

    fun count(bin: Int): Long {
        require(bin in counts.indices) { "bin $bin out of range for $name (0..${counts.size - 1})" }
        return counts[bin]
    }

    val total: Long get() = counts.sum()

    fun rows(): List<Row> = counts.indices.map { index ->
        Row(
            index = index,
            range = when {
                index == 0 -> "<${NumberFormat.fixed(edges[0], 4)}"
                index == edges.size -> ">=${NumberFormat.fixed(edges[index - 1], 4)}"
                else -> "${NumberFormat.fixed(edges[index - 1], 4)}..${NumberFormat.fixed(edges[index], 4)}"
            },
            count = counts[index],
        )
    }

    fun copyOf(): Histogram = Histogram(name, edges, counts.copyOf())

    fun toJson(): MetricJson.Obj = jsonOf(
        "edges" to MetricJson.Arr(edges.map { MetricJson.Num(it) }),
        "counts" to MetricJson.Arr(counts.map { MetricJson.Num(it.toDouble()) }),
        "total" to MetricJson.Num(total.toDouble()),
    )

    data class Row(val index: Int, val range: String, val count: Long)
}
