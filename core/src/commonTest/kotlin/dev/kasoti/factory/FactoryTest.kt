package dev.kasoti.factory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Synthetic patch builders with a *known* ground truth.
 *
 * Testing print-forensics maths against real scans would be circular until the model is
 * trained. Instead these generate textures whose spectral content we choose exactly, so the
 * FFT, radial profile and LBP can each be checked against an answer we know.
 */
object Synthetic {

    const val N = 256

    /** A deterministic dot lattice (offset-style screen) at [frequency] cycles across the patch. */
    fun lattice(frequency: Float, amplitude: Float = 0.35f, seed: Long = 1): GrayImage {
        val px = FloatArray(N * N)
        for (y in 0 until N) {
            for (x in 0 until N) {
                val v = 0.5f + amplitude * cos(2 * PI.toFloat() * frequency * x / N) *
                    cos(2 * PI.toFloat() * frequency * y / N)
                px[y * N + x] = v.coerceIn(0f, 1f)
            }
        }
        return GrayImage(N, N, px)
    }

    /**
     * Concentric rings at [frequency] cycles across the patch.
     *
     * This is the pattern whose spectral peak radius equals the frequency exactly, so it is
     * the right probe for "did the radial profile find the right bin". [lattice] deliberately
     * peaks at `frequency * sqrt(2)` because `cos(ax)cos(by) = ½[cos(a-b) + cos(a+b)]` puts
     * all its energy on the (±f,±f) component — see the test that asserts that.
     */
    fun ringPattern(frequency: Float, amplitude: Float = 0.35f): GrayImage {
        val px = FloatArray(N * N)
        for (y in 0 until N) {
            for (x in 0 until N) {
                val r = kotlin.math.sqrt(((x - N / 2) * (x - N / 2) + (y - N / 2) * (y - N / 2)).toFloat())
                val v = 0.5f + amplitude * cos(2 * PI.toFloat() * frequency * r / N)
                px[y * N + x] = v.coerceIn(0f, 1f)
            }
        }
        return GrayImage(N, N, px)
    }

    /** Pure noise: no periodicity anywhere. */
    fun noise(seed: Long = 7, amplitude: Float = 0.3f): GrayImage {
        val rnd = Random(seed)
        val px = FloatArray(N * N) { (0.5f + (rnd.nextFloat() - 0.5f) * amplitude).coerceIn(0f, 1f) }
        return GrayImage(N, N, px)
    }

    /** Flat tone: no texture at all. */
    fun flat(value: Float = 0.5f): GrayImage = GrayImage(N, N, FloatArray(N * N) { value })

    /** Fine speckle: energy pushed to high spatial frequencies, as toner does. */
    fun speckle(seed: Long = 11, amplitude: Float = 0.4f): GrayImage {
        val rnd = Random(seed)
        val px = FloatArray(N * N)
        for (y in 0 until N) {
            for (x in 0 until N) {
                val white = (rnd.nextFloat() - 0.5f) * amplitude
                px[y * N + x] = (0.5f + white).coerceIn(0f, 1f)
            }
        }
        // One-pixel alternation raises the dominant frequency to Nyquist.
        for (y in 0 until N) {
            for (x in 0 until N) {
                if ((x + y) % 2 == 0) px[y * N + x] = px[y * N + x].coerceIn(0f, 0.6f)
            }
        }
        return GrayImage(N, N, px)
    }

    /**
     * Horizontal grating at [frequency] cycles across the patch. Unlike [lattice] this puts
     * all its energy on the `(±f, 0)` component, so the radial peak lands exactly on `f`.
     */
    fun grating(frequency: Float, amplitude: Float = 0.35f): GrayImage {
        val px = FloatArray(N * N)
        for (y in 0 until N) {
            for (x in 0 until N) {
                val v = 0.5f + amplitude * cos(2 * PI.toFloat() * frequency * x / N)
                px[y * N + x] = v.coerceIn(0f, 1f)
            }
        }
        return GrayImage(N, N, px)
    }
}

class SpectrumTest {

