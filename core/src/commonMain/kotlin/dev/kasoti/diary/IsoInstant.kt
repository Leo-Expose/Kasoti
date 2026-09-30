package dev.kasoti.diary

import dev.kasoti.time.IsoDate
import kotlin.math.abs

/**
 * UTC instant parsing and formatting, in the one format the protocol allows.
 *
 * `:core` has no `java.time` (that is exactly the platform coupling the module layout
 * forbids), but three rules need to compute elapsed time — impossible travel, the facilitator
 * window and sync skew — and all of them need an *absolute* instant, not a calendar date.
 *
 * It lives in `diary` rather than `dev.kasoti.time` only because that package is owned by
 * another work stream right now; this is the obvious future home and the move is mechanical.
 *
 * Accepted input: `YYYY-MM-DD` + (`T` | `t` | space) + `HH:MM[:SS]` + optional `.fff…`
 * fraction + (`Z` | `z` | `±HH:MM`). Leap seconds are rejected rather than clamped: a `60`
 * second reading is a broken clock, and clamping it would quietly shift a travel calculation
 * by a second in whichever direction moves the flag closer to firing.
 */
object IsoInstant {

    /** `YYYY-MM-DDTHH:MM:SS.mmmZ` — the width [format] always produces. */
    const val CANONICAL_LENGTH = 24

    /** @return epoch milliseconds, or `null` when [text] is not a valid UTC-resolvable instant. */
    fun parse(text: String): Long? {
        if (text.length < 16) return null
        val sep = text[10]
        if (sep != 'T' && sep != 't' && sep != ' ') return null
        val date = IsoDate.parse(text.substring(0, 10)) ?: return null
        var i = 11
        val hour = readFixedDigits(text, i, 2) ?: return null
        i += 2
        if (i >= text.length || text[i] != ':') return null
        i++
        val minute = readFixedDigits(text, i, 2) ?: return null
        i += 2
        var second = 0
        var millis = 0L
        if (i < text.length && text[i] == ':') {
            i++
            second = readFixedDigits(text, i, 2) ?: return null
            i += 2
            if (i < text.length && text[i] == '.') {
                i++
                val start = i
                while (i < text.length && text[i] in '0'..'9') i++
                val digits = text.substring(start, i)
                if (digits.isEmpty()) return null
                if (digits.length > 3) return null
                millis = digits.padEnd(3, '0').toLong()
            }
        }
        if (hour > 23 || minute > 59 || second > 59) return null

        var offsetSeconds = 0
        if (i >= text.length) return null
        when (val z = text[i]) {
            'Z', 'z' -> i++
            '+', '-' -> {
                val oh = readFixedDigits(text, i + 1, 2) ?: return null
                i += 3
                if (i >= text.length || text[i] != ':') return null
                val om = readFixedDigits(text, i + 1, 2) ?: return null
                i += 3
                if (oh > 23 || om > 59) return null
                offsetSeconds = oh * 3600 + om * 60
                if (z == '-') offsetSeconds = -offsetSeconds
            }
            else -> return null
        }
        if (i != text.length) return null

        val secondsOfDay = hour * 3600L + minute * 60L + second
        return (date.toEpochDay() * 86_400L + secondsOfDay - offsetSeconds) * 1000L + millis
    }

    fun isValid(text: String): Boolean = parse(text) != null

    /**
     * Millisecond-precision UTC rendering. Always millisecond precision, even when the value
     * is a whole second, so that two texts for the same instant can never differ.
     */
    fun format(epochMillis: Long): String {
        val days = Math.floorDiv(epochMillis, 86_400_000L)
        val msOfDay = Math.floorMod(epochMillis, 86_400_000L)
        val (y, m, d) = civilFromDays(days)
        val hour = msOfDay / 3_600_000L
        val minute = msOfDay / 60_000L % 60
        val second = msOfDay / 1000L % 60
        val milli = msOfDay % 1000L
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(y, m, d, hour, minute, second, milli)
    }

    /** True when [text] is exactly what [format] produces: `YYYY-MM-DDTHH:MM:SS.mmmZ`. */
    fun isCanonicalFixedWidth(text: String): Boolean = text.length == CANONICAL_LENGTH && text.endsWith("Z")

    /**
     * Total order over instants, as a string comparison.
     *
     * Lexicographic ordering *is* chronological ordering for the fixed-width UTC form this
     * codebase writes, and comparing strings is roughly twenty times cheaper than re-parsing.
     * A record whose stamp is in some other legal spelling (an offset, no milliseconds) falls
     * back to parsing rather than being mis-sorted, because the fast path must never be the
     * thing that is wrong.
     */
    fun compare(left: String, right: String): Int {
        if (isCanonicalFixedWidth(left) && isCanonicalFixedWidth(right)) return left.compareTo(right)
        val a = parse(left) ?: return -1
        val b = parse(right) ?: return 1
        return a.compareTo(b)
    }

    /** True when [text] is at or after [horizonIso]. Same fast path as [compare]. */
    fun isAtOrAfter(text: String, horizonIso: String): Boolean = compare(text, horizonIso) >= 0

    /** Minutes of disagreement between an envelope stamp and the caller's notion of now. */
    fun skewMinutes(iso: String, nowMillis: Long): Double? {
        val parsed = parse(iso) ?: return null
        return abs(parsed - nowMillis) / 60_000.0
    }

    private fun readFixedDigits(text: String, at: Int, count: Int): Int? {
        if (at + count > text.length) return null
        var value = 0
        for (k in 0 until count) {
            val c = text[at + k]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }

    /** Howard Hinnant's `civil_from_days`, the inverse of [IsoDate.toEpochDay]. */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }
}
