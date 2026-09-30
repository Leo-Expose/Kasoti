package dev.kasoti.platform.ocr

import dev.kasoti.platform.imaging.SyntheticImages
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manual MRZ path (BUILD.md §5, D5).
 *
 * This is the P0 fallback that makes the desktop build shippable on a machine where
 * Tesseract was never installed, so it is tested as carefully as the automatic one — a
 * fallback that mangles the text it was handed is worse than no fallback, because it looks
 * like a successful read.
 */
class ManualOcrEngineTest {

    private val validTd3 = listOf(
        "L898902C<3UTO6908061F9406236ZE184226B<<<<<10",
        "D23145890<<<<<<<<<<<<<<<<<<<4",
    )

    @Test
    fun `an empty engine is available and returns nothing`() {
        val engine = ManualOcrEngine()
        assertTrue(engine.availability().available)
        val result = engine.recognize(SyntheticImages.solid(8, 8, Color.WHITE))
        assertEquals("", result.text)
        assertTrue(result.words.isEmpty())
        assertEquals(0f, result.meanConfidence)
    }

    @Test
    fun `supplied lines are returned verbatim and flagged manual`() {
        val result = ManualOcrEngine(suppliedLines = validTd3).recognize(crop())
        assertEquals(validTd3.joinToString("\n"), result.text)
        assertTrue(result.manual)
        assertEquals(ManualOcrEngine.ID, result.engineId)
        assertEquals(1f, result.meanConfidence)
    }

    @Test
    fun `typed lowercase is folded into the MRZ alphabet`() {
        val result = ManualOcrEngine(suppliedLines = listOf("l898902c<3uto6908061f9406236ze184226b<<<<<10"))
            .recognize(crop())
        assertEquals(validTd3[0], result.text)
    }

    @Test
    fun `spaces become MRZ filler and unspellable characters are dropped`() {
        // A space is a name separator in the printed zone and a filler in the machine zone.
        // A hyphen and a full stop are simply not in the alphabet, so they vanish rather
        // than becoming fillers — padding a field with a filler the document never had
        // would shift every check digit after it.
        assertEquals("A<BC", ManualOcrEngine.normaliseMrz("a b-c."))
        assertEquals("<<<", ManualOcrEngine.normaliseMrz("   "))
    }

    /** `<` must survive: it is the filler that makes every MRZ field a fixed width. */
    @Test
    fun `the filler character is preserved`() {
        assertEquals("<<<<<<<<", ManualOcrEngine.normaliseMrz("<<<<<<<<"))
        assertEquals(1, ManualOcrEngine.normaliseMrz("<").count { it == '<' })
    }

    @Test
    fun `non-MRZ characters are dropped rather than smuggled in`() {
        assertEquals("AB12", ManualOcrEngine.normaliseMrz("A\u00e9B1€2\u0000"))
    }

    @Test
    fun `an image of text is ignored - the engine reads what it was given`() {
        val canvas = BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, 400, 80)
            g.color = Color.BLACK
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 22)
            g.drawString("IGNORE THIS RENDERED TEXT", 10, 50)
        } finally {
            g.dispose()
        }
        val result = ManualOcrEngine(suppliedLines = validTd3).recognize(canvas)
        assertEquals(validTd3.joinToString("\n"), result.text)
    }

    @Test
    fun `newline-separated text is split into lines`() {
        val result = ManualOcrEngine(suppliedText = validTd3.joinToString("\n")).recognize(crop())
        assertEquals(2, result.words.size)
        assertEquals(validTd3[1], result.words[1].text)
    }

    private fun crop(): BufferedImage = SyntheticImages.solid(16, 16, Color.WHITE)
}

/**
 * The Tess4J engine (BUILD.md §5).
 *
 * These tests never require Tesseract to be installed — that is the point of the class.
 * The configuration assertions are the ones that carry weight: `--psm 6` and the
 * `A-Z0-9<` whitelist are the two settings that stop a machine read from disagreeing with
 * a human read of the same passport, and a typo in either would be invisible in every
 * other test in this repository.
 */
