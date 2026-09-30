# KASOTI — Evaluation Protocol (EVAL.md)

## 1. Rule zero
No metric leaves this harness. Slides cite `run-id` (e.g., `eval-20260929-smoke-a1c7`). Harness output is JSON + human summary; both written under `eval/runs/` for gate runs.

**Rule zero, enforced.** A run-id that does not resolve to a directory under `eval/runs/` is not
evidence. This is not hypothetical: a docs audit found `docs/STATUS.md` citing
`eval-20260929-smoke-c2f4`, a run-id with no directory anywhere, attached to a table of gate
results. It was removed rather than re-created. If you cannot `ls eval/runs/<id>/`, the number
does not go in the deck.

## 2. Datasets (built in DATA.md; registered here)
| ID | Content | Size target | Split | Used for |
|---|---|---|---|---|
| D-MACRO | 256px gray patches, 7 process labels, clip/no-clip × 3 lights | ≥600 (v1) → ≥1500 (M3) | 70/15/15 by SOURCE-doc (no same-doc leakage) | FFT/SVM train+tune+report |
| D-PASTE | photo-swap/tilt clips + macro of boundaries | ≥60 docs | held-out | F3/F4 validation |
| D-MRZ | generated valid + single/double mutants | 10k | all-test | M-gate 100% |
| D-QR | signed-valid + mutated + wrong-key | ≥300 | all-test | Q-gate 100% |
| D-FACE | consented pairs (genuine + impostor) + LFW-subset sanity | ≥400 pairs ours | tune/report split by IDENTITY | threshold + FAR/FRR |
| D-SPOOF | print-attack + screen-attack face clips | ≥120 | held-out | liveness report |
| D-SCEN | scripted diary scenarios (alias/travel/facilitator/watchlist) | 40 scripts | all-test | diary rules 100% on script |
| D-USAB | 5+ novice timed runs + GREY drill | sessions | — | usability issues log |

Leakage rules: splits by source-doc/identity, never by patch/frame. Fixture seeds fixed. Test keys NEVER prod keys (file names say so).

⚠️ **The table above is a target, not an inventory.** `eval/data/` exists and its *manifests*
are committed and authoritative — `manifests/datasets.json` declares each dataset's splits and
`splitUnit`, and `threshold_provenance.json` records which split chose each threshold. **Every
`manifest.csv` has a header and zero rows. No dataset in this table has a single media file.**
So: no macro accuracy, no face TAR@FAR, no liveness APCER/BPCER, no usability session, no GREY
rate. What the harness *can* measure today is D-MRZ, D-QR and D-SCEN, all of which are
generated, plus the D-MACRO *feature extractor* on synthetic patches. `docs/STATUS.md` R-E.

## 3. Harness CLI
```
:eval run --suite=smoke|full|parity --run-id=auto --out=eval/runs/
suites: smoke (D-MRZ sample, D-QR, D-SCEN, unit-parity, latency microbench) ~60 s
        full  (everything + macro + face + per-bucket + histograms + parity)
        parity (Android-vs-desktop; skips loudly without a device)
```
Also accepted: `--repo-root=`, `--device=`, `--mac-rows=`, `--verbose`, `--emit-crosscheck=`,
`--emit-mrz-corpus=`. `--help` prints all of it.
Outputs: `metrics.json` (all numbers, both `evalmetrics` and `governance` halves) · `summary.md`
(paste-ready) · `histograms/` · `failures/` · `crosscheck/kotlin_features.jsonl` ·
`mrz_corpus.jsonl`. Both `metrics.json` and `summary.md` render from one `RunRecord`, so they
cannot disagree.

**Exit codes are distinct on purpose** (see `eval/README.md`): `0` pass · `1` usage/environment
· `2` a gate failed · `3` **INVALID** (a threshold was selected on the report split) · `4`
**INCOMPLETE** (a required suite was skipped) · `70` internal.

⚠️ **Consequence for CI: every run available today exits 4, not 0.** A skipped suite is a
first-class state and makes the run INCOMPLETE — and the device gates (`L-GATE-02`,
`MACRO-CAL`, `MACRO-CLS`, `F-GATE-01`, `F-GATE-03`, `P-GATE-01`) are *always* skipped without a
device and a calibration file. `.github/workflows/ci.yml:96` runs `:eval:run --args="smoke"`
without `continue-on-error`, so the `verify` job **fails at that step by design**. Measured
2026-09-29: `smoke` = 11 pass / 1 skip / 0 fail, exit 4; `full` = 13 pass / 6 skip / 0 fail,
exit 4. Do not "fix" this by making a skipped gate pass (`docs/STATUS.md` R-J).

