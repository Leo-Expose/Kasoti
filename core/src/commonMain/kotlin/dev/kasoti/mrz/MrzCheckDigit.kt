package dev.kasoti.mrz

/**
 * ICAO 9303 check-digit arithmetic.
 *
 * Values: `0`-`9` -> 0..9, `A`-`Z` -> 10..35, `<` (fill) -> 0.
 * Weights: repeating `7, 3, 1`, left to right. Modulo 10.
 *
 * Fail-closed by design (DESIGN.md §5): any character outside the MRZ alphabet makes
 * [compute] return `null` and [verify] return `false`. We never fuzzy-accept a failed
 * check digit, so an unparseable glyph is a failure, not a pass.
 */
object MrzCheckDigit {

    private const val FILLER = '<'
    private val WEIGHTS = intArrayOf(7, 3, 1)
    /** Numeric value of an MRZ character, or `null` if it is not in the MRZ alphabet. */
    fun value(ch: Char): Int? = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'A'..'Z' -> ch - 'A' + 10
        FILLER -> 0
        else -> null
    }

    /**
     * Compute the check digit for [field].
     *
     * @return the digit character, or `null` if [field] contains a non-MRZ character.
     */
    fun compute(field: String): Char? {
        var sum = 0
        for ((index, ch) in field.withIndex()) {
            val v = value(ch) ?: return null
            sum += v * WEIGHTS[index % WEIGHTS.size]
        }
        return '0' + (sum % 10)
    }

    /** True iff [given] is the correct check digit for [field]. */
    fun verify(field: String, given: Char): Boolean {
        val expected = compute(field) ?: return false
        return expected == given
    }

    /**
     * Compute the composite check digit over several spans of MRZ text.
     * Used for the TD3/TD1 final composite check (position 44 / position 30).
     */
    fun computeComposite(vararg spans: String): Char? {
        val joined = spans.joinToString(separator = "")
        return compute(joined)
    }
}
