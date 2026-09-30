package dev.kasoti.checks

import dev.kasoti.fusion.FindingCode
import dev.kasoti.time.IsoDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerhoeffTest {

    /**
     * Verhoeff's three tables as the specification prints them.
     *
     * They are pinned in the test rather than transcribed into `Verhoeff`, which generates all
     * three (D from the D5 Cayley law, P from its generator permutation, INV from D). A
     * generated table that stops matching the specification is silent — the checksum stays a
     * well-behaved function of the wrong tables — so this is the assertion that catches it, and
     * keeping the published rows here means a reviewer can diff them against the source.
     *
     * Source: the Verhoeff algorithm reference that NPCI/2013-14/NACH/Circular 9 (9 July 2013),
     * "Implementation of Verhoeff Algorithm by banks for Aadhaar related applications", points
     * banks at for Aadhaar validation.
     */    private val publishedD = listOf(
        "0123456789", "1234067895", "2340178956", "3401289567", "4012395678",
        "5987604321", "6598710432", "7659821043", "8765932104", "9876543210",
    )
    private val publishedP = listOf(
        "0123456789", "1576283094", "5803796142", "8916043527",
        "9453126870", "4286573901", "2793806415", "7046913258",
    )
    private val publishedInv = "0432156789"

    /**
     * Body -> check digit, computed by an independent implementation written from the tables
     * above rather than read back out of `:core`. Structural sweeps cannot substitute for these:
     * a walk that ignores the position table is still self-consistent, so it appends and
     * verifies happily while returning the wrong digit for every real number.
     */
    private val independentCheckDigits = listOf(
        "12345678901" to 0,
        "99999999001" to 9,
        "99999999000" to 3,
        "00000000000" to 3,
        "11111111111" to 5,
        "23456789012" to 4,
        "98765432109" to 6,
        "10101010101" to 8,
        "12121212121" to 2,
        "99999999999" to 9,
    )

    @Test
    fun `generated tables match the published D, P and inv tables`() {
        val tables = Verhoeff.tables()
        assertEquals(publishedD, tables.d)
        assertEquals(publishedP, tables.p)
        assertEquals(publishedInv, tables.inv)
    }

    @Test
    fun `published worked example - the check digit of 236 is 3`() {
        assertEquals(3, Verhoeff.checkDigit("236"))
        assertEquals("2363", Verhoeff.appendCheckDigit("236"))
    }

    @Test
    fun `check digits agree with an independent implementation`() {
        for ((body, expected) in independentCheckDigits) {
            assertEquals(expected, Verhoeff.checkDigit(body), "body=$body")
            val full = body + expected
            assertEquals(full, Verhoeff.appendCheckDigit(body), "body=$body")
            assertTrue(Verhoeff.isValid(full), "expected $full to verify")
        }
    }

    @Test
    fun `generated numbers verify against their own check digit`() {
        // Round-trip only. It cannot tell a right algorithm from a consistently wrong one —
        // see `check digits agree with an independent implementation` for the half that can.
        listOf("99999999001", "23456789012", "11111111111", "00000000000").forEach { body ->
            val withCheck = Verhoeff.appendCheckDigit(body)
            assertNotNull(withCheck, "body=$body")
            assertTrue(Verhoeff.isValid(withCheck), "expected $withCheck to verify")
        }
    }

    @Test
    fun `swapping two adjacent digits always changes the check digit`() {
        // Verhoeff's headline guarantee: every transposition of two adjacent digits is
        // detected. This is the digit-permutation property, restricted to the swaps the
        // algorithm actually promises to catch. The unrestricted form — moving a digit to
        // ANY other position — is deliberately not asserted: roughly one arbitrary two-digit
        // move in twelve is undetectable even under a correct implementation, so pinning that
        // would pin a falsehood. (A walk with no position table at all still detects about two
        // thirds of adjacent swaps, so this test is a real check, not decoration.)
        var checked = 0
        for (n in 0 until 60) {
            val body = (0 until 11).joinToString("") { ((n * 31 + it * 17 + n / 7) % 10).toString() }
            val check = Verhoeff.checkDigit(body)!!
            for (i in 0 until 10) {
                if (body[i] == body[i + 1]) continue
                val swapped = body.swapAt(i, i + 1)
                assertNotEquals(
                    check,
                    Verhoeff.checkDigit(swapped),
                    "adjacent swap at $i left the check digit alone: $body vs $swapped",
                )
                checked++
            }
        }
        assertTrue(checked > 400, "expected broad transposition coverage, got $checked")
    }

    private fun String.swapAt(a: Int, b: Int): String {
        val chars = toCharArray()
        val swap = chars[a]
        chars[a] = chars[b]
        chars[b] = swap
        return String(chars)
    }

    @Test
    fun `a single digit change breaks the check`() {
        val valid = Verhoeff.appendCheckDigit("12345678901")!!
        for (i in valid.indices) {
            for (d in '0'..'9') {
                if (d == valid[i]) continue
                val broken = valid.replaceRange(i, i + 1, d.toString())
                assertFalse(Verhoeff.isValid(broken), "mutant $broken should not verify")
            }
        }
    }

    @Test
    fun `rejects wrong length and non-digits without throwing`() {
        assertFalse(Verhoeff.isValid(""))
        assertFalse(Verhoeff.isValid("12345"))
        assertFalse(Verhoeff.isValid("12345678901X"))
        assertFalse(Verhoeff.isValid("123456789012"))
        assertNull(Verhoeff.checkDigit(""))
        assertNull(Verhoeff.checkDigit("12A4"))
    }

    @Test
    fun `adversarial single-substitution coverage over many bodies`() {
        var verified = 0
        var broken = 0
        for (n in 0 until 200) {
            val body = (0 until 11).joinToString("") { ((n * 7 + it * 13) % 10).toString() }
            val full = Verhoeff.appendCheckDigit(body) ?: continue
            assertTrue(Verhoeff.isValid(full), "body=$body full=$full")
            verified++
            for (i in full.indices) {
                val d = '0' + ((n + i) % 10)
                if (d == full[i]) continue
                assertFalse(Verhoeff.isValid(full.replaceRange(i, i + 1, d.toString())))
                broken++
            }
        }
        assertTrue(verified >= 200)
        assertTrue(broken > 1_000, "expected broad adversarial coverage, got $broken")
    }
}