## 4. Metric definitions (exact)
- Macro: per-class precision/recall + macro-F1 + confusion matrix; RED-eligible only if per-class recall≥floor (FUSION). ⚠️ **Not measurable** — no D-MACRO. The only model is SYNTHETIC (`svm_print_v1_synthetic.json`, macro-F1 0.8469 on its own synthetic report split, training run `train-20260929T200155Z-SYNTHETIC-s20260932`) and the harness labels it `SYNTHETIC FALLBACK` and never gate-eligible, because a property of `eval/tools/synth_macros.py` is not a property of print processes. Do not quote it as a macro result.
- Face: TAR@FAR sweep (FAR ∈ {1%, 0.1%}); operating point = max TAR with FAR≤0.1% on report split; report FRR there. Per-bucket (gender×age-band×lighting) TAR/FRR; worst-bucket gap reported (no hiding). ⚠️ **Not measurable** — no D-FACE *and* no embedder. The measurement code (ROC, sweep, per-bucket, worst-bucket gap) is implemented, unit-tested and gated; `F-GATE-01` and `F-GATE-03` are SKIPPED with reasons. `Detection.perBucket` has never been run on real data.
- Liveness: APCER/BPCER on D-SPOOF (attack-present / bona-fide error rates). ⚠️ Not measurable — no D-SPOOF, and no embedder for an attack face to be scored against.
- Diary rules: pass/fail per D-SCEN script + 1:N rank-1 retrieval on synthetic gallery (10k distractors). ✅ **Measurable and green** — `D-GATE-01/02/03`, run `eval-20260929-smoke-a1c7`. ⚠️ Synthetic: it exercises the real rules through `FusionEngine`, which is a real result about the rules and not about real travellers.
- Latency: per-stage median/p95 on NAMED devices (build fingerprint recorded). ⚠️ **No named device has run anything.** Host-JVM numbers exist and are labelled HOST; `L-GATE-02` is SKIPPED, never passed.
- GREY rate: % attempts GREY on D-USAB + scenario captures; ≥3-consecutive-GREY→AMBER logic tested. ⚠️ The streak logic is tested; the rate is unmeasurable (no D-USAB sessions).
- Parity: Android vs desktop same-input verdict agreement 100% on D-SCEN + sampled D-FACE (tolerance: sim ±1e-3). ⚠️ **No device has ever run**; `P-GATE-01` SKIPPED. A normative desktop reference does exist (`eval/fixtures/face/detector_reference.json`) so the first device run compares numbers rather than investigating.

## 5. Model-decision spike scorecard (M0.4)
Candidates scored 0–2 on: license-ok · TFLite-both · LFW-sub sanity · on-device p50 · size · alignment simplicity. Highest total wins; ties → smaller. Decision + hashes recorded in DESIGN §6 + run-id cited.

✅ **CLOSED 2026-09-30.** The scorecard and the decision are in `docs/spikes/01-face-model.md`
§5.3 and §7. Result: **detector adopted** (`blazeface_short.tflite`, 11/12; Apache-2.0 verified,
hash-pinned, running on desktop). **Embedding slot left empty** — all seven candidates scored
0 on `license-ok`, and a `license-ok` of 0 is disqualifying regardless of total. So no embedding
model is recorded in `DESIGN.md` §6, and the face-1:1 layer is fail-closed.

## 6. Reporting template (gates)
`eval/runs/<run-id>/summary.md` must contain: date/commit/devices · suite · metrics table · operating points chosen · failures linked · known-limits paragraph (3+ sentences) · sign-off. Gate runs are tagged in git.
⚠️ **Two corrections to the sign-off line.** It used to require "vision owner + lead" — there is
one maintainer (`docs/HANDOFF.md` §2), so self-review is the only available sign-off and should be
labelled as such rather than presented as a second signature. And `eval/runs/` is **git-ignored**:
"committed in git" is not currently true of any run record, so the runs exist on this
filesystem only and a deck that cites a run-id is citing a directory that a fresh clone will not
contain. Fixing that is a one-line `.gitignore` change and a deliberate decision about whether
2.4 MB corpora belong in history.

## 7. Calibration (per-device, before any macro/face numbers count)
Run DATA.md §5 card routine; store `device_calib.json` (white-balance gains, px-per-mm, focus distance). Eval refuses macro suites without calibration ≤7 days old (error message says why).

✅ **The refusal works, and it has fired on every run.** Measured 2026-09-29: both `smoke` and
`full` report `calib REFUSED — No device_calib.json for 'TEST-HARNESS-JVM'`, and `MACRO-CAL` is
SKIPPED rather than passed. **No calibration routine has been run on any device**, and the
`device_calib.json` slot under `eval/data/manifests/calib/` is empty. This is the correct
behaviour and it is also the reason there is no macro or face number in this project.

## 8. Anti-gaming rules
- Tuning on report split = invalid run (harness enforces split discipline by manifest).
- Threshold changes require full `smoke` + note in run log; post-freeze changes need lead sign + `full`.
- No cherry-picked devices: gates run on the NAMED low-end device (SPEC NFR), plus one mid.
  ⚠️ **Zero named devices exist.** This rule is currently vacuous, which is worse than violating
  it: with no device there is nothing to cherry-pick *from*, and also nothing measured.

✅ The split-discipline mechanism is real and is the strongest thing in this harness: two
manifests are read **before any metric is computed**, a `patch`/`frame` split unit is fatal, and
a threshold recorded as chosen on the `report` split makes the run INVALID (exit 3) before a
number exists. A threshold with *no* provenance entry is also blocking. The last smoke run
reported `split VALID — 7 threshold(s) checked, 6 still at the untuned default` — the honesty of
that line is the point: the harness says out loud that most thresholds have never been tuned.