    @Test
    fun `a ring pattern peaks at exactly its own frequency`() {
        for (frequency in intArrayOf(16, 24, 40, 64)) {
            val profile = Spectrum.radialProfile(Synthetic.ringPattern(frequency.toFloat()))
            assertTrue(
                profile.peakFrequency >= frequency - 3 && profile.peakFrequency <= frequency + 3,
                "expected a peak near $frequency, got ${profile.peakFrequency}",
            )
        }
    }

    @Test
    fun `a cross-hatch lattice peaks at frequency times root two`() {
        // `cos(ax)cos(by) = ½[cos(a-b) + cos(a+b)]`, so all the energy lands on the
        // (±f,±f) component, whose radius is f·√2. Pinning this down stops someone later
        // "correcting" the generator and silently invalidating the frequency tests.
        val frequency = 24
        val profile = Spectrum.radialProfile(Synthetic.lattice(frequency.toFloat()))
        val expected = frequency * kotlin.math.sqrt(2f)
        assertTrue(
            profile.peakFrequency >= expected - 4 && profile.peakFrequency <= expected + 4,
            "expected a peak near ${expected.toInt()}, got ${profile.peakFrequency}",
        )
    }

    @Test
    fun `a plain 1-D grating peaks at its own frequency`() {
        val profile = Spectrum.radialProfile(Synthetic.grating(32f))
        assertTrue(
            profile.peakFrequency >= 28 && profile.peakFrequency <= 36,
            "expected a peak near 32, got ${profile.peakFrequency}",
        )
    }

    @Test
    fun `a lattice is far more peaked than noise`() {
        val lattice = Spectrum.radialProfile(Synthetic.lattice(32f))
        val noiseProfile = Spectrum.radialProfile(Synthetic.noise())
        assertTrue(
            lattice.peakiness > noiseProfile.peakiness * 5,
            "lattice peakiness ${lattice.peakiness} should dwarf noise ${noiseProfile.peakiness}",
        )
    }

    @Test
    fun `noise is not peaked`() {
        val profile = Spectrum.radialProfile(Synthetic.noise())
        assertTrue(profile.peakiness < 6f, "noise should look flat, peakiness=${profile.peakiness}")
    }

    @Test
    fun `a flat tone has no energy anywhere`() {
        val profile = Spectrum.radialProfile(Synthetic.flat())
        assertTrue(profile.bandEnergy < 0.5f, "flat patch band energy=${profile.bandEnergy}")
        assertTrue(profile.highFrequencyEnergy < 0.5f)
    }

    @Test
    fun `fine speckle pushes energy upward`() {
        val speckle = Spectrum.radialProfile(Synthetic.speckle())
        val flat = Spectrum.radialProfile(Synthetic.flat())
        assertTrue(
            speckle.highFrequencyEnergy > flat.highFrequencyEnergy,
            "speckle ${speckle.highFrequencyEnergy} should exceed flat ${flat.highFrequencyEnergy}",
        )
    }

    @Test
    fun `profile outputs stay inside their documented ranges`() {
        for (img in listOf(Synthetic.lattice(24f), Synthetic.noise(), Synthetic.flat(), Synthetic.speckle())) {
            val p = Spectrum.radialProfile(img)
            assertTrue(p.bandEnergy in 0f..1f, "bandEnergy=${p.bandEnergy}")
            assertTrue(p.highFrequencyEnergy in 0f..1f, "highFrequencyEnergy=${p.highFrequencyEnergy}")
            assertTrue(p.peakiness >= 1f, "peakiness=${p.peakiness}")
            assertTrue(p.peakFrequency >= 0f)
        }
    }

    @Test
    fun `a non-patch size is rejected rather than silently resampled`() {
        val wrong = GrayImage(64, 64, FloatArray(64 * 64) { 0.5f })
        val error = runCatching { Spectrum.radialProfile(wrong) }.exceptionOrNull()
        assertNotNull(error, "a wrong-sized patch must fail loudly, not be quietly resized")
    }
}

class LbpTest {

