package dev.kasoti.android.field

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.ui.QualityMeter
import dev.kasoti.ui.QualityMeterFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `:core` ⇄ `:ui` quality boundary (FR-C1, SPEC principle 4).
 *
 * The property worth testing is that the round trip is *lossless in both directions for the
 * decision* and *lossy in exactly one place* — the numeric measurements a `QualityMeter` does
 * not carry. A lossy conversion that silently changed `passed` would produce a screen saying
 * "hold still" over a case `:core` has already cleared, which is the failure this whole
 * layering exists to prevent.
 */
class QualityBridgeTest {

    @Test
    fun `a report becomes a meter and back with the same verdict and causes`() {
        val report = QualityReport(
            passed = false,
            causes = listOf(FindingCode.G_BLUR, FindingCode.G_GLARE),
            blurScore = 12f,
            glareRatio = 0.4f,
            brightness = 210f,
        )

        val meter = QualityMeterFactory.toQualityMeter(report, live = true)
        val back = QualityMeterFactory.toQualityReport(meter)

        assertEquals(report.passed, back.passed)
        assertEquals(report.causes, back.causes)
        // Glare at 0.40 against a 0.12 ceiling is disqualifying on its own, and the meter reports
        // the *worst* offender rather than an average — a well-lit but heavily glared frame is
        // not "mostly fine" (this is the same rule `QualityMeter.qualityScore` documents).
        assertEquals(0f, meter.score, 0.01f, "the worst offender dominates, not the mean")
        assertTrue(meter.blocked)
    }

    @Test
    fun `the meter direction drops the numbers rather than inventing them`() {
        val meter = QualityMeter(passed = false, score = 0.1f, causes = listOf(FindingCode.G_DARK), live = false)
        val report = QualityMeterFactory.toQualityReport(meter)

        assertEquals(0f, report.blurScore, 0.0f, "a display value must not become a measurement in an audit record")
        assertEquals(0f, report.glareRatio, 0.0f)
        assertEquals(0f, report.brightness, 0.0f)
        assertFalse(report.occluded)
    }

    @Test
    fun `a clean report is a passing meter with no instructions`() {
        val meter = QualityMeterFactory.toQualityMeter(QualityReport.CLEAN, live = false)
        assertTrue(meter.passed)
        assertEquals(1f, meter.score, 1e-6f)
        assertTrue(meter.instructions(dev.kasoti.i18n.Language.ENGLISH).isEmpty())
    }

    @Test
    fun `measurements survive the report round trip losslessly`() {
        val measurements = CaptureMeasurements(
            blurVariance = 312f,
            glareRatio = 0.07f,
            brightness = 121f,
            yawDegrees = 11f,
            pitchDegrees = 6f,
            occluded = true,
        )
        val report = QualityReportFactory.fromMeasurements(measurements, passed = false, causes = listOf(FindingCode.G_POSE))

        assertEquals(measurements, QualityReportFactory.toMeasurements(report))
    }

    @Test
    fun `a causes-only report carries no measurements at all`() {
        val report = QualityReportFactory.fromCausesOnly(passed = false, causes = listOf(FindingCode.G_FOCUS))
        assertEquals(listOf(FindingCode.G_FOCUS), report.causes)
        assertEquals(0f, report.blurScore, 0.0f, "a documented failure with no frame behind it must not look measured")
        assertEquals(CaptureMeasurements(0f, 0f, 0f), QualityReportFactory.toMeasurements(report).let {
            CaptureMeasurements(it.blurVariance, it.glareRatio, it.brightness)
        })
    }

    @Test
    fun `the two directions agree on passed, which is the only field fusion reads`() {
        for (report in listOf(
            QualityReport.CLEAN,
            QualityReport(passed = false, causes = listOf(FindingCode.G_BLUR)),
            QualityReport(passed = false, causes = listOf(FindingCode.G_NOCLIP, FindingCode.G_OCRLOW)),
        )) {
            val meter = QualityMeterFactory.toQualityMeter(report, live = false)
            assertEquals(report.passed, QualityMeterFactory.toQualityReport(meter).passed, "verdict drift for $report")
        }
    }
}
