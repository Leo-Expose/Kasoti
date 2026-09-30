package dev.kasoti.factory

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The model reader (DESIGN.md §6) and the abstention gate (FR-F1).
 *
 * ## The property under test is refusal, not parsing
 *
 * A model file is a list of numbers with no self-describing labels on the numbers themselves, and
 * the wrong numbers still score a plausible value for every patch. So the question is not "does it
 * read" but "does it refuse everything it should". Each malformed fixture below is built
 * structurally rather than by string surgery on a valid file: `replace` is a mutation that goes
 * silent the moment the fixture is reformatted, and the test then asserts nothing while staying
 * green.
 *
 * ## The gate's property is the opposite
 *
 * A gate that abstains too often is merely unhelpful. A gate that abstains too rarely produces a
 * confident process label on a patch that supports none — and `DocAggregator` will then build a
 * MATCH out of two such labels, which is the `R-PROC-02` false-RED path. The tests below pin the
 * floor from the registry rather than from a literal, so a threshold change cannot quietly move it.
 */
internal object SvmModelFixture {

    val LABELS = ProcessLabel.entries.map { it.name }

    fun names(): List<String> = SvmModelReader.expectedFeatureNames()

    /** Every class scores zero: the untrained demo stub, and the shape `:core` hands out. */
    fun validJson(
        version: String = "svm_print_v1",
        runId: String? = "test-run-0001",
        labels: List<String> = LABELS,
        featureNames: List<String> = names(),
        weightRows: List<FloatArray> = List(labels.size) { FloatArray(MacroFeatures.TOTAL) },
        bias: List<Float> = List(labels.size) { 0f },
        extra: String = "",
    ): String {
        val lines = mutableListOf(
            "{",
            """  "version": "$version",""",
        )
        if (runId != null) lines += """  "trainingRunId": "$runId","""
        lines += """  "labels": [${labels.joinToString(",") { "\"$it\"" }}],"""
        lines += """  "featureNames": [${featureNames.joinToString(",") { "\"$it\"" }}],"""
        lines += """  "weights": [${weightRows.joinToString(",") { row -> row.joinToString(",", "[", "]") }}],"""
        lines += """  "bias": [${bias.joinToString(",")}]"""
        if (extra.isNotEmpty()) {
            // `extra` is inserted *before* the closing brace, without a trailing comma of its own,
            // so a caller cannot produce a file that is valid Kotlin and invalid JSON.
            lines[lines.lastIndex] += ","
            lines += extra
        }
        lines += "}"
        return lines.joinToString("\n") + "\n"
    }
}

class SvmModelReaderTest {

    private val origin = "test-model.json"

    @Test
    fun `a well-formed model loads with its provenance intact`() {
        val loaded = SvmModelReader.parse(
            SvmModelFixture.validJson(
                extra = """"synthetic": true,
  "splitPolicy": "70/15/15 by source document (EVAL.md §2)",
  "trainedOn": "SYNTHETIC corpus",
  "sourceDocCount": 336""",
            ),
            origin,
        )
        assertEquals("svm_print_v1", loaded.model.version)
        assertEquals("test-run-0001", loaded.model.trainingRunId)
        assertEquals(ProcessLabel.entries.toList(), loaded.model.labels)
        assertEquals(MacroFeatures.TOTAL, loaded.model.featureNames.size)
        assertEquals(ProcessLabel.entries.size, loaded.model.weights.size)
        assertTrue(loaded.synthetic)
        assertEquals(336, loaded.sourceDocCount)
        // The word has to reach the operator-facing string, not just the field.
        assertTrue(loaded.provenance.contains("SYNTHETIC"))
    }

    @Test
    fun `a real model does not claim to be synthetic`() {
        val loaded = SvmModelReader.parse(SvmModelFixture.validJson(), origin)
        assertEquals(false, loaded.synthetic)
        assertTrue(!loaded.provenance.contains("SYNTHETIC"))
    }

