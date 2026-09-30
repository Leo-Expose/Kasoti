# D-SPOOF — liveness attack clips

Print-attack and screen-attack face clips, target ≥120 (EVAL.md §2). Measures APCER/BPCER.

**Media is never committed** — the same rule and the same reason as `../face`: these are
photographs of real faces holding real documents.

Held out, not split. A liveness model fitted on the attacks it is later scored against
reports a number that measures memorisation, and liveness is the layer where a false
confidence is most expensive: it is what turns "we could not tell" into "it is genuine".

## What to capture

Per DATA.md §4, each volunteer supplies one print-attack and one screen-attack clip. Vary
the attack surface — matte vs glossy print, phone screen vs laptop, held vs displayed, and
the lighting the booth actually has. An attack set that is one printer and one phone measures
that printer and that phone.

`manifest.csv` columns: `row_id`, `identity_id` (pseudonymous), `attack_medium` (`print` |
`screen`), `attack_surface`, `lighting`, `device_id`, `consent_id`, `captured_at_utc`, `notes`
