package dev.kasoti.android.field

import dev.kasoti.checks.CheckOutcome
import dev.kasoti.checks.DateLogic
import dev.kasoti.checks.FormatValidators
import dev.kasoti.checks.FormatVerdict
import dev.kasoti.checks.VizMrzMatch
import dev.kasoti.fusion.DriftScore
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.mrz.MrzCheck
import dev.kasoti.mrz.MrzField
import dev.kasoti.mrz.MrzFormat
import dev.kasoti.mrz.MrzParser
import dev.kasoti.mrz.MrzResult
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate

/**
 * What the operator can see and the MRZ says, side by side.
 *
 * Both zones are needed to check either: the MRZ is the machine-checked rendering and the VIZ is
 * what an attacker edits first, and a disagreement between them is the whole point (FR-M4).
 */
data class VizFields(
    val name: String = "",
    val dateOfBirth: String = "",
    val documentNumber: String = "",
    val expiryDate: String = "",
    val issueDate: String = "",
    val aadhaarCandidate: String? = null,
) {
    val isEmpty: Boolean
        get() = name.isBlank() && dateOfBirth.isBlank() && documentNumber.isBlank() &&
            aadhaarCandidate == null

    companion object {
        val EMPTY = VizFields()
    }
}

/**
 * OCR → MRZ, with the one correction DESIGN.md §5 sanctions (FR-M2).
 *
 * ML Kit's bundled model reads `O` for `0` and vice versa in a monospaced MRZ roughly as often
 * as any OCR does, and the ICAO check digit exists precisely to catch that class of error. So the
 * one repair the design permits is applied *and verified*: a substitution in a **check-digit
 * position or its span** is tried, and it is only kept if the recomputed check digit agrees.
 *
 * What this class will not do, because DESIGN.md §5 says "never fuzzy-accept a failed check
 * digit (fail-closed)": correct a span whose check digit still disagrees afterwards, blend
 * confusable characters anywhere else, or invent a value where the OCR found none. A check
 * digit that cannot be made to agree is reported as `R-MATH-01` with the expected and observed
 * values, which is what makes the failure attributable to a field (FR-M2's "per-field fail
 * attribution").
 */
