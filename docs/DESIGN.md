# KASOTI — Design Doc (DESIGN.md) · v4 Final

## 1. Architecture

```
┌─ APP-ANDROID (Kotlin, CameraX, TFLite, ML-Kit-bundled*) ─┐  ┌─ APP-DESKTOP (headless JVM text console) ─┐
│ capture lanes · verdict · voice · diary · sync file      │  │ Post-Console CLI · case-bundle export     │
│ NFC (P1) · USB/file sync · demo mode                     │  │ shift report · sync hub · macro collector  │
│ ⚠️ WRITTEN BUT NEVER COMPILED — no SDK, no device        │  │ ✅ runs: `./gradlew :app-desktop:run`     │
└───────────────┬──────────────────────────────────────────┘  └──────────────┬──────────────────────────┘
                │ :ui — pure-Kotlin presentation state + a FieldView contract   │
┌───────────────▼────────────────────────────────────────────────────────────────▼────────────────────────┐
│ :core (Kotlin Multiplatform common — pure logic, 270 tests green)                                          │
│ mrz · checks · qr · factory · face-math · diary · sync · fusion · audit · evalmetrics · threshold        │
└───────────────┬──────────────────────────────────────────────────────────────────────────────────────────┘
                │ expect/actual :platform  (JVM actuals ONLY — no androidMain)
┌───────────────▼──────────────────────────────────────────────────────────────────────────────────────────┐
│ inference(desktop TFLite via ai.djl.tflite) · ocr(Tess4J + manual fallback) · crypto(JCA) · imaging       │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**What is not in the diagram, because it does not exist:**
- **No `@Composable` anywhere.** DESIGN D7 chose Compose Multiplatform; that shape was **not
  taken** (decision log, `HANDOFF.md` §7). `:ui` is a plain `kotlin-jvm` module of presentation
  state, a reducer and a `FieldView` interface, with no renderer bound. `:app-android` writes its
  own Compose view; a desktop renderer does not exist. Logic is tested (44 tests); the binding is
  not. STATUS R-R.
- **No Android `actual`s in `:platform`.** `platform/src/` is `jvmMain` + `jvmTest` only, and
  `core/src/` is `commonMain` + `commonTest` only, so the Android target of `:core` has never
  been compiled. `:app-android` carries its own copies of those seams. STATUS R-P.

**OCR decision (final):** Android = ML Kit Text Recognition v2 (on-device, bundled, Latin+Devanagari) — verdict path does NOT need Play Services at runtime (bundled model). Desktop = Tesseract via Tess4J + system install (P0) with **manual MRZ entry fallback** (console-appropriate). RapidOCR = P2. *(Footnote: "ML-Kit-free" struck — ML Kit bundled-model is allowed; ban is on Play-Services-download-at-runtime. ⚠️ The Android OCR path has never been compiled or run.)*

**Inference decision (final): TFLite everywhere, same bytes, hash-pinned.** Kills ONNX-conversion risk. ⚠️ **Corrected:** the desktop does *not* use a first-party TFLite-JVM runtime — none exists. It uses `ai.djl.tflite`, which **has natives for `linux-x86_64` and `osx-x86_64` only**; `windows-x86_64` and Apple-Silicon Mac are review-only with no on-device inference, and a version bump will not fix it. See BUILD.md §4, spike 01 §4, STATUS R-T/R-U. ORT migration = P2, and it would need the model in ONNX, which breaks "same bytes". GPU/NNAPI delegates: optional, off by default, must not change outputs beyond tolerance test — ⚠️ and "off by default" was **unenforced** until 2026-09-30: the Android binding wrote `apply { useXNNPACK = true }`, which resolves to nothing, because TFLite's `Options` exposes setters and no getters. It now calls `setUseXNNPACK(true)`.

**No OpenCV anywhere.** Needed ops (resize, crop, grayscale, LBP, FFT, NMS, cosine) are implemented in `:core` (pure Kotlin) or thin platform imaging (decode → RGB ByteArray). Rationale: reproducible builds, tiny binaries, no NDK/native-packaging sinkhole.

## 2. Module map — :core packages & key APIs

> **Read this as the design intent, not as a signature reference.** These are the calls the
> design was written around; the shipped names differ in detail. Concretely:
> `verhoeffOk(aadhaar12)` → `Verhoeff.isValid(String)` (`checks/Verhoeff.kt`);
> `cosine(a,b)` → `FaceMath.cosine` (`face/FaceMath.kt`); `validateVoterId`/`validateDl` ship as
> `FormatValidators.validateVoterId`/`validateDl` returning a `CheckOutcome`, not a bare `Verdict`;
> `dateLogic(...)` → `DateLogic.evaluate`; the factory functions are
> `Spectrum`/`Lbp`/`Classifier` rather than `fftHalftoneScore`/`lbpHistogram`/`classifyProcess`;
> sync is `sync/{SyncEnvelope,BundleExporter,BundleImporter}`; fusion is `FusionEngine` plus
> `RedRules`/`AmberRules`/`CoverageRules`. The **packages** and their responsibilities are
> exactly as below; the identifiers are not a contract. Check the source before quoting a name.

```kotlin
// mrz — ICAO 9303 (TD3 2×44, TD1 3×30)
parseMrz(lines: List<String>): MrzResult          // fields + per-field check ok
verifyCheckDigit(field: String, given: Char): Boolean   // weights 7-3-1 mod 10
generateMrzCorpus(seed: Long, n: Int): List<Case> // valid + mutated (eval + tests)

