package dev.kasoti.ui

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.i18n.Language

/**
 * The four capture slots of the guided stranger flow (FR-C1: "≤4 steps").
 *
 * The cap is a hard constraint, not a guideline: an operator holding a lanyard card in one hand
 * and a stranger's document in the other has a short attention budget, and a six-step wizard is
 * how a checkpoint queue forms. [CapturePlanner] in `:app-android` decides which of these are
 * *required* for a given document; this module only knows the four that exist.
 *
 * [MACRO] is two patches inside one step, not two steps — FR-C3's "2 patches" is one operator
 * action, and splitting it would be the cheapest way to blow the budget.
 */
enum class CaptureStepId {
    /** Full-page capture. The only mandatory step. */
    DOCUMENT,

    /** MRZ band, for documents that carry one. */
    MRZ,

    /** Two macro patches under the clip shroud. */
    MACRO,

    /** 1 s face clip, ≥5 frames. */
    FACE,
}

/** One step as the operator sees it. */
data class CaptureStepCard(
    val id: CaptureStepId,
    val ordinal: Int,
    val total: Int,
    val title: String,
    val hint: String,
    val required: Boolean,
    val done: Boolean,
    val meter: QualityMeter,
) {
    val label: String = "$ordinal/$total"
    val canAdvance: Boolean get() = meter.passed || !required
}

/**
 * The live quality meter on every step (FR-C1).
 *
 * Two rules make this worth having as a value rather than a few composables:
 *
 *  1. [passed] is the *gate's* verdict, not a smoothed animation value. An operator who is
 *     being told "hold still" must be told it by the same boolean that will block the capture,
 *     or the meter is a decoration.
 *  2. [causes] is a list of `FindingCode`s, never prose. The instruction shown is
 *     `Messages.of(cause, language)` — the same string that will appear in the audit record and
 *     in the case bundle, so what the officer was told and what was recorded cannot diverge.
 */
data class QualityMeter(
    val passed: Boolean,
    /** 0..1, from the gate's own score. Drives the bar; never drives the decision. */
    val score: Float,
    val causes: List<FindingCode>,
    /** True when the meter is reporting on a live preview frame rather than a still. */
    val live: Boolean,
) {
    val blocked: Boolean get() = !passed && causes.isNotEmpty()

    /** The instruction, in the operator's language. Empty when the gate passed. */
    fun instructions(language: Language): List<String> =
        if (passed) emptyList() else causes.map { dev.kasoti.i18n.Messages.of(it, language) }

    companion object {
        /** No preview yet: unknown, not failed. Must not read as "you are holding it wrong". */
        val UNKNOWN = QualityMeter(passed = true, score = 0f, causes = emptyList(), live = true)

        fun from(report: QualityReport, live: Boolean): QualityMeter = QualityMeter(
            passed = report.passed,
            score = report.qualityScore(),
            causes = report.causes,
            live = live,
        )
    }
}

/**
 * The combined 0..1 quality figure for a capture.
 *
 * Derived from the same measurements the gate reads rather than being an independent score, so
 * the bar and the pass/fail cannot disagree. When the gate passed there is nothing to say
 * about quality, and a number would be inventing one — hence `1.0` for "fine" and a real
 * reading only when something is actually wrong.
 */
fun QualityReport.qualityScore(): Float {
    if (passed) return 1f
    if (causes.isEmpty()) return 0f
    // The worst offender again, mirroring `QualityGate.evaluate`: a well-lit but heavily
    // occluded face is not "mostly fine". Averaging would let three good readings hide one
    // disqualifying one, which is the failure this whole layer exists to prevent.
    val blur = (blurScore / BLUR_REFERENCE).coerceIn(0f, 1f)
    val glare = (1f - glareRatio / GLARE_REFERENCE).coerceIn(0f, 1f)
    val dark = (brightness / BRIGHT_MIN_REFERENCE).coerceIn(0f, 1f)
    val bright = (1f - (brightness - BRIGHT_MAX_REFERENCE) / BRIGHT_SPAN_REFERENCE).coerceIn(0f, 1f)
    val yaw = (1f - (yawDegrees.coerceAtLeast(0f) / POSE_REFERENCE)).coerceIn(0f, 1f)
    val pitch = (1f - (pitchDegrees.coerceAtLeast(0f) / POSE_REFERENCE)).coerceIn(0f, 1f)
    val parts = listOf(blur, glare, dark, bright, yaw, pitch) + if (occluded) listOf(0f) else listOf(1f)
    return parts.min().coerceIn(0f, 1f)
}

