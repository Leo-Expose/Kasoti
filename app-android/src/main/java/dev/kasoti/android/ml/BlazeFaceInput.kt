package dev.kasoti.android.ml

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Turning a captured frame into the detector's `input` tensor, in pure Kotlin.
 *
 * ## Why this is here and not in the TFLite binding
 *
 * The binding cannot be compiled or tested on a machine with no Android SDK, and the input
 * preparation is where two of the detector's load-bearing transforms live:
 *
 *  1. **The resize.** `:platform`'s `ImageIoImaging.resize` is a hand-written bilinear with
 *     **half-pixel centres** — `(dst + 0.5) * src / dst − 0.5` — and its comment says why: the
 *     naive corner mapping shifts the image by a quarter pixel at every scale step, and that shift
 *     is a low-frequency artefact of exactly the kind the macro spectrum features would report as
 *     evidence. Android's `Bitmap.createScaledBitmap(..., filter = true)` is a *different*
 *     sampler, so calling it here would make the two platforms feed the model different pixels and
 *     DESIGN.md §3's comparability law would be broken before inference even starts. The same
 *     half-pixel convention is already used by `dev.kasoti.android.field.Resize` for luma, so this
 *     is the module's existing convention rather than a third one.
 *  2. **The `[-1, 1]` encoding.** The graph has no input-normalisation op, so the caller owns the
 *     range ([BlazeFaceModel.INPUT_MIN]). Feeding raw `0..255` bytes to a FLOAT32 input is measured
 *     to drive `regressors` to absmax ≈ 4.2e6 versus ≈ 1.6e2 for the normalised encodings
 *     (spike 01 §3.2) — the network is being driven four orders of magnitude outside its training
 *     distribution.
 *
 * Both transforms are therefore one implementation, written once here and asserted equal to
 * `:platform`'s on the same bytes by `BlazeFaceInputTest`. The arithmetic is transcribed, including
 * the exact expression `(v / 255f) * 2f - 1f`: float multiplication is not associative, so writing
 * the algebraically identical `(v / 127.5f) - 1f` could differ in the last ulp, and DESIGN.md §3's
 * law is about the two platforms computing the *same* thing rather than an equivalent thing.
 */
object BlazeFaceInput {

    /** Interleaved 8-bit RGB, three bytes per pixel, row-major. */
    const val CHANNELS = BlazeFaceModel.INPUT_CHANNELS

    /** Bytes per float in the model's input tensor. */
    const val FLOAT_BYTES = 4

    /**
     * The full path: an interleaved-RGB frame of any size becomes the model's input tensor.
     *
     * @return `anchors`-independent: `side * side * 3` floats in `[-1, 1]`, NHWC, row-major, RGB.
     */
    fun modelInput(rgb: ByteArray, width: Int, height: Int, side: Int = BlazeFaceModel.INPUT_SIDE): FloatArray {
        val resized = resizeRgb(rgb, width, height, side, side)
        return encode(resized, side, side)
    }

    /**
     * Bilinear resize with half-pixel centres, transcribed from `:platform`'s `ImageIoImaging.resize`.
     *
     * @return `dstWidth * dstHeight * 3` bytes, row-major interleaved RGB.
     */
    fun resizeRgb(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): ByteArray {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "source frame must be non-empty, got ${sourceWidth}x$sourceHeight"
        }
        require(source.size == sourceWidth * sourceHeight * CHANNELS) {
            "expected ${sourceWidth * sourceHeight * CHANNELS} samples for " +
                "${sourceWidth}x$sourceHeight, got ${source.size}"
        }
        require(dstWidth > 0 && dstHeight > 0) { "resize target must be non-empty, got ${dstWidth}x$dstHeight" }
        if (sourceWidth == dstWidth && sourceHeight == dstHeight) return source.copyOf()

        val out = ByteArray(dstWidth * dstHeight * CHANNELS)
        val scaleX = sourceWidth.toFloat() / dstWidth
        val scaleY = sourceHeight.toFloat() / dstHeight

        for (y in 0 until dstHeight) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            val y0 = floor(sy).toInt().coerceIn(0, sourceHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)

            for (x in 0 until dstWidth) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val x0 = floor(sx).toInt().coerceIn(0, sourceWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)

                val d = (y * dstWidth + x) * CHANNELS
                for (c in 0 until CHANNELS) {
                    val p00 = source[(y0 * sourceWidth + x0) * CHANNELS + c].toInt() and 0xFF
                    val p01 = source[(y0 * sourceWidth + x1) * CHANNELS + c].toInt() and 0xFF
                    val p10 = source[(y1 * sourceWidth + x0) * CHANNELS + c].toInt() and 0xFF
                    val p11 = source[(y1 * sourceWidth + x1) * CHANNELS + c].toInt() and 0xFF
                    val top = p00 + (p01 - p00) * fx
                    val bottom = p10 + (p11 - p10) * fx
                    out[d + c] = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /**
     * `0..255` bytes to the model's `[-1, 1]` floats.
     *
     * `(v / 255f) * 2f - 1f`, element for element with `:platform`'s `encodeInput`. Tensors want
     * NHWC row-major RGB and this is that order, so the `i`-th float is
     * `((y * width + x) * 3 + channel)` — the same index expression the decode's caller uses to go
     * back out, which is what keeps the round trip checkable.
     */
    fun encode(rgb: ByteArray, width: Int, height: Int): FloatArray {
        require(rgb.size == width * height * CHANNELS) {
            "expected ${width * height * CHANNELS} samples for ${width}x$height, got ${rgb.size}"
        }
        val out = FloatArray(width * height * CHANNELS)
        var i = 0
        while (i < out.size) {
            val v = (rgb[i].toInt() and 0xFF) / 255f
            out[i] = (v * 2f) - 1f
            i++
        }
        return out
    }
}
