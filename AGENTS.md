# KASOTI — AGENTS.md (working agreement for humans + coding agents)

> If you are an AI coding agent: obey this file first. If a request conflicts with it, say so and propose the compliant alternative.

## 1. Commands (must stay true; update file when they change)
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # Gradle needs JDK 17
# ⚠️ ON A FRESH CLONE, TYPE `sh gradlew …`, NOT `./gradlew …`. Git records all 340 tracked
#    files as mode 100644 — including `gradlew` and every `scripts/*.sh` — because the
#    authoring volume is exFAT and cannot hold the exec bit (STATUS R-Q). `./gradlew` on a
#    fresh clone returns `permission denied` (exit 126), which is what killed both CI runs
#    so far (STATUS R-J). One `git update-index --chmod=+x gradlew scripts/*.sh` fixes it
#    locally; the real fix is committing the mode bit, which needs a non-exFAT clone.
./gradlew :core:jvmTest                    # fast logic tests (run before every push) — 459 tests
./gradlew :core:allTests                   # all core targets (JVM is the only one configured)
./gradlew :ui:test                         # 55 tests; NOT SDK-gated
./gradlew :platform:jvmTest                # 137 tests
./gradlew :app-desktop:test                # 206 tests
./gradlew :eval:test                       # 39 tests
# 896 Gradle tests total, 0 failures — core 459 · platform 137 · app-desktop 206 · eval 39 · ui 55.
# Measured 2026-10-03 in the source checkout on branch `main` (HEAD `9a04f40` + a heavily
# dirty working tree) with `--rerun-tasks`; all five test tasks executed, BUILD SUCCESSFUL.
#   count command: sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test \
#                    :eval:test --rerun-tasks
#   count read from: <module>/build/test-results/**/TEST-*.xml (tests/failures/skipped attrs)
# ⚠️ USE `--rerun-tasks`, NOT `--rerun`. `--rerun` is a per-task option: a trailing
#    `--rerun` re-runs ONLY the last task on the line and leaves the rest UP-TO-DATE, so
#    `--rerun` alone silently reports a stale result for four of the five modules.
# 0 skipped here because `eval/models/blazeface_short.tflite` is already fetched in this
# checkout. On a clean clone exactly 1 test self-skips (DetectorParityReferenceTest: the
# .tflite is git-ignored); after `sh scripts/fetch_models.sh` it runs.
./gradlew :app-desktop:run                 # headless post-console, works from a checkout
./gradlew :app-desktop:installDist         # -> app-desktop/build/install/kasoti/ (model ships inside)
./gradlew :eval:run --args="smoke"         # fixtures smoke (~60 s) — EXITS 4, see below
./gradlew :eval:run --args="full"          # full corpora — EXITS 4, see below
./gradlew :eval:run --args="parity"        # Android-vs-desktop; skips loudly without a device
# --- Lint tasks EXIST and are wired (root build.gradle.kts applies both plugins to
#     :core :platform :app-desktop :eval :ui). They are NOT yet blocking.
#     `:app-android` is deliberately NOT in the linted module list. ---
./gradlew ktlintCheck                     # ktlint 1.5.0 via plugin 12.3.0, ktlint_official style
./gradlew detekt                           # detekt 1.23.8, default ruleset + no config file
# TODO(M2,@build): make those two BLOCKING. Both are red on day one and neither is wired
# into `check`, on purpose — see docs/STATUS.md §4.3. To finish, exactly three things:
#   (1) ktlint: `./gradlew ktlintFormat` rewrites the whole tree at once (4 267 findings, the
#       large majority of them in files that are not the formatter's to touch in the same PR).
#       Give the reformat its own commit, review it as a diff with no behaviour claim, then
#       re-run. (2) detekt: 1 240 weighted issues and 889 of them (71.7%) are MagicNumber —
#       the literals AGENTS.md §2 wants in fusion/thresholds.v1.json. **That file now
#       exists** (schemaVersion 1, 49 thresholds, plus a `structural` section declaring the
#       classes of number that are provably NOT tunables), and `ThresholdName` is names-only
#       over it — which is why core's MagicNumber count fell 527 -> 412 while the *total*
#       fell only 1 349 -> 1 240. Fix the registry first, then triage the rest; the registry
#       step is done, the triage is not. Do NOT switch off a rule or commit a baseline to get
#       to green: that is weakening a gate (AGENTS.md §5, §8). (3)
#       Once both are clean, delete the `afterEvaluate` block in build.gradle.kts that
#       detaches them from `check` so `./gradlew check` lints again.
# Counts re-measured 2026-10-03 (UTC 2026-10-02T19:4xZ) in the source checkout:
#   detekt -> exit 1, all 5 module tasks fail, 1 240 weighted issues
#     (core 592 · eval 470 · app-desktop 88 · platform 79 · ui 11)
#     (MagicNumber 889 · MaxLineLength 116 · ReturnCount 77 · CyclomaticComplexMethod 26)
#   ⚠️ detekt counts LITERALS and `check_no_magic_thresholds.sh` counts LINES, so the two
#      never agree on a total for the same code. detekt also has no notion of a structural
#      exemption, which is why ~412 core literals are still red there and green in the
#      script. Both readings are direction of travel, not a pass.
#   Baseline for the registry work, same tree, before it: 1 349 weighted issues,
#     core 701 · eval 470 · app-desktop 88 · platform 79 · ui 11
#     (MagicNumber 1 004 · MaxLineLength 116 · ReturnCount 77 · CyclomaticComplexMethod 26)
#   ktlintCheck -> exit 1, 4 267 violations, 12 of 35 ktlint Check tasks fail
#     (core 1 854 · eval 987 · app-desktop 792 · platform 341 · ui 293)
#     (standard:function-signature 1 287 · multiline-expression-wrapping 1 082 ·
#      argument-list-wrapping 816 · class-signature 302)
# ⚠️ `ktlintCheck` WITHOUT `--continue` STOPS AT THE FIRST FAILING TASK and under-reports:
#    it printed 3 130 (missing every `:core`/`:platform`/`:app-desktop` test-source-set task
#    and both `.kts` script tasks). Always measure it as `./gradlew ktlintCheck --continue`.
# ⚠️ These are pinned to a WORKING TREE, not a commit — `git status` is heavily dirty and
#    other agents are editing. Always say that when you quote them, and expect drift.
# ./gradlew bundleOffline        -> never an AGP task in this tree; there is no bundle.
# --- Android. The SDK gate is real but an SDK IS present on the dev box today
#     (`local.properties` sdk.dir=, ANDROID_HOME=/opt/android-sdk), so `:app-android` IS in
#     the build — `sh gradlew projects` lists all six projects. It compiles and packages. ---
sh gradlew :app-android:assembleDebug     # OK — 2 APKs: arm64-v8a 33.39 MB, armeabi-v7a 26.56 MB
sh gradlew :app-android:assembleRelease   # OK — 2 APKs: arm64-v8a 22.97 MB, armeabi-v7a 16.14 MB
sh gradlew :app-android:checkApkSize      # OK — the 35 MB gate; measures EVERY artefact
# ABI splits ship `armeabi-v7a` + `arm64-v8a` only. No universal APK, no `x86`/`x86_64`.
# ⛔ sh gradlew :app-android:bundleRelease  — KNOWN BROKEN, exit 1, and there is therefore
#    NO .aab and no Play Store bundle: AGP 8.9.2 cannot apply `splits.abi` and bundle in
#    the same module (`:app-android:buildReleasePreBundle` -> "Sequence contains more than
#    one matching element", PerModuleBundleTask.getResourcesFile). Reproduced with
#    isUniversalApk both false and true. Delivery is APK-only until AGP is upgraded.
# ⛔ sh gradlew :app-android:test           — KNOWN BROKEN, exit 1, 160 compile errors, all
#    in the two test files under `app-android/src/test/java/dev/kasoti/android/ml/`
#    (BlazeFaceDesktopParityTest.kt 150, BlazeFaceInputTest.kt 10). They reference
#    `:platform`'s JVM classes, which the Android variant cannot see. Pre-existing since
#    commit 06bd654 and unrelated to the build fixes; `verify-offline.sh` covers them.
# On a fresh clone these need `sh gradlew` too — same 100644 cause as `gradlew` itself.
sh app-android/tools/verify-offline.sh    # SDK-free proof — exit 0, 201/201
sh scripts/airplane_install_test.sh       # exit 1: passes the APK step, then refuses on the
                                           # 2 unpopulated placeholder hashes in
                                           # scripts/bundle_manifest.sample.txt (by design).
                                           # With --skip-bundle it reaches the device step
                                           # and exits 2 (INCOMPLETE — no adb, no device).
