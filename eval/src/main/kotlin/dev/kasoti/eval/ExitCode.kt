package dev.kasoti.eval

/**
 * Process exit codes. CI gates on these (AGENTS.md §1), so the distinctions matter:
 * a *failed metric* and an *invalid run* are different problems with different fixes, and
 * collapsing them into "1" would hide a split-discipline violation behind what looks like
 * an ordinary regression.
 */
enum class ExitCode(val code: Int, val meaning: String) {
    /** Every selected suite ran and every gate passed. */
    OK(0, "all gates passed"),

    /** Harness usage or environment error: bad arguments, unreadable manifest, missing tool. */
    USAGE(1, "harness usage or environment error"),

    /** A metric gate failed — the numbers are wrong, not the run. */
    GATE_FAILED(2, "a metric gate failed"),

    /**
     * EVAL.md §8: a threshold was selected on the report split, so the run is not evidence.
     * Deliberately distinct from [GATE_FAILED] — retrying will not fix it.
     */
    INVALID_SPLIT(3, "run INVALID: threshold tuned on the report split (EVAL.md §8)"),

    /** A required suite was skipped: no device, no calibration, no dataset present. */
    INCOMPLETE(4, "run INCOMPLETE: a required suite could not run"),

    /** Unexpected internal failure. */
    INTERNAL(70, "internal harness error"),
    ;

    val isPass: Boolean get() = this == OK
}
