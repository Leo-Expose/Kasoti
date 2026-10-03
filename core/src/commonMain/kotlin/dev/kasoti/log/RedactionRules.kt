package dev.kasoti.log

/**
 * The rule tables, the confusable fold, and the compiled shape patterns.
 *
 * Everything in here is data plus one pure mapping. [PiiScrubber] owns the order the rules
 * run in; this file owns what they match. Splitting them that way is what lets the test
 * suite assert the tables directly (disjointness, lookalike coverage) instead of inferring
 * them from string surgery.
 *
 * Internal to the module: the *public* surface of the scrubber is [PiiScrubber] and
 * [RedactionResult]. A caller that reached for `SHAPE_RULES` directly would be reimplementing
 * the pass order, which is the part that is load-bearing.
 */
internal object RedactionRules {

    /**
     * The canonical form of a *key*: lowercased, confusables folded, punctuation dropped.
     *
     * Dropping non-alphanumerics is what makes `full_name`, `fullName`, `full.name` and
     * `FULL-NAME` one key rather than four. Folding is what stops `full_nаme` with a
     * Cyrillic `а` being a fifth.
     */
    fun canonicalKey(key: String): String {
        val folded = fold(key).lowercase()
        val out = StringBuilder(folded.length)
        for (ch in folded) {
            if (ch.isLetterOrDigit()) out.append(ch)
        }
        return out.toString()
    }

    /** Which table, if any, a key label falls in. See [KeyClass] for why this is tri-state. */
    fun classify(key: String): KeyClass {
        val canonical = canonicalKey(key)
        return when {
            PII_KEYS.contains(canonical) -> KeyClass.PII
            OWN_DIGEST_KEYS.contains(canonical) -> KeyClass.OWN_DIGEST
            else -> KeyClass.UNKNOWN
        }
    }

    /** @see RedactionFold.fold */
    fun fold(text: String): String = RedactionFold.fold(text)

    /** @see RedactionFold.foldChar */
    fun foldChar(ch: Char): Char = RedactionFold.foldChar(ch)

    /** @see RedactionFold.isNeutralised */
    fun isNeutralised(ch: Char): Boolean = RedactionFold.isNeutralised(ch)

    /** @see RedactionShapes.shapeRules */
    fun shapeRules(policy: RedactionPolicy): List<Pair<RedactionRule, Regex>> =
        RedactionShapes.shapeRules(policy)

    // =====================================================================
    // Keyed rules
    // =====================================================================

    /**
     * Keys whose *value* is PII whatever the value looks like.
     *
     * Carried over from the desktop `LogScrubber` unchanged, with four additions marked
     * below. Pattern matching alone is not enough: a name is not a recognisable shape —
     * "Dev" is three letters — so the key is what makes it findable. Anything spelled like
     * one of these, under any capitalisation, separator or script, has its value replaced.
     */    val PII_KEYS: Set<String> = setOf(
        "name", "fullname", "firstname", "lastname", "surname", "givennames", "givenname",
        "dob", "dateofbirth", "birthdate", "birth",
        "phone", "mobile", "telephone", "contact", "email", "mail",
        "address", "addr", "aadhaar", "aadhar", "uidai", "ra", "referenceid", "refid",
        "voterid", "voter", "pan", "dl", "licence", "license", "passport", "documentnumber",
        "docno", "personalnumber", "embedding", "emb", "vector", "liveness",
        "guardian", "parent", "spouse", "nationality", "sex", "gender",
        // --- added when the scrubber moved to :core ---------------------------------
        // The shape rule for an MRZ row is anchored on the two ICAO 9303 row lengths, which
        // means it cannot see a row that OCR has shortened or a line that was split before
        // it got here. The key rule has no such precondition, so an MRZ reaching a log
        // *under any name it was given* is caught even when its shape is broken.
        "mrz", "mrzline", "mrzlines", "mrz1", "mrz2", "mrzline1", "mrzline2", "td1", "td3",
        // --- added when the scrubber moved to :core -------------------------------------
        // The six most obvious omissions from a table written for a *console*, which logs
        // `name=` and `dob=` because those are the fields it happens to print. A field-screening
        // system has a holder and an applicant and a traveller on every document it reads, and
        // the JSON-shaped records the field layers emit call the same person `holder`. A key
        // table that does not know those is not fail-closed for them, and a name in a `holder`
        // field is exactly the leak the key rule exists to stop.
        "holder", "owner", "applicant", "traveler", "traveller", "person", "subject",
    )

