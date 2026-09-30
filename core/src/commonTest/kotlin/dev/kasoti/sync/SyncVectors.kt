package dev.kasoti.sync

import dev.kasoti.crypto.Hex

/**
 * Golden vectors for `kasoti-sync/1` (SYNC.md §8).
 *
 * They are constants rather than a resource file because `:core` is multiplatform common code
 * with no resource loader, and because a vector that lives next to the code it pins is easier
 * to review than one that lives in a directory nobody opens.
 *
 * Two kinds of vector, with two different jobs:
 *
 *  - [CANONICAL] vectors pin the *serialisation*. `:core` fully verifies these: parse the input,
 *    write it back, and the bytes must be identical. That is the whole guarantee SYNC.md §2
 *    asks for — two devices holding the same data produce the same text.
 *  - [HMAC] vectors pin the *primitive*, and are the one thing `:core` cannot verify on its own,
 *    because the HMAC is injected (`AGENTS.md §5` forbids hand-rolled crypto and common code has
 *    no JCA). The expected hex below was produced with `javax.crypto`/OpenSSL and is asserted by
 *    the `:platform` suite, where the JCA implementation actually lives. What `:core` *can* and
 *    does verify — in `everySingleByteFlipBreaksTheHmac` — is that these exact bytes are the
 *    bytes the envelope authenticates, which is the half of the property that a bug in our
 *    canonical form or envelope layout would break.
 *
 * `eval/fixtures/sync/vectors.json` is the eval-side copy of this list and must stay in step.
 */
object SyncVectors {

    /** The deployment key the vectors were generated with. Never a real deployment secret. */
    val KEY: ByteArray = "kasoti-prototype-secret".toByteArray(Charsets.UTF_8)

    /** `key -> canonical payload -> HMAC-SHA256 hex`, all three verified in `sync/` tests. */
    data class HmacVector(val name: String, val payload: String, val expectedHex: String)

    data class CanonicalVector(val name: String, val input: String, val canonical: String)

    /**
     * `qScale` is written as `1.0` rather than `1` because the envelope field is a `Float` node
     * (DESIGN.md §4's `qScale`) and canonical output must not depend on which numeric type a
     * field happens to use.
     */
    val CANONICAL: List<CanonicalVector> = listOf(
        CanonicalVector(
            name = "sorted-keys-no-whitespace",
            input = """{ "seqStart" : 1 , "device" : "post7-ph1" , "v" : 1 }""",
            canonical = """{"device":"post7-ph1","seqStart":1,"v":1}""",
        ),
        CanonicalVector(
            name = "nested-objects-and-arrays-keep-array-order",
            input = """{"a":[3,1,2],"b":{"y":2,"x":1}}""",
            canonical = """{"a":[3,1,2],"b":{"x":1,"y":2}}""",
        ),
        CanonicalVector(
            // A sender that spells control characters and printable ASCII as \u escapes must
            // land on the short form, or two devices holding the same string would produce
            // different bytes and every comparison of theirs would fail.
            name = "unicode-escapes-are-shortened",
            input = "{\"k\":\"a\\u0009b\\u0020c\\u0062d\"}",
            canonical = "{\"k\":\"a\\tb cbd\"}",
        ),
        CanonicalVector(
            name = "escapes-round-trip-unchanged",
            input = "{\"k\":\"tab\\there\\u0000nul\\\"quote\\\\slash\"}",
            canonical = "{\"k\":\"tab\\there\\u0000nul\\\"quote\\\\slash\"}",
        ),
        CanonicalVector(
            name = "unicode-is-emitted-as-utf8",
            input = "{\"post\":\"नगर\"}",
            canonical = "{\"post\":\"नगर\"}",
        ),
        CanonicalVector(
            name = "integers-floats-and-boolean",
            input = """{"a":-42,"b":0.87,"c":true,"d":null}""",
            canonical = """{"a":-42,"b":0.87,"c":true,"d":null}""",
        ),
        CanonicalVector(
            name = "empty-bundle-payload",
            input = CANONICAL_EMPTY_ENVELOPE_PAYLOAD,
            canonical = CANONICAL_EMPTY_ENVELOPE_PAYLOAD,
        ),
    )

    val HMAC: List<HmacVector> = listOf(
        // Proves the injected-MAC contract itself before it is trusted with protocol payloads:
        // a short key, a long message, and an empty message all produce distinct, stable digests.
        HmacVector(
            name = "rfc2202-sha256-key-and-message",
            payload = "The quick brown fox jumps over the lazy dog",
            expectedHex = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        ),
        HmacVector(
            name = "empty-envelope-payload",
            payload = CANONICAL_EMPTY_ENVELOPE_PAYLOAD,
            expectedHex = "6a9491954a7c3bd1569ea6a2454c59518c211fdf512e216df9e9b86c3f71946c",
        ),
    )

    /** All hex digests are 32 bytes of lowercase hex — the shape `SyncEnvelope.hmac` requires. */
    fun isWellFormedHex(hex: String): Boolean = hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }

    fun decodeOrNull(hex: String): ByteArray? = Hex.decode(hex)

    /** The exact authenticated payload of a bundle carrying no records. */
    const val CANONICAL_EMPTY_ENVELOPE_PAYLOAD: String =
        "{\"device\":\"post7-ph1\",\"embModel\":\"emb_v1@sha256:" +
            "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
            "\",\"kid\":\"k1\",\"qScale\":1.0,\"records\":[],\"seqEnd\":0,\"seqStart\":0," +
            "\"ts\":\"2026-03-14T09:00:00.000Z\",\"v\":1}"
}
