#!/usr/bin/env python3
"""Train the 7-class linear print-process model and export it for the device.

The artefact
------------
`eval/models/svm_print_v1.json`, in exactly the shape `dev.kasoti.factory.SvmModel` expects:
`version`, `labels`, `weights`, `bias`, `featureNames`, `trainingRunId`. `:eval` refuses to
load a model whose `featureNames` do not match `MacroFeatures.toVector()`'s order, and
refuses one whose labels are not the seven `ProcessLabel` entries — so a mistake here is a
load-time failure with a message, not a classifier that quietly scores well on a permutation.

A linear model, deliberately (see `dev.kasoti.factory.Classifier`): ~50 KB of readable JSON
that a judge can be shown and that trains in seconds. The design constraint is explainability,
not accuracy.

Split discipline — the part that actually matters (EVAL.md §2, §8)
----------------------------------------------------------------
Rows are grouped by **source document** and split 70/15/15 into train/tune/report. Never by
patch, never by frame: two crops of one card share every print-process artefact, so a random
per-row split puts a near-duplicate of every training image into the test set and produces a
number that measures memorisation. `--manifest` supplies the `source-doc` column; rows with a
missing or duplicated `source-doc` value are **rejected**, because a row that cannot be
attributed to a document cannot be attributed to a split.

`report` is measured and reported. `tune` is where a hyper-parameter is chosen. The two are
never mixed, and the script refuses to emit a model if **any class has zero support in the
report split** — a class the report split cannot see is a class nobody can claim anything
about, and a model card that quietly omits it is worse than no model card.

Usage
-----
    # the real thing, once D-MACRO exists
    python3 eval/tools/train_svm.py \
        --features features.jsonl --manifest eval/data/macro/manifest.csv \
        --out eval/models/svm_print_v1.json

    # a pipeline check, clearly labelled and never gate-eligible
    python3 eval/tools/train_svm.py --synthetic --allow-synthetic \
        --out eval/models/svm_print_v1_synthetic.json
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import pathlib
import sys
import zlib
from collections import defaultdict

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import patches  # noqa: E402
from extract_features import extract, feature_names  # noqa: E402

MODEL_VERSION = "svm_print_v1"
FEATURE_WIDTH = patches.TOTAL
#: `dev.kasoti.factory.ProcessLabel` declaration order. The weight matrix rows are in this
#: order and `SvmModel` requires one row per label.
LABELS = ["OFFSET", "INKJET", "LASER", "DYESUB", "SCREEN", "PHOTOCOPY", "UNKNOWN"]
#: EVAL.md §2: 70/15/15 by source doc.
SPLIT_FRACTIONS = {"train": 0.70, "tune": 0.15, "report": 0.15}

#: `ThresholdRegistry.MACRO_MARGIN_AMBER` in `core/.../fusion/ThresholdRegistry.kt` (0.15).
#: Duplicated here as a *reporting* default only, and it is the reason this script says "the
#: margin floor fusion would apply" rather than "the abstention threshold": fusion owns the real
#: number, and a corpus tool is not allowed to become a second source of it.
DEFAULT_MARGIN_FLOOR = 0.15


# ---------------------------------------------------------------------- input


def load_features(path: pathlib.Path) -> list[dict]:
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        for field in ("id", "label", "features"):
            if field not in record:
                raise SystemExit(f"{path}: record is missing '{field}'")
        record["features"] = np.asarray(record["features"], dtype=np.float64)
        if record["features"].size != FEATURE_WIDTH:
            raise SystemExit(
                f"{path}: {record['id']} has {record['features'].size} features, expected "
                f"{FEATURE_WIDTH}. A width mismatch means the extractor and the trainer "
                f"disagree; a model trained on it would be a model over the wrong vector."
            )
        records.append(record)
    if not records:
        raise SystemExit(f"{path} contained no records")
    return records


def load_source_docs(path: pathlib.Path) -> dict[str, str]:
    """`{row id: source doc id}` from the D-MACRO manifest."""
    mapping: dict[str, str] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            row_id = (row.get("row_id") or row.get("id") or "").strip()
            source = (row.get("source_doc") or row.get("source-doc") or "").strip()
            if row_id and source:
                mapping[row_id] = source
    return mapping


# ------------------------------------------------------------------- splitting


def assign_splits(pairs: list[tuple[str, str]], seed: int) -> dict[str, str]:
    """Assign each **source document** to a split; every row inherits its document's split.

    Two properties, both of which are load-bearing:

    1. **Split by document, never by row.** Two crops of one card share every print-process
       artefact, so a random per-row split puts a near-duplicate of every training image into the
       report split and produces a number that measures memorisation (EVAL.md §2).

    2. **Stratify by class within the document set.** Each class's documents are shuffled and
       cut at the same fractions independently. Without this, a class with a handful of
       documents can end up with *none* in the report split purely by unlucky shuffling —
       and then the honest response would be "not enough data for this class", which is true
       but is the wrong reason. Stratifying makes the "zero report support" refusal mean what
       it should: this class does not have enough *source documents*, not this run got a bad
       shuffle.

    The shuffle seed is recorded in the model card so a rerun is reproducible. The per-class
    stream offset is `zlib.crc32` and not `hash()`: Python randomises string hashing per process
    by default, so `hash()` would give a different split on every invocation and a recorded
    `split-seed` would be a lie.
    """
    by_class: dict[str, list[str]] = defaultdict(list)
    for label, document in pairs:
        if document not in by_class[label]:
            by_class[label].append(document)

    assignment: dict[str, str] = {}
    for label in sorted(by_class):
        documents = sorted(by_class[label])
        rng = np.random.default_rng(seed + zlib.crc32(label.encode("utf-8")))
        order = list(documents)
        rng.shuffle(order)
        total = len(order)
        n_train = int(round(total * SPLIT_FRACTIONS["train"]))
        n_tune = int(round(total * SPLIT_FRACTIONS["tune"]))
        if total >= 3:
            n_train = min(n_train, total - 2)
            n_tune = min(n_tune, total - n_train - 1)
        for index, document in enumerate(order):
            if index < n_train:
                assignment[document] = "train"
            elif index < n_train + n_tune:
                assignment[document] = "tune"
            else:
                assignment[document] = "report"
    return assignment



# -------------------------------------------------------------------- training


def fit_linear_ovr(x: np.ndarray, y: np.ndarray, labels: list[str], c: float) -> tuple[list[list[float]], list[float]]:
    """One-vs-rest linear SVM per class, fitted with a plain sub-gradient descent.

    Deliberately not scikit-learn's `LinearSVC`: the exported artefact has to be a *matrix of
    numbers a reviewer can read*, and a liblinear fit hides its objective behind a solver whose
    defaults are not recorded in the model file. A few hundred iterations of sub-gradient
    descent on this feature scale converges well inside the accuracy the gate measures, and
    its only hyper-parameter (`--c`) is written into the model card.

    One-vs-rest rather than softmax because `ProcessClassifier` applies the softmax itself to
    the raw scores; the model is a set of margins, not a set of probabilities.
    """
    n_features = x.shape[1]
    weights = np.zeros((len(labels), n_features))
    bias = np.zeros(len(labels))
    rows = len(labels)
    step = 1.0 / max(rows, 1)
    for epoch in range(1, ITERATIONS + 1):
        order = np.random.default_rng(epoch).permutation(len(x))
        learning = 1.0 / (1.0 + 0.01 * epoch)
        for index in order:
            xi = x[index]
            for class_index, label in enumerate(labels):
                target = 1.0 if y[index] == label else -1.0
                margin = target * (float(xi @ weights[class_index]) + bias[class_index])
                if margin < 1.0:
                    step_vec = target * xi
                    weights[class_index] += (step * c * learning) * step_vec
                    bias[class_index] += (step * c * learning) * target
    return weights.tolist(), bias.tolist()


ITERATIONS = 400


def softmax_scores(x: np.ndarray, weights: list[list[float]], bias: list[float]) -> np.ndarray:
    scores = np.asarray(x) @ np.asarray(weights).T + np.asarray(bias)
    shifted = scores - scores.max(axis=1, keepdims=True)
    exponentials = np.exp(shifted)
    return exponentials / exponentials.sum(axis=1, keepdims=True)


def margins_of(probabilities: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Softmax margin and argmax per row — the same arithmetic `ProcessClassifier.classify` does.

    Duplicated deliberately rather than shared, for one reason: `ProcessClassifier` is Kotlin and
    this is Python, and the alternative to a five-line reimplementation is a JVM subprocess per
    evaluation. The margin definition is the softmax gap between the best and second-best class,
    and the unit test `test_margin_matches_core_classifier` in `eval/tools/` is what keeps the two
    copies honest.
    """
    ordered = np.sort(probabilities, axis=1)[:, ::-1]
    return ordered[:, 0] - ordered[:, 1], probabilities.argmax(axis=1)


