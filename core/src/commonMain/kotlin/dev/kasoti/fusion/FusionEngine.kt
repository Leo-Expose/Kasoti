package dev.kasoti.fusion

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * The verdict engine — FUSION.md §1, implemented in the order the lattice is written.
 *
 * The order is the safety property, not an implementation detail:
 *
 * 1. **Capture quality.** A frame we could not see properly yields GREY with causes. Hard-RED
 *    proofs already in hand still attach, because suppressing them would lose the one thing
 *    the officer needs to know before they ask for another photo — but the verdict stays
 *    GREY, because a bad frame is the most common cause of a *false* RED and an accusation
 *    must never be built on one.
 * 2. **Hard RED.** Data proofs from FUSION.md §2, with the first hit named.
 * 3. **AMBER.** The ranked reasons a human should look.
 * 4. **GREEN**, with the trust-lane fast-path flag when the enrolment allows it.
 *
 * Every path is fail-closed: an unresolved load-bearing layer (FUSION.md §7) puts a floor
 * under the verdict, so the only way to reach GREEN is to have actually cleared the checks
 * that matter for this document family.
 */
object FusionEngine {

    /** Bumped whenever the rule set changes, so a verdict names the rules that produced it. */
    const val FUSION_RULE_VERSION = "fusion-v1"

    /** Codes that put the case in front of a supervisor (DESIGN.md §5, FUSION.md §8). */
    private val SUPERVISOR_CODES = setOf(
        FindingCode.R_ALIAS_01,
        FindingCode.A_WL_01,
        FindingCode.A_GREY3,
    )

    /**
     * @param referenceYear full year used to resolve two-digit dates. `:core` never reads a
     *   device clock (AGENTS.md §5) and `YYMMDD` is ambiguous without it, exactly as
     *   [dev.kasoti.checks.VizMrzMatch.normaliseDate] requires of its callers.
     */
    fun decide(case: Evidence, registry: ThresholdRegistry, referenceYear: Int): VerdictReport {
        val layers = TrackMatrix.presenceOf(case)
        val coverage = CoverageRules.evaluate(layers, case)

        if (!case.quality.passed) {
            return escalateRepeatedRetake(retakeForBadCapture(case, registry, layers, coverage, referenceYear), case, registry)
        }
        if (!case.hasDecisionableData) {
            return escalateRepeatedRetake(nothingToLookAt(case, registry, layers, coverage), case, registry)
        }
        if (coverage.retakes.isNotEmpty()) {
            return escalateRepeatedRetake(retakeForMissingCapture(case, registry, layers, coverage), case, registry)
        }

        val hard = RedRules.evaluate(case, registry, referenceYear)
        val hardRed = hard.filter { it.severity == Severity.RED }
        val hardCandidates = hard.filter { it.severity != Severity.RED }
        if (hardRed.isNotEmpty()) {
            return report(
                case, registry, layers,
                verdict = Verdict.RED,
                negative = hardRed + hardCandidates,
                positive = confirmations(case, registry),
                rationale = buildList {
                    add("hard RED: ${hardRed.first().code} (first hit)")
                    addAll(hardRed.drop(1).map { "corroborating: ${it.code} at ${it.evidenceRef}" })
                    hardCandidates.forEach { add("candidate: ${it.code} at ${it.evidenceRef}") }
                    coverage.escalations.forEach { add("unresolved check: ${it.evidenceRef}") }
                },
            )
        }

        val amber = unknownTrack(case) + AmberRules.evaluate(
            case = case,
            registry = registry,
            referenceYear = referenceYear,
            aliasCandidates = hardCandidates,
        ) + coverage.escalations
        if (amber.isNotEmpty()) {
            return report(
                case, registry, layers,
                verdict = Verdict.AMBER,
                negative = amber,
                positive = confirmations(case, registry),
                rationale = listOf(
                    "secondary inspection: " + amber.joinToString(", ") { "${it.code}@${it.evidenceRef}" },
                ),
            )
        }

        return report(
            case, registry, layers,
            verdict = Verdict.GREEN,
            negative = emptyList(),
            positive = confirmations(case, registry) + trustFlag(case, registry),
            rationale = listOf("all load-bearing checks cleared: " + clearedLayers(layers).joinToString()),
        )
    }

    // ------------------------------------------------------------------ step 1: retake

