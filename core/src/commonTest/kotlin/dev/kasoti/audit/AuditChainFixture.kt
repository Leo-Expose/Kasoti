package dev.kasoti.audit

import dev.kasoti.crypto.Digest
import dev.kasoti.crypto.Hex
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fixtures and the tamper matrix for the audit-chain suites.
 *
 * `AuditChain` is the implementation of invariant I6 (DESIGN.md §8, "audit chain verifies")
 * and the whole reason an officer's decision is reconstructable (FR-S4), so the properties
 * worth testing are adversarial rather than happy-path: a hash chain that only detects
 * truncation, or only detects edits to whole records, is not a chain.
 *
 * The matrix lives in its own file rather than inside either test class because it is
 * executed twice — once against the deterministic fake digest every `:core` suite uses, and
 * once against real JCA SHA-256 in `src/jvmTest/…/AuditChainSha256Test.kt`. Sharing it makes
 * "the JCA run covers the same cases as the fake-digest run" a structural fact instead of a
 * claim, and it keeps the two suites from drifting apart as fields are added to
 * [DecisionRecord].
 *
 * Everything here is deterministic. No clock, no randomness, no ambient state: a failure has
 * to be reproducible from the test name alone.
 */
object AuditChainFixture {
    /** The literal `AuditChain` hashes to seed its genesis hash. Pinned so a change is visible. */
    const val GENESIS_SEED: String = "kasoti-audit/1"

    /** 64 hex characters after the `sha256:` prefix, so the field looks like a real model pin. */
    val EMB_MODEL: String = "emb_v1@sha256:" + "ab".repeat(32)

    const val THR_VERSION: String = "thresholds.v1"
    const val FUSION_VERSION: String = "fusion.v1"
    const val OVERRIDER: String = "off.raju"

    /** Real vocabulary from `app-desktop`'s override command, not an invented code. */
    const val OVERRIDE_REASON: String = "SUP_DOC_AUTHENTIC"

    /**
     * A first-pass decision: no override, the shape every non-escalated verdict takes.
     *
     * [index] varies the id and the timestamp only. Two records differing in exactly one field
     * is the unit the tamper matrix needs, and a chain where every record differs in several
     * fields could not tell a one-field tamper from a wholesale rewrite.
     */
    fun plain(index: Int = 0): DecisionRecord =
        DecisionRecord(
            id = "evt_01HQ0000000000000000" + suffix(index),
            timestampUtc = "2026-03-14T09:%02d:00.000Z".format(index),
            caseId = "case_01HQ0000000000000000B",
            deviceId = "post3-ph1",
            verdict = "RED",
            findingCodes = listOf("R_MATH_01", "Q_SIG_INVALID"),
            thresholdVersion = THR_VERSION,
            fusionRuleVersion = FUSION_VERSION,
            embeddingModel = EMB_MODEL,
            overriddenBy = null,
            overrideReasonCode = null,
        )

    /** The same record after a supervisor override — every optional field populated. */
    fun full(index: Int = 0): DecisionRecord =
        plain(index).copy(
            overriddenBy = OVERRIDER,
            overrideReasonCode = OVERRIDE_REASON,
        )

    /** [count] distinct records, in a stable order. [override] populates the optional fields. */
    fun chain(
        count: Int,
        override: Boolean = false,
    ): List<DecisionRecord> = (0 until count).map { if (override) full(it) else plain(it) }

    /** An [AuditChain] holding exactly [records], in order. */
    fun build(
        records: List<DecisionRecord>,
        digest: Digest,
    ): AuditChain = AuditChain(digest).apply { records.forEach { append(it) } }

    /** @return the tip of the chain these records would produce, computed independently. */
    fun expectedGenesis(digest: Digest): String = Hex.encode(digest.sha256(GENESIS_SEED.toByteArray(Charsets.UTF_8)))