class MrzExtractor(
    private val registry: ThresholdRegistry,
    /** MRZ rows in reading order. A badly-ordered pair is a normal OCR outcome, not an error. */
    private val alphabet: MrzAlphabet = MrzAlphabet.OcrAlphabet,
) {

    /** The recognised MRZ rows plus how they were obtained. `manual` is an audit fact (D5). */
    data class Extraction(
        val result: MrzResult,
        val rows: List<String>,
        val manual: Boolean,
        val repairedFields: Set<MrzField>,
        /** Mean OCR confidence, or `null` for a manual entry. Never invented. */
        val meanConfidence: Float?,
    ) {
        val usable: Boolean get() = result.usable
        val repairsApplied: Boolean get() = repairedFields.isNotEmpty()
    }

    /**
     * @param ocrLines raw OCR lines, in any order and with any junk. Blanks are dropped.
     * @param referenceYear the caller's clock year, for two-digit MRZ dates.
     * @param manual `true` when the officer typed the rows. Recorded, never presented as OCR.
     */
    fun extract(ocrLines: List<String>, referenceYear: Int, manual: Boolean = false, confidence: Float? = null): Extraction {
        val candidates = candidatesFor(ocrLines)
        if (candidates.isEmpty()) {
            return Extraction(
                result = MrzParser.parse(emptyList(), referenceYear),
                rows = emptyList(),
                manual = manual,
                repairedFields = emptySet(),
                meanConfidence = confidence,
            )
        }

        val best = candidates.maxByOrNull { it.rows.size }!!
        val parsed = MrzParser.parse(best.rows, referenceYear)
        val repaired = repair(parsed, referenceYear)

        return Extraction(
            result = repaired.result,
            rows = best.rows,
            manual = manual,
            repairedFields = repaired.fields,
            meanConfidence = confidence,
        )
    }

    /**
     * Every plausible row-set the OCR text could contain.
     *
     * An MRZ is either two 44-character lines or three 30-character ones, and OCR frequently
     * splits or joins them, drops a character, or interleaves them with a line of the visual
     * zone. Rather than trying to repair the text — which is where "fuzzy accept" creeps in —
     * the text is *sliced* into candidate windows and each is parsed on its own merits. A window
     * that is a real TD3/TD1 will be detected as one by `MrzFormat.detect`; anything else fails
     * structurally and is discarded, not repaired.
     */
    private fun candidatesFor(lines: List<String>): List<Candidate> {
        val cleaned = lines.map { it.uppercase().filter { it in ALLOWED } }
            .filter { it.isNotBlank() }
        val out = mutableListOf<Candidate>()

        // Bucket by line width, then take windows *within* a bucket with a bounded gap.
        //
        // The gap is the whole trick. OCR frequently interleaves a line of the visual zone
        // between the two MRZ rows — a passport data page has a printed name right above the
        // machine-readable zone — so requiring the two 44-character rows to be adjacent fails
        // on real documents. Bounding the gap keeps the scan cheap and keeps a whole page of
        // unrelated text from being paired up arbitrarily.
        for ((width, count) in WINDOW_SHAPES) {
            val indices = cleaned.indices.filter { cleaned[it].length == width }
            for (start in indices.indices) {
                val window = indices.subList(start, (start + count).coerceAtMost(indices.size))
                if (window.size < count) break
                if (window.last() - window.first() > MAX_LINE_GAP * (count - 1)) continue
                out += Candidate(window.map { cleaned[it] })
            }
        }

        // Last resort: the whole OCR text collapsed onto one line, for engines that drop the
        // line breaks entirely. A window scan recovers the MRZ if the characters are there and
        // the widths came out right; anything else fails `MrzFormat.detect` and is discarded
        // rather than repaired.
        val joined = cleaned.joinToString("")
        for (width in WIDTHS) {
            if (joined.length < width) continue
            for (offset in 0..(joined.length - width)) {
                out += Candidate(listOf(joined.substring(offset, offset + width)))
            }
        }
        return out
    }

    /**
     * The sanctioned `0 <-> O` repair, applied span by span and accepted only on proof.
     *
     * The repaired span comes back from `:core` as [dev.kasoti.mrz.MrzCheck.span], so this class
     * never has to know where a field sits in a TD1 or TD3 line. That is not a stylistic
     * preference: the column table lives in `MrzParser` and is described there as normative,
     * and a second copy of it in an app module is a second thing to get wrong when the
     * standard is re-read.
     *
     * @return the repaired result and the fields whose value actually changed. A field whose
     *   check digit still fails is left exactly as the OCR read it, so `R-MATH-01` names the
     *   real disagreement instead of a repair that quietly failed.
     */
    internal fun repair(parsed: MrzResult, referenceYear: Int): Repair {
        if (parsed.format == MrzFormat.UNKNOWN) return Repair(parsed, emptySet())
        val repairedFields = mutableSetOf<MrzField>()
        val repairedSpans = mutableMapOf<MrzField, String>()

        val repairedChecks = parsed.checks.map { check ->
            if (check.ok) return@map check
            val fixed = fixSpan(check)
            if (fixed != null) {
                repairedFields += check.field
                repairedSpans[check.field] = fixed.span
                check.copy(span = fixed.span, expected = fixed.expected, observed = fixed.observed)
            } else {
                check
            }
        }

        // Field values follow their spans. If the document-number span was repaired, the parsed
        // document number is the *un*repaired text, and comparing the VIZ against it would
        // report a drift that the OCR did not cause.
        val rebuilt = parsed.copy(
            checks = repairedChecks,
            documentNumber = if (MrzField.DOCUMENT_NUMBER in repairedFields) {
                repairedSpans[MrzField.DOCUMENT_NUMBER]?.let(::cleanValue)
            } else {
                parsed.documentNumber
            },
            // `MrzResult.birthDate` / `expiryDate` are the raw `YYMMDD` text, not a resolved
            // date: `:core` hands the century rule to `VizMrzMatch.normaliseDate`, which
            // already takes a reference year. So the repaired span replaces the text verbatim
            // and this class never resolves a two-digit year itself.
            birthDate = if (MrzField.BIRTH_DATE in repairedFields) {
                repairedSpans[MrzField.BIRTH_DATE] ?: parsed.birthDate
            } else {
                parsed.birthDate
            },
            expiryDate = if (MrzField.EXPIRY_DATE in repairedFields) {
                repairedSpans[MrzField.EXPIRY_DATE] ?: parsed.expiryDate
            } else {
                parsed.expiryDate
            },
        )
        return Repair(rebuilt, repairedFields)
    }

    private data class SpanFix(val span: String, val expected: Char?, val observed: Char?)

    /** @return the repaired span, or `null` when no single-character swap makes the digit agree. */
    private fun fixSpan(check: MrzCheck): SpanFix? {
        for (i in check.span.indices) {
            for (replacement in alphabet.swapsFor(check.span[i])) {
                val candidate = check.span.substring(0, i) + replacement + check.span.substring(i + 1)
                val digit = dev.kasoti.mrz.MrzCheckDigit.compute(candidate) ?: continue
                if (check.observed != null && digit == check.observed) {
                    return SpanFix(candidate, digit, check.observed)
                }
            }
        }
        return null
    }

    /** Filler-stripped and whitespace-collapsed, matching what `:core` hands back. */
    private fun cleanValue(span: String): String? =
        span.replace('<', ' ').trim().replace(Regex("\\s+"), " ").ifEmpty { null }

    private data class Candidate(val rows: List<String>)

    internal data class Repair(val result: MrzResult, val fields: Set<MrzField>)

    companion object {
        const val TD3_LENGTH = 44
        const val TD1_LENGTH = 30
        private val ALLOWED = ('0'..'9') + ('A'..'Z') + '<'

        /**
         * The only two line widths ICAO 9303 defines, with the number of rows each implies.
         * Anything else is not an MRZ row — a 30-character filler run on a data page, for
         * instance, is a shape `:core` would reject anyway.
         */
        private val WINDOW_SHAPES = listOf(TD3_LENGTH to 2, TD1_LENGTH to 3)
        private val WIDTHS = WINDOW_SHAPES.map { it.first }.toSet()

        /**
         * How many non-MRZ lines may sit between two MRZ rows.
         *
         * Three is chosen from what a document actually looks like: a passport's visual zone is
         * one or two printed lines above the MRZ, and ML Kit will sometimes split a printed
         * line in two. Beyond that, pairing two rows is guesswork and the manual-entry
         * fallback (D5) is the honest answer.
         */
        const val MAX_LINE_GAP = 3
    }
}

