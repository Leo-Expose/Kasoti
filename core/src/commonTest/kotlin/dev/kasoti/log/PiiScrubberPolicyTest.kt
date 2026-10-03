package dev.kasoti.log

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scrubber's operating point: where the tunable floors come from, and which way the knobs turn.
 *
 * Split out of `PiiScrubberTest` because it is a different question. That file is about what the
 * scrubber does to a string; this one is about who decides how hard it presses — and the answer
 * (AGENTS.md §2) is that nobody decides it inside a regex. It comes out of `ThresholdRegistry`,
 * versioned and owned, or it is not in the pattern at all.
 *
 * The last test here is the one worth reading first. A knob that can be turned the wrong way
 * without anybody noticing is not a tunable; it is an undocumented behaviour change.
 */
class PiiScrubberPolicyTest {

    private val scrubber = PiiScrubber.DEFAULT

    // ------------------------------------------------------------------ thresholds

    @Test
    fun `the shipped policy is the registry's default, read not copied`() {
        val registry = ThresholdRegistry.defaults()
        assertEquals(
            registry[ThresholdName.REDACT_HEX_MIN_CHARS].toInt(),
            RedactionPolicy.DEFAULT.hexBlobMinChars,
        )
        assertEquals(
            registry[ThresholdName.REDACT_BASE64_MIN_CHARS].toInt(),
            RedactionPolicy.DEFAULT.base64BlobMinChars,
        )
        assertEquals(
            registry[ThresholdName.REDACT_PHONE_MIN_DIGITS].toInt(),
            RedactionPolicy.DEFAULT.phoneMinDigits,
        )
        assertEquals(
            registry[ThresholdName.REDACT_DOCNUM_MIN_DIGITS].toInt(),
            RedactionPolicy.DEFAULT.docNumberMinDigits,
        )
        assertEquals(
            registry[ThresholdName.REDACT_JWT_MIN_SEGMENT_CHARS].toInt(),
            RedactionPolicy.DEFAULT.jwtMinSegmentChars,
        )
    }

    @Test
    fun `a fractional character floor is refused rather than truncated`() {
        // `40.5` silently becoming `40` would mean the floor in the pattern is not the floor
        // anybody reviewed, and a scrubber nobody can reason about is a scrubber nobody trusts.
        val fractional = ThresholdRegistry.defaults().withValue(ThresholdName.REDACT_HEX_MIN_CHARS, 40.5)
        val failure = assertFailsWith<IllegalArgumentException> { RedactionPolicy.from(fractional) }
        val message = assertNotNull(failure.message)
        assertTrue(message.contains("whole number"), "unhelpful message: $message")
    }

    @Test
    fun `a policy that would degenerate the JWT rule is refused`() {
        assertFailsWith<IllegalArgumentException> { RedactionPolicy.DEFAULT.copy(jwtMinSegmentChars = 1) }
    }

    @Test
    fun `a tighter policy redacts more, which is what a floor is for`() {
        val lazy = scrubber.scrub("ref 2345 6789 0123")
        assertFalse(lazy.text.contains("6789"))

        // 12 digits is the phone floor; a policy that accepts 4-digit runs has to eat short
        // numeric fields too. Asserting the direction of the knob stops it being inverted.
        val triggerHappy = PiiScrubber(RedactionPolicy.DEFAULT.copy(phoneMinDigits = 4))
        val eager = triggerHappy.scrub("code=1234")
        assertTrue(eager.redactions > 0, "a lower floor did not make the scrubber more eager")
    }

    @Test
    fun `the ICAO and E164 widths the patterns use are the published ones`() {
        assertEquals(30, RedactionPolicy.ICAO_MRZ_TD1_CHARS)
        assertEquals(44, RedactionPolicy.ICAO_MRZ_TD3_CHARS)
        assertEquals(15, RedactionPolicy.E164_MAX_DIGITS)
        assertEquals(9, RedactionPolicy.MRZ_DOCNUM_CHARS)
        assertEquals(3, RedactionPolicy.JWT_SEGMENTS)
    }

    /**
     * The classifier is a tri-state, not a boolean.
     *
     * `UNKNOWN` is what a field added after the table was written looks like, and it is
     * the reason the console's field corpus is asserted separately: an unknown key falls
     * back to the shape rules, which is weaker, and a caller can see that it happened.
     */
    @Test
    fun `the key classifier is a tri-state, not a boolean`() {
        assertEquals(KeyClass.PII, PiiScrubber.classify("full_name"))
        assertEquals(KeyClass.OWN_DIGEST, PiiScrubber.classify("auditTip"))
        assertEquals(KeyClass.UNKNOWN, PiiScrubber.classify("shoeSize"))
    }

    @Test
    fun `a key that classifies as PII is a name under any spelling`() {
        for (key in listOf(
            "name", "Name", "NAME", "full_name", "fullName", "dateOfBirth", "date.of.birth",
            "dob", "phone", "mobile", "aadhaar", "aadhar", "voterId", "pan", "email",
            "address", "passport", "documentNumber", "embedding", "emb", "nationality",
            "mrz", "mrzLine2", "td3", "surname", "guardian", "sex",
        )) {
            assertTrue(PiiScrubber.isPiiKey(key), "'$key' should be PII")
        }
    }

    @Test
    fun `an operational key is never classified as PII`() {
        for (key in listOf(
            "case", "evt", "verdict", "device", "thresholdVersion", "fusionRuleVersion",
            "code", "layers", "score", "detail", "seq", "clock", "track", "image",
        )) {
            assertFalse(PiiScrubber.isPiiKey(key), "'$key' should not be PII")
        }
    }

    @Test
    fun `every rule has a wire name and the names are stable`() {
        // These strings are grepped, printed in `--help` and asserted on by the desktop
        // console, so they are a wire vocabulary rather than enum constant names.
        val names = PiiScrubber.patternNames
        assertTrue(names.contains("pii-key"))
        assertTrue(names.contains("mrz-line"))
        assertTrue(names.contains("control-chars"))
        assertTrue(names.contains("own-digests"))
        assertTrue(names.contains("jwt"))
        assertEquals(names.size, names.toSet().size, "duplicate rule name")
        assertEquals(RedactionRule.entries.size + 1, names.size, "a rule is missing a name")
    }

    @Test
    fun `control characters can be neutralised without scrubbing`() {
        // A distinct, separately testable transformation: a caller showing an error message in
        // a terminal needs this and not the whole scrubber.
        //
        // Only the ESC is replaced. The `[31m` behind it is ordinary printable text and is
        // deliberately left readable: without the escape byte a terminal renders it literally,
        // so eating it too would destroy evidence of the attempt rather than defuse it.
        assertEquals("a?[31mb", PiiScrubber.neutraliseControlCharacters("a\u001B[31mb"))
        assertEquals("plain", PiiScrubber.neutraliseControlCharacters("plain"))
        assertEquals("", PiiScrubber.neutraliseControlCharacters(""))
    }
}
