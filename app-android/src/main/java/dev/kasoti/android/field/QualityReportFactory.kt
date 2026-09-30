package dev.kasoti.android.field

import dev.kasoti.fusion.QualityReport

/**
 * Raw measurements → the `:core` report `FusionEngine` reads.
 *
 * The counterpart to `dev.kasoti.ui.QualityMeterFactory`, kept on this side because
 * [CaptureMeasurements] is a field-layer type and `:ui` must not know it. Together the two
 * objects are the whole boundary between "numbers from a frame" and "a value the screen can
 * show", and both directions are unit-testable without a device.
 *
 * Every field of [CaptureMeasurements] is copied straight through, including the ones the gate
 * did not use. That matters for the audit: a report that says `G_DARK` should also carry the
 * blur and glare figures the same frame produced, because six hours later the question is
 * usually "was it dark *and* blurred, or just one?" and a report that dropped the unused
 * numbers cannot answer it.
 */
object QualityReportFactory {

    fun fromMeasurements(
        measurements: CaptureMeasurements,
        passed: Boolean,
        causes: List<dev.kasoti.fusion.FindingCode> = emptyList(),
    ): QualityReport = QualityReport(
        passed = passed,
        causes = causes,
        blurScore = measurements.blurVariance,
        glareRatio = measurements.glareRatio,
        brightness = measurements.brightness,
        yawDegrees = measurements.yawDegrees,
        pitchDegrees = measurements.pitchDegrees,
        occluded = measurements.occluded,
    )

    /**
     * A report carrying only causes, for a documented failure with no frame behind it.
     *
     * A demo fixture, a merged verdict, a log replay. The numbers stay at their neutral default
     * rather than being invented, so `qualityScore()` cannot be read off a report where no
     * measurement was taken.
     */
    fun fromCausesOnly(passed: Boolean, causes: List<dev.kasoti.fusion.FindingCode>): QualityReport =
        QualityReport(passed = passed, causes = causes)

    /** `:core` report → the measurements it was built from. Lossless. */
    fun toMeasurements(report: QualityReport): CaptureMeasurements = CaptureMeasurements(
        blurVariance = report.blurScore,
        glareRatio = report.glareRatio,
        brightness = report.brightness,
        yawDegrees = report.yawDegrees,
        pitchDegrees = report.pitchDegrees,
        occluded = report.occluded,
    )
}
