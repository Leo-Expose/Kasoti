package dev.kasoti.checks

/**
 * Damm–Verhoeff checksum: the trailing check digit of the Indian Aadhaar 12-digit identifier.
 *
 * Aadhaar numbers are checksummed with Verhoeff by the Indian payments system. NPCI circular
 * NPCI/2013-14/NACH/Circular 9, "Implementation of Verhoeff Algorithm by banks for Aadhaar
 * related applications" (9 July 2013), states that the "last digit of Aadhaar number is a
 * checksum" and sends implementers to the algorithm's table form, which is the
 * [published definition](https://en.wikipedia.org/wiki/Verhoeff_algorithm) this follows.
 *
 * The algorithm, in that table form:
 *  1. read the digits right to left, the rightmost carrying index `0`;
 *  2. starting from `c = 0`, replace `c` with `D[c][P[i mod 8][digit]]` at each index `i`;
 *  3. a number is valid exactly when the walk finishes at `c == 0`;
 *  4. to *generate* a check digit, append a `0`, run the same walk, and return `INV[c]`.
 *
 * `D` is the Cayley table of the dihedral group D5, `P` is one generator permutation applied
 * iteratively, and `INV` is the multiplicative inverse table. None of the three is transcribed
 * here: each is generated from the definition that produces it, so there is no 100-entry table
 * in this file that can be mistyped. [init] re-derives the invariants a corrupted table would
 * break, so a bad generator fails at class-load rather than quietly mis-verifying a real
 * Aadhaar. The generated tables themselves are pinned against the published ones in `ChecksTest`.
 */
object Verhoeff {

    /** Digits in a table row — also the number of symbols the tables permute. */
    private const val ALPHABET = 10

    /**
     * Order of the rotation generator `r` in D5. Doubles as the size of the rotation half of the
     * digit alphabet, because the encoding below is `digit = r^a s^b` written `a + ROTATION_ORDER * b`.
     */
    private const val ROTATION_ORDER = 5

    /** Order of the reflection generator `s`: a reflection exponent is taken mod this. */
    private const val REFLECTION_ORDER = 2

    /** Rows in the position-permutation table; the generator closes after exactly this many. */
    private const val PERMUTATION_PERIOD = 8

    /** The digit value zero, used both as the walk's starting state and as the appended digit. */
    private const val ZERO_DIGIT = 0

    /** Length of an Aadhaar number, check digit included. */
    private const val AADHAAR_LENGTH = 12

    /**
     * The single permutation the position table is generated from: `(1 5 8 9 4 2 7 0)(3 6)` —
     * one 8-cycle and one 2-cycle, of order [PERMUTATION_PERIOD]. This is the whole of the
     * published `p` table; the other seven rows are its powers.
     */
    private val GENERATOR_CYCLES = arrayOf(
        intArrayOf(1, 5, 8, 9, 4, 2, 7, 0),
        intArrayOf(3, 6),
    )

    private val IDENTITY_ROW = IntArray(ALPHABET) { it }

    private val GENERATOR: IntArray = cyclePermutation(GENERATOR_CYCLES)

    /** `D[x][y]` is the digit for `m(x) · m(y)`, the product of the two D5 elements. */
    private val D: Array<IntArray> = Array(ALPHABET) { x ->
        IntArray(ALPHABET) { y -> dihedralProduct(x, y) }
    }

    /** `P[i]` is the `i`-th power of [GENERATOR]: the permutation applied at position `i mod 8`. */
    private val P: Array<IntArray> = buildPositionPermutations()

    /** `INV[c]` is the digit `k` with `D[c][k] == 0` — the multiplicative inverse of `c`. */
    private val INV: IntArray = IntArray(ALPHABET) { c ->
        IntArray(ALPHABET).indices.single { D[c][it] == ZERO_DIGIT }
    }

    init {
        for ((index, row) in (D + P).withIndex()) {
            check(row.sortedArray().contentEquals(IDENTITY_ROW)) {
                "Verhoeff table row $index must permute 0..9, found ${row.toList()}"
            }
        }
        val order = orderOf(GENERATOR)
        check(order == PERMUTATION_PERIOD) {
            "the position permutation must close after $PERMUTATION_PERIOD rows, order is $order"
        }
        for (c in 0 until ALPHABET) {
            val inverses = IntArray(ALPHABET).indices.filter { D[c][it] == ZERO_DIGIT }
            check(inverses.single() == INV[c]) {
                "INV[$c] must be the unique digit d with D[$c][d] == 0, got ${inverses.toList()}"
            }
        }
    }

