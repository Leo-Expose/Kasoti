package dev.kasoti.android

import android.graphics.Bitmap
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.kasoti.android.capture.CameraController
import dev.kasoti.android.field.CapturePlanner
import dev.kasoti.android.field.CaptureQuality
import dev.kasoti.android.field.CaptureStep
import dev.kasoti.android.field.DemoCatalogue
import dev.kasoti.android.field.MacroStage
import dev.kasoti.android.field.MrzExtractor
import dev.kasoti.android.field.Quad
import dev.kasoti.android.field.QualityReportFactory
import dev.kasoti.android.field.RouteSignals
import dev.kasoti.android.field.ScreeningSession
import dev.kasoti.android.field.TrackRouter
import dev.kasoti.android.field.VizFields
import dev.kasoti.android.platform.AndroidImaging
import dev.kasoti.android.platform.ModelStore
import dev.kasoti.android.platform.VizFieldReader
import dev.kasoti.android.view.CameraSurface
import dev.kasoti.android.view.KasotiScreen
import dev.kasoti.android.view.VoiceReadout
// `Quad.withCorner` is a top-level extension declared in `view/QuadHandleOverlay.kt`, not a
// member of `Quad`. Without this import the overlay's own call site resolved and the activity's
// did not, which is the only reason this ever failed to compile.
import dev.kasoti.android.view.withCorner
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.factory.SvmModelReader
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.i18n.Language
import dev.kasoti.i18n.Messages
import dev.kasoti.mrz.MrzResult
import dev.kasoti.ui.AppState
import dev.kasoti.ui.CaptureProgressBuilder
import dev.kasoti.ui.FieldStrings
import dev.kasoti.ui.FlowController
import dev.kasoti.ui.PermissionState
import dev.kasoti.ui.QuadMode
import dev.kasoti.ui.QualityMeter
import dev.kasoti.ui.ScreenState
import dev.kasoti.ui.UiEvent
import dev.kasoti.ui.VerdictPresenter
import dev.kasoti.ui.VerdictTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single activity.
 *
 * ## One activity, one `AppState`, one writer
 *
 * The state lives in one `mutableStateOf`, the reducer is `dev.kasoti.ui.FlowController`, and the
 * composables only read it. That is the `dev.kasoti.ui.FieldView` contract realised, and it is
 * what makes this app's behaviour testable without a device: the tests in
 * `app-android/src/test` exercise the same reducer, so a renderer bug cannot be masking a logic
 * bug — there is no logic in the renderer.
 *
 * ## What the activity owns, and what `:ui` owns
 *
 * `:ui` owns *what the screen says*: which step, which finding, which tone, whether the advance
 * is blocked. This class owns *what happens when a button is pressed*: take a picture, read the
 * MRZ, take a macro patch, run the cascade, speak the verdict. That split is why the dangerous
 * decision — whether a failed quality gate blocks — is in `:ui` and tested, and the mechanical
 * work is here and not.
 *
 * ## The two vocabulary mappings
 *
 * `:app-android`'s field layer and `:ui` each own a step enum on purpose, so neither depends on
 * the other. [toUiStep] is the only place they meet; see its KDoc for the `null`-on-unmapped
 * decision.
 */
