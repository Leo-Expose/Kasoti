package dev.kasoti.log

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * The scrubber's operating point, read out of [ThresholdRegistry] (AGENTS.md §2).
 *
 * A tunable that is inlined as a literal in a regex is a review fail, and it is also
 * unfalsifiable: nobody can tell from the source whether `40` was chosen or guessed. So
 * every *floor* the shape rules use comes through here, named, versioned, and owned.
 *
 * The *ceilings* are not here and that is a decision rather than an oversight. An MRZ row is
 * 30 or 44 characters (ICAO 9303), a phone number is at most 15 digits (E.164), and a
 * document-number field is 9 characters in the MRZ. Those are fixed by published standards;
 * registering them as tunables would imply they can be moved, which is worse than leaving
 * them in the pattern where the citation sits next to them. The registry's `A6` exemption
 * in `scripts/check_no_magic_thresholds.sh` exists for exactly this category.
 *
 * @property hexBlobMinChars shortest hex run treated as a blob rather than as prose.
 * @property base64BlobMinChars shortest base64 run treated as a blob.
 * @property phoneMinDigits shortest digit run treated as a phone number.
 * @property docNumberMinDigits fewest digits after a 1–2 letter prefix before it reads as a
 *   document number.
 * @property jwtMinSegmentChars shortest dot-separated segment of a run before it reads as a
 *   compact JWT rather than as a dotted hostname or a version string.
 */
data class RedactionPolicy(
    val hexBlobMinChars: Int,
    val base64BlobMinChars: Int,
    val phoneMinDigits: Int,
    val docNumberMinDigits: Int,
    val jwtMinSegmentChars: Int,
) {

    /**
     * Read a policy out of a versioned registry.
     *
     * The registry stores `Double` because it is a tuning table shared with the cascade; a
     * character count is an `Int`. The conversion is checked rather than truncated: a
     * registry edited to `40.5` would otherwise silently become `40`, and a scrubber whose
     * floor is quietly not the reviewed floor is a scrubber nobody can reason about.
     */
    init {
        require(hexBlobMinChars > 0 && base64BlobMinChars > 0) { "blob floors must be positive" }
        require(phoneMinDigits > 0) { "phone floor must be positive" }
        require(docNumberMinDigits > 0) { "document-number floor must be positive" }
        require(jwtMinSegmentChars >= 2) {
            // Below two characters per segment the rule degenerates into "any dotted string",
            // which is the opposite of what it is for.
            "JWT segment floor must be at least 2, or every dotted identifier matches"
        }
    }

    companion object {
        /**
         * Spec-fixed widths the shape rules interpolate into their patterns.
         *
         * Not tunables — see the class comment. Public so the tests can assert against the
         * same numbers the patterns were built from, instead of re-typing them and hoping
         * the two stayed in step.
         */
        const val ICAO_MRZ_TD1_CHARS: Int = 30
        const val ICAO_MRZ_TD3_CHARS: Int = 44

        /** E.164 caps a subscriber number at 15 digits. */
        const val E164_MAX_DIGITS: Int = 15

        /** The MRZ document-number field is 9 characters. */
        const val MRZ_DOCNUM_CHARS: Int = 9

        /** How many dot-separated segments a compact JWT has. RFC 7519 §3.1. */
        const val JWT_SEGMENTS: Int = 3

        fun from(registry: ThresholdRegistry): RedactionPolicy = RedactionPolicy(
            hexBlobMinChars = registry.wholeNumber(ThresholdName.REDACT_HEX_MIN_CHARS),
            base64BlobMinChars = registry.wholeNumber(ThresholdName.REDACT_BASE64_MIN_CHARS),
            phoneMinDigits = registry.wholeNumber(ThresholdName.REDACT_PHONE_MIN_DIGITS),
            docNumberMinDigits = registry.wholeNumber(ThresholdName.REDACT_DOCNUM_MIN_DIGITS),
            jwtMinSegmentChars = registry.wholeNumber(ThresholdName.REDACT_JWT_MIN_SEGMENT_CHARS),
        )

        /**
         * The shipped operating point, frozen at `v1`.
         *
         * Evaluated eagerly so a registry that violates its own bounds fails at startup with
         * a clear message rather than at the first `println` of a screening.
         */
        val DEFAULT: RedactionPolicy = from(ThresholdRegistry.defaults())
    }
}

/** The registry's value for [name] as an exact `Int`, refusing a fractional setting. */
private fun ThresholdRegistry.wholeNumber(name: ThresholdName): Int {
    val raw = this[name]
    val rounded = raw.toInt()
    require(raw == rounded.toDouble()) {
        "threshold $name = $raw is not a whole number of characters; the scrubber needs an exact floor"
    }
    return rounded
}
