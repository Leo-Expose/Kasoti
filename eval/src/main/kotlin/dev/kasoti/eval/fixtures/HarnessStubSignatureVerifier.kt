package dev.kasoti.eval.fixtures

import dev.kasoti.crypto.SignatureVerifier
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **HARNESS STUB — NOT PRODUCTION VERIFICATION.**
 *
 * `:core` declares [SignatureVerifier] as an interface so the pure logic can be tested on
 * any target; the production implementation is the JCA binding in `:platform`
 * (`dev.kasoti.platform.crypto.JcaSignatureVerifier`). This class exists so `:eval` can run
 * the D-QR suite today without depending on `:platform`, which may not be buildable on a
 * machine with no Android SDK and would drag the whole harness behind it.
 *
 * What it does: real RSA/SHA-256 verification through the JDK's own JCA providers, against
 * a throwaway keypair generated in-process at first use.
 *
 * What it deliberately does NOT do:
 *  - it never touches a production key, a key file, or `eval/data/keys/`;
 *  - the keypair is regenerated per JVM, so a fixture signed in one run is not verifiable
 *    in another unless the signature is recomputed — which is why the fixture generator
 *    signs at run time rather than reading signatures from disk;
 *  - it performs no revocation, chain or trust-anchor check, so a signature from *any*
 *    generated key verifies. `SecureQr` is responsible for selecting the key, and the
 *    D-QR suite exercises that selection.
 *
 * Replacing this with `:platform`'s JCA binding must not change any D-QR number. If it
 * does, the stub was wrong about something, and that is exactly the kind of divergence a
 * harness stub is allowed to have only briefly.
 *
 * // TODO(M1,@eval): swap for dev.kasoti.platform.crypto.JcaSignatureVerifier once
 *   :platform builds on the eval host, then delete this file. Until then the D-QR gate
 *   certifies :core's key-selection and tamper-detection logic, not the platform binding.
 */
class HarnessStubSignatureVerifier private constructor(
    private val provider: JcaProvider,
) : SignatureVerifier {

    /**
     * The JDK provider, in one place, so the note above has something concrete to point at.
     * Nothing here implements a primitive; it is a thin adapter over `java.security`.
     */
    private class JcaProvider {
        fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun verify(publicKeyDer: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(publicKeyDer))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun verifyRsaSha256(x509PublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        provider.verify(x509PublicKey, data, signature)

    companion object {
        private var cached: Pair<KeyPair, HarnessStubSignatureVerifier>? = null

        /**
         * A per-JVM keypair. Thread-safe because the harness is single-threaded per suite
         * but [keyPair] may be reached from lazily-initialised fixtures in any order.
         */
        @Synchronized
        fun instance(): Pair<KeyPair, HarnessStubSignatureVerifier> {
            cached?.let { return it }
            val keyPair = JcaProvider().keyPair()
            val verifier = HarnessStubSignatureVerifier(JcaProvider())
            cached = keyPair to verifier
            return cached!!
        }
    }
}

/**
 * The two labels the harness is allowed to attach to key material.
 *
 * DATA.md §7 and EVAL.md §2 are unambiguous: test keys are never production keys, and the
 * filename says so. Making that a type rather than a convention means a production key
 * cannot be passed in without the compiler objecting.
 */
enum class KeyProvenance(val wireValue: String) {
    /** Generated in-process by [HarnessStubSignatureVerifier]. Never written to disk. */
    TEST_HARNESS("test-harness-generated"),

    /** A reviewed, provenance-noted key bundled for the demo. Never a signing key. */
    TEST_DEMO("test-demo-bundled"),
    ;

    val isTest: Boolean get() = true
}

/** An X.509 public key with a filename that cannot be mistaken for a production key. */
data class TestPublicKey(
    val keyId: String,
    val der: ByteArray,
    val provenance: KeyProvenance,
) {
    /**
     * `TEST_<keyId>.der` — EVAL.md §2's "file names say so" rule, applied at the point
     * where a filename would be produced.
     */
    val fileName: String get() = "TEST_${keyId.replace(Regex("[^A-Za-z0-9_]"), "_")}.der"

    init {
        require(keyId.isNotBlank()) { "a key with no id cannot be selected by SecureQr" }
    }

    override fun equals(other: Any?): Boolean =
        other is TestPublicKey && keyId == other.keyId && der.contentEquals(other.der)

    override fun hashCode(): Int = 31 * keyId.hashCode() + der.contentHashCode()
}

/** Deterministic synthetic device id for fixtures. Never a real device serial. */
object TestDeviceIds {
    const val HARNESS = "TEST-HARNESS-JVM"
    const val DEMO_PHONE = "TEST-DEMO-PHONE"
}

/** Small helper so fixtures can sign without touching JCA directly. */
object HarnessSigner {

    fun sign(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /**
     * A deterministic 32-byte id, used for case ids and payload nonces so a run is
     * reproducible from its seed alone.
     */
    fun stableId(namespace: String, index: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$namespace:$index".toByteArray(Charsets.UTF_8))

    /** HMAC helper used by the tamper fixtures to build a wrong-but-well-formed signature. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