    /**
     * A capture that failed the quality gate.
     *
     * The carried-over proofs are the whole subtlety of FUSION.md §1. They are attached at RED
     * severity because the data really does show an expiry or a bad check digit, and they are
     * marked as carried so no consumer mistakes them for the verdict — [VerdictReport.verdict]
     * stays GREY. The retake note names which one, so the officer is not asked to start from
     * nothing. Capture-dependent proofs (a low face similarity on a bad frame) are excluded:
     * a blurred capture is exactly what produces them.
     */
    private fun retakeForBadCapture(
        case: Evidence,
        registry: ThresholdRegistry,
        layers: LayerPresence,
        coverage: CoverageRules.Coverage,
        referenceYear: Int,
    ): VerdictReport {
        val causes = case.quality.causes.map { code ->
            Finding(code, Severity.GREY, "capture/quality#$code", "capture quality gate: $code")
        }
        val carried = RedRules.evaluate(case, registry, referenceYear)
            .filter { it.severity == Severity.RED && it.code !in RedRules.CAPTURE_DEPENDENT }
            .map {
                it.copy(
                    evidenceRef = "carried-over/${it.evidenceRef}",
                    message = "retake; note: prior capture already showed ${it.code.name} (${it.message})",
                )
            }
        return report(
            case, registry, layers,
            verdict = Verdict.GREY,
            negative = causes + coverage.retakes,
            positive = emptyList(),
            carried = carried,
            rationale = buildList {
                add("capture quality failed: " + causes.joinToString(", ") { it.code.name } + " -> retake")
                carried.forEach { add("carried-over proof: ${it.evidenceRef}") }
            },
        )
    }

    /** A required capture never arrived, so no layer can speak: retake, named by cause. */
    private fun retakeForMissingCapture(
        case: Evidence,
        registry: ThresholdRegistry,
        layers: LayerPresence,
        coverage: CoverageRules.Coverage,
    ): VerdictReport = report(
        case, registry, layers,
        verdict = Verdict.GREY,
        negative = coverage.retakes,
        positive = emptyList(),
        rationale = listOf(
            "required capture missing: " + coverage.retakes.joinToString(", ") { it.code.name } + " -> retake",
        ),
    )

    /**
     * Nothing at all was collected: a clean quality gate over a frame with no readable content.
     *
     * `R-PLAIN_TEXT` is the closest existing code, but it is about a *picture* of a page; a
     * capture with no layer evidence at all is a different failure and gets its own code so
     * the retake instructions can differ. Fail-closed: no data is not a pass, and it is
     * certainly not an accusation.
     */
    private fun nothingToLookAt(
        case: Evidence,
        registry: ThresholdRegistry,
        layers: LayerPresence,
        coverage: CoverageRules.Coverage,
    ): VerdictReport = report(
        case, registry, layers,
        verdict = Verdict.GREY,
        negative = coverage.retakes + Finding(
            FindingCode.G_NOEVIDENCE,
            Severity.GREY,
            "capture/quality#G_NOEVIDENCE",
            "no verification layer produced evidence for this capture",
        ),
        positive = emptyList(),
        rationale = listOf("no decisionable evidence -> retake"),
    )

    /**
     * A document family we do not recognise cannot be cleared.
     *
     * The track matrix (§7) cannot say which layers matter, so there is nothing to certify.
     * The answer is AMBER — a human decides — rather than GREEN, and it is deliberately not
     * RED: an unrecognised document is an operational gap in this build, not evidence against
     * the person holding it.
     */
    private fun unknownTrack(case: Evidence): List<Finding> =
        if (case.track == Track.UNKNOWN) {
            listOf(
                Finding(
                    FindingCode.SYS_UNSUPPORTED_TRACK,
                    Severity.AMBER,
                    "case/track#unknown",
                    "the document family is not recognised, so the checks that matter cannot be named",
                ),
            )
        } else {
            emptyList()
        }

    // ------------------------------------------------------------------ assembly

    private fun report(
        case: Evidence,
        registry: ThresholdRegistry,
        layers: LayerPresence,
        verdict: Verdict,
        negative: List<Finding>,
        positive: List<Finding>,
        carried: List<Finding> = emptyList(),
        rationale: List<String>,
    ): VerdictReport {
        val findings = negative + carried + positive
        return VerdictReport(
            verdict = verdict,
            findings = findings,
            firstHit = negative.firstOrNull(),
            carriedOver = carried,
            layers = layers,
            rationale = rationale,
            thresholdVersion = registry.version,
            thresholdRunId = registry.runId,
            fusionRuleVersion = FUSION_RULE_VERSION,
            supervisorRequired = findings.any { it.code in SUPERVISOR_CODES },
            trustFastPath = findings.any { it.code == FindingCode.TRUST_OK },
            demoMode = case.demoMode,
        )
    }

    private fun clearedLayers(layers: LayerPresence): List<String> = Layer.entries
        .filter { layers.bearingOf(it) == Bearing.LOAD_BEARING && layers.isClear(it) }
        .map { it.name.lowercase() }

