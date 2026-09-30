package dev.kasoti.eval.suites

import dev.kasoti.eval.fixtures.QrFixtures
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.FailureCase
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.TableData
import dev.kasoti.qr.QrCrossCheck
import dev.kasoti.qr.SecureQr
import dev.kasoti.qr.SigResult

/**
 * The QR tamper-catch gate (SPEC.md §7, EVAL.md §2 `D-QR`, ≥300 fixtures, 100%).
 *
 * Two gates, not one, because "100%" is ambiguous on its own:
 *
 *  - `Q-GATE-01` — every *rejected* fixture must actually be rejected. A forged name, a
 *    flipped bit, an unknown key: all caught.
 *  - `Q-GATE-02` — every *accepted* fixture must verify against the key it claims. This is
 *    the other way to cheat: refuse everything, pass the tamper gate, and never verify a
 *    single genuine document.
 *
 * The suite also checks [QrCrossCheck], because a valid signature over the wrong person is
 * the interesting forgery: `R-QR-02` is what catches it, and a suite that only tested
 * `verify` would never notice the difference between "signature good" and "document good".
 */
object QrSuite {

    const val GATE_REJECT = "Q-GATE-01"
    const val GATE_ACCEPT = "Q-GATE-02"
    const val GATE_CROSSCHECK = "Q-GATE-03"

