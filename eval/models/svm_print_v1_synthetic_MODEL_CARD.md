# Model card — `svm_print_v1_synthetic.json`

**SYNTHETIC MODEL. Do not quote its accuracy as a macro result.** Read
"Known limits" first; the short version is below.

| Field | Value |
|---|---|
| training run id | `train-20260929T200155Z-SYNTHETIC-s20260932` |
| trained on | SYNTHETIC: eval/tools/synth_macros.py@2026101 (48 docs/class, 6 crops/doc) — NOT D-MACRO |
| rows | {'train': 1428, 'tune': 294, 'report': 294} |
| source documents | {'train': 238, 'tune': 49, 'report': 49} |
| split policy | 70/15/15 by **source document** (EVAL.md §2) |
| split seed | 20260932 |
| features | 66 (`MacroFeatures.TOTAL`): 7 spectral/gross + 59 LBP bins |
| classes | 7 |
| solver | one-vs-rest linear SVM, 400 sub-gradient epochs, C=1.0 |
| synthetic | **True** |
| sha256 | `b72e4bf05971082bb8d0359c4e2c4efcab3bb25e3edde4da0c8ae56431bef2f1` |

## Split discipline

Rows are grouped by source document **before** splitting, so no two crops of one document can
land in different splits. The report split is measured; the tune split is where `C` is
chosen; the two are never mixed. Every class has non-zero report support — the script exits
non-zero rather than emitting a model otherwise.

| actual \ predicted | OFFSET | INKJET | LASER | DYESUB | SCREEN | PHOTOCOPY | UNKNOWN |
|---|---|---|---|---|---|---|---|
| **OFFSET** | 35 | 0 | 0 | 0 | 0 | 7 | 0 |
| **INKJET** | 0 | 39 | 0 | 0 | 0 | 2 | 1 |
| **LASER** | 2 | 0 | 36 | 1 | 0 | 0 | 3 |
| **DYESUB** | 0 | 0 | 0 | 35 | 0 | 7 | 0 |
| **SCREEN** | 0 | 0 | 0 | 0 | 38 | 4 | 0 |
| **PHOTOCOPY** | 4 | 0 | 0 | 5 | 1 | 32 | 0 |
| **UNKNOWN** | 0 | 0 | 3 | 1 | 5 | 0 | 33 |

## Per-class scores on the **report** split

| Class | Support | Precision | Recall | F1 |
|---|---|---|---|---|
| OFFSET | 42 | 0.8537 | 0.8333 | 0.8434 |
| INKJET | 42 | 1.0000 | 0.9286 | 0.9630 |
| LASER | 42 | 0.9231 | 0.8571 | 0.8889 |
| DYESUB | 42 | 0.8333 | 0.8333 | 0.8333 |
| SCREEN | 42 | 0.8636 | 0.9048 | 0.8837 |
| PHOTOCOPY | 42 | 0.6154 | 0.7619 | 0.6809 |
| UNKNOWN | 42 | 0.8919 | 0.7857 | 0.8354 |

macro-precision 0.8544 · macro-recall 0.8435 ·
**macro-F1 0.8469** · accuracy 0.8435 ·
worst class recall 0.7619 (n=294)

On the **tune** split: macro-F1 0.8209, accuracy
0.8163 (n=294).

## Margin distribution on the **report** split

| Class | n | p05 | p25 | median | p75 | p95 | below floor |
|---|---|---|---|---|---|---|---|
| OFFSET | 42 | 0.248 | 0.478 | 0.738 | 0.847 | 0.951 | 1/42 (2%) |
| INKJET | 42 | 0.353 | 0.603 | 0.845 | 0.920 | 0.989 | 0/42 (0%) |
| LASER | 42 | 0.081 | 0.500 | 0.803 | 0.910 | 0.972 | 3/42 (7%) |
| DYESUB | 42 | 0.303 | 0.518 | 0.614 | 0.804 | 0.939 | 1/42 (2%) |
| SCREEN | 42 | 0.112 | 0.729 | 0.949 | 0.994 | 1.000 | 3/42 (7%) |
| PHOTOCOPY | 42 | 0.093 | 0.437 | 0.635 | 0.713 | 0.871 | 4/42 (10%) |
| UNKNOWN | 42 | 0.219 | 0.626 | 0.869 | 0.930 | 0.957 | 2/42 (5%) |

Overall: median 0.767, p05 0.160,
minimum 0.007;
14/294 rows
(4.8%) sit below the 0.15 floor.

## Abstention on the ambiguous specimens

Not part of any split and not accuracy — a question about whether the model knows what it does not know. Each row is a real thing a capture pipeline produces.

| Specimen | What it is | Top class | Runner-up | margin | below the floor? |
|---|---|---|---|---|---|
| `ambiguous_paste_seam` | offset and inkjet regions meeting inside one patch (R-PROC-02 collage) | PHOTOCOPY | OFFSET | 0.3516 | **no** — confident anyway |
| `ambiguous_featureless` | defocused, very low contrast, in shade — a shot taken without the clip | DYESUB | LASER | 0.1017 | abstains |
| `ambiguous_faded_offset` | a 210 lpi offset screen whose dot darkness has decayed into the grain | DYESUB | SCREEN | 0.1290 | abstains |
| `ambiguous_screen_through_offset` | a display showing an offset print: two lattices, one patch (AT-04) | OFFSET | SCREEN | 0.0546 | abstains |

