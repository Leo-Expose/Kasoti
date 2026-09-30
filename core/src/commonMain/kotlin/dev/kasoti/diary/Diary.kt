package dev.kasoti.diary

import dev.kasoti.audit.ChainVerification
import dev.kasoti.face.Embedding
import dev.kasoti.fusion.DiaryHit

/**
 * The repository contract (DESIGN.md §2).
 *
 * Kept as an interface so the file-backed implementation can be swapped for SQL when a
 * deployment outgrows a flat file, and so the rules can be unit-tested against an in-memory
 * double without touching storage. Every method is pure with respect to time: `purge` takes
 * the cutoff as a value rather than reading a clock (AGENTS.md §5).
 *
 * Deletes are local-only (SYNC.md §1). [purge] and [wipe] are retention operations for this
 * device; no bundle ever carries a tombstone, so a purge can never remove another post's
 * evidence. That is a deliberate privacy trade: a compromised device can delete its own log,
 * but not its neighbours'.
 */
interface Diary {

    /**
     * Appends a locally captured event.
     *
     * @throws DiaryModelMismatchException if the record's embedding generation is not the
     *   local one — comparing across generations produces confident nonsense (invariant I1).
     * @throws DiarySequenceException if [CrossingEvent.seq] is neither 0 (assign one) nor
     *   exactly one past the device's high-water mark.
     */
    fun append(event: CrossingEvent)

    /** Brute-force cosine over the int8 store, best first. Empty if [embedding] is a foreign generation. */
    fun search(embedding: Embedding, topK: Int): List<DiaryHit>

    fun get(id: String): CrossingEvent?

    /** @return how many records were removed. Local-only; nothing propagates. */
    fun purge(olderThanIso: String): Int

    /** Drops every record and every vector, and resets the chain and sequence counters. */
    fun wipe()

    /** Append-only, idempotent merge. @see DiaryMergeReport */
    fun mergeIncoming(records: List<CrossingEvent>, quarantine: Quarantine = InMemoryQuarantine()): DiaryMergeReport

    /** Every record in append order. Used by the rules and by re-verification. */
    fun all(): List<CrossingEvent>

    fun size(): Int

    /** Highest [CrossingEvent.seq] accepted from [device]; `0` when nothing is known. */
    fun maxSeq(device: String): Long

    /** Current audit-chain tip, or the genesis hash when the diary is empty. */
    fun tip(): String

    /** The embedding generation this diary can compare against (invariant I1). */
    fun embeddingGeneration(): String

    /** Recomputes the chain from the stored `prev` links. */
    fun verifyChain(): ChainVerification
}

/** Outcome of a diary merge, before any sync-envelope concerns are layered on. */
data class DiaryMergeReport(
    val accepted: Int,
    val dupes: Int,
    val quarantined: Int,
    val rejected: Int = 0,
)

/** Why a batch was held back. Each of these is a whole-batch decision, never per-record. */
enum class QuarantineReason {
    /** Invariant I1: the batch carries a different embedding generation than this device. */
    EMB_MODEL_MISMATCH,

    /** One record's quantisation scale contradicts the envelope header. */
    EMB_SCALE_MISMATCH,

    /** The sender's declared sequence range contradicts the records inside it. */
    SEQ_RANGE_INCONSISTENT,
}

data class QuarantineBatch(
    val reason: QuarantineReason,
    val detail: String,
    val records: List<CrossingEvent>,
)

/**
 * Where held-back batches go.
 *
 * `:core` has no file I/O, so the quarantine *decision* is here and the quarantine *write* is
 * the platform's (SYNC.md §4 calls for a quarantine directory). The decision is the part that
 * can be wrong silently — a batch dropped on the floor looks exactly like a batch that was
 * never received — so the sink is an interface the platform must implement loudly.
 */
interface Quarantine {
    fun hold(reason: QuarantineReason, detail: String, records: List<CrossingEvent>)
}

/** Test/desktop default: keeps the batches in memory so nothing is lost silently. */
class InMemoryQuarantine : Quarantine {
    private val held = mutableListOf<QuarantineBatch>()

    val batches: List<QuarantineBatch> get() = held.toList()

    val recordCount: Int get() = held.sumOf { it.records.size }

    override fun hold(reason: QuarantineReason, detail: String, records: List<CrossingEvent>) {
        held += QuarantineBatch(reason, detail, records)
    }

    fun clear() = held.clear()
}

/** Raised by [Diary.append] for a record the local generation cannot compare against. */
class DiaryModelMismatchException(val recordId: String, val recordModel: String, val localModel: String) :
    IllegalStateException("event $recordId carries $recordModel but this device holds $localModel")

/** Raised by [Diary.append] when a sequence number breaks per-device monotonicity (invariant I2). */
class DiarySequenceException(device: String, seq: Long, expected: Long) :
    IllegalStateException("device $device offered seq $seq where $expected was required")

/** Raised when the on-disk layout is unusable — a truncated vector file, typically. */
class DiaryCorruptException(message: String) : IllegalStateException(message)
