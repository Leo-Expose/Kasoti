package dev.kasoti.eval.run

import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.split.SplitVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema version for `metrics.json`.
 *
 * Bumped whenever a field changes meaning. Downstream consumers (slides, the deck build)
 * key off this string; an unversioned metrics file is not citable evidence (EVAL.md §1).
 */
const val METRICS_SCHEMA_VERSION: String = "kasoti.eval.metrics/v1"

/** Terminal status of a whole run. Maps 1:1 onto [dev.kasoti.eval.ExitCode]. */
@Serializable
enum class RunStatus {
    @SerialName("PASS")
    PASS,

    /** A gate failed. The numbers are wrong. */
    @SerialName("GATE_FAILED")
    GATE_FAILED,

    /**
     * EVAL.md §8 violated: a threshold in use was selected on the report split, so the run
     * is not evidence for anything. Never rendered as a near-miss.
     */
    @SerialName("INVALID")
    INVALID,

    /** A required suite was skipped. Not a pass, not a failure: a hole. */
    @SerialName("INCOMPLETE")
    INCOMPLETE,

    @SerialName("ERROR")
    ERROR,
}

/** One measured number, with the split it was measured on attached (EVAL.md §8). */
@Serializable
data class Metric(
    val name: String,
    val value: Double? = null,
    val unit: String = "",
    /** `train` / `tune` / `report` / `held-out` / `all-test` / `synthetic` / `host`. */
    val split: String? = null,
    val gateId: String? = null,
    val note: String = "",
)

/** A rectangular table: confusion matrices, per-class tables, per-bucket tables. */
@Serializable
data class TableData(
    val name: String,
    val rowLabels: List<String>,
    val columnLabels: List<String>,
    val cells: List<List<Double>>,
    val note: String = "",
)

/** A binned distribution, rendered to `histograms/<name>.csv` and `.svg`. */
@Serializable
data class Histogram(
    val name: String,
    val bins: List<Bin>,
    val unit: String = "",
    val split: String? = null,
    val note: String = "",
) {
    @Serializable
    data class Bin(
        val lowerInclusive: Double,
        val upperExclusive: Double,
        val count: Int,
    )
}

/** A case that missed, for the deck's failure gallery (EVAL.md §3). */
@Serializable
data class FailureCase(
    val suite: String,
    val caseId: String,
    val reason: String,
    /** What should have happened, so a reader can tell an expected from an unexpected miss. */
    val expected: String,
    val observed: String,
    val artefactRef: String = "",
    val gateId: String? = null,
)

/**
 * A threshold decision, stated explicitly.
 *
 * [provisional] is the field that stops an un-tuned default from being quoted as a tuned
 * operating point: FUSION.md §5 says the shipped values are "TBD by tuning", and a metric
 * that depends on one must say so.
 */
@Serializable
data class OperatingPoint(
    val threshold: String,
    val value: Double,
    val unit: String,
    val selectedOnSplit: String,
    val provisional: Boolean,
    val rationale: String,
    val floor: Double,
    val ceiling: Double,
)

/** Provenance of a threshold: which split chose it, in which run, under whose name. */
@Serializable
data class ThresholdProvenance(
    val name: String,
    val dataset: String,
    /** `tune` | `report` | `policy` | `untuned` | `unknown` */
    val selectedOnSplit: String,
    val runId: String,
    val selectedAtUtc: String,
    val owner: String,
    val note: String = "",
) {
    val isUntuned: Boolean get() = selectedOnSplit == UNTUNED
    val isReportSplit: Boolean get() = selectedOnSplit == REPORT

    companion object {
        const val TUNE = "tune"
        const val REPORT = "report"
        const val POLICY = "policy"
        const val UNTUNED = "untuned"
        const val UNKNOWN = "unknown"
    }
}

/** Per-suite result, including the reason a suite did not run. */
@Serializable
data class SuiteReport(
    val suite: String,
    val ran: Boolean,
    val skipReason: String? = null,
    val gateIds: List<String> = emptyList(),
    val durationMs: Long = 0,
    val notes: List<String> = emptyList(),
)

@Serializable
data class DeviceInfo(
    val id: String,
    val model: String,
    val osVersion: String = "",
    val buildFingerprint: String = "",
    val role: String = "",
)

/**
 * The whole run. Serialised verbatim to `eval/runs/<run-id>/metrics.json`.
 *
 * Nothing here is computed by the evaluator agent or read back from a previous run: every
 * number in this object is produced during this process, from `:core`, with the split
 * recorded alongside it.
 */
@Serializable
data class RunRecord(
    val schemaVersion: String = METRICS_SCHEMA_VERSION,
    val runId: String,
    val suite: String,
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val commit: String,
    val commitDirty: Boolean,
    val host: String,
    val jvm: String,
    val devices: List<DeviceInfo>,
    val thresholdsInUse: List<String>,
    val thresholdLedgerVersion: String,
    val thresholdProvenance: List<ThresholdProvenance>,
    val splitDiscipline: SplitVerdict,
    val calibration: CalibrationVerdict,
    val suites: List<SuiteReport>,
    val gates: List<GateResult>,
    val metrics: List<Metric>,
    val tables: List<TableData>,
    val histograms: List<Histogram>,
    val failures: List<FailureCase>,
    val operatingPoints: List<OperatingPoint>,
    /** Adjudicated conflicts in the spec set that the harness refuses to resolve silently. */
    val specConflicts: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val status: RunStatus,
    val exitCode: Int,
) {
    val headline: Metric?
        get() = metrics.firstOrNull { it.name == HEADLINE_MRZ_MUTATE_CATCH }

    companion object {
        /** The one number the deck leads with (SPEC.md §7). */
        const val HEADLINE_MRZ_MUTATE_CATCH = "mrz.mutate_catch.detectable_pct"
    }
}
