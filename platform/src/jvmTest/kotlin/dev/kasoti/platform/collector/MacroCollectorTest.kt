package dev.kasoti.platform.collector

import dev.kasoti.platform.imaging.NormRect
import dev.kasoti.platform.imaging.SyntheticImages
import dev.kasoti.platform.imaging.ImageIoImaging
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The D-MACRO corpus layout (DATA.md §3).
 *
 * The value of this test is almost entirely in the string assertions. The patches
 * themselves are unremarkable; what is expensive to get wrong is a corpus that is
 * subtly *mislabelled by directory* — a `noclip` sample under `clip/`, a `SCREEN` reprint
 * in the `LASER` bucket — because an eval run over that corpus produces numbers that look
 * entirely reasonable and are measuring nothing.
 */
class MacroCollectorTest {

    private fun withCorpus(block: (Path, Path, MacroCollector) -> Unit) {
        val dir = createTempDirectory("kasoti-macro")
        try {
            val corpus = dir.resolve("eval").resolve("data").resolve("macro")
            val source = dir.resolve("source.png")
            val imaging = ImageIoImaging()
            imaging.writeGrayPng(
                imaging.toRgb(SyntheticImages.textured(600, 400)).toGray(),
                source,
            )
            block(dir, source, MacroCollector(corpus))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: Path,
        process: MacroProcess = MacroProcess.OFFSET,
        light: MacroLight = MacroLight.SUN,
        clip: Boolean = true,
        sourceId: String = "specimen-laser-a",
        calibId: String = "calib-post3-001",
        deviceId: String = "post3-ph1",
    ) = MacroCaptureRequest(
        sourceImage = source,
        sourceId = sourceId,
        process = process,
        light = light,
        clip = clip,
        deviceId = deviceId,
        calibId = calibId,
        region = NormRect(0.2f, 0.2f, 0.6f, 0.6f),
    )

    @Test
    fun `a patch lands at the DATA_md_section_3 path`() = withCorpus { _, source, collector ->
        val sample = collector.capture(request(source))
        assertEquals("OFFSET/sun/clip/specimen-laser-a_1.png", sample.relativePath)
        assertTrue(Files.exists(sample.absolutePath))
    }

    @Test
    fun `the noclip bucket is used when the clip was not seated`() = withCorpus { _, source, collector ->
        val sample = collector.capture(request(source, clip = false))
        assertEquals("OFFSET/sun/noclip/specimen-laser-a_1.png", sample.relativePath)
    }

    @Test
    fun `the patch is 256 by 256`() = withCorpus { _, source, collector ->
        val sample = collector.capture(request(source))
        assertEquals(256, sample.patchWidth)
        assertEquals(256, sample.patchHeight)
        val decoded = ImageIoImaging().readRgb(sample.absolutePath)
        assertEquals(256, decoded.width)
        assertEquals(256, decoded.height)
    }

    @Test
    fun `the manifest header and row are the six DATA_md columns`() = withCorpus { _, source, collector ->
        val sample = collector.capture(request(source))
        val lines = Files.readAllLines(sample.manifestFile)
        assertEquals("source-id,process-label,light,clip,device,calib-id,file", lines[0])
        assertEquals("specimen-laser-a,OFFSET,sun,true,post3-ph1,calib-post3-001,specimen-laser-a_1.png", lines[1])
        assertEquals("specimen-laser-a,OFFSET,sun,true,post3-ph1,calib-post3-001,specimen-laser-a_1.png", sample.manifestRow)
    }

    @Test
    fun `every capture appends exactly one row`() = withCorpus { _, source, collector ->
        val first = collector.capture(request(source))
        val second = collector.capture(request(source))
        val lines = Files.readAllLines(first.manifestFile)
        assertEquals(3, lines.size, "one header plus one row per capture")
        assertEquals(first.manifestRow, lines[1])
        assertEquals(second.manifestRow, lines[2])
    }

    @Test
    fun `every label reaches the path and the row`() = withCorpus { _, source, collector ->
        MacroProcess.entries.forEach { process ->
            val sample = collector.capture(request(source, process = process, light = MacroLight.TORCH, clip = false))
            assertTrue(
                sample.relativePath.startsWith("${process.label}/torch/noclip/"),
                "expected ${process.label} at the head of ${sample.relativePath}",
            )
            assertTrue(sample.manifestRow.contains(",${process.label},"))
        }
    }

    /** Repeated runs must not collide — the counter resumes from what is on disk. */
    @Test
    fun `the counter resumes across collector instances`() = withCorpus { dir, source, _ ->
        val corpus = dir.resolve("eval/data/macro")
        val first = MacroCollector(corpus).capture(request(source))
        val second = MacroCollector(corpus).capture(request(source))
        val third = MacroCollector(corpus).capture(request(source))
        assertEquals("specimen-laser-a_1.png", first.fileName)
        assertEquals("specimen-laser-a_2.png", second.fileName)
        assertEquals("specimen-laser-a_3.png", third.fileName)
        assertEquals(3, Files.list(corpus.resolve("OFFSET/sun/clip")).count())
    }

    @Test
    fun `an existing file is never overwritten`() = withCorpus { _, source, collector ->
        collector.capture(request(source))
        assertFailsWith<java.io.IOException> { collector.capture(request(source), indexOverride = 1) }
    }

    @Test
    fun `a source id with path separators is rejected before anything is created`() =
        withCorpus { dir, source, collector ->
            val corpus = dir.resolve("eval/data/macro")
            assertFailsWith<IllegalArgumentException> { collector.capture(request(source, sourceId = "../../escape")) }
            assertFalse(Files.exists(corpus), "a rejected source id must not create directories")
        }

    @Test
    fun `a source id with a comma is rejected so the manifest stays parseable`() =
        withCorpus { _, source, collector ->
            assertFailsWith<IllegalArgumentException> { collector.capture(request(source, sourceId = "a,b")) }
        }

    /** DATA.md §5: calibration is a precondition for the macro eval. */
    @Test
    fun `a blank calibration id is rejected`() = withCorpus { _, source, collector ->
        assertFailsWith<IllegalArgumentException> { collector.capture(request(source, calibId = "")) }
    }

    @Test
    fun `a missing source image fails rather than writing an empty patch`() =
        withCorpus { dir, _, collector ->
            assertFailsWith<java.io.IOException> {
                collector.capture(request(dir.resolve("nope.png")))
            }
        }

    @Test
    fun `the default corpus root is eval_data_macro`() {
        val root = MacroCollector.defaultCorpusRoot(Path.of("/repo"))
        assertEquals("/repo/eval/data/macro", root.toString().replace('\\', '/'))
    }
}

class MacroVocabularyTest {

    @Test
    fun `process labels are case-insensitive and trimmed`() {
        assertEquals(MacroProcess.INKJET, MacroProcess.require("  inkjet "))
        assertEquals(MacroProcess.PHOTOCOPY, MacroProcess.require("PHOTOCOPY"))
    }

    @Test
    fun `an unknown process label is rejected with the valid set listed`() {
        val failure = assertFailsWith<IllegalArgumentException> { MacroProcess.require("laserjet") }
        assertTrue(failure.message!!.contains("OFFSET"))
    }

    @Test
    fun `lights map to their path segment`() {
        assertEquals("sun", MacroLight.require("SUN").label)
        assertEquals("shade", MacroLight.require("shade").label)
        assertEquals("torch", MacroLight.require(" torch ").label)
        assertFailsWith<IllegalArgumentException> { MacroLight.require("torchlight") }
    }

    /** The collector's labels and `:core`'s `ProcessLabel` must be the same vocabulary. */
    @Test
    fun `the seven labels match dev_kasoti_factory_ProcessLabel`() {
        val coreLabels = dev.kasoti.factory.ProcessLabel.entries.map { it.name }.sorted()
        assertEquals(coreLabels, MacroProcess.entries.map { it.label }.sorted())
    }
}
