package dev.kasoti.android.field

import dev.kasoti.checks.CheckOutcome
import dev.kasoti.checks.DateLogic
import dev.kasoti.checks.FormatValidators
import dev.kasoti.checks.FormatVerdict
import dev.kasoti.checks.VizMrzMatch
import dev.kasoti.fusion.DriftScore
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.Track
import dev.kasoti.mrz.MrzResult
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate

/**
 * The math layer: MRZ check digits + dates + formats + VIZ↔MRZ drift (FR-M2, M3, M4).
 *
 * This class owns no arithmetic. Every verdict it returns comes out of `:core` — `MrzParser`
 * did the 7-3-1, `DateLogic` did the expiry, `FormatValidators` did the Verhoeff, `VizMrzMatch`
 * did the drift. What this adds is the *wiring*: choosing which `:core` call applies to this
 * document family, normalising the outputs into one [MathEvidence], and — the part that
 * actually matters — **refusing to guess**.
 *
 * ## Fail-closed, concretely
 *
 * `allChecksPassed` is `true` only when every check that was *supposed to run* actually ran and
 * agreed. A document with no MRZ on a track whose matrix says MATH is load-bearing produces
 * `allChecksPassed = false` with a failed-field marker, not a silent `true`. That single boolean
 * is what `R-MATH-01` reads in `:core`, so getting it wrong here is the difference between
 * "check digit failed" and "we never looked".
 *
 * ## Per-field attribution (FR-M2)
 *
 * `failedFields` is a set of *field names* taken from `:core`'s `MrzField` enum plus the
 * format-check names, so the finding reads "expected 4, got 7, field BIRTH_DATE" rather than
 * "invalid MRZ". `:core`'s `RedRules` groups findings by exactly this set.
 */
class MathLayer(private val registry: ThresholdRegistry) {

    /**
     * @param mrz the MRZ result, or `null` when the MRZ step produced nothing.
     * @param viz what the visual zone says, as read by the VIZ step.
     * @param today the caller's date. Never read from the device clock inside this class.
     * @param expectMrz whether this document family *should* carry an MRZ, from the track
     *   matrix. This is the flag that turns "absent" into "unchecked".
     */
    fun evaluate(
        mrz: MrzResult?,
        viz: VizFields,
        today: IsoDate,
        expectMrz: Boolean,
        track: Track = Track.UNKNOWN,
    ): MathEvidence {
        val failed = linkedSetOf<String>()
        val drift = mutableListOf<DriftScore>()

        // ---- MRZ check digits (FR-M2) -------------------------------------------------
        val checksPassed: Boolean
        if (mrz == null) {
            checksPassed = !expectMrz
            if (expectMrz) failed += "MRZ_ABSENT"
        } else {
            checksPassed = mrz.allChecksPassed
            mrz.failedChecks.forEach { failed += it.field.name }
            if (mrz.format == dev.kasoti.mrz.MrzFormat.UNKNOWN) failed += "MRZ_SHAPE"
        }

        // ---- expiry / issue / calendar rules (FR-M3, R-MATH-02) -----------------------
        val birth = dateOf(mrz?.birthDate, viz.dateOfBirth, today)
        val expiry = dateOf(mrz?.expiryDate, viz.expiryDate, today)
        val issue = dateOf(null, viz.issueDate, today)
        val dateOutcomes = DateLogic.evaluate(birth, expiry, issue, today)
        val datesFailed = dateOutcomes.any { it.verdict == FormatVerdict.FAIL }
        dateOutcomes.filter { it.verdict == FormatVerdict.FAIL }.forEach { failed += "DATES" }

        // ---- per-family format validators (FR-M3) -------------------------------------
        val formats = formatOutcomes(track, viz)
        val formatsFailed = formats.any { it.verdict == FormatVerdict.FAIL }
        formats.filter { it.verdict == FormatVerdict.FAIL }.forEach { failed += "FORMAT" }

        // ---- VIZ ↔ MRZ drift (FR-M4, A-VIZ-01) ----------------------------------------
        if (mrz != null && mrz.format != dev.kasoti.mrz.MrzFormat.UNKNOWN) {
            val tolerance = registry[ThresholdName.VIZ_DRIFT_TOLERANCE].toFloat()
            drift += driftOf("name", VizMrzMatch.nameScore(viz.name, mrz.name.normalized), viz.name, mrz.name.normalized, tolerance, failed)
            drift += driftOf(
                "dob",
                VizMrzMatch.dateScore(viz.dateOfBirth, mrz.birthDate.orEmpty(), today.year),
                viz.dateOfBirth,
                mrz.birthDate.orEmpty(),
                tolerance,
                failed,
            )
            drift += driftOf(
                "document_number",
                VizMrzMatch.numberScore(viz.documentNumber, mrz.documentNumber.orEmpty()),
                viz.documentNumber,
                mrz.documentNumber.orEmpty(),
                tolerance,
                failed,
            )
        }

        return MathEvidence(
            // A drift on a non-check-digit field is an AMBER (A-VIZ-01), not a check-digit
            // failure, so it must not clear `allChecksPassed` to `false` — that would promote
            // a cosmetic VIZ typo into an R-MATH-01 hard RED. `:core` reads the drift
            // separately for exactly this reason.
            allChecksPassed = checksPassed && !datesFailed && !formatsFailed,
            failedFields = failed,
            expired = dateOutcomes.any { it.reason.contains("expired") },
            impossibleDate = datesFailed && birth == null && expiry == null,
            issueAfterExpiry = dateOutcomes.any { it.reason.contains("after expiry") },
            vizDrift = worstDrift(drift),
        )
    }

