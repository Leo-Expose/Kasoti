package dev.kasoti.android.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.kasoti.android.field.CaptureMeasurements
import dev.kasoti.android.field.FieldLog
import dev.kasoti.android.field.FieldLogEntry
import dev.kasoti.android.field.FrameMetrics
import dev.kasoti.android.platform.AndroidImaging
import dev.kasoti.fusion.FindingCode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX wiring (FR-C1, FR-C3, FR-C4).
 *
 * ## What the app owns here rather than delegating to a CameraX convenience
 *
 * Three behaviours are *policy* and would be lost behind a higher-level API:
 *
 *  1. **The macro focus lock** (FR-C3). A macro shot's sharpness is the whole measurement, and
 *     continuous autofocus hunts: it racks through the clip, settles, and moves again on the next
 *     frame. `CameraController.enterMacroMode` pins `AF_MODE_OFF` and `lockFocus`es on the
 *     measured distance, so a blurry macro patch is the operator's hands rather than the
 *     autofocus. The "clip shroud" of the spec is the physical half of the same idea and is
 *     surfaced as [CaptureMode.MACRO] on screen.
 *  2. **Live quality measurement on a downscaled frame.** The analysis pass runs at
 *     [AndroidImaging.ANALYSIS_SIDE] regardless of sensor resolution, so the frame cost is the
 *     same on a 2 GB phone and on a flagship — which is what makes NFR-P1's latency figures
 *     comparable across the two devices SPEC §10 A2 assumes.
 *  3. **Rotation applied once.** `AndroidImaging.applyRotation` is fed
 *     `ImageProxy.imageInfo.rotationDegrees`, and nothing else rotates. Rotating twice, or
 *     rotating and then resampling, would inject exactly the low-frequency artefact the macro
 *     spectrum features report as evidence.
 *
 * ## Threading
 *
 * Analysis and capture run on a single background `ExecutorService`, never the main thread
 * (NFR-R1: ANR-free capture). ML Kit's synchronous form and the TFLite interpreter are both
 * blocking and both live there.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val imaging: AndroidImaging = AndroidImaging(),
    private val log: FieldLog = FieldLog.NOOP,
) {

    enum class CaptureMode {
        /** Continuous AF, whole-document capture. */
        DOCUMENT,

        /**
         * Pinned focus, 256×256 patches, sharpness bar (FR-C3).
         *
         * `clipUsed` is the *claim* about physical hardware and it reaches `:core` as
         * `MacroEvidence.clipUsed`, where a false value makes the MACRO layer ABSENT. It is a
         * claim the app cannot verify — only the operator can — so the UI asks, and the
         * operator's answer is recorded as their answer.
         */
        MACRO,

        /** The 1 s clip, ≥5 frames (FR-C4). */
        FACE,
    }

    sealed interface CaptureResult {
        data class Success(val bitmap: Bitmap, val rotationDegrees: Int) : CaptureResult
        data class Failure(val code: FindingCode) : CaptureResult
    }

    /** A live frame's measurements, for the quality meter (FR-C1). */
    data class Analysis(val measurements: CaptureMeasurements, val rotationDegrees: Int)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null

    @Volatile
    var mode: CaptureMode = CaptureMode.DOCUMENT
        private set

    /** True once the operator has confirmed the clip shroud is seated (FR-C3). */
    @Volatile
    var clipUsed: Boolean = false
        private set

    @Volatile
    private var focusLocked: Boolean = false

    @Volatile
    private var facesSeen: Int = 0

    @Volatile
    var torchOn: Boolean = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasTorch(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    /**
     * Bind the use cases.
     *
     * @param onError receives a `FindingCode`, never an exception: a camera that will not start
     *   is `SYS_CAPTURE_FAILED` and a recovery line, not a crash on a counter.
     */
    fun bind(onError: (FindingCode) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindUseCases(provider, onError)
                } catch (_: Exception) {
                    Log.e(TAG, "camera provider unavailable")
                    onError(FindingCode.SYS_CAPTURE_FAILED)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider, onError: (FindingCode) -> Unit) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            // Backpressure: keep only the latest frame. A backlog on a slow phone would make
            // the quality meter report a capture from half a second ago, which is worse than no
            // meter at all — the operator would be chasing a problem that is not there.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // API MIGRATION, CameraX 1.4.x. The old
                        // `ResolutionStrategy(int side, int fallbackRule)` constructor is `private`
                        // in 1.4.1 (verified with `javap` against camera-core-1.4.1.aar: the only
                        // public constructor is `ResolutionStrategy(android.util.Size, int)`). The
                        // fallback rule constant is unchanged and still public, so the *policy* here
                        // — analyse at the requested side, fall back to the nearest size above it
                        // before below it — is identical; only the spelling of the size changed.
                        ResolutionStrategy(
                            Size(AndroidImaging.ANALYSIS_SIDE, AndroidImaging.ANALYSIS_SIDE),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()

        analysis.setAnalyzer(executor) { proxy -> analyse(proxy) }

        try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            camera = bound
            imageCapture = capture
            imageAnalysis = analysis
            onError(FindingCode.SYS_CAPTURE_FAILED) // clear any previous error
        } catch (_: Exception) {
            Log.e(TAG, "use-case binding failed")
            onError(FindingCode.SYS_CAPTURE_FAILED)
        }
    }

    /**
     * Measure a live frame.
     *
     * Pose is *not* read here. A single YUV analysis frame has no face landmarks without running
     * the detector on it, and running the detector at preview rate on a 2 GB phone is a battery
     * and thermal cost NFR-B1 does not have room for. The face step's own capture produces the
     * landmarks, and `CaptureQuality.evaluateDocument(..., hasPose = ...)` takes the flag from
     * there — an unmeasured pose is a passed pose, not a bad one.
     */
    private fun analyse(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmapSafely() ?: return
            val gray = imaging.downscale(bitmap).let { imaging.toGray(it) }
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.CAPTURE, detail = "analysis ${gray.width}x${gray.height}"))
            lastAnalysis = Analysis(
                measurements = CaptureMeasurements(
                    blurVariance = FrameMetrics.blurVariance(gray),
                    glareRatio = FrameMetrics.glareRatio(gray),
                    brightness = FrameMetrics.brightness(gray),
                ),
                rotationDegrees = proxy.imageInfo.rotationDegrees,
            )
        } catch (_: RuntimeException) {
            // An analysis frame is best-effort. A failure means the meter shows nothing, not
            // that the capture fails — the shutter path is independent.
        } finally {
            proxy.close()
        }
    }

    /** The most recent live measurement, for the quality meter. `null` before the first frame. */
    @Volatile
    var lastAnalysis: Analysis? = null
        private set

    /**
     * Take a still.
     *
     * @param onResult receives a `Bitmap` already rotated upright, so no caller has to remember
     *   to apply `imageInfo.rotationDegrees` — a step that is easy to omit and produces an MRZ
     *   read at 90°, which looks exactly like a bad document.
     */
    fun takeStill(onResult: (CaptureResult) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(CaptureResult.Failure(FindingCode.SYS_CAPTURE_FAILED))
            return
        }
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toBitmapSafely()?.let {
                            imaging.applyRotation(it, image.imageInfo.rotationDegrees)
                        }
                    } catch (_: RuntimeException) {
                        null
                    } finally {
                        image.close()
                    }
                    onResult(
                        if (bitmap == null) {
                            CaptureResult.Failure(FindingCode.SYS_CAPTURE_FAILED)
                        } else {
                            CaptureResult.Success(bitmap, image.imageInfo.rotationDegrees)
                        },
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed: ${exception.message}")
                    onResult(CaptureResult.Failure(FindingCode.SYS_CAPTURE_FAILED))
                }
            },
        )
    }

    /** The face-step clip: frames counted so far. FR-C4 wants ≥5. */
    fun facesCaptured(): Int = facesSeen

    fun resetClipCount() {
        facesSeen = 0
    }

    fun noteFaceCaptured() {
        facesSeen++
    }

    /**
     * Enter macro mode: pin the focus (FR-C3).
     *
     * `AF_MODE_OFF` plus a `lockFocus` at the measured distance. The alternative — leaving
     * continuous AF on — produces a patch whose sharpness is at the mercy of the autofocus
     * hunting, and the resulting `G_FOCUS` retake would be the app blaming the operator for its
     * own camera settings.
     */
    fun enterMacroMode(clipConfirmed: Boolean) {
        mode = CaptureMode.MACRO
        clipUsed = clipConfirmed
        facesSeen = 0
        val control = camera?.cameraControl ?: return
        try {
            control.enableTorch(false)
            torchOn = false
            val point: MeteringPoint = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
            )
            control.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .disableAutoCancel()
                    .build(),
            )
            focusLocked = true
        } catch (_: IllegalArgumentException) {
            // Some devices refuse a metering action with no AF capability. The macro step still
            // runs; the patch will simply be more often out of focus, and `G_FOCUS` will say so.
            focusLocked = false
        }
    }

    fun exitMacroMode() {
        mode = CaptureMode.DOCUMENT
        focusLocked = false
    }

    fun isFocusLocked(): Boolean = focusLocked

    fun setClipUsed(used: Boolean) {
        clipUsed = used
    }

    /** The UV torch (FR-F2). Returns `true` when the torch is actually on. */
    fun setTorch(on: Boolean): Boolean {
        val control = camera?.cameraControl ?: return false
        return try {
            control.enableTorch(on)
            torchOn = on && hasTorch()
            torchOn
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Release everything.
     *
     * Called from `onDestroy`. Leaving an `ExecutorService` running leaks a thread per activity
     * and, on a phone that has been re-opened a few hundred times in a shift, exhausts the
     * process — which is NFR-R1's 50-consecutive-E2E failure mode.
     */
    fun release() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // Nothing useful to do; the provider is going away with the process.
        }
        imageCapture = null
        imageAnalysis = null
        camera = null
        executor.shutdown()
    }

    /**
     * Decode an `ImageProxy` to a `Bitmap`.
     *
     * Two formats, handled explicitly, because the analysis and the still-capture paths give
     * different ones and a silent fallthrough would produce a rotated-by-90° MRZ read — which
     * looks exactly like a bad document and is one of the more expensive mistakes available on
     * a counter.
     *
     * `YUV_420_888` is planar, and a naive "read the first plane" gives luma only, which is
     * actually what the *quality* path wants but not what the still path wants. The still path
     * uses `JPEG` via `ImageCapture`'s own decoder instead, so this only has to be correct for
     * RGBA. Anything else is `null` — an undecodable frame is `SYS_CAPTURE_FAILED`, never a
     * grey rectangle that produces a plausible-looking measurement.
     */
    private fun ImageProxy.toBitmapSafely(): Bitmap? = try {
        when (format) {
            FORMAT_RGBA_8888 -> {
                val buffer = planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val argb = IntArray(width * height)
                val pixels = minOf(argb.size, bytes.size / BYTES_PER_PIXEL)
                for (i in 0 until pixels) {
                    val base = i * BYTES_PER_PIXEL
                    argb[i] = (bytes[base].toInt() and 0xFF) shl 24 or
                        (bytes[base + 1].toInt() and 0xFF) shl 16 or
                        (bytes[base + 2].toInt() and 0xFF) shl 8 or
                        (bytes[base + 3].toInt() and 0xFF)
                }
                Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            }

            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val TAG = "KASOTI"
        const val BYTES_PER_PIXEL = 4

        /**
         * `android.graphics.ImageFormat.RGBA_8888`, whose value is `1`.
         *
         * API MIGRATION. `ImageFormat.RGBA_8888` is **not** in the public SDK — it is absent from
         * `android-34`'s `android.jar` as well as `android-35`'s (checked with `javap`, not
         * assumed), so this line has *never* compiled against a released platform; it is not a
         * consequence of `compileSdk` moving to 35. CameraX still reports this value from
         * `ImageProxy.getFormat()` when the analysis use case is built with
         * `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888` — see `buildAnalysis()` above — and
         * CameraX publishes no replacement constant of its own, so the platform value is named here
         * rather than inlined at the `when`.
         *
         * Do NOT substitute `ImageFormat.FLEX_RGBA_8888` (42). That is the flexible/`YUV`-family
         * pixel format, not the packed RGBA buffer this analyzer produces, and matching on it
         * would send every analysed frame down the `else -> null` branch — i.e. every capture
         * would report `SYS_CAPTURE_FAILED`.
         */
        const val FORMAT_RGBA_8888 = 1
    }
}
