package dev.kasoti.factory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2-D Fourier analysis of a patch (DESIGN.md §5, FR-F1).
 *
 * Implemented directly in `:core` rather than pulled from an imaging library: DESIGN D4
 * rules out OpenCV, and the only operations we need are a separable FFT, a radial average
 * and a few reductions. A radix-2 Cooley–Tukey transform over a power-of-two square is
 * enough, and it makes the factory maths testable against synthetic patterns whose true
 * spectrum we chose ourselves.
 *
 * The transform is separable — rows first, then columns — which turns an O(n^4) naive DFT
 * into O(n^2 log n) and keeps the 256×256 macro budget well inside the 150 ms target.
 */
object Spectrum {

    const val PATCH = 256
    private const val LOG_N = 8 // log2(256)

    /**
     * Radial power profile of [image].
     *
     * @param bandLowRad lowest radius counted as halftone content, in cycles across the patch.
     * @param bandHighRad highest such radius.
     */
    fun radialProfile(image: GrayImage, bandLowRad: Float = 12f, bandHighRad: Float = 90f): HalftoneProfile {
        val n = PATCH
        val m = image.width
        require(m == n && image.height == n) {
            "spectrum analysis expects a ${n}x$n patch, got ${image.width}x${image.height}"
        }

        val mean = image.mean
        val re = Array(n) { FloatArray(n) }
        val im = Array(n) { FloatArray(n) }

        // Subtract the mean and apply a Hann window: the mean would otherwise put all energy
        // in the DC bin, and the window suppresses the spectral leakage that would otherwise
        // smear a genuine periodic peak into its neighbours.
        for (y in 0 until n) {
            for (x in 0 until n) {
                val wx = 0.5f * (1 - cos(2.0 * PI * x / (n - 1)))
                val wy = 0.5f * (1 - cos(2.0 * PI * y / (n - 1)))
                re[y][x] = (image[x, y] - mean) * (wx * wy).toFloat()
            }
        }

        fft2d(re, im)

        // Radial average of the power spectrum. Only the top-left quadrant is needed: the
        // spectrum of a real image is conjugate-symmetric, so it is mirrored.
        val half = n / 2
        val maxR = half - 1
        val radial = DoubleArray(maxR + 1)
        val counts = IntArray(maxR + 1)
        for (y in 0 until half) {
            for (x in 0 until half) {
                val r = sqrt((x * x + y * y).toDouble()).toInt()
                if (r in 0..maxR) {
                    val p = (re[y][x] * re[y][x] + im[y][x] * im[y][x]).toDouble()
                    radial[r] += p
                    counts[r]++
                }
            }
        }
        for (r in 0..maxR) {
            if (counts[r] > 0) radial[r] /= counts[r]
        }

        // DC is discarded: it only encodes overall brightness.
        radial[0] = 0.0

        val peakRadius = (bandLowRad.toInt()..minOf(bandHighRad.toInt(), maxR))
            .maxByOrNull { radial[it] } ?: 0
        val peakEnergy = if (peakRadius > 0) radial[peakRadius] else 0.0

        // "Peakiness" is measured against a local floor rather than the global mean, so a
        // bright specular reflection in one corner cannot masquerade as a halftone lattice.
        val windowStart = maxOf(1, peakRadius - 6)
        val windowEnd = minOf(maxR, peakRadius + 6)
        val neighbours = (windowStart..windowEnd).filter { it != peakRadius }.map { radial[it] }
        val floor = if (neighbours.isEmpty()) 0.0 else neighbours.sorted()[neighbours.size / 2]
        val peakiness = if (peakEnergy + floor <= 0.0) 1.0 else (peakEnergy / floor).coerceAtLeast(1.0)

        var bandTotal = 0.0
        var total = 0.0
        for (r in 1..maxR) {
            total += radial[r]
            if (r in bandLowRad.toInt()..bandHighRad.toInt()) bandTotal += radial[r]
        }
        val bandEnergy = if (total <= 0.0) 0.0 else (bandTotal / total).coerceIn(0.0, 1.0)

        var highFreq = 0.0
        var allFreq = 0.0
        for (r in 1..maxR) {
            allFreq += radial[r]
            if (r >= 64) highFreq += radial[r]
        }
        val highFrequencyEnergy = if (allFreq <= 0.0) 0.0 else (highFreq / allFreq).coerceIn(0.0, 1.0)

        // Least-squares slope of log(power) against log(radius) across the band. Fine
        // toner speckle and coarse dither differ mostly in this exponent.
        val slope = logLogSlope(radial, bandLowRad.toInt(), bandHighRad.toInt().coerceAtMost(maxR))

        return HalftoneProfile(
            peakFrequency = peakRadius.toFloat(),
            peakiness = peakiness.toFloat().coerceIn(1f, 1e6f),
            bandEnergy = bandEnergy.toFloat(),
            highFrequencyEnergy = highFrequencyEnergy.toFloat(),
            spectralSlope = slope.toFloat(),
        )
    }

    private fun logLogSlope(radial: DoubleArray, from: Int, to: Int): Double {
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        var n = 0
        for (r in from..to) {
            if (r <= 0 || radial[r] <= 0.0) continue
            val x = ln(r.toDouble())
            val y = ln(radial[r])
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
            n++
        }
        if (n < 3) return 0.0
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-12) return 0.0
        return (n * sxy - sx * sy) / denom
    }

    /** In-place 2-D FFT: rows then columns, each a radix-2 transform of length 256. */
    private fun fft2d(re: Array<FloatArray>, im: Array<FloatArray>) {
        val n = PATCH
        for (y in 0 until n) fft1d(re[y], im[y])
        for (x in 0 until n) {
            val colR = FloatArray(n)
            val colI = FloatArray(n)
            for (y in 0 until n) {
                colR[y] = re[y][x]
                colI[y] = im[y][x]
            }
            fft1d(colR, colI)
            for (y in 0 until n) {
                re[y][x] = colR[y]
                im[y][x] = colI[y]
            }
        }
    }

    /** Standard in-place iterative Cooley–Tukey on a power-of-two length. */
    private fun fft1d(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = (uRe + vRe).toFloat()
                    im[i + k] = (uIm + vIm).toFloat()
                    re[i + k + len / 2] = (uRe - vRe).toFloat()
                    im[i + k + len / 2] = (uIm - vIm).toFloat()
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        // LOG_N documents the transform depth for anyone auditing the constant.
        check(LOG_N == 8) { "radix-2 depth constant drifted from the patch size" }
    }
}