Floor used here: 0.15 (`ThresholdRegistry.MACRO_MARGIN_AMBER`).

## Where abstention begins: a decaying plate

A 210 lpi offset screen with every geometric feature left intact and only the dot darkness swept. A single specimen asserting "the margin is low" is indistinguishable from a number that was chosen until the assertion passed, so the whole sweep is printed: the abstention region is where the lattice has decayed into the paper grain, and the crossing is visible.

| dot darkness | top class | runner-up | margin | below the floor? |
|---|---|---|---|---|
| 0.90 | OFFSET | DYESUB | 0.9402 | confident |
| 0.70 | OFFSET | DYESUB | 0.6047 | confident |
| 0.50 | DYESUB | OFFSET | 0.1606 | confident |
| 0.40 | DYESUB | SCREEN | 0.2318 | confident |
| 0.30 | DYESUB | SCREEN | 0.2005 | confident |
| 0.26 | DYESUB | SCREEN | 0.1721 | confident |
| 0.20 | DYESUB | SCREEN | 0.1248 | abstains |
| 0.15 | DYESUB | SCREEN | 0.1176 | abstains |
| 0.10 | DYESUB | SCREEN | 0.1008 | abstains |
| 0.06 | DYESUB | SCREEN | 0.0834 | abstains |

## Known limits

1. **This is a SYNTHETIC model and must never gate anything.** The patches come from `eval/tools/synth_macros.py`, which renders *mechanisms* — a halftone screen, a stochastic droplet field, a fused toner edge — and not a single pixel of ink on paper. Every number in this card is a property of that generator and moves the moment the generator changes. It exists so the demo can classify and the pipeline can be proven end to end. SPEC.md §7 needs D-MACRO: real documents, the macro clip, consented capture, split by source document (DATA.md §3, EVAL.md §2).
2. **The class balance reflects what was generated, not the world.** One figure like 0.83 on n=42 should be read as "not yet measured".
3. **The generator's screen-pitch ranges are the reason offset and dye-sub are separable here, and they are not a guarantee.** Offset is rendered at 175–250 lpi and dye-sub at a 240–330 dpi ribbon, which on the DATA.md §1 2 mm / 256 px grid are r 13.8–19.7 and r 18.9–26.0. On a real document these two are separated mainly by *dot shape* and *dot gain*, and **neither is in the 66-feature vector** — `MacroFeatures` has no dot-shape or dot-area term at all. If the confusion table above shows an offset/dye-sub off-diagonal, that is the honest reading, not a tuning failure.
4. **`spectral_slope` is the least reproducible feature.** `eval/tools/cross_check.py` measures a
   divergence of up to ~0.8 absolute between the float32 FFT in `:core` and a double-precision
   reference, because the log-log fit's denominator cancels several significant digits. This
   model is trained on whatever `:core` produced, so it is self-consistent — but a re-training
   on a different-precision pipeline would move that column, and it should not be compared across
   pipelines.
5. **The margin is the number fusion acts on, not the accuracy.** A model with 95% accuracy and
   0.02 margins on hard patches is worse for this system than one with 88% and margins that let
   `A-WORN-01` abstain, because fusion gates RED use on per-class recall (FUSION.md §5) and
   abstains on a low margin. The table above is the corpus-level view; the abstention behaviour
   on held-out ambiguous patches is asserted by `:eval`'s `ShippedMacroModelTest`.
6. **`SCREEN` is the weak class, and it is weak for a reason worth naming.** Attack AT-04 is
   "a screen showing a document", and the report split puts 4 of 42 `SCREEN` patches into
   `DYESUB`/`PHOTOCOPY`. The cause is the generator: the display's emission comb only exists where
   the document has ink, so a screen showing a **text zone** — where the content is near-binary —
   reduces to a near-binary texture and competes with `LASER`. Measured on the held-out page 900,
   4 of 6 crops read `SCREEN`; the two that did not, plus two that did but with margins of
   0.07–0.35 against a RED floor of 0.35. The repair is the microlens/pixel-aperture grid, which
   is real and does not vanish at white; it was implemented, measured, and **removed** because it
   moved macro-F1 by -0.002, pushed more rows the wrong way, and destroyed the abstention
   behaviour on every ambiguous specimen. With no real captures to arbitrate, an unvalidated
   refinement that makes the numbers worse is not a fix. Collect it as a D-MACRO capture
   condition instead.
7. **No adversarial evaluation.** Nothing here says anything about a deliberately constructed
   attack. `SCREEN`'s separation here comes from the generator's comb and moiré, which are the two
   things a real macro photograph of a display does have — but that is a statement about the
   mechanism, not a red-team result.
8. **`UNKNOWN` is a real class here and a first-class outcome in the product.** It is the reject
   pile (DATA.md §3), and its precision is as much a statement about the *other* six classes as
   about itself.
