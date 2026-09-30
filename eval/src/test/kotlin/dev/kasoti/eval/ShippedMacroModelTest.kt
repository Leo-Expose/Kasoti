package dev.kasoti.eval

import dev.kasoti.eval.suites.MacroSpecimenFile
import dev.kasoti.eval.suites.SvmModelLoader
import dev.kasoti.eval.suites.SpecimenFixtures
import dev.kasoti.factory.MacroGate
import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.factory.ProcessLabel
import dev.kasoti.factory.Spectrum
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped `svm_print_v1_synthetic.json`, asserted end to end (DESIGN.md §6, SPEC.md §7).
 *
 * ## What this file is and is not
 *
 * These tests read the *committed* weights file and the *committed* specimen vectors, so they
 * check the artefact that ships rather than a fixture. They assert three things:
 *
 *  1. the file satisfies every one of `SvmModel`'s own `init` requirements, and carries the
 *     provenance the rest of the system hangs its honesty on;
 *  2. each of the seven classes' held-out specimens is ranked under its own class;
 *  3. **the ambiguous specimens do not produce a confident answer.** This is the important one.
 *
 * ## Why (3) is the important one
 *
 * A classifier that always says `OFFSET` passes a shape test and fails the product. What the
 * fusion rules act on is the *margin*: `DocAggregator` refuses to build a MATCH or a MISMATCH
 * unless both zones clear `MACRO_MARGIN_RED`, and `A-WORN-01` fires on a low-margin patch. So the
 * failure this file exists to catch is a model that is confidently wrong, and a margin assertion
 * is the only thing that catches it — an accuracy assertion cannot, because a wrong answer and an
 * unsure answer score identically on accuracy.
 *
 * ## The trust chain, stated honestly
 *
 * The specimen vectors come from `extract_features.extract` (NumPy), not from `:core`. That is a
 * real assumption and it is covered by `eval/tools/cross_check.py`, which measures the
 * NumPy↔Kotlin feature agreement at a stated tolerance on every run. The chain is
 * `synth_macros.py` (committed) → `extract_features.py` (committed, cross-checked) → this
 * committed vector dump → the committed weights. See `eval/data/fixtures/README.md` for why the
 * pixels themselves are not committed.
 */
class ShippedMacroModelTest {

    private val repoRoot: Path = Path.of("..").toAbsolutePath().normalize().let {
        if (Files.isRegularFile(it.resolve("settings.gradle.kts"))) it else Path.of(".").toAbsolutePath().normalize()
    }

    private val registry = ThresholdRegistry.defaults(version = "test", runId = "shipped-model-test")

    private fun modelFile(): Path = repoRoot.resolve(SvmModelLoader.SYNTHETIC_PATH)

    private fun loaded() = assertNotNull(
        SvmModelLoader.loadIfPresent(modelFile()),
        "${SvmModelLoader.SYNTHETIC_PATH} is missing. Regenerate it with " +
            "eval/tools/synth_macros.py then eval/tools/train_svm.py --synthetic-corpus.",
    )

    private fun specimens(): List<MacroSpecimenFile> =
        SpecimenFixtures.loadIfPresent(repoRoot).also {
            assertEquals(emptyList(), SpecimenFixtures.assertFresh(it), "specimen fixture dump is stale")
        }

    // ------------------------------------------------------------------ shape

    @Test
    fun `the shipped model satisfies every SvmModel init requirement`() {
        val model = loaded().model
        // Each of these is an `init` `require` in `dev.kasoti.factory.SvmModel`. They are asserted
        // individually as well as implicitly, because "it constructed" does not say *which*
        // requirement was load-bearing and a future edit that relaxes one should be visible here.
        assertEquals(ProcessLabel.entries.size, model.labels.size, "one weight row per class")
        assertEquals(model.labels.size, model.bias.size, "one bias per class")
        assertEquals(MacroFeatures.TOTAL, model.featureNames.size, "feature names match the width")
        model.weights.forEachIndexed { index, row ->
            assertEquals(MacroFeatures.TOTAL, row.size, "weights[$index] is ${MacroFeatures.TOTAL} long")
            assertTrue(row.all { it.isFinite() }, "weights[$index] has a non-finite entry")
        }
        assertTrue(model.bias.all { it.isFinite() }, "a non-finite bias")
        assertEquals(ProcessLabel.entries.toList(), model.labels, "labels in declaration order")
        assertTrue(model.trainingRunId.isNotBlank(), "a model that cannot be traced to a run")
    }

