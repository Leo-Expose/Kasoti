package dev.kasoti.eval.suites

import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.FailureCase
import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.TableData
import dev.kasoti.evalmetrics.MutateCatch
import dev.kasoti.evalmetrics.MutateCatchReport
import dev.kasoti.evalmetrics.SpecGates
import dev.kasoti.mrz.MrzCorpus
import dev.kasoti.mrz.MrzField
import dev.kasoti.mrz.MrzMutation
import dev.kasoti.mrz.MrzParser

/**
 * The MRZ mutate-catch gate (SPEC.md §7, EVAL.md §2 `D-MRZ`, 10 000 rows, 100%).
 *
 * This is the number the deck leads with. The measurement itself comes from
 * `dev.kasoti.evalmetrics.MutateCatch` — the shared implementation, so the harness and the
 * unit tests cannot disagree about what "caught" means. This suite supplies the corpus, runs
 * the two extra measurements the shared implementation does not do (catch rate broken down
 * by mutation and by field), and builds the gates from `dev.kasoti.evalmetrics.SpecGates`.
 *
 * Three quantities, kept apart on purpose:
 *
 *  1. **Detectable mutants must all be caught — 100%.** A row whose mutation provably
 *     changes a check digit's weighted sum cannot survive, so a miss is a defect in
 *     `MrzParser`, not arithmetic luck.
 *  2. **Structurally blind rows are reported separately and never folded into (1).** TD1 has
 *     no composite check digit, so a recomputed field check digit is undetectable by any MRZ
 *     arithmetic. Averaging them in understates the parser; dropping them overstates the
 *     coverage. They get their own count, their own histogram and their reasons in full.
 *  3. **Valid documents must not be flagged.** The other way to satisfy (1) is to flag
 *     everything, so the false-positive rate is a gate of its own.
 */
object MrzSuite {

    const val GATE_CATCH = "M-GATE-01"
    const val GATE_FALSE_POSITIVE = "M-GATE-02"
    const val GATE_BLIND_SPOTS = "M-GATE-03"

