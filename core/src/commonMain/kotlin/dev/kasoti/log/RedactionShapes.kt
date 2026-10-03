package dev.kasoti.log

/**
 * The *shape* rules: PII recognised by what a value looks like rather than by what it is called.
 *
 * Split out of `RedactionRules.kt` because they answer a different question and are governed by
 * a different body of evidence. `RedactionRules` holds the keyed rules, whose evidence is a
 * field name; this holds the shape rules, whose evidence is ICAO 9303, E.164, RFC 822 and one
 * 14-digit habit. Keeping them apart means a reader checking "does this pattern have a citation?"
 * is not also reading a table of field names, and it keeps every file in this package inside the
 * 400 lines AGENTS.md §2 asks for.
 *
 * Internal to the module, like the rules it holds: the public surface of the scrubber is
 * [PiiScrubber]. A caller that reached for `shapeRules` directly would be reimplementing the
 * pass order, which is the part that is load-bearing.
 */
internal object RedactionShapes {

    // =====================================================================
    // Shape rules
    // =====================================================================

    /**
     * The shape rules, in the order [PiiScrubber] applies them.
     *
     * Order is not load-bearing between these (each replaces non-overlapping spans of the
     * folded shadow) but it *is* load-bearing for the count: the first one to fire becomes
     * [RedactionResult.primaryRule].
     *
     * ## The one number that is NOT carried over
     *
     * The desktop MRZ rule matched `\b[A-Z0-9<]{20,44}\b`. Its lower bound of 20 is wrong,
     * and it is wrong in the way that costs the most: **26 is the length of a ULID.**
     * KASOTI's own event and case identifiers are 26-character Crockford base32 — uppercase,
     * digits, no spaces — so `evt_01JQ8Z3K9QWERTY12345ABCDEF` and a *bare* ULID were both
     * redacted. A scrubber that eats the case id out of every log line destroys the value of
     * the log in exchange for protecting against a document row that is not 26 characters
     * long. The floor is now [RedactionPolicy.ICAO_MRZ_TD1_CHARS] (30), which is the length
     * ICAO 9303 fixes for a TD1 row and which no identifier in this system has.
     *
     * The upper bound is also load-bearing and was left alone: 44 is the TD3 row length, and
     * the boundary anchors are what stop the rule firing *inside* a longer run (see the
     * `DOC_NUMBER` comment for the same trick).
     */
    fun shapeRules(policy: RedactionPolicy): List<Pair<RedactionRule, Regex>> = listOf(
        // ICAO 9303 MRZ row: TD1 is 30 characters, TD3 is 44. The MRZ alphabet has no
        // spaces, so a run of exactly one of those lengths standing alone is a machine zone.
        // The second alternative is the two-row case: a TD3 MRZ is 88 characters and a TD1
        // is 90 once the row separator is gone, and the control-character pass has already
        // turned any separator into `?` — so a caller that joined the rows before logging
        // would otherwise hand us one unbroken run that neither length matches.
        RedactionRule.MRZ_ROW to Regex(mrzRowPattern()),
        // A date of birth or an expiry date. `19|20` is deliberate: the earliest passport in
        // circulation predates 1900 by decades, and a bare `\d{4}` would eat version strings
        // and any four-digit quantity. Only ASCII separators are listed: the Unicode dashes
        // and the fullwidth forms are already ASCII in the shadow by the time this runs, so
        // adding them here would be dead weight in a pattern a reader has to trust.
        RedactionRule.DATE to Regex("""\b(19|20)\d{2}[-/.]\d{2}[-/.]\d{2}\b"""),
        // Aadhaar: twelve digits, optionally in 4/4/4 groups. The bare form is a subset of
        // the phone rule, but it is named separately so a reader can tell which rule fired.
        RedactionRule.AADHAAR to Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b"""),
        // E.164, or any bare digit run long enough to be one. Also anything else long and
        // numeric, which is the trade the original states and this keeps: a false positive
        // costs one `[redacted]`, a false negative costs a phone number in a file that is
        // meant to be permanent. The lookarounds stop it eating a fragment of a longer
        // token, which is how a 15-digit id stops costing a 10-digit id its readability.
        RedactionRule.PHONE to Regex(
            """(?<![\w.])\+?\d{${policy.phoneMinDigits},${RedactionPolicy.E164_MAX_DIGITS}}(?![\w.])""",
        ),
        // An email address. Needs no confusable classes of its own: the shadow folded
        // `ｒａｍ＠ｅｘａｍｐｌｅ．ｃｏｍ` and `ram@exаmple.com` to plain ASCII before this
        // pattern ever saw them.
        RedactionRule.EMAIL to Regex("""\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b"""),
        // A document number: one or two uppercase letters then a long digit run. This is
        // the shape of an Indian (2+7), UK (5+4 in two groups), US and Schengen passport
        // number, and of the 9-character MRZ document field. The word boundaries are what
        // make it safe — inside a 44-character MRZ row the pattern would otherwise match the
        // first nine characters of a much longer token.
        RedactionRule.DOC_NUMBER to Regex(
            """\b[A-Z]{1,2}\d{${policy.docNumberMinDigits},${RedactionPolicy.MRZ_DOCNUM_CHARS}}\b""",
        ),
        // A long hex run: an embedding, a signature blob, a key, a MAC.
        RedactionRule.HEX_BLOB to Regex("""\b[A-Fa-f0-9]{${policy.hexBlobMinChars},}\b"""),
        // A long base64 run, padding included.
        //
        // Padding is a *repeating* group rather than a trailing `={0,2}`, because a base64
        // token is `(payload)+=(optional)` repeated: a log line carrying two concatenated
        // padded chunks has its padding in the middle, and no trailing quantifier can reach
        // past it. The desktop pattern's `\b`-anchored `={0,2}` silently left `=QQ==` outside
        // the match, which is harmless and quietly wrong about what "redacted" means.
        //
        // The lookbehind deliberately includes `=`. A run may only *start* where the previous
        // character cannot be part of a base64 token, and `=` is a token character. That one
        // exclusion is what keeps `sha256=<64 hex>` from being read as a single 71-character
        // payload — the two forms are otherwise indistinguishable, and this is the only
        // thing in the pattern that can tell them apart.
        RedactionRule.BASE64_BLOB to Regex(
            "(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{${policy.base64BlobMinChars},}" +
                "(?:={1,2}[A-Za-z0-9+/]*)*(?![A-Za-z0-9+/=])",
        ),
        // A compact JWT: three base64url segments joined by dots (RFC 7519 §3.1). See
        // [RedactionRule.JWT] for why this exists when a base64 rule already does.
        //
        // Both floors are registered (AGENTS.md §2) and both are the *segment* lengths rather
        // than the token's, because the segment floor is what discriminates. A dotted
        // hostname is the thing this rule must not eat, and `kubernetes.default.svc.cluster.local`
        // has a 3-character segment while the shortest segment of a real JWT header
        // (`{"alg":"HS256","typ":"JWT"}`) is 36. Twelve sits in the empty space between them
        // with room for both to move.
        //
        // `.` and `-` are in the boundary lookarounds, not just in the body class: without
        // them the rule could start matching in the *middle* of a longer dotted identifier
        // and redact a fragment, which is how a hostname ends up half-eaten instead of whole.
        //
        // `=` is deliberately NOT excluded, which is the opposite of the base64 rule. A token
        // almost always arrives as `token=<jwt>` or `Authorization: Bearer <jwt>`, so a
        // lookbehind that rejected `=` would forbid the one place a token appears. There is
        // nothing hex-shaped here for that exclusion to be protecting.
        RedactionRule.JWT to Regex(
            "(?<![A-Za-z0-9._-])" +
                "[A-Za-z0-9_-]{${policy.jwtMinSegmentChars},}\\.[A-Za-z0-9_-]{${policy.jwtMinSegmentChars},}" +
                "\\.[A-Za-z0-9_-]{${policy.jwtMinSegmentChars},}" +
                "(?![A-Za-z0-9._-])",
        ),
    )

    // =====================================================================
    // Invisible characters
    // =====================================================================

    /**
     * The MRZ-row pattern, built from the ICAO widths rather than written out.
     *
     * A single row is 30 (TD1) or 44 (TD3) characters, and the range accepts the slop either
     * way so a row with one filler character transposed still matches. A whole document is
     * two TD3 rows (88) or three TD1 rows (90); the two characters of slack either side of
     * that cover a separator the caller dropped.
     */
    private fun mrzRowPattern(): String {
        val td1 = RedactionPolicy.ICAO_MRZ_TD1_CHARS
        val td3 = RedactionPolicy.ICAO_MRZ_TD3_CHARS
        val documentLow = td3 * 2 - 2
        val documentHigh = td3 * 2 + 2
        return """\b[A-Z0-9<]{$td1,$td3}\b|\b[A-Z0-9<]{$documentLow,$documentHigh}\b"""
    }

    /**
     * C0 (except tab), DEL, and C1.
     *
     * Carried over unchanged from the desktop scrubber. A log line containing any of these
     * is either a terminal-escape attack, a broken separator, or a field a caller forgot to
     * escape — and in all three cases the operator's terminal is no longer showing them what
     * the console recorded, which is the entire purpose of a log. CR goes with the rest so
     * nobody can rewrite a line that was already written.
     */
}
