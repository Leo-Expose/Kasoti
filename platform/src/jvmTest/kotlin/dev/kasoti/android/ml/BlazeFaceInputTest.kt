package dev.kasoti.android.ml

import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.platform.imaging.RgbImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The half of the detector that runs *before* inference: resize, then the `[-1, 1]` encoding.
 *
 * This is the fifth defect, the one that was not on the reported list. The old binding handed the
 * frame to the interpreter as a `ByteBuffer` of interleaved 0..255 bytes at the source resolution;
 * the model's input edge is FLOAT32 `[1, 128, 128, 3]` in `[-1, 1]`. Nothing about that fails
 * loudly at the call site — the interpreter accepts a buffer, and returns a tensor of the right
 * shape either way — which is why it is worth a test that states the right answer rather than
 * relying on the first device run to notice.
 *
 * The parity assertions against `:platform`'s `ImageIoImaging.resize` are the load-bearing part.
 * DESIGN.md §3's comparability law is about the two platforms computing the *same* pixels: call
 * `Bitmap.createScaledBitmap(..., filter = true)` here instead and the platforms feed the model
 * different bytes, which breaks the law before inference even starts and produces a small,
 * plausible, completely invisible accuracy loss.
 */
class BlazeFaceInputTest {

    private val imaging = ImageIoImaging()

    @Test
    fun `a frame becomes exactly the model input edge`() {
        val input = BlazeFaceInput.modelInput(frame(320, 240), 320, 240)
        assertEquals(128 * 128 * 3, input.size, "the tensor is [1, 128, 128, 3]")
        assertTrue(
            input.all { it >= BlazeFaceModel.INPUT_MIN && it <= BlazeFaceModel.INPUT_MAX },
            "every value must be inside the model's stated range, which the graph does not enforce",
        )
    }

