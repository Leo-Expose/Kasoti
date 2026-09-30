package dev.kasoti.diary

import dev.kasoti.fusion.DiaryHit
import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * R-ALIAS-01: a strong face match whose *claimed* identity does not agree with the diary.
 *
 * The two arms are the reason this is not "similarity > x". A match above
 * [ThresholdName.T_ALIAS_HI] means "same face, very likely"; it says nothing about whether the
 * person claimed the same name or the same date of birth. Those contradictions are what turn a
 * match into an alias:
 *  - same face, different `nameSha` → two identities for one face (a reused document, or a
 *    genuine duplicate enrolment);
 *  - same face, different `dob` → most often a data-entry error on one side, which is exactly
 *    why it must reach a human instead of being auto-cleared.
 *
 * **A flag is a RED *candidate*, never a silent RED.** The [Finding] carries
 * [Severity.RED] because fusion ranks it against the other REDs, but
 * [requiresSupervisorConfirmation] is unconditionally `true` and the UI is expected to gate on
 * it: DESIGN.md §5 says an alias needs supervisor confirmation, and a rule that can emit an
 * accusation the officer never confirmed is a rule that will eventually accuse the wrong person
 * with total confidence.
 *
 * `nameSha` comparison is exact-hex, and deliberately so: both sides are the same salted hash
 * over the same normalised name, so anything less than equality means different normalised
 * names, and "close enough" here would be a guess about someone's identity.
 */
data class AliasFlag(
    val eventId: String,
    val similarity: Float,
    val nameDiffers: Boolean,
    val dobDiffers: Boolean,
    /** Every event id that contributed; today that is just [eventId], kept for evidence parity. */
    val eventIds: List<String>,
    /** The typed finding the UI resolves. Carries R_ALIAS-01, never a raw string. */
    val finding: Finding,
) {
    val requiresSupervisorConfirmation: Boolean get() = true
}

/**
 * @param claimedDob `null` when the deployment hashes DOB (SYNC.md §3 allows it). The dob arm
 *   is then skipped rather than treated as "matches everything" — an unevaluable check must
 *   never be counted as a passing one.
 * @param maxFlags bound on what one evaluation may emit. Unbounded output is how a UI ends up
 *   rendering four hundred identical alias cards instead of the three that matter; the count
 *   is a registry value so the choice is reviewable rather than a constant in code.
 */
fun aliasRule(
    hits: List<DiaryHit>,
    claimedNameSha: String,
    claimedDob: String?,
    minSimilarity: Float,
    maxFlags: Int,
): List<AliasFlag> {
    val flags = mutableListOf<AliasFlag>()
    for (hit in hits) {
        if (hit.similarity < minSimilarity) continue
        val nameDiffers = hit.nameHash != claimedNameSha
        val dobDiffers = claimedDob != null && hit.dob != null && hit.dob != claimedDob
        if (!nameDiffers && !dobDiffers) continue
        val reason = when {
            nameDiffers && dobDiffers -> "both name and dob"
            nameDiffers -> "name"
            else -> "dob"
        }
        flags += AliasFlag(
            eventId = hit.eventId,
            similarity = hit.similarity,
            nameDiffers = nameDiffers,
            dobDiffers = dobDiffers,
            eventIds = listOf(hit.eventId),
            finding = Finding(
                code = FindingCode.R_ALIAS_01,
                severity = Severity.RED,
                evidenceRef = hit.eventId,
                message = "face match ${hit.similarity} contradicts claimed $reason; supervisor confirmation required",
            ),
        )
    }
    // Strongest first, id as the tie-break so two runs on the same diary agree exactly.
    flags.sortWith(compareByDescending<AliasFlag> { it.similarity }.thenBy { it.eventId })
    return flags.take(maxFlags)
}

/** Registry-driven overload — the form production code should call. */
fun aliasRule(
    hits: List<DiaryHit>,
    claimedNameSha: String,
    claimedDob: String?,
    reg: ThresholdRegistry,
): List<AliasFlag> = aliasRule(
    hits = hits,
    claimedNameSha = claimedNameSha,
    claimedDob = claimedDob,
    minSimilarity = reg[ThresholdName.T_ALIAS_HI].toFloat(),
    maxFlags = reg[ThresholdName.ALIAS_MAX_FLAGS].toInt(),
)
