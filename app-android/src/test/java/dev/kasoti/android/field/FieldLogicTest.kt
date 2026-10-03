package dev.kasoti.android.field

import dev.kasoti.factory.GrayImage
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.time.IsoDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackRouterTest {

    private val passportMrz = MrzBuilder.buildTd3(
        MrzPerson("SHARMA", "RAMESH", "K4820913", birthDate = IsoDate(1984, 3, 17), expiryDate = IsoDate(2031, 3, 16)),
        2026,
    )

    private fun mrz() = dev.kasoti.mrz.MrzParser.parse(passportMrz, 2026)

    @Test
    fun `a TD3 MRZ is a passport, and says so by name`() {
        val route = TrackRouter.route(RouteSignals(mrz = mrz()))
        assertEquals(Track.PASSPORT, route.track)
        assertEquals("mrz-td3", route.rule)
        assertTrue(route.confident)
        assertTrue(route.codes.isEmpty())
    }

    /** A genuinely Verhoeff-valid Aadhaar, computed rather than pasted. */
    private fun validAadhaar(): String {
        val body = "22345678901"
        return body + dev.kasoti.checks.Verhoeff.checkDigit(body)
    }

    @Test
    fun `a Verhoeff-valid number with a signed QR is an aadhaar`() {
        val number = validAadhaar()
        assertTrue(dev.kasoti.checks.Verhoeff.isValid(number), "the fixture must be valid or the test is vacuous")
        val route = TrackRouter.route(
            RouteSignals(secureQrPresent = true, aadhaarCandidate = number),
        )
        assertEquals(Track.AADHAAR, route.track)
        assertTrue(route.confident)
    }

    @Test
    fun `an aadhaar number that fails Verhoeff is not an aadhaar`() {
        val route = TrackRouter.route(
            RouteSignals(secureQrPresent = true, aadhaarCandidate = validAadhaar().dropLast(1) + "0"),
        )
        assertTrue(route.track != Track.AADHAAR, "a bad check digit must not route to a track that will then report it")
    }

    @Test
    fun `a signed QR with no readable number is an aadhaar, but not confidently`() {
        val route = TrackRouter.route(RouteSignals(secureQrPresent = true, documentShape = RouteSignals.DocumentShape.CARD))
        assertEquals(Track.AADHAAR, route.track)
        assertFalse(route.confident, "the routing was inferred, and the operator is told so")
    }

    @Test
    fun `a card with a voter-shaped number is a voter ID`() {
        val route = TrackRouter.route(
            RouteSignals(documentShape = RouteSignals.DocumentShape.CARD, vizDocumentNumber = "ABC1234567"),
        )
        assertEquals(Track.VOTER, route.track)
        assertTrue(route.confident)
    }

    @Test
    fun `a booklet with no readable MRZ still routes to passport so the chip stays required`() {
        // The alternative is routing it nowhere and waiving the chip check, which would let a
        // passport with an unreadable MRZ through a lighter set of checks than one with a
        // readable MRZ — precisely backwards.
        val route = TrackRouter.route(RouteSignals(documentShape = RouteSignals.DocumentShape.BOOKLET))
        assertEquals(Track.PASSPORT, route.track)
        assertFalse(route.confident)
    }

    @Test
    fun `no signal at all is UNKNOWN with a code, never a guess`() {
        val route = TrackRouter.route(RouteSignals())
        assertEquals(Track.UNKNOWN, route.track)
        assertTrue(route.isUnknown)
        assertEquals(listOf(FindingCode.SYS_UNSUPPORTED_TRACK), route.codes)
    }

    @Test
    fun `adversarial - a shape hint alone never yields a confident route`() {
        // Shape is the weakest signal there is; a confident route from it would be a confident
        // guess, and the whole class is rules-not-guessing (FR-M1).
        for (shape in RouteSignals.DocumentShape.entries) {
            val route = TrackRouter.route(RouteSignals(documentShape = shape))
            if (!route.confident) continue
            assertTrue(
                route.rule.contains("+") || route.rule.contains("mrz"),
                "shape-only route '$route' claims confidence without a verifiable signal",
            )
        }
    }
}

class MathLayerTest {

    private val math = MathLayer(FieldFixtures.REGISTRY)
    private val person = DemoCatalogue.SPECIMEN
    private val today = FieldFixtures.TODAY

    private fun mrz() = dev.kasoti.mrz.MrzParser.parse(MrzBuilder.buildTd3(person, 2026), 2026)

