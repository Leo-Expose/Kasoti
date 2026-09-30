package dev.kasoti.eval.split

import dev.kasoti.eval.run.ThresholdProvenance
import kotlinx.serialization.Serializable

/**
 * Which split chose each threshold, recorded in `eval/data/manifests/threshold_provenance.json`.
 *
 * This file is the anti-gaming ledger. It answers one question per threshold — *which
 * split did the number come from* — and the harness refuses to produce a valid run when
 * the answer is "report". It exists because EVAL.md §8's rule is unenforceable without a
 * durable record: an operating point chosen by looking at the report split is
 * indistinguishable from one chosen on the tune split once it is written into a threshold
 * file, unless somebody wrote down which happened.
 */
@Serializable
data class ThresholdLedger(
    val version: String,
    val entries: List<ThresholdProvenance>,
) {
    fun forName(name: String): ThresholdProvenance? = entries.firstOrNull { it.name == name }

    companion object {
        fun empty(version: String = "absent") = ThresholdLedger(version, emptyList())
    }
}

/**
 * Result of the split-discipline check (EVAL.md §8).
 *
 * The verdict is deliberately three-valued. "Invalid" is not a softer "fail": it means the
 * run cannot be cited at all, and [fatal] carries the sentence that goes into
 * `summary.md` so nobody has to reconstruct the reason from a log.
 */
@Serializable
data class SplitVerdict(
    val valid: Boolean,
    val checked: List<String>,
    val untuned: List<String>,
    val reportSplitTuned: List<String>,
    val unrecorded: List<String>,
    val manifestProblems: List<ManifestProblem>,
    val fatal: List<String>,
) {
    val isValid: Boolean get() = valid && fatal.isEmpty()

    val summary: String
        get() = when {
            fatal.isNotEmpty() -> "INVALID — " + fatal.joinToString("; ")
            !valid -> "INVALID — " + reportSplitTuned.joinToString("; ")
            else -> "VALID — " + checked.size + " threshold(s) checked, " +
                "${untuned.size} still at the untuned default"
        }

    companion object {
        val OK: SplitVerdict = SplitVerdict(
            valid = true,
            checked = emptyList(),
            untuned = emptyList(),
            reportSplitTuned = emptyList(),
            unrecorded = emptyList(),
            manifestProblems = emptyList(),
            fatal = emptyList(),
        )
    }
}

/**
 * The split-discipline gate.
 *
 * EVAL.md §8 makes tuning on the report split an invalid run, and the harness enforces it
 * by manifest. Three things are checked, in increasing order of severity:
 *
 * 1. The dataset manifest must be structurally sound — a leaky split unit invalidates the
 *    run even if the numbers are fine.
 * 2. Every threshold this run actually *used* must have a ledger entry. A threshold with no
 *    recorded provenance is treated as unrecorded and blocks the run: "we do not know
 *    which split chose it" is not a licence to publish it.
 * 3. A recorded `report` selection is fatal on its own, and the message names the
 *    threshold and the run that put it there.
 */
object SplitDiscipline {

    /**
     * @param thresholdsInUse the [dev.kasoti.threshold.ThresholdName]s whose values this
     *   run consulted. Thresholds not consulted cannot have contaminated the numbers, so
     *   they are not checked — a run that never opens a face threshold is not invalidated
     *   by the face threshold's provenance.
     */
    fun check(
        ledger: ThresholdLedger,
        manifest: DatasetManifest,
        thresholdsInUse: Set<String>,
    ): SplitVerdict {
        val problems = DatasetManifestValidator.validate(manifest)
        val fatal = mutableListOf<String>()
        for (p in problems) {
            if (p.severity == ManifestProblemSeverity.FATAL) {
                fatal += "${p.code} [${p.subject}]: ${p.detail}"
            }
        }

        val checked = mutableListOf<String>()
        val untuned = mutableListOf<String>()
        val onReport = mutableListOf<String>()
        val unrecorded = mutableListOf<String>()

        for (name in thresholdsInUse.sorted()) {
            val entry = ledger.forName(name)
            if (entry == null) {
                unrecorded += "$name has no entry in threshold_provenance (v${ledger.version}); " +
                    "the harness cannot prove which split selected it"
                continue
            }
            checked += name

            if (entry.selectedOnSplit == ThresholdProvenance.REPORT) {
                onReport += "$name was selected on the ${DatasetManifest.REPORT} split " +
                    "(run ${entry.runId}, ${entry.selectedAtUtc}, owner ${entry.owner}); " +
                    "EVAL.md §8 makes this an invalid run"
                continue
            }
            if (entry.isUntuned) {
                untuned += "$name is still at its untuned registry default"
            }
        }

        return SplitVerdict(
            valid = onReport.isEmpty() && unrecorded.isEmpty(),
            checked = checked,
            untuned = untuned,
            reportSplitTuned = onReport,
            unrecorded = unrecorded,
            manifestProblems = problems,
            fatal = fatal,
        )
    }
}
