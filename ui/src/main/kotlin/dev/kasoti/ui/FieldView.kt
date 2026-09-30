package dev.kasoti.ui

/**
 * The view seam (DESIGN.md §1, D7).
 *
 * `dev.kasoti.ui` is presentation state and a contract; the rendering is somebody else's
 * problem. This interface is the whole contract, and it is small on purpose: a renderer needs
 * the state, the events, and nothing else. No `Bitmap`, no `Context`, no `Canvas`, no camera
 * handle — which is what makes the *same* screens usable by the Android app and, later, by a
 * desktop secondary-review pane.
 *
 * ## How a renderer attaches
 *
 * Android today: `dev.kasoti.android.view.ComposeFieldView` in `:app-android` implements this
 * with Jetpack Compose. It is a leaf — it reads [AppState], renders, and pushes [UiEvent]
 * back. It contains no threshold, no verdict rule and no string lookup.
 *
 * Desktop later: a second implementation in `:app-desktop`, plus one line in that module's
 * `build.gradle.kts` (`implementation(project(":ui"))`). It renders the same [AppState] and
 * gets the same behaviour, including the same Hindi strings and the same "GREY is not an
 * accusation" guarantee, because the guarantee lives in [VerdictPresenter] rather than in
 * either renderer.
 *
 * ## Contract obligations for an implementer
 *
 *  1. Render [ScreenState.Verdict]'s `screen.headline` at the largest size on the screen. It is
 *     the glanceable word (SPEC §3 P1: giant verdict, one-handed, sun).
 *  2. Apply [VerdictTone.paletteRole] to a colour *role*, never to a literal. `RETAKE` must
 *     resolve to a neutral, unfilled surface.
 *  3. Render [AppState.watermark] over everything, always, whenever it is non-null (FR-C5).
 *  4. Never render `Finding.message`. The `VerdictScreen` rows carry `Messages.of(code, lang)`.
 *  5. Treat [UiEvent.Shutter] as idempotent: the reducer does not change state for it, because
 *     whether the shutter fires is the camera layer's answer, not the state machine's.
 */
interface FieldView {

    fun render(state: AppState)

    /** The only way state changes. Implementations call back into [FlowController.reduce]. */
    fun dispatch(event: UiEvent)
}

/**
 * A no-op view, for tests and for the "camera not yet ready" case.
 *
 * Exists so a test can exercise a reducer without a renderer, and so a partially-wired screen
 * (permission granted, camera binding not finished) has something legal to hold. The
 * [dispatched] list is a recording, which is what makes it useful in tests: an assertion can
 * check that a tap produced exactly the events a keyboard would.
 */
class RecordingFieldView(initial: AppState = AppState(
    language = dev.kasoti.i18n.Language.ENGLISH,
    permission = PermissionState.GRANTED,
    screen = ScreenState.Idle,
    demoMode = false,
    demoBanner = null,
    trust = null,
    transientError = null,
)) : FieldView {

    var state: AppState = initial
        private set

    /** Every event this view was asked to dispatch, in order. */
    val dispatched: MutableList<UiEvent> = mutableListOf()

    /** Every state the reducer produced, in order. Includes the initial one. */
    val rendered: MutableList<AppState> = mutableListOf(initial)

    override fun render(state: AppState) {
        this.state = state
        rendered += state
    }

    override fun dispatch(event: UiEvent) {
        dispatched += event
        render(FlowController.reduce(state, event))
    }
}
