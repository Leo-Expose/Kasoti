# KASOTI — Fusion Rules & Thresholds (FUSION.md) — normative

## 1. Verdict lattice
`GREY` (no decision, retake) overrides everything except hard-RED data proofs? NO — decision order is fixed:
1. If capture-quality fails → GREY (list causes). Exception: hard-RED proofs already in hand (expired date, bad check digit) attach as findings but verdict stays GREY("retake; note: prior capture already showed <finding>") — never accuse off a bad capture.
2. Else evaluate hard-RED rules (§2) → RED + evidence (first-hit + all corroborating).
3. Else AMBER rules (§3) → AMBER + ranked reasons.
4. Else GREEN (+ trust-fast flag if applicable).

## 2. Hard RED (each needs named evidence artifact)
| Code | Rule | Evidence shown |
|---|---|---|
| R-MATH-01 | any MRZ check digit fail (fail-closed, no fuzzy accept) | field + expected/got digits |
| R-MATH-02 | expired / issue-after-expiry / impossible calendar date | dates |
| R-QR-01 | signed-QR signature INVALID on signed tracks | sig report |
| R-QR-02 | QR↔print name/DOB mismatch beyond tolerance | side-by-side fields |
| R-CHIP-01 | e-passport PA fail / DG1↔MRZ mismatch | PA report (P1) |
| R-FACE-01 | 1:1 adjusted sim < T_face_red with quality pass | score bar + threshold |
| R-ALIAS-01 | diary sim ≥ T_alias_hi AND (name≠ OR dob≠) + supervisor confirm | prior event(s) time/post |
| R-TRAV-01 | impossible travel v > Vmax (120 km/h) | map-list P1→P2, Δt |
| R-PROC-01 | process label ∈ {SCREEN} for physical-doc claim, high margin | macro crop + label |
| R-PROC-02 | photo-zone vs text-zone process MISMATCH, both margins high | both crops + labels |

## 3. AMBER (secondary inspection)
A-PROC-01 PVC process-mismatch uncorroborated · A-FACE-01 sim in [T_red, T_green) · A-LIVE-01 weak passive liveness → trigger active challenge · A-QR-01 unsigned-QR inconsistency · A-FAC-01 facilitator pattern · A-WL-01 watchlist hit (any) · A-WORN-01 worn-doc abstain (macro margins low + doc aged) · A-GREY3 three consecutive GREYs · A-VIZ-01 VIZ↔MRZ drift (non-check-digit fields).

## 4. GREY causes (each with retake instruction key)
G-BLUR · G-GLARE · G-DARK · G-POSE · G-OCCLUDE · G-OCRLOW · G-FOCUS(macro) · G-NOCLIP(required track+light). Retake instructions have videos/diagrams in-app (P0: 4 most common).

## 5. Threshold registry (defaults = TBD by tuning; registry file is truth)
| Name | Unit | Set on | Floor/ceiling policy |
|---|---|---|---|
| T_face_red / T_face_green | cosine | D-FACE report split | FAR≤0.1% operating; publish FRR |
| T_alias_hi / T_wl | cosine | D-FACE + D-SCEN | alias needs corroboration (R-ALIAS-01) |
| δ_margin (runner-up gap) | cosine | D-SCEN | no solo action within δ |
| macro_margin_red / _amber | SVM margin | D-MACRO held-out | below floor → UNKNOWN→AMBER/WORN |
| q_blur/q_glare/q_bright/q_pose | native | D-USAB | fail-closed; tuned for <8% GREY field-like |
| Vmax | km/h | fixed 120 | config, logged |
| recheck_p / reverify_days | — | ops default 5% / 30 d | supervisor-adjustable, logged |

⚠️ **State of this table, measured 2026-09-29.** All 34 thresholds exist in
`core/.../threshold/ThresholdRegistry.kt` with name, unit, tuning-data ref, owner, default,
floor and ceiling, and the floor/ceiling invariant is tested. **None of them is tuned.** Every
default is a value a human typed: no D-FACE, no D-MACRO, no D-USAB, no device. The two
face thresholds are therefore *placeholders with safety envelopes*, not operating points, and
`T_FACE_RED` in particular has never been near a FAR measurement.

**And the file this section calls "truth" does not exist.** `fusion/thresholds.v1.json` is
absent, so the "one ThresholdRegistry" claim in AGENTS.md §2 and `DESIGN.md` §1 is half-true:
versioning exists in code, the file does not. The lint that would enforce "no magic numbers
elsewhere" is also red (171 lines), so nothing prevents a second, unregistered threshold
appearing. `docs/STATUS.md` R-O and §4.2.

## 6. Tuning procedure (mandatory, EVAL-gated)
1. Tune ONLY on tune split; 2. select operating point by policy above; 3. verify on report split (numbers go to deck); 4. freeze (version bump `thresholds.vN.json` + run-id); 5. post-freeze change = incident (lead sign + full smoke + note). Per-bucket gaps (EVAL §4) must be READ aloud at review; worst-bucket FRR>2× best → must add AMBER-bias rule or document mitigation (no silent shipping).

## 7. Per-track matrices (which layers are load-bearing ● vs auxiliary ○)
| Layer | passport | aadhaar | voter/DL | paper-id |
|---|---|---|---|---|
| math/MRZ | ● | ○(Verhoeff) | ○ | ○(dates) |
| signed-QR | ○(some visas: treat unsigned) | ● | ○(unsigned) | — |
| chip | ● where present | — | — | — |
| macro | ● | ●(PVC honesty rule) | ●(PVC honesty) | ○+copy-detect |
| UV | ○ | ○ | ○ | — |
| face 1:1 | ● | ● | ● | ● |
| diary | ● | ● | ● | ● |

⚠️ **What "●" currently means.** These cells are *design intent*, and the rules themselves are
implemented and unit-tested (RED 21 tests, AMBER 10, engine 16). Two of the ● layers are not
operational: **face 1:1** has no embedder, so it reports `UNAVAILABLE` and GREEN is blocked
everywhere (`docs/STATUS.md` R-A); **macro** has no D-MACRO data, so only a SYNTHETIC model
exists and its classification is never gate-eligible (R-E). The **coverage rules and this
matrix have never been exercised against a real document**, because the four SPECIMEN tracks do
not exist — `hardware/print_targets.md` is a specification, not artwork (R-L). A ● in this
table is a design commitment, not a working check.

## 8. Trust-lane policy (controls are part of the rule)
Enroll: full stranger check GREEN + live supervisor PIN + subject consent + photo-zone evidence stored. Fast path: face-only (quality-passed) + liveness + diary re-check + random full-recheck (p=5%) + re-verify every 30 d + instant revoke. Enrollment fraud drill is a red-team attack (RT-E1).