    /**
     * `A-GREY3`: repeated retakes are the anti-gaming rule (FUSION.md §3, DESIGN.md §7).
     *
     * It is a post-processing step, and only on an otherwise-GREY verdict: gaming retakes into
     * a refusal is the abuse, so the escalation has to act on the GREY itself. A case that is
     * already AMBER or RED is not made "more escalated" by a run of bad retakes — it is
     * already going to a human. The GREY causes stay attached, because the retake is still
     * needed; what changes is that the case can no longer be closed as "nothing to see".
     *
     * @param greyCount the caller-supplied running count of consecutive GREY attempts,
     *   compared with `>=` so the escalation lands on the limit attempt itself.
     */
    private fun escalateRepeatedRetake(
        report: VerdictReport,
        case: Evidence,
        registry: ThresholdRegistry,
    ): VerdictReport {
        if (report.verdict != Verdict.GREY) return report
        val limit = registry[ThresholdName.GREY_STREAK_LIMIT].toInt()
        if (case.consecutiveGreyCount < limit) return report
        val escalation = Finding(
            FindingCode.A_GREY3,
            Severity.AMBER,
            "case/history#consecutiveGrey=${case.consecutiveGreyCount}",
            "${case.consecutiveGreyCount} consecutive retakes (limit $limit); a supervisor must authorise " +
                "another attempt",
        )
        return report.copy(
            verdict = Verdict.AMBER,
            findings = report.findings + escalation,
            firstHit = escalation,
            rationale = report.rationale + "repeat-retake escalation: ${case.consecutiveGreyCount} >= $limit",
            supervisorRequired = true,
        )
    }

    // ------------------------------------------------------------------ confirmations

    /**
     * What actually cleared, for the audit trail.
     *
     * A layer that did not run contributes nothing: there is no `CHIP_OK` for a chip that was
     * never read, which is the same rule as the RED side and the reason a missing check cannot
     * be laundered into a clean-looking report.
     */
    private fun confirmations(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        val out = mutableListOf<Finding>()

        case.math?.takeIf { it.allChecksPassed }?.let {
            out += Finding(FindingCode.M_OK, Severity.INFO, "mrz/checks#all-pass", "every check digit agreed")
        }
        case.qr?.takeIf { it.present && it.signed && it.signatureValid }?.let {
            out += Finding(FindingCode.Q_SIG_OK, Severity.INFO, "qr/signature#valid", "QR signature verified")
        }
        case.chip?.takeIf { it.present && it.supported && it.passiveAuthValid && it.dg1MatchesMrz }?.let {
            out += Finding(FindingCode.CHIP_OK, Severity.INFO, "chip/pa#valid", "passive authentication verified")
        }
        readFace(case.face, registry)?.takeIf { it.aboveGreen }?.let {
            out += Finding(
                FindingCode.FACE_OK,
                Severity.INFO,
                "face/1:1#adjusted=${NumberFormat.fixed(it.adjusted.toDouble())}",
                "quality-adjusted similarity ${NumberFormat.fixed(it.adjusted.toDouble())} is at or above " +
                    NumberFormat.fixed(it.greenAt.toDouble()),
            )
        }
        readMacro(case.macro, case.presentation, registry)
            ?.takeIf { !it.abstains && !it.labelsDiffer }?.let {
                out += Finding(
                    FindingCode.MACRO_OK,
                    Severity.INFO,
                    "macro/zones#match",
                    "both zones agree on the print process",
                )
            }
        case.diary
            ?.takeIf {
                it.aliasHits.isEmpty() && it.travelFlags.isEmpty() &&
                    it.facilitatorFlags.isEmpty() && it.watchlistHits.isEmpty()
            }
            ?.let { out += Finding(FindingCode.DIARY_CLEAR, Severity.INFO, "diary/#no-conflict", "no diary conflicts") }
        if (case.qr?.keysStale == true) {
            out += Finding(
                FindingCode.SYS_KEYS_STALE,
                Severity.INFO,
                "qr/keyring#rotation-due",
                "bundled signature keys are past their rotation date",
            )
        }
        return out
    }

    /**
     * `TRUST_OK`: the trust lane may take its shortcut (FUSION.md §8).
     *
     * The fast path is only offered on a case that would otherwise be GREEN, and only when the
     * enrolment is live: an enrolled subject whose face-only check cleared, with liveness and
     * a diary re-check behind it. It is never attached to an AMBER or RED — a shortcut that
     * laundered a secondary inspection would defeat the lane. `RANDOM_RECHECK` and `REVOKED`
     * do not qualify, because the scheduler selected this subject precisely because the fast
     * path does not apply.
     */
    private fun trustFlag(case: Evidence, registry: ThresholdRegistry): List<Finding> {
        if (case.trust != TrustState.ENROLLED) return emptyList()
        val reading = readFace(case.face, registry) ?: return emptyList()
        if (!reading.aboveGreen) return emptyList()
        if (reading.evidence.passiveLivenessScore < registry[ThresholdName.LIVE_PASSIVE_MIN]) return emptyList()
        if (reading.evidence.headTurnPassed == null) return emptyList()
        if (case.diary == null) return emptyList()
        return listOf(
            Finding(
                FindingCode.TRUST_OK,
                Severity.INFO,
                "trust/lane#enrolled",
                "trust lane fast path: face-only check cleared with liveness and a diary re-check",
            ),
        )
    }
}
