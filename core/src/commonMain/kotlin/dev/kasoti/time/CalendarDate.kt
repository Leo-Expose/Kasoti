package dev.kasoti.time

/**
 * Proleptic-Gregorian calendar arithmetic with no dependencies and no platform calls.
 *
 * Deliberately hand-rolled rather than delegated to `java.time`: `:core` is shared
 * across JVM, Android and (later) other targets, and the date rules here are fixed by
 * ISO 8601 / ICAO 9303 — not by whichever JDK ships on the device. A device with a
 * skewed clock still gets correct calendar validation (AGENTS.md §2: skew is a flagged
 * concern, not a correctness input).
 */

data class IsoDate(val year: Int, val month: Int, val day: Int) : Comparable<IsoDate> {

    override fun compareTo(other: IsoDate): Int =
        compareValuesBy(this, other, IsoDate::year, IsoDate::month, IsoDate::day)

    /** `YYYY-MM-DD` — the only date wire format KASOTI emits (AGENTS.md §2). */
    fun toIsoString(): String = "%04d-%02d-%02d".format(year, month, day)

    fun toEpochDay(): Long {
        // Howard Hinnant's days_from_civil, valid for the full proleptic range.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe - 719_468L
    }

    override fun toString(): String = toIsoString()

    companion object {
        fun parse(text: String): IsoDate? {
            if (text.length != 10) return null
            if (text[4] != '-' || text[7] != '-') return null
            val y = text.substring(0, 4).toIntOrNull() ?: return null
            val m = text.substring(5, 7).toIntOrNull() ?: return null
            val d = text.substring(8, 10).toIntOrNull() ?: return null
            return CalendarDate.of(y, m, d)
        }
    }
}

object CalendarDate {

    fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

    /** Construct only if the date exists in the calendar. */
    fun of(year: Int, month: Int, day: Int): IsoDate? =
        if (month in 1..12 && day in 1..daysInMonth(year, month)) IsoDate(year, month, day) else null

    /**
     * Parse a 6-digit MRZ `YYMMDD` into a full date.
     *
     * MRZ carries two digits of year, so a century rule is unavoidable. [referenceYear] is
     * the full year the two-digit value should be read against (the "today" supplied by the
     * caller — never read from the device clock inside `:core`).
     *
     * The window rule is the ICAO/ISO one: values more than [slackYears] ahead of the
     * reference are read as the previous century, otherwise the current one. A birth date
     * of `05` in 2026 is 2005, not 1905; an expiry of `30` in 2026 is 2030, not 1930.
     */
    fun parseYymmdd(
        text: String,
        referenceYear: Int,
        slackYears: Int = 15,
    ): IsoDate? {
        if (text.length != 6) return null
        if (!text.all { it in '0'..'9' }) return null
        val yy = text.substring(0, 2).toInt()
        val mm = text.substring(2, 4).toInt()
        val dd = text.substring(4, 6).toInt()
        if (mm !in 1..12 || dd < 1 || dd > 31) return null

        val refYy = referenceYear % 100
        val candidateCurrent = referenceYear / 100 * 100 + yy
        val candidatePrevious = candidateCurrent - 100
        val year = if (candidateCurrent > referenceYear + slackYears) candidatePrevious else candidateCurrent
        return of(year, mm, dd)
    }

    /** Whole years between two dates, floored at zero. Used for expiry and age logic. */
    fun yearsBetween(from: IsoDate, to: IsoDate): Int {
        var years = to.year - from.year
        if (to.month < from.month || (to.month == from.month && to.day < from.day)) years--
        return if (years < 0) 0 else years
    }
}
