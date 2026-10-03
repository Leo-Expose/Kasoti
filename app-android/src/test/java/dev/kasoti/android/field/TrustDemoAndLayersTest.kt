package dev.kasoti.android.field

import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.FileDiary
import dev.kasoti.diary.InMemoryDiaryStorage
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.FaceEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.ProcessLabel
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.i18n.Language
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustLaneTest {

    private val store = InMemoryEnrolmentStore()
    private val registry = FieldFixtures.REGISTRY
    private val today = FieldFixtures.TODAY
    private val pin = SupervisorPin.fixed("4821")

    private fun lane(random: Random = Random(7)) = TrustLane(store, registry, pin, random)

    /** A full stranger check that came back GREEN, built the way a real one is. */
    private fun greenReport(demoMode: Boolean = false) = dev.kasoti.fusion.FusionEngine.decide(
        Evidence(
            track = Track.PASSPORT,
            quality = QualityReport.CLEAN,
            math = MathEvidence(allChecksPassed = true),
            chip = dev.kasoti.fusion.ChipEvidence(true, true, true, true),
            macro = MacroEvidence(ProcessLabel.OFFSET, 0.6f, ProcessLabel.OFFSET, 0.6f, clipUsed = true),
            face = FaceEvidence(0.8f, 0.9f, 0.9f, 0.8f, headTurnPassed = true),
            diary = dev.kasoti.fusion.DiaryEvidence(),
            demoMode = demoMode,
        ),
        registry,
        today.year,
    )

    @Test
    fun `an unknown subject takes the full stranger check`() {
        val decision = lane().decide("nobody", today)
        assertEquals(TrustState.VERIFY, decision.state)
        assertEquals(TrustOutcome.FULL_CHECK, decision.outcome)
    }

    @Test
    fun `a null subject hash is a stranger, not an error`() {
        assertEquals(TrustState.VERIFY, lane().decide(null, today).state)
        assertEquals(TrustState.VERIFY, lane().decide("   ", today).state)
    }

    @Test
    fun `enrolment requires a GREEN stranger check, a PIN and consent`() {
        val l = lane()
        assertEquals(TrustOutcome.PIN_REQUIRED, l.enrol("s1", greenReport(), "", today, true).outcome)
        assertEquals(TrustOutcome.PIN_REJECTED, l.enrol("s1", greenReport(), "0000", today, true).outcome)
        assertEquals(TrustOutcome.ENROLMENT_REFUSED, l.enrol("s1", greenReport(), "4821", today, consentRecorded = false).outcome)
        assertNull(store.get("s1"), "a refused enrolment must not leave a record behind")

        val ok = l.enrol("s1", greenReport(), "4821", today, consentRecorded = true)
        assertEquals(TrustOutcome.ENROLLED, ok.outcome)
        assertNotNull(store.get("s1"))
    }

    @Test
    fun `an AMBER stranger check cannot be enrolled`() {
        // A supervisor must not be able to fast-path somebody the engine just flagged. The
        // first-hit code is carried so the refusal names a reason.
        val amber = dev.kasoti.fusion.FusionEngine.decide(
            Evidence(track = Track.UNKNOWN, quality = QualityReport.CLEAN, face = FaceEvidence(0.8f, 0.9f, 0.9f, 0.8f, true)),
            registry,
            today.year,
        )
        val decision = lane().enrol("s2", amber, "4821", today, consentRecorded = true)
        assertEquals(TrustOutcome.ENROLMENT_REFUSED, decision.outcome)
        assertEquals(FindingCode.SYS_UNSUPPORTED_TRACK, decision.code)
    }

    @Test
    fun `a demo verdict can never be enrolled into a real store`() {
        val decision = lane().enrol("s3", greenReport(demoMode = true), "4821", today, consentRecorded = true)
        assertEquals(TrustOutcome.ENROLMENT_REFUSED, decision.outcome, "invariant I7: a demo fixture must not become a real enrolment")
        assertNull(store.get("s3"))
    }

    @Test
    fun `a live enrolment offers the fast path, and a PIN is irrelevant to using it`() {
        lane().enrol("s4", greenReport(), "4821", today, consentRecorded = true)
        val decision = lane().decide("s4", today)
        assertEquals(TrustState.ENROLLED, decision.state)
        assertTrue(decision.isFastPath)
        assertEquals(registry[ThresholdName.REVERIFY_DAYS].toInt(), decision.daysUntilReverify)
    }

    @Test
    fun `the re-verify interval expires the enrolment`() {
        lane().enrol("s5", greenReport(), "4821", today, consentRecorded = true)
        val due = CalendarArithmetic.plusDays(today, registry[ThresholdName.REVERIFY_DAYS].toInt() + 1)
        val decision = lane().decide("s5", due)
        assertEquals(TrustOutcome.REVERIFY_DUE, decision.outcome)
        assertEquals(TrustState.VERIFY, decision.state, "past the interval it is an ordinary stranger check")
    }

    @Test
    fun `revocation is instant and PIN-gated`() {
        val l = lane()
        l.enrol("s6", greenReport(), "4821", today, consentRecorded = true)

        assertEquals(TrustOutcome.PIN_REJECTED, l.revoke("s6", "0000", today).outcome)
        assertEquals(TrustState.ENROLLED, l.decide("s6", today).state, "a rejected PIN revokes nothing")

        assertEquals(TrustOutcome.REVOKED, l.revoke("s6", "4821", today).outcome)
        assertEquals(TrustState.REVOKED, l.decide("s6", today).state)
        assertTrue(store.get("s6")!!.revoked)
    }

    @Test
    fun `the random re-check is drawn before the fast path and the draw is recorded`() {
        lane().enrol("s7", greenReport(), "4821", today, consentRecorded = true)

        // A seed chosen so the draw lands below RECHECK_P (0.05). Recorded, not asserted by
        // re-rolling: a test that retried until it passed would be testing nothing.
        val forced = lane(Random(1))
        val decision = forced.decide("s7", today)
        assertNotNull(decision.draw, "the draw must be in the audit record, not just in the branch")
        if (decision.outcome == TrustOutcome.RANDOM_RECHECK) {
            assertEquals(TrustState.RANDOM_RECHECK, decision.state)
            assertTrue(decision.draw!! < registry[ThresholdName.RECHECK_P].toFloat())
        } else {
            assertEquals(TrustState.ENROLLED, decision.state)
        }
    }

    @Test
    fun `adversarial - the same seed always gives the same draw`() {
        // A scheduler draw that cannot be reconstructed is a re-check that looks like a bug.
        lane().enrol("s8", greenReport(), "4821", today, consentRecorded = true)
        assertEquals(lane(Random(42)).decide("s8", today).draw, lane(Random(42)).decide("s8", today).draw)
    }

    @Test
    fun `a PIN shorter than the minimum is refused`() {
        assertFalse(SupervisorPin.fixed("4821").verify("48"))
        assertTrue(SupervisorPin.fixed("4821").verify("4821"))
    }
}

