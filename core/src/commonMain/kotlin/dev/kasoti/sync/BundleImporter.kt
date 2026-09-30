package dev.kasoti.sync

import dev.kasoti.crypto.Hmac
import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.Diary
import dev.kasoti.diary.EmbModel
import dev.kasoti.diary.InMemoryQuarantine
import dev.kasoti.diary.IsoInstant
import dev.kasoti.diary.PostMap
import dev.kasoti.diary.Quarantine
import dev.kasoti.diary.QuarantineReason
import dev.kasoti.diary.facilitatorRule
import dev.kasoti.diary.impossibleTravel
import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * What happened to a bundle (SYNC.md §4 step 5).
 *
 * Every outcome is explicit, including the ones that apply nothing. A merge that reports
 * "accepted 0, dupes 400" and a merge that reports "rejected: bad HMAC" are both non-events,
 * and an operator has to be able to tell them apart without reading a log — which is why
 * [outcome] exists instead of inferring it from the counters.
 */
data class MergeReport(
    val outcome: MergeOutcome,
    val accepted: Int = 0,
    val dupes: Int = 0,
    val rejected: Int = 0,
    val quarantined: Int = 0,
    val skewWarnings: List<ClockSkewWarning> = emptyList(),
    val newFlags: List<Finding> = emptyList(),
    val maxSeqAfter: Map<String, Long> = emptyMap(),
    val tipAfter: String = "",
) {
    /** Nothing was written to the diary. The only outcome where this is guaranteed. */
    val applied: Boolean get() = outcome == MergeOutcome.APPLIED
}

enum class MergeOutcome {
    /** Records were merged (possibly zero of them, if they were all duplicates). */
    APPLIED,

    /** A valid bundle that carried no records at all. */
    EMPTY,

    /** Unparseable, structurally invalid, or a protocol version this build cannot read. */
    REJECTED_MALFORMED,

    /** Authenticity failed. Nothing was applied — ever. */
    REJECTED_HMAC,

    /** A bundle claiming to come from this device (cloned install). */
    REJECTED_SELF_DEVICE,

    /** Invariant I1: a different embedding generation. Whole bundle held back. */
    QUARANTINED_MODEL_MISMATCH,
}

data class ClockSkewWarning(
    val device: String,
    val envelopeTs: String,
    val localNow: String,
    /** Minutes of disagreement; meaningless when [parseable] is false. */
    val skewMinutes: Double,
    /**
     * False when the envelope's `ts` could not be read at all. That is reported as a skew
     * warning rather than a rejection: the bundle is authentic (its HMAC passed) but its clock
     * is unusable, and an operator needs to know which device to go and fix.
     */
    val parseable: Boolean = true,
)

/**
 * The receive half of `kasoti-sync/1` (SYNC.md §4).
 *
 * The ordering below is the specification, and each step exists because the *next* step is
 * untrustworthy without it:
 *
 *  1. **Parse.** Nothing else can be evaluated.
 *  2. **Version.** A v2 bundle parsed by a v1 reader is not a malformed v1 bundle, and
 *     reporting it as one would send someone hunting for a corrupt file.
 *  3. **HMAC.** Authenticity before content. A forged bundle that claims our own device id must
 *     not even reach the self-import check, or that check becomes an oracle.
 *  4. **Self-import.** Cloned installs and looped bundles are rejected here (SYNC.md §2).
 *  5. **Generation.** Whole-bundle quarantine on mismatch (invariant I1), *before* any record
 *     is written — never partial.
 *  6. **Decode and re-check the generation per record.** A header that lies about its
 *     generation while its records disagree with each other is caught here, still before any
 *     write, so the quarantine is genuinely whole-file.
 *  7. **Skew**, then per-record dupe/replay filtering, then an ordered merge, then an
 *     incremental flag recomputation.
 *
 * HMAC and generation failures are *atomic*: the diary is not touched at all. Per-record
 * failures are *counted*: one unreadable record in a 400-record bundle must not cost the other
 * 399, because the alternative is that no bundle is ever importable.
 */
