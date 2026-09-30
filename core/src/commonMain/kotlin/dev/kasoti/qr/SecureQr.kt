package dev.kasoti.qr

import dev.kasoti.crypto.Hex
import dev.kasoti.crypto.SignatureVerifier

/**
 * A parsed secure-QR payload (FR-Q1).
 *
 * [signed] decides how the payload is allowed to be used, and it is the single most
 * consequential bit in the whole QR path: an unsigned QR is not evidence of anything, it is
 * only a source of claims to cross-check against what is printed (A-QR-01). A forger can
 * print *any* unsigned QR, so treating it as authoritative would make the layer worthless.
 */
data class QrPayload(
    val raw: ByteArray,
    val version: Int,
    val fields: Map<String, String>,
    val signature: ByteArray?,
    val signed: Boolean,
) {
    val referenceId: String? get() = fields["referenceId"] ?: fields["ra"]
    val name: String? get() = fields["name"]
    val dateOfBirth: String? get() = fields["dob"]
    val gender: String? get() = fields["gender"]
    val district: String? get() = fields["dist"]
    val state: String? get() = fields["state"]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrPayload) return false
        return version == other.version &&
            fields == other.fields &&
            signed == other.signed &&
            raw.contentEquals(other.raw) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null))
    }

    override fun hashCode(): Int {
        var r = raw.contentHashCode()
        r = 31 * r + version
        r = 31 * r + fields.hashCode()
        r = 31 * r + signed.hashCode()
        return r
    }
}

/** Outcome of verifying the payload's signature. */
sealed interface SigResult {
    data class Valid(val keyId: String) : SigResult
    data object Invalid : SigResult

    /** The payload is well-formed but the bundled keys are past their rotation date. */
    data class StaleKeys(val keyId: String) : SigResult

    /** No key matched, and that itself is a finding: a signature from an unknown issuer. */
    data object UnknownKey : SigResult
}

/**
 * The bundled public keys (DESIGN.md §6, THREAT_MODEL.md §6).
 *
 * `uidai_test` and `uidai_prod` are separate slots so a demo can never be mistaken for a
 * production verification, and so the prod slot can be shipped empty until provenance is
 * confirmed. A payload that verifies against the *test* ring is reported with
 * [SigResult.Valid] plus the key id — fusion is expected to treat a test-key verification as
 * a simulated result and label it, never as a real one.
 */
data class KeyRing(
    val keys: Map<String, KeyEntry>,
    /** ISO date after which any key in this ring is considered stale. */
    val rotatedAfter: String? = null,
) {
    data class KeyEntry(
        val keyId: String,
        val x509PublicKey: ByteArray,
        val issuedAt: String,
        val expiresAt: String?,
        val provenance: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is KeyEntry && keyId == other.keyId && x509PublicKey.contentEquals(other.x509PublicKey)

        override fun hashCode(): Int = 31 * keyId.hashCode() + x509PublicKey.contentHashCode()
    }

    companion object {
        /**
         * Test ring with no keys. Fixtures supply the ring explicitly; the production
         * bundle is a separate, provenance-reviewed file. Shipping an empty default means a
         * missing key file degrades to "cannot verify" rather than to "verified".
         */
        fun empty(): KeyRing = KeyRing(keys = emptyMap())
    }
}

/** Parse and verify, in that order: nothing is trusted until the signature checks out. */
object SecureQr {

    /**
     * Verify [payload] against every key in [ring].
     *
     * Keys are tried in order and the first valid one wins. A failed verification is
     * reported, never thrown: malformed signatures are an expected input from a forger.
     */
    fun verify(payload: QrPayload, ring: KeyRing, verifier: SignatureVerifier): SigResult {
        if (!payload.signed || payload.signature == null) return SigResult.Invalid

        var matched: String? = null
        for (entry in ring.keys.values) {
            if (verifier.verifyRsaSha256(entry.x509PublicKey, payload.raw, payload.signature)) {
                matched = entry.keyId
                break
            }
        }
        val keyId = matched ?: return SigResult.UnknownKey
        return if (isStale(ring, keyId)) SigResult.StaleKeys(keyId) else SigResult.Valid(keyId)
    }

    private fun isStale(ring: KeyRing, keyId: String): Boolean {
        val entry = ring.keys[keyId] ?: return false
        val cutoff = ring.rotatedAfter ?: return false
        val expiry = entry.expiresAt ?: return false
        // Plain lexicographic comparison is correct for ISO-8601 dates, and avoids pulling
        // a date parser into the hot path of a screening.
        return expiry < cutoff
    }
}

/** The unsigned/legacy QR path (FR-Q2): parse, then contribute consistency only. */
object UnsignedQr {
    /**
     * Parses `key=value` pairs separated by `&`, as used by several legacy card formats.
     * @return an empty map when nothing parses, so the caller treats it as "no information".
     */
    fun parse(text: String): Map<String, String> =
        text.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else pair.substring(0, idx).trim().lowercase() to pair.substring(idx + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
}

/** Field-level disagreement between a QR claim and the printed document (R-QR-02). */
data class QrMismatch(val field: String, val qrValue: String, val printValue: String)

object QrCrossCheck {

    /**
     * Compares the QR's identity claims against what is printed on the document.
     *
     * A disagreement here is strong evidence (R-QR-02) because the QR is harder to alter
     * convincingly than the visual zone: the attacker must re-sign, and a signature they
     * cannot produce rules the whole forgery out. That is exactly why the *unsigned* path
     * never uses this function.
     */
    fun compare(payload: QrPayload, printName: String, printDob: String): List<QrMismatch> {
        val out = mutableListOf<QrMismatch>()
        val qrName = payload.name
        val qrDob = payload.dateOfBirth

        if (qrName != null && printName.isNotBlank()) {
            if (dev.kasoti.checks.VizMrzMatch.nameScore(printName, qrName) < 1f) {
                out += QrMismatch("name", qrName, printName)
            }
        }
        if (qrDob != null && printDob.isNotBlank()) {
            if (qrDob != printDob) out += QrMismatch("dob", qrDob, printDob)
        }
        return out
    }

    /** Hex form of the signature, for the case bundle and the audit record. */
    fun signatureHex(payload: QrPayload): String? = payload.signature?.let { Hex.encode(it) }
}
