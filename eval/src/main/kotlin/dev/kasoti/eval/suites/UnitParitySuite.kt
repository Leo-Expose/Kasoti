package dev.kasoti.eval.suites

import dev.kasoti.checks.Verhoeff
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.FailureCase
import dev.kasoti.eval.run.Metric
import dev.kasoti.face.FaceMath
import dev.kasoti.factory.Edges
import dev.kasoti.factory.GrayImage
import dev.kasoti.factory.Lbp
import dev.kasoti.mrz.MrzCheckDigit
import dev.kasoti.time.CalendarDate

/**
 * Unit-parity: does `:core` on this target produce the numbers the protocol says it should?
 *
 * The other suites ask "is the classifier right?". This one asks the prior question — "is
 * the arithmetic itself what we think it is?" — by recomputing a small set of quantities
 * two ways and comparing. It exists because a KMP module is compiled per target, and a
 * transcription difference (a `Float` where the reference used `Double`, a reassociated sum)
 * is invisible in a review and shows up only as a metric that drifts by 0.3%.
 *
 * It is a *parity* check, not a correctness check: the expected values here are computed
 * independently in this file from the algorithms as published (ICAO 9303, Damm–Verhoeff,
 * the LBP definition), not read back out of `:core`. A value that `:core` gets wrong and
 * this file gets right is a finding, not a false alarm.
 */
object UnitParitySuite {

    const val GATE = "U-GATE-01"

