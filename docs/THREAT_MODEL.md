# KASOTI — Threat Model, Misuse & Privacy (THREAT_MODEL.md)

## 1. Assets
A1 correct verdicts (safety) · A2 diary embeddings (biometric) · A3 sync secret/keys · A4 audit log integrity · A5 volunteer/eval data · A6 subject rights (dignity, non-discrimination, due process).

## 2. Actors
T1 casual forger (home printer, edits) · T2 organized crosser (card printer, aliases, repeats) · T3 corrupt insider (enrollment fraud, data sale) · T4 state-level printer (offset access) · T5 coercive misuser (function creep: use diary for unrelated policing) · T6 us (bugs, bias, overclaim).

## 3. Attack catalog → layer → mitigation → residual (abridged; full drills in REDTEAM.md)
| ID | Attack | Hits | Mitigation | Residual |
|---|---|---|---|---|
| AT-01 | inkjet full reprint | factory | macro process-mismatch (R-PROC) | dye-sub PVC pro (A-PROC-01 + QR/face/diary) |
| AT-02 | recompute check digits | math | QR-sig/chip/macro/face still apply | consistent-fake needs all-layer pass (forger's bill). **Measured, and not small: 746 of 10 000 rows in our own MRZ corpus are *structurally* undetectable — TD1 has no composite check digit — so a recomputed field check digit is invisible to any MRZ arithmetic. Published as gate `M-GATE-03`, run `eval-20260929-smoke-a1c7`, not folded into the 100% headline in either direction** |
| AT-03 | paste photo-swap | factory+face | zoom assist + tilt-exp + 1:1 mismatch | genuine-piece paste: face 1:1 is backstop. ⚠️ **The backstop does not exist: no embedder, so `face = null` → layer UNAVAILABLE.** This residual is currently *wider* than the row claims |
| AT-04 | screen shows doc | factory | R-PROC-01 subpixel gate | none known (trivially caught) — ⚠️ unverified: the macro classifier is SYNTHETIC and not gate-eligible, so "trivially caught" is a design intent |
| AT-05 | lookalike impostor | face | threshold + AMBER band + supervisor | twins: AMBER-by-policy + enroll-note. ⚠️ `T_FACE_RED` is an untuned placeholder and no face pair has ever been scored |
| AT-06 | print/screen face spoof | liveness | passive + AMBER-active challenge | high-end mask: out-of-scope, logged. ⚠️ D-SPOOF is empty and there is no embedder to spoof |
| AT-07 | alias week-later | diary | R-ALIAS-01 + travel + facilitator | first-timers (by definition). ✅ **the strongest-tested row in this table**: 23/23 attack scripts and 17/17 benign scripts, run `eval-20260929-smoke-a1c7` — on scripts, not on real travellers |
| AT-08 | enroll-with-fake | trust | supervisor PIN + full-check + random recheck + revoke | colluding supervisor → audit anomaly review (M5) |
| AT-09 | GREY-gaming (blur to dodge) | quality | ≥3 GREY→AMBER + supervisor | persistent gaming = secondary by policy |
| AT-10 | sync tamper/replay | sync | HMAC + seq + idempotent merge + quarantine | stolen secret → rotate (kid), history read-only. ✅ **genuinely tested**: `SyncTest`, 39 tests — byte-flip rejection, replay, idempotence, 3-shuffle merge determinism, model-mismatch quarantine, skew warnings, 100k-record merge budget |
| AT-11 | device theft | all | keystore/file-perms + wipe PIN + retention caps | seized unlocked device: MDM-level, MHA dep. ⚠️ **`:app-android` compiles as of 2026-10-03 and has never been run**, so the keystore path and the on-device wipe are still **unverified at runtime** — a compile proves the signature, not the behaviour, and there is no device. The desktop `wipe` command is PIN-gated and tested. Also: the dev checkout is on an exFAT volume that ignores `chmod`, so a secret written inside the repo is world-readable (`docs/STATUS.md` R-Q) |
| AT-12 | model swap | inference | hash-pinned loader refuses | supply-chain: manifest + CI pin check. ⚠️ **Weaker than it reads.** The loader pin is real and tested. But there is **no `gradle/verification-metadata.xml`**, so a swapped *transitive* artefact is undetectable (`docs/STATUS.md` R-H); `verify_bundle.sh` is red because `bundle_manifest.sample.txt` still holds `REPLACE_ME` placeholders — which is also why `scripts/airplane_install_test.sh` exits 1 rather than 2, at step B rather than at the artefact step; and **CI has never completed a single step** — both runs died at the first one on `./gradlew: Permission denied` (R-J, R-Q). ⚠️ **New since 2026-10-03:** the APKs are built from a dependency graph that has **never** been verified or scanned on CI, and with ABI splits on there is **no `.aab`** whose contents a bundle-manifest check could have covered. `checkApkSize` measures size, not provenance |
| AT-13 | threshold tampering | fusion | versioned registry + per-decision logging | insider: audit review (M5 anomaly). ⚠️ **more built than this row said, still not safe — corrected 2026-10-03.** It read "the registry is versioned in code … but the file `fusion/thresholds.v1.json` does not exist, all **34** defaults are untuned, and the magic-number check is red at **171** lines". Two of those three are now wrong: the **file exists** (`core/src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json`, 48 entries with name/unit/default/floor/ceiling/tuning-data/owner, `ThresholdName` reduced to names only, `ThresholdSpecFileTest` failing on divergence) and the magic-number check is red at **175** lines. ⚠️ **The residual is real and it is not the file's absence:** the file is **UNTRACKED**, so it is in no commit, no clone and no CI run; **all 48 defaults are untuned**; and the magic-number gate being red means **a second, unregistered threshold is still not prevented**. A registry nobody can diff is not a control (`docs/STATUS.md` R-O) |
| AT-14 | bias harm (FRR skew) | face | per-bucket eval + AMBER-bias rule + human override | measured + published; never zero — say so. ⚠️ **NOT measured.** `Detection.perBucket` is implemented and unit-tested and has never been run on real data: there is no D-FACE and no embedder. `F-GATE-03` is SKIPPED with a reason. The "measured + published" half of this residual is a requirement, not a result, and the worst-bucket rule in FUSION §6 has nothing to fire on yet. The only bias numbers in the project are the *vendor's* (BlazeFace model card: 5.3 pp across skin tone, 7.5 pp across geographic subregion), which EVAL.md §1 correctly forbids us from presenting as ours |

## 4. Misuse & function creep (say on stage)
Diary answers ONLY: alias/travel/facilitator/watchlist for border screening. Prohibited: fishing expeditions, unrelated investigations, sharing outside SSB chain, enrollment coercion. Technical rails: purpose-tagged queries (logged), one-tap wipe, retention auto-purge, no export of raw embeddings except signed sync to paired post devices, case bundles minimized.

## 5. Privacy design (DPDP Act 2023 posture)
Lawful authority + consent (enrollment) · purpose limitation (§4) · data minimization (128-B embeddings, no live-face retention, masked displays) · retention (diary 30 d default, evidence 7 d, volunteer 90 d/on-request) · security (at-rest encryption, HMAC sync) · rights (access/correction/deletion via supervisor workflow — prototype: wipe + purge scripts) · breach note (lost device = rotate + report drill). Deployment needs MHA legal sign-off; our deliverable makes compliance one config + one drill.

⚠️ **What is code today and what is intent.** Real and tested: the diary schema with its
retention fields, `purge_volunteer_data.sh` (scoped, dry-run tested, re-runs smoke),
`:app-desktop wipe` (PIN-gated, tested), case-bundle minimisation (`crops/` is written without
a live-face raw by default), a `LogScrubber` with adversarial tests in `:app-desktop`, and —
**as of 2026-10-03** — a `:core` **PII scrubber** at `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt`
with seven suites and 111 tests, enforced by a **blocking** CI gate
(`scripts/pii_scrubber_test.sh` exits 0; it used to be red and refuse to pass). ⚠️ **Still not
real, and the distinction matters:** the gate proves the **rules** work, not that **every**
log/cache/crash field routes through them — the script's own closing line says so. So "every field
is redacted" is a *tested, enforced scrubber behind an unproven wiring*, not a tested property of
the shared path (`docs/STATUS.md` §4.1). And the drill has never been run, so "breach note" is a
paragraph rather than a rehearsal. Note also that the consent template (`DATA.md` §6) names no
controller, which a real deployment would have to fix first.

## 6. UIDAI QR keys & rotation
Bundle test + current-prod keys with provenance note; rotation channel = MHA/UIDAI feed (dependency, flagged in deck); expired-key behavior: QR layer → AMBER + "keys stale" (fail-open LOUDLY to human, never silent pass, never silent RED).

⚠️ **No keys have been obtained.** The rotation *behaviour* is implemented and tested — one
bundled key sits inside the rotation window and two outside, so a valid signature under an
expired key reports `StaleKeys` as a distinguishable outcome rather than the same code path — but
every signature the harness has verified came from a per-JVM TEST keypair via a stub verifier.
`uidai_qr_keys.json` has no confirmed source, so the signed-QR layer **cannot be claimed as
working against real material** (`THIRD_PARTY.md` §3, `docs/STATUS.md` R-B).

## 7. Audit & anomaly (prototype)
Hash-chained decisions; supervisor overrides with reason codes; shift report includes RED/AMBER/GREY histograms + override list. M5 adds: cross-post anomaly review (odd enrollment bursts, single-supervisor patterns).

⚠️ `AuditChain` is implemented and the `:app-desktop verify` command recomputes the chain
end-to-end, but **the chain properties have no unit test in any module**
(`DESIGN.md` §8 I6) — the verification path is exercised only by the console. "Hash-chained
decisions" is therefore a code claim with one integration exercise behind it, not a tested
invariant.

## 8. Deployment disclaimer (deck + README + app About)
Prototype for evaluation. Operational use requires: MHA authorization, DPDP compliance review, CERT-In audit, PKD/registry feeds, bias/disparity longitudinal study, officer training + grievance redress. We list these BEFORE judges ask.