```
**`:eval:run` exits 4, not 0, whenever a required suite is skipped** — and the device gates are
always skipped without a device. 4 = INCOMPLETE, which is the correct answer, not a broken
build. A run that returned 0 would imply a device was attached. Exit codes: 0 pass · 2 gate
failed · 3 INVALID (report-split tuning) · 4 INCOMPLETE. **Those are the *harness's* codes, not
gradlew's:** gradlew runs it in a child JVM, prints the child's code on stderr
(`> Process '…java'' finished with non-zero exit value 4`) and then exits **1** itself, so
`./gradlew :eval:run …; echo $?` prints 1, never 4. Anything reading these codes has to parse
that stderr line — `.github/workflows/ci.yml` does, and treats an unreadable code as a failure.

CI (`.github/workflows/ci.yml`, **860 lines** as of 2026-10-03) runs on every push: build +
all five modules' tests + eval smoke + lint + `:core` network-import ban + PII-scrubber test +
the SDK-free Android proof + a per-ABI APK size check. **Five facts about it that this file
must not paper over.**

1. There are now **three** different dispositions among the gates, and conflating them is how a
   real regression gets filed under "known red":
   · **`ktlintCheck` and `detekt` are ADVISORY, still red, and must not be touched** (TODO
     above). They carry `continue-on-error: true` because both default rulesets are red on day
     one — thousands of ktlint formatting findings and ~1.2k detekt issues. That is a backlog,
     not a decision.
   · **The magic-threshold step (`scripts/check_no_magic_thresholds.sh`) is a HARD GATE as of
     2026-10-03** — `continue-on-error: true` removed, a finding fails the build (decision,
     date and reason in `docs/HANDOFF.md` §7). It was green when it was promoted (measured
     2026-10-03: `sh scripts/check_no_magic_thresholds.sh` → exit 0, 69 files scanned,
     0 findings), so the flag was no longer absorbing a red backlog — it was deferring a
     decision about a gate that was ready: the script self-tests, fails closed on a broken
     scanner or an unreadable file, and cross-checks its allowlist against
     `fusion/thresholds.v1.json` in **both** directions so neither file can be widened alone.
     A second CI step proves on every run that it can fail: it injects a literal into a
     sandbox copy of the tree and requires a non-zero exit (`magic-thresholds-negtest`).
     ⚠️ **Blocking is not the same as complete, and this gate does NOT close the
     magic-number problem.** 7 of the 14 script-enforced exemptions match on *path* rather
     than on the line — 17 file paths under `mrz/`, `time/CalendarDate.kt`,
     `diary/IsoInstant.kt`, `json/*`, `diary/Ulid.kt`, `crypto/Primitives.kt`,
     `factory/ModelJson.kt`, `evalmetrics/MetricJson.kt`, `checks/Verhoeff.kt`,
     `log/RedactionPolicy.kt`, `diary/EmbModel.kt`, `factory/Spectrum.kt`,
     `evalmetrics/{Percentiles,Classification}.kt`, `evalmetrics/Gate.kt` — where an injected
     literal produces exit 0. detekt would catch those and is **advisory**, so there is **no
     automated net** for a magic number in those files: a reviewer still must. The list and
     the measurement are in the script's own header; do not read green here as "no magic
     numbers".
   · **The PII-scrubber step is also a hard gate**: it carries no `continue-on-error`, and it
     fails the build if the scrubber is absent, broken, or silently running zero tests.
     (Measured 2026-10-03: `sh scripts/pii_scrubber_test.sh` exits 0 — 7 scrubber suites
     green, 111 tests.)