    @Test
    fun `weight rows follow ProcessLabel order however the file is ordered`() {
        // A permuted file is not a broken file — it is a file whose row i belongs to a different
        // class. Reordering on load is what stops that from becoming a model that scores.
        val canonical = SvmModelReader.parse(SvmModelFixture.validJson(), origin)
        val permuted = SvmModelFixture.validJson(
            labels = SvmModelFixture.LABELS.reversed(),
            weightRows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) { i -> (it + 1).toFloat() } }
                .reversed(),
            bias = List(ProcessLabel.entries.size) { it.toFloat() }.reversed(),
        )
        val loaded = SvmModelReader.parse(permuted, origin)
        assertEquals(canonical.model.labels, loaded.model.labels)
        // After reordering, the weight for the *first* enum entry is the one the file put first.
        assertEquals(1f, loaded.model.weights[ProcessLabel.entries.indexOf(ProcessLabel.entries[0])][0])
        assertEquals(7f, loaded.model.weights[ProcessLabel.entries.indexOf(ProcessLabel.entries[6])][0])
    }

    @Test
    fun `a model with no training run id is refused`() {
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(SvmModelFixture.validJson(runId = null), origin)
        }
        assertTrue(failure.message!!.contains("trainingRunId"), failure.message!!)
    }

    @Test
    fun `a model with the wrong number of feature names is refused`() {
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(SvmModelFixture.validJson(featureNames = SvmModelFixture.names().dropLast(1)), origin)
        }
        assertTrue(failure.message!!.contains("feature names"), failure.message!!)
    }

    @Test
    fun `a model whose feature names are permuted is refused`() {
        // The dangerous one. The count is right, so a loader that trusted the names would score
        // every patch over a shuffled vector and produce a number shaped like a metric.
        val names = SvmModelFixture.names().toMutableList()
        val swap = names[2]
        names[2] = names[3]
        names[3] = swap
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(SvmModelFixture.validJson(featureNames = names), origin)
        }
        assertTrue(failure.message!!.contains("toVector() order"), failure.message!!)
    }

    @Test
    fun `the snake_case spelling the trainer writes is accepted`() {
        val snake = listOf(
            "peak_frequency", "peakiness", "band_energy", "high_frequency_energy",
            "spectral_slope", "std_dev", "edge_density",
        ) + (0 until MacroFeatures.LBP_BINS).map { "lbp_%02d".format(it) }
        val loaded = SvmModelReader.parse(SvmModelFixture.validJson(featureNames = snake), origin)
        assertEquals(MacroFeatures.TOTAL, loaded.model.featureNames.size)
    }

    @Test
    fun `a model with a short weight vector is refused by index`() {
        val rows = List(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL - 1) }
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(SvmModelFixture.validJson(weightRows = rows), origin)
        }
        assertTrue(failure.message!!.contains("weights[0]"), failure.message!!)
    }

    @Test
    fun `a model with the wrong class count is refused`() {
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(
                SvmModelFixture.validJson(
                    labels = SvmModelFixture.LABELS.dropLast(1),
                    weightRows = List(ProcessLabel.entries.size - 1) { FloatArray(MacroFeatures.TOTAL) },
                ),
                origin,
            )
        }
        assertTrue(failure.message!!.contains("classes"), failure.message!!)
    }

    @Test
    fun `a model naming an unknown process class is refused`() {
        val labels = SvmModelFixture.LABELS.map { if (it == "OFFSET") "GRAVURE" else it }
        val failure = assertFailsWith<SvmModelFormatException> {
            SvmModelReader.parse(SvmModelFixture.validJson(labels = labels), origin)
        }
        assertTrue(failure.message!!.contains("GRAVURE"), failure.message!!)
    }

    @Test
    fun `a non-numeric weight is refused rather than read as zero`() {
        val broken = SvmModelFixture.validJson().replaceFirst("0.0,", "\"zero\",")
        val failure = assertFailsWith<SvmModelFormatException> { SvmModelReader.parse(broken, origin) }
        assertTrue(failure.message!!.contains("not a number"), failure.message!!)
    }

    @Test
    fun `a weight too large for a float is refused instead of becoming an infinity`() {
        // `1.0e400` is a legal JSON number and parses to a finite *Double*, so it gets past the
        // syntax check and all the way to the narrowing — which is the only place it can be
        // caught. Left alone it would become an infinite weight, and an infinite score has a
        // non-finite softmax whose every comparison is false, so the argmax would return the
        // first class at full confidence.
        val broken = SvmModelFixture.validJson().replaceFirst("[0.0,0.0,0.0", "[1.0e400,0.0,0.0")
        val failure = assertFailsWith<SvmModelFormatException> { SvmModelReader.parse(broken, origin) }
        assertTrue(failure.message!!.contains("finite float"), failure.message!!)
    }

    @Test
    fun `NaN and Infinity literals are refused as invalid JSON, not narrowed`() {
        // `kotlinx.serialization` is lenient about a trailing float suffix but not about these,
        // and neither is `json.load` in the trainer. Accepting them here would mean this reader
        // and the trainer do not agree on which files exist.
        for (literal in listOf("NaN", "Infinity", "-Infinity")) {
            val broken = SvmModelFixture.validJson().replaceFirst("[0.0,0.0,0.0", "[$literal,0.0,0.0")
            val failure = assertFailsWith<SvmModelFormatException>("accepted $literal") {
                SvmModelReader.parse(broken, origin)
            }
            assertTrue(failure.message!!.contains("not valid JSON"), failure.message!!)
        }
    }

    @Test
    fun `input that is not JSON is refused`() {
        val failure = assertFailsWith<SvmModelFormatException> { SvmModelReader.parse("this is not json", origin) }
        assertTrue(failure.message!!.contains("not valid JSON"), failure.message!!)
    }

    @Test
    fun `a JSON array where an object was expected is refused`() {
        val failure = assertFailsWith<SvmModelFormatException> { SvmModelReader.parse("[]", origin) }
        assertTrue(failure.message!!.contains("not a JSON object"), failure.message!!)
    }

    @Test
    fun `lenient JSON is refused so this reader and the trainer agree on the file format`() {
        // Trailing comma and a comment. `kotlinx.serialization` in lenient mode and several other
        // parsers accept at least one of these; accepting either here would mean two files with
        // one name.
        for (text in listOf("""{"a": 1,}""", """{/* hi */ "a": 1}""", """{'a': 1}""")) {
            assertFailsWith<SvmModelFormatException>("accepted lenient JSON: $text") {
                SvmModelReader.parse(text, origin)
            }
        }
    }

    @Test
    fun `duplicate keys are refused`() {
        val text = SvmModelFixture.validJson().replaceFirst("\"version\"", "\"bias\": [0.0],\n  \"version\"")
        assertFailsWith<SvmModelFormatException> { SvmModelReader.parse(text, origin) }
    }

    @Test
    fun `the reader agrees with SvmModel's own requirements`() {
        // If `:core` ever changes the feature width, the reader's fixtures would keep passing
        // while the shipped model stopped loading. This is the assertion that notices.
        val loaded = SvmModelReader.parse(SvmModelFixture.validJson(), origin)
        assertEquals(MacroFeatures.TOTAL, loaded.model.weights[0].size)
        assertEquals(ProcessLabel.entries.size, loaded.model.weights.size)
        assertEquals(ProcessLabel.entries.size, loaded.model.bias.size)
        assertNotEquals(0, SvmModelReader.expectedFeatureNames().size)
    }
}

