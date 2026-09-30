package dev.kasoti.evalmetrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two harness artefacts: `metrics.json` and the paste-ready `summary.md` (EVAL.md §3, §6).
 *
 * The point of these tests is that the two are *derived from one structure*. EVAL.md §1 makes
 * the run directory the thing a metric dispute is settled against, so a summary that quietly
 * disagrees with its own JSON is not a cosmetic bug.
 */
class MetricSinkTest {

    private fun sink(): MetricSink = MetricSink(
        runId = "eval-2026-09-smoke-a3f9",
        suite = "smoke",
        commit = "deadbeef",
        devices = listOf("post3-ph1 (Pixel 6a)"),
        split = "report",
    )

    @Test
    fun `counters accumulate and measures replace`() {
        val sink = sink()
        sink.count("mrz.cases", 1).count("mrz.cases", 3).count("mrz.caught", 2)
        sink.measure("face.tar", 0.95).measure("face.tar", 0.97)
        val snapshot = sink.snapshot()
        assertEquals(4L, snapshot.counters["mrz.cases"], "counters accumulate")
        assertEquals(2L, snapshot.counters["mrz.caught"])
        assertEquals(0.97, snapshot.measures["face.tar"])
    }

    @Test
    fun `a non-finite measurement is refused rather than written as NaN`() {
        val sink = sink()
        val failure = runCatching { sink.measure("face.tar", Double.NaN) }
        assertTrue(failure.isFailure, "JSON cannot carry NaN, so the sink must not pretend to")
    }

    @Test
    fun `histogram bins count underflow and overflow separately`() {
        val histogram = sink().histogram("macro.margin", listOf(0.1, 0.35))
        listOf(0.05, 0.05, 0.2, 0.4, 0.9).forEach(histogram::observe)
        assertEquals(3, histogram.bins)
        assertEquals(2L, histogram.count(0), "below the first edge")
        assertEquals(1L, histogram.count(1), "between the edges")
        assertEquals(2L, histogram.count(2), "at or above the last edge")
        assertEquals(5L, histogram.total)
        assertEquals(listOf("<0.1000", "0.1000..0.3500", ">=0.3500"), histogram.rows().map { it.range })
    }

    @Test
    fun `a histogram refuses edges that would make a bin ambiguous`() {
        val failures = listOf(0.1 to 0.1, 0.3 to 0.2).map { (a, b) ->
            runCatching { Histogram("m", listOf(a, b)) }.isFailure
        }
        assertTrue(failures.all { it })
        assertTrue(runCatching { Histogram("m", emptyList()) }.isFailure)
        assertTrue(runCatching { Histogram("m", listOf(0.1)).observe(Double.NaN) }.isFailure)
    }

    @Test
    fun `the metrics json round trips through the reader and keeps field order`() {
        val sink = sink()
        sink.count("mrz.detectable", 240)
        sink.measure("mrz.catchRate", 1.0)
        sink.bucket("face.tar", "female|30-45|dim", 0.94)
        sink.histogram("macro.margin", listOf(0.1, 0.35)).observe(0.2)
        sink.note("TD1 recomputed field checks are structurally blind")

        val text = sink.metricsJson()
        val parsed = MetricJsonReader.parse(text) as MetricJson.Obj
        assertEquals("eval-2026-09-smoke-a3f9", parsed.string("runId"))
        assertEquals("smoke", parsed.string("suite"))
        assertEquals("report", parsed.string("split"))
        assertEquals(240.0, (parsed.obj("counters"))?.number("mrz.detectable"))
        assertEquals(1.0, (parsed.obj("measures"))?.number("mrz.catchRate"))
        assertEquals(0.94, (parsed.obj("buckets")?.obj("face.tar"))?.number("female|30-45|dim"))
        val counts = (parsed.obj("histograms")?.obj("macro.margin"))?.array("counts") ?: emptyList()
        assertEquals(3, counts.size, "two edges make three bins")
        assertEquals(1.0, (counts[1] as MetricJson.Num).value)
        assertTrue(text.endsWith("\n"), "a JSON file ends with a newline")
        assertTrue(text.indexOf("counters") < text.indexOf("measures"), "sections keep a stable order")
    }

