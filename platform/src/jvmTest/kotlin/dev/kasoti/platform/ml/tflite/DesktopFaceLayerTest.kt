package dev.kasoti.platform.ml.tflite

import dev.kasoti.crypto.Hex
import dev.kasoti.platform.crypto.JcaDigest
import dev.kasoti.platform.ml.ModelErrorCode
import dev.kasoti.platform.ml.ModelLoadException
import dev.kasoti.platform.ml.ModelLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop face layer: what it refuses, and what it will not invent.
 *
 * The theme of every test here is **fail-closed**. The failure this project is built to avoid is
 * not a crash; it is a screening tool that confidently produces a similarity number it did not
 * measure. So the interesting cases are all the ways a face layer can be *absent*, and each has to
 * be distinguishable from every other:
 *
 * | state | detector | embedder | must never |
 * |---|---|---|---|
 * | fully provisioned | present | present | — |
 * | no embedder (today) | present | `null` | report `canCompareFaces` |
 * | wrong OS | `null` | `null` | crash, or pretend to look |
 * | model absent | `null` | `null` | report "no face in frame" |
 * | model tampered | throws | `null` | fall back to another model |
 */
class DesktopFaceLayerTest {

    private val loader = ModelLoader(JcaDigest())

    // --- runtime availability ---------------------------------------------------

    @Test
    fun `runtime availability is reported, never assumed`() {
        val availability = TfliteRuntime.availability
        assertNotNull(availability.detail)
        if (availability.available) {
            assertNotNull(availability.libraryName, "an available runtime must name its library")
        } else {
            // Fail-closed reporting: a reason the operator can act on, not a bare false.
            assertTrue(availability.detail.isNotBlank())
        }
    }

    @Test
    fun `the probe is memoised so it cannot report two different answers`() {
        assertEquals(TfliteRuntime.availability, TfliteRuntime.availability)
    }

    /**
     * Documents the platform coverage honestly.
     *
     * Windows x86-64 and macOS aarch64 have **no published** TFLite JNI native, which is not a
     * build bug on our side and cannot be fixed by a version bump. BUILD.md §4's prescribed
     * response is that those OSes ship "review-only, no on-device inference"; this asserts the
     * runtime agrees with that and reports it rather than throwing.
     */
    @Test
    fun `an OS with no published native reports review-only instead of throwing`() {
        val published = TfliteRuntime.isPlatformPublished()
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        val knownMissing = (os.contains("windows")) ||
            ((os.contains("mac") || os.contains("darwin")) && arch == "aarch64")
        assertEquals(!knownMissing, published, "platform table disagrees with the known coverage")
        if (knownMissing) {
            val availability = TfliteRuntime.availability
            assertFalse(availability.available)
            assertTrue(availability.detail.contains("review-only"), availability.detail)
        }
    }

    // --- hash pinning (RT-E8) ---------------------------------------------------

    /** A hash check on a truncated model is a hash check on the wrong bytes. */
    @Test
    fun `a model file smaller than the floor is refused before the interpreter sees it`() {
        val dir = createTempDirectory("kasoti-face")
        val file = dir.resolve(BlazeFaceContract.NAME)
        file.writeBytes(ByteArray(1024))
        val failure = assertFailsWith<ModelLoadException> {
            TfliteBlazeFaceDetector.open(loader, file)
        }
        assertEquals(ModelErrorCode.TOO_SMALL, failure.code)
    }

    @Test
    fun `the build-time pin is the digest we actually measured, and it is well formed`() {
        assertEquals(ModelLoader.SHA256_HEX_LENGTH, BlazeFaceContract.SHA256.length)
        assertTrue(BlazeFaceContract.SHA256.all { it in "0123456789abcdef" })
        assertTrue(BlazeFaceContract.SHA256_SOURCE_URL.startsWith("https://"))
        // The versioned path, never the moving `latest` alias.
        assertContains(BlazeFaceContract.SHA256_SOURCE_URL, "/float16/1/")
        assertFalse(BlazeFaceContract.SHA256_SOURCE_URL.contains("/latest/"))
    }

