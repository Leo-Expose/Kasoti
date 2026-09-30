package dev.kasoti.eval.output

import dev.kasoti.eval.metrics.GovernanceReport
import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.RunRecord
import dev.kasoti.evalmetrics.MetricJson
import dev.kasoti.evalmetrics.MetricJsonReader
import dev.kasoti.evalmetrics.MetricJsonWriter
import dev.kasoti.evalmetrics.jsonOf

/** Alias so the sink signatures stay readable without a `java.nio.file` import per file. */
typealias Path = java.nio.file.Path

/**
 * Serialises a completed [RunRecord] to the four artefacts EVAL.md §3 names.
 *
 * ## Relationship to `dev.kasoti.evalmetrics`
 *
 * `:core`'s `MetricSink` owns the *numbers*: the counter/measure/bucket split, histogram
 * bins, and the `metrics.json` shape. This interface is the run-level writer that puts those
 * numbers next to the governance facts a run needs to be citable — split discipline
 * (EVAL.md §8), calibration (EVAL.md §7), and whether a suite was skipped or passed.
 *
 * The two are joined by [EvalMetricsBridge], which projects a [RunRecord] onto `:core`'s
 * sink and reads the snapshot back. `dev.kasoti.evalmetrics` is the only serialiser of
 * metrics; this module writes the files around it.
 *
 * // TODO(M1,@eval): if :core's MetricSink grows a governance channel (run status, skipped
 *   suites, calibration), fold GovernanceReport into it and delete this interface. Until
 *   then the split is deliberate: a gate status must not sit next to a rate in the same map,
 *   where a consumer could add them.
 */
interface MetricSink {
    fun write(record: RunRecord, outDir: Path)
}

/**
 * Writes the four artefacts EVAL.md §3 names: `metrics.json`, `summary.md`, `histograms/`
 * and `failures/`.
 *
 * ## Division of labour with `dev.kasoti.evalmetrics`
 *
 * The numbers in `metrics.json` are produced by `:core`'s `MetricSink` and rendered by
 * `:core`'s `MetricJsonWriter`, via [EvalMetricsBridge]. This class adds only what the sink
 * does not model — run governance — and the file/CSV/SVG plumbing. It never recomputes a
 * metric, so `summary.md` and `metrics.json` cannot disagree: both are rendered from the
 * same [RunRecord] and the same bridge.
 *
 * `metrics.json` is pretty-printed and field-ordered on purpose. These files are committed
 * for gate runs (EVAL.md §1) and reviewed in diffs, so a stable byte-level layout matters
 * more than a minimal one: a reviewer asking "did this number move?" is asking a question
 * about a diff.
 */
