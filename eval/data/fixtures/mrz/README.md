# `fixtures/mrz/` — D-MRZ corpus dumps

Formatted views of `dev.kasoti.mrz.MrzCorpus`, the **normative** generator (EVAL.md §2,
DATA.md §7). Nothing here is an input to a gate; the gate generates the corpus in-process.

```bash
./gradlew :eval:run --args="--emit-mrz-corpus=/tmp/d_mrz.jsonl --mac-rows=10000"
python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --format csv --out eval/data/fixtures/mrz/
python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --blind-only --format md
```

Any smoke run already carries the corpus at `mrz_corpus.jsonl` in its run directory.

**Commit the blind-spot dump.** `--blind-only` selects the mutated rows with no recorded
blind-spot reason — currently the TD1 recomputed-field rows, where no MRZ arithmetic can help
because TD1 has no composite check digit. Those rows are the concrete form of the argument for
the signed-QR, chip, macro and face layers existing at all, and they are much easier to
defend in a review than a sentence saying "some things are undetectable".
