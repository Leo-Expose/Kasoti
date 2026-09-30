package dev.kasoti.eval.suites

import dev.kasoti.factory.GrayImage
import dev.kasoti.factory.ProcessLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic synthetic macro patches.
 *
 * The harness needs *something* to run the macro path on before any real card has been
 * captured, and EVAL.md §8 forbids borrowing D-MACRO to do it. So the patches are
 * generated from a fixed seed with a documented, closed-form recipe: the same 8-bit pixels
 * come out on every run, on every target, in Kotlin and in NumPy.
 *
 * That determinism is what makes the NumPy↔Kotlin cross-check in `eval/tools/` possible
 * without shipping a single image (DATA.md §1: no biometric media in the repo, and these
 * are not biometric but the same discipline applies to anything patch-shaped). The recipe
 * is duplicated in `eval/tools/patches.py`; the two must agree, and `cross_check.py` is
 * what proves they do.
 *
 * The textures are *plausible*, not real: a halftone lattice with a known pitch, speckle
 * with a known density, a gradient with a known slope. That is enough to exercise the
 * feature path and the FFT, and it is emphatically not enough to claim anything about real
 * print processes — every macro number derived from these is labelled `synthetic` in
 * `metrics.json` and carries that caveat into the gate's `detail`.
 */
object SpectrumPatch {

    /** `:core`'s spectrum path requires exactly this size. */
    const val SIZE = 256

    const val SEED = 20_260_931L

    fun lattice(): GrayImage = render { x, y, _ ->
        // A square lattice at period 8: a strong, deliberate spectral peak.
        val on = (x % 8) < 4 && (y % 8) < 4
        if (on) 0.85f else 0.25f
    }

    fun rosette(): GrayImage = render { x, y, _ ->
        // A 45° rosette, the characteristic offset screen.
        val d = kotlin.math.abs((x + y) % 12 - 6)
        if (d < 3) 0.8f else 0.3f
    }

    fun speckle(): GrayImage = render { x, y, n ->
        // Deterministic dither: a hash of (x, y, seed) thresholded at 0.6.
        val h = hash2(x, y, n)
        if (h > 0.6) 0.75f else 0.2f
    }

    fun gradient(): GrayImage = render { x, _, _ ->
        (x.toFloat() / (SIZE - 1)) * 0.8f + 0.1f
    }

    fun flat(): GrayImage = GrayImage(SIZE, SIZE, FloatArray(SIZE * SIZE) { 0.5f })

    /** One patch per process class, plus a deliberately ambiguous one. */
    fun forLabel(label: ProcessLabel): GrayImage = when (label) {
        ProcessLabel.OFFSET -> rosette()
        ProcessLabel.LASER -> lattice()
        ProcessLabel.INKJET -> speckle()
        ProcessLabel.DYESUB -> gradient()
        ProcessLabel.SCREEN -> render { x, y, n -> if ((x / 3 + y / 3) % 2 == 0) 0.9f else 0.1f }
        ProcessLabel.PHOTOCOPY -> render { x, y, _ ->
            // Low-contrast, blurred copy of a lattice.
            val on = (x % 16) < 8 && (y % 16) < 8
            if (on) 0.62f else 0.48f
        }
        ProcessLabel.UNKNOWN -> flat()
    }

    /**
     * @param n a per-patch nonce, so two calls to the same recipe do not produce identical
     *   bytes and a metrics table cannot accidentally be built from one patch.
     */
    private inline fun render(pixel: (x: Int, y: Int, nonce: Int) -> Float): GrayImage {
        val out = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                out[y * SIZE + x] = pixel(x, y, 0)
            }
        }
        return GrayImage(SIZE, SIZE, out)
    }

    /**
     * A stable integer hash. Chosen over `kotlin.random.Random` because that class is not
     * contractually stable across platforms, and these pixels have to be reproducible in
     * NumPy on a laptop as well as on a JVM.
     */
    fun hash2(x: Int, y: Int, nonce: Int): Float {
        var h = x * 374_761_393 + y * 668_265_263 + nonce * 2_147_483_647
        h = (h xor (h shr 13)) * 1_274_126_177
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFF) / 2_147_483_647f
    }

    /** Quantise to 8-bit and normalise, the way a decoded capture arrives. */
    fun toBytes(image: GrayImage): ByteArray = ByteArray(image.pixels.size) { index ->
        ((image.pixels[index].coerceIn(0f, 1f) * 255f).toInt() and 0xFF).toByte()
    }
}
