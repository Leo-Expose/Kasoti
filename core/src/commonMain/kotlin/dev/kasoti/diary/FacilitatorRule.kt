package dev.kasoti.diary

import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * A-FAC-01: one subject keeps crossing, alongside the *same* small company each time.
 *
 * **What a "group" is** — the definition the design left open:
 *
 * > A **group** is a cohort of subjects, identified by the first
 * > [ThresholdName.FACILITATOR_GROUP_PREFIX_HEX] hex characters of the subject's `nameSha`
 * > (falling back to `docHash` when a deployment hashes the name differently). Two subjects are
 * > in the same group exactly when their keys are equal.
 *
 * Why hashed and never a name: the diary never holds a name, so a group key that started from
 * one would either break that or leak through a prefix. The key is the first
 * [ThresholdName.FACILITATOR_GROUP_PREFIX_HEX] hex characters of a salted SHA-256 — a
 * *fingerprint*, not a bucket, and not reversible to a name.
 *
 * The prefix length is the single most consequential number in this rule and it was chosen by
 * measurement, not taste. At 4 hex characters (16 bits) a 100k-record diary has ~65k possible
 * keys, so about 44k of them collide by accident and the rule ends up treating most of the
 * population as a "recurring subject" — which both floods the report and makes every merge
 * re-examine the whole diary. At 8 hex characters (32 bits) accidental recurrence is
 * essentially nil, while *genuine* recurrence is untouched: the same person crossing again has
 * the same hash, and that is the only recurrence this rule is supposed to see.
 *
 * A group only *counts* for an anchor once it has co-occurred with that anchor at least
 * [ThresholdName.FACILITATOR_MIN_COOCCURRENCES] times inside the window. This is the part that
 * makes the rule a facilitator rule rather than a footfall counter: at a busy post, every
 * passer-by is technically in the company of hundreds of strangers, and a rule that counted raw
 * company would flag the entire population on its first busy morning. Company that *repeats* is
 * the signature.
 *
 * The **anchor** must itself have crossed at least [ThresholdName.FACILITATOR_MIN_ANCHOR_EVENTS]
 * times in the window — a subject seen once has demonstrated nothing.
 *
 * Co-occurrence is same post within [ThresholdName.FACILITATOR_COOCCUR_HOURS] of absolute clock
 * difference. Deliberately not "same post and same day": a night crossing and the morning
 * crossing of one shift are the same company, and a day bucket would hide the overnight runs that
 * are the pattern. The cost is that two unrelated travellers who happen to share a post and a
 * shift count as one association — which is why this is AMBER, why `minGroups` is a registry
 * value tuned on `D-SCEN`, and why it is red-teamed before anyone trusts it.
 *
 * **Evidence is bounded, and so is the report.** [FacFlag.distinctGroups] is the true count, but
 * the group keys, event ids and devices attached to a flag are capped at
 * [ThresholdName.FACILITATOR_MAX_EVIDENCE], and one evaluation returns at most
 * [ThresholdName.FACILITATOR_MAX_FLAGS_PER_EVAL] flags, strongest first. Both are *reporting*
 * bounds and neither touches a detection threshold: at a single busy gate the cohort list runs to
 * hundreds and a merge could otherwise return thousands of flags, which is not something an
 * officer reads and not something a merge report should carry. Building that unbounded evidence
 * was measured at minutes of merge time at 100k records; with the caps it is bounded work.
 * Dropping the remaining flags loses no detection — they are recomputed from the diary on the
 * next evaluation or search, and a wider budget reproduces the full set on demand.
 */
data class FacFlag(
    /** Hashed subject key of the suspected organiser. Never a name, never reversible. */
    val anchor: String,
    /** Devices that recorded the anchor's crossings. */
    val devices: List<String>,
    /** True number of qualifying cohorts, which may exceed the evidence lists below. */
    val distinctGroups: Int,
    val windowDays: Int,
    /** Up to [ThresholdName.FACILITATOR_MAX_EVIDENCE] of the qualifying cohort keys. */
    val groupKeys: List<String>,
    val windowStartIso: String,
    val windowEndIso: String,
    /** Up to [ThresholdName.FACILITATOR_MAX_EVIDENCE] of the contributing event ids. */
    val eventIds: List<String>,
    /** The typed finding the UI resolves. Carries A_FAC-01, never a raw string. */
    val finding: Finding,
) {
    /** True when the evidence lists were truncated and the full set is larger. */
    val evidenceTruncated: Boolean get() = distinctGroups > groupKeys.size
}

