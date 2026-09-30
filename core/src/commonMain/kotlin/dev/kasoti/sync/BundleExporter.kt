package dev.kasoti.sync

import dev.kasoti.crypto.Hmac
import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.EmbModel
import dev.kasoti.diary.IsoInstant
import dev.kasoti.json.Base64Codec
import dev.kasoti.json.CanonicalJson
import dev.kasoti.json.JsonParser
import dev.kasoti.json.JsonValue
import dev.kasoti.json.float
import dev.kasoti.json.jsonObj
import dev.kasoti.json.string
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/** An exported bundle: the exact bytes plus the envelope they carry. */
class SyncBundle(
    val fileName: String,
    val text: String,
    val envelope: SyncEnvelope,
) {
    val bytes: ByteArray get() = text.toByteArray(Charsets.UTF_8)
    val sizeBytes: Int get() = text.toByteArray(Charsets.UTF_8).size
}

/**
 * A watchlist entry (SYNC.md §3, `t:"watch"`).
 *
 * Watchlists are the one record type small enough for a QR, which is the whole reason this
 * shape exists: HQ can push a new wanted-subject list to a post with no cable, no network and
 * no re-provisioning. The cost is bytes, which is why [label] is bounded and the pack is
 * budget-checked rather than assumed to fit.
 */
data class WatchlistEntry(
    val id: String,
    val emb: ByteArray,
    val qScale: Float,
    val label: String,
    val source: String,
    val expiresIso: String,
) {
    fun canonical(): String = CanonicalJson.write(toJson())

    fun toJson(): JsonValue.Obj = jsonObj(
        RECORD_TYPE_KEY to JsonValue.Str(RECORD_TYPE),
        "emb" to JsonValue.Str(Base64Codec.encode(emb)),
        "exp" to JsonValue.Str(expiresIso),
        "id" to JsonValue.Str(id),
        "label" to JsonValue.Str(label),
        "qScale" to JsonValue.F32(qScale),
        "source" to JsonValue.Str(source),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatchlistEntry) return false
        return id == other.id && qScale == other.qScale && label == other.label &&
            source == other.source && expiresIso == other.expiresIso && emb.contentEquals(other.emb)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + emb.contentHashCode()
        h = 31 * h + qScale.hashCode()
        h = 31 * h + label.hashCode()
        h = 31 * h + source.hashCode()
        h = 31 * h + expiresIso.hashCode()
        return h
    }

    companion object {
        const val RECORD_TYPE = "watch"
        const val RECORD_TYPE_KEY = "t"

        /** Watchlist ids are `wl_<ulid>` per AGENTS.md §2. */
        private val ID_PATTERN = Regex("^wl_[0-9A-HJKMNP-TV-Z]{26}$")

        fun decode(text: String): WatchlistEntry? = decode(JsonParser.parseObjectOrNull(text))

        fun decode(obj: JsonValue.Obj?): WatchlistEntry? {
            if (obj == null) return null
            val type = obj.string(RECORD_TYPE_KEY)
            if (type != null && type != RECORD_TYPE) return null
            val id = obj.string("id") ?: return null
            if (!ID_PATTERN.matches(id)) return null
            val emb = Base64Codec.decodeOrNull(obj.string("emb") ?: return null) ?: return null
            val qScale = obj.float("qScale") ?: return null
            val label = obj.string("label") ?: return null
            val source = obj.string("source") ?: return null
            val expires = obj.string("exp") ?: return null
            if (!IsoInstant.isValid(expires)) return null
            return WatchlistEntry(id, emb, qScale, label, source, expires)
        }
    }
}

/**
 * Builds `kasoti-sync/1` bundles.
 *
 * The exporter's whole job is to be *uninteresting*: it never reorders arbitrarily, never
 * re-encodes and never "helpfully" drops a record. Everything that could go wrong — signing the
 * wrong bytes, mislabelling the sequence range, exceeding a transport budget — is a check that
 * fails loudly with a typed error instead of producing a bundle the receiver must then diagnose.
 */
class BundleExporter(private val hmac: Hmac) {

