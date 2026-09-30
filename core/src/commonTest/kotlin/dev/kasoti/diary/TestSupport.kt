package dev.kasoti.diary

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import dev.kasoti.crypto.Hmac
import dev.kasoti.face.Embedding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Test doubles and fixtures shared by the diary and sync suites.
 *
 * `:core` receives [Digest]/[Hmac] as interfaces so the *logic* stays pure while the platform
 * supplies JCA (AGENTS.md §5). The flip side is that a `:core` test cannot prove SHA-256 is
 * SHA-256 — and it should not try. What these tests prove is that the right *bytes* are
 * authenticated: the HMAC covers the canonical payload and nothing else, so flipping one byte
 * anywhere changes the result. Conformance of the primitive itself is asserted against the same
 * golden vectors in `:platform`, where JCA is available.
 */
class FakeDigest : Digest {

    override fun sha256(bytes: ByteArray): ByteArray {
        val out = ByteArray(DIGEST_LENGTH)
        for (word in 0 until 4) {
            val value = fnv(bytes, SEEDS[word])
            for (byteIndex in 0 until 8) {
                out[word * 8 + byteIndex] = ((value shr (56 - byteIndex * 8)) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun fnv(bytes: ByteArray, seed: Long): Long {
        var h = seed
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h *= FNV_PRIME
        }
        // Length mixing keeps "ab"+"c" distinct from "a"+"bc", which matters because the
        // canonical payloads being hashed are concatenations.
        h = h xor (bytes.size.toLong() * LENGTH_MIX)
        // Avalanche (the murmur3 finaliser). Without it the high bits of an FNV barely move
        // when the *last* byte changes, and the facilitator rule groups on a short hash prefix —
        // which would then collapse four different subjects into one cohort.
        h = h xor (h ushr 33); h *= -49064778989728563L
        h = h xor (h ushr 33); h *= -4265267296055464877L
        return h xor (h ushr 33)
    }

    private companion object {
        const val DIGEST_LENGTH = 32
        const val FNV_PRIME = 0x100000001b3L
        const val LENGTH_MIX = 1_000_003L
        val SEEDS = longArrayOf(-3750763034362895579L, -7046029254386353131L, 2862934286244824517L, 0x5bf03635L)
    }
}

/** Deterministic MAC: HMAC-shaped, key-mixing included, cryptographically meaningless. */
class FakeHmac : Hmac {
    private val digest = FakeDigest()
    override fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mixed = ByteArray(key.size + 1 + data.size)
        key.copyInto(mixed)
        mixed[key.size] = 0x00
        data.copyInto(mixed, key.size + 1)
        return digest.sha256(mixed)
    }
}

/** Counts digests so the perf test can report the hashing volume it stood in for. */
class RecordingDigest(private val inner: Digest = FakeDigest()) : Digest {
    var calls: Long = 0
        private set
    var bytesHashed: Long = 0
        private set

    override fun sha256(bytes: ByteArray): ByteArray {
        calls++
        bytesHashed += bytes.size
        return inner.sha256(bytes)
    }
}

object Fixtures {

    val EMB_MODEL: String = "emb_v1@sha256:" + "ab".repeat(EmbModel.SHA256_HEX_LENGTH / 2)
    val OTHER_EMB_MODEL: String = "emb_v1@sha256:" + "cd".repeat(EmbModel.SHA256_HEX_LENGTH / 2)
    val KEY: ByteArray = "kasoti-prototype-secret".toByteArray(Charsets.UTF_8)

    private fun hex(label: String): String =
        Hex.encode(FakeDigest().sha256(label.toByteArray(Charsets.UTF_8)))

    /** 2026-03-14T09:00:00.000Z — a fixed instant so nothing in a test depends on a clock. */
    const val T0 = "2026-03-14T09:00:00.000Z"
    const val T0_MILLIS = 1_773_478_800_000L

    val REGISTRY: ThresholdRegistry = ThresholdRegistry.defaults()

    /**
     * A deterministic unit-norm 128-d embedding.
     *
     * Unit-norm matters: the cosine path assumes the platform already normalised, so a fixture
     * that is not would make every similarity in the suite wrong in the same direction and hide
     * real bugs.
     */
    fun embedding(seed: Int): FloatArray {
        val random = Random(seed)
        val values = FloatArray(Embedding.DIM)
        var norm = 0.0
        for (i in values.indices) {
            values[i] = random.nextFloat() * 2f - 1f
            norm += (values[i].toDouble() * values[i])
        }
        val scale = (1.0 / sqrt(norm)).toFloat()
        for (i in values.indices) values[i] *= scale
        return values
    }

    fun embeddingTag(values: FloatArray, model: String = EMB_MODEL) = Embedding.of(values, model)

    fun event(
        index: Int,
        seq: Long = 0L,
        device: String = "post3-ph1",
        post: String = "RAXAUL",
        ts: String = IsoInstant.format(T0_MILLIS + index * 60_000L),
        track: Track = Track.AADHAAR,
        // Realistic 64-hex salted hashes. Feeding a counter or a shared prefix here would
        // collapse every fixture subject into one facilitator cohort, which is a property of the
        // *hash*, not of the rule — hence hashed fixtures and a validated record field.
        nameSha: String = hex("name|$index|$device"),
        dob: String? = "1998-04-11",
        docHash: String = hex("doc|$index|$device"),
        embModel: String = EMB_MODEL,
        emb: FloatArray = embedding(index),
        q: Float = 0.87f,
        verdict: Verdict = Verdict.GREEN,
        findings: List<FindingCode> = listOf(FindingCode.M_OK, FindingCode.Q_SIG_OK),
        prev: String = "",
    ): CrossingEvent {
        val quantised = QuantisedEmbedding.of(emb)
        return CrossingEvent(
            // Seeded by device as well as index: two posts recording "their" fifth event
            // are two different events and must not collide on an id.
            id = Ulid.eventId(T0_MILLIS + index, Random(index * 131 + device.hashCode())),
            seq = seq,
            device = device,
            post = post,
            ts = ts,
            track = track,
            nameSha = nameSha,
            dob = dob,
            docHash = docHash,
            embModel = embModel,
            emb = quantised.bytes,
            qScale = quantised.qScale,
            q = q,
            verdict = verdict,
            findings = findings,
            prev = prev,
        )
    }

    /** Posts far enough apart that a short hop is impossible at 120 km/h. */
    fun postMap(): PostMap = mapOf(
        "RAXAUL" to PostCoord("RAXAUL", 26.2444, 84.6639),
        "BHOPAL" to PostCoord("BHOPAL", 23.2599, 77.4126),
        "GUNA" to PostCoord("GUNA", 24.6465, 77.3108),
        "NEEMUCH" to PostCoord("NEEMUCH", 23.9333, 74.7833),
    )
}
