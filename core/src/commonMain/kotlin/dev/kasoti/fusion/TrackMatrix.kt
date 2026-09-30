package dev.kasoti.fusion

/**
 * Which layers are load-bearing per document family (FUSION.md §7).
 *
 * The table is normative, not a default: an Aadhaar with no secure QR has not been checked
 * against anything cryptographic, and a paper ID with no chip is not missing a check because
 * paper IDs have no chip. Encoding this as data rather than scattering `if (track == ...)`
 * through the rules is what keeps "fail closed on the layers that matter" and "do not invent
 * a missing layer" from contradicting each other.
 */
object TrackMatrix {

    private val PASSPORT = mapOf(
        Layer.MATH to Bearing.LOAD_BEARING,
        Layer.SIGNED_QR to Bearing.AUXILIARY,
        Layer.CHIP to Bearing.LOAD_BEARING,
        Layer.MACRO to Bearing.LOAD_BEARING,
        Layer.UV to Bearing.AUXILIARY,
        Layer.FACE to Bearing.LOAD_BEARING,
        Layer.DIARY to Bearing.LOAD_BEARING,
    )

    private val AADHAAR = mapOf(
        Layer.MATH to Bearing.AUXILIARY,
        Layer.SIGNED_QR to Bearing.LOAD_BEARING,
        Layer.CHIP to Bearing.NOT_APPLICABLE,
        Layer.MACRO to Bearing.LOAD_BEARING,
        Layer.UV to Bearing.AUXILIARY,
        Layer.FACE to Bearing.LOAD_BEARING,
        Layer.DIARY to Bearing.LOAD_BEARING,
    )

    private val VOTER_OR_LICENCE = mapOf(
        Layer.MATH to Bearing.AUXILIARY,
        Layer.SIGNED_QR to Bearing.AUXILIARY,
        Layer.CHIP to Bearing.NOT_APPLICABLE,
        Layer.MACRO to Bearing.LOAD_BEARING,
        Layer.UV to Bearing.AUXILIARY,
        Layer.FACE to Bearing.LOAD_BEARING,
        Layer.DIARY to Bearing.LOAD_BEARING,
    )

    private val PAPER_ID = mapOf(
        Layer.MATH to Bearing.AUXILIARY,
        Layer.SIGNED_QR to Bearing.NOT_APPLICABLE,
        Layer.CHIP to Bearing.NOT_APPLICABLE,
        Layer.MACRO to Bearing.AUXILIARY,
        Layer.UV to Bearing.NOT_APPLICABLE,
        Layer.FACE to Bearing.LOAD_BEARING,
        Layer.DIARY to Bearing.LOAD_BEARING,
    )

    /**
     * An unrecognised track declares nothing load-bearing.
     *
     * Marking every layer `LOAD_BEARING` here would be the obvious "safe" choice and it is
     * wrong twice over: it would demand a chip from a voter ID, and it would not actually
     * fail closed. The real protection is that [dev.kasoti.fusion.FusionEngine] refuses to
     * return GREEN for a track it does not recognise, because it cannot name the checks that
     * were skipped.
     */
    private val UNKNOWN_TRACK = Layer.entries.associateWith { Bearing.AUXILIARY }

    private fun tableFor(track: Track): Map<Layer, Bearing> = when (track) {
        Track.PASSPORT -> PASSPORT
        Track.AADHAAR -> AADHAAR
        Track.VOTER, Track.DRIVING_LICENCE -> VOTER_OR_LICENCE
        Track.PAPER_ID -> PAPER_ID
        Track.UNKNOWN -> UNKNOWN_TRACK
    }.let { table -> table + (Layer.QUALITY to Bearing.LOAD_BEARING) }

    fun bearingOf(track: Track, layer: Layer): Bearing = tableFor(track).getValue(layer)

    /** A signed QR is the identity anchor for Aadhaar; elsewhere it is a bonus. */
    fun signedQrIsLoadBearing(track: Track): Boolean = bearingOf(track, Layer.SIGNED_QR) == Bearing.LOAD_BEARING

    /**
     * Status of every layer for this capture.
     *
     * [Evidence] is nullable per layer by design (the cascade in DESIGN.md §2), so this is
     * where "did not run" becomes an explicit, inspectable state instead of a silent default.
     */
    fun presenceOf(evidence: Evidence): LayerPresence {
        val bearing = tableFor(evidence.track)
        val status = Layer.entries.associateWith { layer -> statusOf(evidence, layer, bearing) }
        return LayerPresence(track = evidence.track, bearing = bearing, status = status)
    }

    private fun statusOf(evidence: Evidence, layer: Layer, bearing: Map<Layer, Bearing>): LayerStatus {
        if (bearing.getValue(layer) == Bearing.NOT_APPLICABLE) return LayerStatus.UNSUPPORTED
        return when (layer) {
            // A failed gate did not produce a result; it produced a non-result. "PRESENT"
            // would read as a check that cleared, which is the opposite of the truth.
            Layer.QUALITY -> if (evidence.quality.passed) LayerStatus.PRESENT else LayerStatus.UNKNOWN
            Layer.MATH -> presentOrAbsent(evidence.math != null)
            Layer.SIGNED_QR -> presentOrAbsent(evidence.qr?.present == true)
            Layer.CHIP -> when {
                evidence.chip == null || !evidence.chip.present -> LayerStatus.ABSENT
                evidence.chip.supported -> LayerStatus.PRESENT
                // A reader stub that reports "not verified" is worse than no reader: it
                // looks like a check happened. It must never read as PRESENT.
                else -> LayerStatus.UNKNOWN
            }

            Layer.MACRO -> presentOrAbsent(evidence.macro != null && evidence.macro.clipUsed)
            Layer.UV -> when {
                evidence.macro == null -> LayerStatus.ABSENT
                evidence.macro.uvState == UvState.PRESENT -> LayerStatus.PRESENT
                evidence.macro.uvState == UvState.UNSUPPORTED -> LayerStatus.UNSUPPORTED
                else -> LayerStatus.ABSENT
            }

            Layer.FACE -> presentOrAbsent(evidence.face != null)
            Layer.DIARY -> presentOrAbsent(evidence.diary != null)
        }
    }

    private fun presentOrAbsent(present: Boolean): LayerStatus =
        if (present) LayerStatus.PRESENT else LayerStatus.ABSENT
}
