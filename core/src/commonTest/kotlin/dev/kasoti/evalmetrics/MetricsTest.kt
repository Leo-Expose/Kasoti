package dev.kasoti.evalmetrics

import dev.kasoti.face.PairLabel
import dev.kasoti.face.PairTrial
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Verdict
import dev.kasoti.mrz.MrzCorpus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Metric arithmetic against hand-computed values (EVAL.md §4).
 *
 * Every number below is computed by hand, not copied from a run. A metric whose only test is
 * "the harness printed 0.87" is a metric nobody has checked, and EVAL.md §1 makes these the
 * numbers slides are allowed to quote.
 */
class MetricsTest {

    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ------------------------------------------------------------------ classification

    @Test
    fun `per-class precision and recall on a hand-checked confusion matrix`() {
        // actual x predicted:  A->A  A->A  A->B  B->A  B->B  C->C
        val pairs = listOf(
            "A" to "A",
            "A" to "A",
            "A" to "B",
            "B" to "A",
            "B" to "B",
            "C" to "C",
        )
        val metrics = Classification.of(pairs, labels = listOf("A", "B", "C"))

        // A: tp = 2, predicted 3 (its own 2 plus B's miss), actual 3.
        val a = metrics.perClass.first { it.label == "A" }
        assertEquals(3, a.support)
        assertEquals(3, a.predicted)
        close(2.0 / 3.0, a.precision)
        close(2.0 / 3.0, a.recall)
        close(2.0 / 3.0, a.f1)

        // B: tp = 1, predicted 2 (its own plus A's miss), actual 2.
        val b = metrics.perClass.first { it.label == "B" }
        assertEquals(2, b.support)
        assertEquals(1, b.falsePositive)
        assertEquals(1, b.falseNegative)
        close(0.5, b.precision)
        close(0.5, b.recall)
        close(0.5, b.f1)

        // C: perfect.
        val c = metrics.perClass.first { it.label == "C" }
        assertEquals(1, c.support)
        close(1.0, c.precision)
        close(1.0, c.recall)
        close(1.0, c.f1)

        assertEquals(6, metrics.n)
        close(4.0 / 6.0, metrics.accuracy)
        close((2.0 / 3.0 + 0.5 + 1.0) / 3.0, metrics.macroPrecision)
        close((2.0 / 3.0 + 0.5 + 1.0) / 3.0, metrics.macroRecall)
        close((2.0 / 3.0 + 0.5 + 1.0) / 3.0, metrics.macroF1)
    }

    @Test
    fun `the confusion matrix holds every cell including the off-diagonal ones`() {
        val metrics = Classification.of(
            listOf("A" to "A", "A" to "B", "B" to "B", "C" to "B"),
            labels = listOf("A", "B", "C"),
        )
        assertEquals(listOf("A", "B", "C"), metrics.matrix.labels)
        assertEquals(listOf(listOf(1, 1, 0), listOf(0, 1, 0), listOf(0, 1, 0)), metrics.matrix.rows)
        assertEquals(1, metrics.matrix.count("A", "A"))
        assertEquals(1, metrics.matrix.count("C", "B"))
        assertEquals(0, metrics.matrix.count("C", "C"))
    }

    @Test
    fun `an undefined rate is zero, not one`() {
        val metrics = Classification.of(listOf("A" to "A"), labels = listOf("A", "B"))
        val b = metrics.perClass.first { it.label == "B" }
        assertEquals(0, b.support)
        assertEquals(0.0, b.precision, "a class that was never predicted has no precision")
        assertEquals(0.0, b.recall)
        assertEquals(1.0, metrics.macroF1, "an unused label must not drag the macro average down")
    }

    @Test
    fun `the recall floor decides RED-use and names the classes that fail it`() {
        val metrics = Classification.of(
            listOf("A" to "A", "A" to "A", "B" to "A", "B" to "A"),
            labels = listOf("A", "B"),
        )
        close(1.0, metrics.recallOf("A"))
        close(0.0, metrics.recallOf("B"))
        close(0.0, metrics.minRecall)
        assertFalse(metrics.meetsRecallFloor(0.85))
        assertEquals(listOf("B"), metrics.belowRecallFloor(0.85))
        assertTrue(Classification.of(listOf("A" to "A"), labels = listOf("A")).meetsRecallFloor(0.85))
    }

    @Test
    fun `an empty corpus is not a perfect classifier`() {
        val metrics = Classification.of(emptyList(), labels = listOf("A", "B"))
        assertEquals(0, metrics.n)
        assertEquals(0.0, metrics.accuracy)
        assertEquals(0.0, metrics.macroF1)
        assertFalse(metrics.meetsRecallFloor(0.0), "a missing measurement never clears a floor")
    }

