package dev.kasoti.log

/**
 * How the rules run, as opposed to what they match.
 *
 * `RedactionRules` / `RedactionFold` / `RedactionShapes` say what a PII field, an MRZ row or a
 * confusable character *is*. This file says what happens to a line once that is decided, and it
 * is kept apart for the reason the order is load-bearing: a caller who wants to know *whether*
 * something is redacted needs [PiiScrubber], and a caller who wants to change *what* is redacted
 * needs the rules. Folding the two together is how a shape rule ends up quietly depending on a
 * pass it was supposed to run before.
 *
 * The whole pipeline is one pure function of `(text, compiled rules)` returning a
 * [RedactionResult]. It has no state, no clock, no randomness and no way to reach a caller, so
 * the same input produces the same output on every device and in every test — which is the
 * property that lets `:core` be reviewed as the layer that provably has no side effects.
 */
internal object RedactionPipeline {

    /**
     * The whole scrubber.
     *
     * @param text the line as it was written.
     * @param shapeRules the compiled shape rules, passed in so this object stays stateless and
     *   the `Regex` objects are built once per scrubber rather than once per line.
     */
    fun scrub(
        text: String,
        shapeRules: List<Pair<RedactionRule, Regex>>,
    ): RedactionResult {
        val shadow = shadow(text)
        val edits = collectEdits(text, shadow, shapeRules)
        val counts = LinkedHashMap<RedactionRule, Int>()
        for (edit in edits) counts.merge(edit.rule, 1, Int::plus)
        return RedactionResult(
            text = assemble(text, edits),
            redactions = edits.size,
            neutralisedControls = countNeutralised(text, edits),
            byRule = counts,
        )
    }

    internal class Shadow(
        val folded: String,
        val origin: IntArray?,
        val filtered: Int,
    ) {
        /** Original index of the character this shadow index stands for. */
        fun at(shadowIndex: Int): Int = origin?.get(shadowIndex) ?: shadowIndex

        /**
         * Original index of the character *after* the one at [shadowIndex].
         *
         * This is not [at] `+ 1`: when the character that follows in the shadow was filtered
         * out of the original, the span has to reach past it, or the redaction would leave a
         * trailing invisible character sitting outside the replaced region.
         */
        fun after(shadowIndex: Int): Int =
            origin?.get(shadowIndex + 1) ?: shadowIndex + 1

        /** The original bytes the shadow range stands for. */
        fun slice(text: String, range: IntRange): String =
            text.substring(at(range.first), after(range.last))
    }

    internal fun shadow(text: String): Shadow {
        var filtered = 0
        for (i in text.indices) {
            if (RedactionFold.isNeutralised(text[i])) {
                filtered++
            }
        }
        // Folding happens on BOTH paths. An earlier version returned the raw line here,
        // which meant the key rules never saw a folded string and every Cyrillic-homoglyph
        // field name bypassed them while a table in RedactionRules documented the bypass
        // as closed. `fold` returns its argument unchanged when there is nothing to fold,
        // so the common clean-ASCII line costs one pass and no allocation.
        if (filtered == 0) return Shadow(RedactionRules.fold(text), null, 0)
        val chars = StringBuilder(text.length)
        val origin = IntArray(text.length + 1)
        var kept = 0
        for (i in text.indices) {
            val ch = text[i]
            if (RedactionFold.isNeutralised(ch)) continue
            chars.append(ch)
            origin[kept++] = i
        }
        origin[kept] = text.length
        return Shadow(RedactionRules.fold(chars.toString()), origin, filtered)
    }

    // =====================================================================
    // Edits
    // =====================================================================

    /** One replacement, in original-line coordinates. */
    internal class Edit(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
        val rule: RedactionRule,
    )


    /**
     * Every redaction, in the order they will be written, with overlaps already resolved.
     *
     * Gathered and sorted rather than applied rule by rule, because two rules *can* match
     * overlapping spans — a 44-character MRZ row also contains a document number, and a
     * `name=` field contains both — and applying them in sequence would need the second to
     * notice that the first had already eaten its bytes. Doing it in one pass makes the
     * precedence explicit: leftmost wins, and among equal starts the shortest span wins,
     * which is the keyed field over the shape inside its own value.
     */
    private fun collectEdits(
        text: String,
        shadow: Shadow,
        shapeRules: List<Pair<RedactionRule, Regex>>,
    ): List<Edit> {
        val found = ArrayList<Edit>()
        found += keyedEdits(text, shadow)
        val preserved = ownDigestRanges(shadow)
        for ((rule, pattern) in shapeRules) {
            for (match in pattern.findAll(shadow.folded)) {
                if (preserved.any { match.range.first <= it.last && it.first <= match.range.last }) {
                    continue
                }
                found += Edit(
                    start = shadow.at(match.range.first),
                    endExclusive = shadow.after(match.range.last),
                    replacement = REDACTION_MARKER,
                    rule = rule,
                )
            }
        }
        found.sortWith(compareBy({ it.start }, { it.endExclusive }))
        val kept = ArrayList<Edit>(found.size)
        var consumedTo = -1
        for (candidate in found) {
            if (candidate.start > consumedTo) {
                kept += candidate
                consumedTo = candidate.endExclusive - 1
            }
        }
        return kept
    }

