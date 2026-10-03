# KASOTI — AGENTS.md (working agreement for humans + coding agents)

> If you are an AI coding agent: obey this file first. If a request conflicts with it, say so and propose the compliant alternative.

## 1. Commands (must stay true; update file when they change)
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # Gradle needs JDK 17
# PREFER `sh gradlew …` ANYWAY — it is habit, not necessity, and it is the only form that
# works for every entry point. ⚠️ CORRECTED 2026-10-03: this section used to say git records
#    all 340 tracked files as mode 100644, that `./gradlew` returns `permission denied`
#    (exit 126) on a fresh clone, and that this "is what killed both CI runs so far". All
#    three stopped being true on 2026-10-03 (commit df6d309): `gradlew` and 8 of the 9
#    `scripts/*.sh` are now committed mode **100755**, so `./gradlew` works on a fresh Linux
#    runner. Measured 2026-10-03 at HEAD a0f5a45:
#      $ git ls-files -s | awk '{print $1}' | sort | uniq -c
#            466 100644
#              9 100755      <- gradlew + 8 scripts; the exec bit is committed
#    The 2 tracked scripts still at 100644 are `app-android/tools/verify-offline.sh` and
#    `scripts/ci_bundle_manifest_gate.sh`, which is why `sh <script>` is still the right
#    invocation for those two. The exFAT cause is still real (STATUS R-Q) — it is the reason
#    the local `ls -l gradlew` always looked right — but the symptom is fixed for `gradlew`.
./gradlew :core:jvmTest                    # fast logic tests (run before every push) — 459 tests
./gradlew :core:allTests                   # all core targets (JVM is the only one configured)
./gradlew :ui:test                         # 58 tests; NOT SDK-gated
./gradlew :platform:jvmTest                # 155 tests
./gradlew :app-desktop:test                # 206 tests
./gradlew :eval:test                       # 39 tests
# 917 Gradle tests across the five non-Android modules, 0 failures, 0 skipped —
#   core 459 · ui 58 · platform 155 · app-desktop 206 · eval 39.
# `:app-android` adds **426 test EXECUTIONS but only 142 unique tests**: the three build
#   types each run the same 20 suites over the same 8 test files, so
#   `testDebugUnitTest` 142 + `testReleaseUnitTest` 142 + `testSideloadUnitTest` 142 = 426.
#   Counting 426 as if they were 426 distinct tests overstates the suite by 3x — quote 142.
# GRAND TOTAL 1 343 executions (917 + 426) if you want one number for "all six modules".
# Measured 2026-10-03 in the source checkout on branch `main`, HEAD a0f5a45, **with an
#   Android SDK present**, via ONE command, `--rerun-tasks`, BUILD SUCCESSFUL in 2m21s,
#   132 actionable tasks all executed (none UP-TO-DATE):
#   sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test \
#                :app-android:test --rerun-tasks
#   count read from: <module>/build/test-results/**/TEST-*.xml (tests/failures/skipped attrs)
# ⚠️ `:app-android:test` is a NEW number — it was red until 2026-10-03 (see the note below).
#    It is also a moving target in this working tree: another agent was editing
#    `app-android/src/**` while this was measured. Re-measure before quoting; the five
#    non-Android module counts are the stable ones.
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
#     :core :platform :app-desktop :eval :ui, and as of 2026-10-03 to :app-android too).
#     They are NOT yet blocking. ---
./gradlew ktlintCheck                     # ktlint 1.5.0 via plugin 12.3.0, ktlint_official style
./gradlew detekt                           # detekt 1.23.8, default ruleset + no config file
# TODO(M2,@build): make those two BLOCKING. Both are red on day one and neither is wired
# into `check`, on purpose — see docs/STATUS.md §4.3. To finish, exactly three things:
#   (1) ktlint: `./gradlew ktlintFormat` rewrites the whole tree at once (5 683 findings, the
#       large majority of them in files that are not the formatter's to touch in the same PR).
#       Give the reformat its own commit, review it as a diff with no behaviour claim, then
#       re-run. (2) detekt: 1 497 weighted issues over six modules — 1 250 of them the five
#       non-Android modules — and 997 (66.6%) are MagicNumber, the literals AGENTS.md §2
#       wants in fusion/thresholds.v1.json. **That file now exists** (schemaVersion 1, 49
#       thresholds, plus a `structural` section declaring the classes of number that are
#       provably NOT tunables) and `ThresholdName` is names-only over it, which is why the
#       registry step is done. Fix the registry first, then triage the rest; the triage is
#       not. Do NOT switch off a rule or commit a baseline to get to green: that is weakening
#       a gate (AGENTS.md §5, §8). (3)
#       Once both are clean, delete the `afterEvaluate` block in build.gradle.kts that
#       detaches them from `check` so `./gradlew check` lints again.
# Counts re-measured 2026-10-03 at HEAD a0f5a45 + another agent's uncommitted
#   `build.gradle.kts` change adding `:app-android` to LINTED_MODULES. Quoting either total
#   without saying whether `:app-android` is on the list is now ambiguous, and the build
#   script says so too: no SDK => five modules, SDK present => six.
#   detekt -> exit 1, all 6 module tasks fail, 1 497 weighted issues
#     (app-android 247 · core 592 · eval 470 · app-desktop 88 · platform 82 · ui 18)
#     (MagicNumber 997 · MaxLineLength 184 · ReturnCount 102 · CyclomaticComplexMethod 30)
#     five-module subset (app-android excluded) = 1 250.
#   ⚠️ detekt counts LITERALS and `check_no_magic_thresholds.sh` counts LINES, so the two
#      never agree on a total for the same code. detekt also has no notion of a structural
#      exemption, which is why a large residue of core literals is still red there and green
#      in the script. Both readings are direction of travel, not a pass.
#   ktlintCheck --continue --rerun-tasks -> exit 1, 5 683 findings, 15 of 51 Check tasks fail
#     (core 1 921 · app-android 1 251 · eval 987 · app-desktop 787 · platform 422 · ui 315)
#     (standard:function-signature 1 751 · multiline-expression-wrapping 1 366 ·
#      argument-list-wrapping 948 · class-signature 411)
#   count command: sh gradlew ktlintCheck --continue --rerun-tasks, then sum the
#     `*.kt:LINE:COL:` lines in `<module>/build/reports/ktlint/*/*.txt` (strip ANSI first).
# ⚠️ `ktlintCheck` WITHOUT `--continue` STOPS AT THE FIRST FAILING TASK and under-reports —
#    so badly that CI's own advisory lint step (which runs bare `ktlintCheck`) printed 1 689
#    against a local 5 683 on the same day. Always measure it as
#    `./gradlew ktlintCheck --continue`, and say so when you quote a number.
# ⚠️ These are pinned to a WORKING TREE, not a commit — other agents were editing
#    `app-android/src/**` and `build.gradle.kts` throughout. Always say that when you quote
#    them, and expect drift.
# ./gradlew bundleOffline        -> still not a Gradle task in this tree, and never was.
#    PROBE IT, do not assume: `sh gradlew -q help --task bundleOffline` exits 1 (measured
#    2026-10-03). ⚠️ CORRECTED 2026-10-03: this line used to read "there is no bundle",
#    which is false and was the half that mattered — conflating a missing *task* with a
#    missing *artefact*. There IS a bundle now, 38 764 190 B (36.97 MB), and it is the
#    shipping product: `sh gradlew :app-android:bundleRelease` is BUILD SUCCESSFUL.
#    `bundleOffline` remains a declared gap (ci.yml probes for it and prints
#    "NOT IMPLEMENTED"), owned by the maintainer, STATUS §7.
# --- Android. The SDK gate is real but an SDK IS present on the dev box today
#     (`local.properties` sdk.dir=/home/leo/.sdk), so `:app-android` IS in the build —
#     `sh gradlew projects` lists all six projects. It compiles, packages and bundles.
#     ⚠️ CORRECTED 2026-10-03: this used to read "ANDROID_HOME=/opt/android-sdk". The SDK is
#     at `/home/leo/.sdk`, a WRITABLE COMPOSITE: `build-tools`, `cmdline-tools`,
#     `platform-tools` and `tools` are symlinks into `/opt/android-sdk`, which is itself
#     NOT writable, while `licenses/`, `lic.log` and `platforms/{android-34,android-35}`
#     are real local directories. `local.properties` is the only path that must be
#     unexported-and-reset-free: with `ANDROID_HOME` unset, the build resolves through
#     `sdk.dir`. ---
sh gradlew :app-android:assembleDebug     # OK — 1 universal APK, 88.72 MB container
sh gradlew :app-android:assembleRelease   # OK — 1 universal APK, 77.55 MB container
sh gradlew :app-android:bundleRelease     # OK — app-android-release.aab, 36.97 MB. THE PRODUCT.
sh gradlew :app-android:sideloadApk       # OK — 1-ABI APK for `adb install` on a real handset
                                           #   (arm64-v8a, 23.01 MB; -Pkasoti.sideloadAbi=armeabi-v7a -> 16.18 MB)
