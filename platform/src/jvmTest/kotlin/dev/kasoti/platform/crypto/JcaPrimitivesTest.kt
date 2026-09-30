package dev.kasoti.platform.crypto

import dev.kasoti.crypto.Hex
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A keypair generated for one test run.
 *
 * Deliberately not a checked-in constant. A hardcoded private key in a repository is a
 * leaked key the moment the repository is public, and — worse — a hardcoded *public* key
 * makes it possible for a broken verifier to keep passing after someone changes the
 * algorithm. Generating here means the test is self-contained and the JCA is the only
 * thing under test.
 */
internal object TestKeys {

    /** @return the DER `SubjectPublicKeyInfo` and its private key, generated for this run. */
    fun generate(bits: Int = 2048): Pair<ByteArray, PrivateKey> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        return pair.public.encoded to pair.private
    }

    fun sign(data: ByteArray, privateKey: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /** @return (x509 public key DER, private key, public key) — the third for key-file tests. */
    fun pair(bits: Int = 2048): Triple<ByteArray, PrivateKey, java.security.PublicKey> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        return Triple(pair.public.encoded, pair.private, pair.public)
    }
}

/**
 * FIPS 180-4 / RFC 4231 conformance.
 *
 * This is the whole reason `:core` declares `Digest` and `Hmac` as interfaces. The audit
 * chain, the sync envelope and the model pins are all *only* as trustworthy as the hash
 * underneath them, and `:core` cannot test its own — it has no hash. A stub that returns
 * 32 zero bytes would satisfy every `:core` test perfectly and silently destroy the audit
 * chain. Pinning the published vectors here is what turns the seam from a convention into
 * a checked contract.
 */
class JcaDigestTest {

    private val digest = JcaDigest()

    @Test
    fun `SHA-256 of the empty string matches FIPS 180-4`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `SHA-256 of abc matches FIPS 180-4 example`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.sha256Hex("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `SHA-256 of the 448-bit two-block message matches FIPS 180-4`() {
        val message =
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digest.sha256Hex(message.toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `SHA-256 of one million a characters matches FIPS 180-4`() {
        val million = ByteArray(MILLION) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            digest.sha256Hex(million),
        )
    }

    @Test
    fun `digest length is 32 bytes`() {
        assertEquals(32, digest.sha256("anything".toByteArray()).size)
    }

    /**
     * Adversarial: a single flipped bit must change the digest.
     *
     * A digest with a collision on adjacent inputs is not a digest, and the model-pin check
     * (RT-E8) is exactly a one-bit-change scenario applied to model bytes.
     */
    @Test
    fun `flipping one bit changes the digest`() {
        val a = "kasoti-model-bytes".toByteArray(Charsets.US_ASCII)
        val b = a.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(digest.sha256Hex(a) == digest.sha256Hex(b))
    }

    /** Adversarial: the instance must not carry state between calls. */
    @Test
    fun `consecutive calls are independent`() {
        val one = digest.sha256Hex("abc".toByteArray(Charsets.US_ASCII))
        digest.sha256("a different message entirely".toByteArray(Charsets.US_ASCII))
        val two = digest.sha256Hex("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(one, two)
    }

    private companion object {
        const val MILLION = 1_000_000
    }
}

/** RFC 4231 HMAC-SHA-256 test vectors. */
class JcaHmacTest {

    private val hmac = JcaHmac()
    private val digest = JcaDigest()

    @Test
    fun `RFC 4231 case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hex.encode(hmac.sha256(key, data)),
        )
    }

    @Test
    fun `RFC 4231 case 2`() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hex.encode(hmac.sha256("Jefe".toByteArray(Charsets.US_ASCII), "what do ya want for nothing?".toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `RFC 4231 case 3`() {
        val key = ByteArray(20) { 0xaa.toByte() }
        val data = ByteArray(50) { 0xdd.toByte() }
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            Hex.encode(hmac.sha256(key, data)),
        )
    }

    @Test
    fun `RFC 4231 case 4`() {
        val key = listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
            0x16, 0x17, 0x18, 0x19).map { it.toByte() }.toByteArray()
        val data = ByteArray(50) { 0xcd.toByte() }
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            Hex.encode(hmac.sha256(key, data)),
        )
    }

    @Test
    fun `RFC 4231 case 6 - key longer than the block size`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            Hex.encode(hmac.sha256(key, data)),
        )
    }

    @Test
    fun `RFC 4231 case 7 - key longer than the block size, short data`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            Hex.encode(hmac.sha256(key, data)),
        )
    }

    /** Adversarial: the sync envelope is MAC'd, so a key/data swap must be detectable. */
    @Test
    fun `swapping key and data changes the mac`() {
        val a = hmac.sha256(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = hmac.sha256(byteArrayOf(4, 5, 6), byteArrayOf(1, 2, 3))
        assertFalse(a.contentEquals(b))
    }

    /**
     * Documented behaviour: the JCA refuses a zero-length key, so we refuse it too.
     *
     * RFC 2104 would allow it, but SYNC.md §3 mandates a 32-byte secret and an empty one
     * is a provisioning fault. Failing loudly beats an interoperable-but-nonsense MAC.
     */
    @Test
    fun `an empty key is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> {
            hmac.sha256(ByteArray(0), "data".toByteArray(Charsets.US_ASCII))
        }
    }

    /** Adversarial: state must not leak between calls on a shared instance. */
    @Test
    fun `consecutive calls are independent`() {
        val key = ByteArray(20) { 0x0b }
        val first = hmac.sha256(key, "Hi There".toByteArray(Charsets.US_ASCII))
        hmac.sha256(key, "unrelated".toByteArray(Charsets.US_ASCII))
        val second = hmac.sha256(key, "Hi There".toByteArray(Charsets.US_ASCII))
        assertContentEquals(first, second)
    }

    /** Our MAC output must be the 32 bytes `:core` expects, byte-for-byte stable. */
    @Test
    fun `our mac is 32 bytes and byte-stable across calls`() {
        val key = ByteArray(32) { 7 }
        val mac = hmac.sha256(key, "payload".toByteArray(Charsets.US_ASCII))
        assertEquals(32, mac.size)
        assertContentEquals(mac, hmac.sha256(key, "payload".toByteArray(Charsets.US_ASCII)))
    }
}

class JcaSignatureVerifierTest {

