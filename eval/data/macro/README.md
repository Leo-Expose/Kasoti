# D-MACRO — print-process patches

**Media is never committed.** `eval/data/**/*.png` and `*.jpg` are gitignored; this directory
holds the manifest and the SOP, not the pixels. Patches are 2 mm texture crops (DATA.md §1)
and are visually inspected by the collector *and* the owner to contain no readable characters
or faces before they are ever used.

## SOP (DATA.md §3)

- Sources: teammates' own genuine microprint zones, patches only; and SPECIMEN reprints on
  inkjet, laser, photocopy and phone-screen captures.
- Protocol: clip v2 seated flat → in-app macro mode → sharpness bar green → capture.
- Log per patch: `{source-id, process-label, light, clip, device, calib-id}`.

## `manifest.csv` columns

`row_id`, `source_doc`, `process_label`, `light`, `clip`, `device_id`, `calib_id`,
`captured_at_utc`, `operator`, `double_checked_by`, `notes`

- **`source_doc` is the one that matters.** EVAL.md §2 requires the 70/15/15 split to be *by
  source document*. Two crops of one card share every pixel-level artefact, so splitting by
  patch leaks the test set into training and the resulting accuracy measures memorisation.
  `train_svm.py` refuses to train without it and says so.
- `double_checked_by` is not decoration. DATA.md §1 requires two people to confirm a patch
  contains no legible PII, and an unconfirmed row has not had that review.

## Labels and target size

OFFSET, INKJET, LASER, DYESUB, SCREEN, PHOTOCOPY, UNKNOWN (the reject pile, kept for
negatives). Target ≥600 for v1, ≥1500 by M3. Clip/no-clip × 3 lights, so a bare
`source_doc` is only comparable within a lighting condition.
