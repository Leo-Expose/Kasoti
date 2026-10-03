package dev.kasoti.log

/**
 * The confusable fold and the invisible-character set.
 *
 * These two live together because they are the same idea seen from both sides. A character is
 * in this file either because it *looks* like an ASCII letter it is not (so a key rule is
 * fooled by it) or because it renders as nothing (so the reader and the file disagree about
 * what was written). Both are ways of getting a value past a redactor that a human eye stops
 * noticing, and both are undone here, before anything else runs.
 *
 * The fold is length-preserving on purpose. [RedactionPipeline] matches against the folded
 * shadow and redacts the *original* bytes at the offsets the shadow reported, so a rule that
 * fires at offset *n* in the shadow fires at offset *n* in the line that was logged. A fold
 * that changed length would let the scrubber redact the wrong span, which is a worse bug than
 * the one it fixes.
 *
 * The character classification is compared as `Int` bounds rather than written as `Char`
 * literals, so that no invisible character has to appear in this source file — where it would
 * be invisible to the next reviewer too.
 */
internal object RedactionFold {

    private val HOMOGLYPHS: Map<Char, Char> = buildMap {
        // Cyrillic. The lowercase block is the one that matters: an attacker replaces a
        // letter in a key, and keys are lowercase by convention in this codebase.
        for ((cyrillic, latin) in listOf(
            'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'н' to 'h', 'і' to 'i',
            'ј' to 'j', 'к' to 'k', 'м' to 'm', 'о' to 'o', 'р' to 'p', 'ѕ' to 's',
            'т' to 't', 'у' to 'y', 'х' to 'x', 'ԁ' to 'd', 'һ' to 'h', 'ӏ' to 'l',
            'ԛ' to 'q', 'ԝ' to 'w', 'г' to 'r', 'п' to 'n',
        )) {
            put(cyrillic, latin)
            put(cyrillic.uppercaseChar(), latin.uppercaseChar())
        }
        // Greek. `ο` (omicron) for `o` is the classic one.
        for ((greek, latin) in listOf(
            'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o',
            'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x', 'γ' to 'y', 'μ' to 'u',
        )) {
            put(greek, latin)
            put(greek.uppercaseChar(), latin.uppercaseChar())
        }
        // Digits in the scripts a Nepali/Indian operator actually reads. The phone and
        // Aadhaar rules are ASCII-digit rules; these are the same digits.
        for (base in listOf(0x0660, 0x06F0, 0x0966, 0x0E50)) {
            for (offset in 0..9) put((base + offset).toChar(), ('0' + offset))
        }
        // Punctuation and separators that carry meaning to the rules.
        put('‐', '-') // HYPHEN
        put('‑', '-') // NON-BREAKING HYPHEN
        put('‒', '-') // FIGURE DASH
        put('–', '-') // EN DASH
        put('—', '-') // EM DASH
        put('―', '-') // HORIZONTAL BAR
        put('−', '-') // MINUS SIGN
        put(' ', ' ') // NO-BREAK SPACE
        put('　', ' ') // IDEOGRAPHIC SPACE
        put('‘', '"') // LEFT SINGLE QUOTATION MARK
        put('’', '"') // RIGHT SINGLE QUOTATION MARK
        put('“', '"') // LEFT DOUBLE QUOTATION MARK
        put('”', '"') // RIGHT DOUBLE QUOTATION MARK
        // Latin-1 letters that fold to ASCII without being homoglyphs.
        put('ı', 'i') // DOTLESS I
        put('İ', 'I') // LATIN CAPITAL LETTER I WITH DOT ABOVE
    }

    /**
     * The fullwidth forms, folded arithmetically rather than table by table.
     *
     * U+FF01..U+FF5E is the fullwidth twin of ASCII 0x21..0x7E with a fixed 0xFEE0 offset,
     * so `ｒａｍ＠ｅｘａｍｐｌｅ．ｃｏｍ` folds to `ram@example.com` and the *existing* email
     * rule catches it with no change to that rule at all. This is the single highest-value
     * entry in the file and it costs one subtraction.
     */
    private const val FULLWIDTH_LOW: Int = 0xFF01
    private const val FULLWIDTH_HIGH: Int = 0xFF5E
    private const val FULLWIDTH_OFFSET: Int = 0xFEE0

