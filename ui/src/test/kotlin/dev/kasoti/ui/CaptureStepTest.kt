package dev.kasoti.ui

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.i18n.Language
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FieldStringsTest {

    @Test
    fun `every key has an English string`() {
        assertEquals(emptySet(), FieldStrings.missingEnglish(), "AGENTS.md §2: no blank strings to the UI")
    }

    @Test
    fun `every key has a Hindi string because P1 is Hindi-first`() {
        assertEquals(emptySet(), FieldStrings.missingHindi(), "FR-R1 makes a missing Hindi string a build failure")
    }

    @Test
    fun `no string is blank and none falls through to its key name`() {
        for (key in FieldStrings.Key.entries) {
            for (language in Language.entries) {
                val text = FieldStrings.of(key, language)
                assertTrue(text.isNotBlank(), "${key.name}/$language resolved to a blank string")
                assertTrue(
                    text != key.name,
                    "${key.name}/$language fell through to the enum name — a string is missing",
                )
            }
        }
    }

    @Test
    fun `Hindi is actually different, not a copy of the English`() {
        val identical = FieldStrings.Key.entries.filter { key ->
            FieldStrings.of(key, Language.ENGLISH) == FieldStrings.of(key, Language.HINDI)
        }
        assertEquals(
            emptyList(),
            identical,
            "these keys render identically in both languages, which usually means English was copied over",
        )
    }

    @Test
    fun `placeholders are formatted`() {
        assertEquals("Step 2 of 4", FieldStrings.of(FieldStrings.Key.STEP_OF, Language.ENGLISH, 2, 4))
        assertEquals("चरण 2 / 4", FieldStrings.of(FieldStrings.Key.STEP_OF, Language.HINDI, 2, 4))
    }

    @Test
    fun `finding strings come from core, never from this catalogue`() {
        // The app's furniture and `:core`'s finding vocabulary are separate contracts. A key
        // here that duplicates a FindingCode string is how the two start to drift, because only
        // `:core`'s completeness test would notice the *other* one going stale.
        val furniture = FieldStrings.Key.entries.map { FieldStrings.of(it, Language.ENGLISH) }.toSet()
        val coreStrings = FindingCode.entries.map { dev.kasoti.i18n.Messages.of(it, Language.ENGLISH) }.toSet()
        val collisions = furniture.intersect(coreStrings)
        assertEquals(emptySet(), collisions, "UI chrome must not restate a :core finding string")
    }

    /**
     * The keys added when `app-android`'s Compose renderer stopped carrying bare literals.
     *
     * `FieldStringsTest` above already enforces completeness for *every* key generically, so what
     * this adds is the part a generic check cannot see: that each of these was given a real
     * Hindi translation and not merely copied across, and that the two that interpolate a number
     * still line their placeholders up. A key pasted in with the English text passes the
     * completeness test and fails this one.
     */
    @Test
    fun `the keys that replaced hardcoded renderer literals are translated, not copied`() {
        val added = listOf(
            FieldStrings.Key.BACK,
            FieldStrings.Key.PERMISSION_SETTINGS,
            FieldStrings.Key.CARRIED_OVER,
            FieldStrings.Key.CHECKS_TITLE,
            FieldStrings.Key.CLIP_ON,
            FieldStrings.Key.CLIP_OFF,
            FieldStrings.Key.TRUST_REVERIFY_IN,
            FieldStrings.Key.NO_NETWORK_CLAIM,
            FieldStrings.Key.DEMO_MODE_OFF,
            FieldStrings.Key.ERROR_DISMISS,
            FieldStrings.Key.CD_CROP_OVERLAY,
            FieldStrings.Key.CD_VERDICT_HEADLINE,
            FieldStrings.Key.CD_LAYER_STATUS,
            FieldStrings.Key.CD_QUALITY_METER,
            FieldStrings.Key.CD_SHARPNESS_METER,
            FieldStrings.Key.CD_DEMO_MODE,
        )
        for (key in added) {
            assertTrue(key !in FieldStrings.missingEnglish(), "$key has no English string")
            assertTrue(key !in FieldStrings.missingHindi(), "$key has no Hindi string")
            assertTrue(
                FieldStrings.of(key, Language.ENGLISH) != FieldStrings.of(key, Language.HINDI),
                "$key renders the same in both languages — the Hindi was copied from the English",
            )
        }
    }

    @Test
    fun `the interpolated renderer keys substitute in both languages`() {
        assertEquals("re-verify in 12 days", FieldStrings.of(FieldStrings.Key.TRUST_REVERIFY_IN, Language.ENGLISH, 12))
        assertEquals("12 दिनों में पुनः-सत्यापन", FieldStrings.of(FieldStrings.Key.TRUST_REVERIFY_IN, Language.HINDI, 12))
        assertEquals("Verdict: DO NOT CLEAR", FieldStrings.of(FieldStrings.Key.CD_VERDICT_HEADLINE, Language.ENGLISH, "DO NOT CLEAR"))
        assertEquals("निर्णय: जारी रखें नहीं", FieldStrings.of(FieldStrings.Key.CD_VERDICT_HEADLINE, Language.HINDI, "जारी रखें नहीं"))
        assertEquals("Layer mrz, MRZ/A/B/C", FieldStrings.of(FieldStrings.Key.CD_LAYER_STATUS, Language.ENGLISH, "mrz", "MRZ/A/B/C"))
        assertEquals("परत mrz, MRZ/A/B/C", FieldStrings.of(FieldStrings.Key.CD_LAYER_STATUS, Language.HINDI, "mrz", "MRZ/A/B/C"))
        assertEquals("Capture quality 42%", FieldStrings.of(FieldStrings.Key.CD_QUALITY_METER, Language.ENGLISH, 42))
        assertEquals("कैप्चर गुणवत्ता 42%", FieldStrings.of(FieldStrings.Key.CD_QUALITY_METER, Language.HINDI, 42))
        assertEquals("Sharpness: Photo zone", FieldStrings.of(FieldStrings.Key.CD_SHARPNESS_METER, Language.ENGLISH, "Photo zone"))
        assertEquals("तीव्रता: चित्र क्षेत्र", FieldStrings.of(FieldStrings.Key.CD_SHARPNESS_METER, Language.HINDI, "चित्र क्षेत्र"))
    }

    /**
     * The idle card's network claim, and the drift it settles.
     *
     * The Android renderer used to hold a private const reading "Everything here runs on this
     * device" while the `err_no_network_needed` resource read "Everything above ran on this
     * device". The const's wording is the one that survives, because the idle card is the only
     * place either sentence is rendered and at that point nothing has been captured: there is
     * nothing above the claim and nothing that has run. This test exists so the resolution cannot
     * be quietly re-drifted, and so nobody "fixes" it towards the past tense by copying the
     * resource.
     */
    @Test
    fun `the network claim keeps the wording that is true on the screen that shows it`() {
        val english = FieldStrings.of(FieldStrings.Key.NO_NETWORK_CLAIM, Language.ENGLISH)
        assertTrue(english.contains("Everything here runs on this device"), "got: $english")
        assertTrue(
            !english.contains("Everything above ran"),
            "the past-tense resource wording is false on the idle card — nothing has run yet",
        )
    }
}

