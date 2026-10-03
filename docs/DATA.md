# KASOTI — Data Plan & Collection SOPs (DATA.md)

> ⚠️ **Zero real data has been collected.** `eval/data/` exists and its manifests are committed
> — `datasets.json` (splits + `splitUnit`), `threshold_provenance.json` (the anti-gaming
> ledger), the calibration slot — and **every `manifest.csv` has a header and zero rows**.
> No macro patch, no face crop, no paste clip, no spoof clip, no usability session, and no
> volunteer consent form has been signed. The SOPs below are written and unexecuted; §2's
> SPECIMEN cards do not exist as artwork. `docs/STATUS.md` R-E, R-F.

## 1. Prime directive
**No real-PII pixels leave a consent boundary.** Macro patches are 2 mm texture crops — visually inspected to contain no readable characters/faces before commit. Face data = volunteers + public sets only, with signed consent (template §6). Violation = delete + incident note.

Enforcement is mechanical, not aspirational: `.gitignore` excludes `eval/data/**/*.png` and
`*.jpg`, re-admitting only `eval/data/fixtures/**` (generated vectors and recipes, not
photographs), and `eval/models/*` is excluded with a wildcard so no `.tflite` can be committed by
accident. `scripts/purge_volunteer_data.sh <id>` is written, scoped to `eval/data`, `eval/runs`
and `eval/fixtures`, and dry-run tested — but it has never been run against real rows, because
there are none.

## 2. SPECIMEN mock cards (M0.6)
Design 4 watermarked tracks (passport-datapage-like, aadhaar-like, voter-like, paper-nagarikta-like) with: guilloche-ish background (drawn, not copied), photo box, MRZ band (passport), QR block (aadhaar-like w/ TEST-signed payload), stamp box. Big diagonal `SPECIMEN — KASOTI TEST`. Print masters on office laser for layout proof; genuine-process references come from §3.
⚠️ **Not produced.** `hardware/print_targets.md` is a specification and says so in its own first
line ("This is a specification, not artwork"). No design file, no printed set. This blocks
`FusionEngine`'s coverage rules and the track matrix (FUSION.md §7) from ever being exercised
against a real card, and it is why `DEMO.md` §4's "SPECIMENs printed (10 sets)" cannot be
ticked. `docs/STATUS.md` R-L.

## 3. Macro SOP (D-MACRO)
Sources: (a) teammates' OWN genuine cards/letters/currency microprint zones — PATCHES ONLY, collector + owner double-check no legible PII; (b) SPECIMEN reprints on inkjet + laser + photocopy + phone-screen captures.
Protocol per patch: clip v2 seated (shroud flat) → in-app macro mode → sharpness bar green → capture; log: {source-id, process-label, light(sun/shade/torch), clip(bool), device, calib-id}. Target M0: ≥600; M3: ≥1500. Labels: OFFSET, INKJET, LASER, DYESUB, SCREEN, PHOTOCOPY, UNKNOWN(reject-pile, kept for negatives).
Layout: `eval/data/macro/<process>/<light>/<clip|noclip>/<sourceid>_<n>.png` + `manifest.csv`.

⚠️ **Two blockers before the first patch can be captured, in order.** (1) **No clip has been
built** — `hardware/clip_bom.md` is a costed BOM at ~₹445/clip. (⚠️ **corrected 2026-10-03:** the
in-app macro mode's "never been compiled" reason is gone — `:app-android` now compiles and ships
inside four APKs. It has still never been **run**, so the macro mode has no exercised capture
path, and the clip is still unbuilt.) (2) **No calibration** — without
`device_calib.json` the harness refuses macro metrics outright (EVAL.md §7), so a patch captured
without the §5 routine is a patch that cannot be used for a gate.

**Layout, as actually committed:** the per-label directory tree above is the *target*. What
exists is `eval/data/macro/manifest.csv` with its ten columns
(`row_id,source_doc,process_label,light,clip,device_id,calib_id,captured_at_utc,operator,double_checked_by,notes`)
and **zero data rows**. `row_id` and `source_doc` are required — `train_svm.py` refuses to train
without them, because a row that cannot be attributed to a source document cannot be attributed
to a split, and D-MACRO's split unit *is* the source document (`datasets.json`).

## 4. Face pairs SOP (D-FACE) + consent
Each volunteer: 1 "doc-style" still (plain wall, front) + 6 varied (light/angle/expression) + 1 print-attack + 1 screen-attack clip for D-SPOOF. Genuine pairs = doc×varied; impostor = cross-identity doc×varied (balanced). Buckets: gender × age-band × lighting recorded. Consent covers: capture, on-device + repo storage (CROPPED faces only), eval use, deletion on request within 24 h, no redistribution. Public LFW/CFP subsets: license-noted, sanity-only.

⚠️ **Two independent reasons this has not started, and only one of them is fixable by effort.**
(1) **No embedder.** `emb_v1.tflite` does not exist — every candidate was rejected on licence
grounds (`docs/spikes/01-face-model.md` §2.1). Collecting consented crops now would produce
images that no pipeline can turn into a vector, so the consent burden would be taken on for
nothing. (2) **No device.** Capture lives in `:app-android`, which **compiles and packages** as of
2026-10-03 but has **never been run** — no `adb`, no handset. Consent-first ordering matters more
than usual here: get a licence, then a device, then consent.

## 5. Calibration card (printable, `hardware/calibration_card.pdf`)
Contents: 4 gray patches (white/18%/black + skin-tone), 5 mm grid + 1 mm scale, resolution wedges, mini rosette-vs-droplet explainer. Routine (30 s, in-app guided): seat clip on card → capture → app stores `device_calib.json` {wb gains, px/mm, focus-ok}. Required ≤7 d before macro eval (EVAL §7). Bring 2 laminated cards to finals.

✅ The **PDF exists and compiles** (299,935 B, 2 pages, `pdflatex` + `lmodern`; the `.tex` source
is committed so it is reproducible). ⚠️ **The routine has never been run** — there is no `device_calib.json`
for any device, which is why every eval run reports `calib REFUSED` and `MACRO-CAL` is SKIPPED.
The routine itself is in-app. ⚠️ **corrected 2026-10-03:** `:app-android` now compiles and packages,
so the card is no longer "a printable artefact with no code behind it" — but the routine has still
never been executed, so it is a printable artefact with an **unexercised** capture path behind it.
A `device_calib.json` requires a device, and there is none. `docs/STATUS.md` R-D.

## 6. Consent template (short, keep signed copies offline)
"I consent to KASOTI (SIH prototype) capturing cropped face/macro-texture images of my own documents for offline testing, stored in the team's private repo, used only for evaluation, deleted on my request within 24 h, never published or shared externally. Date/signature." (Minors: guardian signs.)
⚠️ No signed copy exists. Two changes this project owes the template before it is used: "the
team's private repo" should name the actual retention location, and with a single maintainer there
is no "team" — the consent needs an identified controller and a reachable contact.

## 7. MRZ/QR fixtures (generated, zero PII)
MRZ: `core` generator (seeded) → valid + 1-char mutants in each check field + format breaks. QR: TEST keypair signs valid payloads; mutants flip bytes/fields/keys. Vectors committed; prod-key slots stay EMPTY with rotation doc pointer.

## 8. Storage & retention
`eval/data/` (manifests + media) · `eval/runs/` (outputs). Face/macro-source media: private repo only, no forks-public. Post-finals: volunteer data deleted on request; default purge 90 d. Deletion is `scripts/purge_volunteer_data.sh <volunteer-id>` (removes media + diary test rows + re-runs smoke to confirm green).