// checks — formats & logic
verhoeffOk(aadhaar12: String): Boolean
validateVoterId(s: String): Verdict; validateDl(s: String): Verdict
dateLogic(issue: Date?, dob: Date, expiry: Date?, now: Date): List<Finding>
vizMrzMatch(viz: VizFields, mrz: MrzFields): DriftScore  // normalized name/phone-date compare

// qr — Aadhaar secure QR (+ generic)
parseSecureQr(payload: ByteArray): QrPayload             // XML/GZIP variants incl.
verifyQrSignature(payload: QrPayload, keys: KeyRing): SigResult  // RSA-SHA256, test+prod rings
parseUnsignedQr(text: String): Map<String,String>        // consistency-only

// factory — print forensics on 256×256 gray patches
fftHalftoneScore(patch: Gray256): Float        // radial-band peak energy [0,1]
lbpHistogram(patch: Gray256): FloatArray       // uniform-LBP-59
classifyProcess(patch: Gray256, w: SvmWeights): ProcessLabel  // {OFFSET,INKJET,LASER,DYESUB,SCREEN,PHOTOCOPY,UNKNOWN} + margin
aggregate(patches: List<PatchResult>): DocProcess  // photo-zone vs text-zone compare → MATCH/MISMATCH

// face-math — embeddings are platform-produced; math here
cosine(a: Embedding128, b: Embedding128): Float
qualityGate(sample: FaceSample): GateResult     // blur/pose/brightnessesson thresholds from FUSION
fuseMatchScore(sim: Float, qDoc: Float, qLive: Float): AdjustedSim
farFrr(pairs: List<PairTrial>): Curve           // eval + threshold selection

// diary — file-backed repo (JSONL + embeddings.bin); interface allows SQL later
interface Diary { append(e: CrossingEvent); search(q: Embedding128, topK: Int): List<Hit>; get(id): Event?; purge(olderThan: Instant): Int; wipe() }
aliasRule(hits, claimedName, claimedDob): List<AliasFlag>
impossibleTravel(events, posts: PostMap): List<TravelFlag>   // dist/Δt > vmax
facilitatorRule(events, windowDays: Int, minGroups: Int): List<FacFlag>

// sync — kasoti-sync/1 (wire in SYNC.md)
exportDelta(sinceSeq: Long, hmacKey: Secret): Bundle
importBundle(bytes: ByteArray, hmacKey: Secret): MergeReport  // verify→append-merge, idempotent
watchlistPack(entries): SmallBundle // QR-sized