    @Test
    fun `the shipped model declares itself synthetic, in the file and in the run id`() {
        val trained = loaded()
        assertTrue(trained.synthetic, "the shipped model must carry synthetic=true")
        assertContains(trained.model.trainingRunId, "SYNTHETIC")
        assertContains(trained.banner, "SYNTHETIC")
        assertContains(trained.banner, "never gate-eligible", ignoreCase = true)
    }

    @Test
    fun `the shipped model records its split policy and its corpus size`() {
        val trained = loaded()
        assertContains(trained.splitPolicy, "source document")
        assertTrue(trained.sourceDocCount > 100, "sourceDocCount was ${trained.sourceDocCount}")
    }

    @Test
    fun `the shipped model stays inside the DESIGN section 6 size budget`() {
        val bytes = Files.size(modelFile())
        // DESIGN.md §6 says "~50 KB". A model that has quietly grown to a megabyte is a
        // different decision that nobody made, so the budget is asserted rather than assumed.
        assertTrue(bytes in 1_000..250_000, "shipped model is $bytes bytes, DESIGN §6 budgets ~50 KB")
    }

    @Test
    fun `the shipped model scores every class distinctly rather than collapsing`() {
        // An all-zero or near-symmetric weight matrix would satisfy every shape check above and
        // classify nothing. This asserts the weights are actually doing something.
        val model = loaded().model
        val spread = model.weights.map { row -> row.sum() }
        assertTrue(spread.distinct().size >= 5, "weight rows are nearly identical: $spread")
        val scores = model.scores(
            specimens().first { it.label == "OFFSET" }.toMacroFeatures(),
        )
        assertTrue(scores.values.toSet().size >= 4, "scores collapse onto a few values: $scores")
    }

    // ------------------------------------------------------- per-class labels

    @Test
    fun `each class's held-out specimen is ranked under its own class`() {
        val classifier = ProcessClassifier(loaded().model)
        val failures = specimens()
            .filter { it.isClassSpecimen }
            .mapNotNull { row ->
                val patch = classifier.classify(row.toMacroFeatures())
                if (patch.label.name == row.label) null else "${row.id}: ${row.label} -> ${patch.label}"
            }
        assertEquals(emptyList(), failures, "held-out specimens misranked")
    }

