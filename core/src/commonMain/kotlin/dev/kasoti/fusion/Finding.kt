package dev.kasoti.fusion

/**
 * Every finding carries a code, never a raw string (AGENTS.md §2).
 *
 * The code is the contract: the UI resolves it to an English and a Hindi string via
 * [dev.kasoti.i18n.Messages], and the audit log records it verbatim. Messages here are for
 * logs and the eval harness only — they must never be what the officer reads, because they
 * are not translatable.
 */
data class Finding(
    val code: FindingCode,
    val severity: Severity,
    /** Stable pointer to the artefact that justifies the finding: a case id, crop name, field. */
    val evidenceRef: String,
    /** Human-readable detail for logs and the failure gallery. Not for the UI. */
    val message: String = "",
) {
    val red: Boolean get() = severity == Severity.RED
    val amber: Boolean get() = severity == Severity.AMBER
    val grey: Boolean get() = severity == Severity.GREY
}

/**
 * The complete finding vocabulary.
 *
 * Codes are stable identifiers and are never renamed or reused — a code that disappears
 * would break historical audit records. The FUSION.md section for each is noted so the
 * doc and the code cannot drift apart silently.
 */
enum class FindingCode {
    // --- hard RED (FUSION.md §2) ---
    R_MATH_01, // any MRZ check digit fail
    R_MATH_02, // expired / issue-after-expiry / impossible calendar date
    R_QR_01, // signed-QR signature invalid
    R_QR_02, // QR vs print name/DOB mismatch
    R_CHIP_01, // e-passport PA fail / DG1 vs MRZ mismatch
    R_FACE_01, // 1:1 adjusted similarity below the RED threshold
    R_ALIAS_01, // diary alias with supervisor confirmation
    R_TRAV_01, // impossible travel
    R_PROC_01, // process label says SCREEN for a physical-document claim
    R_PROC_02, // photo-zone vs text-zone process mismatch

    // --- AMBER (FUSION.md §3) ---
    A_PROC_01, // PVC process mismatch, uncorroborated
    A_FACE_01, // similarity in the ambiguous band
    A_LIVE_01, // weak passive liveness, needs the active challenge
    A_QR_01, // unsigned-QR inconsistency
    A_FAC_01, // facilitator pattern
    A_WL_01, // watchlist hit
    A_WORN_01, // worn document, abstained
    A_GREY3, // three consecutive GREYs (gaming defence)
    A_VIZ_01, // VIZ vs MRZ drift on a non-check-digit field
    A_MISSING_LAYER, // a load-bearing check produced no usable result; not a pass

    // --- GREY causes (FUSION.md §4) ---
    G_BLUR,
    G_GLARE,
    G_DARK,
    G_POSE,
    G_OCCLUDE,
    G_OCRLOW,
    G_FOCUS,
    G_NOCLIP,
    G_NOEVIDENCE, // no layer produced anything to judge

    // --- positive confirmations, recorded for the audit trail ---
    M_OK, // math verified
    Q_SIG_OK, // QR signature verified
    CHIP_OK, // chip PA verified
    FACE_OK, // face 1:1 passed
    DIARY_CLEAR, // no alias/travel/facilitator/watchlist hit
    MACRO_OK, // macro process consistent across zones
    TRUST_OK, // trust lane fast path taken
    R_PLAIN_TEXT, // image is plain text, not a document

    // --- operational / system ---
    SYS_MODEL_MISMATCH, // embedding model generation differs
    SYS_KEYS_STALE, // bundled signature keys are past their rotation date
    SYS_THRESHOLDS_EDITED, // registry hash differs from the frozen value
    SYS_CAPTURE_FAILED,
    SYS_UNSUPPORTED_TRACK,
}

/** Ordering matters: a RED outranks an AMBER, which outranks a GREY cause. */
enum class Severity {
    RED,
    AMBER,
    GREY,
    INFO,
}

/**
 * The verdict lattice (FUSION.md §1).
 *
 * `GREY` is not a "no" — it is "we could not look properly", and it must never be rendered
 * as an accusation. Keeping it a distinct case rather than a severity means fusion cannot
 * accidentally produce `GREY` for something that was actually checked and passed.
 */
enum class Verdict {
    GREEN,
    AMBER,
    RED,
    GREY,
    ;

    val requiresSecondary: Boolean get() = this == AMBER || this == RED
    val isRetake: Boolean get() = this == GREY
}

/**
 * The complete evidence bundle handed to [FusionEngine.decide].
 *
 * Every field is optional because the cascade (DESIGN.md principle 2) means a case may
 * legitimately stop early: a failed quality gate means no math, QR, macro or face result
 * exists yet, and pretending otherwise invites decisions on data we never collected.
 */
