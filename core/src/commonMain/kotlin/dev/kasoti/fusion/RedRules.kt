package dev.kasoti.fusion

import dev.kasoti.checks.VizMrzMatch
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * Hard-RED rules — FUSION.md §2, evaluated in table order.
 *
 * Every rule here is a *data* proof: a check digit that does not add up, a signature that
 * does not verify, two zones printed in different processes. That is the reason these can
 * accuse, and it is also why each one must name the artefact it stands on — a RED without
 * evidenceRef is an accusation with nothing behind it, which is the thing this whole package
 * exists to prevent.
 *
 * Rules never decide the verdict; [FusionEngine] does. The split exists so that the
 * quality-gate exception in FUSION.md §1 can re-run these rules and *discard* the answer
 * without duplicating any of the logic.
 */
internal object RedRules {

    /** FUSION.md §2 table order, which is also first-hit order. */
    private val ORDER = listOf(
        FindingCode.R_MATH_01,
        FindingCode.R_MATH_02,
        FindingCode.R_QR_01,
        FindingCode.R_QR_02,
        FindingCode.R_CHIP_01,
        FindingCode.R_FACE_01,
        FindingCode.R_ALIAS_01,
        FindingCode.R_TRAV_01,
        FindingCode.R_PROC_01,
        FindingCode.R_PROC_02,
    )

    /**
     * Proofs that depend on the live frame, and so must not be carried over a failed gate.
     *
     * A blurry or badly-lit frame is the most common cause of a low 1:1 similarity, which is
     * exactly why quality gates run *before* matching (DESIGN.md §5). The other rules read
     * the printed document or the diary, and a bad frame does not change what is printed on
     * paper — those proofs are safe to attach during a retake.
     */
    val CAPTURE_DEPENDENT: Set<FindingCode> = setOf(FindingCode.R_FACE_01)

    fun evaluate(case: Evidence, registry: ThresholdRegistry, referenceYear: Int): List<Finding> =
        ORDER.flatMap { evaluate(it, case, registry, referenceYear) }

    private fun evaluate(
        code: FindingCode,
        case: Evidence,
        registry: ThresholdRegistry,
        referenceYear: Int,
    ): List<Finding> = when (code) {
        FindingCode.R_MATH_01 -> checkDigits(case)
        FindingCode.R_MATH_02 -> dates(case)
        FindingCode.R_QR_01 -> qrSignature(case)
        FindingCode.R_QR_02 -> qrAgainstPrint(case, registry, referenceYear)
        FindingCode.R_CHIP_01 -> chip(case)
        FindingCode.R_FACE_01 -> face(case, registry)
        FindingCode.R_ALIAS_01 -> alias(case, registry)
        FindingCode.R_TRAV_01 -> travel(case, registry)
        FindingCode.R_PROC_01 -> processIsScreen(case, registry)
        FindingCode.R_PROC_02 -> processZonesDisagree(case, registry)
        else -> emptyList()
    }

    /** R-MATH-01: any check digit fail. Fail-closed, no fuzzy accept (FUSION.md §2). */
    private fun checkDigits(case: Evidence): List<Finding> {
        val math = case.math?.takeIf { !it.allChecksPassed } ?: return emptyList()
        val fields = math.failedFields.sorted()
        if (fields.isEmpty()) {
            // A failure with no attribution is still a failure. The MRZ layer must not be
            // able to hide one by returning an empty field set, so it is reported as-is and
            // the missing attribution is named in the message.
            return listOf(
                Finding(
                    code = FindingCode.R_MATH_01,
                    severity = Severity.RED,
                    evidenceRef = "mrz/checks#unattributed",
                    message = "a check digit failed and the MRZ layer attributed it to no field",
                ),
            )
        }
        return fields.map { field ->
            Finding(
                code = FindingCode.R_MATH_01,
                severity = Severity.RED,
                evidenceRef = "mrz/$field#check-digit",
                message = "check digit failed for $field",
            )
        }
    }