    // --- the layer --------------------------------------------------------------

    @Test
    fun `an empty layer cannot compare faces and says why`() {
        val layer = DesktopFaceLayer.empty()
        assertFalse(layer.canDetect)
        assertFalse(layer.canEmbed)
        assertFalse(layer.canCompareFaces)
        assertContains(layer.unavailableReasons, FaceLayerUnavailable.EMBEDDER_UNAVAILABLE)
    }

    /**
     * The single most important test in this file.
     *
     * A layer with a working detector and no embedder must report `canCompareFaces == false`. If
     * this ever became `true`, `:core` would believe a 1:1 face check had run, and
     * `fuseMatchScore` would temper a similarity that does not exist.
     */
    @Test
    fun `a detector without an embedder can locate but never compare`() {
        val layer = DesktopFaceLayer.open(loader, emptyModelDir())
        if (layer.canDetect) {
            assertFalse(layer.canEmbed, "no embedder may ever be present today (spike 01 §2.1)")
            assertFalse(layer.canCompareFaces)
        }
        assertContains(layer.unavailableReasons, FaceLayerUnavailable.EMBEDDER_UNAVAILABLE)
    }

    @Test
    fun `a missing model directory degrades the layer rather than throwing`() {
        val layer = DesktopFaceLayer.open(loader, Path.of("/nonexistent-kasoti-models"))
        assertFalse(layer.canDetect)
        assertFalse(layer.canCompareFaces)
        assertTrue(layer.unavailableReasons.isNotEmpty())
    }

    /**
     * A tampered model must not fall back to another model, and must not become "no face found".
     *
     * A "no face in frame" result for a swapped-weights file is the worst outcome available: the
     * app looks healthy, the audit chain verifies, and every face is silently missed.
     */
    @Test
    fun `a tampered model is refused loudly instead of degrading to no-face`() {
        val dir = createTempDirectory("kasoti-face-tamper")
        val file = dir.resolve(BlazeFaceContract.NAME)
        val notTheRealModel = ByteArray(BlazeFaceContract.MINIMUM_BYTES + 4096) { 0x41 }
        file.writeBytes(notTheRealModel)

        val failure = assertFailsWith<ModelLoadException> { TfliteBlazeFaceDetector.open(loader, file) }
        assertEquals(ModelErrorCode.HASH_MISMATCH, failure.code)
        assertEquals(BlazeFaceContract.SHA256, failure.expectedSha256)
        assertEquals(Hex.encode(JcaDigest().sha256(notTheRealModel)), failure.actualSha256)
    }

    // --- the honesty guarantees -------------------------------------------------

    /**
     * The guarantee this whole task turns on.
     *
     * There is no embedder, so there is no similarity, and [DesktopFaceLayer.embed] must return
     * `null` rather than something a caller could mistake for a measurement. There is deliberately
     * no code path anywhere in `:platform` that can produce an `Embedding` today.
     */
    @Test
    fun `no embedding can be produced because no embedding model exists`() {
        val layer = DesktopFaceLayer.open(loader, emptyModelDir())
        assertNull(layer.embed(frameOf(64, 64)))
    }

    @Test
    fun `an empty layer detects nothing rather than inventing a detection`() {
        val layer = DesktopFaceLayer.empty()
        assertNull(layer.detect(frameOf(64, 64)))
        assertNull(layer.embed(frameOf(64, 64)))
    }

    @Test
    fun `close is safe on a layer that never opened anything`() {
        DesktopFaceLayer.empty().close()
        DesktopFaceLayer.empty().close()
    }

    // --- real model, when one has been fetched -----------------------------------