    /**
     * Pass 1 — redact a value whose *key* says it is PII.
     *
     * Two patterns, quoted before bare, for one reason: whichever runs second would otherwise
     * see the first one's output and either re-count a redaction or match a fragment of the
     * marker. So the bare pass is told which shadow ranges the quoted pass already claimed
     * and steps over them, rather than relying on the sort in [collectEdits] to break the tie.
     *
     * A match that so much as *touches* an own-digest span is not here — that is decided once,
     * for every rule at once, in [ownDigestRanges].
     */
    internal fun keyedEdits(text: String, shadow: Shadow): List<Edit> {
        val edits = ArrayList<Edit>()
        val claimed = ArrayList<IntRange>()

        // The two patterns describe a field differently, and the difference is spelled out here
        // rather than guessed at inside one shared helper: the quoted pattern knows its value
        // sits between `q` and `cq`, and the bare pattern has to take the tail of its match.
        //
        // That has to be per-pattern because Kotlin's `MatchGroupCollection.get(name)` *throws*
        // for a name the pattern does not have instead of returning null, so a shared helper
        // that reads an optional group is an exception thrown from inside a log writer. A
        // [KeyedField] carries the answer with the match, and `keyedEdit` never has to ask.
        for (match in RedactionRules.QUOTED_PAIR.findAll(shadow.folded)) {
            val field = KeyedField(
                match = match,
                valueStart = match.groups["q"]!!.range.last + 1,
                valueEnd = match.groups["cq"]!!.range.first,
                before = QUOTED_RE_EMIT_BEFORE,
                after = QUOTED_RE_EMIT_AFTER,
            )
            val edit = keyedEdit(text, shadow, field)
            if (edit != null) edits += edit
            claimed += match.range
        }
        for (match in RedactionRules.BARE_PAIR.findAll(shadow.folded)) {
            if (claimed.any { match.range.first <= it.last && it.first <= match.range.last }) continue
            val field = KeyedField(
                match = match,
                valueStart = match.groups["sep"]!!.range.last + 1,
                valueEnd = match.range.last + 1,
                before = BARE_RE_EMIT,
                after = emptyList(),
            )
            val edit = keyedEdit(text, shadow, field)
            if (edit != null) edits += edit
            claimed += match.range
        }
        return edits
    }

    /** One keyed-field match, with the pattern-specific facts about where its value is. */
    internal class KeyedField(
        val match: MatchResult,
        /** Shadow offset of the field's value, and just past its last byte. */
        val valueStart: Int,
        val valueEnd: Int,
        /** Named groups copied verbatim before the marker, in order. */
        val before: List<String>,
        /** Named groups copied verbatim after the marker, in order. Empty for the bare pattern. */
        val after: List<String>,
    )

    /**
     * Turn one keyed-field match into an [Edit], or `null` when the field is not PII or has
     * already been redacted.
     *
     * The replacement re-emits the key, the separator and whatever quotes the field actually
     * had, taken from the **original** bytes rather than from the shadow, so `{"name": "X"}`
     * comes back as `{"name": "[redacted]"}` and `nаme=RAMESH` comes back with the homoglyph
     * the attacker put there. Re-emitting the shadow's version would have quietly normalised
     * attacker-controlled bytes into something that looks legitimate.
     */
    internal fun keyedEdit(text: String, shadow: Shadow, field: KeyedField): Edit? {
        val match = field.match
        val value = shadow.folded.substring(field.valueStart, field.valueEnd)
        // Three reasons to decline, in the order they are cheapest to check.
        return when {
            RedactionRules.classify(match.groups["key"]!!.value) != KeyClass.PII -> null
            field.valueStart > field.valueEnd -> null
            value.trim('"') == REDACTION_MARKER -> null
            else -> {
                val rebuilt = StringBuilder(match.value.length)
                for (name in field.before) rebuilt.append(shadow.slice(text, match.groups[name]!!.range))
                rebuilt.append(REDACTION_MARKER)
                for (name in field.after) rebuilt.append(shadow.slice(text, match.groups[name]!!.range))
                Edit(
                    start = shadow.at(match.range.first),
                    endExclusive = shadow.after(match.range.last),
                    replacement = rebuilt.toString(),
                    rule = RedactionRule.PII_KEY,
                )
            }
        }
    }