// fusion — deterministic verdict (rules in FUSION.md)
decide(case: Evidence, reg: ThresholdRegistry): Verdict  // GREEN/AMBER/RED/GREY + findings[]
// audit — hash chain over decisions (SHA-256, JCA)
append(rec: DecisionRecord): Hash; verifyChain(): Boolean
```

**Thresholds:** single `ThresholdRegistry` (versioned file, `fusion/thresholds.v1.json`); platform/UI code must not contain magic numbers (lint check). ⚠️ **Half-true today:** the registry *is* implemented, versioned, floor/ceiling-enforced and tested — as a Kotlin enum in `core/.../threshold/ThresholdRegistry.kt`, with 34 thresholds. The **file** `fusion/thresholds.v1.json` does not exist, and **all 34 defaults are untuned** (values a human typed, not values a split chose). The magic-number check that would enforce the second half is red at 171 lines. STATUS R-O, §4.2.

## 3. Platform expect/actual surface (minimal by design)

| Capability | Android actual | Desktop actual | I/O contract |
|---|---|---|---|
| Face detect | BlazeFace-short TFLite. ⚠️ **the binding is compiled only against hand-written API stubs — never against the real SDK — and has never run on a device.** The decode, sigmoid, anchor grid, NMS and input preparation are now pure Kotlin in `dev.kasoti.android.ml`, unit-tested and required to be **bit-identical** to `:platform`'s on the real detector output committed in `eval/fixtures/face/raw_outputs/` | Same BlazeFace TFLite via `ai.djl.tflite`. ✅ works on `linux-x86_64` / `osx-x86_64`; **review-only elsewhere** | RGB in → boxes+landmarks out |
| Face embed | ⚠️ **NO WEIGHTS EXIST** — the `emb_v1.tflite` slot is empty. The code path is a labelled seam that yields `face = null` | same — no weights, no path | aligned face → 128 floats |
| OCR | ML Kit TR v2 bundled. ⚠️ never compiled | Tess4J + tesseract (MRZ config) / manual fallback. ✅ 137 platform tests green | crop → text + conf |
| Crypto | JCA (RSA/EC/AES) + Keystore. ⚠️ never compiled | JCA + OS store notes. ✅ | bytes in/out |
| NFC | IsoDep + BAC/PA (P1; stub P0) | Phase-2 stub (documented) | — |
| Imaging | Bitmap→RGB/gray, resize. ⚠️ never compiled | ImageIO→RGB/gray, resize. ✅ | → ByteArray to :core |
| TTS | Android TTS (Hin/Eng). ⚠️ never compiled | none (P2) | — |

⚠️ **There are no Android `actual`s at all.** `platform/src/` contains only `jvmMain` and
`jvmTest`; `:app-android` carries its own implementations of these seams. So the Android
column above is a *design*, and every "never compiled" in it is literal. STATUS R-P, R-C.

⚠️ **"Shared NMS" was wrong, and being wrong about it is what the face-detector parity work
turned up.** `:platform`'s detector lives in a KMP `jvmMain` source set that an Android module
cannot resolve, so the two implementations of the SSD decode, the sigmoid, the 896-anchor grid and
NMS are **duplicates**, not one shared function — there is no `platform/src/androidMain` to put
them in. That is why the decode was extracted into `app-android/.../ml/`, a pure-Kotlin file with
no `android.*` and no `org.tensorflow.*` import, and why `app-android/tools/verify-offline.sh` now
loads `:platform`'s compiled classes and asserts the two agree **bit for bit** on the real detector
output in `eval/fixtures/face/raw_outputs/`. Until `:platform` gains an `androidMain` source set
(which needs a `namespace`/`compileSdk` there and an ownership handover), duplication is structural
and the test is what keeps it from becoming drift. The input preparation is duplicated for the same
reason and for a second one: Android's `Bitmap.createScaledBitmap(…, filter = true)` is a
different sampler from the desktop's half-pixel-centre bilinear, so sharing would have been wrong
as well as impossible.

**Comparability law:** diary embeddings are comparable ONLY if same model bytes + same alignment + same normalization. Enforced: `embModelSha256` recorded in every diary record + every sync bundle; import rejects mismatched model generations into quarantine (never silently merges). ⚠️ Note what the law does *not* cover: the **detector's** boxes and keypoints are compared between platforms (they are the same file and the same decode), but the *embedder* and its 5-point alignment — which the law is about — have never been compared, because there is no embedder. `TfliteFaceDetector.REFERENCE_EYE_DISTANCE` is therefore an unverified constant with no desktop counterpart to check it against, and is recorded as an open parity item rather than presented as satisfied.

## 4. Data formats (normative summaries; SYNC.md is normative for sync)

```json
// diary record (JSONL line) — embeddings in parallel .bin (128×int8, same order)
{"id":"evt_...","seq":412,"device":"post3-ph1","post":"RAXAUL","ts":"2026-..Z",
 "track":"aadhaar","nameSha":"…","dob":"1998-..-..","docHash":"…",
 "embModel":"emb_v1@sha256:9f…","q":0.87,"verdict":"GREEN","prev":"sha:…"}
