package dev.kasoti.log

/**
 * The PII log scrubber: the single definition, in `:core`, of what KASOTI is not allowed to
 * write to a log, a cache or a crash report (AGENTS.md §4, §5; THREAT_MODEL.md §3).
 *
 * Pure, synchronous, deterministic and dependency-free. No clock, no randomness, no
 * `expect`/`actual`, no platform types — so a verdict path that scrubs a string cannot
 * acquire a capability it did not have, and the same input produces the same output on every
 * device and in every test. That is why this lives in `:core` and not in a UI or platform
 * module: `:core` is the layer a reviewer can prove has no network and no side effects.
 *
 * ## WHAT THIS MUST NOT BE
 *
 * **This is a last line of defence, and treating it as a first line is the one design failure
 * that would make the product worse than having no scrubber at all.**
 *
 * The failure has a specific shape. A developer adds a log line carrying a name. A scrubber
 * exists, so the line prints `[redacted]` and the feature demo works. The name reached the
 * string. It is now in the process heap, in whatever buffer the log writer used, in a core
 * dump if the process is killed, in a debugger's memory view, and — on a device that is
 * later imaged — on disk. Redaction at the sink does not undo any of that. What it *does*
 * do is remove the pressure to fix the bug, because from the console it looks handled. The
 * scrubber has made logging PII **cheaper**: one fewer thing to think about at the call
 * site, in exchange for a marker that says the call site was wrong.
 *
 * So the rule this class asks of its callers is:
 *
 * > **A field that reaches `scrub` and comes back `[redacted]` is a bug at the call site. The
 * > scrubber bounds the blast radius; it does not discharge the debt.**
 *
 * The evidence card in the UI, the `[redacted]` marker and [RedactionResult.redactions] exist
 * to make that bug *loud*. A silent pass-through is a privacy incident with no signal; a loud
 * `[redacted]` is a privacy incident an operator can find in a log review. Bounded, visible,
 * countable — that is the entire design brief. Anything that made the marker quieter would
 * make this class worse than useless.
 *
 * ## HOW IT WORKS: ONE SHADOW, THREE PASSES, ORIGINAL BYTES PRESERVED
 *
 * Every rule runs against a **shadow** of the line rather than the line itself, and every
 * redaction is applied to the original bytes at the offsets the shadow reported. The shadow
 * has two transformations:
 *
 *  1. **Neutralised characters are deleted.** Not replaced — deleted, with an index map back
 *     to the original. This is the fix for the bypass that made the previous version of this
 *     file look thorough and miss the obvious: `n<U+200B>ame=RAMESH` reached the key rules as
 *     `n?ame=RAMESH`, which classifies as an unknown field, so the value went to disk. A
 *     shadow that deletes what cannot be seen makes the key rules see the word a human sees.
 *  2. **Confusables are folded to their ASCII twins**, length-preservingly — see
 *     [RedactionRules.fold]. This is the same fix for the key rules, which previously ran on
 *     the *unfolded* line: `nаme=RAMESH` with a Cyrillic `а` did not match `[A-Za-z]` and did
 *     not match `name` either, so the value survived in the clear while a table in
 *     [RedactionRules] sat there documenting that this exact bypass was closed.
 *
 * What is deliberately **not** done is rewriting the stored line to its folded form. A log
 * that silently normalised `nаme` to `name` would erase the only evidence that somebody had
 * put a homoglyph in a field name. So the shadow is a matching device and the original is
 * what gets written: `name=[redacted]` with a `?` where the zero-width character was, which
 * is a line an operator can look at and a reviewer can act on.
 *
 * ## WHAT IT REDACTS, AND WHAT IT DELIBERATELY LEAVES ALONE
 *
 * Redacts: PII that arrived *under a key* whose name says what it is (a name is not a
 * recognisable shape, so the key is the only signal there is), and PII with a recognisable
 * *shape* that arrived without one — an MRZ row, a date, a long digit run, an email, a
 * document number, a hex or base64 blob, a compact JWT.
 *
 * Leaves alone, on purpose:
 *
 *  - **Our own identifiers.** A ULID is 26 characters of Crockford base32 — uppercase,
 *    alphanumeric, space-free. It is the single easiest thing in this system to mistake for
 *    a document row, and redacting the case id out of every log line would cost the log its
 *    entire purpose. See [RedactionRules]' MRZ rule for the number that was moved to keep
 *    them readable.
 *  - **Our own digests**, but only under a key that says it is one. A redacted audit tip is
 *    worse than no audit tip: it is the one value in the line that lets somebody check the
 *    chain by hand. The allowlist is seven keys long on purpose, because broadening it to
 *    "anything hex-shaped" would also un-redact an embedding dumped as hex.
 *  - **The key itself.** A redacted line still says *which field* was redacted. Knowing that
 *    `dob` was present in this line is operationally necessary and is not PII.
 *  - **Anything that merely resembles a finding code, a threshold version or a fusion
 *    version.** See the negative-case tests: a scrubber that eats hashes and ids destroys
 *    more value than it protects.
 *
 * ## WHAT IT CANNOT DO
 *
 * A name in free prose. "Dev" is three letters. There is no shape, no key and no registry
 * that separates it from a word, and any heuristic that tries will eventually eat legitimate
 * log text — which is how a control gets disabled rather than obeyed. A surname in a note
 * field reaches the log. The chokepoint upstream of `scrub` is the control for that, and it
 * is a code review, not a regex.
 *
 * It also cannot redact a value it is not *given*. It sees one string at a time, so a name
 * split across two `println` calls is two words to it. That is a property of the interface,
 * not a bug that more rules would fix, and it is why the call sites are the control.
 *
 * @param policy the scrubber's operating point, read from `ThresholdRegistry` (AGENTS.md §2).
 */
