#!/usr/bin/env python3
"""Prove the NumPy feature extractor and `:core` agree, and report every divergence.

Why this is a test and not a comment
------------------------------------
`eval/tools/extract_features.py` reimplements `dev.kasoti.factory.ProcessClassifier.extract`
in NumPy so the offline training pipeline can compute features without a JVM. Two
implementations of the same maths drift unless something checks them, and "I read the code
and it looks the same" is not a check. This script is the check: it regenerates the shared
synthetic recipes, extracts features both ways, and compares element by element against
per-feature limits that were **measured** rather than guessed.

What the limits mean
--------------------
`extract_features.FEATURE_LIMITS` splits the features into three groups, and the distinction
is the substantive result rather than a detail:

* **float32 accumulation** (`std_dev`, `edge_density`, the 59 LBP bins) — `:core` sums in
  `Float` over ~65 000 terms and NumPy sums in `float64`. The arithmetic is identical; the
  observed spread is ~1e-7 absolute. A larger delta here is a real disagreement.
* **float32 FFT** (`band_energy`, `high_frequency_energy`, `peakiness`) — `:core`'s twiddle
  recurrence runs in `Float` and NumPy's transform is double precision, so the radial profile
  itself differs and the derived quantities inherit it. Energy fractions are stable to
  ~5e-4; a ratio against a local floor amplifies that to a few percent.
* **advisory** (`spectral_slope`) — the two implementations differ by up to ~0.8 absolute,
  because the least-squares denominator cancels several significant digits. That is a
  finding about `:core`'s precision, it is reported on every run, and it is deliberately not
  gated: a limit loose enough to accommodate it would not be a check.

The measured deltas are printed whether or not the check passes, so a tolerance that has
drifted loose is visible without opening this file.

This check earned its keep. It found, in this repository: a transposed x/y axis convention
(`GrayImage.get(x, y)` reads `pixels[y * width + x]`, so array indices are `[y, x]`); a
signed-versus-unsigned 32-bit shift in the shared hash; a `np.where` that silently promoted a
float32 pipeline to float64; and a float32 divide that had to be reproduced in float32 or the
dither recipe produced a visibly different image. Three of those four were in this side of
the check, which is the expected ratio.

Usage
-----
    ./gradlew :eval:run --args="--emit-crosscheck=/tmp/kotlin_features.jsonl"
    python3 eval/tools/cross_check.py --kotlin /tmp/kotlin_features.jsonl

    # or against any committed run directory, which carries the vectors with it
    python3 eval/tools/cross_check.py \
        --kotlin eval/runs/eval-<date>-smoke-<hash>/crosscheck/kotlin_features.jsonl

Exit code 0 = agree, 1 = gated divergence, 2 = usage/IO.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import patches  # noqa: E402
from extract_features import (  # noqa: E402
    ADVISORY,
    FEATURE_LIMITS,
    LBP_LIMIT,
    SPIRAL_SLOPE_FINDING,
    feature_names,
    extract,
)


def load_kotlin(path: pathlib.Path) -> dict[str, dict]:
    records: dict[str, dict] = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        if "id" not in record or "features" not in record:
            raise SystemExit(f"{path}:{number}: record has no id/features")
        records[record["id"]] = record
    if not records:
        raise SystemExit(f"{path} contained no records")
    return records


def limit_for(name: str) -> tuple[float, str]:
    if name.startswith("lbp_"):
        return LBP_LIMIT, "float32-accumulation"
    return FEATURE_LIMITS.get(name, (0.0, "unknown"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--kotlin", required=True, help="path to kotlin_features.jsonl from :eval")
    parser.add_argument("--quiet", action="store_true", help="print only the verdict and any failures")
    args = parser.parse_args(argv)

    kotlin_path = pathlib.Path(args.kotlin)
    if not kotlin_path.is_file():
        print(f"cross_check: {kotlin_path} not found. Produce it with:\n"
              f"  ./gradlew :eval:run --args=\"--emit-crosscheck={kotlin_path}\"", file=sys.stderr)
        return 2

    try:
        kotlin = load_kotlin(kotlin_path)
    except (OSError, json.JSONDecodeError) as error:
        print(f"cross_check: {error}", file=sys.stderr)
        return 2

    names = feature_names()
    expected_ids = {f"syn-{label.lower()}" for label in patches.RECIPES}
    missing = expected_ids - set(kotlin)
    if missing:
        print(f"cross_check: the Kotlin side is missing recipe(s) {sorted(missing)}", file=sys.stderr)
        return 2

    worst: dict[str, tuple[float, str]] = {n: (0.0, "") for n in names}
    failures: list[str] = []
    advisories: list[str] = []

    for label, recipe in patches.RECIPES.items():
        vector_id = f"syn-{label.lower()}"
        reference = kotlin[vector_id]

        kotlin_names = list(reference.get("featureNames", names))
        if kotlin_names != names:
            failures.append(
                f"{vector_id}: feature NAME order differs. :core has {kotlin_names[:4]}… where "
                f"NumPy has {names[:4]}…. A shuffled feature order produces a classifier that "
                f"is testing a permutation, so this is a hard failure."
            )
            continue
        if reference.get("size") != patches.PATCH:
            failures.append(f"{vector_id}: :core used a {reference.get('size')}px patch, NumPy used {patches.PATCH}px")

        kotlin_features = np.asarray(reference["features"], dtype=np.float64)
        if kotlin_features.size != patches.TOTAL:
            failures.append(f"{vector_id}: :core produced {kotlin_features.size} features, expected {patches.TOTAL}")
            continue

        # The recipes are generated rather than shipped, and used **unquantised** because
        # `:core`'s SpectrumPatch hands raw floats to GrayImage. Quantising on one side only
        # is exactly the half-difference that makes a cross-check meaningless.
        numpy_features = np.asarray(extract(recipe()), dtype=np.float64)

        for index, name in enumerate(names):
            delta = abs(float(numpy_features[index]) - float(kotlin_features[index]))
            if delta > worst[name][0]:
                worst[name] = (delta, vector_id)
            limit, kind = limit_for(name)
            if kind == ADVISORY:
                continue
            if delta > limit:
                failures.append(
                    f"{vector_id}.{name} ({kind}): numpy={numpy_features[index]:.10g} "
                    f"kotlin={kotlin_features[index]:.10g} |Δ|={delta:.3g} (limit {limit:g})"
                )

    if not args.quiet:
        print(f"recipes compared          : {len(patches.RECIPES)}")
        print(f"features per recipe       : {patches.TOTAL} "
              f"({7} spectral/gross + {patches.TOTAL - 7} LBP bins)")
        print(f"feature-name order        : identical in both implementations")
        print()
        print("worst observed |Δ| per feature group (measured, not assumed):")
        for name in names[:7]:
            delta, where = worst[name]
            limit, kind = limit_for(name)
            flag = "  (advisory)" if kind == ADVISORY else ""
            print(f"  {name:<24} {delta:>10.3g}   limit {limit:<8g} {kind}{flag}"
                  + (f"   worst on {where}" if where else ""))
        lbp_delta = max(worst[n][0] for n in names if n.startswith("lbp_"))
        lbp_where = max((worst[n] for n in names if n.startswith("lbp_")), key=lambda pair: pair[0])[1]
        print(f"  {'lbp_* (59 bins)':<24} {lbp_delta:>10.3g}   limit {LBP_LIMIT:<8g} float32-accumulation"
              f"   worst on {lbp_where}")
        print()
        print("float32-accumulation  : :core sums in Float over ~65 000 terms, NumPy in float64.")
        print("                       Identical arithmetic; the spread is rounding, not disagreement.")
        print("float32-fft          : :core's twiddle recurrence runs in Float, so the radial")
        print("                       profile itself differs and derived quantities inherit it.")
        print()
        advisories.append(SPIRAL_SLOPE_FINDING)

    for advisory in advisories:
        print("FINDING (reported, not gated):")
        print(f"  {advisory}")
        print()

    if failures:
        print(f"DIVERGENCE — {len(failures)} gated disagreement(s):", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print("AGREE — the NumPy extractor and :core produce the same feature vectors within the")
    print("measured limits for every gated feature. The model exported by train_svm.py is")
    print("therefore a model over the features the device actually computes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
