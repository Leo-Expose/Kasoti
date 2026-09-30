package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.ProcessLabel
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.TrustState
import dev.kasoti.fusion.Verdict
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/** The verdict plus the findings that produced it, ready to print and to record. */
data class Decision(val verdict: Verdict, val findings: List<Finding>)

/**
 * The seam in front of `dev.kasoti.fusion.FusionEngine`.
 *
 * `FusionEngine` is the single owner of the rules; this interface is what the console talks
 * to, so that a caller can be tested without a real engine and a real engine can be
 * swapped without touching a caller. [CoreFusionEngine] is the production implementation.
 *
 * @param referenceYear full year used to resolve two-digit MRZ dates. It is a parameter and
 *   not a clock read because `:core` refuses to resolve `YYMMDD` against a device clock
 *   (AGENTS.md §5) — the caller is the only thing that knows what "today" was.
 */
interface VerdictEngine {
    /** Recorded in every `DecisionRecord` (invariant I3). */
    val ruleVersion: String

    fun decide(evidence: Evidence, registry: ThresholdRegistry, referenceYear: Int): Decision
}

/**
 * The console's own reading of FUSION.md, used when [CoreFusionEngine] is not selected.
 *
 * It implements §1 (decision order), §2 (hard RED), §3 (AMBER) and §4 (GREY causes) and
 * nothing else. Every numeric comparison reads [ThresholdRegistry]; there is no bare
 * literal in this file, so a reviewer can tell that nothing here can quietly move an
 * operating point (AGENTS.md §2).
 *
 * What it deliberately does *not* do: chip, NFC and the diary rules. Those layers have no
 * evidence source wired in the console yet, and a rule with no evidence is a rule that
 * would fire on nothing.
 */
class LocalRuleEngine : VerdictEngine {

    override val ruleVersion: String get() = RULE_VERSION

    override fun decide(
        evidence: Evidence,
        registry: ThresholdRegistry,
        referenceYear: Int,
    ): Decision {
        val findings = mutableListOf<Finding>()

        collectQuality(evidence, findings)
        collectMath(evidence, findings, registry)
        collectQr(evidence, findings)
        collectMacro(evidence, findings, registry)
        collectFace(evidence, findings, registry)
        collectTrust(evidence, findings, registry)

        // FUSION.md §1: the decision order is fixed and a failed quality gate wins over
        // everything except evidence we already hold. A bad capture is not a person.
        if (!evidence.quality.passed) {
            return Decision(Verdict.GREY, findings.sortedWith(SEVERITY_ORDER))
        }
        if (findings.any { it.severity == Severity.RED }) {
            return Decision(Verdict.RED, findings.sortedWith(SEVERITY_ORDER))
        }
        if (findings.any { it.severity == Severity.AMBER }) {
            return Decision(Verdict.AMBER, findings.sortedWith(SEVERITY_ORDER))
        }
        return Decision(Verdict.GREEN, findings.sortedWith(SEVERITY_ORDER))
    }

    /** FUSION.md §4: the causes of a retake, never an accusation. */
    private fun collectQuality(evidence: Evidence, out: MutableList<Finding>) {
        evidence.quality.causes.forEach { cause ->
            out += Finding(cause, Severity.GREY, "capture.quality", qualityDetail(cause))
        }
    }

    private fun collectMath(evidence: Evidence, out: MutableList<Finding>, registry: ThresholdRegistry) {
        val math = evidence.math ?: return
        if (!math.allChecksPassed) {
            val failed = math.failedFields.sorted().joinToString(",").ifEmpty { "unspecified" }
            out += Finding(FindingCode.R_MATH_01, Severity.RED, "mrz.check", "check digit failure in: $failed")
        }
        if (math.expired || math.impossibleDate || math.issueAfterExpiry) {
            val why = buildList {
                if (math.expired) add("expired")
                if (math.issueAfterExpiry) add("issued after expiry")
                if (math.impossibleDate) add("impossible calendar date")
            }.joinToString(", ")
            out += Finding(FindingCode.R_MATH_02, Severity.RED, "document.dates", why)
        }
        val drift = math.vizDrift
        val tolerance = registry[ThresholdName.VIZ_DRIFT_TOLERANCE].toFloat()
        if (drift != null && drift.score < tolerance) {
            out += Finding(
                FindingCode.A_VIZ_01,
                Severity.AMBER,
                "viz.${drift.field}",
                "printed value drifts from the MRZ on ${drift.field} " +
                    "(score ${fmt(drift.score)}, tolerance ${fmt(tolerance)})",
            )
        }
    }

    private fun collectQr(evidence: Evidence, out: MutableList<Finding>) {
        val qr = evidence.qr ?: return
        if (qr.present && qr.signed && !qr.signatureValid) {
            out += Finding(
                FindingCode.R_QR_01,
                Severity.RED,
                "qr.signature",
                "signed QR failed RSA-SHA256 verification against the bundled key ring",
            )
        }
        if (qr.keysStale) {
            out += Finding(
                FindingCode.SYS_KEYS_STALE,
                Severity.AMBER,
                "qr.keyring",
                "the key that verified is past its rotation date",
            )
        }
        for ((field, _) in qr.mismatches) {
            out += Finding(FindingCode.R_QR_02, Severity.RED, "qr.field.$field", "QR and print disagree on $field")
        }
        if (qr.present && !qr.signed && qr.unsignedFieldsPresent) {
            out += Finding(
                FindingCode.A_QR_01,
                Severity.AMBER,
                "qr.unsigned",
                "QR carries claims with no signature; treated as consistency evidence only",
            )
        }
        if (qr.present && qr.signed && qr.signatureValid && qr.mismatches.isEmpty()) {
            out += Finding(FindingCode.Q_SIG_OK, Severity.INFO, "qr.signature", "QR signature verified")
        }
    }