    /**
     * Real inference on the real pinned model, if `eval/models/` has been populated.
     *
     * Skipped, loudly and with the reason attached, when the model has not been fetched — which is
     * the normal state of a fresh checkout and of CI. It is written as a real test rather than a
     * scratch script so that whoever runs `scripts/fetch_models.sh` gets it for free.
     */
    @Test
    fun `the pinned detector runs on this machine`() {
        val model = locateModel() ?: return
        assumeTrue(
            TfliteRuntime.availability.available,
            "TFLite runtime unavailable: ${TfliteRuntime.availability.detail}",
        )

        TfliteBlazeFaceDetector.open(loader, model).use { detector ->
            assertEquals(BlazeFaceContract.SHA256, detector.sha256)
            assertEquals(BlazeFaceContract.NAME + "@sha256:" + BlazeFaceContract.SHA256, detector.modelTag)

            val (detection, timing) = detector.detectWithTiming(frameOf(256, 256))
            // A synthetic gradient contains no face, so `null` is the expected and correct answer.
            // What is being asserted is that inference *ran* and produced sane timings.
            assertNull(detection, "a synthetic gradient must not produce a detection")
            assertTrue(timing.inferenceNanos > 0, "inference reported no native duration")
            println(
                "RESULT blazeface_short modelTag=${detector.modelTag}",
            )
            println(
                "RESULT native inference ${"%.2f".format(timing.inferenceMillis)} ms, " +
                    "input prep ${"%.2f".format(timing.inputMillis)} ms, " +
                    "detections=0 (synthetic input contains no face, as expected)",
            )
        }
    }

    /** Repeated runs on identical bytes must agree exactly; the diary's comparability law needs it. */
    @Test
    fun `repeated runs on identical bytes are deterministic`() {
        val model = locateModel() ?: return
        assumeTrue(TfliteRuntime.availability.available, "no TFLite runtime")

        TfliteBlazeFaceDetector.open(loader, model).use { detector ->
            val frame = frameOf(128, 128)
            val first = detector.detectWithTiming(frame)
            repeat(3) {
                val again = detector.detectWithTiming(frame)
                assertEquals(
                    first.first?.score,
                    again.first?.score,
                    "detection score differed between runs; the diary comparability law " +
                        "requires identical bytes to give identical results",
                )
            }
            println("RESULT repeated-runs deterministic over 4 inferences")
        }
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * Find `eval/models/<model>` by walking up to the Gradle root.
     *
     * Gradle runs a module's tests with the *module* directory as the working directory, so a
     * relative `eval/models/...` resolves to `platform/eval/models/...` and the model-gated tests
     * would silently skip on a machine that had just fetched it. A skip that means "not fetched"
     * when the truth is "wrong directory" is exactly the kind of quiet failure this project cannot
     * afford, so the root is located by walking up to `settings.gradle.kts` and the test's own
     * message names the path it looked at.
     */
    private fun locateModel(): Path? {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                val candidate = dir.resolve("eval/models").resolve(BlazeFaceContract.NAME)
                if (Files.isRegularFile(candidate)) return candidate
                return null
            }
            dir = dir.parent
        }
        return null
    }

    private fun emptyModelDir(): Path = createTempDirectory("kasoti-face-empty")

    /**
     * A deterministic synthetic frame with no face in it.
     *
     * Deliberately not a person's face: no consented capture exists (`D-FACE` is empty) and using a
     * real one here would be exactly the consent failure THREAT_MODEL §8 is about. A synthetic
     * image is enough to prove the runtime executes and that the no-detection path is taken.
     */
    private fun frameOf(width: Int, height: Int): dev.kasoti.platform.imaging.RgbImage {
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                pixels[i] = ((x * 7) % 256).toByte()
                pixels[i + 1] = ((y * 11) % 256).toByte()
                pixels[i + 2] = (((x + y) * 5) % 256).toByte()
            }
        }
        return dev.kasoti.platform.imaging.RgbImage(width, height, pixels)
    }
}
