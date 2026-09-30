package dev.kasoti.eval.suites

import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.device.AttachedDevice
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.TableData
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.abs

/**
 * Android-vs-desktop parity (EVAL.md §4, §3).
 *
 * "Same-input verdict agreement 100% on D-SCEN + sampled D-FACE, tolerance ±1e-3."
 *
 * This suite **skips loudly and explicitly** when no device is present. That is the single
 * most important behaviour in the file. A parity suite that passes because it found nothing
 * to compare is worse than no parity suite at all: the deck would say "Android and desktop
 * agree" and the sentence would be fabricated. So the no-device path produces a SKIPPED
 * gate, an explicit skip reason, an INCOMPLETE run and a non-zero exit code — never a pass.
 *
 * The comparison itself needs a desktop run of the same inputs, which the app produces
 * (`eval/runs/<id>/desktop_verdicts.jsonl`) and the device reproduces
 * (`eval/runs/<id>/device_verdicts.jsonl`). The harness reads both and compares. If either
 * file is missing that is also a loud skip, for the same reason.
 */
object ParitySuite {

    const val GATE_AGREEMENT = "P-GATE-01"

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val tables: List<TableData>,
        val notes: List<String>,
    )

    /** One case's verdict, as each platform computed it. */
    data class Verdict(
        val caseId: String,
        val dataset: String,
        val verdict: String,
        val findingCodes: List<String>,
        val similarity: Double,
    )

    fun run(
        devices: List<AttachedDevice>,
        registry: ThresholdRegistry,
        calibration: CalibrationVerdict,
        desktopVerdicts: List<Verdict>?,
        deviceVerdicts: List<Verdict>?,
    ): Result {
        val notes = mutableListOf<String>()

        if (devices.isEmpty()) {
            return skipped(
                reason = "no Android device is attached (or adb is not on PATH). EVAL.md §3 " +
                    "requires a device *and* a desktop run of the same inputs for the parity " +
                    "suite; without a device there is nothing to compare and this suite is " +
                    "SKIPPED, not passed.",
                notes = notes + "Parity SKIPPED — no device. This is a hole in the evidence, not a pass.",
            )
        }

        val device = devices.first()
        if (!calibration.accepted) {
            return skipped(
                reason = "device '${device.serial}' is attached but uncalibrated, so its " +
                    "verdicts are not comparable with the desktop's: ${calibration.reason}",
                notes = notes + "Parity SKIPPED — attached device is uncalibrated.",
            )
        }

        if (desktopVerdicts.isNullOrEmpty()) {
            return skipped(
                reason = "device '${device.serial}' (${device.model}, ${device.role}) is " +
                    "attached, but no desktop verdict file was found. Parity compares two " +
                    "runs; with only one there is nothing to compare, and the suite is SKIPPED.",
                notes = notes + "Parity SKIPPED — no desktop verdicts to compare against.",
            )
        }
        if (deviceVerdicts.isNullOrEmpty()) {
            return skipped(
                reason = "device '${device.serial}' is attached, but no device verdict file was " +
                    "found. The orchestrated script (scripts/parity_run.sh) writes both; with " +
                    "only the desktop side present the suite is SKIPPED.",
                notes = notes + "Parity SKIPPED — no device verdicts to compare against.",
            )
        }

        val desktopByCase = desktopVerdicts.associateBy { it.caseId }
        val deviceByCase = deviceVerdicts.associateBy { it.caseId }
        val common = desktopByCase.keys.filter { it in deviceByCase }.sorted()

        if (common.isEmpty()) {
            return skipped(
                reason = "the desktop and device verdict files share no case ids " +
                    "(${desktopByCase.size} desktop, ${deviceByCase.size} device). " +
                    "Comparing different inputs would agree trivially, so the suite is SKIPPED.",
                notes = notes + "Parity SKIPPED — no shared case ids between the two runs.",
            )
        }

        val mismatchedVerdict = mutableListOf<String>()
        val mismatchedFindings = mutableListOf<String>()
        var maxSimDelta = 0.0
        val rows = mutableListOf<List<Double>>()

        for (caseId in common) {
            val d = desktopByCase.getValue(caseId)
            val a = deviceByCase.getValue(caseId)
            if (d.verdict != a.verdict) mismatchedVerdict += "$caseId: desktop=${d.verdict} device=${a.verdict}"
            if (d.findingCodes.sorted() != a.findingCodes.sorted()) {
                mismatchedFindings += "$caseId: desktop=${d.findingCodes.sorted()} device=${a.findingCodes.sorted()}"
            }
            val delta = abs(d.similarity - a.similarity)
            if (delta > maxSimDelta) maxSimDelta = delta
            rows += listOf(delta)
        }

        val agreementPct = (common.size - mismatchedVerdict.size).toDouble() / common.size
        val withinTolerance = maxSimDelta <= TOLERANCE
        val pass = mismatchedVerdict.isEmpty() && mismatchedFindings.isEmpty() && withinTolerance

        val gate = GateResult(
            id = GATE_AGREEMENT,
            name = "Android-vs-desktop verdict agreement",
            status = if (pass) GateStatus.PASS else GateStatus.FAIL,
            bar = "100% agreement, similarity within ±$TOLERANCE (EVAL.md §4)",
            observed = "%.2f%% verdict agreement over %d cases, max |Δsimilarity| = %.2e"
                .format(agreementPct * 100, common.size, maxSimDelta),
            detail = "Device ${device.serial} (${device.model}, ${device.role}, fingerprint " +
                "${device.buildFingerprint.ifEmpty { "unrecorded" }}). " +
                "Cases only in one run are excluded and counted in the note below.",
            evidenceRef = device.serial,
        )

        val gates = listOf(gate)
        val metrics = listOf(
            Metric("parity.cases_compared", common.size.toDouble(), "cases", "held-out", GATE_AGREEMENT),
            Metric("parity.verdict_agreement_pct", agreementPct * 100.0, "%", "held-out", GATE_AGREEMENT),
            Metric("parity.finding_agreement_pct",
                (common.size - mismatchedFindings.size).toDouble() / common.size * 100.0,
                "%", "held-out", GATE_AGREEMENT),
            Metric("parity.max_similarity_delta", maxSimDelta, "cosine", "held-out", GATE_AGREEMENT,
                "tolerance ±$TOLERANCE"),
        )

        val table = TableData(
            name = "Parity per-case deltas",
            rowLabels = common,
            columnLabels = listOf("|Δ similarity|"),
            cells = rows,
            note = "Desktop vs ${device.model}. Cases present in only one of the two runs are " +
                "not compared and not counted as agreement.",
        )

        notes += "Parity device: ${device.serial} — ${device.model}, Android " +
            "${device.androidVersion} (SDK ${device.sdkInt}), ${device.role}."
        notes += "Parity compared ${common.size} of " +
            "${desktopByCase.size + deviceByCase.size - common.size} case ids across the two runs."

        return Result(gates, metrics, listOf(table), notes)
    }

    private fun skipped(reason: String, notes: List<String>) = Result(
        gates = listOf(
            GateResult(
                id = GATE_AGREEMENT,
                name = "Android-vs-desktop verdict agreement",
                status = GateStatus.SKIPPED,
                bar = "100% agreement on identical inputs (EVAL.md §4)",
                observed = "SKIPPED — not measured",
                detail = reason,
                evidenceRef = "",
            ),
        ),
        metrics = listOf(
            Metric("parity.cases_compared", 0.0, "cases", "held-out", GATE_AGREEMENT, "suite skipped"),
            Metric("parity.verdict_agreement_pct", null, "%", "held-out", GATE_AGREEMENT,
                "NOT MEASURED — do not read as 0% or as 100%"),
        ),
        tables = emptyList(),
        notes = notes,
    )

    /** EVAL.md §4: "tolerance: sim ±1e-3". */
    const val TOLERANCE: Double = 1e-3
}
