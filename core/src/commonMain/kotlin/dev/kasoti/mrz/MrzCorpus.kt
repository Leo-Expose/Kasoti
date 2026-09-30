package dev.kasoti.mrz

import kotlin.random.Random

/** How a corpus case was derived from a valid document. */
enum class MrzMutation {
    /** Untouched valid document. */
    NONE,
    /** One character inside a check-protected data span, leaving the check digit stale. */
    DATA_CHAR,
    /** One character inside a check-digit position. */
    CHECK_DIGIT,
    /** A data character changed *and* its own check digit recomputed (the AT-02 attack). */
    RECOMPUTED_FIELD,
    /** A character of the name field changed. */
    NAME_CHAR,
    /** The composite check digit altered. */
    COMPOSITE_DIGIT,
    /** Line length / count broken so the zone no longer matches a known layout. */
    FORMAT_BREAK,
}

/**
 * One corpus row.
 *
 * [expectedCaught] is the *contract* for the gate: the harness asserts that
 * [MrzCorpus.detect] agrees with it. Cases with `expectedCaught = false` are not failures —
 * they are documented blind spots in ICAO 9303 that no MRZ arithmetic can close, and they
 * are exactly the substrate the other layers (QR signature, chip PA, macro, face) exist for.
 */
data class MrzCorpusCase(
    val id: String,
    val format: MrzFormat,
    val lines: List<String>,
    val mutation: MrzMutation,
    val expectedCaught: Boolean,
    val expectedFields: Set<MrzField>,
    /** Human-readable reason an [expectedCaught] `false` case is structurally blind. */
    val blindSpotReason: String? = null,
)

/**
 * Deterministic corpus generator for the `D-MRZ` dataset (EVAL.md §2, 10k rows).
 *
 * Two properties make the 100% catch gate meaningful rather than circular:
 *
 * 1. **Detectable mutants are provably detectable.** For [MrzMutation.DATA_CHAR] we pick the
 *    replacement character so the field's weighted sum provably changes (see
 *    [charWithDifferentCheckContribution]). No mutant can silently survive by arithmetic luck.
 * 2. **Undetectable mutants are labelled, not hidden.** TD1 has no composite check digit, and
 *    sex / nationality sit outside every check span in both formats, so a recomputed check
 *    digit over those fields is genuinely undetectable. Those rows ship with
 *    `expectedCaught = false` and are reported by the harness as coverage gaps.
 */
object MrzCorpus {

    private const val FILLER = '<'
    private val ALPHABET: List<Char> = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<".toList()
    private val WEIGHTS = intArrayOf(7, 3, 1)

    /**
     * @param count total rows; roughly one third are valid documents, the rest mutants.
     * @param referenceYear full year used to render and re-read two-digit dates.
     */
    fun generate(
        seed: Long,
        count: Int,
        referenceYear: Int,
    ): List<MrzCorpusCase> {
        val rnd = Random(seed)
        val cases = ArrayList<MrzCorpusCase>(count)
        repeat(count) { index ->
            val person = MrzPerson.sample(seed * 31 + index)
            val td3 = index % 2 == 0
            val valid = if (td3) MrzBuilder.buildTd3(person, referenceYear) else MrzBuilder.buildTd1(person, referenceYear)
            val format = if (td3) MrzFormat.TD3 else MrzFormat.TD1
            val mutation = mutationFor(rnd, format)

            cases += build(
                id = "mrz-%05d".format(index),
                format = format,
                valid = valid,
                mutation = mutation,
                rnd = rnd,
                referenceYear = referenceYear,
            )
        }
        return cases
    }

    private fun mutationFor(rnd: Random, format: MrzFormat): MrzMutation {
        // Every 4th case is clean, so precision on valid documents is measurable too.
        if (rnd.nextInt(4) == 0) return MrzMutation.NONE
        val pool = buildList {
            add(MrzMutation.DATA_CHAR)
            add(MrzMutation.CHECK_DIGIT)
            add(MrzMutation.NAME_CHAR)
            add(MrzMutation.FORMAT_BREAK)
            add(MrzMutation.RECOMPUTED_FIELD) // detectable in TD3, structurally blind in TD1
            if (format == MrzFormat.TD3) add(MrzMutation.COMPOSITE_DIGIT)
        }
        return pool[rnd.nextInt(pool.size)]
    }

