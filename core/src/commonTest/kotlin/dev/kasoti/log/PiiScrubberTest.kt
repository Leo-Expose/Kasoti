package dev.kasoti.log

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PII scrubber's contract: what it removes, what it reports, and what it promises a caller.
 *
 * **This is the suite the CI gate runs.** The adversarial and the negative cases live in
 * [PiiScrubberAdversarialTest] and [PiiScrubberFalsePositiveTest] respectively, and
 * `scripts/pii_scrubber_test.sh` runs all four classes in this file's package.
 *
 * The division of labour between the three is deliberate and worth stating, because it is the
 * distinction that matters most in a scrubber:
 *
 *  - **here**, the things that must happen. A name under a PII key goes. The marker is stable.
 *    The count is right. Scrubbing twice equals scrubbing once. Nothing throws.
 *  - **adversarial**, the ways somebody tries to stop it.
 *  - **negative**, the ways it must *not* act, because a scrubber that eats the case id, the
 *    audit tip and the finding codes is worse than no scrubber at all.
 */
class PiiScrubberTest {

    private val scrubber = PiiScrubber.DEFAULT
    private val marker = REDACTION_MARKER

    // ------------------------------------------------------------------ what it removes

    @Test
    fun `a name under a PII key is removed`() {
        val result = scrubber.scrub("name=RAMESH VERMA")
        assertEquals("name=$marker", result.text)
        assertEquals(1, result.redactions)
        assertEquals(RedactionRule.PII_KEY, result.primaryRule)
    }

    /**
     * A two-word name is one value, and the sibling fields after it are still readable.
     *
     * Both halves have to hold. A rule that stops the value at the first space leaves the
     * surname in the log, which is not a redaction; a rule that runs to the end of the line
     * takes `verdict=RED` with it, which makes the log useless. The value ends at the next
     * `key<sep>` token, so both survive.
     */
    @Test
    fun `a two-word name is removed whole and the next field is not`() {
        val result = scrubber.scrub("name=RAMESH VERMA verdict=RED code=R_MATH_01")
        assertEquals("name=$marker verdict=RED code=R_MATH_01", result.text)
        assertEquals(1, result.redactions)
    }

    @Test
    fun `a two-word name in an aligned table is removed whole`() {
        val result = scrubber.scrub("name    RAMESH VERMA    verdict  RED")
        assertEquals("name    $marker    verdict  RED", result.text)
    }

    @Test
    fun `a date of birth under any spelling is removed`() {
        for (key in listOf("dob", "DOB", "dateOfBirth", "date_of_birth", "Date-Of-Birth", "birth")) {
            for (sep in listOf("=", ": ", "  ")) {
                val result = scrubber.scrub("  $key${sep}1990-01-01")
                assertFalse(result.text.contains("1990"), "'$key$sep' leaked: ${result.text}")
                assertTrue(result.redactions >= 1, "'$key$sep' removed nothing")
            }
        }
    }

    @Test
    fun `a TD3 MRZ row is removed as one value, not as its fields`() {
        val row = "L898902C<3UTO6908061F9406236ZE184226B<<<<<10"
        val result = scrubber.scrub("ocr=$row")
        assertFalse(result.text.contains("L898902C"), "the row survived: ${result.text}")
        // One redaction for the row. A scrubber that fired the doc-number rule on the passport
        // field inside it and left the rest of the row visible would be leaking the birth date.
        assertEquals(1, result.redactions)
        assertEquals(RedactionRule.MRZ_ROW, result.primaryRule)
    }

    @Test
    fun `a TD1 MRZ row is removed`() {
        val result = scrubber.scrub("mrz=I<UTO<D<<883<<<<<<<<<<<<<<<<<<<")
        assertFalse(result.text.contains("D<<883"))
        assertTrue(result.text.contains(marker))
    }

    @Test
    fun `an email address is removed`() {
        val result = scrubber.scrub("contact ram.verma+post@example.com now")
        assertFalse(result.text.contains("example.com"), "leaked: ${result.text}")
        assertEquals(RedactionRule.EMAIL, result.primaryRule)
    }

    @Test
    fun `a phone number is removed`() {
        val result = scrubber.scrub("reach me on +9779812345678 today")
        assertFalse(result.text.contains("9812345678"), "leaked: ${result.text}")
        assertEquals(RedactionRule.PHONE, result.primaryRule)
    }

    @Test
    fun `a passport number is removed when it arrives with no key at all`() {
        val result = scrubber.scrub("document AB1234567 presented")
        assertFalse(result.text.contains("AB1234567"), "leaked: ${result.text}")
        assertEquals(RedactionRule.DOC_NUMBER, result.primaryRule)
    }

    @Test
    fun `a passport number is removed under a key as well`() {
        val result = scrubber.scrub("passport=XY1234567")
        assertEquals("passport=$marker", result.text)
    }