    /**
     * FUSION.md §5: below the margin floor, a patch reads `UNKNOWN`, not "its best guess".
     *
     * A linear classifier with no discriminative power still returns *some* class — the
     * first one it happens to score highest. Printing that as a label is how an untrained
     * model ends up reported as `OFFSET` in front of somebody. Below the floor the reading
     * is not a weak opinion, it is no opinion, and the case takes `A_WORN_01` to secondary
     * review instead.
     */
    private fun collectMacro(evidence: Evidence, out: MutableList<Finding>, registry: ThresholdRegistry) {
        val macro = evidence.macro ?: return
        val red = registry[ThresholdName.MACRO_MARGIN_RED].toFloat()
        val amber = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()

        val photo = if (macro.photoZoneMargin < amber) ProcessLabel.UNKNOWN else macro.photoZoneLabel
        val text = if (macro.textZoneMargin < amber) ProcessLabel.UNKNOWN else macro.textZoneLabel
        val bothConfident = macro.photoZoneMargin >= red && macro.textZoneMargin >= red

        if (photo == ProcessLabel.SCREEN && macro.photoZoneMargin >= red &&
            evidence.presentation == Presentation.PHYSICAL
        ) {
            out += Finding(
                FindingCode.R_PROC_01,
                Severity.RED,
                "macro.photoZone",
                "photo zone reads as a screen display but a physical document was presented",
            )
        }
        if (photo != text && bothConfident) {
            out += Finding(
                FindingCode.R_PROC_02,
                Severity.RED,
                "macro.zones",
                "photo zone reads $photo but text zone reads $text",
            )
        } else if (photo != text) {
            out += Finding(
                FindingCode.A_PROC_01,
                Severity.AMBER,
                "macro.zones",
                "zones disagree ($photo vs $text) but not both confidently — " +
                    "insufficient evidence to call a paste",
            )
        }
        if (macro.photoZoneMargin < amber || macro.textZoneMargin < amber) {
            out += Finding(
                FindingCode.A_WORN_01,
                Severity.AMBER,
                "macro.margin",
                "at least one zone is below the margin floor ${fmt(amber)}; " +
                    "the process reading is abstained, not guessed",
            )
        }
        if (!macro.clipUsed) {
            out += Finding(
                FindingCode.A_WORN_01,
                Severity.AMBER,
                "macro.clip",
                "macro captured without the shroud clip; halftone reading is less reliable",
            )
        }
    }

    private fun collectFace(evidence: Evidence, out: MutableList<Finding>, registry: ThresholdRegistry) {
        val face = evidence.face ?: return
        val red = registry[ThresholdName.T_FACE_RED].toFloat()
        val green = registry[ThresholdName.T_FACE_GREEN].toFloat()
        when {
            face.similarity < red -> out += Finding(
                FindingCode.R_FACE_01,
                Severity.RED,
                "face.similarity",
                "adjusted similarity ${fmt(face.similarity)} is below the RED threshold ${fmt(red)}",
            )
            face.similarity < green -> out += Finding(
                FindingCode.A_FACE_01,
                Severity.AMBER,
                "face.similarity",
                "adjusted similarity ${fmt(face.similarity)} is in the ambiguous band",
            )
            else -> out += Finding(
                FindingCode.FACE_OK,
                Severity.INFO,
                "face.similarity",
                "adjusted similarity ${fmt(face.similarity)} passed at T_green",
            )
        }
    }

    private fun collectTrust(evidence: Evidence, out: MutableList<Finding>, registry: ThresholdRegistry) {
        if (evidence.trust == TrustState.REVOKED) {
            out += Finding(
                FindingCode.SYS_UNSUPPORTED_TRACK,
                Severity.RED,
                "trust.enrolment",
                "enrolment was revoked; the fast path does not apply",
            )
        }
        val limit = registry[ThresholdName.GREY_STREAK_LIMIT].toInt()
        if (evidence.consecutiveGreyCount >= limit) {
            out += Finding(
                FindingCode.A_GREY3,
                Severity.AMBER,
                "capture.greyStreak",
                "${evidence.consecutiveGreyCount} consecutive GREYs at this post (limit $limit)",
            )
        }
        // Demo mode deliberately raises NO finding. It is recorded on the case, in the
        // bundle, and printed in the console banner — but it has no `FindingCode` that
        // means "this is a demo", and borrowing an operational code would resolve to an
        // operator-facing sentence about the wrong thing entirely.
    }

    private fun qualityDetail(cause: FindingCode): String = when (cause) {
        FindingCode.G_BLUR -> "capture too soft to read"
        FindingCode.G_GLARE -> "glare on the document"
        FindingCode.G_DARK -> "capture too dark"
        FindingCode.G_POSE -> "camera angle outside tolerance"
        FindingCode.G_OCCLUDE -> "document partly covered"
        FindingCode.G_OCRLOW -> "OCR confidence below the floor"
        FindingCode.G_FOCUS -> "macro capture out of focus"
        FindingCode.G_NOCLIP -> "shroud clip required for this track and light"
        else -> "quality gate failed"
    }

    private fun fmt(value: Float): String = "%.3f".format(value)

    companion object {
        /**
         * The FUSION.md revision this reading was written against. Bumped whenever the
         * rules change, and recorded on every decision so a historical verdict is never
         * silently re-interpreted under a newer set of rules (invariant I3).
         */
        const val RULE_VERSION = "console-local-2026.09-fusion-v4"

        private val SEVERITY_ORDER = compareBy<Finding>({ it.severity.ordinal }, { it.code.name })
    }
}