/**
 * @param windowDays width of the sliding window, from the registry.
 * @param minGroups distinct qualifying cohorts the anchor must have accumulated.
 * @param minAnchorEvents how often the anchor itself must appear before it is examined at all.
 * @param minCooccurrences how many times one cohort must turn up alongside the anchor.
 * @param maxPartners cap on distinct cohorts counted for one anchor, taken in crossing order.
 *   The absolute work bound: even at a gate that processes a crossing a minute, an evaluation
 *   cannot sweep an unbounded window. It is defence in depth — the cohort key above already
 *   keeps the set of examined anchors proportional to the genuinely recurring subjects.
 * @param maxEvidence cap on the ids, keys and devices carried by a single flag.
 * @param maxFlags cap on how many flags one evaluation returns.
 * @return at most one flag per anchor: the *earliest* qualifying window. One flag per anchor
 *   keeps the merge report bounded and makes the output a function of the event set rather than
 *   of how many windows happened to overlap — which is what makes it safe to recompute
 *   incrementally on every merge (SYNC.md §4).
 */
fun facilitatorRule(
    events: List<CrossingEvent>,
    windowDays: Int,
    minGroups: Int,
    coOccurHours: Int,
    groupPrefixHex: Int,
    maxPartners: Int,
    minAnchorEvents: Int,
    minCooccurrences: Int,
    maxEvidence: Int,
    maxFlags: Int,
): List<FacFlag> {
    require(windowDays > 0) { "windowDays must be positive, got $windowDays" }
    require(minGroups >= 2) { "minGroups below 2 would fire on a single cohort, got $minGroups" }
    require(coOccurHours > 0) { "coOccurHours must be positive, got $coOccurHours" }
    require(minAnchorEvents >= 2) { "an anchor seen once has shown nothing; got $minAnchorEvents" }
    require(minCooccurrences >= 2) { "company met once is footfall, not a pattern; got $minCooccurrences" }
    require(maxEvidence >= 1) { "maxEvidence must be positive, got $maxEvidence" }
    require(maxFlags >= 1) { "maxFlags must be positive, got $maxFlags" }
    if (events.size < minGroups) return emptyList()

    val dated = events.mapNotNull { e ->
        val at = IsoInstant.parse(e.ts) ?: return@mapNotNull null
        Triple(e, at, subjectKeyOf(e, groupPrefixHex))
    }.sortedWith(compareBy({ it.second }, { it.first.id }))
    if (dated.size < minGroups) return emptyList()

    val windowMillis = windowDays.toLong() * 86_400_000L
    val coOccurMillis = coOccurHours.toLong() * 3_600_000L

    // Both groupings inherit `dated`'s time ordering, which is what lets the co-occurrence sweep
    // be two binary searches rather than a scan of the whole post. A scan made this rule
    // quadratic in the crossings at a busy post — found by the sync merge budget test.
    val byPostAndTime = dated.groupBy { it.first.post }
    val anchors = dated.groupBy { it.third }
    val flags = mutableListOf<FacFlag>()

    for ((anchor, anchorEvents) in anchors) {
        var lo = 0
        for (hi in anchorEvents.indices) {
            val windowEnd = anchorEvents[hi].second
            while (anchorEvents[lo].second < windowEnd - windowMillis) lo++
            if (hi < lo) continue
            val windowEvents = anchorEvents.subList(lo, hi + 1)
            if (windowEvents.size < minAnchorEvents) continue

            val company = HashMap<String, CrossingEvent>()
            val met = HashMap<String, Int>()
            for ((anchorEvent, at) in windowEvents) {
                if (met.size >= maxPartners) break
                val atPost = byPostAndTime.getValue(anchorEvent.post)
                val from = firstAtOrAfter(atPost, at - coOccurMillis)
                val until = firstAfter(atPost, at + coOccurMillis)
                for (index in from until until) {
                    val (other, _, otherKey) = atPost[index]
                    if (otherKey == anchor) continue
                    val seen = met[otherKey]
                    company.putIfAbsent(otherKey, other)
                    met[otherKey] = (seen ?: 0) + 1
                    if (met.size >= maxPartners) break
                }
            }
            var qualifyingCount = 0
            for (count in met.values) if (count >= minCooccurrences) qualifyingCount++
            if (qualifyingCount < minGroups) continue
            val qualifying = met.filterValues { it >= minCooccurrences }.keys.sorted()

            // Evidence collection is capped as it is built, not afterwards. Materialising the
            // whole cohort list and then truncating it is what made this rule cost minutes of
            // merge time at 100k records.
            val contributing = LinkedHashSet<String>()
            val devices = LinkedHashSet<String>()
            for (event in windowEvents) {
                if (contributing.size < maxEvidence) contributing += event.first.id
                if (devices.size < maxEvidence) devices += event.first.device
            }
            for (key in qualifying) {
                if (contributing.size >= maxEvidence && devices.size >= maxEvidence) break
                val partner = company[key] ?: continue
                if (contributing.size < maxEvidence) contributing += partner.id
                if (devices.size < maxEvidence) devices += partner.device
            }

            flags += FacFlag(
                anchor = anchor,
                devices = devices.sorted(),
                distinctGroups = qualifyingCount,
                windowDays = windowDays,
                groupKeys = qualifying.take(maxEvidence),
                windowStartIso = windowEvents.first().first.ts,
                windowEndIso = windowEvents.last().first.ts,
                eventIds = contributing.sorted().take(maxEvidence),
                finding = Finding(
                    code = FindingCode.A_FAC_01,
                    severity = Severity.AMBER,
                    evidenceRef = anchor,
                    message = "$qualifyingCount cohorts crossed repeatedly with one subject inside $windowDays days",
                ),
            )
            break
        }
    }
    // Most-associated first: that is the subject an officer should look at, and ties break on
    // the anchor key so the list is stable across runs. The cap keeps one evaluation's cost and
    // its merge report bounded; the thresholds that decided each flag are untouched.
    flags.sortWith(compareByDescending<FacFlag> { it.distinctGroups }.thenBy { it.anchor })
    return flags.take(maxFlags)
}

