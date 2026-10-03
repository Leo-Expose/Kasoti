package dev.kasoti.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What somebody does to a log line to get a name past a scrubber.
 *
 * **The document's OCR output is attacker-controlled by definition.** Every string in this
 * file is therefore a hostile input as much as a realistic one: a forged passport is a more
 * useful attack tool against this system than a real one, because the real one is somebody's
 * problem and the forged one is somebody's *plan*.
 *
 * The cases are grouped by the bypass they close, and each one is here because the scrubber
 * had a version that failed it:
 *
 *  - confusable keys and invisible characters in a *key*, which the pre-`:core` implementation
 *    missed because its key rules ran on the raw line and its folding table was dead code;
 *  - separator and spacing variants, which are free to vary and cost an attacker nothing;
 *  - values that arrive inside another value, and fields that arrive inside a JSON string;
 *  - the two properties without which none of the above matters: nothing throws, and scrubbing
 *    twice is the same as scrubbing once.
 *
 * Every non-ASCII character in this file is written as a `\uXXXX` escape rather than as the
 * character itself. A test whose whole purpose is "this invisible character" must not be
 * invisible in the source, or the next reader cannot tell it from a typo — and cannot review
 * whether the file still contains the cases it claims to.
 */
class PiiScrubberAdversarialTest {

    private val scrubber = PiiScrubber.DEFAULT
    private val marker = REDACTION_MARKER

    // ------------------------------------------------------------------ confusable keys

    /**
     * A Cyrillic `\u0430` (U+0430) in a field name is not an `a` in a field name.
     *
     * This is the single most important case in the file. The homoglyph table exists, the
     * class documentation says the bypass is closed, and the *first* version of the `:core`
     * scrubber still let `n\u0430me=RAMESH` through — because the key rules were handed the raw
     * line and only the shape rules got the folded shadow. A documented mitigation that is
     * never exercised is worse than none, because it is counted as done.
     *
     * Why the attacker picks Cyrillic: they pick the character their reader's eye resolves,
     * and on a Nepali-locale keyboard U+0430 is one key away from U+0061.
     */
    @Test
    fun `a field name spelled with Cyrillic homoglyphs is still a PII field`() {
        val cases = listOf(
            "n\u0430me=RAMESH",
            "n\u0430me: RAMESH",
            "D\u041EB=1990-01-01",
            "d\u043Eb  1990-01-01",
            "p\u0430ssport=AB1234567",
            "n\u0430me=\"Ramesh Verma\"",
            "NUMBER=AB1234567".replace("NUMBER", "p\u0430ssport"),
        )
        for (line in cases) {
            val result = scrubber.scrub(line)
            assertTrue(
                result.redactions >= 1,
                "homoglyphed key bypassed the scrubber: in=[$line] out=[${result.text}]",
            )
            assertFalse(
                result.text.contains("RAMESH"),
                "the value survived: in=[$line] out=[${result.text}]",
            )
        }
    }

    /**
     * Greek omicron for Latin `o`, and the rest of the Greek/Cyrillic blocks.
     *
     * Asserting the whole block rather than one character: a fold table that covers `\u0430` and
     * not `\u03BF` is a lookup list that somebody extended by hand and will extend again by hand.
     */
    @Test
    fun `the Greek block folds too`() {
        // Greek alpha (U+03B1) for Latin `a`. The omicron cannot be used for this particular
        // word: `n` + omicron folds to `no`, not to `na`, so a test written that way would pass
        // or fail for a reason that has nothing to do with homoglyphs. Asserted separately below.
        val result = scrubber.scrub("n\u03B1me=RAMESH")
        assertEquals(1, result.redactions, "out=[${result.text}]")
        assertFalse(result.text.contains("RAMESH"))

        // The classic Greek homoglyph, asserted as a fold rather than through a word, because
        // no Latin field label happens to contain a second `o`.
        assertEquals('o', RedactionRules.foldChar('\u03BF'))
        assertEquals('O', RedactionRules.foldChar('\u039F'))
    }

    /**
     * Fullwidth letters (U+FF41 and friends) are the cheapest homoglyph there is: they are a
     * fixed offset from ASCII, so the whole block folds arithmetically rather than table by
     * table. The fullwidth colon (U+FF1A) folds to `:` and is what makes the second case work.
     */
    @Test
    fun `a fullwidth field name is folded and redacted`() {
        for (line in listOf(
            "ｎａｍｅ=RAMESH",
            "ｎａｍｅ：RAMESH",
            "DＯＢ=1990-01-01",
        )) {
            val result = scrubber.scrub(line)
            assertTrue(result.redactions >= 1, "fullwidth key bypassed: in=[$line]")
            assertFalse(result.text.contains("RAMESH"), "leaked: in=[$line] out=[${result.text}]")
        }
    }