    /**
     * Keys whose value is a digest this process computed.
     *
     * Carried over unchanged, and deliberately *short*. The list is a coupling with the
     * console's own field labels (`auditTip`, `chainTip`) and it is explicitly enumerated:
     * broadening it to "anything hex-shaped" would also un-redact an embedding dumped as
     * hex, which is the content AGENTS.md §5 forbids logging. A new digest-shaped field must
     * be added here deliberately, and [assertSafe] is what makes the omission loud.
     *
     * Note the asymmetry that makes this safe: preservation is decided by the *key*, and a
     * PII key wins over a safe key everywhere in [PiiScrubber]'s pass order. So there is no
     * input for which the two tables disagree about the same span.
     */
    val OWN_DIGEST_KEYS: Set<String> = setOf(
        "tip", "audittip", "chaintip", "hash", "sha256", "chainsha", "digest",
    )

    /**
     * `key: "value"` / `key="value"`, with or without a leading quote on the key.
     *
     * Both the opening *and* the closing quote of the value are captured, separately.
     *
     * The closing quote is the second half of a bug that shipped a broken scrubber which
     * looked correct: `[^"]*` stops *before* the value's closing quote, so the match consumed
     * `"name": "Ramesh Verma` and left the `"` behind — and the replacement re-emitted one of
     * its own. The result was `{"name": "[redacted]""}`: a doubled quote, and a JSON document
     * that no longer parses. Every caller of that rule that embedded its output in JSON got
     * a corrupt line, and the corruption was invisible to any test that asserted on the
     * presence of `[redacted]` rather than on the validity of the string.
     *
     * Capturing it as `(?<cq>"?)` fixes it for both shapes: a well-formed pair comes back with
     * both quotes, and a truncated one (`name="abc`, which a caller can produce) comes back
     * with only the quote it actually had, instead of acquiring a closing one it never had.
     *
     * The leading quote on the key is captured for the same reason — [PiiScrubber] re-emits
     * the key and separator as they were *written*, so `{"name": …}` stays `{"name": …}`.
     */
    val QUOTED_PAIR: Regex = Regex(
        """(?<key>"?[A-Za-z_][A-Za-z0-9_.-]*"?)(?<sep>\s*[=:]\s*)(?<q>")[^"]*(?<cq>"?)""",
    )


    /**
     * Where a value under a key ends, when there are no quotes or brackets to tell us.
     *
     * Two boundaries and no others: the next recognisable `key<sep>` token, or the end of the
     * line. Everything else in the value belongs to the value.
     *
     * ## WHY THIS IS NOT "STOP AT THE NEXT SPACE"
     *
     * The rule this replaced stopped a bare value at the first space, which is a coin flip
     * between a privacy failure and an unreadable log and happened to land on readability:
     *
     *     name=RAMESH VERMA verdict=RED   ->   name=[redacted] VERMA verdict=RED
     *
     * That line is a bug, not a trade-off. `VERMA` is the surname; the whole point of having
     * a key table is that a name has no shape to find on its own, and then the rule stops
     * halfway through the one name it was told about. A half-redacted name is not a redacted
     * name.
     *
     * The fix gets both halves of the trade at once, because a log line *does* have structure
     * to exploit: `verdict=RED` is a `key<sep>` token, so it is a boundary, and
     * `name=RAMESH VERMA verdict=RED` becomes `name=[redacted] verdict=RED`. Readable *and*
     * complete. A two-word name with nothing after it becomes `name=[redacted]`, because
     * there is nothing after it.
     *
     * The aligned-table form (`key   value`) is a boundary too, or `name   RAMESH VERMA
     *   verdict  RED` would take `VERMA` with it.
     *
     * ## WHY THIS CANNOT LOSE REDACTION
     *
     * For a non-PII key the match is returned verbatim, so a wider value changes nothing
     * observable. For a PII key this rule can only ever redact *more*, never less, than the
     * old one did on the same input — the old value was a prefix of the new one, and the old
     * match's extent was a subset of the new match's. That direction is the one worth having
     * in a control whose job is to fail closed.
     */
    private const val VALUE_END = "(?=[ \t]+[A-Za-z_][A-Za-z0-9_.-]*[ \t]*[=:][ \t]*" +
        "|[ \t]{2,}[A-Za-z_][A-Za-z0-9_.-]*" +
        "|(?![^\n]))"