    /**
     * Export a contiguous slice of this device's diary.
     *
     * @param records in any order. The exporter sorts by `(ts, device, id)`, so re-exporting the
     *   same slice is byte-identical and a receiver that merges two exports of overlapping days
     *   sees a deterministic order.
     * @param qScale the nominal scale of the slice. Each record carries its own authoritative
     *   scale; the header value is a fast consistency check for the receiver.
     */
    fun export(
        device: String,
        records: List<CrossingEvent>,
        key: ByteArray,
        nowMillis: Long,
        embModel: String,
        post: String,
        kid: String = SyncEnvelope.DEFAULT_KID,
        qScale: Float? = null,
    ): SyncBundle {
        require(EmbModel.isValid(embModel)) { "embModel '$embModel' is not name@sha256:<64 hex>" }
        val ordered = records.sortedWith(ORDER)
        // Derived from the *ordered* slice, never from the caller's ordering: two exports of the
        // same records in different input orders must be byte-identical, or "re-export this day"
        // produces a different HMAC and looks like tampering.
        val nominalScale = qScale ?: ordered.firstOrNull()?.qScale ?: 1f
        val seqStart = ordered.minOfOrNull { it.seq } ?: 0L
        val seqEnd = ordered.maxOfOrNull { it.seq } ?: 0L
        val envelope = SyncEnvelope(
            v = SyncEnvelope.PROTOCOL_VERSION,
            kid = kid,
            device = device,
            seqStart = seqStart,
            seqEnd = seqEnd,
            ts = IsoInstant.format(nowMillis),
            embModel = embModel,
            qScale = nominalScale,
            records = ordered.map { it.canonical() },
            hmac = "",
        ).signed(hmac, key)
        return SyncBundle(fileName(device, post, seqStart, seqEnd, 0), envelope.encode(), envelope)
    }

    /**
     * Split a slice into transport-sized bundles (SYNC.md §5: 5 MB files).
     *
     * Records are never split across files. A record that cannot fit a file on its own is a
     * typed failure, because the alternative — truncating an embedding — yields a record that
     * decodes into a plausible-looking but wrong face.
     */
    fun exportSplit(
        device: String,
        records: List<CrossingEvent>,
        key: ByteArray,
        nowMillis: Long,
        embModel: String,
        post: String,
        reg: ThresholdRegistry,
        kid: String = SyncEnvelope.DEFAULT_KID,
    ): List<SyncBundle> {
        val limit = reg[ThresholdName.SYNC_FILE_SPLIT_BYTES].toInt()
        val ordered = records.sortedWith(ORDER)
        val batches = mutableListOf<List<CrossingEvent>>()
        var current = mutableListOf<CrossingEvent>()
        var currentBytes = ENVELOPE_OVERHEAD_BYTES
        for (record in ordered) {
            val recordBytes = record.canonical().length
            if (currentBytes + recordBytes > limit && current.isNotEmpty()) {
                batches += current
                current = mutableListOf()
                currentBytes = ENVELOPE_OVERHEAD_BYTES
            }
            if (currentBytes + recordBytes > limit) {
                throw BundleTooLargeException(record.id, currentBytes + recordBytes, limit)
            }
            current += record
            currentBytes += recordBytes
        }
        if (current.isNotEmpty()) batches += current

        return batches.mapIndexed { index, batch ->
            val bundle = export(device, batch, key, nowMillis, embModel, post, kid)
            if (index == 0) bundle else SyncBundle(suffixed(bundle.fileName, index), bundle.text, bundle.envelope)
        }
    }

    /**
     * Build a QR-sized watchlist pack (SYNC.md §5: QR carries watchlists and provisioning only).
     *
     * Diary deltas never travel this way — a QR holds about 3 KB and a day of a busy post does
     * not. The budget is enforced against the *encoded* size including the HMAC, because the
     * scanner counts the whole symbol and a pack one byte over is a pack that will not scan.
     *
     * @throws WatchlistPackTooLargeException when the pack exceeds the byte budget. There is no
     *   silent truncation: telling an operator "3 of 9 wanted subjects arrived" without saying
     *   so is the failure mode this exception exists to prevent.
     */
    fun watchlistPack(
        entries: List<WatchlistEntry>,
        key: ByteArray,
        nowMillis: Long,
        embModel: String,
        reg: ThresholdRegistry,
        device: String = WATCHLIST_DEVICE,
        kid: String = SyncEnvelope.DEFAULT_KID,
    ): SyncBundle {
        val labelLimit = reg[ThresholdName.WATCHLIST_LABEL_MAX_CHARS].toInt()
        entries.forEach { entry ->
            require(ID_PATTERN.matches(entry.id)) { "watchlist id '${entry.id}' is not wl_<ulid>" }
            require(entry.label.length <= labelLimit) {
                "watchlist entry ${entry.id} has a ${entry.label.length}-char label, over the $labelLimit-char QR budget"
            }
            require(entry.emb.isNotEmpty()) { "watchlist entry ${entry.id} carries an empty embedding" }
            require(entry.qScale > 0f) { "watchlist entry ${entry.id} has a non-positive qScale" }
            require(IsoInstant.isValid(entry.expiresIso)) {
                "watchlist entry ${entry.id} expiry '${entry.expiresIso}' is not a UTC ISO-8601 instant"
            }
        }
        val envelope = SyncEnvelope(
            v = SyncEnvelope.PROTOCOL_VERSION,
            kid = kid,
            device = device,
            seqStart = 0L,
            seqEnd = 0L,
            ts = IsoInstant.format(nowMillis),
            embModel = embModel,
            qScale = entries.firstOrNull()?.qScale ?: 1f,
            records = entries.map { it.canonical() },
            hmac = "",
        ).signed(hmac, key)
        val bundle = SyncBundle(WATCHLIST_FILE_NAME, envelope.encode(), envelope)
        val budget = reg[ThresholdName.QR_PACKET_MAX_BYTES].toInt()
        if (bundle.sizeBytes > budget) {
            throw WatchlistPackTooLargeException(entries.size, bundle.sizeBytes, budget)
        }
        return bundle
    }

