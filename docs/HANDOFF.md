# KASOTI — Handoff & Maintainer Doc (HANDOFF.md)

> **This file is the process doc.** For the state of the *build* — verified commands, the
> decisions that look wrong on purpose, the two gates that are deliberately red, and the
> `./gradlew :eval:run` exit-code trap — read **`BUILD_HANDOFF.md`** first. For what works and
> what has never been run, read `STATUS.md`. This file covers roles, rituals and onboarding only.

> **This project is maintained by ONE person.** Earlier revisions of this file described a
> six-person team with an Android pair, a desktop engineer and a vision/data engineer. That
> is not the project any more, and pretending otherwise cost real time: role tables sent
> reviewers looking for owners who do not exist, and `bus-factor ≥ 2` was a commitment
> nothing could keep. This file now describes one maintainer, and §2 records the consequence
> (there is no second pair of eyes) rather than hiding it.

## 1. Assumptions (update if wrong)
**One maintainer · solo-built · ~4 weeks to finals** · Android SDK not currently installed, so `:app-android` is not in the build · desktop (Linux) is the only target that compiles today · Windows and Apple-Silicon Mac are *review-only, no on-device inference* (see §6). The solo-founder variant in `ROADMAP.md` §7 is **not** hypothetical: it is what is being built.

## 2. Roles (one owner per row; owner = review gate)
| Role | Who | Owns | Docs |
|---|---|---|---|
| **Lead + maintainer** | the repo owner (this file's author) | everything below; final say on scope, freeze, fusion rules, thresholds registry, licences, and every approval in §2 | SPEC, FUSION, RISKS, HANDOFF |
| `:core` | maintainer | mrz, checks, qr, factory, face-math, diary, sync, fusion, audit, evalmetrics, threshold | DESIGN §2, SYNC, AGENTS |
| `:platform` (JVM only) | maintainer | JCA crypto, ImageIO imaging, Tess4J OCR, model loader, desktop TFLite detector | DESIGN §3, BUILD |
| `:ui` | maintainer | presentation state + `FieldView` contract. **No Compose binding exists** — see `ui/README.md` | DESIGN §1 (D7) |
| `:app-desktop` | maintainer | post-console CLI, case-bundle export, shift report, `LogScrubber` | BUILD, DESIGN §3 |
| `:app-android` | maintainer, **unverified** | capture, ML Kit OCR, TFLite detector, demo mode. Written but **never compiled** — no SDK, no device | DESIGN §3, BUILD |
| `:eval` harness | maintainer | suites, gates, split discipline, calibration gate, run records | DATA, EVAL, REDTEAM |
| Datasets / consent / demo | maintainer | SPECIMEN artwork, D-MACRO, D-FACE, the demo script and the Q&A rehearsal | DATA, DEMO, QA_BANK |

Everyone — meaning the one person: red-team day, one hostile-Q&A rehearsal aloud, one
airplane-install test. All three are still outstanding (§4).

**What the single-owner table costs, stated plainly:**

- **No second reviewer.** AGENTS.md §3 item 7 ("review by the owner of the touched area")
  and §4 ("reviewer pastes verdict") are satisfied only by self-review, which is weaker
  than a second pair of eyes by exactly the amount you would expect. Treat every
  self-review as a *check*, never as an approval.
- **Bus factor is 1, and it is not fixable by effort.** `RISKS.md` R12 assumed ≥2. If the
  maintainer is unavailable the project stops. The only mitigations that exist are
  (a) the commit history and `docs/` being the whole state of the project, and
  (b) `docs/STATUS.md` §7 naming the next five things, so a successor starts from a list
  rather than from nothing.
- **Single-person blind spots are the real risk.** Nothing in the pipeline is adversarially
  reviewed. `docs/REDTEAM.md` exists for exactly this and **has never been run**.

## 3. Onboarding checklist (a successor, or the maintainer returning after a gap, day 1–2)
- [ ] Read `docs/README.md` → `SPEC.md` → `DESIGN.md` → `AGENTS.md` (in that order); note 3 questions.
- [ ] `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` — Gradle needs **JDK 17**.
- [ ] Build: `./gradlew :core:jvmTest` + `./gradlew :eval:run --args="smoke"` on your machine
      (log OS/JDK in `docs/BUILD.md` §1 tested-matrix). Note: `smoke` currently exits **4**
      (INCOMPLETE) because the device gate is skipped — that is correct behaviour, not a
      broken build. See `docs/STATUS.md` §1.
- [ ] Read `docs/STATUS.md` end to end. It is the only file that says what is true today,
      and §7 names the next five things.
- [ ] Install an Android SDK (`local.properties` → `sdk.dir=`, or `ANDROID_HOME`) and run
      `./gradlew :app-android:assembleDebug`. **Nothing about the field app has ever been
      compiled or run** — see `docs/STATUS.md` R-C. This is the single biggest unknown.
- [ ] Shadow the demo script (`DEMO.md` §3) once on paper, and the recovery lines (§5).
- [ ] Capture 20 macro patches (`DATA.md` §3) — **the dataset is at zero rows**; this has
      the longest lead time in the project and nothing else unblocks it.

## 4. Rituals
There is no standup. The rituals that survive in a one-person project are the ones that
are *artefacts* rather than meetings, because a meeting with yourself does not happen:
- **Milestone review (end of M0–M3):** live acceptance demo per `SPEC.md` §6, written up
  as Go / Cut-P1 / Replan in `docs/STATUS.md`. Self-administered; still worth doing, because
  a written Go/No-Go is falsifiable and a remembered one is not.
- **Scope freeze (M4 hour 0):** P0-only board; anything else needs a written entry in §7.
- **Decision log (§7):** every reversal written down within 24 h (date, decider, why).
  This is the ritual that matters most here, because there is no one to reconstruct the
  reasoning for you later.
- **Rehearsal log (`DEMO.md` §6):** currently empty. Rehearse once: no projector · once:
  hostile (self-interrupt with `QA_BANK.md`) · once: timed 150 s.

## 5. Knowledge map (bus factor is 1 — that is the honest entry, not a gap to fill)
| Area | Primary | Backup | State |
|---|---|---|---|
| Fusion / thresholds* | maintainer | **none** | `ThresholdRegistry.kt` exists; `fusion/thresholds.v1.json` does **not** (STATUS R-O) |
| Sync / crypto* | maintainer | **none** | strongest area: `SyncTest` 39 tests incl. 100k-record perf |
| Face pipeline* | maintainer | **none** | detector works on desktop; **embedder blocked on licensing** (STATUS R-A) |
| Macro / SVM | maintainer | **none** | only a SYNTHETIC model exists; no D-MACRO media (STATUS R-E) |
| Android capture | maintainer | **none** | **never compiled** — no SDK, no device (STATUS R-C) |
| Desktop / packaging | maintainer | **none** | console runs from checkout; packaging task does not exist (BUILD §3) |
| Eval harness | maintainer | **none** | works; `smoke` = 11 pass / 1 skip, `full` = 13 pass / 6 skip |
| Demo / presenting | maintainer | **none** | runbook written, **never rehearsed** |
| Licences / procurement | maintainer | **none** | `THIRD_PARTY.md` is current except for the ML Kit + Compose rows (now added) |

*Starred areas were flagged for bus-factor ≥2 in the old team version of this file. That
requirement is formally unmeetable and is recorded here as accepted, not as pending.

## 6. Contacts & access
| Item | Value |
|---|---|
| Repo URL / branch strategy | **no git remote is configured** (`git remote -v` is empty). History is local-only; CI cannot run and PRs are impossible until a remote exists (STATUS R-S). Branch naming in `CONTRIBUTING.md` §1 is the intent, not current practice. |
| CI dashboard | none — `.github/workflows/ci.yml` is written and YAML-valid, but **has never executed** (no remote, no runner). STATUS R-J. |
| Shared drive (datasets, deck) | none. Datasets and the deck are uncommitted; the SPECIMEN artwork does not exist (`hardware/print_targets.md` is a spec, not artwork). |
| Hardware (devices, clips, LEDs) keeper | maintainer. `hardware/clip_bom.md` is a costed BOM, not a built clip: **no clip has been built.** |
| Judge-liaison / mentor | none assigned |
| Build machine | Ubuntu, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` (Temurin 17.0.20.1). **The repo sits on an exFAT volume that ignores `chmod`**, so every file is mode 755 and any 0600 secret written inside the checkout is world-readable — provision to `~/.kasoti/provisioning` instead. STATUS R-Q. |

## 7. Decision log
| Date | Decision | Decider | Why / link |
|---|---|---|---|
| 2026-09-30 | TFLite-everywhere (D1) — kept, with the desktop runtime changed to `ai.djl.tflite` | maintainer | no first-party TFLite JVM artefact exists; `docs/spikes/01-face-model.md` §4 |
| 2026-09-30 | File diary (D2) | maintainer | DESIGN §9 |
| 2026-09-30 | HMAC sync prototype (D3) | maintainer | SYNC §7; 39 tests green |
| 2026-09-30 | Adopt `blazeface_short.tflite` (detector); `emb_v1.tflite` **not** filled | maintainer | licensing, not engineering — spike 01 §2.1, §7 |
| 2026-09-30 | Windows x86-64 and Apple-Silicon Mac are **review-only, no on-device inference** | maintainer | no upstream native exists; a version bump will not fix it. Spike 01 §4 |
| 2026-09-30 | `:ui` is a plain `kotlin-jvm` presentation-state module, **not** Compose Multiplatform | maintainer | cannot be compiled or resolved without the SDK; `ui/README.md` §1 |
| 2026-09-30 | Commit the **synthetic** SVM (`svm_print_v1_synthetic.json`) alongside the absent D-MACRO model | maintainer | its absence is how the demo silently abstains to UNKNOWN again. Labelled loudly at every use; never gate-eligible |
| 2026-09-30 | This project is **solo-maintained**; the six-role table above is history | maintainer | reality |
| | ~~ML Kit bundled OCR~~ — not compiled, not measured | | see STATUS R-C; every Android claim is currently a claim |

## 8. Handoff checklists
**Maintainer stepping away (planned or not):** every branch merged or documented · `docs/STATUS.md` §7 rewritten to the new state, not appended to · every open `R-` in `STATUS.md` §5 given a one-line verdict · the decision log (§7) current · secrets rotated (`scripts/provision.sh` keys live outside the repo — see the exFAT warning in §6) · the embedder licensing question (STATUS R-A) either answered in writing or explicitly still open.
**Receiving the project:** onboarding §3 · read `STATUS.md` first and believe it over the other docs · re-run `:core:jvmTest` and `:eval:run --args="smoke"` before changing anything · do not trust a number in a doc that does not cite an `eval/runs/<run-id>/` (AGENTS.md §5).