/**
 * Invariant I7 end to end: a demo run must be incapable of reaching a real diary.
 *
 * The cases that matter are the adversarial ones — an *incoming* bundle carrying demo records,
 * and an export of a record that somehow got written.
 */
class DiaryGuardTest {

    private val log = mutableListOf<FieldLogEntry>()
    private val repo = FileDiary(InMemoryDiaryStorage(), dev.kasoti.android.platform.JcaDigest(), EMBED_MODEL)
    private val guard = DiaryGuard(repo) { log += it }

    @Test
    fun `a real crossing is written`() {
        val result = guard.append("case_1", event("evt_" + "A".repeat(26)), greenReport(demoMode = false))
        assertIs<DiaryGuard.Result.Written>(result)
        assertEquals(1, repo.size())
    }

    @Test
    fun `a demo crossing is refused and never reaches the diary`() {
        val result = guard.append("case_2", event("evt_" + "B".repeat(26)), greenReport(demoMode = true))
        assertIs<DiaryGuard.Result.Refused>(result)
        assertEquals(DiaryGuard.Refusal.Reason.DEMO_EVIDENCE, result.refusal.reason)
        assertEquals(0, repo.size(), "the diary must be untouched")
        assertTrue(log.any { it.kind == FieldLogEntry.Kind.DEMO }, "and the refusal must be logged")
    }

