package dev.kasoti.platform.ml.tflite

import dev.kasoti.crypto.Hex
import dev.kasoti.platform.crypto.JcaDigest
import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.platform.ml.ModelLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop half of the face-layer parity reference (`eval/fixtures/face/`).
 *
 * ## What this does and does not establish
 *
 * This is **not** a parity test. Parity means Android and desktop agreeing, and there is no
 * Android SDK and no device, so parity is unverified and unclaimed. What this establishes is the
 * desktop side of a *checkable contract*: the fixture image, the model digest, and the expected
 * output are all pinned, so the first person to run this on a device compares two numbers rather
 * than investigating a discrepancy.
 *
 * The fixture deliberately contains no face, so the expectation is **zero detections**. That is a
 * weak assertion on its own — two broken platforms agree on zero — but it catches the failure this
 * exists for: one platform silently holding no model (or a different one) and reporting "no face"
 * where the other reports a face. Pinning the *input digest* and the *model digest* alongside it
 * is what gives the assertion teeth, because a platform with the wrong model still returns zero
 * here and is still wrong.
 *
 * The `[0,1]` vs `[-1,1]` input-range fork recorded in `manifest.json` is **not** settled by this
 * test and cannot be: a synthetic image with no face produces the same zero detections under
 * either encoding. It needs a consented crop, which is why the field is marked NOT VERIFIED there
 * rather than quietly assumed.
 */
class DetectorParityReferenceTest {

    private val loader = ModelLoader(JcaDigest())

    @Test
    fun `the reference fixture is the file the reference claims it is`() {
        val image = fixtureImage()
        val digest = Hex.encode(JcaDigest().sha256(Files.readAllBytes(image)))
        assertEquals(
            digestInReference(),
            digest,
            "eval/fixtures/face/no_face_synthetic_256.png has changed. Its digest is pinned in " +
                "detector_reference.json and manifest.json; if the change is intended, update the " +
                "reference AND re-derive every expected value in it, because the numbers describe " +
                "these exact bytes.",
        )
    }

    @Test
    fun `the reference fixture decodes to the size the reference claims`() {
        val rgb = ImageIoImaging().readRgb(fixtureImage())
        assertEquals(256, rgb.width)
        assertEquals(256, rgb.height)
    }

    /**
     * The substantive one: the pinned model, on the pinned bytes, produces the pinned result.
     *
     * Skipped with a stated reason when the model has not been fetched, which is the normal state
     * of a fresh checkout. It is never silently skipped for any other reason.
     */
    @Test
    fun `the pinned detector produces the reference result on the reference bytes`() {
        val model = repoRoot().resolve("eval/models").resolve(BlazeFaceContract.NAME)
        assumeTrue(
            Files.isRegularFile(model),
            "detector not fetched — run ./scripts/fetch_models.sh scripts/models_manifest.tsv",
        )
        assumeTrue(
            TfliteRuntime.availability.available,
            "TFLite runtime unavailable: ${TfliteRuntime.availability.detail}",
        )

        val rgb = ImageIoImaging().readRgb(fixtureImage())
        TfliteBlazeFaceDetector.open(loader, model).use { detector ->
            assertEquals(EXPECTED_MODEL_TAG, detector.modelTag)
            val (detection, timing) = detector.detectWithTiming(rgb)

            assertNull(
                detection,
                "the reference image contains no face, so the reference expectation is zero " +
                    "detections. A detection here means the input encoding, the decode or the " +
                    "score floor has changed — all three are load-bearing and none is a threshold.",
            )
            assertTrue(timing.inferenceNanos > 0, "inference reported no native duration")
            println(
                "PARITY-REFERENCE detections=0 " +
                    "nativeMs=${"%.2f".format(timing.inferenceMillis)} " +
                    "inputPrepMs=${"%.2f".format(timing.inputMillis)} " +
                    "(timings are informational, not an assertion — they are machine-dependent)",
            )
        }
    }

    // --- helpers ----------------------------------------------------------------

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("could not locate the Gradle root from ${Path.of("").toAbsolutePath()}")
    }

    private fun fixtureImage(): Path = repoRoot().resolve(FIXTURE_RELATIVE)

    /**
     * Pull the pinned digest out of the reference file.
     *
     * A targeted scan rather than a JSON parse: `:core`'s JSON API is not this module's to depend
     * on for a test-only constant, and the file is a human-readable record whose whole value is
     * that you can read it. A missing field fails the test, which is the behaviour we want.
     */
    private fun digestInReference(): String {
        val text = repoRoot().resolve("eval/fixtures/face/detector_reference.json").readText()
        val match = Regex("\"input_sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)
            ?: error(
                "detector_reference.json has no \"input_sha256\": a 64-hex-digit field. The " +
                    "reference must pin the input bytes, or a changed fixture passes unnoticed.",
            )
        return match.groupValues[1]
    }

    private companion object {
        const val FIXTURE_RELATIVE = "eval/fixtures/face/no_face_synthetic_256.png"

        /**
         * The tag the reference pins, built from [BlazeFaceContract] so it cannot drift from the
         * constant the loader actually verifies against.
         */
        val EXPECTED_MODEL_TAG =
            "${BlazeFaceContract.NAME}@sha256:${BlazeFaceContract.SHA256}"
    }
}