data class Evidence(
    val track: Track,
    val quality: QualityReport = QualityReport.CLEAN,
    val math: MathEvidence? = null,
    val qr: QrEvidence? = null,
    val chip: ChipEvidence? = null,
    val macro: MacroEvidence? = null,
    val face: FaceEvidence? = null,
    val diary: DiaryEvidence? = null,
    val trust: TrustState = TrustState.VERIFY,
    val consecutiveGreyCount: Int = 0,
    /** Physical document presented vs a screen showing a document — decides `R-PROC-01`. */
    val presentation: Presentation = Presentation.PHYSICAL,
    val demoMode: Boolean = false,
) {
    /** True once enough layers have run to justify a decision rather than a retake. */
    val hasDecisionableData: Boolean
        get() = quality.passed && (math != null || qr != null || macro != null || face != null)
}

/** Document family. Drives which layers are load-bearing (FUSION.md §7). */
enum class Track {
    PASSPORT,
    AADHAAR,
    VOTER,
    DRIVING_LICENCE,
    PAPER_ID,
    UNKNOWN,
}

/** How the document reached the device. `SCREEN` is a first-class case, not a bug. */
enum class Presentation {
    PHYSICAL,
    SCREEN,
    REPRINT,
    PHOTO_OF_PHOTO,
}

/** Trust-lane position (FUSION.md §8). */
enum class TrustState {
    ENROLLED, // supervisor-approved enrolment, fast path
    VERIFY, // full stranger check
    REVOKED, // enrolment was revoked
    RANDOM_RECHECK, // scheduler selected this subject for a full check anyway
}

/**
 * Capture quality (FR-C1, FUSION.md §4).
 *
 * Fail-closed: a failed gate yields GREY with an explicit cause, never an accusation
 * (DESIGN.md principle 4).
 */
data class QualityReport(
    val passed: Boolean,
    val causes: List<FindingCode> = emptyList(),
    val blurScore: Float = 0f,
    val glareRatio: Float = 0f,
    val brightness: Float = 0f,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val occluded: Boolean = false,
) {
    companion object {
        val CLEAN = QualityReport(passed = true)
    }
}

data class MathEvidence(
    val allChecksPassed: Boolean,
    val failedFields: Set<String> = emptySet(),
    val expired: Boolean = false,
    val impossibleDate: Boolean = false,
    val issueAfterExpiry: Boolean = false,
    val vizDrift: DriftScore? = null,
)

data class QrEvidence(
    val present: Boolean,
    val signed: Boolean,
    val signatureValid: Boolean,
    val keysStale: Boolean = false,
    /** Field-level disagreements between the QR and the printed document. */
    val mismatches: List<Pair<String, Pair<String, String>>> = emptyList(),
    val unsignedFieldsPresent: Boolean = false,
)

data class ChipEvidence(
    val present: Boolean,
    val passiveAuthValid: Boolean,
    val dg1MatchesMrz: Boolean,
    val supported: Boolean,
)

data class MacroEvidence(
    val photoZoneLabel: ProcessLabel,
    val photoZoneMargin: Float,
    val textZoneLabel: ProcessLabel,
    val textZoneMargin: Float,
    val uvState: UvState = UvState.UNSUPPORTED,
    val clipUsed: Boolean,
)

data class FaceEvidence(
    val similarity: Float,
    val docQuality: Float,
    val liveQuality: Float,
    val passiveLivenessScore: Float,
    val headTurnPassed: Boolean?,
)

data class DiaryEvidence(
    val aliasHits: List<DiaryHit> = emptyList(),
    val travelFlags: List<TravelFlag> = emptyList(),
    val facilitatorFlags: List<FacilitatorFlag> = emptyList(),
    val watchlistHits: List<DiaryHit> = emptyList(),
    val topHitSimilarity: Float = 0f,
    val runnerUpSimilarity: Float = 0f,
)

/** Print-process classification (FR-F1). */
enum class ProcessLabel {
    OFFSET,
    INKJET,
    LASER,
    DYESUB,
    SCREEN,
    PHOTOCOPY,
    UNKNOWN,
}

enum class UvState {
    PRESENT,
    ABSENT,
    UNSUPPORTED,
}

/** Comparison of two normalised text fields, used for VIZ↔MRZ and QR↔print (FR-M4, R-QR-02). */
data class DriftScore(
    val field: String,
    val score: Float,
    val left: String,
    val right: String,
) {
    val identical: Boolean get() = score >= 1f
}

/**
 * A ranked 1:N diary hit (FR-H3).
 *
 * [nameHash] is a salted hash, never a name: the diary is a biometric log and the UI must
 * not be a readable roll of who crossed where (THREAT_MODEL.md §4). [dob] is carried only
 * because the alias rule needs it, and deployments may hash it instead (SYNC.md §3).
 */
data class DiaryHit(
    val eventId: String,
    val similarity: Float,
    val post: String,
    val timestamp: String,
    val nameHash: String,
    val dob: String? = null,
)

/** An implied speed between two crossings that exceeds the policy maximum (R-TRAV-01). */
data class TravelFlag(
    val fromEventId: String,
    val toEventId: String,
    val fromPost: String,
    val toPost: String,
    val distanceKm: Double,
    val hours: Double,
    val impliedSpeedKmh: Double,
)

/** Many crossings sharing a small set of groups inside a window (A-FAC-01). */
data class FacilitatorFlag(
    val distinctGroups: Int,
    val windowDays: Int,
    val eventIds: List<String>,
)