```
Case bundle (zip): `case.json` (evidence + findings + policy versions) + `crops/` (doc-photo region, macro patches, NO live-face raw by default) + `audit.txt` (chain excerpt). Size target <500 KB.

## 5. Algorithms (final choices + why)
- **MRZ:** direct ICAO implementation; OCR post-correction limited to `0↔O` in check-digit positions + conf-weighted retry; never "fuzzy-accept" a failed check digit (fail-closed).
- **Aadhaar QR:** parse → canonical bytes → RSA-SHA256 verify → field extract → chain checks: QR↔print name/DOB equality → face binding. Keyring: `uidai_test` + `uidai_prod` slots, rotation procedure in THREAT_MODEL.md.
- **Macro:** per patch: FFT radial-peak score + LBP-59 → linear SVM (7 classes, trained offline sklearn, exported weights ~50 KB) → margin; doc-level: photo-zone vs text-zone label compare + margin floors. Calibration card white-balance/scale normalize first (DATA.md).
- **Face:** detect → 5-pt align (112×112) → embed → L2-norm → cosine. Quality gates BEFORE match (blur Laplacian-var, brightness band, yaw/pitch from landmarks). Spoof gates: screen-subpixel (macro FFT on doc capture doubles for print attacks) + texture + AMBER-active head-turn (yaw delta >15° in 2 s).
- **Diary search:** INT8-quantized embeddings, brute-force SIMD-friendly loop; 10k<100 ms target; top-K + margin rule (hit must beat runner-up by δ to be actionable alone).
- **Rules:** alias (sim≥T_hi AND (name≠ OR dob≠)) → RED-candidate (needs supervisor confirm UX, not silent); travel (v>vmax=120 km/h w/ post coords) → RED; facilitator (≥K distinct groups/N days) → AMBER; watchlist (sim≥T_wl) → AMBER + supervisor.

## 6. Model & key inventory (as of 2026-09-30; see `THIRD_PARTY.md` §3 and spike 01 for provenance)

| Artifact | Source | License check | Android | Desktop | Size | Real state |
|---|---|---|---|---|---|---|
| `blazeface_short.tflite` | MediaPipe / BlazeFace short-range, float16/1 | **Apache-2.0 — VERIFIED on the model card** | TFLite | via `ai.djl.tflite` | 229,746 B | ✅ **OBTAINED.** SHA-256 `b4578f35…`, digest measured by us (Google publishes no `.sha256` sidecar). Runs on desktop. `blaze_face_full_range` (1,083,786 B) also fetched and recorded, **not adopted** |
| `emb_v1.tflite` | MobileFaceNet-class | n/a | — | — | — | ⛔ **DOES NOT EXIST.** 7 candidates evaluated, all rejected on licence grounds; 2 upstream repos are 404. No weights were fabricated. `face = null` → layer UNAVAILABLE → **GREEN 1:1 unreachable**, fail-closed by design |
| `svm_print_v1.json` (D-MACRO) | to be trained by us (sklearn) | ours | :core | :core | ~50 KB | ⛔ **DOES NOT EXIST** — no D-MACRO media. `eval/data/macro/manifest.csv` has 0 rows |
| `svm_print_v1_synthetic.json` | `eval/tools/synth_macros.py` | ours | :core | :core | 13,718 B | ⚠️ **SYNTHETIC. Not D-MACRO, never gate-eligible.** macro-F1 0.8469 on its own synthetic report split (run `train-20260929T200155Z-SYNTHETIC-s20260932`) — a property of the generator, **not** a print-process result. Committed deliberately so the demo does not silently abstain to UNKNOWN |
| ML Kit TR + barcode bundled | Google | ToS offline use — verify; **banned** `com.google.android.gms` variants | bundled | n/a | ~10 MB* | ⚠️ declared in the catalog, **never compiled** |
| Tesseract + tessdata | distro | Apache | n/a | system | external | declared; Tess4J engine + tests exist on desktop |
| `uidai_qr_keys.json` | **no confirmed source** | UNVERIFIED | bundled | bundled | KBs | ⛔ **NOT OBTAINED.** `:core` exercises TEST keys only. Signed-QR cannot be claimed as working against real material. STATUS R-B |
| HMAC sync secret | generated at provisioning | n/a | keystore | OS-protected file | 32 B | `scripts/provision.sh` works. ⚠️ **the repo is on an exFAT volume that ignores `chmod`**, so a secret written inside the checkout is world-readable; provision to `~/.kasoti/provisioning`. STATUS R-Q |
*Counts toward APK budget; if over, P1 fallback: Tesseract-Android (Tess4J-android) — undecided, and unmeasurable while there is no APK.

**M0 model spike (timeboxed 2 days, decision matrix in EVAL.md):** ✅ **CLOSED — see
`docs/spikes/01-face-model.md`.** Result: detector adopted (11/12 on the EVAL.md §5 scorecard);
embedding slot left **empty on licence grounds**; and the spike's second question — whether a
desktop TFLite JVM runtime exists at all — turned out to be the harder one, and the answer was
no (BUILD.md §4, spike §4). Candidates A) MobileFaceNet-TFLite port B) GhostFaceNet
C) EdgeFace-XS are all rejected; D/E/F were added during the search and are also rejected.

## 7. Error & GREY policy
Quality gates are fail-closed: blur/glare/pose/OCR-conf below floor → GREY with specific retake instruction ("move out of direct sun", "hold 8 cm", "clean lens"). GREY rate is a tracked metric (target <8% stranger attempts in field-like light; gaming GREY to dodge RED is itself logged: ≥3 GREYs in a row → AMBER + supervisor).

## 8. Security/correctness invariants
I1 model-hash match on diary import else quarantine · I2 monotonic seq per device (replay reject) · I3 thresholds versioned + logged per decision · I4 no verdict path touches network · I5 PII scrubber on logs · I6 audit chain verifies · I7 demo-mode watermark + fixture-seed isolation.

**Status per invariant, measured 2026-09-29.** This section used to carry the header
"invariants (tested)". That was not true of every row and is not claimed any more:

| # | Invariant | Tested? | Evidence |
|---|---|---|---|
| I1 | model-hash match on diary import, else quarantine | ✅ yes | `SyncTest` + `DiaryTest` (9 + 5 quarantine assertions) |
| I2 | monotonic seq per device (replay reject) | ✅ yes | `SyncTest`, 8 replay assertions |
| I3 | thresholds versioned + logged per decision | ⚠️ **partly** | `ThresholdRegistry` floors/ceilings/version/contentHash are exercised by tests. The *file* `fusion/thresholds.v1.json` does not exist, and the magic-number check is red at 171 lines — so "no magic numbers" is unenforced (STATUS R-O, §4.2) |
| I4 | no verdict path touches network | ✅ yes, but by **source grep**, not bytecode | `scripts/check_no_network_in_core.sh` — green, 75 files scanned, exit 0. A banned import would be caught; a reflective or generated one would not. "Bytecode test" was the wrong word |
| I5 | PII scrubber on logs | ❌ **NO** | `scripts/pii_scrubber_test.sh` is **red**: no scrubber in `:core`/`:platform`/`eval/src`. A `LogScrubber` + tests exist in `:app-desktop` only. Until the rules are in `:core` and every field routes through them, "no PII in logs" is an intention (STATUS §4.1) |
| I6 | audit chain verifies | ❌ **NO** | `AuditChain.kt` is implemented and **has no test in any module** — `rg -l AuditChain` over `core/src/commonTest`, `platform/src/jvmTest` and `eval/src` returns nothing. `:app-desktop`'s `verify` command exercises it end to end, but nothing asserts the chain properties |
| I7 | demo-mode watermark + fixture-seed isolation | ⚠️ **partly** | The UI watermark is asserted (`ui/src/test/.../FlowControllerTest.kt`, 5 references). **"Demo events never merge to real diary" is untested** — the demo session code lives in `:app-android`, which has never been compiled |

## 9. Key decisions (ADR-lite)
| ID | Decision | Rationale | If wrong |
|---|---|---|---|
| D1 | TFLite everywhere | zero conversion risk, one hash to pin | ⚠️ **Corrected.** The "if wrong" column used to say "ORT P2 if desktop perf bites", which named the wrong failure mode: **perf is not what breaks it.** There is no first-party TFLite JVM artifact at all, and the substitute that exists has natives for two of four desktop targets. `windows-x86_64` and Apple-Silicon Mac are review-only with no on-device inference, permanently, and no version bump changes that. The escape hatch is ORT-JVM — which needs the model in ONNX, i.e. a different file per platform, which is the thing D1 exists to prevent — so it is a spike, not a bump. BUILD.md §4, spike 01 §4, RISKS R4, STATUS R-T |
| D2 | File diary (JSONL+bin) | no SQL setup, inspectable, sync-trivial | migrate behind Diary interface |
| D3 | HMAC sync (prototype) | 20-line JCA both platforms; PKI later | upgrade path in SYNC.md §7 |
| D4 | No OpenCV | packaging + NDK sinkhole avoided | revisit only with measured need |
| D5 | Tess4J desktop OCR + manual fallback | console-appropriate, honest | RapidOCR P2 |
| D6 | ML Kit Android OCR bundled | best accuracy/effort on-device | Tess-android fallback if size/ToS. ⚠️ never compiled or measured |
| D7 | ~~CMP shared UI~~ → **NOT TAKEN as written** | D7's premise was team leverage, and there is no team | `:ui` is a plain `kotlin-jvm` presentation-state module with a `FieldView` contract and no `@Composable`; the binding is a separate, later, additive change and has never been compiled. See §1 and STATUS R-R |
