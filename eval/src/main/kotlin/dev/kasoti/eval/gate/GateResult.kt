package dev.kasoti.eval.gate

import kotlinx.serialization.Serializable

/**
 * Outcome of a single gate (SPEC.md §7 ship-blockers, EVAL.md §4 metric definitions).
 *
 * A gate has exactly three terminal states and no others, because the failure mode this
 * harness exists to prevent is a gate quietly resolving to something that reads like a
 * pass. [SKIPPED] is spelled out as its own state precisely so that "we could not measure
 * this" can never be rendered as "we measured this and it was fine".
 */
@Serializable
enum class GateStatus {
    /** Measured, and it met the bar. */
    PASS,

    /** Measured, and it missed the bar. Fails the run. */
    FAIL,

    /**
     * Not measured: no device, no calibration, no dataset, or an upstream gate already
     * failed. A run containing a [SKIPPED] gate is INCOMPLETE, never PASS.
     */
    SKIPPED,

    /**
     * Measured, but the measurement is not admissible: the threshold that produced it was
     * selected on the report split (EVAL.md §8). Distinct from [FAIL] because the fix is
     * to re-tune, not to change the number.
     */
    INVALID,
    ;

    val isPass: Boolean get() = this == PASS
}

/**
 * One gate result.
 *
 * [bar] is the *stated* acceptance criterion from SPEC.md §7 / EVAL.md §4 so a reader can
 * check the bar without opening the doc. [detail] carries the numbers. Both belong in
 * `metrics.json`; neither is a substitute for the run-id.
 */
@Serializable
data class GateResult(
    val id: String,
    val name: String,
    val status: GateStatus,
    val bar: String,
    val observed: String,
    val detail: String = "",
    /** Stable pointer to the artefact backing this gate: a fixture file, a case id, a table. */
    val evidenceRef: String = "",
) {
    val isBlocking: Boolean get() = status == GateStatus.FAIL || status == GateStatus.INVALID
}

/** Aggregate gate outcome for one suite. */
data class SuiteGates(
    val suite: String,
    val results: List<GateResult>,
    /** Present when the suite could not run at all; the reason is shown, never swallowed. */
    val skipReason: String? = null,
) {
    val blocking: List<GateResult> get() = results.filter { it.isBlocking }
    val skipped: List<GateResult> get() = results.filter { it.status == GateStatus.SKIPPED }
    val hasSkip: Boolean get() = skipReason != null || skipped.isNotEmpty()
}