sh gradlew :app-android:checkApkSize      # OK — the 35 MB gate, 4 containers, all in budget
# ⚠️ THE GATE'S METRIC CHANGED, AND THE OLD SENTENCE WAS THE OPPOSITE OF THE TRUTH.
#   `checkApkSize` no longer measures an APK's FILE SIZE. It measures each container's
#   **compressed worst-case per-device slice** — the bytes one phone actually downloads.
#   A container is ~2.5x that, because an APK stores the per-ABI `.so` files STORED while
#   an `.aab` DEFLATEs them, so gating the container would be gating a number no device ever
#   downloads, and it would be red by construction. Measured 2026-10-03 (exit 0, 4
#   containers): `.aab` 36.97 MB file / **14.60 MB slice** (x86) · release APK 77.55 /
#   25.52 · sideload APK 23.01 / **22.91** (arm64-v8a) · debug APK 88.72 / 35.90 —
#   **ADVISORY, 0.90 MB over**, and reported, not ignored (R8 is off for debug).
# ⚠️ **THERE ARE NO ABI SPLITS.** `splits { abi { … } }` is absent from the file entirely;
#   the bundle carries all four ABIs and Play picks server-side, so `x86`/`x86_64` are
#   present and the app installs on an emulator again. "No universal APK" and "no
#   x86/x86_64" were both true of the split configuration and are now false.
# ⛔ sh gradlew :app-android:bundleRelease  — was "KNOWN BROKEN, exit 1, AGP 8.9.2 cannot
#    apply splits.abi and bundle in the same module". STALE: the module no longer uses ABI
#    splits, `bundleRelease` is BUILD SUCCESSFUL, and `.aab` is the shipping path. The AGP
#    incompatibility itself is real and is still load-bearing — it is why splits must stay
#    off (app-android/build.gradle.kts, the bundle ADR) — it is just no longer the thing
#    breaking this task.
# sh gradlew :app-android:test           — was "KNOWN BROKEN, exit 1, 160 compile errors in
#    two ml/BlazeFace*.kt files". FIXED: those two suites moved to
#    `platform/src/jvmTest/kotlin/dev/kasoti/android/ml/`, where `:platform`'s JVM classes
#    are visible, and `:app-android:test` is BUILD SUCCESSFUL — 142 tests x 3 build-type
#    variants = 426 executions, 0 failures (measured 2026-10-03, re-confirmed on a second
#    `--rerun-tasks` run). `app-android/tools/verify-offline.sh` still covers them too.
# On a fresh clone these need `sh gradlew` too — the 2 remaining 100644 scripts aside.
sh app-android/tools/verify-offline.sh    # SDK-free proof — exit 0, 204/204
sh scripts/airplane_install_test.sh       # exit 1: finds all four containers, then refuses
                                           # on the 2 unpopulated placeholder hashes in
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

