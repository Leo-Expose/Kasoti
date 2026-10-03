# KASOTI — Finals Demo Runbook (DEMO.md)

> ⚠️ **This runbook has never been rehearsed.** §6's log is empty. The demo also depends on
> things that do not exist yet: no SPECIMEN artwork, no built clip, no `:app-android` build, no
> face embedder. Treat this as a script to be rehearsed, not a record of a performance.

## 1. Venue assumptions
Power + projector (HDMI + backup VGA dongle) · network assumed DEAD (we never need it) · ambient light mixed → bring clip shroud + calibration card + small desk lamp (consistent macro light) · table for exhibit.

## 2. Roles (one person)
The original six-role split (SPEAKER / OPERATOR / JUDGE-HANDLER / DEBUG / Q&A-TECH / Q&A-POLICY)
assumed a team. **There is one maintainer**, so the roles collapse to one body with a fixed
sequence of hats, and the discipline of the split has to be *rehearsed* rather than staffed:

- **SPEAKER** (default) — pitch. Never touches a device mid-sentence.
- **OPERATOR** (second hat) — runs the phone/console, hands nothing.
- **JUDGE-HANDLER** (third hat) — hands clip/prints to judges and *guides their hands*.
- **DEBUG** (fourth hat) — swap to the backup device, whisper-fix, own the P0 board.
- **Q&A** — vision / face / sync / privacy, from `QA_BANK.md`.

Rehearse the transitions explicitly: the failure mode of a one-person demo is talking over your
own hands. Lanyard cards with recovery lines still apply.

## 3. Minute-by-minute (180 s + 120 s buffer/Q&A)
- 0:00–0:20 Airplane-mode ON (projector shows quick-settings). Genuine SPECIMEN → GREEN. "Raxaul. No network. Next please."
- 0:20–1:00 MICROSCOPE MOMENT: live macro of genuine vs inkjet side-by-side. Judge handed ANY printout → classify live.
  **Do not say "our 50 KB model sees it in 30 ms."** That number was written before anything was
  trained or timed. The honest line: *"our macro features run in `:core`; the classifier is a
  linear SVM and its accuracy on real print substrate is not yet measured — we will not quote a
  number we have not produced."* (A regression into the old sentence is worse than a slow demo.)
- 1:00–1:40 LIVE FORGERY: pre-printed inkjet twin → RED (R-PROC + evidence). Glue-swap photo (pre-glued spare if time) → RED face/macro. "Design: perfect copy. Factory: wrong."
  ⚠️ The face half of this depends on an embedder that **does not exist**. As of today this beat
  can only be played with the macro/MRZ layers. Say so rather than faking it.
- 1:40–2:20 THE ALIAS: a scripted crossing under one name → later under another → RED alias + travel
  fixture on the console. This one is honest today: `D-GATE-01`/`02`/`03` are green
  (run `eval-20260929-smoke-a1c7`), on scripts rather than on real people.
- 2:20–3:00 CLOSE (3 slides): forger's bill (per-substrate ladder) · queue math (lanes) · cost
  · failure-gallery teaser ("and here's what beats us — ask us").
  ⚠️ **Cost slide: the clip is ~₹445/clip by the BOM, not ₹300** (`hardware/clip_bom.md` §2).
  Present ₹350–450, or present the cut-scope option. Do not present ₹300.

Buffer: trust-lane pass · case-bundle export (`:app-desktop export` works) · wipe drill
(`:app-desktop wipe` works, PIN-gated).

## 4. Setup checklist (night before + morning)
Devices charged + battery-saver OFF · demo-mode ON + seeded + reset rehearsed · models/keys hash-verified (`scripts/fetch_models.sh` green; `scripts/verify_bundle.sh` will be red until `bundle_manifest.sample.txt` is populated) · USB installers (APK + Win/Linux/Mac zips) on 2 sticks ⚠️ **corrected 2026-10-03, and corrected again later the same day: the APKs exist — but not as four split ones.** There is now **one universal debug APK** (88.72 MB), **one universal release APK** (77.55 MB), a **1-ABI `sideload` APK** (23.01 MB arm64-v8a / 16.18 MB armeabi-v7a) and the **`app-android-release.aab` (36.97 MB)**, which is the shipping product; `checkApkSize` gates the compressed worst-case per-device slice (14.60 / 25.52 / 22.91 / 35.90-advisory) against the unchanged 35.0 MB. `splits.abi` was removed because it is mutually exclusive with app bundles on AGP 8.9.2, which is what broke `bundleRelease` before. **No `.aab` has been uploaded to Play**, **no Windows/macOS build has been produced** (both are review-only for on-device inference anyway, STATUS R-T), and **no APK has ever been installed on a device**, so the "backup device with a known-good build" below is still empty · SPECIMENs printed (10 sets) ⚠️ **the artwork does not exist** (`hardware/print_targets.md` is a spec) + pre-glued spare + glue stick · 2 clips ⚠️ **not built** + calibration cards (laminated; the PDF exists and compiles) · exhibit board (macro prints large) · printed gallery ⚠️ **empty — no red-team day has been run** + Q&A one-pager per judge · backup device with a known-good build ⚠️ **a build now exists (2026-10-03) but has never been installed or run**, so "known-good" is unearned.

## 5. Recovery lines (memorize; calm > clever)
- App crash → swap to the backup device. SPEAKER: "That's why we carry two lanes — like a real post." (Never debug on stage.)
- Macro misclassifies live → "Good — that's an AMBER case; console, please," → show secondary flow (AMBER path is also a demo).
- Judge's printout breaks router → "Unsupported track — watch what honest software does," → GREY + reason (GREY is a feature).
- Projector dies → paper exhibit board + phone-pass-around (rehearse once without projector).
- A judge asks for a number we don't have → "We don't have that measurement yet, and we won't
  invent it. It's on our list." This is the answer the whole project is built to make possible;
  using a fake number once destroys every real number we have.
- "But X beats you" → "Yes — gallery slide N. Attacker cost: …. Next question welcome."

## 6. Rehearsal log (5 consecutive crash-free = gate)
| # | date | venue-like? | crashes | notes | sign |
|---|---|---|---|---|---|
| — | **NOT YET REHEARSED** | | | | |

Rehearse once: no projector · once: hostile (interrupt yourself with QA_BANK) · once: timed 150 s
(cuttable middle). Until five rows exist here, SPEC §6 M4's rehearsal criterion is unmet and
DEMO.md must not claim the demo is demoable.