    @Test
    fun `the summary is paste-ready and carries the caveats with the numbers`() {
        val sink = sink()
        sink.count("mrz.detectable", 240)
        sink.measure("face.frr", 0.05)
        sink.bucket("face.frr", "male|18-29|bright", 0.01)
        sink.histogram("macro.margin", listOf(0.1, 0.35)).observe(0.2)
        sink.note("blind | structurally blind: TD1 has no composite check digit")

        val markdown = sink.summaryMarkdown()
        assertTrue(markdown.startsWith("# KASOTI eval summary"))
        assertTrue(markdown.contains("- run-id: `eval-2026-09-smoke-a3f9`"))
        assertTrue(markdown.contains("- commit: deadbeef"))
        assertTrue(markdown.contains("- devices: post3-ph1 (Pixel 6a)"))
        assertTrue(markdown.contains("| mrz.detectable | 240 |"))
        assertTrue(markdown.contains("| face.frr | 0.050000 |"))
        assertTrue(markdown.contains("| face.frr | male\\|18-29\\|bright |"), "pipes are escaped")
        assertTrue(markdown.contains("## Known limits"))
        assertTrue(markdown.contains("structurally blind"))
    }

    @Test
    fun `a hostile note cannot break out of the markdown table`() {
        val markdown = sink().note("| injected | row |\nsecond line").summaryMarkdown()
        assertFalse(markdown.contains("| injected |"), "a table row cannot be injected through a note")
        assertTrue(markdown.contains("\\| injected \\| row \\| second line"))
    }

    @Test
    fun `the gate table renders the SPEC 7 columns and separates pass from fail`() {
        val table = GateTable(
            listOf(
                Gate("MRZ mutate-catch", "100%", "100.000% (240/240)", true, "EVAL.md §2 D-MRZ"),
                Gate("Crash-free end-to-end runs", "50/50", "49/50", false),
            ),
        )
        val markdown = table.toMarkdown()
        assertTrue(markdown.startsWith("| Gate | Bar | Measured | Result |"))
        assertTrue(markdown.contains("| MRZ mutate-catch | 100% | 100.000% (240/240) | PASS |"))
        assertTrue(markdown.contains("| Crash-free end-to-end runs | 50/50 | 49/50 | FAIL |"))
        assertFalse(table.allPassed)
        assertEquals(1, table.failing.size)
    }

    @Test
    fun `gates fail on a missing measurement rather than passing quietly`() {
        val emptyClassification = Classification.of(emptyList(), labels = listOf("OFFSET"))
        assertFalse(
            SpecGates.macroRedUseFloor(emptyClassification, "OFFSET", recallFloor = 0.85).pass,
            "no macro samples is not an 85% recall",
        )
        assertFalse(SpecGates.wallClockMedian("post3-ph1", null, 15.0).pass)
        assertFalse(SpecGates.crashFree(runs = 10, crashFree = 10).pass, "10 runs is not the 50 the spec asks for")
        assertTrue(SpecGates.crashFree(runs = 50, crashFree = 50).pass)
        assertFalse(SpecGates.crashFree(runs = 50, crashFree = 49).pass)

        val slow = Percentiles.summarise("stranger", listOf(14.0, 16.0, 20.0))
        assertFalse(SpecGates.wallClockMedian("post3-ph1", slow, 15.0).pass, "median 16.0 is over the ceiling")
        val onTheBar = Percentiles.summarise("stranger", listOf(14.0, 16.0, 15.0))
        assertTrue(SpecGates.wallClockMedian("post3-ph1", onTheBar, 15.0).pass, "the ceiling is inclusive")
        val fast = Percentiles.summarise("stranger", listOf(4.0, 6.0, 5.0))
        assertTrue(SpecGates.wallClockMedian("post3-ph1", fast, 15.0).pass)
    }
}