/** First index of a time-sorted list whose instant is at or after [target]. */
private fun firstAtOrAfter(sorted: List<Triple<CrossingEvent, Long, String>>, target: Long): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (sorted[mid].second < target) low = mid + 1 else high = mid
    }
    return low
}

/** First index of a time-sorted list whose instant is strictly after [target]. */
private fun firstAfter(sorted: List<Triple<CrossingEvent, Long, String>>, target: Long): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (sorted[mid].second <= target) low = mid + 1 else high = mid
    }
    return low
}

/** Registry-driven overload — the form production code should call. */
fun facilitatorRule(events: List<CrossingEvent>, reg: ThresholdRegistry): List<FacFlag> = facilitatorRule(
    events = events,
    windowDays = reg[ThresholdName.FACILITATOR_WINDOW_DAYS].toInt(),
    minGroups = reg[ThresholdName.FACILITATOR_MIN_GROUPS].toInt(),
    coOccurHours = reg[ThresholdName.FACILITATOR_COOCCUR_HOURS].toInt(),
    groupPrefixHex = reg[ThresholdName.FACILITATOR_GROUP_PREFIX_HEX].toInt(),
    maxPartners = reg[ThresholdName.FACILITATOR_MAX_PARTNERS].toInt(),
    minAnchorEvents = reg[ThresholdName.FACILITATOR_MIN_ANCHOR_EVENTS].toInt(),
    minCooccurrences = reg[ThresholdName.FACILITATOR_MIN_COOCCURRENCES].toInt(),
    maxEvidence = reg[ThresholdName.FACILITATOR_MAX_EVIDENCE].toInt(),
    maxFlags = reg[ThresholdName.FACILITATOR_MAX_FLAGS_PER_EVAL].toInt(),
)

/**
 * The hashed cohort key for [event]: `nameSha`, else `docHash`, else the event id.
 *
 * Exposed because a supervisor-facing report needs to show *which* cohort was counted without
 * being able to re-identify it, and the same function must produce both.
 */
fun subjectKeyOf(event: CrossingEvent, prefixHex: Int = 8): String {
    require(prefixHex in 1..EmbModel.SHA256_HEX_LENGTH) { "prefixHex $prefixHex out of range" }
    val source = event.nameSha.ifBlank { event.docHash }
    if (source.isBlank()) return event.id
    return source.take(prefixHex)
}
