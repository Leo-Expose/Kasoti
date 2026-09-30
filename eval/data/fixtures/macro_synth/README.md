# `fixtures/macro_synth/` — the shared synthetic recipes

**There are no pixels in this directory, and there are not going to be.** What is committed is
the *identity* of the recipes; the pixels are generated identically on both sides, on demand.

| Side | Where the recipes live |
|---|---|
| Kotlin | `dev.kasoti.eval.suites.SpectrumPatch` (the `:eval` module) |
| NumPy | `eval/tools/patches.py` |

Seven recipes, one per `ProcessLabel` declaration order: `OFFSET` (rosette), `INKJET` (dither
from a specified 32-bit hash), `LASER` (square lattice), `DYESUB` (ramp), `SCREEN` (3 px
subpixel lattice), `PHOTOCOPY` (low-contrast blurred copy), `UNKNOWN` (flat).

## Why recipes and not images

Three reasons, in order of how much they would hurt if ignored:

1. **The cross-check needs byte-identical input.** `eval/tools/cross_check.py` proves the
   NumPy and Kotlin feature extractors agree; that is only meaningful if both were handed the
   same image. A committed `.png` would be a binary blob whose provenance nobody can check in
   a diff.
2. **DATA.md §1.** A patch-shaped file in the repository is one somebody will later copy a
   real patch into.
3. **Reproducibility.** A model trained on features from a committed blob is a model whose
   training data no longer exists once the blob is regenerated.

## What these do and do not support

They exercise the FFT, the LBP histogram, the Sobel and the whole export path, and they let
`train_svm.py --synthetic` prove the training pipeline runs end to end. They support **no**
claim whatsoever about print processes, and every number derived from them is labelled
`synthetic` in `metrics.json` and carries that into the gate. SPEC.md §7's offset-vs-inkjet
bar needs D-MACRO, captured per DATA.md §3, in the private repository.


---

## `specimens.jsonl` — the held-out specimen set

Added alongside the seven shared recipes above, and it is a **vector dump** rather than pixels,
which is the second of the three kinds of file this repository's fixture policy admits. The
reason is the same as above and stronger here: the pixels are the output of a 300-line generator,
and committing them instead would mean a reviewer cannot tell from the diff whether they came
from the generator or from somewhere else. The chain that is trusted instead is

```
eval/tools/synth_macros.py   (committed; the renderer)
  -> extract_features.extract (committed; cross-checked against :core by cross_check.py)
    -> specimens.jsonl        (committed; 11 rows x 66 floats)
      -> the shipped weights  (committed; eval/models/svm_print_v1_synthetic.json)
```

Each row is one 256×256 patch from a **held-out** document (index 900, far past the training
range): seven class specimens and four deliberately ambiguous ones. `expectation` is `class` for
the first seven and `abstain` for the rest.

Regenerate with:

```bash
python3 eval/tools/synth_macros.py \
    --emit-specimens eval/data/fixtures/macro_synth/specimens.jsonl
```

`dev.kasoti.eval.suites.SpecimenFixtures.assertFresh` fails the build if the dump was generated
from a different seed, is missing a class, is unmarked `synthetic`, or has the wrong width — a
dump left behind by a renamed fixture is the one way this could silently test the wrong thing.

**The four ambiguous specimens, and what each is:**

| id | what it is | what it is for |
|---|---|---|
| `ambiguous_paste_seam` | offset and inkjet regions meeting inside one patch | the R-PROC-02 collage. Per-patch margins do *not* catch it; the zone *pair* does |
| `ambiguous_featureless` | defocused, very low contrast, in shade | a shot taken without the clip. The right answer here is a low margin, not a confident class |
| `ambiguous_faded_offset` | a 210 lpi offset screen whose dot darkness has decayed into the paper grain | the primary abstention case. Geometry untouched, only amplitude fallen, so the reading is underdetermined rather than hard |
| `ambiguous_screen_through_offset` | a display showing an offset print: two lattices, one patch | AT-04's hard case |
