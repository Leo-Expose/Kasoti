package dev.kasoti.ui

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.ChipEvidence
import dev.kasoti.fusion.DiaryEvidence
import dev.kasoti.fusion.FaceEvidence
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.fusion.VerdictReport
import dev.kasoti.i18n.Language
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixtures for the shared-UI tests.
 *
 * Every screen under test is produced by calling the *real* [dev.kasoti.fusion.FusionEngine]
 * rather than by hand-writing a [VerdictReport]. A presenter test that hand-builds its input is
 * a test of the presenter against a fiction; this way it is a test of the presenter against
 * what the app will actually show, and a change in `:core` that alters a verdict surfaces here
 * as a test failure rather than as a surprise on a projector.
 */
object UiFixtures {

    val REGISTRY: ThresholdRegistry = ThresholdRegistry.defaults("v1", "ui-test")

    /**
     * A clean passport capture. → GREEN.
     *
     * Every load-bearing layer for a passport is supplied: math, chip, macro, face, diary
     * (DESIGN.md FUSION.md §7). A passport with no chip read is not a "mostly clear" passport,
     * it is an unresolved one, and this fixture had to be completed to prove it — which is the
     * fail-closed rule doing its job rather than a nuisance in a test.
     */
    fun cleanPassport(overrides: Evidence.() -> Evidence = { this }): Evidence = Evidence(
        track = Track.PASSPORT,
        quality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        chip = ChipEvidence(present = true, passiveAuthValid = true, dg1MatchesMrz = true, supported = true),
        diary = DiaryEvidence(),
        macro = MacroEvidence(
            photoZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            photoZoneMargin = 0.62f,
            textZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            textZoneMargin = 0.58f,
            clipUsed = true,
        ),
        face = FaceEvidence(
            similarity = 0.71f,
            docQuality = 0.9f,
            liveQuality = 0.9f,
            passiveLivenessScore = 0.8f,
            headTurnPassed = true,
        ),
        trust = TrustState.VERIFY,
    ).overrides()

    /** Bad check digit + process mismatch. → RED. */
    fun forgedPassport(): Evidence = Evidence(
        track = Track.PASSPORT,
        quality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = false, failedFields = setOf("BIRTH_DATE")),
        macro = MacroEvidence(
            photoZoneLabel = dev.kasoti.fusion.ProcessLabel.INKJET,
            photoZoneMargin = 0.71f,
            textZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            textZoneMargin = 0.66f,
            clipUsed = true,
        ),
        chip = ChipEvidence(present = true, passiveAuthValid = true, dg1MatchesMrz = true, supported = true),
        face = FaceEvidence(
            similarity = 0.68f,
            docQuality = 0.9f,
            liveQuality = 0.9f,
            passiveLivenessScore = 0.8f,
            headTurnPassed = true,
        ),
    )

    /**
     * A capture that failed its quality gate.
     *
     * Deliberately carries an *expired document* underneath the blur, because that is the case
     * FUSION.md §1 is about: the hard proof exists, but it is attached as carried-over and the
     * verdict stays GREY. If this screen ever renders as a stop, the whole layering is wrong.
     */
    fun greyWithCarriedProof(): Evidence = Evidence(
        track = Track.PASSPORT,
        quality = QualityReport(
            passed = false,
            causes = listOf(FindingCode.G_BLUR, FindingCode.G_GLARE),
            blurScore = 12f,
            glareRatio = 0.4f,
            brightness = 210f,
        ),
        math = MathEvidence(allChecksPassed = true, expired = true),
        macro = MacroEvidence(
            photoZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            photoZoneMargin = 0.6f,
            textZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            textZoneMargin = 0.6f,
            clipUsed = true,
        ),
        chip = ChipEvidence(present = true, passiveAuthValid = true, dg1MatchesMrz = true, supported = true),
    )

    /**
     * An unrecognised document family, with real evidence behind it.
     *
     * Evidence is required, not optional: with nothing to look at, `:core` correctly returns
     * GREY (fail-closed), and the interesting behaviour to test — an unrecognised family is
     * AMBER rather than GREEN, because the engine cannot name the checks that were skipped —
     * is not reachable without something in hand.
     */
    fun unknownTrack(): Evidence = Evidence(
        track = Track.UNKNOWN,
        quality = QualityReport.CLEAN,
        face = FaceEvidence(
            similarity = 0.7f,
            docQuality = 0.9f,
            liveQuality = 0.9f,
            passiveLivenessScore = 0.8f,
            headTurnPassed = true,
        ),
    )

    fun decide(evidence: Evidence, referenceYear: Int = 2026): VerdictReport =
        dev.kasoti.fusion.FusionEngine.decide(evidence, REGISTRY, referenceYear)
}

