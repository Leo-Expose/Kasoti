package dev.kasoti.platform.imaging

import dev.kasoti.factory.GrayImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Builds deterministic test images without shipping binary fixtures. */
internal object SyntheticImages {

    fun solid(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return image
    }

    /**
     * A deterministic two-tone gradient with a high-frequency checker on top.
     *
     * The checker matters: a resize that silently collapses high frequencies would show up
     * here as a flattened std-dev, which is exactly the artefact the macro classifier reads.
     */
    fun textured(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val checker = if (((x / 4) + (y / 4)) % 2 == 0) 235 else 15
                val base = ((x * 255) / width) and 0xFF
                image.setRGB(x, y, (base shl 16) or (checker shl 8) or checker)
            }
        }
        return image
    }

    fun toRgb(image: BufferedImage): RgbImage = ImageIoImaging().toRgb(image)

    fun write(image: BufferedImage, target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        ImageIO.write(image, "png", target.toFile())
    }
}

class RgbImageTest {

    @Test
    fun `luma conversion uses Rec601 weights`() {
        val rgb = RgbImage(2, 1, byteArrayOf(255.toByte(), 0, 0, 0, 0, 255.toByte()))
        val gray = rgb.toGray()
        // 0.299 and 0.114 of full scale, rounded.
        assertEquals(76, (gray.get(0, 0) * 255f).toInt())
        assertEquals(29, (gray.get(1, 0) * 255f).toInt())
    }

    @Test
    fun `channel accessors read the interleaved layout`() {
        val rgb = RgbImage(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(1, rgb.red(0, 0))
        assertEquals(2, rgb.green(0, 0))
        assertEquals(3, rgb.blue(0, 0))
        assertEquals(4, rgb.red(1, 0))
        assertEquals(5, rgb.green(1, 0))
        assertEquals(6, rgb.blue(1, 0))
        // The packed accessor is 0xRRGGBB, so (1, 2, 3) packs to 0x010203.
        assertEquals(0x010203, rgb[0, 0])
        assertEquals(0x040506, rgb[1, 0])
    }

    @Test
    fun `a wrong-sized buffer is rejected rather than read out of bounds`() {
        assertFailsWith<IllegalArgumentException> { RgbImage(4, 4, ByteArray(10)) }
    }
}

class ImageIoImagingTest {

    private val imaging = ImageIoImaging()

