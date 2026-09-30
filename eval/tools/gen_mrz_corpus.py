#!/usr/bin/env python3
"""Write the D-MRZ corpus out as reviewable fixture files.

**The Kotlin generator is normative, and this file contains none of it.**

`dev.kasoti.mrz.MrzCorpus.generate(seed, count, referenceYear)` is the corpus of record
(EVAL.md §2, DATA.md §7). `:eval` calls it in-process, so the M-gate is measured on the
generator that also builds the documents — which is honest about the consequence: the
mutate-catch gate proves the parser agrees with the generator, and **cannot** detect a shared
misreading of ICAO 9303, because both were written by the same team. Two other anchors cover
that, and they are the ones that matter: the published ICAO 9303 Part 5 worked examples, and
an independent 7-3-1 reference implementation, both in `:eval`'s unit-parity suite. A third
implementation by the same people would be a third opinion, not a second one.

An earlier draft of this file *was* such a reimplementation, including a transcription of
`kotlin.random.Random`'s XorWow. It was deleted, for a specific reason worth recording:
`kotlin.random.Random` is a JDK implementation detail rather than a published algorithm, its
`nextInt(bound)` has a power-of-two fast path whose exact overflow behaviour is not part of any
specification, and reimplementing it is a coin flip that fails silently — a fixture file that
differs from `:core` in ways nobody notices, which is worse than not having the file.

So this script does no generation. It reads the corpus `:eval` emits and formats it, which
means a row here is the same row the gate measured, by construction rather than by agreement.

Why it exists at all
--------------------
A corpus you cannot look at is hard to review. A forger, a red-teamer and a reviewer all
want the same artefact: the actual MRZ lines, on disk, that produce a given result. The
blind-spot rows in particular are worth reading — they are the cases no MRZ arithmetic can
catch, and the whole argument for the signed-QR, chip, macro and face layers rests on them
being a real, bounded set rather than a shrug.

Usage
-----
    # 1. emit the normative corpus from :core
    ./gradlew :eval:run --args="--emit-mrz-corpus=/tmp/d_mrz.jsonl --mac-rows=10000"

    # 2. format it for reading
    python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --format csv \
        --out eval/data/fixtures/mrz/
    python3 eval/tools/gen_mrz_corpus.py --in /tmp/d_mrz.jsonl --blind-only

    # any smoke run already carries one, at crosscheck/../mrz_corpus.jsonl
    python3 eval/tools/gen_mrz_corpus.py \
        --in eval/runs/eval-<date>-smoke-<hash>/mrz_corpus.jsonl
"""

import argparse
import json
import pathlib
import sys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--in", dest="source", required=True,
                        help="corpus JSONL emitted by :eval (--emit-mrz-corpus, or a run directory's mrz_corpus.jsonl)")
    parser.add_argument("--format", choices=("jsonl", "csv", "md"), default="csv")
    parser.add_argument("--out", default="-", help="output directory, or '-' for stdout")
    parser.add_argument("--blind-only", action="store_true",
                        help="only the structurally blind rows — the ones no MRZ arithmetic can catch")
    parser.add_argument("--limit", type=int, default=0, help="cap the rows written (0 = no cap)")
    args = parser.parse_args(argv)

    source = pathlib.Path(args.source)
    if not source.is_file():
        print(f"gen_mrz_corpus: {source} not found.\n"
              f"  Produce it with:\n"
              f"    ./gradlew :eval:run --args=\"--emit-mrz-corpus={source}\"", file=sys.stderr)
        return 2

    rows = []
    for line in source.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        # `expectedCaught` is also false for unmutated valid documents, which are the
        # *precision* half of the gate rather than blind spots. `--blind-only` means a
        # mutated row with no recorded blind-spot reason, so the flag cannot quietly widen
        # into "everything not caught" and make the blind count look larger than it is.
        if args.blind_only:
            mutated = row.get("mutation", "NONE") != "NONE"
            if row.get("expectedCaught", True) or not mutated:
                continue
        rows.append(row)
    if args.limit:
        rows = rows[: args.limit]
    if not rows:
        print("gen_mrz_corpus: nothing to write (no rows, or no blind rows).", file=sys.stderr)
        return 1

    if args.format == "jsonl":
        text = "\n".join(json.dumps(row, sort_keys=True) for row in rows) + "\n"
    elif args.format == "csv":
        import csv
        import io
        buffer = io.StringIO()
        writer = csv.writer(buffer)
        writer.writerow(["id", "format", "mutation", "expected_caught", "blind_spot_reason",
                         "line_0", "line_1", "line_2"])
        for row in rows:
            padded = list(row["lines"]) + ["", ""]
            writer.writerow([
                row["id"], row["format"], row["mutation"],
                str(row.get("expectedCaught", "")).lower(),
                row.get("blindSpotReason") or "",
                *padded,
            ])
        text = buffer.getvalue()
    else:
        lines = [
            f"# D-MRZ — {len(rows)} row(s) from `{source}`",
            "",
            "| id | format | mutation | expected caught | lines |",
            "|---|---|---|---|---|",
        ]
        for row in rows:
            rendered = " / ".join(f"`{line}`" for line in row["lines"])
            reason = f" — {row['blindSpotReason']}" if row.get("blindSpotReason") else ""
            lines.append(
                f"| `{row['id']}` | {row['format']} | {row['mutation']} | "
                f"{str(row.get('expectedCaught', '')).lower()} | {rendered}{reason} |"
            )
        text = "\n".join(lines) + "\n"

    if args.out == "-":
        sys.stdout.write(text)
    else:
        directory = pathlib.Path(args.out)
        directory.mkdir(parents=True, exist_ok=True)
        suffix = {"jsonl": "jsonl", "csv": "csv", "md": "md"}[args.format]
        name = f"d_mrz{suffix}" if args.blind_only else f"d_mrz_{len(rows)}rows.{suffix}"
        (directory / name).write_text(text, encoding="utf-8")
        print(f"wrote {len(rows)} row(s) to {directory / name}")

    blind = sum(
        1 for row in rows
        if not row.get("expectedCaught", True) and row.get("mutation", "NONE") != "NONE"
    )
    print(
        f"{len(rows)} row(s), {blind} structurally blind. "
        f"dev.kasoti.mrz.MrzCorpus is normative and this file adds no generation of its own — "
        f"every row here is a row the M-gate measured.",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
