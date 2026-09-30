package dev.kasoti.platform.imaging

import dev.kasoti.factory.GrayImage
import java.awt.image.BufferedImage
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Raised when a file cannot be decoded as an image. */
class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ImageIO-backed decode / resize / deskew for the desktop path (DESIGN.md §3).
 *
 * Scope is intentionally narrow: turn bytes into [RgbImage] or [GrayImage], resize, and
 * deskew a quadrilateral. Every downstream operation — luma, blur variance, FFT, LBP,
 * NMS — is `:core` maths, because DESIGN D4 rules OpenCV out of the build and a second
 * implementation of the same maths is a second set of bugs.
 */
class ImageIoImaging {

    /** Decoded image, still in whatever colour space the file used. */
    fun readBufferedImage(source: Path): BufferedImage = Files.newInputStream(source).use { readBufferedImage(it) }

    fun readBufferedImage(stream: InputStream): BufferedImage {
        val image = stream.use { ImageIO.read(it) }
            ?: throw ImageDecodeException("no ImageIO reader recognised this stream")
        return flattenToRgb(image)
    }

    fun readRgb(source: Path): RgbImage = toRgb(readBufferedImage(source))

    fun readGray(source: Path): GrayImage = readRgb(source).toGray()

    /** Flatten alpha onto white so a transparent PNG background does not read as black. */
    private fun flattenToRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, out.width, out.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    fun toRgb(source: BufferedImage): RgbImage {
        val out = ByteArray(source.width * source.height * RgbImage.CHANNELS)
        var i = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val rgb = source.getRGB(x, y)
                out[i++] = ((rgb shr 16) and 0xFF).toByte()
                out[i++] = ((rgb shr 8) and 0xFF).toByte()
                out[i++] = (rgb and 0xFF).toByte()
            }
        }
        return RgbImage(source.width, source.height, out)
    }

    /**
     * Bilinear resize.
     *
     * Half-pixel centres (`(dst + 0.5) * src / dst`) rather than the naive `dst * src / dst`
     * corner mapping: the latter shifts the image by a quarter pixel at every scale step,
     * and that shift is a low-frequency artefact which is exactly the kind of thing the
     * macro spectrum features would happily report as evidence.
     */
    fun resize(source: RgbImage, width: Int, height: Int): RgbImage {
        require(width > 0 && height > 0) { "resize target must be non-empty, got ${width}x$height" }
        if (source.width == width && source.height == height) return source

        val out = ByteArray(width * height * RgbImage.CHANNELS)
        val scaleX = source.width.toFloat() / width
        val scaleY = source.height.toFloat() / height

        for (y in 0 until height) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            val y0 = floor(sy).toInt().coerceIn(0, source.height - 1)
            val y1 = (y0 + 1).coerceAtMost(source.height - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)

            for (x in 0 until width) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val x0 = floor(sx).toInt().coerceIn(0, source.width - 1)
                val x1 = (x0 + 1).coerceAtMost(source.width - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)

                val d = (y * width + x) * RgbImage.CHANNELS
                for (c in 0 until RgbImage.CHANNELS) {
                    val p00 = source.pixels[(y0 * source.width + x0) * RgbImage.CHANNELS + c].toInt() and 0xFF
                    val p01 = source.pixels[(y0 * source.width + x1) * RgbImage.CHANNELS + c].toInt() and 0xFF
                    val p10 = source.pixels[(y1 * source.width + x0) * RgbImage.CHANNELS + c].toInt() and 0xFF
                    val p11 = source.pixels[(y1 * source.width + x1) * RgbImage.CHANNELS + c].toInt() and 0xFF
                    val top = p00 + (p01 - p00) * fx
                    val bottom = p10 + (p11 - p10) * fx
                    out[d + c] = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return RgbImage(width, height, out)
    }

    /**
     * Deskew: map the unit square through the quad and sample bilinearly.
     *
     * Output width is supplied; the height follows from the quad's own aspect ratio so a
     * card that was photographed at an angle is rectified to the shape the desk actually is,
     * rather than to a square the operator typed in.
     */
    fun cropQuad(source: RgbImage, quad: RgbQuad, outputWidth: Int): RgbImage {
        require(outputWidth > 0) { "output width must be positive, got $outputWidth" }
        val aspect = (quad.height / quad.width).coerceAtLeast(MIN_ASPECT)
        val outputHeight = ceil(outputWidth * aspect).toInt().coerceAtLeast(1)

        val out = ByteArray(outputWidth * outputHeight * RgbImage.CHANNELS)
        for (y in 0 until outputHeight) {
            val v = if (outputHeight == 1) 0f else y.toFloat() / (outputHeight - 1)
            for (x in 0 until outputWidth) {
                val u = if (outputWidth == 1) 0f else x.toFloat() / (outputWidth - 1)
                val p = quad.pointAt(u, v)
                val sx = (p.x * source.width - 0.5f).coerceIn(-0.5f, source.width - 0.5f)
                val sy = (p.y * source.height - 0.5f).coerceIn(-0.5f, source.height - 0.5f)
                val d = (y * outputWidth + x) * RgbImage.CHANNELS
                for (c in 0 until RgbImage.CHANNELS) {
                    out[d + c] = sampleChannel(source, sx, sy, c)
                }
            }
        }
        return RgbImage(outputWidth, outputHeight, out)
    }

    private fun sampleChannel(source: RgbImage, sx: Float, sy: Float, channel: Int): Byte {
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = (x0 + 1).coerceAtMost(source.width - 1)
        val y1 = (y0 + 1).coerceAtMost(source.height - 1)
        val cx0 = x0.coerceIn(0, source.width - 1)
        val cx1 = x1.coerceIn(0, source.width - 1)
        val cy0 = y0.coerceIn(0, source.height - 1)
        val cy1 = y1.coerceIn(0, source.height - 1)
        val fx = (sx - cx0).coerceIn(0f, 1f)
        val fy = (sy - cy0).coerceIn(0f, 1f)

        val p00 = source.pixels[(cy0 * source.width + cx0) * RgbImage.CHANNELS + channel].toInt() and 0xFF
        val p01 = source.pixels[(cy0 * source.width + cx1) * RgbImage.CHANNELS + channel].toInt() and 0xFF
        val p10 = source.pixels[(cy1 * source.width + cx0) * RgbImage.CHANNELS + channel].toInt() and 0xFF
        val p11 = source.pixels[(cy1 * source.width + cx1) * RgbImage.CHANNELS + channel].toInt() and 0xFF
        val top = p00 + (p01 - p00) * fx
        val bottom = p10 + (p11 - p10) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255).toByte()
    }

    /**
     * The centre [size]×[size] square of a normalised rectangle, clamped to the frame.
     *
     * Macro patches want texture, not the card's margins, so the operator marks a rough
     * region and this takes the part of it that is actually inside the image. Coordinates
     * are fractions of the frame so a patch recipe survives a re-capture at another size.
     */
    fun centreCropGray(source: RgbImage, region: NormRect, size: Int): GrayImage {
        require(size > 0) { "patch size must be positive, got $size" }
        val sizeF = size.toFloat()
        val sideX = (region.width * source.width).coerceAtMost(source.width.toFloat())
        val sideY = (region.height * source.height).coerceAtMost(source.height.toFloat())
        val side = minOf(sideX, sideY, sizeF)
        val centreX = region.centreX * source.width
        val centreY = region.centreY * source.height
        val left = (centreX - side / 2f).coerceIn(0f, (source.width - side).coerceAtLeast(0f))
        val top = (centreY - side / 2f).coerceIn(0f, (source.height - side).coerceAtLeast(0f))

        val sideInt = ceil(side).toInt().coerceIn(1, minOf(source.width, source.height))
        val leftInt = left.toInt().coerceIn(0, source.width - sideInt)
        val topInt = top.toInt().coerceIn(0, source.height - sideInt)

        val cropPixels = ByteArray(sideInt * sideInt * RgbImage.CHANNELS)
        for (row in 0 until sideInt) {
            val from = ((topInt + row) * source.width + leftInt) * RgbImage.CHANNELS
            val to = from + sideInt * RgbImage.CHANNELS
            source.pixels.copyInto(cropPixels, (row * sideInt) * RgbImage.CHANNELS, from, to)
        }
        return resize(RgbImage(sideInt, sideInt, cropPixels), size, size).toGray()
    }

    /** Write a grayscale PNG. Used by the macro collector (DATA.md §3). */
    fun writeGrayPng(image: GrayImage, target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, encodeGrayPng(image))
    }

    /**
     * Encode a grayscale PNG into memory.
     *
     * Separate from [writeGrayPng] because the case bundle needs the bytes and the
     * collector needs a file, and neither should have to write a temporary one to get the
     * other.
     */
    fun encodeGrayPng(image: GrayImage): ByteArray {
        val bytes = ByteArray(image.width * image.height)
        for (i in bytes.indices) {
            bytes[i] = (image.pixels[i] * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        val buffer = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        buffer.raster.setDataElements(0, 0, image.width, image.height, bytes)
        val out = java.io.ByteArrayOutputStream(bytes.size / 2)
        if (!ImageIO.write(buffer, "png", out)) {
            throw ImageDecodeException("no PNG writer is registered in this JRE")
        }
        return out.toByteArray()
    }

    private companion object {
        /** Guards against a sliver quad demanding a four-figure output height. */
        const val MIN_ASPECT = 1e-3f
    }
}

/** A normalised axis-aligned rectangle, as fractions of the frame. */
data class NormRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centreX: Float get() = x + width / 2f
    val centreY: Float get() = y + height / 2f

    init {
        require(x >= 0f && y >= 0f) { "rect origin must be inside the frame" }
        require(width > 0f && height > 0f) { "rect must have positive extent" }
    }
}
