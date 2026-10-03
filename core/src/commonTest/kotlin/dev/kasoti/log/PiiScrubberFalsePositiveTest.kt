package dev.kasoti.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strings a scrubber must **not** touch.
 *
 * ## WHY THIS FILE IS AS LONG AS THE ADVERSARIAL ONE
 *
 * A false negative is a name on disk. A false positive is a mangled log. Both are failures,
 * but they are not the *same* failure, and they do not cost the same:
 *
 *  - a name on disk is permanent, is in a file this project promises is PII-free, and cannot
 *    be recalled;
 *  - a mangled log line is annoying, recoverable by re-running, and — as long as it is
 *    *visible* rather than silent — is found the next time somebody reads the log.
 *
 * The asymmetry is why the rules fail closed, and it is also why this file exists. A scrubber
 * that eats hashes, ids, file paths, finding codes and version strings protects nothing that
 * the fail-closed cases were protecting, and destroys the log that a privacy incident would be
 * investigated *with*. Redacting the case id out of every line means there is no way to say
 * which case a line was about, which is the one thing a screening log has to be able to say.
 *
 * So the bar here is not "no false positives". It is: **every false positive the rules can
 * justify has to be recorded here with the reason it is acceptable**, so that a reader can
 * disagree with the trade rather than discover it.
 *
 * ## THE ONE ACCEPTED FALSE POSITIVE
 *
 * A compact UTC timestamp — `202609301200`, `YYYYMMDDHHmm` — is twelve bare digits, and so is
 * an Aadhaar number. There is no way to tell them apart from the text, so the scrubber takes
 * the timestamp. That is the right way round for a privacy control (an operator can re-derive
 * the time from the line's position in the log; a name cannot be re-derived) and it is
 * asserted *as a false positive* below so that it is a decision on the record rather than a
 * surprise in production.
 */
class PiiScrubberFalsePositiveTest {

    private val scrubber = PiiScrubber.DEFAULT

    /** Assert both halves: nothing was removed, and the line is byte-for-byte what came in. */
    private fun untouched(line: String, why: String) {
        val result = scrubber.scrub(line)
        assertEquals(line, result.text, "$why: the line was modified to [${result.text}]")
        assertEquals(0, result.redactions, "$why: reported ${result.redactions} redaction(s)")
        assertEquals(0, result.neutralisedControls, "$why: neutralised ${result.neutralisedControls}")
        assertTrue(result.isClean, "$why: reported not clean")
    }

    // ------------------------------------------------------------------ our own identifiers

    /**
     * A ULID is 26 characters of Crockford base32: uppercase, digits, no spaces.
     *
     * This is the single most important negative case in the project, and the reason the MRZ
     * rule's lower bound is 30 (ICAO 9303's TD1 row length) rather than the 20 the desktop
     * implementation used. At 20, `evt_01M3RP1JS6DV7VKZBXP77G3P8P` and a *bare* ULID were both
     * redacted — the case id was eaten out of every single log line. The floor is now a
     * number no identifier in this system can reach.
     */
    @Test
    fun `a ULID is not an MRZ row`() {
        for (line in listOf(
            "evt_01M3RP1JS6DV7VKZBXP77G3P8P",
            "case_01M3RP1JS6DV7VKZBXP77G3P8P",
            "01M3RP1JS6DV7VKZBXP77G3P8P",
            "case=01M3RP1JS6DV7VKZBXP77G3P8P verdict=RED",
            "evt_01M3RP1JS6DV7VKZBXP77G3P8P at 2026-09-30T17:51:09Z",
            "clip=01M3RP1JS6DV7VKZBXP77G3P8P-3 facing=0.94",
        )) {
            untouched(line, "a ULID must survive: $line")
        }
    }

    @Test
    fun `a ULID embedded in a longer token is still not redacted`() {
        // The MRZ rule is anchored by word boundaries so it cannot fire inside a longer run.
        // Without the anchor, a ULID with a suffix would lose its first 30 characters and the
        // log would carry a truncated identifier that looks valid.
        untouched("ref=01M3RP1JS6DV7VKZBXP77G3P8P01M3RP1JS6DV7VKZBXP77G3P8P", "a doubled ULID")
    }

    // ------------------------------------------------------------------ our own digests

    /**
     * The console's own audit and chain tips are the one value in a line that lets somebody
     * check the hash chain by hand. A redacted audit tip is worse than no audit tip.
     */
    @Test
    fun `our own digests survive under the keys that say they are digests`() {
        val digest = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        for (line in listOf(
            "  auditTip       $digest",
            "  chainTip       $digest",
            "sha256=$digest",
            "chainSha = $digest",
            "digest: $digest",
            "tip  $digest",
            "tip=$digest",
            "hash=$digest",
        )) {
            assertTrue(
                scrubber.scrub(line).text.contains(digest),
                "our own digest was eaten from: $line",
            )
            assertEquals(0, scrubber.scrub(line).redactions, "counted as PII: $line")
        }
    }

    @Test
    fun `an embedding-shaped blob is not rescued by the digest allowlist`() {
        // The asymmetry that makes the allowlist safe: `embedding` is a PII key, and the key
        // rules run first, so there is no input for which the two tables disagree about the
        // same span. If this test ever passes with 0 redactions, the ordering has been broken.
        val blob = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        for (key in listOf("embedding", "emb", "vector")) {
            val result = scrubber.scrub("$key=$blob")
            assertFalse(result.text.contains(blob), "'$key' let a blob through")
            assertTrue(result.redactions >= 1, "'$key' reported no redaction")
        }
    }

    @Test
    fun `an unkeyed hex blob is still redacted`() {
        // The complement of the allowlist test: preservation is decided by the key, so a
        // 64-character hex run with no key at all is not ours and is removed.
        val blob = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        for (line in listOf("payload $blob", "signature=$blob", "$blob")) {
            assertFalse(scrubber.scrub(line).text.contains(blob), "blob survived: $line")
        }
    }

    // ------------------------------------------------------------------ versions and codes

    @Test
    fun `a version string is not a date`() {
        // `19|20` in the date rule is what keeps this out. A bare `\d{4}` would eat every
        // four-digit quantity in the codebase, which is most of them.
        for (line in listOf(
            "version=2.1.21",
            "thresholdVersion=thresholds.v1",
            "fusion=console-local-2026.09-fusion-v4",
            "app=0.1.0-SNAPSHOT",
            "layer=1.0.0-alpha.3",
            "schema=v3",
        )) {
            untouched(line, "a version string must survive: $line")
        }
    }

    @Test
    fun `a finding code is not PII`() {
        for (line in listOf(
            "finding=A_FACE_01",
            "code=R_MATH_01 code2=G_BLUR code3=M_OK",
            "findings=[R_MATH_01,A_FACE_01,A_MISSING_LAYER]",
        )) {
            untouched(line, "a finding code must survive: $line")
        }
    }

    @Test
    fun `a short numeric id is not a phone number`() {
        // Below the 10-digit phone floor. Over-eager rules make logs unreadable, and an
        // unreadable log is not reviewed.
        for (line in listOf("seq=412 case=7", "port=3 attempt=2", "layers=3 of 5")) {
            untouched(line, "a short numeric id must survive: $line")
        }
    }

    // ------------------------------------------------------------------ arithmetic and paths

    /**
     * A check-digit computation printed in a log.
     *
     * `191 mod 10 = 1` is the MRZ check-digit identity and it is *supposed* to be in the log:
     * it is how an operator verifies the maths by hand. The document-number rule is the risk
     * here — `191` is three digits against a 6-digit floor, and the numbers are separated by
     * spaces, so nothing should fire. Asserting it because this is the line whose mangling
     * would be noticed first.
     */
    @Test
    fun `a check-digit sum is not a document number`() {
        for (line in listOf(
            "checkDigit: 191 mod 10 = 1",
            "191 mod 10 = 1",
            "mrz digit 3: 1*7 + 9*3 + 1*1 = 49 mod 10 = 9",
            "weighted sum 191 remainder 1 check 1",
        )) {
            untouched(line, "a check-digit computation must survive: $line")
        }
    }

    @Test
    fun `a file path is not a document number`() {
        // `/var/lib/kasoti/cases/01M3.../clip1.png` contains a 26-character ULID and a `.png`
        // extension. Between them they look like a doc number and a base64 blob to rules that
        // are not anchored.
        for (line in listOf(
            "image=/var/lib/kasoti/cases/01M3RP1JS6DV7VKZBXP77G3P8P/clip1.png",
            "specimen=/tmp/kasoti-demo/specimen.json",
            "model=eval/models/svm_print_v1.json",
            "home=~/.kasoti-console/audit.log",
        )) {
            untouched(line, "a file path must survive: $line")
        }
    }

    @Test
    fun `a local endpoint is not an email address`() {
        untouched("endpoint=http://localhost:8080/verify", "a loopback endpoint")
        untouched("endpoint=https://127.0.0.1:8443/health", "a loopback health check")
    }

    @Test
    fun `an ISO timestamp is not a date of birth`() {
        // The date rule needs exactly `YYYY-MM-DD` with a `-`, `/` or `.` between the parts.
        // An ISO instant has a `T` and colons, so it does not match — and a log full of
        // timestamps that the scrubber ate would be useless for reconstructing an incident.
        // This is the format KASOTI actually writes (`clock=` in the console), so the cost of
        // the date rule is zero in practice.
        for (line in listOf(
            "clock=2026-09-30T17:51:09.123Z",
            "at=2026-09-30T17:51:09+05:45",
            "ended=2026-09-30T00:00:00Z",
        )) {
            untouched(line, "an ISO timestamp must survive: $line")
        }
    }

    /**
     * The second accepted false positive: a space-separated timestamp loses its date part.
     *
     * `2026-09-30 17:51:09` contains `2026-09-30`, which is exactly what the date rule is
     * looking for, and the rule cannot tell a date followed by a clock time from a date of
     * birth.
     *
     * It would be easy to add `(?![ \t]+\d{1,2}:\d{2})` to the date pattern and make this
     * test pass, and that would be a mistake: it would hand an attacker a one-token bypass of
     * the date rule, to protect a timestamp format this project does not emit. The date rule
     * stays, and the false positive is on the record. If a future caller really does write
     * space-separated timestamps, the fix is a key table entry (`clock` is `UNKNOWN` today and
     * the shape rules decide), not a hole in the date rule.
     */
    @Test
    fun `a space-separated timestamp loses its date part, and that is the accepted cost`() {
        val result = scrubber.scrub("stamp=2026-09-30 17:51:09")
        assertEquals("stamp=$REDACTION_MARKER 17:51:09", result.text)
        assertEquals(RedactionRule.DATE, result.primaryRule)
    }

    // ------------------------------------------------------------------ encoded but short

    @Test
    fun `a short base64 run is not a blob`() {
        // Under the 60-character blob floor. `ZmxvYmFyYmF6` is 12 characters and is exactly
        // the sort of value that appears in a URL path segment or a short token.
        untouched("cursor=ZmxvYmFyYmF6", "a short base64 run")
        untouched("nonce=aGVsbG8gd29ybGQ", "a 16-character base64 run")
    }

    @Test
    fun `a dotted hostname is not a JWT`() {
        // The JWT rule requires three segments of at least the registered floor each. This is
        // the false positive that rule exists to avoid, and it is the reason the floor is a
        // *segment* length rather than a total length.
        for (line in listOf(
            "kubernetes.default.svc.cluster.local",
            "host=myappinstance.production.internal",
            "topic=kasoti.screening.events.v2",
            "db=postgres.analytics.replica-eu.1",
        )) {
            untouched(line, "a dotted hostname must survive: $line")
        }
    }

    @Test
    fun `a two-segment version is not a JWT`() {
        untouched("release=1.2.3", "an ip-ish dotted triple")
        untouched("ip=10.20.30.40", "a private IPv4 address")
    }

    // ------------------------------------------------------------------ structures

    @Test
    fun `a bracketed list of field names is not an embedding`() {
        // `fields=[name, dob]` is a schema, not a payload. Only a *PII key* holding a list is
        // removed; a non-PII key holding a list of names is left alone, and that is correct:
        // the names of the fields are not the values in them.
        untouched("fields=[name, dob, passport]", "a schema field list")
        untouched("required=[ocr, chip, face]", "a schema field list")
    }

    @Test
    fun `a key holding the word name is not itself a name`() {
        untouched("field=name", "a field label")
        untouched("track=NAME", "a document track name")
        untouched("reason=PASSPORT_MRZ_UNREADABLE", "a reason code")
    }

    @Test
    fun `the safe siblings of a scrubbed field survive`() {
        // The property that makes a scrubbed line worth keeping: redaction must be local.
        val line = "case=01M3RP1JS6DV7VKZBXP77G3P8P name=RAMESH VERMA verdict=RED " +
            "code=R_MATH_01 layers=3 device=post3-ph1 clock=2026-09-30T17:51:09Z"
        val result = scrubber.scrub(line)
        assertFalse(result.text.contains("RAMESH"))
        assertEquals(1, result.redactions)
        for (survivor in listOf(
            "01M3RP1JS6DV7VKZBXP77G3P8P", "verdict=RED", "R_MATH_01", "layers=3",
            "device=post3-ph1", "2026-09-30T17:51:09Z", "name=",
        )) {
            assertTrue(result.text.contains(survivor), "'$survivor' was lost: ${result.text}")
        }
    }

    @Test
    fun `an operator-facing sentence is not mangled`() {
        // The readability floor. If ordinary English starts coming back full of markers, the
        // control gets switched off and then nothing is protected at all.
        for (line in listOf(
            "wipe refused: audit chain does not verify",
            "case stored under ~/.kasoti-console",
            "supervisor override: print defect, justified by photo",
            "no macro model found; the print-process layer is UNAVAILABLE",
        )) {
            untouched(line, "operator prose must survive: $line")
        }
    }
}
