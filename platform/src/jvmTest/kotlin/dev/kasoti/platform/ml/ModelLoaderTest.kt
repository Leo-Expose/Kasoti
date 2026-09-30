package dev.kasoti.platform.ml

import dev.kasoti.crypto.Hex
import dev.kasoti.platform.crypto.JcaDigest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Model hash pinning (invariant I12, red-team attack RT-E8, BUILD.md §4).
 *
 * The property under test is not "hashing works" — that is JcaDigestTest's job. It is
 * "a model that is not the pinned model cannot be used", including the cases an attacker
 * would actually try: a byte flipped in the weights, a truncated download, a pin that was
 * never filled in, and a *valid* model file that belongs to a different generation.
 */
class ModelLoaderTest {

    private val loader = ModelLoader(JcaDigest())

    private fun pinFor(bytes: ByteArray, name: String = "emb_v1", minimumBytes: Int = 1) = PinnedModel(
        name = name,
        sha256 = loader.digestOf(bytes),
        minimumBytes = minimumBytes,
    )

    private fun weights(size: Int = 4096): ByteArray =
        ByteArray(size) { ((it * 37 + 11) % 251).toByte() }

    @Test
    fun `the correct model loads`() {
        val bytes = weights()
        val loaded = loader.loadBytes(pinFor(bytes), bytes)
        assertEquals("emb_v1", loaded.name)
        assertEquals(loader.digestOf(bytes), loaded.sha256)
        assertEquals(bytes.size, loaded.sizeBytes)
        assertTrue(loaded.modelTag.startsWith("emb_v1@sha256:"))
    }

    /** RT-E8 proper: one flipped weight byte must be fatal. */
    @Test
    fun `a single flipped byte in the weights is refused`() {
        val honest = weights()
        val tampered = honest.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        val failure = assertFailsWith<ModelLoadException> { loader.loadBytes(pinFor(honest), tampered) }
        assertEquals(ModelErrorCode.HASH_MISMATCH, failure.code)
        assertEquals("err.model_hash", failure.code.messageKey)
        assertEquals(loader.digestOf(honest), failure.expectedSha256)
        assertEquals(loader.digestOf(tampered), failure.actualSha256)
    }

    /**
     * The failure carries the message key the UI resolves.
     *
     * An operator staring at "model refused" with no digest has no way to tell a
     * provisioning mistake from a tampered file, and those need opposite responses — so the
     * key must be present *and* the human message must not be the only thing there.
     */
    @Test
    fun `the failure names the message key the UI resolves`() {
        val failure = assertFailsWith<ModelLoadException> { loader.loadBytes(pinFor(weights()), weights(8)) }
        assertEquals("err.model_hash", failure.code.messageKey)
        assertTrue(failure.message.orEmpty().contains("err.model_hash"))
        assertTrue(failure.message.orEmpty().contains("emb_v1"), "the failure must name the model")
    }

    /** A truncated download must not be mistaken for a small model. */
    @Test
    fun `a truncated model is refused`() {
        val full = weights(8192)
        val failure = assertFailsWith<ModelLoadException> {
            loader.loadBytes(pinFor(full, minimumBytes = 4096), full.copyOf(1024))
        }
        assertEquals(ModelErrorCode.TOO_SMALL, failure.code)
    }

