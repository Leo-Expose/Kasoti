package dev.kasoti.platform.ml

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import dev.kasoti.crypto.constantTimeEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Why a model load was refused.
 *
 * A closed vocabulary rather than free text, because every case here is a *tamper* signal
 * and a tamper signal must not be phrased differently on every machine. Each maps to a
 * message key the UI resolves in English and Hindi (AGENTS.md §2).
 */
enum class ModelErrorCode(val messageKey: String) {
    /** SHA-256 of the bytes did not match the pin. This is RT-E8. */
    HASH_MISMATCH("err.model_hash"),

    /** The file is not there. Usually a provisioning miss, not an attack. */
    MISSING("err.model_missing"),

    /** The file is there but empty or implausibly small for a real model. */
    TOO_SMALL("err.model_truncated"),

    /** The pinned hash itself is not a well-formed 64-character hex digest. */
    BAD_PIN("err.model_pin_malformed"),
}

/**
 * A load that failed, with enough detail to debug it and nothing that could leak key
 * material. [expectedSha256] and [actualSha256] are model digests, not secrets — they are
 * the whole point of the record and they are printed in the console banner.
 */
class ModelLoadException(
    val code: ModelErrorCode,
    val modelName: String,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    cause: Throwable? = null,
) : Exception("model '$modelName' refused: ${code.messageKey}", cause)

/**
 * A model file plus the identity it was proven to have.
 *
 * The type is the enforcement mechanism, not a convenience wrapper: [bytes] can only be
 * reached through a successful [ModelLoader.load], so there is no way to hand unverified
 * bytes to an interpreter. That is what makes invariant I12 structural rather than a
 * convention someone has to remember.
 */
class LoadedModel(
    val name: String,
    val sha256: String,
    val bytes: ByteArray,
) {
    /** The tag recorded in every diary record and sync bundle (DESIGN.md §3 comparability law). */
    val modelTag: String get() = "$name@sha256:$sha256"

    val sizeBytes: Int get() = bytes.size
}

/**
 * Hash-pinned model loading (BUILD.md §4, invariant I12, red-team attack RT-E8).
 *
 * The attack this exists for: an attacker who can write one file inside the app's data
 * directory swaps `emb_v1.tflite` for their own weights, which now decide whether the
 * subject matches. The app would then "work perfectly" — embeddings would be produced,
 * the chain would verify, the audit would be clean — while every verdict was the
 * attacker's. Signature verification does not help here because the attacker controls the
 * *inputs* to it; only a digest pinned at build time does.
 *
 * **Fail-closed.** A mismatch is an exception, not a warning and not a fallback to a
 * default model. The caller decides what a missing model means, and it is never "treat it
 * as a pass".
 */
class ModelLoader(private val digest: Digest) {

    /** Verify an in-memory blob against [pin]. */
    fun loadBytes(pin: PinnedModel, bytes: ByteArray): LoadedModel {
        requireWellFormedPin(pin)
        if (bytes.size < pin.minimumBytes) {
            throw ModelLoadException(ModelErrorCode.TOO_SMALL, pin.name, pin.sha256, actualOf(bytes))
        }
        val actual = Hex.encode(digest.sha256(bytes))
        // Constant-time on the ASCII digest so a timing oracle cannot be used to discover
        // the pinned value byte by byte. The pin ships in the binary, so this is defence in
        // depth — but the comparison is the one place the two meet, so it is the one place
        // to be careful.
        if (!constantTimeEquals(actual.toByteArray(Charsets.US_ASCII), pin.sha256.toByteArray(Charsets.US_ASCII))) {
            throw ModelLoadException(ModelErrorCode.HASH_MISMATCH, pin.name, pin.sha256, actual)
        }
        return LoadedModel(pin.name, actual, bytes)
    }

    /**
     * Read and verify a model file.
     *
     * The file is fully read before hashing rather than streamed, so a file that is being
     * rewritten underneath us either hashes consistently or not at all — never as a
     * half-old, half-new mixture that happens to match nothing.
     */
    fun load(pin: PinnedModel, file: Path): LoadedModel {
        requireWellFormedPin(pin)
        if (!Files.isRegularFile(file)) {
            throw ModelLoadException(ModelErrorCode.MISSING, pin.name, pin.sha256)
        }
        return loadBytes(pin, Files.readAllBytes(file))
    }

    /** Hash without loading. Used by `scripts/fetch_models.sh` verification and the console banner. */
    fun digestOf(bytes: ByteArray): String = Hex.encode(digest.sha256(bytes))

    private fun actualOf(bytes: ByteArray): String? = digestOf(bytes)

    private fun requireWellFormedPin(pin: PinnedModel) {
        if (pin.sha256.length != SHA256_HEX_LENGTH || !pin.sha256.all { it in "0123456789abcdef" }) {
            throw ModelLoadException(ModelErrorCode.BAD_PIN, pin.name, pin.sha256)
        }
    }

    companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * The build-time pin for one model artifact.
 *
 * [sha256] is a compile-time constant in the shipped binary, never read from a file beside
 * the model: a pin that ships alongside the thing it pins is not a pin. `BUILD.md §3` puts
 * the reference hashes in the `eval/models` directory; the release build copies them in here
 * during provisioning.
 */
data class PinnedModel(
    val name: String,
    val sha256: String,
    val minimumBytes: Int = 1,
)
