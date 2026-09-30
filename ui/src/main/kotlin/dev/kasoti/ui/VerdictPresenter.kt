package dev.kasoti.ui

import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Layer
import dev.kasoti.fusion.LayerStatus
import dev.kasoti.fusion.Verdict
import dev.kasoti.fusion.VerdictReport
import dev.kasoti.i18n.Language
import dev.kasoti.i18n.Messages

/**
 * One line in the findings table.
 *
 * [text] is always `Messages.of(code, language)` — never `Finding.message`, which is a log
 * string in English and is not translatable (AGENTS.md §2, and the KDoc on `Finding` says so
 * explicitly). [detail] carries the evidence reference, which is a stable pointer a reviewer
 * can go and look at, and is deliberately *not* translated because it is a machine identifier.
 */
data class FindingRow(
    val code: FindingCode,
    val severityText: String,
    val text: String,
    val evidenceRef: String,
    val tone: VerdictTone,
    /** The only line an officer needs when a voice readout has to fit into one breath. */
    val voiceText: String,
) {
    val isCause: Boolean get() = tone == VerdictTone.RETAKE
}

/** Which layers ran, and whether the verdict leaned on them (FUSION.md §7). */
data class LayerRow(
    val layer: Layer,
    val status: LayerStatus,
    val loadBearing: Boolean,
    val ref: String,
)

/**
 * The complete verdict screen, ready to render.
 *
 * [headline] is the giant, glanceable word. [tone] is what it is allowed to look like. The two
 * are produced together by [VerdictPresenter] so that a renderer cannot pair the word RETAKE
 * with the visual treatment of a stop sign — that pairing is the single most likely way this
 * app could hurt someone, and it should not be reachable by forgetting a colour somewhere.
 *
 * [retakeInstructions] is separated from [findings] on purpose. A GREY cause is not a finding
 * about a document; it is a sentence telling the operator how to hold the next photo. Mixing
 * them in one list is how a retake instruction ends up under a heading that reads like a
 * finding.
 */
data class VerdictScreen(
    val verdictName: String,
    val headline: String,
    val tone: VerdictTone,
    val findings: List<FindingRow>,
    val retakeInstructions: List<FindingRow>,
    /** Present only on GREY: proofs the previous capture already showed, attached but not acted on. */
    val carriedOver: List<FindingRow>,
    val layers: List<LayerRow>,
    val supervisorRequired: Boolean,
    val trustFastPath: Boolean,
    val demoMode: Boolean,
    val policyLine: String,
    /** Short, stable, non-localised hook for the case-bundle crop lookup. `null` when unknown. */
    val thumbnailKey: String?,
) {
    val isRetake: Boolean get() = tone == VerdictTone.RETAKE
    val isAccusatory: Boolean get() = tone.isAccusatory

    /** True when this screen needs a thumbnail to make sense. Used to size the crop capture. */
    val wantsEvidence: Boolean get() = tone.isAccusatory || findings.any { it.code == FindingCode.R_PROC_02 }
}

/**
 * Turns a `:core` [VerdictReport] into a screen.
 *
 * The only place in the project that resolves a `FindingCode` to operator-facing text. There is
 * no alternative path and no configuration: an engineer who wants a different string has to
 * change `:core`'s `Messages`, where the completeness test lives, and that is the point.
 */
object VerdictPresenter {

    fun present(
        report: VerdictReport,
        language: Language,
        thumbnailKey: String? = null,
    ): VerdictScreen {
        val tone = VerdictTone.of(report.verdict)
        val rows = report.findings.map { it.toRow(language) }
        val screen = VerdictScreen(
            verdictName = report.verdict.name,
            headline = headlineFor(report, language),
            tone = tone,
            // A retake cause is an instruction, not a finding: it moves to its own list so the
            // findings list under a GREY reads as empty, which is the truth.
            findings = if (tone == VerdictTone.RETAKE) rows.filterNot { it.isCause } else rows,
            retakeInstructions = if (tone == VerdictTone.RETAKE) rows.filter { it.isCause } else emptyList(),
            carriedOver = report.carriedOver.map { it.toRow(language) },
            layers = report.layers.status.map { (layer, status) ->
                LayerRow(
                    layer = layer,
                    status = status,
                    loadBearing = report.layers.bearingOf(layer) == dev.kasoti.fusion.Bearing.LOAD_BEARING,
                    ref = report.layers.ref(layer),
                )
            },
            supervisorRequired = report.supervisorRequired,
            trustFastPath = report.trustFastPath,
            demoMode = report.demoMode,
            policyLine = report.policyVersions,
            thumbnailKey = thumbnailKey,
        )
        return if (tone == VerdictTone.RETAKE) checkedAsNonAccusatory(screen) else screen
    }

    /**
     * The last line of defence for FUSION.md §1.
     *
     * `Messages` is in `:core` and its Hindi strings were written by other people; a future
     * edit could easily make a GREY cause read like a verdict ("document is invalid", say). The
     * designer of this system must not be the one who finds out at a counter, so the assembled
     * GREY screen is checked here and a violation is a hard failure — which, in a demo, is
     * exactly the recovery line from DEMO.md §5: honest software says so.
     */
    private fun checkedAsNonAccusatory(screen: VerdictScreen): VerdictScreen {
        val text = buildString {
            append(screen.headline).append('\n')
            screen.retakeInstructions.forEach { append(it.text).append('\n') }
            screen.findings.forEach { append(it.text).append('\n') }
            screen.carriedOver.forEach { append(it.text).append('\n') }
        }
        AccusationLexicon.assertNoAccusation(text)
        return screen
    }

    private fun headlineFor(report: VerdictReport, language: Language): String = when (report.verdict) {
        Verdict.GREEN -> FieldStrings.of(FieldStrings.Key.VERDICT_GREEN, language)
        Verdict.AMBER -> FieldStrings.of(FieldStrings.Key.VERDICT_AMBER, language)
        Verdict.RED -> FieldStrings.of(FieldStrings.Key.VERDICT_RED, language)
        // Deliberately the retake verb, not a verdict word. "RETAKE" tells the operator what to
        // do next; "INVALID" or "UNVERIFIED" tells them something about the document, which is
        // precisely the claim a failed quality gate cannot support.
        Verdict.GREY -> FieldStrings.of(FieldStrings.Key.VERDICT_GREY, language)
    }

    private fun Finding.toRow(language: Language): FindingRow = FindingRow(
        code = code,
        severityText = severity.name,
        text = Messages.of(code, language),
        evidenceRef = evidenceRef,
        tone = severity.tone(),
        voiceText = Messages.of(code, language),
    )
}
