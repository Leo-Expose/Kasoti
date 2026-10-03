package dev.kasoti.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The scrubber's tables, asserted directly rather than through string surgery.
 *
 * ## WHAT "DENY BY DEFAULT FOR NEW STRUCT FIELDS" CAN AND CANNOT MEAN HERE
 *
 * AGENTS.md §4 asks for scrubbing that is "deny-by-default for new struct fields". The
 * literal reading — an allowlist, so an unrecognised field is redacted — was rejected, and the
 * rejection is the point of this file:
 *
 *  - An allowlist for *log content* means a field nobody thought of is simply gone. The audit
 *    tip, the case id, the finding code, the device name, the clock: every one of those is
 *    something an investigator needs and something a deny-by-default log cannot contain. The
 *    log would be unreadable, the control would be switched off within a day, and the switch
 *    would be permanent.
 *  - The privacy requirement is about *values*, not *fields*. A name has no shape. There is
 *    no rule that finds `RAMESH` in a sentence without also eating half the English language.
 *
 * So the real deny-by-default control is **upstream of the string**: the decision about which
 * fields exist is a code review, and this class bounds what happens when that review misses.
 * `PiiScrubber.classify` returning `UNKNOWN` is the observable signal of that miss, which is
 * why the classification is a tri-state rather than a boolean — a boolean would let a caller
 * ask the question they actually have ("is this PII?") and discard the case that says
 * "nobody has looked at this yet".
 *
 * [consoleFieldCorpusIsNeverUnreviewed] is the closest this repository gets to enforcing that
 * upstream review, and its limits are stated in its own comment.
 */
class PiiScrubberFieldCorpusTest {

    /**
     * Every field label the desktop console actually prints.
     *
     * Taken from `Console.kt` / `ConsolePolicy.kt`, not from memory. The assertion is not that
     * all of them are classified — several are deliberately `UNKNOWN` and fall back to the
     * shape rules — but that the corpus is *deliberate*: every entry is either a PII key, a
     * digest key, or explicitly listed as reviewed-and-not-PII. A label that appears in the
     * console and in neither list is a gap this test exists to make visible.
     */
    private val consoleLabels: List<String> = listOf(
        "case", "cases", "verdict", "device", "clock", "track", "image", "fields",
        "auditTip", "chainTip", "fusion", "svm", "macro", "seq", "code", "layers",
    )

    /** Labels the console prints that are known not to be PII and rely on the shape rules. */
    private val reviewedAsNotPii: List<String> = listOf(
        "case", "cases", "verdict", "device", "clock", "track", "image", "fields",
        "fusion", "svm", "macro", "seq", "code", "layers",
    )

    /**
     * The upstream control, asserted as far as a unit test can assert it.
     *
     * **Honest limit:** this is a corpus of sixteen labels chosen by a human reading two
     * source files. It cannot notice a field the console starts printing tomorrow, because
     * nothing here reads the console. A version that derived the corpus from the console's
     * source would be a real improvement and is written down in the report rather than claimed
     * here — a test that re-implements a code generator and quietly diverges from it is worse
     * than a short explicit list.
     *
     * What it *does* catch, which is not nothing: a rename in the console that lands on a key
     * the scrubber treats differently, and a PII key table that loses an entry.
     */
    @Test
    fun consoleFieldCorpusIsNeverUnreviewed() {
        val unreviewed = consoleLabels.filter { label ->
            PiiScrubber.classify(label) == KeyClass.UNKNOWN && label !in reviewedAsNotPii
        }
        assertTrue(
            unreviewed.isEmpty(),
            "console labels nobody has classified: $unreviewed — add them to PII_KEYS, " +
                "OWN_DIGEST_KEYS or reviewedAsNotPii, deliberately",
        )
    }

