# `:eval` — the evaluation harness

EVAL.md §1: **no metric leaves this harness.** Every number in a slide cites a `run-id`, and
every `run-id` resolves to a directory under `eval/runs/` containing the metrics, the summary
and the failure gallery it came from.

```bash
./gradlew :eval:run --args="smoke"     # ~60 s, no device, no Python, no network
./gradlew :eval:run --args="full"      # corpora, per-bucket, histograms, parity
./gradlew :eval:run --args="parity"    # Android-vs-desktop; SKIPS LOUDLY without a device
```

`--run-id=auto` (the default), `--out=`, `--repo-root=`, `--device=`, `--mac-rows=`,
`--verbose`, and two side doors for the Python cross-check (`--emit-crosscheck`,
`--emit-mrz-corpus`). `--help` prints all of it.

## Exit codes — the part CI reads

| Code | Meaning |
|---|---|
| 0 | every selected suite ran and every gate passed |
| 1 | harness usage or environment error |
| 2 | a metric gate **failed** |
| 3 | the run is **INVALID** — a threshold was selected on the report split |
| 4 | the run is **INCOMPLETE** — a required suite was skipped |
| 70 | internal error |

They are distinct on purpose. `2` and `3` are different problems with different fixes — one
is a regression, the other is a methodological violation that retrying will not cure — and
collapsing them into "1" would hide a split-discipline breach behind what looks like an
ordinary red build.

A **skipped** suite is never a pass. `SKIPPED` is a first-class gate state, it makes the run
`INCOMPLETE` (exit 4), and `summary.md` says so in a banner. The parity suite is the reason
this matters most: a parity check that passes because it found nothing to compare is worse
than no parity check, because the deck would say "Android and desktop agree" and the sentence
would be invented.

## Outputs — `eval/runs/<run-id>/`

| File | What it is |
|---|---|
| `metrics.json` | every number, every gate, the split each was measured on, and the verdict. Two halves: `evalmetrics` (`:core`'s sink, `:core`'s writer) and `governance` (split discipline, calibration, gate states) |
| `summary.md` | the EVAL.md §6 report: date/commit/devices · suite · metrics table · operating points · failures linked · a ≥3-sentence known-limits paragraph · sign-off lines |
| `histograms/` | face genuine/impostor, macro margins, as `.csv` and inline `.svg` |
| `failures/failures.md` | the deck's failure gallery: every miss, with a reproducible case id |
| `crosscheck/kotlin_features.jsonl` | `:core`'s feature vectors for the shared synthetic recipes, so `cross_check.py` can re-verify a committed run later |
| `mrz_corpus.jsonl` | the normative D-MRZ corpus the M-gate measured, for review |

`metrics.json` and `summary.md` are both rendered from one `RunRecord`, so they cannot
disagree — which is the failure mode of a hand-written summary.

## The three mechanisms worth knowing about

**1. Split discipline is a hard gate, not a warning** (EVAL.md §8). Two manifests in
`eval/data/manifests/` are read *before any metric is computed*. `datasets.json` declares each
dataset's split and its `splitUnit`; a `patch`- or `frame`-level split is fatal, because two
crops of one card share every print-process artefact. `threshold_provenance.json` records
which split chose each threshold, and a `report` selection makes the run **INVALID** before a
single number exists — so an invalid run cannot even produce the metrics it would otherwise
produce. A threshold with *no* provenance entry is also blocking: not knowing is not a
licence. A threshold never consulted cannot have contaminated a number, so only the ones this
run actually reads are checked. See `SplitDisciplineTest` for the adversarial cases.

**2. The calibration gate fails closed** (EVAL.md §7). Without a `device_calib.json` under 7
days old, the macro suite does not run — and the refusal message says *why*: without
white-balance and pixels-per-millimetre, a threshold fitted across devices is fitted to the
camera's colour pipeline rather than to the print process. Also refused: another device's
calibration, an unparseable capture time, a future timestamp, `focusOk: false`, a
white-balance vector of the wrong length. There is no `TEST_` escape hatch, on purpose.

**3. The MRZ headline counts only what can be caught.** 10 000 rows, 100% required on
*detectable* mutants (SPEC.md §7). The structurally blind rows — TD1 has no composite check
digit, so a recomputed field check digit is undetectable by any MRZ arithmetic — are counted
and reported **separately, with their reasons**, and never folded into the headline in either
direction. A second gate checks that valid documents are *not* flagged, because the other way
to pass a 100% catch gate is to flag everything.