    /** EVAL.md §2: fixture seeds are fixed so a run is reproducible. */
    const val SEED = 20_260_929L
    const val REFERENCE_YEAR = 2026

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val tables: List<TableData>,
        val histograms: List<Histogram>,
        val failures: List<FailureCase>,
        val notes: List<String>,
        val report: MutateCatchReport,
    )

    fun run(rowCount: Int): Result {
        val cases = MrzCorpus.generate(seed = SEED, count = rowCount, referenceYear = REFERENCE_YEAR)

        // The shared implementation owns the definition of "caught" and of the three
        // populations. Everything below is attribution on top of it.
        val report = MutateCatch.evaluate(cases, REFERENCE_YEAR)

        val failures = mutableListOf<FailureCase>()

        // Missed detectable mutants, with the lines that survived.
        val missed = cases.filter { it.expectedCaught }.filter { case ->
            !MrzCorpus.detect(MrzParser.parse(case.lines, REFERENCE_YEAR))
        }
        for (case in missed) {
            failures += FailureCase(
                suite = "mrz",
                caseId = case.id,
                reason = "detectable mutant survived: ${case.mutation} in ${case.format} passed " +
                    "every check. A DATA_CHAR mutation provably changes a check digit's weighted " +
                    "sum, so this is a parser defect, not bad luck.",
                expected = "caught by MrzParser",
                observed = "allChecksPassed=true, structuralErrors=[]",
                artefactRef = case.lines.joinToString(" | "),
                gateId = GATE_CATCH,
            )
        }
        for (id in report.falsePositives) {
            val case = cases.first { it.id == id }
            failures += FailureCase(
                suite = "mrz",
                caseId = id,
                reason = "a valid document was flagged. Catching every mutant by flagging " +
                    "everything would satisfy the catch gate and be useless.",
                expected = "not flagged",
                observed = "flagged",
                artefactRef = case.lines.joinToString(" | "),
                gateId = GATE_FALSE_POSITIVE,
            )
        }

        // Attribution: catch rate per mutation and per expected field.
        val perMutation = linkedMapOf<MrzMutation, IntArray>()
        val perField = linkedMapOf<MrzField, IntArray>()
        val fieldIds = cases.flatMap { it.expectedFields }.toSet()
        for (case in cases) {
            val caught = MrzCorpus.detect(MrzParser.parse(case.lines, REFERENCE_YEAR))
            val slot = perMutation.getOrPut(case.mutation) { IntArray(2) }
            slot[0]++
            if (case.expectedCaught && caught) slot[1]++
            for (field in case.expectedFields) {
                val fs = perField.getOrPut(field) { IntArray(2) }
                fs[0]++
                if (caught) fs[1]++
            }
        }

        val catchGate = SpecGates.mrzMutateCatch(report)
        val blindGate = SpecGates.blindSpots(report)
        val gates = listOf(
            GateResult(
                id = GATE_CATCH,
                name = catchGate.name,
                status = if (catchGate.pass) GateStatus.PASS else GateStatus.FAIL,
                bar = catchGate.bar,
                observed = catchGate.measured,
                detail = "${report.total} rows, seed $SEED, reference year $REFERENCE_YEAR. " +
                    "${report.blindCount} structurally blind rows are excluded from the " +
                    "denominator and reported separately as mrz.mutate_catch.structurally_blind. " +
                    "Source: ${catchGate.source}.",
                evidenceRef = "dev.kasoti.mrz.MrzCorpus.generate/$SEED/$rowCount",
            ),
            GateResult(
                id = GATE_FALSE_POSITIVE,
                name = "MRZ false-positive rate on valid documents",
                status = if (report.falsePositives.isEmpty()) GateStatus.PASS else GateStatus.FAIL,
                bar = "0% — EVAL.md §4 precision; not in SPEC.md §7, but a suite that passes the " +
                    "catch gate by flagging everything is not evidence",
                observed = "${report.falsePositives.size}/${report.cleanCases} valid rows flagged",
                detail = "Valid rows are the corpus rows with mutation=NONE and no blind-spot reason.",
                evidenceRef = "dev.kasoti.evalmetrics.MutateCatchReport.precise",
            ),
            GateResult(
                id = GATE_BLIND_SPOTS,
                name = blindGate.name,
                status = if (blindGate.pass) GateStatus.PASS else GateStatus.FAIL,
                bar = blindGate.bar + " — EVAL.md §4, and the reason the 100% gate cannot hide behind them",
                observed = blindGate.measured,
                detail = "These rows are undetectable by MRZ arithmetic. They are the substrate " +
                    "the other layers (QR signature, chip PA, macro, face) exist for, and no MRZ " +
                    "gate can close them. Full reasons are itemised in the table below.",
                evidenceRef = blindGate.source ?: "EVAL.md §4",
            ),
        )

        val metrics = listOf(
            Metric("mrz.corpus.rows", report.total.toDouble(), "rows", "all-test", GATE_CATCH),
            Metric(
                "mrz.mutate_catch.detectable_pct", report.catchRate, "fraction", "all-test", GATE_CATCH,
                "HEADLINE — the deck's number (SPEC.md §7). Caught / detectable; blind rows excluded.",
            ),
            Metric("mrz.mutate_catch.detectable_caught", report.caught.toDouble(), "rows", "all-test", GATE_CATCH),
            Metric("mrz.mutate_catch.detectable_total", report.detectable.toDouble(), "rows", "all-test", GATE_CATCH),
            Metric(
                "mrz.mutate_catch.structurally_blind", report.blindCount.toDouble(), "rows", "all-test", GATE_BLIND_SPOTS,
                "NOT part of the headline; see mrz.mutate_catch.blind_reasons",
            ),
            Metric("mrz.mutate_catch.structurally_blind_pct", report.blindRate, "fraction", "all-test", GATE_BLIND_SPOTS),
            Metric("mrz.valid.rows", report.cleanCases.toDouble(), "rows", "all-test", GATE_FALSE_POSITIVE),
            Metric(
                "mrz.valid.false_positive_pct",
                if (report.cleanCases == 0) 0.0 else report.falsePositives.size.toDouble() / report.cleanCases,
                "fraction", "all-test", GATE_FALSE_POSITIVE,
            ),
            Metric("mrz.td3.rows", cases.count { it.format.name == "TD3" }.toDouble(), "rows", "all-test"),
            Metric("mrz.td1.rows", cases.count { it.format.name == "TD1" }.toDouble(), "rows", "all-test"),
            Metric("mrz.distinct_expected_fields", fieldIds.size.toDouble(), "fields", "all-test", GATE_CATCH,
                "every check-protected field is exercised as a mutation target"),
        )

        val tables = listOf(
            TableData(
                name = "MRZ catch rate by mutation",
                rowLabels = perMutation.keys.map { it.name },
                columnLabels = listOf("rows", "detectable", "caught", "catch %", "blind rows"),
                cells = perMutation.map { (_, counts) ->
                    listOf(
                        counts[0].toDouble(),
                        counts[1].toDouble(),
                        counts[1].toDouble(),
                        if (counts[0] == 0) 0.0 else counts[1] * 100.0 / counts[0],
                        (counts[0] - counts[1]).toDouble(),
                    )
                },
                note = "Rows not counted as detectable are structurally blind mutants, reported " +
                    "in the last column rather than folded into the catch rate.",
            ),
            TableData(
                name = "MRZ catch rate by expected field",
                rowLabels = perField.keys.map { it.name },
                columnLabels = listOf("detectable rows", "caught", "catch %"),
                cells = perField.map { (_, counts) ->
                    listOf(counts[0].toDouble(), counts[1].toDouble(), counts[1] * 100.0 / counts[0].coerceAtLeast(1))
                },
                note = "Per-field attribution: a miss in one field must not be hidden by the " +
                    "aggregate (FUSION.md R-MATH-01 requires named evidence per field).",
            ),
            TableData(
                name = "MRZ structurally blind rows by reason",
                rowLabels = report.blindReasons.keys.toList(),
                columnLabels = listOf("rows"),
                cells = report.blindReasons.values.map { listOf(it.toDouble()) },
                note = "Undetectable by MRZ arithmetic, by construction. No MRZ gate can close " +
                    "these; they are what the QR, chip, macro and face layers are for.",
            ),
        )

        val histograms = listOf(
            categoricalHistogram(
                name = "mrz_mutation_mix",
                counts = perMutation.mapKeys { it.key.name }.mapValues { it.value[0] },
                unit = "rows",
                split = "all-test",
                note = "Composition of the generated corpus, for reference when reading the catch rate.",
            ),
            categoricalHistogram(
                name = "mrz_blind_reasons",
                counts = report.blindReasons,
                unit = "rows",
                split = "all-test",
                note = "Why the structurally blind rows are undetectable.",
            ),
        )

        val notes = buildList {
            add("Corpus generated in-process from seed $SEED; no MRZ media is committed.")
            add("Catch/precision definitions come from dev.kasoti.evalmetrics.MutateCatch, so the " +
                "harness and :core's own tests cannot disagree about what 'caught' means.")
            add("HEADLINE ${pct(report.catchRate)} on ${report.detectable} detectable mutants; " +
                "${report.blindCount} structurally blind rows reported separately and NOT counted " +
                "in either direction.")
            report.blindReasons.forEach { (reason, count) -> add("  · $count × $reason") }
        }

        return Result(gates, metrics, tables, histograms, failures, notes, report)
    }

    /**
     * A categorical distribution rendered as a histogram.
     *
     * The bins are ranks, not values: one bin per category ordered by descending count, so
     * the CSV is a shape a chart can consume rather than an identity mapping. The category
     * names live in the accompanying table, which is where a reader looks them up.
     */
    private fun categoricalHistogram(
        name: String,
        counts: Map<String, Int>,
        unit: String,
        split: String,
        note: String,
    ): Histogram {
        if (counts.isEmpty()) return Histogram(name, emptyList(), unit, split, note)
        val ordered = counts.entries.sortedByDescending { it.value }
        val bins = ordered.mapIndexed { index, entry ->
            Histogram.Bin(
                lowerInclusive = index.toDouble(),
                upperExclusive = index + 1.0,
                count = entry.value,
            )
        }
        return Histogram(
            name = name,
            bins = bins,
            unit = unit,
            split = split,
            note = "$note Bin i is category ${ordered.joinToString(", ") { it.key }} " +
                "(bins are ranks ordered by count).",
        )
    }

    private fun pct(v: Double): String = "%.4f".format(v * 100).trimEnd('0').trimEnd('.') + "%"
}
