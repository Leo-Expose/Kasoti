package dev.kasoti.android.platform

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hmac
import dev.kasoti.crypto.SignatureVerifier
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The JCA-backed primitives, for Android (DESIGN.md §3, "Crypto: JCA (RSA/EC/AES) + Keystore").
 *
 * ## Why these live in `:app-android` and not `:platform`
 *
 * `:platform` owns the `expect`/`actual` surface, but it currently only has a `jvmMain`, whose
 * actuals are written against `java.awt` / `ImageIO` and therefore cannot be resolved from an
 * Android module at all. Adding an `androidMain` source set to `:platform` is the right end
 * state and needs a `namespace`/`compileSdk` in that module's build file plus an ownership
 * handover; this work does not own `:platform`.
 *
 * So the Android implementations are here, in `dev.kasoti.android.platform.*`, and each one
 * documents its intended home. Moving them is a package rename plus a module dependency — no
 * logic moves, and the logic is what the tests in this module cover.
 *
 * ## Hand-rolled crypto is forbidden (AGENTS.md §5)
 *
 * Everything below delegates to the platform provider. Where a construction is questionable
 * (an HMAC key of a bare password, below) the concern is named in the KDoc rather than left
 * implicit.
 *
 * ## No `android.*` imports in this file
 *
 * That is deliberate and load-bearing: it is what lets `app-android`'s unit tests exercise
 * these classes on a bare JVM. A `MessageDigest` is not an Android API.
 */
class JcaDigest : Digest {
    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(bytes)

    private companion object {
        const val ALGORITHM = "SHA-256"
    }
}

/**
 * HMAC-SHA256 over a raw key (SYNC.md §4, D3).
 *
 * The 20-line JCA form on both platforms, as D3 chose. `SecretKeySpec` with a raw byte array
 * is the correct construction *given a key*; the risk with HMAC is the key, not the MAC, and
 * that risk is handled at provisioning (a 32-byte random secret generated once and stored in
 * the keystore) rather than here.
 */
class JcaHmac(private val key: ByteArray) : Hmac {
    override fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        // The instance's own key is used when the caller passes the constructor's; the
        // interface takes it per call so the same object can serve a rotated key.
        val effective = if (key.isEmpty()) this.key else key
        mac.init(SecretKeySpec(effective, ALGORITHM))
        return mac.doFinal(data)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

/**
 * RSA-SHA256 verification against a DER `SubjectPublicKeyInfo` (FR-Q1).
 *
 * The interface's contract is explicit that a malformed key or signature must return `false`
 * rather than throw, because a bad QR is an *expected input* from a forger, not an exception.
 * Every failure path here therefore ends in `false`.
 */
class JcaSignatureVerifier : SignatureVerifier {

    override fun verifyRsaSha256(x509PublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        if (x509PublicKey.isEmpty() || signature.isEmpty()) {
            false
        } else {
            val key = KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(x509PublicKey))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        // `X509EncodedKeySpec` throws this on bytes that are not a SubjectPublicKeyInfo.
        false
    } catch (_: ArrayIndexOutOfBoundsException) {
        // A truncated signature is a forged input, not a crash.
        false
    }

    private companion object {
        const val KEY_ALGORITHM = "RSA"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }
}

/**
 * Derives an HMAC key from a supervisor PIN (FR-H5, AGENTS.md §5).
 *
 * ## The concern, named
 *
 * A four-digit PIN has about 13 bits of entropy, so a plain `SecretKeySpec(pin)` is brute-forceable
 * by anyone holding the file. PBKDF2 with a per-device salt raises the cost of each guess, which
 * is the mitigation available without a secure element. It does **not** make a four-digit PIN
 * strong, and the field lane accepts a four-digit PIN because SPEC §3 requires a flow a jawan
 * can complete one-handed with gloves on (THREAT_MODEL.md accepts the insider-with-the-handset
 * threat as out of scope for the fast path).
 *
 * Storing the derived key rather than the PIN is the part that matters operationally: a
 * keystore-backed blob that yields the HMAC key is a far smaller loss than a file containing a
 * PIN an operator reuses for their bank.
 */
class PinKeyDeriver(
    private val salt: ByteArray,
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    init {
        require(salt.isNotEmpty()) { "a per-device salt is required; an empty one is worse than none" }
        require(iterations >= MIN_ITERATIONS) {
            "PBKDF2 iterations below $MIN_ITERATIONS are not worth the name"
        }
    }

    fun derive(pin: String): ByteArray {
        require(pin.length >= MIN_PIN_LENGTH) { "PIN is too short to be a PIN" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_BITS = 256
        const val MIN_PIN_LENGTH = 4

        /**
         * 210 000 rounds.
         *
         * OWASP's 2023 floor for PBKDF2-HMAC-SHA256 is 600 000 for *passwords*; this is a
         * four-digit PIN over an HMAC key, and the threat is a stolen file rather than an
         * online guessing attack, so the cost is set where it keeps an unlock under a second
         * on a low-end phone. The value is named so a reviewer can see the trade rather than
         * find a literal in a constructor call.
         */
        const val DEFAULT_ITERATIONS = 210_000
        const val MIN_ITERATIONS = 10_000
    }
}

/**
 * Signing with a keystore-held key.
 *
 * Not on the verdict path — KASOTI verifies, it never signs, because there is nothing here that
 * should be able to produce an assertion. It exists for the sync-bundle HMAC test and for
 * provisioning receipts, and it is here rather than in a util file so that the *absence* of a
 * signing path on the screening path is a visible fact.
 */
class KeystoreSigner(private val privateKey: PrivateKey) {
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }

    companion object {
        /** Reads a PKCS#8 private key. Provided for provisioning, not for the verdict path. */
        fun fromPkcs8(der: ByteArray): PrivateKey =
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }
}
