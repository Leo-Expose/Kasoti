package dev.kasoti.fusion

import dev.kasoti.face.fuseMatchScore
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * Derived views of the raw evidence, with the operating point already applied.
 *
 * Both the RED and the AMBER layer need the *same* reading of a capture — the adjusted
 * similarity and where it falls relative to the two face thresholds, the macro margins and
 * what they license. Computing that twice in two files is how the two layers start
 * disagreeing about whether a case is a face RED or a face AMBER, which is precisely the
 * false-accusation path the design exists to close. So the reading is made once, here.
 */
internal data class FaceReading(
    val evidence: FaceEvidence,
    val adjusted: Float,
    val redAt: Float,
    val greenAt: Float,
) {
    val belowRed: Boolean get() = adjusted < redAt

    val aboveGreen: Boolean get() = adjusted >= greenAt

    /** The ambiguous band: close enough to call, too weak to call. */
    val ambiguous: Boolean get() = !belowRed && !aboveGreen
}

internal fun readFace(face: FaceEvidence?, registry: ThresholdRegistry): FaceReading? {
    if (face == null) return null
    return FaceReading(
        evidence = face,
        adjusted = fuseMatchScore(face.similarity, face.docQuality, face.liveQuality),
        redAt = registry[ThresholdName.T_FACE_RED].toFloat(),
        greenAt = registry[ThresholdName.T_FACE_GREEN].toFloat(),
    )
}

/** One classified crop, named so the finding can point at it. */
internal data class MacroZone(
    val name: String,
    val label: ProcessLabel,
    val margin: Float,
) {
    val isScreen: Boolean get() = label == ProcessLabel.SCREEN
}

/**
 * What the macro layer is allowed to claim.
 *
 * The margin floors are the whole point (FUSION.md §5): a classifier that is nearly tied
 * between `OFFSET` and `INKJET` carries no information, so its disagreement with the other
 * zone is not evidence of a paste-up. Reading the two margins and the two labels together
 * is what lets the RED rule insist on corroboration and the AMBER rule decline to act.
 */
internal data class MacroReading(
    val photoZone: MacroZone,
    val textZone: MacroZone,
    val presentation: Presentation,
    val redAt: Float,
    val amberAt: Float,
) {
    val zones: List<MacroZone> get() = listOf(photoZone, textZone)

    val labelsDiffer: Boolean get() = photoZone.label != textZone.label

    val confident: Boolean get() = photoZone.margin >= redAt && textZone.margin >= redAt

    /** At least one zone reached the AMBER floor, so at least one is willing to speak. */
    val audible: Boolean get() = photoZone.margin >= amberAt || textZone.margin >= amberAt

    /** Both zones are below the floor: the classifier abstains, so the document is unreadable. */
    val abstains: Boolean get() = !audible

    /**
     * Whether a physical document was *claimed*.
     *
     * A reprint and a photo-of-a-photo are both claims about a physical artefact, and both
     * should be met with "that is not what the print forensics say" rather than a pass.
     * Only [Presentation.SCREEN] is the opposite claim.
     */
    val claimsPaper: Boolean
        get() = presentation == Presentation.PHYSICAL ||
            presentation == Presentation.REPRINT ||
            presentation == Presentation.PHOTO_OF_PHOTO

    val claimsScreen: Boolean get() = presentation == Presentation.SCREEN

    /** Zones that claim SCREEN at or above the AMBER floor, i.e. a usable screen reading. */
    fun screenHints(): List<MacroZone> = zones.filter { it.isScreen && it.margin >= amberAt }
}

internal fun readMacro(macro: MacroEvidence?, presentation: Presentation, registry: ThresholdRegistry): MacroReading? {
    if (macro == null) return null
    return MacroReading(
        photoZone = MacroZone("photo-zone", macro.photoZoneLabel, macro.photoZoneMargin),
        textZone = MacroZone("text-zone", macro.textZoneLabel, macro.textZoneMargin),
        presentation = presentation,
        redAt = registry[ThresholdName.MACRO_MARGIN_RED].toFloat(),
        amberAt = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat(),
    )
}
