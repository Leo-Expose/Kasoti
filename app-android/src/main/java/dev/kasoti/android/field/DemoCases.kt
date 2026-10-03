package dev.kasoti.android.field

import dev.kasoti.fusion.ChipEvidence
import dev.kasoti.fusion.DiaryEvidence
import dev.kasoti.fusion.DiaryHit
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FaceEvidence
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.threshold.ThresholdName

/**
 * A demo case's evidence.
 *
 * ## `demoMode` is a constant, not a field
 *
 * It is a computed property, so it cannot be passed as `false` by a caller who forgot it, and
 * there is no constructor overload that omits it. This is the app-side half of invariant I7
 * ("demo-mode watermark + fixture-seed isolation — demo events never merge to a real diary"):
 * the flag travels into `Evidence.demoMode`, comes back out of `:core` on the
 * `VerdictReport`, reaches the sink, and is what [DiaryGuard] keys on. Every one of those
 * steps is a place a `Boolean` parameter would eventually be passed wrongly.
 */
data class DemoCase(
    val scenarioId: String,
    val track: Track,
    val pageQuality: QualityReport,
    val math: MathEvidence? = null,
    val qr: dev.kasoti.fusion.QrEvidence? = null,
    val chip: ChipEvidence? = null,
    val macro: MacroEvidence? = null,
    val face: FaceEvidence? = null,
    val diary: DiaryEvidence? = null,
    val trust: TrustState = TrustState.VERIFY,
    val consecutiveGreyCount: Int = 0,
    val presentation: Presentation = Presentation.PHYSICAL,
) {
    val demoMode: Boolean get() = true

    fun toEvidence(): Evidence = Evidence(
        track = track,
        quality = pageQuality,
        math = math,
        qr = qr,
        chip = chip,
        macro = macro,
        face = face,
        diary = diary,
        trust = trust,
        consecutiveGreyCount = consecutiveGreyCount,
        presentation = presentation,
        demoMode = true,
    )
}

/**
 * The scenario bodies.
 *
 * Every value here is either a [ThresholdRegistry] derivation or a named fixture constant with
 * a stated meaning, and the distinction is load-bearing. A demo that hard-codes `0.71` as "a
 * good similarity" silently stops demonstrating anything the day `T_FACE_GREEN` moves to
 * 0.60 — and the failure is invisible, because the demo still produces a verdict. So the
 * values that are *meant* to sit above or below a threshold are written as `floor + clearance`,
 * and only genuinely arbitrary readings (a simulated cosine for a simulated capture) are
 * literals, each one named.
 *
 * These are **simulated inputs, not measurements**. Nothing here ran a model. The watermark,
 * the constant `demoMode`, and [DiaryGuard] are what stop a simulated reading from being
 * mistaken for a real one — all three structural, none advisory.
 */
internal object DemoBodies {

    /**
     * Headroom added above a registry floor.
     *
     * Large enough that a re-tuned threshold inside its policy band does not flip a demo
     * verdict mid-rehearsal, small enough that the reading still reads as "comfortably clear"
     * rather than "implausibly perfect". Sits inside every relevant `ThresholdName`'s
     * floor..ceiling range where one applies.
     */
    private const val CLEARANCE = 0.22f

    /** A high-but-plausible capture quality for a hand-held phone in good light. */
    private const val CAPTURE_QUALITY = 0.92f

    /** Above `LIVE_PASSIVE_MIN` (0.50) with headroom, so a re-tuned floor cannot flip it. */
    private const val PASSIVE_LIVENESS = 0.78f

    /**
     * Salted-hash stand-ins. Demo fixtures only — never a name, never a real subject hash.
     *
     * Well-formed 64-hex strings because `:core`'s `CrossingEvent` rejects anything else, and
     * because a demo that could not be written to a diary could not demonstrate the isolation
     * it is meant to demonstrate. Distinct values so the two subjects are not the same person
     * to the alias rule.
     */
    val RAMESH_HASH: String = "0".repeat(64)
    val SURESH_HASH: String = "1".repeat(64)

