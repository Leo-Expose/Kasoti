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
**One maintainer · solo-built · ~4 weeks to finals** · ⚠️ **CHANGED 2026-10-03: an Android SDK IS
installed** (`local.properties` `sdk.dir=`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` both
`/opt/android-sdk`) and `sh gradlew projects` lists **all six** projects, so `:app-android` is in
the build and compiles and packages four APKs inside the 35 MB budget. **This row said "Android
SDK not installed" as recently as 2026-09-30T17:36Z and was correct then.** What has *not* changed:
**no `adb` on `PATH` and no device**, so nothing has ever been installed or run on hardware ·
desktop (Linux) is the only target that has been *run* · Windows and Apple-Silicon Mac are
*review-only, no on-device inference* (see §6) · **there is no `.aab`** — `bundleRelease` fails on
AGP 8.9.2 with ABI splits, so delivery is APK-only · **a git remote exists and CI runs, but has
never gone green** (§6). The solo-founder variant in `ROADMAP.md` §7 is **not** hypothetical: it
is what is being built.

## 2. Roles (one owner per row; owner = review gate)
| Role | Who | Owns | Docs |
|---|---|---|---|
| **Lead + maintainer** | the repo owner (this file's author) | everything below; final say on scope, freeze, fusion rules, thresholds registry, licences, and every approval in §2 | SPEC, FUSION, RISKS, HANDOFF |
| `:core` | maintainer | mrz, checks, qr, factory, face-math, diary, sync, fusion, audit, evalmetrics, threshold | DESIGN §2, SYNC, AGENTS |
| `:platform` (JVM only) | maintainer | JCA crypto, ImageIO imaging, Tess4J OCR, model loader, desktop TFLite detector | DESIGN §3, BUILD |
| `:ui` | maintainer | presentation state + `FieldView` contract. **No Compose binding exists** — see `ui/README.md` | DESIGN §1 (D7) |
| `:app-desktop` | maintainer | post-console CLI, case-bundle export, shift report, `LogScrubber` | BUILD, DESIGN §3 |
| `:app-android` | maintainer, **compiles but never run** | capture, ML Kit OCR, TFLite detector, demo mode. ⚠️ **2026-10-03: it compiles and packages** (4 APKs, size gate green) — the blocker was one `#` character on line 1 of its build script. **Still unverified at runtime**: no device, `:app-android:test` does not compile (160 errors), no `.aab` | DESIGN §3, BUILD |
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
- **Nothing has ever been verified by a second machine.** CI exists and runs, and has failed
  twice at the first step because the `gradlew` exec bit is not committed (§6, R-J/R-Q). So
  every number in `STATUS.md` rests on one developer's laptop, and "self-review" is not an
  abstraction here — it is the whole verification story until one commit fixes that.
- **Single-person blind spots are the real risk.** Nothing in the pipeline is adversarially
  reviewed. `docs/REDTEAM.md` exists for exactly this and **has never been run**.

## 3. Onboarding checklist (a successor, or the maintainer returning after a gap, day 1–2)
- [ ] Read `docs/README.md` → `SPEC.md` → `DESIGN.md` → `AGENTS.md` (in that order); note 3 questions.
- [ ] `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` — Gradle needs **JDK 17**.
- [ ] ⚠️ **On your clone, `./gradlew` returns `permission denied`.** Git commits every tracked
      file as mode `100644` (this repo is authored on exFAT — R-Q). Type `sh gradlew …`, or run
      `git update-index --chmod=+x gradlew scripts/*.sh app-android/tools/*.sh` once. This is
      not a local quirk: it is why both CI runs are red.
- [ ] Build: `./gradlew :core:jvmTest` + `./gradlew :eval:run --args="smoke"` on your machine
      (log OS/JDK in `docs/BUILD.md` §1 tested-matrix). Note: `smoke` currently exits **4**
      (INCOMPLETE) because the device gate is skipped — that is correct behaviour, not a
      broken build. See `docs/STATUS.md` §1.
- [ ] Read `docs/STATUS.md` end to end. It is the only file that says what is true today,
      and §7 names the next five things.
- [ ] ~~Install an Android SDK and run `:app-android:assembleDebug`~~ **Done 2026-10-03.** What
      replaces it: get `adb` and a handset, then
      `sh scripts/airplane_install_test.sh --skip-bundle` — it already walks to step D and stops
      at "adb not on PATH". **Nothing about the field app has ever been *run***, and a build is
      not a run — see `docs/STATUS.md` R-C. This is still the single biggest unknown.
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
| Fusion / thresholds* | maintainer | self-review 2026-10-03 | `fusion/thresholds.v1.json` is the registry of record (49 thresholds, `structural` section for the non-tunables); `ThresholdName` is names-only over it. ⚠️ both the JSON and its tests are still **untracked** (STATUS R-O) |
| Sync / crypto* | maintainer | **none** | strongest area: `SyncTest` 39 tests incl. 100k-record perf |
| Face pipeline* | maintainer | **none** | detector works on desktop; **embedder blocked on licensing** (STATUS R-A) |
| Macro / SVM | maintainer | **none** | only a SYNTHETIC model exists; no D-MACRO media (STATUS R-E) |
| Android capture | maintainer | **none** | ⚠️ **compiles and packages** (2026-10-03) but **has never run** — no `adb`, no device; `:app-android:test` does not compile; no `.aab` (STATUS R-C) |
| Desktop / packaging | maintainer | **none** | **distribution works** — `installDist`/`distZip`/`distTar` build and the packaged launcher runs; the model-loses-on-packaging defect is fixed (BUILD_HANDOFF §4.7). Clean-machine install still unexercised |
| Eval harness | maintainer | **none** | works; `smoke` = 11 pass / 1 skip (re-verified 2026-09-30, run `eval-20260930-smoke-93ec`), `full` = 13 pass / 6 skip |
| CI / delivery | maintainer | **none** | ⚠️ **the weakest area in the project** — two red runs, both blocked on the `gradlew` exec bit, so no gate has ever been enforced off this machine (R-J, R-Q) |
| Demo / presenting | maintainer | **none** | runbook written, **never rehearsed** |
| Licences / procurement | maintainer | **none** | `THIRD_PARTY.md` is current except for the ML Kit + Compose rows (now added). **ML Kit's bundled-model offline use under the Google Terms is still marked UNVERIFIED and its "runtime behaviour has never been exercised" is still true** — compiling the app did not run the OCR |
| Privacy / PII | maintainer | **none** | ✅ **improved 2026-10-03**: `core/.../log/PiiScrubber.kt` exists, 7 suites / 111 tests green, and the CI gate is **blocking**. Not yet: routing every log/cache/crash field through it |

*Starred areas were flagged for bus-factor ≥2 in the old team version of this file. That
requirement is formally unmeetable and is recorded here as accepted, not as pending.

## 6. Contacts & access
| Item | Value |
|---|---|
| Repo URL / branch strategy | **`origin` = `https://github.com/Leo-Expose/Kasoti.git` (fetch + push).** Measured 2026-09-30: `git remote -v` shows it, `git branch -vv` → `* main 9a04f40 [origin/main]`, `git rev-list --left-right --count main...origin/main` → `0 0`. Branch naming in `CONTRIBUTING.md` §1 is still the intent rather than current practice — everything so far has gone to `main` directly. ⚠️ **This row previously said "no git remote is configured" and that was false** (STATUS R-S). |
| CI dashboard | `https://github.com/Leo-Expose/Kasoti/actions`. ⚠️ **It has run — twice, both red.** Runs `36663026772` (`9a04f40`) and `36658909646` (`06bd654`), both failed at the first step with `./gradlew: Permission denied` (exit 126), because git commits every tracked file as mode `100644` on this exFAT volume. `gh run list` / `gh run view <id> --log-failed` reproduce it. STATUS R-J, R-Q. |
| Public browser demo | **`https://github.com/Leo-Expose/Kasoti-Demo.git`**, a separate static repo, presented as **"Docuscan"**. It replays recorded engine output and re-derives ICAO 9303 check digits in the browser; it does **not** re-implement fusion. STATUS §8 has the detail. |
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
| **2026-09-30** | **The browser demo is presented as "Docuscan"; the product stays KASOTI.** The rename is **presentational only** — page title, prose, and the sample documents the demo generates. No identifier, package, path, class, script or recorded output was renamed, and this repository is untouched by it. | maintainer | The demo is the public link a judge opens first, and "KASOTI (कसौटी)" needs transliteration to be pronounceable to an audience that does not read Devanagari. Renaming the *product* at this point would invalidate every doc path, package name, `messageKey`, spec reference and recorded transcript already committed — a large, risk-bearing diff for a presentational gain, and it would make the recorded outputs inconsistent with the source that produced them. Splitting the two lets the link be legible without the repository becoming a lie about its own history. **This decision is reversible for the demo and is not being applied to the product.** `docs/STATUS.md` §8 is the reference version of this. |
| **2026-09-30** | **The demo's recorded engine transcripts stay byte-untouched, so they still say "KASOTI".** Not edited, not re-captured, not find-and-replaced. | maintainer | The verdict panel's central claim is that it replays **verbatim** `:app-desktop` output, captured by `tools/capture_real_output.py`. Editing `KASOTI screening RED` → `Docuscan screening RED` would make that claim false to save a reader five seconds of confusion, and there is no way for a reader to tell a doctored capture from a real one. The banner mismatch is instead **disclosed on the page and here**: the transcripts are captures, the rename is presentational, and the inconsistency is a known property rather than an oversight. This is the same rule as AGENTS.md §5 — a metric or an artefact that has been edited to look better is worse than one that is merely unflattering. |
| **2026-09-30** | **Build the desktop distribution properly, and fix the model it was silently losing.** Outcome: `:app-desktop` (the `application` plugin) provides `installDist`/`distZip`/`distTar`; the distribution ships `svm_print_v1_synthetic.json` and its model card under `share/kasoti/models/`, wired into the single `installDist` `Sync` so `distZip`, `distTar` and `shipConsole` inherit it; and a `ModelLocator` prints which file it resolved and **where from** (`--svm` → env → packaged → repository). | maintainer | Two distinct things were wrong and only one was visible. First, four docs said "desktop packaging does not exist" on the strength of a task name (`packageDistributionForCurrentOS`) that never existed — the capability was there all along, so the honest fix was to correct the docs, not to accept the wrong conclusion. Second, and worse, the distribution genuinely was broken: `application` packages only `lib/`, the console's model lookup is repo-relative, so a packaged run found **no model, abstained quietly on the print-process layer, and could return a different verdict from a checkout console on the same document.** Fixing the packaging without surfacing the origin would have left the same class of bug one layer down, so the locator now **prints** the origin: a silent miss and a deliberate fallback must never look the same. Verified 2026-09-30 — at `9a04f40` the distribution held zero `.json`/`.tflite` files; after the fix the packaged launcher's `help` reports the model as *shipped inside this installation*. `BUILD_HANDOFF.md` §4.7 has the before/after. **Deferred:** an install on a machine with no source checkout (SPEC §6 M2). |
| 2026-09-30 | Commit the `gradlew`/`scripts/*.sh` exec bit, or make CI call `sh gradlew` | maintainer | Both CI runs died at the first step on `./gradlew: Permission denied` because git records all 340 tracked files as `100644` — the authoring volume is exFAT and cannot hold the exec bit, so the defect is invisible locally (`ls -l gradlew` shows `-rwxr-xr-x`) and fatal on every clone. Reproduced on a real `git clone` 2026-09-30. Committing the mode bit is preferred; it needs a clone on a non-exFAT filesystem. Until then `sh gradlew` is the documented form in `BUILD.md` §3a and `STATUS.md` §0, because a doc that tells a judge to type a command that returns `permission denied` is worse than no doc. **STILL OPEN as of 2026-10-03** — re-measured `git ls-files -s \| awk '{print $1}' \| sort \| uniq -c` → `340 100644`, `core.fileMode=false`. Nothing in this repository has been compiled by CI. |
| **2026-10-03** | **`:app-android` is now in the build, compiles and packages — and the module ships `armeabi-v7a` + `arm64-v8a` APKs only, with NO `.aab`.** | maintainer | Two things happened and only one of them is good news, so both are recorded together. **Good:** an Android SDK was installed and `sh gradlew :app-android:assembleDebug` / `assembleRelease` / `checkApkSize` all succeed — 33.39 / 26.56 MB debug, 22.97 / 16.14 MB release, all within the **unchanged 35.0 MB** budget. The root cause of a module that had "never been compiled" was **one character**: `app-android/build.gradle.kts` line 1 began with `#`, a *Groovy* comment marker, which the Kotlin script compiler parsed as source. Fixing that, raising `compileSdk` 34→35 (an androidx `minCompileSdk` requirement; `minSdk 26`/`targetSdk 34` untouched) and enabling `splits.abi` was sufficient. **Cost:** AGP 8.9.2 **cannot** apply `splits.abi` and build an app bundle in the same module, so `:app-android:bundleRelease` fails and **there is no `.aab` and no Play Store bundle — delivery is APK-only**. The alternative (drop splits, keep bundling) puts the release artefact at 77.53 MB, over budget by 2.2×, and `x86_64` alone is 35.95 MB, so the trade was taken knowingly rather than by accident. `x86`/`x86_64` are dropped, so **there is no emulator build** — a real loss of demo convenience, accepted on size. CI's size step was rewritten to delegate to `checkApkSize`. `:app-android:test` remains broken (160 compile errors, pre-existing since `06bd654`) and is covered by `verify-offline.sh` instead. Detail and all measurements: `docs/STATUS.md` §2 and §5 R-C. |
| **2026-10-03** | **Accept APK-only delivery until AGP is upgraded; do NOT re-add an emulator ABI and do NOT widen the 35 MB budget to get a bundle.** | maintainer | Written down because both tempting "fixes" are gate-weakening. Dropping `splits.abi` restores `bundleRelease` and returns the release artefact to 77.53 MB; widening the budget to fit `x86_64` (35.95 MB) weakens NFR-S1. Both were rejected in `app-android/build.gradle.kts`'s ADR. Resolving it properly needs an AGP upgrade or the Tesseract-Android capability swap — a licence/capability decision, not a packaging one. AGENTS.md §5 and §8 forbid buying a green build with a weaker gate. |
| **2026-10-03** | **The `:core` PII scrubber is implemented and its CI gate is promoted from advisory to blocking.** | maintainer | `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt` plus seven suites under `core/src/commonTest/kotlin/dev/kasoti/log/`; `sh scripts/pii_scrubber_test.sh` exits 0 with 111 tests green, and the workflow step no longer carries `continue-on-error`, so an absent, broken or silently-empty scrubber now fails the build. That is AGENTS.md §4's "no PII in logs" becoming a *check*. **What is deliberately not claimed:** the script proves the rules work, not that every call site uses them. Until every log/cache/crash field routes through `PiiScrubber`, this is a tested scrubber behind an intent. |
| **2026-10-03** | **`fusion/thresholds.v1.json` is now the registry of record — 49 thresholds with name / unit / default / floor / ceiling / tuning-data ref / owner — but it is UNTRACKED and all 49 defaults are untuned.** | maintainer | AGENTS.md §2 has required this file since the start; it did not exist until now. It does: `schemaVersion` 1, `version` `v1`, and `ThresholdName` was reduced to *names only* so `ThresholdSpecSource` can embed the file verbatim and `ThresholdSpecFileTest` fails if the two ever diverge. Two caveats recorded rather than buried: `git status` reports the file as `??`, so **it is in no commit, in no clone and in no CI run** — an uncommitted registry of record is a registry of record on one laptop; and the defaults are human-typed, not split-chosen, so "versioned" is true and "tuned" is not. **SUPERSEDED, same day, later rows:** the count went **DOWN** once the enum stopped holding the numbers — 1 004 → 889, `:core` 527 → 412, total 1 349 → 1 240. An earlier measurement of this same tree read 968 → 1 004 and concluded the registry was the diagnosis but not the cure; that conclusion was wrong, and the cause was measuring before `ThresholdName` had actually been reduced to names-only. |
| **2026-10-03** | **The magic-threshold gate is CLOSED: `scripts/check_no_magic_thresholds.sh` passes (exit 0, 69 files scanned, 0 findings), and the allowlist is now declared in the registry rather than in the script.** | maintainer | The registry (`fusion/thresholds.v1.json`, `schemaVersion` 1, **49** thresholds) now carries a `structural` section: 17 declared classes of number that are provably NOT tunables, each with an id, an owner, a scope and a prose reason. 14 of them are `enforcedBy: ["script"]` and have a matching rule in the script; 3 are `enforcedBy: ["review"]` (the Unicode codepoint tables, the `:platform` model-pinned anchor hyperparameters, the MRZ corpus generators) and deliberately have no rule. The script cross-checks the two lists in BOTH directions and exits 2 on any mismatch, so neither file can be widened alone. It also self-tests: it aborts unless it still flags a bare `0.42` in a plain comparison, unless the `31`-multiplier probe is still exempt, and unless it can see inside a `${...}` interpolation. That last probe exists because an earlier version of the script blanked string interpolations along with the string, which hid every formatting width in `MetricSink.kt` — the concrete reason it and detekt used to disagree. |
| **2026-10-03** | **Leave the magic-threshold CI step advisory for now, and decide separately whether to promote it.** | maintainer | It is green, so `continue-on-error: true` is no longer hiding a red gate — it is deferring a decision. The script is now strong enough to be blocking on the evidence: it self-tests, it fails closed on a broken scanner or an unreadable source file, and its allowlist cannot be widened without a matching entry in the registry. The PII scrubber is the precedent for a non-lint hard gate (STATUS R-…, AGENTS.md §1 item 1). Promoting it is a **gate-strengthening** change and was deliberately NOT done here, because doing it silently would take a decision away from the owner. One caveat to weigh first: 6 of the 17 exemptions are file- or package-scoped, so a tunable added inside `dev/kasoti/mrz/`, `IsoInstant.kt` or `CalendarDate.kt` would not be caught by the script. detekt would still catch it. |
| **2026-10-03** | **Eight literals were migrated onto the registry; 412 core MagicNumber literals were deliberately NOT, and each class has a recorded reason instead.** | maintainer | Migrated: `FUSE_TEMPER_FLOOR` / `FUSE_TEMPER_SLOPE` (the `fuseMatchScore` temper ramp, now on `ThresholdRegistry` so a re-tuned ramp is reachable from the verdict path), `FACE_FAR_TARGET_COARSE` / `FACE_FAR_TARGET_STRICT` (the EVAL.md §4 TAR@FAR points), `FACE_BUCKET_GAP_MAX_RATIO` (FUSION.md §6), `HALFTONE_HIGH_FREQ_RADIUS` (the Spectrum band edge), `YYMMDD_CENTURY_SLACK_YEARS`, and `MAX_PLAUSIBLE_AGE` (which decides a `FormatVerdict.FAIL`, and was therefore the one non-obvious catch). Not migrated, each with a reason in the registry's `structural` section: ICAO 9303 field geometry, the published calendar algorithm, exact unit scale factors, the `hashCode` mixing idiom, wire-codec radices, geodetic and physical constants, published format shapes, rate complements, the published DSP window, display widths, statistic definitions, SPEC §7 gate bars, feature-vector layout, capacity reserves. **Every default is byte-identical to what it was**, so no verdict changed; `ThresholdRegistryTest` and the four module suites are the evidence. |
| **2026-10-03** | **Two judgement calls inside the registry that the owner may want to overrule, both recorded in the file itself rather than only here.** | maintainer | (1) `S10` treats Spectrum's six-bin peak neighbourhood as a *definition of locality* rather than a re-tunable band, so it is exempted rather than registered; the one band choice in that file that IS a decision (`HALFTONE_HIGH_FREQ_RADIUS`) was registered. (2) `FUSE_TEMPER_FLOOR` and `FUSE_TEMPER_SLOPE` are registered as two entries rather than deriving the slope as `1 - floor`, because `0.4f != 1f - 0.6f` in binary32 and deriving it would have silently shifted every fused score. Their sum is asserted by a `sumToOne` constraint at registry load, which is the ONE new fail-closed path this work adds — it cannot trigger with the shipped values, and `ThresholdRegistryTest` covers it. |
| **2026-10-03** | **The registry ships as a JSON file PLUS a verbatim string constant in `ThresholdSpecSource.kt`, kept identical by a test.** | maintainer | `:core`'s `commonMain` has no `expect`/`actual` today and cannot read a file on every target — and its Android target is wired only when an SDK is present, so an `actual` would have to be written for a target nobody here compiles. So the text travels as a constant and `ThresholdSpecFileTest` (jvmTest) asserts the two are character-for-character identical, failing loudly if they drift. The JSON is therefore the only file a maintainer edits. **The obvious risk of this shape is that both files are untracked** — see STATUS R-O. |
| | ~~ML Kit bundled OCR~~ — now **compiled**, still **never run** | | see STATUS R-C. The app packages; no OCR call has ever executed. Its bundled-model offline use under the Google Terms remains UNVERIFIED |

## 8. Handoff checklists
**Maintainer stepping away (planned or not):** every branch merged or documented · `docs/STATUS.md` §7 rewritten to the new state, not appended to · every open `R-` in `STATUS.md` §5 given a one-line verdict · the decision log (§7) current · secrets rotated (`scripts/provision.sh` keys live outside the repo — see the exFAT warning in §6) · the embedder licensing question (STATUS R-A) either answered in writing or explicitly still open.
**Receiving the project:** onboarding §3 · read `STATUS.md` first and believe it over the other docs · re-run `:core:jvmTest` and `:eval:run --args="smoke"` before changing anything · do not trust a number in a doc that does not cite an `eval/runs/<run-id>/` (AGENTS.md §5).
