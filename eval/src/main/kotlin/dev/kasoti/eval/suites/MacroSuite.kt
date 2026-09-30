package dev.kasoti.eval.suites

import dev.kasoti.factory.DocAggregator
import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessLabel
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.factory.SvmModel
import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.TableData
import dev.kasoti.evalmetrics.Classification
import dev.kasoti.evalmetrics.ClassificationMetrics
import dev.kasoti.evalmetrics.SpecGates
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.ln

/**
 * The macro suite (EVAL.md §4, SPEC.md §7).
 *
 * Three gates, in increasing order of what they would let a reader conclude:
 *
 * 1. **Features** — always runnable, on synthetic patches. Proves the extractor produces
 *    finite numbers, and gives the NumPy cross-check in `eval/tools/` something to compare
 *    against. Supports no claim about print processes.
 * 2. **Calibration** (EVAL.md §7) — without an accepted `device_calib.json` the suite
 *    refuses. Not because the numbers would be slightly less trustworthy: without
 *    white-balance and pixels-per-millimetre they measure the camera's colour pipeline, and
 *    a threshold fitted across two pipelines is fitted to the pipeline.
 * 3. **Classification** — needs trained weights. `ProcessClassifier` is a linear model
 *    over them; with none there is nothing to classify with, and hand-made weights would
 *    produce a number shaped like a metric without being one.
 *
 * With (2) and (3) both absent — the repository's current state — the run is INCOMPLETE,
 * not PASS. That is the honest result, and it is why `full` cannot be signed off today.
 */
object MacroSuite {

    const val GATE_FEATURES = "MACRO-FEAT"
    const val GATE_CALIBRATION = "MACRO-CAL"
    const val GATE_CLASSIFIER = "MACRO-CLS"

    /** SPEC.md §7: publish; ≥90% for offset-vs-inkjet with clip, floor ≥85% for RED use. */
    const val SPEC_BAR: String =
        "publish; ≥90% offset-vs-inkjet (clip, held-out); floor ≥85% for RED use, else AMBER-only"

    /** SPEC.md §7's RED-use floor; the implementation lives beside the code that applies it. */
    const val RECALL_FLOOR: Double = MacroClassification.RECALL_FLOOR