    private fun suffixed(fileName: String, index: Int): String =
        fileName.removeSuffix(".json") + "_part$index.json"

    companion object {
        const val WATCHLIST_FILE_NAME = "kasoti_watchlist.json"

        /**
         * A pack is not a device's diary, so it carries a reserved device id. Using a real
         * device id would make a pack look like a sequence-less delta in every log that lists
         * bundles by sender.
         */
        const val WATCHLIST_DEVICE = "hq-watchlist"

        private val ID_PATTERN = Regex("^wl_[0-9A-HJKMNP-TV-Z]{26}$")

        /** Fixed envelope fields in UTF-8 bytes, plus headroom for the 64-hex HMAC. */
        private const val ENVELOPE_OVERHEAD_BYTES = 512

        /** Same total order the receiver merges in (SYNC.md §4 step 3), so a re-export is stable. */
        private val ORDER: Comparator<CrossingEvent> =
            compareBy<CrossingEvent> { IsoInstant.parse(it.ts) ?: 0L }
                .thenBy { it.device }
                .thenBy { it.id }

        /** `kasoti_sync_<post>_<seqStart>_<seqEnd>[_partN]_<device>.json` (SYNC.md §5). */
        fun fileName(device: String, post: String, seqStart: Long, seqEnd: Long, index: Int): String {
            val suffix = if (index == 0) "" else "_part$index"
            return "kasoti_sync_${post}_${seqStart}_${seqEnd}${suffix}_${device}.json"
        }
    }
}

/** A single record cannot fit the transport budget. Truncating it is not an option. */
class BundleTooLargeException(recordId: String, sizeBytes: Int, limitBytes: Int) :
    IllegalStateException("record $recordId needs $sizeBytes bytes but the transport limit is $limitBytes")

/** The watchlist pack does not fit the QR budget; send fewer entries or split by source. */
class WatchlistPackTooLargeException(entryCount: Int, sizeBytes: Int, limitBytes: Int) :
    IllegalStateException("watchlist pack of $entryCount entries is $sizeBytes bytes, over the $limitBytes-byte QR budget")

/** What a watchlist pack contained when it arrived. */
data class WatchlistImport(
    val verified: Boolean,
    val outcome: WatchlistOutcome,
    val entries: List<WatchlistEntry>,
    val expired: List<WatchlistEntry>,
    val sizeBytes: Int,
)

enum class WatchlistOutcome {
    ACCEPTED,
    REJECTED_HMAC,
    REJECTED_MALFORMED,
    QUARANTINED_MODEL_MISMATCH,
}

object WatchlistPackReader {

    /**
     * Verify and read a pack. Deliberately separate from [BundleImporter]: a pack has no diary
     * records, no sequence range and no replay risk, so running it through the delta importer
     * would emit a merge report full of meaningless zeroes.
     */
    fun read(
        bytes: ByteArray,
        key: ByteArray,
        hmac: Hmac,
        localEmbModel: String,
        nowMillis: Long,
    ): WatchlistImport {
        val size = bytes.size
        val envelope = SyncEnvelope.decode(bytes)
            ?: return WatchlistImport(false, WatchlistOutcome.REJECTED_MALFORMED, emptyList(), emptyList(), size)
        if (!envelope.verify(hmac, key)) {
            return WatchlistImport(false, WatchlistOutcome.REJECTED_HMAC, emptyList(), emptyList(), size)
        }
        if (!EmbModel.sameGeneration(envelope.embModel, localEmbModel)) {
            return WatchlistImport(false, WatchlistOutcome.QUARANTINED_MODEL_MISMATCH, emptyList(), emptyList(), size)
        }
        val decoded = envelope.records.map { WatchlistEntry.decode(it) }
        if (decoded.any { it == null }) {
            return WatchlistImport(false, WatchlistOutcome.REJECTED_MALFORMED, emptyList(), emptyList(), size)
        }
        val entries = decoded.filterNotNull()
        val expired = entries.filter { (IsoInstant.parse(it.expiresIso) ?: Long.MAX_VALUE) < nowMillis }
        return WatchlistImport(true, WatchlistOutcome.ACCEPTED, entries, expired, size)
    }
}