    @Test
    fun `an undeclared label cannot vanish from the matrix`() {
        val metrics = Classification.of(listOf("A" to "Z"))
        assertEquals(listOf("A", "Z"), metrics.matrix.labels)
    }

    // ------------------------------------------------------------------ TAR@FAR

    /**
     * Impostors and genuine scores are exact multiples of 1/1024, so every count below is
     * hand-checkable and free of float drift.
     *
     * 246 impostors from 0.2002 to 0.4395, 38 high genuine pairs, one genuine at 0.4385 (just
     * above the third-highest impostor) and one at 0.25.
     */
    private fun faceTrials(): List<PairTrial> =
        (205..450).map { PairTrial(PairLabel.IMPOSTOR, it / 1024f) } +
            (620..657).map { PairTrial(PairLabel.GENUINE, it / 1024f) } +
            listOf(PairTrial(PairLabel.GENUINE, 449 / 1024f), PairTrial(PairLabel.GENUINE, 0.25f))

    @Test
    fun `TAR at a looser FAR budget is never lower than at a stricter one`() {
        val evaluation = FaceMetrics.evaluate(faceTrials())
        val at1pct = evaluation.sweep.first { it.farTarget == 0.01 }
        val at01pct = evaluation.sweep.first { it.farTarget == 0.001 }

        // 2/246 impostors above 0.4385 is inside a 1% budget and 39 of 40 genuine pairs match.
        // The rates are Float divisions inside Detection, hence the 1e-6 tolerance.
        close(39.0 / 40.0, at1pct.tar, 1e-6)
        close(2.0 / 246.0, at1pct.far, 1e-6)
        close(1.0 / 40.0, at1pct.frr, 1e-6)
        // A 0.1% budget admits no impostor at all, which costs the marginal genuine pair.
        close(38.0 / 40.0, at01pct.tar, 1e-6)
        close(0.0, at01pct.far, 1e-6)
        close(2.0 / 40.0, at01pct.frr, 1e-6)

        assertTrue(at1pct.tar > at01pct.tar, "a looser budget cannot be worse")
        assertTrue(at1pct.far <= 0.01, "the 1% point respects its budget")
        assertTrue(at01pct.far <= 0.001, "the 0.1% point respects its budget")
        assertEquals(at01pct, evaluation.operatingPoint, "the operating point is the strictest budget")
        assertEquals(40, evaluation.genuineTrials)
        assertEquals(246, evaluation.impostorTrials)
    }

    @Test
    fun `the SPEC face gate turns on the measured FRR`() {
        val evaluation = FaceMetrics.evaluate(faceTrials())
        close(0.05, evaluation.frrAtOperatingPoint, 1e-6)
        assertTrue(SpecGates.faceFrrFloor(evaluation, maxFrR = 0.06).pass)
        assertFalse(SpecGates.faceFrrFloor(evaluation, maxFrR = 0.04).pass)
        assertTrue(SpecGates.faceFrrFloor(evaluation, maxFrR = 0.05).measured.contains("FAR 0.000%"))
    }

    @Test
    fun `per-bucket rates are reported and the spread is flagged rather than hidden`() {
        // The 0.1% operating threshold has to clear the nearest impostor, which leaves the
        // two low-scoring genuine pairs unmatched. That is the per-bucket spread FUSION.md §6
        // refuses to let be averaged into a single headline number.
        val evaluation = FaceMetrics.evaluate(
            faceTrials().map {
                val low = it.label == PairLabel.GENUINE && it.similarity < 620 / 1024f
                it.copy(bucket = if (low) "bucket-b" else "bucket-a")
            },
        )
        assertEquals(listOf("bucket-a", "bucket-b"), evaluation.perBucket.map { it.bucket })
        close(0.0, evaluation.perBucket.first { it.bucket == "bucket-a" }.frr, 1e-6)
        close(1.0, evaluation.perBucket.first { it.bucket == "bucket-b" }.frr, 1e-6)
        assertEquals(38, evaluation.perBucket.first { it.bucket == "bucket-a" }.genuine)
        assertEquals(0, evaluation.perBucket.first { it.bucket == "bucket-b" }.impostor)
        assertTrue(evaluation.bucketGapExceedsPolicy, "FUSION.md §6 wants this read aloud, not filed")
    }

    @Test
    fun `a one-sided trial set produces no operating point rather than a flattering one`() {
        val onlyGenuine = FaceMetrics.evaluate(listOf(PairTrial(PairLabel.GENUINE, 0.9f)))
        assertEquals(0, onlyGenuine.impostorTrials)
        assertEquals(0.0, onlyGenuine.operatingPoint.tar)
        assertFalse(SpecGates.faceFrrFloor(onlyGenuine, 0.05).pass, "a gate cannot pass on a missing class")
    }

    // ------------------------------------------------------------------ liveness