    /**
     * `key=value`, `key: value`, `key   value` (the console's aligned tables).
     *
     * The value is one quoted string, one bracketed list, or — failing both — everything up
     * to the next field, as [VALUE_END] defines. The quoted and bracketed alternatives come
     * first because they are unambiguous: a bracket or a quote inside a value is data, and a
     * field boundary inside quotes would be a false boundary.
     *
     * The quoted alternative is listed *before* the run-to-next-field one so that a value the
     * quoted pass already redacted is consumed whole. Without it the second alternative would
     * match `"[redacted` and stop at the `]` on the second scrub, and the count would report a
     * redaction that had already happened.
     *
     * `}` and `)` stay inside the value on purpose. A field value is attacker-controlled and
     * template syntax like `${...}` is the obvious way to smuggle a payload past a
     * redactor that stops at the closing brace; truncating there would leave it visible.
     */
    val BARE_PAIR: Regex = Regex(
        """(?<key>[A-Za-z_][A-Za-z0-9_.-]*)(?<sep>\s*[=:]\s*|\s{2,})""" +
            """(?:"[^"]*"|\[[^\[\]]*\]|[^\n]*?""" + VALUE_END + """)""",
    )

    /**
     * `key=<hex>` and `key    <hex>` — the spans the shape rules must not touch.
     *
     * Used only to *exclude* spans, never to rewrite them. The desktop implementation
     * instead rewrote a safe digest into a chunked, private-use-fenced form, ran the shape
     * rules over the whole line, and then un-fenced it. That worked, and it was also a
     * standing invitation to the next person to add a rule: for the duration of the shape
     * pass the digest existed on disk in a form only the un-fencing regex knew how to read,
     * and a rule that did not respect the fence would have quietly eaten it. Excluding the
     * span means the digest is never in a matchable form in the first place.
     */
    val OWN_DIGEST_FIELD: Regex = Regex(
        """(?<key>[A-Za-z_][A-Za-z0-9_.-]*)(?<sep>\s*[=:]\s*|\s{2,})(?<value>[A-Fa-f0-9]{32,})""",
    )

    // =====================================================================
    // Confusable folding
    // =====================================================================

    /**
     * One-character homoglyphs, folded to their ASCII twin.
     *
     * **Every entry here is a bypass that existed before this table did.** `nаme=RAMESH`
     * with a Cyrillic `а` (U+0430) is not `name=RAMESH`, so the key table did not match it
     * and the value went to disk in the clear. An attacker who can influence a field name —
     * and a document's OCR output is attacker-controlled by definition — picks the letter
     * their operator's eye resolves most naturally, which for a Nepali-locale keyboard is
     * very often the Cyrillic homoglyph rather than the Latin one.
     *
     * The map is only allowed to contain 1:1 substitutions. That is what lets
     * [PiiScrubber] fold a string into a same-length shadow and redact the *original*
     * bytes at the corresponding offsets, so the stored log is never silently rewritten to
     * look more normal than it was. Ligatures (`ﬀ` → `ff`) and mathematical alphanumerics
     * are excluded for exactly that reason; they are listed as known gaps in the class
     * comment on [PiiScrubber] rather than approximated here.
     */
}
