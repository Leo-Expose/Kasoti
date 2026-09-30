package dev.kasoti.desktop

import dev.kasoti.desktop.screen.DemoSvm
import dev.kasoti.desktop.screen.SvmModelException
import dev.kasoti.desktop.screen.SvmModelFile
import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hand-written model files, in exactly the shape `train_svm.py` is expected to export.
 *
 * The malformed variants are built structurally rather than by string surgery on the valid
 * one. Mutating JSON by `replace` is a test that passes for the wrong reason: change the
 * fixture's formatting and the mutation silently becomes a no-op, and the test then asserts
 * nothing at all while still being green.
 */
internal object SvmModelFileFixture {

    private val names = SvmModelFile.expectedFeatureNames()

    /** Every class scores zero: the untrained demo stub. */
    fun untrainedStubJson(): String = model(
        version = "svm_print_demo",
        trainingRunId = DemoSvm.TRAINING_RUN_ID,
        labels = ProcessLabel.entries.map { it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
    )

    /**
     * A model that *does* discriminate: peakiness alone separates LASER from the rest, so a
     * well-formed macro patch gets a real, confident label.
     */
    fun trainedJson(trainingRunId: String = "d-macro-2026-09-holdout"): String {
        val peakinessIndex = names.indexOf("peakiness")
        val rows = ProcessLabel.entries.map { label ->
            FloatArray(MacroFeatures.TOTAL) { if (it == peakinessIndex) if (label == ProcessLabel.LASER) 6f else -1f else 0f }
        }
        return model(
            version = "svm_print_v1",
            trainingRunId = trainingRunId,
            labels = ProcessLabel.entries.map { it.name },
            featureNames = names,
            weightRows = rows,
        )
    }

    fun shortWeightVectorJson(): String = model(
        version = "svm_print_v1",
        trainingRunId = "d-macro-2026-09-holdout",
        labels = ProcessLabel.entries.map { it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL - 1) },
    )

    fun tooFewClassesJson(): String = model(
        version = "svm_print_v1",
        trainingRunId = "d-macro-2026-09-holdout",
        labels = ProcessLabel.entries.dropLast(1).map { it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size - 1) { FloatArray(MacroFeatures.TOTAL) },
    )

    fun wrongFeatureNameCountJson(): String = model(
        version = "svm_print_v1",
        trainingRunId = "d-macro-2026-09-holdout",
        labels = ProcessLabel.entries.map { it.name },
        featureNames = names.dropLast(1),
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
    )

