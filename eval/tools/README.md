# `eval/tools/` — offline Python

Python lives here and nowhere else (AGENTS.md §2). Every script in this directory is an
**offline producer of artefacts that Kotlin then reads**. None of it has a runtime role: no
shipped APK may contain a Python interpreter or import anything from here, and
`./gradlew :eval:run` must work on a machine with no Python installed at all. The first half
of that is CI's image scan; the second is why `eval smoke` is pure JVM.

```bash
python3 -m pip install -r eval/tools/requirements.txt   # pinned exactly; see that file for why
```

---

## `cross_check.py` — proves the NumPy extractor matches `:core`

**The one that matters most.** `extract_features.py` reimplements
`dev.kasoti.factory.ProcessClassifier.extract` in NumPy so the training pipeline can compute
features without a JVM. Duplicated maths is only tolerable if something checks it, and this
is that something.

```bash
./gradlew :eval:run --args="--emit-crosscheck=/tmp/kotlin_features.jsonl"
python3 eval/tools/cross_check.py --kotlin /tmp/kotlin_features.jsonl
```

Any smoke run already carries the vectors, so a committed run can be re-verified later
against the `:core` build that produced it:

```bash
python3 eval/tools/cross_check.py \
    --kotlin eval/runs/eval-<date>-smoke-<hash>/crosscheck/kotlin_features.jsonl
```

Exit 0 = agree, 1 = a gated divergence, 2 = usage/IO. Tolerances are **measured**, and the
observed deltas print on every run so a tolerance that has drifted loose is visible.

**What it has already caught**, all of it in the NumPy side, none of it in `:core`:
a transposed x/y axis convention (`GrayImage.get(x, y)` reads `pixels[y * width + x]`, so
array indices are `[y, x]` — invisible on symmetric test patterns, a 5% error in
`edge_density` on dither); a signed-versus-unsigned 32-bit shift in the shared integer hash; a
`np.where` that silently promoted a float32 pipeline to float64; and a divide that had to be
reproduced in float32 or the dither recipe produced a visibly different image.

**What it reports and does not gate:** `spectral_slope` differs by up to ~0.8 absolute
between the float32 FFT in `:core` and a double-precision reference, because the log-log
least-squares denominator cancels several significant digits. That is a real
precision-sensitivity finding for the core owner, printed on every run. It is deliberately
not gated — a limit loose enough to accommodate 0.8 would not be a check.

---

## `extract_features.py` — feature vectors in NumPy

Implements the same definitions `:core` does: a Hann-windowed separable 2-D FFT, a radial
power average, a uniform-LBP-59 histogram and a Sobel gradient count.

```bash
python3 eval/tools/extract_features.py --out features.jsonl      # all seven recipes
python3 eval/tools/extract_features.py --recipes OFFSET,LASER --out -
```

Output: one JSON object per line — `{id, label, recipe, seed, size, featureNames, features}`
— a 66-float vector in `MacroFeatures.toVector()` order. `train_svm.py` consumes exactly
this shape, and `:eval`'s `--emit-crosscheck` emits the Kotlin equivalent under the same keys,
which is what makes the comparison a comparison.

**Do not use this to compute features for anything the gate measures.** Use `:core`.

---

## `synth_macros.py` — the SYNTHETIC print-process corpus

**There is no print-process dataset in this repository.** `eval/data/macro/` is a manifest with a
header and no rows, because real patches need physical documents, the macro clip, and consented
capture (DATA.md §3). `synth_macros.py` builds the next best thing and labels it: 256×256 textures
for all seven classes, each rendered from the *mechanism* that physically produces that class
rather than from noise wearing a label.

