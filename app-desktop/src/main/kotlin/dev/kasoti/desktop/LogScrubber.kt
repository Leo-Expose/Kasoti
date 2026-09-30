package dev.kasoti.desktop

/**
 * The PII log scrubber (invariant I5, AGENTS.md §5, THREAT_MODEL.md §3).
 *
 * The threat is not hypothetical for this system. Almost every string that reaches a log
 * line came out of a document somebody is holding: a name off a passport, a date of birth,
 * an MRZ line, a phone number from a printed form. AGENTS.md forbids logging names, DOBs
 * and embeddings, and "just be careful" is not a control — a `println` three call-frames
 * away is one refactor from printing a name.
 *
 * So the scrubber is a chokepoint. Every console log line goes through [scrub], and the
 * test suite feeds it the hostile strings a document can contain: ANSI escapes, CR/LF, a
 * quoted CSV field, a template expression, a bidi override that reorders what the operator
 * sees, and a value long enough to be an embedding.
 */
object LogScrubber {

    /**
     * Keys whose *value* is PII regardless of what the value looks like.
     *
     * Pattern matching alone is not enough. A name is not a recognisable shape — "Dev" is
     * three letters — so the key is what makes it findable. Anything spelled like one of
     * these, under any capitalisation or separator, has its value replaced.
     */
    private val PII_KEYS = setOf(
        "name", "fullname", "firstname", "lastname", "surname", "givennames", "givenname",
        "dob", "dateofbirth", "birthdate", "birth",
        "phone", "mobile", "telephone", "contact", "email", "mail",
        "address", "addr", "aadhaar", "aadhar", "uidai", "ra", "referenceid", "refid",
        "voterid", "voter", "pan", "dl", "licence", "license", "passport", "documentnumber",
        "docno", "personalnumber", "embedding", "emb", "vector", "liveness",
        "guardian", "parent", "spouse", "nationality", "sex", "gender",
    )

