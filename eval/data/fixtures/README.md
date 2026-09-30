# `eval/data/fixtures/` — generated fixtures

The one place under `eval/data/` where image-shaped files *are* admitted by `.gitignore`
(`!eval/data/fixtures/**`), and the reason is not an oversight: the fixtures here are
**generated vectors and recipes, not photographs**. No face, no card, no document, no
biometric anything.

The `!eval/data/fixtures/**` re-admission is a real risk of its own — it is the one rule in
`.gitignore` that re-admits image extensions. So the rule for this directory is: a file here
may be a *recipe* that deterministically produces pixels, a *vector dump*, or a corpus of
generated MRZ lines. A file here may never be a capture. If you find yourself wanting to put
a `.png` here, what you actually want is a recipe, and the recipes are in
`eval/tools/patches.py` and `dev.kasoti.eval.suites.SpectrumPatch`. The one exception is
`macro_synth/specimens.jsonl`, which is a **vector dump** — the second of the three admitted kinds
— because its pixels are the output of a 300-line generator and a committed image would break the
provenance chain. `macro_synth/README.md` documents the chain and the regeneration command.

| Directory | Contents | Produced by |
|---|---|---|
| `mrz/` | dumps of the normative D-MRZ corpus, for review | `eval/tools/gen_mrz_corpus.py` reading `:eval --emit-mrz-corpus` |
| `qr/` | signed D-QR payload vectors | `:eval`'s QR suite (signatures are per-JVM and are not committed) |
| `macro_synth/` | the synthetic recipe manifest — names, seeds and parameters, not pixels | `eval/tools/patches.py` ↔ `SpectrumPatch` |
| `macro_synth/specimens.jsonl` | 11 held-out specimen **feature vectors** — 7 class + 4 ambiguous — with their expected outcome | `eval/tools/synth_macros.py --emit-specimens` |

## Why generated rather than shipped

Shipping a corpus of images would make the cross-language feature check depend on a binary
blob, and a binary blob is exactly the thing that rots silently: someone regenerates it with a
different recipe, the pixels change by a few quantisation levels, and the model is now trained
on features nobody can reproduce. Recipes in code, with a cross-check that proves both
implementations generate the same bytes, fail loudly instead.
