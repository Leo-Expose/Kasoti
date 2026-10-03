package dev.kasoti.threshold

/**
 * Every tunable in the system, addressed by name (FR-R3, AGENTS.md §2).
 *
 * This enum is now **names and nothing else**. Its `default`, `floor`, `ceiling`, `unit`,
 * `tuningDataRef` and `owner` all come from `fusion/thresholds.v1.json` via [THRESHOLD_SPEC],
 * so the operating point a device runs on lives in one versioned data file with a review trail
 * rather than in a constructor argument list that a reviewer has to notice.
 *
 * That split is the point, and it is why the properties are `get()` accessors rather than
 * constructor parameters: the enum's constant initialisers run when this class loads, and
 * loading them from a parsed document inside a constructor would make the two initialise in an
 * order nobody can see. Deferring the lookup to first read removes the cycle entirely — the
 * document is parsed without ever mentioning this enum, and [ThresholdSpecFileTest] asserts in
 * both directions that the two agree, so neither can drift or hold an orphan.
 *
 * Adding a tunable is therefore: one object in the JSON, and one constant here. The JSON is
 * validated (unknown field, missing field, out-of-range default, undeclared unit, unknown
 * schema version all fail), so the compiler is no longer the thing standing between a bad
 * operating point and a verdict.
 */
enum class ThresholdName {
    // --- face verification + fusion (FUSION.md §2, EVAL.md §2 D-FACE) ---
    T_FACE_RED,
    T_FACE_GREEN,
    T_ALIAS_HI,
    T_WL,
    DELTA_MARGIN,
    MACRO_MARGIN_RED,
    MACRO_MARGIN_AMBER,

    // --- capture usability bands (EVAL.md §4 D-USAB) ---
    Q_BLUR,
    Q_GLARE,
    Q_BRIGHT_MIN,
    Q_BRIGHT_MAX,
    Q_POSE_YAW,
    Q_POSE_PITCH,

    // --- liveness + QR + visualisation drift ---
    LIVE_PASSIVE_MIN,
    QR_DRIFT_TOLERANCE,
    VIZ_DRIFT_TOLERANCE,

    // --- the fusion score-tempering ramp (dev.kasoti.face.fuseMatchScore) ---
    FUSE_TEMPER_FLOOR,
    FUSE_TEMPER_SLOPE,

    // --- face detector operating points (EVAL.md §4 D-FACE TAR@FAR, FUSION.md §6) ---
    FACE_FAR_TARGET_COARSE,
    FACE_FAR_TARGET_STRICT,
    FACE_BUCKET_GAP_MAX_RATIO,

    // --- halftone / print analysis (EVAL.md D-FACTORY) ---
    HALFTONE_HIGH_FREQ_RADIUS,

    // --- field and policy defaults (owner: @lead) ---
    VMAX,
    RECHECK_P,
    REVERIFY_DAYS,
    GREY_STREAK_LIMIT,
    CLOCK_SKEW_MAX_MIN,
    YYMMDD_CENTURY_SLACK_YEARS,
    MAX_PLAUSIBLE_AGE,

    // --- scenario / facilitator policy (EVAL.md §4 D-SCEN) ---
    FACILITATOR_MIN_GROUPS,
    FACILITATOR_WINDOW_DAYS,
    FACILITATOR_COOCCUR_HOURS,
    FACILITATOR_GROUP_PREFIX_HEX,
    FACILITATOR_MAX_PARTNERS,
    FACILITATOR_MIN_ANCHOR_EVENTS,
    FACILITATOR_MIN_COOCCURRENCES,
    FACILITATOR_MAX_EVIDENCE,
    FACILITATOR_MAX_FLAGS_PER_EVAL,
    ALIAS_MAX_FLAGS,
    TRAVEL_MAX_FLAGS,
    DIARY_TOP_K,

    // --- PII-scrubber sensitivity (dev.kasoti.log, AGENTS.md §2/§4) ---
    // These are the *floors* of the log scrubber's shape rules: the shortest run treated as a
    // blob or a number rather than as prose. They are registered rather than inlined so that
    // "make the scrubber more or less eager" is a reviewed, versioned edit with an owner.
    //
    // THEY CANNOT CHANGE A VERDICT, and each entry says so in its own `rationale`. Lowering a
    // floor makes logs *less* readable and not safer (it mangles ULIDs and finding codes);
    // raising one lets more PII-shaped text through. The trade is deliberately asymmetric,
    // which is why every ceiling is much tighter than its floor.
    //
    // The corresponding *ceilings* are NOT tunable and are deliberately not registered: an MRZ
    // row is 30 (TD1) or 44 (TD3) characters by ICAO 9303, a phone number is at most 15 digits
    // by E.164, and a document-number field is 9 characters in the MRZ. Those are spec-fixed
    // widths — the same category the registry records as `S01` and `S08`.
    REDACT_HEX_MIN_CHARS,
    REDACT_BASE64_MIN_CHARS,
    REDACT_PHONE_MIN_DIGITS,
    REDACT_DOCNUM_MIN_DIGITS,
    REDACT_JWT_MIN_SEGMENT_CHARS,

    // --- offline sync transport budgets (SYNC.md §5) ---
    QR_PACKET_MAX_BYTES,
    SYNC_FILE_SPLIT_BYTES,
    WATCHLIST_LABEL_MAX_CHARS,
    ;

    /** The registry row behind this name. */
    val spec: ThresholdSpecEntry get() = THRESHOLD_SPEC.entry(name)

    val unit: String get() = spec.unit

    val tuningDataRef: String get() = spec.tuningDataRef

    val owner: String get() = spec.owner

    /** Why this operating point is where it is, where there was more to say than the name. */
    val rationale: String? get() = spec.rationale

    val default: Double get() = spec.default

    /** Inclusive lower bound an operating point may never cross without lead sign-off. */
    val floor: Double get() = spec.floor

    val ceiling: Double get() = spec.ceiling
}