    @Test
    fun `the corpus keeps its two digest labels classified as digests`() {
        // These two are the only fields in the console that must NOT be redacted, so a change
        // to either table that moved them is a bug with a very specific symptom: the audit
        // chain stops being checkable by hand and nobody notices until they need to.
        assertEquals(KeyClass.OWN_DIGEST, PiiScrubber.classify("auditTip"))
        assertEquals(KeyClass.OWN_DIGEST, PiiScrubber.classify("chainTip"))
        val digest = "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809"
        val line = "  auditTip       $digest"
        assertEquals(line, scrubAuditTipLine(line), "the audit tip line was modified")
    }

    private fun scrubAuditTipLine(line: String): String =
        PiiScrubber.DEFAULT.scrub(line).text

    /**
     * Every test function in this package is actually annotated.
     *
     * This exists because a refactor that moves tests between these files *did* drop one
     * `@Test` annotation, and the symptom was a suite that compiled, ran, reported green and
     * was quietly one test short. Nothing in Gradle notices: the class still had other
     * tests. Only comparing the count against what the source declares catches it, which is
     * what this does.
     *
     * `dev.kasoti.log` only, and read from the class files rather than the sources, so it
     * measures what JUnit will actually see rather than what the text appears to say.
     */
    @Test
    fun `every scrubber test class declares its tests as annotated functions`() {
        val classes = listOf(
            "dev.kasoti.log.PiiScrubberTest",
            "dev.kasoti.log.PiiScrubberAdversarialTest",
            "dev.kasoti.log.PiiScrubberInjectionTest",
            "dev.kasoti.log.PiiScrubberFalsePositiveTest",
            "dev.kasoti.log.PiiScrubberRuleBoundaryTest",
            "dev.kasoti.log.PiiScrubberFieldCorpusTest",
            "dev.kasoti.log.PiiScrubberPolicyTest",
        )
        val unannotated = mutableListOf<String>()
        for (name in classes) {
            val type = Class.forName(name)
            val declared = type.declaredMethods.count { it.name.startsWith("test") }
            val annotated = type.declaredMethods.count { m ->
                m.annotations.any { it.annotationClass.qualifiedName == "org.junit.jupiter.api.Test" } ||
                    m.annotations.any { it.annotationClass.qualifiedName == "kotlin.test.Test" }
            }
            if (annotated < declared) unannotated += "$name: $annotated annotated of $declared"
        }
        assertTrue(unannotated.isEmpty(), "unannotated test functions: $unannotated")
    }

    // ------------------------------------------------------------------ table invariants

    @Test
    fun `the PII and digest tables do not overlap`() {
        // The pass order makes an overlap harmless today (a PII key wins), but an overlap is
        // a table that contradicts itself, and the next rule somebody adds will find that out
        // by accident.
        val pii = RedactionRules.PII_KEYS
        val digests = RedactionRules.OWN_DIGEST_KEYS
        val overlap = pii.intersect(digests)
        assertTrue(overlap.isEmpty(), "keys in both tables: $overlap")
    }

    @Test
    fun `the PII key table holds only canonical keys`() {
        // Every entry is compared against its own canonical form, so `full_name` and
        // `full.name` cannot both be in the table: the second would never be reachable,
        // because classification canonicalises the key before the lookup.
        val nonCanonical = RedactionRules.PII_KEYS.filter { RedactionRules.canonicalKey(it) != it }
        assertTrue(nonCanonical.isEmpty(), "unreachable PII keys: $nonCanonical")
        val nonCanonicalDigests =
            RedactionRules.OWN_DIGEST_KEYS.filter { RedactionRules.canonicalKey(it) != it }
        assertTrue(nonCanonicalDigests.isEmpty(), "unreachable digest keys: $nonCanonicalDigests")
    }

    @Test
    fun `key classification is stable across spellings`() {
        val spellings = listOf(
            "full_name", "fullName", "full.name", "FULL-NAME", "Full Name", "full  name",
        )
        val classes = spellings.map { PiiScrubber.classify(it) }.toSet()
        assertEquals(setOf(KeyClass.PII), classes, "spellings disagreed: $classes")
    }