    /**
     * Folds one character to its ASCII twin, or returns it unchanged.
     *
     * Total by construction: every branch either returns a character or falls through to
     * `this`. It cannot throw on a lone surrogate, on a NUL, or on any code point, which is
     * what makes [PiiScrubber.scrub] total over arbitrary `String` input.
     */
    fun foldChar(ch: Char): Char {
        val code = ch.code
        if (code in FULLWIDTH_LOW..FULLWIDTH_HIGH) {
            return (code - FULLWIDTH_OFFSET).toChar()
        }
        return HOMOGLYPHS[ch] ?: ch
    }

    /**
     * A same-length shadow of [text] in which every confusable is its ASCII twin.
     *
     * Same length is the whole contract, and it is load-bearing twice over: a shape rule that
     * matches at offset *n* in the shadow matches at offset *n* in the original, and every
     * redaction can be applied to the bytes that were actually logged instead of to a
     * normalised copy of them. A fold that changed length would let the scrubber redact the
     * wrong span, which is a worse bug than the one it is fixing.
     */
    fun fold(text: String): String {
        var firstChange = -1
        for (i in text.indices) {
            if (foldChar(text[i]) != text[i]) {
                firstChange = i
                break
            }
        }
        if (firstChange < 0) return text
        val out = CharArray(text.length)
        for (i in text.indices) {
            val ch = text[i]
            out[i] = if (i < firstChange) ch else foldChar(ch)
        }
        return String(out)
    }

    private fun isC0OrC1(code: Int): Boolean = code < 0x20 || code in 0x7F..0x9F

    /**
     * Characters that render as nothing but change what the reader sees.
     *
     * **This set is entirely new, and the desktop scrubber's own test claimed to cover it.**
     * `a bidi override is neutralised` asserts that `name=RA<U+202E>MESH VERMA` comes back
     * without the override — and it did, but only because U+202E happened to sit *inside a
     * PII key's value*, which the key rule deleted wholesale. The test passed without
     * exercising `neutraliseControlCharacters` at all, and the same character in any other
     * position survived. A test that passes for a reason other than the one it names is
     * worse than no test, because it retires the risk.
     *
     * What is here, and why each one is a forging vector rather than a cosmetic oddity:
     *
     *  - U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR. `String.lines()` splits on
     *    `\n` and `\r` and not on these, so a scrubber and a log viewer disagree about where
     *    the lines are. A JavaScript or JSON reader *does* treat them as line breaks. That
     *    gap is enough to append a forged record to a log the scrubber believed was one line.
     *  - U+202A..U+202E and U+2066..U+2069. Bidi embedding, override and isolate. The
     *    operator reads one string; the file contains another. `name=RA<U+202E>MESH` renders
     *    as `SHEMAR` in a terminal that honours the override.
     *  - U+200B..U+200F and U+FEFF. Zero-width space/joiners and the LRM/RLM marks. These
     *    make `n<U+200B>ame` and `name` two different byte strings that a human, a diff tool
     *    and a diff-based reviewer all read as the same word — which is precisely what makes
     *    them useful to someone trying to get a field past a key rule.
     *  - U+00AD SOFT HYPHEN and U+2060..U+2064. Same idea, and the same reason: invisible,
     *    and a `grep` for the field name does not find the field.
     *
     * The ranges are compared as `Int` bounds rather than written as `Char` literals so that
     * no invisible character has to appear in this source file, where it would be invisible
     * to the next reviewer too.
     */
    private val INVISIBLE_RANGES: List<IntRange> = listOf(
        0x00AD..0x00AD, // SOFT HYPHEN
        0x061C..0x061C, // ARABIC LETTER MARK
        0x200B..0x200F, // ZWSP, ZWNJ, ZWJ, LRM, RLM
        0x2028..0x2029, // LINE / PARAGRAPH SEPARATOR
        0x202A..0x202E, // BIDI EMBEDDING .. POP DIRECTIONAL FORMATTING
        0x2060..0x2064, // WORD JOINER, FUNCTION APPLICATION, INVISIBLE TIMES/PLUS/MINUS
        0x2066..0x2069, // LRI, RLI, FSI, PDI
        0xFEFF..0xFEFF, // ZERO WIDTH NO-BREAK SPACE / BOM
    )

    /** True for a character that renders as nothing and must not reach a stored log line. */
    fun isNeutralised(ch: Char): Boolean {
        val code = ch.code
        return isC0OrC1(code) || INVISIBLE_RANGES.any { code in it }
    }
}
