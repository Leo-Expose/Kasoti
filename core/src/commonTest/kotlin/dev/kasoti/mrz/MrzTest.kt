package dev.kasoti.mrz

import dev.kasoti.assertNotEqualsQuietly
import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrzCheckDigitTest {

    @Test
    fun `values follow ICAO 9303`() {
        assertEquals(0, MrzCheckDigit.value('0'))
        assertEquals(9, MrzCheckDigit.value('9'))
        assertEquals(10, MrzCheckDigit.value('A'))
        assertEquals(35, MrzCheckDigit.value('Z'))
        assertEquals(0, MrzCheckDigit.value('<'))
        assertNull(MrzCheckDigit.value('a'))
        assertNull(MrzCheckDigit.value('!'))
    }

    @Test
    fun `weights cycle 7-3-1`() {
        // 'B' has value 11, so each position reveals its weight directly mod 10.
        assertEquals(7, MrzCheckDigit.compute("B<<<<<<")!!.code - '0'.code) // 11*7 = 77
        assertEquals(3, MrzCheckDigit.compute("<B<<<<<")!!.code - '0'.code) // 11*3 = 33
        assertEquals(1, MrzCheckDigit.compute("<<B<<<<")!!.code - '0'.code) // 11*1 = 11
        assertEquals(7, MrzCheckDigit.compute("<<<B<<<")!!.code - '0'.code) // weight repeats
    }

    @Test
    fun `compute fails closed on non-mrz characters`() {
        assertNull(MrzCheckDigit.compute("AB!C"))
        assertNull(MrzCheckDigit.compute("AB C"))
        assertNull(MrzCheckDigit.compute("ab12"))
        assertFalse(MrzCheckDigit.verify("AB!C", '0'))
        // A field made only of legal characters always produces a digit.
        assertNotNull(MrzCheckDigit.compute("AB1C"))
    }

    @Test
    fun `verify accepts only the exact digit`() {
        val field = "L898902C"
        val good = MrzCheckDigit.compute(field)!!
        assertTrue(MrzCheckDigit.verify(field, good))
        assertFalse(MrzCheckDigit.verify(field, if (good == '9') '8' else '9'))
    }

    @Test
    fun `composite concatenates its spans before weighting`() {
        // ICAO composites weight the concatenated spans as one continuous run, so span
        // boundaries must not reset the 7-3-1 cycle.
        assertEquals(MrzCheckDigit.compute("ABCDEF"), MrzCheckDigit.computeComposite("AB", "CDEF"))
        assertNotEqualsQuietly(
            MrzCheckDigit.compute("ABCDEF"),
            MrzCheckDigit.compute("AB<CDEF"),
        )
    }
}

class MrzBuilderParserRoundTripTest {

    private val referenceYear = 2026

    @Test
    fun `generated TD3 parses and passes every check`() {
        repeat(200) { seed ->
            val person = MrzPerson.sample(seed.toLong())
            val lines = MrzBuilder.buildTd3(person, referenceYear)
            val result = MrzParser.parse(lines, referenceYear)

            assertEquals(MrzFormat.TD3, result.format, "seed=$seed lines=$lines")
            assertEquals(listOf(44, 44), lines.map { it.length })
            assertTrue(result.structuralErrors.isEmpty(), "seed=$seed errors=${result.structuralErrors} lines=$lines")
            assertTrue(
                result.allChecksPassed,
                "seed=$seed failed=${result.failedChecks} lines=$lines",
            )
            assertEquals(person.surname, result.name.surname, "seed=$seed")
            assertEquals(person.givenNames, result.name.givenNames, "seed=$seed")
            assertEquals(person.documentNumber, result.documentNumber, "seed=$seed")
        }
    }

