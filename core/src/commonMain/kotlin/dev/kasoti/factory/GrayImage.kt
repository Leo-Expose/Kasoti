package dev.kasoti.factory

/**
 * A single-channel image in the range 0.0..1.0, row-major.
 *
 * Grayscale conversion happens in `:platform` (which owns decoding); `:core` only ever sees
 * normalised samples. Keeping the type tiny and explicit is what lets the factory maths be
 * tested against synthetic patterns with a known ground truth.
 */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0) { "image must be non-empty" }
        require(pixels.size == width * height) {
            "expected ${width * height} samples, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    val mean: Float get() = pixels.sum().toFloat() / pixels.size

    val stdDev: Float
        get() {
            val m = mean
            var acc = 0.0
            for (p in pixels) {
                val d = p - m
                acc += d * d
            }
            return kotlin.math.sqrt(acc / pixels.size).toFloat()
        }

    companion object {
        fun of(width: Int, height: Int, pixels: FloatArray): GrayImage = GrayImage(width, height, pixels)

        /** Build from 0..255 bytes, the form a platform decoder naturally produces. */
        fun fromBytes(width: Int, height: Int, bytes: ByteArray): GrayImage =
            GrayImage(width, height, FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f })
    }
}

/**
 * Print-process classes (FR-F1).
 *
 * `UNKNOWN` is a first-class outcome, not a failure: a patch we cannot classify must
 * degrade to AMBER/WORN rather than being forced into the nearest class (FUSION.md §5).
 */
enum class ProcessLabel {
    OFFSET,
    INKJET,
    LASER,
    DYESUB,
    SCREEN,
    PHOTOCOPY,
    UNKNOWN,
}

/** Per-patch classification output including the margin that the fusion rules depend on. */
data class PatchResult(
    val label: ProcessLabel,
    /** Score gap between the winning class and the runner-up. Low margin means "don't trust this". */
    val margin: Float,
    val scores: Map<ProcessLabel, Float>,
    val features: MacroFeatures,
    val halftone: HalftoneProfile,
)

/** Document-level aggregation of the photo zone and the text zone (FR-F1). */
data class DocProcess(
    val photoZone: PatchResult,
    val textZone: PatchResult,
    val agreement: Agreement,
) {
    enum class Agreement {
        /** Both zones agree and both are confident — the process reading is trustworthy. */
        MATCH,

        /** Zones disagree with both margins high: a collage, a paste-over, a reprint. */
        MISMATCH,

        /** Zones disagree but a margin is low: worn, blurred, or genuinely ambiguous. */
        UNCERTAIN,

        /** No usable macro evidence at all. */
        NO_EVIDENCE,
    }
}

/**
 * Radial power-spectrum profile of a patch.
 *
 * The discriminator between processes is not "how much texture" but *how regular* it is:
 * offset lithography lays a deterministic rosette or dot lattice, producing a sharp peak at
 * one characteristic spatial frequency, whereas inkjet dithering spreads energy across a
 * broad band. So the features that matter are the frequency of the strongest radial peak,
 * how peaked it is relative to the local floor, and how much energy sits in the expected
 * halftone band versus everywhere else.
 */
data class HalftoneProfile(
    /** Radius of the strongest radial peak, in cycles across the patch. */
    val peakFrequency: Float,
    /** (peak / local median) of the radial profile, 1.0 meaning flat. */
    val peakiness: Float,
    /** Share of total spectral energy inside the halftone band, 0..1. */
    val bandEnergy: Float,
    /** Overall energy of the image's high-frequency content. */
    val highFrequencyEnergy: Float,
    /** Slope of the radial profile, distinguishing fine toner speckle from coarse dither. */
    val spectralSlope: Float,
)