    @Test
    fun `a missing file is reported as missing, not as a mismatch`() {
        val dir = createTempDirectory("kasoti-models")
        try {
            val failure = assertFailsWith<ModelLoadException> {
                loader.load(pinFor(weights()), dir.resolve("absent.tflite"))
            }
            assertEquals(ModelErrorCode.MISSING, failure.code)
            assertEquals("err.model_missing", failure.code.messageKey)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a model loads from disk when the bytes are intact`() {
        val dir = createTempDirectory("kasoti-models-disk")
        try {
            val bytes = weights()
            val file: Path = dir.resolve("emb_v1.tflite")
            Files.write(file, bytes)
            val loaded = loader.load(pinFor(bytes), file)
            assertEquals(bytes.size, loaded.sizeBytes)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A different model generation, correctly installed, is still refused.
     *
     * This is the case that gets argued about in review: "the file is a valid model, just
     * not the one we pinned — surely we can use it?" No. The diary would then hold vectors
     * from two different generation, and cosine between them is noise. The comparability
     * law (DESIGN.md §3) has no exception clause.
     */
    @Test
    fun `a valid but different model generation is refused`() {
        val pinned = weights(4096)
        val other = weights(4096).also { it[0] = 0x7f }
        val failure = assertFailsWith<ModelLoadException> { loader.loadBytes(pinFor(pinned), other) }
        assertEquals(ModelErrorCode.HASH_MISMATCH, failure.code)
    }

    @Test
    fun `an empty model is refused`() {
        val failure = assertFailsWith<ModelLoadException> { loader.loadBytes(pinFor(ByteArray(0)), ByteArray(0)) }
        assertTrue(failure.code == ModelErrorCode.TOO_SMALL || failure.code == ModelErrorCode.HASH_MISMATCH)
    }

    /** Adversarial: a placeholder pin must be caught, not silently accepted. */
    @Test
    fun `a malformed pin is refused`() {
        for (bad in listOf("", "deadbeef", "z".repeat(64), "a".repeat(63), "A".repeat(64))) {
            val failure = assertFailsWith<ModelLoadException>("pin '$bad' should be rejected") {
                loader.loadBytes(PinnedModel("emb_v1", bad), weights())
            }
            assertEquals(ModelErrorCode.BAD_PIN, failure.code)
        }
    }

    @Test
    fun `a malformed pin names the right message key`() {
        val failure = assertFailsWith<ModelLoadException> { loader.loadBytes(PinnedModel("emb_v1", "nope"), weights()) }
        assertEquals("err.model_pin_malformed", failure.code.messageKey)
    }

    /** The digest of an empty blob is the FIPS value, so a pin generated anywhere agrees. */
    @Test
    fun `the loader digest is the standard SHA-256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            loader.digestOf(ByteArray(0)),
        )
    }

    /** Every failure code maps to a distinct message key, or the UI cannot tell them apart. */
    @Test
    fun `every error code has a distinct message key`() {
        val keys = ModelErrorCode.entries.map { it.messageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}

/**
 * The `EmbeddingModel` seam (DESIGN.md §5, D1).
 *
 * No TFLite binding ships in this pass — another track owns it — so what is testable today
 * is that the seam refuses to hand out bytes without a verified load, and that the tag it
 * would stamp on every embedding is derived from the *verified* digest rather than from
 * whatever file happened to be on disk.
 */
class PinnedEmbeddingModelSourceTest {

    @Test
    fun `an intact model yields a tag carrying the verified digest`() {
        val loader = ModelLoader(JcaDigest())
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val pin = PinnedModel("emb_v1", loader.digestOf(bytes))
        val dir = createTempDirectory("kasoti-emb")
        try {
            val file = dir.resolve("emb_v1.tflite")
            Files.write(file, bytes)
            val opened = PinnedEmbeddingModelSource(loader, pin).open(file)
            assertEquals("emb_v1@sha256:${Hex.encode(JcaDigest().sha256(bytes))}", opened.modelTag)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a tampered model never reaches the interpreter`() {
        val loader = ModelLoader(JcaDigest())
        val honest = ByteArray(1024) { (it % 251).toByte() }
        val pin = PinnedModel("emb_v1", loader.digestOf(honest))
        val dir = createTempDirectory("kasoti-emb-bad")
        try {
            val file = dir.resolve("emb_v1.tflite")
            Files.write(file, honest.copyOf().also { it[10] = 0x00; it[11] = 0x01 })
            assertFailsWith<ModelLoadException> { PinnedEmbeddingModelSource(loader, pin).open(file) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