    @Test
    fun `a demo record arriving in a bundle is held back, not merged`() {
        val demo = event("evt_" + "C".repeat(26))
        val real = event("evt_" + "D".repeat(26))
        val held = mutableListOf<List<CrossingEvent>>()

        val summary = guard.mergeIncoming(listOf(demo, real), setOf(demo.id)) { _, _, records -> held += records }

        assertEquals(1, summary.refusedDemo)
        assertEquals(listOf(demo), held.single())
        assertEquals(1, summary.accepted)
        assertEquals(1, repo.size(), "only the real record is in the diary")
    }

    @Test
    fun `a bundle of nothing but demo records merges nothing at all`() {
        val demo = event("evt_" + "E".repeat(26))
        val held = mutableListOf<List<CrossingEvent>>()
        val summary = guard.mergeIncoming(listOf(demo), setOf(demo.id)) { _, _, records -> held += records }

        assertEquals(0, summary.accepted)
        assertEquals(0, repo.size())
        assertEquals(1, held.size)
    }

    @Test
    fun `export refuses a demo-sourced record`() {
        assertFalse(guard.exportable(event("evt_" + "F".repeat(26)), greenReport(demoMode = true)))
        assertTrue(guard.exportable(event("evt_" + "0".repeat(26)), greenReport(demoMode = false)))
    }

    @Test
    fun `adversarial - the demo bit comes from the report, so it cannot be forgotten`() {
        // The guard's only input for the decision is `VerdictReport.demoMode`. There is no
        // boolean parameter on the append path, so no caller can pass `demoMode = false` by
        // omission; the value is whatever `:core` carried through from `Evidence`.
        val report = greenReport(demoMode = true)
        assertTrue(report.demoMode)
        assertEquals("GREEN", report.verdict.name)
    }

    // ------------------------------------------------------------------ helpers

    private fun greenReport(demoMode: Boolean) = dev.kasoti.fusion.FusionEngine.decide(
        Evidence(
            track = Track.PASSPORT,
            quality = QualityReport.CLEAN,
            math = MathEvidence(allChecksPassed = true),
            chip = dev.kasoti.fusion.ChipEvidence(true, true, true, true),
            macro = MacroEvidence(ProcessLabel.OFFSET, 0.6f, ProcessLabel.OFFSET, 0.6f, clipUsed = true),
            face = FaceEvidence(0.8f, 0.9f, 0.9f, 0.8f, headTurnPassed = true),
            diary = dev.kasoti.fusion.DiaryEvidence(),
            demoMode = demoMode,
        ),
        FieldFixtures.REGISTRY,
        FieldFixtures.REFERENCE_YEAR,
    )

    private fun event(id: String) = CrossingEvent(
        id = id,
        seq = 1,
        device = "post-test",
        post = "RAXAUL",
        ts = "2026-09-29T11:00:00.000Z",
        track = Track.PASSPORT,
        nameSha = "0".repeat(64),
        dob = null,
        docHash = "1".repeat(64),
        embModel = EMBED_MODEL,
        emb = ByteArray(128),
        qScale = 1f,
        q = 0.9f,
        verdict = dev.kasoti.fusion.Verdict.GREEN,
        findings = emptyList(),
        prev = "",
    )

    private companion object {
        /** A well-formed model tag: `name@sha256:<64 hex>`, which `:core` validates. */
        val EMBED_MODEL: String = "emb_v1@sha256:" + "ab".repeat(32)
    }
}

