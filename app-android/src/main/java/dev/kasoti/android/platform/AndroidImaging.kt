package dev.kasoti.android.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.kasoti.android.field.Quad
import dev.kasoti.android.field.Resize
import dev.kasoti.factory.GrayImage
import kotlin.math.roundToInt

/**
 * `Bitmap` → `:core` types, and back (DESIGN.md §3, "Imaging: Bitmap→RGB/gray, resize").
 *
 * ## Intended home
 *
 * `:platform/androidMain`. This module does not own `:platform`, whose current `jvmMain`
 * actuals are written against `java.awt` and cannot be resolved from an Android module at all.
 * Adding an `androidMain` source set needs a `namespace`/`compileSdk` in that module's build
 * file and an ownership handover. This is a package rename away from where it belongs and no
 * logic will move — the logic is what the unit tests in this module cover.
 *
 * ## Normalisation, and what is deliberately absent
 *
 * The only normalisation applied is 0..255 → 0..1 (`GrayImage.fromBytes` does it) plus the
 * bilinear resize. Contrast stretch, white balance and gamma all "help" a classifier and all
 * destroy the thing it measures: the halftone lattice and the toner speckle *are* the evidence
 * (`:platform`'s `MacroCollector` says the same, and DATA.md §5's white-balance normalisation
 * belongs to the calibration-card routine, which is a different stage with its own provenance
 * record).
 *
 * ## No OpenCV
 *
 * DESIGN.md D4 rules it out. Everything needed — decode, resize, quad rectification, luma,
 * blur variance, FFT, LBP, NMS — is either here or `:core` maths, which is what keeps the build
 * free of an NDK sinkhole and the binary count near zero.
 */
class AndroidImaging {

    /**
     * Luma as `:core` defines it.
     *
     * Rec. 601 luma rather than a flat mean of the three channels: `:core`'s spectrum features
     * measure *structure*, and a flat mean would let a saturated blue ink and a saturated
     * red ink of the same density read as the same tone. 601 is also what the desktop
     * `ImageIoImaging` uses, so a patch collected on one platform and classified on the other
     * is measuring the same quantity (DESIGN.md §3's comparability law).
     */
    fun toGray(bitmap: Bitmap): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(width * height)
        for (i in out.indices) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return GrayImage(width, height, out)
    }

    /**
     * A luma crop for the analysis pass.
     *
     * The macro sharpness gate and the live quality meter both read a *region*, not the whole
     * frame: the shutter's own sharpness and the operator's fingers at the edge of the frame
     * are not evidence about the document. A caller that forgets to crop gets a meter that
     * measures the wrong thing, which is why the region is a required argument.
     */
    fun toGray(bitmap: Bitmap, region: NormalisedRect): GrayImage {
        val left = (region.x * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (region.y * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = ((region.x + region.width) * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((region.y + region.height) * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
        return toGray(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top))
    }

    /**
     * Rectify a [Quad] to a straight-on image (the deskew).
     *
     * Implemented with `Bitmap.createBitmap(source, x, y, w, h, matrix, filter)`, which is
     * Android's own bilinear sampler — the same primitive `:platform`'s `ImageIoImaging.cropQuad`
     * implements by hand on the JVM, so the two platforms deskew the same way and a crop recipe
     * survives the trip.
     *
     * The output width is supplied and the height follows the quad's own aspect ratio, so a
     * card photographed at an angle is rectified to the shape the desk actually is rather than
     * to a square the operator guessed.
     */
    fun cropQuad(source: Bitmap, quad: Quad, outputWidth: Int = DEFAULT_CROP_WIDTH): Bitmap {
        require(outputWidth > 0) { "output width must be positive, got $outputWidth" }
        val width = source.width.toFloat()
        val height = source.height.toFloat()
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft

        val quadWidth = maxOf(distance(tl, tr), distance(bl, br))
        val quadHeight = maxOf(distance(tl, bl), distance(tr, br))
        if (quadWidth < 1f || quadHeight < 1f) return source
        val outputHeight = (outputWidth * quadHeight / quadWidth).roundToInt().coerceIn(1, MAX_CROP_HEIGHT)

        // The unit square maps onto the quad, which is a perspective divide; an affine matrix
        // is the correct approximation for the small perspective error a hand-held capture has,
        // and a full projective warp would need a mesh and buy nothing at this size.
        val matrix = Matrix().apply {
            setPolyToPoly(SOURCE_QUAD, 0, DEST_QUAD, 0, 4)
            postScale(outputWidth / width, outputHeight / height)
            postTranslate(0f, 0f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Downscale for the analysis pass, so the frame cost is independent of sensor resolution. */
    fun downscale(source: Bitmap, maxSide: Int = ANALYSIS_SIDE): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** Resize a luma image to the macro corpus patch size (DATA.md §3: 256×256). */
    fun toPatchSize(gray: GrayImage, size: Int = 256): GrayImage = Resize.toSquare(gray, size)

    /**
     * Apply the rotation CameraX reported, and return a bitmap safe to sample.
     *
     * The rotation comes from `ImageProxy.imageInfo.rotationDegrees`, not from EXIF, and that
     * is a deliberate choice beyond saving a dependency: `androidx.exifinterface` would be a new
     * coordinate plus a parse of bytes the camera pipeline has already interpreted, and the two
     * can disagree. CameraX's value is the one the sensor actually applied.
     *
     * A quarter turn is lossless, which matters here: the macro layer is a frequency
     * measurement, and a resampling rotation would inject exactly the kind of low-frequency
     * artefact that the spectrum features would report as evidence. A 180° turn is also
     * lossless. No other value is a camera rotation, so the default is identity rather than a
     * guess.
     */
    fun applyRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap = when (rotationDegrees) {
        90 -> bitmap.rotate(90f)
        180 -> bitmap.rotate(180f)
        270 -> bitmap.rotate(270f)
        else -> bitmap
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap =
        Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)

    private fun distance(a: Quad.Point, b: Quad.Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** A region of a frame, as fractions. See [toGray]'s note on why this is required. */
    data class NormalisedRect(val x: Float, val y: Float, val width: Float, val height: Float) {
        init {
            require(x >= 0f && y >= 0f) { "region origin must be inside the frame" }
            require(width > 0f && height > 0f) { "region must have positive extent" }
        }

        companion object {
            /** The middle half of the frame: where a document's photo zone usually is. */
            val CENTRE_HALF = NormalisedRect(0.25f, 0.25f, 0.5f, 0.5f)

            /** The full frame, for the live quality meter on a whole-page capture. */
            val FULL = NormalisedRect(0f, 0f, 1f, 1f)
        }
    }

    companion object {
        /** Wide enough for an MRZ to be legible after rectification, small enough to OCR fast. */
        const val DEFAULT_CROP_WIDTH = 1_280

        /** The analysis pass never needs more than this on the long side. */
        const val ANALYSIS_SIDE = 640

        /**
         * A four-figure output height would be an OOM on a 2 GB device, and a quad that asks for
         * one is a degenerate quad — which [dev.kasoti.android.field.Quad.validate] should have
         * rejected. The cap is a backstop, not the check.
         */
        const val MAX_CROP_HEIGHT = 2_048

        private val SOURCE_QUAD = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        private val DEST_QUAD = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}