/**
 * The abstention gate.
 *
 * Driven from a hand-built feature vector rather than an image so the *decision boundary* is the
 * thing under test. The floor comes from the registry, so `MACRO_MARGIN_AMBER` is the single
 * definition (AGENTS.md §2: no magic numbers outside the registry) and a threshold change moves
 * this test with it rather than breaking it.
 */
class MacroGateTest {

    private val registry = ThresholdRegistry.defaults(version = "test", runId = "gate-test")

    /** A model that scores on peakiness alone: high for the first class, low for the rest. */
    private fun peakinessModel(scale: Float): ProcessClassifier = ProcessClassifier(
        SvmModel(
            version = "fixture-peakiness-v0",
            labels = ProcessLabel.entries.toList(),
            weights = Array(ProcessLabel.entries.size) { index ->
                FloatArray(MacroFeatures.TOTAL) { if (it == 1) if (index == 0) scale else -scale else 0f }
            },
            bias = FloatArray(ProcessLabel.entries.size),
            featureNames = SvmModelReader.expectedFeatureNames(),
            trainingRunId = "fixture-not-trained",
        ),
    )

    private fun features(peakiness: Float) = MacroFeatures(
        peakFrequency = 16f,
        peakiness = peakiness,
        bandEnergy = 0.5f,
        highFrequencyEnergy = 0.01f,
        spectralSlope = -3f,
        stdDev = 0.3f,
        edgeDensity = 0.5f,
        lbpBins = FloatArray(MacroFeatures.LBP_BINS) { 1f / MacroFeatures.LBP_BINS },
    )

    @Test
    fun `a confident patch is reported under its own label`() {
        val reading = MacroGate.read(peakinessModel(scale = 8f), features(peakiness = 40f), registry)
        assertEquals(ProcessLabel.OFFSET, reading.label)
        assertTrue(!reading.abstained)
        assertTrue(!reading.noModel)
        assertEquals(reading.patch.label, reading.label)
    }