2. The eval-smoke step honours the harness's own exit codes, so **exit 4 does not fail the
   build** (2 and 3 still do).
3. `:app-android` is skipped entirely without an SDK, which the job summary reports as SKIPPED
   rather than passed. **With** an SDK, the size step delegates to `:app-android:checkApkSize`
   rather than `:app-android:bundleRelease`, because `bundleRelease` no longer runs (ABI splits
   × AGP 8.9.2 — see above). There is consequently **no `.aab` and no Play Store bundle** from
   this build; delivery is APK-only.
4. **CI has run, twice, and both runs FAILED — at the first step, for a reason that has nothing
   to do with the code.** A remote does exist (`origin` =
   `https://github.com/Leo-Expose/Kasoti.git`, `main` in sync with `origin/main`); what is
   missing is a green run. Both runs died with `./gradlew: Permission denied` /
   `##[error]Process completed with exit code 126.` because git records every tracked file as
   mode `100644` (the warning at the top of this section), so on a fresh Linux runner `gradlew`
   is not executable. `docs/STATUS.md` R-J has the run ids; R-Q has the cause.
5. **Until that is fixed, no statement of the form "CI is green" is sayable — and "CI has never
   run" is equally false.** No Kotlin in this repository has ever been compiled by CI, and no
   gate in this file has ever been enforced by anything except the maintainer.


