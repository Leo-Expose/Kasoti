package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.ProcessLabel as FusionLabel
import dev.kasoti.fusion.QrEvidence
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The console's reading of FUSION.md.
 *
 * The engine exists only until `dev.kasoti.fusion.FusionEngine` lands, so what is being
 * pinned here is the *decision order* (FUSION.md §1) rather than every rule. Order is the
 * part that is easy to get subtly wrong and impossible to notice: a rule engine that
 * checks AMBER before RED produces a calmer, friendlier system that quietly stops
 * detecting the thing it exists to detect.
 */
class LocalRuleEngineTest {

    private val engine = LocalRuleEngine()
    private val registry = ThresholdRegistry.defaults("v-test", "test-run")

    /** Any fixed year: the local engine only reads it to resolve two-digit MRZ dates. */
    private val REFERENCE_YEAR = 2026

    private fun decide(evidence: Evidence) = engine.decide(evidence, registry, REFERENCE_YEAR)

    private fun Evidence.codes() = decide(this).findings.map { it.code }

    private fun Evidence.verdict() = decide(this).verdict

    private fun clean(track: Track = Track.PASSPORT) = Evidence(track = track, quality = QualityReport.CLEAN)

    // ------------------------------------------------------------------ order

    @Test
    fun `a clean case with no evidence is GREEN, not AMBER`() {
        assertEquals(Verdict.GREEN, clean().verdict())
    }

