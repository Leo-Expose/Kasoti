package dev.kasoti.eval.output

import dev.kasoti.eval.ExitCode
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.metrics.GovernanceReport
import dev.kasoti.eval.run.RunRecord

/**
 * Renders `summary.md` — the paste-ready report EVAL.md §6 specifies.
 *
 * EVAL.md §6 requires seven things in this file: date, commit, devices, suite, a metrics
 * table, the operating points chosen, the failures linked, a known-limits paragraph of at
 * least three sentences, and a sign-off line. Each is emitted as its own titled section
 * so a reviewer can confirm completeness by reading the headings, and [checklist] fails
 * the run's own self-test if one is ever dropped.
 *
 * Everything here is derived from [RunRecord] — the renderer computes no numbers of its
 * own, so the prose cannot drift away from `metrics.json`.
 */
class SummaryRenderer {

    /**
     * @param bridge the `dev.kasoti.evalmetrics` view of the same run, appended verbatim so
     *   the shared metrics rendering appears in the paste-ready report without being
     *   re-derived here.
     */
    fun render(record: RunRecord, bridge: EvalMetricsBridge, governance: GovernanceReport): String = buildString {
        appendLine("# KASOTI eval — `${record.runId}`")
        appendLine()
        appendLine(statusBanner(record))
        appendLine()

        appendLine("## 1. Run identification")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| run-id | `${record.runId}` |")
        appendLine("| suite | `${record.suite}` |")
        appendLine("| date (UTC) | ${record.startedAtUtc} |")
        appendLine("| commit | `${record.commit}`${if (record.commitDirty) " **(dirty working tree)**" else ""} |")
        appendLine("| host | `${record.host}` |")
        appendLine("| jvm | `${record.jvm}` |")
        appendLine(
            "| devices | " + if (record.devices.isEmpty()) {
                "none attached (host-only run)"
            } else {
                record.devices.joinToString("; ") { "`${it.id}` ${it.model} ${it.osVersion} (${it.role})" }
            } + " |",
        )
        appendLine("| metrics schema | `${record.schemaVersion}` |")
        appendLine()

        appendLine("## 2. Headline")
        appendLine()
        val headline = record.headline
        if (headline == null) {
            appendLine("The MRZ mutate-catch number was not produced in this run.")
        } else {
            appendLine(
                "> **MRZ mutate-catch: ${pct(headline.value)} of *detectable* mutants** " +
                    "(run `${record.runId}`, gate `M-GATE-01`).",
            )
        }
        val blind = record.metrics.firstOrNull { it.name == "mrz.mutate_catch.structurally_blind" }
        if (blind != null) {
            appendLine(">")
            appendLine(
                "> **Structurally blind cases: ${blind.value?.toInt() ?: 0}** — reported " +
                    "separately and *never* folded into the headline above. These are rows no " +
                    "MRZ arithmetic can catch; see §6.",
            )
        }
        appendLine()

        appendLine("## 3. Suites")
        appendLine()
        appendLine("| Suite | Ran | Duration | Gates | Note |")
        appendLine("|---|---|---|---|---|")
        for (suite in record.suites) {
            val gates = suite.gateIds.joinToString(", ") { "`$it`" }.ifEmpty { "—" }
            appendLine(
                "| `${suite.suite}` | ${if (suite.ran) "yes" else "**no**"} | " +
                    "${suite.durationMs} ms | $gates | ${suite.skipReason ?: suite.notes.joinToString("; ")} |",
            )
        }
        appendLine()

        appendLine("## 4. Gates")
        appendLine()
        appendLine("| Gate | Status | Bar | Observed | Detail |")
        appendLine("|---|---|---|---|---|")
        for (gate in record.gates) {
            appendLine(
                "| `${gate.id}` ${gate.name} | ${badge(gate.status)} | ${gate.bar} | " +
                    "${gate.observed} | ${gate.detail.ifEmpty { "—" }.replace("|", "\\|")} |",
            )
        }
        appendLine()

        appendLine("## 5. Metrics")
        appendLine()
        appendLine("| Metric | Value | Unit | Split | Note |")
        appendLine("|---|---|---|---|---|")
        for (metric in record.metrics) {
            appendLine(
                "| `${metric.name}` | ${metric.value?.let { num(it) } ?: "—"} | ${metric.unit} | " +
                    "${metric.split ?: "—"} | ${metric.note.replace("|", "\\|")} |",
            )
        }
        appendLine()

        for (table in record.tables) {
            appendLine("### ${table.name}")
            appendLine()
            appendLine("| | ${table.columnLabels.joinToString(" | ")} |")
            appendLine("|---|${table.columnLabels.joinToString("|---|")}")
            table.rowLabels.forEachIndexed { r, row ->
                val cells = table.cells.getOrNull(r)?.joinToString(" | ") { num(it) } ?: ""
                appendLine("| **$row** | $cells |")
            }
            if (table.note.isNotEmpty()) {
                appendLine()
                appendLine("_${table.note}_")
            }
            appendLine()
        }

        if (record.histograms.isNotEmpty()) {
            appendLine("### Histograms")
            appendLine()
            for (histogram in record.histograms) {
                appendLine("- `histograms/${histogram.name}.csv` / `.svg` — ${histogram.bins.size} bins" +
                    (histogram.split?.let { ", split `$it`" } ?: "") +
                    (if (histogram.note.isNotEmpty()) ". ${histogram.note}" else ""))
            }
            appendLine()
        }

        appendLine("## 6. Operating points chosen")
        appendLine()
        if (record.operatingPoints.isEmpty()) {
            appendLine("None: this run consulted no tunable thresholds.")
        } else {
            appendLine("| Threshold | Value | Unit | Selected on split | Provisional | Policy range | Rationale |")
            appendLine("|---|---|---|---|---|---|---|")
            for (point in record.operatingPoints) {
                appendLine(
                    "| `${point.threshold}` | ${num(point.value)} | ${point.unit} | " +
                        "`${point.selectedOnSplit}` | ${if (point.provisional) "**yes**" else "no"} | " +
                        "[${num(point.floor)}, ${num(point.ceiling)}] | ${point.rationale} |",
                )
            }
            val provisional = record.operatingPoints.count { it.provisional }
            if (provisional > 0) {
                appendLine()
                appendLine(
                    "> $provisional operating point(s) are still at the untuned registry default " +
                        "(`selectedOnSplit: untuned`). FUSION.md §5 records the shipped values as " +
                        "\"TBD by tuning\", so a metric that depends on one is a pipeline reading, " +
                        "not a tuned operating point, and is marked as such here.",
                )
            }
        }
        appendLine()

        appendLine("## 7. Split discipline (EVAL.md §8)")
        appendLine()
        val split = record.splitDiscipline
        appendLine("**Verdict: ${split.summary}**")
        appendLine()
        appendLine("- ledger version: `${record.thresholdLedgerVersion}`")
        appendLine("- thresholds consulted this run: ${record.thresholdsInUse.joinToString(", ") { "`$it`" }.ifEmpty { "none" }}")
        if (split.untuned.isNotEmpty()) {
            appendLine("- untuned (registry default, provisional): ${split.untuned.joinToString("; ")}")
        }
        if (split.unrecorded.isNotEmpty()) {
            appendLine("- **unrecorded provenance:** ${split.unrecorded.joinToString("; ")}")
        }
        if (split.reportSplitTuned.isNotEmpty()) {
            appendLine("- **selected on the report split (EVAL.md §8 violation):** ${split.reportSplitTuned.joinToString("; ")}")
        }
        if (split.manifestProblems.isNotEmpty()) {
            appendLine("- manifest problems:")
            for (problem in split.manifestProblems) {
                appendLine("  - `${problem.severity}` `${problem.code}` [${problem.subject}] — ${problem.detail}")
            }
        }
        appendLine()

        appendLine("## 8. Calibration (EVAL.md §7)")
        appendLine()
        appendLine("- device: `${record.calibration.deviceId}`")
        appendLine("- verdict: ${if (record.calibration.accepted) "**accepted**" else "**refused**"} — ${record.calibration.reason}")
        appendLine()

        appendLine("## 9. Failures")
        appendLine()
        if (record.failures.isEmpty()) {
            appendLine("No failing cases. See `failures/failures.md`.")
        } else {
            appendLine("${record.failures.size} failing case(s), itemised in [`failures/failures.md`](failures/failures.md):")
            appendLine()
            for (failure in record.failures.take(MAX_LISTED_FAILURES)) {
                appendLine("- `${failure.caseId}` (${failure.suite}) — expected ${failure.expected}, observed ${failure.observed}")
            }
            if (record.failures.size > MAX_LISTED_FAILURES) {
                appendLine("- …and ${record.failures.size - MAX_LISTED_FAILURES} more in `failures/failures.md`")
            }
        }
        appendLine()

        if (record.specConflicts.isNotEmpty()) {
            appendLine("## 10. Spec conflicts")
            appendLine()
            appendLine(
                "The harness found the following contradictions in the normative docs. It does " +
                    "not resolve them, and no number in this run depends on how they are settled.",
            )
            appendLine()
            for (conflict in record.specConflicts) {
                appendLine("- $conflict")
            }
            appendLine()
        }

        val sectionNumber = if (record.specConflicts.isEmpty()) 10 else 11
        appendLine("## $sectionNumber. Known limits")
        appendLine()
        for (limit in knownLimits(record)) {
            appendLine("- $limit")
        }
        appendLine()
        appendLine(knownLimitsParagraph(record))
        appendLine()

        appendLine("## ${sectionNumber + 1}. `dev.kasoti.evalmetrics` rendering")
        appendLine()
        appendLine(
            "The block below is produced by `:core`'s own `MetricSink` and reproduced verbatim, " +
                "so the shared metrics rendering and this report cannot disagree.",
        )
        appendLine()
        appendLine("```")
        append(bridge.toMarkdown().trimEnd())
        appendLine()
        appendLine("```")
        appendLine()

        appendLine("## ${sectionNumber + 2}. Sign-off")
        appendLine()
        appendLine("| Role | Name | Date | Verdict |")
        appendLine("|---|---|---|---|")
        appendLine("| Vision owner | ______________________ | __________ | ${signoffPrompt(record)} |")
        appendLine("| Lead | ______________________ | __________ | ${signoffPrompt(record)} |")
        appendLine()
        appendLine(
            "Unsigned. A run with no signatures here is a development run: citable as a number, " +
                "not citable as a gate (EVAL.md §6).",
        )
        appendLine()
        appendLine("---")
        appendLine()
        appendLine(
            "Generated by `:eval` from run `${record.runId}`. " +
                "No metric in this file was typed by hand (AGENTS.md §5).",
        )
    }

