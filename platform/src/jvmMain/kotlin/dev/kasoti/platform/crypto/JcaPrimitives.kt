package dev.kasoti.platform.crypto

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hmac
import dev.kasoti.crypto.SignatureVerifier
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SHA-256 backed by the JCA (AGENTS.md §5 forbids hand-rolled crypto).
 *
 * `MessageDigest` is stateful and therefore not thread-safe, so a fresh instance is taken
 * per call rather than cached in a field. The audit chain and the sync envelope are not hot
 * paths, and a shared instance is a genuinely nasty bug to find later.
 */
class JcaDigest : Digest {

    override fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(DIGEST_ALGORITHM).digest(bytes)

    /** Hex of [sha256], so callers do not each re-implement the same `ByteArray` formatting. */
    fun sha256Hex(bytes: ByteArray): String = dev.kasoti.crypto.Hex.encode(sha256(bytes))

    private companion object {
        const val DIGEST_ALGORITHM = "SHA-256"
    }
}

/**
 * HMAC-SHA256 backed by the JCA.
 *
 * The empty key is rejected explicitly. RFC 2104 defines HMAC over a zero-length key, but
 * `SecretKeySpec` refuses to build one, and an empty sync secret is a provisioning fault
 * rather than a protocol case — failing loudly at the call site beats a subtly
 * non-interoperable MAC. SYNC.md §3 mandates 32 bytes.
 */
class JcaHmac : Hmac {

    override fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "HMAC-SHA256 requires a non-empty key (SYNC.md §3 mandates 32 bytes)" }
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(key, MAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private companion object {
        const val MAC_ALGORITHM = "HmacSHA256"
    }
}

/**
 * RSA-SHA256 verification of the Aadhaar secure QR (FR-Q1, THREAT_MODEL.md §6).
 *
 * The single most important property here is that **nothing throws**. `SecureQr.verify`
 * walks a key ring, and a forged QR is a normal, expected input: an attacker controls every
 * byte of the payload, the signature and (by feeding us a bad key file) potentially the key
 * material. An exception escaping this method would turn a forger's malformed signature into
 * a crash loop on the post's screening terminal.
 */
class JcaSignatureVerifier : SignatureVerifier {

    override fun verifyRsaSha256(x509PublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (x509PublicKey.isEmpty() || signature.isEmpty()) return false
        return try {
            val key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(x509PublicKey))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: GeneralSecurityException) {
            // InvalidKeySpecException (garbage DER), SignatureException (wrong-length or
            // corrupted signature), NoSuchAlgorithmException (impossible on a conforming JRE).
            false
        } catch (e: RuntimeException) {
            // `X509EncodedKeySpec` and `Mac` throw IllegalArgumentException on malformed
            // input rather than the checked JCA exceptions. Attacker-controlled, so also false.
            false
        }
    }

    private companion object {
        const val KEY_ALGORITHM = "RSA"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }
}
