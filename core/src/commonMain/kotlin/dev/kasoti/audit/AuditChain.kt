package dev.kasoti.audit

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import dev.kasoti.json.CanonicalJson
import dev.kasoti.json.JsonValue

/**
 * One recorded decision. This is the unit an officer's action is reconstructed from
 * (FR-S4, invariant I6), so it deliberately carries the *policy versions* that produced it:
 * a verdict cannot be re-interpreted later if the thresholds and fusion rules it used are
 * not recorded alongside it.
 */
data class DecisionRecord(
    val id: String,
    val timestampUtc: String,
    val caseId: String,
    val deviceId: String,
    val verdict: String,
    val findingCodes: List<String>,
    val thresholdVersion: String,
    val fusionRuleVersion: String,
    val embeddingModel: String?,
    val overriddenBy: String? = null,
    val overrideReasonCode: String? = null,
) {
    init {
        require(overriddenBy == null || overrideReasonCode != null) {
            "an override without a reason code is not a valid record ($id)"
        }
    }

    /**
     * Canonical serialisation for hashing: sorted keys, no whitespace, UTF-8.
     * Matches SYNC.md §2's canonical form so the same bytes work in both places.
     */
    /**
     * Canonical serialisation for hashing: sorted keys, no whitespace, UTF-8, via
     * [CanonicalJson] so the escaping rules are the ones the sync wire format already uses
     * rather than a second, hand-rolled set.
     *
     * `findings` is a real JSON array, not a `|`-joined string. Joining made the encoding
     * ambiguous: `["A", "B"]` and `["A|B"]` produced identical bytes, so a record could be
     * swapped for its twin and still verify. Anything that crosses a trust boundary has to
     * be unambiguous, which is the whole point of hashing it.
     *
     * Changing this changes the wire format and therefore every hash already stored. Do it
     * as a versioned change, never as a silent fix.
     */
    fun canonical(): String = CanonicalJson.write(
        JsonValue.Obj(
            mapOf(
                "case" to JsonValue.Str(caseId),
                "device" to JsonValue.Str(deviceId),
                "embed" to (embeddingModel?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "findings" to JsonValue.Arr(findingCodes.map { JsonValue.Str(it) }),
                "fusion" to JsonValue.Str(fusionRuleVersion),
                "id" to JsonValue.Str(id),
                "overrideBy" to (overriddenBy?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "overrideReason" to (overrideReasonCode?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "thr" to JsonValue.Str(thresholdVersion),
                "ts" to JsonValue.Str(timestampUtc),
                "verdict" to JsonValue.Str(verdict),
            ),
        ),
    )
}

/** Result of checking a chain end to end. */
data class ChainVerification(
    val valid: Boolean,
    val length: Int,
    /** Index of the first record whose hash does not follow from its predecessor. */
    val brokenAtIndex: Int?,
    val reason: String = "",
)

/**
 * Append-only hash chain over decisions (invariant I6, THREAT_MODEL.md §7).
 *
 * Each record's hash covers its own canonical bytes *and* the previous hash, so removing or
 * reordering a record invalidates everything after it. The digest is injected, keeping this
 * class pure and testable while the actual SHA-256 comes from the platform (AGENTS.md §5).
 */
class AuditChain(private val digest: Digest) {

    private val records = mutableListOf<DecisionRecord>()
    private val hashes = mutableListOf<String>()

    val size: Int get() = records.size

    /** Genesis hash: the chain must start somewhere and that somewhere is fixed. */
    private val genesis: String = Hex.encode(digest.sha256(GENESIS_SEED.toByteArray(Charsets.UTF_8)))

    fun tip(): String = hashes.lastOrNull() ?: genesis

    fun append(record: DecisionRecord): String {
        val payload = record.canonical() + "|" + tip()
        val hash = Hex.encode(digest.sha256(payload.toByteArray(Charsets.UTF_8)))
        records += record
        hashes += hash
        return hash
    }

    fun all(): List<DecisionRecord> = records.toList()

    /** Recomputes the chain from scratch. Used by the audit screen and by the test suite. */
    fun verify(): ChainVerification {
        var previous = genesis
        for ((i, record) in records.withIndex()) {
            val expected = Hex.encode(
                digest.sha256((record.canonical() + "|" + previous).toByteArray(Charsets.UTF_8)),
            )
            if (expected != hashes[i]) {
                return ChainVerification(false, records.size, i, "record ${record.id} does not hash to its stored value")
            }
            previous = expected
        }
        return ChainVerification(true, records.size, null)
    }

    /**
     * Verifies a complete log supplied from elsewhere (a merged log, a case bundle) against
     * this chain. The incoming records are compared directly rather than trusted, so a bundle
     * cannot assert its own integrity without proving it.
     *
     * The question this answers is "is this the same log I hold?", so the lengths must agree.
     * A shorter log is a *truncation*, not a subset: every record in it matches, so a
     * per-record comparison alone would happily call it valid, and an attacker who can
     * shorten a bundle can otherwise delete decisions. That was the I6 fail-open case — an
     * empty bundle used to verify against a chain of five, because the old implementation
     * short-circuited on an empty list and otherwise only compared the final tip.
     *
     * Comparing record by record rather than only the tip is also what makes
     * [ChainVerification.brokenAtIndex] name the record that actually differs.
     */
    fun verifyExternal(incoming: List<DecisionRecord>): ChainVerification {
        if (incoming.size != records.size) {
            return ChainVerification(
                valid = false,
                length = incoming.size,
                brokenAtIndex = minOf(incoming.size, records.size),
                reason = "supplied log has ${incoming.size} records, chain has ${records.size}",
            )
        }
        for ((i, record) in incoming.withIndex()) {
            if (records[i].canonical() != record.canonical()) {
                return ChainVerification(
                    valid = false,
                    length = incoming.size,
                    brokenAtIndex = i,
                    reason = "record ${record.id} does not match the chain at position $i",
                )
            }
        }
        return ChainVerification(true, incoming.size, null)
    }

    private companion object {
        const val GENESIS_SEED = "kasoti-audit/1"
    }
}
