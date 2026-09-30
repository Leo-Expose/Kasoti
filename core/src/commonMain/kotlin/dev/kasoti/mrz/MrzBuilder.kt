package dev.kasoti.mrz

import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate
import kotlin.random.Random

/** Identity fields that get rendered into a machine-readable zone. */
data class MrzPerson(
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val nationality: String = "IND",
    val birthDate: IsoDate,
    val sex: Char = 'M',
    val expiryDate: IsoDate,
    val personalNumber: String = "",
) {
    companion object {
        /** A valid adult whose dates stay unambiguous under any century rule. */
        fun sample(seed: Long): MrzPerson {
            val rnd = Random(seed)
            val surnames = listOf("SHARMA", "VERMA", "PATEL", "SINGH", "GUPTA", "REDDY", "NAIR", "BOSE")
            val given = listOf("RAMESH", "SURESH", "ANITA", "VIKRAM", "MEENA", "ARJUN", "KAVITA", "DEV")
            val birth = IsoDate(1970 + rnd.nextInt(30), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            val expiry = IsoDate(birth.year + rnd.nextInt(5, 12), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            return MrzPerson(
                surname = surnames[rnd.nextInt(surnames.size)],
                givenNames = given[rnd.nextInt(given.size)],
                documentNumber = buildString {
                    repeat(8) { append(('A' + rnd.nextInt(26))) }
                },
                birthDate = birth,
                expiryDate = expiry,
                sex = if (rnd.nextBoolean()) 'M' else 'F',
                personalNumber = buildString { repeat(14) { append(('A' + rnd.nextInt(26))) } },
            )
        }
    }
}

/**
 * Builds *valid* machine-readable zones.
 *
 * Used by the eval corpus generator (D-MRZ), by unit tests, and by demo fixtures. Every
 * check digit is computed from the field it protects, so a [build] result always passes
 * [MrzResult.allChecksPassed] — any failing case got there by mutation.
 */
object MrzBuilder {

    private const val FILLER = '<'

    /** TD3: two 44-character lines, the travel-document layout. */
    fun buildTd3(person: MrzPerson, referenceYear: Int): List<String> {
        val docNo = field(person.documentNumber, 9)
        val nationality = field(person.nationality, 3)
        val dob = yymmdd(person.birthDate)
        val expiry = yymmdd(person.expiryDate)
        val personal = field(person.personalNumber.ifEmpty { person.documentNumber }, 14)

        val line1 = StringBuilder(44)
        line1.append(docNo)
        line1.append(MrzCheckDigit.compute(docNo))
        line1.append(nationality)
        line1.append(dob)
        line1.append(MrzCheckDigit.compute(dob))
        line1.append(person.sex)
        line1.append(expiry)
        line1.append(MrzCheckDigit.compute(expiry))
        line1.append(personal)
        line1.append(MrzCheckDigit.compute(personal))
        check(line1.length == 43) { "TD3 line 1 pre-composite must be 43 chars, got ${line1.length}" }

        val line2 = nameField(person)

        val composite = MrzCheckDigit.computeComposite(
            line1.substring(0, 10),
            line1.substring(13, 20),
            line1.substring(21, 43),
            line2,
        )
        line1.append(composite)

        return listOf(line1.toString(), line2)
    }

    /** TD1: three 30-character lines, the id-card layout. */
    fun buildTd1(person: MrzPerson, referenceYear: Int): List<String> {
        val docNo = field(person.documentNumber, 9)
        val nationality = field(person.nationality, 3)
        val dob = yymmdd(person.birthDate)
        val expiry = yymmdd(person.expiryDate)
        val optional1 = field(person.personalNumber, 8)
        val name = nameField(person).let { if (it.length > 29) it.substring(0, 29) else it.padEnd(29, FILLER) }
        val optional2 = field(person.nationality, 6)
        val issuer = field(person.nationality, 15)

        val line1 = StringBuilder(30)
        line1.append(docNo)
        line1.append(MrzCheckDigit.compute(docNo))
        line1.append(nationality)
        line1.append(dob)
        line1.append(MrzCheckDigit.compute(dob))
        line1.append(person.sex)
        line1.append(optional1)
        line1.append(MrzCheckDigit.compute(optional1))

        val line2 = name + MrzCheckDigit.compute(name)

        val line3 = StringBuilder(30)
        line3.append(optional2)
        line3.append(MrzCheckDigit.compute(optional2))
        line3.append(expiry)
        line3.append(MrzCheckDigit.compute(expiry))
        line3.append(issuer)
        line3.append(MrzCheckDigit.compute(issuer))

        return listOf(line1.toString(), line2, line3.toString())
    }

    /** `SURNAME<<GIVEN<NAMES` padded to 44 with fillers. */
    private fun nameField(person: MrzPerson): String {
        val surname = sanitize(person.surname)
        val given = sanitize(person.givenNames)
        val raw = if (given.isEmpty()) surname else "$surname<<$given"
        val padded = if (raw.length > 44) raw.substring(0, 44) else raw.padEnd(44, FILLER)
        return padded
    }

    /** `YYMMDD` from a full date. The [referenceYear] is not needed: the builder writes the
     *  date the caller supplied, and the parser re-reads it with the same century rule. */
    fun yymmdd(date: IsoDate): String {
        val text = date.toIsoString() // YYYY-MM-DD
        return text.substring(2, 4) + text.substring(5, 7) + text.substring(8, 10)
    }

    /**
     * Normalise free text into the MRZ alphabet: uppercase, whitespace becomes a filler,
     * anything outside `A-Z0-9<` is dropped. Digits are legal MRZ characters, so document
     * numbers such as `AB1234567` survive intact.
     */
    private fun sanitize(text: String): String = text
        .uppercase()
        .map { if (it.isWhitespace()) '<' else it }
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
        .joinToString("")
        .replace(Regex("<+"), "<")
        .trim('<')

    private fun field(value: String, width: Int): String {
        val clean = sanitize(value).take(width)
        return clean.padEnd(width, FILLER)
    }
}

/** Re-exported so corpus consumers need only one import. */
typealias MrzCalendar = CalendarDate