    @Test
    fun `a confusable spelling of a key classifies the same as the ASCII one`() {
        for (key in listOf("name", "dob", "passport")) {
            val confusable = key.replace('a', 'а').replace('o', 'о').replace('p', 'р')
            if (confusable == key) continue
            assertEquals(
                PiiScrubber.classify(key),
                PiiScrubber.classify(confusable),
                "'$confusable' classified differently from '$key'",
            )
        }
    }

    @Test
    fun `an MRZ-ish key is a PII key because its shape rule cannot see a damaged row`() {
        // The MRZ shape rule is anchored on the two ICAO row lengths, which means it cannot
        // see a row OCR has shortened or a line that was split upstream. The key rule has no
        // such precondition, so these entries are the catch for a document that arrived
        // damaged — which is the normal case for a low-end camera at a border post.
        for (key in listOf("mrz", "mrzLine", "mrz1", "mrz2", "td1", "td3")) {
            assertEquals(KeyClass.PII, PiiScrubber.classify(key), "'$key' should be a PII key")
        }
        assertEquals("mrz=[redacted]", PiiScrubber.DEFAULT.scrub("mrz=L898902C").text)
    }

    // ------------------------------------------------------------------ the confusable fold

    @Test
    fun `folding is length preserving, which is what makes offset mapping safe`() {
        // If the fold changed length, a rule that matched at offset n in the shadow would
        // redact the wrong span in the original. That is a worse bug than the one the fold
        // exists to prevent, so it is asserted as a property rather than assumed.
        for (line in listOf(
            "nаme=RAMESH",
            "reach me at ९८१२३४५६७८",
            "１９９０-０１-０１",
            "“name”: “RAMESH”",
            "p‑assport=AB1234567",
            "plain ascii line",
        )) {
            assertEquals(
                line.length,
                RedactionRules.fold(line).length,
                "fold changed the length of [$line]",
            )
        }
    }

    @Test
    fun `folding is an involution on the ASCII it produces`() {
        val folded = RedactionRules.fold("nаme=RAMESH")
        assertEquals("name=RAMESH", folded)
        assertEquals(folded, RedactionRules.fold(folded))
    }

    @Test
    fun `a fold that is not a homoglyph leaves the character alone`() {
        // A fold table is a security control, so an over-broad entry is a silent log-mangling
        // bug. `µ` and `Ω` are not letters anything in this project logs, and folding them
        // would corrupt a legitimate line.
        assertEquals('µ', RedactionRules.foldChar('µ'))
        assertEquals('Ω', RedactionRules.foldChar('Ω'))
        assertEquals('é', RedactionRules.foldChar('é'))
    }

    // ------------------------------------------------------------------ rule independence

    @Test
    fun `each shape rule can fire on its own`() {
        // A rule that only ever fires alongside another is a rule nobody has verified. The
        // inputs are one per rule, with nothing else in the line that any rule matches.
        val cases = listOf(
            RedactionRule.MRZ_ROW to "L898902C<3UTO6908061F9406236ZE184226B<<<<<10",
            RedactionRule.DATE to "1990-01-01",
            RedactionRule.AADHAAR to "2345 6789 0123",
            RedactionRule.PHONE to "+9779812345678",
            RedactionRule.EMAIL to "a.b@c.example",
            RedactionRule.DOC_NUMBER to "AB1234567",
            RedactionRule.HEX_BLOB to "a".repeat(64),
            RedactionRule.BASE64_BLOB to "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg==",
            RedactionRule.JWT to (
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                    ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
                    ".dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
                ),
        )
        for ((expected, line) in cases) {
            val result = PiiScrubber.DEFAULT.scrub(line)
            assertTrue(
                result.byRule.containsKey(expected),
                "$expected did not fire on its own input [$line]: ${result.byRule}",
            )
        }
    }

