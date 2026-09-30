package dev.kasoti.threshold

/**
 * Every tunable in the system, addressed by name (FR-R3, AGENTS.md §2).
 *
 * Code may not contain a bare numeric literal where one of these belongs. A UI or platform
 * module that needs a threshold reads it from the registry, so changing an operating point
 * is a versioned file change with a review trail rather than a sneaky edit.
 */
enum class ThresholdName(
    val unit: String,
    val tuningDataRef: String,
    val owner: String,
    val default: Double,
    /** Inclusive lower bound an operating point may never cross without lead sign-off. */
    val floor: Double,
    val ceiling: Double,
) {
    T_FACE_RED("cosine", "D-FACE report split", "vision", 0.35, 0.20, 0.60),
    T_FACE_GREEN("cosine", "D-FACE report split", "vision", 0.55, 0.35, 0.80),
    T_ALIAS_HI("cosine", "D-FACE + D-SCEN", "vision", 0.80, 0.60, 0.95),
    T_WL("cosine", "D-SCEN", "vision", 0.75, 0.60, 0.95),
    DELTA_MARGIN("cosine", "D-SCEN", "vision", 0.08, 0.02, 0.30),
    MACRO_MARGIN_RED("svm-margin", "D-MACRO held-out", "vision", 0.35, 0.15, 1.0),
    MACRO_MARGIN_AMBER("svm-margin", "D-MACRO held-out", "vision", 0.15, 0.05, 0.60),
    Q_BLUR("laplacian-var", "D-USAB", "vision", 100.0, 30.0, 400.0),
    Q_GLARE("ratio", "D-USAB", "vision", 0.12, 0.03, 0.40),
    Q_BRIGHT_MIN("mean", "D-USAB", "vision", 40.0, 15.0, 90.0),
    Q_BRIGHT_MAX("mean", "D-USAB", "vision", 225.0, 180.0, 250.0),
    Q_POSE_YAW("degrees", "D-USAB", "vision", 25.0, 10.0, 45.0),
    Q_POSE_PITCH("degrees", "D-USAB", "vision", 20.0, 8.0, 40.0),
    LIVE_PASSIVE_MIN("score", "D-SPOOF", "vision", 0.50, 0.20, 0.90),
    QR_DRIFT_TOLERANCE("agreement", "D-QR", "vision", 0.15, 0.05, 0.50),
    VMAX("km/h", "fixed by policy", "lead", 120.0, 60.0, 400.0),
    RECHECK_P("probability", "ops default", "lead", 0.05, 0.01, 0.25),
    REVERIFY_DAYS("days", "ops default", "lead", 30.0, 7.0, 180.0),
    FACILITATOR_MIN_GROUPS("count", "D-SCEN", "vision", 3.0, 2.0, 10.0),
    FACILITATOR_WINDOW_DAYS("days", "D-SCEN", "vision", 30.0, 7.0, 180.0),
    GREY_STREAK_LIMIT("count", "D-USAB", "lead", 3.0, 2.0, 6.0),
    VIZ_DRIFT_TOLERANCE("ratio", "D-SCEN", "vision", 0.15, 0.05, 0.50),

    // --- diary + sync tuning points added by the diary/sync work (owner: @vision, @lead) ---
    // Alias/travel/facilitator and transport bounds. Each is a reviewable policy choice: how
    // many flags one evaluation may emit, how wide a cohort window is, how big a QR may get.
    ALIAS_MAX_FLAGS("count", "D-SCEN", "vision", 3.0, 1.0, 20.0),
    TRAVEL_MAX_FLAGS("count", "D-SCEN", "vision", 5.0, 1.0, 50.0),
    FACILITATOR_COOCCUR_HOURS("hours", "D-SCEN", "vision", 12.0, 1.0, 72.0),
    FACILITATOR_GROUP_PREFIX_HEX("hex-chars", "D-SCEN", "vision", 8.0, 4.0, 32.0),
    FACILITATOR_MAX_PARTNERS("count", "D-SCEN", "vision", 512.0, 16.0, 8192.0),
    FACILITATOR_MIN_ANCHOR_EVENTS("count", "D-SCEN", "vision", 2.0, 2.0, 10.0),
    FACILITATOR_MIN_COOCCURRENCES("count", "D-SCEN", "vision", 2.0, 2.0, 10.0),
    FACILITATOR_MAX_EVIDENCE("count", "D-SCEN", "vision", 32.0, 4.0, 512.0),
    FACILITATOR_MAX_FLAGS_PER_EVAL("count", "D-SCEN", "vision", 16.0, 1.0, 256.0),
    DIARY_TOP_K("count", "D-SCEN", "vision", 5.0, 1.0, 50.0),

    // SYNC.md §2: clock skew beyond 5 minutes is a warning, not a rejection.
    CLOCK_SKEW_MAX_MIN("minutes", "ops default", "lead", 5.0, 1.0, 60.0),

    // SYNC.md §5 transport budgets. QR_PACKET_MAX_BYTES is the QR-version-40-L byte-mode
    // capacity (2953); SYNC_FILE_SPLIT_BYTES is the 5 MB file split.
    QR_PACKET_MAX_BYTES("bytes", "SYNC.md §5 QR budget", "lead", 2953.0, 1024.0, 4096.0),
    SYNC_FILE_SPLIT_BYTES("bytes", "SYNC.md §5 file split", "lead", 5_242_880.0, 65_536.0, 104_857_600.0),
    WATCHLIST_LABEL_MAX_CHARS("chars", "SYNC.md §5 QR budget", "lead", 64.0, 8.0, 256.0),
    ;

    init {
        require(default in floor..ceiling) {
            "threshold $name default $default outside [$floor, $ceiling]"
        }
    }
}

/**
 * A versioned, immutable set of thresholds.
 *
 * [version] is embedded in every decision record (invariant I3), so a verdict can always be
 * reproduced against the operating point that produced it. [contentHash] is what the audit
 * chain and the "was this registry tampered with" check compare against.
 */
data class ThresholdRegistry(
    val version: String,
    val values: Map<ThresholdName, Double>,
    val runId: String,
    val frozenAt: String?,
) {
    operator fun get(name: ThresholdName): Double =
        values[name] ?: error("threshold $name is missing from registry $version")

    fun withValue(name: ThresholdName, value: Double): ThresholdRegistry {
        require(value in name.floor..name.ceiling) {
            "threshold $name = $value is outside its policy range [${name.floor}, ${name.ceiling}]"
        }
        return copy(values = values + (name to value))
    }

    /** Canonical serialisation — sorted keys, no whitespace (mirrors SYNC.md §2). */
    fun canonical(): String = buildString {
        append("{\"runId\":\"").append(runId).append("\",\"v\":\"").append(version).append("\",\"values\":{")
        append(values.toSortedMap().entries.joinToString(",") { (k, v) -> "\"${k.name}\":$v" })
        append("}}")
    }

    companion object {
        /** The shipped defaults, frozen at `v1`. */
        fun defaults(version: String = "v1", runId: String = "bootstrap"): ThresholdRegistry =
            ThresholdRegistry(
                version = version,
                values = ThresholdName.entries.associateWith { it.default },
                runId = runId,
                frozenAt = null,
            )
    }
}
