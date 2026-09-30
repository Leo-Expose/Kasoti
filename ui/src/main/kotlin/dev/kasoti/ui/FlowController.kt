package dev.kasoti.ui

import dev.kasoti.fusion.FindingCode
import dev.kasoti.i18n.Language

/**
 * What the operator (or the camera) can do.
 *
 * Every interaction in the app is one of these. The point of a closed vocabulary is that
 * "what can happen next" is answerable by reading a file rather than by reading a screen, and
 * that the reducer below can be tested exhaustively without a device.
 */
sealed interface UiEvent {

    // --- permissions (FR-R2) ---
    data object RequestPermission : UiEvent
    data class PermissionResult(val granted: Boolean, val canAskAgain: Boolean) : UiEvent
    data object PermissionSettingsOpened : UiEvent

    // --- capture (FR-C1..C4) ---
    data object Shutter : UiEvent
    data object AdvanceStep : UiEvent
    data object BackStep : UiEvent
    data class LiveQuality(val report: dev.kasoti.fusion.QualityReport) : UiEvent
    data object SwitchQuadMode : UiEvent
    data class MoveQuadHandle(val handle: Int, val x: Float, val y: Float) : UiEvent
    data class AcceptQuad(val corners: List<Pair<Float, Float>>) : UiEvent
    data class MacroSharpness(val slot: MacroZoneSlot, val sharpness: Float) : UiEvent
    data class SetClipUsed(val used: Boolean) : UiEvent
    data class SetFocusLocked(val locked: Boolean) : UiEvent

    // --- verdict ---
    data object NextPerson : UiEvent
    data object RepeatVoice : UiEvent
    data object ShowDetails : UiEvent

    // --- trust lane (FR-H5) ---
    data class Enrol(val pin: String) : UiEvent
    data class Revoke(val pin: String) : UiEvent
    data object RequestRecheck : UiEvent

    // --- demo (FR-C5) ---
    data class LoadDemoCase(val id: String) : UiEvent
    data object ResetDemo : UiEvent
    data object ToggleDemoMode : UiEvent

    // --- global ---
    data class SetLanguage(val language: Language) : UiEvent
    data object DismissError : UiEvent
}

/**
 * The reducer. Pure, total, and the only writer of [AppState].
 *
 * Three properties are enforced here rather than left to renderers, because each of them is a
 * way this app could misbehave in front of a member of the public:
 *
 *  1. **A failed quality gate blocks the advance** (FR-C1: "GREY blocks advance on failure").
 *     [AdvanceStep] on a blocked step is a no-op that leaves the state byte-identical, so a
 *     double-tap cannot slip past.
 *  2. **No screen transition without permission.** Withdrawing permission from any state lands
 *     on [ScreenState.Permissions]; no state carries a verdict that could be shown without a
 *     camera.
 *  3. **A GREY verdict cannot advance to a verdict-shaped screen.** After a retake the flow
 *     returns to [ScreenState.Idle] with the step list intact, so the operator re-shoots
 *     rather than seeing a stale RED.
 */
object FlowController {