    fun genuinePassport(inputs: DemoInputs, scenarioId: String): DemoCase = DemoCase(
        scenarioId = scenarioId,
        track = Track.PASSPORT,
        pageQuality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        chip = CLEAR_CHIP,
        macro = macro(inputs, both = dev.kasoti.fusion.ProcessLabel.OFFSET),
        face = face(inputs, simAboveGreen(inputs)),
        diary = DiaryEvidence(),
    )

    /**
     * The inkjet printout: a genuine *reprint*, both zones INKJET, both confident.
     *
     * AMBER, not RED, and that is the interesting part of the moment. A judge holding an inkjet
     * printout at a checkpoint is holding something that is not a security document, but the
     * process reading on its own is not a *forgery* — it is a copy. `:core` agrees
     * (`A_PROC_01`), which is exactly the "that's an AMBER case, console please" recovery line
     * in DEMO.md §5.
     */
    fun inkjetPrintout(inputs: DemoInputs): DemoCase = DemoCase(
        scenarioId = DemoCatalogue.INKJET_PRINTOUT,
        track = Track.PASSPORT,
        pageQuality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        chip = CLEAR_CHIP,
        macro = macro(inputs, both = dev.kasoti.fusion.ProcessLabel.INKJET),
        face = face(inputs, simAboveGreen(inputs)),
        diary = DiaryEvidence(),
    )

    /**
     * The forged twin: the specimen's data is perfect and only the *factory* is wrong.
     *
     * `R_PROC_02` needs both zones confident and disagreeing, which is why the margins are
     * pushed above `MACRO_MARGIN_RED` rather than to the AMBER floor. A pair that disagrees with
     * a low margin is not evidence of anything — FUSION.md §5's whole point, and the reason
     * `DocAggregator` distinguishes MISMATCH from UNCERTAIN.
     */
    fun forgedTwin(inputs: DemoInputs): DemoCase = DemoCase(
        scenarioId = DemoCatalogue.FORGED_INKJET_TWIN,
        track = Track.PASSPORT,
        pageQuality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        chip = CLEAR_CHIP,
        macro = MacroEvidence(
            photoZoneLabel = dev.kasoti.fusion.ProcessLabel.INKJET,
            photoZoneMargin = confident(inputs),
            textZoneLabel = dev.kasoti.fusion.ProcessLabel.OFFSET,
            textZoneMargin = confident(inputs),
            clipUsed = true,
        ),
        face = face(inputs, simAboveGreen(inputs)),
        diary = DiaryEvidence(),
    )

    /**
     * The alias: a diary hit that clears `T_ALIAS_HI`, on a *different* name, corroborated by
     * the runner-up gap `DELTA_MARGIN`.
     *
     * Both arms matter. Without the gap, `:core` requires supervisor confirmation rather than
     * acting — "needs supervisor confirm UX, not silent" (FUSION.md §3) — so a demo that
     * showed the quiet version would be demonstrating a rule the design deliberately does not
     * have, which is worse than not demonstrating it.
     */
    fun aliasCase(inputs: DemoInputs): DemoCase {
        val top = inputs.of(ThresholdName.T_ALIAS_HI) + 0.09f
        return DemoCase(
            scenarioId = DemoCatalogue.ALIAS_RAMESH_SURESH,
            track = Track.PASSPORT,
            pageQuality = QualityReport.CLEAN,
            math = MathEvidence(allChecksPassed = true),
            chip = CLEAR_CHIP,
            macro = macro(inputs, both = dev.kasoti.fusion.ProcessLabel.OFFSET),
            face = face(inputs, simAboveGreen(inputs)),
            diary = DiaryEvidence(
                aliasHits = listOf(
                    DiaryHit(
                        eventId = "evt_0" + "0".repeat(25),
                        similarity = top,
                        post = "RAXAUL-NORTH",
                        timestamp = CalendarArithmetic.plusDays(inputs.today, -9).toIsoString() + "T04:12:00.000Z",
                        nameHash = RAMESH_HASH,
                        dob = DemoCatalogue.SPECIMEN.birthDate.toIsoString(),
                    ),
                ),
                topHitSimilarity = top,
                // The gap is what makes the hit actionable on its own. It is written as
                // `DELTA_MARGIN + clearance` for exactly the same reason as the margins above.
                runnerUpSimilarity = top - (inputs.of(ThresholdName.DELTA_MARGIN) + 0.04f),
            ),
        )
    }