class PiiScrubber(private val policy: RedactionPolicy = RedactionPolicy.DEFAULT) {

    /**
     * The shape rules, compiled once per scrubber.
     *
     * Built in the constructor because the pattern strings interpolate the policy's floors.
     * Compiling them per call would be the obvious mistake and it is not free: a
     * `Regex.compile` is tens of microseconds and a console line goes through here on every
     * field of every verdict.
     */
    private val shapeRules: List<Pair<RedactionRule, Regex>> = RedactionShapes.shapeRules(policy)

    /**
     * Redact [text], returning both the line to store and what was removed.
     *
     * **Total.** It cannot throw for any `String`: the patterns are fixed, the assembly is a
     * per-character walk that falls through to the input character, and there is no
     * recursion, no indexing that can go out of bounds and no numeric conversion of caller
     * data. A log writer that throws while handling an error is a log writer that loses the
     * error.
     *
     * @return a [RedactionResult]; never null, never partial.
     */
    fun scrub(text: String): RedactionResult =
        RedactionPipeline.scrub(text, shapeRules)

    companion object {
        /** What a neutralised character becomes. See [assemble]. */
        const val NEUTRALISED_CHAR: Char = '?'

        /**
         * One character, log-safe.
         *
         * A companion function rather than an instance method because it is pure and because
         * the public [neutraliseControlCharacters] needs it too. It cannot throw: the question
         * is a range comparison, and every branch returns a character.
         */
        private fun neutraliseOne(ch: Char): Char =
            if (RedactionFold.isNeutralised(ch)) NEUTRALISED_CHAR else ch

        /** The shared scrubber at the shipped operating point. */
        val DEFAULT: PiiScrubber = PiiScrubber()

        /**
         * True when a key label, however spelled, denotes a PII field.
         *
         * The two-argument-free form of the tri-state [classify], kept because "is this key
         * PII" is the question almost every caller actually wants and making them discard a
         * tri-state to ask a yes/no question is how the middle case gets ignored.
         */
        fun isPiiKey(key: String): Boolean = RedactionRules.classify(key) == KeyClass.PII

        /** How a scrubber reads a key label: [KeyClass.PII], [KeyClass.OWN_DIGEST] or unknown. */
        fun classify(key: String): KeyClass = RedactionRules.classify(key)

        /**
         * Replace every control or invisible character with [NEUTRALISED_CHAR], keeping tab.
         *
         * Exposed because it is a distinct, separately testable transformation: a caller that
         * wants a log-safe rendering of a string it is *not* about to scrub — an error
         * message shown in a terminal, a case id echoed back to an operator — needs this and
         * not the full scrubber.
         *
         * Note the deliberate difference from what [scrub] does internally: here the
         * characters are *replaced*, not deleted, so the output keeps the caller's character
         * count and the mapping back to the original stays obvious. Deleting is the right
         * choice for matching and the wrong one for display.
         */
        fun neutraliseControlCharacters(text: String): String {
            val out = StringBuilder(text.length)
            for (ch in text) out.append(neutraliseOne(ch))
            return out.toString()
        }

        /**
         * The rule names, for a `--help` line and for the test that checks coverage.
         *
         * The spellings are inherited verbatim from the desktop scrubber's `patternNames` and
         * are a *wire vocabulary*: they are printed, grepped and asserted on, so they are not
         * renamed to suit a new enum constant. The list grew — it is every [RedactionRule]
         * plus the digest-preservation rule — but no existing name changed.
         */
        val patternNames: List<String> =
            RedactionRule.entries.map { it.wireName } + OWN_DIGEST_RULE_NAME

        private const val OWN_DIGEST_RULE_NAME = "own-digests"
    }
}
