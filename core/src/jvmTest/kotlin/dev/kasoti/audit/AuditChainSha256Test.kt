package dev.kasoti.audit

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The JCA half of the audit-chain suite: the same properties as `AuditChainTest`, over a real
 * `MessageDigest` and with the hash bytes pinned to known answers.
 *
 * Why both halves exist. `AuditChainTest` runs on the deterministic fake every `:core` suite
 * uses, which is the right tool for testing *chaining*: it is stable across platforms and
 * across JCA vendors, so a failure means the chain logic changed. What it cannot do is prove
 * the bytes the chain commits to are the bytes SHA-256 produces — swap in a digest with a
 * collision, or a digest that hashes a prefix, and the fake happily signs for it.
 *
 * AGENTS.md §5 forbids hand-rolled crypto, so the only place that can be checked is a source
 * set with the JCA on the classpath, which is what this file is. `app-desktop` supplies
 * `dev.kasoti.platform.crypto.JcaDigest` to `AuditStore`; the known-answer assertions below are
 * what tie the two together.
 */
class AuditChainSha256Test {
    private val digest: Digest = JcaSha256

    @Test
    fun genesisIsTheSha256OfThePinnedSeed() {
        // Known answer, computed out of band (not by calling the code under test). If this ever
        // changes, either GENESIS_SEED changed or a chain's stored genesis no longer matches.
        assertEquals(
            "0b2e9f9453c94b1178207e389dc292915646d846c86c31b9a57abd4ed027f0a6",
            Hex.encode(digest.sha256("kasoti-audit/1".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(
            "0b2e9f9453c94b1178207e389dc292915646d846c86c31b9a57abd4ed027f0a6",
            AuditChain(digest).tip(),
        )
    }

    /**
     * Known answer for the exact payload layout `append` commits to:
     * `sha256(canonical + "|" + previousTip)`, over the fixture's canonical bytes.
     *
     * This is the test that fails if anyone "cleans up" the separator, the key names, the key
     * order, the JSON array inside `findings`, or the `null` spelling. Every one of those is
     * a silent change to a wire format that already has hashes stored against it, and there is
     * no other place in the build that would notice.
     */
    @Test
    fun theFirstRecordHashIsAKnownAnswer() {
        val chain = AuditChain(digest)
        assertEquals(
            "e4d6361493aee3a9628a33ab9931db09d9e014d58411806a8048d63b36c0d889",
            chain.append(AuditChainFixture.plain(0)),
            "plain(0) over the genesis tip",
        )

        val withOverride = AuditChain(digest)
        assertEquals(
            "ec5b525d8fb5641c5c125b25feca48080b4d6889caffd8cf788c95e28d1e74c8",
            withOverride.append(AuditChainFixture.full(0)),
            "the override fields are inside the hashed payload, not decoration",
        )
    }

    @Test
    fun aThreeRecordChainLandsOnAKnownTip() {
        val chain = AuditChain(digest)
        for (record in AuditChainFixture.chain(3)) chain.append(record)
        assertEquals("10e7f827cf8eb0760b9ccfbcc2bbff4c69b499f5e2594e4e50121ecdda823220", chain.tip())
        assertTrue(chain.verify().valid)
    }

    @Test
    fun everyFieldMutationAtEveryPositionBreaksTheChainUnderRealSha256() {
        AuditChainFixture.runTamperMatrix(digest, depth = 5)
    }

    @Test
    fun reorderingAndRemovingRecordsBothBreakVerificationUnderRealSha256() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(5), digest)
        for ((shape, edited) in AuditChainFixture.removalShapes(5)) {
            AuditChainFixture.assertRejected(chain.verifyExternal(edited), shape)
            assertNotEquals(chain.tip(), AuditChainFixture.build(edited, digest).tip(), "$shape: the tip moved")
        }
    }

    /**
     * A chain is only a chain if two different histories cannot land on the same tip, so this
     * checks the property the hashing is *for*: across 2,000 seeded pseudo-random records, no
     * two canonical forms collide and no two hashes repeat.
     *
     * Weak on its own — 2,000 records cannot probe SHA-256's collision resistance — and that is
     * the point. It is a tripwire for a *coding* accident (a field dropped from `canonical()`, a
     * delimiter reused, a mutable list aliased into the record), all of which would collapse
     * histories long before any cryptographic attack is feasible.
     */
    @Test
    fun twoThousandRandomRecordsStayDistinct() {
        val random = Random(0x5EED_1A6C)

        fun hex(length: Int): String =
            buildString(length) {
                for (i in 0 until length) {
                    append("0123456789abcdef"[random.nextInt(16)])
                }
            }

        val records =
            (0 until 2_000).map { index ->
                AuditChainFixture.plain(index % 100).copy(
                    caseId = "case_" + hex(20),
                    deviceId = "post" + random.nextInt(40) + "-ph" + random.nextInt(3),
                    timestampUtc =
                        "2026-03-14T%02d:%02d:%02d.000Z".format(
                            random.nextInt(24),
                            random.nextInt(60),
                            random.nextInt(60),
                        ),
                    findingCodes = List(random.nextInt(4)) { "R_MATH_0" + random.nextInt(9) },
                    embeddingModel = if (random.nextBoolean()) null else "emb_v1@sha256:" + hex(64),
                )
            }
        assertEquals(
            records.size,
            records.map { it.canonical() }.distinct().size,
            "two records shared canonical bytes, so their hashes are interchangeable",
        )

        val chain = AuditChain(digest)
        for (record in records) chain.append(record)
        assertTrue(chain.verify().valid, "a 2,000-record chain must verify")
        assertEquals(64, chain.tip().length)

        assertNotEquals(
            chain.tip(),
            AuditChainFixture.build(records.reversed(), digest).tip(),
            "order is part of the identity: the same records in another order are another chain",
        )
    }

    @Test
    fun theKnownAnswerTestsWouldFailIfThePayloadLayoutChanged() {
        // A self-check on the known answers themselves: `append` must be committing to
        // `canonical + "|" + previous`, so recomputing it here from the public surface has to
        // reproduce the same tip. If this ever disagrees with the hard-coded hexes above, the
        // hexes are stale and the two assertions that use them are testing nothing.
        val chain = AuditChain(digest)
        val record = AuditChainFixture.plain(0)
        val expected =
            Hex.encode(
                JcaSha256.sha256("${record.canonical()}|${chain.tip()}".toByteArray(Charsets.UTF_8)),
            )
        assertEquals(expected, chain.append(record))
    }

    /** JCA-backed [Digest]. `platform`'s `JcaDigest` is the production implementation of this. */
    private object JcaSha256 : Digest {
        override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    @Test
    fun thisTestClassIsActuallyUsingSha256() {
        // Guard against a `Digest` that silently degrades: SHA-256 of the empty input is a
        // published constant, so this fails loudly rather than letting the class test nothing.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hex.encode(JcaSha256.sha256(ByteArray(0))),
        )
        assertFalse(
            Hex.encode(JcaSha256.sha256("a".toByteArray())) ==
                Hex.encode(JcaSha256.sha256("b".toByteArray())),
        )
    }
}