    @Test
    fun `a clean MRZ with matching VIZ passes`() {
        val result = math.evaluate(
            mrz = mrz(),
            viz = VizFields(name = "SHARMA RAMESH", dateOfBirth = "1984-03-17", documentNumber = "K4820913"),
            today = today,
            expectMrz = true,
            track = Track.PASSPORT,
        )
        assertTrue(result.allChecksPassed, "failed: ${result.failedFields}")
    }

    @Test
    fun `a mutated check digit is a failure attributed to the field`() {
        val lines = MrzBuilder.buildTd3(person, 2026)
        val mutated = lines[0].replaceRange(19, 20, if (lines[0][19] == '0') "1" else "0")
        val result = math.evaluate(
            mrz = dev.kasoti.mrz.MrzParser.parse(listOf(mutated, lines[1]), 2026),
            viz = VizFields.EMPTY,
            today = today,
            expectMrz = true,
            track = Track.PASSPORT,
        )
        assertFalse(result.allChecksPassed)
        assertTrue(result.failedFields.contains("BIRTH_DATE"), "FR-M2: per-field fail attribution, got ${result.failedFields}")
    }

    @Test
    fun `an MRZ on a track that should have one but has none is unchecked, not fine`() {
        val result = math.evaluate(mrz = null, viz = VizFields.EMPTY, today = today, expectMrz = true, track = Track.PASSPORT)
        assertFalse(result.allChecksPassed, "the boolean `R-MATH-01` reads must not be true for a check that never ran")
        assertTrue(result.failedFields.contains("MRZ_ABSENT"))
    }

    @Test
    fun `an MRZ that is not expected does not fail the case`() {
        val result = math.evaluate(mrz = null, viz = VizFields.EMPTY, today = today, expectMrz = false, track = Track.AADHAAR)
        assertTrue(result.allChecksPassed, "an Aadhaar has no MRZ; demanding one would GREY every genuine card")
    }

    @Test
    fun `an expired document fails`() {
        val expired = person.copy(expiryDate = IsoDate(2020, 3, 16))
        val result = math.evaluate(
            mrz = dev.kasoti.mrz.MrzParser.parse(MrzBuilder.buildTd3(expired, 2026), 2026),
            viz = VizFields.EMPTY,
            today = today,
            expectMrz = true,
            track = Track.PASSPORT,
        )
        assertTrue(result.expired)
        assertFalse(result.allChecksPassed)
    }

    @Test
    fun `a VIZ typo is drift, not a check-digit failure`() {
        // A VIZ/MRZ disagreement is an AMBER with two strings to compare. Promoting it to
        // R-MATH-01 would turn a printing quirk into a hard RED.
        val result = math.evaluate(
            mrz = mrz(),
            viz = VizFields(name = "SHARMA RAMESH", dateOfBirth = "1984-03-18", documentNumber = "K4820913"),
            today = today,
            expectMrz = true,
            track = Track.PASSPORT,
        )
        assertTrue(result.allChecksPassed, "a drifted DOB in the visual zone is not a check-digit failure")
        assertNotNull(result.vizDrift, "but it is recorded as drift")
        assertEquals("dob", result.vizDrift!!.field)
    }

    @Test
    fun `an implausible date of birth is a date failure, not a parse failure`() {
        val result = math.evaluate(
            mrz = null,
            viz = VizFields(dateOfBirth = "1800-01-01", documentNumber = "ABC1234567"),
            today = today,
            expectMrz = false,
            track = Track.VOTER,
        )
        assertFalse(result.allChecksPassed)
        assertTrue(result.failedFields.contains("DATES"))
    }
}

class QuadTest {

    private fun frame(w: Int, h: Int, isDoc: (Int, Int) -> Boolean): GrayImage =
        GrayImage(w, h, FloatArray(w * h) { i -> if (isDoc(i % w, i / w)) 0.95f else 0.08f })

    @Test
    fun `the auto proposer finds a centred bright rectangle`() {
        val w = 120
        val h = 90
        val gray = frame(w, h) { x, y -> x in 20..99 && y in 15..74 }
        val quad = AutoQuadDetector.propose(gray)
        assertNotNull(quad, "a high-contrast centred card must be found")
        assertEquals(QuadVerdict.OK, quad!!.validate())
    }

    @Test
    fun `the auto proposer refuses a uniform frame rather than inventing a document`() {
        val flat = GrayImage(80, 60, FloatArray(80 * 60) { 0.5f })
        assertNull(AutoQuadDetector.propose(flat), "no document, no quad, and the operator is asked to drag four handles")
    }

