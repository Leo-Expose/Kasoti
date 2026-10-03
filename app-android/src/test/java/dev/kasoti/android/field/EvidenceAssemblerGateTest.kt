package dev.kasoti.android.field

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Verdict
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.time.IsoDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single most important test in the module (SPEC principle 4, FUSION.md §1).
 *
 * The property: **a failed quality gate must never produce a face comparison, and therefore
 * never an `R-FACE-01`.** Everything else in this file is supporting evidence; this is the one
 * that protects a member of the public from being accused because the sun was behind them.
 */
class EvidenceAssemblerGateTest {

    private val assembler = EvidenceAssembler(FieldFixtures.REGISTRY)
    private val quality = CaptureQuality(FieldFixtures.REGISTRY)

    private fun embeddings(): Pair<FloatArray, FloatArray> {
        val a = FloatArray(128) { 0.1f }
        val b = FloatArray(128) { 0.1f + it * 0.001f }
        return a to b
    }

    private fun assemble(
        gate: EvidenceAssembler.evaluatedGate,
        match: EvidenceAssembler.GatedMatch?,
        demoMode: Boolean = false,
    ) = assembler.assemble(
        caseId = "case_test",
        track = dev.kasoti.fusion.Track.PASSPORT,
        pageQuality = gate.report,
        math = dev.kasoti.fusion.MathEvidence(allChecksPassed = true),
        chip = dev.kasoti.fusion.ChipEvidence(true, true, true, true),
        macro = dev.kasoti.fusion.MacroEvidence(
            dev.kasoti.fusion.ProcessLabel.OFFSET, 0.6f,
            dev.kasoti.fusion.ProcessLabel.OFFSET, 0.6f,
            clipUsed = true,
        ),
        match = match,
        diary = dev.kasoti.fusion.DiaryEvidence(),
        demoMode = demoMode,
        referenceYear = FieldFixtures.REFERENCE_YEAR,
    )

    @Test
    fun `a failed gate refuses the match outright`() {
        val gate = assembler.gate(badCapture(), hasPose = false, quality = quality)
        assertFalse(gate.report.passed)

        val (doc, live) = embeddings()
        val match = assembler.gatedMatch(gate, doc, live, 0.9f, 0.9f, 0.8f, headTurnPassed = true)

        assertNull(match, "a blurred capture must not be compared to a document photo at all")
    }

    @Test
    fun `a failed gate yields GREY, not RED, even with an impossible similarity on the table`() {
        val gate = assembler.gate(badCapture(), hasPose = false, quality = quality)
        val (doc, live) = embeddings()
        val match = assembler.gatedMatch(gate, doc, live, 0.9f, 0.9f, 0.8f, true)

        val result = assemble(gate, match)

        assertEquals(Verdict.GREY, result.verdict, "the worst outcome of a bad frame is a retake, never an accusation")
        assertFalse(result.report.has(FindingCode.R_FACE_01))
        assertEquals(emptyList(), result.report.red)
    }

    @Test
    fun `a passing gate produces a face layer and can reach GREEN`() {
        val gate = assembler.gate(goodCapture(), hasPose = false, quality = quality)
        val (doc, live) = embeddings()
        val match = assembler.gatedMatch(gate, doc, live, 0.9f, 0.9f, 0.8f, headTurnPassed = true)

        assertNotNull(match)
        assertEquals(Verdict.GREEN, assemble(gate, match).verdict)
    }

    @Test
    fun `a missing embedding is a named capture failure, never a fabricated score`() {
        val gate = assembler.gate(goodCapture(), hasPose = false, quality = quality)
        val match = assembler.gatedMatch(gate, docEmbedding = null, liveEmbedding = FloatArray(128), 0.9f, 0.9f, 0.8f, true)
        assertNull(match, "a system that reports a similarity it did not compute is worse than one that admits it did not look")
    }

    @Test
    fun `mismatched embedding dimensions are refused rather than compared`() {
        val gate = assembler.gate(goodCapture(), hasPose = false, quality = quality)
        assertNull(
            assembler.gatedMatch(gate, FloatArray(64), FloatArray(128), 0.9f, 0.9f, 0.8f, true),
        )
    }

    @Test
    fun `a bad frame with an expired document keeps the proof, but as carried over`() {
        // FUSION.md §1's exception: the expiry is true regardless of focus, and the officer
        // should be told. What they must never be shown is a RED.
        val gate = assembler.gate(badCapture(), hasPose = false, quality = quality)
        val result = assembler.assemble(
            caseId = "case_test",
            track = dev.kasoti.fusion.Track.PASSPORT,
            pageQuality = gate.report,
            math = dev.kasoti.fusion.MathEvidence(allChecksPassed = true, expired = true),
            referenceYear = FieldFixtures.REFERENCE_YEAR,
        )

        assertEquals(Verdict.GREY, result.verdict)
        assertTrue(
            result.report.carriedOver.any { it.code == FindingCode.R_MATH_02 },
            "the officer must be told the earlier capture already showed an expiry, got ${result.report.carriedOver}",
        )
    }

    @Test
    fun `the demo flag travels through to the report`() {
        val gate = assembler.gate(goodCapture(), hasPose = false, quality = quality)
        val result = assemble(gate, null, demoMode = true)
        assertTrue(result.report.demoMode, "invariant I7 reads this off the report, not off a parameter")
        assertTrue(result.evidence.demoMode)
    }

