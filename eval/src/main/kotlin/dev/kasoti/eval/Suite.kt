package dev.kasoti.eval

/**
 * A harness suite (EVAL.md §3).
 *
 * [suites] lists the *required* members in dependency order; [optionalSuites] are run when
 * their preconditions are met and loudly skipped when not. Nothing is silently dropped:
 * a skipped suite is reported with the reason, and a run with a skipped required suite
 * exits [ExitCode.INCOMPLETE] rather than passing.
 */
enum class Suite(
    val id: String,
    val suites: List<String>,
    val optionalSuites: List<String>,
    val approxSeconds: Int,
) {
    /**
     * ~60 s. D-MRZ corpus, D-QR fixtures, D-SCEN diary scripts, unit-parity, latency
     * microbenchmark. Deliberately device-free so it runs on a laptop and in CI.
     */
    SMOKE(
        id = "smoke",
        suites = listOf("mrz", "qr", "diary", "unit-parity", "latency"),
        optionalSuites = emptyList(),
        approxSeconds = 60,
    ),

    /**
     * Everything smoke does, plus per-bucket breakdowns, histograms, the macro suite
     * (calibration-gated, EVAL.md §7) and Android-vs-desktop parity (device-gated).
     */
    FULL(
        id = "full",
        suites = listOf("mrz", "qr", "diary", "unit-parity", "latency", "macro", "face", "histograms"),
        optionalSuites = listOf("parity"),
        approxSeconds = 3600,
    ),

    /**
     * Android-vs-desktop verdict agreement on identical inputs (EVAL.md §4). Needs a
     * physical device and a desktop run of the same inputs; without one it SKIPS LOUDLY.
     */
    PARITY(
        id = "parity",
        suites = listOf("parity"),
        optionalSuites = emptyList(),
        approxSeconds = 600,
    ),
    ;

    val isDeviceGated: Boolean get() = this == PARITY || optionalSuites.contains("parity")
}
