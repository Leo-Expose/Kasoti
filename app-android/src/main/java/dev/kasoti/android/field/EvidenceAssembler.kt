package dev.kasoti.android.field

import dev.kasoti.fusion.ChipEvidence
import dev.kasoti.fusion.DiaryEvidence
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FaceEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.QrEvidence
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.fusion.Verdict
import dev.kasoti.fusion.VerdictReport
import dev.kasoti.face.FaceMath
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate

/**
 * The result of assembling a case, plus the evidence that produced it.
 *
 * Kept together so a caller cannot obtain a [VerdictReport] without also being handed the
 * [Evidence] it came from — a verdict whose inputs are not available is not reproducible
 * (invariant I3), and on a field device the inputs are the part that is easy to lose.
 */
data class AssembledCase(
    val caseId: String,
    val evidence: Evidence,
    val report: VerdictReport,
    /** The per-step quality reports, for the capture log and the case bundle. */
    val stepQuality: List<StepQuality>,
) {
    val verdict: Verdict get() = report.verdict
    val requiresRetake: Boolean get() = verdict.isRetake
}

data class StepQuality(val step: String, val report: QualityReport)

/**
 * Assembles a case and calls the engine — and refuses, structurally, to let a match happen
 * before the quality gate (SPEC principle 4, FUSION.md §1, DESIGN.md §5 "Quality gates BEFORE
 * match").
 *
 * ## Why this is enforced by type and not by convention
 *
 * The bug this prevents is the single worst one in the system: a blurry capture produces a low
 * face similarity, a low face similarity is `R-FACE-01`, and a RED is shown to someone whose
 * only actual problem was that the sun was behind them. Every defence that relies on the code
 * author remembering to check the gate first is a defence that fails once, under deadline, on
 * the demo floor.
 *
 * So the match is not a `FloatArray` parameter. It is a [GatedMatch] — a value that *cannot be
 * constructed* without a passing [QualityGate.evaluatedGate]. There is no overload that takes
 * raw embeddings, and no path from a failed gate to a `FaceEvidence`, because
 * [gatedMatch] returns `null` in that case and the `face = ` argument below is then `null`, and
 * a `null` face layer makes GREEN unreachable (FUSION.md §7) rather than inventing a score.
 *
 * The three consequences, all deliberate:
 *  - a failed gate ⇒ `face = null` ⇒ `:core` returns GREY with the retake causes;
 *  - a failed gate still lets hard, capture-independent proofs through (an expired date is
 *    true regardless of focus) — `:core` attaches them as carried-over;
 *  - nothing here ever fabricates a score to fill a gap. An unbound embedding model produces
 *    `face = null` and a named `SYS_CAPTURE_FAILED` finding, not a plausible number. A system
 *    that reports a similarity it did not compute is worse than one that admits it did not look.
 */
class EvidenceAssembler(
    private val registry: ThresholdRegistry,
    private val log: FieldLog = FieldLog.NOOP,
) {

    /** A match that has cleared its gate. Unconstructible from a failed [QualityGate.evaluatedGate]. */
    class GatedMatch internal constructor(
        val docEmbedding: FloatArray,
        val liveEmbedding: FloatArray,
        val docQuality: Float,
        val liveQuality: Float,
        val passiveLiveness: Float,
        val headTurnPassed: Boolean?,
    ) {
        fun similarity(): Float = FaceMath.cosine(docEmbedding, liveEmbedding)
    }

    /** The receipt for a gate that ran, whatever its verdict. */
    data class evaluatedGate(val report: QualityReport)

    fun gate(measurements: CaptureMeasurements, hasPose: Boolean, quality: CaptureQuality): evaluatedGate =
        evaluatedGate(quality.evaluateDocument(measurements, hasPose))

    /**
     * @return the match, or `null` when the gate failed or the inputs are unusable.
     *
     * A `null` is not an error path: it is the *correct* answer for a bad capture, and the
     * caller is expected to hand `null` straight to `Evidence.face`.
     */
    fun gatedMatch(
        gate: evaluatedGate,
        docEmbedding: FloatArray?,
        liveEmbedding: FloatArray?,
        docQuality: Float,
        liveQuality: Float,
        passiveLiveness: Float,
        headTurnPassed: Boolean?,
    ): GatedMatch? {
        if (!gate.report.passed) {
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.QUALITY_GATE, codes = gate.report.causes, detail = "match refused"))
            return null
        }
        if (docEmbedding == null || liveEmbedding == null) {
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.FACE, codes = listOf(dev.kasoti.fusion.FindingCode.SYS_CAPTURE_FAILED), detail = "no embedding"))
            return null
        }
        if (docEmbedding.size != liveEmbedding.size) return null
        return GatedMatch(docEmbedding, liveEmbedding, docQuality, liveQuality, passiveLiveness, headTurnPassed)
    }

    /**
     * Builds the [Evidence] and runs the engine.
     *
     * @param stepQuality per-step reports, carried into the case bundle and the log.
     * @param qualityReports one per capture step; **the document-page report is the one that
     *   decides the QUALITY layer**, because it is the layer `:core` treats as load-bearing
     *   for every track (FUSION.md §7). A failed face gate is a step failure and shows up as a
     *   step instruction; a failed page gate is the verdict.
     */
    fun assemble(
        caseId: String,
        track: Track,
        pageQuality: QualityReport,
        stepQuality: List<StepQuality> = emptyList(),
        math: MathEvidence? = null,
        qr: QrEvidence? = null,
        chip: ChipEvidence? = null,
        macro: MacroEvidence? = null,
        match: GatedMatch? = null,
        diary: DiaryEvidence? = null,
        trust: TrustState = TrustState.VERIFY,
        consecutiveGreyCount: Int = 0,
        presentation: Presentation = Presentation.PHYSICAL,
        demoMode: Boolean = false,
        referenceYear: Int,
    ): AssembledCase {
        val face = match?.let {
            FaceEvidence(
                similarity = it.similarity(),
                docQuality = it.docQuality,
                liveQuality = it.liveQuality,
                passiveLivenessScore = it.passiveLiveness,
                headTurnPassed = it.headTurnPassed,
            )
        }

        val evidence = Evidence(
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
            demoMode = demoMode,
        )

        val report = dev.kasoti.fusion.FusionEngine.decide(evidence, registry, referenceYear)
        log.write(FieldLogEntry.verdict(caseId, report))
        return AssembledCase(caseId, evidence, report, stepQuality)
    }
}