    @Test
    fun `the encoding is the model card's transform, element for element`() {
        // 0 -> -1 and 255 -> +1, and the interior is the linear map between them.
        val rgb = byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 128.toByte(), 0, 0xFF.toByte())
        val encoded = BlazeFaceInput.encode(rgb, 3, 1)
        // Three pixels: black, white, and (128, 0, 255). Index (pixel * 3 + channel).
        assertEquals(-1f, encoded[0], 0f, "byte 0 -> -1")
        assertEquals(-1f, encoded[2], 0f, "byte 0 -> -1")
        assertEquals(1f, encoded[3], 0f, "byte 255 -> +1")
        assertEquals(1f, encoded[5], 0f, "byte 255 -> +1")
        assertEquals((128f / 255f) * 2f - 1f, encoded[6], 0f)
        assertEquals(-1f, encoded[7], 0f, "byte 0 -> -1")
        assertEquals(1f, encoded[8], 0f, "byte 255 -> +1")
    }

    @Test
    fun `the encoding is the one the desktop writes, bit for bit`() {
        val rgb = frame(37, 23)
        val mine = BlazeFaceInput.encode(rgb, 37, 23)
        val theirs = FloatArray(rgb.size)
        val buffer = java.nio.ByteBuffer
            .allocateDirect(rgb.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        for (i in rgb.indices) {
            val v = (rgb[i].toInt() and 0xFF) / 255f
            buffer.putFloat((v * 2f) - 1f)
        }
        buffer.rewind()
        buffer.asFloatBuffer().get(theirs)
        // Exactly equal, not merely close. The algebraically identical `(v / 127.5f) - 1f` can
        // differ in the last ulp, and DESIGN.md §3 is about both platforms computing the same
        // thing rather than an equivalent thing.
        assertTrue(mine.contentEquals(theirs), "the last-ulp difference is the one that matters here")
    }

    @Test
    fun `the resize is the desktop half-pixel bilinear, not the Android sampler`() {
        for ((w, h) in listOf(256 to 256, 320 to 240, 640 to 480, 91 to 37, 7 to 500)) {
            val rgb = frame(w, h)
            val mine = BlazeFaceInput.resizeRgb(rgb, w, h, 128, 128)
            val theirs = imaging.resize(RgbImage(w, h, rgb.copyOf()), 128, 128)
            assertTrue(
                mine.contentEquals(theirs.pixels),
                "resize disagreement at ${w}x$h: the two platforms would feed the model " +
                    "different pixels, which is a DESIGN.md §3 comparability break, not a rounding " +
                    "difference",
            )
        }
    }

    @Test
    fun `a same-size frame is passed through unchanged`() {
        val rgb = frame(128, 128)
        assertTrue(BlazeFaceInput.resizeRgb(rgb, 128, 128, 128, 128).contentEquals(rgb))
        // ...but as a copy: the caller must not be able to alias the capture buffer into the tensor.
        val copy = BlazeFaceInput.resizeRgb(rgb, 128, 128, 128, 128)
        copy[0] = 99
        assertEquals(rgb[0], frame(128, 128)[0], "resizeRgb must not alias its input")
    }

    @Test
    fun `the resize keeps the corners rather than collapsing to a border`() {
        // Half-pixel centres: at exactly 2x downscale, output (0,0) samples source (0.25, 0.25), so
        // it is a blend of the four corner-ish source pixels rather than a copy of (0,0). The naive
        // corner mapping would copy (0,0) exactly and shift the image by a quarter pixel.
        val rgb = ByteArray(4 * 4 * 3)
        for (i in 0 until 4 * 4) {
            rgb[i * 3] = 0
            rgb[i * 3 + 1] = 0
            rgb[i * 3 + 2] = (if (i == 0) 255 else 0).toByte()
        }
        val out = BlazeFaceInput.resizeRgb(rgb, 4, 4, 2, 2)
        assertTrue(out[2] != (-1).toByte(), "the first output pixel must be a blend, not the source corner")
        assertTrue(out[2] > 0, "and it must be a blend towards the bright source pixel, not zero")
    }

    @Test
    fun `a frame whose length disagrees with its dimensions is refused`() {
        assertFailsWith<IllegalArgumentException> { BlazeFaceInput.modelInput(ByteArray(10), 320, 240) }
        assertFailsWith<IllegalArgumentException> { BlazeFaceInput.resizeRgb(ByteArray(10), 320, 240, 8, 8) }
        assertFailsWith<IllegalArgumentException> { BlazeFaceInput.resizeRgb(ByteArray(3), 1, 1, 0, 4) }
        assertFailsWith<IllegalArgumentException> { BlazeFaceInput.encode(ByteArray(5), 2, 1) }
    }

    @Test
    fun `a non-square frame is resized on both axes independently`() {
        // The eval corpus is full of 4:3 captures and 1:1 document crops. Squashing the aspect
        // ratio into a square would change which face the model sees, and the desktop does not.
        val w = 200
        val h = 100
        val rgb = frame(w, h)
        val mine = BlazeFaceInput.resizeRgb(rgb, w, h, 128, 128)
        val theirs = imaging.resize(RgbImage(w, h, rgb.copyOf()), 128, 128)
        assertTrue(mine.contentEquals(theirs.pixels))
        assertEquals(128 * 128 * 3, mine.size)
    }

    @Test
    fun `a flat frame encodes to a constant, which is what makes the no-face fixture reproducible`() {
        val flat = ByteArray(64 * 48 * 3) { 128.toByte() }
        val encoded = BlazeFaceInput.encode(flat, 64, 48)
        val expected = (128f / 255f) * 2f - 1f
        assertTrue(encoded.all { abs(it - expected) == 0f })
    }

    private fun frame(width: Int, height: Int): ByteArray =
        ByteArray(width * height * 3) { i ->
            // Deterministic, high-frequency, and not a photograph of anything.
            ((i * 37 + (i / 3) * 11) % 256).toByte()
        }
}