    /**
     * One entry per [DecisionRecord] field: a label, and a mutation that changes **only** that
     * field's value and always keeps the record constructible.
     *
     * Three of the fourteen are the same field reached three ways, because a `List<String>`
     * carries more than one kind of lie: dropping a finding, adding one, and *reordering* the
     * same findings. Reordering is the one a naive implementation gets wrong — the canonical
     * form joins them with `|`, so order does change the bytes, and that is what makes the
     * finding sequence tamper-evident rather than merely tamper-attemptable.
     *
     * Applied to [full], which has an override, so the two override fields can be mutated
     * without tripping the constructor's "override without a reason code" guard.
     */
    fun mutations(): List<Pair<String, (DecisionRecord) -> DecisionRecord>> =
        listOf(
            "id" to { r -> r.copy(id = r.id + "T") },
            "timestampUtc" to { r -> r.copy(timestampUtc = r.timestampUtc.replace(".000Z", ".001Z")) },
            "caseId" to { r -> r.copy(caseId = r.caseId.dropLast(1) + "C") },
            "deviceId" to { r -> r.copy(deviceId = r.deviceId.dropLast(1) + "2") },
            "verdict" to { r -> r.copy(verdict = if (r.verdict == "RED") "AMBER" else "RED") },
            "findingCodes.dropLast" to { r -> r.copy(findingCodes = r.findingCodes.dropLast(1)) },
            "findingCodes + one" to { r -> r.copy(findingCodes = r.findingCodes + "M_OK") },
            "findingCodes.reversed" to { r -> r.copy(findingCodes = r.findingCodes.reversed()) },
            "thresholdVersion" to { r -> r.copy(thresholdVersion = r.thresholdVersion + ".2") },
            "fusionRuleVersion" to { r -> r.copy(fusionRuleVersion = r.fusionRuleVersion + ".2") },
            "embeddingModel" to { r -> r.copy(embeddingModel = r.embeddingModel?.dropLast(2) + "cd") },
            "embeddingModel -> null" to { r -> r.copy(embeddingModel = null) },
            "overriddenBy" to { r -> r.copy(overriddenBy = r.overriddenBy?.uppercase() ?: OVERRIDER) },
            "overrideReasonCode" to { r ->
                r.copy(overrideReasonCode = r.overrideReasonCode?.lowercase() ?: OVERRIDE_REASON)
            },
        )

    /**
     * The property the chain exists for: an edit anywhere in the chain is detected.
     *
     * Asserted three ways, because a single assertion would pass for the wrong reason:
     *
     *  1. the pristine chain still verifies, so a failure cannot be blamed on a broken fixture;
     *  2. `verifyExternal` rejects the edited list;
     *  3. the edited list's *own* tip differs from the stored tip — the tip moved, so nothing
     *     downstream can mistake the edit for the original.
     *
     * The mutation is also checked to have actually changed the record, because a no-op
     * mutation would make every one of these assertions pass vacuously.
     */
    fun assertTamperRejected(
        digest: Digest,
        original: List<DecisionRecord>,
        index: Int,
        label: String,
        mutate: (DecisionRecord) -> DecisionRecord,
    ) {
        val chain = build(original, digest)
        val where = "record $index / $label"
        assertTrue(chain.verify().valid, "$where: the untampered chain must verify first")

        val edited = original[index].let(mutate)
        assertNotEquals(original[index], edited, "$where: the mutation must actually change the record")

        val tampered = original.toMutableList().also { it[index] = edited }
        val verdict = chain.verifyExternal(tampered)
        assertFalse(verdict.valid, "$where: verifyExternal accepted a tampered chain")
        assertTrue(verdict.reason.isNotEmpty(), "$where: a rejection must carry a reason")

        assertNotEquals(
            chain.tip(),
            build(tampered, digest).tip(),
            "$where: the tip did not move, so the edit is invisible to anything that trusts it",
        )
    }

    /**
     * Runs the whole matrix: every field, at every position in the chain.
     *
     * The base is [full], not [plain]: two of the mutations touch the override fields, and
     * setting `overriddenBy` on a record with no reason code is rejected by the constructor
     * before it ever reaches the chain. An override is part of the record, so it has to be in
     * scope for the tamper to be a *silent* edit rather than a loud crash.
     */
    fun runTamperMatrix(
        digest: Digest,
        depth: Int = 5,
    ) {
        val records = chain(depth, override = true)
        for (index in 0 until depth) {
            for ((label, mutate) in mutations()) {
                assertTamperRejected(digest, records, index, label, mutate)
            }
        }
    }

    /**
     * @return the indices of the records [size] removes one at a time, i.e. every way a log can
     *   be truncated while still looking like a prefix of itself.
     */
    fun removalShapes(size: Int): List<Pair<String, List<DecisionRecord>>> {
        val records = chain(size)
        val shapes = mutableListOf<Pair<String, List<DecisionRecord>>>()
        for (dropped in records.indices) {
            shapes += "dropped record $dropped" to (records.indices - dropped).map { records[it] }
        }
        shapes += "reversed" to records.reversed()
        for (i in 0 until records.size - 1) {
            val swapped = records.toMutableList()
            val tmp = swapped[i]
            swapped[i] = swapped[i + 1]
            swapped[i + 1] = tmp
            shapes += "swapped $i and ${i + 1}" to swapped
        }
        return shapes
    }

    /** Asserts [verification] read as a failure, with a usable [because] message. */
    fun assertRejected(
        verification: ChainVerification,
        because: String,
    ) {
        assertFalse(verification.valid, "$because: expected a rejection, got a pass")
        assertNotNull(verification.brokenAtIndex, "$because: a rejection must locate a record")
    }

    private fun suffix(index: Int): String = index.toString(16).uppercase().padStart(2, '0')
}
