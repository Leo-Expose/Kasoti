package dev.kasoti.threshold

/**
 * A versioned, immutable set of thresholds.
 *
 * [version] is embedded in every decision record (invariant I3), so a verdict can always be
 * reproduced against the operating point that produced it. The operating points themselves come
 * from `fusion/thresholds.v1.json`; this class is the frozen set a single evaluation runs on,
 * and the thing [canonical] is computed over.
 *
 * The split is deliberate. The registry is the *policy* (which operating points exist, what
 * their defaults are, how far they may move). This is the *run* (which values this particular
 * evaluation froze, and under which registry version). Collapsing them would mean a verdict
 * record could not name the policy it was decided under, which is the whole reason I3 exists.
 *
 * There is no "load what is present" behaviour: [get] throws on an absent name rather than
 * substituting a default, so a threshold nobody declared is a loud failure rather than a quiet
 * 0.0 that reads as "nothing detected" (AGENTS.md §4, no silent default-pass).
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
        /**
         * The shipped defaults, frozen at the registry's own version.
         *
         * The version is taken from the parsed document rather than hardcoded so that a second
         * registry version cannot be loaded under a record claiming to be `v1`: the two would
         * then disagree in the one field a reader uses to reproduce a verdict.
         */
        fun defaults(
            version: String = THRESHOLD_SPEC.version,
            runId: String = "bootstrap",
        ): ThresholdRegistry = ThresholdRegistry(
            version = version,
            values = ThresholdName.entries.associateWith { it.default },
            runId = runId,
            frozenAt = null,
        )
    }
}
