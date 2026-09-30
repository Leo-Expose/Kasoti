package dev.kasoti.checks

import dev.kasoti.fusion.DriftScore
import dev.kasoti.time.CalendarDate

/**
 * Visual-inspection-zone vs machine-readable-zone comparison (FR-M4, A-VIZ-01).
 *
 * The MRZ is the trustworthy rendering: it is printed by the issuing authority, is
 * machine-checked, and is not what a forger edits first. The VIZ is what a human reads and
 * what a photo-swap or print-and-reprint attack actually manipulates. A disagreement between
 * them is therefore evidence *about the VIZ*, and is scored as a drift rather than a
 * pass/fail so the fusion layer can weigh it per field.
 *
 * Names are compared after normalisation because "SHARMA, RAMESH K." and
 * "SHARMA<<RAMESH K" are the same person; dates are compared as calendar dates, not strings.
 */
object VizMrzMatch {

    /**
     * @return 1.0 for identical after normalisation, 0.0 for no overlap.
     */
    fun nameScore(viz: String, mrz: String): Float {
        val a = normaliseName(viz)
        val b = normaliseName(mrz)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f

        // Compare token sets: word order and filler separators are not identity-bearing,
        // but a genuinely different name is.
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val shared = ta.intersect(tb).size
        // Reward partial overlap, but a surname mismatch should never read as a match.
        return shared.toFloat() / (ta.union(tb).size)
    }

    /**
     * Date comparison. `99999999` and other all-nines sentinels are treated as "not printed"
     * rather than as a real date, matching how print omission is actually encoded.
     */
    fun dateScore(viz: String, mrz: String, referenceYear: Int): Float {
        val a = normaliseDate(viz, referenceYear) ?: return 0f
        val b = normaliseDate(mrz, referenceYear) ?: return 0f
        return if (a == b) 1f else 0f
    }

    /** Document numbers: digits and letters only, case-folded. */
    fun numberScore(viz: String, mrz: String): Float {
        val a = viz.filter { it.isLetterOrDigit() }.uppercase()
        val b = mrz.filter { it.isLetterOrDigit() }.uppercase()
        if (a.isEmpty() || b.isEmpty()) return 0f
        return if (a == b) 1f else 0f
    }

    fun drift(field: String, score: Float, left: String, right: String): DriftScore =
        DriftScore(field = field, score = score, left = left, right = right)

    /** Uppercase, strip everything that is not a letter or digit, collapse to single spaces. */
    fun normaliseName(raw: String): String = raw
        .uppercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    /**
     * Accepts `YYYY-MM-DD`, `DD/MM/YYYY`, `DD-MM-YYYY` and the MRZ `YYMMDD`.
     *
     * @param referenceYear full year used to resolve a bare `YYMMDD`. `:core` never reads a
     *   device clock (AGENTS.md §5), so the caller supplies it.
     * @return canonical `YYYY-MM-DD`, or `null` when unparseable or an "omitted" sentinel.
     */
    fun normaliseDate(raw: String, referenceYear: Int): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.all { it == '9' } || v.all { it == '0' }) return null

        if (v.length == 6 && v.all { it.isDigit() }) {
            return CalendarDate.parseYymmdd(v, referenceYear)?.toIsoString()
        }

        val parts = when {
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(v) -> v.split("-")
            Regex("\\d{2}[/-]\\d{2}[/-]\\d{4}").matches(v) -> {
                val s = v.replace('/', '-').split('-')
                listOf(s[2], s[1], s[0])
            }
            else -> return null
        }
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return CalendarDate.of(y, m, d)?.toIsoString()
    }
}
