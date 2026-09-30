package dev.kasoti.fusion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AMBER rules of FUSION.md §3 and the ranking the officer actually reads.
 *
 * AMBER is "a machine saw something odd and cannot accuse"; the recurring assertion in this
 * file is that a *near miss* stays there. A zone margin under the floor, a watchlist score
 * under the threshold, a runner-up inside the delta margin — none of them may escalate.
 */
internal class FusionAmberRulesTest : FusionFixtures() {

    @Test
    fun `A-PROC-01 an uncorroborated zone disagreement is AMBER`() {
        val report = decide(cleanPassport { copy(macro = macro(ProcessLabel.OFFSET, 0.90f, ProcessLabel.INKJET, 0.20f)) })
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals(FindingCode.A_PROC_01, report.amber.single().code)
    }

    @Test
    fun `A-FACE-01 the ambiguous band is AMBER not RED`() {
        val report = decide(
            cleanPassport {
                copy(face = FaceEvidence(0.45f, 1f, 1f, passiveLivenessScore = 0.9f, headTurnPassed = true))
            },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals(FindingCode.A_FACE_01, report.amber.single().code)
    }

    @Test
    fun `A-LIVE-01 a weak passive score is AMBER and a failed challenge is a second finding`() {
        val weak = decide(
            cleanPassport {
                copy(face = FaceEvidence(0.80f, 1f, 1f, passiveLivenessScore = 0.20f, headTurnPassed = true))
            },
        )
        assertEquals(Verdict.AMBER, weak.verdict)
        assertEquals(FindingCode.A_LIVE_01, weak.amber.single().code)

        val failed = decide(
            cleanPassport {
                copy(face = FaceEvidence(0.80f, 1f, 1f, passiveLivenessScore = 0.90f, headTurnPassed = false))
            },
        )
        assertEquals(Verdict.AMBER, failed.verdict)
        assertEquals(FindingCode.A_LIVE_01, failed.amber.single().code)
    }

    @Test
    fun `A-QR-01 an unsigned QR on a track that needs one is AMBER`() {
        val report = decide(
            aadhaar { copy(qr = QrEvidence(present = true, signed = false, signatureValid = false)) },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertTrue(report.has(FindingCode.A_QR_01))
    }

    @Test
    fun `A-QR-01 an unsigned QR whose fields disagree with the print is AMBER, not RED`() {
        val report = decide(
            aadhaar {
                copy(
                    qr = QrEvidence(
                        present = true,
                        signed = false,
                        signatureValid = false,
                        mismatches = listOf("name" to ("SHARMA RAMESH" to "KHAN AYISHA")),
                    ),
                )
            },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertFalse(report.has(FindingCode.R_QR_02), "an unsigned QR is never a hard proof")
        assertTrue(report.has(FindingCode.A_QR_01))
    }

    @Test
    fun `A-FAC-01 a facilitator pattern is AMBER and the registry gate is re-applied`() {
        val qualifying = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        facilitatorFlags = listOf(FacilitatorFlag(4, 20, listOf("evt_01", "evt_02", "evt_03"))),
                    ),
                )
            },
        )
        assertEquals(Verdict.AMBER, qualifying.verdict)
        assertEquals(FindingCode.A_FAC_01, qualifying.amber.single().code)

        val tooFewGroups = decide(
            cleanPassport {
                copy(diary = DiaryEvidence(facilitatorFlags = listOf(FacilitatorFlag(2, 5, listOf("evt_01")))))
            },
        )
        assertEquals(Verdict.GREEN, tooFewGroups.verdict, "two groups is not a facilitator pattern")
    }

    @Test
    fun `A-WL-01 a watchlist hit is AMBER with a supervisor`() {
        val report = decide(
            cleanPassport { copy(diary = DiaryEvidence(watchlistHits = listOf(hit("evt_77", 0.80f)))) },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals(FindingCode.A_WL_01, report.amber.single().code)
        assertTrue(report.supervisorRequired)

        val below = decide(
            cleanPassport { copy(diary = DiaryEvidence(watchlistHits = listOf(hit("evt_77", 0.50f)))) },
        )
        assertEquals(Verdict.GREEN, below.verdict, "below T_wl is not a watchlist hit")
    }

    @Test
    fun `A-WORN-01 two undecided zones abstain instead of accusing`() {
        val report = decide(
            cleanPassport { copy(macro = macro(ProcessLabel.OFFSET, 0.04f, ProcessLabel.INKJET, 0.03f)) },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals(FindingCode.A_WORN_01, report.amber.single().code)
    }

    @Test
    fun `A-VIZ-01 printed and machine-readable zones drifting apart is AMBER`() {
        val report = decide(
            cleanPassport {
                copy(math = MathEvidence(allChecksPassed = true, vizDrift = DriftScore("name", 0.10f, "A B", "C D")))
            },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals(FindingCode.A_VIZ_01, report.amber.single().code)
    }

    @Test
    fun `AMBER reasons are ranked in table order with the strongest first within a rule`() {
        val report = decide(
            cleanPassport {
                copy(
                    face = FaceEvidence(0.45f, 1f, 1f, passiveLivenessScore = 0.1f, headTurnPassed = true),
                    macro = macro(ProcessLabel.OFFSET, 0.90f, ProcessLabel.INKJET, 0.20f),
                    diary = DiaryEvidence(
                        watchlistHits = listOf(hit("evt_90", 0.90f), hit("evt_91", 0.79f)),
                    ),
                )
            },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        val codes = report.amber.map { it.code }
        assertEquals(
            listOf(
                FindingCode.A_PROC_01,
                FindingCode.A_FACE_01,
                FindingCode.A_LIVE_01,
                FindingCode.A_WL_01,
                FindingCode.A_WL_01,
            ),
            codes,
        )
        val watchlist = report.amber.filter { it.code == FindingCode.A_WL_01 }
        assertTrue(watchlist.first().evidenceRef.contains("evt_90"), "the stronger watchlist hit ranks first")
    }

    // ------------------------------------------------------------------ §1 decision order
}
