package dev.kasoti.android.field

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A registry whose floors are known, plus a VIZ that is blank unless a test fills it. */
internal object FieldFixtures {
    val REGISTRY: ThresholdRegistry = ThresholdRegistry.defaults("v1", "field-test")
    val TODAY = dev.kasoti.time.IsoDate(2026, 9, 29)
    val CLOCK = FixedClock("2026-09-29T11:00:00.000Z")
    val REFERENCE_YEAR = 2026
}

/** A capture that is in focus, correctly lit, and straight on. */
internal fun goodCapture(): CaptureMeasurements = CaptureMeasurements(
    blurVariance = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat() * 2f,
    glareRatio = FieldFixtures.REGISTRY[ThresholdName.Q_GLARE].toFloat() * 0.2f,
    brightness = 128f,
)

internal fun badCapture(): CaptureMeasurements = CaptureMeasurements(
    blurVariance = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat() * 0.1f,
    glareRatio = FieldFixtures.REGISTRY[ThresholdName.Q_GLARE].toFloat() * 4f,
    brightness = 250f,
)

class CaptureQualityTest {

    private val quality = CaptureQuality(FieldFixtures.REGISTRY)

    @Test
    fun `a good capture passes the gate`() {
        val report = quality.evaluateDocumentPage(goodCapture())
        assertTrue(report.passed, "a clean capture must not be rejected: ${report.causes}")
        assertTrue(report.causes.isEmpty())
    }

    @Test
    fun `a blurred capture fails with G_BLUR, not with an accusation`() {
        val report = quality.evaluateDocumentPage(
            goodCapture().copy(blurVariance = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat() * 0.1f),
        )
        assertFalse(report.passed)
        assertEquals(listOf(FindingCode.G_BLUR), report.causes)
    }

    @Test
    fun `over-bright is G_GLARE, not G_DARK`() {
        val report = quality.evaluateDocumentPage(
            goodCapture().copy(brightness = FieldFixtures.REGISTRY[ThresholdName.Q_BRIGHT_MAX].toFloat() + 10f),
        )
        assertTrue(report.causes.contains(FindingCode.G_GLARE))
        assertFalse(report.causes.contains(FindingCode.G_DARK), "too dark and too bright are different instructions")
    }

    @Test
    fun `pose is only checked when landmarks exist`() {
        // A detector that did not load must not fail every capture on the device.
        val noPose = quality.evaluateDocument(goodCapture().copy(yawDegrees = 60f), hasPose = false)
        assertTrue(noPose.passed, "an unmeasured pose is not a bad pose")

        val withPose = quality.evaluateDocument(goodCapture().copy(yawDegrees = 60f), hasPose = true)
        assertTrue(withPose.causes.contains(FindingCode.G_POSE))
    }

    @Test
    fun `macro sharpness is reported as a fraction and a distinct code`() {
        val bar = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat()
        val sharp = quality.evaluateMacroSharpness(bar * 2f)
        assertTrue(sharp.passed)
        assertEquals(1f, sharp.fraction, 0.001f, "the bar is full at the threshold, never past it")
        assertNull(sharp.code)

        val blunt = quality.evaluateMacroSharpness(bar * 0.3f)
        assertFalse(blunt.passed)
        assertEquals(FindingCode.G_FOCUS, blunt.code, "G_FOCUS, so the operator re-seats the clip rather than wiping the lens")
        assertEquals(0.3f, blunt.fraction, 0.01f)
    }

    @Test
    fun `uv distinguishes cannot from did-not-run`() {
        val noTorch = quality.uvState(torchAvailable = false, torchOn = false, uVResponseObserved = null)
        assertEquals(CaptureQuality.UvReading.UNSUPPORTED, noTorch)

        val torchOff = quality.uvState(torchAvailable = true, torchOn = false, uVResponseObserved = null)
        assertEquals(CaptureQuality.UvReading.NOT_OBSERVED, torchOff, "a device that could have done it and did not is not the same")

        val observed = quality.uvState(torchAvailable = true, torchOn = true, uVResponseObserved = false)
        assertEquals(CaptureQuality.UvReading.ABSENT, observed)
    }
}

class FrameMetricsTest {

    @Test
    fun `a flat image has no focus`() {
        val flat = dev.kasoti.factory.GrayImage(16, 16, FloatArray(256) { 0.5f })
        assertEquals(0f, FrameMetrics.blurVariance(flat), 1e-3f)
    }