    /** FUSION.md §1: a failed capture outranks every data rule. */
    @Test
    fun `a failed quality gate wins over an expired document`() {
        val evidence = Evidence(
            track = Track.PASSPORT,
            quality = QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR)),
            math = MathEvidence(allChecksPassed = false, expired = true),
        )
        assertEquals(Verdict.GREY, evidence.verdict())
        // The hard-RED data proof is still attached as a finding, per §1.
        assertTrue(FindingCode.R_MATH_02 in evidence.codes())
    }

    @Test
    fun `RED outranks AMBER`() {
        val evidence = clean().copy(
            math = MathEvidence(allChecksPassed = false, failedFields = setOf("COMPOSITE")),
            qr = QrEvidence(present = true, signed = false, signatureValid = false, unsignedFieldsPresent = true),
        )
        assertEquals(Verdict.RED, evidence.verdict())
    }

    @Test
    fun `AMBER outranks GREEN`() {
        val evidence = clean().copy(macro = wornMacro())
        assertEquals(Verdict.AMBER, evidence.verdict())
    }

    // -------------------------------------------------------------------- math

    @Test
    fun `a check digit failure is R-MATH-01 and RED`() {
        val evidence = clean().copy(math = MathEvidence(allChecksPassed = false, failedFields = setOf("DOC")))
        assertEquals(Verdict.RED, evidence.verdict())
        assertTrue(FindingCode.R_MATH_01 in evidence.codes())
    }

    @Test
    fun `the failing field is named in the evidence reference`() {
        val evidence = clean().copy(math = MathEvidence(allChecksPassed = false, failedFields = setOf("BIRTH")))
        val finding = decide(evidence).findings.first { it.code == FindingCode.R_MATH_01 }
        assertEquals("mrz.check", finding.evidenceRef)
        assertTrue(finding.message.contains("BIRTH"))
    }

    @Test
    fun `an expired document is R-MATH-02`() {
        val evidence = clean().copy(math = MathEvidence(allChecksPassed = true, expired = true))
        assertTrue(FindingCode.R_MATH_02 in evidence.codes())
    }

    @Test
    fun `clean math raises no finding at all`() {
        val evidence = clean().copy(math = MathEvidence(allChecksPassed = true))
        assertTrue(decide(evidence).findings.isEmpty())
    }

    // --------------------------------------------------------------------- QR

    @Test
    fun `an invalid signature on a signed QR is R-QR-01`() {
        val evidence = clean().copy(
            qr = QrEvidence(present = true, signed = true, signatureValid = false),
        )
        assertTrue(FindingCode.R_QR_01 in evidence.codes())
    }

    @Test
    fun `an unsigned QR is A-QR-01, never R-QR-01`() {
        val evidence = clean().copy(
            qr = QrEvidence(present = true, signed = false, signatureValid = false, unsignedFieldsPresent = true),
        )
        assertFalse(FindingCode.R_QR_01 in evidence.codes())
        assertTrue(FindingCode.A_QR_01 in evidence.codes())
    }

    @Test
    fun `a verified signature is recorded as a positive confirmation`() {
        val evidence = clean().copy(
            qr = QrEvidence(present = true, signed = true, signatureValid = true),
        )
        val confirmation = decide(evidence).findings.first { it.code == FindingCode.Q_SIG_OK }
        assertEquals(Severity.INFO, confirmation.severity)
        assertEquals(Verdict.GREEN, evidence.verdict())
    }

    @Test
    fun `a field mismatch is R-QR-02 and names the field`() {
        val evidence = clean().copy(
            qr = QrEvidence(
                present = true,
                signed = true,
                signatureValid = true,
                mismatches = listOf("name" to ("QR" to "PRINT")),
            ),
        )
        val finding = decide(evidence).findings.first { it.code == FindingCode.R_QR_02 }
        assertEquals("qr.field.name", finding.evidenceRef)
    }

    @Test
    fun `a stale key is SYS_KEYS_STALE at AMBER, not a verification failure`() {
        val evidence = clean().copy(
            qr = QrEvidence(present = true, signed = true, signatureValid = true, keysStale = true),
        )
        assertTrue(FindingCode.SYS_KEYS_STALE in evidence.codes())
        assertFalse(FindingCode.R_QR_01 in evidence.codes())
    }

    // ------------------------------------------------------------------ macro

    @Test
    fun `confidently disagreeing zones are R-PROC-02`() {
        val confident = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.1f
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.OFFSET,
                photoZoneMargin = confident,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = confident,
                clipUsed = true,
            ),
        )
        val finding = decide(evidence).findings.first { it.code == FindingCode.R_PROC_02 }
        assertTrue(finding.message.contains("OFFSET"), finding.message)
        assertTrue(finding.message.contains("SCREEN"), finding.message)
    }

    /**
     * Low margins mean "don't trust this", not "zones disagree".
     *
     * FUSION.md §5 sends a below-floor patch to UNKNOWN, so both zones abstain, they agree
     * by agreeing not to, and the case takes A-WORN-01. Calling it A-PROC-01 would report
     * a paste-attack suspicion that the evidence does not support; calling it R-PROC-02
     * would accuse somebody on the strength of two zeroes.
     */
    @Test
    fun `zones under the margin floor abstain to A-WORN-01 rather than accusing`() {
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.OFFSET,
                photoZoneMargin = 0.01f,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = 0.01f,
                clipUsed = true,
            ),
        )
        val codes = evidence.codes()
        assertFalse(FindingCode.R_PROC_02 in codes, "an under-floor pair must never be a paste")
        assertFalse(FindingCode.A_PROC_01 in codes, "abstaining is not a disagreement")
        assertTrue(FindingCode.A_WORN_01 in codes)
        assertEquals(Verdict.AMBER, evidence.verdict())
    }

    /** One confident zone and one abstaining zone is a disagreement, but not a confident one. */
    @Test
    fun `one confident zone and one under-floor zone is A-PROC-01, not RED`() {
        val confident = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.1f
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.OFFSET,
                photoZoneMargin = confident,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = 0.01f,
                clipUsed = true,
            ),
        )
        val codes = evidence.codes()
        assertTrue(FindingCode.A_PROC_01 in codes)
        assertFalse(FindingCode.R_PROC_02 in codes)
    }

    @Test
    fun `a SCREEN reading for a physical claim is R-PROC-01`() {
        val confident = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.1f
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.SCREEN,
                photoZoneMargin = confident,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = confident,
                clipUsed = true,
            ),
            presentation = Presentation.PHYSICAL,
        )
        assertTrue(FindingCode.R_PROC_01 in evidence.codes())
        assertEquals(Verdict.RED, evidence.verdict())
    }

    @Test
    fun `a SCREEN reading for a screen presentation is not R-PROC-01`() {
        val confident = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.1f
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.SCREEN,
                photoZoneMargin = confident,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = confident,
                clipUsed = true,
            ),
            presentation = Presentation.SCREEN,
        )
        assertFalse(FindingCode.R_PROC_01 in evidence.codes())
    }

    @Test
    fun `agreeing zones raise no macro finding`() {
        val confident = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.1f
        val evidence = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.OFFSET,
                photoZoneMargin = confident,
                textZoneLabel = FusionLabel.OFFSET,
                textZoneMargin = confident,
                clipUsed = true,
            ),
        )
        assertFalse(FindingCode.R_PROC_02 in evidence.codes())
        assertFalse(FindingCode.A_PROC_01 in evidence.codes())
    }

    @Test
    fun `a capture without the clip is A-WORN-01`() {
        val evidence = clean().copy(macro = wornMacro())
        assertTrue(FindingCode.A_WORN_01 in evidence.codes())
    }

    // ------------------------------------------------------------------- trust

    @Test
    fun `a revoked enrolment is RED`() {
        val evidence = clean().copy(trust = dev.kasoti.fusion.TrustState.REVOKED)
        assertEquals(Verdict.RED, evidence.verdict())
    }

    @Test
    fun `the GREY streak is read from the registry limit`() {
        val limit = registry[ThresholdName.GREY_STREAK_LIMIT].toInt()
        assertTrue(FindingCode.A_GREY3 in clean().copy(consecutiveGreyCount = limit).codes())
        assertFalse(FindingCode.A_GREY3 in clean().copy(consecutiveGreyCount = limit - 1).codes())
    }

    /**
     * Demo mode raises no finding and moves no verdict.
     *
     * It is a provenance flag, not an observation. The only `FindingCode` that could carry
     * it is an operational one, and every operational code resolves to an operator-facing
     * sentence about a real problem — "the camera could not capture" would be a lie on
     * screen. The flag travels on the case record and in the bundle instead (invariant I7).
     */
    @Test
    fun `demo mode raises no finding and leaves the verdict alone`() {
        val demo = clean().copy(demoMode = true)
        assertTrue(demo.demoMode)
        assertEquals(Verdict.GREEN, demo.verdict())
        assertTrue(decide(demo).findings.isEmpty())
    }

    // ----------------------------------------------------------------- policy

    @Test
    fun `the rule version is recorded and is not blank`() {
        assertTrue(engine.ruleVersion.isNotBlank())
        assertTrue(engine.ruleVersion.contains("fusion-v4"))
    }

    @Test
    fun `findings come back in severity order, RED first`() {
        val evidence = clean().copy(
            math = MathEvidence(allChecksPassed = false),
            qr = QrEvidence(present = true, signed = false, signatureValid = false, unsignedFieldsPresent = true),
        )
        val severities = decide(evidence).findings.map { it.severity }
        assertEquals(severities.sortedBy { it.ordinal }, severities)
    }

    /** A threshold change must move the decision, or invariant I3 is a lie. */
    @Test
    fun `moving a threshold in the registry moves the decision`() {
        val above = registry[ThresholdName.MACRO_MARGIN_RED].toFloat() + 0.05f
        val base = clean().copy(
            macro = MacroEvidence(
                photoZoneLabel = FusionLabel.OFFSET,
                photoZoneMargin = above,
                textZoneLabel = FusionLabel.SCREEN,
                textZoneMargin = above,
                clipUsed = true,
            ),
        )
        assertTrue(FindingCode.R_PROC_02 in base.codes())

        val raised = registry.withValue(ThresholdName.MACRO_MARGIN_RED, 0.95)
        val after = engine.decide(base, raised, REFERENCE_YEAR)
        assertFalse(FindingCode.R_PROC_02 in after.findings.map { it.code })
        assertTrue(FindingCode.A_PROC_01 in after.findings.map { it.code })
    }

    private fun wornMacro() = MacroEvidence(
        photoZoneLabel = FusionLabel.UNKNOWN,
        photoZoneMargin = 0f,
        textZoneLabel = FusionLabel.UNKNOWN,
        textZoneMargin = 0f,
        clipUsed = false,
    )
}
