package dev.kasoti.ui

import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.Verdict

/**
 * How a verdict is *felt*, independent of what it says.
 *
 * This enum exists because the difference between "you are clear to go" and "we could not look
 * properly" is not a colour choice — it is a different relationship with the person standing
 * in front of the device. FUSION.md §1 is emphatic that GREY "is not a no, it is we could not
 * look properly", and SPEC principle 4 (surfaced through `FindingCode` retake instructions) is
 * emphatic that a failed quality gate must never read as an accusation.
 *
 * So the distinction is made once, here, in a value a test can assert on, instead of being left
 * to whichever colour the renderer happened to reach for:
 *
 *  - [RETAKE] is the only tone with [isAccusatory] `false` that is also not [CLEAR]. It is
 *    styled as a *camera instruction*, and its palette role is deliberately neutral — grey
 *    card, dashed border, an icon that reads as a camera. A RED screen uses a filled,
 *    high-contrast block. Nothing about a GREY can be mistaken for a RED at a glance or at a
 *    distance, which is exactly the confusion that matters when a projector is showing the
 *    screen to a room.
 *  - [STOP] (RED) is the only tone that is both filled and accusatory. It is allowed to shout.
 *  - [SECONDARY] (AMBER) is emphatically not an accusation either — it is a routing decision —
 *    but it does need to read as "a person must look at this next", so it gets its own tone
 *    rather than borrowing RED's.
 *
 * [paletteRole] names an abstract role, never a colour literal. The Android renderer maps roles
 * to real colour resources; that mapping is presentation, not policy, so it lives in the
 * renderer (and in `values/colors.xml` + `values-hi`, which is where a theme change is visible
 * in review).
 */
enum class VerdictTone(
    val isAccusatory: Boolean,
    val isBlocking: Boolean,
    val paletteRole: String,
) {
    /** GREEN — cleared. Nothing to do. */
    CLEAR(isAccusatory = false, isBlocking = false, paletteRole = "clear"),

    /** AMBER — a second pair of eyes, not a finding against the person. */
    SECONDARY(isAccusatory = false, isBlocking = true, paletteRole = "secondary"),

    /** RED — a hard proof. The only tone that may read as an accusation. */
    STOP(isAccusatory = true, isBlocking = true, paletteRole = "stop"),

    /** GREY — retake. Deliberately the most visually *unlike* [STOP] of the four. */
    RETAKE(isAccusatory = false, isBlocking = false, paletteRole = "retake"),
    ;

    /** A GREY must be neutral, never filled: a filled block is what makes RED read as RED. */
    val isNeutralSurface: Boolean get() = this == RETAKE

    companion object {
        fun of(verdict: Verdict): VerdictTone = when (verdict) {
            Verdict.GREEN -> CLEAR
            Verdict.AMBER -> SECONDARY
            Verdict.RED -> STOP
            // Fail-closed on an enum we do not recognise: a new verdict is a human decision,
            // and defaulting it to CLEAR would be the one genuinely dangerous default here.
            Verdict.GREY -> RETAKE
        }
    }
}

/**
 * Words that would turn a retake into an accusation, and must never appear in a GREY screen.
 *
 * Kept as data in the shared module rather than as an assertion inside one renderer so that a
 * future renderer — a desktop one, a Compose Multiplatform one, a screenshot-diff test — can be
 * held to the same rule. [assertNoAccusation] is what the tests call.
 */
object AccusationLexicon {

    private val FORBIDDEN = listOf(
        "fail", "failed", "fraud", "forged", "fake", "counterfeit", "banned", "blacklist",
        "illegal", "liar", "liar!", "guilty", "arrest", "detain", "criminal", "suspicious",
        "suspect", "reject", "denied", "disqualified", "caught", "stolen", "impersonator",
        "warrant", "prosecut", "असली", "नकली", "धोखा", "फ्रॉड", "अपराध", "गिरफ्तार",
        "विलंबित", "अस्वीकृत", "खारिज", "संदिग्ध",
    )

    /** @return the offending words present in [text], lowercased. Empty when clean. */
    fun violations(text: String): List<String> {
        val haystack = text.lowercase()
        return FORBIDDEN.filter { haystack.contains(it) }.sorted()
    }

    fun assertNoAccusation(text: String) {
        val hits = violations(text)
        require(hits.isEmpty()) { "GREY screen reads as an accusation: $hits in \"$text\"" }
    }
}

/** Severity → tone, for the finding rows underneath a verdict. */
fun Severity.tone(): VerdictTone = when (this) {
    Severity.RED -> VerdictTone.STOP
    Severity.AMBER -> VerdictTone.SECONDARY
    // A GREY cause is a photo instruction, and INFO is a receipt. Neither is ever a warning.
    Severity.GREY -> VerdictTone.RETAKE
    Severity.INFO -> VerdictTone.CLEAR
}
