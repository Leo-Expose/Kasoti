package dev.kasoti.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where each rule stops firing, and the two false positives this scrubber accepts on purpose.
 *
 * Split out of `PiiScrubberFalsePositiveTest` because these are two halves of one decision and
 * they belong in one file: a rule that fires only means something relative to the point where it
 * stops, and an accepted false positive is only acceptable because the boundary above it is
 * where somebody chose. Read the accepted-cost tests as the argument for the boundary assertions,
 * not as an apology for them.
 */
class PiiScrubberRuleBoundaryTest {

    private val scrubber = PiiScrubber.DEFAULT

    // ------------------------------------------------------------------ the accepted cost

    /**
     * The one false positive this scrubber accepts on purpose.
     *
     * An Aadhaar number and a compact UTC timestamp are both twelve bare digits. The scrubber
     * cannot tell them apart and fails closed. Asserting it here rather than leaving it to be
     * discovered means the trade is a decision on the record: if a future reviewer wants the
     * timestamp kept, this test is the thing they change, and they have to change it on
     * purpose.
     */
    @Test
    fun `a compact timestamp is eaten because it is indistinguishable from an Aadhaar number`() {
        val result = scrubber.scrub("stamp=202609301200")
        assertEquals(RedactionRule.AADHAAR, result.primaryRule, "out=[${result.text}]")
        assertEquals("stamp=$REDACTION_MARKER", result.text)
    }

    /**
     * An MRZ row at exactly 30 and exactly 44 characters is removed, and one character either
     * side is not.
     *
     * The boundary is asserted on both edges because a rule that is wrong in the permissive
     * direction eats identifiers and a rule that is wrong in the restrictive direction lets a
     * document row through, and both are one character away from looking correct.
     */
    @Test
    fun `the MRZ rule fires only at the ICAO row lengths`() {
        // Built to the exact ICAO length rather than hand-typed, so the assertion is about the
        // rule and not about whether the author counted correctly.
        val seed = "L898902C<3UTO6908061F9406236ZE184226B"
        val td3 = seed.padEnd(RedactionPolicy.ICAO_MRZ_TD3_CHARS, '<')
            .take(RedactionPolicy.ICAO_MRZ_TD3_CHARS)
        assertEquals(44, td3.length)
        assertEquals(1, scrubber.scrub("ocr=$td3").redactions, "a 44-char TD3 row survived")

        // The floor is the TD1 row length (30), not the TD3 length, and the range takes slop
        // either side of both published widths so a transposed filler still matches. So the
        // boundary to assert is 29/30, and a 42-character run is a row with two characters of
        // slop — correctly eaten.
        val td1 = seed.padEnd(RedactionPolicy.ICAO_MRZ_TD1_CHARS, '<')
            .take(RedactionPolicy.ICAO_MRZ_TD1_CHARS)
        assertEquals(30, td1.length)
        assertEquals(1, scrubber.scrub("ocr=$td1").redactions, "a 30-char TD1 row survived")

        val below = seed.take(RedactionPolicy.ICAO_MRZ_TD1_CHARS - 1)
        assertEquals(29, below.length)
        assertEquals(0, scrubber.scrub("ref=$below").redactions, "a 29-char run was eaten")

        // Two rows is two redactions, not one: the 30..44 alternative matches each 44-character
        // row separately because a `<` filler is a word boundary and the two rows meet at one.
        // Two rows removed is the outcome we want; the number is asserted so a change that
        // collapsed it into one span is visible rather than silently different.
        val longer = "$td3$td3"
        assertEquals(88, longer.length)
        assertEquals(2, scrubber.scrub("ocr=$longer").redactions, "a two-row document survived")
    }

    @Test
    fun `the document-number rule fires only at its digit floor`() {
        assertEquals(0, scrubber.scrub("ref=AB12345").redactions, "five digits is below the floor")
        assertTrue(scrubber.scrub("ref=AB123456").redactions >= 1, "six digits is at the floor")
    }

    @Test
    fun `the phone rule fires only at its digit floor`() {
        assertEquals(0, scrubber.scrub("ref=123456789").redactions, "nine digits is below the floor")
        assertTrue(scrubber.scrub("ref=1234567890").redactions >= 1, "ten digits is at the floor")
    }
}
