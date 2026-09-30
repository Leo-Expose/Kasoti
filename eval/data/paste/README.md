# D-PASTE — photo-swap and tilt clips

Held-out dataset for the F3/F4 validation. Target ≥60 documents (EVAL.md §2).

**Media is never committed.** What lives here is the manifest, the capture SOP and the
consent register.

Held out entirely, and not a split of anything: D-PASTE is validation data, never a fitting
target. A paste-attack model fitted on the attacks it is then scored against reports a
detection rate that is really a memorisation rate.

`manifest.csv` columns: `row_id`, `source_doc`, `attack_type` (`photo-swap` | `tilt` |
`reprint` | `paste-over`), `macro_included`, `device_id`, `calib_id`, `captured_at_utc`,
`consent_id`, `operator`, `notes`