    private fun build(
        id: String,
        format: MrzFormat,
        valid: List<String>,
        mutation: MrzMutation,
        rnd: Random,
        referenceYear: Int,
    ): MrzCorpusCase {
        if (mutation == MrzMutation.NONE) {
            return MrzCorpusCase(id, format, valid, mutation, expectedCaught = false, expectedFields = emptySet())
        }

        return when (mutation) {
            MrzMutation.DATA_CHAR -> dataCharMutation(id, format, valid, rnd)
            MrzMutation.CHECK_DIGIT -> checkDigitMutation(id, format, valid, rnd)
            MrzMutation.COMPOSITE_DIGIT -> compositeMutation(id, format, valid, rnd)
            MrzMutation.NAME_CHAR -> nameCharMutation(id, format, valid, rnd)
            MrzMutation.RECOMPUTED_FIELD -> recomputedFieldMutation(id, format, valid, rnd)
            MrzMutation.FORMAT_BREAK -> formatBreakMutation(id, format, valid, rnd)
            MrzMutation.NONE -> error("unreachable")
        }
    }

    // ---------------------------------------------------------------- mutations

    private fun dataCharMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        val target = protectedSpan(rnd, format)
        val pos = target.randomPosition(rnd)
        val replacement = charWithDifferentCheckContribution(valid[target.line][pos], pos - target.start, -1, rnd)
        val lines = valid.toMutableList()
        lines[target.line] = replaceAt(lines[target.line], pos, replacement)
        return MrzCorpusCase(id, format, lines, MrzMutation.DATA_CHAR, true, setOf(target.field))
    }

    private fun checkDigitMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        val target = protectedSpan(rnd, format)
        val checkPos = target.checkIndex
        val lines = valid.toMutableList()
        lines[target.line] = replaceAt(lines[target.line], checkPos, differentChar(lines[target.line][checkPos], rnd))
        return MrzCorpusCase(id, format, lines, MrzMutation.CHECK_DIGIT, true, setOf(target.field))
    }

    private fun compositeMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        require(format == MrzFormat.TD3) { "TD1 has no composite check digit" }
        val lines = valid.toMutableList()
        val pos = 43
        lines[0] = replaceAt(lines[0], pos, differentChar(lines[0][pos], rnd))
        return MrzCorpusCase(id, format, lines, MrzMutation.COMPOSITE_DIGIT, true, setOf(MrzField.COMPOSITE))
    }

    private fun nameCharMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        // Line 2 of both layouts is the name. In TD3 the composite spans all 44 characters of
        // line 2; in TD1 the name's own check digit covers positions 1-29.
        val nameLen = if (format == MrzFormat.TD3) 44 else 29
        val pos = rnd.nextInt(nameLen)
        // Line 2 begins at composite offset 39, and 39 % 3 == 0, so the composite weight
        // index coincides with the position inside line 2 for both layouts.
        val replacement = charWithDifferentCheckContribution(valid[1][pos], pos, pos, rnd)
        val lines = valid.toMutableList()
        lines[1] = replaceAt(lines[1], pos, replacement)
        return MrzCorpusCase(id, format, lines, MrzMutation.NAME_CHAR, true, setOf(MrzField.NAME))
    }

    /**
     * The AT-02 attack: alter the data, then recompute that field's own check digit.
     *
     * Catching this requires care. A character change provably breaks whichever single check
     * digit guards it, but the TD3 composite spans *both* the data and the field's check
     * digit, and their weighted deltas can cancel modulo 10 — changing a data character and
     * recomputing its check digit can leave the composite bit-for-bit identical. So rather
     * than reasoning about weights, we construct the full mutant and reject any candidate
     * whose composite is unchanged.
     *
     * TD1 has no composite, so a recomputed field check is genuinely undetectable there; that
     * row is emitted with `expectedCaught = false` and the reason recorded.
     */
    private fun recomputedFieldMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        // Date-bearing spans are excluded on purpose. Editing a date and recomputing its check
        // digit can also be caught by *calendar validity* (an impossible month or day), which
        // is a different mechanism from check-digit arithmetic and is covered by the `checks`
        // package. Restricting the attack to non-date spans keeps this corpus row measuring
        // exactly one thing: what the check-digit scheme alone can and cannot do.
        val target = protectedSpan(rnd, format, excludeDates = true)
        val pos = target.randomPosition(rnd)
        val originalLine = valid[target.line]
        val originalChar = originalLine[pos]
        val hasComposite = format == MrzFormat.TD3
        val originalComposite = td3Composite(valid, format)

        var chosenLine: String? = null
        for (ch in ALPHABET.filter { it != originalChar }.shuffled(rnd)) {
            val mutatedData = replaceAt(originalLine, pos, ch)
            val newCheck = MrzCheckDigit.compute(mutatedData.substring(target.start, target.checkIndex)) ?: continue
            val candidateLine = replaceAt(mutatedData, target.checkIndex, newCheck)
            if (candidateLine == originalLine) continue
            if (hasComposite) {
                // Only accept a candidate the composite genuinely catches, so `expectedCaught`
                // means the attack was demonstrated rather than asserted.
                val trial = valid.toMutableList().also { it[target.line] = candidateLine }
                if (td3Composite(trial, format) == originalComposite) continue
            }
            chosenLine = candidateLine
            break
        }

        val lines = valid.toMutableList()
        lines[target.line] = chosenLine ?: originalLine

        return MrzCorpusCase(
            id = id,
            format = format,
            lines = lines,
            mutation = MrzMutation.RECOMPUTED_FIELD,
            expectedCaught = hasComposite && chosenLine != null,
            expectedFields = setOf(MrzField.COMPOSITE),
            blindSpotReason = when {
                !hasComposite -> "TD1 has no composite check digit: a recomputed field check digit is not detectable by MRZ arithmetic"
                chosenLine == null -> "every replacement character left the composite unchanged at this offset (structurally blind)"
                else -> null
            },
        )
    }

    /**
     * Composite check digit of a TD3 line pair.
     *
     * Returns `null` for TD1, which has no composite. Note that even in TD3 the composite is
     * not a complete defence against AT-02: the composite delta for a data edit at offset `p`
     * is `d*w_composite(p) + (d*w_field(p))*w_check` mod 10, and for a handful of offsets that
     * coefficient is identically zero. Editing the data and recomputing the field's check digit
     * then leaves the composite bit-for-bit identical. Those offsets are real properties of
     * ICAO 9303, are measured by the corpus, and are reported rather than hidden.
     */
    private fun td3Composite(lines: List<String>, format: MrzFormat): Char? {
        if (format != MrzFormat.TD3 || lines.size < 2) return null
        val l1 = lines[0]
        if (l1.length < 43) return null
        return MrzCheckDigit.computeComposite(
            l1.substring(0, 10),
            l1.substring(13, 20),
            l1.substring(21, 43),
            lines[1],
        )
    }

    private fun formatBreakMutation(id: String, format: MrzFormat, valid: List<String>, rnd: Random): MrzCorpusCase {
        val lines = when (rnd.nextInt(3)) {
            0 -> valid.dropLast(1) // too few lines
            1 -> valid.toMutableList().also {
                val l = rnd.nextInt(it.size)
                it[l] = it[l].dropLast(1) // wrong length
            }.toList()
            else -> valid.toMutableList().also { it[0] = it[0] + "9" }.toList() // too long
        }
        return MrzCorpusCase(id, format, lines, MrzMutation.FORMAT_BREAK, true, emptySet())
    }

    // ---------------------------------------------------------------- span table

    private data class Span(
        val line: Int,
        val start: Int,
        val endExclusive: Int,
        val checkIndex: Int,
        val field: MrzField,
        /** True for date fields, whose edits are also constrained by calendar validity. */
        val isDate: Boolean = false,
    ) {
        fun randomPosition(rnd: Random): Int = start + rnd.nextInt((endExclusive - start).coerceAtLeast(1))
    }

    /** Spans protected by a check digit, in the format's own coordinates. */
    private fun protectedSpans(format: MrzFormat): List<Span> = when (format) {
        MrzFormat.TD3 -> listOf(
            Span(0, 0, 9, 9, MrzField.DOCUMENT_NUMBER),
            Span(0, 13, 19, 19, MrzField.BIRTH_DATE, isDate = true),
            Span(0, 21, 27, 27, MrzField.EXPIRY_DATE, isDate = true),
            Span(0, 28, 42, 42, MrzField.PERSONAL_NUMBER),
        )
        MrzFormat.TD1 -> listOf(
            Span(0, 0, 9, 9, MrzField.DOCUMENT_NUMBER),
            Span(0, 13, 19, 19, MrzField.BIRTH_DATE, isDate = true),
            Span(0, 21, 29, 29, MrzField.OPTIONAL_DATA),
            Span(2, 0, 6, 6, MrzField.OPTIONAL_DATA),
            Span(2, 7, 13, 13, MrzField.EXPIRY_DATE, isDate = true),
            Span(2, 14, 29, 29, MrzField.PERSONAL_NUMBER),
        )
        MrzFormat.UNKNOWN -> emptyList()
    }

    private fun protectedSpan(rnd: Random, format: MrzFormat, excludeDates: Boolean = false): Span {
        val spans = protectedSpans(format).let { if (excludeDates) it.filterNot { s -> s.isDate } else it }
        return spans[rnd.nextInt(spans.size)]
    }

    /**
     * Pick a replacement character that is guaranteed to change the named check digit(s).
     *
     * A mutation is invisible to a weight-`w` check only when `delta * w ≡ 0 (mod 10)`; e.g.
     * swapping `1` for `11` in a weight-1 position. Every such candidate is rejected, so no
     * corpus row can pass by arithmetic luck and inflate the catch rate.
     *
     * @param compositeWeightIndex weight index inside the TD3 composite, or `-1` to skip that
     *   requirement (TD1 has no composite, and plain data mutations only need the field check).
     */
    private fun charWithDifferentCheckContribution(
        original: Char,
        fieldWeightIndex: Int,
        compositeWeightIndex: Int,
        rnd: Random,
    ): Char {
        val oldValue = MrzCheckDigit.value(original) ?: 0
        val fieldWeight = WEIGHTS[fieldWeightIndex % WEIGHTS.size]
        val compositeWeight = if (compositeWeightIndex < 0) null else WEIGHTS[compositeWeightIndex % WEIGHTS.size]
        val candidates = ALPHABET.filter { ch ->
            val newValue = MrzCheckDigit.value(ch) ?: return@filter false
            val delta = newValue - oldValue
            (delta * fieldWeight) % 10 != 0 && (compositeWeight == null || (delta * compositeWeight) % 10 != 0)
        }
        return candidates[rnd.nextInt(candidates.size)]
    }

    /** Any character other than [original], used for check-digit and composite positions. */
    private fun differentChar(original: Char, rnd: Random): Char {
        val candidates = ALPHABET.filter { it != original }
        return candidates[rnd.nextInt(candidates.size)]
    }

    private fun replaceAt(line: String, index: Int, ch: Char): String =
        line.substring(0, index) + ch + line.substring(index + 1)

    // ---------------------------------------------------------------- evaluation

    /** Did the system catch this case? Any failed check, structural error, or unusable shape counts. */
    fun detect(result: MrzResult): Boolean =
        !result.allChecksPassed || result.structuralErrors.isNotEmpty() || result.format == MrzFormat.UNKNOWN

    /** Which fields actually failed, for attribution reporting. */
    fun detectedFields(result: MrzResult): Set<MrzField> = result.failedChecks.map { it.field }.toSet()
}