class Tess4JOcrEngineTest {

    @Test
    fun `the MRZ configuration matches BUILD_md_section_5`() {
        val engine = Tess4JOcrEngine()
        assertEquals(6, Tess4JOcrEngine.PAGE_SEG_MODE)
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<", Tess4JOcrEngine.MRZ_WHITELIST)
        assertEquals(
            "--psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<",
            engine.configurationLine,
        )
    }

    @Test
    fun `the whitelist contains no character an MRZ cannot hold`() {
        val legal = ('A'..'Z') + ('0'..'9') + '<'
        assertEquals(legal.toSet(), Tess4JOcrEngine.MRZ_WHITELIST.toSet())
    }

    /** A duplicate or a stray character here would silently change what the engine may emit. */
    @Test
    fun `the whitelist has no duplicates and the MRZ alphabet length`() {
        val whitelist = Tess4JOcrEngine.MRZ_WHITELIST
        assertEquals(whitelist.length, whitelist.toSet().size, "duplicate character in the whitelist")
        assertEquals(26 + 10 + 1, whitelist.length)
    }

    /**
     * The contract that keeps a post screening without Tesseract.
     *
     * On this machine (and on a freshly provisioned post machine) `libtesseract` is absent,
     * so [availability] must report unavailable *and* [recognize] must return an empty
     * result rather than throwing an `UnsatisfiedLinkError` into the screening loop.
     */
    @Test
    fun `an engine without the native library reports unavailable and does not throw`() {
        val engine = Tess4JOcrEngine()
        val status = engine.availability()
        if (status.available) return // a machine with Tesseract installed: nothing to assert
        assertTrue(status.detail.isNotBlank(), "an unavailable engine must say why")
        val result = engine.recognize(SyntheticImages.solid(64, 32, Color.WHITE))
        assertEquals("", result.text)
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun `the availability probe result is stable`() {
        val engine = Tess4JOcrEngine()
        assertEquals(engine.availability(), engine.availability())
    }
}

/** Engine selection (D5). */
class OcrEnginesTest {

    private class StubEngine(
        override val id: String,
        private val ok: Boolean,
        private val text: String = "STUB",
    ) : OcrEngine {
        var calls = 0
        override fun availability() = OcrAvailability(ok, if (ok) "ready" else "no native library")
        override fun recognize(image: BufferedImage): OcrResult {
            calls++
            return OcrResult(text, listOf(OcrWord(text, 0.9f)), id, manual = false)
        }
    }

    @Test
    fun `the preferred engine is used when it is available`() {
        val preferred = StubEngine("tess", ok = true)
        val engines = OcrEngines(preferred)
        assertEquals("tess", engines.active().id)
        assertEquals("STUB", engines.recognize(SyntheticImages.solid(4, 4, Color.WHITE)).text)
        assertEquals(1, preferred.calls)
    }

    @Test
    fun `the manual engine takes over when Tesseract is missing`() {
        val preferred = StubEngine("tess", ok = false)
        val fallback = ManualOcrEngine(suppliedLines = listOf("LINE1"))
        val engines = OcrEngines(preferred, fallback)
        assertEquals(ManualOcrEngine.ID, engines.active().id)
        val result = engines.recognize(SyntheticImages.solid(4, 4, Color.WHITE))
        assertEquals("LINE1", result.text)
        assertTrue(result.manual)
        assertEquals(0, preferred.calls, "an unavailable engine must not be called at all")
    }

    @Test
    fun `describe names the fallback and the reason`() {
        val described = OcrEngines(StubEngine("tess", ok = false)).describe()
        assertTrue(described.contains(ManualOcrEngine.ID))
        assertTrue(described.contains("fallback"))
    }

    @Test
    fun `describe names the active engine when it is available`() {
        val described = OcrEngines(StubEngine("tess", ok = true)).describe()
        assertTrue(described.startsWith("tess"))
        assertFalse(described.contains("fallback"))
    }
}