class DateLogicTest {

    private val today = IsoDate(2026, 9, 29)

    @Test
    fun `expired document fails`() {
        val out = DateLogic.evaluate(IsoDate(1990, 5, 17), IsoDate(2020, 1, 1), today = today)
        assertTrue(out.any { it.verdict == FormatVerdict.FAIL && it.reason.contains("expired") })
        assertTrue(out.all { FindingCode.R_MATH_02 in it.findings })
    }

    @Test
    fun `valid document produces no findings`() {
        assertTrue(DateLogic.evaluate(IsoDate(1990, 5, 17), IsoDate(2030, 1, 1), today = today).isEmpty())
    }

    @Test
    fun `issue after expiry fails`() {
        val out = DateLogic.evaluate(
            birthDate = IsoDate(1990, 1, 1),
            expiryDate = IsoDate(2025, 1, 1),
            issueDate = IsoDate(2026, 1, 1),
            today = today,
        )
        assertTrue(out.any { it.reason.contains("after expiry") })
    }

    @Test
    fun `future birth date fails`() {
        val out = DateLogic.evaluate(IsoDate(2030, 1, 1), null, today = today)
        assertTrue(out.any { it.reason.contains("not in the past") })
    }

    @Test
    fun `implausible age fails`() {
        val out = DateLogic.evaluate(IsoDate(1850, 1, 1), null, today = today)
        assertTrue(out.any { it.reason.contains("plausible maximum") })
    }

    @Test
    fun `absent dates are not failures`() {
        assertTrue(DateLogic.evaluate(null, null, today = today).isEmpty())
    }
}

class FormatValidatorTest {