    /**
     * R-MATH-02: expired, impossible calendar date, or issue after expiry.
     *
     * "Expired" arrives pre-computed: `now` is an injected parameter (AGENTS.md §5), and
     * `:core` never reads a device clock.
     */
    private fun dates(case: Evidence): List<Finding> {
        val math = case.math ?: return emptyList()
        return buildList {
            if (math.expired) {
                add(
                    Finding(
                        FindingCode.R_MATH_02, Severity.RED, "doc/dates#expired",
                        "the expiry date is in the past",
                    ),
                )
            }
            if (math.impossibleDate) {
                add(
                    Finding(
                        FindingCode.R_MATH_02, Severity.RED, "doc/dates#impossible",
                        "a printed date is not a real calendar date",
                    ),
                )
            }
            if (math.issueAfterExpiry) {
                add(
                    Finding(
                        FindingCode.R_MATH_02, Severity.RED, "doc/dates#issue-after-expiry",
                        "the issue date is later than the expiry date",
                    ),
                )
            }
        }
    }

    /** R-QR-01: signature invalid on a signed QR. Stale keys are reported, not excused. */
    private fun qrSignature(case: Evidence): List<Finding> {
        val qr = case.qr?.takeIf { it.present && it.signed && !it.signatureValid } ?: return emptyList()
        val rotation = if (qr.keysStale) "; bundled keys are past their rotation date" else ""
        return listOf(
            Finding(
                FindingCode.R_QR_01, Severity.RED, "qr/signature#invalid",
                "the signed QR failed signature verification$rotation",
            ),
        )
    }

    /**
     * R-QR-02: the signed QR disagrees with the print beyond tolerance.
     *
     * The recorded mismatches are disagreements the comparison layer already found; the
     * tolerance decides whether a disagreement is a *gross* one. A gross name or date
     * disagreement on a signed QR means the print was altered after the issuer signed it,
     * which is an attack, so the band is deliberately wide: a half-transliterated surname
     * must not be a RED.
     */
    private fun qrAgainstPrint(
        case: Evidence,
        registry: ThresholdRegistry,
        referenceYear: Int,
    ): List<Finding> {
        val qr = case.qr?.takeIf { it.present && it.signed } ?: return emptyList()
        val tolerance = registry[ThresholdName.QR_DRIFT_TOLERANCE].toFloat()
        return qr.mismatches.mapNotNull { (field, values) ->
            val agreement = agreement(field, values.first, values.second, referenceYear)
            if (agreement >= tolerance) {
                null
            } else {
                Finding(
                    FindingCode.R_QR_02, Severity.RED, "qr/vs-print#$field",
                    "signed QR and print disagree on $field (agreement ${NumberFormat.fixed(agreement.toDouble(), 2)}, " +
                        "tolerance ${NumberFormat.fixed(tolerance.toDouble(), 2)})",
                )
            }
        }
    }

    /**
     * Score a QR/print disagreement with the comparison that matches the field.
     *
     * Raw strings are compared rather than carried into the message: names and dates of
     * birth are PII and must not reach logs (AGENTS.md §5). An unrecognised field falls back
     * to the name comparison, which returns 1.0 for equal values and 0.0 when the token sets
     * are disjoint — the fail-closed end for a field nobody taught us to read.
     */
    internal fun agreement(field: String, left: String, right: String, referenceYear: Int): Float {
        val name = field.uppercase()
        return when {
            name.contains("NAME") -> VizMrzMatch.nameScore(left, right)
            name.contains("DOB") || name.contains("BIRTH") || name.contains("DATE") ->
                VizMrzMatch.dateScore(left, right, referenceYear)

            name.contains("NUMBER") || name.contains("DOC") -> VizMrzMatch.numberScore(left, right)
            left.isBlank() || right.isBlank() -> 0f
            else -> VizMrzMatch.nameScore(left, right)
        }
    }