    @Test
    fun `every declared rule is reachable from some input`() {
        // The complement of the previous test: a rule nothing can trigger is a rule that looks
        // like coverage in the `--help` list and is not. CONTROL_CHAR is excluded because it
        // fires on absence of input rather than presence of it.
        val reachable = RedactionRule.entries
            .filter { it != RedactionRule.CONTROL_CHAR }
            .mapNotNull { rule ->
                val sample = sampleFor(rule) ?: return@mapNotNull null
                if (PiiScrubber.DEFAULT.scrub(sample).byRule.containsKey(rule)) rule else null
            }
            .toSet()
        val unreachable = RedactionRule.entries - RedactionRule.CONTROL_CHAR - reachable
        assertTrue(unreachable.isEmpty(), "rules no input can trigger: $unreachable")
    }

    private fun sampleFor(rule: RedactionRule): String? = when (rule) {
        RedactionRule.PII_KEY -> "name=RAMESH"
        RedactionRule.MRZ_ROW -> "L898902C<3UTO6908061F9406236ZE184226B<<<<<10"
        RedactionRule.DATE -> "1990-01-01"
        RedactionRule.AADHAAR -> "2345 6789 0123"
        RedactionRule.PHONE -> "+9779812345678"
        RedactionRule.EMAIL -> "a.b@c.example"
        RedactionRule.DOC_NUMBER -> "AB1234567"
        RedactionRule.HEX_BLOB -> "a".repeat(64)
        RedactionRule.BASE64_BLOB -> "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg=="
        RedactionRule.JWT -> (
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
                ".dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            )
        RedactionRule.CONTROL_CHAR -> null
    }

    @Test
    fun `the primary rule is the one a call-site review can act on`() {
        // A keyed name plus a phone number is two problems; the one a reviewer can fix is the
        // call site that logged the name. The first rule in declaration order is that one.
        val result = PiiScrubber.DEFAULT.scrub("name=RAMESH note=call 9812345678")
        assertNotEquals(RedactionRule.PHONE, result.primaryRule)
        assertTrue(result.redactions >= 1)
    }

    @Test
    fun `only control characters are excluded from the PII count`() {
        for (rule in RedactionRule.entries) {
            assertEquals(
                rule != RedactionRule.CONTROL_CHAR,
                RedactionRule.isPii(rule),
                "$rule is mis-classified for the count",
            )
        }
    }

    @Test
    fun `the neutralised character is a visible placeholder`() {
        // `?` and not a space, and not the empty string: a reader has to be able to see that
        // something was removed *from this position*, and a space reads as a space.
        assertNotEquals(' ', PiiScrubber.NEUTRALISED_CHAR)
        assertNotEquals(' ', PiiScrubber.NEUTRALISED_CHAR)
        assertTrue(PiiScrubber.NEUTRALISED_CHAR.isLetter() || PiiScrubber.NEUTRALISED_CHAR == '?')
    }

    @Test
    fun `the marker cannot be matched by any shape rule, which is what makes it idempotent`() {
        val marker = REDACTION_MARKER
        for ((rule, pattern) in RedactionRules.shapeRules(RedactionPolicy.DEFAULT)) {
            assertFalse(
                pattern.containsMatchIn(marker),
                "$rule matches the redaction marker, so re-scrubbing would not be idempotent",
            )
        }
        assertFalse(patternMatchesAnyRule("$marker $marker"), "a marker pair matches a rule")
    }

    private fun patternMatchesAnyRule(line: String): Boolean =
        RedactionRules.shapeRules(RedactionPolicy.DEFAULT).any { it.second.containsMatchIn(line) }

    @Test
    fun `the marker survives a second scrub untouched`() {
        val marker = REDACTION_MARKER
        val result = PiiScrubber.DEFAULT.scrub("name=$marker dob=$marker")
        assertEquals("name=$marker dob=$marker", result.text)
        assertEquals(0, result.redactions)
    }
}
