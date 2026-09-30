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
| 17 | `STATUS.md` | What works, what is red, what has never been run; §7 names the next five tasks |

> `STATUS.md` and `BUILD_HANDOFF.md` are recent and were written after a 47-claim audit of
> this doc set. Where an older doc disagrees with them, they are newer and the disagreement
> is a bug worth reporting.

## Quickstart (what actually runs today — see `docs/BUILD.md` §3 and `docs/STATUS.md` §1)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk      # Gradle needs JDK 17
git clone <repo> && cd kasoti
./gradlew :core:jvmTest                # 270 tests, 0 failures
./gradlew :ui:test                     # 44 tests
./gradlew :platform:jvmTest            # 137 tests
./gradlew :app-desktop:test            # 169 tests
./gradlew :app-desktop:run             # headless post-console; prints usage
./gradlew :eval:run --args="smoke"     # 11 pass / 1 skip; EXITS 4, not 0 (see below)
```

`:app-android:installDebug` is listed here for the target state and **does not run**: no Android
SDK is installed, so `:app-android` is not in the build at all. Nothing about the field app has
ever been compiled or run (`docs/STATUS.md` R-C).

**Exit code 4 is correct, not a broken build.** `:eval:run` returns 4 = INCOMPLETE whenever a
required suite is skipped, and the device gates are always skipped without a device. A smoke run
returning 0 would imply a device was attached.

First-run rule: **everything works in airplane mode on a fresh install.** No model/key downloads
at runtime. Ever. `ci.yml` is written to check it, but **CI has never run** (no git remote, no
runner) and the offline proof it performs is warm-cache only — `docs/STATUS.md` R-J, R-K.

## Repo layout (as built, not as planned)

```
kasoti/
  core/            Kotlin Multiplatform common: ALL logic (mrz, checks, qr, factory,
                   face-math, diary, sync, fusion, audit, evalmetrics, threshold)
  platform/        expect/actual — **JVM only today** (crypto, imaging, Tess4J OCR,
                   model loader, desktop TFLite detector). No androidMain.
  ui/              plain kotlin-jvm presentation state + a FieldView contract.
                   **Not Compose Multiplatform** — see ui/README.md §1
  app-android/     CameraX field app (lanes). **Written, never compiled — no SDK.**
  app-desktop/     headless Post-Console CLI (Linux/Win/Mac run from checkout;
                   distribution packaging not built)
  eval/            harness CLI + fixtures + manifests + runs (no real PII)
  hardware/        clip BOM, calibration card PDF+TeX, print_targets.md (a spec, not artwork)
  docs/            this package
```

Always in the build: `:core :platform :eval :app-desktop :ui`. SDK-gated: `:app-android`
(`settings.gradle.kts:52-65`).

## Ten principles (non-negotiable)

1. **No verdict needs network.** Network is for sync/reports only. Enforced today: `:core` has
   zero banned network imports (75 files scanned, green).
2. **Cascade: cheapest check first.** Math → QR/chip → factory → face → diary.
3. **One embedding model everywhere.** Cross-device diary comparison forbids model drift
   (DESIGN.md §5). ⚠️ **No embedding model exists yet** — the slot is empty on licence grounds
   (spike 01 §2.1), so 1:1 is fail-closed and GREEN is unreachable. The *rule* holds; the model
   does not exist.
4. **GREY, don't guess.** Poor capture → mandatory retake, never an accusation.
5. **Measured beats claimed.** No metric on a slide unless the harness printed it. ⚠️ Currently
   one number clears that bar (§Status). Two gates we would want are red and are not worked
   around: no `:core` PII scrubber, and 171 unregistered numeric literals.
6. **Corroboration for RED on weak substrates.** PVC print-mismatch alone → AMBER (FUSION.md).
7. **Decision-assist only.** Officer overrides; every override logged.
8. **Privacy by construction.** Texture patches contain no PII; embeddings on-device; retention caps; one-tap wipe.
9. **Boring tech wins.** File diary, HMAC sync, TFLite everywhere, zero OpenCV (rationale in DESIGN.md §9).
10. **Freeze early, rehearse hard.** Hour-0 scope freeze at finals; P0-only board after. ⚠️ Nothing
    has been rehearsed.

## Status

All unchecked, deliberately, and `docs/STATUS.md` §1–§5 is the authority — read it before
believing any milestone claim.

- [ ] M0 core + harness + clip + first numbers — `:core` and the harness are real (620 tests
      green across four modules; `smoke` and `full` both run). **No clip has been built, and
      there are no first numbers**: no D-MACRO media, no calibration, no face embedder.
- [ ] M1 Android E2E airplane-mode — `:app-android` has never been compiled. No APK, no
      airplane proof, no size number.
- [ ] M2 desktop + cross-device sync — the console runs and the sync layer is the best-tested
      part of the project (39 tests incl. a 100k-record merge budget), but cross-*device* sync
      is unproven (one device has ever existed) and desktop packaging does not exist.
- [ ] M3 red-team + frozen metrics — `docs/REDTEAM.md` is a runbook; it has never been run.
      All 34 thresholds are untuned; the threshold registry file does not exist.
- [ ] M4 finals

**The one number that is a real, citable result:** MRZ mutate-catch 100% of *detectable*
mutants (6725/6725), with 746 structurally blind rows reported separately — run
`eval-20260929-smoke-a1c7`, gate `M-GATE-01`. It is logic verified on a generated corpus, not
a result about real documents. There is **no** macro or face accuracy number in this project,
because there is no calibration, no macro dataset and no embedder.

## Maintainer

One person. `docs/HANDOFF.md` §2 records what that costs (no second reviewer, bus factor 1) and
§5 is a knowledge map whose backup column reads "none" throughout, because that is the truth.

## License / use
Prototype for SIH demonstration and research evaluation. Not for operational deployment without MHA legal/technical clearance (see THREAT_MODEL.md §8). **There is no `LICENSE` file at the repo root** (`docs/STATUS.md` R-I) — Apache-2.0 components are vendored under their own terms and our own terms exist only in this sentence. Model weights: `THIRD_PARTY.md` §3 is the register; the detector's licence is verified, the embedder slot is empty, and the UIDAI QR keys are unobtained.