    @Test
    fun `a flat image collapses to a single LBP bin`() {
        val hist = Lbp.histogram(Synthetic.flat())
        assertEquals(1f, hist.sum(), 1e-4f)
        // A constant patch makes every neighbour equal-or-greater, i.e. the all-ones ring
        // code 255. Which bin index that maps to is an artefact of the derived table's
        // ordering, so the property under test is concentration, not a particular index.
        val occupied = hist.count { it > 0.01f }
        assertEquals(1, occupied, "flat patch must occupy exactly one bin, got $occupied")
        assertEquals(1f, hist.max(), 1e-4f)
    }

    @Test
    fun `noise spreads mass across many bins`() {
        val hist = Lbp.histogram(Synthetic.noise())
        val occupied = hist.count { it > 0.01f }
        assertTrue(occupied > 10, "noise should occupy many bins, got $occupied")
    }

    @Test
    fun `feature extraction is deterministic`() {
        val a = Lbp.histogram(Synthetic.lattice(16f))
        val b = Lbp.histogram(Synthetic.lattice(16f))
        assertTrue(a.contentEquals(b), "the same texture must always produce the same descriptor")
    }

    @Test
    fun `histogram is normalised and bounded`() {
        val hist = Lbp.histogram(Synthetic.speckle())
        assertEquals(1f, hist.sum(), 1e-3f)
        assertTrue(hist.all { it in 0f..1f })
        assertEquals(MacroFeatures.LBP_BINS, hist.size)
    }
}

class EdgesTest {

    @Test
    fun `a flat image has no edges`() {
        assertEquals(0f, Edges.density(Synthetic.flat()), 1e-4f)
    }

    @Test
    fun `a lattice has many edges`() {
        val density = Edges.density(Synthetic.lattice(32f))
        assertTrue(density > 0.1f, "expected a busy edge map, got $density")
    }

    @Test
    fun `a smooth ramp has few edges`() {
        val px = FloatArray(Synthetic.N * Synthetic.N) { i -> i.toFloat() / (Synthetic.N * Synthetic.N) }
        val density = Edges.density(GrayImage(Synthetic.N, Synthetic.N, px))
        assertTrue(density < 0.05f, "a smooth ramp should not be edge-dense, got $density")
    }
}

class DocAggregatorTest {

    private val registry = dev.kasoti.threshold.ThresholdRegistry.defaults()

    private fun patch(label: ProcessLabel, margin: Float) = PatchResult(
        label = label,
        margin = margin,
        scores = mapOf(label to 1f),
        features = emptyFeatures(),
        halftone = HalftoneProfile(0f, 1f, 0f, 0f, 0f),
    )

    private fun emptyFeatures() = MacroFeatures(
        0f, 1f, 0f, 0f, 0f, 0f, 0f, FloatArray(MacroFeatures.LBP_BINS),
    )

    @Test
    fun `matching confident zones are a match`() {
        val result = DocAggregator.aggregate(
            patch(ProcessLabel.OFFSET, 0.9f),
            patch(ProcessLabel.OFFSET, 0.8f),
            registry,
        )
        assertEquals(DocProcess.Agreement.MATCH, result.agreement)
    }

    @Test
    fun `confident disagreement is a mismatch`() {
        val result = DocAggregator.aggregate(
            patch(ProcessLabel.LASER, 0.9f),
            patch(ProcessLabel.INKJET, 0.7f),
            registry,
        )
        assertEquals(DocProcess.Agreement.MISMATCH, result.agreement)
    }

    @Test
    fun `disagreement with low margins is uncertain, not a mismatch`() {
        val result = DocAggregator.aggregate(
            patch(ProcessLabel.LASER, 0.02f),
            patch(ProcessLabel.INKJET, 0.01f),
            registry,
        )
        assertEquals(DocProcess.Agreement.UNCERTAIN, result.agreement)
    }

    @Test
    fun `no macro evidence at all is reported as such`() {
        val result = DocAggregator.aggregate(null, null, registry)
        assertEquals(DocProcess.Agreement.NO_EVIDENCE, result.agreement)
        assertEquals(ProcessLabel.UNKNOWN, result.photoZone.label)
    }

    @Test
    fun `a single zone is never a match`() {
        val result = DocAggregator.aggregate(patch(ProcessLabel.OFFSET, 0.99f), null, registry)
        assertEquals(DocProcess.Agreement.UNCERTAIN, result.agreement)
    }
}
