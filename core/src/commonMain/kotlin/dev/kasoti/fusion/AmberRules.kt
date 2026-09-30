package dev.kasoti.fusion

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * AMBER rules — FUSION.md §3, "secondary inspection".
 *
 * AMBER is not a softer RED. It is the design's way of saying "a machine can see something
 * odd here but not enough to accuse, and a human has to look". That is why every rule in
 * this file is required to be *ambiguous, uncorroborated or worn* rather than merely
 * positive, and why the ranking matters: the first reason is the one the officer reads.
 *
 * `A-GREY3` is absent because it is a property of the session, not of the evidence; it is
 * applied by [FusionEngine] after the verdict is settled.
 */
internal object AmberRules {

    /**
     * FUSION.md §3 table order, which is also the ranking order.
     *
     * `A-GREY3` is listed even though this object never produces it: the escalation is a
     * property of the session rather than of the evidence, and the table it belongs to is the
     * same table, so the order stays complete.
     */
    private val ORDER = listOf(
        FindingCode.A_PROC_01,
        FindingCode.A_FACE_01,
        FindingCode.A_LIVE_01,
        FindingCode.A_QR_01,
        FindingCode.A_FAC_01,
        FindingCode.A_WL_01,
        FindingCode.A_WORN_01,
        FindingCode.A_GREY3,
        FindingCode.A_VIZ_01,
    )

    /**
     * A reason with its table position and its evidence strength.
     *
     * Ordering is by table position first so the reason is stable across runs, then by
     * strength within a reason so a second watchlist hit does not outrank the first.
     */
    private data class Ranked(val tableIndex: Int, val weight: Double, val finding: Finding) {
        companion object {
            fun of(weight: Double, finding: Finding): Ranked =
                Ranked(ORDER.indexOf(finding.code), weight, finding)
        }
    }

    fun evaluate(
        case: Evidence,
        registry: ThresholdRegistry,
        referenceYear: Int,
        /**
         * Findings a §2 rule produced at AMBER severity — currently the alias candidate that
         * the runner-up gap left unactionable. They are ranked here rather than in
         * [FusionEngine] so the officer sees one ordered list.
         */
        aliasCandidates: List<Finding> = emptyList(),
    ): List<Finding> = (
        process(case, registry) +
            faceBand(case, registry) +
            liveness(case, registry) +
            qr(case, registry, referenceYear) +
            facilitator(case, registry) +
            watchlist(case, registry) +
            diaryLeads(aliasCandidates) +
            worn(case, registry) +
            vizDrift(case, registry)
        ).sortedWith(compareBy({ it.tableIndex }, { -it.weight })).map { it.finding }

    /**
     * An unactionable alias candidate has no row of its own in FUSION.md §3, so it is ranked
     * with the watchlist: both come out of the same 1:N search and both mean "a person a
     * supervisor has to look at". The reason it is not in the table is also the reason it
     * cannot sit next to a hard proof — it lost the comparison to the runner-up.
     */
    private fun diaryLeads(candidates: List<Finding>): List<Ranked> {
        if (candidates.isEmpty()) return emptyList()
        val slot = ORDER.indexOf(FindingCode.A_WL_01)
        return candidates.map { Ranked(slot, 0.0, it) }
    }

    // ------------------------------------------------------------------ A-PROC-01 / A-WORN-01

    /**
     * A-PROC-01: the print process looks wrong, uncorroborated.
     *
     * Three ways in, all of them "not enough to accuse": the zones disagree but only one of
     * them is confident; a zone hints SCREEN below the RED margin while paper was claimed; or
     * a screen was *declared* while both zones confidently agree the print is real paper.
     *
     * A-WORN-01 covers the fourth case, where neither zone will speak at all.
     */
    private fun process(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val reading = readMacro(case.macro, case.presentation, registry) ?: return emptyList()
        if (reading.abstains) return emptyList()
        val weakest = minOf(reading.photoZone.margin, reading.textZone.margin).toDouble()
        val out = mutableListOf<Ranked>()

        if (reading.labelsDiffer && !reading.confident) {
            out += Ranked.of(
                weakest,
                Finding(
                    FindingCode.A_PROC_01,
                    Severity.AMBER,
                    "macro/${reading.photoZone.name},macro/${reading.textZone.name}#MISMATCH-UNCORROBORATED",
                    "photo zone ${reading.photoZone.label} at ${NumberFormat.fixed(reading.photoZone.margin.toDouble())} " +
                        "vs text zone ${reading.textZone.label} at " +
                        "${NumberFormat.fixed(reading.textZone.margin.toDouble())}; only one zone reached the " +
                        "RED margin ${NumberFormat.fixed(reading.redAt.toDouble())}",
                ),
            )
        }

        if (reading.claimsPaper) {
            out += reading.screenHints()
                .filter { it.margin < reading.redAt }
                .map { zone ->
                    Ranked.of(
                        zone.margin.toDouble(),
                        Finding(
                            FindingCode.A_PROC_01,
                            Severity.AMBER,
                            "macro/${zone.name}#${zone.label}@${NumberFormat.fixed(zone.margin.toDouble())}",
                            "${zone.name} hints ${zone.label} at margin ${NumberFormat.fixed(zone.margin.toDouble())}, " +
                                "below the RED margin ${NumberFormat.fixed(reading.redAt.toDouble())}; uncorroborated",
                        ),
                    )
                }
        }

        if (reading.claimsScreen && reading.confident && !reading.labelsDiffer &&
            reading.photoZone.label != ProcessLabel.SCREEN
        ) {
            out += Ranked.of(
                reading.photoZone.margin.toDouble(),
                Finding(
                    FindingCode.A_PROC_01,
                    Severity.AMBER,
                    "macro/${reading.photoZone.name},macro/${reading.textZone.name}#claimed-screen",
                    "a screen was declared but both zones read ${reading.photoZone.label} above the RED margin; " +
                        "the declared presentation and the print forensics disagree",
                ),
            )
        }
        return out
    }

