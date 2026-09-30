package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.FusionEngine
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.ProcessLabel
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The seam between the console and `dev.kasoti.fusion.FusionEngine`.
 *
 * Deliberately *not* a second copy of `:core`'s rule tests. `:core` owns the rules and
 * tests them where the rules live; what the console has to get right is that it calls the
 * real engine, passes the caller's reference year through, and reports the rule version the
 * engine actually used. Anything more would be asserting `:core`'s behaviour from the wrong
 * side of a module boundary, and would fail every time a rule is retuned.
 */
class CoreFusionEngineTest {

    private val registry = ThresholdRegistry.defaults("v1", "test-run")
    private val engine = CoreFusionEngine()

    private fun evidence(
        quality: QualityReport = QualityReport.CLEAN,
        math: MathEvidence? = MathEvidence(allChecksPassed = true),
        macro: MacroEvidence? = confidentMacro(),
    ) = Evidence(track = Track.PASSPORT, quality = quality, math = math, macro = macro)

    private fun confidentMacro() = MacroEvidence(
        photoZoneLabel = ProcessLabel.OFFSET,
        photoZoneMargin = 0.8f,
        textZoneLabel = ProcessLabel.OFFSET,
        textZoneMargin = 0.8f,
        clipUsed = true,
    )

    @Test
    fun `the rule version is the engine's, not the console's`() {
        assertEquals(FusionEngine.FUSION_RULE_VERSION, engine.ruleVersion)
        assertNotEquals(LocalRuleEngine.RULE_VERSION, engine.ruleVersion)
    }

    /**
     * The console hands the engine's verdict straight through.
     *
     * Asserted as *equality with a direct engine call* rather than against a literal. The
     * value of GREEN/AMBER/RED is `:core`'s to decide and `:core`'s to change; what would be
     * a defect here is the console quietly altering it on the way past.
     */
    @Test
    fun `the verdict is the engine's own, unmodified`() {
        val case = evidence()
        val direct = FusionEngine.decide(case, registry, referenceYear = 2026)
        val decision = engine.decide(case, registry, referenceYear = 2026)
        assertEquals(direct.verdict, decision.verdict)
        assertEquals(direct.findings.map { it.code }, decision.findings.map { it.code })
        assertTrue(decision.verdict in Verdict.entries)
    }

    @Test
    fun `the engine's rationale is captured for the console to show`() {
        engine.decide(
            evidence(math = MathEvidence(allChecksPassed = false, failedFields = setOf("DOC"))),
            registry,
            referenceYear = 2026,
        )
        assertTrue(engine.rationale.isNotEmpty(), "a RED with no stated reason is not auditable")
    }

    /**
     * The reference year reaches the engine rather than being read from a clock.
     *
     * `:core` refuses to resolve a two-digit MRZ date against a device clock, so the caller
     * supplies it. If the console ever started supplying its own, a date would silently
     * change meaning across a century boundary and nothing would fail. Asserted by
     * comparing against a direct call at each year: the console must agree with the engine
     * at the year it was given, for both of them.
     */
    @Test
    fun `the reference year is threaded through rather than read from a clock`() {
        val case = Evidence(
            track = Track.PASSPORT,
            quality = QualityReport.CLEAN,
            macro = confidentMacro(),
            math = MathEvidence(allChecksPassed = true),
        )
        for (year in listOf(2026, 2060, 1990)) {
            val direct = FusionEngine.decide(case, registry, referenceYear = year)
            val through = engine.decide(case, registry, referenceYear = year)
            assertEquals(direct.verdict, through.verdict, "diverged at reference year $year")
        }
    }

    @Test
    fun `a hard MRZ failure reaches the caller as a RED finding`() {
        val decision = engine.decide(
            evidence(math = MathEvidence(allChecksPassed = false, failedFields = setOf("DOC"))),
            registry,
            referenceYear = 2026,
        )
        assertTrue(decision.findings.any { it.code == FindingCode.R_MATH_01 })
    }

    /** The console must not fall back to a verdict when the engine raises. */
    @Test
    fun `the console cascade defaults to the core engine`() {
        val cascade = ScreeningCascade()
        assertTrue(cascade.engine is CoreFusionEngine, "the default engine must be :core's")
    }

    @Test
    fun `a file import with no face and no diary is a retake, not a GREEN`() {
        // The console cannot produce face or diary evidence yet, and `:core` is
        // fail-closed about load-bearing layers (FUSION.md §7). The console therefore
        // reports GREY today for every real file import. Asserted deliberately: if this ever
        // silently becomes GREEN, the console has started treating "we did not look" as
        // "there was nothing to find".
        val decision = engine.decide(
            Evidence(track = Track.PASSPORT, quality = QualityReport.CLEAN, math = MathEvidence(allChecksPassed = true)),
            registry,
            referenceYear = 2026,
        )
        assertTrue(decision.verdict.isRetake, "expected a retake, got ${decision.verdict}")
    }

    @Test
    fun `the console can still screen a file it can read end to end`() {
        val dir = createTempDirectory("kasoti-core-engine")
        try {
            val image: Path = dir.resolve("doc.png")
            Files.write(image, ByteArray(64) { 200.toByte() })
            val result = ScreeningCascade().screen(
                ScreeningRequest(
                    image = image,
                    track = Track.PASSPORT,
                    today = IsoDate(2026, 9, 29),
                    deviceId = "test",
                ),
                caseId = "case_TEST",
            )
            assertEquals(FusionEngine.FUSION_RULE_VERSION, result.fusionRuleVersion)
            assertTrue(result.warnings.isNotEmpty(), ":core's rationale should reach the console")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