class CaptureStepTest {

    private val meters = mutableMapOf<CaptureStepId, QualityMeter>()

    private fun build(vararg steps: CaptureStepId, current: Int = 0) = CaptureProgressBuilder.build(
        steps = steps.toList(),
        done = emptyMap(),
        meters = meters,
        currentIndex = current,
        language = Language.ENGLISH,
    )

    @Test
    fun `the stranger flow is capped at four steps`() {
        assertEquals(4, CaptureProgress.MAX_STEPS)
        assertFailsWith<IllegalArgumentException> {
            build(CaptureStepId.DOCUMENT, CaptureStepId.MRZ, CaptureStepId.MACRO, CaptureStepId.FACE, CaptureStepId.DOCUMENT)
        }
    }

    @Test
    fun `the full four-step flow is accepted`() {
        val progress = build(CaptureStepId.DOCUMENT, CaptureStepId.MRZ, CaptureStepId.MACRO, CaptureStepId.FACE)
        assertEquals(4, progress.total)
        assertEquals(listOf("1/4", "2/4", "3/4", "4/4"), progress.cards.map { it.label })
    }

    @Test
    fun `a failed required step blocks the advance`() {
        meters[CaptureStepId.DOCUMENT] = QualityMeter(
            passed = false,
            score = 0.2f,
            causes = listOf(FindingCode.G_BLUR),
            live = true,
        )
        val progress = build(CaptureStepId.DOCUMENT, CaptureStepId.MRZ, current = 0)

        assertTrue(progress.blocker != null, "FR-C1: GREY blocks advance on failure")
        assertTrue(!progress.allDone)
        assertTrue(!progress.current!!.canAdvance)
    }

    @Test
    fun `a passing step does not block`() {
        meters[CaptureStepId.DOCUMENT] = QualityMeter(true, 0.9f, emptyList(), live = true)
        val progress = build(CaptureStepId.DOCUMENT, CaptureStepId.MRZ, current = 0)

        assertEquals(null, progress.blocker)
        assertTrue(progress.current!!.canAdvance)
    }

    @Test
    fun `no preview yet is not a failure`() {
        // A jawan who has not pointed the camera at the document yet must not be told they are
        // holding it wrong. UNKNOWN passes, so the shutter is not blocked by a missing frame.
        assertTrue(QualityMeter.UNKNOWN.passed)
        assertEquals(emptyList(), QualityMeter.UNKNOWN.instructions(Language.ENGLISH))
    }

