# D-FACE — consented face pairs

**No image, crop, thumbnail or embedding ever enters this directory or the repository.**
DATA.md §1 and §4 are unambiguous, and `.gitignore` enforces the mechanical half. What is
committed is the *consent register* and the manifest: enough to say which identity is in
which split, and nothing that could identify them.

## Split (EVAL.md §2)

`tune` / `report`, split **by IDENTITY**. Both images of one volunteer must sit in the same
split — a "genuine" pair in the report split that is a re-sighting of a training identity
inflates TAR and is the single easiest way to fake a good face number.

## Per volunteer (DATA.md §4)

1 doc-style still + 6 varied (light/angle/expression) + 1 print-attack + 1 screen-attack clip.
Genuine pairs = doc × varied; impostor = cross-identity doc × varied, balanced. Buckets
recorded: gender × age band × lighting.

## Consent

Template in DATA.md §6. Covers capture, on-device + repo storage of **cropped** faces only,
eval use, deletion on request within 24 h, no redistribution. Signed copies stay offline.
Deletion: `scripts/purge_volunteer_data.sh <volunteer-id>`, which removes media, the diary
test rows, and re-runs smoke to confirm green.

`manifest.csv` columns: `row_id`, `identity_id` (**pseudonymous** — never a name),
`pair_type` (`genuine` | `impostor`), `bucket` (`gender×ageband×lighting`),
`embedding_model`, `device_id`, `calib_id`, `consent_id`, `captured_at_utc`, `notes`