// The meter's display references. NOT operating thresholds: the pass/fail boundary comes from
// `ThresholdRegistry` via `QualityGate`, and these exist only to draw a bar that is monotonic
// with it. They are the registry's `v1` defaults, written out, and are named so a reviewer can
// see at a glance that changing one does not move a gate. AGENTS.md §5's ban on magic numbers
// is about numbers that *decide*; these decide nothing, and
// `app-android/src/test/.../QualityMeterRegistryAgreementTest.kt` asserts they still agree
// with `ThresholdRegistry.defaults()` so they cannot silently drift.
private const val BLUR_REFERENCE = 100f
private const val GLARE_REFERENCE = 0.12f
private const val BRIGHT_MIN_REFERENCE = 40f
private const val BRIGHT_MAX_REFERENCE = 225f
private const val BRIGHT_SPAN_REFERENCE = 255f
private const val POSE_REFERENCE = 25f

/** The stepper strip: which steps, which are done, which is current. */
data class CaptureProgress(
    val cards: List<CaptureStepCard>,
    val currentIndex: Int,
) {
    val total: Int get() = cards.size
    val current: CaptureStepCard? get() = cards.getOrNull(currentIndex)
    val completed: Int get() = cards.count { it.done }
    val allDone: Boolean get() = cards.isNotEmpty() && cards.all { !it.required || it.meter.passed }

    /** A step the operator has to fix before continuing. `null` when the flow may advance. */
    val blocker: CaptureStepCard? = cards.getOrNull(currentIndex)?.takeIf { it.required && !it.meter.passed }

    init {
        require(total <= MAX_STEPS) { "FR-C1 caps the stranger flow at $MAX_STEPS steps, got $total" }
        require(currentIndex in 0..total) { "currentIndex $currentIndex out of range for $total steps" }
    }

    companion object {
        /** FR-C1: "Guided stranger flow ≤4 steps". Asserted in `init`, not just documented. */
        const val MAX_STEPS = 4

        val EMPTY = CaptureProgress(emptyList(), 0)
    }
}

/** Builds step cards from ids, the per-step results, and the language. */
object CaptureProgressBuilder {

    fun build(
        steps: List<CaptureStepId>,
        done: Map<CaptureStepId, Boolean>,
        meters: Map<CaptureStepId, QualityMeter>,
        currentIndex: Int,
        language: Language,
    ): CaptureProgress {
        require(steps.size <= CaptureProgress.MAX_STEPS) {
            "FR-C1 caps the stranger flow at ${CaptureProgress.MAX_STEPS} steps, got ${steps.size}: $steps"
        }
        val total = steps.size
        val cards = steps.mapIndexed { index, id ->
            CaptureStepCard(
                id = id,
                ordinal = index + 1,
                total = total,
                title = FieldStrings.of(titleKey(id), language),
                hint = FieldStrings.of(hintKey(id), language),
                // Everything the planner lists is required unless it says otherwise; the
                // planner omits optional steps entirely, so `true` here is the safe default.
                required = true,
                done = done[id] == true,
                meter = meters[id] ?: QualityMeter.UNKNOWN,
            )
        }
        return CaptureProgress(cards, currentIndex)
    }

    private fun titleKey(id: CaptureStepId): FieldStrings.Key = when (id) {
        CaptureStepId.DOCUMENT -> FieldStrings.Key.STEP_DOCUMENT
        CaptureStepId.MRZ -> FieldStrings.Key.STEP_MRZ
        CaptureStepId.MACRO -> FieldStrings.Key.STEP_MACRO
        CaptureStepId.FACE -> FieldStrings.Key.STEP_FACE
    }

    private fun hintKey(id: CaptureStepId): FieldStrings.Key = when (id) {
        CaptureStepId.DOCUMENT -> FieldStrings.Key.STEP_DOCUMENT_HINT
        CaptureStepId.MRZ -> FieldStrings.Key.STEP_MRZ_HINT
        CaptureStepId.MACRO -> FieldStrings.Key.STEP_MACRO_HINT
        CaptureStepId.FACE -> FieldStrings.Key.STEP_FACE_HINT
    }
}