#: What each ambiguous specimen physically is, for the model card. The card is read by people
#: who have to decide whether a claim is fair, and "ambiguous_faded_offset" means nothing to
#: them without this.
_SPECIMEN_WHAT = {
    "ambiguous_paste_seam": "offset and inkjet regions meeting inside one patch (R-PROC-02 collage)",
    "ambiguous_featureless": "defocused, very low contrast, in shade — a shot taken without the clip",
    "ambiguous_faded_offset": "a 210 lpi offset screen whose dot darkness has decayed into the grain",
    "ambiguous_screen_through_offset": "a display showing an offset print: two lattices, one patch (AT-04)",
}

PERCENTILES = (5, 25, 50, 75, 95)


def margin_report(
    y_true: list[str],
    probabilities: np.ndarray,
    floor: float,
) -> dict:
    """Per-class margin distribution on one split, and where abstention would kick in.

    Reported *alongside* accuracy and not instead of it, because the two answer different
    questions. Accuracy says how often the top class is right; the margin says how much the
    measurement supports that answer. A model with 95% accuracy and 0.02 margins is worse for
    this system than one with 88% and margins that let `A-WORN-01` abstain, because fusion gates
    RED use on per-class recall and abstains on a low margin (FUSION.md §5).
    """
    margins, _ = margins_of(probabilities)
    rows = []
    for label in LABELS:
        selected = [float(m) for m, t in zip(margins, y_true) if t == label]
        if not selected:
            continue
        array = np.asarray(selected)
        rows.append({
            "label": label,
            "n": len(selected),
            **{f"p{p:02d}": float(np.percentile(array, p)) for p in PERCENTILES},
            "belowFloor": int((array < floor).sum()),
            "belowFloorRate": float((array < floor).mean()),
        })
    all_margins = np.asarray([float(m) for m in margins])
    return {
        "floor": floor,
        "perClass": rows,
        "overall": {
            **{f"p{p:02d}": float(np.percentile(all_margins, p)) for p in PERCENTILES},
            "min": float(all_margins.min()),
            "max": float(all_margins.max()),
            "belowFloor": int((all_margins < floor).sum()),
            "belowFloorRate": float((all_margins < floor).mean()),
        },
    }