    /**
     * R-CHIP-01: passive authentication failed, or the chip's DG1 contradicts the MRZ.
     *
     * Requires [ChipEvidence.supported]. A reader stub that returns "not verified" produces
     * no chip finding at all: the layer reports itself as unresolved, and the unresolved
     * load-bearing layer is what keeps the case out of GREEN.
     */
    private fun chip(case: Evidence): List<Finding> {
        val chip = case.chip?.takeIf { it.present && it.supported } ?: return emptyList()
        return buildList {
            if (!chip.passiveAuthValid) {
                add(
                    Finding(
                        FindingCode.R_CHIP_01, Severity.RED, "chip/pa#invalid",
                        "passive authentication did not verify",
                    ),
                )
            }
            if (!chip.dg1MatchesMrz) {
                add(
                    Finding(
                        FindingCode.R_CHIP_01, Severity.RED, "chip/dg1-vs-mrz#mismatch",
                        "chip data group 1 does not match the machine-readable zone",
                    ),
                )
            }
        }
    }

    /**
     * R-FACE-01: quality-adjusted similarity below `T_FACE_RED`.
     *
     * The comparison uses [fuseMatchScore], not the raw cosine, so a poor capture is pushed
     * towards AMBER rather than towards a confident accusation.
     */
    private fun face(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val reading = readFace(case.face, registry) ?: return emptyList()
        if (!reading.belowRed) return emptyList()
        return listOf(
            Finding(
                FindingCode.R_FACE_01,
                Severity.RED,
                "face/1:1#adjusted=${NumberFormat.fixed(reading.adjusted.toDouble())}" +
                    "@T=${NumberFormat.fixed(reading.redAt.toDouble())}",
                "quality-adjusted similarity ${NumberFormat.fixed(reading.adjusted.toDouble())} is below the " +
                    "RED threshold ${NumberFormat.fixed(reading.redAt.toDouble())}",
            ),
        )
    }

    /**
     * R-ALIAS-01: a prior crossing that matches this face under different details.
     *
     * Two things keep this from being a silent RED (FUSION.md §5):
     *
     * 1. The `delta` rule. A hit only counts when it beats the runner-up by
     *    [ThresholdName.DELTA_MARGIN]; inside that band the top two gallery entries are
     *    indistinguishable and the search has not actually identified anybody. Such a hit is
     *    reported at AMBER — a candidate, never an action.
     * 2. Only the leading hit can carry that action. Hits ranked below it lost the same
     *    comparison, so they are attached as context, not as independent accusations.
     *
     * The name/DOB inequality is already applied by the diary layer: [DiaryEvidence.aliasHits]
     * is the output of `sim >= T_alias_hi AND (name != OR dob !=)` (DESIGN.md §5), and the
     * engine cannot re-derive a name comparison from a salted hash.
     */
    private fun alias(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val diary = case.diary ?: return emptyList()
        val floor = registry[ThresholdName.T_ALIAS_HI].toFloat()
        val hits = diary.aliasHits.filter { it.similarity >= floor }.sortedByDescending { it.similarity }
        if (hits.isEmpty()) return emptyList()

        val required = registry[ThresholdName.DELTA_MARGIN].toFloat()
        val gap = diary.topHitSimilarity - diary.runnerUpSimilarity
        val corroborated = gap >= required
        val leader = if (corroborated) hits.first() else null

        return hits.mapIndexed { index, hit ->
            val ref = "diary/alias#${hit.eventId}@${hit.post}/${hit.timestamp}"
            val score = NumberFormat.fixed(hit.similarity.toDouble())
            when {
                hit === leader -> Finding(
                    FindingCode.R_ALIAS_01, Severity.RED, ref,
                    "prior crossing at ${hit.post} on ${hit.timestamp} matches this face ($score) under " +
                        "different name or date of birth; separated from the runner-up by " +
                        "${NumberFormat.fixed(gap.toDouble())} (delta ${NumberFormat.fixed(required.toDouble())}); " +
                        "supervisor confirmation required",
                )

                index == 0 -> Finding(
                    FindingCode.R_ALIAS_01, Severity.AMBER, ref,
                    "candidate alias at ${hit.post} on ${hit.timestamp} ($score) is not separated from the " +
                        "runner-up by ${NumberFormat.fixed(gap.toDouble())} (delta " +
                        "${NumberFormat.fixed(required.toDouble())}); not actionable alone",
                )

                else -> Finding(
                    FindingCode.R_ALIAS_01, Severity.AMBER, ref,
                    "further prior crossing above the alias threshold at ${hit.post} on ${hit.timestamp} ($score)",
                )
            }
        }
    }