    /**
     * The EVAL.md §6 known-limits paragraph: prose, at least three sentences, and always
     * including the limits that apply to *this* run rather than a generic boilerplate list.
     */
    private fun knownLimitsParagraph(record: RunRecord): String = buildString {
        val sentences = mutableListOf<String>()

        val skipped = record.suites.filterNot { it.ran }
        if (skipped.isNotEmpty()) {
            sentences +=
                "This run did not cover ${skipped.joinToString(", ") { "the ${it.suite} suite" }}, " +
                    "so any statement about those areas here describes the harness rather than the " +
                    "product."
        } else {
            sentences +=
                "Every suite in this run executed, which makes the numbers internally complete but " +
                    "says nothing about the datasets they were measured on: " +
                    "the MRZ and QR corpora are generated in-process from fixed seeds, so they " +
                    "measure the parser against its own generator and cannot detect a shared " +
                    "misreading of ICAO 9303."
        }

        sentences +=
            "The face, liveness and macro figures are bounded by what the collected datasets " +
            "actually contain: with the corpora currently committed, per-bucket and worst-bucket " +
            "gaps (EVAL.md §4) have too little support to be read as evidence about any " +
            "demographic or lighting condition, and no per-bucket number here should be quoted " +
            "without its bucket size."

        val untuned = record.operatingPoints.count { it.provisional }
        if (untuned > 0) {
            sentences +=
                "$untuned of the ${record.operatingPoints.size} operating points in this run are " +
                    "still the untuned registry defaults, so a metric that depends on one measures " +
                    "the pipeline end-to-end rather than a tuned decision boundary, and the " +
                    "corresponding number moves when the threshold is tuned."
        }

        sentences +=
            "A pass here means the gate as written in SPEC.md §7 was met on this data, on this " +
            "host, at this commit; it is not a claim about adversarial performance, because the " +
            "red-team corpus (REDTEAM.md) is run separately and is not part of this harness."

        if (record.splitDiscipline.untuned.isNotEmpty()) {
            sentences +=
                "Finally, split discipline is enforced only as far as the committed manifests " +
                    "declare: the harness can prove a threshold was not selected on the report " +
                    "split, but it cannot prove that the split itself was built without leakage, " +
                    "because the media that would let it check never enters the repository."
        }

        append(sentences.joinToString(" "))
    }