    @Test
    fun `a sharp checkerboard scores far above a blurred one`() {
        val size = 64
        val sharp = dev.kasoti.factory.GrayImage(size, size, FloatArray(size * size) { i ->
            if (((i % size) / 4 + i / size / 4) % 2 == 0) 0f else 1f
        })
        val blurred = Resize.toSquare(sharp, size)
        // A real blur: average each pixel with its neighbours.
        val smoothed = dev.kasoti.factory.GrayImage(size, size, FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val sum = blurred[x, y] +
                blurred[(x + 1) % size, y] + blurred[(x + size - 1) % size, y] +
                blurred[x, (y + 1) % size] + blurred[x, (y + size - 1) % size]
            sum / 5f
        })

        assertTrue(
            FrameMetrics.blurVariance(sharp) > FrameMetrics.blurVariance(smoothed) * 4f,
            "sharp=${FrameMetrics.blurVariance(sharp)} smoothed=${FrameMetrics.blurVariance(smoothed)}",
        )
    }

    /**
     * The `SCALE` constant is what puts [FrameMetrics.blurVariance] in the range
     * `ThresholdRegistry.Q_BLUR` is expressed in. If the two ever disagree, the sharpness gate
     * silently stops working — every patch reads as perfectly focused, which is the most
     * dangerous possible failure for a gate whose whole job is to reject a blurred macro shot.
     */
    @Test
    fun `the variance scale lands a synthetic edge inside the Q_BLUR operating band`() {
        val size = 64
        val step = dev.kasoti.factory.GrayImage(size, size, FloatArray(size * size) { i ->
            if ((i % size) < size / 2) 0f else 1f
        })
        val score = FrameMetrics.blurVariance(step)
        val bar = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat()
        val ceiling = FieldFixtures.REGISTRY[ThresholdName.Q_BLUR].toFloat() * 40f
        assertTrue(score in bar..ceiling, "a hard vertical edge scored $score, outside the plausible band around $bar")
    }

    @Test
    fun `glare ratio counts only near-white pixels`() {
        val dark = dev.kasoti.factory.GrayImage(10, 10, FloatArray(100) { 0.2f })
        assertEquals(0f, FrameMetrics.glareRatio(dark), 1e-6f)

        val half = dev.kasoti.factory.GrayImage(10, 10, FloatArray(100) { if (it < 50) 0.2f else 1f })
        assertEquals(0.5f, FrameMetrics.glareRatio(half), 0.01f)
    }

    @Test
    fun `brightness is reported on the 0-255 scale the registry uses`() {
        val image = dev.kasoti.factory.GrayImage(8, 8, FloatArray(64) { 0.5f })
        assertEquals(127.5f, FrameMetrics.brightness(image), 0.01f)
    }
}

class CapturePlannerTest {

    @Test
    fun `a passport plans all four steps and no more`() {
        val plan = CapturePlanner.plan(Track.PASSPORT)
        assertEquals(
            listOf(CaptureStep.DOCUMENT, CaptureStep.MRZ, CaptureStep.MACRO, CaptureStep.FACE),
            plan,
        )
        assertEquals(CapturePlanner.MAX_STEPS, plan.size)
    }

    @Test
    fun `an aadhaar drops the MRZ step because its math layer is Verhoeff, not an MRZ`() {
        val plan = CapturePlanner.plan(Track.AADHAAR)
        assertFalse(plan.contains(CaptureStep.MRZ), "demanding an MRZ from an Aadhaar would GREY every genuine card")
        assertTrue(plan.contains(CaptureStep.MACRO), "MACRO is load-bearing for Aadhaar (PVC honesty rule)")
    }

    @Test
    fun `a paper id drops the macro obligation to something the operator can skip`() {
        val plan = CapturePlanner.plan(Track.PAPER_ID)
        assertFalse(plan.contains(CaptureStep.MRZ))
    }

    @Test
    fun `an unknown track still gets a document and a face before it is escalated`() {
        val plan = CapturePlanner.plan(Track.UNKNOWN)
        assertTrue(plan.contains(CaptureStep.DOCUMENT))
        assertTrue(plan.contains(CaptureStep.FACE))
        assertTrue(plan.size <= CapturePlanner.MAX_STEPS)
    }

    @Test
    fun `requirements name the layer and its code`() {
        val reqs = CapturePlanner.requirements(Track.PASSPORT)
        assertEquals(4, reqs.size)
        assertTrue(reqs.all { it.required })
        assertTrue(reqs.all { it.code == FindingCode.A_MISSING_LAYER })
    }

    @Test
    fun `adversarial - every track in the enum plans within the cap`() {
        for (track in Track.entries) {
            val plan = CapturePlanner.plan(track)
            assertTrue(plan.size <= CapturePlanner.MAX_STEPS, "track $track planned ${plan.size} steps")
            assertEquals(CaptureStep.DOCUMENT, plan.first(), "the page capture is never optional")
            assertNotNull(plan.distinct().singleOrNull { it == CaptureStep.DOCUMENT })
        }
    }
}
