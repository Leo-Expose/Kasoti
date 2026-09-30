package dev.kasoti.mrz

import dev.kasoti.time.CalendarDate

/**
 * ICAO 9303 MRZ parser (FR-M2).
 *
 * Parsing is *total and non-throwing*: a document with a bad check digit still yields its
 * fields, with the failure recorded per field in [MrzResult.checks]. That is what makes
 * `R-MATH-01` attributable ("expected 4, got 7, field BIRTH_DATE") instead of a generic
 * "invalid MRZ". Callers must gate on [MrzResult.allChecksPassed].
 *
 * Column positions follow ICAO 9303 Part 4, Part 5 (TD3) and Part 6 (TD1). The tables below
 * are the normative reference; do not "simplify" them.
 */
object MrzParser {

    private const val FILLER = '<'
    private val UPPER_ALNUM = ('0'..'9') + ('A'..'Z')

    /**
     * @param referenceYear full year used to resolve 2-digit MRZ years (caller's clock, not ours).
     */
    fun parse(lines: List<String>, referenceYear: Int): MrzResult {
        val cleaned = lines.map { it.trim() }
        return when (MrzFormat.detect(cleaned)) {
            MrzFormat.TD3 -> parseTd3(cleaned, referenceYear)
            MrzFormat.TD1 -> parseTd1(cleaned, referenceYear)
            MrzFormat.UNKNOWN -> MrzResult(
                format = MrzFormat.UNKNOWN,
                rawLines = cleaned,
                structuralErrors = listOf(
                    "shape ${cleaned.size}x${cleaned.firstOrNull()?.length ?: 0} matches no TD3/TD1 layout",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- TD3 (passport)

    private fun parseTd3(lines: List<String>, referenceYear: Int): MrzResult {
        val l1 = lines[0]
        val l2 = lines[1]
        val errors = mutableListOf<String>()

        val documentNumber = l1.slice(0, 9).trimFiller()
        val nationality = l1.slice(10, 13).trimFiller()
        val birthDate = l1.slice(13, 19)
        val sex = l1[20].toString()
        val expiryDate = l1.slice(21, 27)
        val personalNumber = l1.slice(28, 42)

        val checks = mutableListOf<MrzCheck>(
            check(MrzField.DOCUMENT_NUMBER, l1.slice(0, 9), l1[9]),
            check(MrzField.BIRTH_DATE, birthDate, l1[19]),
            check(MrzField.EXPIRY_DATE, expiryDate, l1[27]),
            check(MrzField.PERSONAL_NUMBER, l1.slice(28, 42), l1[42]),
        )
        // TD3 line 2 is pure name (ICAO 9303 Part 5) — it carries no check digits of its own.
        // Its integrity is covered by the composite below, which spans the whole of line 2.

        // Composite over line1 (1-10, 14-20, 22-43) then the whole of line 2.
        val compositeSpans = arrayOf(l1.slice(0, 10), l1.slice(13, 20), l1.slice(21, 43), l2)
        checks += MrzCheck(
            field = MrzField.COMPOSITE,
            span = compositeSpans.joinToString(""),
            expected = MrzCheckDigit.computeComposite(*compositeSpans),
            observed = l1[43].takeIf { it != FILLER },
        )

        if (l1.any { it != FILLER && it !in UPPER_ALNUM } || l2.any { it != FILLER && it !in UPPER_ALNUM }) {
            errors += "MRZ contains characters outside the A-Z0-9< alphabet"
        }
        validateDateField(MrzField.BIRTH_DATE, birthDate, referenceYear, errors)
        validateDateField(MrzField.EXPIRY_DATE, expiryDate, referenceYear, errors)
        if (sex !in setOf("M", "F", "X", "<")) errors += "sex '$sex' is not M/F/X/<"

        return MrzResult(
            format = MrzFormat.TD3,
            rawLines = cleaned(lines),
            documentNumber = documentNumber.ifEmpty { null },
            nationality = nationality.ifEmpty { null },
            birthDate = birthDate.takeIf { validateDateField(MrzField.BIRTH_DATE, it, referenceYear, mutableListOf()) == null },
            sex = sex.takeIf { it.firstOrNull() != FILLER },
            expiryDate = expiryDate.takeIf { validateDateField(MrzField.EXPIRY_DATE, it, referenceYear, mutableListOf()) == null },
            personalNumber = personalNumber.ifEmpty { null },
            name = parseName(l2),
            checks = checks,
            structuralErrors = errors,
        )
    }

    // ---------------------------------------------------------------- TD1 (id card)

    private fun parseTd1(lines: List<String>, referenceYear: Int): MrzResult {
        val l1 = lines[0]
        val l2 = lines[1]
        val l3 = lines[2]
        val errors = mutableListOf<String>()

        val documentNumber = l1.slice(0, 9).trimFiller()
        val nationality = l1.slice(10, 13).trimFiller()
        val birthDate = l1.slice(13, 19)
        val sex = l1[20].toString()
        val optional1 = l1.slice(21, 29)
        val expiryDate = l3.slice(7, 13)
        val issuer = l3.slice(14, 29)

        val checks = mutableListOf<MrzCheck>(
            check(MrzField.DOCUMENT_NUMBER, l1.slice(0, 9), l1[9]),
            check(MrzField.BIRTH_DATE, birthDate, l1[19]),
            check(MrzField.OPTIONAL_DATA, optional1, l1[29]),
            check(MrzField.NAME, l2.slice(0, 29), l2[29]),
            check(MrzField.OPTIONAL_DATA, l3.slice(0, 6), l3[6]),
            check(MrzField.EXPIRY_DATE, expiryDate, l3[13]),
            check(MrzField.PERSONAL_NUMBER, issuer, l3[29]),
        )

        listOf(l1, l2, l3).forEachIndexed { i, line ->
            if (line.any { it != FILLER && it !in UPPER_ALNUM }) {
                errors += "TD1 line ${i + 1} contains characters outside the A-Z0-9< alphabet"
            }
        }
        validateDateField(MrzField.BIRTH_DATE, birthDate, referenceYear, errors)
        validateDateField(MrzField.EXPIRY_DATE, expiryDate, referenceYear, errors)
        if (sex !in setOf("M", "F", "X", "<")) errors += "sex '$sex' is not M/F/X/<"

        return MrzResult(
            format = MrzFormat.TD1,
            rawLines = lines,
            documentNumber = documentNumber.ifEmpty { null },
            nationality = nationality.ifEmpty { null },
            birthDate = birthDate.takeIf { validateDateField(MrzField.BIRTH_DATE, it, referenceYear, mutableListOf()) == null },
            sex = sex.takeIf { it.firstOrNull() != FILLER },
            expiryDate = expiryDate.takeIf { validateDateField(MrzField.EXPIRY_DATE, it, referenceYear, mutableListOf()) == null },
            personalNumber = optional1.trimFiller().ifEmpty { null },
            name = parseName(l2.slice(0, 29)),
            checks = checks,
            structuralErrors = errors,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun check(field: MrzField, span: String, observed: Char): MrzCheck =
        MrzCheck(field = field, span = span, expected = MrzCheckDigit.compute(span), observed = observed)

    /**
     * `SURNAME<<GIVEN<NAMES`. ICAO puts a single `<` between words of a multi-word name and
     * `<<` where the surname ends.
     */
    private fun parseName(field: String): MrzName {
        val separator = field.indexOf("<<")
        return if (separator < 0) {
            MrzName(surname = field.trimFiller(), givenNames = "")
        } else {
            MrzName(
                surname = field.substring(0, separator).trimFiller(),
                givenNames = field.substring(separator + 2).trimFiller(),
            )
        }
    }

    /** Returns an error string, or `null` when the field is a real calendar date. */
    private fun validateDateField(field: MrzField, text: String, referenceYear: Int, sink: MutableList<String>): String? {
        if (text.all { it == FILLER }) return null // absent date on a non-mandatory track
        if (!text.all { it in '0'..'9' }) {
            val err = "$field date '$text' is not six digits"
            sink += err
            return err
        }
        val mm = text.substring(2, 4).toInt()
        val dd = text.substring(4, 6).toInt()
        if (CalendarDate.parseYymmdd(text, referenceYear) == null) {
            val err = "$field date '$text' is not a real calendar date"
            sink += err
            return err
        }
        if (mm !in 1..12) {
            val err = "$field date '$text' has month $mm"
            sink += err
            return err
        }
        if (dd < 1 || dd > 31) {
            val err = "$field date '$text' has day $dd"
            sink += err
            return err
        }
        return null
    }

    /** Inclusive 0-based slice, clamped so a short line never throws. */
    private fun String.slice(start: Int, endExclusive: Int): String {
        if (start >= length) return ""
        return substring(start, minOf(endExclusive, length))
    }

    private fun String.trimFiller(): String = replace(FILLER, ' ').trim().replace(Regex("\\s+"), " ")

    private fun cleaned(lines: List<String>): List<String> = lines
}