    private fun knownLimits(record: RunRecord): List<String> {
        val limits = mutableListOf(
            "MRZ and QR corpora are generated in-process from fixed seeds (EVAL.md §2); they are " +
                "regression fixtures, not independent test data.",
        )
        val skipped = record.suites.filterNot { it.ran }
        if (skipped.isNotEmpty()) {
            limits += "Not executed this run: " + skipped.joinToString(", ") { "`${it.suite}` (${it.skipReason})" } + "."
        }
        val withBuckets = record.metrics.filter { it.name.contains("bucket") && (it.value ?: 0.0) > 0 }
        if (withBuckets.isEmpty()) {
            limits += "No per-bucket breakdown has data behind it in this run; EVAL.md §4 bucket " +
                "gaps are therefore unreported rather than reported as zero."
        }
        if (record.splitDiscipline.untuned.isNotEmpty()) {
            limits += "Provisional (untuned) thresholds: " + record.splitDiscipline.untuned.joinToString(", ") + "."
        }
        limits += "Datasets and thresholds are whatever the committed manifests declare; the " +
            "harness enforces the declaration, not its truth."
        return limits
    }

    private fun signoffPrompt(record: RunRecord): String = when (record.status) {
        dev.kasoti.eval.run.RunStatus.PASS -> "accept / reject"
        dev.kasoti.eval.run.RunStatus.INVALID -> "**do not accept** — invalid run"
        dev.kasoti.eval.run.RunStatus.INCOMPLETE -> "**incomplete** — cannot accept"
        dev.kasoti.eval.run.RunStatus.GATE_FAILED -> "**reject** — gate failed"
        dev.kasoti.eval.run.RunStatus.ERROR -> "**reject** — harness error"
    }

