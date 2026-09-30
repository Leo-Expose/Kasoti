# `eval/data/` — datasets and their manifests

**No biometric media belongs in this directory, and none is committed.** DATA.md §1 is
unambiguous: macro patches are 2 mm texture crops visually inspected to contain no readable
characters or faces, face crops are consented biometric data, and a violation is a delete plus
an incident note. `.gitignore` enforces this mechanically — `eval/data/**/*.png` and
`*.jpg` are ignored, with `eval/data/fixtures/**` re-admitted because the fixtures there are
generated vectors and recipes, not photographs.

What *is* committed is the thing that makes a dataset citable: the **manifests**.

## Layout

| Path | Dataset | Contents |
|---|---|---|
| `manifests/` | — | `datasets.json` (split declarations), `threshold_provenance.json` (the anti-gaming ledger), `calib/<device-id>/device_calib.json` (per-device calibration) |
| `macro/` | D-MACRO | `manifest.csv` + the `manifest.csv` per DATA.md §3 layout; media excluded |
| `paste/` | D-PASTE | photo-swap/tilt clips; media excluded |
| `face/` | D-FACE | consent register; **no** images, ever |
| `spoof/` | D-SPOOF | print/screen attack clips; media excluded |
| `scen/` | D-SCEN | the 40 scripted diary scenarios, as text |
| `usab/` | D-USAB | usability session notes and the GREY-rate log |
| `fixtures/` | — | generated fixtures: MRZ corpus dumps, QR vectors, synthetic macro recipes |

Each level has its own README saying what belongs there, what the split policy is, and what
must never be added.

## The two files that matter most

**`manifests/datasets.json`** is the authority for splits. The harness reads *this file* and
not the directory layout, because a directory depth is exactly the thing a motivated person
would quietly change while a manifest change is a reviewed diff. It also declares the
`splitUnit`, and the harness refuses a manifest that declares a leaky one (`patch`, `frame`):
two crops of one document share every print-process artefact, so a model fitted across such a
split has already seen its test data.

**`manifests/threshold_provenance.json`** records, per threshold, which split chose its
value. This is the anti-gaming ledger (EVAL.md §8). A threshold recorded as selected on the
`report` split makes the run INVALID — a hard gate, not a warning — because a threshold picked
by looking at the report split has been fitted to the numbers it is then used to report, and
nothing in the resulting file can tell a reader that. A threshold with *no* entry is also
blocking: "we do not know which split chose it" is not a licence to publish it.

## Collecting something

1. Follow the relevant DATA.md §3/§4 SOP. Consent first; signed copies offline.
2. Write the row into the dataset's `manifest.csv`. `row_id` and `source_doc` are **required**
   for D-MACRO: `train_svm.py` refuses to train without them, because a row that cannot be
   attributed to a source document cannot be attributed to a split.
3. Run the in-app calibration routine (DATA.md §5) if the capture is macro. `full` refuses
   macro metrics without a `device_calib.json` under 7 days old, and the error says why.
4. `./gradlew :eval:run --args="smoke"` — the split check runs before any number is computed,
   so a manifest that cannot support the claim is caught before there is a claim to catch.