    @Test
    fun `an Aadhaar number is removed in both its grouped and bare forms`() {
        for (value in listOf("2345 6789 0123", "2345-6789-0123", "234567890123")) {
            val result = scrubber.scrub("ref $value")
            assertFalse(result.text.contains("6789"), "'$value' leaked: ${result.text}")
        }
    }

    @Test
    fun `a hex blob is removed`() {
        val blob = "a".repeat(64)
        assertFalse(scrubber.scrub("payload $blob").text.contains(blob))
    }

    @Test
    fun `a base64 blob is removed`() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg=="
        assertFalse(scrubber.scrub("attachment $blob").text.contains("QUJDREVG"))
    }

    /**
     * A compact JWT is three base64url segments, each *shorter* than the base64 blob floor, so
     * the base64 rule structurally cannot see one. It is also a bearer credential and, decoded,
     * a personal-subject assertion.
     */
    @Test
    fun `a compact JWT is removed`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
            ".dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        for (line in listOf("token=$jwt", "bearer $jwt", "Authorization: Bearer $jwt")) {
            val result = scrubber.scrub(line)
            assertFalse(result.text.contains("eyJhbGciOiJIUzI1NiIs"), "leaked in '$line'")
            assertEquals(RedactionRule.JWT, result.primaryRule, "wrong rule for '$line'")
        }
    }

    /**
     * Fail closed: PII that arrives *inside* a value is removed with the value, not looked for
     * inside it. A name cannot be recognised by shape, so this is the only thing that can be
     * done with it, and doing it means a PII field's value never reaches the log even if it
     * contains a phone number the shape rules would otherwise have caught on its own.
     */
    @Test
    fun `PII inside a keyed value is removed with the value and never reported separately`() {
        val result = scrubber.scrub("address=call 9812345678 about RAMESH")
        assertEquals("address=$marker", result.text)
        assertEquals(1, result.redactions, "the phone number was counted separately")
    }

    @Test
    fun `a PII-shaped value under a non-PII key is caught by shape`() {
        // The same string as above, with a key nobody classifies. Nothing about the *key* is
        // suspicious here, so the shape rules have to do the work — and they catch the phone
        // number but not the name, which is the "what it cannot do" case stated as a test.
        val result = scrubber.scrub("note=call 9812345678 about RAMESH")
        assertFalse(result.text.contains("9812345678"), "the phone number survived")
        assertTrue(result.text.contains("RAMESH"), "this is the documented free-prose limit")
        assertEquals(RedactionRule.PHONE, result.primaryRule)
    }

    // ------------------------------------------------------------------ JSON and shape

    @Test
    fun `a scrubbed JSON document is still a JSON document`() {
        val line = """{"name": "Ramesh Verma", "dob": "1990-01-01", "track": "PASSPORT"}"""
        val result = scrubber.scrub(line)
        assertEquals("""{"name": "[redacted]", "dob": "[redacted]", "track": "PASSPORT"}""", result.text)
        // The real assertion: quote counts balance, so a parser does not see a string that runs
        // off the end of the document. The version of this rule that emitted a doubled closing
        // quote produced a line that *looked* scrubbed and parsed as nothing at all.
        assertEquals(line.count { it == '"' }, result.text.count { it == '"' })
    }

    @Test
    fun `a truncated quoted value does not acquire a closing quote`() {
        // A caller can produce this. Emitting `name="[redacted]"` here would be inventing a
        // quote that was never logged, and a JSON reader would then swallow the next field.
        assertEquals("name=\"$marker", scrubber.scrub("name=\"unterminated").text)
    }

    @Test
    fun `a bracketed list under a PII key is removed whole`() {
        val result = scrubber.scrub("embedding=[0.12, -0.44, 0.98, 0.31]")
        assertFalse(result.text.contains("0.98"), "leaked: ${result.text}")
    }

    @Test
    fun `a manifest row under a PII key is removed whole, not up to the first space`() {
        // `name=SHARMA,RAMESH,OFFSET` is one field. Redacting only `SHARMA` would leave the
        // given names in the log looking like harmless data, which is the failure mode that
        // makes a partial redaction worse than none: it looks like it worked.
        val result = scrubber.scrub("name=SHARMA,RAMESH,OFFSET,sun")
        assertFalse(result.text.contains("SHARMA"))
        assertFalse(result.text.contains("RAMESH"))
        assertFalse(result.text.contains("OFFSET"))
    }

    @Test
    fun `stopping at a space keeps the rest of the line readable`() {
        assertEquals("name=$marker verdict=RED", scrubber.scrub("name=RAMESH verdict=RED").text)
    }

    // ------------------------------------------------------------------ the marker and the count

    @Test
    fun `the marker is a stable token rather than an empty string`() {
        // The requirement behind this: a reader must be able to tell "nothing was here" from
        // "something was here and it was removed". An empty replacement cannot.
        assertEquals(marker, REDACTION_MARKER)
        assertFalse(REDACTION_MARKER.isEmpty())
        assertEquals(marker, PiiScrubber.DEFAULT.scrub(marker).text)
    }

    @Test
    fun `the count says how much was removed rather than only that something was`() {
        val result = scrubber.scrub("name=RAMESH dob=1990-01-01 phone=9812345678")
        assertEquals(3, result.redactions)
        // One *rule*, three hits. The breakdown is keyed by rule, not by field, so a line with
        // three keyed fields has one entry and a count of three.
        assertEquals(1, result.byRule.size)
        assertEquals(3, result.byRule.getValue(RedactionRule.PII_KEY))
    }

    @Test
    fun `the count does not include neutralised control characters`() {
        // A terminal escape is an attack, not a name. Counting it as a redaction would tell an
        // operator that somebody's details reached disk when what happened was that somebody
        // tried to repaint their terminal.
        val result = scrubber.scrub("verdict=GREEN[31m")
        assertEquals(0, result.redactions)
        assertEquals(1, result.neutralisedControls)
        assertFalse(result.isClean)
    }

    @Test
    fun `a clean line reports clean`() {
        val result = scrubber.scrub("verdict=RED code=R_MATH_01 layers=3")
        assertTrue(result.isClean)
        assertEquals(0, result.redactions)
        assertEquals(0, result.neutralisedControls)
        assertTrue(result.byRule.isEmpty())
        assertNull(result.primaryRule)
    }

    @Test
    fun `the per-rule breakdown is populated only for rules that fired`() {
        val result = scrubber.scrub("name=RAMESH")
        assertEquals(setOf(RedactionRule.PII_KEY), result.byRule.keys)
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    fun `scrubbing twice equals scrubbing once`() {
        val lines = listOf(
            "name=RAMESH dob=1990-01-01 verdict=RED",
            """{"name": "Ramesh Verma", "passport": "AB1234567"}""",
            "ocr=L898902C<3UTO6908061F9406236ZE184226B<<<<<10",
            "email=x@y.com phone=9812345678 hex=${"a".repeat(64)}",
            "auditTip  ${"9f".repeat(32)}",
            "verdict=GREENtxt",
        )
        for (line in lines) {
            val once = scrubber.scrub(line)
            val twice = scrubber.scrub(once.text)
            // The *text* being equal is the idempotence property. The counts are not equal,
            // and must not be: the second pass has nothing left to do, so it reports nothing.
            // A pass that re-reported the first pass's work is the phantom-re-daction bug.
            assertEquals(once.text, twice.text, "text changed on re-scrub: '$line'")
            assertEquals(0, twice.redactions, "a second pass reported work it did not do: '$line'")
            assertEquals(0, twice.neutralisedControls, "a second pass re-reported controls: '$line'")
            assertTrue(twice.byRule.isEmpty(), "a second pass attributed a rule: '$line'")
            assertTrue(twice.isClean, "a second pass did not find the line already clean: '$line'")
        }
    }

    @Test
    fun `idempotence holds when the scrubber is asked three times`() {
        val once = scrubber.scrub("name=RAMESH VERMA dob=1990-01-01 verdict=RED")
        val twice = scrubber.scrub(once.text)
        val thrice = scrubber.scrub(twice.text)
        assertEquals(once.text, twice.text)
        assertEquals(once.text, thrice.text)
        assertEquals(2, once.redactions)
        assertEquals(0, twice.redactions)
        assertEquals(0, thrice.redactions)
    }

    // ------------------------------------------------------------------ totality

    @Test
    fun `an empty string is returned unchanged and reports clean`() {
        val result = scrubber.scrub("")
        assertEquals("", result.text)
        assertTrue(result.isClean)
    }

    @Test
    fun nothingThrowsOnMalformedInput() {
        val hostile = listOf(
            "", " ", "=", ":", "=", "name", "name=", "=RAMESH", "\"", "\"\"", "{", "}",
            "[]", "key=", "a".repeat(20_000), " ", "name=$marker",
            "::::::", "----", "....", " ", "�", "😀" + "name=X",
            "name=é́́=̀", "%s%s%s%n".repeat(50), "name=\${'$'}{HOME}",
        )
        for (line in hostile) {
            val result = scrubber.scrub(line)
            // The assertion is the call itself: a log writer that throws while handling an error
            // is a log writer that loses the error.
            assertTrue(result.text.length >= 0, "scrub('$line') produced nothing")
        }
    }

    @Test
    fun `a very long value is removed rather than truncated`() {
        val result = scrubber.scrub("name=" + "A".repeat(5_000))
        assertFalse(result.text.contains("AAAA"))
        assertEquals("name=$marker", result.text)
    }

    @Test
    fun `a long line of clean text is returned untouched`() {
        val line = "verdict=RED ".repeat(2_000)
        assertEquals(line, scrubber.scrub(line).text)
    }

    // ------------------------------------------------------------------ typed surface

}
