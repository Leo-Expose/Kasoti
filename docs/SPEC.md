# KASOTI — Spec Sheet (SPEC.md) · v4 FINAL

> **Status of this document.** This is the *target*. Nothing in §4–§7 is a report of what
> exists. For what is true today read `docs/STATUS.md` — and note that four of the six
> assumptions in §10 are currently false, including the ones that carry the face and
> signed-QR layers. Acceptance criteria in §6 are unmet in full: no milestone has been
> accepted, `:app-android` has never been **run on a device** (it has compiled and packaged
> since 2026-10-03, and no `.aab` can be produced from this build), and no red-team day has been
> run.

## 1. Goals
G1. Screen identity documents at SSB checkpoints **offline**, verdict in seconds.
G2. Cover the full PS-26188 bullet list (fake docs, altered photo, modified DOB, tampered stamps, impersonation, multi-identity, expired) with a named mechanism per bullet (traceability matrix §8).
G3. Run on low-end Android (2 GB RAM, no Play dependency for verdict) + Windows/Linux/Mac console from **one shared core**.
G4. Produce **measured** metrics + a failure gallery before finals — no unmeasured claims.
G5. Demo live, adversarially (judges try to fool it), installable from USB in airplane mode.

## 2. Non-goals (explicit)
N1. No live registry lookups (UIDAI/passport DB/DigiLocker) — interfaces ready, stubs labeled SIMULATED.
N2. No central citizen biometrics database. Diary = crossing-event log with retention caps.
N3. No court-grade forensics. KASOTI triages; experts confirm.
N4. No iOS. No cloud backend. No real-time multi-post networking.
N5. No age/gender estimation, no emotion/behavior scoring, no predictive policing scores.

## 3. Users & personas
- **P1 Jawan (primary lane):** low training time, gloves/dust/sun, Hindi-first. Needs: 3-tap flow, giant verdict, voice readout, works one-handed.
- **P2 Post supervisor:** secondary inspection, enrollment approval, shift reports. Needs: desktop console, evidence zoom, override + reason codes.
- **P3 HQ analyst (mini-view):** merged flags across posts. Needs: alias/travel/facilitator lists, export bundles.
- **P4 Vigilance/forensics (downstream):** case bundle import. Needs: evidence crops + decision log (read-only).

## 4. Functional requirements (testable; ID stable)
**Capture**
- FR-C1 Guided stranger flow ≤4 steps; each step has live quality meter; GREY blocks advance on failure.
- FR-C2 Auto quad-crop with manual corner fallback (4-drag handles).
- FR-C3 Macro mode: 2 patches (photo-zone + text-zone), sharpness bar, fixed-focus lock via clip shroud.
- FR-C4 Face capture: 1 s clip, ≥5 frames, pose/blur/brightness gates.
- FR-C5 Demo mode: seeded fixtures, one-tap reset, visible DEMO watermark.
**Router & math**
- FR-M1 Track router by signals (MRZ present / signed-QR present / chip / none) — rules only.
- FR-M2 ICAO 9303 MRZ parse + 7-3-1 verify for TD3/TD1; per-field fail attribution.
- FR-M3 Verhoeff (Aadhaar), format validators (voter/DL), date/expiry logic with reason codes.
- FR-M4 VIZ↔MRZ fuzzy cross-match (name/DOB/number) with drift score.
**QR & chip**
- FR-Q1 Aadhaar secure-QR parse + offline RSA signature verify (bundled test/prod keys, rotation doc).
- FR-Q2 Unsigned-QR parse + consistency-only verdict contribution, labeled UNSIGNED.
- FR-N1 (P1) NFC e-passport: BAC→read DG1/DG2→PA verify; AA if supported. Stub-first with labeled fallback.
**Factory**
- FR-F1 Print-process classify per patch: {offset-like, inkjet-like, laser-like, dyesub-like, screen, photocopy, unknown}; patch-level + doc-level aggregation.
- FR-F2 UV present/absent/unsupported recorder (docs table in DESIGN.md) with shroud guidance.
- FR-F3 Manual macro-zoom assist (side-by-side, pinch) for stamp/photo-boundary inspection.
- FR-F4 (P1-experimental) Torch-tilt viewer with frame-diff overlay, EXPERIMENTAL badge.
**Face & diary**
- FR-H1 1:1 live↔doc match with similarity + quality-adjusted threshold; score histogram artifact saved per case.
- FR-H2 Passive liveness always; active head-turn challenge for AMBER only.
- FR-H3 Diary append (embedding + hashes + meta) and 1:N search with ranked hits + thresholds.
- FR-H4 Alias / impossible-travel / facilitator / watchlist rules (FUSION.md) with evidence links.
- FR-H5 Trust lanes: enroll (supervisor PIN) / verify-fast / revoke / random-recheck scheduler / re-verify interval.
**Sync & console**
- FR-S1 Export/import/merge `kasoti-sync/1` bundles (file/USB); HMAC auth; append-only merge; idempotent.
- FR-S2 Watchlist create→distribute (file + QR-small)→match loop.
- FR-S3 Desktop console: secondary review, diary admin, sync hub, shift report, case-bundle export (zip).
- FR-S4 Audit: hash-chained decision log; override with reason code; one-tap wipe (with supervisor PIN).
**I18n/a11y/robustness**
- FR-R1 UI strings: English + Hindi (P0); Nepali (P1). Voice verdict Hin/Eng (Android).
- FR-R2 First-run offline: fresh install in airplane mode passes smoke suite.
- FR-R3 All thresholds in one registry file; no magic numbers in UI/platform code.