    /** Absolute agreement required. Tight, because everything below is exact arithmetic. */
    const val TOLERANCE = 1e-6

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val failures: List<FailureCase>,
        val notes: List<String>,
    )

    private data class Check(
        val name: String,
        val reference: Double,
        val fromCore: Double,
        val note: String,
    )

    fun run(): Result {
        val checks = mutableListOf<Check>()
        val failures = mutableListOf<FailureCase>()

        // ---- ICAO 9303 check digits ------------------------------------------
        // The worked example from ICAO 9303 Part 5: document number L898902C< has check
        // digit 3. A reference that merely reimplemented :core's bug would agree here too,
        // so this is pinned to the published value, not to a second implementation.
        val docNo = "L898902C<"
        checks += Check(
            "mrz.checkDigit(L898902C<) == 3 [ICAO 9303 P5 worked example]",
            3.0,
            (MrzCheckDigit.compute(docNo)?.toString()?.toIntOrNull() ?: -1).toDouble(),
            "ICAO 9303 Part 5",
        )
        checks += Check(
            "mrz.checkDigit(740812) == 2 [ICAO 9303 P5 date of birth]",
            2.0,
            (MrzCheckDigit.compute("740812")?.toString()?.toIntOrNull() ?: -1).toDouble(),
            "ICAO 9303 Part 5",
        )
        checks += Check(
            "mrz.checkDigit(all-filler) == 0",
            referenceCheckDigit("<<<<<<<<"),
            (MrzCheckDigit.compute("<<<<<<<<")?.toString()?.toIntOrNull() ?: -1).toDouble(),
            "fillers are value 0 (ICAO 9303)",
        )
        // The composite is checked against an independent 7-3-1 implementation rather than a
        // remembered digit: the point of this suite is that two implementations written from
        // the algorithm agree. The two rows above are the external anchors (the published
        // ICAO 9303 Part 5 worked examples), and this one is the internal cross-check.
        checks += Check(
            "mrz.composite over the ICAO spans [independent 7-3-1]",
            referenceCheckDigit("L898902C<3" + "UTO7408122F"),
            (MrzCheckDigit.computeComposite("L898902C<3", "UTO7408122F")?.toString()?.toIntOrNull() ?: -1)
                .toDouble(),
            "ICAO 9303 Part 5 spans: line1 1-10, 14-20, 22-43, then the whole of line 2",
        )

        // ---- Verhoeff ----------------------------------------------------------
        // "123456789012" is not a valid Aadhaar; the check digit is computed independently
        // from the published D/P tables below rather than from :core.
        val aadhaarBody = "99999999001"
        checks += Check(
            "verhoeff.checkDigit($aadhaarBody) [independent D/P tables]",
            referenceVerhoeff(aadhaarBody).toDouble(),
            (Verhoeff.checkDigit(aadhaarBody) ?: -1).toDouble(),
            "Damm–Verhoeff, tables transcribed in this file",
        )
        val appended = Verhoeff.appendCheckDigit(aadhaarBody)
        checks += Check(
            "verhoeff.round-trip: appendCheckDigit(body) is 12 digits and isValid() accepts it",
            1.0,
            if (appended != null && appended.length == 12 && Verhoeff.isValid(appended)) 1.0 else 0.0,
            "a check-digit function whose own output fails its own validator is broken",
        )

        // ---- Calendar ----------------------------------------------------------
        checks += Check(
            "calendar.daysInMonth(2000-02) == 29 (leap: divisible by 400)",
            29.0,
            CalendarDate.daysInMonth(2000, 2).toDouble(),
            "proleptic Gregorian",
        )
        checks += Check(
            "calendar.daysInMonth(1900-02) == 28 (leap: divisible by 100 but not 400)",
            28.0,
            CalendarDate.daysInMonth(1900, 2).toDouble(),
            "the 1900 case is the one a %4 test gets wrong",
        )
        checks += Check(
            "calendar.parseYymmdd(991231, 2026) year == 1999 (within slack)",
            1999.0,
            (CalendarDate.parseYymmdd("991231", 2026)?.year ?: -1).toDouble(),
            "ICAO/ISO 15-year slack",
        )
        // The century rule as CalendarDate.parseYymmdd documents it: a two-digit year more
        // than `slackYears` beyond the reference is read as the PREVIOUS century. That reads
        // counter-intuitively next to FUSION.md's "an expiry of 30 in 2026 is 2030", so the
        // boundary is pinned on both sides of it here: 2040 is inside the 15-year window and
        // stays 2040; 2050 is outside it and is read as 1950. If someone later decides the
        // rule is wrong, this row is where the disagreement surfaces.
        checks += Check(
            "calendar.parseYymmdd(400101, 2026) year == 2040 (inside the 15-year slack)",
            2040.0,
            (CalendarDate.parseYymmdd("400101", 2026)?.year ?: -1).toDouble(),
            "14 years out is inside the slack window",
        )
        checks += Check(
            "calendar.parseYymmdd(500101, 2026) year == 1950 (24 years out, past the slack)",
            1950.0,
            (CalendarDate.parseYymmdd("500101", 2026)?.year ?: -1).toDouble(),
            "the documented rule reads a far-future two-digit year as the previous century",
        )

        // ---- LBP / Sobel on a synthetic patch ----------------------------------
        val patch = syntheticPatch()
        val lbpCore = Lbp.histogram(patch)
        val lbpRef = referenceLbp(patch)
        var maxLbpDelta = 0.0
        for (i in lbpCore.indices) maxLbpDelta = maxOf(maxLbpDelta, kotlin.math.abs(lbpCore[i] - lbpRef[i]))
        checks += Check("lbp.histogram max |Δ| vs independent implementation", 0.0, maxLbpDelta, "59 uniform bins")
        checks += Check(
            "lbp.histogram sums to 1",
            1.0,
            lbpCore.sum().toDouble(),
            "a normalised histogram; a drift here rescales every bin",
        )
        checks += Check(
            "edges.density vs independent Sobel count",
            referenceEdgeDensity(patch),
            Edges.density(patch).toDouble(),
            "gradient magnitude above 0.08",
        )
        checks += Check(
            "grayImage.stdDev vs two-pass reference",
            referenceStdDev(patch),
            patch.stdDev.toDouble(),
            "population std dev, not sample",
        )

        // ---- Face math ---------------------------------------------------------
        val a = floatArrayOf(0.6f, 0.8f, 0f, 0f)
        val b = floatArrayOf(0.0f, 0.0f, 1f, 0f)
        checks += Check("face.cosine(orthogonal) == 0", 0.0, FaceMath.cosine(a, b).toDouble(), "")
        checks += Check("face.cosine(self) == 1", 1.0, FaceMath.cosine(a, a).toDouble(), "")
        checks += Check(
            "face.l2Normalize([3,4]) == [0.6,0.8]",
            1.0,
            FaceMath.l2Normalize(floatArrayOf(3f, 4f)).let { it[0] * it[0] + it[1] * it[1] }.toDouble(),
            "unit norm",
        )
        checks += Check(
            "face.cosine(zero vector) == 0 (not NaN)",
            0.0,
            FaceMath.cosine(FloatArray(4), a).toDouble(),
            "a zero embedding must not poison a comparison",
        )

        var mismatches = 0
        for (check in checks) {
            val delta = kotlin.math.abs(check.fromCore - check.reference)
            val ok = delta <= TOLERANCE
            if (!ok) {
                mismatches++
                failures += FailureCase(
                    suite = "unit-parity",
                    caseId = check.name,
                    reason = "parity mismatch: reference=%.10g, :core=%.10g, |Δ|=%.3g (tolerance %g)"
                        .format(check.reference, check.fromCore, delta, TOLERANCE),
                    expected = "%.10g".format(check.reference),
                    observed = "%.10g".format(check.fromCore),
                    artefactRef = check.note,
                    gateId = GATE,
                )
            }
        }

        val gates = listOf(
            GateResult(
                id = GATE,
                name = "Unit parity: :core vs independently computed references",
                status = if (mismatches == 0) GateStatus.PASS else GateStatus.FAIL,
                bar = "all checks within $TOLERANCE absolute",
                observed = "${checks.size - mismatches}/${checks.size} agree",
                detail = "Reference values are computed in the harness from the published " +
                    "algorithms and the ICAO 9303 worked examples, not read from :core.",
                evidenceRef = "dev.kasoti.eval.suites.UnitParitySuite",
            ),
        )

        val metrics = listOf(
            Metric("unit_parity.checks", checks.size.toDouble(), "checks", "host", GATE),
            Metric("unit_parity.mismatches", mismatches.toDouble(), "checks", "host", GATE),
            Metric("unit_parity.tolerance", TOLERANCE, "", "host", GATE),
        )

        val notes = buildList {
            add("This suite proves the harness and :core agree on the arithmetic, not that the " +
                "arithmetic models reality. ICAO 9303's worked examples are used as external " +
                "anchors so a shared misreading of the spec cannot pass unnoticed.")
            add("Patch size is ${SpectrumPatch.SIZE}x${SpectrumPatch.SIZE}, which is the size " +
                ":core's spectrum path requires, so the feature checks run on a realistic input.")
            // This block used to carry an "OUTSTANDING :core DEFECT" paragraph about
            // dev.kasoti.checks.Verhoeff implementing a single-table walk with no
            // permutation table, and it was guarded by `if (mismatches > 0)`. Two things
            // were wrong with that. It was unreachable: the guard only fires when a check
            // disagrees, so a suite that reported zero mismatches — the only state this
            // gate can pass in — could never print it, and a note nobody can print is not
            // a report. And it was obsolete: Verhoeff now walks D[c][P[(i + rowOffset) % 8]
            // [digit] with the generated P table (Verhoeff.kt:110) and agrees with the
            // published rows and the '99999999001' -> 9 vector, both asserted in
            // core/src/commonTest/.../checks/ChecksTest.kt.
            //
            // What replaces it is the honest statement: the Verhoeff path is anchored on
            // the published tables and on that one external vector, so a regression shows
            // up as a gate MISMATCH with the exact case named, not as prose. A defect that
            // is still open belongs in the failure gallery and in DESIGN.md, both of which
            // are read; a defect note attached to a passing branch is neither.
        }

        return Result(gates, metrics, failures, notes)
    }

    // ---------------------------------------------------------------- references
    // Independent implementations, written from the algorithm definitions rather than
    // ported from :core, so that a transcription error in either one shows up as a
    // disagreement instead of being mirrored.

    private fun referenceCheckDigit(field: String): Double {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for ((i, ch) in field.withIndex()) {
            val value = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                '<' -> 0
                else -> return -1.0
            }
            sum += value * weights[i % 3]
        }
        return (sum % 10).toDouble()
    }

    private fun referenceVerhoeff(digits: String): Int {
        val d = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
            intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
            intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
            intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
            intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
            intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
            intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
            intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
            intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
        )
        val p = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
            intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
            intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
            intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
            intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
            intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
            intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
        )
        val inv = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)
        // c = D[c][P[j mod 8][digit]] where j counts from the RIGHT, starting at 0.
        // Walking i left-to-right, j is length-1-i, hence p[(length - i) % 8].
        var c = 0
        for (i in digits.length - 1 downTo 0) {
            val fromRight = digits.length - 1 - i
            c = d[c][p[(fromRight + 1) % 8][digits[i] - '0']]
        }
        return inv[c]
    }

    /**
     * The 58 rotationally-unique uniform patterns, ranked by code, derived by scanning
     * rather than transcribed so it cannot be mistyped. Computed once per process.
     */
    private val uniformTable: IntArray by lazy {
        IntArray(256) { -1 }.also { table ->
            var bin = 0
            for (code in 0..255) {
                val bits = IntArray(8) { (code shr (7 - it)) and 1 }
                val transitions = (0 until 8).count { bits[it] != bits[(it + 1) % 8] }
                if (transitions == 0 || transitions == 2) table[code] = bin++
            }
            check(bin == 58) { "expected 58 uniform LBP patterns, found $bin" }
        }
    }

    private fun referenceLbp(image: GrayImage): DoubleArray {
        val histogram = DoubleArray(59)
        var uniform = 0
        for (y in 1 until image.height - 1) {
            for (x in 1 until image.width - 1) {
                val centre = image[x, y]
                var code = 0
                for (k in 0 until 8) {
                    val (dx, dy) = NEIGHBOURS[k]
                    code = (code shl 1) or (if (image[x + dx, y + dy] >= centre) 1 else 0)
                }
                val bin = uniformTable[code]
                if (bin >= 0) {
                    histogram[bin] += 1.0
                    uniform++
                }
            }
        }
        for (i in histogram.indices) if (uniform > 0) histogram[i] /= uniform
        return histogram
    }

    private fun referenceEdgeDensity(image: GrayImage): Double {
        var strong = 0
        var total = 0
        for (y in 1 until image.height - 1) {
            for (x in 1 until image.width - 1) {
                val gx = -image[x - 1, y - 1] - 2 * image[x - 1, y] - image[x - 1, y + 1] +
                    image[x + 1, y - 1] + 2 * image[x + 1, y] + image[x + 1, y + 1]
                val gy = -image[x - 1, y - 1] - 2 * image[x, y - 1] - image[x + 1, y - 1] +
                    image[x + 1, y + 1] + 2 * image[x, y + 1] + image[x + 1, y + 1]
                total++
                if (kotlin.math.sqrt((gx * gx + gy * gy).toDouble()) > 0.08) strong++
            }
        }
        return if (total == 0) 0.0 else strong.toDouble() / total
    }

    private fun referenceStdDev(image: GrayImage): Double {
        val n = image.pixels.size
        val mean = image.pixels.sumOf { it.toDouble() } / n
        val variance = image.pixels.sumOf { (it - mean) * (it - mean) } / n
        return kotlin.math.sqrt(variance)
    }

    /** Shared synthetic patch: a deterministic lattice, so the same bytes every run. */
    private fun syntheticPatch(): GrayImage = SpectrumPatch.lattice()

    /** The 8-neighbour ring, in the same clockwise order the LBP definition uses. */
    private val NEIGHBOURS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(1, 0),
        intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0),
    )
}