## 2. Repo conventions
- Kotlin only for product code (common + platform). Python allowed ONLY in `eval/tools/` (corpus/model-export scripts), pinned reqs, no runtime role.
- One ThresholdRegistry (`core/src/commonMain/.../fusion/thresholds.v1.json`). Adding a tunable? Add it there with: name, default, unit, tuning-data ref, owner. Magic numbers elsewhere = review fail.
- Every new check ships with: (a) unit/property tests incl. adversarial cases, (b) eval-fixture entries, (c) FUSION.md row (RED/AMBER/GREY contribution), (d) failure-mode note in THREAT_MODEL.md if spoofable.
- File sizes: keep `:core` files <400 lines; split by package. No `Util.kt` dumping grounds.
- Errors: typed `Finding(code, severity, evidenceRef, messageKey)` — never raw strings to UI; every code has Hin/Eng strings.
- Time: UTC ISO-8601 everywhere; device-clock skew flagged at sync (>5 min → warning in merge report).
- IDs: `evt_<ulid>`, `case_<ulid>`; never sequential-only across devices (seq is per-device + device-id composite).

## 3. Definition of Done (every task)
1. Tests added + green (`:core` ≥80% on touched packages).
2. Eval impact run (`smoke` min; note metric deltas in PR).
3. Thresholds (if any) registered + versioned.
4. Strings added (Eng+Hindi; Nepali-todo tag if P1).
5. Docs touched: SPEC/DESIGN/FUSION/EVAL/THREAT as applicable (PR lists files).
6. Demo-mode + airplane behavior considered (new feature must not need network).
7. Review by owner of touched area (HANDOFF.md) + lint clean.

## 4. Review checklist (reviewer pastes verdict)
- [ ] Verdict path has zero network (trace it).
- [ ] No PII in logs/caches (run scrubber test with new fields).
- [ ] GREY/fail-closed on low quality (no silent default-pass).
- [ ] Thresholds registered, not hardcoded.
- [ ] Tests include at least one adversarial/mutated case.
- [ ] Metrics claim? Only with harness command + output attached.
- [ ] Size impact noted (APK/bundle delta).
- [ ] i18n keys added; RTL/large-font not broken (screenshot for UI PRs).

## 5. Forbidden patterns (CI or review blocks)
- Network clients in `:core` (grep-ban: okhttp, ktor-client, HttpURLConnection, sockets).
- `System.currentTimeMillis()` for security decisions without skew note; crypto hand-rolled (use JCA).
- Storing raw live-face bitmaps by default; logging names/DOBs/embeddings.
- Hand-typed accuracy numbers in docs/slides (must cite `eval` run id).
- New dependencies without 5-line ADR in the PR (what/why/size/license/alternative).
- `// TODO` without owner + milestone (`// TODO(M2,@name): …`).
- Commented-out code. Dead flags older than one milestone.

## 6. How to add a verification layer (recipe)
1. Write the finding codes + fusion row FIRST (FUSION.md), incl. what it misses.
2. Implement pure logic in `:core/<pkg>` with fixtures in `eval/fixtures/<pkg>/`.
3. Add `expect/actual` ONLY for platform I/O (camera bytes, keystore, TTS).
4. Wire UI evidence card (thumbnail + one-line reason + voice key).
5. Tune threshold via EVAL.md procedure; record operating point.
6. Red-team it (REDTEAM.md attack rows) before claiming it works.

## 7. Spikes (timeboxed, decision-forcing)
Template: `docs/spikes/NN-title.md` — question, options, method, timebox (≤2 d), decision matrix, decision + date + owner. Open spikes must be ≤2 per milestone. Undecided at timebox → leads pick; document why.

## 8. Agent-specific rules (AI coders)
- Work in small diffs (<300 lines) with tests; never refactor + feature in one diff.
- Never invent model weights/keys/APIs — use inventory (DESIGN.md §6) or open a spike.
- Never weaken a gate (threshold, GREY floor, auth) to make tests pass — report the conflict instead.
- Quote file+line when citing behavior; update the doc you relied on if it's wrong (docs are code).
- End each work block with: what changed, how verified (exact commands), residual risks.

## 9. Incident & conflict rules
- P0 bug (crash/data-loss/false-RED path): stop-line — fix + regression test before features.
- Scope dispute: SPEC.md tier wins; P2 needs leads' written OK in decision log (HANDOFF.md).
- Metric dispute: rerun harness; harness wins over memory.
- **Solo project (added 2026-09-30):** there is no lead and no second reviewer. §3 item 7
  ("review by the owner of the touched area") and §4 are therefore satisfied only by
  self-review, which is a *check*, not an approval — say which one you did. CI is not a
  reviewer either: it has run twice and failed both times (see §1 items 4–5), so no gate in
  this file has ever been enforced by anything except the maintainer. §7's "leads pick"
  resolves to the single maintainer, and the decision it demands must be **written into
  `docs/HANDOFF.md` §7 with a date and a reason**, because an undocumented solo decision is
  unreviewable by construction. See `docs/HANDOFF.md` §2.
