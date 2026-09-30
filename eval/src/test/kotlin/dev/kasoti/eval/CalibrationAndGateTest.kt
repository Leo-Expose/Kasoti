package dev.kasoti.eval

import dev.kasoti.eval.calibration.CalibrationGate
import dev.kasoti.eval.calibration.DeviceCalibration
import dev.kasoti.eval.suites.FaceSuite
import dev.kasoti.eval.suites.MacroSuite
import dev.kasoti.eval.suites.MrzSuite
import dev.kasoti.eval.suites.QrSuite
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calibration gate (EVAL.md §7) and the gates' own behaviour.
 *
 * The calibration cases follow the same shape as the split-discipline ones: the interesting
 * tests are the ones where the gate must say NO. A freshness check that has only ever been
 * seen to pass has not been tested.
 */
class CalibrationGateTest {

    /** 2026-09-01T00:00:00Z. Fixed, so the test never reads a clock (AGENTS.md §5). */
    private val now = 1_788_220_800L
    private val path = "/tmp/device_calib.json"

    private fun calibration(
        deviceId: String = "TEST-HARNESS-JVM",
        capturedAtUtc: String = "2026-08-30T00:00:00Z",
        focusOk: Boolean = true,
        gains: List<Float> = listOf(1.02f, 1.0f, 0.98f),
    ) = DeviceCalibration(
        deviceId = deviceId,
        capturedAtUtc = capturedAtUtc,
        whiteBalanceGains = gains,
        pxPerMm = 42.5f,
        focusOk = focusOk,
    )

    @Test
    fun `a fresh calibration is accepted`() {
        val verdict = CalibrationGate.evaluate("TEST-HARNESS-JVM", calibration(), now, path)
        assertTrue(verdict.accepted, verdict.reason)
    }

    @Test
    fun `an absent calibration is refused with a message that says why`() {
        val verdict = CalibrationGate.evaluate("TEST-HARNESS-JVM", null, now, path)
        assertFalse(verdict.accepted)
        // The message has to explain the consequence, not just report the absence. A reader
        // who does not know why the suite refused has no way to judge whether it matters.
        assertContains(verdict.reason, "colour pipeline")
        assertContains(verdict.reason, "7 days")
    }

    @Test
    fun `a calibration older than seven days is refused`() {
        val verdict = CalibrationGate.evaluate(
            "TEST-HARNESS-JVM",
            calibration(capturedAtUtc = "2026-08-20T00:00:00Z"), // 12 days before `now`
            now,
            path,
        )
        assertFalse(verdict.accepted)
        assertContains(verdict.reason, "days old")
        assertContains(verdict.reason, "Lighting and focus drift")
    }

    @Test
    fun `a calibration dated in the future is refused as clock skew, not accepted as fresh`() {
        val verdict = CalibrationGate.evaluate(
            "TEST-HARNESS-JVM",
            calibration(capturedAtUtc = "2026-09-01T06:00:00Z"), // 6h ahead of `now`
            now,
            path,
        )
        assertFalse(verdict.accepted, "a future timestamp is a broken clock, not a fresh capture")
        assertContains(verdict.reason, "future")
    }

    @Test
    fun `another device's calibration is refused rather than borrowed`() {
        val verdict = CalibrationGate.evaluate("TEST-DEMO-PHONE", calibration(deviceId = "TEST-HARNESS-JVM"), now, path)
        assertFalse(verdict.accepted)
        assertContains(verdict.reason, "TEST-HARNESS-JVM")
    }

    @Test
    fun `an unparseable capture time is refused`() {
        val verdict = CalibrationGate.evaluate("TEST-HARNESS-JVM", calibration(capturedAtUtc = "yesterday"), now, path)
        assertFalse(verdict.accepted)
        assertContains(verdict.reason, "capturedAtUtc")
    }

    @Test
    fun `focusOk false is refused`() {
        val verdict = CalibrationGate.evaluate("TEST-HARNESS-JVM", calibration(focusOk = false), now, path)
        assertFalse(verdict.accepted)
        assertContains(verdict.reason, "focusOk=false")
    }
}