    private val verifier = JcaSignatureVerifier()
    private val data = "<?xml version=\"1.0\"?><payload name=\"SPECIMEN\"/>".toByteArray(Charsets.UTF_8)

    @Test
    fun `a valid signature verifies`() {
        val (publicKey, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        assertTrue(verifier.verifyRsaSha256(publicKey, data, signature))
    }

    @Test
    fun `a flipped byte in the signature fails`() {
        val (publicKey, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        signature[signature.size / 2] = (signature[signature.size / 2].toInt() xor 0x01).toByte()
        assertFalse(verifier.verifyRsaSha256(publicKey, data, signature))
    }

    @Test
    fun `a flipped byte in the data fails`() {
        val (publicKey, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        val tampered = data.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(verifier.verifyRsaSha256(publicKey, tampered, signature))
    }

    @Test
    fun `a truncated signature fails without throwing`() {
        val (publicKey, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        assertFalse(verifier.verifyRsaSha256(publicKey, data, signature.copyOf(signature.size / 2)))
    }

    /**
     * The contract that matters most: a garbage key returns `false` and never throws.
     *
     * An attacker controls the key file if they can reach the data directory, and a
     * `KeyFactory` exception escaping into the screening loop is a denial of service
     * against the post.
     */
    @Test
    fun `a garbage key fails without throwing`() {
        val (_, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        val garbageKey = "this is definitely not a DER SubjectPublicKeyInfo".toByteArray(Charsets.US_ASCII)
        assertFalse(verifier.verifyRsaSha256(garbageKey, data, signature))
    }

    @Test
    fun `an empty key fails without throwing`() {
        val (_, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        assertFalse(verifier.verifyRsaSha256(ByteArray(0), data, signature))
    }

    @Test
    fun `an empty signature fails without throwing`() {
        val (publicKey, _, _) = TestKeys.pair()
        assertFalse(verifier.verifyRsaSha256(publicKey, data, ByteArray(0)))
    }

    @Test
    fun `random bytes as a signature fail without throwing`() {
        val (publicKey, _, _) = TestKeys.pair()
        val noise = ByteArray(256) { (it * 31 % 251).toByte() }
        assertFalse(verifier.verifyRsaSha256(publicKey, data, noise))
    }

    /** Adversarial: a key of the wrong type (EC) must not be mistaken for an RSA one. */
    @Test
    fun `an EC public key is rejected`() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val (_, privateKey, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        assertFalse(verifier.verifyRsaSha256(ec.public.encoded, data, signature))
    }

    @Test
    fun `a signature from a different key fails`() {
        val (publicKey, _, _) = TestKeys.pair()
        val (_, otherPrivate, _) = TestKeys.pair()
        val signature = TestKeys.sign(data, otherPrivate)
        assertFalse(verifier.verifyRsaSha256(publicKey, data, signature))
    }

    /**
     * A key written to disk and read back must still verify.
     *
     * This is the shipping path: `uidai_qr_keys.json` holds base64 SubjectPublicKeyInfo blobs
     * that a provisioning step writes and the app reads later, so the bytes that arrive at
     * runtime are not necessarily the bytes the key was generated as.
     */
    @Test
    fun `a key written to disk and read back still verifies`() {
        val (_, privateKey, publicKey) = TestKeys.pair()
        val signature = TestKeys.sign(data, privateKey)
        val stored = Files.createTempFile("kasoti-key", ".der")
        try {
            Files.write(stored, publicKey.encoded)
            assertTrue(verifier.verifyRsaSha256(Files.readAllBytes(stored), data, signature))
        } finally {
            Files.deleteIfExists(stored)
        }
    }
}