    @Test
    fun `meter instructions are the core finding strings, localised`() {
        val meter = QualityMeter(false, 0.1f, listOf(FindingCode.G_DARK, FindingCode.G_BLUR), live = true)
        assertEquals(
            listOf(
                dev.kasoti.i18n.Messages.of(FindingCode.G_DARK, Language.ENGLISH),
                dev.kasoti.i18n.Messages.of(FindingCode.G_BLUR, Language.ENGLISH),
            ),
            meter.instructions(Language.ENGLISH),
        )
        assertEquals(
            dev.kasoti.i18n.Messages.of(FindingCode.G_DARK, Language.HINDI),
            meter.instructions(Language.HINDI).first(),
        )
    }

    @Test
    fun `a clean capture reports full quality and an empty cause list`() {
        val meter = QualityMeter.from(QualityReport.CLEAN, live = false)
        assertEquals(1f, meter.score)
        assertTrue(meter.causes.isEmpty())
    }

    @Test
    fun `the meter score is the worst offender, never an average`() {
        // Glare alone disqualifies. A mean of "one catastrophic reading and five fine ones"
        // is how a system ends up accusing someone because the sun was behind them.
        val report = QualityReport(
            passed = false,
            causes = listOf(FindingCode.G_GLARE),
            blurScore = 400f,
            glareRatio = 0.4f,
            brightness = 100f,
        )
        assertTrue(QualityMeter.from(report, live = false).score < 0.2f)
    }

    /**
     * The display references in `CaptureStep.kt` are documented as "not operating thresholds".
     * This is the test that keeps that true: if `:core` re-tunes `Q_BLUR`, the bar must follow,
     * so a change to the registry cannot silently desynchronise the meter from the gate.
     */
    @Test
    fun `meter display references still agree with the shipped registry defaults`() {
        val registry = ThresholdRegistry.defaults()
        // 100 = Q_BLUR, 0.12 = Q_GLARE, 40/225 = Q_BRIGHT_MIN/MAX, 25 = Q_POSE_YAW (v1 defaults).
        assertEquals(100f, registry[ThresholdName.Q_BLUR].toFloat(), 0.001f)
        assertEquals(0.12f, registry[ThresholdName.Q_GLARE].toFloat(), 0.0001f)
        assertEquals(40f, registry[ThresholdName.Q_BRIGHT_MIN].toFloat(), 0.001f)
        assertEquals(225f, registry[ThresholdName.Q_BRIGHT_MAX].toFloat(), 0.001f)
        assertEquals(25f, registry[ThresholdName.Q_POSE_YAW].toFloat(), 0.001f)
    }
}

class AccusationLexiconTest {

    @Test
    fun `the retake headline is clean in both languages`() {
        for (language in Language.entries) {
            AccusationLexicon.assertNoAccusation(FieldStrings.of(FieldStrings.Key.VERDICT_GREY, language))
        }
    }

    @Test
    fun `every GREY cause string is clean in both languages`() {
        val greyCodes = listOf(
            FindingCode.G_BLUR, FindingCode.G_GLARE, FindingCode.G_DARK, FindingCode.G_POSE,
            FindingCode.G_OCCLUDE, FindingCode.G_OCRLOW, FindingCode.G_FOCUS,
            FindingCode.G_NOCLIP, FindingCode.G_NOEVIDENCE, FindingCode.A_MISSING_LAYER,
        )
        for (code in greyCodes) {
            for (language in Language.entries) {
                AccusationLexicon.assertNoAccusation(dev.kasoti.i18n.Messages.of(code, language))
            }
        }
    }

    @Test
    fun `adversarial - a string that accuses is caught`() {
        assertEquals(listOf("forged"), AccusationLexicon.violations("This document is forged"))
        assertEquals(listOf("असली"), AccusationLexicon.violations("यह असली नहीं है"))
        assertFailsWith<IllegalArgumentException> {
            AccusationLexicon.assertNoAccusation("possible alias - this is a fraud")
        }
        // Deliberately NOT in the lexicon: "lying" is a substring of "underlying", and a
        // substring matcher on a short word would then fail every screen containing it. The
        // lexicon is curated for that reason; adding a short word needs a word-boundary rule.
    }

    @Test
    fun `adversarial - case and padding do not hide a word`() {
        assertTrue(AccusationLexicon.violations("  COUNTERFEIT  ").contains("counterfeit"))
        assertTrue(AccusationLexicon.violations("RejEct").contains("reject"))
    }

    @Test
    fun `adversarial - ordinary retake prose is not a false positive`() {
        // "clean" must not trip "fail"-style substring matching, and the strings the app really
        // shows on a GREY must survive their own guard.
        assertEquals(emptyList(), AccusationLexicon.violations("Image is blurry"))
        assertEquals(emptyList(), AccusationLexicon.violations("Too much glare"))
        assertEquals(emptyList(), AccusationLexicon.violations("RETAKE"))
        assertEquals(emptyList(), AccusationLexicon.violations("Macro clip is required for this document"))
    }
}
