# `eval/data/manifests/` — split declarations, the provenance ledger, and calibration

Three things live here, and all three are read by `:eval` **before any metric is computed**.

## `datasets.json` — which split a row belongs to

The authority for splits (EVAL.md §2). The harness reads this file and *not* the directory
layout, because a directory depth is exactly the thing a motivated person would quietly
change while a manifest change is a reviewed diff.

The `splitUnit` field is enforced, not documented: `source-doc` and `identity` are accepted,
and `patch` / `frame` / `image` / `file` / `row` are **fatal**. Two crops of one document
share every pixel-level artefact, so a model fitted across a patch-level split has already
seen its test data, and the resulting accuracy measures memorisation. A manifest that declares
a leaky unit invalidates the run even when every number in it happens to be fine — otherwise
"the numbers were good" launders a broken experimental design.

## `threshold_provenance.json` — which split chose each threshold

The anti-gaming ledger (EVAL.md §8). One question per threshold: *which split produced this
number?*

- `tune` — the normal, correct answer.
- `report` — **INVALIDATES THE RUN.** A threshold picked by looking at the report split has
  been fitted to the numbers it is then used to report, and nothing in the resulting artefact
  can tell a reader that.
- `policy` / `ops-default` — fixed by policy or agreed by the leads; not a tuning claim, and
  not a loophole, because a policy value was never fitted to a split.
- `untuned` — still at the registry default. This is the current, honest state: FUSION.md §5
  records the shipped values as "TBD by tuning". A metric resting on an untuned threshold is a
  pipeline reading, and the harness marks it provisional in `summary.md`.
- **no entry at all** — blocking. "We do not know which split chose it" is not a licence to
  publish it.

## `calib/<device-id>/device_calib.json` — per-device calibration

Written by the in-app guided routine (DATA.md §5). EVAL.md §7: macro and face numbers do not
count without one no older than **7 days**, and the harness's refusal message explains why
rather than just reporting the absence — without white-balance and pixels-per-millimetre, a
threshold fitted across devices is fitted to the camera's colour pipeline rather than to the
print process.

Also refused, all with the reasoning in the message: another device's calibration, an
unparseable `capturedAtUtc`, a timestamp in the future (that is a clock, not a fresh capture),
`focusOk: false`, and a white-balance vector that is not three channels long.

**Do not hand-write one of these to make a suite run.** There is no `TEST_` escape hatch here
by design: a fabricated calibration would produce a number shaped like a measurement, and the
shape is the only thing a reader in six weeks will check.