    /**
     * The value spans of fields whose key says they hold a digest this process computed.
     *
     * Returned as ranges rather than rewritten in place. The desktop implementation parked
     * the digest behind a private-use fence, ran the shape rules over the whole line, and
     * un-fenced it afterwards — which meant that for the duration of the shape pass a
     * protected digest was sitting in the string in a form only the un-fencing regex could
     * read, and the next rule anyone added would have had to know about the fence. Excluding
     * the span means there is no protected intermediate state to get wrong.
     *
     * A shape match that overlaps one is dropped whole, not trimmed: the span is a hands-off
     * region declared by an explicit `key<sep>`, and a rule that has reached across it is
     * reading the digest as part of something else. Emitting the parts that stick out would
     * risk writing a mangled remnant of the digest, which is worse than either extreme.
     */
    internal fun ownDigestRanges(shadow: Shadow): List<IntRange> =
        RedactionRules.OWN_DIGEST_FIELD.findAll(shadow.folded)
            .filter { match ->
                RedactionRules.classify(match.groups["key"]!!.value) == KeyClass.OWN_DIGEST
            }
            .map { it.groups["value"]!!.range }
            .toList()

    // =====================================================================
    // Assembly
    // =====================================================================

    /**
     * Write the stored line: original bytes, with each edited span replaced.
     *
     * The neutralisation pass is folded in here rather than run as a separate step over the
     * finished string, because the two interact: a character that sits *inside* a redaction
     * is removed rather than replaced, and counting it as neutralised would tell an operator
     * that somebody tried to forge a log line when what happened is that a name was removed.
     */
    internal fun assemble(text: String, edits: List<Edit>): String {
        if (edits.isEmpty() && !text.any { RedactionRules.isNeutralised(it) }) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        for (edit in edits) {
            appendNeutralised(out, text, cursor, edit.start)
            appendNeutralised(out, edit.replacement)
            cursor = edit.endExclusive
        }
        appendNeutralised(out, text, cursor, text.length)
        return out.toString()
    }

    internal fun appendNeutralised(out: StringBuilder, source: String, from: Int, to: Int) {
        for (i in from until to) out.append(neutraliseOne(source[i]))
    }

    internal fun appendNeutralised(out: StringBuilder, source: String) {
        for (ch in source) out.append(neutraliseOne(ch))
    }

    /**
     * How many characters the stored line had replaced rather than kept.
     *
     * Two regions are counted, and both are *replacements* rather than kept text:
     *
     *  - the gaps between redactions, where a control character was swapped for `?`;
     *  - the re-emitted key of a redacted field, because the shadow deleted an invisible
     *    character so the key rules could see the word and the stored key is the original
     *    bytes. `n<U+200B>ame=[redacted]` keeps a `?` where the zero-width character was, and
     *    counting it is how an operator finds out a field *label* was tampered with rather than
     *    merely long.
     *
     * Characters *inside* a redacted value are neither counted nor neutralised: they were
     * removed with the value, and reporting them would tell an operator that somebody tried to
     * forge a log line when what happened is that a name was removed.
     */
    internal fun countNeutralised(text: String, edits: List<Edit>): Int {
        var count = 0
        var cursor = 0
        for (edit in edits) {
            for (i in cursor until edit.start) if (RedactionFold.isNeutralised(text[i])) count++
            for (ch in edit.replacement) if (RedactionFold.isNeutralised(ch)) count++
            cursor = edit.endExclusive
        }
        for (i in cursor until text.length) if (RedactionFold.isNeutralised(text[i])) count++
        return count
    }


            /**
             * The named groups of each keyed-field pattern that are re-emitted verbatim around the
             * marker. A `List` rather than a hard-coded sequence of appends because the order *is*
             * the contract — key, separator, opening quote, marker, closing quote — and a
             * refactor that reordered it would produce a line that still looked right in a
             * snapshot test.
             *
             * The split is the point: `q` is emitted *before* the marker and `cq` *after* it, so
             * the marker lands between the quotes. Emitting both and then appending the marker is
             * what produced `{"name": ""[redacted]}` — and, in the version before that,
             * `{"name": "[redacted]""}`, which is not JSON at all.
             */
            private val QUOTED_RE_EMIT_BEFORE: List<String> = listOf("key", "sep", "q")
            private val QUOTED_RE_EMIT_AFTER: List<String> = listOf("cq")
            private val BARE_RE_EMIT: List<String> = listOf("key", "sep")

    /**
     * One character, log-safe.
     *
     * Lives here rather than on [PiiScrubber] because the pipeline is what applies it, and a
     * private companion member cannot be reached from another file in Kotlin. It is exposed to
     * callers as `PiiScrubber.neutraliseControlCharacters`, which is the transformation anybody
     * outside this package actually wants.
     */
    internal fun neutraliseOne(ch: Char): Char =
        if (RedactionFold.isNeutralised(ch)) PiiScrubber.NEUTRALISED_CHAR else ch

}