    @Test
    fun `APCER and BPCER are error rates over their own populations`() {
        val metrics = Liveness.evaluate(
            attackScores = listOf(0.9, 0.8, 0.6, 0.2, 0.1),
            bonaFideScores = listOf(0.9, 0.8, 0.7, 0.3),
            threshold = 0.5,
        )
        close(3.0 / 5.0, metrics.apcer)
        close(1.0 / 4.0, metrics.bpcer)
        assertTrue(metrics.publishable)
    }

    @Test
    fun `an empty spoof set is not a perfect liveness score`() {
        val metrics = Liveness.evaluate(emptyList(), emptyList(), 0.5)
        assertEquals(0.0, metrics.apcer)
        assertFalse(metrics.publishable, "zero samples must never read as a pass")
    }

    // ------------------------------------------------------------------ diary

    @Test
    fun `a diary scenario passes only on the verdict and the pinned codes`() {
        val metrics = DiaryMetrics.scenarios(
            listOf(
                ScenarioOutcome(
                    "alias-1", Verdict.RED, Verdict.RED,
                    expectedCodes = setOf(FindingCode.R_ALIAS_01),
                    codes = setOf(FindingCode.R_ALIAS_01),
                ),
                ScenarioOutcome(
                    "alias-2", Verdict.AMBER, Verdict.RED,
                    expectedCodes = setOf(FindingCode.A_WL_01),
                    codes = setOf(FindingCode.R_ALIAS_01),
                ),
                ScenarioOutcome(
                    "travel-1", Verdict.RED, Verdict.RED,
                    expectedCodes = setOf(FindingCode.R_TRAV_01),
                    codes = setOf(FindingCode.R_TRAV_01),
                ),
                ScenarioOutcome(
                    "travel-2", Verdict.RED, Verdict.RED,
                    expectedCodes = setOf(FindingCode.R_TRAV_01),
                    codes = setOf(FindingCode.R_FACE_01),
                ),
            ),
        )
        assertEquals(4, metrics.total)
        assertEquals(2, metrics.passed)
        assertEquals(2, metrics.failed)
        close(0.5, metrics.passRate)
        assertEquals(listOf("alias-2", "travel-2"), metrics.failures.map { it.id })
    }

    @Test
    fun `rank-1 retrieval counts a near tie as a miss`() {
        val metrics = DiaryMetrics.retrieval(
            probes = listOf(
                RetrievalProbe("p1", "evt_true", listOf(RetrievalHit("evt_true", 0.90), RetrievalHit("evt_other", 0.89))),
                RetrievalProbe("p2", "evt_true", listOf(RetrievalHit("evt_true", 0.80), RetrievalHit("evt_other", 0.40))),
                RetrievalProbe("p3", "evt_true", listOf(RetrievalHit("evt_other", 0.95), RetrievalHit("evt_true", 0.60))),
            ),
            gallerySize = 10_000,
            deltaRequired = 0.08,
        )
        assertEquals(10_000, metrics.gallerySize)
        close(2.0 / 3.0, metrics.rank1Rate)
        close(1.0 / 3.0, metrics.actionableRate, 1e-9)
        assertEquals(1, metrics.outcomes[0].rankOfTruth)
        assertEquals(1, metrics.outcomes[1].rankOfTruth)
        assertEquals(2, metrics.outcomes[2].rankOfTruth)
        close(1.0, metrics.medianRank)
    }

    @Test
    fun `a truth that is not in the returned page ranks last, not first`() {
        val metrics = DiaryMetrics.retrieval(
            probes = listOf(RetrievalProbe("p1", "evt_true", listOf(RetrievalHit("evt_a", 0.9), RetrievalHit("evt_b", 0.8)))),
            gallerySize = 10_000,
            deltaRequired = 0.08,
        )
        assertEquals(Int.MAX_VALUE, metrics.outcomes.single().rankOfTruth)
        assertEquals(0.0, metrics.rank1Rate)
    }

    // ------------------------------------------------------------------ mutate-catch

    @Test
    fun `mutate-catch rates only the detectable rows and publishes the blind ones`() {
        val year = 2026
        val cases = MrzCorpus.generate(seed = 7L, count = 400, referenceYear = year)
        val report = MutateCatch.evaluate(cases, referenceYear = year)

        assertTrue(report.publishable)
        assertEquals(1.0, report.catchRate, "every provably detectable mutant is caught")
        assertTrue(report.precise, "a valid document must never be flagged: ${report.falsePositives}")
        assertEquals(cases.size, report.detectable + report.blindCount + report.cleanCases)
        assertTrue(report.blindCount > 0, "TD1 has no composite check digit, so blind rows must exist")
        assertTrue(report.blindReasons.isNotEmpty(), "every blind row carries a reason")
        assertTrue(report.blindCases.all { it.reason.isNotBlank() })
        assertTrue(SpecGates.mrzMutateCatch(report).pass)
        assertTrue(SpecGates.blindSpots(report).pass, "the blind count is published, which is the bar")
    }