    /** R-TRAV-01: an implied speed between two crossings above the policy maximum. */
    private fun travel(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val diary = case.diary ?: return emptyList()
        val vmax = registry[ThresholdName.VMAX]
        return diary.travelFlags.filter { it.impliedSpeedKmh > vmax }.map { flag ->
            Finding(
                FindingCode.R_TRAV_01,
                Severity.RED,
                "diary/travel#${flag.fromEventId}->${flag.toEventId} ${flag.fromPost}->${flag.toPost}",
                "implied ${NumberFormat.fixed(flag.impliedSpeedKmh, 1)} km/h over " +
                    "${NumberFormat.fixed(flag.distanceKm, 1)} km in ${NumberFormat.fixed(flag.hours, 2)} h " +
                    "(policy maximum ${NumberFormat.fixed(vmax, 0)} km/h)",
            )
        }
    }

    /**
     * R-PROC-01: the print forensics say SCREEN while a physical document was claimed.
     *
     * Reading of the two directions, which the spec leaves to the implementer:
     *
     * * A **screen showing a document is the attack** when paper is claimed. That is the
     *   rule above, and it needs the SCREEN zone to be at or above `MACRO_MARGIN_RED` — a
     *   low-margin SCREEN reading is an uncorroborated hint and becomes A-PROC-01.
     * * The **reverse** (a screen is declared but the paper is genuine) is not evidence of
     *   an attack. It is a labelling error, or a subject who tapped the wrong button, and
     *   accusing on it would be a false RED. It becomes A-PROC-01 so a human resolves it.
     */
    private fun processIsScreen(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val reading = readMacro(case.macro, case.presentation, registry) ?: return emptyList()
        if (!reading.claimsPaper) return emptyList()
        return reading.zones
            .filter { it.isScreen && it.margin >= reading.redAt }
            .map { zone ->
                Finding(
                    FindingCode.R_PROC_01,
                    Severity.RED,
                    "macro/${zone.name}#${zone.label}@${NumberFormat.fixed(zone.margin.toDouble())}",
                    "${zone.name} reads ${zone.label} at margin ${NumberFormat.fixed(zone.margin.toDouble())} " +
                        "while a ${case.presentation.name.lowercase()} document was claimed",
                )
            }
    }

    /**
     * R-PROC-02: photo zone and text zone were printed by different processes.
     *
     * Corroboration is mandatory (FUSION.md §5, DESIGN.md principle 6): a genuine document
     * is printed in one pass, so a disagreement is evidence — but only when *both* zones
     * reached the RED margin floor. One confident zone disagreeing with an uncertain one is
     * A-PROC-01, and two uncertain zones are A-WORN-01. A bare macro mismatch is never a RED.
     */
    private fun processZonesDisagree(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val reading = readMacro(case.macro, case.presentation, registry) ?: return emptyList()
        if (!reading.labelsDiffer || !reading.confident) return emptyList()
        val photo = reading.photoZone
        val text = reading.textZone
        return listOf(
            Finding(
                FindingCode.R_PROC_02,
                Severity.RED,
                "macro/${photo.name},macro/${text.name}#MISMATCH",
                "photo zone ${photo.label} at ${NumberFormat.fixed(photo.margin.toDouble())} vs text zone " +
                    "${text.label} at ${NumberFormat.fixed(text.margin.toDouble())}; both above the RED margin " +
                    NumberFormat.fixed(reading.redAt.toDouble()),
            ),
        )
    }
}
