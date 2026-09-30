package dev.kasoti.checks

import dev.kasoti.fusion.FindingCode
import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate

/**
 * Outcome of a format / date check (FR-M3).
 *
 * A check never throws and never silently passes: when a value cannot be evaluated the
 * result is [FormatVerdict.UNKNOWN] carrying a reason, so fusion can decide whether that
 * is a GREY (can't read it) or an AMBER (read it, doesn't add up).
 */
data class CheckOutcome(
    val verdict: FormatVerdict,
    val reason: String = "",
    val findings: List<FindingCode> = emptyList(),
)

enum class FormatVerdict {
    /** Structurally valid and consistent. */
    OK,

    /** Readable but wrong: bad check digit, expired, impossible date. */
    FAIL,

    /** Cannot evaluate: absent, too short, unreadable. */
    UNKNOWN,
}

/** Date rules that gate a screening (R-MATH-02). */
object DateLogic {

    /**
     * @param today supplied by the caller — `:core` never reads a device clock for a security
     *   decision (AGENTS.md §5). Skew is surfaced separately at sync time (SYNC.md §2).
     */
    fun evaluate(
        birthDate: IsoDate?,
        expiryDate: IsoDate?,
        issueDate: IsoDate? = null,
        today: IsoDate,
    ): List<CheckOutcome> {
        val out = mutableListOf<CheckOutcome>()

        if (birthDate != null && birthDate >= today) {
            out += CheckOutcome(
                FormatVerdict.FAIL,
                "date of birth ${birthDate.toIsoString()} is not in the past",
                listOf(FindingCode.R_MATH_02),
            )
        }

        if (expiryDate != null) {
            if (expiryDate < today) {
                out += CheckOutcome(
                    FormatVerdict.FAIL,
                    "document expired on ${expiryDate.toIsoString()}",
                    listOf(FindingCode.R_MATH_02),
                )
            }
            if (issueDate != null && issueDate > expiryDate) {
                out += CheckOutcome(
                    FormatVerdict.FAIL,
                    "issue date ${issueDate.toIsoString()} is after expiry ${expiryDate.toIsoString()}",
                    listOf(FindingCode.R_MATH_02),
                )
            }
        }

        // A holder cannot be older than 130; a "born" date further back is a data error,
        // not a real person, and silently accepting it would let a forger pad the field.
        birthDate?.let {
            val age = CalendarDate.yearsBetween(it, today)
            if (age > MAX_PLAUSIBLE_AGE) {
                out += CheckOutcome(
                    FormatVerdict.FAIL,
                    "implied age $age exceeds the plausible maximum of $MAX_PLAUSIBLE_AGE",
                    listOf(FindingCode.R_MATH_02),
                )
            }
        }

        return out
    }

    const val MAX_PLAUSIBLE_AGE = 130
}

/** Structural validators for the domestic document tracks (FR-M3). */
object FormatValidators {

    /** Voter identity cards: 3 letters then 8 digits, or the older 3+7 form. */
    fun validateVoterId(value: String): CheckOutcome {
        val v = value.uppercase().trim()
        if (v.isEmpty()) return CheckOutcome(FormatVerdict.UNKNOWN, "no voter id supplied")
        val letters = v.takeWhile { it in 'A'..'Z' }
        val digits = v.substring(letters.length)
        return when {
            letters.length != 3 -> CheckOutcome(FormatVerdict.FAIL, "voter id must start with 3 letters")
            digits.length != 8 && digits.length != 7 ->
                CheckOutcome(FormatVerdict.FAIL, "voter id must have 7 or 8 digits, got ${digits.length}")
            else -> CheckOutcome(FormatVerdict.OK)
        }
    }

    /**
     * Driving licences: format-only. A real licence has no public check digit we can compute
     * offline, so this validates shape and says nothing more — claiming otherwise would be
     * the kind of overclaim the whole eval protocol exists to prevent.
     */
    fun validateDl(value: String): CheckOutcome {
        val v = value.uppercase().replace(Regex("[\\s-]"), "")
        if (v.isEmpty()) return CheckOutcome(FormatVerdict.UNKNOWN, "no driving licence supplied")
        if (v.length !in 8..20) {
            return CheckOutcome(FormatVerdict.FAIL, "driving licence length ${v.length} is implausible")
        }
        if (!v.all { it in 'A'..'Z' || it in '0'..'9' }) {
            return CheckOutcome(FormatVerdict.FAIL, "driving licence contains unexpected characters")
        }
        return CheckOutcome(FormatVerdict.OK)
    }

    /** Aadhaar: 12 digits with a valid Verhoeff check digit. */
    fun validateAadhaar(value: String): CheckOutcome {
        val v = value.filter { it.isDigit() }
        if (v.isEmpty()) return CheckOutcome(FormatVerdict.UNKNOWN, "no aadhaar number supplied")
        if (v.length != 12) {
            return CheckOutcome(FormatVerdict.FAIL, "aadhaar must be 12 digits, got ${v.length}")
        }
        if (!Verhoeff.isValid(v)) {
            return CheckOutcome(FormatVerdict.FAIL, "aadhaar Verhoeff check digit failed")
        }
        return CheckOutcome(FormatVerdict.OK)
    }
}