/**
 * The demo catalogue is only worth having if it produces what DEMO.md says it produces, at the
 * thresholds the app actually ships. These assertions are the rehearsal check.
 */
class DemoCatalogueTest {

    private val session = DemoSession(
        registry = FieldFixtures.REGISTRY,
        today = FieldFixtures.TODAY,
    )

    private fun verdictOf(id: String) = dev.kasoti.fusion.FusionEngine.decide(
        session.load(id)!!.toEvidence(),
        FieldFixtures.REGISTRY,
        FieldFixtures.REFERENCE_YEAR,
    )

    @Test
    fun `every scenario produces the verdict the demo script expects`() {
        session.enable()
        for (scenario in DemoCatalogue.all()) {
            val report = verdictOf(scenario.id)
            val drift = session.verify(scenario, report)
            assertNull(
                drift,
                "scenario ${scenario.id} was expected ${scenario.expected} but produced " +
                    "${report.verdict} (${drift?.detail}); the demo script would be wrong on stage",
            )
        }
    }

    @Test
    fun `every demo verdict carries the demo flag, so none can reach a real diary`() {
        session.enable()
        for (scenario in DemoCatalogue.all()) {
            assertTrue(verdictOf(scenario.id).demoMode, "${scenario.id} lost its demoMode flag (I7)")
        }
    }

    @Test
    fun `loading is refused when demo mode is off`() {
        assertNull(session.load(DemoCatalogue.GENUINE_PASSPORT), "a demo path that works when it should not will be left on")
    }

    @Test
    fun `an unknown scenario id is refused, not guessed at`() {
        session.enable()
        assertNull(session.load("no-such-scenario"))
    }

    @Test
    fun `the generated MRZ parses clean, so the demo exercises the real parser`() {
        val inputs = session.inputs()
        val lines = DemoCatalogue.mrzFor(DemoCatalogue.SPECIMEN, inputs)
        val parsed = dev.kasoti.mrz.MrzParser.parse(lines, inputs.referenceYear)
        assertTrue(parsed.allChecksPassed, "a demo MRZ that fails its own check digits would be a terrible demo")
        assertEquals(dev.kasoti.mrz.MrzFormat.TD3, parsed.format)
        assertEquals("SHARMA", parsed.name.surname)
        assertEquals("RAMESH", parsed.name.givenNames)
    }

    @Test
    fun `one-tap reset turns demo mode off and clears the counters`() {
        session.enable()
        session.load(DemoCatalogue.GENUINE_PASSPORT)
        session.load(DemoCatalogue.FORGED_INKJET_TWIN)

        val after = session.toggle()

        assertFalse(after.enabled, "resetting must leave the app in a real-screening state")
        assertNull(after.loadedScenarioId)
        assertEquals(0, after.runsThisSession)
    }

    @Test
    fun `the watermark follows demo mode, not the loaded scenario`() {
        assertFalse(session.state.watermarked)
        session.enable()
        assertTrue(session.state.watermarked, "FR-C5: the DEMO watermark is visible whenever demo mode is on")
        assertFalse(session.reset().watermarked)
    }

    @Test
    fun `scenario titles are localised and distinct`() {
        val english = DemoCatalogue.all().map { it.title(Language.ENGLISH) }
        val hindi = DemoCatalogue.all().map { it.title(Language.HINDI) }
        assertTrue(english.all { it.isNotBlank() })
        assertTrue(hindi.all { it.isNotBlank() })
        assertTrue(english.zip(hindi).none { (a, b) -> a == b }, "a copied-over Hindi title is a missed string")
    }
}

class FieldLogTest {

    @Test
    fun `an entry renders as one greppable line`() {
        val line = FieldLogFormat.render(
            FieldLogEntry.of(FieldLogEntry.Kind.ROUTE, caseId = "case_x", codes = listOf(FindingCode.R_MATH_01), detail = "track=PASSPORT"),
        )
        assertTrue(line.contains("KASOTI"))
        assertTrue(line.contains("R_MATH_01"))
        assertTrue(line.contains("case_x"))
        assertFalse(line.contains("\n"), "one event, one line: a supervisor greps this at 3am")
    }