## 5. Non-functional requirements
- NFR-P1 Compute budget (mid-low phone, e.g. 4×A53): math <10 ms · QR <100 ms · macro 2 patches <150 ms · face detect+embed <400 ms · diary 10k <100 ms. Wall-clock: stranger ≤12 s guided · trust ≤4 s. Measured on 2 named devices, medians reported.
- NFR-S1 Size: APK ≤35 MB (models ≤8 MB total) · desktop bundle ≤120 MB (JVM+TFLite natives+Tess).
- NFR-O1 Offline: zero runtime downloads; CI airplane-install test.
- NFR-B1 Battery: <3% per 100 stranger screenings (measured, or publish measured number).
- NFR-P2 Privacy: no raw face photo retained by default (evidence crops: doc-photo region only,Blur option); embeddings AES-at-rest (platform keystore); retention default 30 d diary / 7 d evidence; one-tap wipe; no PII in logs/crash reports (log scrubber test).
- NFR-R1 Reliability: 50 consecutive E2E runs crash-free on each platform before M-gate; ANR-free capture.
- NFR-U1 Usability: jawan training ≤30 min (video + loupe-game); SUS-style 5-question check with ≥5 novices, issues logged.
- NFR-M1 Maintainability: :core ≥80% line coverage on mrz/checks/qr/factory-math/diary/sync/fusion; detekt+ktlint clean; every new dep needs ADR note.

## 6. Acceptance criteria (per milestone — demo-or-it-didn't-happen)
- M0: `core` tests green; eval smoke prints metrics; sync file round-trips A→B with merge verified; clip v2 photographed + calibration routine runs.
- M1: airplane-mode E2E (stranger + trust) on 2 devices ×20 runs; medians recorded; APK size checked; demo-mode reset works.
- M2: alias planted on phone A flags on phone B post-sync; watchlist QR loop works; desktop shift report + case-bundle export open on a clean machine.
- M3: red-team day executed; failure gallery ≥ every miss; frozen models/hashes; metrics deck = harness output; 25 Q&A rehearsed aloud.
- M4: venue rehearsal ×3; USB installers verified on wiped devices; roles + backups assigned.

## 7. Metric gates (ship-blockers; numbers from EVAL.md)
| Gate | Bar |
|---|---|
| MRZ mutate-catch (10k corpus) | 100% |
| QR tamper-catch (fixtures) | 100% |
| Macro offset-vs-inkjet (clip, held-out) | publish; target ≥90%, floor for RED-use ≥85% else AMBER-only |
| Macro without-clip | publish (expect lower; drives clip-mandatory rule) |
| Face TAR@FAR on OUR pairs | publish operating point; floor: FRR ≤5% @ FAR 0.1% else AMBER-biased policy |
| Wall-clock medians | publish per device; stranger ≤15 s hard ceiling |
| Crash-free | 50/50 E2E per platform |