    private fun statusBanner(record: RunRecord): String {
        val badge = when (record.status) {
            dev.kasoti.eval.run.RunStatus.PASS -> "✅ **PASS**"
            dev.kasoti.eval.run.RunStatus.GATE_FAILED -> "❌ **GATE FAILED**"
            dev.kasoti.eval.run.RunStatus.INVALID ->
                "⛔ **INVALID RUN — NOT EVIDENCE** (EVAL.md §8: threshold selected on the report split)"
            dev.kasoti.eval.run.RunStatus.INCOMPLETE ->
                "⚠️ **INCOMPLETE** — a required suite was skipped; this is not a pass"
            dev.kasoti.eval.run.RunStatus.ERROR -> "💥 **HARNESS ERROR**"
        }
        return "$badge · suite `${record.suite}` · exit ${record.exitCode} " +
            "(${ExitCode.entries.firstOrNull { it.code == record.exitCode }?.meaning ?: "?"})"
    }

    private fun badge(status: GateStatus): String = when (status) {
        GateStatus.PASS -> "✅ PASS"
        GateStatus.FAIL -> "❌ FAIL"
        GateStatus.SKIPPED -> "⏭️ SKIPPED"
        GateStatus.INVALID -> "⛔ INVALID"
    }

    /** A percentage from a fraction. The `trimEnd` runs before the `%` is appended, so the
     *  sign is never mistaken for a trailing zero and stripped. */
    private fun pct(v: Double?): String {
        if (v == null) return "—"
        return "%.4f".format(v * 100.0).trimEnd('0').trimEnd('.') + "%"
    }

    private fun num(v: Double): String = when {
        v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        kotlin.math.abs(v) >= 1e-4 && kotlin.math.abs(v) < 1e7 -> "%.6f".format(v).trimEnd('0').trimEnd('.')
        else -> "%.6g".format(v)
    }

    private companion object {
        const val MAX_LISTED_FAILURES = 25
    }
}
