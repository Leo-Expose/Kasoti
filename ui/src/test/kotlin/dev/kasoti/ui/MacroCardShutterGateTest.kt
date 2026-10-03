package dev.kasoti.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The macro shutter gate: what `ComposeFieldView.MacroCardScreen`'s `enabled = stage.ready` may
 * be true for (FR-C3).
 *
 * ## The defect this file exists to pin shut
 *
 * `MacroCard.awaiting` used to derive `DONE` from `fraction > 0f`. `fraction` is
 * `(blurVariance / Q_BLUR).coerceIn(0f, 1f)`, so a patch measured at, say, 40 % of the bar is
 * `0.4f` — **taken and blurry** — and `> 0f` read it as taken. The card could therefore report
 * `DONE`, and the shutter could light, on a blurry macro pair that the field layer's
 * `MacroStage.Stage.canAdvance` (`complete && both passed`) would refuse. The UI was offering an
 * action the verdict path then failed closed on. The fix is `fraction >= 1f`: "fully taken".
 *
 * ## What these tests deliberately do NOT claim
 *
 * They do not claim the field layer is happy, and they cannot: `:ui` has no access to
 * `MacroStage.Stage`, and `canAdvance` remains the authoritative check (its KDoc says so).
 * These tests claim only that `MacroCard` cannot report a patch as taken that the bar rejected —
 * which is exactly the half that was wrong, and exactly the half a `MacroCard` derivation is
 * responsible for. The cross-module identity (`fraction >= 1f` ⟺ `Sharpness.passed` ⟺ the
 * conjunct inside `canAdvance`) is asserted in `:app-android`'s `MacroStageTest`, where the
 * real `CaptureQuality` is reachable.
 *
 * AGENTS.md §3.1 / §4: at least one adversarial/mutated case per check. Every test below that
 * asserts `assertFalse(ready)` is one — the interesting inputs are the ones that must NOT enable
 * the shutter, and a suite of only-happy-path assertions would have stayed green through the
 * defect above.
 */
class MacroCardShutterGateTest {
    /** The `Q_BLUR` bar's registry range is `[30, 400]`, so a bar of 0 cannot occur. */
    private val bar = 100f

    private fun fractionOf(blurVariance: Float): Float = (blurVariance / bar).coerceIn(0f, 1f)

    // ------------------------------------------------------------------ the passing case

    @Test
    fun `both patches fully taken means the shutter is offered`() {
        val card =
            MacroCard(
                photoZoneSharpness = 1f,
                textZoneSharpness = 1f,
                clipUsed = true,
                focusLocked = true,
            )
        assertEquals(MacroZoneSlot.DONE, card.awaiting)
        assertTrue(card.ready, "two patches at or above the bar is the one case that offers the shutter")
    }

    @Test
    fun `a fraction above 1 is still done because the producer clamps it`() {
        // Defensive: `CaptureQuality` clamps, but a card constructed from any other producer must
        // not be able to read as *not* taken just by being generous.
        val card =
            MacroCard(
                photoZoneSharpness = 4f,
                textZoneSharpness = 9f,
                clipUsed = true,
                focusLocked = true,
            )
        assertEquals(MacroZoneSlot.DONE, card.awaiting)
        assertTrue(card.ready)
    }

    // ------------------------------------------------------------------ adversarial cases

    @Test
    fun `ADVERSARIAL a blurry but taken pair does not offer the shutter`() {
        // The exact shape the `> 0f` derivation got wrong: both patches were measured, both bars
        // are partially filled, and the old code answered DONE. The camera should be sent back to
        // the PHOTO zone for a re-shoot.
        val blurry = fractionOf(40f)
        assertTrue(blurry > 0f, "the fixture must be taken-but-blurry, not absent")
        val card =
            MacroCard(
                photoZoneSharpness = blurry,
                textZoneSharpness = blurry,
                clipUsed = true,
                focusLocked = true,
            )
        assertFalse(card.ready, "a pair below the bar must not offer the shutter (regression)")
        assertEquals(MacroZoneSlot.PHOTO, card.awaiting, "the operator is pointed back at the photo zone")
    }

