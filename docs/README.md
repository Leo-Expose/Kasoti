# KASOTI (कसौटी) — Offline Border Identity Screening
### SIH 2026 · PS-26188 · MHA / SSB · Android + desktop console
### `docs/STATUS.md` says what is true; this page is the pitch.

> *"Sona chamak se nahi, kasoti se parkha jata hai."*
> Gold is judged not by its shine, but by the touchstone.

**One line:** an offline-first screening system that verifies **how a document was made** (print-process forensics via a sub-₹500 phone clip), **what the data claims** (check-digits, signed-QR, chip), and **who the person is across time and posts** (face diary + alias/travel rules) — verdict in seconds, airplane mode, no server.

**Authoritative docs (v4, this folder):** these are the whole specification. There is **no
root `README.md` and no v2/v3 predecessor** in this repository — earlier revisions of this line
referred to `kasoti-blueprint.md` and `kasoti-final-plan.md`, neither of which is present, so
"v2/v3 remain as history" was not true and the reference has been removed rather than relocated.

## Doc map — read in this order

| # | Doc | What it answers |
|---|---|---|
| 1 | `SPEC.md` | What we build, what we don't, acceptance criteria, metric gates |
| 2 | `DESIGN.md` | Architecture, modules, APIs, algorithms, models |
| 3 | `../AGENTS.md` | Working agreement for humans + coding agents (commands, DoD, review). **At the repo root, not in this folder** |
| 4 | `ROADMAP.md` | Milestones M0–M4, task tables, cut list, crash variants |
| 5 | `BUILD.md` | Prereqs, build/run/test/package, troubleshooting |
| 6 | `DATA.md` | Dataset collection SOPs, consent, calibration card |
| 7 | `EVAL.md` | Metrics, harness, gates, reporting |
| 8 | `SYNC.md` | `kasoti-sync/1` protocol (format, crypto, merge, transports) |
| 9 | `FUSION.md` | Fusion rules, thresholds, tuning procedure |
| 10 | `THREAT_MODEL.md` | Attackers, attacks, mitigations, misuse, privacy/DPDP |
| 11 | `REDTEAM.md` | Red-team runbook + failure gallery |
| 12 | `DEMO.md` | Finals demo runbook (script, roles, backups) |
| 13 | `QA_BANK.md` | 30 hostile Q&As with answers |
| 14 | `RISKS.md` | Risk register with tripwires |
| 15 | `HANDOFF.md` | Roles, onboarding, rituals, decision log |
| 16 | `BUILD_HANDOFF.md` | **Build state as left: verified commands, the decisions that look wrong on purpose, the two gates that are red, the exit-code trap** |
| 17 | `STATUS.md` | What works, what is red, what has never been run; **§0 is the reviewer-facing delivery table**; §7 names the next five tasks; §8 covers the browser demo |

> `STATUS.md` and `BUILD_HANDOFF.md` are recent and were written after a 47-claim audit of
> this doc set, re-measured on 2026-09-30 after a further audit, and **re-measured again on
> 2026-10-03** — a pass that corrected the two biggest stale claims in the set: `:app-android`
> now compiles and packages, and the test counts had drifted by 159. Where an older doc
> disagrees with them, they are newer and the disagreement is a bug worth reporting.

## Quickstart (what actually runs today — see `docs/BUILD.md` §3 and `docs/STATUS.md` §1)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk      # Gradle needs JDK 17
git clone https://github.com/Leo-Expose/Kasoti.git && cd Kasoti

