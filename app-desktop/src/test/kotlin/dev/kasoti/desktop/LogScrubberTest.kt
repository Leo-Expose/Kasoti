package dev.kasoti.desktop

import dev.kasoti.log.REDACTION_MARKER
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PII log scrubber (invariant I5, AGENTS.md §5).
 *
 * **This file is unchanged since the rules moved to `:core`, and that is the point of it.**
 * The rules now live in `dev.kasoti.log.PiiScrubber` and [LogScrubber] delegates to them, so
 * every assertion below is running against the `:core` implementation through the console's
 * chokepoint. It is the faithful-move proof: 300 lines written against the old rules, green
 * against the new ones, with no assertion relaxed to get there.
 *
 * (The only edit this file has taken since the move is where it reads the marker from —
 * `Redaction.MARKER` became `REDACTION_MARKER`, because there is now one definition of the
 * token rather than two. The object it came from is gone.)
 *
 * The second half of this file is the part that matters. It is full of strings that a
 * real document — or an attacker who has edited one — can produce. A scrubber tested only
 * on `"name=RAMESH"` proves nothing: the failure modes that reach a production log are the
 * ones nobody thought of, and most of them are about *breaking the log* rather than about
 * matching a name.
 *
 * The cases this file does **not** cover — confusable keys, Unicode digits, invisible
 * characters, and above all the things a scrubber must *not* mangle — are in
 * `:core`'s own suite, in `dev.kasoti.log.PiiScrubberAdversarialTest` and
 * `PiiScrubberFalsePositiveTest`.
 */
class LogScrubberTest {

    private val marker = REDACTION_MARKER

    // ------------------------------------------------------------------ keyed values

    @Test
    fun `a keyed name is redacted`() {
        assertEquals("name=$marker", LogScrubber.scrub("name=RAMESH"))
    }

    @Test
    fun `key spelling and separator do not matter`() {
        for (line in listOf("DOB: 1990-01-01", "dob=1990-01-01", "Date_Of_Birth = 1990-01-01", "name : RAMESH")) {
            val scrubbed = LogScrubber.scrub(line)
            assertFalse(scrubbed.contains("1990-01-01"), "DOB survived in '$line'")
            assertTrue(scrubbed.contains(marker))
        }
    }

    @Test
    fun `a quoted value is redacted including the quotes`() {
        val scrubbed = LogScrubber.scrub("""{"name": "RAMESH VERMA", "track": "PASSPORT"}""")
        assertFalse(scrubbed.contains("RAMESH"))
        assertTrue(scrubbed.contains("\"track\": \"PASSPORT\""))
    }

    @Test
    fun `an embedding is never logged`() {
        val scrubbed = LogScrubber.scrub("embedding=[0.12, -0.44, 0.98, 0.31]")
        assertFalse(scrubbed.contains("0.98"))
    }

    @Test
    fun `non-PII keys survive so the log stays useful`() {
        val line = "verdict=RED code=R_MATH_01 thresholds=v1 device=post3-ph1"
        assertEquals(line, LogScrubber.scrub(line))
    }

    @Test
    fun `a finding code and a threshold version are not mistaken for PII`() {
        val line = "finding=A_FACE_01 thresholdVersion=thresholds.v1 fusion=console-local-2026.09-fusion-v4"
        assertEquals(line, LogScrubber.scrub(line))
    }

    // ------------------------------------------------------------------ shapes

    @Test
    fun `an MRZ row is redacted`() {
        val scrubbed = LogScrubber.scrub("ocr=L898902C<3UTO6908061F9406236ZE184226B<<<<<10")
        assertFalse(scrubbed.contains("L898902C"))
        assertTrue(scrubbed.contains(marker))
    }

    @Test
    fun `an Aadhaar number is redacted`() {
        val scrubbed = LogScrubber.scrub("ref 2345 6789 0123")
        assertFalse(scrubbed.contains("2345"))
    }

    @Test
    fun `a phone number is redacted`() {
        assertFalse(LogScrubber.scrub("contact +9779812345678").contains("9812345678"))
    }

    @Test
    fun `an email address is redacted`() {
        val scrubbed = LogScrubber.scrub("reach me at ram.verma+post@example.com")
        assertFalse(scrubbed.contains("example.com"))
    }

