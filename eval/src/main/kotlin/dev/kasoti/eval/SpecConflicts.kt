package dev.kasoti.eval

import dev.kasoti.eval.split.ThresholdLedger
import dev.kasoti.threshold.ThresholdName

/**
 * Contradictions inside the normative docs that the harness refuses to resolve silently.
 *
 * These are surfaced, not fixed. AGENTS.md §8 says to report a conflict rather than pick a
 * side, and EVAL.md §1 says no metric leaves without a run-id — a run that quietly
 * reinterpreted its own spec would produce a number nobody could reproduce. The conflicts
 * are recomputed from the committed manifests and the committed registry on every run, so
 * fixing the underlying doc makes the warning disappear on its own.
 */
object SpecConflicts {

    fun detect(ledger: ThresholdLedger): List<String> {
        val conflicts = mutableListOf<String>()

        // EVAL.md §4 fixes the face operating point on the *report* split:
        //   "operating point = max TAR with FAR≤0.1% on report split; report FRR there"
        // FUSION.md §6 fixes tuning on the *tune* split:
        //   "1. Tune ONLY on tune split; ... 2. select operating point by policy above;
        //    3. verify on report split"
        // Read together, the operating point is *selected* on the report split while
        // *tuning* happens on the tune split. The harness records the ledger's answer, and
        // the rule it enforces is the strict one: a threshold whose provenance says `report`
        // invalidates the run. If the team's intent is the softer reading — that selecting
        // an operating point is not "tuning" — the ledger should say `tune` for the
        // parameter search and the report split should be reserved for measurement. That is
        // a decision for the vision owner, not for the harness.
        val faceRed = ledger.forName(ThresholdName.T_FACE_RED.name)
        if (faceRed != null && faceRed.selectedOnSplit == "report") {
            conflicts += "T_FACE_RED is recorded as selected on the **report** split. " +
                "EVAL.md §4 places the face operating point on the report split, while " +
                "FUSION.md §6 says 'Tune ONLY on tune split'. The harness enforces the " +
                "strict reading (EVAL.md §8) and marks this run INVALID. Resolve by " +
                "recording the *parameter search* split in the ledger, and reserve the report " +
                "split for measurement."
        }

        // ThresholdName.tuningDataRef is a free-text string, so a split name can be written
        // into it without any check. Flag the ones that name a split the harness cannot
        // resolve, rather than parsing them and guessing.
        for (threshold in ThresholdName.entries) {
            val ref = threshold.tuningDataRef
            if (ref.contains("report", ignoreCase = true) &&
                !ledger.forName(threshold.name)?.selectedOnSplit.equals("report")
            ) {
                conflicts += "${threshold.name}.tuningDataRef is '$ref', which names the report " +
                    "split, but the ledger records " +
                    "${ledger.forName(threshold.name)?.selectedOnSplit ?: "no split"}. One of " +
                    "the two is stale; the ledger is the one the harness enforces."
            }
        }

        return conflicts
    }
}
