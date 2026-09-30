package dev.kasoti.diary

import dev.kasoti.face.Embedding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Identity and quantisation of a face-embedding generation.
 *
 * DESIGN.md's comparability law is the reason this is a first-class object rather than a
 * string field: two embeddings are comparable only if they came from the same weight bytes,
 * the same alignment and the same normalisation. Recording `emb_v1@sha256:<64 hex>` in every
 * diary record *and* every sync bundle is how invariant I1 gets enforced at the door instead
 * of being discovered as a nonsensical similarity score six months later.
 */
object EmbModel {

    const val SHA256_HEX_LENGTH = 64

    /** @return `name@sha256:<hex>`, the only tag form KASOTI writes. */
    fun tag(name: String, weightsSha256Hex: String): String = "$name@" + SHA256_PREFIX + weightsSha256Hex

    /** @return the model name (`emb_v1`), or `null` when [tag] is malformed. */
    fun name(tag: String): String? {
        val at = tag.indexOf('@')
        if (at <= 0) return null
        return tag.substring(0, at)
    }

    /** @return the hex weight digest inside [tag], or `null` when [tag] is malformed. */
    fun sha256Hex(tag: String): String? {
        val at = tag.indexOf('@')
        if (at <= 0) return null
        val rest = tag.substring(at + 1)
        if (!rest.startsWith(SHA256_PREFIX)) return null
        val hex = rest.substring(SHA256_PREFIX.length)
        if (hex.length != SHA256_HEX_LENGTH) return null
        return if (hex.all { it in '0'..'9' || it in 'a'..'f' }) hex else null
    }

    fun isValid(tag: String): Boolean = sha256Hex(tag) != null

    /** True when two tags denote the same weight bytes, regardless of the model name casing. */
    fun sameGeneration(a: String, b: String): Boolean = sha256Hex(a) != null && sha256Hex(a) == sha256Hex(b)

    private const val SHA256_PREFIX = "sha256:"
}

/**
 * INT8 quantisation of a 128-d embedding (DESIGN.md §5).
 *
 * A face embedding is L2-normalised in `Float` on the device, then stored as signed bytes so
 * a 100k-record diary is 12.8 MB instead of 51 MB — the difference between shipping a diary
 * over USB and not. The cost is precision: a shared [QuantisedEmbedding.qScale] with symmetric
 * `x / scale` rounding keeps the relative error under 1% of the vector norm, which is two
 * orders of magnitude below the [dev.kasoti.threshold.ThresholdName.T_ALIAS_HI] step.
 *
 * The scale is stored *per record* rather than only in the bundle header (SYNC.md §3), because
 * the scale is a property of the data: a diary that silently re-scaled itself after a platform
 * change would still be arithmetically self-consistent and completely wrong.
 */
data class QuantisedEmbedding(val bytes: ByteArray, val qScale: Float) {

    init {
        require(bytes.size == Embedding.DIM) { "expected ${Embedding.DIM} int8 values, got ${bytes.size}" }
        require(qScale > 0f && qScale.isFinite()) { "qScale must be positive and finite, got $qScale" }
    }

    /**
     * Dequantise back to the original value range.
     *
     * [qScale] is the *divisor*: [of] stores `round(value / qScale)` so the byte always lands in
     * `[-127, 127]`, and recovery is therefore a multiplication. Getting this the wrong way
     * round is silent — the vector still "looks like a face" — which is why it is spelled out
     * here and asserted in the test suite.
     */
    fun dequantise(): FloatArray {
        val out = FloatArray(bytes.size)
        for (i in bytes.indices) out[i] = bytes[i].toInt() * qScale
        return out
    }

    companion object {
        /**
         * @param vector L2-normalised embedding values.
         * @return quantised bytes plus the symmetric scale that recovers them.
         */
        fun of(vector: FloatArray): QuantisedEmbedding {
            require(vector.size == Embedding.DIM) { "expected ${Embedding.DIM} dimensions" }
            var maxAbs = 0f
            for (v in vector) maxAbs = max(maxAbs, abs(v))
            // A zero vector has no meaningful scale; 1/127 keeps the maths defined and the
            // resulting similarity is 0, which is the correct answer for "no signal".
            val scale = if (maxAbs <= MIN_SCALE) 1f / MAX_ABS else maxAbs / MAX_ABS
            val bytes = ByteArray(Embedding.DIM)
            for (i in vector.indices) {
                bytes[i] = (vector[i] / scale).roundToInt().coerceIn(-MAX_ABS, MAX_ABS).toByte()
            }
            return QuantisedEmbedding(bytes, scale)
        }

        private const val MAX_ABS = 127
        private const val MIN_SCALE = 1e-8f
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is QuantisedEmbedding && qScale == other.qScale && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + qScale.hashCode()
}

/**
 * Cosine similarity between a query and a stored int8 vector.
 *
 * Fused dequantise-and-dot: the naive version materialises a 128-float array per record,
 * which for a 100k diary is 100k allocations per search. The invariant is the same either
 * way — both vectors must be dequantised with *their own* scale, or a diary written at one
 * scale and searched at another returns a number that looks plausible and is not a cosine.
 *
 * Cosine is scale-invariant, so the stored [storedScale] cancels out entirely; it is still
 * taken explicitly because the caller must be forced to acknowledge which scale it holds, and
 * because a future non-cosine score will need it.
 */
fun cosineQuantised(query: FloatArray, stored: ByteArray, storedScale: Float): Float {
    if (stored.size != query.size || stored.isEmpty()) return 0f
    var dot = 0.0
    var normQ = 0.0
    var normS = 0.0
    for (i in query.indices) {
        val s = stored[i].toDouble()
        dot += query[i].toDouble() * s
        normQ += query[i].toDouble() * query[i]
        normS += s * s
    }
    if (normQ <= 0.0 || normS <= 0.0) return 0f
    return (dot / (sqrt(normQ) * sqrt(normS))).toFloat().coerceIn(-1f, 1f)
}