    const val SEED = 20_260_930L

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val tables: List<TableData>,
        val failures: List<FailureCase>,
        val notes: List<String>,
    )

    fun run(size: Int = QrFixtures.TARGET_SIZE): Result {
        val corpus = QrFixtures.build(seed = SEED, size = size)
        val verifier = QrFixtures.verifier()

        val byExpectation = linkedMapOf<QrFixtures.Expectation, IntArray>() // [total, asExpected]
        val failures = mutableListOf<FailureCase>()
        var crossCheckPairs = 0
        var crossCheckCaught = 0

        for (case in corpus.cases) {
            val outcome = SecureQr.verify(case.payload, corpus.ring, verifier)
            val asExpected = outcome.matches(case.expectation)
            byExpectation.getOrPut(case.expectation) { IntArray(2) }[0]++
            if (asExpected) {
                byExpectation.getValue(case.expectation)[1]++
            } else {
                failures += FailureCase(
                    suite = "qr",
                    caseId = case.id,
                    reason = "verification outcome did not match the fixture's intent " +
                        "(mutation: ${case.mutation})",
                    expected = case.expectation.name,
                    observed = outcome.describe(),
                    artefactRef = "keyId=${case.keyId}; fields=" +
                        case.payload.fields.entries.joinToString(",") { "${it.key}=${it.value}" },
                    gateId = if (case.expectation == QrFixtures.Expectation.VALID) GATE_ACCEPT else GATE_REJECT,
                )
            }

            // R-QR-02: a correctly signed payload that describes a different person.
            if (case.expectation == QrFixtures.Expectation.VALID) {
                val wrongPrint = "FORGED PERSON ${case.id}"
                val mismatches = QrCrossCheck.compare(case.payload, wrongPrint, "1970-01-01")
                crossCheckPairs++
                if (mismatches.isNotEmpty()) crossCheckCaught++
            }
        }

        val rejected = byExpectation.filterKeys {
            it == QrFixtures.Expectation.REJECTED || it == QrFixtures.Expectation.UNSIGNED
        }
        val rejectedTotal = rejected.values.sumOf { it[0] }
        val rejectedCaught = rejected.values.sumOf { it[1] }
        val acceptedTotal = byExpectation[QrFixtures.Expectation.VALID]?.get(0) ?: 0
        val acceptedOk = byExpectation[QrFixtures.Expectation.VALID]?.get(1) ?: 0
        val staleTotal = byExpectation[QrFixtures.Expectation.STALE]?.get(0) ?: 0
        val staleOk = byExpectation[QrFixtures.Expectation.STALE]?.get(1) ?: 0

        val rejectPct = ratio(rejectedCaught, rejectedTotal)
        val acceptPct = ratio(acceptedOk, acceptedTotal)
        val crossPct = ratio(crossCheckCaught, crossCheckPairs)

        val gates = listOf(
            GateResult(
                id = GATE_REJECT,
                name = "QR tamper-catch on rejected fixtures",
                status = if (rejectPct >= 1.0) GateStatus.PASS else GateStatus.FAIL,
                bar = "100% (SPEC.md §7)",
                observed = pct(rejectPct) + " ($rejectedCaught/$rejectedTotal)",
                detail = "bit-flipped payloads, re-encoded fields, rogue-key signatures and " +
                    "unsigned payloads, all of which must not verify. SecureQr reports a signed " +
                    "but unverifiable payload as UnknownKey (no key matched) and an unsigned one " +
                    "as Invalid; both are rejections, and RedRules.qrSignature collapses them " +
                    "into R-QR-01 behind a single signatureValid boolean.",
                evidenceRef = "dev.kasoti.eval.fixtures.QrFixtures.build/$SEED/$size",
            ),
            GateResult(
                id = GATE_ACCEPT,
                name = "QR accept rate on genuine signed fixtures",
                status = if (acceptPct >= 1.0) GateStatus.PASS else GateStatus.FAIL,
                bar = "100% — refusing every payload satisfies the tamper gate and is useless",
                observed = pct(acceptPct) + " ($acceptedOk/$acceptedTotal)",
                detail = "Genuine payloads signed by a bundled key must return SigResult.Valid.",
                evidenceRef = "dev.kasoti.eval.fixtures.QrFixtures.build/$SEED/$size",
            ),
            GateResult(
                id = GATE_CROSSCHECK,
                name = "QR↔print cross-check catches a valid signature over the wrong person",
                status = if (crossPct >= 1.0) GateStatus.PASS else GateStatus.FAIL,
                bar = "100% (FUSION.md R-QR-02)",
                observed = pct(crossPct) + " ($crossCheckCaught/$crossCheckPairs)",
                detail = "A genuine, correctly signed payload compared against a mismatched " +
                    "printed name and DOB must produce at least one QrMismatch.",
                evidenceRef = "dev.kasoti.qr.QrCrossCheck.compare",
            ),
        )

        val metrics = listOf(
            Metric("qr.corpus.size", corpus.cases.size.toDouble(), "cases", "all-test", GATE_REJECT),
            Metric("qr.tamper_catch_pct", rejectPct, "%", "all-test", GATE_REJECT),
            Metric("qr.accept_pct", acceptPct, "%", "all-test", GATE_ACCEPT),
            Metric("qr.crosscheck_catch_pct", crossPct, "%", "all-test", GATE_CROSSCHECK),
            Metric("qr.stale_key_rows", staleTotal.toDouble(), "cases", "all-test", GATE_ACCEPT),
            Metric("qr.stale_key_detected_pct", ratio(staleOk, staleTotal), "%", "all-test", GATE_ACCEPT,
                "SYS_KEYS_STALE: a valid signature under an expired key is reported, never silently accepted"),
            Metric("qr.rejected_rows", rejectedTotal.toDouble(), "cases", "all-test", GATE_REJECT),
            Metric("qr.unsigned_rows", (byExpectation[QrFixtures.Expectation.UNSIGNED]?.get(0) ?: 0).toDouble(),
                "cases", "all-test", GATE_REJECT,
                "an unsigned QR is a source of claims, never evidence (A-QR-01)"),
        )

        val table = TableData(
            name = "QR verification outcome by fixture arm",
            rowLabels = byExpectation.keys.map { it.name },
            columnLabels = listOf("cases", "as expected", "%"),
            cells = byExpectation.map { (_, counts) ->
                listOf(
                    counts[0].toDouble(),
                    counts[1].toDouble(),
                    if (counts[0] == 0) 0.0 else counts[1] * 100.0 / counts[0],
                )
            },
        )

        val notes = buildList {
            add(
                "Signatures are produced at run time by a HARNESS STUB verifier over a " +
                    "per-JVM RSA keypair. Production verification is the JCA binding in " +
                    ":platform; these numbers certify :core's key selection and tamper " +
                    "detection, not the platform binding.",
            )
            add("All key material is labelled TEST and is never written to eval/data/keys/.")
            add(
                "Ring rotation date is fixed: one bundled key is inside the rotation window " +
                    "(${QrFixtures.FRESH_KEY_ID}) and two are outside it, so a valid signature " +
                    "reports Valid and a valid signature under an expired key reports StaleKeys " +
                    "— distinguishable outcomes rather than the same code path. Every tamper " +
                    "arm is signed by the FRESH key precisely so the tamper check and the " +
                    "rotation check cannot be confused for one another.",
            )
        }

        return Result(gates, metrics, listOf(table), failures, notes)
    }

    /**
     * The acceptance rule, stated once.
     *
     * `Valid` is the only accepting outcome, `StaleKeys` is accepting-with-a-warning, and
     * everything else is a rejection. Asserting that split rather than exact enum values is
     * what makes the gate measure the security property ("an unsigned or unverifiable QR is
     * not evidence") instead of an implementation label.
     */
    private fun SigResult.matches(expected: QrFixtures.Expectation): Boolean = when (expected) {
        QrFixtures.Expectation.VALID -> this is SigResult.Valid
        QrFixtures.Expectation.REJECTED -> this !is SigResult.Valid && this !is SigResult.StaleKeys
        QrFixtures.Expectation.STALE -> this is SigResult.StaleKeys
        QrFixtures.Expectation.UNSIGNED -> this is SigResult.Invalid
    }

    private fun SigResult.describe(): String = when (this) {
        is SigResult.Valid -> "Valid(keyId=$keyId)"
        is SigResult.StaleKeys -> "StaleKeys(keyId=$keyId)"
        SigResult.Invalid -> "Invalid"
        SigResult.UnknownKey -> "UnknownKey"
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 1.0 else numerator.toDouble() / denominator

    private fun pct(v: Double): String = "%.4f".format(v * 100).trimEnd('0').trimEnd('.') + "%"
}