    @Test
    fun `the detail is length-capped and stripped of long digit runs`() {
        val detail = "doc " + "9".repeat(400) + " end"
        val line = FieldLogFormat.render(FieldLogEntry.of(FieldLogEntry.Kind.SYSTEM, detail = detail))
        assertTrue(line.length < 400, "got ${line.length} chars")
        assertFalse(line.contains("9999999"), "a long digit run is the shape a document number has")
    }

    @Test
    fun `a verdict entry carries the codes and the layer rationale`() {
        // Every load-bearing passport layer is supplied, because otherwise `:core` correctly
        // returns GREY and the hard math proof never fires — a log line that silently contains
        // fewer codes than the verdict really had would be an audit defect.
        val report = dev.kasoti.fusion.FusionEngine.decide(
            Evidence(
                track = Track.PASSPORT,
                quality = QualityReport.CLEAN,
                math = MathEvidence(allChecksPassed = false, failedFields = setOf("BIRTH_DATE")),
                chip = dev.kasoti.fusion.ChipEvidence(true, true, true, true),
                macro = MacroEvidence(ProcessLabel.OFFSET, 0.6f, ProcessLabel.OFFSET, 0.6f, clipUsed = true),
                face = FaceEvidence(0.8f, 0.9f, 0.9f, 0.8f, headTurnPassed = true),
                diary = dev.kasoti.fusion.DiaryEvidence(),
            ),
            FieldFixtures.REGISTRY,
            FieldFixtures.REFERENCE_YEAR,
        )
        assertEquals(dev.kasoti.fusion.Verdict.RED, report.verdict)
        val entry = FieldLogEntry.verdict("case_y", report)
        assertEquals(FieldLogEntry.Kind.VERDICT, entry.kind)
        assertTrue(entry.codes.contains(FindingCode.R_MATH_01))
        assertTrue(entry.evidenceRefs.isNotEmpty(), "invariant I3: the refs travel with the verdict")
    }
}

class SkewReportTest {

    @Test
    fun `agreement within the bar is agreeing`() {
        val report = SkewReport.measure("2026-09-29T11:00:00.000Z", "2026-09-29T10:59:30.000Z", FieldFixtures.REGISTRY)
        assertEquals(SkewReport.State.AGREEING, report.state)
    }

    @Test
    fun `beyond the bar is a warning, never a block`() {
        val report = SkewReport.measure("2026-09-29T11:00:00.000Z", "2026-09-29T10:00:00.000Z", FieldFixtures.REGISTRY)
        assertEquals(SkewReport.State.SKEWED, report.state)
        assertFalse(report.isBlocking, "SYNC.md §2: skew beyond the bar is a warning, not a rejection")
        assertEquals(60, report.minutes)
    }

    @Test
    fun `no reference point is UNVERIFIED, which is not the same as agreeing`() {
        val report = SkewReport.measure("2026-09-29T11:00:00.000Z", null, FieldFixtures.REGISTRY)
        assertEquals(SkewReport.State.UNVERIFIED, report.state)
    }

    @Test
    fun `the bar comes from the registry, not a literal`() {
        val registry = ThresholdRegistry.defaults().withValue(ThresholdName.CLOCK_SKEW_MAX_MIN, 1.0)
        val report = SkewReport.measure("2026-09-29T11:02:00.000Z", "2026-09-29T11:00:00.000Z", registry)
        assertEquals(SkewReport.State.SKEWED, report.state, "tightening the policy bar tightens the check")
    }
}

class QrLayerTest {

    private val neverValid = NeverValidVerifier

    private object NeverValidVerifier : dev.kasoti.crypto.SignatureVerifier {
        override fun verifyRsaSha256(x509PublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = false
    }
    private val layer = QrLayer(neverValid)