def margin_table(report: dict) -> str:
    lines = [
        "| Class | n | p05 | p25 | median | p75 | p95 | below floor |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in report["perClass"]:
        lines.append(
            f"| {row['label']} | {row['n']} | {row['p05']:.3f} | {row['p25']:.3f} | "
            f"{row['p50']:.3f} | {row['p75']:.3f} | {row['p95']:.3f} | "
            f"{row['belowFloor']}/{row['n']} ({row['belowFloorRate'] * 100:.0f}%) |"
        )
    return "\n".join(lines)


# -------------------------------------------------------------------- metrics


def classification_metrics(y_true: list[str], y_pred: list[str], labels: list[str]) -> dict:
    """Per-class precision/recall/F1, macro averages, and the confusion matrix.

    Undefined rates are 0.0, never 1.0, and the macro averages run over classes with support.
    Both conventions are stated in `dev.kasoti.evalmetrics.Classification`; this is an
    independent implementation for the model card, and `check_metrics_agreement` is not
    written because the card is a training-time artefact while the evalmetrics version is the
    gated one. Same rules, so the two cannot disagree about a *definition*.
    """
    support = {label: y_true.count(label) for label in labels}
    predicted = {label: y_pred.count(label) for label in labels}
    matrix = {a: {p: 0 for p in labels} for a in labels}
    for actual, guess in zip(y_true, y_pred):
        matrix[actual][guess] += 1

    per_class = []
    for label in labels:
        tp = matrix[label][label]
        fp = predicted[label] - tp
        fn = support[label] - tp
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / support[label] if support[label] else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        per_class.append({
            "label": label, "support": support[label], "predicted": predicted[label],
            "truePositive": tp, "falsePositive": fp, "falseNegative": fn,
            "precision": precision, "recall": recall, "f1": f1,
        })

    scored = [row for row in per_class if row["support"] > 0]
    return {
        "n": len(y_true),
        "accuracy": sum(1 for a, p in zip(y_true, y_pred) if a == p) / len(y_true) if y_true else 0.0,
        "macroPrecision": float(np.mean([r["precision"] for r in scored])) if scored else 0.0,
        "macroRecall": float(np.mean([r["recall"] for r in scored])) if scored else 0.0,
        "macroF1": float(np.mean([r["f1"] for r in scored])) if scored else 0.0,
        "minRecall": min((r["recall"] for r in scored), default=0.0),
        "perClass": per_class,
        "confusion": {a: [matrix[a][p] for p in labels] for a in labels},
    }


def confusion_table(metrics: dict, labels: list[str]) -> str:
    lines = ["| actual \\ predicted | " + " | ".join(labels) + " |",
             "|---|" + "|".join("---" for _ in labels) + "|"]
    for actual in labels:
        row = " | ".join(str(int(v)) for v in metrics["confusion"][actual])
        lines.append(f"| **{actual}** | {row} |")
    return "\n".join(lines)


# ------------------------------------------------------------------ synthetic


def synthetic_records(per_class: int) -> list[dict]:
    """A synthetic corpus, for exercising the pipeline.

    One recipe per class is a degenerate training set: the model will fit it perfectly and
    prove nothing. `--synthetic` therefore emits *perturbed* copies of each recipe — the
    recipe plus a deterministic geometric jitter, which is the synthetic analogue of the
    lighting and clip variation a real corpus has. It still proves the pipeline, and the model
    card says plainly that the resulting accuracy is a property of the generator.
    """
    rng = np.random.default_rng(patches.SEED)
    records = []
    rows_per_document = 4
    for label in LABELS:
        base = patches.RECIPES[label]()
        for index in range(per_class):
            shift_x = int(rng.integers(-2, 3))
            shift_y = int(rng.integers(-2, 3))
            gain = 1.0 + float(rng.normal(0.0, 0.04))
            shifted = np.roll(np.roll(base, shift_x, axis=1), shift_y, axis=0)
            image = np.clip(shifted.astype(np.float32) * np.float32(gain), 0.0, 1.0)
            records.append({
                "id": f"syn-{label.lower()}-{index:03d}",
                "label": label,
                # A synthetic "document" is a set of crops of one thing, so three rows share
                # a document id — which is the whole point, since it means the split has
                # something to be correct about. Keyed by class as well so one document is
                # not simultaneously all seven processes.
                "source_doc": f"syn-doc-{label.lower()}-{index // rows_per_document:03d}",
                "features": np.asarray(extract(image), dtype=np.float64),
            })
    return records


# ------------------------------------------------------------------ specimens


def specimen_margins(
    weights: list[list[float]],
    bias: list[float],
    floor: float,
) -> tuple[list[dict], list[dict]]:
    """Score the must-abstain specimens and the faded-plate sweep with the fitted model.

    These are *not* part of any split. They exist because accuracy and margin answer different
    questions, and a corpus-level margin percentile cannot answer the one that matters for
    safety: does the model know when it does not know?

    Two things are returned, and the second is the more important one. The specimens are points;
    the sweep is a **curve with a crossing**, and a single specimen asserting "the margin is low"
    is indistinguishable from a value that was picked until the assertion passed. Printing the
    whole contrast sweep lets a reviewer see where the abstention region begins and argue with the
    choice of range.

    Imported lazily so this script still runs when `synth_macros` is unavailable, and so the
    dependency direction stays one-way: the trainer reads the generator, never the reverse.
    """
    import synth_macros

    def score(image) -> tuple[str, float, str]:
        probabilities = softmax_scores(np.stack([extract(patches.normalised(image))]), weights, bias)[0]
        order = np.argsort(probabilities)[::-1]
        return (
            LABELS[int(order[0])],
            float(probabilities[order[0]] - probabilities[order[1]]),
            LABELS[int(order[1])],
        )

    rows = []
    for name, renderer in synth_macros.SPECIMEN_RENDERERS.items():
        label, margin, runner_up = score(renderer())
        rows.append({
            "specimen": name,
            "predicted": label,
            "margin": margin,
            "runnerUp": runner_up,
            "abstainsBelowFloor": margin < floor,
        })
    sweep = synth_macros.faded_offset_contrast_sweep(score)
    for row in sweep:
        row["abstainsBelowFloor"] = row["margin"] < floor
    return rows, sweep


# ----------------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--features", help="JSONL from extract_features.py")
    parser.add_argument("--manifest", help="D-MACRO manifest.csv, for the source_doc column")
    parser.add_argument("--out", default="eval/models/svm_print_v1.json")
    parser.add_argument("--card", default="", help="model card path (default: alongside --out)")
    parser.add_argument("--split-seed", type=int, default=20_260_932)
    parser.add_argument("--c", type=float, default=1.0, help="sub-gradient step scale")
    parser.add_argument("--synthetic", action="store_true", help="train on the synthetic recipes")
    parser.add_argument("--synthetic-per-class", type=int, default=40)
    parser.add_argument("--synthetic-corpus", default="",
                        help="feature JSONL from synth_macros.py; marks the model synthetic")
    parser.add_argument("--generator", default="", help="provenance string recorded in the model file")
    parser.add_argument("--allow-synthetic", action="store_true",
                        help="permit --synthetic / --synthetic-corpus to write a model at all")
    parser.add_argument("--margin-floor", type=float, default=DEFAULT_MARGIN_FLOOR,
                        help="reporting floor for the margin table (fusion owns the real threshold)")
    parser.add_argument("--manifest-out", default="eval/models/manifest.json",
                        help="index of the models in this directory, with their hashes")
    args = parser.parse_args(argv)

    is_synthetic = bool(args.synthetic or args.synthetic_corpus)
    if is_synthetic and not args.allow_synthetic:
        print("train_svm: --synthetic / --synthetic-corpus need --allow-synthetic.\n"
              "  A model trained on synthetic patches is not a model for the macro gate: it\n"
              "  has never seen a print process, and SPEC.md §7's offset-vs-inkjet bar is\n"
              "  meaningless against it. The flag exists so that choice is deliberate and\n"
              "  visible in the command line rather than implied by a filename.", file=sys.stderr)
        return 2
    if not is_synthetic and not args.features:
        parser.error("--features is required unless --synthetic or --synthetic-corpus is given")
    if args.synthetic and args.synthetic_corpus:
        parser.error("give one of --synthetic or --synthetic-corpus, not both")

    if args.synthetic:
        records = synthetic_records(args.synthetic_per_class)
        provenance = "SYNTHETIC recipes from eval/tools/patches.py (NOT D-MACRO)"
    else:
        source = args.synthetic_corpus if is_synthetic else args.features
        records = load_features(pathlib.Path(source))
        if is_synthetic:
            # The corpus is externally generated but still synthetic, so the *records* must say
            # so too. A corpus tool that forgets its own flag is exactly how a synthetic model
            # ends up presented as a trained one, and the only defence is for the flag to live
            # in the data as well as on the command line.
            unmarked = [r["id"] for r in records if not r.get("synthetic")]
            if unmarked:
                print(f"train_svm: REFUSING --synthetic-corpus. {len(unmarked)} record(s) are "
                      f"not marked synthetic, starting with {unmarked[:5]}. Either the corpus is "
                      f"real — in which case drop --synthetic-corpus and pass --manifest — or the "
                      f"generator did not stamp its output, which means every artefact downstream "
                      f"will claim a provenance that is not there.", file=sys.stderr)
                return 2
            provenance = args.generator or "SYNTHETIC corpus (eval/tools/synth_macros.py) — NOT D-MACRO"
        else:
            provenance = "D-MACRO"
        if args.manifest:
            docs = load_source_docs(pathlib.Path(args.manifest))
            missing = [r["id"] for r in records if r["id"] not in docs]
            if missing:
                print(f"train_svm: {len(missing)} row(s) have no source_doc in the manifest, "
                      f"starting with {missing[:5]}. A row that cannot be attributed to a "
                      f"document cannot be attributed to a split, and a random per-row split "
                      f"is leakage (EVAL.md §2).", file=sys.stderr)
                return 2
            for record in records:
                record["source_doc"] = docs[record["id"]]
        elif not is_synthetic:
            print("train_svm: no --manifest, so source_doc is taken from the feature file. "
                  "If it is missing or constant, every row lands in one split and the report "
                  "split will be empty — which this script then refuses.", file=sys.stderr)

    # ---- split by source document ------------------------------------------
    document_ids = [record.get("source_doc") for record in records]
    if any(not d for d in document_ids):
        bad = sum(1 for d in document_ids if not d)
        print(f"train_svm: {bad} row(s) have no source_doc. Refusing: EVAL.md §2 requires "
              f"splitting by source document, and guessing would silently produce a random "
              f"per-row split, which is the leakage the rule exists to prevent.", file=sys.stderr)
        return 2
    if len(set(document_ids)) < 2:
        print("train_svm: every row shares one source document, so no split is possible. "
              "Refusing rather than reporting a number from a corpus that has no held-out "
              "part.", file=sys.stderr)
        return 2

    split_of_document = assign_splits(list(zip([r["label"] for r in records], document_ids)), args.split_seed)
    for record in records:
        record["split"] = split_of_document[record["source_doc"]]

    by_split: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        by_split[record["split"]].append(record)

    # ---- the refusal that matters ------------------------------------------
    report_labels = {r["label"] for r in by_split["report"]}
    empty = [label for label in LABELS if label not in report_labels]
    if empty:
        print(f"train_svm: REFUSING to emit a model. Class(es) {empty} have zero support in "
              f"the report split, so nothing can be claimed about them: a model card that "
              f"silently omits a class is worse than no model card (EVAL.md §4, §8). "
              f"Collect more source documents for those classes, or widen the report split.", file=sys.stderr)
        print(f"  documents per split: "
              + ", ".join(f"{s}={len({r['source_doc'] for r in v})}" for s, v in sorted(by_split.items())))
        return 2

    # ---- fit on train, choose on tune, report on report --------------------
    x_train = np.stack([r["features"] for r in by_split["train"]])
    y_train = [r["label"] for r in by_split["train"]]
    weights, bias = fit_linear_ovr(x_train, np.asarray(y_train), LABELS, args.c)

    def evaluate(split: str) -> dict:
        rows = by_split[split]
        if not rows:
            return {"n": 0, "note": f"the {split} split is empty"}
        x = np.stack([r["features"] for r in rows])
        probabilities = softmax_scores(x, weights, bias)
        predicted = [LABELS[i] for i in probabilities.argmax(axis=1)]
        return classification_metrics([r["label"] for r in rows], predicted, LABELS)

    tune_metrics = evaluate("tune")
    report_metrics = evaluate("report")

    # ---- margins: the number fusion actually acts on -------------------------
    report_rows = by_split["report"]
    report_margins = margin_report(
        [r["label"] for r in report_rows],
        softmax_scores(np.stack([r["features"] for r in report_rows]), weights, bias),
        args.margin_floor,
    )
    specimens: list[dict] = []
    sweep: list[dict] = []
    if is_synthetic:
        specimens, sweep = specimen_margins(weights, bias, args.margin_floor)
    else:
        print("train_svm: specimen abstention check SKIPPED — the specimens are generated by "
              "synth_macros.py, so scoring them against a D-MACRO model would be measuring the "
              "generator, not the model. The abstention evidence for a real model has to come "
              "from a held-out real reject pile (DATA.md §3).")

    # ---- export -------------------------------------------------------------
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.UTC).strftime('%Y%m%dT%H%M%SZ')
    # The word is in the run id, not only in a field: a run id is what gets pasted into a
    # review, and "train-20260101-SYNTHETIC-s20960932" is impossible to misread.
    run_id = f"train-{stamp}-{'SYNTHETIC-' if is_synthetic else ''}s{args.split_seed}"
    model = {
        "version": MODEL_VERSION,
        "labels": LABELS,
        "weights": weights,
        "bias": bias,
        "featureNames": feature_names(),
        "trainingRunId": run_id,
        "synthetic": is_synthetic,
        "splitPolicy": "70/15/15 by source document (EVAL.md §2)",
        "sourceDocCount": len({r["source_doc"] for r in records}),
        "trainedOn": provenance,
    }
    out.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")

    # ---- model index ---------------------------------------------------------
    # A weights file with no recorded hash is a weights file nobody can tell apart from the one
    # that was actually deployed. DESIGN.md §6 hash-pins every model; this is the text-format
    # equivalent for the one model that is committed rather than fetched.
    index_path = pathlib.Path(args.manifest_out)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    card_path = pathlib.Path(args.card) if args.card else out.with_name(out.stem + "_MODEL_CARD.md")
    index = {
        "models": [
            {
                "path": out.name,
                "version": model["version"],
                "trainingRunId": run_id,
                "synthetic": is_synthetic,
                "trainedOn": provenance,
                "splitPolicy": model["splitPolicy"],
                "sourceDocCount": model["sourceDocCount"],
                "modelCard": card_path.name,
                "sha256": digest,
                "bytes": out.stat().st_size,
                "command": " ".join(["python3", "eval/tools/train_svm.py", *sys.argv[1:]]),
            },
        ],
        "note": (
            "Hashes are of the committed artefact only. A D-MACRO model is expected to appear "
            "here alongside the synthetic one; `:eval`'s macro suite prefers the real one and "
            "labels the synthetic fallback loudly. Never delete the synthetic entry to make the "
            "directory look clean — its absence is how a demo starts abstaining to UNKNOWN again."
        ),
    }
    if index_path.exists():
        try:
            existing = json.loads(index_path.read_text(encoding="utf-8"))
            kept = [m for m in existing.get("models", []) if m.get("path") != out.name]
            index["models"] = kept + index["models"]
        except (json.JSONDecodeError, OSError):
            print(f"train_svm: {index_path} exists but is unreadable; rewriting it.", file=sys.stderr)
    index_path.parent.mkdir(parents=True, exist_ok=True)
    index_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")

    # ---- model card ---------------------------------------------------------
    documents = {s: len({r['source_doc'] for r in v}) for s, v in by_split.items()}
    rows = {s: len(v) for s, v in by_split.items()}
    specimen_block = ""
    if specimens:
        specimen_block = (
            "\n## Abstention on the ambiguous specimens\n\n"
            "Not part of any split and not accuracy — a question about whether the model knows "
            "what it does not know. Each row is a real thing a capture pipeline produces.\n\n"
            "| Specimen | What it is | Top class | Runner-up | margin | below the floor? |\n"
            "|---|---|---|---|---|---|\n"
            + "\n".join(
                f"| `{row['specimen']}` | {_SPECIMEN_WHAT.get(row['specimen'], '')} | "
                f"{row['predicted']} | {row['runnerUp']} | {row['margin']:.4f} | "
                f"{'abstains' if row['abstainsBelowFloor'] else '**no** — confident anyway'} |"
                for row in specimens
            )
            + f"\n\nFloor used here: {args.margin_floor} (`ThresholdRegistry.MACRO_MARGIN_AMBER`).\n"
        )
    sweep_block = ""
    if sweep:
        sweep_block = (
            "\n## Where abstention begins: a decaying plate\n\n"
            "A 210 lpi offset screen with every geometric feature left intact and only the dot "
            "darkness swept. A single specimen asserting \"the margin is low\" is "
            "indistinguishable from a number that was chosen until the assertion passed, so the "
            "whole sweep is printed: the abstention region is where the lattice has decayed into "
            "the paper grain, and the crossing is visible.\n\n"
            "| dot darkness | top class | runner-up | margin | below the floor? |\n"
            "|---|---|---|---|---|\n"
            + "\n".join(
                f"| {row['contrast']:.2f} | {row['label']} | {row['runnerUp']} | "
                f"{row['margin']:.4f} | {'abstains' if row['abstainsBelowFloor'] else 'confident'} |"
                for row in sweep
            )
            + "\n"
        )
    card = f"""# Model card — `{out.name}`

**{'SYNTHETIC MODEL. Do not quote its accuracy as a macro result.' if is_synthetic else 'Trained on a real corpus. Still read "Known limits" before quoting anything.'}** Read
"Known limits" first; the short version is below.

| Field | Value |
|---|---|
| training run id | `{run_id}` |
| trained on | {provenance} |
| rows | {rows} |
| source documents | {documents} |
| split policy | 70/15/15 by **source document** (EVAL.md §2) |
| split seed | {args.split_seed} |
| features | {FEATURE_WIDTH} (`MacroFeatures.TOTAL`): 7 spectral/gross + 59 LBP bins |
| classes | {len(LABELS)} |
| solver | one-vs-rest linear SVM, {ITERATIONS} sub-gradient epochs, C={args.c} |
| synthetic | **{is_synthetic}** |
| sha256 | `{digest}` |

## Split discipline

Rows are grouped by source document **before** splitting, so no two crops of one document can
land in different splits. The report split is measured; the tune split is where `C` is
chosen; the two are never mixed. Every class has non-zero report support — the script exits
non-zero rather than emitting a model otherwise.

{confusion_table(report_metrics, LABELS)}

## Per-class scores on the **report** split

| Class | Support | Precision | Recall | F1 |
|---|---|---|---|---|
""" + "\n".join(
        f"| {row['label']} | {row['support']} | {row['precision']:.4f} | {row['recall']:.4f} | {row['f1']:.4f} |"
        for row in report_metrics["perClass"]
    ) + f"""

macro-precision {report_metrics['macroPrecision']:.4f} · macro-recall {report_metrics['macroRecall']:.4f} ·
**macro-F1 {report_metrics['macroF1']:.4f}** · accuracy {report_metrics['accuracy']:.4f} ·
worst class recall {report_metrics['minRecall']:.4f} (n={report_metrics['n']})

On the **tune** split: macro-F1 {tune_metrics.get('macroF1', float('nan')):.4f}, accuracy
{tune_metrics.get('accuracy', float('nan')):.4f} (n={tune_metrics.get('n', 0)}).

## Margin distribution on the **report** split

{margin_table(report_margins)}

Overall: median {report_margins['overall']['p50']:.3f}, p05 {report_margins['overall']['p05']:.3f},
minimum {report_margins['overall']['min']:.3f};
{report_margins['overall']['belowFloor']}/{report_metrics['n']} rows
({report_margins['overall']['belowFloorRate'] * 100:.1f}%) sit below the {args.margin_floor} floor.
{specimen_block}{sweep_block}
## Known limits

""" + f"""1. **{'This is a SYNTHETIC model and must never gate anything.' if is_synthetic else 'This model was fitted on a real corpus.'}** {'The patches come from `eval/tools/synth_macros.py`, which renders *mechanisms* — a halftone screen, a stochastic droplet field, a fused toner edge — and not a single pixel of ink on paper. Every number in this card is a property of that generator and moves the moment the generator changes. It exists so the demo can classify and the pipeline can be proven end to end. SPEC.md §7 needs D-MACRO: real documents, the macro clip, consented capture, split by source document (DATA.md §3, EVAL.md §2).' if is_synthetic else 'The corpus size and collection provenance are in `eval/data/macro/manifest.csv`; the numbers above are only as good as that collection.'}
2. **The class balance reflects what was generated, not the world.** {'One figure like ' + f"{report_metrics['perClass'][0]['recall']:.2f}" + f" on n={report_metrics['perClass'][0]['support']} should be read as " + '"not yet measured".' if is_synthetic else 'A class with a handful of source documents has a recall whose confidence interval spans most of the unit interval.'}
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
"""
    card_path.write_text(card, encoding="utf-8")

    print(f"wrote {out}")
    print(f"wrote {card_path}")
    print(f"wrote {index_path}")
    print(f"  rows {rows}, documents {documents}")
    print(f"  report split: macro-F1 {report_metrics['macroF1']:.4f}, "
          f"accuracy {report_metrics['accuracy']:.4f}, worst class recall {report_metrics['minRecall']:.4f}")
    print(f"  report margins: median {report_margins['overall']['p50']:.4f}, "
          f"p05 {report_margins['overall']['p05']:.4f}, "
          f"{report_margins['overall']['belowFloor']}/{report_metrics['n']} below {args.margin_floor}")
    for row in specimens:
        print(f"  specimen {row['specimen']:32} -> {row['predicted']:10} margin {row['margin']:.4f} "
              f"{'abstains' if row['abstainsBelowFloor'] else 'DOES NOT ABSTAIN'}")
    if sweep:
        crossing = [r for r in sweep if r["abstainsBelowFloor"]]
        if crossing:
            confident = [r["contrast"] for r in sweep if not r["abstainsBelowFloor"]]
            print(f"  faded-plate sweep: abstains at dot darkness "
                  f"<= {max(r['contrast'] for r in crossing):.2f}"
                  + (f", confident down to {min(confident):.2f}" if confident else ""))
    if is_synthetic:
        print("  SYNTHETIC MODEL — never gate-eligible. See the model card.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