class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private var camera: CameraController? = null
    private var voice: VoiceReadout? = null

    private var state by mutableStateOf(newState())

    /**
     * The capture pipeline's own working state.
     *
     * Separate from [AppState] on purpose. `AppState` is the *view* model and is what the
     * `:ui` tests exercise; this is the pipeline's scratch space and is deliberately *not*
     * rendered directly. Merging them would put an `MrzResult?` into a data class whose whole
     * job is to be a pure description of a screen, and it would be one more thing to get right
     * in a class nobody can unit-test.
     */
    private val capture = CaptureWorkingState()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The permission contract does not report whether another ask is possible, so it is
        // inferred from whether this was the *first* denial. A second denial is treated as
        // final, which is what `PermissionState.onResult` does with the pair. Being wrong in the
        // cautious direction costs one dead-end screen; being wrong in the other direction
        // produces an app that nags a gloved operator. Cautious is right.
        val canAskAgain = state.permission != PermissionState.DENIED_ONCE
        reduce(UiEvent.PermissionResult(granted = granted, canAskAgain = canAskAgain))
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as KasotiApp).graph
        voice = VoiceReadout(this, graph.log).also { it.start() }

        // A start-up refusal is surfaced as a transient error with a recovery line, and the app
        // still opens. A handset with missing models can still do macro work and manual MRZ
        // entry; hiding the whole app would leave an operator with nothing at a post.
        graph.errors.get().firstOrNull()?.let { error ->
            state = state.copy(transientError = errorText(error))
        }

        setContent {
            KasotiScreen(
                state = state,
                on = ::onEvent,
                // The live preview, as a slot. `attachPreview` is called once from
                // `CameraSurface`'s factory and is idempotent, so a recomposition does not
                // rebind the camera — which would drop the preview to black for a frame each
                // time a quality reading lands.
                camera = {
                    CameraSurface(
                        onPreviewReady = ::attachPreview,
                        quad = currentQuad,
                        interactive = (state.screen as? ScreenState.Capturing)?.quadMode == QuadMode.MANUAL,
                        onHandleMoved = { index, x, y ->
                            onEvent(UiEvent.MoveQuadHandle(index, x, y))
                            capture.quad = capture.quad?.withCorner(index, x, y)
                        },
                        // The toggle, not the device locale: the overlay's accessibility
                        // description resolves through `FieldStrings`, so it has to be keyed by
                        // the same `Language` as everything the officer reads.
                        language = state.language,
                    )
                },
            )
        }
    }

    override fun onDestroy() {
        camera?.release()
        camera = null
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the loop

    private fun reduce(event: UiEvent) {
        val next = FlowController.reduce(state, event)
        val languageChanged = next.language != state.language
        state = next
        if (languageChanged) voice?.applyLanguage(next.language)
    }

    private fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.RequestPermission -> permissionLauncher.launch(Manifest.permission.CAMERA)

            // The shutter is a camera action, not a state transition. `FlowController`
            // deliberately leaves the state alone for it, which is what stops a double-tap
            // producing two captures.
            is UiEvent.Shutter -> {
                if (state.demoMode) runDemo() else captureStill()
                return
            }

            is UiEvent.AdvanceStep -> {
                advanceStep()
                return
            }

            is UiEvent.AcceptQuad -> {
                capture.quad = capture.quad ?: Quad.FULL_FRAME
                captureStill()
                return
            }

            is UiEvent.SwitchQuadMode -> {
                reduce(event)
                return
            }

            is UiEvent.LiveQuality -> {
                // Pushed by the camera's analyzer. Recorded rather than reduced, because the
                // meter belongs to the *current* step and the pipeline knows which that is.
                applyAnalysis()
                return
            }

            is UiEvent.SetClipUsed -> {
                reduce(event)
                camera?.setClipUsed(event.used)
                return
            }

            is UiEvent.SetFocusLocked -> reduce(event)

            is UiEvent.LoadDemoCase -> {
                capture.loadedScenarioId = event.id
                reduce(event)
                return
            }

            is UiEvent.ToggleDemoMode -> {
                // The watermark and the DEMO banner come from the *session*, not from a local
                // boolean, so a reset really does remove them (FR-C5's one-tap reset).
                graph.demoSession.toggle()
                state = state.copy(demoMode = graph.demoSession.state.enabled)
                return
            }

            is UiEvent.ResetDemo -> {
                graph.demoSession.reset()
                capture.reset()
                state = state.copy(demoMode = false, screen = ScreenState.Idle)
                return
            }

            is UiEvent.RepeatVoice -> {
                speakVerdict()
                return
            }

            is UiEvent.NextPerson -> {
                graph.screening.nextPerson()
                capture.reset()
                reduce(event)
                return
            }

            else -> reduce(event)
        }
    }

    /**
     * Bind the camera, once the composable tree has produced a `PreviewView`.
     *
     * The `PreviewView` cannot be created here — it needs a `Context` Compose owns, and creating
     * a second one would mean a competing view tree — so the composable creates it and calls
     * [attachPreview]. Until it exists the capture path reports `SYS_CAPTURE_FAILED` rather than
     * doing nothing silently, and both guards below make the call idempotent: a recomposition
     * must not rebind the camera, which would drop the preview to black on every quality reading.
     */
    private fun startCamera() {
        if (camera != null) return
        val previewView = capture.previewView ?: return
        camera = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            log = graph.log,
        ).also { controller ->
            controller.bind { code -> state = state.copy(transientError = codeText(code)) }
        }
    }

    // ------------------------------------------------------------------ capture

    private fun captureStill() {
        val controller = camera
        if (controller == null) {
            state = state.copy(transientError = codeText(FindingCode.SYS_CAPTURE_FAILED))
            return
        }
        val step = currentStep()
        controller.takeStill { result ->
            lifecycleScope.launch {
                when (result) {
                    is CameraController.CaptureResult.Failure ->
                        state = state.copy(transientError = codeText(result.code))

                    is CameraController.CaptureResult.Success -> onStill(step, result.bitmap)
                }
            }
        }
    }

    private suspend fun onStill(step: CaptureStep, bitmap: Bitmap) {
        when (step) {
            CaptureStep.DOCUMENT -> {
                // The page capture: measure quality, and look for a croppable quad. The quad
                // proposal is shown *before* it is committed, so an operator sees a wrong
                // auto-crop as a wrong auto-crop rather than discovering it in an OCR result.
                val gray = withContext(Dispatchers.Default) {
                    AndroidImaging().downscale(bitmap).let { AndroidImaging().toGray(it) }
                }
                capture.quad = dev.kasoti.android.field.AutoQuadDetector.propose(gray) ?: Quad.FULL_FRAME
                capture.documentQuality = graph.quality.evaluateDocumentPage(
                    dev.kasoti.android.field.CaptureMeasurements(
                        blurVariance = dev.kasoti.android.field.FrameMetrics.blurVariance(gray),
                        glareRatio = dev.kasoti.android.field.FrameMetrics.glareRatio(gray),
                        brightness = dev.kasoti.android.field.FrameMetrics.brightness(gray),
                    ),
                )
            }

            CaptureStep.MRZ -> readMrz(bitmap)

            CaptureStep.MACRO -> takeMacroPatch(bitmap)

            CaptureStep.FACE -> {
                controller()?.let { c ->
                    c.noteFaceCaptured()
                    if (c.facesCaptured() >= FACE_CLIP_FRAMES) runScreening()
                }
            }
        }
    }

    /**
     * OCR the MRZ band (FR-M2).
     *
     * The recogniser is the bundled model; when it is unavailable the extraction is empty, the
     * MRZ step's meter reports `G_OCRLOW`, and the operator's route is manual entry (BUILD.md
     * §5). The bitmap is downscaled first: an MRZ is 44 characters of monospaced text, and OCR
     * at 12 megapixels is slower than at the analysis side with no accuracy cost, on a phone
     * whose budget is a battery percentage per hundred screenings (NFR-B1).
     */
    private suspend fun readMrz(bitmap: Bitmap) {
        val engine = graph.ocr
        val recognition = withContext(Dispatchers.Default) {
            if (engine == null) null else engine.recognize(AndroidImaging().downscale(bitmap))
        }
        if (recognition == null) {
            capture.mrz = null
            capture.mrzConfidence = null
            state = state.copy(transientError = codeText(FindingCode.G_OCRLOW))
            return
        }
        val viz = withContext(Dispatchers.Default) { VizFieldReader().read(recognition.lines) }
        val extraction = withContext(Dispatchers.Default) {
            MrzExtractor(graph.registry).extract(
                ocrLines = recognition.lines.map { it.text },
                referenceYear = graph.clock.today().year,
                manual = false,
                confidence = recognition.meanConfidence,
            )
        }
        capture.mrz = extraction.result.takeIf { it.format != dev.kasoti.mrz.MrzFormat.UNKNOWN }
        capture.mrzConfidence = recognition.meanConfidence
        capture.viz = viz
        state = state.copy(transientError = null)
    }

    /** One of the two FR-C3 macro patches, through the focus gate and the classifier. */
    private suspend fun takeMacroPatch(bitmap: Bitmap) {
        val stage = withContext(Dispatchers.Default) {
            val gray = AndroidImaging().downscale(bitmap).let { AndroidImaging().toGray(it) }
            val slot = if (capture.macro?.photoZone == null) {
                MacroStage.Slot.PHOTO_ZONE
            } else {
                MacroStage.Slot.TEXT_ZONE
            }
            macroStage().take(
                slot = slot,
                image = gray,
                clipUsed = controller()?.clipUsed ?: false,
                uv = graph.quality.uvState(
                    torchAvailable = controller()?.hasTorch() ?: false,
                    torchOn = controller()?.torchOn ?: false,
                    uVResponseObserved = null,
                ),
                // `capture.macro` is null until the first macro patch lands, and that null is
                // *load-bearing* — `buildReport` reads `capture.macro?.evidence()` and needs
                // `null` to mean "the step did not run" (`MacroStage.Stage.evidence()` is itself
                // nullable for the same reason). So the empty state is constructed HERE rather
                // than by pre-seeding `capture.macro`, and it is the same literal the module's own
                // tests use (`TrustDemoAndLayersTest`): both patches absent, no clip, UV never
                // observed. `take()` overwrites `uv` and `clipUsed` on the copy it returns, so the
                // values below are never read back.
                existing = capture.macro
                    ?: MacroStage.Stage(
                        photoZone = null,
                        textZone = null,
                        clipUsed = false,
                        uv = CaptureQuality.UvReading.NOT_OBSERVED,
                    ),
            )
        }
        capture.macro = stage
        state = state.copy(
            screen = ScreenState.Macro(
                dev.kasoti.ui.MacroCard(
                    photoZoneSharpness = stage.photoZone?.sharpness?.fraction ?: 0f,
                    textZoneSharpness = stage.textZone?.sharpness?.fraction ?: 0f,
                    clipUsed = stage.clipUsed,
                    focusLocked = controller()?.isFocusLocked() ?: false,
                    // `ready = stage.canAdvance` is NOT passed here, and must never be.
                    // `MacroCard.ready` is a *derived* `val` (`ui/ScreenState.kt:174`), and its
                    // own KDoc records that making it a stored field was a deliberate bug fix:
                    // "the reducer wrote a derived value back into the object it was derived
                    // from — a cycle that deadlocked the second patch". Passing it cannot be
                    // reinstated without re-creating that shape.
                    //
                    // The two do not need to be reconciled by threading a value through: the
                    // sharpness half is the same predicate on both sides
                    // (`fraction >= 1f` ⟺ `Sharpness.passed`, same `Q_BLUR` bar), and
                    // `MacroStage.Stage.canAdvance` remains the authoritative field-layer check —
                    // see its KDoc. Nothing here is a workaround and nothing here is pending.
                ),
            ),
            transientError = null,
        )
    }

    /**
     * Push the camera's latest live measurements into the quality meter (FR-C1).
     *
     * Two things are worth stating. First, the *page* gate — blur, glare, brightness — is
     * evaluated here because those three are measurable from a live preview frame; the face
     * gate additionally needs pose, which does not exist until the face step's own capture, so
     * it is evaluated there and only there. Second, a live reading is a live reading: it is
     * marked `live = true` so the UI and the log can distinguish "the preview was bad" from
     * "the capture was bad", which is a different conversation with the operator.
     */
    private fun applyAnalysis() {
        val analysis = camera?.lastAnalysis ?: return
        val report = graph.quality.evaluateDocumentPage(analysis.measurements)
        reduce(
            UiEvent.LiveQuality(
                QualityReportFactory.fromMeasurements(
                    measurements = analysis.measurements,
                    passed = report.passed,
                    causes = report.causes,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ screening

    private fun runScreening() {
        val signals = RouteSignals(
            mrz = capture.mrz,
            aadhaarCandidate = capture.viz.aadhaarCandidate,
            vizDocumentNumber = capture.viz.documentNumber,
            documentShape = RouteSignals.DocumentShape.CARD,
        )
        val route = TrackRouter.route(signals)
        val steps = ScreeningSession.Steps(
            plan = currentPlan(route.track),
            stepQuality = mapOf(CaptureStep.DOCUMENT to (capture.documentQuality ?: QualityReport.CLEAN)),
            mrz = capture.mrz,
            macro = capture.macro?.evidence(),
        )
        val outcome = graph.screening.screen(
            steps = steps,
            signals = signals,
            viz = capture.viz,
            trust = TrustState.VERIFY,
            demoMode = state.demoMode,
        )
        val screen = VerdictPresenter.present(outcome.report, state.language, thumbnailKey = outcome.caseId)
        state = state.copy(
            screen = ScreenState.Verdict(screen, outcome.report),
            transientError = null,
        )
        speakVerdict()
    }

    /**
     * The demo run (FR-C5).
     *
     * Goes through the *same* [dev.kasoti.fusion.FusionEngine] call the live path uses, so the
     * demo demonstrates the system rather than a parallel implementation of it. The differences
     * are the fixture's evidence and the `demoMode` flag, and nothing else.
     */
    private fun runDemo() {
        val scenario = DemoCatalogue.byId(capture.loadedScenarioId ?: DEFAULT_DEMO_SCENARIO)
            ?: DemoCatalogue.byId(DEFAULT_DEMO_SCENARIO)
            ?: return
        val demoCase = graph.demoSession.load(scenario.id) ?: run {
            // Demo mode is off, or the id is unknown. The recovery line is honest rather than
            // silently returning to the capture screen (DEMO.md §5). Resolved through
            // `FieldStrings` for the same reason every other string on screen is: a hardcoded
            // literal here was an English-only banner on a Hindi device (AGENTS.md §2).
            state = state.copy(
                transientError = FieldStrings.of(FieldStrings.Key.DEMO_MODE_OFF, state.language),
            )
            return
        }
        val outcome = graph.screening.runDemo(demoCase)
        val screen = VerdictPresenter.present(outcome.report, state.language, thumbnailKey = outcome.caseId)
        val drift = graph.demoSession.verify(scenario, outcome.report)
        state = state.copy(
            screen = ScreenState.Verdict(screen, outcome.report),
            transientError = drift?.detail,
        )
        speakVerdict()
    }

    /**
     * Speak one line.
     *
     * The worst finding, or the verdict word. Never the finding table, the layer states or the
     * policy fingerprint: a readout that recites four codes is slower than useless at a counter,
     * and an officer who stops listening to it stops hearing the one sentence that matters.
     */
    private fun speakVerdict() {
        val screen = (state.screen as? ScreenState.Verdict)?.screen ?: return
        val line = screen.findings.firstOrNull()?.text
            ?: screen.retakeInstructions.firstOrNull()?.text
            ?: screen.headline
        voice?.speak(line, isRetake = screen.tone == VerdictTone.RETAKE)
    }

    private fun advanceStep() {
        val capturing = state.screen as? ScreenState.Capturing ?: return
        if (capturing.progress.blocker != null) return
        val next = capturing.progress.currentIndex + 1
        if (next >= capturing.progress.total) {
            runScreening()
            return
        }
        state = state.copy(
            screen = capturing.copy(
                progress = capturing.progress.copy(
                    cards = capturing.progress.cards.mapIndexed { i, card ->
                        if (i < next) card.copy(done = true) else card
                    },
                    currentIndex = next,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun currentStep(): CaptureStep {
        val capturing = state.screen as? ScreenState.Capturing ?: return CaptureStep.DOCUMENT
        return fromUiStep(capturing.progress.current?.id ?: dev.kasoti.ui.CaptureStepId.DOCUMENT)
    }

    private fun currentPlan(track: Track): List<CaptureStep> = CapturePlanner.plan(track)

    private fun controller(): CameraController? = camera

    private fun macroStage(): MacroStage {
        capture.macroStage?.let { return it }
        val created = MacroStage(
            // The trained weights, if this build bundles them. D-MACRO first, committed synthetic
            // second, and `null` if neither is present — in which case every patch classifies as
            // UNKNOWN with a zero margin and the macro layer abstains. That is the honest
            // behaviour of a build with no model, and `DemoCatalogue.KNOWN_GAPS` records it
            // rather than hiding it behind a plausible label.
            //
            // Read through `dev.kasoti.factory.SvmModelReader`, the same reader the desktop console
            // and the eval harness use. Three readers of a weights file is two too many: the
            // failure they share is a permuted feature order producing a model that scores
            // plausibly on every patch.
            classifier = loadSvmClassifier(),
            registry = graph.registry,
            quality = graph.quality,
        )
        capture.macroStage = created
        return created
    }

    /**
     * The bundled macro model, or `null`.
     *
     * A missing asset is `null` and nothing else. A *malformed* asset is also `null` rather than a
     * crash, for the same reason the app completes a screening with no model at all: the other
     * layers are load-bearing and a weights file is not, and taking the whole cascade down over a
     * corrupt file would trade a visible gap for an invisible one. The refusal is not silent,
     * though — `SvmModelReader`'s message names the file and the reason, and it is logged.
     */
    private fun loadSvmClassifier(): ProcessClassifier? {
        for (asset in listOf(ModelStore.SVM_ASSET, ModelStore.SVM_SYNTHETIC_ASSET)) {
            val text = runCatching {
                applicationContext.assets.open(asset).bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val loaded = runCatching { SvmModelReader.parse(text, asset) }.getOrElse { error ->
                Log.w(TAG, "macro model $asset unusable, falling back to UNKNOWN: ${error.message}")
                return null
            }
            Log.i(
                TAG,
                "macro model bound from $asset: ${loaded.provenance}",
            )
            return ProcessClassifier(loaded.model)
        }
        return null
    }

    private fun codeText(code: FindingCode): String =
        "${Messages.of(code, state.language)} (${code.name})"

    private fun errorText(error: AppGraph.StartupError): String = codeText(error.code)

    /**
     * Attach the `PreviewView` from the composable tree, and start the camera if permitted.
     *
     * The `PreviewView` cannot be created in the activity because it needs a `Context` that
     * Compose owns, and creating it here would mean a second, competing view tree. So the
     * composable creates it and hands it over, exactly once — [startCamera] is idempotent and
     * [capture.previewView] is the guard.
     */
    fun attachPreview(previewView: androidx.camera.view.PreviewView) {
        if (capture.previewView === previewView) return
        capture.previewView = previewView
        if (state.permission.canScreen) startCamera()
    }

    private fun newState(): AppState = AppState(
        language = Language.ENGLISH,
        permission = PermissionState.UNKNOWN,
        screen = ScreenState.Idle,
        demoMode = false,
        demoBanner = null,
        trust = null,
        transientError = null,
    )

    /** The pipeline's scratch state. Deliberately private and not rendered directly. */
    private class CaptureWorkingState {
        var mrz: MrzResult? = null
        var mrzConfidence: Float? = null
        var viz: VizFields = VizFields.EMPTY
        var quad: Quad? = null
        var documentQuality: QualityReport? = null
        var macro: MacroStage.Stage? = null
        var macroStage: MacroStage? = null
        var previewView: androidx.camera.view.PreviewView? = null
        var loadedScenarioId: String? = null

        fun reset() {
            mrz = null
            mrzConfidence = null
            viz = VizFields.EMPTY
            quad = null
            documentQuality = null
            macro = null
            loadedScenarioId = null
        }
    }

    /**
     * The manual quad the operator has dragged, for the overlay to draw and the pipeline to crop.
     *
     * A *read* of a value the pipeline owns; the only way to change it is a `UiEvent.AcceptQuad`.
     *
     * This was a **top-level** `val` in the file and could not compile: `capture` is
     * `private val capture = CaptureWorkingState()` — a member of [MainActivity] — so a top-level
     * declaration has no `capture` in scope at all ("Unresolved reference 'capture'"). Both were
     * moved into the class as `private` instance properties, which is also the tighter visibility
     * the KDoc's own argument asks for: `setContent { CameraSurface(quad = currentQuad, …) }` is
     * the only reader, and it is *inside* this class, so nothing outside needs access. The KDoc's
     * claim that these are public "because the overlay composable is in a different file" was not
     * true — `CameraSurface` receives the quad as a parameter and never names either property.
     */
    private val currentQuad: Quad? get() = capture.quad

    /** The `PreviewView` the composable tree created, once it exists. */
    private val previewSurface: androidx.camera.view.PreviewView? get() = capture.previewView

    private companion object {
        /** Log tag for the model binding, so a field report can name which weights a build had. */
        const val TAG = "kasoti.model"

        /** FR-C4: "1 s clip, ≥5 frames". */
        const val FACE_CLIP_FRAMES = 5

        /**
         * The scenario the one-tap demo button runs.
         *
         * The forged twin, because it is the beat DEMO.md §3 spends forty seconds on and because
         * it is the one that must be *correct* — a demo whose red case lands on green needs a
         * second take, and DEMO.md §6 counts consecutive crash-free rehearsals.
         */
        const val DEFAULT_DEMO_SCENARIO = DemoCatalogue.FORGED_INKJET_TWIN
    }
}

// ---------------------------------------------------------------------------------------------
// Vocabulary mapping — the only place `:app-android`'s and `:ui`'s step enums meet
// ---------------------------------------------------------------------------------------------

/** `@return` the `:app-android` step for a `:ui` one. */
fun fromUiStep(id: dev.kasoti.ui.CaptureStepId): CaptureStep = when (id) {
    dev.kasoti.ui.CaptureStepId.DOCUMENT -> CaptureStep.DOCUMENT
    dev.kasoti.ui.CaptureStepId.MRZ -> CaptureStep.MRZ
    dev.kasoti.ui.CaptureStepId.MACRO -> CaptureStep.MACRO
    dev.kasoti.ui.CaptureStepId.FACE -> CaptureStep.FACE
}

/**
 * `@return` the `:ui` step, or `null` when the field layer knows a step `:ui` does not.
 *
 * `null` rather than a default, on purpose: a step added on one side and not the other then
 * produces a visibly shortened wizard — which an operator will report — instead of a step that
 * silently does nothing.
 */
fun toUiStep(step: CaptureStep): dev.kasoti.ui.CaptureStepId? = when (step) {
    CaptureStep.DOCUMENT -> dev.kasoti.ui.CaptureStepId.DOCUMENT
    CaptureStep.MRZ -> dev.kasoti.ui.CaptureStepId.MRZ
    CaptureStep.MACRO -> dev.kasoti.ui.CaptureStepId.MACRO
    CaptureStep.FACE -> dev.kasoti.ui.CaptureStepId.FACE
}

/** `@return` the `:ui` progress for a field-layer plan. Unmapped steps are dropped, not guessed. */
fun toUiProgress(
    plan: List<CaptureStep>,
    done: Set<CaptureStep> = emptySet(),
    meters: Map<CaptureStep, QualityMeter> = emptyMap(),
    currentIndex: Int = 0,
    language: Language = Language.ENGLISH,
) = CaptureProgressBuilder.build(
    steps = plan.mapNotNull(::toUiStep),
    // `toUiStep` is nullable *by design* (a step the field layer knows and `:ui` does not must be
    // dropped, not guessed), so `associate` would type this `Map<CaptureStepId?, Boolean>` and fail
    // the parameter. Same `mapNotNull { …?.let { … } }.toMap()` shape the `meters` line below uses.
    done = done.mapNotNull { step -> toUiStep(step)?.let { it to true } }.toMap(),
    meters = meters.mapNotNull { (step, meter) -> toUiStep(step)?.let { it to meter } }.toMap(),
    currentIndex = currentIndex,
    language = language,
)
