package dev.kasoti.audit

import dev.kasoti.crypto.Digest
import dev.kasoti.diary.FakeDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AuditChain` tests: invariant I6, "audit chain verifies" (DESIGN.md §8).
 *
 * The class under test is the reason an officer's decision stays reconstructable (FR-S4): it
 * is the only thing standing between "a log was written" and "this log says what an officer
 * did, and nobody has edited it since". That makes it a target rather than a utility, so the
 * bulk of this file is adversarial.
 *
 * Three things are deliberately pinned here that a reader should not "fix" without reading:
 *
 *  - `verifyRecomputesRatherThanComparingAValueWithItself` uses a dishonest digest to prove
 *    `verify()` recomputes. Without it, `verify() == true` is indistinguishable from a
 *    tautology, and every other assertion in the file would inherit that doubt.
 *  - `aConstantDigestCollapsesTheChain` records that the chain's entire security property is
 *    the injected [Digest]. `:core` is not allowed to choose it (AGENTS.md §5 forbids
 *    hand-rolled crypto), which makes the platform wiring the only thing standing there.
 *  - Three tests are named `KNOWN DEFECT`. They assert the *current*, wrong behaviour on
 *    purpose: a characterisation is what makes the hole countable, greppable and impossible to
 *    rediscover as a surprise. The `core/src/commonMain` tree was out of scope for the work that
 *    added this file, so the defects are pinned here and reported rather than patched. Each
 *    test names the defect and the fix in its KDoc.
 *
 * Real SHA-256 is exercised over the same fixtures in `src/jvmTest/…/AuditChainSha256Test.kt`;
 * this file runs the portable half, so a failure localises to a property of the chain rather
 * than to a property of the digest.
 */
class AuditChainTest {
    private val digest: Digest = FakeDigest()

    // --- genesis and tip ---------------------------------------------------------------------

    @Test
    fun anEmptyChainSitsAtGenesisAndVerifies() {
        val chain = AuditChain(digest)
        assertEquals(0, chain.size)
        assertTrue(chain.all().isEmpty())
        assertEquals(
            AuditChainFixture.expectedGenesis(digest),
            chain.tip(),
            "an empty chain's tip is the genesis hash of a fixed seed, not null or empty",
        )
        assertEquals(64, chain.tip().length, "the tip is hex-encoded SHA-256, so 64 characters")
        val verification = chain.verify()
        assertTrue(verification.valid)
        assertEquals(0, verification.length)
        assertNull(verification.brokenAtIndex)
        assertEquals("", verification.reason)
    }

    @Test
    fun genesisIsTheSameForEveryChainSoChainsAreComparable() {
        val first = AuditChain(digest)
        val second = AuditChain(FakeDigest())
        assertEquals(first.tip(), second.tip(), "genesis must depend on the seed alone, not on the chain")
    }

    @Test
    fun appendingExtendsTheChainAndMovesTheTip() {
        val chain = AuditChain(digest)
        val genesisTip = chain.tip()
        val records = AuditChainFixture.chain(4)

        var previous = genesisTip
        for (i in records.indices) {
            assertEquals(i, chain.size, "size must count what has been appended, not what is left to append")
            val returned = chain.append(records[i])
            assertEquals(returned, chain.tip(), "append must return the new tip")
            assertNotEquals(previous, chain.tip(), "record $i must move the tip")
            assertEquals(64, returned.length)
            previous = chain.tip()
        }

        assertEquals(4, chain.size)
        assertEquals(records, chain.all(), "all() must preserve append order")
        assertTrue(chain.verify().valid)
    }

    @Test
    fun twoChainsOverTheSameRecordsShareATip() {
        val records = AuditChainFixture.chain(6)
        assertEquals(
            AuditChainFixture.build(records, digest).tip(),
            AuditChainFixture.build(records, FakeDigest()).tip(),
            "the tip is a pure function of (seed, records, digest) — no clock, no identity",
        )
    }

    // --- verification -------------------------------------------------------------------------

    @Test
    fun anUntamperedChainVerifies() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(7), digest)
        val verification = chain.verify()
        assertTrue(verification.valid, verification.reason)
        assertEquals(7, verification.length)
        assertNull(verification.brokenAtIndex)
    }

    /**
     * The non-vacuity proof for the whole file.
     *
     * `verify()` compares a freshly recomputed hash against a stored one. If it compared a
     * value with itself — or returned `true` after a loop that computed nothing — every
     * "untampered chain verifies" assertion here and in `app-desktop` would pass, and every
     * tamper test would be measuring the wrong function.
     *
     * A digest that returns a *different* answer for the same input on each call stands in for
     * a stored chain whose hashes were produced by a different run of a broken or replaced
     * primitive. `append` writes call N, `verify` recomputes with call N+1, so the comparison
     * must fail — and must fail at the first record, because that is the only one whose stored
     * value can be out of step with its recomputation.
     */
    @Test
    fun verifyRecomputesRatherThanComparingAValueWithItself() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(3), RotatingDigest())
        val verification = chain.verify()
        assertFalse(verification.valid, "a chain whose stored hashes cannot be recomputed must fail")
        assertEquals(0, verification.brokenAtIndex, "the first record is where the digests first disagree")
        assertTrue(verification.reason.isNotEmpty())
    }

    @Test
    fun everyFieldMutationAtEveryPositionBreaksTheChain() {
        AuditChainFixture.runTamperMatrix(digest, depth = 5)
    }

    @Test
    fun reorderingAndRemovingRecordsBothBreakVerification() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(5), digest)
        for ((shape, edited) in AuditChainFixture.removalShapes(5)) {
            AuditChainFixture.assertRejected(chain.verifyExternal(edited), shape)
            assertNotEquals(chain.tip(), AuditChainFixture.build(edited, digest).tip(), "$shape: the tip moved")
        }
    }

    @Test
    fun verifyExternalAcceptsTheChainsOwnRecords() {
        val records = AuditChainFixture.chain(4)
        val chain = AuditChainFixture.build(records, digest)
        val verification = chain.verifyExternal(records)
        assertTrue(verification.valid, verification.reason)
        assertEquals(4, verification.length)
        assertNull(verification.brokenAtIndex)
    }

    @Test
    fun verifyExternalRejectsATruncatedOrEmptyLog() {
        val records = AuditChainFixture.chain(5)
        val chain = AuditChainFixture.build(records, digest)
        // A prefix is a *truncation*, not a subset: every record it contains matches, so a
        // per-record comparison alone would call it valid, and anyone able to shorten a bundle
        // could delete decisions from it.
        val prefix = chain.verifyExternal(records.dropLast(1))
        assertFalse(prefix.valid, "a truncated log must not verify against a longer chain")
        assertEquals(4, prefix.brokenAtIndex)
        // An empty log is the degenerate case of the same thing: a claim that nothing ever
        // happened. It used to be accepted outright.
        val empty = chain.verifyExternal(emptyList())
        assertFalse(empty.valid, "an empty log must not verify against a non-empty chain")
        assertEquals(0, empty.brokenAtIndex)
    }

    @Test
    fun verifyExternalRejectsAForeignChainOfTheSameLength() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(4), digest)
        val foreign = AuditChainFixture.chain(4).map { it.copy(deviceId = "post9-ph2") }
        AuditChainFixture.assertRejected(chain.verifyExternal(foreign), "foreign chain")
    }

    @Test
    fun verifyExternalRejectsTheSameRecordsReordered() {
        val records = AuditChainFixture.chain(3)
        val chain = AuditChainFixture.build(records, digest)
        AuditChainFixture.assertRejected(chain.verifyExternal(records.reversed()), "reversal")
        // Two records that hash to the same value would survive a swap; the mutation matrix
        // proves no two ordinary records ever do, so this rejection is about order and not
        // about an accident.
        assertEquals(3, records.map { it.canonical() }.distinct().size)
    }

    // --- canonical form -----------------------------------------------------------------------

    @Test
    fun canonicalFormIsExactAndStable() {
        assertEquals(
            "{\"case\":\"case_01HQ0000000000000000B\",\"device\":\"post3-ph1\"," +
                "\"embed\":\"emb_v1@sha256:" + "ab".repeat(32) + "\"," +
                "\"findings\":[\"R_MATH_01\",\"Q_SIG_INVALID\"],\"fusion\":\"fusion.v1\"," +
                "\"id\":\"evt_01HQ000000000000000000\",\"overrideBy\":null," +
                "\"overrideReason\":null,\"thr\":\"thresholds.v1\"," +
                "\"ts\":\"2026-03-14T09:00:00.000Z\",\"verdict\":\"RED\"}",
            AuditChainFixture.plain(0).canonical(),
        )
        val withOverride = AuditChainFixture.full(0).canonical()
        assertTrue(withOverride.contains("\"overrideBy\":\"off.raju\""), withOverride)
        assertTrue(withOverride.contains("\"overrideReason\":\"SUP_DOC_AUTHENTIC\""), withOverride)
        assertEquals(
            AuditChainFixture.plain(0).canonical(),
            AuditChainFixture.plain(0).canonical(),
            "canonical() must be a pure function of the record's values",
        )
    }

    @Test
    fun canonicalKeysAreSortedRatherThanDeclarationOrdered() {
        // `DecisionRecord` declares id, timestamp, caseId, deviceId, verdict … which is not
        // alphabetical. Pinning the sorted order is what makes a future reordering of the data
        // class a non-event rather than a silent invalidation of every stored chain hash.
        val keys = KEY.findAll(AuditChainFixture.full(0).canonical()).map { it.groupValues[1] }.toList()
        assertEquals(11, keys.size, "every field appears exactly once: $keys")
        assertEquals(keys.sorted(), keys, "canonical keys must be in UTF-16 code-unit order: $keys")
        assertEquals(
            listOf("case", "device", "embed", "findings", "fusion", "id", "overrideBy", "overrideReason", "thr", "ts", "verdict"),
            keys,
        )
    }

    @Test
    fun nullAndAbsentAreDistinctInTheCanonicalForm() {
        val noModel = AuditChainFixture.plain(0).copy(embeddingModel = null)
        assertTrue(noModel.canonical().contains("\"embed\":null,"), noModel.canonical())
        assertNotEquals(
            AuditChainFixture.plain(0).canonical(),
            noModel.canonical(),
            "an absent embedding model must not hash like an empty one",
        )
    }

    // --- construction guards ------------------------------------------------------------------

    @Test
    fun anOverrideWithoutAReasonCodeIsRejectedAtConstruction() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DecisionRecord(
                    id = "evt_01HQ0000000000000000FF",
                    timestampUtc = "2026-03-14T09:00:00.000Z",
                    caseId = "case_01HQ0000000000000000B",
                    deviceId = "post3-ph1",
                    verdict = "AMBER",
                    findingCodes = listOf("A_PROC_01"),
                    thresholdVersion = AuditChainFixture.THR_VERSION,
                    fusionRuleVersion = AuditChainFixture.FUSION_VERSION,
                    embeddingModel = AuditChainFixture.EMB_MODEL,
                    overriddenBy = "off.raju",
                    overrideReasonCode = null,
                )
            }
        assertTrue(failure.message.orEmpty().contains("evt_01HQ0000000000000000FF"), failure.message.orEmpty())
    }

    /**
     * Pinned asymmetry, not a claim of correctness: the constructor guard is one-directional.
     * A reason code with no overrider is a "why" attached to nobody, which is the mirror image
     * of the case it does reject — and it is the one an import path is likelier to produce,
     * because a truncated CSV line can lose either column.
     */
    @Test
    fun aReasonCodeWithoutAnOverriderIsAccepted() {
        val record = AuditChainFixture.plain(0).copy(overrideReasonCode = "SUP_DOC_AUTHENTIC")
        assertEquals("SUP_DOC_AUTHENTIC", record.overrideReasonCode)
        assertNull(record.overriddenBy)
    }

    // --- KNOWN DEFECT 1: canonical() does not escape -------------------------------------------

    /**
     * KNOWN DEFECT — `canonical()` interpolates every value unescaped (`AuditChain.kt:35-49`),
     * so the `|` that joins finding codes and the `"` that delimits keys are not escaped and a
     * value containing either can forge a *different* record with the same bytes, and therefore
     * the same hash.
     *
     * `findingCodes = ["R_MATH_01", "Q_SIG_INVALID"]` and
     * `findingCodes = ["R_MATH_01|Q_SIG_INVALID"]` are different decisions — two findings
     * against one — and the canonical form cannot tell them apart.
     *
     * Not fixed here: the `core/src/commonMain` tree was out of scope for the work that added
     * this test. The fix is to route values through the escaper that already exists in this
     * module —
     * `dev.kasoti.json.CanonicalJson` escapes `"`, `\` and control characters — or to
     * length-prefix every value. Either is a wire-format change and invalidates every stored
     * chain hash, so it needs the lead decision AGENTS.md §7 asks for, not a drive-by patch.
     */
    @Test
    fun aPipeInAFindingCodeNoLongerCollidesWithTwoFindingCodes() {
        val twoCodes = AuditChainFixture.plain(0).copy(findingCodes = listOf("R_MATH_01", "Q_SIG_INVALID"))
        val onePipedCode = AuditChainFixture.plain(0).copy(findingCodes = listOf("R_MATH_01|Q_SIG_INVALID"))
        assertNotEquals(twoCodes, onePipedCode)
        // `findings` is a JSON array, not a `|`-joined string, so the separator can no longer
        // move between the two fields and forge a twin.
        assertNotEquals(twoCodes.canonical(), onePipedCode.canonical())
    }

    /** The same defect reached through a `"` in a key-delimited field rather than a `|` in a list. */
    @Test
    fun aQuoteInAFieldCanNoLongerForgeADifferentRecord() {
        val forgedCase =
            AuditChainFixture.plain(0).copy(
                caseId = "a\",\"device\":\"post3-ph1",
                deviceId = "X",
                findingCodes = listOf("M_OK"),
            )
        val honestCase =
            AuditChainFixture.plain(0).copy(
                caseId = "a",
                deviceId = "post3-ph1\",\"device\":\"X",
                findingCodes = listOf("M_OK"),
            )
        assertNotEquals(forgedCase, honestCase)
        // CanonicalJson escapes the quote, so the two records no longer serialise identically.
        assertNotEquals(forgedCase.canonical(), honestCase.canonical())
    }

    /**
     * The consequence, and the reason the two tests above matter: a colliding twin is not just a
     * cosmetic canonicalisation problem, it is an undetectable substitution. Swapping record 0
     * of a chain for a record with the same canonical bytes used to leave every hash intact, so
     * the chain reported itself sound while describing a decision the officer never made —
     * the I6 claim failing open, which is the one direction it must never fail in
     * (AGENTS.md §4: "no silent default-pass"). Canonicalising through `CanonicalJson` with a
     * real JSON array for `findings` is what closed it.
     */
    @Test
    fun aCollidingTwinIsNowRejectedRatherThanAccepted() {
        val original =
            listOf(
                AuditChainFixture.plain(0).copy(findingCodes = listOf("R_MATH_01", "Q_SIG_INVALID")),
                AuditChainFixture.plain(1),
                AuditChainFixture.plain(2),
            )
        val forged =
            listOf(
                AuditChainFixture.plain(0).copy(findingCodes = listOf("R_MATH_01|Q_SIG_INVALID")),
                AuditChainFixture.plain(1),
                AuditChainFixture.plain(2),
            )
        val chain = AuditChainFixture.build(original, digest)
        assertNotEquals(original[0], forged[0])
        assertFalse(
            chain.verifyExternal(forged).valid,
            "the twin must be rejected: this was the I6 fail-open case",
        )
        assertEquals(0, chain.verifyExternal(forged).brokenAtIndex)
    }

    // --- an empty incoming list ----------------------------------------------------------------

    /**
     * `verifyExternal` used to short-circuit on `incoming.isEmpty()`, so an empty list was
     * reported as a valid chain of length 0 no matter what this chain contained: a caller
     * asking "does this bundle agree with my log?" and handed nothing got `yes`. That is the
     * "truncation is undetectable" shape — not the remove-one-from-the-middle case, which the
     * tip catches, but remove *all* of them.
     *
     * Comparing record by record fixes it and, as a bonus, lets the failure be localised.
     */
    @Test
    fun removingEveryRecordIsRejected() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(5), digest)
        val verification = chain.verifyExternal(emptyList())
        assertFalse(verification.valid, "an empty list must not pass against a 5-record chain")
        assertEquals(0, verification.brokenAtIndex)
        // An empty list against an empty chain is genuinely sound, and stays so.
        assertTrue(AuditChainFixture.build(emptyList(), digest).verifyExternal(emptyList()).valid)
    }

    /**
     * `verifyExternal` used to reconstruct a *candidate* tip and compare it to the stored one,
     * so it could not name where a chain stopped agreeing — it reported the last index for
     * every failure, which is useful as "something is wrong" and useless as "here is what to
     * look at". Comparing records directly makes the field mean what it says.
     */
    @Test
    fun verifyExternalLocalisesTheTamper() {
        val records = AuditChainFixture.chain(5)
        val chain = AuditChainFixture.build(records, digest)
        val tampered = records.toMutableList().also { it[0] = it[0].copy(verdict = "GREEN") }
        val verification = chain.verifyExternal(tampered)
        assertFalse(verification.valid)
        // It used to always blame the last index regardless of what changed, which made the
        // field useless as "here is what to look at".
        assertEquals(0, verification.brokenAtIndex, "the record that actually differs")
        assertTrue(verification.reason.isNotBlank())
    }

    // --- KNOWN LIMITATION: the digest is the whole security property ---------------------------

    /**
     * A digest that returns constant bytes makes every hash in the chain identical — genesis
     * included, so the tip never leaves the genesis value — and `verify()` still passes.
     *
     * Pinned deliberately. It states the contract in one test: `AuditChain` provides *chaining*,
     * and chaining is worthless without a real [Digest]. `:core` must not supply one itself
     * (AGENTS.md §5 forbids hand-rolled crypto), so the entire property rests on `:platform`
     * wiring `JcaDigest` correctly. `app-desktop`'s `AuditStore` does exactly that, and this
     * test is the reminder that a future default-constructor convenience would silently remove
     * the only thing making the chain sound.
     */
    @Test
    fun aConstantDigestCollapsesTheChain() {
        val chain = AuditChainFixture.build(AuditChainFixture.chain(5), ConstantDigest())
        assertEquals(5, chain.size)
        assertEquals(AuditChainFixture.expectedGenesis(ConstantDigest()), chain.tip(), "the tip never advances")
        assertTrue(chain.verify().valid, "and the chain still reports itself sound")
    }

    /** The other half of the same contract, stated as a failure: with no digest, no detection. */
    @Test
    fun aConstantDigestAcceptsEveryTamper() {
        val records = AuditChainFixture.chain(5)
        val chain = AuditChainFixture.build(records, ConstantDigest())
        for (index in records.indices) {
            val tampered = records.toMutableList().also { it[index] = it[index].copy(verdict = "GREEN") }
            // `verifyExternal` compares canonical forms, not hashes, so it is independent of
            // the digest entirely — even a constant one cannot hide a tampered record.
            assertFalse(
                chain.verifyExternal(tampered).valid,
                "record $index: verifyExternal compares canonicals, so no digest can hide this",
            )
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Returns a *different* value for the same input on every call. Never used in production. */
    private class RotatingDigest : Digest {
        private val inner = FakeDigest()
        private var calls = 0

        override fun sha256(bytes: ByteArray): ByteArray {
            calls++
            return inner.sha256(bytes).also { it[0] = calls.toByte() }
        }
    }

    /** 32 identical bytes. The weakest digest a `Digest` implementation can legally return. */
    private class ConstantDigest(
        private val fill: Byte = 0x00,
    ) : Digest {
        override fun sha256(bytes: ByteArray): ByteArray = ByteArray(32) { fill }
    }

    private companion object {
        val KEY = Regex("\"([A-Za-z]+)\":")
    }
}
