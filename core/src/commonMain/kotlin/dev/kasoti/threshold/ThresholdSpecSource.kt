package dev.kasoti.threshold

/**
 * The embedded copy of `dev/kasoti/fusion/thresholds.v1.json`, verbatim.
 *
 * WHY A COPY EXISTS AT ALL. That JSON file is the registry of record (AGENTS.md §2). `:core`
 * code has to read it, and `commonMain` cannot: there is no file, classpath-resource or asset
 * API that exists on every KASOTI target without an `expect`/`actual` pair, and `:core` has
 * zero `expect`/`actual` today. Adding one for a 250-line constant would trade a real
 * portability problem for a real build-configuration problem -- the Android target is wired
 * only when an SDK is present (`core/build.gradle.kts`), so an `actual` would have to be
 * written for a target nobody here compiles. So the text travels as a constant instead.
 *
 * THE COPY CANNOT DRIFT. `ThresholdSpecFileTest` (jvmTest) reads
 * `core/src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json` off disk and asserts it is
 * character-for-character this text. Edit the JSON, forget this, and `:core:jvmTest` fails --
 * so the file is the only thing a maintainer ever has to edit, and this one is enforced.
 *
 * WHY THE SCAN IS EXEMPT. `scripts/check_no_magic_thresholds.sh` skips `dev/kasoti/threshold/`
 * because that package *is* the registry. Without that exemption every registered threshold
 * would be reported twice: once here, once as the literal it was before. The exemption is the
 * reason the numbers live in a data file rather than in the enum.
 */
internal object ThresholdSpecSource {