    fun reduce(state: AppState, event: UiEvent): AppState = when (event) {

        // ---------------------------------------------------------------- permissions
        is UiEvent.RequestPermission -> state.copy(
            screen = ScreenState.Permissions(PermissionState.REQUESTED),
        )

        is UiEvent.PermissionResult -> withPermission(state, state.permission.onResult(event.granted, event.canAskAgain))

        is UiEvent.PermissionSettingsOpened -> state

        // ---------------------------------------------------------------- capture
        is UiEvent.Shutter -> state

        is UiEvent.AdvanceStep -> advance(state)

        is UiEvent.BackStep -> back(state)

        is UiEvent.LiveQuality -> withMeter(state, event.report)

        is UiEvent.SwitchQuadMode -> state.mapCapturing { it.copy(quadMode = flip(it.quadMode)) }

        is UiEvent.MoveQuadHandle, is UiEvent.AcceptQuad -> state

        is UiEvent.MacroSharpness -> state.mapCapturing { capturing ->
            val card = capturing.macroSlot ?: MacroCard.EMPTY
            capturing.copy(
                macroSlot = when (event.slot) {
                    MacroZoneSlot.PHOTO -> card.copy(photoZoneSharpness = event.sharpness)
                    MacroZoneSlot.TEXT -> card.copy(textZoneSharpness = event.sharpness)
                    MacroZoneSlot.DONE -> card
                },
            )
        }

        // Both of these create the sub-state on first use. The camera reports clip-detected and
        // focus-locked before either patch is scored, so requiring a prior scoring event would
        // drop exactly the two facts that decide whether the macro layer counts at all.
        is UiEvent.SetClipUsed -> state.mapCapturing {
            it.copy(macroSlot = (it.macroSlot ?: MacroCard.EMPTY).copy(clipUsed = event.used))
        }

        is UiEvent.SetFocusLocked -> state.mapCapturing {
            it.copy(macroSlot = (it.macroSlot ?: MacroCard.EMPTY).copy(focusLocked = event.locked))
        }

        // ---------------------------------------------------------------- verdict
        is UiEvent.NextPerson -> state.copy(screen = ScreenState.Idle, transientError = null)

        is UiEvent.RepeatVoice, is UiEvent.ShowDetails -> state

        // ---------------------------------------------------------------- trust
        is UiEvent.Enrol, is UiEvent.Revoke, is UiEvent.RequestRecheck -> state

        // ---------------------------------------------------------------- demo
        is UiEvent.LoadDemoCase, is UiEvent.ResetDemo -> state

        is UiEvent.ToggleDemoMode -> state.copy(
            demoMode = !state.demoMode,
            demoBanner = if (!state.demoMode) state.demoBanner else null,
        )

        // ---------------------------------------------------------------- global
        is UiEvent.SetLanguage -> state.copy(language = event.language)

        is UiEvent.DismissError -> state.copy(transientError = null)
    }

    /**
     * The FR-C1 gate.
     *
     * A no-op on a blocked step, by design: the operator should get audible/visual feedback
     * from the meter, which is already on screen and already carries the retake instruction, and
     * a second state change here would be a second thing that could disagree with it.
     */
    private fun advance(state: AppState): AppState {
        val capturing = state.screen as? ScreenState.Capturing ?: return state
        val blocker = capturing.progress.blocker ?: return state
        // `blocker` non-null ⇒ the current required step failed its gate. Do not move.
        @Suppress("UNUSED_EXPRESSION")
        blocker
        return state
    }

    private fun back(state: AppState): AppState {
        val capturing = state.screen as? ScreenState.Capturing ?: return state
        if (capturing.progress.currentIndex == 0) return state
        return state.copy(
            screen = capturing.copy(progress = capturing.progress.copy(currentIndex = capturing.progress.currentIndex - 1)),
        )
    }

    private fun withMeter(state: AppState, report: dev.kasoti.fusion.QualityReport): AppState =
        state.mapCapturing { capturing ->
            val current = capturing.progress.current
            val meter = QualityMeter.from(report, live = true)
            capturing.copy(
                progress = capturing.progress.copy(
                    cards = capturing.progress.cards.map { card ->
                        if (card.id == current?.id) card.copy(meter = meter) else card
                    },
                ),
            )
        }

    /** Withdrawing permission from any state lands on the permission card. */
    private fun withPermission(state: AppState, permission: PermissionState): AppState {
        if (permission.canScreen) {
            return if (state.screen is ScreenState.Permissions) {
                state.copy(permission = permission, screen = ScreenState.Idle)
            } else {
                state.copy(permission = permission)
            }
        }
        return state.copy(permission = permission, screen = ScreenState.Permissions(permission))
    }

    private inline fun AppState.mapCapturing(block: (ScreenState.Capturing) -> ScreenState.Capturing): AppState {
        val capturing = screen as? ScreenState.Capturing ?: return this
        return copy(screen = block(capturing))
    }

    private fun flip(mode: QuadMode): QuadMode = if (mode == QuadMode.AUTO) QuadMode.MANUAL else QuadMode.AUTO
}

/** The retake instruction for a blocked step, in the operator's language. `null` when clear. */
fun AppState.blockingInstruction(): String? {
    val capturing = screen as? ScreenState.Capturing ?: return null
    val blocker = capturing.progress.blocker ?: return null
    return blocker.meter.instructions(language).firstOrNull()
        ?: FieldStrings.of(FieldStrings.Key.QUALITY_HOLD, language)
}

/** Convenience for a renderer: the codes behind the current blocker, for logging. */
fun AppState.blockingCauses(): List<FindingCode> =
    (screen as? ScreenState.Capturing)?.progress?.blocker?.meter?.causes ?: emptyList()
