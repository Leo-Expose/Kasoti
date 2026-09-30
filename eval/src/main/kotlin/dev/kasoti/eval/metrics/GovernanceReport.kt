package dev.kasoti.eval.metrics

import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.DeviceInfo
import dev.kasoti.eval.run.FailureCase
import dev.kasoti.eval.run.OperatingPoint
import dev.kasoti.eval.run.RunStatus
import dev.kasoti.eval.run.SuiteReport
import dev.kasoti.eval.run.ThresholdProvenance
import dev.kasoti.eval.split.SplitVerdict
import kotlinx.serialization.Serializable

/**
 * The run *governance* half of `metrics.json` — everything about the run that is not a
 * measurement.
 *
 * Split discipline (EVAL.md §8), calibration (EVAL.md §7), which suites ran and which were
 * skipped, and the operating points with their provenance all describe whether a number may
 * be *believed*, not what the number is. `dev.kasoti.evalmetrics.MetricSink` models
 * measurements and deliberately does not model these, so they are serialised as a sibling
 * object rather than squeezed into the sink's counter/measure/bucket maps where a gate
 * status would sit next to a rate and invite exactly the comparison the split check exists
 * to forbid.
 *
 * The split is not cosmetic: it is what lets a reader answer "is this run citable?" from
 * one file, without reading the code and without trusting the console output.
 */
@Serializable
data class GovernanceReport(
    val status: RunStatus,
    val exitCode: Int,
    /** The three EVAL.md §8 facts, restated so the file is self-contained. */
    val splitDiscipline: SplitGovernance,
    val calibration: CalibrationGovernance,
    val suites: List<SuiteReport>,
    val gates: List<GateGovernance>,
    val operatingPoints: List<OperatingPoint>,
    val thresholdProvenance: List<ThresholdProvenance>,
    val failures: List<FailureCase>,
    val specConflicts: List<String>,
    val notes: List<String>,
    val hosts: List<DeviceInfo>,
    val host: String,
    val jvm: String,
    val commitDirty: Boolean,
)

@Serializable
data class SplitGovernance(
    val valid: Boolean,
    val summary: String,
    val checked: List<String>,
    val untuned: List<String>,
    val reportSplitTuned: List<String>,
    val unrecorded: List<String>,
    val manifestProblems: List<ManifestProblemGovernance>,
) {
    companion object {
        fun of(verdict: SplitVerdict) = SplitGovernance(
            valid = verdict.isValid,
            summary = verdict.summary,
            checked = verdict.checked,
            untuned = verdict.untuned,
            reportSplitTuned = verdict.reportSplitTuned,
            unrecorded = verdict.unrecorded,
            manifestProblems = verdict.manifestProblems.map {
                ManifestProblemGovernance(it.severity.name, it.code, it.subject, it.detail)
            },
        )
    }
}

@Serializable
data class ManifestProblemGovernance(
    val severity: String,
    val code: String,
    val subject: String,
    val detail: String,
)

@Serializable
data class CalibrationGovernance(
    val deviceId: String,
    val present: Boolean,
    val accepted: Boolean,
    val ageDays: Double? = null,
    val reason: String,
    val source: String,
) {
    companion object {
        fun of(verdict: CalibrationVerdict) = CalibrationGovernance(
            deviceId = verdict.deviceId,
            present = verdict.present,
            accepted = verdict.accepted,
            ageDays = verdict.ageDays,
            reason = verdict.reason,
            source = verdict.source,
        )
    }
}

/**
 * A gate as governance.
 *
 * [status] is a five-state enum rather than a boolean because the states are not
 * interchangeable in a report: PASS, FAIL, SKIPPED and INVALID mean four different things to
 * a reader, and the whole reason this harness has an exit code for "incomplete" is that
 * SKIPPED must not be rendered as PASS.
 */
@Serializable
data class GateGovernance(
    val id: String,
    val name: String,
    val status: GateStatus,
    val bar: String,
    val observed: String,
    val detail: String,
    val evidenceRef: String,
) {
    companion object {
        fun of(gate: GateResult) = GateGovernance(
            id = gate.id,
            name = gate.name,
            status = gate.status,
            bar = gate.bar,
            observed = gate.observed,
            detail = gate.detail,
            evidenceRef = gate.evidenceRef,
        )
    }
}