    @Test
    fun `the auto proposer refuses a region too small to be a document`() {
        // A logo or a stamp: high contrast, correctly shaped, far too small to crop an MRZ from.
        val gray = frame(120, 90) { x, y -> x in 55..64 && y in 40..49 }
        assertNull(AutoQuadDetector.propose(gray))
    }

    @Test
    fun `a full-frame quad validates`() {
        assertEquals(QuadVerdict.OK, Quad.FULL_FRAME.validate())
        assertEquals(1f, Quad.FULL_FRAME.areaFraction, 0.001f)
    }

    @Test
    fun `a degenerate quad is rejected`() {
        val sliver = Quad(
            Quad.Point(0.1f, 0.1f), Quad.Point(0.9f, 0.1f),
            Quad.Point(0.9f, 0.11f), Quad.Point(0.1f, 0.11f),
        )
        assertTrue(sliver.validate() != QuadVerdict.OK, "a 1%-tall quad OCRs nothing and must not be accepted")
    }

    @Test
    fun `a self-intersecting quad is rejected`() {
        val bowtie = Quad(
            Quad.Point(0.1f, 0.1f), Quad.Point(0.9f, 0.8f),
            Quad.Point(0.9f, 0.1f), Quad.Point(0.1f, 0.8f),
        )
        assertEquals(QuadVerdict.NOT_CONVEX, bowtie.validate())
    }

    @Test
    fun `a quad dragged off the frame is rejected`() {
        val off = Quad(
            Quad.Point(0.1f, 0.1f), Quad.Point(1.5f, 0.1f),
            Quad.Point(1.5f, 0.9f), Quad.Point(0.1f, 0.9f),
        )
        assertEquals(QuadVerdict.OUTSIDE_FRAME, off.validate())
    }

    @Test
    fun `ID-1 and passport aspects are both accepted`() {
        // 85.6 x 54 mm is 1.586; a passport data page is about 1.42.
        for (aspect in listOf(1.586f, 1.42f, 0.8f, 1.4f)) {
            val q = Quad(
                Quad.Point(0f, 0f), Quad.Point(aspect * 0.4f, 0f),
                Quad.Point(aspect * 0.4f, 0.4f), Quad.Point(0f, 0.4f),
            )
            assertEquals(QuadVerdict.OK, q.validate(), "aspect $aspect must be accepted")
        }
    }

    @Test
    fun `the frame corners vote for the background, so a dark table works as well as a light one`() {
        // Document dark, table light: the exact inverse of the bright-card case.
        val w = 100
        val h = 80
        val gray = GrayImage(w, h, FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 20..79 && y in 20..59) 0.05f else 0.9f
        })
        val quad = AutoQuadDetector.propose(gray)
        assertNotNull(quad, "a dark document on a light table is still a document")
    }
}

class CalendarArithmeticTest {

    @Test
    fun `the epoch-day inverse round-trips for the whole proleptic range we can express`() {
        for (year in listOf(1, 99, 1500, 1899, 1970, 2000, 2024, 2100)) {
            for (month in 1..12) {
                val day = 1.coerceAtMost(dev.kasoti.time.CalendarDate.daysInMonth(year, month))
                val date = IsoDate(year, month, day)
                assertEquals(date, CalendarArithmetic.fromEpochDay(date.toEpochDay()), "round trip failed for $date")
            }
        }
    }

    @Test
    fun `adding days crosses month, year and leap boundaries`() {
        val feb28 = IsoDate(2024, 2, 28)
        assertEquals(IsoDate(2024, 2, 29), CalendarArithmetic.plusDays(feb28, 1), "2024 is a leap year")
        assertEquals(IsoDate(2023, 3, 1), CalendarArithmetic.plusDays(IsoDate(2023, 2, 28), 1), "2023 is not")

        val newYearsEve = IsoDate(2025, 12, 31)
        assertEquals(IsoDate(2026, 1, 1), CalendarArithmetic.plusDays(newYearsEve, 1))
    }

    @Test
    fun `adding years clamps 29 February to 28 February`() {
        assertEquals(IsoDate(2025, 2, 28), CalendarArithmetic.plusYears(IsoDate(2024, 2, 29), 1))
        assertEquals(IsoDate(2028, 2, 29), CalendarArithmetic.plusYears(IsoDate(2024, 2, 29), 4))
    }

    @Test
    fun `days between is signed`() {
        assertEquals(9, CalendarArithmetic.daysBetween(IsoDate(2026, 9, 20), IsoDate(2026, 9, 29)))
        assertEquals(-9, CalendarArithmetic.daysBetween(IsoDate(2026, 9, 29), IsoDate(2026, 9, 20)))
    }
}