## 8. Traceability: PS bullets → mechanism → test
| PS bullet | Mechanism | Proved by |
|---|---|---|
| Fake passports/visas | MRZ math + chip PA + macro + UV | MRZ corpus · NFC test · macro set · UV table |
| Altered photographs | macro photo-zone + zoom assist + face 1:1 + tilt-exp | paste set · face pairs |
| Modified DOB | check-digit + VIZ↔MRZ + QR-sig + macro DOB-zone | mutate corpus · fixtures |
| Tampered stamps | zoom assist + UV + macro stamp texture | stamp fixture gallery |
| Impersonation | face 1:1 + liveness (passive + AMBER-active) | pairs · spoof set (print/screen) |
| Multi-identity | diary alias + travel + facilitator + watchlist | scenario fixtures · sync test |
| Expired/invalid | date logic gates | unit corpus |

## 9. Scope tiers
**P0 (finals must-have):** FR-C1–C5, M1–M4, Q1–Q2, F1–F3, H1–H5, S1–S4, R1(Eng+Hin)–R3.
**P1 (demo if green by M2 review):** FR-N1 real-chip verify, FR-F4 tilt overlay beyond viewer, Nepali strings, desktop NFC notes, HQ map-list polish.
**P2 (deck/preview only):** ORT migration, RapidOCR desktop, registry integrations, iOS, cloud dashboard, stamp-bridge automation.
**Cut list (do not build):** age/gender, ELA-CNN, template registries, LLM features, central biometric DB, real-time networking, predictive scores.

## 10. Assumptions & dependencies
A1. ~~Team ~6, mixed; Android-strong; ≥1 person owns eval/data full-time in M0–M1.~~
**Superseded by reality: the project is solo-maintained** (`docs/HANDOFF.md` §2), and the
`solo-founder variant` in `ROADMAP.md` §7 is what is being built rather than a contingency.
A2. ⚠️ **STILL NOT MET — but for half the original reason, and the change is dated.** It used to
read "**Zero Android devices and no Android SDK**". ⚠️ **Corrected 2026-10-03: an Android SDK is
now installed** (`local.properties` `sdk.dir=`, `ANDROID_HOME=/opt/android-sdk`) and
`:app-android` compiles and packages four APKs within the 35 MB budget. **Still zero Android
devices and no `adb`**, so nothing has been installed or run, and NFR-S1's installable-offline
half, NFR-R1's 50 crash-free runs and §6 M1 have no exit. The dev host is Linux, so Windows and
Apple-Silicon desktop are additionally "review-only, no on-device inference" for lack of any
TFLite native (`docs/BUILD.md` §4, spike 01 §4). The dev checkout is on an **exFAT volume that
ignores `chmod`**, so provisioning must target `~/.kasoti/provisioning`, not the repo. There is
also **no `.aab`** — `:app-android:bundleRelease` fails on AGP 8.9.2 with ABI splits, so delivery
is APK-only.
A3. ⚠️ **NOT MET** — no genuine IDs have been contributed. `eval/data/` exists with manifests
and split declarations; **every dataset has zero media rows.**
A4. No real e-passport sample guaranteed → NFC stub-first. (Unchanged; still true.)
A5. ⚠️ **NOT MET, and it was never true** — "UIDAI QR test vectors + public-key material
obtainable from public implementations/docs". The keys have **not** been obtained and no source
has been confirmed; `THIRD_PARTY.md` §3 records the absence rather than a URL, because AGENTS.md
§8 forbids inventing one. Rotation remains an MHA dependency. `docs/STATUS.md` R-B.
A6. Venue has power + projector; network assumed hostile/absent (we demo airplane-mode anyway).
⚠️ Untested — no venue has been visited and no rehearsal has happened (`DEMO.md` §6).

**Read this section as a list of unmet assumptions, not a list of met ones.** Four of six
(A1, A2, A3, A5) are false, and they are the four that carry the load-bearing layers.

## 11. Glossary (short)
Track = doc-type pipeline (passport/aadhaar/voter-DL/paper). GREY = retake, no decision. AMBER = secondary. Case bundle = exported evidence zip. Diary = crossing-event embedding log. Lane = one screening station.