    @Test
    fun `a patch below the amber floor abstains to UNKNOWN rather than naming a class`() {
        // Scale chosen so the softmax margin lands just under MACRO_MARGIN_AMBER. The point is
        // not the number: it is that a low margin produces UNKNOWN and never a class name.
        val reading = MacroGate.read(peakinessModel(scale = 0.2f), features(peakiness = 40f), registry)
        assertTrue(
            reading.patch.margin < registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat(),
            "margin ${reading.margin}",
        )
        assertEquals(ProcessLabel.UNKNOWN, reading.label)
        assertTrue(reading.abstained)
        // The measurement is kept, not discarded: a report can show what the model said *and*
        // that the system is not claiming it.
        assertNotEquals(ProcessLabel.UNKNOWN, reading.patch.label)
    }

    @Test
    fun `the abstention boundary is the registry's value, not a literal in this file`() {
        // AGENTS.md §2: a tunable lives in the registry, not in a magic number. A test that
        // hard-coded 0.15 would keep passing after somebody moved the threshold, which is exactly
        // the drift the registry exists to prevent. So: one model, one patch, three registries.
        val confident = peakinessModel(scale = 1.1f).classify(features(40f))
        val amber = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
        val margin = confident.margin
        assertTrue(margin > amber, "fixture margin $margin should sit above the shipped $amber")

        val strict = registry.withValue(ThresholdName.MACRO_MARGIN_AMBER, (margin + 0.05).toDouble())
        val loose = registry.withValue(ThresholdName.MACRO_MARGIN_AMBER, (margin - 0.05).toDouble())
        assertTrue(
            MacroGate.read(peakinessModel(scale = 1.1f), features(40f), strict).abstained,
            "a margin of $margin must abstain under a floor of ${strict[ThresholdName.MACRO_MARGIN_AMBER]}",
        )
        val looseReading = MacroGate.read(peakinessModel(scale = 1.1f), features(40f), loose)
        assertTrue(
            !looseReading.abstained && looseReading.label != ProcessLabel.UNKNOWN,
            "the same patch must be reported under a floor of ${loose[ThresholdName.MACRO_MARGIN_AMBER]}",
        )
        // And the low-margin patch abstains under the shipped default, which is the case the
        // product actually runs.
        assertTrue(
            MacroGate.read(peakinessModel(scale = 0.2f), features(40f), registry).abstained,
            "a low-margin patch must abstain under the shipped MACRO_MARGIN_AMBER of $amber",
        )
    }

    @Test
    fun `no model is abstention, and it is distinguishable from a low margin`() {
        val reading = MacroGate.read(null as ProcessClassifier?, features(40f), registry)
        assertEquals(ProcessLabel.UNKNOWN, reading.label)
        assertTrue(reading.abstained)
        assertTrue(reading.noModel)
        assertEquals(0f, reading.margin)
        assertEquals(0f, reading.patch.features.peakFrequency)
    }

    @Test
    fun `a model that says UNKNOWN is never promoted to a class, however confident`() {
        // A patch the model *positively* identifies as the reject pile is not a low-margin
        // reading. It is a confident UNKNOWN, which is the right answer and must survive the
        // gate — turning it into a low-margin abstention would lose the distinction that tells
        // fusion "no idea" apart from "confidently not a print".
        val model = ProcessClassifier(
            SvmModel(
                version = "fixture-unknown-v0",
                labels = ProcessLabel.entries.toList(),
                weights = Array(ProcessLabel.entries.size) { index ->
                    val own = if (index == ProcessLabel.entries.indexOf(ProcessLabel.UNKNOWN)) 9f else -9f
                    FloatArray(MacroFeatures.TOTAL) { if (it == 1) own else 0f }
                },
                bias = FloatArray(ProcessLabel.entries.size),
                featureNames = SvmModelReader.expectedFeatureNames(),
                trainingRunId = "fixture-not-trained",
            ),
        )
        val reading = MacroGate.read(model, features(1.05f), registry)
        assertEquals(ProcessLabel.UNKNOWN, reading.label)
        assertEquals(ProcessLabel.UNKNOWN, reading.patch.label)
        assertTrue(reading.patch.margin > registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat())
        // The system is still not claiming a process — that is what `abstained` means — but the
        // *reason* is visible in the measurement: the model committed to UNKNOWN rather than
        // running out of confidence. Fusion's A-WORN-01 path reads the label, and a report that
        // showed "abstained, margin 0.52" would be describing a different event from "abstained,
        // margin 0.02".
        assertTrue(reading.abstained)
        assertTrue(!reading.noModel)
    }

}