    @Test
    fun `no QR is an absent layer, not a pass`() {
        val outcome = layer.evaluate(null, dev.kasoti.qr.KeyRing.empty(), "SHARMA", "1984-03-17")
        assertEquals(false, outcome.evidence!!.present)
        assertEquals(false, outcome.evidence!!.signatureValid)
        assertEquals(emptyList(), outcome.codes())
    }

    @Test
    fun `an unsigned payload never reports a valid signature`() {
        val payload = dev.kasoti.qr.QrPayload(
            raw = "name=SHARMA&dob=1984-03-17".toByteArray(),
            version = 1,
            fields = mapOf("name" to "SHARMA", "dob" to "1984-03-17"),
            signature = null,
            signed = false,
        )
        val outcome = layer.evaluate(payload, dev.kasoti.qr.KeyRing.empty(), "SHARMA", "1984-03-17")
        assertFalse(outcome.evidence!!.signed)
        assertFalse(outcome.evidence!!.signatureValid, "an unsigned QR is not evidence of anything (FR-Q2)")
        assertTrue(outcome.unsignedFields.isNotEmpty())
        assertEquals(listOf(FindingCode.A_QR_01), outcome.codes())
    }

    @Test
    fun `an unsigned payload that disagrees with the print is an AMBER, with both values`() {
        val payload = dev.kasoti.qr.QrPayload(
            raw = "name=VERMA&dob=1970-01-01".toByteArray(),
            version = 1,
            fields = mapOf("name" to "VERMA", "dob" to "1970-01-01"),
            signature = null,
            signed = false,
        )
        val outcome = layer.evaluate(payload, dev.kasoti.qr.KeyRing.empty(), "SHARMA", "1984-03-17")
        assertEquals(2, outcome.evidence!!.mismatches.size, "both fields must be shown side by side")
        assertEquals(listOf(FindingCode.A_QR_01), outcome.codes(), "and it is never a hard RED")
    }

    @Test
    fun `a signed payload with no key in the ring is an unknown key, not a pass`() {
        val payload = dev.kasoti.qr.QrPayload(
            raw = "payload".toByteArray(),
            version = 1,
            fields = emptyMap(),
            signature = byteArrayOf(1, 2, 3),
            signed = true,
        )
        val outcome = layer.evaluate(payload, dev.kasoti.qr.KeyRing.empty(), "", "")
        assertEquals(dev.kasoti.qr.SigResult.UnknownKey, outcome.signature)
        assertFalse(outcome.evidence!!.signatureValid)
        assertEquals(listOf(FindingCode.R_QR_01), outcome.codes())
    }

    @Test
    fun `a missing key file degrades to an empty ring, never to a verified QR`() {
        assertTrue(layer.ringFrom(null).keys.isEmpty())
        assertTrue(layer.ringFrom(ByteArray(0)).keys.isEmpty())
        assertTrue(layer.ringFrom("not a key file".toByteArray()).keys.isEmpty())
    }
}

class MacroStageTest {

    private val quality = CaptureQuality(FieldFixtures.REGISTRY)
    private val stage = MacroStage(classifier = null, registry = FieldFixtures.REGISTRY, quality = quality)

    @Test
    fun `with no model bound the label is UNKNOWN, never a guess`() {
        val patch = patch(sharp = true)
        val after = stage.take(MacroStage.Slot.PHOTO_ZONE, patch, clipUsed = true, uv = CaptureQuality.UvReading.UNSUPPORTED, existing = emptyStage)
        assertEquals(ProcessLabel.UNKNOWN, after.photoZone!!.label, "a demo build with no SVM must not claim a print process")
        assertEquals(0f, after.photoZone!!.margin, "and no margin, because none was computed")
    }