    @Test
    fun `generated TD1 parses and passes every check`() {
        repeat(200) { seed ->
            val person = MrzPerson.sample(seed.toLong())
            val lines = MrzBuilder.buildTd1(person, referenceYear)
            val result = MrzParser.parse(lines, referenceYear)

            assertEquals(MrzFormat.TD1, result.format, "seed=$seed lines=$lines")
            assertEquals(listOf(30, 30, 30), lines.map { it.length })
            assertTrue(result.structuralErrors.isEmpty(), "seed=$seed errors=${result.structuralErrors} lines=$lines")
            assertTrue(result.allChecksPassed, "seed=$seed failed=${result.failedChecks} lines=$lines")
            assertEquals(person.surname, result.name.surname, "seed=$seed")
        }
    }

    @Test
    fun `real-shaped TD3 document parses with per-field attribution`() {
        // Layout matches a real travel document: alphanumeric number, 3-letter issuing
        // state, YYMMDD dates, filler-padded personal number, `P<CTRY` name prefix.
        val person = MrzPerson(
            surname = "ERIKSSON",
            givenNames = "ANNA MARIA",
            documentNumber = "L898902C3",
            nationality = "UTO",
            birthDate = IsoDate(1974, 8, 12),
            sex = 'F',
            expiryDate = IsoDate(2012, 4, 15),
            personalNumber = "ZE184226B",
        )
        val lines = MrzBuilder.buildTd3(person, referenceYear)
        val result = MrzParser.parse(lines, referenceYear)

        assertEquals(MrzFormat.TD3, result.format)
        assertTrue(result.allChecksPassed, "failed=${result.failedChecks}")
        assertEquals("UTO", result.nationality)
        assertEquals("F", result.sex)
        assertEquals("ERIKSSON", result.name.surname)
        assertEquals("1974-08-12", CalendarDate.parseYymmdd(result.birthDate!!, referenceYear)!!.toIsoString())
        assertEquals("2012-04-15", CalendarDate.parseYymmdd(result.expiryDate!!, referenceYear)!!.toIsoString())
    }
}

class MrzParserAttributionTest {

    private val referenceYear = 2026

    @Test
    fun `bad document number is attributed to that field only`() {
        val lines = validTd3()
        val broken = lines.toMutableList()
        broken[0] = broken[0].replaceRange(0, 1, if (lines[0][0] == 'A') "B" else "A")
        val result = MrzParser.parse(broken, referenceYear)
        val failed = result.failedChecks.map { it.field }.toSet()
        assertTrue(MrzField.DOCUMENT_NUMBER in failed, "failed=$failed")
    }

    @Test
    fun `impossible calendar date is reported as a structural error`() {
        val person = MrzPerson(
            surname = "TEST",
            givenNames = "CASE",
            documentNumber = "AB1234567",
            birthDate = IsoDate(1990, 2, 30), // never a real date
            expiryDate = IsoDate(2030, 1, 1),
        )
        val lines = MrzBuilder.buildTd3(person, referenceYear)
        val result = MrzParser.parse(lines, referenceYear)
        assertTrue(result.structuralErrors.any { it.contains("BIRTH_DATE") }, "errors=${result.structuralErrors}")
    }

    @Test
    fun `unknown shape is reported not thrown`() {
        val result = MrzParser.parse(listOf("too", "short"), referenceYear)
        assertEquals(MrzFormat.UNKNOWN, result.format)
        assertFalse(result.usable)
        assertTrue(MrzCorpus.detect(result))
    }

    @Test
    fun `lowercase letters are rejected rather than normalised`() {
        val lines = validTd3().toMutableList()
        lines[0] = "l" + lines[0].substring(1)
        val result = MrzParser.parse(lines, referenceYear)
        assertTrue(result.structuralErrors.any { it.contains("alphabet") }, "errors=${result.structuralErrors}")
    }

    @Test
    fun `TD3 composite check digit covers the whole of line 2`() {
        // ICAO 9303 Part 5 §4.2.2: the final check digit spans line 1 (1-10, 14-20, 22-43)
        // plus line 2 (1-44). Line 2 is the *name* — including it is what makes a name
        // mutation (AT-03 photo/name swap) break the composite even when every field's own
        // check digit is internally consistent. This is a load-bearing property, so it is
        // pinned by a test rather than left to a remembered specimen string.
        val base = validTd3()
        val result = MrzParser.parse(base, referenceYear)
        assertTrue(result.allChecksPassed, "specimen must be internally consistent")

        val spans = arrayOf(base[0].substring(0, 10), base[0].substring(13, 20), base[0].substring(21, 43), base[1])
        val withName = MrzCheckDigit.computeComposite(*spans)
        val withoutName = MrzCheckDigit.computeComposite(spans[0], spans[1], spans[2])
        assertNotEqualsQuietly(withName, withoutName)
    }