    fun noTrainingRunIdJson(): String = model(
        version = "svm_print_v1",
        trainingRunId = null,
        labels = ProcessLabel.entries.map { it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
    )

    fun unknownClassJson(): String = model(
        version = "svm_print_v1",
        trainingRunId = "d-macro-2026-09-holdout",
        labels = ProcessLabel.entries.map { if (it == ProcessLabel.OFFSET) "GRAVURE" else it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
    )

    /**
     * Build a model file in the shape `train_svm.py` actually writes.
     *
     * The numbers are emitted as **plain JSON numbers**, with no Kotlin `f` suffix. They used to
     * carry one, and `kotlinx.serialization` accepted it, so the fixture looked right. `:core`'s
     * canonical reader is strict — it has to be, because `json.load` on the trainer side does not
     * accept a float suffix either, and a reader that accepts a file the writer cannot produce
     * means two different files share one name. `Json.parseToJsonElement`'s leniency about
     * trailing `f` is a JVM-flavoured tolerance, not part of the format.
     */
    private fun model(
        version: String,
        trainingRunId: String?,
        labels: List<String>,
        featureNames: List<String>,
        weightRows: List<FloatArray>,
        bias: List<Float> = List(labels.size) { 0f },
    ): String = buildString {
        appendLine("{")
        appendLine("""  "version": "$version",""")
        if (trainingRunId != null) appendLine("""  "trainingRunId": "$trainingRunId",""")
        appendLine("""  "labels": [${labels.joinToString(",") { "\"$it\"" }}],""")
        appendLine("""  "featureNames": [${featureNames.joinToString(",") { "\"$it\"" }}],""")
        appendLine("""  "weights": [${weightRows.joinToString(",") { row -> row.joinToString(",", "[", "]") }}],""")
        appendLine("""  "bias": [${bias.joinToString(",")}]""")
        appendLine("}")
    }

    /**
     * A model that confidently calls every patch `OFFSET`.
     *
     * Not a trained classifier — a *fixture*. Its only job is to give the macro layer a
     * high, non-zero margin on both zones so the document-level reading is a clean MATCH
     * and a test about, say, an MRZ check digit is not drowned out by a spurious
     * photo-zone/text-zone disagreement. Real training data belongs to the eval corpus.
     */
    fun confidentAgreeingModelJson(): String = model(
        version = "fixture-offset-v0",
        trainingRunId = "fixture-not-trained",
        labels = ProcessLabel.entries.map { it.name },
        featureNames = names,
        weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
        bias = ProcessLabel.entries.map { if (it == ProcessLabel.OFFSET) 8f else 0f },
    )
}

/**
 * The SVM artefact reader (DESIGN.md §6).
 *
 * The property that matters is refusal: a model file that cannot be traced to a tuning run,
 * or whose shape is wrong, must stop the screening. A silently-defaulted classifier would
 * produce confident-looking process labels with no provenance, and those labels are what
 * thresholds get tuned against.
 */
class SvmModelFileTest {

    @Test
    fun `a well-formed model loads`() {
        val model = SvmModelFile.parse(SvmModelFileFixture.trainedJson())
        assertEquals("svm_print_v1", model.version)
        assertEquals(ProcessLabel.entries.size, model.labels.size)
        assertEquals("d-macro-2026-09-holdout", model.trainingRunId)
        assertEquals(MacroFeatures.TOTAL, model.featureNames.size)
        assertEquals(ProcessLabel.entries.size, model.weights.size)
    }

    @Test
    fun `the demo stub is refused unless demo mode is on`() {
        val stub = SvmModelFile.parse(SvmModelFileFixture.untrainedStubJson())
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.requireUsable(stub, demoMode = false) }
        assertTrue(failure.message!!.contains("--demo"))
        SvmModelFile.requireUsable(stub, demoMode = true)
    }

    @Test
    fun `a real model is usable without demo mode`() {
        SvmModelFile.requireUsable(SvmModelFile.parse(SvmModelFileFixture.trainedJson()), demoMode = false)
    }

    @Test
    fun `a model without a training run id is refused`() {
        val failure = assertFailsWith<SvmModelException> {
            SvmModelFile.parse(SvmModelFileFixture.noTrainingRunIdJson())
        }
        assertTrue(failure.message!!.contains("trainingRunId"))
    }

    @Test
    fun `a model with the wrong number of feature names is refused`() {
        val failure = assertFailsWith<SvmModelException> {
            SvmModelFile.parse(SvmModelFileFixture.wrongFeatureNameCountJson())
        }
        assertTrue(failure.message!!.contains("feature names"))
    }

    @Test
    fun `a model with a short weight vector is refused`() {
        val failure = assertFailsWith<SvmModelException> {
            SvmModelFile.parse(SvmModelFileFixture.shortWeightVectorJson())
        }
        assertTrue(failure.message!!.contains("weights[0]"))
    }

    @Test
    fun `a model naming an unknown process class is refused`() {
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.parse(SvmModelFileFixture.unknownClassJson()) }
        assertTrue(failure.message!!.contains("GRAVURE"))
    }

    @Test
    fun `a model with the wrong class count is refused`() {
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.parse(SvmModelFileFixture.tooFewClassesJson()) }
        assertTrue(failure.message!!.contains("classes"))
    }

    @Test
    fun `a non-numeric weight is refused rather than read as zero`() {
        val broken = SvmModelFileFixture.trainedJson().replaceFirst("[0.0,", "[\"zero\",")
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.parse(broken) }
        assertTrue(failure.message!!.contains("not a number"))
    }

    @Test
    fun `a file that is not JSON is refused`() {
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.parse("this is not json") }
        assertTrue(failure.message!!.contains("not valid JSON"))
    }

    @Test
    fun `a JSON array where an object was expected is refused`() {
        val failure = assertFailsWith<SvmModelException> { SvmModelFile.parse("[]") }
        assertTrue(failure.message!!.contains("not a JSON object"))
    }

    @Test
    fun `the expected feature names match the core feature vector order`() {
        val names = SvmModelFile.expectedFeatureNames()
        assertEquals(MacroFeatures.TOTAL, names.size)
        assertEquals("peakFrequency", names[0])
        assertEquals("peakiness", names[1])
        assertEquals("edgeDensity", names[6])
        assertEquals("lbp0", names[7])
    }
}