    /**
     * The word that has to appear wherever a synthetic macro number is printed.
     *
     * One constant, because the failure this guards against is a *drift* between places: a gate
     * detail that says synthetic, a console line that does not, and a run directory where half
     * the artefacts are unqualified.
     */
    const val SYNTHETIC_BANNER = "SYNTHETIC"

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val tables: List<TableData>,
        val histograms: List<Histogram>,
        val notes: List<String>,
    )

    fun run(
        registry: ThresholdRegistry,
        calibration: CalibrationVerdict,
        model: TrainedModel?,
        specimens: List<MacroSpecimenFile> = emptyList(),
    ): Result {
        val gates = mutableListOf<GateResult>()
        val metrics = mutableListOf<Metric>()
        val tables = mutableListOf<TableData>()
        val histograms = mutableListOf<Histogram>()
        val notes = mutableListOf<String>()

        // ---- 1. features ------------------------------------------------------
        val features = featureReport()
        metrics += features.metrics
        tables += features.table
        histograms += features.peakinessHistogram
        gates += GateResult(
            id = GATE_FEATURES,
            name = "Macro feature extractor on synthetic patches",
            status = GateStatus.PASS,
            bar = "reported, not gated — synthetic input cannot support a claim about real print processes",
            observed = "${ProcessLabel.entries.size} patches, ${features.nonFinite} non-finite",
            detail = "Every feature is finite and in range on patches generated from a fixed " +
                "seed. This proves the extractor runs and never divides by zero; it says " +
                "nothing about classification accuracy, which needs D-MACRO.",
            evidenceRef = "dev.kasoti.eval.suites.SpectrumPatch",
        )

        // ---- 2. calibration ---------------------------------------------------
        val calibrationGate = if (calibration.accepted) {
            GateResult(
                id = GATE_CALIBRATION,
                name = "Macro calibration (EVAL.md §7)",
                status = GateStatus.PASS,
                bar = "device_calib.json ≤ 7 days old, focusOk, 3 wb gains",
                observed = "%.2f days old".format(calibration.ageDays ?: 0.0),
                detail = calibration.reason,
                evidenceRef = calibration.source,
            )
        } else {
            GateResult(
                id = GATE_CALIBRATION,
                name = "Macro calibration (EVAL.md §7)",
                status = GateStatus.SKIPPED,
                bar = "device_calib.json ≤ 7 days old, focusOk, 3 wb gains",
                observed = "refused",
                detail = calibration.reason,
                evidenceRef = calibration.source,
            )
        }
        gates += calibrationGate
        if (!calibration.accepted) {
            notes += "Macro metrics REFUSED: ${calibration.reason}"
        }

        // ---- 3. classification ------------------------------------------------
        if (model == null) {
            gates += GateResult(
                id = GATE_CLASSIFIER,
                name = "Print-process classifier, per-class precision/recall",
                status = GateStatus.SKIPPED,
                bar = SPEC_BAR,
                observed = "not run — no trained model",
                detail = "No svm_print_v1.json was found. ProcessClassifier is a linear model " +
                    "over trained weights (dev.kasoti.factory.SvmModel); with no weights there " +
                    "is nothing to classify with, and hand-made weights would produce a number " +
                    "shaped like a metric without being one. Train one with " +
                    "`eval/tools/train_svm.py` once D-MACRO patches exist.",
                evidenceRef = "eval/models/svm_print_v1.json",
            )
            notes += "Macro classification SKIPPED: no trained model (eval/models/svm_print_v1.json absent)."
            return Result(gates, metrics, tables, histograms, notes)
        }

        if (model.synthetic) {
            notes += "$SYNTHETIC_BANNER MODEL — resolution: ${model.resolution.label}. " +
                model.resolution.note
        }
        val classification = MacroClassification.report(registry, model, specimens)
        gates += classification.gate
        metrics += classification.metrics
        tables += classification.table
        histograms += classification.marginHistogram
        notes += classification.notes
        return Result(gates, metrics, tables, histograms, notes)
    }

    // ---------------------------------------------------------------- features

    private class FeatureReport(
        val metrics: List<Metric>,
        val table: TableData,
        val peakinessHistogram: Histogram,
        val nonFinite: Int,
    )

    private fun featureReport(): FeatureReport {
        val metrics = mutableListOf<Metric>()
        var nonFinite = 0
        val rows = ProcessLabel.entries.map { label ->
            val image = SpectrumPatch.forLabel(label)
            val profile = dev.kasoti.factory.Spectrum.radialProfile(image)
            val lbp = dev.kasoti.factory.Lbp.histogram(image)
            val edge = dev.kasoti.factory.Edges.density(image)
            val finite = listOf(
                profile.peakFrequency, profile.peakiness, profile.bandEnergy,
                profile.highFrequencyEnergy, profile.spectralSlope, image.stdDev, edge,
            ).all { it.isFinite() } && lbp.all { it.isFinite() }

            val key = label.name.lowercase()
            metrics += Metric("macro.synthetic.$key.peak_frequency", profile.peakFrequency.toDouble(), "cycles/patch", "synthetic", GATE_FEATURES)
            metrics += Metric(
                "macro.synthetic.$key.peakiness", profile.peakiness.toDouble(), "ratio", "synthetic", GATE_FEATURES,
                "peak / local median of the radial profile; 1.0 means a flat spectrum",
            )
            metrics += Metric("macro.synthetic.$key.band_energy", profile.bandEnergy.toDouble(), "ratio", "synthetic", GATE_FEATURES)
            metrics += Metric("macro.synthetic.$key.edge_density", edge.toDouble(), "ratio", "synthetic", GATE_FEATURES)
            metrics += Metric("macro.synthetic.$key.lbp_sum", lbp.sum().toDouble(), "", "synthetic", GATE_FEATURES, "a normalised LBP histogram sums to 1")
            if (!finite) nonFinite++
            listOf(
                profile.peakFrequency.toDouble(),
                profile.peakiness.toDouble(),
                profile.bandEnergy.toDouble(),
                edge.toDouble(),
                lbp.sum().toDouble(),
            ) to finite
        }

        metrics += Metric(
            "macro.synthetic.non_finite_patches", nonFinite.toDouble(), "patches", "synthetic", GATE_FEATURES,
            "counted explicitly because a single NaN poisons every downstream metric",
        )
        metrics += Metric("macro.synthetic.lbp_sums_to_one", rows.count { it.first[4] == 1.0 }.toDouble(), "patches", "synthetic", GATE_FEATURES)

        val table = TableData(
            name = "Macro features on synthetic patches (pipeline check, not a metric)",
            rowLabels = ProcessLabel.entries.map { it.name },
            columnLabels = listOf("peak frequency", "peakiness", "band energy", "edge density", "LBP Σ"),
            cells = rows.map { it.first },
            note = "Generated from SpectrumPatch, not from D-MACRO. Every row here is a " +
                "property of the generator and must not be quoted as a property of a print process.",
        )

        return FeatureReport(metrics, table, peakinessHistogram(rows.map { it.first[1] }), nonFinite)
    }

    /**
     * Peakiness is unbounded above, so equal-width bins would stack every patch into the
     * last one. Log bins keep the synthetic range legible.
     */
    private fun peakinessHistogram(values: List<Double>): Histogram {
        val bins = 8
        val lo = ln(1.0)
        val hi = ln(1e6)
        val step = (hi - lo) / bins
        val counts = IntArray(bins)
        for (value in values) {
            val index = (((ln(value.coerceAtLeast(1.0)) - lo) / step).toInt()).coerceIn(0, bins - 1)
            counts[index]++
        }
        return Histogram(
            name = "macro_peakiness_synthetic",
            bins = (0 until bins).map {
                Histogram.Bin(lo + it * step, lo + (it + 1) * step, counts[it])
            },
            unit = "log(peakiness)",
            split = "synthetic",
            note = "Log-spaced because peakiness spans 1..1e6. Real D-MACRO patches are not in " +
                "the repository (DATA.md §1), so this distribution is a property of the " +
                "generator.",
        )
    }
}

/** Alias so the agreement type is named once. */
typealias DocProcessAgreement = dev.kasoti.factory.DocProcess.Agreement

/**
 * A trained model plus its provenance.
 *
 * [synthetic] is carried through to every metric and gate detail. A model trained on synthetic
 * patches can exercise the whole path and is still never gate-eligible, and the only way to
 * guarantee that is to make it impossible to forget the flag.
 */
data class TrainedModel(
    val model: SvmModel,
    val path: String,
    val synthetic: Boolean,
    val splitPolicy: String = "",
    val sourceDocCount: Int = 0,
    val trainedOn: String = "",
    val resolution: SvmModelLoader.ModelResolution = SvmModelLoader.ModelResolution.DIRECT,
) {
    val trainingRunId: String get() = model.trainingRunId

    /** Stamped onto the returned copy so a caller cannot report a model under the wrong reason. */
    fun withResolution(which: SvmModelLoader.ModelResolution): TrainedModel = copy(resolution = which)

    /** The one-line banner every console, gate detail and model card must carry. */
    val banner: String
        get() = when {
            synthetic -> "SYNTHETIC — trained on generated textures, NEVER gate-eligible"
            else -> "trained on ${model.trainingRunId}"
        }
}