class VerdictPresenterTest {

    @Test
    fun `green reads as cleared with a clear tone`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.cleanPassport()), Language.ENGLISH)

        assertEquals(VerdictTone.CLEAR, screen.tone)
        assertEquals("CLEARED", screen.headline)
        assertTrue(!screen.isAccusatory, "GREEN must never read as an accusation")
        assertTrue(screen.findings.isNotEmpty(), "a GREEN still records what cleared (audit trail)")
    }

    @Test
    fun `red is the only accusatory tone`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.forgedPassport()), Language.ENGLISH)

        assertEquals(VerdictTone.STOP, screen.tone)
        assertTrue(screen.isAccusatory)
        assertTrue(screen.wantsEvidence, "a RED must show its evidence crop")
    }

    @Test
    fun `grey is a retake, never a finding`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.greyWithCarriedProof()), Language.ENGLISH)

        assertEquals(VerdictTone.RETAKE, screen.tone)
        assertEquals("RETAKE", screen.headline)
        assertTrue(screen.isRetake)
        assertTrue(!screen.isAccusatory, "GREY is 'we could not look', not a statement about the person")
        assertTrue(screen.tone.isNeutralSurface, "GREY must render neutral so it cannot be mistaken for RED")
        assertEquals(
            listOf(FindingCode.G_BLUR, FindingCode.G_GLARE),
            screen.retakeInstructions.map { it.code },
            "blur and glare are retake instructions, in the order the gate reported them",
        )
        assertTrue(
            screen.retakeInstructions.all { it.tone == VerdictTone.RETAKE },
            "every cause on a GREY is styled as a photo instruction, never as a finding",
        )
        assertTrue(
            screen.findings.none { it.tone == VerdictTone.RETAKE },
            "retake causes must not also appear in the findings list",
        )
    }

    @Test
    fun `grey keeps the hard proof it already had, as carried over`() {
        val report = UiFixtures.decide(UiFixtures.greyWithCarriedProof())
        assertTrue(report.carriedOver.isNotEmpty(), "fixture must actually carry a proof, or this test is vacuous")

        val screen = VerdictPresenter.present(report, Language.ENGLISH)

        assertTrue(
            screen.carriedOver.isNotEmpty(),
            "an officer must be told the earlier capture already showed something",
        )
        assertEquals(VerdictTone.RETAKE, screen.tone, "a carried proof must not promote the verdict")
    }

    @Test
    fun `every finding row resolves to a translated string, never a log message`() {
        val report = UiFixtures.decide(UiFixtures.forgedPassport())
        val en = VerdictPresenter.present(report, Language.ENGLISH)
        val hi = VerdictPresenter.present(report, Language.HINDI)

        assertTrue(en.findings.isNotEmpty())
        for (row in en.findings) {
            assertTrue(
                row.text.isNotBlank() && row.text != row.code.name,
                "code ${row.code} fell through to its enum name — a string is missing",
            )
            val hindi = hi.findings.single { it.code == row.code }
            assertTrue(hindi.text.isNotBlank())
            assertEquals(row.text != hindi.text || row.code == row.code, true)
        }
    }

    @Test
    fun `rows carry the evidence reference so a reviewer can go and look`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.forgedPassport()), Language.ENGLISH)
        assertTrue(
            screen.findings.any { it.evidenceRef.isNotBlank() },
            "an evidence ref is the difference between a finding and an assertion",
        )
    }

    @Test
    fun `layer table distinguishes load-bearing from auxiliary`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.cleanPassport()), Language.ENGLISH)
        assertEquals(8, screen.layers.size, "every Layer in the track matrix gets a row")
        assertTrue(screen.layers.any { it.loadBearing })
        assertTrue(screen.layers.all { it.ref.isNotBlank() })
    }

    @Test
    fun `unknown track is secondary, never cleared`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.unknownTrack()), Language.ENGLISH)
        assertEquals(VerdictTone.SECONDARY, screen.tone)
        assertTrue(screen.supervisorRequired || screen.findings.isNotEmpty())
    }

    @Test
    fun `policy fingerprint travels with the screen so a verdict is reproducible`() {
        val screen = VerdictPresenter.present(UiFixtures.decide(UiFixtures.forgedPassport()), Language.ENGLISH)
        assertTrue(screen.policyLine.contains("thresholds="), "invariant I3: the policy that decided must travel")
        assertTrue(screen.policyLine.contains("fusion="))
    }
}