    /**
     * A-WORN-01: the document is too worn or too out of focus to judge.
     *
     * FUSION.md §3 pairs "macro margins low" with "doc aged". The age half has no channel in
     * [Evidence] — the macro feature extractor does not report it — so the abstention itself
     * fires the rule. A genuinely aged card and a blurred clip are the same operational
     * problem for the officer: nobody can tell from this capture, so a human looks.
     */
    private fun worn(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val reading = readMacro(case.macro, case.presentation, registry) ?: return emptyList()
        if (!reading.abstains) return emptyList()
        return listOf(
            Ranked.of(
                0.0,
                Finding(
                    FindingCode.A_WORN_01,
                    Severity.AMBER,
                    "macro/${reading.photoZone.name},macro/${reading.textZone.name}#ABSTAIN",
                    "both zones are below the AMBER margin ${NumberFormat.fixed(reading.amberAt.toDouble())} " +
                        "(${NumberFormat.fixed(reading.photoZone.margin.toDouble())} / " +
                        "${NumberFormat.fixed(reading.textZone.margin.toDouble())}); print process not judged",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ A-FACE-01 / A-LIVE-01

    /** A-FACE-01: the similarity sits in the band between the two face thresholds. */
    private fun faceBand(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val reading = readFace(case.face, registry) ?: return emptyList()
        if (!reading.ambiguous) return emptyList()
        val span = (reading.greenAt - reading.redAt).toDouble()
        val closeness = if (span > 0.0) 1.0 - (reading.adjusted - reading.redAt) / span else 0.0
        return listOf(
            Ranked.of(
                closeness,
                Finding(
                    FindingCode.A_FACE_01,
                    Severity.AMBER,
                    "face/1:1#adjusted=${NumberFormat.fixed(reading.adjusted.toDouble())}" +
                        "@band=[${NumberFormat.fixed(reading.redAt.toDouble())}," +
                        "${NumberFormat.fixed(reading.greenAt.toDouble())})",
                    "quality-adjusted similarity ${NumberFormat.fixed(reading.adjusted.toDouble())} is inside " +
                        "the ambiguous band; the AMBER-biased policy applies",
                ),
            ),
        )
    }

    /**
     * A-LIVE-01: liveness was weak, or the active challenge was not passed.
     *
     * A weak passive score *triggers* the active head-turn challenge; a failed challenge is
     * itself the finding. A passed challenge does not erase a weak passive score, because the
     * two measure different things and the face band is still the face band.
     */
    private fun liveness(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val face = case.face ?: return emptyList()
        val floor = registry[ThresholdName.LIVE_PASSIVE_MIN]
        val out = mutableListOf<Ranked>()
        if (face.passiveLivenessScore < floor) {
            out += Ranked.of(
                1.0 - face.passiveLivenessScore,
                Finding(
                    FindingCode.A_LIVE_01,
                    Severity.AMBER,
                    "face/liveness#passive=${NumberFormat.fixed(face.passiveLivenessScore.toDouble())}" +
                        "@${NumberFormat.fixed(floor)}",
                    "passive liveness score ${NumberFormat.fixed(face.passiveLivenessScore.toDouble())} is below " +
                        "the floor ${NumberFormat.fixed(floor)}; run the active challenge",
                ),
            )
        }
        if (face.headTurnPassed == false) {
            out += Ranked.of(
                1.0,
                Finding(
                    FindingCode.A_LIVE_01,
                    Severity.AMBER,
                    "face/active-challenge#head-turn-failed",
                    "the active head-turn challenge was attempted and not passed",
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ A-QR-01

    /**
     * A-QR-01: an unsigned QR that is not consistent.
     *
     * Three shapes, all of them "no cryptographic anchor": the track needs a signed QR and
     * there is none; the payload claims a signature while carrying unsigned fields; or an
     * unsigned payload's fields disagree with the print. A *signed* QR is deliberately absent
     * here — a gross disagreement on a signed QR is R-QR-02, because a valid signature does
     * not mean the document face was not altered afterwards, and a small one is inside
     * tolerance by construction. Only the unsigned case is an inconsistency, exactly as
     * FUSION.md §3 words it.
     */
    private fun qr(case: Evidence, registry: ThresholdRegistry, referenceYear: Int): List<Ranked> {
        val qr = case.qr?.takeIf { it.present } ?: return emptyList()
        val out = mutableListOf<Ranked>()

        if (!qr.signed && TrackMatrix.signedQrIsLoadBearing(case.track)) {
            out += Ranked.of(
                0.0,
                Finding(
                    FindingCode.A_QR_01,
                    Severity.AMBER,
                    "qr/unsigned#expected-signed",
                    "a ${case.track.name.lowercase()} was presented without a signed QR; the signature anchor " +
                        "was not checked",
                ),
            )
        }
        if (qr.signed && qr.unsignedFieldsPresent) {
            out += Ranked.of(
                0.0,
                Finding(
                    FindingCode.A_QR_01,
                    Severity.AMBER,
                    "qr/signed#carries-unsigned-fields",
                    "the payload claims a signature but carries unsigned fields",
                ),
            )
        }

        val tolerance = registry[ThresholdName.QR_DRIFT_TOLERANCE].toFloat()
        qr.mismatches.forEach { (field, values) ->
            val agreement = RedRules.agreement(field, values.first, values.second, referenceYear)
            if (agreement >= tolerance) return@forEach
            out += Ranked.of(
                1.0 - agreement,
                Finding(
                    FindingCode.A_QR_01,
                    Severity.AMBER,
                    "qr/vs-print#$field@${NumberFormat.fixed(agreement.toDouble(), 2)}",
                    "QR and print disagree on $field (agreement ${NumberFormat.fixed(agreement.toDouble(), 2)}); " +
                        "inside the RED band, so inconsistent rather than tampered",
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ diary rules

    /**
     * A-FAC-01: many crossings sharing few groups inside a short window.
     *
     * The registry is re-applied here rather than trusted: the diary layer produced the flag,
     * but the thresholds that define a facilitator pattern are policy, and a deployment that
     * loosens them must not silently keep the old fusion behaviour.
     */
    private fun facilitator(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val diary = case.diary ?: return emptyList()
        val minGroups = registry[ThresholdName.FACILITATOR_MIN_GROUPS].toInt()
        val window = registry[ThresholdName.FACILITATOR_WINDOW_DAYS]
        return diary.facilitatorFlags
            .filter { it.distinctGroups >= minGroups && it.windowDays <= window }
            .sortedByDescending { it.distinctGroups }
            .map { flag ->
                Ranked.of(
                    flag.distinctGroups.toDouble(),
                    Finding(
                        FindingCode.A_FAC_01,
                        Severity.AMBER,
                        "diary/facilitator#groups=${flag.distinctGroups}/window=${flag.windowDays}d",
                        "${flag.distinctGroups} distinct groups across ${flag.eventIds.size} events inside " +
                            "${flag.windowDays} days (policy: ${minGroups} groups within " +
                            "${NumberFormat.fixed(window, 0)} days)",
                    ),
                )
            }
    }

    /** A-WL-01: a watchlist entry matched. Always secondary inspection, always a supervisor. */
    private fun watchlist(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val diary = case.diary ?: return emptyList()
        val floor = registry[ThresholdName.T_WL].toFloat()
        return diary.watchlistHits
            .filter { it.similarity >= floor }
            .sortedByDescending { it.similarity }
            .map { hit ->
                Ranked.of(
                    hit.similarity.toDouble(),
                    Finding(
                        FindingCode.A_WL_01,
                        Severity.AMBER,
                        "diary/watchlist#${hit.eventId}@${hit.post}",
                        "watchlist entry matched at ${NumberFormat.fixed(hit.similarity.toDouble())} " +
                            "(floor ${NumberFormat.fixed(floor.toDouble())}); supervisor confirmation required",
                    ),
                )
            }
    }

    // ------------------------------------------------------------------ A-VIZ-01

    /**
     * A-VIZ-01: the printed zone and the machine-readable zone disagree.
     *
     * The MRZ is what the issuer machine-printed; the VIZ is what a photo-swap edits. A
     * disagreement is therefore evidence about the print, and the raw values stay in the case
     * bundle rather than in the message because a name or a date of birth is PII.
     */
    private fun vizDrift(case: Evidence, registry: ThresholdRegistry): List<Ranked> {
        val drift = case.math?.vizDrift ?: return emptyList()
        val tolerance = registry[ThresholdName.VIZ_DRIFT_TOLERANCE]
        if (drift.score >= tolerance) return emptyList()
        return listOf(
            Ranked.of(
                1.0 - drift.score,
                Finding(
                    FindingCode.A_VIZ_01,
                    Severity.AMBER,
                    "viz/mrz#${drift.field}@${NumberFormat.fixed(drift.score.toDouble(), 2)}",
                    "printed ${drift.field} and the machine-readable zone agree only " +
                        "${NumberFormat.fixed(drift.score.toDouble(), 2)} (tolerance " +
                        "${NumberFormat.fixed(tolerance)}); raw values are in the case bundle",
                ),
            ),
        )
    }
}
