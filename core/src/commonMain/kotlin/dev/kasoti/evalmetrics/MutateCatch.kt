package dev.kasoti.evalmetrics

import dev.kasoti.mrz.MrzCorpus
import dev.kasoti.mrz.MrzCorpusCase
import dev.kasoti.mrz.MrzFormat
import dev.kasoti.mrz.MrzMutation
import dev.kasoti.mrz.MrzParser

/**
 * A corpus row that check-digit arithmetic provably cannot catch, and why.
 *
 * These are published as first-class output, not folded away. TD1 has no composite check
 * digit and sex/nationality sit outside every protected span in both formats, so a
 * recomputed field check digit is genuinely invisible to the MRZ layer — the substrate the
 * signed QR, the chip, the macro and the face layers exist to cover. A catch rate that
 * counted them would be a number about the corpus generator rather than about the system.
 */
data class BlindCase(
    val id: String,
    val format: MrzFormat,
    val mutation: MrzMutation,
    val reason: String,
) {
    fun toJson(): MetricJson.Obj = jsonOf(
        "id" to MetricJson.Str(id),
        "format" to MetricJson.Str(format.name),
        "mutation" to MetricJson.Str(mutation.name),
        "reason" to MetricJson.Str(reason),
    )
}

data class MutateCatchReport(
    val total: Int,
    val detectable: Int,
    val caught: Int,
    /** Caught / detectable. Blind cases are excluded by construction. */
    val catchRate: Double,
    /** Rows with no mutation, which a gate must *not* flag. */
    val cleanCases: Int,
    val falsePositives: List<String>,
    val blindCases: List<BlindCase>,
) {
    val blindCount: Int get() = blindCases.size

    val blindRate: Double get() = if (total == 0) 0.0 else blindCount.toDouble() / total

    /** Blind reasons collapsed to counts, for the summary table. */
    val blindReasons: Map<String, Int> = blindCases.groupingBy { it.reason }.eachCount()

    /** False positives on clean documents are a gate failure even when the catch rate is perfect. */
    val precise: Boolean get() = falsePositives.isEmpty()

    /** A rate over zero detectable cases is not a pass, it is a missing measurement. */
    val publishable: Boolean get() = detectable > 0

    fun toJson(): MetricJson.Obj = jsonOf(
        "total" to MetricJson.Num(total.toDouble()),
        "detectable" to MetricJson.Num(detectable.toDouble()),
        "caught" to MetricJson.Num(caught.toDouble()),
        "catchRate" to MetricJson.Num(catchRate),
        "cleanCases" to MetricJson.Num(cleanCases.toDouble()),
        "falsePositives" to MetricJson.Arr(falsePositives.map { MetricJson.Str(it) }),
        "blindCount" to MetricJson.Num(blindCount.toDouble()),
        "blindRate" to MetricJson.Num(blindRate),
        "blindReasons" to MetricJson.Obj(blindReasons.mapValues { MetricJson.Num(it.value.toDouble()) }),
        "blindCases" to MetricJson.Arr(blindCases.map { it.toJson() }),
        "publishable" to MetricJson.Bool(publishable),
    )
}

/**
 * MRZ mutate-catch over the D-MRZ corpus (EVAL.md §2, SPEC.md §7).
 *
 * Three populations are reported side by side, because collapsing them is how a 100% gate
 * gets declared on a corpus that was mostly unmutated:
 *
 * * **detectable** rows (`expectedCaught = true`) — the population the catch rate is over;
 * * **blind** rows (`expectedCaught = false` *with* a recorded reason) — published with
 *   reasons, never counted in the rate;
 * * **clean** rows — valid documents that must not be flagged, which is the precision half
 *   of the same gate.
 */
object MutateCatch {

    fun evaluate(cases: List<MrzCorpusCase>, referenceYear: Int): MutateCatchReport {
        val detectable = mutableListOf<MrzCorpusCase>()
        val clean = mutableListOf<MrzCorpusCase>()
        val blind = mutableListOf<BlindCase>()

        for (case in cases) {
            when {
                case.expectedCaught -> detectable += case
                case.blindSpotReason != null -> blind += BlindCase(
                    id = case.id,
                    format = case.format,
                    mutation = case.mutation,
                    reason = case.blindSpotReason,
                )

                else -> clean += case
            }
        }

        val caught = detectable.count { row -> MrzCorpus.detect(parse(row, referenceYear)) }
        val falsePositives = clean.filter { MrzCorpus.detect(parse(it, referenceYear)) }.map { it.id }

        return MutateCatchReport(
            total = cases.size,
            detectable = detectable.size,
            caught = caught,
            catchRate = if (detectable.isEmpty()) 0.0 else caught.toDouble() / detectable.size,
            cleanCases = clean.size,
            falsePositives = falsePositives,
            blindCases = blind.sortedBy { it.id },
        )
    }

    private fun parse(case: MrzCorpusCase, referenceYear: Int) = MrzParser.parse(case.lines, referenceYear)
}
