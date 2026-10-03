package dev.kasoti.ui

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QualityReport
import dev.kasoti.i18n.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowControllerTest {

    private fun state(
        permission: PermissionState = PermissionState.GRANTED,
        screen: ScreenState = ScreenState.Idle,
        language: Language = Language.ENGLISH,
    ) = AppState(
        language = language,
        permission = permission,
        screen = screen,
        demoMode = false,
        demoBanner = null,
        trust = null,
        transientError = null,
    )

    private fun capturing(
        steps: List<CaptureStepId> = listOf(CaptureStepId.DOCUMENT, CaptureStepId.MRZ),
        current: Int = 0,
        meter: QualityMeter = QualityMeter.UNKNOWN,
    ): ScreenState.Capturing {
        val meters = steps.associateWith { if (it == steps[current]) meter else QualityMeter.UNKNOWN }
        return ScreenState.Capturing(
            CaptureProgressBuilder.build(
                steps = steps,
                done = emptyMap(),
                meters = meters,
                currentIndex = current,
                language = Language.ENGLISH,
            ),
        )
    }

    // ------------------------------------------------------------------ permissions

    @Test
    fun `permission machine - first ask lands on one denial`() {
        assertEquals(PermissionState.DENIED_ONCE, PermissionState.UNKNOWN.onResult(granted = false))
        assertEquals(PermissionState.GRANTED, PermissionState.UNKNOWN.onResult(granted = true))
    }

    @Test
    fun `permission machine - a second denial is final and does not nag`() {
        val twice = PermissionState.UNKNOWN
            .onResult(granted = false)
            .onResult(granted = false)
        assertEquals(PermissionState.DENIED, twice)
        assertFalse(twice.canPrompt, "one calm re-prompt, then recovery is Settings")
    }

    @Test
    fun `permission machine - cannot-ask-again is its own state, not a spendable denial`() {
        val state = PermissionState.UNKNOWN.onResult(granted = false, canAskAgain = false)
        assertEquals(PermissionState.PERMANENTLY_DENIED, state)
        assertFalse(state.canPrompt)
        assertFalse(state.canScreen)
    }

    @Test
    fun `adversarial - withdrawing permission from a verdict screen cannot leave it on screen`() {
        val report = UiFixtures.decide(UiFixtures.forgedPassport())
        val withVerdict = state(screen = ScreenState.Verdict(VerdictPresenter.present(report, Language.ENGLISH), report))

        val after = FlowController.reduce(withVerdict, UiEvent.PermissionResult(granted = false, canAskAgain = true))

        assertTrue(after.screen is ScreenState.Permissions, "a RED must not remain visible without a camera")
        assertFalse(after.canScreen)
    }

    @Test
    fun `granting permission leaves the idle screen alone`() {
        val after = FlowController.reduce(state(permission = PermissionState.DENIED_ONCE), UiEvent.PermissionResult(granted = true, canAskAgain = true))
        assertEquals(PermissionState.GRANTED, after.permission)
        assertTrue(after.screen is ScreenState.Idle)
    }

    // ------------------------------------------------------------------ FR-C1 blocking

    @Test
    fun `advance on a blocked step changes nothing at all`() {
        val blocked = state(screen = capturing(meter = QualityMeter(false, 0.1f, listOf(FindingCode.G_BLUR), true)))

        val after = FlowController.reduce(blocked, UiEvent.AdvanceStep)

        assertSame(blocked.screen, after.screen, "a double-tap on a failed gate must not slip past")
    }

    @Test
    fun `a blocked step produces the retake instruction in the operator's language`() {
        val blocked = state(
            screen = capturing(meter = QualityMeter(false, 0.1f, listOf(FindingCode.G_GLARE), true)),
            language = Language.HINDI,
        )
        val after = FlowController.reduce(blocked, UiEvent.AdvanceStep)

        assertEquals(
            dev.kasoti.i18n.Messages.of(FindingCode.G_GLARE, Language.HINDI),
            after.blockingInstruction(),
        )
    }

    @Test
    fun `live quality updates the current step's meter only`() {
        val steps = listOf(CaptureStepId.DOCUMENT, CaptureStepId.MRZ, CaptureStepId.MACRO)
        val state = state(screen = capturing(steps, current = 1, meter = QualityMeter.UNKNOWN))

        val after = FlowController.reduce(
            state,
            UiEvent.LiveQuality(QualityReport(passed = false, causes = listOf(FindingCode.G_POSE), yawDegrees = 40f)),
        )

        val progress = (after.screen as ScreenState.Capturing).progress
        assertEquals(FindingCode.G_POSE, progress.current!!.meter.causes.single())
        assertTrue(
            progress.cards.filterIndexed { i, _ -> i != 1 }.all { it.meter.causes.isEmpty() },
            "a pose failure on the MRZ step must not appear on the document step",
        )
        assertEquals(listOf(FindingCode.G_POSE), after.blockingCauses())
    }

    @Test
    fun `back walks the stepper and stops at the first step`() {
        val state = state(screen = capturing(current = 1))
        assertEquals(0, ((FlowController.reduce(state, UiEvent.BackStep).screen) as ScreenState.Capturing).progress.currentIndex)

        val atStart = state(screen = capturing(current = 0))
        assertSame(atStart.screen, FlowController.reduce(atStart, UiEvent.BackStep).screen)
    }

    // ------------------------------------------------------------------ macro sub-state

    @Test
    fun `macro takes the photo zone first, then the text zone, then is ready`() {
        var s = state(screen = capturing(steps = listOf(CaptureStepId.MACRO)))
        assertNull(s.mapCapturingOrNull()?.awaiting, "no sub-state before the first patch")

        // 1f, not 0.9f: `MacroCard.awaiting` derives DONE from `fraction >= 1f` ("fully taken"),
        // because `fraction` is measured against the `Q_BLUR` bar and 0.9f is a taken-but-BLURRY
        // patch. The boundary cases live in `MacroCardShutterGateTest`.
        s = FlowController.reduce(s, UiEvent.MacroSharpness(MacroZoneSlot.PHOTO, 1f))
        assertEquals(MacroZoneSlot.TEXT, s.mapCapturingOrNull()?.awaiting)

        s = FlowController.reduce(s, UiEvent.MacroSharpness(MacroZoneSlot.TEXT, 1f))
        val macro = s.mapCapturingMacro()
        assertTrue(macro.photoZoneSharpness > 0f)
        assertTrue(macro.textZoneSharpness > 0f)
        assertTrue(macro.ready, "both FR-C3 patches taken means the step can advance")
        assertEquals(MacroZoneSlot.DONE, macro.awaiting)
    }

    @Test
    fun `macro does not report done while either patch is still below the bar`() {
        // The reducer must not be able to talk `MacroCard` into `DONE` early. `MacroStage.Stage
        // .canAdvance` is the authoritative field-layer check; this only proves the presentation
        // predicate stops agreeing with it at a partial patch.
        var s = state(screen = capturing(steps = listOf(CaptureStepId.MACRO)))
        s = FlowController.reduce(s, UiEvent.MacroSharpness(MacroZoneSlot.PHOTO, 0.55f))
        assertFalse(s.mapCapturingMacro().ready)
        assertEquals(MacroZoneSlot.PHOTO, s.mapCapturingMacro().awaiting)

        s = FlowController.reduce(s, UiEvent.MacroSharpness(MacroZoneSlot.PHOTO, 1f))
        assertFalse(s.mapCapturingMacro().ready, "the text zone has not been taken yet")
        assertEquals(MacroZoneSlot.TEXT, s.mapCapturingMacro().awaiting)
    }

    @Test
    fun `macro records clip use and focus lock because both change what the evidence means`() {
        var s = state(screen = capturing(steps = listOf(CaptureStepId.MACRO)))
        s = FlowController.reduce(s, UiEvent.SetClipUsed(true))
        s = FlowController.reduce(s, UiEvent.SetFocusLocked(true))

        val macro = s.mapCapturingMacro()
        assertTrue(macro.clipUsed, "without the clip the macro layer is ABSENT, not a pass")
        assertTrue(macro.focusLocked)
    }

    // ------------------------------------------------------------------ misc / global

    @Test
    fun `language switch is a single event and does not touch the screen`() {
        val s = state(screen = capturing(), language = Language.ENGLISH)
        val hi = FlowController.reduce(s, UiEvent.SetLanguage(Language.HINDI))
        assertEquals(Language.HINDI, hi.language)
        assertSame(s.screen, hi.screen)
    }

    @Test
    fun `next person clears the verdict and any transient error`() {
        val report = UiFixtures.decide(UiFixtures.forgedPassport())
        val s = state(screen = ScreenState.Verdict(VerdictPresenter.present(report, Language.ENGLISH), report))
            .copy(transientError = "camera busy")

        val after = FlowController.reduce(s, UiEvent.NextPerson)
        assertTrue(after.screen is ScreenState.Idle)
        assertNull(after.transientError)
    }

    @Test
    fun `the demo watermark is present iff demo mode is on`() {
        val off = state()
        assertNull(off.watermark)

        val on = FlowController.reduce(off, UiEvent.ToggleDemoMode)
        assertNotNull(on.watermark)
        assertTrue(on.demoMode)
        assertEquals(FieldStrings.of(FieldStrings.Key.DEMO_WATERMARK, Language.ENGLISH), on.watermark)

        assertNull(FlowController.reduce(on, UiEvent.ToggleDemoMode).watermark)
    }

    @Test
    fun `shutter and trust events are intentionally inert in the reducer`() {
        // They are answered by the camera and the trust store, not by the state machine.
        // Recording that they change nothing stops a future edit from quietly making the
        // reducer pretend a PIN was accepted.
        val s = state()
        assertSame(s, FlowController.reduce(s, UiEvent.Shutter))
        assertSame(s, FlowController.reduce(s, UiEvent.Enrol("1234")))
        assertSame(s, FlowController.reduce(s, UiEvent.Revoke("1234")))
    }

    private fun AppState.mapCapturingOrNull(): MacroCard? = (screen as ScreenState.Capturing).macroSlot

    private fun AppState.mapCapturingMacro(): MacroCard =
        checkNotNull(mapCapturingOrNull()) { "macro sub-state was not created" }
}