    @Test
    fun `an empty corpus is not a 100 percent catch rate`() {
        val report = MutateCatch.evaluate(emptyList(), referenceYear = 2026)
        assertEquals(0.0, report.catchRate)
        assertFalse(report.publishable)
        assertFalse(SpecGates.mrzMutateCatch(report).pass)
    }

    // ------------------------------------------------------------------ percentiles

    @Test
    fun `percentiles on known inputs`() {
        val even = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(2.5, Percentiles.median(even))
        assertEquals(3.85, Percentiles.p95(even), 1e-9, "0.95 * 3 = 2.85, between the 3rd and 4th")

        val odd = listOf(4.0, 1.0, 3.0, 2.0, 100.0)
        assertEquals(3.0, Percentiles.median(odd), "order statistics, not insertion order")
        assertEquals(80.8, Percentiles.p95(odd), 1e-9)

        assertEquals(0.0, Percentiles.median(emptyList()), "an absent measurement is not a crash")
        assertEquals(7.0, Percentiles.percentile(listOf(7.0), 0.95))
        assertEquals(3.0, Percentiles.percentile(listOf(3.0, 9.0), 0.0))
        assertEquals(9.0, Percentiles.percentile(listOf(3.0, 9.0), 1.0))
    }

    @Test
    fun `a latency summary keeps the tail visible`() {
        val distribution = assertNotNull(Percentiles.summarise("capture", listOf(1.0, 1.1, 1.2, 14.0)))
        assertEquals(4, distribution.count)
        assertEquals(1.0, distribution.min)
        assertEquals(14.0, distribution.max)
        assertTrue(distribution.p95 > distribution.median)
        assertEquals(4.325, distribution.mean, 1e-9)
        assertNull(Percentiles.summarise("empty", emptyList()))
    }

    // ------------------------------------------------------------------ JSON round trip

    @Test
    fun `JSON round trips values including hostile strings`() {
        val hostile = listOf(
            "quote\"inside",
            "back\\slash",
            "newline\nand\ttab",
            "carriage\rreturn",
            "form\u000Cfeed",
            "control\u0001char",
            "देवनागरी नाम",
            "emoji 👩🏽‍🚒",
            "astral 𝕮",
            "",
            "  padded  ",
        )
        val original = MetricJson.Obj(
            linkedMapOf(
                "runId" to MetricJson.Str("eval-2026-smoke"),
                "count" to MetricJson.Num(50.0),
                "rate" to MetricJson.Num(0.9833333333),
                "negative" to MetricJson.Num(-0.5),
                "flag" to MetricJson.Bool(true),
                "nothing" to MetricJson.Null,
                "hostile" to MetricJson.Arr(hostile.map { MetricJson.Str(it) }),
                "nested" to jsonOf("a" to jsonOf("b" to MetricJson.Arr(listOf(MetricJson.Num(1.0))))),
            ),
        )
        val text = MetricJsonWriter.write(original)
        assertEquals(original, MetricJsonReader.parse(text), "a round trip must be lossless")
        assertTrue(text.contains("\\u0001"), "a control character is escaped, not emitted raw")
        assertFalse(text.contains('\u0001'), "no raw control character survives into the file")
        assertTrue(text.contains("\"count\":50,"), "integers keep their integral form")
    }

    @Test
    fun `the JSON reader rejects what two readers could disagree about`() {
        val rejected = listOf(
            "",
            "{",
            "{\"a\":}",
            "{\"a\" 1}",
            "{a:1}",
            "[1,]",
            "01",
            "-",
            "1.",
            ".5",
            "+1",
            "1e",
            "\"unterminated",
            "\"bad \\x escape\"",
            "NaN",
            "Infinity",
            "1 2",
            "{\"a\":1}}",
        )
        for (text in rejected) {
            assertNull(MetricJsonReader.parseOrNull(text), "should have been rejected: '$text'")
        }
    }

    @Test
    fun `the JSON reader accepts the shapes the harness emits`() {
        assertEquals(MetricJson.Obj(emptyMap()), MetricJsonReader.parse("  { }  "))
        assertEquals(MetricJson.Arr(emptyList()), MetricJsonReader.parse("[]"))
        assertEquals(MetricJson.Num(-1500.0), MetricJsonReader.parse("-1.5e3"))
        assertEquals(MetricJson.Str("é"), MetricJsonReader.parse("\"\\u00e9\""))
        assertEquals(MetricJson.Str("😀"), MetricJsonReader.parse("\"\\ud83d\\ude00\""))
        assertEquals(MetricJson.Obj(linkedMapOf("a" to MetricJson.Bool(false))), MetricJsonReader.parse("{\"a\":false}"))
    }
}