## Suites

| Suite | In | What it does |
|---|---|---|
| `mrz` | smoke, full | 10 000-row mutate corpus; the headline number |
| `qr` | smoke, full | signed/mutated/rogue-key/unsigned fixtures; `SecureQr` key selection and tamper detection |
| `diary` | smoke, full | 40 D-SCEN scripts through the **real** `dev.kasoti.diary` rules and `FusionEngine`, plus a 1:N rank-1 probe against 10 000 distractors |
| `unit-parity` | smoke, full | `:core` against independently computed references and the published ICAO 9303 worked examples |
| `latency` | smoke, full | per-stage median/p95 on the host, with the device gate explicitly skipped |
| `macro` | full | features (synthetic) + classification (needs a trained model), calibration-gated |
| `face` | full | TAR@FAR, operating point, per-bucket — all skipped, with the reason, until D-FACE exists |
| `parity` | full, `parity` | Android-vs-desktop agreement; skips loudly without a device |

## Relationships to other modules

- **`:core` only.** Never `:platform` — the harness must run on a laptop and in CI with no
  device. Where `:core` leaves a primitive behind as an interface, `:eval` supplies a
  clearly-labelled harness stub (`HarnessStubSignatureVerifier`), never a silent default.
- **`dev.kasoti.evalmetrics`** owns the *numbers*: `MetricSink` holds them and
  `MetricJsonWriter` serialises them, bridged by `EvalMetricsBridge`. This module owns the
  run *governance* — split discipline, calibration, skipped-versus-passed — which the sink
  deliberately does not model, so a gate status cannot end up sitting next to a rate in the
  same map where a consumer might add them. Gates are built from `SpecGates`; catch,
  classification, face and percentile maths all come from `:core`.
- **`eval/tools/`** is the only Python (AGENTS.md §2) and has no runtime role. `smoke` needs no
  Python at all. See `eval/tools/README.md`.

## Known state of the repository

**The Verhoeff defect described here is closed, and `U-GATE-01` is green.** This section used to
say a `smoke` run was `GATE_FAILED` on `dev.kasoti.checks.Verhoeff`, which implemented
`c = D[c][digit]` with no permutation table and disagreed with the reference on `99999999001`
(reference 9, `:core` 4). Measured 2026-09-29 on commit `6db63d9`, run
`eval-20260929-smoke-a1c7`: **`U-GATE-01` passes 19/19**, and the same is true of every run
directory currently under `eval/runs/`.

The fix is in `core/src/commonMain/kotlin/dev/kasoti/checks/Verhoeff.kt`: it **generates** the D,
P and INV tables from the D5 dihedral-group definition rather than transcribing them, re-derives
the invariants a corrupted table would break in an `init` block so a bad generator fails at
class-load, and pins the generated tables against the published ones in `ChecksTest` along with
the published `236 → 3` worked example. `FormatValidators.validateAadhaar` is downstream of it
and is fixed too.

⚠️ **One piece of that old text survives in code.** `UnitParitySuite.kt:233-244` still contains
the "OUTSTANDING :core DEFECT" note verbatim, including the stale "reference 9, :core 4" numbers.
It sits inside `if (mismatches > 0)`, so it cannot render while the gate passes — it is dead
text, not a live claim. It should be deleted; `eval/src/**` was outside the write scope of the
docs audit that found it, and it is tracked in `docs/STATUS.md` §4.6.

`smoke` is **INCOMPLETE (exit 4)**, not `GATE_FAILED`: 11 gates pass, 1 is skipped, 0 fail. The
skip is `L-GATE-02`, the device gate, and a skipped suite is never a pass.

`full` still cannot be signed off, and says so: no calibration, no D-FACE, no D-MACRO media, and
no embedder. `MACRO-CAL`, `MACRO-CLS`, `F-GATE-01`, `F-GATE-03` and `P-GATE-01` are `SKIPPED`
with reasons, the run is `INCOMPLETE`, and the exit code says so. The committed
`svm_print_v1_synthetic.json` resolves as `SYNTHETIC FALLBACK` and its numbers are a property of
`eval/tools/synth_macros.py`, not of print processes.
