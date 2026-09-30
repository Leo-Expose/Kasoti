package dev.kasoti.platform.imaging

import dev.kasoti.factory.GrayImage

/**
 * A decoded 8-bit interleaved RGB raster, row-major, three bytes per pixel.
 *
 * Deliberately the plainest possible container: `:core` receives raw samples and owns every
 * decision derived from them (DESIGN D4 — no OpenCV anywhere). If this type grew a `blur()`
 * or `cropToFace()` method, the reproducibility argument for hand-rolling the maths would
 * quietly disappear.
 */
class RgbImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "image must be non-empty (${width}x$height)" }
        require(pixels.size == width * height * CHANNELS) {
            "expected ${width * height * CHANNELS} samples for ${width}x$height, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int {
        val i = (y * width + x) * CHANNELS
        return ((pixels[i].toInt() and 0xFF) shl 16) or
            ((pixels[i + 1].toInt() and 0xFF) shl 8) or
            (pixels[i + 2].toInt() and 0xFF)
    }

    /** Red channel of the pixel at (x, y), 0..255. */
    fun red(x: Int, y: Int): Int = pixels[(y * width + x) * CHANNELS].toInt() and 0xFF

    /** Green channel of the pixel at (x, y), 0..255. */
    fun green(x: Int, y: Int): Int = pixels[(y * width + x) * CHANNELS + 1].toInt() and 0xFF

    /** Blue channel of the pixel at (x, y), 0..255. */
    fun blue(x: Int, y: Int): Int = pixels[(y * width + x) * CHANNELS + 2].toInt() and 0xFF

    fun toGray(): GrayImage {
        val out = ByteArray(width * height)
        for (i in out.indices) {
            val p = i * CHANNELS
            // Rec.601 luma in fixed-point integer arithmetic. Integer, not float: this runs
            // per pixel on every capture and the 1/1000 rounding is far below anything the
            // macro features can resolve.
            val luma = 299 * (pixels[p].toInt() and 0xFF) +
                587 * (pixels[p + 1].toInt() and 0xFF) +
                114 * (pixels[p + 2].toInt() and 0xFF)
            out[i] = ((luma + 500) / 1000).coerceIn(0, 255).toByte()
        }
        return GrayImage.fromBytes(width, height, out)
    }

    companion object {
        const val CHANNELS = 3
    }
}

/** A point in normalised image coordinates: (0,0) top-left, (1,1) bottom-right. */
data class NormPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "corner coordinates must be finite" }
    }
}

/**
 * Four normalised corners of a document (or MRZ band) in reading order.
 *
 * Normalised rather than pixel coordinates because a card is captured at wildly different
 * resolutions across the eval corpus; a quad defined in fractions of the frame transfers
 * unchanged from a 12 MP phone frame to a 2 MP desktop import.
 */
data class RgbQuad(
    val topLeft: NormPoint,
    val topRight: NormPoint,
    val bottomRight: NormPoint,
    val bottomLeft: NormPoint,
) {
    /** Mean of the two horizontal edge lengths, in normalised units. */
    val width: Float get() = (topLeft.distanceTo(topRight) + bottomLeft.distanceTo(bottomRight)) / 2f

    /** Mean of the two vertical edge lengths, in normalised units. */
    val height: Float get() = (topLeft.distanceTo(bottomLeft) + topRight.distanceTo(bottomRight)) / 2f

    init {
        require(width > MIN_EXTENT && height > MIN_EXTENT) {
            "a degenerate quad (${width}x$height) would crop to nothing"
        }
    }

    /** The quad's position at `(u, v)` in the unit square, by bilinear interpolation. */
    fun pointAt(u: Float, v: Float): NormPoint {
        val topX = topLeft.x + (topRight.x - topLeft.x) * u
        val topY = topLeft.y + (topRight.y - topLeft.y) * u
        val bottomX = bottomLeft.x + (bottomRight.x - bottomLeft.x) * u
        val bottomY = bottomLeft.y + (bottomRight.y - bottomLeft.y) * u
        return NormPoint(topX + (bottomX - topX) * v, topY + (bottomY - topY) * v)
    }

    fun scaled(factor: Float): RgbQuad = RgbQuad(
        topLeft = NormPoint(topLeft.x * factor, topLeft.y * factor),
        topRight = NormPoint(topRight.x * factor, topRight.y * factor),
        bottomRight = NormPoint(bottomRight.x * factor, bottomRight.y * factor),
        bottomLeft = NormPoint(bottomLeft.x * factor, bottomLeft.y * factor),
    )

    companion object {
        /** A quad smaller than this collapses to too few source pixels to be evidence. */
        const val MIN_EXTENT = 1e-3f

        /** The full frame — the identity quad, used when a caller has no corners. */
        fun fullFrame(): RgbQuad = RgbQuad(
            topLeft = NormPoint(0f, 0f),
            topRight = NormPoint(1f, 0f),
            bottomRight = NormPoint(1f, 1f),
            bottomLeft = NormPoint(0f, 1f),
        )
    }
}

private fun NormPoint.distanceTo(other: NormPoint): Float {
    val dx = x - other.x
    val dy = y - other.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
