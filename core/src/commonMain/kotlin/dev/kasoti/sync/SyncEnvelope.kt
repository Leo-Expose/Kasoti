package dev.kasoti.sync

import dev.kasoti.crypto.Hex
import dev.kasoti.crypto.Hmac
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
 * The `kasoti-sync/1` envelope (SYNC.md §2).
 *
 * ```json
 * {"v":1,"kid":"k1","device":"…","seqStart":n,"seqEnd":m,"ts":"…Z",
 *  "embModel":"emb_v1@sha256:…","qScale":0.0123,
 *  "records":[…],"hmac":"<hex>"}
 * ```
 *
 * Two decisions are worth stating outright because they look like protocol violations and are
 * not:
 *
 *  - **The HMAC covers the header minus `hmac`.** Signing a field with itself is impossible;
 *    excluding it is the only construction where verification is a pure function of what
 *    arrived. The excluded field is exactly one, and [headerJson] is the only thing that is
 *    ever hashed, so there is no second code path that could disagree.
 *  - **[records] holds canonical JSON *text* here but is written as JSON *objects* on the
 *    wire.** Each record's bytes are produced by [dev.kasoti.json.CanonicalJson] and are
 *    embedded verbatim. Splicing them in rather than re-encoding guarantees that the bytes the
 *    HMAC was computed over are the bytes that arrive, even for a record carrying fields this
 *    build has never heard of.
 *
 * [embModel] is in the header because DESIGN.md's comparability law requires the generation to
 * be stated *per bundle*, not merely per record: a bundle whose header lies about the
 * generation is a bundle that should be quarantined whole, and that decision has to be possible
 * before any record is trusted.
 */
data class SyncEnvelope(
    val v: Int,
    val kid: String,
    val device: String,
    val seqStart: Long,
    val seqEnd: Long,
    val ts: String,
    val embModel: String,
    val qScale: Float,
    /** Canonical JSON text, one entry per record, in the order they will be merged. */
    val records: List<String>,
    val hmac: String,
) {
    init {
        require(v == PROTOCOL_VERSION) { "unsupported sync protocol version $v" }
        require(kid.isNotBlank()) { "kid (key id) must not be blank" }
        require(device.isNotBlank()) { "device must not be blank" }
        require(seqStart >= 0 && seqEnd >= seqStart) { "sequence range $seqStart..$seqEnd is not well-ordered" }
    }

    /** The authenticated payload: every field except `hmac`. */
    fun headerJson(): JsonValue.Obj = jsonObj(
        "device" to JsonValue.Str(device),
        "embModel" to JsonValue.Str(embModel),
        "kid" to JsonValue.Str(kid),
        "qScale" to JsonValue.F32(qScale),
        "records" to jsonArr(*records.map { CanonicalJson.verbatim(it) }.toTypedArray()),
        "seqEnd" to JsonValue.Num(seqEnd),
        "seqStart" to JsonValue.Num(seqStart),
        "ts" to JsonValue.Str(ts),
        "v" to JsonValue.Num(v.toLong()),
    )

    /** The canonical UTF-8 bytes covered by the HMAC. */
    fun payloadBytes(): ByteArray = CanonicalJson.writeUtf8(headerJson())

    fun toJson(): JsonValue.Obj = jsonObj(
        "device" to JsonValue.Str(device),
        "embModel" to JsonValue.Str(embModel),
        "hmac" to JsonValue.Str(hmac),
        "kid" to JsonValue.Str(kid),
        "qScale" to JsonValue.F32(qScale),
        "records" to jsonArr(*records.map { CanonicalJson.verbatim(it) }.toTypedArray()),
        "seqEnd" to JsonValue.Num(seqEnd),
        "seqStart" to JsonValue.Num(seqStart),
        "ts" to JsonValue.Str(ts),
        "v" to JsonValue.Num(v.toLong()),
    )

    /** The exact bytes written to disk or the wire. */
    fun encode(): String = CanonicalJson.write(toJson())

    /** @return a copy carrying an HMAC over [payloadBytes] under [key]. */
    fun signed(mac: Hmac, key: ByteArray): SyncEnvelope =
        copy(hmac = Hex.encode(mac.sha256(key, payloadBytes())))

    /** @return true when [mac] agrees with the recorded `hmac`. Never throws on malformed hex. */
    fun verify(mac: Hmac, key: ByteArray): Boolean {
        val given = Hex.decode(hmac) ?: return false
        val expected = mac.sha256(key, payloadBytes())
        return dev.kasoti.crypto.constantTimeEquals(given, expected)
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        /** SYNC.md §6: the default key id for the prototype HMAC profile. */
        const val DEFAULT_KID = "k1"

        /** @return a decoded envelope from raw bundle bytes. */
        fun decode(bytes: ByteArray): SyncEnvelope? = decode(String(bytes, Charsets.UTF_8))

        fun decode(text: String): SyncEnvelope? = decode(JsonParser.parseObjectOrNull(text))

        /** @return the envelope, or `null` for anything this protocol version cannot express. */
        fun decode(obj: JsonValue.Obj?): SyncEnvelope? {
            if (obj == null) return null
            val v = obj.long("v")?.toInt() ?: return null
            if (v != PROTOCOL_VERSION) return null
            val kid = obj.string("kid") ?: return null
            val device = obj.string("device") ?: return null
            val seqStart = obj.long("seqStart") ?: return null
            val seqEnd = obj.long("seqEnd") ?: return null
            val ts = obj.string("ts") ?: return null
            val embModel = obj.string("embModel") ?: return null
            val qScale = obj.float("qScale") ?: return null
        val records = obj.array("records") ?: return null
        val hmac = obj.string("hmac") ?: return null
        if (seqStart < 0 || seqEnd < seqStart) return null
        // Records arrive as JSON objects; each is re-canonicalised here so that [records] holds
        // the exact canonical text this build would have written. Re-canonicalising rather than
        // copying raw bytes is deliberate: it is what turns "the sender sent something that is
        // not canonical" into a MAC mismatch instead of a silently trusted payload.
        val texts = records.map { CanonicalJson.write(it) }
        return SyncEnvelope(v, kid, device, seqStart, seqEnd, ts, embModel, qScale, texts, hmac)
        }
    }
}
