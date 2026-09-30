# `:app-desktop` — the Post-Console (DESIGN.md §1)

Secondary review · diary admin · sync hub · shift report · case-bundle export · override.

**Headless is a hard requirement.** There is no Android SDK in CI, no display guarantee on a
post machine, and a reviewer who cannot run the thing will not run the thing. So this pass
ships the domain logic plus a fully functional text UI.

## Commands

```
kasoti screen   --image F --track T [--fields sidecar.json] [--mrz L1,L2] [--demo]
                [--svm model.json] [--today YYYY-MM-DD] [--device D] [--grey-streak N]
                [--no-clip] [--fusion core|local] [--no-rationale]
kasoti override --case C --verdict V --reason R --by OPERATOR [--note TEXT]
kasoti verify   [--verbose]
kasoti export   --case C [--out bundle.zip]
kasoti wipe     --pin P --yes
kasoti macro    --image F --source-id S --process P [--light sun|shade|torch] [--rect x,y,w,h]
kasoti help
```

Exit codes: `0` ok · `1` usage · `2` refused by policy · `3` integrity failure · `4` I/O.

Override reasons (closed set, enforced): `SUP_DOC_AUTHENTIC`, `SUP_PRINT_DEFECT`,
`SUP_DATA_ENTRY_ERROR`, `SUP_TRANSLITERATION`, `SUP_DEVICE_FAULT`, `SUP_OTHER_JUSTIFIED`.
The last one additionally requires `--note`.

## What it does today

- **screen** — runs the cascade (image → math → QR → macro → face → diary), prints the layer
  table, the finding table (code, severity, evidence ref, detail), the verdict with the
  officer-facing sentence in English and Hindi, `:core`'s own rationale, and both policy
  versions (invariant I3). Appends a `DecisionRecord` to the audit chain and stores a
  scrubbed case summary.
- **override** — appends a `DecisionRecord` with `overriddenBy` + `overrideReasonCode`. The
  `DecisionRecord` constructor itself refuses an override with no reason code, so the
  constraint is structural rather than a check that could be skipped.
- **verify** — rebuilds the chain from the stored log and exits `3` if a record does not hash
  to its stored value.
- **export** — writes `case.json` + `crops/` + `audit.txt` as a zip, under 500 KB, with no
  live-face data unless `--include-live-face` is passed (NFR-P2).
- **wipe** — supervisor-PIN gated one-tap wipe, compared in constant time over a SHA-256
  digest, leaving a timestamped marker.
- **macro** — the DATA.md §3 collector, so a labelled patch can be taken from the console.

## The sidecar

`--fields sidecar.json` carries the extracted fields. Every field is optional.

```json
{
  "mrz": ["<44 chars>", "<44 chars>"],
  "printName": "SHARMA RAMESH",
  "printDob": "1990-06-08",
  "issueDate": "2019-03-04",
  "expiry": "2031-06-07",
  "qrRawBase64": "<the exact bytes that were signed>",
  "qrSignatureBase64": "<RSA-SHA256 signature>",
  "qrX509KeyBase64": "<SubjectPublicKeyInfo DER>",
  "qrKeyId": "uidai_test",
  "qrFields": {"name": "...", "dob": "..."},
  "photoZone": {"x": 0.14, "y": 0.18, "w": 0.20, "h": 0.32},
  "textZone":  {"x": 0.05, "y": 0.72, "w": 0.90, "h": 0.12},
  "presentation": "PHYSICAL",
  "trust": "VERIFY"
}
```

`qrX509KeyBase64` is a **test** key supplied per screening. The production ring is a
provenance-reviewed file that does not exist yet (DESIGN.md §6 keeps the `uidai_prod` slot
empty on purpose).

## Privacy

- Every printed line goes through `LogScrubber` (invariant I5). It redacts PII *keys*
  (`name`, `dob`, `embedding`, …) and PII *shapes* (MRZ rows, ISO dates, Aadhaar numbers,
  phone numbers, emails, long hex/base64), and neutralises control characters so a document
  cannot forge or repaint a log line.
- The case bundle carries findings, severities, evidence refs, policy versions, chain hashes
  and macro crops. It does **not** carry names, dates of birth, MRZ text, document numbers
  or face pixels. The MRZ *check results* travel; the MRZ *characters* do not.

## Deliberate omissions

- **Compose Multiplatform is not a dependency in this pass.** It is a large download and a
  build risk, and the app must stay green on a machine with no display. The view layer is
  already behind `ConsoleView`, so adding a CMP surface is a new implementation of that
  interface and nothing else. Tracked for M2; tripwire in ROADMAP is D7.
- **`FusionEngine` is used, and the console is GREY on every file import today.**
  `CoreFusionEngine` delegates to `dev.kasoti.fusion.FusionEngine` (the default; `--fusion local`
  selects `LocalRuleEngine`, the console's own reading of FUSION.md §1–§4). `:core` is
  fail-closed about load-bearing layers (FUSION.md §7): a passport case with no macro, face
  or diary evidence cannot reach GREEN, so the console prints `RETAKE` and says which capture
  is missing. That is the correct behaviour, and it is also why the end-to-end tests pin
  `--fusion local` — they are about the console's plumbing, and re-deriving `:core`'s rules
  from here would break every time a rule is retuned. `CoreFusionEngineTest` covers the
  delegation itself.
- **`LocalRuleEngine` is kept deliberately.** It is the comparison oracle during threshold
  tuning: a divergence between it and `:core` on the same evidence is a finding, not a
  nuisance. It is also the console's fallback if the fusion package is mid-refactor.
- **`dev.kasoti.diary` does not exist yet.** The diary layer reports `UNAVAILABLE` and
  contributes `null` evidence. `TODO(M2,@core)`.
- **The face layer has no interpreter.** `TODO(M2,@vision)`; it reports `UNAVAILABLE` rather
  than inventing a similarity.
- **`crops/` is empty in an exported bundle.** `TODO(M2,@vision)`: a case does not yet store
  its macro zones. An empty directory is a smaller lie than a wrong one.
- **`SvmModelFile` should live in `:core`** (DESIGN §6) so Android and the eval harness read
  the artefact the same way. `TODO(M2,@core)`; it is a move, not a rewrite.
- **The sidecar is parsed with `kotlinx-serialization-json` at runtime.** `:core` is
  concurrently gaining a `dev.kasoti.json` package; once that stabilises the sidecar reader
  should move onto it and drop the extra dependency.
