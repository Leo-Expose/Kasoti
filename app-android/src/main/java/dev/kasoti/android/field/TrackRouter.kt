package dev.kasoti.android.field

import dev.kasoti.checks.FormatValidators
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.mrz.MrzFormat
import dev.kasoti.mrz.MrzResult

/**
 * The observable facts the router is allowed to reason about (FR-M1: "rules only").
 *
 * A closed list on purpose. FR-M1 says *signals*; the temptation is to pass the whole capture
 * and let the router look at pixels, and then the router becomes a second, untestable
 * implementation of the OCR and the classifier. Everything here is a value somebody already
 * computed, so the routing decision is a pure function of a small record and can be tested
 * exhaustively.
 *
 * [documentShape] is the one judgement call in the list and it is deliberately coarse: a
 * booklet, a card, or a sheet. It only ever breaks a tie between two domestic tracks, never
 * between a family we recognise and one we do not.
 */
data class RouteSignals(
    /** Parsed MRZ, or `null` when the MRZ step was not run / produced nothing usable. */
    val mrz: MrzResult? = null,
    val secureQrPresent: Boolean = false,
    val unsignedQrPresent: Boolean = false,
    val chipPresent: Boolean = false,
    /** A 12-digit candidate read off the visual zone, Verhoeff checked by the caller. */
    val aadhaarCandidate: String? = null,
    /** The visual-zone document number, for the voter / licence shape checks. */
    val vizDocumentNumber: String? = null,
    val documentShape: DocumentShape = DocumentShape.UNKNOWN,
) {
    enum class DocumentShape { BOOKLET, CARD, SHEET, UNKNOWN }
}

/** The routing decision, with the rule that made it. */
data class Route(
    val track: Track,
    /** The rule id that fired, e.g. `mrz-td3`. Stable: it is logged and quoted in review. */
    val rule: String,
    /** False when the signals were too thin and [track] is the fail-closed default. */
    val confident: Boolean,
    /** The `FindingCode`s the route itself contributes (e.g. `SYS_UNSUPPORTED_TRACK`). */
    val codes: List<FindingCode> = emptyList(),
) {
    val isUnknown: Boolean get() = track == Track.UNKNOWN
}

/**
 * The track router (FR-M1).
 *
 * Two properties it is built to have:
 *
 *  1. **Rules, not a classifier.** Every branch is a named, inspectable fact about the document
 *     family. The decision is reproducible from the [RouteSignals] alone, and a wrong route is a
 *     review conversation ("why did it call that a voter ID?") rather than a debugging session.
 *  2. **Fail-closed on the unknown.** An unrecognised document family is
 *     [Track.UNKNOWN], which `:core` turns into AMBER + `SYS_UNSUPPORTED_TRACK` — a human
 *     decides. It is never silently mapped onto a neighbouring family, because guessing
 *     "passport" for a document that is neither would load the wrong set of load-bearing checks
 *     and produce a confident, wrong GREEN.
 *
 * Note what the router does *not* decide: whether the document is genuine. It only decides which
 * set of checks applies. Every track still has to clear its own load-bearing layers.
 */
object TrackRouter {

    fun route(signals: RouteSignals): Route = when {
        // --- an MRZ is the strongest single signal, and its format names the family ---
        signals.mrz?.format == MrzFormat.TD3 -> Route(Track.PASSPORT, "mrz-td3", confident = true)
        signals.mrz?.format == MrzFormat.TD1 -> td1(signals)

        // --- no MRZ, but an Aadhaar is identifiable by its own rules ---
        signals.secureQrPresent && isAadhaarNumber(signals.aadhaarCandidate) ->
            Route(Track.AADHAAR, "aadhaar-secure-qr+verhoeff", confident = true)
        isAadhaarNumber(signals.aadhaarCandidate) ->
            Route(Track.AADHAAR, "aadhaar-verhoeff", confident = true)
        signals.secureQrPresent && signals.documentShape == DocumentShapeShapeCard ->
            // A signed QR with no readable number is still a strong Aadhaar hint: the QR is
            // harder to forge convincingly than the print. Weaker than the rule above, so
            // `confident` is false and the reviewer is told the routing was inferred.
            Route(Track.AADHAAR, "aadhaar-secure-qr-only", confident = false)

        // --- domestic cards, by visual-zone shape ---
        signals.documentShape == DocumentShapeShapeCard && isVoterId(signals.vizDocumentNumber) ->
            Route(Track.VOTER, "voter-shape+format", confident = true)
        signals.documentShape == DocumentShapeShapeCard && isDrivingLicence(signals.vizDocumentNumber) ->
            Route(Track.DRIVING_LICENCE, "dl-shape+format", confident = false)
        signals.unsignedQrPresent && signals.documentShape == DocumentShapeShapeCard ->
            // An unsigned QR on a card is consistent-only evidence (FR-Q2). It may pick the
            // family; it may never clear it. `confident = false` says so.
            Route(Track.PAPER_ID, "card+unsigned-qr", confident = false)

        signals.documentShape == DocumentShapeShapeSheet ->
            Route(Track.PAPER_ID, "sheet-no-mrz", confident = false)
        signals.documentShape == DocumentShapeShapeBooklet ->
            // A booklet with no readable MRZ: almost certainly a passport whose MRZ step was
            // skipped or mis-OCR'd. Routing it to PASSPORT is the only reading that keeps the
            // chip requirement attached, and the MRZ layer will simply come back ABSENT.
            Route(Track.PASSPORT, "booklet-no-mrz", confident = false)

        else -> Route(
            track = Track.UNKNOWN,
            rule = "no-signal",
            confident = false,
            codes = listOf(FindingCode.SYS_UNSUPPORTED_TRACK),
        )
    }

    /**
     * TD1 is used by several document families, so the MRZ alone does not name one.
     *
     * The Aadhaar and voter checks run first because both are *verifiable* — Verhoeff and a
     * format rule respectively — while "it is a TD1" alone is not. A shape hint is the last
     * resort and is explicitly not confident.
     */
    private fun td1(signals: RouteSignals): Route = when {
        isAadhaarNumber(signals.aadhaarCandidate) -> Route(Track.AADHAAR, "mrz-td1+aadhaar-verhoeff", confident = true)
        signals.secureQrPresent -> Route(Track.AADHAAR, "mrz-td1+secure-qr", confident = true)
        isVoterId(signals.vizDocumentNumber) -> Route(Track.VOTER, "mrz-td1+voter-format", confident = true)
        signals.documentShape == DocumentShapeShapeCard ->
            Route(Track.DRIVING_LICENCE, "mrz-td1+card", confident = false)
        else -> Route(Track.PAPER_ID, "mrz-td1", confident = false)
    }

    private fun isAadhaarNumber(candidate: String?): Boolean =
        candidate != null && FormatValidators.validateAadhaar(candidate).verdict == dev.kasoti.checks.FormatVerdict.OK

    private fun isVoterId(candidate: String?): Boolean =
        candidate != null && FormatValidators.validateVoterId(candidate).verdict == dev.kasoti.checks.FormatVerdict.OK

    private fun isDrivingLicence(candidate: String?): Boolean =
        candidate != null && FormatValidators.validateDl(candidate).verdict == dev.kasoti.checks.FormatVerdict.OK

    // Local aliases keep the `when` above readable without importing a nested enum four times.
    private val DocumentShapeShapeCard = RouteSignals.DocumentShape.CARD
    private val DocumentShapeShapeSheet = RouteSignals.DocumentShape.SHEET
    private val DocumentShapeShapeBooklet = RouteSignals.DocumentShape.BOOKLET
}