| Class | Mechanism | Why |
|---|---|---|
| `OFFSET` | dot screen on a rotated square lattice, 175–250 lpi | the halftone plate fixes the dot positions, so the lattice is the most periodic thing in the set |
| `INKJET` | stochastic threshold dither against a locally averaged tone, then capillary bleed | droplets are placed without a fixed screen, so there is no dominant lattice |
| `LASER` | 2–4 level quantised coverage, toner speckle, low-frequency fuser mottle | fused toner has almost no mid-tone and no lattice |
| `DYESUB` | fine regular dither, 240–330 dpi ribbon, low dot contrast, shadow banding | a transfer ribbon carries a finer pitch than a lithographic plate, and the transfer is continuous |
| `SCREEN` | RGB subpixel comb × row grid × content, plus an in-band moiré beat, glass gradient and mura | a photographed display's *observable* in-band signature is the moiré; the comb itself is usually below the optical resolution limit |
| `PHOTOCOPY` | a real source process re-imaged: optical blur, faint tone curve, ghost double, grain, near-miss moiré | a photocopy is a copy of *something* plus the copier's signature; rendering it as an independent texture would teach the model something false |
| `UNKNOWN` | six ways a patch can contain no print process at all: blank paper, sensor noise, motion blur, extreme defocus, dust, specular | DATA.md §3's reject pile. None is made by blurring a periodic field, because "blurry" is a failure mode and not a class |

