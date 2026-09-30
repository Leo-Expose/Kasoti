package dev.kasoti.fusion

/**
 * A verification layer, named so that "we looked and it was fine" stays separable from
 * "we never got to look" (FUSION.md §7).
 *
 * The distinction is the whole reason [LayerStatus] has four values and not a boolean: a
 * chip that is absent because the document has no chip, absent because the build has no NFC
 * reader, and absent because the tap failed are three different operational stories, and
 * only one of them is a defect.
 */
enum class Layer {
    QUALITY,
    MATH,
    SIGNED_QR,
    CHIP,
    MACRO,
    UV,
    FACE,
    DIARY,
}

enum class LayerStatus {
    /** The layer ran and returned a usable result. */
    PRESENT,

    /** The layer applies to this document but produced nothing. */
    ABSENT,

    /** The layer cannot apply to this document family, or this build has no reader for it. */
    UNSUPPORTED,

    /** A result exists but cannot be trusted: a stub reader, a partial parse, a failed gate. */
    UNKNOWN,
}

/** How much the verdict leans on a layer for a given track (FUSION.md §7). */
enum class Bearing {
    /** A verdict is not defensible without it. */
    LOAD_BEARING,

    /** Helps when present; its absence is not by itself a problem. */
    AUXILIARY,

    /** Does not exist for this document family. */
    NOT_APPLICABLE,
}

/**
 * Which layers ran, which were load-bearing, and which are therefore unresolved.
 *
 * [unresolved] is the machine-readable form of the fail-closed rule (AGENTS.md §4): a
 * load-bearing layer that did not clear makes `GREEN` unreachable, no matter how good the
 * layers that *did* run look.
 */
data class LayerPresence(
    val track: Track,
    val bearing: Map<Layer, Bearing>,
    val status: Map<Layer, LayerStatus>,
) {
    init {
        require(bearing.keys == status.keys) { "every layer needs both a bearing and a status" }
    }

    fun bearingOf(layer: Layer): Bearing = bearing.getValue(layer)

    fun statusOf(layer: Layer): LayerStatus = status.getValue(layer)

    fun isClear(layer: Layer): Boolean = statusOf(layer) == LayerStatus.PRESENT

    /** Load-bearing layers that did not clear. Non-empty means no GREEN. */
    val unresolved: Set<Layer> = bearing.entries
        .filter { it.value == Bearing.LOAD_BEARING && status.getValue(it.key) != LayerStatus.PRESENT }
        .map { it.key }
        .toSet()

    /** `mac/PRESENT`, `face/ABSENT` — the form quoted in findings and reports. */
    fun ref(layer: Layer): String = "${layer.name.lowercase()}/${statusOf(layer).name}"
}

/**
 * The complete, self-describing outcome of a fusion decision.
 *
 * Everything needed to reproduce or challenge the decision travels with it (invariant I3):
 * the threshold version and run id, the fusion rule version, which layers were load-bearing
 * and what state they were in, and — for a GREY that was carrying hard-RED proofs — those
 * proofs in [carriedOver]. A verdict that cannot be re-derived from its own record is not
 * auditable.
 */
data class VerdictReport(
    val verdict: Verdict,
    /** Ordered: first hit, corroborating, then AMBER, then GREY causes, then confirmations. */
    val findings: List<Finding>,
    /** The finding that decided the verdict, or `null` for a clean GREEN. */
    val firstHit: Finding?,
    /**
     * Hard-RED proofs found on a capture that failed the quality gate (FUSION.md §1).
     *
     * These are a subset of [findings] and are *not* the verdict. They exist so the retake
     * note can say "the previous capture already showed this" without the officer reading an
     * accusation off a bad frame.
     */
    val carriedOver: List<Finding>,
    val layers: LayerPresence,
    val rationale: List<String>,
    val thresholdVersion: String,
    val thresholdRunId: String,
    val fusionRuleVersion: String,
    /** A supervisor must look at this case before it is acted on. */
    val supervisorRequired: Boolean,
    val trustFastPath: Boolean,
    /** Carried through verbatim so demo evidence can never be merged into a real diary (I7). */
    val demoMode: Boolean,
) {
    val red: List<Finding> get() = findings.filter { it.severity == Severity.RED }

    val amber: List<Finding> get() = findings.filter { it.severity == Severity.AMBER }

    val greyCauses: List<Finding> get() = findings.filter { it.severity == Severity.GREY }

    val confirmations: List<Finding> get() = findings.filter { it.severity == Severity.INFO }

    val codes: Set<FindingCode> get() = findings.mapTo(LinkedHashSet()) { it.code }

    fun has(code: FindingCode): Boolean = findings.any { it.code == code }

    val requiresSecondary: Boolean get() = verdict.requiresSecondary

    /** One-line policy fingerprint for the audit record. */
    val policyVersions: String
        get() = "thresholds=$thresholdVersion@$thresholdRunId fusion=$fusionRuleVersion"
}