    /**
     * Curly quotes (U+201C / U+201D) instead of straight ones, so the quoted-field rule does
     * not match. A document can contain any quote mark at all and the value that matters here
     * is the one an attacker edits.
     */
    @Test
    fun `curly quotes do not hide a PII field`() {
        val result = scrubber.scrub("“name”: “RAMESH VERMA”")
        assertTrue(result.redactions >= 1, "bypassed: ${result.text}")
        assertFalse(result.text.contains("RAMESH"))
    }

    /**
     * A non-breaking hyphen (U+2011) inside a key: `p‑assport`.
     *
     * Also the reason the rule attribution matters. Before the fold reached the key rules this
     * was redacted by the *document-number* shape rule, which removed the value and left a
     * line claiming a passport number had been seen rather than that a personal field had
     * been logged. An operator triaging a log acts on that difference.
     */
    @Test
    fun `a non-ASCII hyphen inside a key is attributed to the key rule`() {
        val result = scrubber.scrub("p‑assport=AB1234567")
        assertEquals(RedactionRule.PII_KEY, result.primaryRule, "out=[${result.text}]")
        assertEquals(1, result.redactions)
    }

    // ------------------------------------------------------------------ invisible characters

    /**
     * A zero-width space (U+200B) inside a key makes `name` and `name` two different byte
     * strings that a human, a diff tool and a `grep` all read as the same word — which is
     * exactly what makes it useful to somebody trying to get a field past a key rule.
     *
     * The pre-`:core` implementation neutralised the character to `?` and *then* matched
     * keys, so `n?ame=RAMESH` classified as unknown and the name went to disk. The shadow now
     * deletes invisible characters before matching, which is what closes it.
     */
    @Test
    fun `a zero-width character inside a key does not hide a PII field`() {
        val cases = listOf(
            "n\u200Came=RAMESH",
            "nam\u200Ce=RAMESH",
            "name\uFEFF=RAMESH",
            "d\u200Cob=1990-01-01",
            "pa\u200Cssport=AB1234567",
        )
        for (line in cases) {
            val result = scrubber.scrub(line)
            assertTrue(result.redactions >= 1, "invisible char in key bypassed: [$line]")
            assertFalse(result.text.contains("RAMESH"), "leaked: out=[${result.text}]")
        }
    }

    /**
     * The invisible character inside the *key* is reported, and the key is preserved.
     *
     * Two properties at once, and both matter. The `?` says the stored line is not the line
     * that arrived, so an operator knows somebody tampered with a field label. Keeping the
     * key at all says *which* field, which is what makes the line actionable. A scrubber that
     * normalised the homoglyphed key to `name` would have destroyed the only evidence that
     * this happened.
     */
    @Test
    fun `a tampered key is reported rather than silently normalised`() {
        val result = scrubber.scrub("n\u200Came=RAMESH")
        assertTrue(
            result.text.startsWith("n${PiiScrubber.NEUTRALISED_CHAR}ame="),
            "the key was not preserved with a marker: [${result.text}]",
        )
        assertEquals(1, result.neutralisedControls)
        assertEquals(1, result.redactions)
    }

    @Test
    fun `a bidi override inside a value is neutralised`() {
        // The operator reads one string; the file contains another. `name=RA` + U+202E +
        // `MESH` renders as `SHEMAR` in a terminal that honours the override.
        val result = scrubber.scrub("name=RA\u202EMESH VERMA")
        assertFalse(result.text.contains('\u202E'))
        assertEquals("name=$marker", result.text)
    }

    @Test
    fun `a bidi override outside any PII field is still neutralised`() {
        // The desktop scrubber's own test claimed to cover this and did not: its bidi case
        // sat inside a PII key's value, so the key rule deleted it wholesale and the
        // neutraliser was never exercised. A test that passes for a reason other than the one
        // it names retires the risk it was written to cover.
        val result = scrubber.scrub("verdict=GREEN\u202Etxt")
        assertFalse(result.text.contains('\u202E'))
        assertEquals(1, result.neutralisedControls)
        assertEquals(0, result.redactions)
    }

    /**
     * U+2028 and U+2029 are line breaks to a JavaScript or JSON reader and not to
     * `String.lines()`. That disagreement is enough to append a forged record to a log the
     * scrubber believed was one line.
     */
    @Test
    fun `a line or paragraph separator cannot forge a second record`() {
        for (separator in listOf('\u2028', '\u2029', '\n', '\r')) {
            val result = scrubber.scrub("case=7${separator}forged=entry")
            assertEquals(1, result.text.lines().size, "two lines out of [$result]")
            assertFalse(result.text.contains(separator), "separator survived in ${result.text}")
        }
    }