    /**
     * `@return` the drift, recorded as a named field when it is outside tolerance.
     *
     * Recorded rather than thrown: a drifted field is an AMBER with two strings to compare
     * side by side, which is a far better conversation at a counter than a rejection.
     */
    private fun driftOf(
        field: String,
        score: Float,
        left: String,
        right: String,
        tolerance: Float,
        sink: MutableSet<String>,
    ): DriftScore {
        val drift = VizMrzMatch.drift(field, score, left, right)
        if (!drift.identical && score < 1f - tolerance) sink += "VIZ:$field"
        return drift
    }

    /** The worst drift is what the evidence field can carry; `:core` weighs it per field. */
    private fun worstDrift(drifts: List<DriftScore>): DriftScore? =
        drifts.minByOrNull { it.score }

    /**
     * Which format validators apply, and only those.
     *
     * The first cut of this ran *every* validator over the visual zone and took the worst
     * result. That is wrong in a way the tests caught immediately: a passport number
     * `K4820913` is not a voter ID, so `validateVoterId` returns FAIL on a perfectly good
     * passport and the case is failed by a check that was never applicable. Running a check
     * that does not apply and treating its failure as a finding is how a system accumulates
     * false REDs.
     *
     * So the routed track selects the validator, exactly as `TrackMatrix` selects which layers
     * are load-bearing. A track with no public check digit — passport, paper ID, unknown —
     * runs no format validator at all, because there is honestly nothing to verify; the date
     * rules above still apply. `:core`'s `FormatValidators.validateDl` says as much in its own
     * doc comment, and the app is not allowed to overclaim on its behalf.
     */
    private fun formatOutcomes(track: Track, viz: VizFields): List<CheckOutcome> = buildList {
        when (track) {
            Track.AADHAAR -> viz.aadhaarCandidate?.let { add(FormatValidators.validateAadhaar(it)) }
            Track.VOTER -> viz.documentNumber.takeIf { it.isNotBlank() }
                ?.let { add(FormatValidators.validateVoterId(it)) }
            Track.DRIVING_LICENCE -> viz.documentNumber.takeIf { it.isNotBlank() }
                ?.let { add(FormatValidators.validateDl(it)) }
            // No public check digit exists for these families offline. Running a format rule
            // anyway would manufacture findings, which is the overclaim EVAL.md exists to
            // prevent.
            Track.PASSPORT, Track.PAPER_ID, Track.UNKNOWN -> Unit
        }
    }

    /**
     * Read a date from either zone.
     *
     * The MRZ wins when both are present and disagreeing — no: they cannot be made to agree
     * here, and the disagreement is already recorded as drift. For the *date* the MRZ is
     * preferred because it is the machine-checked rendering, and any disagreement is already
     * captured by the drift list. The VIZ is the fallback for a document with no MRZ.
     */
    private fun dateOf(mrzText: String?, vizText: String?, reference: IsoDate): IsoDate? {
        mrzText?.takeIf { it.isNotBlank() }?.let { text ->
            // The MRZ carries `YYMMDD`; `VizMrzMatch.normaliseDate` resolves both shapes and
            // needs the same reference year `:core` was given for the parse.
            VizMrzMatch.normaliseDate(text, reference.year)?.let { return IsoDate.parse(it) }
        }
        vizText?.takeIf { it.isNotBlank() }?.let { text ->
            VizMrzMatch.normaliseDate(text, reference.year)?.let { return IsoDate.parse(it) }
        }
        return null
    }

    /** The `R-MATH-02` codes a date rule can contribute, for a caller that wants them directly. */
    fun dateCodes(outcomes: List<CheckOutcome>): List<FindingCode> =
        outcomes.flatMap { it.findings }.distinct()
}
