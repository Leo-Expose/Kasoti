package dev.kasoti.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Injection and scale: a value that carries structure of its own, and inputs large enough to
 * break an implementation that assumed tidy data.
 *
 * Split out of `PiiScrubberAdversarialTest` so each file stays inside the 400 lines AGENTS.md §2
 * asks for, and because the two ask different questions. That file asks what an attacker did to
 * the *field labels*; this one asks what an attacker did to the *values*, and what happens with
 * fifty thousand of them.
 *
 * The theme: a key comes from our code and a value comes off a document somebody else wrote.
 * That is the whole difference in how much the two can be trusted to mean what they say.
 */
class PiiScrubberInjectionTest {

    private val scrubber = PiiScrubber.DEFAULT
    private val marker = REDACTION_MARKER

    // ------------------------------------------------------------------ injection

    /**
     * A value that closes its own quote and opens a field of its own.
     *
     * The injected `dob` is a PII key like any other, so it is redacted too. That is what
     * stops `name="x", dob=1990-01-01` from smuggling a date of birth *inside* a value that
     * a reader of the first scrubbed line would assume was already handled.
     */
    @Test
    fun `a quote-injecting value cannot smuggle a second PII field past the scrubber`() {
        val hostile = """name="x", dob=1990-01-01, phone=9812345678"""
        val result = scrubber.scrub(hostile)
        assertFalse(result.text.contains("1990-01-01"), "the injected DOB survived")
        assertFalse(result.text.contains("9812345678"), "the injected phone survived")
        assertTrue(result.redactions >= 3, "expected three redactions, got ${result.redactions}")
    }

    @Test
    fun `a brace in a value does not smuggle a payload past a value that stops early`() {
        // A redactor that stopped a value at the closing brace would leave `jdbc:mysql://…`
        // visible after a `${`. `}` and `)` stay inside the value on purpose.
        val result = scrubber.scrub("name=\${System.getenv('SECRET')}\${jdbc:mysql://u:p@h/db}")
        assertFalse(result.text.contains("System.getenv"))
        assertFalse(result.text.contains("jdbc:mysql"))
        assertTrue(result.text.contains(marker))
    }

    @Test
    fun `a template expression in a non-PII field is left visible and unexpanded`() {
        // The scrubber is a redactor, not a template engine. A scrubber that silently
        // evaluated its input would be a far worse bug than one that passes a string through.
        val line = "error=\${jdbc:mysql://host/db} connection refused"
        assertEquals(line, scrubber.scrub(line).text)
    }

    @Test
    fun `a CSV-injecting value cannot add a column`() {
        // A leading `=`, `+`, `-` or `@` is a formula to a spreadsheet. The value goes either
        // way; what matters is that a whole CSV row does not survive in pieces.
        val result = scrubber.scrub("name=SHARMA,RAMESH,OFFSET,sun")
        assertFalse(result.text.contains("SHARMA"))
        assertFalse(result.text.contains("RAMESH"))
        assertFalse(result.text.contains("OFFSET"))
    }

    // ------------------------------------------------------------------ scale and structure

    @Test
    fun `an empty string is handled`() {
        assertEquals("", scrubber.scrub("").text)
    }

    @Test
    fun `a very long value is removed rather than truncated`() {
        val result = scrubber.scrub("name=" + "A".repeat(50_000))
        assertEquals("name=$marker", result.text)
    }

    @Test
    fun `many fields in one line are each reported`() {
        val line = (1..50).joinToString(" ") { "name=PERSON$it" }
        val result = scrubber.scrub(line)
        assertEquals(50, result.redactions)
        assertFalse(result.text.contains("PERSON"))
    }

    @Test
    fun `a value embedded in a JSON-ish string does not survive`() {
        val line = """{"fields":{"holder":"RAMESH VERMA","email":"a@b.com"},"seq":7}"""
        val result = scrubber.scrub(line)
        assertFalse(result.text.contains("RAMESH"), "leaked: ${result.text}")
        assertFalse(result.text.contains("a@b.com"), "leaked: ${result.text}")
        assertTrue(result.text.contains("\"seq\":7"), "the safe sibling field was lost: ${result.text}")
    }

    /**
     * A value split across two log lines.
     *
     * Two honest statements about this, because the temptation is to write a rule for it:
     *
     *  - each line *is* scrubbed, and a line that carries half a passport number has half a
     *    passport number in it;
     *  - no rule can see the other half, because `PiiScrubber` is given one string at a time.
     *    Joining lines before scrubbing would fix it and would also mean the scrubber had to
     *    decide what a line *is*, which is a property of the log writer, not of a redactor.
     *
     * So this asserts the property that is actually available: neither half completes a shape,
     * and the half that carries the PII key has its value removed.
     */
    @Test
    fun `a keyed value split across two lines is removed by the half that has the key`() {
        val first = scrubber.scrub("passport=AB12")
        val second = scrubber.scrub("34567 verdict=RED")
        assertFalse(first.text.contains("AB12"), "the keyed half survived")
        // The second half is a bare five-digit run: no key, no shape, ten digits below the
        // phone floor. There is nothing for the scrubber to recognise, and claiming otherwise
        // would be claiming a control it does not have. The honest statement is the one below.
        assertEquals("34567 verdict=RED", second.text)
        assertEquals(0, second.redactions)
    }

    @Test
    fun `a document number split across two lines is not reassembled`() {
        // The complementary case: the second half on its own is not a document number, and
        // because the scrubber is handed one string at a time it cannot be joined with the
        // first. This is a property of the interface, not a rule, and it is asserted so that
        // somebody adding a line-joining heuristic has to break this test on purpose.
        val first = scrubber.scrub("seen AB12")
        val second = scrubber.scrub("34567 verdict=RED")
        assertEquals("seen AB12", first.text)
        assertEquals("34567 verdict=RED", second.text)
        // Note what is deliberately NOT asserted: concatenating the two strings *does* re-form
        // the number. In the file they are two lines, so no `grep` finds it and no reader
        // sees it as one value. Joining lines before scrubbing is the only way to close this,
        // and that would mean the scrubber deciding what a line is — a property of the log
        // writer, not of a redactor. Recorded rather than papered over.
    }
}