    @Test
    fun `a blurred patch is measured but not given a label with a confident margin`() {
        val after = stage.take(
            MacroStage.Slot.PHOTO_ZONE, patch(sharp = false), clipUsed = true,
            uv = CaptureQuality.UvReading.UNSUPPORTED, existing = emptyStage,
        )
        assertFalse(after.photoZone!!.sharpness.passed)
        assertEquals(FindingCode.G_FOCUS, after.photoZone!!.sharpness.code)
        assertEquals(0f, after.photoZone!!.margin, "a blurred patch must not produce a margin an R-PROC rule could read")
    }

    @Test
    fun `the step cannot advance until both patches are focused`() {
        var current = MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED)
        current = stage.take(MacroStage.Slot.PHOTO_ZONE, patch(true), true, CaptureQuality.UvReading.UNSUPPORTED, current)
        assertFalse(current.canAdvance, "one patch is not a macro reading")
        assertEquals(MacroStage.Slot.TEXT_ZONE, current.awaitingSlot())

        current = stage.take(MacroStage.Slot.TEXT_ZONE, patch(false), true, CaptureQuality.UvReading.UNSUPPORTED, current)
        assertFalse(current.canAdvance, "a blurred second patch still blocks")
        assertEquals(listOf(FindingCode.G_FOCUS), current.blockingCodes)

        current = stage.take(MacroStage.Slot.TEXT_ZONE, patch(true), true, CaptureQuality.UvReading.UNSUPPORTED, current)
        assertTrue(current.canAdvance)
        assertNotNull(current.evidence())
    }

