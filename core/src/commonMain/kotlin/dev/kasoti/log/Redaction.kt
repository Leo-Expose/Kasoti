package dev.kasoti.log

/**
 * The stable token written in place of a removed value.
 *
 * It is a constant and NOT a localised message, on purpose. Three reasons, in order of how
 * much they matter:
 *
 *  1. **It is parsed.** `grep -c '\[redacted\]' audit.log` is how an operator finds out that
 *     a field leaked into a format the scrubber did not recognise. A token that changes with
 *     the UI locale would make that count locale-dependent, which is exactly the sort of
 *     number that looks fine until it is relied on.
 *  2. **A log outlives its reader.** The line was written under one language setting and is
 *     read under another. Re-labelling stored bytes at read time is how "what is stored is
 *     what is read" stops being true (cf. the bidi-override test in [PiiScrubber]).
 *  3. **An empty string would be worse in every way.** "" cannot be distinguished from
 *     "this field was absent", so a reader cannot tell a redaction from a null. The whole
 *     point of the marker is that it is *distinguishable* — see [RedactionResult.redactions]
 *     for the count that goes with it.
 *
 * The brackets matter as much as the word: they cannot begin or end a token that any of the
 * shape rules in [PiiScrubber] match, so the marker is idempotent — scrubbing an already
 * scrubbed line cannot re-redact it, and cannot inflate the count either.
 */
const val REDACTION_MARKER: String = "[redacted]"

/**
 * Why a span was removed.
 *
 * Carried per-rule rather than as a single total because the *response* to each is
 * different: a `PII_KEY` hit means a call site is logging a field it should not be (fix the
 * call site), while a `DOC_NUMBER` hit with no key means the value arrived inside free text
 * from a document (the scrubber did its job, and the count is the signal). Naming them lets
 * a reader of [RedactionResult.byRule] tell those two situations apart, which is the
 * difference between a bug report and a shrug.
 */
enum class RedactionRule(val wireName: String) {
    /** A `key=value` whose key denotes a PII field. The field, not the shape, is the signal. */
    PII_KEY("pii-key"),

    /** A TD1 (30-char) or TD3 (44-char) run of MRZ-alphabet characters. ICAO 9303. */
    MRZ_ROW("mrz-line"),

    /** A 19xx/20xx date with `-`, `/`, `.` or a Unicode dash between the parts. */
    DATE("date"),

    /** Twelve digits in 4/4/4 groups, or bare. Aadhaar. */
    AADHAAR("aadhaar"),

    // Known, accepted cost of the rule above, recorded here rather than discovered in a log:
    // an Aadhaar number and a compact UTC timestamp (`202609301200`, `YYYYMMDDHHmm`) are both
    // twelve bare digits and there is no way to tell them apart from the text alone. The
    // scrubber fails closed and takes the timestamp. That is the right way round for a
    // privacy control — an operator who loses a timestamp can re-derive it from the line's
    // own position in the log, whereas a name on disk is permanent — but it is a real cost
    // and anyone who reads `aadhaar` in [RedactionResult.byRule] should know the rule fired.

    /** A 10–15 digit run with an optional `+`. E.164. */
    PHONE("phone"),

    /** An email address. */
    EMAIL("email"),

    /** A 1–2 letter prefix followed by 6–9 digits: the near-universal passport-number shape. */
    DOC_NUMBER("doc-number"),

    /** A long hex run. An embedding, a signature or a key, dumped as hex. */
    HEX_BLOB("hex-blob"),

    /** A long base64 run. Same three candidates, different encoding. */
    BASE64_BLOB("base64-blob"),

    /**
     * A compact JWT (RFC 7519): three dot-separated base64url segments.
     *
     * **Added when the scrubber moved to `:core`.** The desktop rule set had no JWT rule and
     * the base64 rule cannot cover one: a real token's three segments are each *shorter*
     * than the blob floor, so `eyJhbGci….eyJzdWIi….dBjftJeZ…` passed through untouched —
     * a bearer credential and, once decoded, a personal-subject assertion, in a log file
     * that is meant to be permanent and offline. A scrubber with a base64 rule that cannot
     * see the most common base64 payload on earth is not a control, it is a gesture.
     *
     * The cost is false positives on long dotted identifiers. See the registry entry
     * `REDACT_JWT_MIN_SEGMENT_CHARS` for why the floor is set where it is.
     */
    JWT("jwt"),

    /** A C0/C1 control character or an invisible formatting character. Not PII — see below. */
    CONTROL_CHAR("control-chars"),
    ;

    companion object {
        /**
         * Rules whose hits are PII removals, i.e. the ones [RedactionResult.redactions]
         * counts. [CONTROL_CHAR] is a *neutralisation*: the character was not carrying a
         * name, it was trying to forge a log line or repaint a terminal. Counting it as a
         * redaction would inflate the number an operator reads as "PII reached disk".
         */
        fun isPii(rule: RedactionRule): Boolean = rule != CONTROL_CHAR
    }
}

/**
 * How a scrubber reads a field label.
 *
 * The middle case is the interesting one and it is the reason this is a tri-state rather
 * than the boolean the desktop scrubber used. `UNKNOWN` is what a field added *after* the
 * key table was written looks like: the scrubber neither trusts it nor accuses it, it falls
 * back to the shape rules. Making that a named state — and asserting in the test suite that
 * the console's own field labels are never `UNKNOWN` — is the closest this repository can
 * get to AGENTS.md §4's "scrubbing is deny-by-default for new struct fields" without
 * turning the log into an allowlist, which would cost more readability than it buys privacy.
 * See `PiiScrubberFieldCorpusTest` for the enforcement and its honest limits.
 */
enum class KeyClass {
    /** The value is PII whatever it looks like. Redact it. */
    PII,

    /** The value is a digest this process computed. Preserve it. */
    OWN_DIGEST,

    /** Not classified. The shape rules decide. */
    UNKNOWN,
}

/**
 * The outcome of scrubbing one string.
 *
 * [redactions] exists because a scrubbed line with no count is a line nobody can act on.
 * A reader who sees `[redacted]` needs to know whether it replaced one phone number or forty
 * tokens of embedding, and "several things I should not have logged reached this line" is a
 * bug report whereas "one phone number in a free-text note" is not.
 *
 * The count describes what *this* pass removed, so it is zero on an already-scrubbed line
 * rather than the number of markers in it. That is the property a defence-in-depth double pass
 * needs — a caller that scrubs twice must not see the count go up — and it is worth
 * stating precisely, because the alternative (counting markers found in the text) would report
 * a redaction that had already happened, which is how a count stops being an alert.
 */
data class RedactionResult(
    /** The text to store. Never contains a value the rules recognised as PII. */
    val text: String,
    /** How many PII values were removed. See [RedactionRule.isPii] for what counts. */
    val redactions: Int,
    /** How many control/invisible characters were replaced. Not PII; see [RedactionRule]. */
    val neutralisedControls: Int,
    /** Per-rule counts. Only rules that fired are present, so an empty map means "clean". */
    val byRule: Map<RedactionRule, Int>,
) {
    /** True when nothing at all was removed. The cheap "this line is safe" answer. */
    val isClean: Boolean get() = redactions == 0 && neutralisedControls == 0

    /**
     * One rule to attribute a finding to when an operator asks "what was in this line?".
     *
     * The first rule in declaration order that fired, not the most frequent one: a line
     * with a keyed name *and* a phone number should be reported as a keyed-name bug, because
     * that is the one a call-site review can act on.
     */
    val primaryRule: RedactionRule? get() = RedactionRule.entries.firstOrNull { byRule[it] != null }
}