CI (`.github/workflows/ci.yml`, **1 308 lines** as of 2026-10-03 at HEAD `a0f5a45`) runs on every
push: build + all five non-Android modules' tests + eval smoke + lint + `:core` network-import ban
+ PII-scrubber test + the magic-threshold gate + the SDK-free Android proof + the
per-device-slice size check. **Five facts about it that this file must not paper over.**

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
   rather than passed. **With** an SDK the size step delegates to `:app-android:checkApkSize`,
   which itself `dependsOn("bundleRelease", "assembleDebug", "assembleRelease",
   "assembleSideload")` and gates the **`.aab`**, so the green run built the shipping bundle
   (`run 37112766189`: `bundle/app-android-release.aab container = 36.97 MB … worst per-device
   slice = 14.60 MB at x86 … within budget`).
4. **CI HAS GONE GREEN ONCE — and the next run is red on a different gate. Both facts matter.**
   ⚠️ **CORRECTED 2026-10-03: this item used to read "**CI IS GREEN.** Run `37112766189` on commit
   `f6a0540` ("noir") **succeeded in 8m25s** … ⚠️ this section used to say 'CI has run, twice, and
   both runs FAILED' and 'No Kotlin in this repository has ever been compiled by CI.' Both were true
   until the exec bit landed and both are now false."** The measured history is **seven runs, five
   failures and one success**, and the shape of it is the useful part:
   · `36658909646` (2026-09-30) and `36663026772` (2026-09-30) — **exit code 126** at the very
     first Gradle step, `:core:jvmTest`, from `./gradlew` not being executable (STATUS R-J,
     cause R-Q). Fixed by commit `df6d309`.
   · `37090379579` (2026-10-03, 16m16s) — failed at `GATE: Android APKs built + per-ABI size
     check (<= 35 MB each)`. A real gate failure, not a packaging failure.
   · `37110497967` (2026-10-03, 2m43s) — failed at `PROOF: the magic-threshold gate exits
     non-zero on an injected literal`. Also a real gate failure: the negative test caught the
     promoted gate doing nothing, which is precisely what it exists to catch.
   · **`37112766189` (`f6a0540`) — ✅ success, 8m25s.** Its Android size gate printed
     `bundle/app-android-release.aab container = 36.97 MB … worst per-device slice = 14.60 MB at
     x86 … within budget`; its magic-threshold step printed `PASS — 69 file(s) scanned, 0
     unregistered literals, 14 registry-declared structural exemptions`; its PII step printed
     `PASS — 7 suite(s) reported, 111 test(s) executed, all green`.
   · **`37124132380` (`a0f5a45`, HEAD) — ❌ failure, 7m54s.** ⚠️ **it was `in_progress` when this
     table was first written on 2026-10-03 and has since finished red — do not read it as a
     pass.** It cleared 16 steps including the magic-threshold negative test, the advisory lint
     step and the Android bundle size gate, then failed at `GATE: bundle manifest integrity
     (invariant I12 / AT-12 model swap)` — the same `scripts/bundle_manifest.sample.txt`
     **2 unpopulated placeholder hashes** documented in the command block above and in
     `docs/STATUS.md` §0 row 13. **That is the known, deliberately-unpopulated gate, not a
     regression**, and it is the same condition that makes `scripts/airplane_install_test.sh`
     exit 1.
   Re-run `gh run list --limit 5` before repeating any of this; it is a moving fact, and it moved
   twice during this very documentation pass.