class BundleImporter(
    private val hmac: Hmac,
    private val localDevice: String,
    private val localEmbModel: String,
    private val reg: ThresholdRegistry,
) {
    init {
        require(EmbModel.isValid(localEmbModel)) { "local embModel '$localEmbModel' is not name@sha256:<64 hex>" }
    }

    fun import(
        bytes: ByteArray,
        key: ByteArray,
        diary: Diary,
        nowMillis: Long,
        quarantine: Quarantine = InMemoryQuarantine(),
        posts: PostMap = emptyMap(),
    ): MergeReport = import(String(bytes, Charsets.UTF_8), key, diary, nowMillis, quarantine, posts)

    fun import(
        text: String,
        key: ByteArray,
        diary: Diary,
        nowMillis: Long,
        quarantine: Quarantine = InMemoryQuarantine(),
        posts: PostMap = emptyMap(),
    ): MergeReport {
        val envelope = SyncEnvelope.decode(text)
            ?: return report(MergeOutcome.REJECTED_MALFORMED, diary)
        if (!envelope.verify(hmac, key)) {
            return report(MergeOutcome.REJECTED_HMAC, diary)
        }
        if (envelope.device == localDevice) {
            return report(MergeOutcome.REJECTED_SELF_DEVICE, diary)
        }
        if (!EmbModel.sameGeneration(envelope.embModel, localEmbModel)) {
            quarantine.hold(
                QuarantineReason.EMB_MODEL_MISMATCH,
                "bundle header declares ${envelope.embModel}, this device holds $localEmbModel",
                emptyList(),
            )
            return report(MergeOutcome.QUARANTINED_MODEL_MISMATCH, diary, quarantined = envelope.records.size)
        }
        if (envelope.records.isEmpty()) {
            return report(MergeOutcome.EMPTY, diary)
        }

        var rejected = 0
        val decoded = mutableListOf<CrossingEvent>()
        val foreignGeneration = mutableListOf<CrossingEvent>()
        for (text0 in envelope.records) {
            val record = CrossingEvent.decode(text0)
            when {
                record == null -> rejected++
                !EmbModel.sameGeneration(record.embModel, localEmbModel) -> foreignGeneration += record
                else -> decoded += record
            }
        }
        // Still nothing written, so this is a whole-bundle hold rather than a partial merge.
        if (foreignGeneration.isNotEmpty()) {
            quarantine.hold(
                QuarantineReason.EMB_MODEL_MISMATCH,
                "records declare ${foreignGeneration.first().embModel} inside a bundle headed ${envelope.embModel}",
                foreignGeneration,
            )
            return report(
                MergeOutcome.QUARANTINED_MODEL_MISMATCH,
                diary,
                rejected = rejected,
                quarantined = envelope.records.size,
            )
        }

        val skew = skewWarning(envelope, nowMillis)

        // I2 replay guard. An id already held is a dupe, not a replay: re-importing yesterday's
        // bundle is the *expected* case and must be a quiet no-op, while a *new* bundle carrying
        // old sequence numbers is the suspicious one.
        val seenInBatch = HashSet<String>(decoded.size)
        val maxSeq = mutableMapOf(envelope.device to diary.maxSeq(envelope.device))
        var dupes = 0
        val fresh = mutableListOf<CrossingEvent>()
        for (record in decoded) {
            when {
                diary.get(record.id) != null || !seenInBatch.add(record.id) -> dupes++
                record.seq <= (maxSeq[envelope.device] ?: 0L) -> rejected++
                else -> {
                    fresh += record
                    maxSeq[envelope.device] = record.seq
                }
            }
        }

        val freshSorted = fresh.sortedWith(ORDER)
        val merged = diary.mergeIncoming(freshSorted, quarantine)

        val flags = recomputeFlags(diary, freshSorted, posts)
        return MergeReport(
            outcome = MergeOutcome.APPLIED,
            accepted = merged.accepted,
            dupes = dupes + merged.dupes,
            rejected = rejected + merged.rejected,
            quarantined = merged.quarantined,
            skewWarnings = listOfNotNull(skew),
            newFlags = flags,
            maxSeqAfter = maxSeq.toMap(),
            tipAfter = diary.tip(),
        )
    }

    /**
     * Incremental derived-flag recomputation (SYNC.md §4 step 4).
     *
     * The scope is every record inside the facilitator window ending at the newest merged event,
     * plus the merged records themselves. That is the whole region in which a travel or
     * facilitator flag could have become true: travel compares crossings hours apart, and the
     * facilitator window is [ThresholdName.FACILITATOR_WINDOW_DAYS]. Records outside it cannot
     * have changed because of this merge, so scanning the whole diary on every import would be
     * paying for answers nobody can act on — and at 100k records it is the difference between a
     * merge finishing in seconds and not finishing.
     *
     * Alias and watchlist findings are deliberately *not* here: they need the *claimed* identity
     * of the person in front of the camera, which only exists at search time (SYNC.md §4).
     *
     * Residual risk, stated plainly: a flag that becomes true entirely outside the window is not
     * re-reported by this merge. It is evaluated on the next search and on any UI recompute, so it
     * is a reporting-latency risk rather than a detection gap.
     */
    private fun recomputeFlags(
        diary: Diary,
        fresh: List<CrossingEvent>,
        posts: PostMap,
    ): List<Finding> {
        if (fresh.isEmpty()) return emptyList()
        val newest = fresh.maxOf { IsoInstant.parse(it.ts) ?: 0L }
        val horizonMillis = newest - (reg[ThresholdName.FACILITATOR_WINDOW_DAYS].toLong() * 86_400_000L)
        val horizonIso = IsoInstant.format(horizonMillis)
        val ids = fresh.map { it.id }.toHashSet()

        val scope = diary.all().filter { it.id in ids || IsoInstant.isAtOrAfter(it.ts, horizonIso) }
        val flags = mutableListOf<Finding>()
        if (posts.isNotEmpty()) {
            flags += impossibleTravel(scope, posts, reg).map { travel ->
                Finding(
                    code = FindingCode.R_TRAV_01,
                    severity = Severity.RED,
                    evidenceRef = "${travel.fromEventId}->${travel.toEventId}",
                    message = "implied ${travel.impliedSpeedKmh} km/h between ${travel.fromPost} and ${travel.toPost}",
                )
            }
        }
        flags += facilitatorRule(scope, reg).map { it.finding }
        return flags
    }

    private companion object {
        /** SYNC.md §4 step 3, compared as instants so a non-canonical stamp cannot misorder. */
        val ORDER: Comparator<CrossingEvent> =
            compareBy<CrossingEvent> { IsoInstant.parse(it.ts) ?: 0L }
                .thenBy { it.device }
                .thenBy { it.id }
    }

    private fun skewWarning(envelope: SyncEnvelope, nowMillis: Long): ClockSkewWarning? {
        val localNow = IsoInstant.format(nowMillis)
        val skew = IsoInstant.skewMinutes(envelope.ts, nowMillis)
            ?: return ClockSkewWarning(envelope.device, envelope.ts, localNow, 0.0, parseable = false)
        if (skew <= reg[ThresholdName.CLOCK_SKEW_MAX_MIN]) return null
        return ClockSkewWarning(
            device = envelope.device,
            envelopeTs = envelope.ts,
            localNow = localNow,
            skewMinutes = Math.round(skew * 100.0) / 100.0,
        )
    }

    private fun report(
        outcome: MergeOutcome,
        diary: Diary,
        rejected: Int = 0,
        quarantined: Int = 0,
    ) = MergeReport(
        outcome = outcome,
        rejected = rejected,
        quarantined = quarantined,
        maxSeqAfter = emptyMap(),
        tipAfter = diary.tip(),
    )
}