    /**
     * Shape-based rules, applied to the whole line.
     *
     * These catch PII that arrived without a key — inside a free-text note, or quoted by a
     * third-party message. Each pattern is deliberately narrow: a rule so eager that it
     * eats the threshold version or a finding code destroys the log's usefulness, and a
     * reviewer who cannot read the log stops reading it.
     */
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // An MRZ row: a long unbroken run of MRZ-alphabet characters. Upper bound 44 keeps
        // it off prose; the MRZ alphabet has no spaces, so a 20+ run is a machine zone.
        Regex("""\b[A-Z0-9<]{20,44}\b""") to "mrz-line",
        // ISO or slash-separated date of birth.
        Regex("""\b(19|20)\d{2}[-/]\d{2}[-/]\d{2}\b""") to "date",
        // Aadhaar: 12 digits, optionally in 4/4/4 groups.
        Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""") to "aadhaar",
        // E.164 or a bare 10-15 digit run — phone numbers, and also anything else long and
        // numeric, which is the trade: a false positive here costs a `[redacted]`, a false
        // negative costs a phone number in a permanent log file.
        Regex("""(?<![\w.])\+?\d{10,15}(?![\w.])""") to "phone",
        // An email address.
        Regex("""\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""") to "email",
        // A long base64 or hex run: an embedding, a signature blob, a key.
        Regex("""\b[A-Fa-f0-9]{40,}\b""") to "hex-blob",
        Regex("""\b[A-Za-z0-9+/]{60,}={0,2}\b""") to "base64-blob",
    )

    /**
     * `key: "quoted value"` and `key="quoted value"`, with or without a leading quote on the
     * key. A quoted value is bounded by the closing quote, so a comma inside it cannot make
     * the redactor stop early.
     */
    private val QUOTED_PAIR = Regex("""(?<key>"?[A-Za-z_][A-Za-z0-9_.-]*"?)(?<sep>\s*[=:]\s*")[^"]*"""")

    /**
     * `key=value` and `key: value`.
     *
     * The value is one *comma-separated run* or one bracketed list, never a bare token.
     * That distinction is the whole point: `name=SHARMA,RAMESH,OFFSET` is one field in a
     * manifest row, and redacting only `SHARMA` would leave the rest of the row — including
     * the given names — sitting in the log looking like harmless data. Stopping at a space
     * instead is what keeps `name=RAMESH verdict=RED` readable.
     *
     * `}` and `)` stay inside the value on purpose. A field value is attacker-controlled and
     * template syntax like `${...}` is the obvious way to smuggle a payload past a redactor
     * that stops at the closing brace; truncating there would leave the payload visible.
     */
    private val BARE_PAIR = Regex(
        """(?<key>[A-Za-z_][A-Za-z0-9_.-]*)(?<sep>\s*[=:]\s*)(?:\[[^\[\]]*\]|[^\s,\]]+(?:,[^\s,\]]+)*)""",
    )

    /**
     * Strip C0/C1 control characters except tab.
     *
     * A name is not supposed to contain `\u001b`, and a log line that does is either a
     * terminal-escape attack or a broken field separator. Either way the operator's
     * terminal is no longer showing them what the console recorded, which is the whole
     * point of a log. CR is dropped along with the rest so nobody can rewrite a line that
     * was already written.
     */
    fun neutraliseControlCharacters(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '\t' -> append(ch)
                ch.code < 0x20 || ch.code in 0x7F..0x9F -> append('?')
                else -> append(ch)
            }
        }
    }

    /**
     * Keys whose value is a digest or an identifier the console itself produced.
     *
     * These survive the shape-based pass even when they look like a hex blob, because a
     * redacted audit tip is worse than useless — it is the one value in the whole line that
     * lets somebody check the chain by hand. The shape rules exist for *attacker-controlled*
     * content; a hash this process just computed is not that.
     *
     * The list is a coupling with the console's own field labels (`auditTip`, `chainTip`),
     * and it is deliberately explicit: broadening it to "anything hex-shaped" would also
     * un-redact an embedding dumped as hex, which is exactly the content AGENTS.md §5
     * forbids logging. A new digest-shaped field must be added here deliberately.
     */
    private val SAFE_KEYS = setOf(
        "tip", "audittip", "chaintip", "hash", "sha256", "chainsha", "digest",
    )

    /**
     * `key=<hex>` and `key    <hex>`.
     *
     * The separator is either a normal `=`/`:` or a run of spaces, because the console
     * prints its fields as an aligned table (`audit tip    a1b2…`) rather than as JSON.
     */
    private val SAFE_HEX_PAIR = Regex(
        """(?<key>[A-Za-z_][A-Za-z0-9_.-]*)(?<sep>\s*[=:]\s*|\s{2,})(?<value>[A-Fa-f0-9]{32,})""",
    )

    /** @return [line] with every PII value replaced and every control character neutralised. */
    fun scrub(line: String): String {
        val safe = neutraliseControlCharacters(line)

        val quoted = QUOTED_PAIR.replace(safe) { match ->
            if (!isPiiKey(match.groups["key"]!!.value)) {
                match.value
            } else {
                match.groups["key"]!!.value + match.groups["sep"]!!.value + Redaction.MARKER + '"'
            }
        }

        val keyed = BARE_PAIR.replace(quoted) { match ->
            if (!isPiiKey(match.groups["key"]!!.value)) {
                match.value
            } else {
                match.groups["key"]!!.value + match.groups["sep"]!!.value + Redaction.MARKER
            }
        }

        val parked = SAFE_HEX_PAIR.replace(keyed) { match ->
            val key = match.groups["key"]!!.value
            if (!SAFE_KEYS.contains(key.lowercase())) {
                match.value
            } else {
                match.groups["key"]!!.value + match.groups["sep"]!!.value + park(match.groups["value"]!!.value)
            }
        }

        var out = parked
        for ((pattern, _) in PATTERNS) {
            out = pattern.replace(out, Redaction.MARKER)
        }
        return UNPARK.replace(out) { match -> match.groupValues[1].replace(".", "") }
    }

    /**
     * Split a digest into short chunks behind a private-use fence.
     *
     * Chunking matters: parking the digest intact would not protect it. A 64-character hex
     * run is contiguous, so it still matches both the hex rule and the base64 rule from the
     * inside, and a "protected" value that gets redacted anyway is worse than no protection
     * at all because the test suite says it works.
     */
    private fun park(digest: String): String =
        PARK_OPEN + digest.chunked(PARK_CHUNK).joinToString(".") + PARK_CLOSE

    private val UNPARK = Regex("$PARK_OPEN([A-Fa-f0-9.]+)$PARK_CLOSE")

    private const val PARK_OPEN = "\uE000"
    private const val PARK_CLOSE = "\uE001"
    private const val PARK_CHUNK = 20

    /** True when a key name, however spelled, denotes a PII field. */
    fun isPiiKey(key: String): Boolean {
        val canonical = key.lowercase().filter { it.isLetterOrDigit() }
        return PII_KEYS.contains(canonical)
    }

    /** The pattern names, for the console's `--help` and for the test that checks coverage. */
    val patternNames: List<String> = PATTERNS.map { it.second } +
        listOf("pii-key", "control-chars", "own-digests")

}