/**
 * The gates themselves.
 *
 * These are integration-level rather than unit tests: they run the real suites over a small
 * corpus and assert the properties the gates promise, so a refactor that quietly changes what
 * "caught" or "detected" means fails here rather than in a deck.
 */
class GateBehaviourTest {

    private val registry = ThresholdRegistry.defaults(version = "test", runId = "test")

    @Test
    fun `the MRZ headline excludes structurally blind rows and reaches 100 percent on the rest`() {
        val result = MrzSuite.run(rowCount = 2_000)
        val headline = result.metrics.single { it.name == "mrz.mutate_catch.detectable_pct" }
        val blind = result.metrics.single { it.name == "mrz.mutate_catch.structurally_blind" }

        assertEquals(1.0, headline.value, "every detectable mutant must be caught")
        assertTrue((blind.value ?: 0.0) > 0.0, "TD1 has blind rows; a run reporting none is suspicious")
        assertTrue(blind.note.contains("NOT part of the headline"), "the blind metric must say so itself")

        // The headline must not be the catch rate over *all* rows: if it were, the blind rows
        // would be dragging it below 1.0 and the gate would be measuring the corpus generator.
        val rows = result.metrics.single { it.name == "mrz.corpus.rows" }.value!!
        val detectable = result.metrics.single { it.name == "mrz.mutate_catch.detectable_total" }.value!!
        assertTrue(detectable < rows, "the corpus must contain both detectable and non-detectable rows")
    }

    @Test
    fun `the QR suite rejects every tampered fixture and accepts every genuine one`() {
        val result = QrSuite.run(size = 60)
        val reject = result.gates.single { it.id == QrSuite.GATE_REJECT }
        val accept = result.gates.single { it.id == QrSuite.GATE_ACCEPT }
        assertEquals(1.0, result.metrics.single { it.name == "qr.tamper_catch_pct" }.value)
        assertEquals(1.0, result.metrics.single { it.name == "qr.accept_pct" }.value)
        assertTrue(reject.observed.startsWith("100"), reject.observed)
        assertTrue(accept.observed.startsWith("100"), accept.observed)
    }

    @Test
    fun `the macro classifier gate is SKIPPED rather than passed when no model exists`() {
        val verdict = dev.kasoti.eval.calibration.CalibrationVerdict.missing("TEST-HARNESS-JVM", "/nowhere")
        val result = MacroSuite.run(registry, verdict, model = null)
        val calibration = result.gates.single { it.id == MacroSuite.GATE_CALIBRATION }
        val classifier = result.gates.single { it.id == MacroSuite.GATE_CLASSIFIER }

        assertEquals(dev.kasoti.eval.gate.GateStatus.SKIPPED, calibration.status)
        assertEquals(dev.kasoti.eval.gate.GateStatus.SKIPPED, classifier.status)
        assertContains(classifier.detail, "svm_print_v1.json")
        assertContains(classifier.detail, "hand-made weights would produce a number shaped like a metric")
    }

    @Test
    fun `the face suite skips its operating point rather than reporting zero`() {
        val result = FaceSuite.run(registry, trials = null, datasetNote = "no data")
        val gate = result.gates.single { it.id == FaceSuite.GATE_OPERATING_POINT }
        assertEquals(dev.kasoti.eval.gate.GateStatus.SKIPPED, gate.status)
        val metric = result.metrics.single { it.name == "face.operating_point.tfr_at_far_0.1pct" }
        assertEquals(null, metric.value, "an unmeasured metric must be null, never 0.0")
        assertContains(metric.note, "NOT MEASURED")
    }

    @Test
    fun `every threshold in the registry has a name the harness can report`() {
        // The operating points and the split ledger are keyed by name; a name that drifts
        // between the two would silently drop a threshold out of the split check.
        for (threshold in ThresholdName.entries) {
            assertTrue(threshold.name.isNotBlank())
            assertTrue(threshold.default in threshold.floor..threshold.ceiling)
        }
    }
}
