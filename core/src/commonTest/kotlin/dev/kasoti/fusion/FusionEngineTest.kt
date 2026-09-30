package dev.kasoti.fusion

import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision order of FUSION.md §1, the fail-closed coverage rules, and the shape of the
 * report the rest of the system consumes.
 *
 * These are the cross-cutting cases: the interaction between a bad capture and a real proof,
 * the anti-gaming escalation, a layer that never ran, and the record a decision has to leave
 * behind.
 */
internal class FusionEngineTest : FusionFixtures() {

    @Test
    fun `a failed quality gate is GREY with every cause listed`() {
        val report = decide(
            cleanPassport {
                copy(quality = QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR, FindingCode.G_DARK)))
            },
        )
        assertEquals(Verdict.GREY, report.verdict)
        assertEquals(setOf(FindingCode.G_BLUR, FindingCode.G_DARK), report.greyCauses.map { it.code }.toSet())
        assertTrue(report.red.isEmpty())
    }

    @Test
    fun `an expired document on a bad capture stays GREY and the proof is still attached`() {
        val report = decide(
            cleanPassport {
                copy(
                    quality = QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR)),
                    math = MathEvidence(allChecksPassed = true, expired = true),
                )
            },
        )
        assertEquals(Verdict.GREY, report.verdict, "never accuse off a bad capture")
        assertEquals(1, report.carriedOver.size)
        val carried = report.carriedOver.single()
        assertEquals(FindingCode.R_MATH_02, carried.code)
        assertTrue(carried.message.startsWith("retake; note: prior capture already showed R_MATH_02"))
        assertTrue(carried.evidenceRef.startsWith("carried-over/"))
        assertTrue(report.rationale.any { it.contains("carried-over proof") })
    }

    @Test
    fun `a face proof is not carried over a failed quality gate`() {
        val report = decide(
            cleanPassport {
                copy(
                    quality = QualityReport(passed = false, causes = listOf(FindingCode.G_GLARE)),
                    face = FaceEvidence(0.20f, 1f, 1f, passiveLivenessScore = 0.1f, headTurnPassed = true),
                )
            },
        )
        assertEquals(Verdict.GREY, report.verdict)
        assertTrue(report.carriedOver.isEmpty(), "a bad frame is exactly what lowers a face similarity")
    }

    @Test
    fun `three consecutive retakes escalate to AMBER with a supervisor`() {
        val grey = decide(
            cleanPassport {
                copy(quality = QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR)), consecutiveGreyCount = 2)
            },
        )
        assertEquals(Verdict.GREY, grey.verdict, "two in a row is still a retake")
        assertFalse(grey.supervisorRequired)

        val escalated = decide(
            cleanPassport {
                copy(quality = QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR)), consecutiveGreyCount = 3)
            },
        )
        assertEquals(Verdict.AMBER, escalated.verdict)
        assertTrue(escalated.has(FindingCode.A_GREY3))
        assertTrue(escalated.supervisorRequired)
        assertEquals(FindingCode.A_GREY3, escalated.firstHit?.code)
        assertTrue(escalated.has(FindingCode.G_BLUR), "the retake is still needed, so its cause stays")
    }

    @Test
    fun `repeated retakes do not escalate a case that is already going to a human`() {
        val red = decide(
            cleanPassport {
                copy(math = MathEvidence(allChecksPassed = true, expired = true), consecutiveGreyCount = 9)
            },
        )
        assertEquals(Verdict.RED, red.verdict)
        assertFalse(red.has(FindingCode.A_GREY3))
    }

    @Test
    fun `a missing macro clip on a passport is a retake, not a pass`() {
        val report = decide(
            cleanPassport {
                copy(macro = macro().copy(clipUsed = false))
            },
        )
        assertEquals(Verdict.GREY, report.verdict)
        assertTrue(report.has(FindingCode.G_NOCLIP))
    }

    @Test
    fun `empty evidence is neither GREEN nor RED`() {
        val report = decide(Evidence(track = Track.PASSPORT))
        assertEquals(Verdict.GREY, report.verdict)
        assertTrue(report.has(FindingCode.G_NOEVIDENCE))
        assertTrue(report.red.isEmpty())
        assertEquals(FindingCode.G_OCRLOW, report.firstHit?.code, "the first retake cause leads the report")
    }

    // ------------------------------------------------------------------ fail-closed coverage

    @Test
    fun `an absent chip layer produces no chip finding in either direction`() {
        val report = decide(cleanPassport { copy(chip = null) })
        assertFalse(report.has(FindingCode.R_CHIP_01), "no chip means no chip accusation")
        assertFalse(report.has(FindingCode.CHIP_OK), "no chip means no chip confirmation")
        assertEquals(LayerStatus.ABSENT, report.layers.statusOf(Layer.CHIP))
        assertTrue(Layer.CHIP in report.layers.unresolved)
    }

    @Test
    fun `an unsupported chip reader is neither a RED nor a silent pass`() {
        val report = decide(
            cleanPassport {
                copy(
                    chip = ChipEvidence(
                        present = true,
                        passiveAuthValid = false,
                        dg1MatchesMrz = false,
                        supported = false,
                    ),
                )
            },
        )
        assertFalse(report.has(FindingCode.R_CHIP_01), "a stub reader must not manufacture a proof")
        assertFalse(report.has(FindingCode.CHIP_OK), "and must not manufacture a confirmation")
        assertEquals(LayerStatus.UNKNOWN, report.layers.statusOf(Layer.CHIP))
        assertEquals(Verdict.AMBER, report.verdict, "an unverifiable load-bearing layer blocks GREEN")
        assertTrue(report.has(FindingCode.A_MISSING_LAYER))
    }

    @Test
    fun `a missing face layer blocks GREEN instead of passing silently`() {
        val report = decide(cleanPassport { copy(face = null) })
        assertEquals(Verdict.AMBER, report.verdict)
        assertTrue(report.has(FindingCode.A_MISSING_LAYER))
        assertTrue(report.amber.any { it.evidenceRef.contains("face=ABSENT") })
    }

    @Test
    fun `a chip layer that is not applicable to the track is not a missing check`() {
        val report = decide(aadhaar())
        assertEquals(LayerStatus.UNSUPPORTED, report.layers.statusOf(Layer.CHIP))
        assertFalse(report.has(FindingCode.A_MISSING_LAYER))
    }

    @Test
    fun `an unrecognised document family cannot be cleared`() {
        val report = decide(cleanPassport { copy(track = Track.UNKNOWN) })
        assertEquals(Verdict.AMBER, report.verdict)
        assertTrue(report.has(FindingCode.SYS_UNSUPPORTED_TRACK))
    }

    // ------------------------------------------------------------------ report and trust lane

    @Test
    fun `a clean passport is GREEN and confirms what it checked`() {
        val report = decide(cleanPassport())
        assertEquals(Verdict.GREEN, report.verdict)
        assertEquals(
            setOf(
                FindingCode.M_OK,
                FindingCode.Q_SIG_OK,
                FindingCode.CHIP_OK,
                FindingCode.FACE_OK,
                FindingCode.MACRO_OK,
                FindingCode.DIARY_CLEAR,
            ),
            report.confirmations.map { it.code }.toSet(),
        )
        assertFalse(report.trustFastPath, "a stranger is not on the fast path")
        assertTrue(report.findings.all { it.evidenceRef.isNotBlank() })
    }

    @Test
    fun `the trust fast path is offered only to an enrolled subject whose face check cleared`() {
        val enrolled = decide(cleanPassport { copy(trust = TrustState.ENROLLED) })
        assertEquals(Verdict.GREEN, enrolled.verdict)
        assertTrue(enrolled.has(FindingCode.TRUST_OK))
        assertTrue(enrolled.trustFastPath)

        val recheck = decide(cleanPassport { copy(trust = TrustState.RANDOM_RECHECK) })
        assertEquals(Verdict.GREEN, recheck.verdict)
        assertFalse(recheck.trustFastPath, "the scheduler asked for the full check")

        val revoked = decide(cleanPassport { copy(trust = TrustState.REVOKED) })
        assertFalse(revoked.trustFastPath)
    }

    @Test
    fun `demo mode travels with the report so evidence can never merge into a real diary`() {
        val report = decide(cleanPassport { copy(demoMode = true) })
        assertTrue(report.demoMode)
        assertFalse(decide(cleanPassport()).demoMode)
    }

    @Test
    fun `the report names the operating point that produced it`() {
        val pinned = ThresholdRegistry.defaults(version = "v7", runId = "eval-2026-smoke")
        val report = FusionEngine.decide(cleanPassport(), pinned, year)
        assertEquals("v7", report.thresholdVersion)
        assertEquals("eval-2026-smoke", report.thresholdRunId)
        assertEquals(FusionEngine.FUSION_RULE_VERSION, report.fusionRuleVersion)
        assertTrue(report.policyVersions.contains("thresholds=v7@eval-2026-smoke"))
    }
}