# ⚠️ On a fresh clone use `sh gradlew`, NOT `./gradlew` — see the note below. Every
#    tracked file is committed mode 100644, so ./gradlew returns "permission denied".
sh gradlew :core:jvmTest                # 459 tests, 0 failures
sh gradlew :ui:test                     # 55 tests
sh gradlew :platform:jvmTest            # 137 tests
sh gradlew :app-desktop:test            # 206 tests
sh gradlew :eval:test                   # 39 tests
# → 896 tests, 0 failures. Re-measured 2026-10-03 in the source checkout with
#   sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test --rerun-tasks
#   (20 tasks, all executed). ⚠️ use --rerun-tasks, not --rerun: a trailing --rerun re-runs
#   only the last task and leaves the other four UP-TO-DATE.
# (On a clean clone 1 test self-skips until you run `sh scripts/fetch_models.sh`.)
sh gradlew :app-desktop:run             # headless post-console; prints usage
sh gradlew :app-desktop:installDist     # builds a runnable distribution
sh gradlew :eval:run --args="smoke"     # 11 pass / 1 skip; EXITS 4, not 0 (see below)
```

> **⚠️ `./gradlew` does not work on a fresh clone.** Git records all 340 tracked files — including
> `gradlew` and every `scripts/*.sh` — as mode `100644`, because the authoring volume is **exFAT**
> and cannot hold the exec bit. `git ls-files -s | awk '{print $1}' | sort | uniq -c` → `340 100644`.
> Your working tree will *look* right (`-rwxr-xr-x`); a fresh clone will not. **This is why both
> CI runs are red** — they died at the first step on `./gradlew: Permission denied`. Use
> `sh gradlew …`, or fix it properly with `git update-index --chmod=+x gradlew scripts/*.sh`.
> `docs/STATUS.md` §0 has the full delivery table; R-J and R-Q have the details.

`**Android, as of 2026-10-03.** An SDK **is** installed, so `:app-android` **is** in the build and
**compiles and packages**:

```bash
sh gradlew :app-android:assembleDebug     # 2 APKs — arm64-v8a 33.39 MB, armeabi-v7a 26.56 MB
sh gradlew :app-android:assembleRelease   # 2 APKs — arm64-v8a 22.97 MB, armeabi-v7a 16.14 MB
sh gradlew :app-android:checkApkSize      # the 35 MB gate — 4 artefacts, all within budget
sh app-android/tools/verify-offline.sh    # exit 0, 201/201 — the SDK-free proof
```

Three things that were **not** fixed and are stated rather than glossed: **there is no `.aab`** —
`:app-android:bundleRelease` fails on AGP 8.9.2 once ABI splits are on, so **this build is
APK-only and cannot produce a Play Store bundle**; **`:app-android:test` does not compile** (160
errors in two `ml/` test files that import `:platform`'s JVM classes); and **nothing has ever run
on a device** — no `adb`, no handset — so `installDebug`, the airplane-install proof and NFR-R1's
50 crash-free runs are all still outstanding. See `docs/STATUS.md` R-C.

**Exit code 4 is correct, not a broken build.** `:eval:run` returns 4 = INCOMPLETE whenever a
required suite is skipped, and the device gates are always skipped without a device. A smoke run
returning 0 would imply a device was attached.

**CI runs, and it is red.** A remote exists and `main` is in sync with `origin/main`, but both
recorded runs failed at the first step on the exec bit above, so **no claim on this page has ever
been verified by a second machine** (`docs/STATUS.md` R-J). The offline proof CI performs is also
warm-cache only — R-K.

## Repo layout (as built, not as planned)

```
kasoti/
  core/            Kotlin Multiplatform common: ALL logic (mrz, checks, qr, factory,
                   face-math, diary, sync, fusion, audit, evalmetrics, threshold)
  platform/        expect/actual — **JVM only today** (crypto, imaging, Tess4J OCR,
                   model loader, desktop TFLite detector). No androidMain.
  ui/              plain kotlin-jvm presentation state + a FieldView contract.
                   **Not Compose Multiplatform** — see ui/README.md §1
  app-android/     CameraX field app (lanes). **Compiles and packages since 2026-10-03
                   (4 APKs, size gate green); never run on a device, no .aab.**
  app-desktop/     headless Post-Console CLI (Linux/Win/Mac; runs from a checkout, and
                   `installDist`/`distZip`/`distTar` build a runnable distribution)
  eval/            harness CLI + fixtures + manifests + runs (no real PII)
  hardware/        clip BOM, calibration card PDF+TeX, print_targets.md (a spec, not artwork)
  docs/            this package
```

Always in the build: `:core :platform :eval :app-desktop :ui`. SDK-gated: `:app-android`
(`settings.gradle.kts:52-65`).

## Ten principles (non-negotiable)

1. **No verdict needs network.** Network is for sync/reports only. Enforced today: `:core` has
   zero banned network imports (**93 files scanned**, green — `scripts/check_no_network_in_core.sh`,
   measured 2026-10-03; it read 78 on 2026-09-30 and the count moves as `:core` grows).
2. **Cascade: cheapest check first.** Math → QR/chip → factory → face → diary.
3. **One embedding model everywhere.** Cross-device diary comparison forbids model drift
   (DESIGN.md §5). ⚠️ **No embedding model exists yet** — the slot is empty on licence grounds
   (spike 01 §2.1), so 1:1 is fail-closed and GREEN is unreachable. The *rule* holds; the model
   does not exist.
4. **GREY, don't guess.** Poor capture → mandatory retake, never an accusation.
5. **Measured beats claimed.** No metric on a slide unless the harness printed it. ⚠️ Currently
   one number clears that bar (§Status). Of the two gates that were red on 2026-09-30, **one is
   now green** — the `:core` PII scrubber exists, its 7 suites run 111 tests, and the CI gate is
   blocking. The other is **still red**: 175 unregistered numeric literals. Neither was worked
   around; the green one was closed with code and tests, and the red one is still red.
6. **Corroboration for RED on weak substrates.** PVC print-mismatch alone → AMBER (FUSION.md).
7. **Decision-assist only.** Officer overrides; every override logged.
8. **Privacy by construction.** Texture patches contain no PII; embeddings on-device; retention caps; one-tap wipe.
9. **Boring tech wins.** File diary, HMAC sync, TFLite everywhere, zero OpenCV (rationale in DESIGN.md §9).
10. **Freeze early, rehearse hard.** Hour-0 scope freeze at finals; P0-only board after. ⚠️ Nothing
    has been rehearsed.

## Status

All unchecked, deliberately, and `docs/STATUS.md` §1–§5 is the authority — read it before
believing any milestone claim.

- [ ] M0 core + harness + clip + first numbers — `:core` and the harness are real (**896 tests
      green across five modules**; `smoke` and `full` both run). **No clip has been built, and
      there are no first numbers**: no D-MACRO media, no calibration, no face embedder.
- [ ] M1 Android E2E airplane-mode — **partly unblocked 2026-10-03.** `:app-android` now compiles
      and produces four APKs, and **the size gate is green** (33.39 / 26.56 / 22.97 / 16.14 MB
      against 35.0 MB). **Still missing: the airplane proof, the E2E run, the 50 crash-free runs,
      and any device execution at all.** There is also **no `.aab`** — APK-only delivery.
- [ ] M2 desktop + cross-device sync — the console runs **both from a checkout and from a
      packaged distribution** (the model now ships inside it), and the sync layer is the
      best-tested part of the project (39 tests incl. a 100k-record merge budget). What is still
      unproven is cross-*device* sync (one device has ever existed) and a genuinely clean-machine
      install.
- [ ] M3 red-team + frozen metrics — `docs/REDTEAM.md` is a runbook; it has never been run.
      All **36** thresholds are untuned; the threshold registry file does not exist.
- [ ] M4 finals

**The one number that is a real, citable result:** MRZ mutate-catch 100% of *detectable*
mutants (6725/6725), with 746 structurally blind rows reported separately — run
`eval-20260929-smoke-a1c7`, gate `M-GATE-01`. It is logic verified on a generated corpus, not
a result about real documents. There is **no** macro or face accuracy number in this project,
because there is no calibration, no macro dataset and no embedder.

## The browser demo — "Docuscan"

There is a **second repository**, `https://github.com/Leo-Expose/Kasoti-Demo.git`, and it is the
public link. It is a static, zero-backend page: no build step, no server, no network call, and a
dropped document never leaves the machine.

**It is presented as "Docuscan". The product in *this* repository is still KASOTI (कसौटI)** — the
rename covers the page, the prose and the sample documents the demo generates, and touches no
identifier, path, class or recorded output. `docs/STATUS.md` §8 has the detail; the decision and
its reason are in `HANDOFF.md` §7.

**It does one thing the product cannot do without a device: it shows the arithmetic and the
measurement record.** It parses a TD1/TD3 MRZ in the tab and shows every check digit's term-by-term
working using a JavaScript port of `core/src/commonMain/kotlin/dev/kasoti/mrz/` (the weights 7-3-1
and the mod-10 rule are **ICAO Doc 9303's**, not ours); it re-runs the 10 000-row mutate corpus;
it extracts a local PDF's text layer with pdf.js; and it replays five recorded console verdicts
captured verbatim from the real `:app-desktop` engine. English/Hindi throughout.

**What it deliberately does not do: it does not re-implement fusion.** No scoring, no finding
codes, no RED/AMBER/GREY logic — `assets/js/` contains none. The verdict panel *replays decisions
made elsewhere*. A judge watching the page is watching recorded output, not a live decision, and
it does not decide whether the document you pasted is a forgery. It also does not substitute for
the hands-on device demonstration the project still owes, because `:app-android` has never been
**run** — it compiles and packages as of 2026-10-03, but no handset has ever installed it (R-C).

**One deliberate inconsistency:** the recorded transcripts still print `KASOTI screening RED`,
because they are byte-for-byte captures and editing them would make the panel's "verbatim" claim
false. Seeing "KASOTI" inside a Docuscan page is not a bug.

---

## Maintainer

One person. `docs/HANDOFF.md` §2 records what that costs (no second reviewer, bus factor 1) and
§5 is a knowledge map whose backup column reads "none" throughout, because that is the truth.

## License / use
Prototype for SIH demonstration and research evaluation. Not for operational deployment without MHA legal/technical clearance (see THREAT_MODEL.md §8). **There is no `LICENSE` file at the repo root** (`docs/STATUS.md` R-I) — Apache-2.0 components are vendored under their own terms and our own terms exist only in this sentence. Model weights: `THIRD_PARTY.md` §3 is the register; the detector's licence is verified, the embedder slot is empty, and the UIDAI QR keys are unobtained.