    fun watchlist(inputs: DemoInputs): DemoCase = DemoCase(
        scenarioId = DemoCatalogue.WATCHLIST_HIT,
        track = Track.PASSPORT,
        pageQuality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        chip = CLEAR_CHIP,
        macro = macro(inputs, both = dev.kasoti.fusion.ProcessLabel.OFFSET),
        face = face(inputs, simAboveGreen(inputs)),
        diary = DiaryEvidence(
            watchlistHits = listOf(
                DiaryHit(
                    eventId = "evt_1" + "1".repeat(25),
                    similarity = inputs.of(ThresholdName.T_WL) + 0.06f,
                    post = "WATCHLIST",
                    timestamp = CalendarArithmetic.plusDays(inputs.today, -120).toIsoString() + "T09:00:00.000Z",
                    nameHash = SURESH_HASH,
                ),
            ),
        ),
    )

    /**
     * The router's honest failure: an unrecognised family is AMBER, never GREEN (FR-M1).
     *
     * `TrackMatrix` declares nothing load-bearing for `Track.UNKNOWN`, and `FusionEngine`
     * refuses to clear it because it cannot name the checks that were skipped. A demo
     * recognises document families by accepting every kind, not by refusing them.
     */
    fun unsupportedTrack(inputs: DemoInputs): DemoCase = DemoCase(
        scenarioId = DemoCatalogue.ROUTER_UNSUPPORTED,
        track = Track.UNKNOWN,
        pageQuality = QualityReport.CLEAN,
        face = face(inputs, simAboveGreen(inputs)),
    )

    /**
     * The GREY moment: a blurred capture with an expired document underneath.
     *
     * The expiry is there on purpose. It is the FUSION.md §1 case — a hard proof that exists,
     * on a frame we could not see properly — and the screen must show the proof as *carried
     * over* under a RETAKE headline, never as a RED. This is the one scenario where getting the
     * presentation wrong would be an accusation of a member of the public, which is why it is
     * in the demo catalogue and not only in a test.
     *
     * Note the absent `face`: the gate failed, so `EvidenceAssembler.gatedMatch` would have
     * refused to build one, and a `null` face is what makes GREEN unreachable anyway.
     */
    fun blurryRetake(inputs: DemoInputs): DemoCase = DemoCase(
        scenarioId = DemoCatalogue.BLURRY_RETAKE,
        track = Track.PASSPORT,
        pageQuality = QualityReport(
            passed = false,
            causes = listOf(FindingCode.G_BLUR, FindingCode.G_GLARE),
            blurScore = inputs.of(ThresholdName.Q_BLUR) * 0.12f,
            glareRatio = inputs.of(ThresholdName.Q_GLARE) * 3.5f,
            brightness = inputs.of(ThresholdName.Q_BRIGHT_MAX) - 4f,
        ),
        math = MathEvidence(allChecksPassed = true, expired = true),
        // The presentation an attacker would use. Recorded so `R_PROC_01` can fire when the
        // macro layer does run, and honest about what the operator was actually shown.
        presentation = Presentation.SCREEN,
    )

    // ------------------------------------------------------------------ helpers

    private val CLEAR_CHIP =
        ChipEvidence(present = true, passiveAuthValid = true, dg1MatchesMrz = true, supported = true)

    private fun macro(inputs: DemoInputs, both: dev.kasoti.fusion.ProcessLabel) = MacroEvidence(
        photoZoneLabel = both,
        photoZoneMargin = confident(inputs),
        textZoneLabel = both,
        textZoneMargin = confident(inputs),
        clipUsed = true,
    )

    private fun confident(inputs: DemoInputs) = inputs.of(ThresholdName.MACRO_MARGIN_RED) + CLEARANCE

    private fun simAboveGreen(inputs: DemoInputs) = inputs.of(ThresholdName.T_FACE_GREEN) + 0.18f

    private fun face(inputs: DemoInputs, similarity: Float) = FaceEvidence(
        similarity = similarity,
        docQuality = CAPTURE_QUALITY,
        liveQuality = CAPTURE_QUALITY,
        passiveLivenessScore = PASSIVE_LIVENESS,
        headTurnPassed = true,
    )

    private fun DemoInputs.of(name: ThresholdName): Float = registry[name].toFloat()
}
