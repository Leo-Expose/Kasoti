package dev.kasoti.eval

import dev.kasoti.eval.split.DatasetEntry
import dev.kasoti.eval.split.DatasetManifest
import dev.kasoti.eval.split.DatasetManifestValidator
import dev.kasoti.eval.split.ManifestProblemSeverity
import dev.kasoti.eval.split.SplitDiscipline
import dev.kasoti.eval.split.SplitVerdict
import dev.kasoti.eval.split.ThresholdLedger
import dev.kasoti.eval.run.ThresholdProvenance
import dev.kasoti.threshold.ThresholdName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the anti-gaming mechanism (EVAL.md §8).
 *
 * The positive cases matter less than the negative ones. A split-discipline check that has
 * never been seen to *fail* is a check nobody knows works, so the adversarial cases here are
 * the point of the file: each one constructs a manifest or ledger a motivated person might
 * plausibly write, and asserts the harness refuses it.
 */
class SplitDisciplineTest {

    private val healthyManifest = DatasetManifest(
        version = "test",
        datasets = listOf(
            DatasetEntry(
                id = "D-FACE",
                splits = listOf("tune", "report"),
                splitUnit = DatasetEntry.UNIT_IDENTITY,
                reportSplit = "report",
            ),
        ),
    )

    private fun entry(name: String, split: String) = ThresholdProvenance(
        name = name,
        dataset = "D-FACE",
        selectedOnSplit = split,
        runId = "eval-test",
        selectedAtUtc = "2026-01-01T00:00:00Z",
        owner = "vision",
    )

    private fun ledger(vararg entries: ThresholdProvenance) = ThresholdLedger("test", entries.toList())

    // ---- the happy path ------------------------------------------------------

    @Test
    fun `a threshold tuned on the tune split is valid`() {
        val verdict = SplitDiscipline.check(
            ledger = ledger(entry(ThresholdName.T_FACE_RED.name, ThresholdProvenance.TUNE)),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.T_FACE_RED.name),
        )
        assertTrue(verdict.isValid, "expected a valid verdict, got: ${verdict.summary}")
        assertEquals(listOf(ThresholdName.T_FACE_RED.name), verdict.checked)
    }

    @Test
    fun `a threshold not used by this run is not checked`() {
        val verdict = SplitDiscipline.check(
            // The ledger says T_FACE_RED WAS report-tuned. It must not invalidate a run that
            // never consults it, or every run would be poisoned by every historical mistake.
            ledger = ledger(
                entry(ThresholdName.T_FACE_RED.name, ThresholdProvenance.REPORT),
                entry(ThresholdName.VMAX.name, ThresholdProvenance.POLICY),
            ),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.VMAX.name),
        )
        assertTrue(verdict.isValid, verdict.summary)
        assertEquals(listOf(ThresholdName.VMAX.name), verdict.checked)
    }

    // ---- the adversarial cases ----------------------------------------------

    @Test
    fun `a threshold selected on the report split invalidates the run`() {
        val verdict = SplitDiscipline.check(
            ledger = ledger(entry(ThresholdName.T_FACE_RED.name, ThresholdProvenance.REPORT)),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.T_FACE_RED.name),
        )
        assertFalse(verdict.isValid, "tuning on the report split must invalidate the run")
        assertEquals(1, verdict.reportSplitTuned.size)
        assertContains(verdict.reportSplitTuned.first(), ThresholdName.T_FACE_RED.name)
        assertContains(verdict.reportSplitTuned.first(), "EVAL.md §8")
    }

    @Test
    fun `a threshold with no provenance is treated as unrecorded, not as fine`() {
        val verdict = SplitDiscipline.check(
            ledger = ledger(),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.T_FACE_RED.name),
        )
        assertFalse(verdict.isValid, "an unrecorded threshold must block the run")
        assertEquals(1, verdict.unrecorded.size)
    }

    @Test
    fun `splitting D-MACRO by patch is rejected as leakage`() {
        val leaky = DatasetManifest(
            version = "test",
            datasets = listOf(
                DatasetEntry(
                    id = "D-MACRO",
                    splits = listOf("train", "tune", "report"),
                    splitUnit = "patch",
                    reportSplit = "report",
                ),
            ),
        )
        val problems = DatasetManifestValidator.validate(leaky)
        val leak = problems.single { it.code == "MANIFEST_LEAKY_SPLIT_UNIT" }
        assertEquals(ManifestProblemSeverity.FATAL, leak.severity)

        val verdict = SplitDiscipline.check(ledger(), leaky, setOf(ThresholdName.MACRO_MARGIN_RED.name))
        assertFalse(verdict.isValid, "a leaky split unit must invalidate the run outright")
        assertContains(verdict.fatal.joinToString(" "), "MANIFEST_LEAKY_SPLIT_UNIT")
    }

    @Test
    fun `a report split that is not among the declared splits is rejected`() {
        val inconsistent = DatasetManifest(
            version = "test",
            datasets = listOf(
                DatasetEntry(
                    id = "D-FACE",
                    splits = listOf("train", "tune"),
                    splitUnit = DatasetEntry.UNIT_IDENTITY,
                    reportSplit = "report",
                ),
            ),
        )
        val problems = DatasetManifestValidator.validate(inconsistent)
        assertTrue(
            problems.any { it.code == "MANIFEST_REPORT_SPLIT_ABSENT" },
            "declaring a report split the dataset does not have would let report numbers be " +
                "attributed to a split that does not exist: $problems",
        )
    }

    @Test
    fun `a duplicate dataset declaration is rejected`() {
        // A shadowed second declaration could declare a legal split unit while the first
        // declares a leaky one, and a reader would have no way to tell which is in force.
        val shadowed = DatasetManifest(
            version = "test",
            datasets = listOf(
                DatasetEntry("D-MACRO", listOf("train", "tune", "report"), DatasetEntry.UNIT_SOURCE_DOC, "report"),
                DatasetEntry("D-MACRO", listOf("train", "tune", "report"), "patch", "report"),
            ),
        )
        assertTrue(
            DatasetManifestValidator.validate(shadowed).any { it.code == "MANIFEST_DUPLICATE_DATASET" },
        )
    }

    @Test
    fun `an untuned threshold is valid but reported as untuned`() {
        val verdict = SplitDiscipline.check(
            ledger = ledger(entry(ThresholdName.T_FACE_RED.name, ThresholdProvenance.UNTUNED)),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.T_FACE_RED.name),
        )
        assertTrue(verdict.isValid, "an untuned default has not been fitted to any split")
        assertEquals(1, verdict.untuned.size, "the untuned threshold must be reported as such")
        assertContains(verdict.untuned.first(), ThresholdName.T_FACE_RED.name)
    }

    @Test
    fun `an empty manifest is fatal rather than vacuously fine`() {
        val verdict = SplitDiscipline.check(ledger(), DatasetManifest("test", emptyList()), setOf("X"))
        assertFalse(verdict.isValid)
        assertContains(verdict.fatal.joinToString(" "), "MANIFEST_EMPTY")
    }

    @Test
    fun `the summary states the reason rather than just the verdict`() {
        val verdict: SplitVerdict = SplitDiscipline.check(
            ledger = ledger(entry(ThresholdName.T_FACE_RED.name, ThresholdProvenance.REPORT)),
            manifest = healthyManifest,
            thresholdsInUse = setOf(ThresholdName.T_FACE_RED.name),
        )
        assertContains(verdict.summary, "INVALID")
        assertContains(verdict.summary, "T_FACE_RED")
    }
}
