package dev.kasoti.ui

import dev.kasoti.fusion.QualityReport

/**
 * The `:core` ⇄ `:ui` conversion for capture quality (FR-C1, SPEC principle 4).
 *
 * `:ui` and `:app-android` do not depend on each other — `:ui` is framework-free and
 * `dev.kasoti.ui` must stay testable without a device — so something has to convert a
 * `QualityReport` (what `:core` reads) into a `QualityMeter` (what the screen shows) and back.
 * This is that something, in `:ui`, with no Android types and therefore unit-testable.
 *
 * It is in this module rather than in the app so that the conversion is covered by a test that
 * runs on every CI machine, not only on a workstation that happens to have the SDK. Two
 * hand-written conversions would eventually disagree about what "passed" means, and the
 * disagreement would only ever show up as a screen saying "hold still" over a case `:core` has
 * already cleared.
 */
object QualityMeterFactory {

    /**
     * `:core` report → the screen's meter.
     *
     * @param live `true` for a preview-frame reading, `false` for a still. It changes nothing
     *   about the verdict — it is there so the UI can show "live" versus "captured" and so a
     *   log can say which one produced a finding.
     */
    fun toQualityMeter(report: QualityReport, live: Boolean): QualityMeter = QualityMeter(
        passed = report.passed,
        score = report.qualityScore(),
        causes = report.causes,
        live = live,
    )

    /**
     * The screen's meter → a `:core` report, for handing a decision back.
     *
     * The numeric measurements are all zero because a `QualityMeter` does not carry them: it is a
     * display value, and inventing numbers for it would put a plausible measurement into an
     * audit record where none was taken. `:core` reads only [QualityReport.passed] and
     * [QualityReport.causes] for the retake decision, so nothing is lost — and a report whose
     * codes carry the whole meaning is the honest one.
     */
    fun toQualityReport(meter: QualityMeter): QualityReport = QualityReport(
        passed = meter.passed,
        causes = meter.causes,
    )
}