/**
 * The MRZ character alphabet and the *only* two substitutions the app will ever make.
 *
 * A value, not a `when` buried in the extractor, so the rule is reviewable in one place and so a
 * future "let us also fix `1↔I`" proposal has to change a documented list rather than a
 * comparison.
 */
interface MrzAlphabet {
    /** Characters the OCR is allowed to emit at all. Anything else is dropped, not repaired. */
    fun scrub(raw: String): String

    /** The characters [c] may be replaced with. Empty for an already-plausible character. */
    fun swapsFor(c: Char): List<Char>

    /**
     * The `0 ↔ O` pair, and nothing else.
     *
     * The MRZ alphabet is `A-Z0-9<`, so an `O` in a numeric position is *always* an OCR error
     * and a `0` in an alphabetic position is *always* an OCR error. Every other confusable pair
     * has at least one legitimate use, which is why they are excluded: `1/I`, `2/Z`, `5/S` and
     * `8/B` all occur in genuine document numbers and names, so "repairing" them would corrupt
     * a valid document more often than it would fix a broken one.
     */
    object OcrAlphabet : MrzAlphabet {
        override fun scrub(raw: String): String = raw.uppercase()

        override fun swapsFor(c: Char): List<Char> = when (c) {
            '0' -> listOf('O')
            'O' -> listOf('0')
            else -> emptyList()
        }
    }
}
