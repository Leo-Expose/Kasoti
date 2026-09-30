package dev.kasoti.android.field

import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate

/**
 * The one arithmetic `:core` does not expose, and why it lives here.
 *
 * `IsoDate` has `toEpochDay()` — Howard Hinnant's `days_from_civil` — but no inverse, and
 * `:core` is not a module this work owns. Everything the app needs from the other direction
 * is day *arithmetic*: "re-verify in 30 days" (`REVERIFY_DAYS`), "purge evidence older than 7
 * days" (NFR-P2), "expired on day N" for a message. Writing the inverse in six lines here
 * beats a round trip through `java.time` (which would make the field layer untestable
 * identically across targets for no benefit) and beats editing `:core`, which another owner
 * may be mid-change on.
 *
 * The implementation is Hinnant's `civil_from_days`, the exact inverse of the function
 * `:core` already uses, so the pair round-trips for the whole proleptic range. That is
 * asserted in `CalendarArithmeticTest`, including the negatives and the leap days — a
 * hand-rolled date function that is *nearly* right is worse than none.
 */
object CalendarArithmetic {

    /** `today + days`. Negative [days] moves backwards; used for retention cutoffs. */
    fun plusDays(date: IsoDate, days: Int): IsoDate = fromEpochDay(date.toEpochDay() + days)

    /**
     * `date` shifted by whole years, clamped to the last valid day of the target month.
     *
     * Clamping is what a human means by "re-verify a year after enrolment" when enrolment was
     * on 29 February: 28 February, not 1 March. Rolling forward would push the re-verify a day
     * past policy; rolling back would shorten the interval.
     */
    fun plusYears(date: IsoDate, years: Int): IsoDate {
        val targetYear = date.year + years
        val day = minOf(date.day, CalendarDate.daysInMonth(targetYear, date.month))
        return IsoDate(targetYear, date.month, day)
    }

    /** Whole days from [from] to [to]; negative when [to] is earlier. */
    fun daysBetween(from: IsoDate, to: IsoDate): Int = (to.toEpochDay() - from.toEpochDay()).toInt()

    /** Hinnant's `civil_from_days`; the exact inverse of `IsoDate.toEpochDay()`. */
    fun fromEpochDay(epochDay: Long): IsoDate {
        val z = epochDay + SHIFT
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return IsoDate((if (month <= 2) year + 1 else year).toInt(), month, day)
    }

    /** Epoch day 0 is 1970-01-01, which is 719_468 days after 0000-03-01. */
    private const val SHIFT = 719_468L
}