    @Test
    fun `each process class's held-out specimen is reported under its own class`() {
        // The "sensible label" claim, in the form the product uses it: `MacroGate` returns the
        // class rather than UNKNOWN, which needs a margin at or above MACRO_MARGIN_AMBER. Scoring
        // through the gate rather than through `classify` is the point — an argmax on its own is
        // not what an operator sees.
        //
        // `SCREEN` and `UNKNOWN` are excluded and asserted separately below, for stated reasons.
        val classifier = ProcessClassifier(loaded().model)
        val failures = specimens()
            .filter { it.isClassSpecimen && it.label !in EXCLUDED_FROM_STRONG_CLAIM }
            .mapNotNull { row ->
                val reading = MacroGate.read(classifier, row.toMacroFeatures(), registry)
                when {
                    reading.abstained ->
                        "${row.id}: abstained at margin ${reading.margin} " +
                            "(floor ${registry[ThresholdName.MACRO_MARGIN_AMBER]})"
                    reading.label.name != row.label ->
                        "${row.id}: ${row.label} -> ${reading.label.name} at margin ${reading.margin}"
                    else -> null
                }
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `the reject pile is reported as UNKNOWN, and a confident UNKNOWN is still UNKNOWN`() {
        // `UNKNOWN` is a *class*, not a failure: it is DATA.md §3's reject pile and a first-class
        // outcome that fusion turns into AMBER/`A-WORN-01`. A confident UNKNOWN is therefore the
        // best possible answer for this specimen, and it must not be "promoted" to a process.
        val classifier = ProcessClassifier(loaded().model)
        val reading = MacroGate.read(
            classifier, specimens().first { it.label == "UNKNOWN" }.toMacroFeatures(), registry,
        )
        assertEquals(ProcessLabel.UNKNOWN, reading.label)
        assertTrue(!reading.noModel)
    }

    @Test
    fun `the screen specimen is the pinned weak case and is measured, not asserted away`() {
        // A real defect in the shipped model, recorded so it cannot disappear quietly.
        //
        // The committed SCREEN specimen carries a margin of ~0.14 against a MACRO_MARGIN_AMBER of
        // 0.15, so the system **abstains** on it rather than reporting SCREEN. The cause is in the
        // generator: the screen's emission comb only exists where the document has ink, so a screen
        // showing a text zone reduces to a near-binary texture and competes with `LASER`. On the
        // held-out page, 2 of 6 crops were misread that way.
        //
        // This asserts the measurement, not a threshold. If a future corpus — real data, ideally —
        // makes SCREEN comfortable, this test fails and the fix is to update it and the model card
        // together, not to loosen an assertion. Until then the shipped behaviour is: SCREEN
        // abstains on this specimen, which is a safe failure (AMBER, not a false `SCREEN`).
        val classifier = ProcessClassifier(loaded().model)
        val amber = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
        val reading = MacroGate.read(
            classifier, specimens().first { it.id == "specimen-screen" }.toMacroFeatures(), registry,
        )
        assertTrue(
            reading.patch.margin < amber,
            "the SCREEN specimen now clears the floor at ${reading.patch.margin}; the shipped model " +
                "improved, so update this test and the model card together",
        )
        assertEquals(
            ProcessLabel.UNKNOWN, reading.label,
            "a sub-floor margin must abstain, not report a class",
        )
    }

    // ------------------------------------------------------------ abstention

    @Test
    fun `a deliberately ambiguous patch yields a low margin, not a confident answer`() {
        val classifier = ProcessClassifier(loaded().model)
        val amber = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
        val faded = specimens().first { it.id == "specimen-ambiguous-faded-offset" }
        val patch = classifier.classify(faded.toMacroFeatures())

        // The mechanism, in case the fixture is ever regenerated: a 210 lpi offset screen whose
        // dot darkness has decayed into the paper grain. The lattice is still geometrically
        // intact — pitch, angle and dot gain are untouched — and only its amplitude has fallen to
        // where it competes with the noise. There is no measurement that resolves that, so a
        // high margin here would be confidence the patch does not support.
        assertTrue(
            patch.margin < amber,
            "the faded-offset specimen scored ${patch.margin}, at or above MACRO_MARGIN_AMBER $amber",
        )
        assertTrue(patch.margin < red(), "expected a low margin, got ${patch.margin}")
    }

    @Test
    fun `the gate reports UNKNOWN for every ambiguous specimen that falls below the floor`() {
        val classifier = ProcessClassifier(loaded().model)
        val readings = specimens().filterNot { it.isClassSpecimen }
            .map { it.id to MacroGate.read(classifier, it.toMacroFeatures(), registry) }
        assertTrue(readings.isNotEmpty(), "no ambiguous specimens in the fixture dump")
        for ((id, reading) in readings) {
            if (reading.margin < registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()) {
                assertEquals(
                    ProcessLabel.UNKNOWN, reading.label,
                    "$id fell below the floor and must not be reported under a class",
                )
                assertTrue(reading.abstained, "$id fell below the floor without abstaining")
            }
        }
    }

    @Test
    fun `a screen shown through a print produces no MATCH or MISMATCH from the zone pair`() {
        // AT-04's hard case: one patch carrying two print-process signatures. The per-patch margin
        // is the first line of defence; if it clears, the second is that `DocAggregator` refuses
        // to turn two such readings into a document-level claim. Both are checked, because a
        // system that only has the first line has a confident-answer failure waiting.
        val classifier = ProcessClassifier(loaded().model)
        val rows = specimens().associate { it.id to it.toMacroFeatures() }
        val ambiguous = classifier.classify(rows.getValue("specimen-ambiguous-screen-through-offset"))
        val clean = classifier.classify(rows.getValue("specimen-offset"))
        if (ambiguous.margin < registry[ThresholdName.MACRO_MARGIN_RED].toFloat()) {
            val aggregate = dev.kasoti.factory.DocAggregator.aggregate(ambiguous, clean, registry)
            assertTrue(
                aggregate.agreement == dev.kasoti.factory.DocProcess.Agreement.UNCERTAIN,
                "a low-margin zone must not produce ${aggregate.agreement}",
            )
        }
    }

    @Test
    fun `no model at all is abstention rather than a label`() {
        // The state a device is in before the weights are provisioned. It must complete a
        // screening, not throw, and it must not invent a class.
        val reading = MacroGate.read(null as ProcessClassifier?, specimens().first().toMacroFeatures(), registry)
        assertEquals(ProcessLabel.UNKNOWN, reading.label)
        assertTrue(reading.noModel)
        assertTrue(reading.abstained)
    }

    // ------------------------------------------------------- model resolution

    @Test
    fun `resolution prefers the D-MACRO model and never silently keeps the synthetic one`() {
        // The realistic way this goes wrong: real patches land in eval/data/macro/, a real model
        // is trained, and a run still reports the synthetic number because the synthetic file is
        // still there. So the preference is asserted against a temporary tree holding both.
        val sandbox = Files.createTempDirectory("kasoti-macro-resolve")
        try {
            Files.createDirectories(sandbox.resolve("eval/models"))
            val real = SvmModelReaderFixture.realVariantOf(modelFile(), sandbox)
            val demoted = Files.readString(modelFile()).replace("\"synthetic\": true", "\"synthetic\": false")
            Files.writeString(real, demoted)
            val synthetic = sandbox.resolve(SvmModelLoader.SYNTHETIC_PATH)
            Files.writeString(synthetic, Files.readString(modelFile()))

            val both = SvmModelLoader.resolve(sandbox)
            assertEquals(SvmModelLoader.ModelResolution.REAL_PREFERRED, both.which)
            assertTrue(!both.model!!.synthetic, "resolution picked the synthetic model while a real one existed")

            val syntheticOnly = Files.createTempDirectory("kasoti-macro-synth-only")
            try {
                Files.createDirectories(syntheticOnly.resolve("eval/models"))
                Files.writeString(
                    syntheticOnly.resolve(SvmModelLoader.SYNTHETIC_PATH),
                    Files.readString(modelFile()),
                )
                val fallback = SvmModelLoader.resolve(syntheticOnly)
                assertEquals(SvmModelLoader.ModelResolution.SYNTHETIC_FALLBACK, fallback.which)
                assertTrue(fallback.model!!.synthetic)
                assertContains(fallback.which.note, "not the d-macro gate", ignoreCase = true)
            } finally {
                Files.walk(syntheticOnly).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }

            val none = Files.createTempDirectory("kasoti-macro-none")
            try {
                val absent = SvmModelLoader.resolve(none)
                assertEquals(SvmModelLoader.ModelResolution.NONE, absent.which)
                assertEquals(null, absent.model)
            } finally {
                Files.walk(none).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        } finally {
            Files.walk(sandbox).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    // ------------------------------------------------------------- fixtures

    @Test
    fun `the specimen dump is one row per class plus the ambiguous set`() {
        val rows = specimens()
        assertEquals(ProcessLabel.entries.size, SpecimenFixtures.classSpecimens(rows).size)
        assertTrue(SpecimenFixtures.ambiguousSpecimens(rows).size >= 3)
        assertEquals(
            rows.size,
            SpecimenFixtures.classSpecimens(rows).size + SpecimenFixtures.ambiguousSpecimens(rows).size,
        )
    }

    @Test
    fun `toMacroFeatures inverts toVector exactly`() {
        // The specimen dump stores the vector `MacroFeatures.toVector` produces, and
        // `toMacroFeatures` reconstructs the features from it. If that round trip is lossy, the
        // margins measured here are not the margins the device would see — and the numbers are
        // subtly wrong rather than obviously wrong, which is the worst way to be wrong.
        for (row in specimens()) {
            val features = row.toMacroFeatures()
            assertTrue(features.peakFrequency.isFinite() && features.peakFrequency in 1f..Spectrum.PATCH.toFloat())
            assertTrue(features.peakiness >= 1f, "peakiness ${features.peakiness} lost its ln1p")
            assertEquals(1.0, features.lbpBins.sum().toDouble(), 1e-4, "${row.id}: LBP does not sum to 1")
        }
    }

    @Test
    fun `a stale specimen dump is refused rather than used`() {
        val rows = specimens()
        assertTrue(SpecimenFixtures.assertFresh(rows).isEmpty())
        assertEquals(1, SpecimenFixtures.assertFresh(rows.map { it.copy(generator = "synth_macros.py@1") }).size)
        assertEquals(1, SpecimenFixtures.assertFresh(rows.map { it.copy(synthetic = false) }).size)
        assertEquals(
            1,
            SpecimenFixtures.assertFresh(rows.map { it.copy(features = it.features.dropLast(1)) }).size,
        )
        assertEquals(1, SpecimenFixtures.assertFresh(emptyList()).size)
    }

    private fun red(): Float = registry[ThresholdName.MACRO_MARGIN_RED].toFloat()

    private companion object {
        /**
         * The two specimens with their own test, because each fails a blanket assertion for a
         * stated reason: `SCREEN` is the pinned weak case (see its own test), and `UNKNOWN` is a
         * class whose *correct* answer is the label itself, so "did not abstain" is the wrong
         * question to ask about it.
         */
        val EXCLUDED_FROM_STRONG_CLAIM = setOf("SCREEN", "UNKNOWN")
    }
}

/** Puts a file at the D-MACRO path inside [sandbox]; returns where it landed. */
private object SvmModelReaderFixture {
    fun realVariantOf(source: Path, sandbox: Path): Path =
        sandbox.resolve(SvmModelLoader.REAL_PATH).also { it.parent?.let { p -> Files.createDirectories(p) } }
            .also { Files.writeString(it, Files.readString(source)) }
}