    /** Character-for-character the contents of `fusion/thresholds.v1.json`. */
    const val TEXT: String = """{
  "registry": "kasoti.thresholds",
  "schemaVersion": 1,
  "version": "v1",
  "authority": "AGENTS.md §2 / FR-R3. This file is the registry of record: name, default, unit, tuning-data ref, owner. A magic number anywhere else is a review fail. Dev.kasoti.threshold.ThresholdSpecSource embeds this text verbatim as a Kotlin raw string (dev/kasoti/threshold/ is exempt from the magic-number scan, so the embedded copy is not double-counted) and ThresholdSpecFileTest fails if the two ever diverge. EDITING CONSTRAINT: because it is embedded in a Kotlin raw string, this file must contain no dollar sign anywhere and no run of three consecutive double quotes. A dollar sign makes Kotlin read the rest as a template, and the build then fails with a syntax error that points at the wrong place entirely. Write 'a string interpolation' in prose instead. Reason strings are the usual place this bites.",
  "units": {
    "cosine": "cosine similarity, 0..1",
    "score": "unitless 0..1 score",
    "score-factor": "unitless multiplier applied to a score",
    "ratio": "unitless quotient",
    "agreement": "how closely two independently-read claims agree, 0..1",
    "far-rate": "false-accept rate as a probability",
    "frr-rate": "false-reject rate as a probability",
    "svm-margin": "signed distance from the macro SVM decision surface",
    "laplacian-var": "variance of the Laplacian, a blur proxy",
    "mean": "mean 0..255 intensity",
    "degrees": "angle in degrees",
    "km/h": "kilometres per hour",
    "probability": "probability 0..1",
    "count": "an integer quantity",
    "days": "whole days",
    "hours": "whole hours",
    "minutes": "whole minutes",
    "years": "whole years",
    "chars": "characters",
    "digits": "decimal digits",
    "hex-chars": "hexadecimal characters",
    "bytes": "bytes",
    "px-radius": "pixel radius in a raster"
  },
  "constraints": [
    {
      "kind": "sumToOne",
      "members": ["FUSE_TEMPER_FLOOR", "FUSE_TEMPER_SLOPE"],
      "tolerance": 1e-9,
      "reason": "The two numbers are the intercept and the slope of one affine ramp in dev.kasoti.face.fuseMatchScore. Their sum is what makes a full-quality match leave the raw similarity unchanged; if they stop summing to one, 'full quality is transparent' stops being true and every fused score shifts. Checked once when the registry is loaded, not per call, so it cannot cost a verdict path anything."
    }
  ],
  "thresholds": [
    { "name": "T_FACE_RED", "unit": "cosine", "default": 0.35, "floor": 0.20, "ceiling": 0.60, "tuningDataRef": "D-FACE report split", "owner": "vision" },
    { "name": "T_FACE_GREEN", "unit": "cosine", "default": 0.55, "floor": 0.35, "ceiling": 0.80, "tuningDataRef": "D-FACE report split", "owner": "vision" },
    { "name": "T_ALIAS_HI", "unit": "cosine", "default": 0.80, "floor": 0.60, "ceiling": 0.95, "tuningDataRef": "D-FACE + D-SCEN", "owner": "vision" },
    { "name": "T_WL", "unit": "cosine", "default": 0.75, "floor": 0.60, "ceiling": 0.95, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "DELTA_MARGIN", "unit": "cosine", "default": 0.08, "floor": 0.02, "ceiling": 0.30, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "MACRO_MARGIN_RED", "unit": "svm-margin", "default": 0.35, "floor": 0.15, "ceiling": 1.0, "tuningDataRef": "D-MACRO held-out", "owner": "vision" },
    { "name": "MACRO_MARGIN_AMBER", "unit": "svm-margin", "default": 0.15, "floor": 0.05, "ceiling": 0.60, "tuningDataRef": "D-MACRO held-out", "owner": "vision" },
    { "name": "Q_BLUR", "unit": "laplacian-var", "default": 100.0, "floor": 30.0, "ceiling": 400.0, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "Q_GLARE", "unit": "ratio", "default": 0.12, "floor": 0.03, "ceiling": 0.40, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "Q_BRIGHT_MIN", "unit": "mean", "default": 40.0, "floor": 15.0, "ceiling": 90.0, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "Q_BRIGHT_MAX", "unit": "mean", "default": 225.0, "floor": 180.0, "ceiling": 250.0, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "Q_POSE_YAW", "unit": "degrees", "default": 25.0, "floor": 10.0, "ceiling": 45.0, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "Q_POSE_PITCH", "unit": "degrees", "default": 20.0, "floor": 8.0, "ceiling": 40.0, "tuningDataRef": "D-USAB", "owner": "vision" },
    { "name": "LIVE_PASSIVE_MIN", "unit": "score", "default": 0.50, "floor": 0.20, "ceiling": 0.90, "tuningDataRef": "D-SPOOF", "owner": "vision" },
    { "name": "QR_DRIFT_TOLERANCE", "unit": "agreement", "default": 0.15, "floor": 0.05, "ceiling": 0.50, "tuningDataRef": "D-QR", "owner": "vision" },

    { "name": "FUSE_TEMPER_FLOOR", "unit": "score-factor", "default": 0.6, "floor": 0.0, "ceiling": 1.0, "tuningDataRef": "D-FACE fusion temper ramp", "owner": "vision",
      "rationale": "The score a match is multiplied by at ZERO quality. Lifted out of dev.kasoti.face.fuseMatchScore, where it was 0.6f inline. High enough that a badly-taken capture still contributes half its similarity and lands in the AMBER band rather than disappearing; low enough that a poor live-capture cannot manufacture a match out of a weak raw score." },
    { "name": "FUSE_TEMPER_SLOPE", "unit": "score-factor", "default": 0.4, "floor": 0.0, "ceiling": 1.0, "tuningDataRef": "D-FACE fusion temper ramp", "owner": "vision",
      "rationale": "The per-unit-of-quality gain on top of FUSE_TEMPER_FLOOR, so that full quality is exactly transparent. Registered separately rather than derived as 1 - FUSE_TEMPER_FLOOR because 0.4f != 1f - 0.6f in binary32 and deriving it would silently change every fused score; the sum is asserted by the sumToOne constraint above." },

    { "name": "FACE_FAR_TARGET_COARSE", "unit": "far-rate", "default": 0.01, "floor": 0.0001, "ceiling": 0.10, "tuningDataRef": "EVAL.md §4 D-FACE", "owner": "vision",
      "rationale": "The 1% FAR point EVAL.md §4 names. Reported as one end of the TAR@FAR sweep; the strict 0.1% point is the operating point actually shipped." },
    { "name": "FACE_FAR_TARGET_STRICT", "unit": "far-rate", "default": 0.001, "floor": 0.0001, "ceiling": 0.10, "tuningDataRef": "EVAL.md §4 D-FACE", "owner": "vision",
      "rationale": "The 0.1% FAR point, and the strictest of the two EVAL.md §4 names, so it is the one the sweep selects. Lowering it asks the ROC for a resolution that ~400 pair trials cannot honestly support; the floor is where that stops." },
    { "name": "FACE_BUCKET_GAP_MAX_RATIO", "unit": "ratio", "default": 2.0, "floor": 1.0, "ceiling": 10.0, "tuningDataRef": "FUSION.md §6", "owner": "vision",
      "rationale": "FUSION.md §6: a worst per-bucket FRR more than twice the best bucket means the detector is uneven across cohorts, which is a fairness finding rather than a tuning finding. Kept above 1.0 because a ratio of exactly 1.0 would fail on any non-zero measurement noise." },

    { "name": "HALFTONE_HIGH_FREQ_RADIUS", "unit": "px-radius", "default": 64.0, "floor": 8.0, "ceiling": 256.0, "tuningDataRef": "D-FACTORY", "owner": "vision",
      "rationale": "The radial-frequency cut in dev.kasoti.factory.Spectrum, above which energy is counted as 'high frequency'. It separates fine toner speckle from coarse dither, so moving it moves which half of the spectrum the D-FACTORY features are reading. The one Spectrum constant that was a band choice rather than a formula coefficient; the rest of that file is the published window and the published transform." },

    { "name": "VMAX", "unit": "km/h", "default": 120.0, "floor": 60.0, "ceiling": 400.0, "tuningDataRef": "fixed by policy", "owner": "lead" },
    { "name": "RECHECK_P", "unit": "probability", "default": 0.05, "floor": 0.01, "ceiling": 0.25, "tuningDataRef": "ops default", "owner": "lead" },
    { "name": "REVERIFY_DAYS", "unit": "days", "default": 30.0, "floor": 7.0, "ceiling": 180.0, "tuningDataRef": "ops default", "owner": "lead" },
    { "name": "GREY_STREAK_LIMIT", "unit": "count", "default": 3.0, "floor": 2.0, "ceiling": 6.0, "tuningDataRef": "D-USAB", "owner": "lead" },
    { "name": "CLOCK_SKEW_MAX_MIN", "unit": "minutes", "default": 5.0, "floor": 1.0, "ceiling": 60.0, "tuningDataRef": "ops default", "owner": "lead",
      "rationale": "SYNC.md §2: clock skew beyond 5 minutes is a warning, not a rejection. Kept as a tunable because it is an operational tolerance that has to move when a deployment's clocks are known-drifty, and moving it should not be a code change." },

    { "name": "FACILITATOR_MIN_GROUPS", "unit": "count", "default": 3.0, "floor": 2.0, "ceiling": 10.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_WINDOW_DAYS", "unit": "days", "default": 30.0, "floor": 7.0, "ceiling": 180.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_COOCCUR_HOURS", "unit": "hours", "default": 12.0, "floor": 1.0, "ceiling": 72.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_GROUP_PREFIX_HEX", "unit": "hex-chars", "default": 8.0, "floor": 4.0, "ceiling": 32.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_MAX_PARTNERS", "unit": "count", "default": 512.0, "floor": 16.0, "ceiling": 8192.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_MIN_ANCHOR_EVENTS", "unit": "count", "default": 2.0, "floor": 2.0, "ceiling": 10.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_MIN_COOCCURRENCES", "unit": "count", "default": 2.0, "floor": 2.0, "ceiling": 10.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_MAX_EVIDENCE", "unit": "count", "default": 32.0, "floor": 4.0, "ceiling": 512.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "FACILITATOR_MAX_FLAGS_PER_EVAL", "unit": "count", "default": 16.0, "floor": 1.0, "ceiling": 256.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "ALIAS_MAX_FLAGS", "unit": "count", "default": 3.0, "floor": 1.0, "ceiling": 20.0, "tuningDataRef": "D-SCEN", "owner": "vision",
      "rationale": "How many alias flags one evaluation may emit. A reviewable policy choice about how wide a search result is allowed to be before an officer has to open a case, not a consequence of the data." },
    { "name": "TRAVEL_MAX_FLAGS", "unit": "count", "default": 5.0, "floor": 1.0, "ceiling": 50.0, "tuningDataRef": "D-SCEN", "owner": "vision",
      "rationale": "Same shape as ALIAS_MAX_FLAGS, for impossible-travel hits, of which there are legitimately more per evaluation." },
    { "name": "DIARY_TOP_K", "unit": "count", "default": 5.0, "floor": 1.0, "ceiling": 50.0, "tuningDataRef": "D-SCEN", "owner": "vision" },
    { "name": "VIZ_DRIFT_TOLERANCE", "unit": "ratio", "default": 0.15, "floor": 0.05, "ceiling": 0.50, "tuningDataRef": "D-SCEN", "owner": "vision" },

    { "name": "YYMMDD_CENTURY_SLACK_YEARS", "unit": "years", "default": 15.0, "floor": 1.0, "ceiling": 50.0, "tuningDataRef": "SPEC.md date policy", "owner": "lead",
      "rationale": "The two-digit-year window in dev.kasoti.time.CalendarDate.parseYymmdd. A bare '05' means the century that puts it within this many years after the reference year: 2005, not 1905. It was 15 inline. It is a tunable and not a calendar constant because it is a policy about how old a document may plausibly be, and a census or a driving licence and a passport have different plausible ages." },

    { "name": "MAX_PLAUSIBLE_AGE", "unit": "years", "default": 130.0, "floor": 100.0, "ceiling": 200.0, "tuningDataRef": "SPEC.md date policy", "owner": "lead",
      "rationale": "The age a date-consistency check will believe, in dev.kasoti.checks.DateAndFormat. A 'born' date further back is a data error rather than a real person, and accepting one silently would let a forger pad the field, so exceeding this is a FAIL with R_MATH_02. Registered because it decides a verdict, not because the number is interesting. The floor of 100 is the youngest age any documented person has reached; the ceiling of 200 leaves room for a bad OCR year without making the check unreachable." },

    { "name": "REDACT_HEX_MIN_CHARS", "unit": "chars", "default": 40.0, "floor": 32.0, "ceiling": 64.0, "tuningDataRef": "D-PRIV log corpus", "owner": "privacy",
      "rationale": "The shortest run treated as a hex blob rather than as prose. Lowering it makes logs less readable and not safer, because it mangles ULIDs and finding codes; raising it lets more PII-shaped text through. The trade is deliberately asymmetric, which is why the ceiling is much tighter than the floor." },
    { "name": "REDACT_BASE64_MIN_CHARS", "unit": "chars", "default": 60.0, "floor": 40.0, "ceiling": 128.0, "tuningDataRef": "D-PRIV log corpus", "owner": "privacy",
      "rationale": "Same asymmetry as REDACT_HEX_MIN_CHARS. Sized above a base64-encoded UUID plus label and below an embedded blob." },
    { "name": "REDACT_PHONE_MIN_DIGITS", "unit": "digits", "default": 10.0, "floor": 7.0, "ceiling": 12.0, "tuningDataRef": "D-PRIV log corpus", "owner": "privacy",
      "rationale": "The shortest digit run treated as a phone number. Below roughly seven digits this rule eats every sequence number and every timestamp." },
    { "name": "REDACT_DOCNUM_MIN_DIGITS", "unit": "digits", "default": 6.0, "floor": 4.0, "ceiling": 8.0, "tuningDataRef": "D-PRIV log corpus", "owner": "privacy",
      "rationale": "The shortest digit run treated as a document number." },
    { "name": "REDACT_JWT_MIN_SEGMENT_CHARS", "unit": "chars", "default": 12.0, "floor": 8.0, "ceiling": 32.0, "tuningDataRef": "D-PRIV log corpus", "owner": "privacy",
      "rationale": "A compact JWT (RFC 7519) is three dot-separated base64url segments. The general base64 floor cannot see one, because each segment is short and a real token slides between all three segment boundaries. This is a PII floor and not a credential one: the payload of a personal-subject assertion decodes to something like {sub: ...}. The floor is a SEGMENT length because that is what discriminates -- a dotted hostname is the thing this rule must not eat, and kubernetes.default.svc.cluster.local has a 3-character segment while the shortest segment of a real JWT header ({alg: HS256, typ: JWT}) is 36. Twelve sits in the empty space between them. ONE number, not two: a separate minimum-total-chars floor would be implied by this one, and a registered tunable that no pattern reads is a threshold nobody has to keep true. THEY CANNOT CHANGE A VERDICT, unlike every other entry here: these only decide what a log line is allowed to keep." },

    { "name": "QR_PACKET_MAX_BYTES", "unit": "bytes", "default": 2953.0, "floor": 1024.0, "ceiling": 4096.0, "tuningDataRef": "SYNC.md §5 QR budget", "owner": "lead",
      "rationale": "The QR-version-40-L byte-mode capacity. SYNC.md §5 fixes the choice; registering it means a change of QR version shows up as a reviewed registry edit rather than as a silent truncation." },
    { "name": "SYNC_FILE_SPLIT_BYTES", "unit": "bytes", "default": 5242880.0, "floor": 65536.0, "ceiling": 104857600.0, "tuningDataRef": "SYNC.md §5 file split", "owner": "lead",
      "rationale": "The 5 MB file split for offline transfer." },
    { "name": "WATCHLIST_LABEL_MAX_CHARS", "unit": "chars", "default": 64.0, "floor": 8.0, "ceiling": 256.0, "tuningDataRef": "SYNC.md §5 QR budget", "owner": "lead",
      "rationale": "How much of a QR packet a watchlist label may occupy. Above this the label crowds out the payload it is attached to, which fails closed in the wrong direction." }
  ],

  "structural": [
    {
      "id": "S01-icao-9303-mrz-layout",
      "enforcedBy": ["script"],
      "scope": "every file under core/src/commonMain/kotlin/dev/kasoti/mrz/",
      "owner": "vision",
      "reason": "Field start/end positions, line lengths and check-digit weights in an ICAO 9303 Part 4 machine-readable zone are fixed by the standard, not chosen. TD1 is three lines of 30, TD3 is two of 44, the personal-number field starts at column 28 and the composite check digit sits at column 43, and the weights are 7-3-1. A device that disagreed with the standard here would reject a genuine passport, which is the loudest possible failure and not a tuning question. The whole package is exempted rather than each integer: the thing being vouched for is that this package is a codec for a published format, and a list of 46 individually-approved numbers would be a list nobody re-reasons when the standard moves. RESIDUAL RISK: a tunable added to this package would be invisible to the gate."
    },
    {
      "id": "S02-iso-8601-gregorian-calendar",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.time.CalendarDate and dev.kasoti.diary.IsoInstant",
      "owner": "vision",
      "reason": "These two files are calendar codecs end to end. Their integers are the published Howard Hinnant civil-days algorithm (719468 epoch offset, 146097-day era, 153-month, 365-day year, the /4 /100 /400 leap rule), the ISO-8601 field positions, the 86_400_000 ms day and 3_600_000 ms hour, the days-in-month table, and the 23/59/59 clock bounds. Every one is either a constant of the algorithm or a property of the Gregorian calendar. There is no operating point to re-tune in a file whose whole job is to be correct about the calendar. The one exception was already extracted: YYMMDD_CENTURY_SLACK_YEARS is a policy about how old a document may be and is registered."
    },
    {
      "id": "S03-exact-scale-factor",
      "enforcedBy": ["script"],
      "scope": "any line in the scanned source sets, where the literal is 10, 60, 100, 1000, 3600, 60000, 1000000, 3600000 or 86400000 (integer, underscored or floating-point form)",
      "owner": "vision",
      "reason": "The exact conversion factors between seconds, minutes, hours, days, milliseconds and microseconds, the percent factor, and the decimal radix. They are correct by definition, in both directions: they are the milliseconds in a day whether they appear as a unit conversion or as the fixed-decimal rounding scale in Math.round(v * 1000.0) / 1000.0. Registering one would be registering arithmetic. A wrong value here is a units bug, and the only review that catches a units bug is reading the operand next to it, which this gate does not help with."
    },
    {
      "id": "S04-hash-mix-multiplier",
      "enforcedBy": ["script"],
      "scope": "any line matching 31 * x or x * 31",
      "owner": "vision",
      "reason": "The 31-multiplier hashCode mixing idiom that Kotlin's data classes generate and that every hand-written hashCode() override in this repository copies. It is an arbitrary spreader, chosen for being odd and cheap, not an operating point; changing it changes hash distribution and nothing else. Declared so that the 27 copies read as one decision rather than 27 separate undocumented ones."
    },
    {
      "id": "S05-wire-codec-radic",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.json (Base64Codec, JsonParser, CanonicalJson, JsonValue), dev.kasoti.diary.Ulid, dev.kasoti.crypto.Primitives, dev.kasoti.factory.ModelJson, dev.kasoti.evalmetrics.MetricJson",
      "owner": "vision",
      "reason": "RFC 4648 base64 packs 3 bytes into 4 six-bit characters and base32 five bits per character over a 0..31 value range; a JSON unicode escape is exactly four hex digits over a 6-character buffer; hex is one nibble per character; and 2^53 is the largest integer binary64 represents exactly. These are radices fixed by the encodings. The rule is scoped to the encoder and decoder files rather than to the literal values on purpose: 3, 4, 5 and 6 are ordinary numbers everywhere else in the repository, and a value-set rule would have exempted them there too."
    },
    {
      "id": "S06-unicode-codepoint-table",
      "enforcedBy": ["review"],
      "scope": "dev.kasoti.log.RedactionFold",
      "owner": "privacy",
      "reason": "Unicode block boundaries and the C0/C1 control ranges: the Arabic-Indic and Devanagari digit bases, and the ranges of invisible or direction-changing codepoints. These are emphatically NOT tunables and the reason is worth stating plainly: a registered which-codepoints-are-invisible threshold would be an attack surface, because whoever could move it could un-neutralise a bidi override and forge a record. The block boundaries are the security property, so they belong in code where changing them is a diff a reviewer sees. No script rule is needed because the existing hex exemptions already cover them; this entry exists to record why they must never be given one."
    },
    {
      "id": "S07-geodetic-physical-constant",
      "enforcedBy": ["script"],
      "scope": "any line naming latDeg, lonDeg or EARTH_MEAN_RADIUS",
      "owner": "vision",
      "reason": "-90..90 and -180..180 are the definition of the coordinate system, and 6371.0088 km is the IUGG mean Earth radius. Tuning either would not tune the product; it would make the great-circle distance wrong, which is what makes the impossible-travel rule wrong. The rule is anchored on the identifier rather than on the value, because 90 and 180 are ordinary numbers elsewhere."
    },
    {
      "id": "S08-published-format-shape",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.checks.Verhoeff, dev.kasoti.log.RedactionPolicy, dev.kasoti.diary.EmbModel",
      "owner": "lead",
      "reason": "Shapes fixed by a published specification or a storage format: the Verhoeff d-table rotates its row by five, an MRZ row is 30 or 44 characters by ICAO 9303, a phone number is at most 15 digits by E.164, a JWT is three base64url segments by RFC 7519, and a quantised embedding is stored in the int8 range with MAX_ABS at 127 so that the value set is symmetric. The validators exist precisely to enforce these shapes, so the number in the code and the number in the rule are the same fact."
    },
    {
      "id": "S09-percent-unity-conversion",
      "enforcedBy": ["script"],
      "scope": "any line computing 1.0 - x, x in 0.0..1.0, or else 1.0",
      "owner": "vision",
      "reason": "The complement of a rate, and the closed unit interval it lives in. 1.0 - tar is the false-reject rate of a detector whose true-accept rate is tar; there is no independent choice in it. Registering these would give a reviewer two names for one number and a way to move a rate by editing its complement."
    },
    {
      "id": "S10-published-dsp-window",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.factory.Spectrum",
      "owner": "vision",
      "reason": "The Hann window 0.5*(1 - cos(2*pi*n/(N-1))), the -2*pi/len angular step, the 1e-12 denominator guard that keeps a flat spectrum from producing a NaN slope, the 1e6 peakiness clamp, the six-bin neighbourhood the local peak floor is measured against, and the radix-2 depth check. These are the published formulas and their numerical guards. The one genuine band choice in the file is not covered by this exemption: the radial cut separating fine toner speckle from coarse dither is registered as HALFTONE_HIGH_FREQ_RADIUS. JUDGEMENT CALL, recorded so it can be overruled: the six-bin peak neighbourhood is treated as a definition of locality rather than as a re-tunable band. If the owner disagrees it becomes HALFTONE_PEAK_NEIGHBOUR_RADIUS, and that is a registry decision rather than a code edit."
    },
    {
      "id": "S11-display-format-width",
      "enforcedBy": ["script"],
      "scope": "any line passing a literal as the decimals argument of NumberFormat.fixed or NumberFormat.percent",
      "owner": "vision",
      "reason": "Formatting: a module whose every number is a display width or a radix, and a report renderer whose every number is the number of decimals it prints a metric with. How many digits a published metric is rendered with cannot move a verdict, a threshold comparison or a byte on the wire, and giving it a threshold owner would imply a tuning process for a formatting choice. MetricSink is listed separately because the script only reaches it through a string interpolation: these literals live inside a string interpolation, which is code rather than string text, and the scanner copies the interpolation body into the code stream precisely so that a formatting width cannot hide there the way it used to."
    },
    {
      "id": "S12-model-pinned-hyperparameter",
      "enforcedBy": ["review"],
      "scope": "the :platform BlazeFace anchor decoding and TFLite tensor shapes",
      "owner": "vision",
      "reason": "MIN_SCALE, MAX_SCALE, ANCHOR_OFFSET, the stride vector and the leading batch dimension of 1 are properties of the hash-pinned blazeface_short.tflite in the DESIGN.md section 6 inventory, not operating points. AGENTS.md section 5 says model parameters are never invented here. Editing one in Kotlin would decouple the decoder from the shipped weights and degrade detection silently, which is the exact failure the model hash exists to prevent. Not registered and not scanned by this script, which only reads :core; named here so that the absence is a decision on the record rather than an oversight."
    },
    {
      "id": "S13-corpus-generation-shape",
      "enforcedBy": ["review"],
      "scope": "the MRZ corpus and builder generators",
      "owner": "vision",
      "reason": "The random mutation mix (one mutant in four rows) and the sampled birth and expiry ranges. These shape a fixture, not a verdict: the generators produce eval input and nothing in the verdict path reads them. They are not tunables because there is no operating point behind them -- there is a corpus, and its size is a property of the generator. No separate script rule is needed because they live in the S01 package."
    },
    {
      "id": "S14-report-statistic-definition",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.evalmetrics.Percentiles and dev.kasoti.evalmetrics.Classification",
      "owner": "vision",
      "reason": "0.5 is what median means and 0.95 is what p95 means, and 2.0*p*r/(p+r) is what the harmonic mean is. Registering these would be registering a definition: moving 0.95 to 0.99 would not tune anything, it would make the function called p95 stop being p95 while every table in EVAL.md kept the label. The choice to publish p95 rather than p99 is a policy, and belongs in the harness's configuration."
    },
    {
      "id": "S15-spec-fixed-gate-bar",
      "enforcedBy": ["script"],
      "scope": "dev.kasoti.evalmetrics.Gate",
      "owner": "lead",
      "reason": "SPEC.md section 7 states these bars and they are not up for re-tuning by a vision edit: 100% mutate-catch over the detectable mutants, and 50/50 crash-free end-to-end runs per platform. The registry already carries the section 7 bars that are policy -- the macro recall floor, the face FRR ceiling and the wall-clock ceiling all arrive as arguments. A bar fixed in SPEC.md belongs to SPEC.md. If the lead changes one, SPEC.md and this entry change together, which is the review trail the registry exists for."
    },
    {
      "id": "S16-feature-vector-layout",
      "enforcedBy": ["script"],
      "scope": "any line naming LBP_BINS, comparing a bin index, or declaring DIM",
      "owner": "vision",
      "reason": "The width of a feature vector: 59 local-binary-pattern bins plus a separator bin, and the 128 dimensions of an embedding. The extractor and the model that consumes the vector share one width, and the encoder and the decoder of a stored embedding share one range. Moving either desynchronises two halves of a pair rather than tuning anything."
    },
    {
      "id": "S17-capacity-reserve",
      "enforcedBy": ["script"],
      "scope": "any line declaring a constant whose name ends in _BYTES, _SIZE, _CAPACITY, _OVERHEAD or _LENGTH",
      "owner": "vision",
      "reason": "A declared reserve, sized with headroom rather than measured: 512 bytes of envelope in BundleExporter covers the fixed fields plus the 64-hex HMAC with room to spare. The matching cap is a separate registered tunable (QR_PACKET_MAX_BYTES); the reserve is a margin, in the same category as an array capacity, and giving it a tuning owner would imply a decision where there is only slack."
    }
  ]
}
"""
}
