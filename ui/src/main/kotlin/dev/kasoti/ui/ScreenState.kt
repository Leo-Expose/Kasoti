package dev.kasoti.ui

import dev.kasoti.fusion.VerdictReport
import dev.kasoti.i18n.Language

/**
 * The camera-permission state machine (FR-R2, and the "first run" half of NFR-O1).
 *
 * Modelled as an explicit machine rather than a Boolean because the interesting case is the
 * third one, and it is the case a kiosk in a dusty booth actually hits: the operator taps
 * Deny *once*, by accident, with gloves on. A boolean app either stays stuck on a dead screen
 * or nags again immediately. [DENIED_ONCE] buys exactly one calm, non-blocking re-prompt and
 * then settles into [DENIED] with a named recovery line — "open Settings", not "the app is
 * broken".
 *
 * The rule the machine encodes: **no permission, no screening, and never a fabricated
 * verdict.** There is no transition out of a denied state that produces a `VerdictScreen`.
 */
enum class PermissionState {
    /** Never asked. The first-run card explains why, then asks. */
    UNKNOWN,

    /** Asked, waiting on the system dialog. */
    REQUESTED,

    /** Granted. */
    GRANTED,

    /** Denied, but the operator has not been shown a re-prompt yet. */
    DENIED_ONCE,

    /** Denied and the re-prompt is spent. Recovery is a trip to system Settings. */
    DENIED,

    /**
     * The OS will not ask again (Android 11+ "don't ask again"), or the device has no camera
     * at all. Distinct from [DENIED] because there is no re-prompt to spend.
     */
    PERMANENTLY_DENIED,
    ;

    val canScreen: Boolean get() = this == GRANTED

    /** Whether asking again is even possible. */
    val canPrompt: Boolean get() = this == UNKNOWN || this == DENIED_ONCE

    fun onResult(granted: Boolean, canAskAgain: Boolean = true): PermissionState = when {
        granted -> GRANTED
        !canAskAgain -> PERMANENTLY_DENIED
        this == DENIED_ONCE -> DENIED
        else -> DENIED_ONCE
    }
}

/** Where the trust lane stands for the current subject (FR-H5). Presentation only. */
data class TrustCard(
    val enrolled: Boolean,
    val revoked: Boolean,
    val fastPathAvailable: Boolean,
    val recheckScheduled: Boolean,
    val daysUntilReverify: Int,
    val stateName: String,
)

/**
 * The one screen's state.
 *
 * A single sealed hierarchy rather than a nav graph on purpose: the field app has exactly one
 * primary flow with a bounded number of states, and a nav graph's back-stack on a device that
 * may be killed by the OS between steps is a source of "the operator came back and the app had
 * forgotten which step they were on" — which on a screening device means a stranger's document
 * is handed back without a verdict. [CaptureProgress] is the resume point and it is data, so
 * it can be persisted verbatim.
 *
 * Everything here is immutable. The renderer observes; [FlowController] is the only writer.
 */
sealed interface ScreenState {

    /** Camera permission not yet granted. No capture, no verdict. */
    data class Permissions(val permission: PermissionState) : ScreenState

    /** Mid-capture. [progress] says which step and whether it is blocked. */
    data class Capturing(
        val progress: CaptureProgress,
        val quadMode: QuadMode = QuadMode.AUTO,
        val macroSlot: MacroCard? = null,
    ) : ScreenState

    /** The macro step's two-patch sub-state (FR-C3). */
    data class Macro(val stage: MacroCard) : ScreenState

    /** A verdict is on screen. */
    data class Verdict(
        val screen: VerdictScreen,
        val report: VerdictReport,
    ) : ScreenState

    /** Nothing captured yet — the "next person" idle card. */
    data object Idle : ScreenState

    /** Trust-lane enrolment / revoke / re-check (FR-H5). */
    data class Trust(val card: TrustCard) : ScreenState
}

/** Auto crop vs the 4-drag-handle manual fallback (FR-C2). */
enum class QuadMode { AUTO, MANUAL }

/**
 * Which of the two macro patches is being taken, plus the sharpness bar (FR-C3).
 *
 * A sharpness of `0f` is the "not taken yet" sentinel rather than a nullable, because it is
 * also what the bar renders as empty, and a bar with a hole in it reads correctly to an
 * operator in a way that a missing row does not. [awaiting] is the single place that
 * convention is applied.
 */
data class MacroCard(
    val photoZoneSharpness: Float,
    val textZoneSharpness: Float,
    val clipUsed: Boolean,
    val focusLocked: Boolean,
) {
    /**
     * Which patch the camera should be pointed at next.
     *
     * A single source of truth on purpose. An earlier shape carried `ready` as a stored field
     * set *from* this, which meant the reducer wrote a derived value back into the object it
     * was derived from — a cycle that deadlocked the second patch, because "both patches
     * taken" could never become true. `ready` is now derived too, and neither can disagree.
     */
    val awaiting: MacroZoneSlot get() = when {
        photoZoneSharpness <= 0f -> MacroZoneSlot.PHOTO
        textZoneSharpness <= 0f -> MacroZoneSlot.TEXT
        else -> MacroZoneSlot.DONE
    }

    val ready: Boolean get() = awaiting == MacroZoneSlot.DONE

    companion object {
        val EMPTY = MacroCard(0f, 0f, clipUsed = false, focusLocked = false)
    }
}

enum class MacroZoneSlot { PHOTO, TEXT, DONE }

/** Everything the renderer needs that is not screen-local. */
data class AppState(
    val language: Language,
    val permission: PermissionState,
    val screen: ScreenState,
    val demoMode: Boolean,
    val demoBanner: String?,
    val trust: TrustCard?,
    /** A blocking, operator-facing error. Never a verdict; always a recovery line. */
    val transientError: String?,
) {
    val canScreen: Boolean get() = permission.canScreen && screen !is ScreenState.Permissions

    val watermark: String? get() = if (demoMode) FieldStrings.of(FieldStrings.Key.DEMO_WATERMARK, language) else null
}