    private fun validTd3() = MrzBuilder.buildTd3(
        MrzPerson(
            surname = "SHARMA",
            givenNames = "RAMESH",
            documentNumber = "AB1234567",
            birthDate = IsoDate(1990, 5, 17),
            expiryDate = IsoDate(2030, 5, 16),
        ),
        referenceYear,
    )
}

class MrzCorpusTest {

    private val referenceYear = 2026

    @Test
    fun `every detectable mutant is caught`() {
        val cases = MrzCorpus.generate(seed = 42, count = 10_000, referenceYear = referenceYear)
        val disagreements = cases.mapNotNull { c ->
            val result = MrzParser.parse(c.lines, referenceYear)
            val caught = MrzCorpus.detect(result)
            if (caught != c.expectedCaught) {
                "id=${c.id} mutation=${c.mutation} expected=${c.expectedCaught} actual=$caught lines=${c.lines}"
            } else {
                null
            }
        }
        assertTrue(disagreements.isEmpty(), "corpus contract broken:\n" + disagreements.take(10).joinToString("\n"))
    }

    @Test
    fun `valid documents are never reported as caught`() {
        val cases = MrzCorpus.generate(seed = 7, count = 2_000, referenceYear = referenceYear)
        val valid = cases.filter { it.mutation == MrzMutation.NONE }
        assertTrue(valid.size > 100, "expected a healthy valid slice, got ${valid.size}")
        valid.forEach { c ->
            val result = MrzParser.parse(c.lines, referenceYear)
            assertFalse(MrzCorpus.detect(result), "false positive on ${c.id} lines=${c.lines} failed=${result.failedChecks}")
        }
    }

    @Test
    fun `every detectable recomputed check digit is caught by the TD3 composite`() {
        val cases = MrzCorpus.generate(seed = 11, count = 3_000, referenceYear = referenceYear)
        val recomputed = cases.filter { it.mutation == MrzMutation.RECOMPUTED_FIELD }
        assertTrue(recomputed.isNotEmpty(), "expected recomputed mutants")
        val detectable = recomputed.filter { it.expectedCaught }
        assertTrue(detectable.size > 50, "expected a healthy detectable slice, got ${detectable.size}")

        detectable.forEach { c ->
            val result = MrzParser.parse(c.lines, referenceYear)
            assertTrue(MrzCorpus.detect(result), "recomputed mutant survived: ${c.lines}")
            assertTrue(
                result.failedChecks.any { it.field == MrzField.COMPOSITE },
                "expected composite to catch it, got ${result.failedChecks}",
            )
        }
    }

    @Test
    fun `structurally blind recomputes are labelled and do survive`() {
        // ICAO 9303 leaves real holes in the AT-02 attack surface:
        //   * TD1 has no composite check digit at all.
        //   * In TD3, the composite delta for a data edit at certain positions is identically
        //     zero mod 10 once the field's own check digit is recomputed — positions 14, 15,
        //     17, 18 of the date-of-birth field and 22, 25 of the date-of-expiry field.
        // These are not bugs in KASOTI; they are properties of the standard. They are measured
        // and published (EVAL.md) because they are the reason the remaining layers exist.
        val cases = MrzCorpus.generate(seed = 13, count = 3_000, referenceYear = referenceYear)
            .filter { it.mutation == MrzMutation.RECOMPUTED_FIELD && !it.expectedCaught }
        assertTrue(cases.isNotEmpty(), "expected documented blind spots")

        cases.forEach { c ->
            assertTrue(c.blindSpotReason!!.isNotBlank(), "blind spot ${c.id} has no reason")
            val result = MrzParser.parse(c.lines, referenceYear)
            assertFalse(
                MrzCorpus.detect(result),
                "row ${c.id} was labelled blind but was actually caught; the label is wrong",
            )
        }
    }