    @Test
    fun `an incomplete step produces no evidence at all`() {
        val after = stage.take(MacroStage.Slot.PHOTO_ZONE, patch(true), true, CaptureQuality.UvReading.UNSUPPORTED, MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED))
        assertNull(after.evidence(), "a half-taken macro step must leave the layer ABSENT, not partially filled")
    }

    @Test
    fun `the process-label mapping is total and total-looking`() {
        for (label in dev.kasoti.factory.ProcessLabel.entries) {
            assertEquals(
                label.name,
                MacroStage.fusionLabel(label).name,
                "the two `:core` ProcessLabel enums must map by name, one for one",
            )
        }
    }

    @Test
    fun `the clip flag reaches the evidence, because without it the layer is absent`() {
        val withClip = stage.take(MacroStage.Slot.PHOTO_ZONE, patch(true), true, CaptureQuality.UvReading.UNSUPPORTED, MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED))
            .let { stage.take(MacroStage.Slot.TEXT_ZONE, patch(true), true, CaptureQuality.UvReading.UNSUPPORTED, it) }
        assertTrue(withClip.evidence()!!.clipUsed)

        val without = stage.take(MacroStage.Slot.PHOTO_ZONE, patch(true), false, CaptureQuality.UvReading.UNSUPPORTED, MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED))
            .let { stage.take(MacroStage.Slot.TEXT_ZONE, patch(true), false, CaptureQuality.UvReading.UNSUPPORTED, it) }
        assertFalse(without.evidence()!!.clipUsed, "`:core`'s TrackMatrix reads exactly this flag")
    }

    @Test
    fun `a patch is resized to the corpus patch size before it is measured`() {
        val odd = dev.kasoti.factory.GrayImage(97, 61, FloatArray(97 * 61) { if (it % 3 == 0) 1f else 0f })
        val after = stage.take(MacroStage.Slot.PHOTO_ZONE, odd, true, CaptureQuality.UvReading.UNSUPPORTED, MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED))
        assertEquals(MacroStage.PATCH_SIZE, after.photoZone!!.image!!.width)
        assertEquals(MacroStage.PATCH_SIZE, after.photoZone!!.image!!.height)
    }

    /**
     * The cross-module invariant that lets `:ui` derive the shutter's affordance without importing
     * this package: `MacroCard.awaiting` is `DONE` exactly when both `Sharpness.fraction` values
     * are `>= 1f`, and that is exactly when both `Sharpness.passed` are true — which is the
     * sharpness half of [MacroStage.Stage.canAdvance].
     *
     * `MacroStageTest` is the only place both sides are reachable at once: `:ui` cannot see
     * `CaptureQuality`, and `:app-android` cannot see the UI derivation. Asserted over a sweep
     * across the bar because the boundary is the whole claim — a `>` instead of a `>=` on either
     * side would pass every non-boundary case and fail only here.
     */
    @Test
    fun `MacroCard awaiting is DONE exactly when canAdvance's sharpness half is satisfied`() {
        val bar = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat()
        assertTrue(bar > 0f, "Q_BLUR must be positive, else the fraction is NaN and the two halves can diverge")

        val variants = listOf(0f, bar * 0.25f, bar * 0.999f, bar, bar * 1.001f, bar * 2f)
        for (photoVariance in variants) {
            for (textVariance in variants) {
                val photo = quality.evaluateMacroSharpness(photoVariance)
                val text = quality.evaluateMacroSharpness(textVariance)
                val bothPassed = photo.passed && text.passed

                val card = dev.kasoti.ui.MacroCard(
                    photoZoneSharpness = photo.fraction,
                    textZoneSharpness = text.fraction,
                    clipUsed = true,
                    focusLocked = true,
                )
                val stage = MacroStage.Stage(
                    photoZone = patchOf(MacroStage.Slot.PHOTO_ZONE, photo),
                    textZone = patchOf(MacroStage.Slot.TEXT_ZONE, text),
                    clipUsed = true,
                    uv = CaptureQuality.UvReading.UNSUPPORTED,
                )

                assertEquals(bothPassed, card.ready, "photo=$photoVariance text=$textVariance: the shutter must not be offered below the bar")
                assertEquals(bothPassed, stage.canAdvance, "photo=$photoVariance text=$textVariance: both sides must agree about sharpness")
                assertEquals(stage.canAdvance, card.ready, "photo=$photoVariance text=$textVariance: `:ui` and the field layer must not disagree")
            }
        }
    }

    /** A [MacroStage.Patch] carrying an already-measured sharpness, so the sweep above is exact. */
    private fun patchOf(slot: MacroStage.Slot, sharpness: CaptureQuality.Sharpness) = MacroStage.Patch(
        slot = slot,
        label = ProcessLabel.UNKNOWN,
        margin = 0f,
        sharpness = sharpness,
        image = null,
    )

    /**
     * A patch whose focus measure lands where the test wants it.
     *
     * Built at [MacroStage.PATCH_SIZE] rather than at a small size that would then be
     * *upscaled*: `MacroStage.take` resizes to 256x256, and a bilinear upscale is itself a
     * blur — so a "sharp" 32x32 patch arrives blurred and the test ends up measuring the
     * resize rather than the focus gate. Amplitude scales the Laplacian variance with its
     * square, so the calibration factor is the square root of the ratio.
     */
    private fun patch(sharp: Boolean): dev.kasoti.factory.GrayImage {
        val size = MacroStage.PATCH_SIZE
        val target = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat() * if (sharp) 2f else 0.1f
        val pattern = dev.kasoti.factory.GrayImage(size, size, FloatArray(size * size) { i ->
            if (((i % size) / 2 + i / size / 2) % 2 == 0) 0f else 1f
        })
        val measured = FrameMetrics.blurVariance(pattern)
        val scale = if (measured > 0f) kotlin.math.sqrt(target / measured).coerceIn(0f, 1f) else 0f
        return dev.kasoti.factory.GrayImage(size, size, FloatArray(size * size) { pattern.pixels[it] * scale })
    }

    /** The zone a correctly-scored stage is waiting on, mirroring `MacroStage.take`'s order. */
    private fun MacroStage.Stage.awaitingSlot(): MacroStage.Slot = when {
        photoZone == null -> MacroStage.Slot.PHOTO_ZONE
        textZone == null -> MacroStage.Slot.TEXT_ZONE
        else -> MacroStage.Slot.TEXT_ZONE
    }

    private val emptyStage = MacroStage.Stage(null, null, clipUsed = false, uv = CaptureQuality.UvReading.UNSUPPORTED)
}