    @Test
    fun `ADVERSARIAL one sharp patch and one blurry patch is not a pair`() {
        val card =
            MacroCard(
                photoZoneSharpness = 1f,
                textZoneSharpness = fractionOf(72.5f),
                clipUsed = true,
                focusLocked = true,
            )
        assertFalse(card.ready, "a half-sharp pair is not a sharp pair")
        assertEquals(MacroZoneSlot.TEXT, card.awaiting)
    }

    @Test
    fun `ADVERSARIAL a fully sharp pair that is not focus locked still only reports sharpness`() {
        // `MacroCard.ready` is a *presentation* predicate about the sharpness bars, not about the
        // field layer's pair verdict. This test pins that scope down on purpose: it must NOT start
        // reading `focusLocked`, because that would make `:ui` a second, weaker copy of
        // `canAdvance` (which also needs both patches *in focus*), and AGENTS.md §8 forbids two
        // implementations of one safety rule. The authoritative focus check lives in
        // `MacroStage.Stage.canAdvance`; this assertion exists so neither side is silently
        // widened later.
        val card =
            MacroCard(
                photoZoneSharpness = 1f,
                textZoneSharpness = 1f,
                clipUsed = true,
                focusLocked = false,
            )
        assertTrue(card.ready, "sharpness only — see the KDoc; canAdvance owns the focus half")
    }

    @Test
    fun `ADVERSARIAL a partially taken pair does not offer the shutter`() {
        val card =
            MacroCard(
                photoZoneSharpness = 1f,
                textZoneSharpness = 0f,
                clipUsed = true,
                focusLocked = true,
            )
        assertFalse(card.ready, "one of two patches missing is not a pair")
        assertEquals(MacroZoneSlot.TEXT, card.awaiting)
    }

    @Test
    fun `ADVERSARIAL nothing taken at all offers nothing`() {
        val card = MacroCard.EMPTY
        assertFalse(card.ready)
        assertEquals(MacroZoneSlot.PHOTO, card.awaiting)
    }

    @Test
    fun `ADVERSARIAL clip and focus flags cannot make a blurry pair offer the shutter`() {
        // Every boolean the card carries set to the operator-friendly value. The shutter must still
        // be closed, because the gate reads the sharpness bars and nothing else.
        val card =
            MacroCard(
                photoZoneSharpness = fractionOf(29.9f),
                textZoneSharpness = fractionOf(29.9f),
                clipUsed = true,
                focusLocked = true,
            )
        assertFalse(card.ready, "flags must not be a bypass around the sharpness bar")
    }

    // ------------------------------------------------------------------ the invariant

    @Test
    fun `awaiting is DONE if and only if every fraction is at or above the bar`() {
        // The property that makes `MacroCard` and `MacroStage.Stage.canAdvance` agree about
        // sharpness. Swept rather than asserted on two hand-picked values: the boundary at exactly
        // 1f is where a `>` vs `>=` mistake would hide.
        val values = listOf(0f, 0.001f, 0.5f, 0.9999f, 1f, 1.5f)
        for (photo in values) {
            for (text in values) {
                val card =
                    MacroCard(
                        photoZoneSharpness = photo,
                        textZoneSharpness = text,
                        clipUsed = true,
                        focusLocked = true,
                    )
                val bothAtBar = photo >= 1f && text >= 1f
                assertEquals(
                    bothAtBar,
                    card.ready,
                    "photo=$photo text=$text: ready must be exactly (both >= 1f)",
                )
                assertEquals(
                    bothAtBar,
                    card.awaiting == MacroZoneSlot.DONE,
                    "photo=$photo text=$text: DONE must be exactly (both >= 1f)",
                )
            }
        }
    }

    @Test
    fun `the shutter gate is a pure function of the two fractions`() {
        // Same inputs, same answer, every time — the property that broke when `ready` was a stored
        // field written back by the reducer.
        val card =
            MacroCard(
                photoZoneSharpness = 0.6f,
                textZoneSharpness = 1f,
                clipUsed = false,
                focusLocked = false,
            )
        repeat(5) { assertFalse(card.ready) }
        assertEquals(MacroZoneSlot.PHOTO, card.awaiting)
        assertEquals(card, card.copy(), "the card is a value; equality must survive a copy")
    }
}
