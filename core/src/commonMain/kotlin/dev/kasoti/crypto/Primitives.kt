package dev.kasoti.crypto

/**
 * Cryptographic primitives expressed as interfaces.
 *
 * `:core` is a common-source-set module with no platform types, and hand-rolled
 * cryptography is forbidden (AGENTS.md §5). Splitting this way means the *logic* that uses
 * hashing — the audit chain, the sync envelope — stays pure and unit-testable in `:core`,
 * while the actual primitives are provided by the JCA-backed `:platform` module on every
 * target. The interface is deliberately tiny: only what the protocol needs.
 */
interface Digest {
    /** @return the 32-byte SHA-256 digest of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray
}

interface Hmac {
    /** @return the 32-byte HMAC-SHA256 of [data] under [key]. */
    fun sha256(key: ByteArray, data: ByteArray): ByteArray
}

/**
 * Asymmetric signature checking, used for the Aadhaar secure QR (FR-Q1).
 *
 * [x509PublicKey] is a DER-encoded SubjectPublicKeyInfo. Keeping the key as opaque bytes
 * means `:core` needs no crypto library while `:platform` still hands the bytes straight to
 * the JCA `KeyFactory` — so there is exactly one place where key material is parsed.
 */
interface SignatureVerifier {
    /**
     * @return `true` iff [signature] is a valid RSA-SHA256 signature over [data] by
     *   [x509PublicKey]. Must return `false` — never throw — on a malformed key or
     *   signature, because a bad QR is an expected input, not an exceptional one.
     */
    fun verifyRsaSha256(x509PublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4])
            append(DIGITS[v and 0x0F])
        }
    }

    /** @return hex bytes, or `null` for odd length or non-hex characters. */
    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexValue(hex[i * 2])
            val lo = hexValue(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Constant-time comparison, so a merge report cannot leak how much of a MAC matched. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
