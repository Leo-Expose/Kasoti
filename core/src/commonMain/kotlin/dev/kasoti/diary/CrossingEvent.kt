package dev.kasoti.diary

import dev.kasoti.crypto.Hex
import dev.kasoti.face.Embedding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.json.Base64Codec
import dev.kasoti.json.CanonicalJson
import dev.kasoti.json.JsonParser
import dev.kasoti.json.JsonValue
import dev.kasoti.json.array
import dev.kasoti.json.float
import dev.kasoti.json.jsonArr
import dev.kasoti.json.jsonObj
import dev.kasoti.json.long
import dev.kasoti.json.string

/**
 * One person presenting one document at one border post (SYNC.md §3, DESIGN.md §4).
 *
 * What is deliberately *absent* is the point. There is no name — only [nameSha], a salted
 * hash keyed by the deployment id, so a stolen diary does not read out a roll of who crossed
 * where (THREAT_MODEL.md §4). There is no face image — only a quantised embedding. There is
 * no raw verdict text — only a [Verdict] enum and typed [FindingCode]s, because AGENTS.md §2
 * forbids raw strings reaching the UI and a verdict is the one string an officer acts on.
 *
 * [dob] is the single exception to the hash-everything rule. Alias display needs it
 * (SYNC.md §3), and deployments that prefer to hash it simply set it to `null`, which
 * disables the dob arm of R-ALIAS-01 rather than weakening it.
 *
 * [extra] carries fields this build does not understand. Forward compatibility is not
 * politeness here: a diary written by a newer device must re-verify its own HMAC after a
 * round trip through an older one, and silently dropping unknown keys would break exactly
 * that. Anything we do understand still wins — an unknown field cannot shadow a known one.
 */