    @Test
    fun `voter id shapes`() {
        assertEquals(FormatVerdict.OK, FormatValidators.validateVoterId("ABC1234567").verdict)
        assertEquals(FormatVerdict.OK, FormatValidators.validateVoterId("abc1234567").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateVoterId("AB12345678").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateVoterId("ABCD1234567").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateVoterId("ABC123456").verdict)
        assertEquals(FormatVerdict.UNKNOWN, FormatValidators.validateVoterId("").verdict)
    }

    @Test
    fun `driving licence validates shape only`() {
        assertEquals(FormatVerdict.OK, FormatValidators.validateDl("DL-2019-00123456").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateDl("SHORT").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateDl("DL@2019#00123456").verdict)
        assertEquals(FormatVerdict.UNKNOWN, FormatValidators.validateDl("  ").verdict)
    }

    @Test
    fun `aadhaar length and verhoeff are both enforced`() {
        val valid = Verhoeff.appendCheckDigit("12345678901")!!
        assertEquals(FormatVerdict.OK, FormatValidators.validateAadhaar(valid).verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateAadhaar(valid.dropLast(1)).verdict)
        // Separators in a printed/scanned number are stripped before checking.
        assertEquals(FormatVerdict.OK, FormatValidators.validateAadhaar(" ${valid.substring(0, 4)} ${valid.substring(4)} ").verdict)
        assertEquals(FormatVerdict.FAIL, FormatValidators.validateAadhaar(valid.replaceRange(0, 1, "0")).verdict)
        assertEquals(FormatVerdict.UNKNOWN, FormatValidators.validateAadhaar("").verdict)
    }
}

class VizMrzMatchTest {

    private val referenceYear = 2026

    @Test
    fun `names match across separators and word order`() {
        assertEquals(1f, VizMrzMatch.nameScore("SHARMA, RAMESH K.", "SHARMA<<RAMESH K"))
        assertEquals(1f, VizMrzMatch.nameScore("Ramesh Kumar Sharma", "SHARMA<<RAMESH KUMAR"))
        assertEquals(1f, VizMrzMatch.nameScore("sharma ramesh", "SHARMA<<RAMESH"))
    }

    @Test
    fun `different names do not score a match`() {
        assertEquals(0f, VizMrzMatch.nameScore("SHARMA", "VERMA"))
        assertTrue(VizMrzMatch.nameScore("SHARMA RAMESH", "VERMA SURESH") < 0.5f)
    }

    @Test
    fun `empty sides score zero rather than a false match`() {
        assertEquals(0f, VizMrzMatch.nameScore("", "VERMA"))
        assertEquals(0f, VizMrzMatch.nameScore("VERMA", ""))
        assertEquals(0f, VizMrzMatch.nameScore("!!!", "???"))
    }

    @Test
    fun `dates normalise across formats`() {
        val a = VizMrzMatch.normaliseDate("1990-05-17", referenceYear)
        val b = VizMrzMatch.normaliseDate("17/05/1990", referenceYear)
        val c = VizMrzMatch.normaliseDate("900517", referenceYear)
        assertEquals("1990-05-17", a)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(1f, VizMrzMatch.dateScore("17-05-1990", "900517", referenceYear))
    }

    @Test
    fun `omitted date sentinels are not real dates`() {
        assertNull(VizMrzMatch.normaliseDate("999999", referenceYear))
        assertNull(VizMrzMatch.normaliseDate("000000", referenceYear))
        assertNull(VizMrzMatch.normaliseDate("", referenceYear))
        assertNull(VizMrzMatch.normaliseDate("not-a-date", referenceYear))
    }

    @Test
    fun `impossible dates are rejected`() {
        assertNull(VizMrzMatch.normaliseDate("1990-02-30", referenceYear))
        assertNull(VizMrzMatch.normaliseDate("30/02/1990", referenceYear))
    }

    @Test
    fun `document numbers compare on alphanumerics only`() {
        assertEquals(1f, VizMrzMatch.numberScore("AB-1234567", "ab1234567"))
        assertEquals(0f, VizMrzMatch.numberScore("AB1234567", "AB7654321"))
        assertEquals(0f, VizMrzMatch.numberScore("", "AB1234567"))
    }
}
