package dev.kasoti.factory

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feature vector handed to the print-process classifier.
 *
 * These are the quantities a print process actually leaves behind, and each is there for a
 * stated reason rather than because it "seemed informative":
 *
 * - [peakFrequency] — the lattice pitch of offset/dyesub screens, in cycles per patch.
 * - [peakiness]      — how *regular* that lattice is; the single strongest separator between
 *                      a deterministic screen and stochastic dithering.
 * - [bandEnergy]     — how much of the spectrum sits in the halftone band at all.
 * - [highFrequencyEnergy] — toner speckle and inkjet dither push energy upward.
 * - [spectralSlope]  — separates fine speckle from coarse dither.
 * - [lbpBins]        — uniform-LBP-59 histogram; captures local micro-texture that the global
 *                      spectrum cannot see (ink bleed vs toner edge).
 * - [stdDev], [edgeDensity] — gross contrast and edge statistics.
 */
data class MacroFeatures(
    val peakFrequency: Float,
    val peakiness: Float,
    val bandEnergy: Float,
    val highFrequencyEnergy: Float,
    val spectralSlope: Float,
    val stdDev: Float,
    val edgeDensity: Float,
    val lbpBins: FloatArray,
) {
    /** Flattened, scale-normalised vector in the order the trained weights expect. */
    fun toVector(): FloatArray {
        val out = FloatArray(NUMERIC_FEATURES + lbpBins.size)
        out[0] = peakFrequency / Spectrum.PATCH
        out[1] = ln1p(peakiness) / 6f
        out[2] = bandEnergy
        out[3] = highFrequencyEnergy
        out[4] = spectralSlope
        out[5] = stdDev
        out[6] = edgeDensity
        for (i in lbpBins.indices) out[NUMERIC_FEATURES + i] = lbpBins[i]
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MacroFeatures) return false
        return peakFrequency == other.peakFrequency &&
            peakiness == other.peakiness &&
            bandEnergy == other.bandEnergy &&
            highFrequencyEnergy == other.highFrequencyEnergy &&
            spectralSlope == other.spectralSlope &&
            stdDev == other.stdDev &&
            edgeDensity == other.edgeDensity &&
            lbpBins.contentEquals(other.lbpBins)
    }

    override fun hashCode(): Int {
        var result = peakFrequency.hashCode()
        result = 31 * result + peakiness.hashCode()
        result = 31 * result + bandEnergy.hashCode()
        result = 31 * result + highFrequencyEnergy.hashCode()
        result = 31 * result + spectralSlope.hashCode()
        result = 31 * result + stdDev.hashCode()
        result = 31 * result + edgeDensity.hashCode()
        result = 31 * result + lbpBins.contentHashCode()
        return result
    }

    companion object {
        const val NUMERIC_FEATURES = 7
        const val LBP_BINS = 59
        const val TOTAL = NUMERIC_FEATURES + LBP_BINS
    }
}

private fun ln1p(x: Float): Float = kotlin.math.ln(1f + x)

/** Local binary pattern texture descriptor (DESIGN.md §5, FR-F1). */
object Lbp {

    /**
     * Uniform LBP with 59 bins, computed over a 3x3 neighbourhood on the 8-neighbour ring.
     *
     * 59 rather than 256 bins because uniform patterns — those whose circular transitions are
     * all 0 or all 1 — carry the shape of the micro-texture, while the remaining patterns are
     * noise-dominated and split the signal across bins that individually mean little. A
     * blank patch produces a single populated bin; toner speckle spreads across many.
     *
     * @return a histogram of [MacroFeatures.LBP_BINS] values summing to 1.
     */
    fun histogram(image: GrayImage, bins: Int = MacroFeatures.LBP_BINS): FloatArray {
        val hist = FloatArray(bins)
        val w = image.width
        val h = image.height
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = image[x, y]
                var code = 0
                for (k in 0 until 8) {
                    val (dx, dy) = NEIGHBOURS[k]
                    val bit = if (image[x + dx, y + dy] >= c) 1 else 0
                    code = (code shl 1) or bit
                }
                if (isUniform(code)) {
                    hist[uniformTable[code]] += 1f
                    count++
                }
            }
        }
        if (count > 0) {
            for (i in hist.indices) hist[i] /= count
        }
        return hist
    }

    /**
     * Circular transition count of an 8-bit ring code.
     *
     * The comparison wraps from the last neighbour back to the first, because the LBP ring
     * is cyclic. A pattern is uniform when that count is 0 (all bits equal) or 2 (one
     * contiguous block of ones), which is 58 of the 256 possible codes.
     */
    private fun circularTransitions(code: Int): Int {
        var transitions = 0
        var previous = (code shr 7) and 1
        for (k in 1..8) {
            val bit = (code shr (8 - k)) and 1
            if (bit != previous) transitions++
            previous = bit
        }
        if (previous != ((code shr 7) and 1)) transitions++
        return transitions
    }

    private fun isUniform(code: Int): Boolean {
        val t = circularTransitions(code)
        return t == 0 || t == 2
    }

    private val NEIGHBOURS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(1, 0),
        intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0),
    )

    /**
     * The 58 rotationally-unique uniform patterns, indexed by their 8-bit code.
     * Derived by scanning all 256 codes rather than transcribed, so it cannot be mistyped,
     * and asserted to contain exactly 58 entries.
     */
    private val uniformTable: IntArray = IntArray(256) { -1 }.also { table ->
        var bin = 0
        for (code in 0 until 256) {
            if (isUniform(code)) table[code] = bin++
        }
        check(bin == 58) { "expected 58 rotationally unique uniform patterns, found $bin" }
    }
}

/** Sobel gradient magnitude statistics. */
object Edges {
    fun density(image: GrayImage, threshold: Float = 0.08f): Float {
        val w = image.width
        val h = image.height
        var strong = 0
        var total = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx =
                    -image[x - 1, y - 1] - 2 * image[x - 1, y] - image[x - 1, y + 1] +
                        image[x + 1, y - 1] + 2 * image[x + 1, y] + image[x + 1, y + 1]
                val gy =
                    -image[x - 1, y - 1] - 2 * image[x, y - 1] - image[x + 1, y - 1] +
                        image[x - 1, y + 1] + 2 * image[x, y + 1] + image[x + 1, y + 1]
                total++
                if (sqrt(gx * gx + gy * gy) > threshold) strong++
            }
        }
        return if (total == 0) 0f else strong.toFloat() / total
    }
}