    @Test
    fun `TD1 rows are always labelled as composite-free blind spots`() {
        val td1 = MrzCorpus.generate(seed = 13, count = 3_000, referenceYear = referenceYear)
            .filter { it.mutation == MrzMutation.RECOMPUTED_FIELD && it.format == MrzFormat.TD1 }
        assertTrue(td1.isNotEmpty(), "expected TD1 recomputed mutants")
        td1.forEach { c ->
            assertFalse(c.expectedCaught, "TD1 has no composite; row ${c.id} should be a blind spot")
            assertTrue(c.blindSpotReason!!.contains("composite"))
        }
    }

    @Test
    fun `corpus is deterministic for a fixed seed`() {
        val a = MrzCorpus.generate(seed = 5, count = 200, referenceYear = referenceYear)
        val b = MrzCorpus.generate(seed = 5, count = 200, referenceYear = referenceYear)
        assertEquals(a, b)
    }

    @Test
    fun `blind spots are labelled with a reason`() {
        val cases = MrzCorpus.generate(seed = 3, count = 4_000, referenceYear = referenceYear)
        val blind = cases.filter { !it.expectedCaught && it.mutation == MrzMutation.RECOMPUTED_FIELD }
        assertTrue(blind.all { !it.blindSpotReason.isNullOrBlank() })
    }
}

class CalendarDateTest {

    @Test
    fun `leap years follow the Gregorian rule`() {
        assertTrue(CalendarDate.isLeapYear(2024))
        assertTrue(CalendarDate.isLeapYear(2000))
        assertFalse(CalendarDate.isLeapYear(1900))
        assertFalse(CalendarDate.isLeapYear(2026))
        assertEquals(29, CalendarDate.daysInMonth(2024, 2))
        assertEquals(28, CalendarDate.daysInMonth(2026, 2))
    }

    @Test
    fun `century rule reads near years forward and old years backward`() {
        // In 2026, "05" as a birth year means 2005, not 1905.
        assertEquals(IsoDate(2005, 6, 8), CalendarDate.parseYymmdd("050608", 2026))
        // An expiry 30 years out stays 2030 rather than wrapping to 1930.
        assertEquals(IsoDate(2030, 6, 23), CalendarDate.parseYymmdd("300623", 2026))
        // A genuinely old birth year reads into the previous century.
        assertEquals(IsoDate(1984, 1, 1), CalendarDate.parseYymmdd("840101", 2026))
    }

    @Test
    fun `impossible dates are rejected`() {
        assertNull(CalendarDate.parseYymmdd("900231", 2026))
        assertNull(CalendarDate.parseYymmdd("901301", 2026))
        assertNull(CalendarDate.parseYymmdd("911331", 2026))
        assertNull(CalendarDate.of(2026, 13, 1))
        assertNull(CalendarDate.of(2026, 0, 1))
        assertNotNull(CalendarDate.of(2024, 2, 29))
    }

    @Test
    fun `iso parsing is strict`() {
        assertEquals(IsoDate(2026, 9, 29), IsoDate.parse("2026-09-29"))
        assertNull(IsoDate.parse("2026-9-29"))
        assertNull(IsoDate.parse("26-09-29"))
        assertNull(IsoDate.parse("not-a-date"))
    }

    @Test
    fun `years between floors at zero and respects month and day`() {
        assertEquals(0, CalendarDate.yearsBetween(IsoDate(2030, 1, 1), IsoDate(2026, 1, 1)))
        assertEquals(36, CalendarDate.yearsBetween(IsoDate(1990, 5, 17), IsoDate(2026, 9, 29)))
        assertEquals(36, CalendarDate.yearsBetween(IsoDate(1990, 5, 17), IsoDate(2026, 5, 17)))
        assertEquals(35, CalendarDate.yearsBetween(IsoDate(1990, 5, 17), IsoDate(2026, 5, 16)))
    }
}