Screen pitches are stated in lpi/dpi and converted, because a period of *p* pixels has its spectral
fundamental at radius 256/*p* and getting that backwards is invisible until the model learns the
generator's line spacing instead of the print screen. The document layout is scaled physically
too — on a 2 mm / 256 px grid a real text line is 45–70 px, which puts the *content* period below
the analysis band [12, 90] and leaves the band for the screen.

```bash
# the corpus, with pixels (gitignored)
python3 eval/tools/synth_macros.py --out eval/data/synthetic_macro \
    --docs-per-class 48 --patches-per-doc 6

# feature vectors for the trainer, no pixels on disk
python3 eval/tools/synth_macros.py --no-images --out /tmp/synth \
    --features /tmp/synth/features.jsonl

# the specimen set `ShippedMacroModelTest` asserts on
python3 eval/tools/synth_macros.py \
    --emit-specimens eval/data/fixtures/macro_synth/specimens.jsonl
```

**Split discipline.** A *source document* is one rendered page plus every crop taken from it, and
all crops of a page share the same ink, the same screen and the same paper — so splitting by patch
would leak the report set into training. `train_svm.py` groups by `source_doc` and refuses a run
where any class has zero report support.

**Determinism.** Every random draw comes from a counter-based integer hash or from a xorshift32
stream seeded by `(base seed, class index, document index)`. Nothing calls `numpy.random`, whose
stream is explicitly not guaranteed stable across releases, and no seed is unrecoverable. A model
retrained from this file on a different NumPy must come out identical.

**What it does not model.** Listed in the model card's known limits, and the three that matter
most are: the copier's tone quantisation (absent on purpose — it exists at the *copier's* ~200 dpi
resolution, which the optical blur already represents, and rendering it at the patch's own pixel
scale invents a fine dither that is an inkjet's signature); the microlens/pixel-aperture grid on a
display (implemented, measured, removed — see the model card); and anything at all about a real
ink, a real paper, or a real camera.

---

## `train_svm.py` — the print-process model

```bash
# the real thing, once D-MACRO exists
python3 eval/tools/extract_features.py --out /tmp/features.jsonl
python3 eval/tools/train_svm.py --features /tmp/features.jsonl \
    --manifest eval/data/macro/manifest.csv --out eval/models/svm_print_v1.json

# the shipped synthetic model (48 docs/class x 6 crops, 2016 patches)
python3 eval/tools/synth_macros.py --no-images --out /tmp/synth \
    --features /tmp/synth/features.jsonl
python3 eval/tools/train_svm.py --synthetic-corpus /tmp/synth/features.jsonl \
    --allow-synthetic --out eval/models/svm_print_v1_synthetic.json

# a pipeline check over the seven closed-form recipes
python3 eval/tools/train_svm.py --synthetic --allow-synthetic \
    --out eval/models/svm_print_v1_synthetic.json
```

**Both routes are wired and neither is a fallback.** The real route reads D-MACRO features with a
`manifest.csv` for the `source_doc` column; the synthetic route reads a `synth_macros.py` corpus
whose every record is stamped `"synthetic": true`, and `train_svm.py` **refuses** a
`--synthetic-corpus` whose records are not — because a corpus tool that forgets its own flag is
exactly how a synthetic model ends up presented as a trained one. `:eval`'s `SvmModelLoader.resolve`
and the console both prefer `eval/models/svm_print_v1.json` and fall back to
`svm_print_v1_synthetic.json` with a loud label, so a run cannot keep reporting the synthetic
number after real data lands.

Writes `eval/models/svm_print_v1.json` in the shape `dev.kasoti.factory.SvmModel` expects
(`version`, `labels`, `weights`, `bias`, `featureNames`, `trainingRunId`, plus `synthetic`
and `splitPolicy`) and a model card beside it with per-class precision/recall, the confusion
matrix, macro-F1, and a known-limits list.

**Split discipline (EVAL.md §2).** Rows are grouped by **source document** and cut 70/15/15
into train/tune/report, stratified by class. Never by patch, never by frame: two crops of one
card share every print-process artefact, so a random per-row split measures memorisation.
`report` is measured; `tune` is where `C` is chosen; the two are never mixed.

**The refusal.** It exits non-zero rather than emitting a model if any class has zero support
in the report split, and prints how many documents landed in each split so the cause is
diagnosable. A class the report split cannot see is a class nobody can claim anything about,
and a model card that quietly omits one is worse than no model card.

**Both synthetic routes need `--allow-synthetic`.** A model trained on generated geometry has
never seen a print process, so SPEC.md §7's offset-vs-inkjet bar is meaningless against it. The
extra flag makes that a deliberate, visible choice rather than something implied by a filename —
and the word `SYNTHETIC` appears in the run id, in the model file's `synthetic` field, in every
corpus record, in `eval/models/manifest.json`, and as the first line of the model card.

**What the card reports beyond accuracy.** Per-class precision/recall on the report split, the
**margin distribution** per class (p05/p25/median/p75/p95 and the share below
`MACRO_MARGIN_AMBER`), the ambiguous specimens with their margins, and a *faded-plate sweep* — an
offset screen with its dot darkness swept and the margin printed at each step. The sweep is there
because a single specimen asserting "the margin is low" is indistinguishable from a value that was
chosen until the assertion passed; a curve with a visible crossing is auditable.

**`eval/models/manifest.json`** indexes every model in that directory with its sha256, its
training run id, its provenance and the exact command that produced it. A weights file with no
recorded hash cannot be told apart from the one that was actually deployed.

---

## `gen_mrz_corpus.py` — reviewable D-MRZ fixtures

```bash
./gradlew :eval:run --args="--emit-mrz-corpus=/tmp/d_mrz.jsonl --mac-rows=10000"
python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --format csv --out eval/data/fixtures/mrz/
python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --blind-only --format md
```

**`dev.kasoti.mrz.MrzCorpus` is normative and this file contains none of it.** An earlier
draft *was* a reimplementation, including a transcription of `kotlin.random.Random`'s XorWow;
it was deleted because `Random` is a JDK implementation detail rather than a published
algorithm, and a transcription that fails silently produces a fixture file that differs from
`:core` in ways nobody notices. This one reads the corpus `:eval` emits and formats it, so a
row here is the same row the gate measured, by construction rather than by agreement.

`--blind-only` is worth knowing about: it selects the mutated rows with no recorded
blind-spot reason — the ones no MRZ arithmetic can catch. Those are the substrate the
signed-QR, chip, macro and face layers exist for, and reading them is the fastest way to
understand what the 100% gate does *not* cover.

---

## `patches.py` — the shared synthetic recipes

Not a script; a module the other three import. Closed-form geometry, no RNG beyond a
specified 32-bit integer hash, identical recipes on the Kotlin and NumPy sides. These are
**not** a corpus and support no claim about print processes — they exist so the two feature
implementations can be handed byte-identical input without shipping an image (DATA.md §1).
