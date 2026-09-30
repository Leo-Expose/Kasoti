package dev.kasoti.fusion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hard-RED rules of FUSION.md §2, plus the alias corroboration rules of §5.
 *
 * A RED is the only output of this system that can put someone in trouble, so every case here
 * checks three things: the verdict moved, the code is the one the table names, and the finding
 * points at an artefact that could be shown to a human.
 */
internal class FusionRedRulesTest : FusionFixtures() {

    @Test
    fun `R-MATH-01 a failed check digit is RED and names the field`() {
        val report = decide(
            cleanPassport {
                copy(math = MathEvidence(allChecksPassed = false, failedFields = setOf("DOCUMENT_NUMBER", "BIRTH_DATE")))
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(2, report.red.size)
        assertTrue(report.red.all { it.code == FindingCode.R_MATH_01 })
        assertTrue(report.red.any { it.evidenceRef.startsWith("mrz/BIRTH_DATE") })
    }

    @Test
    fun `R-MATH-02 an expired document is RED`() {
        val report = decide(cleanPassport { copy(math = MathEvidence(allChecksPassed = true, expired = true)) })
        assertEquals(Verdict.RED, report.verdict)
        assertEquals("doc/dates#expired", report.red.single().evidenceRef)
    }

    @Test
    fun `R-MATH-02 also covers impossible dates and issue after expiry`() {
        val report = decide(
            cleanPassport {
                copy(math = MathEvidence(allChecksPassed = true, impossibleDate = true, issueAfterExpiry = true))
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(
            setOf("doc/dates#impossible", "doc/dates#issue-after-expiry"),
            report.red.map { it.evidenceRef }.toSet(),
        )
    }

    @Test
    fun `R-QR-01 an invalid signature on a signed QR is RED and stale keys are noted`() {
        val report = decide(
            cleanPassport {
                copy(qr = QrEvidence(present = true, signed = true, signatureValid = false, keysStale = true))
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_QR_01, report.red.single().code)
        assertTrue(report.red.single().message.contains("rotation"))
        assertTrue(report.has(FindingCode.SYS_KEYS_STALE), "the stale keyring is still reported")
    }

    @Test
    fun `R-QR-02 a gross name disagreement between a signed QR and the print is RED`() {
        val report = decide(
            cleanPassport {
                copy(
                    qr = QrEvidence(
                        present = true,
                        signed = true,
                        signatureValid = true,
                        mismatches = listOf("name" to ("SHARMA RAMESH" to "KHAN AYISHA")),
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_QR_02, report.red.single().code)
        assertEquals("qr/vs-print#name", report.red.single().evidenceRef)
    }

    @Test
    fun `R-QR-02 accepts a half-transliterated name as consistent`() {
        val report = decide(
            cleanPassport {
                copy(
                    qr = QrEvidence(
                        present = true,
                        signed = true,
                        signatureValid = true,
                        mismatches = listOf("name" to ("SHARMA RAMESH K" to "SHARMA<<RAMESH")),
                    ),
                )
            },
        )
        assertFalse(report.has(FindingCode.R_QR_02), "a shared token set must not be a RED")
        assertEquals(Verdict.GREEN, report.verdict)
    }

    @Test
    fun `R-QR-02 resolves a two-digit date rather than calling a reformatting a mismatch`() {
        val report = decide(
            cleanPassport {
                copy(
                    qr = QrEvidence(
                        present = true,
                        signed = true,
                        signatureValid = true,
                        mismatches = listOf("dob" to ("980101" to "1998-01-01")),
                    ),
                )
            },
        )
        assertFalse(report.has(FindingCode.R_QR_02), "YYMMDD and ISO are the same date")
    }

    @Test
    fun `R-CHIP-01 a failed passive authentication is RED`() {
        val report = decide(
            cleanPassport {
                copy(
                    chip = ChipEvidence(
                        present = true,
                        passiveAuthValid = false,
                        dg1MatchesMrz = true,
                        supported = true,
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_CHIP_01, report.red.single().code)
    }

    @Test
    fun `R-FACE-01 compares the quality adjusted similarity against T_FACE_RED`() {
        val report = decide(
            cleanPassport {
                copy(
                    face = FaceEvidence(
                        similarity = 0.30f,
                        docQuality = 1f,
                        liveQuality = 1f,
                        passiveLivenessScore = 0.9f,
                        headTurnPassed = true,
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict, "0.30 adjusted is below T_FACE_RED=0.35")
        assertEquals(FindingCode.R_FACE_01, report.red.single().code)
        assertTrue(report.red.single().evidenceRef.contains("@T=0.350"), "the ref carries the threshold")
    }

    @Test
    fun `tempering pushes a borderline match into AMBER instead of claiming GREEN`() {
        // 0.60 at full quality is 0.60 adjusted, which clears T_FACE_GREEN=0.55.
        val sharp = decideFace(similarity = 0.60f, quality = 1f)
        assertEquals(Verdict.GREEN, sharp.verdict)
        assertTrue(sharp.has(FindingCode.FACE_OK))

        // The same raw score on a poor capture is tempered to 0.38, inside the band.
        val dull = decideFace(similarity = 0.60f, quality = 0.05f)
        assertEquals(Verdict.AMBER, dull.verdict)
        assertEquals(FindingCode.A_FACE_01, dull.amber.single().code)
        assertFalse(dull.has(FindingCode.FACE_OK), "a bad capture must not claim a face confirmation")
    }

    @Test
    fun `R-ALIAS-01 a corroborated alias is RED and always needs a supervisor`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        aliasHits = listOf(hit(similarity = 0.86f)),
                        topHitSimilarity = 0.86f,
                        runnerUpSimilarity = 0.70f,
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_ALIAS_01, report.red.single().code)
        assertTrue(report.supervisorRequired, "an alias is never a silent RED")
        assertTrue(report.red.single().message.contains("supervisor"))
    }

    @Test
    fun `R-TRAV-01 an implied speed above the policy maximum is RED`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        travelFlags = listOf(
                            TravelFlag("evt_01", "evt_02", "RAXAUL", "PATNA", 500.0, 1.0, 500.0),
                        ),
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_TRAV_01, report.red.single().code)
        assertTrue(report.red.single().evidenceRef.contains("RAXAUL->PATNA"))
    }

    @Test
    fun `R-TRAV-01 ignores a speed inside the policy maximum`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        travelFlags = listOf(TravelFlag("evt_01", "evt_02", "RAXAUL", "PATNA", 100.0, 1.0, 100.0)),
                    ),
                )
            },
        )
        assertEquals(Verdict.GREEN, report.verdict)
    }

    @Test
    fun `R-PROC-01 a confident SCREEN label on a claimed paper document is RED`() {
        val report = decide(
            cleanPassport { copy(macro = macro(ProcessLabel.SCREEN, 0.91f, ProcessLabel.SCREEN, 0.88f)) },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertTrue(report.red.all { it.code == FindingCode.R_PROC_01 })
        assertTrue(report.red.any { it.evidenceRef.contains("photo-zone") })
        assertTrue(report.red.any { it.evidenceRef.contains("text-zone") })
    }

    @Test
    fun `R-PROC-01 does not fire when the screen was declared honestly`() {
        val report = decide(
            cleanPassport {
                copy(
                    presentation = Presentation.SCREEN,
                    macro = macro(ProcessLabel.SCREEN, 0.91f, ProcessLabel.SCREEN, 0.88f),
                )
            },
        )
        assertEquals(Verdict.GREEN, report.verdict, "a screen showing a document is its own case")
    }

    @Test
    fun `R-PROC-01 treats a declared screen with real paper as AMBER, never RED`() {
        val report = decide(
            cleanPassport {
                copy(presentation = Presentation.SCREEN, macro = macro(ProcessLabel.OFFSET, 0.91f, ProcessLabel.OFFSET, 0.88f))
            },
        )
        assertEquals(Verdict.AMBER, report.verdict)
        assertEquals("macro/photo-zone,macro/text-zone#claimed-screen", report.amber.single().evidenceRef)
    }

    @Test
    fun `R-PROC-02 needs both zones above the RED margin`() {
        val report = decide(cleanPassport { copy(macro = macro(ProcessLabel.OFFSET, 0.90f, ProcessLabel.INKJET, 0.85f)) })
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(FindingCode.R_PROC_02, report.red.single().code)
    }

    // ------------------------------------------------------------------ §3 AMBER

    @Test
    fun `the alias delta rule keeps a near tie out of the RED band`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        aliasHits = listOf(hit(similarity = 0.86f), hit("evt_02", 0.84f)),
                        topHitSimilarity = 0.86f,
                        runnerUpSimilarity = 0.84f,
                    ),
                )
            },
        )
        assertEquals(Verdict.AMBER, report.verdict, "0.02 is inside delta_margin=0.08")
        assertTrue(report.red.isEmpty())
        assertEquals(2, report.amber.size)
        assertTrue(report.amber.first().message.contains("not actionable alone"))
    }

    @Test
    fun `a second alias hit is context, not an independent accusation`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        aliasHits = listOf(hit("evt_01", 0.90f), hit("evt_02", 0.86f)),
                        topHitSimilarity = 0.90f,
                        runnerUpSimilarity = 0.70f,
                    ),
                )
            },
        )
        assertEquals(Verdict.RED, report.verdict)
        assertEquals(1, report.red.size, "only the hit that beat the runner-up can be RED")
        assertEquals("diary/alias#evt_01@RAXAUL/2026-01-02T04:15:00Z", report.red.single().evidenceRef)
    }

    @Test
    fun `an alias below the threshold is not an alias at all`() {
        val report = decide(
            cleanPassport {
                copy(
                    diary = DiaryEvidence(
                        aliasHits = listOf(hit(similarity = 0.60f)),
                        topHitSimilarity = 0.60f,
                        runnerUpSimilarity = 0.20f,
                    ),
                )
            },
        )
        assertEquals(Verdict.GREEN, report.verdict)
    }

    @Test
    fun `every finding carries a severity and a real evidence reference`() {
        val report = decide(
            cleanPassport {
                copy(
                    math = MathEvidence(allChecksPassed = false, failedFields = setOf("NAME"), expired = true),
                    macro = macro(ProcessLabel.OFFSET, 0.9f, ProcessLabel.INKJET, 0.8f),
                    diary = DiaryEvidence(travelFlags = listOf(TravelFlag("a", "b", "P1", "P2", 900.0, 1.0, 900.0))),
                )
            },
        )
        assertNotNull(report.firstHit)
        assertTrue(report.findings.all { it.evidenceRef.isNotBlank() && "#" in it.evidenceRef })
        assertEquals(FindingCode.R_MATH_01, report.firstHit?.code, "FUSION.md §2 table order decides the first hit")
    }
}
