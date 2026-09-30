package dev.kasoti.diary

import dev.kasoti.audit.ChainVerification
import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import dev.kasoti.face.Embedding
import dev.kasoti.fusion.DiaryHit

/**
 * The file-backed diary (DESIGN.md §4, decision D2).
 *
 * Layout: one JSONL line per event plus a parallel `embeddings.bin` of 128-byte int8 vectors in
 * the same order. No database, because the first thing anyone asks for at a border post is "let
 * me open the file" — and because a flat file makes a USB bundle a `cp`.
 *
 * Three invariants live here rather than in the callers:
 *  - **Monotonic per-device sequence** (I2). `seq` is per-device; the globally unique identity
 *    of an event is `(device, seq)`, which is why the replay guard is a per-device map and not
 *    a single counter.
 *  - **Embedding generation pinned** (I1). Records from another model are never written.
 *  - **Deterministic merge.** Incoming records are sorted by `(ts, device, id)` before append,
 *    so the resulting chain tip depends on the *set* that was merged, not the order it arrived
 *    in. Two posts exchanging the same day's bundles end up with the same tip.
 */
class FileDiary(
    private val storage: DiaryStorage,
    private val digest: Digest,
    /** The local embedding generation, e.g. `emb_v1@sha256:<64 hex>`. */
    val embModel: String,
) : Diary {

    init {
        require(EmbModel.isValid(embModel)) { "local embModel '$embModel' is not name@sha256:<64 hex>" }
    }

    private var loaded = false
    private val records = mutableListOf<CrossingEvent>()
    private val vectors = mutableListOf<ByteArray>()
    private val chain = mutableListOf<String>()
    /**
     * Id index.
     *
     * Not a nicety: the sync importer calls `get` once per incoming record to separate a dupe
     * from a fresh event, so a linear scan makes importing N records quadratic. Found by the
     * sync merge budget test at 100k records, not by reading the code.
     */
    private val byId = mutableMapOf<String, CrossingEvent>()
    private val maxSeqByDevice = mutableMapOf<String, Long>()

    // --- loading ---------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        val lines = storage.readLines()
        val storedVectors = storage.readVectors()
        if (lines.size != storedVectors.size) {
            throw DiaryCorruptException(
                "diary is misaligned: ${lines.size} records but ${storedVectors.size} vectors; " +
                    "a vector file that does not match its JSONL cannot be searched safely",
            )
        }
        for (index in lines.indices) {
            val record = CrossingEvent.decode(lines[index])
                ?: throw DiaryCorruptException("diary line ${index + 1} is not a readable crossing event")
            if (record.emb.size != storedVectors[index].size) {
                throw DiaryCorruptException(
                    "diary vector ${index + 1} is ${storedVectors[index].size} bytes, " +
                        "expected ${Embedding.DIM}",
                )
            }
            require(EmbModel.sameGeneration(record.embModel, embModel)) {
                "diary record ${record.id} carries ${record.embModel} but this device holds $embModel"
            }
            records += record
            vectors += storedVectors[index]
            byId[record.id] = record
            chain += chainHashOfCanonical(record.canonicalForChain(record.prev))
            val current = maxSeqByDevice[record.device] ?: 0L
            if (record.seq > current) maxSeqByDevice[record.device] = record.seq
        }
        loaded = true
    }

    // --- append ------------------------------------------------------------------------

    override fun append(event: CrossingEvent) {
        ensureLoaded()
        if (!EmbModel.sameGeneration(event.embModel, embModel)) {
            throw DiaryModelMismatchException(event.id, event.embModel, embModel)
        }
        val expected = (maxSeqByDevice[event.device] ?: 0L) + 1
        val resolved = if (event.seq <= 0L) {
            event.copy(seq = expected)
        } else {
            if (event.seq != expected) throw DiarySequenceException(event.device, event.seq, expected)
            event
        }
        if (resolved.id in byId) {
            throw DiarySequenceException(resolved.device, resolved.seq, expected)
        }
        writeRecord(resolved)
    }

    private fun writeRecord(event: CrossingEvent) {
        val linked = event.copy(prev = tip())
        val line = linked.canonical()
        storage.appendLine(line)
        storage.appendVector(event.emb)
        storage.flush()
        records += linked
        vectors += event.emb
        byId[linked.id] = linked
        // `line` is already `canonicalForChain(tip())` because `linked.prev` *is* the tip we
        // just read; canonicalising a second time to hash it doubled the cost of every append.
        chain += chainHashOfCanonical(line)
        val current = maxSeqByDevice[linked.device] ?: 0L
        if (linked.seq > current) maxSeqByDevice[linked.device] = linked.seq
    }

    // --- read --------------------------------------------------------------------------

    override fun search(embedding: Embedding, topK: Int): List<DiaryHit> {
        ensureLoaded()
        if (topK <= 0) return emptyList()
        // The comparability law: a foreign generation is not "a worse match", it is not a
        // match at all. Silently returning hits would be a false-RED machine.
        if (!EmbModel.sameGeneration(embedding.modelTag, embModel)) return emptyList()

        // Bounded top-K in one pass with no per-record allocation. Sorting a scored copy of the
        // whole diary would allocate a pair for every record — 100k of them for a large diary —
        // on a path that runs while an officer is waiting at a counter.
        val bestScore = FloatArray(topK)
        val bestIndex = IntArray(topK) { -1 }
        var filled = 0
        for (i in records.indices) {
            val sim = cosineQuantised(embedding.values, vectors[i], records[i].qScale)
            if (filled == topK && !outranks(sim, records[i].id, bestScore[topK - 1], records[bestIndex[topK - 1]].id)) {
                continue
            }
            var slot = if (filled < topK) filled++ else topK - 1
            while (slot > 0 && outranks(sim, records[i].id, bestScore[slot - 1], records[bestIndex[slot - 1]].id)) {
                bestScore[slot] = bestScore[slot - 1]
                bestIndex[slot] = bestIndex[slot - 1]
                slot--
            }
            bestScore[slot] = sim
            bestIndex[slot] = i
        }
        return (0 until filled).map { toHit(records[bestIndex[it]], bestScore[it]) }
    }

    /** Strict "better than" ordering: similarity first, event id as the deterministic tie-break. */
    private fun outranks(similarity: Float, id: String, bestSimilarity: Float, bestId: String): Boolean =
        similarity > bestSimilarity || (similarity == bestSimilarity && id < bestId)

    override fun get(id: String): CrossingEvent? {
        ensureLoaded()
        return byId[id]
    }

    override fun all(): List<CrossingEvent> {
        ensureLoaded()
        return records.toList()
    }

    override fun size(): Int {
        ensureLoaded()
        return records.size
    }

    override fun maxSeq(device: String): Long {
        ensureLoaded()
        return maxSeqByDevice[device] ?: 0L
    }

    override fun tip(): String {
        ensureLoaded()
        return chain.lastOrNull() ?: genesisHash()
    }

    override fun embeddingGeneration(): String = embModel

    override fun verifyChain(): ChainVerification {
        ensureLoaded()
        var previous = genesisHash()
        for ((index, record) in records.withIndex()) {
            if (record.prev != previous) {
                return ChainVerification(false, records.size, index, "record ${record.id} does not link to its predecessor")
            }
            previous = chain[index]
        }
        return ChainVerification(true, records.size, null)
    }

    // --- merge -------------------------------------------------------------------------

    /**
     * Append-only, idempotent merge (SYNC.md §4 step 3).
     *
     * Whole-batch quarantine on a generation mismatch, never partial: a batch is the unit a
     * sender vouches for, and accepting the 99% of it that happens to parse would leave the
     * diary looking complete while missing exactly the records that were hard to read. The
     * cost is that one corrupt vector quarantines a whole day; that is the intended trade,
     * because a quarantine is recoverable by re-transfer and a silent gap is not.
     *
     * Duplicates are counted against ids already held *and* within the batch, so importing the
     * same bundle twice is a no-op the second time rather than a doubling.
     */
    override fun mergeIncoming(records: List<CrossingEvent>, quarantine: Quarantine): DiaryMergeReport {
        ensureLoaded()
        if (records.isEmpty()) return DiaryMergeReport(0, 0, 0)

        val foreign = records.filter { !EmbModel.sameGeneration(it.embModel, embModel) }
        if (foreign.isNotEmpty()) {
            quarantine.hold(
                QuarantineReason.EMB_MODEL_MISMATCH,
                "batch declares ${foreign.first().embModel}, this device holds $embModel",
                records,
            )
            return DiaryMergeReport(0, 0, records.size)
        }

        val ordered = records.sortedWith(MERGE_ORDER)
        val batchIds = HashSet<String>(ordered.size)
        var dupes = 0
        var rejected = 0
        val fresh = mutableListOf<CrossingEvent>()
        for (record in ordered) {
            when {
                record.id in byId || !batchIds.add(record.id) -> dupes++
                record.seq <= 0 -> {
                    // A record with no sequence cannot be replay-guarded, so it cannot be
                    // stored. Counting it is the honest outcome; dropping it silently is not.
                    rejected++
                }
                else -> fresh += record
            }
        }
        for (record in fresh) writeRecord(record)
        return DiaryMergeReport(fresh.size, dupes, 0, rejected)
    }

    // --- retention ----------------------------------------------------------------------

    /**
     * Drops records older than [olderThanIso]. Local-only by contract (SYNC.md §1).
     *
     * The chain is *not* rewritten: history stays as it was, so a purged diary no longer
     * verifies from genesis and the surviving records still link to hashes whose records are
     * gone. That is intentional. Recomputing the chain would invent history that never
     * happened, and no remote can rely on our chain anyway because deletes never propagate.
     */
    override fun purge(olderThanIso: String): Int {
        ensureLoaded()
        val cutoff = IsoInstant.parse(olderThanIso)
            ?: throw IllegalArgumentException("purge cutoff '$olderThanIso' is not a UTC ISO-8601 instant")
        val keepIndices = records.indices.filter { index ->
            val at = IsoInstant.parse(records[index].ts)
            // Undated record: keep it. Deleting what we cannot place in time is how a diary
            // loses its most recent evidence.
            at == null || at >= cutoff
        }
        if (keepIndices.size == records.size) return 0
        val removed = records.size - keepIndices.size
        replaceStore(keepIndices)
        return removed
    }

    override fun wipe() {
        ensureLoaded()
        records.clear()
        vectors.clear()
        chain.clear()
        byId.clear()
        maxSeqByDevice.clear()
        storage.replaceAll(emptyList(), emptyList())
        storage.flush()
    }

    private fun replaceStore(keepIndices: List<Int>) {
        val keptRecords = keepIndices.map { records[it] }
        val keptVectors = keepIndices.map { vectors[it] }
        val keptChain = keepIndices.map { chain[it] }
        records.clear()
        records += keptRecords
        vectors.clear()
        vectors += keptVectors
        chain.clear()
        chain += keptChain
        byId.clear()
        byId += keptRecords.associateBy { it.id }
        maxSeqByDevice.clear()
        for (record in keptRecords) {
            val current = maxSeqByDevice[record.device] ?: 0L
            if (record.seq > current) maxSeqByDevice[record.device] = record.seq
        }
        storage.replaceAll(keptRecords.map { it.canonical() }, keptVectors)
        storage.flush()
    }

    // --- helpers ------------------------------------------------------------------------

    /** The chain link hash. Its input is the record's own canonical bytes, `prev` field included. */
    private fun chainHashOfCanonical(canonicalRecord: String): String =
        Hex.encode(digest.sha256(canonicalRecord.toByteArray(Charsets.UTF_8)))

    private fun genesisHash(): String =
        Hex.encode(digest.sha256(GENESIS_SEED.toByteArray(Charsets.UTF_8)))

    private fun toHit(record: CrossingEvent, similarity: Float) = DiaryHit(
        eventId = record.id,
        similarity = similarity,
        post = record.post,
        timestamp = record.ts,
        nameHash = record.nameSha,
        dob = record.dob,
    )

    private companion object {
        const val GENESIS_SEED = "kasoti-diary/1"

        /**
         * (ts, device, id) — the ordering SYNC.md §4 mandates, total so merges are deterministic.
         *
         * Compared through [IsoInstant.compare] rather than as raw strings: a record may legally
         * carry `2026-03-14T14:30:00+05:30` instead of the canonical UTC form, and sorting that
         * lexicographically would put it in the wrong place — a merge that is deterministic but
         * not chronological is deterministic in the wrong order.
         */
        val MERGE_ORDER: Comparator<CrossingEvent> =
            compareBy<CrossingEvent> { IsoInstant.parse(it.ts) ?: 0L }
                .thenBy { it.device }
                .thenBy { it.id }
    }
}