5. **What green does NOT mean — preserve this half.** The green run is **one run**, and HEAD is
   currently red. No `.aab` has ever been uploaded to Play (the bundle is built and measured,
   never published); no build has ever been installed on a device, so NFR-R1's 50 crash-free runs
   are still 0; `scripts/airplane_install_test.sh` still cannot exit 0 (2 unpopulated placeholder
   hashes in `bundle_manifest.sample.txt`, and with `--skip-bundle` no `adb` and no device); the
   bundle-manifest CI gate is therefore **red on HEAD**; the eval harness runs on a host JVM and
   marks itself `INCOMPLETE` (exit 4); and ktlint + detekt remain red and advisory. CI going green
   once means the gates that exist and are wired *can* be enforced end to end — it is not a
   statement about the gates that do not exist, and it is not a statement about the current
   commit.

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
  reviewer either: it is a gate runner, not a second pair of eyes, and it only enforces what
  §1 item 1 wires as blocking (magic thresholds, PII scrubber) plus the checks that run
  unconditionally. ⚠️ **CORRECTED 2026-10-03: this sentence used to say "it has run twice and
  failed both times, so no gate in this file has ever been enforced by anything except the
  maintainer."** CI is green as of run `37112766189` (§1 item 4), so the hard gates are now
  machine-enforced — but the two linters are still `continue-on-error` and no gate here judges
  whether the *code* is any good, which is what a reviewer is for. §7's "leads pick" resolves
  to the single maintainer, and the decision it demands must be **written into
  `docs/HANDOFF.md` §7 with a date and a reason**, because an undocumented solo decision is
  unreviewable by construction. See `docs/HANDOFF.md` §2.