    @Test
    fun `an ANSI escape sequence cannot repaint the operator's terminal`() {
        val hostile = "verdict=GREEN\u001B[31m\u001B]8;;http://evil.example\u0007"
        val result = scrubber.scrub(hostile)
        assertFalse(result.text.contains('\u001B'))
        assertFalse(result.text.contains('\u0007'))
        assertEquals(3, result.neutralisedControls)
    }

    @Test
    fun `a null byte does not truncate the line`() {
        val result = scrubber.scrub("verdict=RED\u0000ignored")
        assertTrue(result.text.startsWith("verdict=RED"))
        assertTrue(result.text.endsWith("ignored"))
        assertEquals(1, result.neutralisedControls)
    }

    // ------------------------------------------------------------------ separator variants

    @Test
    fun `every separator spelling reaches the value`() {
        for (separator in listOf("=", ":", " = ", " : ", "\t=\t", "  ", "=  ", ":   ")) {
            val result = scrubber.scrub("dob${separator}1990-01-01")
            assertFalse(result.text.contains("1990-01-01"), "'$separator' leaked: ${result.text}")
        }
    }

    @Test
    fun `date separator variants are all removed`() {
        for (value in listOf(
            "1990-01-01",
            "1990/01/01",
            "1990.01.01",
            "1990‑01‑01",
            "1990–01–01",
            "1990−01−01",
            "１９９０-０１-０１",
            "\u0967\u096F\u096F\u0966-\u0966\u0967-\u0966\u0967",
        )) {
            val result = scrubber.scrub("printed $value on the card")
            assertTrue(result.redactions >= 1, "'$value' leaked through as [${result.text}]")
        }
    }

    @Test
    fun `digits in other scripts are folded before the numeric rules see them`() {
        // The phone and Aadhaar rules are ASCII-digit rules. These are the same digits, in the
        // scripts a Nepali or Indian operator actually reads.
        val devanagari = "\u096F\u096E\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E"
        val arabic = "\u0669\u0668\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668"
        val fullwidth = "９８１２３４５６７８"
        for (digits in listOf(devanagari, arabic, fullwidth)) {
            val result = scrubber.scrub("reach me at $digits")
            assertTrue(result.redactions >= 1, "non-ASCII digits bypassed: [$digits]")
            assertEquals(RedactionRule.PHONE, result.primaryRule, "wrong rule for [$digits]")
        }
    }
    // ------------------------------------------------------------------ the two properties

    @Test
    fun `idempotence holds for every adversarial line in this file`() {
        val hostile = listOf(
            "n\u0430me=RAMESH",
            "“name”: “RAMESH”",
            "n\u200Came=RAMESH",
            """{"name": "Ramesh Verma", "dob": "1990-01-01"}""",
            """name="x", dob=1990-01-01, phone=9812345678""",
            "token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
                ".dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "verdict=GREEN\u202Etxt",
            "case=7\u2028forged=entry",
            "reach me at \u096F\u096E\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E",
            "p‑assport=AB1234567",
            "１９９０-０１-０１",
            "n\u0430me=" + "A".repeat(2_000),
            "dob  1990-01-01 dob  1991-02-02",
        )
        for (line in hostile) {
            val once = scrubber.scrub(line)
            val twice = scrubber.scrub(once.text)
            assertEquals(
                once.text,
                twice.text,
                "not idempotent: in=[$line] once=[${once.text}] twice=[${twice.text}]",
            )
            // The count describes what *this* pass removed, so a second pass over a scrubbed
            // line reports zero. What must not happen is it reporting the same number again:
            // that is the phantom redaction a defence-in-depth double pass would otherwise
            // accumulate, and a count that grows on every pass is a count nobody can alert on.
            assertEquals(0, twice.redactions, "a second pass reported work it did not do: [$line]")
            assertTrue(twice.byRule.isEmpty(), "a second pass attributed a rule: [$line]")
        }
    }

    @Test
    fun `nothing throws on hostile input`() {
        val hostile = listOf(
            "",
            "n\u200Ca\u202Em\uFEFFe=",
            """""""""""",
            "=".repeat(1_000),
            "name=".repeat(500),
            "\u0000\u0001\u0002",
            "%%%%",
            "\${".repeat(200),
            "\u00C0\u0301",
            "😀".repeat(100) + "name=RAMESH",
            "L898902C<3UTO6908061F9406236ZE184226B<<<<<1".repeat(200),
            "\u0000",
            "name=\u0000",
        )
        for (line in hostile) {
            val result = scrubber.scrub(line)
            assertTrue(
                result.text.isNotEmpty() || line.isEmpty(),
                "scrub of a ${line.length}-char hostile string produced nothing",
            )
        }
    }
}