    @Test
    fun `decodes a PNG into RGB and gray`() {
        val dir = createTempDirectory("kasoti-img")
        try {
            val file = dir.resolve("doc.png")
            SyntheticImages.write(SyntheticImages.textured(32, 16), file)
            val rgb = imaging.readRgb(file)
            assertEquals(32, rgb.width)
            assertEquals(16, rgb.height)
            assertEquals(32 * 16 * 3, rgb.pixels.size)

            val gray = imaging.readGray(file)
            assertEquals(32, gray.width)
            assertTrue(gray.pixels.all { it in 0f..1f })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an alpha image is flattened onto white, not black`() {
        val dir = createTempDirectory("kasoti-alpha")
        try {
            val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            val file = dir.resolve("alpha.png")
            ImageIO.write(image, "png", file.toFile())
            val rgb = imaging.readRgb(file)
            // Fully transparent source pixel: the flattened result is white, not 0.
            assertEquals(255, rgb.red(1, 1))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a non-image file is rejected with a typed error`() {
        val dir = createTempDirectory("kasoti-bad")
        try {
            val file = dir.resolve("not-an-image.png")
            Files.writeString(file, "this is a text file wearing a .png extension")
            assertFailsWith<ImageDecodeException> { imaging.readRgb(file) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resizing to the same size is an identity`() {
        val source = SyntheticImages.toRgb(SyntheticImages.textured(16, 16))
        val out = imaging.resize(source, 16, 16)
        assertTrue(source.pixels.contentEquals(out.pixels))
    }

    @Test
    fun `bilinear resize preserves a flat field exactly`() {
        val flat = SyntheticImages.toRgb(SyntheticImages.solid(64, 64, Color(120, 130, 140)))
        val small = imaging.resize(flat, 16, 16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(120, small.red(x, y))
                assertEquals(130, small.green(x, y))
                assertEquals(140, small.blue(x, y))
            }
        }
    }

    @Test
    fun `bilinear resize does not shift the image`() {
        // A step edge down the middle of an 8x8 field, widened to 16x16.
        //
        // Half-pixel-centre mapping puts the transition symmetrically on the 7/8 boundary:
        // columns 0..6 solid white, columns 9..15 solid black, and the two straddling
        // columns summing to full scale. The naive corner mapping puts the blend at
        // 127/0 instead — still "a step between 7 and 8", but shifted by a quarter pixel,
        // which is precisely the low-frequency smear the macro features would report as
        // physical evidence about the document.
        val source = RgbImage(
            8, 8,
            ByteArray(8 * 8 * 3) { i ->
                val x = (i / 3) % 8
                if (x < 4) 0xFF.toByte() else 0x00
            },
        )
        val out = imaging.resize(source, 16, 16)

        for (x in 0..6) assertEquals(255, out.red(x, 0), "column $x should be solid white")
        for (x in 9..15) assertEquals(0, out.red(x, 0), "column $x should be solid black")
        assertEquals(255, out.red(7, 0) + out.red(8, 0), "the blend must straddle the edge symmetrically")
    }

    @Test
    fun `resize rejects a zero target`() {
        val source = SyntheticImages.toRgb(SyntheticImages.solid(8, 8, Color.BLACK))
        assertFailsWith<IllegalArgumentException> { imaging.resize(source, 0, 8) }
    }

    @Test
    fun `a full-frame quad crop is a resample, not a transpose`() {
        val source = SyntheticImages.toRgb(SyntheticImages.solid(40, 20, Color(10, 20, 30)))
        val crop = imaging.cropQuad(source, RgbQuad.fullFrame(), outputWidth = 20)
        // The full frame is square in normalised units, so the output is square too.
        assertEquals(20, crop.width)
        assertEquals(20, crop.height)
        assertEquals(10, crop.red(5, 5))
        assertEquals(20, crop.green(5, 5))
        assertEquals(30, crop.blue(5, 5))
    }

    @Test
    fun `a quad crop rectifies a rotated document`() {
        // A card photographed inside a tilted quad. Cropping the quad must produce an
        // upright rectangle, so every output sample lands on the document rather than on
        // the background around it. A transposed or inverted warp leaks white into the
        // middle, which is what the centre mean below detects.
        val frame = 200
        val quad = RgbQuad(
            topLeft = NormPoint(0.30f, 0.10f),
            topRight = NormPoint(0.70f, 0.30f),
            bottomRight = NormPoint(0.65f, 0.85f),
            bottomLeft = NormPoint(0.25f, 0.60f),
        )
        val source = paintQuad(frame, quad)
        val crop = imaging.cropQuad(source, quad, outputWidth = 40)

        val centre = centralMean(crop)
        assertTrue(centre < 32f, "the centre of the rectified card should be dark, was $centre")
        // The un-cropped frame's own centre is mostly background, so rectification is a
        // real improvement rather than a restatement of the input.
        val rawCentre = centralMean(source)
        assertTrue(centre < rawCentre, "rectification should not be worse than the raw frame")
    }

    @Test
    fun `a degenerate quad is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RgbQuad(
                topLeft = NormPoint(0.1f, 0.1f),
                topRight = NormPoint(0.9f, 0.1f),
                bottomRight = NormPoint(0.9f, 0.1f),
                bottomLeft = NormPoint(0.1f, 0.1f),
            )
        }
    }

    /** Mean brightness over the central half of an image, as 0..255. */
    private fun centralMean(image: RgbImage): Float {
        val x0 = image.width / 4
        val y0 = image.height / 4
        var acc = 0.0
        var n = 0
        for (y in y0 until image.height - y0) {
            for (x in x0 until image.width - x0) {
                acc += image.red(x, y)
                n++
            }
        }
        return (acc / n).toFloat()
    }

    /**
     * Paint the frame black inside the quad's convex hull and white outside.
     *
     * The bilinear patch `pointAt` maps the unit square onto is contained in the hull of the
     * four corners, so filling the hull guarantees every interior sample is black while
     * leaving the outside as a genuine contrast for the warp to be measured against.
     */
    private fun paintQuad(frame: Int, quad: RgbQuad): RgbImage {
        val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        val out = ByteArray(frame * frame * 3)
        for (y in 0 until frame) {
            for (x in 0 until frame) {
                val px = (x + 0.5f) / frame
                val py = (y + 0.5f) / frame
                val inside = (0 until 4).all { edge ->
                    val a = corners[edge]
                    val b = corners[(edge + 1) % 4]
                    val cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
                    cross >= -HULL_TOLERANCE
                }
                val v = if (inside) 0 else 255
                val i = (y * frame + x) * 3
                out[i] = v.toByte()
                out[i + 1] = v.toByte()
                out[i + 2] = v.toByte()
            }
        }
        return RgbImage(frame, frame, out)
    }

    @Test
    fun `centre crop returns the requested size`() {
        val source = SyntheticImages.toRgb(SyntheticImages.textured(640, 480))
        val patch = imaging.centreCropGray(source, NormRect(0.25f, 0.25f, 0.5f, 0.5f), size = 256)
        assertEquals(256, patch.width)
        assertEquals(256, patch.height)
        assertEquals(256 * 256, patch.pixels.size)
    }

    @Test
    fun `a region larger than the frame is clamped rather than crashing`() {
        val source = SyntheticImages.toRgb(SyntheticImages.textured(120, 120))
        val patch = imaging.centreCropGray(source, NormRect(0.0f, 0.0f, 1f, 1f), size = 64)
        assertEquals(64, patch.width)
        assertEquals(64, patch.height)
    }

    @Test
    fun `a gray PNG round-trips through disk within one 8-bit step`() {
        val dir = createTempDirectory("kasoti-png")
        try {
            val original = GrayImage.fromBytes(8, 8, ByteArray(64) { (it * 4).toByte() })
            val file = dir.resolve("patch.png")
            imaging.writeGrayPng(original, file)
            val restored = imaging.readGray(file)
            assertEquals(8, restored.width)
            assertEquals(8, restored.height)
            for (i in original.pixels.indices) {
                assertTrue(abs(original.pixels[i] - restored.pixels[i]) <= 1f / 255f + 1e-6f)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writeGrayPng creates missing parent directories`() {
        val dir = createTempDirectory("kasoti-png-nested")
        try {
            val file = dir.resolve("a").resolve("b").resolve("patch.png")
            imaging.writeGrayPng(GrayImage.fromBytes(4, 4, ByteArray(16)), file)
            assertTrue(Files.exists(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val HULL_TOLERANCE = 1e-3f
    }
}

class NormRectTest {

    @Test
    fun `centre is computed from the origin and extent`() {
        val rect = NormRect(0.2f, 0.4f, 0.4f, 0.2f)
        assertEquals(0.4f, rect.centreX)
        assertEquals(0.5f, rect.centreY)
    }

    @Test
    fun `a zero-extent rect is rejected`() {
        assertFailsWith<IllegalArgumentException> { NormRect(0f, 0f, 0f, 1f) }
    }

    @Test
    fun `a negative origin is rejected`() {
        assertFailsWith<IllegalArgumentException> { NormRect(-0.1f, 0f, 1f, 1f) }
    }
}

class RgbQuadTest {

    @Test
    fun `a full frame has unit extent`() {
        val quad = RgbQuad.fullFrame()
        assertEquals(1f, quad.width)
        assertEquals(1f, quad.height)
    }

    @Test
    fun `pointAt maps the unit square onto the corners`() {
        val quad = RgbQuad.fullFrame()
        assertEquals(0f, quad.pointAt(0f, 0f).x)
        assertEquals(1f, quad.pointAt(1f, 0f).x)
        assertEquals(1f, quad.pointAt(1f, 1f).y)
        assertEquals(0.5f, quad.pointAt(0.5f, 0.5f).x)
    }

    @Test
    fun `a non-finite corner is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RgbQuad(
                NormPoint(Float.NaN, 0f),
                NormPoint(1f, 0f),
                NormPoint(1f, 1f),
                NormPoint(0f, 1f),
            )
        }
    }
}