    /**
     * The running checksum state of [digits]: the walk of step 2 above, right to left with `i`
     * counting from `0` at the rightmost digit. [rowOffset] shifts every position by that many
     * `P` rows, which is how [checkDigit] avoids materialising an appended digit.
     *
     * The `P` row is what makes a digit's *position* matter; two numbers built from the same
     * digits in a different order generally do not share a state. Dropping `P` leaves a
     * one-table walk that is a different function, not a faster one.
     *
     * @return the final `c`, or `null` if [digits] is empty or is not all digits.
     */
    private fun state(digits: String, rowOffset: Int = 0): Int? {
        if (digits.isEmpty()) return null
        var c = ZERO_DIGIT
        for ((i, index) in digits.indices.reversed().withIndex()) {
            val digit = digits[index] - '0'
            if (digit !in ZERO_DIGIT..ALPHABET - 1) return null
            c = D[c][P[(i + rowOffset) % PERMUTATION_PERIOD][digit]]
        }
        return c
    }

    /**
     * Checksum of a digit string.
     *
     * Step 4 above is "append a 0, perform the calculation, the check digit is `INV[c]`". The
     * append lands on the *right*, so the appended `0` takes position `0` and every digit of
     * [digits] moves up one `P` row — hence [rowOffset] of 1. The appended digit's own
     * contribution is then `D[c][P[0][0]]` onto a state that is still `0`, i.e. `D[0][0] == 0`,
     * a genuine no-op; so the shift is the whole of the effect. Reading the `0` as a trailing
     * digit instead would select `P[digits.length mod 8]` and return a different digit.
     *
     * @return the Verhoeff check digit, or `null` if [digits] is empty or not all digits.
     */
    fun checkDigit(digits: String): Int? = state(digits, rowOffset = 1)?.let { INV[it] }

    /**
     * True iff [aadhaar] is 12 digits and its trailing check digit is correct.
     *
     * This uses step 3 — the full number's state being zero — rather than re-deriving the digit
     * and comparing it to the last character. Both are the published criterion, and using a
     * different one here means a fault in [checkDigit] cannot hide behind this validator.
     */
    fun isValid(aadhaar: String): Boolean =
        aadhaar.length == AADHAAR_LENGTH && state(aadhaar) == ZERO_DIGIT

    /** Appends the correct check digit to an 11-digit body. */
    fun appendCheckDigit(body: String): String? = checkDigit(body)?.let { body + it }

    /** The three generated tables, for a test to pin them to the published ones. */
    internal data class Tables(val d: List<String>, val p: List<String>, val inv: String)

    /**
     * Renders the generated tables as rows of digit characters, in the layout the specification
     * prints them. `internal` because it exists solely for that test: the tables themselves are
     * an implementation detail and no product code should read them.
     */
    internal fun tables(): Tables = Tables(
        d = D.map { it.joinToString("") },
        p = P.map { it.joinToString("") },
        inv = INV.joinToString(""),
    )

    /**
     * Product in the dihedral group D5: `r^a s^b · r^c s^d = r^(a + (-1)^b c) s^((b + d) mod 2)`.
     * A reflection reverses the sense of the rotation it multiplies, which is why `D` is not
     * symmetric and why the order of the walk is not free.
     */
    private fun dihedralProduct(x: Int, y: Int): Int {
        val a = x % ROTATION_ORDER
        val b = x / ROTATION_ORDER
        val c = y % ROTATION_ORDER
        val d = y / ROTATION_ORDER
        val rotation = (if (b == 0) a + c else a - c) % ROTATION_ORDER
        val reflection = (b + d) % REFLECTION_ORDER
        // Kotlin's `%` keeps the sign of the dividend, so the rotation needs re-basing to 0..4.
        return (rotation + ROTATION_ORDER) % ROTATION_ORDER + reflection * ROTATION_ORDER
    }

    /**
     * The position table: `P[0]` is the identity and `P[i]` the `i`-th power of [GENERATOR],
     * built by the published rule `p(i + j, n) = p(i, p(j, n))`.
     */
    private fun buildPositionPermutations(): Array<IntArray> {
        val rows = Array(PERMUTATION_PERIOD) { IDENTITY_ROW.copyOf() }
        for (row in 1 until PERMUTATION_PERIOD) {
            val previous = rows[row - 1]
            rows[row] = IntArray(ALPHABET) { GENERATOR[previous[it]] }
        }
        return rows
    }

    /** One-line notation of the permutation given as disjoint cycles. */
    private fun cyclePermutation(cycles: Array<IntArray>): IntArray {
        val permutation = IDENTITY_ROW.copyOf()
        for (cycle in cycles) {
            for (i in cycle.indices) {
                permutation[cycle[i]] = cycle[(i + 1) % cycle.size]
            }
        }
        return permutation
    }

    /** The smallest `k > 0` for which `permutation^k` is the identity. */
    private fun orderOf(permutation: IntArray): Int {
        var power = IDENTITY_ROW.copyOf()
        for (order in 1..ALPHABET) {
            power = IntArray(ALPHABET) { permutation[power[it]] }
            if (power.contentEquals(IDENTITY_ROW)) return order
        }
        error("a permutation of $ALPHABET symbols has order at most $ALPHABET")
    }
}