    @Test
    fun `a long hex blob is redacted`() {
        val signature = "a".repeat(128)
        val scrubbed = LogScrubber.scrub("sig=$signature")
        assertFalse(scrubbed.contains(signature))
    }

    @Test
    fun `a long base64 blob is redacted`() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo" + "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=" + "QQ=="
        val scrubbed = LogScrubber.scrub("payload $blob")
        assertFalse(scrubbed.contains("QUJDREVG"))
    }

    /** A short number is not a phone number. Over-eager rules make logs unreadable. */
    @Test
    fun `a short numeric id survives`() {
        val line = "seq=412 case=7"
        assertEquals(line, LogScrubber.scrub(line))
    }

    // ------------------------------------------------------- adversarial: hostile input

    @Test
    fun `an ANSI escape cannot repaint the operator's terminal`() {
        val hostile = "verdict=GREEN\u001B[31m\u001B]8;;http://evil.example\u0007"
        val scrubbed = LogScrubber.scrub(hostile)
        assertFalse(scrubbed.contains('\u001B'), "ESC survived scrubbing")
        assertFalse(scrubbed.contains('\u0007'), "BEL survived scrubbing")
    }

    @Test
    fun `a newline cannot forge a second log line`() {
        val scrubbed = LogScrubber.scrub("note=ok\n2026-01-01T00:00:00Z INFO wipe completed")
        assertFalse(scrubbed.contains('\n'))
        assertEquals(1, scrubbed.lines().size)
    }

    @Test
    fun `a carriage return cannot overwrite a line already written`() {
        assertFalse(LogScrubber.scrub("a\rb").contains('\r'))
    }

    @Test
    fun `a null byte does not truncate the line`() {
        val scrubbed = LogScrubber.scrub("verdict=RED\u0000ignored")
        assertTrue(scrubbed.startsWith("verdict=RED"))
        assertTrue(scrubbed.endsWith("ignored"))
    }

    /**
     * A bidi override can make a value render as something other than its bytes.
     *
     * The operator sees one thing; the log file contains another. Neutralising it means
     * what is stored is what is read.
     */
    @Test
    fun `a bidi override is neutralised`() {
        val hostile = "name=RA\u202EMESH VERMA"
        val scrubbed = LogScrubber.scrub(hostile)
        assertFalse(scrubbed.contains('\u202E'))
    }

    /**
     * The scrubber is a redactor, not a template engine.
     *
     * A template expression sitting in a PII *value* disappears with the value. One in a
     * non-PII field is left exactly as written — visible to an investigator, and unexpanded,
     * because the scrubber never interprets what it is given. Asserting both halves is the
     * point: a scrubber that silently evaluated its input would be a far worse bug than one
     * that passes a string through.
     */
    @Test
    fun `a template expression is redacted with its value and never expanded`() {
        val hostile = "name=\${System.getenv('SECRET')}\${jdbc:mysql://user:pw@host/db}"
        val scrubbed = LogScrubber.scrub(hostile)
        assertFalse(scrubbed.contains("System.getenv"))
        assertFalse(scrubbed.contains("jdbc:mysql"))
        assertTrue(scrubbed.contains(marker))
    }

    @Test
    fun `a template expression in a non-PII field is left visible and unexpanded`() {
        val line = "error=\${jdbc:mysql://host/db} connection refused"
        assertEquals(line, LogScrubber.scrub(line))
    }

    /**
     * An attacker who controls a value must not be able to close the quote and open a field
     * of their own. The injected `dob` is a PII key like any other, so it is redacted too —
     * which is what stops `name="x", dob=1990-01-01` from smuggling a date of birth past
     * the scrubber inside a value that looked already-redacted.
     */
    @Test
    fun `a quote-injecting value cannot smuggle a second PII field past the scrubber`() {
        val hostile = """name="x", dob=1990-01-01, phone=9812345678"""
        val scrubbed = LogScrubber.scrub(hostile)
        assertFalse(scrubbed.contains("1990-01-01"), "the injected DOB survived")
        assertFalse(scrubbed.contains("9812345678"), "the injected phone survived")
        assertTrue(scrubbed.count { it == '[' } >= 3, "every injected PII field should be redacted: $scrubbed")
    }

    /** A name containing a comma would otherwise break the manifest's column alignment. */
    @Test
    fun `a CSV-injecting value is redacted`() {
        val scrubbed = LogScrubber.scrub("name=SHARMA,RAMESH,OFFSET,sun")
        assertFalse(scrubbed.contains("SHARMA"))
    }

    @Test
    fun `a very long value is fully removed rather than truncated`() {
        val hostile = "name=" + "A".repeat(5000)
        val scrubbed = LogScrubber.scrub(hostile)
        assertFalse(scrubbed.contains("AAAA"))
        assertTrue(scrubbed.length < 100)
    }

    @Test
    fun `an empty line is unchanged`() {
        assertEquals("", LogScrubber.scrub(""))
    }

    @Test
    fun `scrubbing is idempotent`() {
        val hostile = "name=RAMESH dob=1990-01-01 verdict=RED"
        val once = LogScrubber.scrub(hostile)
        assertEquals(once, LogScrubber.scrub(once))
    }

    // ------------------------------------------------------------------ own digests

    /**
     * The console's own hashes must survive.
     *
     * A redacted audit tip is worse than no audit tip: it is the one value in the line that
     * lets somebody check the chain by hand. These are hashes the process just computed,
     * not attacker-controlled content, so the shape rules — which exist for the latter —
     * must not eat them.
     */
    @Test
    fun `the console's own audit tip survives as an aligned field`() {
        val digest = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        val scrubbed = LogScrubber.scrub("  auditTip       $digest")
        assertContains(scrubbed, digest)
    }

    /** The field label the wipe receipt prints, protected by the same rule. */
    @Test
    fun `the chainTip before a wipe survives`() {
        val digest = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        assertContains(LogScrubber.scrub("  chainTip       $digest"), digest)
    }

    /** An embedding is hex-shaped too, and must not be rescued by the digest allowlist. */
    @Test
    fun `an embedding-shaped blob is still redacted even under a digest-like key`() {
        val embedding = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        assertFalse(LogScrubber.scrub("embedding=$embedding").contains(embedding))
        assertFalse(LogScrubber.scrub("vector = $embedding").contains(embedding))
    }

    @Test
    fun `a keyed digest survives the equals form too`() {
        val digest = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        assertContains(LogScrubber.scrub("sha256=$digest"), digest)
        assertContains(LogScrubber.scrub("chainSha = $digest"), digest)
    }

    /** An *attacker-supplied* long hex run under a non-safe key is still redacted. */
    @Test
    fun `an unkeyed hex blob is still redacted`() {
        val blob = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        assertFalse(LogScrubber.scrub("payload $blob").contains(blob))
        assertFalse(LogScrubber.scrub("signature=$blob").contains(blob))
    }

    @Test
    fun `a name may not borrow the tip key to smuggle a blob through`() {
        val blob = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        // The key rule runs first, so `name` wins over the later `tip` inside the value.
        assertFalse(LogScrubber.scrub("name=$blob").contains(blob))
    }

    // ------------------------------------------------------------------ key table

    @Test
    fun `the PII key table catches the obvious spellings`() {
        val keys = listOf(
            "name", "Name", "NAME", "full_name", "fullName", "dateOfBirth", "date_of_birth",
            "dob", "phone", "mobile", "aadhaar", "aadhar", "voterId", "pan", "email",
            "address", "passport", "documentNumber", "embedding", "emb", "nationality",
        )
        for (key in keys) {
            assertTrue(LogScrubber.isPiiKey(key), "'$key' should be treated as PII")
        }
    }

    @Test
    fun `the PII key table does not swallow operational keys`() {
        val keys = listOf("case", "verdict", "device", "thresholdVersion", "fusionRuleVersion", "code", "layers")
        for (key in keys) {
            assertFalse(LogScrubber.isPiiKey(key), "'$key' should not be treated as PII")
        }
    }

    @Test
    fun `the pattern list is non-empty and named for the help text`() {
        assertTrue(LogScrubber.patternNames.contains("mrz-line"))
        assertTrue(LogScrubber.patternNames.contains("pii-key"))
        assertTrue(LogScrubber.patternNames.contains("control-chars"))
    }
}
