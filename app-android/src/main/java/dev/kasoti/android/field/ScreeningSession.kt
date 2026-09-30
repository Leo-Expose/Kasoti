package dev.kasoti.android.field

import dev.kasoti.fusion.DiaryEvidence
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.fusion.VerdictReport
import dev.kasoti.mrz.MrzResult
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate

/**
 * One screening, from the first shutter to a verdict (the cascade of DESIGN.md principle 2).
 *
 * ## Why this is a class and not a function
 *
 * Because the interesting behaviour is what it *remembers*: which step we are on, the running
 * count of consecutive GREY attempts (which feeds `:core`'s `A_GREY3` escalation), and the
 * per-step quality reports the case bundle needs. A stateless `screen(evidence)` would have to
 * be handed all of that by the caller, and one caller would eventually forget the counter — at
 * which point the anti-gaming rule silently stops working, which is a *security* regression
 * that no test would catch.
 *
 * ## Ordering, which is the safety property
 *
 * `screen()` is the only entry point and it runs the layers in the order the design fixes:
 *
 *  1. quality — the gate decides whether anything else is allowed to speak;
 *  2. route — the track, from the signals step 1 produced;
 *  3. math / QR / macro / face / diary — cheapest and most decisive first;
 *  4. `FusionEngine.decide`.
 *
 * Step 2 needs the MRZ, which is captured in its own step, so [screen] takes a *complete* set of
 * step results. In the app the UI advances through the steps and calls this once, at the end.
 * Nothing here re-orders or re-derives; the whole point is that there is one order and it is
 * written down.
 */
class ScreeningSession(
    private val registry: ThresholdRegistry,
    private val clock: FieldClock,
    private val quality: CaptureQuality,
    private val assembler: EvidenceAssembler,
    private val math: MathLayer,
    private val ids: IdFactory,
    private val log: FieldLog = FieldLog.NOOP,
) {

    /** Everything the capture steps produced. */
    data class Steps(
        val plan: List<CaptureStep>,
        /** Per-step quality reports, indexed by step. The page report is the one that matters. */
        val stepQuality: Map<CaptureStep, QualityReport> = emptyMap(),
        val mrz: MrzResult? = null,
        val macro: MacroEvidence? = null,
        val qrOutcome: QrLayer.Outcome? = null,
        val chip: dev.kasoti.fusion.ChipEvidence? = null,
        val match: EvidenceAssembler.GatedMatch? = null,
        val diary: DiaryEvidence? = null,
    ) {
        fun reportFor(step: CaptureStep): QualityReport =
            stepQuality[step] ?: QualityReport.CLEAN
    }

    data class Outcome(
        val caseId: String,
        val route: Route,
        val trust: TrustState,
        val assembled: AssembledCase,
        val consecutiveGreyCount: Int,
        /** The engine's own explanation, carried verbatim rather than re-derived (AGENTS.md §8). */
        val rationale: List<String>,
    ) {
        val report: VerdictReport get() = assembled.report
        val evidence: Evidence get() = assembled.evidence
    }

    /**
     * The running count of consecutive GREY attempts.
     *
     * Persisted by the caller; held here so it cannot be reset by accident between attempts.
     * `A_GREY3` compares with `>=` in `:core`, so the escalation lands on the limit attempt
     * itself and a case that has just gone AMBER cannot be closed as "nothing to see".
     */
    var consecutiveGreyCount: Int = 0
        private set

    fun screen(
        steps: Steps,
        signals: RouteSignals,
        viz: VizFields = VizFields.EMPTY,
        trust: TrustState = TrustState.VERIFY,
        presentation: Presentation = Presentation.PHYSICAL,
        demoMode: Boolean = false,
    ): Outcome {
        val caseId = ids.caseId()
        val route = TrackRouter.route(signals)
        val pageQuality = steps.reportFor(CaptureStep.DOCUMENT)

        // The math layer knows whether this family *should* have an MRZ, from the same track
        // matrix `:core` uses, so an absent MRZ is "unchecked" rather than "fine".
        val expectMrz = dev.kasoti.fusion.TrackMatrix.bearingOf(
            route.track,
            dev.kasoti.fusion.Layer.MATH,
        ) == dev.kasoti.fusion.Bearing.LOAD_BEARING

        val mathEvidence = math.evaluate(
            mrz = steps.mrz,
            viz = viz,
            today = clock.today(),
            expectMrz = expectMrz,
        )

        val assembled = assembler.assemble(
            caseId = caseId,
            track = route.track,
            pageQuality = pageQuality,
            stepQuality = steps.stepQuality.map { (step, report) -> StepQuality(step.name, report) },
            math = mathEvidence,
            qr = steps.qrOutcome?.evidence,
            chip = steps.chip,
            macro = steps.macro,
            // The gated match, never a raw similarity. On a failed gate this is `null` and the
            // face layer simply did not run — see `EvidenceAssembler`.
            match = steps.match,
            diary = steps.diary,
            trust = trust,
            consecutiveGreyCount = consecutiveGreyCount,
            presentation = presentation,
            demoMode = demoMode,
            referenceYear = clock.today().year,
        )

        consecutiveGreyCount = if (assembled.verdict.isRetake) consecutiveGreyCount + 1 else 0
        log.write(
            FieldLogEntry.of(
                kind = FieldLogEntry.Kind.ROUTE,
                caseId = caseId,
                codes = route.codes,
                detail = "track=${route.track.name} rule=${route.rule} confident=${route.confident}",
            ),
        )

        return Outcome(
            caseId = caseId,
            route = route,
            trust = trust,
            assembled = assembled,
            consecutiveGreyCount = consecutiveGreyCount,
            rationale = assembled.report.rationale,
        )
    }

    /**
     * Run a demo scenario end to end.
     *
     * Shares [screen]'s engine call so the demo is not a separate code path with its own idea of
     * how a verdict is reached. The only differences are the `demoMode` flag and the fixture's
     * evidence — which is the point: a demo that took a different route would demonstrate
     * nothing.
     */
    fun runDemo(demoCase: DemoCase): Outcome {
        val route = Route(demoCase.track, "demo-fixture", confident = true, codes = demoCase.pageQuality.causes)
        val assembled = assembler.assemble(
            caseId = ids.caseId(),
            track = demoCase.track,
            pageQuality = demoCase.pageQuality,
            stepQuality = emptyList(),
            math = demoCase.math,
            qr = demoCase.qr,
            chip = demoCase.chip,
            macro = demoCase.macro,
            match = null,
            diary = demoCase.diary,
            trust = demoCase.trust,
            consecutiveGreyCount = demoCase.consecutiveGreyCount,
            presentation = demoCase.presentation,
            demoMode = demoCase.demoMode,
            referenceYear = clock.today().year,
        )
        return Outcome(
            caseId = assembled.caseId,
            route = route,
            trust = demoCase.trust,
            assembled = assembled,
            consecutiveGreyCount = demoCase.consecutiveGreyCount,
            rationale = assembled.report.rationale,
        )
    }

    /** Next person's counter reset, called when the operator taps through a verdict. */
    fun nextPerson() {
        consecutiveGreyCount = 0
    }

    fun registryVersion(): String = registry.version
}
