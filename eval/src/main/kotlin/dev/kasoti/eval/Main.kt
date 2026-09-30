package dev.kasoti.eval

import kotlin.system.exitProcess

/**
 * Entry point for `:eval run` (EVAL.md §3).
 *
 * Exit codes are part of the contract — CI gates on them (AGENTS.md §1), and a gate that
 * cannot fail the build is not a gate:
 *
 * | code | meaning |
 * |---|---|
 * | 0 | every selected suite ran and every gate passed |
 * | 1 | harness usage or internal error (bad args, unreadable manifest) |
 * | 2 | a metric gate FAILED |
 * | 3 | the run is INVALID (EVAL.md §8 split discipline violated) |
 * | 4 | the run is INCOMPLETE (a required suite was skipped: no device, no calibration, no data) |
 * | 70 | internal error |
 */
fun main(args: Array<String>) {
    exitProcess(EvalRunner().run(args))
}