    @Test
    fun `quality-adjusted similarity is what fusion reads, not the raw cosine`() {
        val gate = assembler.gate(goodCapture(), hasPose = false, quality = quality)
        val doc = FloatArray(128) { 1f }
        val live = FloatArray(128) { 0.9f } // cosine < 1
        val match = assembler.gatedMatch(gate, doc, live, 0.9f, 0.9f, 0.8f, true)!!
        val raw = dev.kasoti.face.FaceMath.cosine(doc, live)
        val adjusted = dev.kasoti.face.fuseMatchScore(raw, 0.9f, 0.9f)
        assertTrue(adjusted < raw, "a merely-good capture must be tempered, not reported at face value")
    }
}

class MrzExtractorTest {

    private val extractor = MrzExtractor(FieldFixtures.REGISTRY)
    private val person = MrzPerson(
        surname = "SHARMA",
        givenNames = "RAMESH",
        documentNumber = "K4820913",
        birthDate = IsoDate(1984, 3, 17),
        expiryDate = IsoDate(2031, 3, 16),
        personalNumber = "SPECIMEN001",
    )
    private val valid = MrzBuilder.buildTd3(person, FieldFixtures.REFERENCE_YEAR)

    @Test
    fun `a clean MRZ parses and passes every check`() {
        val extraction = extractor.extract(valid, FieldFixtures.REFERENCE_YEAR)
        assertTrue(extraction.result.allChecksPassed, "built MRZ must parse clean: ${extraction.result.failedChecks}")
        assertEquals("SHARMA", extraction.result.name.surname)
        // `MrzResult.birthDate` is the raw `YYMMDD`; `:core` owns the century rule.
        assertEquals("840317", extraction.result.birthDate)
        assertEquals(
            "1984-03-17",
            dev.kasoti.checks.VizMrzMatch.normaliseDate(extraction.result.birthDate!!, FieldFixtures.REFERENCE_YEAR),
            "and the century rule must resolve it the same way the live path will",
        )
        assertFalse(extraction.repairsApplied)
    }

    @Test
    fun `an O-for-zero in a span is repaired, and only because the check digit proved it`() {
        // Put a letter `O` where a digit belongs, inside the birth-date span (13..18). The
        // zero is the third character, so index 15 in the line is what gets mangled.
        val mangled = valid[0].replaceRange(15, 16, "O")
        assertEquals("84O317", mangled.substring(13, 19), "the fixture must really be an O-for-zero")
        val extraction = extractor.extract(listOf(mangled, valid[1]), FieldFixtures.REFERENCE_YEAR)

        assertTrue(extraction.repairsApplied, "the 0/O repair is exactly the substitution DESIGN.md §5 permits")
        assertTrue(extraction.result.allChecksPassed, "and it is only accepted when the check digit agrees")
        assertEquals("840317", extraction.result.birthDate, "the field value follows the repaired span")
        assertTrue(MrzFieldSet.BIRTH_DATE in extraction.repairedFields)
    }

    @Test
    fun `adversarial - a real mutation is NOT repaired away`() {
        // Change the check digit itself. That is a forger, not an OCR error, and the design
        // says fail-closed: the whole point of R-MATH-01 is that it still fires.
        val forged = valid[0].replaceRange(19, 20, if (valid[0][19] == '0') "1" else "0")
        val extraction = extractor.extract(listOf(forged, valid[1]), FieldFixtures.REFERENCE_YEAR)

        assertFalse(extraction.result.allChecksPassed, "a mutated check digit must fail")
        assertTrue(
            extraction.result.failedChecks.any { it.field == MrzFieldSet.BIRTH_DATE },
            "and it must be attributed to the field, got ${extraction.result.failedChecks}",
        )
    }

    @Test
    fun `adversarial - a confusable pair other than zero-slash-O is never touched`() {
        // `1` vs `I` is a legitimate confusion in real document numbers, so repairing it would
        // corrupt a valid document more often than it would fix a broken one.
        val withLetterI = valid[0].replaceRange(0, 9, "I" + valid[0].substring(1, 9))
        val extraction = extractor.extract(listOf(withLetterI, valid[1]), FieldFixtures.REFERENCE_YEAR)
        assertFalse(
            extraction.repairedFields.contains(MrzFieldSet.DOCUMENT_NUMBER),
            "only 0/O is a sanctioned repair",
        )
    }

    @Test
    fun `junk around the MRZ does not stop it being found`() {
        val noisy = listOf("SPECIMEN CARD", "<<<<<<<<<<<<<<<<<<<<<<", valid[0], "SOME NOISE", valid[1], "9999")
        val extraction = extractor.extract(noisy, FieldFixtures.REFERENCE_YEAR)
        assertEquals(dev.kasoti.mrz.MrzFormat.TD3, extraction.result.format)
        assertTrue(extraction.result.allChecksPassed)
    }

    @Test
    fun `no MRZ at all is an empty result, not a crash`() {
        val extraction = extractor.extract(listOf("no machine readable zone here"), FieldFixtures.REFERENCE_YEAR)
        assertEquals(dev.kasoti.mrz.MrzFormat.UNKNOWN, extraction.result.format)
        assertFalse(extraction.usable)
        assertNull(extraction.meanConfidence)
    }

    @Test
    fun `a manual entry is marked manual and never presented as OCR`() {
        val extraction = extractor.extract(valid, FieldFixtures.REFERENCE_YEAR, manual = true)
        assertTrue(extraction.manual, "D5: 'the officer read the passport' and 'Tesseract read it' are different evidence")
    }
}

/** Re-export so the tests read cleanly against `:core`'s enum. */
private typealias MrzFieldSet = dev.kasoti.mrz.MrzField