class JsonMetricSink(
    private val summaryRenderer: SummaryRenderer = SummaryRenderer(),
) : MetricSink {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        // A governance field that is `null` (an unmeasured age, an absent detail) is written
        // as `null` rather than omitted, so a reader can tell "not measured" from "not
        // applicable" — the same distinction the gate states carry.
        explicitNulls = true
    }

    override fun write(record: RunRecord, outDir: Path) {
        val bridge = EvalMetricsBridge(
            runId = record.runId,
            suite = record.suite,
            commit = record.commit,
            devices = record.devices.map { "${it.id} (${it.role})" },
            split = record.splitDiscipline.checked.joinToString("+").ifEmpty { "none-in-use" },
        ).absorb(record)

        java.nio.file.Files.createDirectories(outDir)

        val governance = GovernanceReport(
            status = record.status,
            exitCode = record.exitCode,
            splitDiscipline = dev.kasoti.eval.metrics.SplitGovernance.of(record.splitDiscipline),
            calibration = dev.kasoti.eval.metrics.CalibrationGovernance.of(record.calibration),
            suites = record.suites,
            gates = record.gates.map { dev.kasoti.eval.metrics.GateGovernance.of(it) },
            operatingPoints = record.operatingPoints,
            thresholdProvenance = record.thresholdProvenance,
            failures = record.failures,
            specConflicts = record.specConflicts,
            notes = record.notes,
            hosts = record.devices,
            host = record.host,
            jvm = record.jvm,
            commitDirty = record.commitDirty,
        )

        val document = jsonOf(
            "schemaVersion" to MetricJson.Str(record.schemaVersion),
            "runId" to MetricJson.Str(record.runId),
            "suite" to MetricJson.Str(record.suite),
            "startedAtUtc" to MetricJson.Str(record.startedAtUtc),
            "finishedAtUtc" to MetricJson.Str(record.finishedAtUtc),
            // The numbers: produced and serialised by :core's sink and writer.
            "evalmetrics" to bridge.toJson(),
            // The run's admissibility: split discipline, calibration, gate states.
            "governance" to governanceJson(governance),
            // Tables and histograms are rendered in their own files; named here so a reader
            // of metrics.json knows what else is in the run directory.
            "tables" to MetricJson.Arr(record.tables.map { MetricJson.Str(it.name) }),
            "histograms" to MetricJson.Arr(record.histograms.map { MetricJson.Str(it.name) }),
            "failures" to MetricJson.Arr(record.failures.map { MetricJson.Str(it.caseId) }),
            "metricsJson" to MetricJson.Str("governance + evalmetrics are authoritative; " +
                "the flat metric list is reproduced in summary.md"),
        )

        java.nio.file.Files.writeString(
            outDir.resolve("metrics.json"),
            MetricJsonWriter.write(document) + "\n",
        )
        java.nio.file.Files.writeString(
            outDir.resolve("summary.md"),
            summaryRenderer.render(record, bridge, governance),
        )
        writeHistograms(record, outDir.resolve("histograms"))
        writeFailures(record, outDir.resolve("failures"))
        // The `evalmetrics` half on its own, for consumers that only want the numbers.
        java.nio.file.Files.writeString(
            outDir.resolve("metrics.evalmetrics.json"),
            MetricJsonWriter.write(bridge.toJson()) + "\n",
        )
    }

    /**
     * Serialise the governance half with kotlinx, then re-read it through `:core`'s parser.
     *
     * The round trip is deliberate: it means both halves of `metrics.json` are guaranteed to
     * be well-formed by the *same* RFC-8259 reader, so a consumer cannot find one half
     * acceptable and the other not. It also means the escape rules cannot disagree between
     * the two writers.
     */
    private fun governanceJson(governance: GovernanceReport): MetricJson.Obj {
        val text = json.encodeToString(GovernanceReport.serializer(), governance)
        return MetricJsonReader.parse(text) as MetricJson.Obj
    }

    private fun writeHistograms(record: RunRecord, dir: Path) {
        if (record.histograms.isEmpty()) return
        java.nio.file.Files.createDirectories(dir)
        for (histogram in record.histograms) {
            val name = histogram.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val csv = buildString {
                append("# ${histogram.name}")
                histogram.split?.let { append(" | split=$it") }
                if (histogram.note.isNotEmpty()) append(" | ${histogram.note.replace("\n", " ")}")
                append('\n')
                append("bin_lower,bin_upper,count\n")
                for (bin in histogram.bins) {
                    append(fmt(bin.lowerInclusive)).append(',')
                    append(fmt(bin.upperExclusive)).append(',')
                    append(bin.count).append('\n')
                }
            }
            java.nio.file.Files.writeString(dir.resolve("$name.csv"), csv)
            java.nio.file.Files.writeString(dir.resolve("$name.svg"), svg(histogram))
        }
    }

    /**
     * A minimal inline-SVG histogram. SVG rather than PNG because it is diffable, scales in a
     * markdown viewer, and needs no image library on the harness classpath.
     */
    private fun svg(histogram: Histogram): String {
        val width = 720
        val height = 220
        val pad = 32
        val max = histogram.bins.maxOfOrNull { it.count } ?: 0
        val innerW = width - 2 * pad
        val innerH = height - 2 * pad
        val barW = if (histogram.bins.isEmpty()) innerW.toDouble() else innerW.toDouble() / histogram.bins.size
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
            append("""viewBox="0 0 $width $height" font-family="monospace" font-size="11">""").append('\n')
            append("""<rect width="$width" height="$height" fill="#ffffff"/>""").append('\n')
            histogram.bins.forEachIndexed { index, bin ->
                val h = if (max <= 0) 0.0 else bin.count.toDouble() / max * innerH
                val x = pad + index * barW
                val y = pad + innerH - h
                append(
                    """<rect x="${fmt(x)}" y="${fmt(y)}" width="${fmt(barW * 0.92)}" height="${fmt(h)}" """ +
                        """fill="#3b5bdb"><title>["${fmt(bin.lowerInclusive)}, """ +
                        "${fmt(bin.upperExclusive)}) = ${bin.count}</title></rect>",
                ).append('\n')
            }
            append("""<line x1="$pad" y1="${pad + innerH}" x2="${pad + innerW}" y2="${pad + innerH}" stroke="#343a40"/>""").append('\n')
            val lo = histogram.bins.firstOrNull()?.lowerInclusive ?: 0.0
            val hi = histogram.bins.lastOrNull()?.upperExclusive ?: 1.0
            append("""<text x="$pad" y="${height - 8}" fill="#343a40">${fmt(lo)}</text>""").append('\n')
            append(
                """<text x="${pad + innerW}" y="${height - 8}" fill="#343a40" text-anchor="end">""" +
                    "${fmt(hi)} ${histogram.unit}</text>",
            ).append('\n')
            append("""<text x="$pad" y="16" fill="#343a40">${histogram.name} — max bin = $max</text>""").append('\n')
            append("</svg>\n")
        }
    }

    private fun writeFailures(record: RunRecord, dir: Path) {
        java.nio.file.Files.createDirectories(dir)
        val md = if (record.failures.isEmpty()) {
            buildString {
                appendLine("# Failure gallery — ${record.runId}")
                appendLine()
                appendLine("No failing cases. The gates that were measured and passed are in")
                appendLine("`../summary.md`; suites that were **skipped** say so in")
                appendLine("`../metrics.json` under `governance.gates[].status = SKIPPED`.")
                appendLine()
                appendLine("A skipped gate is not a passing gate. This file being empty does not")
                appendLine("mean the run covered everything.")
            }
        } else {
            buildString {
                appendLine("# Failure gallery — ${record.runId}")
                appendLine()
                appendLine("${record.failures.size} case(s). These are the deck's failure gallery: every")
                appendLine("one is a real miss with a reproducible case id, not a curated selection.")
                appendLine()
                for (failure in record.failures) {
                    appendLine("## `${failure.caseId}`")
                    appendLine()
                    appendLine("- suite: `${failure.suite}`")
                    failure.gateId?.let { appendLine("- gate: `$it`") }
                    appendLine("- expected: ${failure.expected}")
                    appendLine("- observed: ${failure.observed}")
                    appendLine("- reason: ${failure.reason}")
                    if (failure.artefactRef.isNotEmpty()) appendLine("- artefact: `${failure.artefactRef}`")
                    appendLine()
                }
            }
        }
        java.nio.file.Files.writeString(dir.resolve("failures.md"), md)
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(v)
}