data class CrossingEvent(
    val id: String,
    val seq: Long,
    val device: String,
    val post: String,
    val ts: String,
    val track: Track,
    val nameSha: String,
    val dob: String?,
    val docHash: String,
    val embModel: String,
    val emb: ByteArray,
    val qScale: Float,
    val q: Float,
    val verdict: Verdict,
    val findings: List<FindingCode>,
    val prev: String,
    val extra: Map<String, JsonValue> = emptyMap(),
) {
    init {
        require(ID_PATTERN.matches(id)) { "crossing event id '$id' is not evt_<ulid>" }
        require(seq >= 0) { "seq must be non-negative, got $seq" }
        require(device.isNotBlank()) { "device id must not be blank" }
        require(IsoInstant.isValid(ts)) { "ts '$ts' is not a UTC ISO-8601 instant" }
        require(EmbModel.isValid(embModel)) { "embModel '$embModel' is not name@sha256:<64 hex>" }
        require(emb.size == Embedding.DIM) { "embedding must be ${Embedding.DIM} int8 values" }
        require(qScale > 0f && qScale.isFinite()) { "qScale must be positive and finite, got $qScale" }
        require(q >= 0f && q <= 1f) { "quality q must be within [0,1], got $q" }
        require(nameSha.isNotBlank() || docHash.isNotBlank()) {
            "an event needs at least one hashed identity: nameSha, docHash, or both — never a raw name"
        }
        require(nameSha.isBlank() || HASH_PATTERN.matches(nameSha)) {
            "nameSha must be a lowercase hex digest (SYNC.md §3), got '${nameSha.take(12)}…'"
        }
        require(docHash.isBlank() || HASH_PATTERN.matches(docHash)) {
            "docHash must be a lowercase hex digest (SYNC.md §3), got '${docHash.take(12)}…'"
        }
    }

    /** @return the vector as a [QuantisedEmbedding] so callers cannot use the wrong scale. */
    fun quantised(): QuantisedEmbedding = QuantisedEmbedding(emb, qScale)

    /**
     * Canonical serialisation: sorted keys, no whitespace, UTF-8 (SYNC.md §2).
     *
     * This single string is what the diary line, the sync record and the chain hash all use.
     * Three separate serialisers would be three chances for two devices to disagree.
     */
    fun canonical(): String = CanonicalJson.write(toJson())

    /**
     * The chain link input for this record when it follows [previous].
     *
     * `prev` is a *local* link, not part of the immutable payload: a record merged from
     * another device carries that device's chain position, which means nothing here. Re-anchoring
     * on append is what makes the local tip a pure function of the local record order.
     */
    fun canonicalForChain(previous: String): String = copy(prev = previous).canonical()

    /**
     * Re-derives the model tag from a weights digest.
     *
     * Call this whenever a record is copied into a new generation — an offlined device that
     * received new weights must not keep claiming the old tag, because the tag is the only
     * thing that stops a mismatched comparison from producing a confident wrong similarity.
     */
    fun withRecomputedEmbModel(modelName: String, weightsSha256: ByteArray): CrossingEvent =
        copy(embModel = EmbModel.tag(modelName, Hex.encode(weightsSha256)))

    fun toJson(): JsonValue.Obj = jsonObj(
        RECORD_TYPE_KEY to JsonValue.Str(RECORD_TYPE),
        "device" to JsonValue.Str(device),
        "docHash" to JsonValue.Str(docHash),
        "dob" to (dob?.let { JsonValue.Str(it) } ?: JsonValue.Null),
        "emb" to JsonValue.Str(Base64Codec.encode(emb)),
        "embModel" to JsonValue.Str(embModel),
        "findings" to jsonArr(*findings.map { JsonValue.Str(it.name) }.toTypedArray()),
        "id" to JsonValue.Str(id),
        "nameSha" to JsonValue.Str(nameSha),
        "post" to JsonValue.Str(post),
        "prev" to JsonValue.Str(prev),
        "q" to JsonValue.F32(q),
        "qScale" to JsonValue.F32(qScale),
        "seq" to JsonValue.Num(seq),
        "ts" to JsonValue.Str(ts),
        "track" to JsonValue.Str(track.name),
        "verdict" to JsonValue.Str(verdict.name),
    ).mergeExtra(extra)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CrossingEvent) return false
        return id == other.id && seq == other.seq && device == other.device && post == other.post &&
            ts == other.ts && track == other.track && nameSha == other.nameSha && dob == other.dob &&
            docHash == other.docHash && embModel == other.embModel && emb.contentEquals(other.emb) &&
            qScale == other.qScale && q == other.q && verdict == other.verdict &&
            findings == other.findings && prev == other.prev && extra == other.extra
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + seq.hashCode()
        h = 31 * h + device.hashCode()
        h = 31 * h + post.hashCode()
        h = 31 * h + ts.hashCode()
        h = 31 * h + track.hashCode()
        h = 31 * h + nameSha.hashCode()
        h = 31 * h + emb.contentHashCode()
        h = 31 * h + qScale.hashCode()
        h = 31 * h + q.hashCode()
        h = 31 * h + prev.hashCode()
        return h
    }

    companion object {
        const val RECORD_TYPE = "cross"
        const val RECORD_TYPE_KEY = "t"

        /** ULID in canonical Crockford base32: 10 time characters then 16 random ones. */
        private val ID_PATTERN = Regex("^evt_[0-9A-HJKMNP-TV-Z]{26}$")

        /**
         * A hex digest of a real hash function, 128 to 512 bits.
         *
         * Enforced because the facilitator rule groups subjects by a short prefix of `nameSha`
         * (A-FAC-01). If `nameSha` were allowed to be any string, a counter or a shared prefix
         * would put every subject in the diary into one cohort and the rule would flag the entire
         * population. Cheap to check, impossible to notice until a flag says something absurd.
         */
        private val HASH_PATTERN = Regex("^[0-9a-f]{32,128}$")

        private val CLAIMED_KEYS = setOf(
            RECORD_TYPE_KEY, "device", "docHash", "dob", "emb", "embModel", "findings", "id",
            "nameSha", "post", "prev", "q", "qScale", "seq", "ts", "track", "verdict",
        )

        /** @return the record, or `null` if [json] is missing a field or has one of the wrong type. */
        fun decode(json: JsonValue.Obj?): CrossingEvent? {
            if (json == null) return null
            val type = json.string(RECORD_TYPE_KEY)
            if (type != null && type != RECORD_TYPE) return null
            val id = json.string("id") ?: return null
            val seq = json.long("seq") ?: return null
            val device = json.string("device") ?: return null
            val post = json.string("post") ?: return null
            val ts = json.string("ts") ?: return null
            val nameSha = json.string("nameSha") ?: return null
            val docHash = json.string("docHash") ?: return null
            val embModel = json.string("embModel") ?: return null
            val embText = json.string("emb") ?: return null
            val emb = Base64Codec.decodeOrNull(embText) ?: return null
            val q = json.float("q") ?: return null
            val qScale = json.float("qScale") ?: return null
            val verdict = verdictOrNull(json.string("verdict")) ?: return null
            val track = trackOrNull(json.string("track")) ?: return null
            val findings = json.array("findings")?.map { item ->
                findingCodeOrNull((item as? JsonValue.Str)?.value) ?: return null
            } ?: return null
            val extra = json.fields.filterKeys { it !in CLAIMED_KEYS }
            // Constructing validates the record, and validation failing is an ordinary outcome
            // for untrusted input: a bundle with `q: 9.5` must be counted as rejected, not thrown
            // out of the middle of an import that has 400 other records to get through.
            return try {
                CrossingEvent(
                    id = id,
                    seq = seq,
                    device = device,
                    post = post,
                    ts = ts,
                    track = track,
                    nameSha = nameSha,
                    dob = json.string("dob"),
                    docHash = docHash,
                    embModel = embModel,
                    emb = emb,
                    qScale = qScale,
                    q = q,
                    verdict = verdict,
                    findings = findings,
                    prev = json.string("prev") ?: "",
                    extra = extra,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** @return the record parsed from [canonicalJson], or `null` for anything malformed. */
        fun decode(canonicalJson: String): CrossingEvent? = decode(JsonParser.parseObjectOrNull(canonicalJson))


        /**
         * Enum lookup by wire name.
         *
         * `enumValues<E>()` clones the whole constants array on every call on the JVM, and this
         * runs four times per record across a 100k-record import. The maps are built once.
         */
        private val TRACKS = Track.entries.associateBy { it.name }
        private val VERDICTS = Verdict.entries.associateBy { it.name }
        private val FINDING_CODES = FindingCode.entries.associateBy { it.name }

        fun trackOrNull(name: String?): Track? = if (name == null) null else TRACKS[name]
        fun verdictOrNull(name: String?): Verdict? = if (name == null) null else VERDICTS[name]
        fun findingCodeOrNull(name: String?): FindingCode? = if (name == null) null else FINDING_CODES[name]
    }
}

/** Adds unknown-field passthrough without letting an unknown key shadow a known one. */
private fun JsonValue.Obj.mergeExtra(extra: Map<String, JsonValue>): JsonValue.Obj {
    if (extra.isEmpty()) return this
    val merged = LinkedHashMap(fields)
    for ((key, value) in extra) if (key !in merged) merged[key] = value
    return JsonValue.Obj(merged)
}
