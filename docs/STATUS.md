# STATUS — what actually works right now

**Living document.** Rewritten often; if your change flips any line here, update it
in the same change. Last measured **2026-10-03** (UTC 2026-10-02T19:4xZ) against branch `main`
at HEAD **`9a04f40` plus a heavily dirty working tree** — `git status` shows 40 modified tracked
files and 14 untracked paths, and other agents are editing. Every row below was executed in that
session; the eval run id in §3 is the run that produced those gate numbers and was **not**
re-executed (see §4.7).

> **Re-measured 2026-10-03. This pass corrected the two largest stale claims in the document
> set: `:app-android` now COMPILES AND PACKAGES, and the test counts had drifted by 159 tests.**
> The previous pass (2026-09-30) corrected more than thirty claims that a reviewer could have
> falsified in under five minutes each. Two things the 2026-09-30 header asserted that are now
> known to be false: that `:core` had 302 tests (it is **459**), and that `:app-android` was "still
> unbuilt" (**it now produces four APKs and passes its own 35 MB size gate**).
>
> ⚠️ **This count changed *while this pass was running*, and that is the most useful thing in
> it.** The first full re-run measured `:core` at **413** (32 test classes). A second re-run, made
> after the documentation edits, measured **459** (35 classes) — the same working tree, minutes
> later. The delta is **three new suites** (`dev.kasoti.threshold.ThresholdRegistryTest`,
> `ThresholdSpecTest`, `ThresholdSpecFileTest`, +46 tests) added by another agent mid-session,
> implementing the threshold registry this very pass was documenting. Every figure in this file is
> therefore a **snapshot with a timestamp on a moving tree**, exactly as §9 says: *"a number without
> a commit and a date is a rumour."* The number asserted here is the **459** one, measured last.
> If you re-run and get something else, the tree moved — say so rather than editing the doc to
> match silently.
>
> **Every correction moved a claim toward the truth, and several moved it against our interest.**
> CI is red, `gradlew` does not run from a clone, there is no `.aab`, `:app-android:test` does
> not compile, `ktlint`/`detekt` are red, and the magic-threshold gate is red. None of those was
> softened or removed to make the project look better; each is now stated with the command that
> proves it.

> ⚠️ **The tracked tree is being edited concurrently by other agents.** These measurements are
> attributed to a **working tree**, not to a commit, because the work that produced them is
> uncommitted. Anything measured later belongs in a new dated session, not patched into a number
> without saying when it came from. Lint counts in particular are the numbers most likely to
> drift within the hour.

Authoritative docs are in `docs/`; this file only says what is *true today*, not
what is planned. Plan lives in `docs/ROADMAP.md`.

---

## 0. Delivery status — what a reviewer can run today, from a clean checkout

**This table is the one to paste into a submission.** Rows 1–12, 15 and the script rows were
executed on 2026-10-03 in the **source checkout** on Temurin/JDK 17.0.20.1 / Ubuntu **with an
Android SDK present** (`local.properties` `sdk.dir=`, `ANDROID_HOME=/opt/android-sdk`), so
`:app-android` is in the build. Rows marked *commit-pinned* come from the 2026-09-30 fresh-clone
pass at `9a04f40` and were not re-run this session. `./gradlew` is a lie on a fresh clone in
every case (§0 note) — use `sh gradlew`.

| # | Command (exactly as a reviewer would type it) | Result today | Measured |
|---|---|---|---|
| 1 | `./gradlew :core:jvmTest` | ❌ **`permission denied`** — see the warning below. Not a code fault | 2026-09-30 |
| 2 | `sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test --rerun-tasks` | ✅ **`BUILD SUCCESSFUL` — 896 tests, 0 failures, 0 errors, 0 skipped.** core **459** · ui 55 · platform 137 · app-desktop 206 · eval 39. 20 tasks executed, none UP-TO-DATE. ⚠️ **measured twice and it moved: 850 → 896 mid-pass** (`:core` 413 → 459) because another agent added the three `dev.kasoti.threshold` suites while this pass was running — see the header note. **0 skipped** because `eval/models/blazeface_short.tflite` is already fetched in this checkout; on a clean clone 1 test self-skips (`DetectorParityReferenceTest` — the `.tflite` is git-ignored; `sh scripts/fetch_models.sh` makes it run) | 2026-10-03, source checkout |
| 3 | `sh gradlew :app-desktop:run` | ✅ prints the console usage banner, exit 0 (a headless text UI, not a GUI) | 2026-09-30 |
| 4 | `sh gradlew :app-desktop:installDist` | ✅ **BUILD SUCCESSFUL** — produces a runnable distribution in `app-desktop/build/install/kasoti/` with the model inside it (see §4.5) | 2026-10-03 |
| 5 | `app-desktop/build/install/kasoti/bin/kasoti help` | ✅ runs; prints the command table and reports the model resolved *"(shipped inside this installation)"* | 2026-10-03 |
| 6 | `sh gradlew :eval:run --args="smoke"` | ⚠️ **runs, exits 4 = INCOMPLETE** — 11 pass, 1 skipped, 0 failed. Correct off-device (§3). **Not re-run this session**; the cited run id is from 2026-09-30 | 2026-09-30 |
| 7 | `sh gradlew ktlintCheck --continue` | ❌ **exit 1 — 4 267 violations across 12 of 35 failing ktlint Check tasks.** The task exists (§4.3). ⚠️ **without `--continue` it under-reports (3 130)** because Gradle stops at the first failing task | 2026-10-03 |
| 8 | `sh gradlew detekt` | ❌ **exit 1 — 1 349 weighted issues** across all 5 module tasks | 2026-10-03 |
| 9 | `sh scripts/check_no_network_in_core.sh` | ✅ **exit 0 — 93 Kotlin files scanned, zero banned constructs** (was 78 on 2026-09-30) | 2026-10-03 |
| 10 | `sh scripts/check_no_magic_thresholds.sh` | ❌ **exit 1 — 175 source lines** carry unregistered literals (was 171) | 2026-10-03 |
| 11 | `sh scripts/pii_scrubber_test.sh` | ✅ **exit 0 — PII scrubber present, 7 suites green, 111 tests executed.** ⚠️ this row said "PII scrubber NOT IMPLEMENTED YET, exit 1" on 2026-09-30. `core/.../log/PiiScrubber.kt` exists and the gate is now **blocking in CI** (§4.1) | 2026-10-03 |
| 12 | `sh scripts/fetch_models.sh` | ✅ **exit 0 — every requested model present and SHA-256 verified** | 2026-10-03 |
| 13 | `sh scripts/airplane_install_test.sh` | ⚠️ **exit 1 — and NOT on size.** It passes step A (it finds all four APKs and hashes the release arm64 candidate), then step B **refuses**: `scripts/bundle_manifest.sample.txt` still holds 2 unpopulated placeholder hashes. That is by design and pre-existing; the file's own header says a green run is impossible until real model hashes exist. `sh scripts/airplane_install_test.sh --skip-bundle` reaches step D and **exits 2** = INCOMPLETE (no `adb` on PATH, no device) | 2026-10-03 |
| 14 | `sh gradlew :app-android:assembleDebug` | ✅ **BUILD SUCCESSFUL — 2 APKs.** `app-android-arm64-v8a-debug.apk` **33.39 MB** (35 015 121 B) and `app-android-armeabi-v7a-debug.apk` **26.56 MB** (27 852 097 B). `:app-android` **is** in the build: `sh gradlew projects` lists all six projects | 2026-10-03 |
| 15 | `sh gradlew :app-android:assembleRelease` | ✅ **BUILD SUCCESSFUL — 2 APKs.** `…-arm64-v8a-release-unsigned.apk` **22.97 MB** (24 089 671 B), `…-armeabi-v7a-release-unsigned.apk` **16.14 MB** (16 926 647 B) | 2026-10-03 |
| 16 | `sh gradlew :app-android:checkApkSize` | ✅ **BUILD SUCCESSFUL — the 35 MB gate, 4 artefacts measured, all within budget.** It requires an APK for each shipped ABI in *both* variants (a missing ABI is a failure) and fails if **any** measured artefact exceeds 35.0 MB | 2026-10-03 |
| 17 | `sh gradlew :app-android:bundleRelease` | ⛔ **exit 1 — KNOWN BROKEN.** `:app-android:buildReleasePreBundle` FAILED with `Sequence contains more than one matching element.` (AGP 8.9.2 `PerModuleBundleTask.getResourcesFile`). **There is no `.aab`** and this build **cannot produce a Play Store bundle**. Delivery is APK-only | 2026-10-03 |
| 18 | `sh gradlew :app-android:test` | ⛔ **exit 1 — KNOWN BROKEN, 160 compile errors**, all in `app-android/src/test/java/dev/kasoti/android/ml/BlazeFaceDesktopParityTest.kt` (150) and `…/ml/BlazeFaceInputTest.kt` (10). They reference `:platform`'s JVM classes (`dev.kasoti.platform.*`, `BlazeFaceAnchors`, `RgbImage`, `ImageIoImaging`) that the Android variant cannot see. Pre-existing since commit `06bd654`; `app-android/tools/verify-offline.sh` covers these two suites on a bare JVM | 2026-10-03 |
| 19 | `sh app-android/tools/verify-offline.sh` | ✅ **exit 0 — 201 tests started / 201 successful / 0 failed**, plus a tier-2 stub-compile of the Android-facing sources. SDK-free | 2026-10-03 |
| 20 | `sh gradlew :core:allTests` | ✅ exists; JVM is the only configured target, so it is `:core:jvmTest` under another name | 2026-09-30 |

> ### ⚠️ Row 1 is the single most important line in this file, and it is a **packaging** bug, not a code bug.
>
> **Git records all 340 tracked files as mode `100644` — including `gradlew` and every
> `scripts/*.sh`** — because the authoring volume is **exFAT**, which cannot represent the exec
> bit. Measured 2026-09-30:
>
> ```
> $ ls -l gradlew                          # the working tree — looks fine
> -rwxr-xr-x 1 leo leo 8733 gradlew
> $ git ls-files -s | awk '{print $1}' | sort | uniq -c
>       340 100644                          # not one 100755 in the tree
> $ ls -l gradlew                          # a fresh clone — what a reviewer gets
> -rw-r--r-- 1 leo leo 8733 gradlew
> $ ./gradlew --version
> zsh:1: permission denied: ./gradlew
> ```
>
> The working tree *always* looks correct on exFAT, so this is invisible locally and appears
> on every machine that clones. It is **R-Q**, and it is the sole reason both CI runs are red
> (§5, R-J). Two fixes, in order of preference: commit the mode bit (`git update-index --chmod=+x
> gradlew scripts/*.sh` — needs a non-exFAT clone), or make CI invoke `sh gradlew`. Until one
> happens, **every `./gradlew` command in this doc set is a lie on a fresh clone** and row 2 is
> the form that actually works. Fixing this is the cheapest, highest-value item in the project —
> it is one commit and it unblocks every automated check in existence.

**What a reviewer cannot run today, stated plainly:** the Android app **on a device** (it
compiles and packages — §0 rows 14–17 — but has never been installed or run, and there is no
device), `:app-android`'s own JVM test task (§0 row 18), a `.aab` or a Play Store bundle (§0 row
17), a signed release build, a bundle-verified offline artefact (`scripts/bundle_manifest.sample.txt`
is unpopulated, so `verify_bundle.sh` and the airplane test refuse by design), the Android
desktop *device* parity check, a macro or face accuracy figure (no calibration, no D-MACRO media,
no embedder — §3), and a green `ktlint`/`detekt`/`check_no_magic_thresholds`.

> **Why the lint numbers are quoted from the working tree and re-measured each pass.** `ktlintCheck`
> and `detekt` return different totals from run to run because other agents are editing the tree
> while they run — on 2026-09-30 the same day read 3 878 / 1 313 in the working tree and 3 840 /
> 1 309 from a fresh clone of `9a04f40`. On 2026-10-03 the working tree read **4 267 / 1 349**,
> reproduced identically across two consecutive `ktlintCheck --continue` runs. **A lint count
> with no date and no tree attached is not a measurement, it is a snapshot of whoever was typing
> at the time.** Read them as "red, of this order of magnitude", never as a pinned constant.
>
> ⚠️ **Methodological trap, and it is the reason a naive ktlint count under-reports by ~1 100.**
> `sh gradlew ktlintCheck` **stops at the first failing task**, so it printed **3 130** violations
> and missed every `:core`/`:platform`/`:app-desktop` test-source-set task and both `.kts`
> script tasks. `sh gradlew ktlintCheck --continue` runs all of them and prints **4 267**. Always
> measure with `--continue`.

---

## 1. Commands that work right now

Measured 2026-10-03 on Ubuntu, Temurin/JDK 17.0.20.1,
`JAVA_HOME=/usr/lib/jvm/java-17-openjdk`, branch `main` at HEAD `9a04f40` **plus a dirty working
tree**, **with an Android SDK present** (`local.properties` `sdk.dir=`, `ANDROID_HOME` and
`ANDROID_SDK_ROOT` both `/opt/android-sdk`), so `:app-android` **is** in the build — see
`settings.gradle.kts:52-65`. **`:ui` *is* in the build regardless of the SDK**
(`settings.gradle.kts:50`). Rows still marked 2026-09-30 were not re-executed this session and
say so. §0 is the reviewer-facing table; this is the detailed one, with provenance.

| Command | Result, measured 2026-10-03 unless the row says otherwise |
|---|---|
| `sh gradlew :core:jvmTest` | ✅ **BUILD SUCCESSFUL — 459 tests, 0 failures, 0 errors, 0 skipped across 35 test classes.** Re-run with `--rerun-tasks`, not read off an up-to-date cache. ⚠️ **needs `sh gradlew` on a fresh clone** (§0) |
| `sh gradlew :core:allTests` | ✅ exists; the JVM target is the only one configured, so it is `:core:jvmTest` under another name |
| `sh gradlew :ui:test` | ✅ **BUILD SUCCESSFUL** — 55 tests, 0 failures |
| `sh gradlew :platform:jvmTest` | ✅ **BUILD SUCCESSFUL** — 137 tests, 0 failures |
| `sh gradlew :app-desktop:test` | ✅ **BUILD SUCCESSFUL** — 206 tests, 0 failures |
| `sh gradlew :eval:test` | ✅ **BUILD SUCCESSFUL** — 39 tests, 0 failures (also re-measured on its own with a bare trailing `--rerun`) |
| **all five together** | ✅ **BUILD SUCCESSFUL — 896 tests, 0 failures, 0 skipped** (`sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test --rerun-tasks`; 20 actionable tasks, 20 executed). Counts read from `<module>/build/test-results/**/TEST-*.xml`. ⚠️ **an earlier identical command in this same session read 850** (`:core` 413) — the tree gained three `:core` suites mid-pass. If your number differs from 896, re-measure before assuming either is wrong. ⚠️ **use `--rerun-tasks`, not `--rerun`**: a trailing `--rerun` re-runs only the last task and leaves the other four `UP-TO-DATE`, so it silently reports stale XML for four of five modules. On a clean clone expect **1 skipped** instead of 0 (`DetectorParityReferenceTest`, model-conditional — see §0) |
| `sh gradlew :app-desktop:run` | ✅ runs, prints the console usage banner, exit 0 (a headless text UI, not a GUI) |
| `sh gradlew :eval:run --args="smoke"` | ⚠️ **runs, exits 4 = INCOMPLETE** — 12 gates: 11 pass, 1 skipped, 0 failed. Exit 4 is the *correct* code: the device gate cannot run on a host (§3). **Not re-run 2026-10-03**; the run id cited in §3 is from 2026-09-30 |
| `sh gradlew :app-desktop:installDist` | ✅ **BUILD SUCCESSFUL** — see §4.5. **This was "does not exist" in the 2026-09-29 revision of this file and that was wrong** |
| `sh gradlew :app-desktop:distZip` / `distTar` | ✅ exist (the `application` plugin's tasks) |
| `app-desktop/build/install/kasoti/bin/kasoti help` | ✅ **runs and executes the packaged launcher**; prints the command table and the resolved model path with its origin — measured 2026-10-03 as *"…/share/kasoti/models/svm_print_v1_synthetic.json (shipped inside this installation)"* |
| `sh scripts/check_no_network_in_core.sh` | ✅ **green — 93 Kotlin files scanned, zero banned constructs** (exit 0). ⚠️ measured 78 on 2026-09-30 and 75 before that; the count moves as `:core` grows |
| `./scripts/check_no_magic_thresholds.sh` | ❌ **red (exit 1) — 175 source lines** carry unregistered numeric literals in `:core` product code (measured 2026-10-03; 171 on 2026-09-30). `dev/kasoti/threshold/` is exempt, since it embeds the registry file verbatim |
| ~~`./scripts/pii_scrubber_test.sh`~~ | ✅ **GREEN as of 2026-10-03 — exit 0, 7 suites, 111 tests.** ⚠️ this row said "red (exit 1) — PII scrubber NOT IMPLEMENTED YET in `:core`" on 2026-09-30; `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt` and its seven suites now exist and the CI step is **blocking** (§4.1) |
| `./scripts/fetch_models.sh` | ✅ **green (exit 0) — 1 model present and SHA-256 verified** (`blazeface_short.tflite`, 229,746 B, `b4578f35…`). `--record` independently reproduces the pinned digest. See §2.1. |
| `./scripts/fetch_models.sh scripts/models_manifest.sample.tsv` | ✅ **green by refusing** — the sample's `REPLACE_ME` fields abort before any network access. The refusal path still works. |
| `./scripts/verify_bundle.sh` | ✅ **works, correctly red (exit 1)** — refuses the unpopulated `bundle_manifest.sample.txt` rather than reporting a green "verified" bundle |
| `./scripts/provision.sh` | ✅ works (exit 0); 0600 secret, never printed, refuses to write inside the repo. **Not re-executed in this session** — see §4.7 for what that means |
| `./scripts/purge_volunteer_data.sh <id> --dry-run` | ✅ works (exit 0) — reports "already clean" and re-runs smoke. Exit 2 with no argument is the usage error, by design. Measured with a deliberately non-existent id |
| `app-android/tools/verify-offline.sh` | ✅ **exit 0 — 201 tests started / 201 successful / 0 failed**, plus a tier-2 stub-compile of the Android-facing sources. SDK-free. Run with `sh` on a fresh clone. ⚠️ the script's own tier-2 summary line still says aapt2/d8/R8/Compose and the real SDK signatures "are all still unverified" — that text is now **half wrong**, because `assembleDebug`/`assembleRelease` have run all of them. The **script** is the stale thing here, not this row |
| `sh gradlew ktlintCheck --continue` | ❌ **the task EXISTS and FAILS — exit 1, 4 267 violations across 12 of 35 failing ktlint Check tasks.** ⚠️ the 2026-09-29 revision of this file said "not runnable — the task does not exist". That was **false**: it was measured against an older commit, before the plugin was wired. ⚠️ **without `--continue` the same command prints only 3 130** — Gradle stops at the first failing task. See §4.3 |
| `sh gradlew detekt` | ❌ **the task EXISTS and FAILS — exit 1, 1 349 weighted issues across all 5 module tasks.** ⚠️ same "task does not exist" correction as ktlint. See §4.3 |
| `sh gradlew :app-android:assembleDebug` | ✅ **BUILD SUCCESSFUL — 2 APKs** (arm64-v8a 33.39 MB, armeabi-v7a 26.56 MB). **This row said "not runnable here — no Android SDK" on 2026-09-30, and that is no longer true**: an SDK is present, `sh gradlew projects` lists all six projects, and `compileSdk` is 35 |
| `sh gradlew :app-android:assembleRelease` | ✅ **BUILD SUCCESSFUL — 2 APKs** (arm64-v8a 22.97 MB, armeabi-v7a 16.14 MB). R8 + `shrinkResources` on |
| `sh gradlew :app-android:checkApkSize` | ✅ **BUILD SUCCESSFUL — 4 artefacts measured, all within the 35.0 MB budget.** Requires an APK for each shipped ABI in both variants; fails if any artefact is over budget |
| `sh gradlew :app-android:bundleRelease` | ⛔ **exit 1 — KNOWN BROKEN.** `:app-android:buildReleasePreBundle` → `Sequence contains more than one matching element.` No `.aab` is produced. AGP 8.9.2 cannot apply `splits.abi` and bundle in one module |
| `sh gradlew :app-android:test` | ⛔ **exit 1 — KNOWN BROKEN, 160 compile errors**, all in `ml/BlazeFaceDesktopParityTest.kt` (150) and `ml/BlazeFaceInputTest.kt` (10): they import `:platform`'s JVM classes, which the Android variant cannot see. Pre-existing since `06bd654` |
| `sh gradlew :app-desktop:packageDistributionForCurrentOS` | ⛔ **does not exist — and never did.** The task name was wrong, not the capability: `installDist`/`distZip`/`distTar` do exist and work (§4.5) |
| `sh gradlew bundleOffline` | ⛔ **does not exist** — it was never an AGP task in this tree. (`:app-android:bundleRelease` is a real task and is broken; `bundleOffline` is not a task at all — two different facts, previously conflated here) |
| `sh scripts/airplane_install_test.sh` | ⚠️ **works; exits 1** — it now *finds* the APKs (step A lists all four and hashes the release arm64 candidate) and refuses at step B on 2 unpopulated placeholder hashes in `scripts/bundle_manifest.sample.txt`. **This row said "exits 2 — no APK/AAB build output found" on 2026-09-30; both the exit code and the reason were wrong.** With `--skip-bundle` it reaches step D and exits 2 = INCOMPLETE (no `adb`, no device) |
| `sh scripts/pii_scrubber_test.sh` | ✅ **green (exit 0) — 7 scrubber suites, 111 tests executed.** ⚠️ this row said "red (exit 1) — PII scrubber NOT IMPLEMENTED YET in `:core`" on 2026-09-30. `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt` now exists with seven suites under `core/src/commonTest/kotlin/dev/kasoti/log/`, and the CI step is **blocking**. The script's own closing note is still the right caveat: this proves the rules work, not that every call site uses them |

> **On the concurrency caveat.** It no longer applies to the tracked tree — it was clean at
> `9a04f40`, and it is heavily dirty again as of 2026-10-03. The build-cache pack error it
> describes (`Entry 'tree-classpathSnapshot' closed at …`) is a two-daemons-sharing-`build/`
> artefact; if it reappears, serialise the build or use `--no-build-cache`. **Do not "fix" it in
> product code.**
>
> ⚠️ **Every `scripts/*.sh` that shells out to Gradle needs `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
> exported, or it fails with a bare `What went wrong: 27`.** That is the default-JDK-27 failure
> mode reaching the scripts. Measured 2026-10-03: `sh scripts/pii_scrubber_test.sh` exits 1 with
> `> What went wrong: 27` when `JAVA_HOME` is unset, and exits 0 when it is set. The scripts
> document JDK 17 as a prerequisite; a maintainer reading "exit 1" without that context will
> misdiagnose it as a scrubber failure.

> **A standing build warning, not noise:** every Gradle invocation prints *"The Kotlin
> Gradle plugin was loaded multiple times in different subprojects"*. `:app-desktop`,
> `:core` and `:eval` each pin the plugin version explicitly. Harmless today, fatal later —
> see R-M.

---

## 2. What is implemented

### `:core` (pure Kotlin, no expect/actual) — substantial

**459 tests, 0 failures, 0 skipped** (`:core:jvmTest`, `--rerun-tasks`, 2026-10-03, 35 test
classes). ⚠️ **an earlier run in the same session read 413 across 32 classes** — the three
`dev.kasoti.threshold` suites landed while this pass was in progress. ⚠️ **this said 302 on 2026-09-30**; the growth is the `:core` PII-scrubber work
(`dev.kasoti.log.*`) plus earlier additions. `check_no_network_in_core.sh` now scans **93**
`:core` Kotlin files, up from 78.


| Area | State |
|---|---|
| MRZ — ICAO 9303 TD1/TD3 parse, 7-3-1 check digits, builder, seeded 10k mutate corpus | ✅ implemented + tested |
| Checks — Verhoeff, date/format validators, VIZ↔MRZ fuzzy match | ✅ implemented + tested. **The Verhoeff P-table defect is CLOSED**: `core/.../checks/Verhoeff.kt` generates the D/P/INV tables from the dihedral-group definition, `ChecksTest.kt:27-61` pins the generated tables against the published ones, and `ChecksTest.kt:65-67` asserts the published `236 → 3` worked example (both references verified against the file on 2026-09-30). `U-GATE-01` passes 19/19 (§3) |
| QR — secure-QR parse + verify over key slots, with rotation/expiry states | ✅ implemented + tested (TEST keys only — see R-B) |
| Diary — alias rule, impossible travel, facilitator rule, file-backed repo, ULID ids | ✅ implemented + tested |
| Fusion — `Finding` vocabulary, RED/AMBER/GREY rules, coverage rules, track matrix, verdict report | ✅ implemented + tested |
| Face math — cosine similarity, quality gates, detection curves, per-bucket | ✅ implemented + tested. `Detection.perBucket` has **never been run on real data** (no D-FACE) |
| Macro/factory — grayscale, spectrum (FFT radial peak), features (LBP-59), classifier | ✅ implemented + tested |
| Eval metrics — classification, FAR/FRR, mutate-catch, latency, JSON sink | ✅ implemented + tested |
| Audit — hash chain over decisions | ✅ **implemented AND tested — this row said "NOT tested" in the previous revision and that was false.** Three test files exist: `core/src/commonTest/kotlin/dev/kasoti/audit/AuditChainTest.kt`, `core/src/commonTest/kotlin/dev/kasoti/audit/AuditChainFixture.kt` and `core/src/jvmTest/kotlin/dev/kasoti/audit/AuditChainSha256Test.kt`. The old claim rested on a `grep` that matched no test *source set* path; run it correctly (`grep -rl AuditChain core/src/*Test*`) and they are there. `DESIGN.md` §8 I6 is accurate after all |
| i18n — message-key catalogue (Eng + Hin) | ✅ implemented + tested |
| Threshold registry — versioned, floor/ceiling enforced, canonical serialisation | ✅ implemented + tested. ⚠️ **CHANGED 2026-10-03: `fusion/thresholds.v1.json` NOW EXISTS** at `core/src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json` (27 831 B, `schemaVersion` 1, `version` `v1`), with **49** thresholds each carrying name / unit / default / floor / ceiling / `tuningDataRef` / `owner`, plus a `units` legend, a `constraints` array and a `structural` section declaring **17** classes of number that are provably NOT tunables (14 enforced by `scripts/check_no_magic_thresholds.sh`, which cross-checks the list in both directions; 3 review-only). The script now **passes** — exit 0, 69 files scanned, 0 findings, down from 175 — and self-tests so it cannot report a pass from a broken scanner. `ThresholdName` is now **names and nothing else** (49 entries, matching the file), `ThresholdSpecSource` embeds the file text verbatim, and `ThresholdSpecFileTest` fails if the two ever diverge. **Two caveats, both real:** the file is **untracked** (`git status` → `??`), so it is not in any commit and not in CI; and **all 49 defaults are still untuned** — they are values a human typed, not values a split chose. (R-O) |

### `:ui` (plain `kotlin-jvm` presentation state — **not** Compose)

**55 tests, 0 failures** (`:ui:test`, `--rerun-tasks`, 2026-10-03). ⚠️ this said **44** on
2026-09-30. Pure presentation state + a `FieldView` contract + a reducer.
**There is no `@Composable` anywhere in `:ui`**; `ComposeFieldView.kt` lives in `:app-android`
and **has now been compiled by AGP and the Compose compiler** (`assembleDebug`/`assembleRelease`
succeed), so the Android rendering path is real code in a shipped APK — it has simply never been
run on a device. The **desktop** renderer still does not exist, and that is the remaining gap.
`ui/README.md` §1 states the reasoning and the migration path. `DESIGN.md` D7's
"CMP shared UI" was therefore **not** taken — corrected in `DESIGN.md` §1.

### `:app-desktop` (headless post-console)

**206 tests, 0 failures** (`:app-desktop:test`, `--rerun-tasks`, 2026-10-03). ⚠️ this said **169**
on 2026-09-30; the growth includes `ConsoleModelProvenanceTest` and `ModelLocatorTest`, which
cover the model-origin reporting described below. Text CLI over `:core` + `:platform` with
commands `screen`, `override`, `verify`, `export`, `wipe`, `macro`, `sample`. Case-bundle export,
shift report, a `LogScrubber` with its own adversarial tests, and a `MacroModel`/`ModelLocator`
pair that resolve and *report the origin* of the macro model exist.

**Packaging works, and this section used to say it did not.** `sh gradlew :app-desktop:installDist`
succeeds and produces a runnable distribution at `app-desktop/build/install/kasoti/`; the
launcher `bin/kasoti` executes and prints its command table. The task name previously advertised
here, `packageDistributionForCurrentOS`, does not exist and never did — the *name* was wrong, not
the capability, and "desktop packaging has not been built" was an overstatement built on that
wrong name. Full state, including one real defect found and fixed in it, is in **§4.5**.
"Clean-machine install" (SPEC §6 M2) is now *partly* proved: the launcher runs outside Gradle,
but a clean-machine install has still not been exercised end-to-end on a machine with no source
checkout present. Re-verified 2026-10-03: `bin/kasoti help` reports the model as
*"(shipped inside this installation)"*.

### `:platform` (expect/actual — **JVM implementations only**)

`src/` contains `jvmMain` and `jvmTest` and nothing else. 137 tests, 0 failures.

JCA crypto primitives · RGB/gray imaging + ImageIO · OCR engine interface with a
manual fallback and a Tess4J implementation · model loader with hash checking ·
embedding-model interface · macro vocabulary · **desktop TFLite detector**
(`ml/tflite/`: `TfliteRuntime`, `TfliteBlazeFaceDetector`, `AnchorDecoder`,
`BlazeFaceContract`, `DesktopFaceLayer`).

**There are no Android `actual`s**, so `:platform` still has no Android implementation and
`:app-android` carries its own copies of the platform seams (R-P). ⚠️ **CHANGED 2026-10-03:** the
Android target of `:core` **has now been compiled** — `core/build.gradle.kts` and
`platform/build.gradle.kts` both grew a real `android { }` block (`compileSdk = 35`,
`minSdk = 26`) when the SDK gate opened, so this sentence's "has never been compiled" is no
longer true for `:core`. What is still true: neither `:core` nor `:platform` has an
`androidMain` source set, and `:platform` has **no Android `actual` implementations**.

### `:app-android` (CameraX field app) — **COMPILES AND PACKAGES as of 2026-10-03**

This is the single largest correction in this file's history. Until 2026-09-30 the module had
**never been compiled by AGP**, and roughly a dozen docs said so.

**Root cause, found and fixed 2026-10-03 — one character.** `app-android/build.gradle.kts`
line 1 began with `#`:

```
-# KASOTI — :app-android (DESIGN.md §1, §3; BUILD.md §3; AGENTS.md §1)
+// KASOTI — :app-android (DESIGN.md §1, §3; BUILD.md §3; AGENTS.md §1)
```

`#` is a **Groovy** comment character. In a Kotlin script (`.kts`) the Kotlin script compiler
parses it as source, not as a comment, so the module never configured. Exactly **one** `#` existed
in the file, on that line (`git diff` confirms the single-line change). Nothing about the module's
32 `main` Kotlin files, its resources, its manifest, its CameraX/Compose/ML-Kit/TFLite usage or
its `plugins {}` block was wrong — the build file simply could not be read.

**What was also needed, and was done:**

| Change | Why | Where |
|---|---|---|
| `compileSdk` 34 → **35** | `androidx.activity:activity-compose:1.10.1` and `androidx.core:core-ktx:1.15.0` (both already pinned in `libs.versions.toml`, neither added for this) declare `minCompileSdk = 35`, so `checkDebugAarMetadata` failed before a single Kotlin file was read. An **API migration, not a product decision.** `minSdk 26` / `targetSdk 34` **unchanged**; build-tools 35.0.0 | `app-android`, `core`, `platform` |
| `splits.abi` enabled, `isUniversalApk = false` | The single all-ABI debug APK measured **87.9 MB** and the release **77.53 MB** against a 35 MB budget — 76.6 MB of that is duplicated native code (`libmlkit_google_ocr_pipeline.so` 41.0 MB, `libbarhopper_v3.so` 20.2 MB, `libtensorflowlite_jni.so` 15.2 MB across four ABIs), and neither R8 nor `shrinkResources` can shrink a prebuilt `.so` | `app-android/build.gradle.kts` ADR |
| Shipping ABIs reduced to **`armeabi-v7a` + `arm64-v8a`** | `x86_64` measures **35.95 MB**, i.e. **over** the 35 MB budget. `x86` buys nothing (no physical handset KASOTI targets is x86, and Google no longer publishes 32-bit x86 system images). Dropping them is a **device-coverage** decision and is written down as one | same |

**Measured artefacts — `sh gradlew :app-android:assembleDebug` / `assembleRelease`
(2026-10-03).** Budget 35.0 MB, unchanged, and not moved to make a number look better:

| Artifact | Bytes | MB | vs 35 MB |
|---|---|---|---|
| `app-android-arm64-v8a-debug.apk` | 35 015 121 | **33.39** | within, **1.61 MB headroom** |
| `app-android-armeabi-v7a-debug.apk` | 27 852 097 | **26.56** | within |
| `app-android-arm64-v8a-release-unsigned.apk` | 24 089 671 | **22.97** | within |
| `app-android-armeabi-v7a-release-unsigned.apk` | 16 926 647 | **16.14** | within |

The R8 delta between each debug/release pair (−31.2 % arm64, −39.2 % armeabi-v7a) is the proof
that enabling splits did **not** bypass shrinking. Per-device download drops from 87.9 MB to
22.97 MB (release arm64) / 16.14 MB (release armeabi-v7a).

**`checkApkSize` is a real gate and it is stricter than it was.** `sh gradlew
:app-android:checkApkSize` → `BUILD SUCCESSFUL`, "4 artefact(s) measured, all within 35.0 MB". It
**requires** an APK for every shipping ABI in **both** variants (a missing ABI is a *failure*, not
a smaller number — a build that quietly stopped producing `armeabi-v7a` would otherwise make the
gate look better), **measures every** `.apk` it finds rather than a hand-picked subset, and fails
if **any** artefact exceeds the budget. `.github/workflows/ci.yml` step 8 was changed to delegate
to it.

**⛔ The one real cost, stated plainly: there is no `.aab`, and this build cannot produce a Play
Store bundle.** `sh gradlew :app-android:bundleRelease` → exit 1:

```
Execution failed for task ':app-android:buildReleasePreBundle'.
> Sequence contains more than one matching element.
    at com.android.build.gradle.internal.tasks.PerModuleBundleTask.getResourcesFile
       (PerModuleBundleTask.kt:565)
```

AGP 8.9.2 cannot apply `splits.abi` and build an app bundle in the same module. Reproduced with
`isUniversalApk` both `false` and `true`, and with AGP's default ABI set, so it is caused by
`splits.abi` being on at all. `ndk.abiFilters` is not a workaround — AGP rejects the combination
outright. The alternative (drop `splits.abi`, keep `bundleRelease`, and let the 35 MB gate go back
to red at 77.53 MB) is worse, so **delivery is APK-only until AGP is upgraded.** Resolving it
properly needs an AGP upgrade, or the Tesseract-Android capability swap, which shrinks the payload
instead of duplicating it less; neither is a packaging change.

**⛔ `:app-android:test` is still broken, and it is a separate, pre-existing defect.** exit 1,
**160 compile errors**, all in two files: `app-android/src/test/java/dev/kasoti/android/ml/BlazeFaceDesktopParityTest.kt`
(150) and `…/ml/BlazeFaceInputTest.kt` (10). They import `:platform`'s **JVM** classes
(`dev.kasoti.platform.*`, `BlazeFaceAnchors`, `RgbImage`, `ImageIoImaging`), which the Android
variant cannot see. Pre-existing since commit `06bd654`, unrelated to today's build fixes.
`app-android/tools/verify-offline.sh` compiles and runs exactly these two suites on a bare JVM
against `:core`'s classes, so the assertions are covered — **by the script, not by Gradle**.

**⚠️ Stale comments in the build file itself, deliberately not touched in this documentation
pass.** `app-android/build.gradle.kts` still carries a "VERIFICATION STATUS" header saying the
module "has **never been compiled**" and that "nothing in `src/main/java/dev/kasoti/android/{capture,platform,view}`,
no resource file, and no manifest line has been through aapt2, d8, R8 or the Compose compiler";
it still quotes "145 unit tests in this module (110 field + 35 ml)" and "189 tests" for
`verify-offline.sh` (measured **201**); and the `splits.abi` comment still says
"`scripts/provision.sh` publishes an `.aab`, which Play slices server-side" — which is false while
`bundleRelease` is broken. **This task is documentation-only and does not edit build files**, so
those are reported as found-stale, not fixed.

**What is still unmeasured about `:app-android`, and must not be implied by "it builds":** it has
never been installed on a device, never run, never screenshotted, and `checkApkSize` says nothing
about crash-freedom. NFR-R1 (50 crash-free runs) and the airplane-install proof are **still
unmet**. See R-C.


### 2.1 Face layer — measured 2026-09-30 (spike 01, `docs/spikes/01-face-model.md`)

**The face layer is half built, and which half is not the half anyone would guess.**

| | State |
|---|---|
| **Detector** | ✅ **WORKS.** `blazeface_short.tflite` (BlazeFace short-range, float16/1) obtained from Google, **Apache-2.0 verified on the model's own model card**, 229,746 B, SHA-256 pinned to a digest **we measured** (`b4578f35…`, because Google publishes no `.sha256` sidecar). Runs on desktop JVM; deterministic across repeated runs on identical bytes. Fetch with `./scripts/fetch_models.sh`; provenance in `THIRD_PARTY.md` §3. |
| **Embedding** | ❌ **LICENCE DECIDED, WEIGHTS STILL NOT OBTAINED.** A research-licensed embedder is **acceptable for the prototype only** (`docs/README.md` §License: a SIH demonstration and research evaluation is a non-commercial research context) — decision, consequences and date in `docs/spikes/01-face-model.md` §7 item 5. A **second** sourcing round after that decision checked four further candidates (AdaFace-via-PINTO, ONNX Model Zoo ArcFace, `estebanuri/face_recognition`, MediaPipe Face Embedder) and obtained nothing; two of the ecosystem's reference repos had gone 404 in the meantime. `face = null` → layer `UNAVAILABLE` → **GREEN 1:1 unreachable**. Fail-closed, by design. **The decision is a permission, not an implementation.** |
| **Android parity** | ⚠️ **PARTIAL, AND THE PART IS NAMED.** An SDK is now present, but **no device**, so **device parity is still not claimed.** What *is* checked mechanically, on a bare JVM: the Android detector's **decode** (sigmoid, 896-anchor grid, box and keypoint placement, IoU, NMS) and its **input preparation** (half-pixel-centre bilinear resize + the model's `[-1,1]` encoding) are bit-identical to `:platform`'s, asserted by `BlazeFaceDesktopParityTest` / `BlazeFaceInputTest` against the detector's **real output tensors** committed in `eval/fixtures/face/raw_outputs/`. ⚠️ **CHANGED 2026-10-03:** `TfliteFace.kt` is no longer compiled *only* against hand-written stubs — `:app-android` now builds through AGP against the real LiteRT AAR, and its `binding to InterpreterApi + InterpreterFactory` compiles. What is **still** true: it has never been executed on a device, so nothing here is evidence about what a device produces, and the two parity suites are run by `app-android/tools/verify-offline.sh` (201/201) rather than by `:app-android:test`, which does not compile (§2, `:app-android`). |
| **Desktop latency** | ⚠️ `blazeface_short` native inference **p50 ≈ 10.8 ms, p95 ≈ 20.1 ms** on the dev box, 4 threads, synthetic 128×128 input — our own probe, in `docs/spikes/01-face-model.md` §5.2, and **not** reproducible from the repo (the probe was scaffolding and is not committed, per spike §5.1 step 5). Google publishes **2.94 ms CPU on Pixel 6**. **NFR-P1's "<400 ms detect + embed" is NOT measured on a named device and is NOT claimed** (EVAL.md §8 forbids cherry-picked devices; §4 requires NAMED devices). *(An earlier revision of this line quoted "6.7–14.6 ms p50-ish" from somewhere with no run id and no provenance; it has been removed rather than restated — AGENTS.md §5.)* |

**Why there is no embedder — the one finding that matters most.** Every face-recognition
checkpoint ecosystem is trained on MS-Celeb-1M / MS1M / VGGFace2 / CASIA-WebFace, all
non-commercial-research datasets. `deepinsight/insightface` states this in its own README and
since 2025-11-24 requires an **email** to license even its open-sourced models. Two of
DESIGN §6's three named candidates (`DXG-INF/GhostFaceNet`, `deepghs/edgeface`) are **404 — the
repositories are gone**. Google's MediaPipe Face Embedder task is documented but **its weights
are not published** — the `mediapipe-models` bucket has 21 prefixes and none is
`face_embedder/`. **No weights were fabricated, approximated or synthesised, and no code path in
`:platform` can produce an embedding.**

**The licensing half is now closed and the fetching half is not.** As project lead I decided on
2026-09-30 that a **research-licensed embedder is acceptable for the prototype**, on the grounds
that `docs/README.md` §License defines the deliverable as a SIH demonstration and research
evaluation — a non-commercial research context — which is exactly what a "non-commercial research
purposes only" checkpoint licence permits. Three consequences are recorded as obligations, not
intentions, in `docs/spikes/01-face-model.md` §7 item 5:

1. **the embedder may not ship in an operational deployment** (the prototype-only grant does not
   travel; `THREAT_MODEL.md` §8 already carries the operational disclaimer);
2. **licence obligations attach to redistribution, and redistribution is a separate right from
   use** — so a candidate that permits research *use* may still forbid *redistribution*, in which
   case the artefact is provisioned outside the repo like a secret and never committed;
3. **the vendor scope caveats and the measured skin-tone (5.3 pp) and regional (7.5 pp) recall gaps
   must be disclosed rather than discovered by a judge** — the two paragraphs immediately below are
   that disclosure, and the duty extends to the embedder, which comes from the same MS1M/VGGFace2
   lineage and carries the same provenance question.

A second, focused search then ran *because* the decision made a research licence admissible. Four
further candidates were checked for their actual licence text and for whether a TFLite conversion is
possible, and **all four were rejected**: AdaFace-via-PINTO (MIT on the *conversion*; upstream
`minchul/cvlface_adaface` **404**, WebFace260M/MS1MV3 data, 512-d), ONNX Model Zoo ArcFace
(Apache-2.0 text, but `onnx/models/vision/body_analysis/arcface` is itself **404**, 512-d, ONNX,
MS1MV2), `estebanuri/face_recognition` (a genuine 22.5 MB `facenet.tflite` — and the repository has
**no licence file of any kind**, all rights reserved, at 4.5× the ≤5 MB budget), and the MediaPipe
Face Embedder (still unpublished; the bucket publishes `audio_embedder`, `image_embedder` and
`text_embedder` and no face one). **Nothing was converted, fabricated, approximated or synthesised,
and the seam is left as a clean documented `null`.** Full evidence: `docs/spikes/01-face-model.md`
§2.1 and §7 item 6.

**Two things a judge will ask, recorded before they ask.**

1. **Stated scope mismatch.** The model card lists *"any form of surveillance or identity
   recognition"* as explicitly **out of scope**, and says the model "is not intended for human
   life-critical decisions". Apache-2.0 permits our use; the authors documented the opposite
   boundary. Lead decision, §5 and spike §6.1.
2. **Documented per-slice bias (Google's numbers, not ours — EVAL.md §1 requires a run id we
   cannot supply).** Recall 98.4% across perceived gender (98.2 vs 98.5 — a **0.3 pp** gap), but
   **98.1% across skin tones spanning 94.7–100% (5.3 pp)** and **99.1% across 17 geographic
   subregions spanning 92.5–100% (7.5 pp)**. The skin-tone and subregion spreads are the numbers
   that matter for a land border, and the same model being consistent across gender while varying
   across skin tone is itself the finding. **There are NO age-bucket numbers at all**, and
   EVAL.md §4 requires gender × age-band × lighting — so two of our three required axes have no
   vendor coverage and no coverage of our own. Vendor eval sets are 720/800/350 images, stated as
   **not disjoint**, from the same source as training. `Detection.perBucket` is implemented and
   tested and has **never been run on real data**. See spike §6.2–6.4.

**Two things this work found that are not about the model.**

3. **`BUILD.md §4` was wrong about the desktop TFLite runtime, and is now corrected.**
   There is no first-party TFLite JVM artefact. Re-probed 2026-09-30 and confirmed:
   `org.tensorflow:tensorflow-lite:2.17.0`'s "jar" is **1,411 bytes of ASCII that is a verbatim
   copy of its own POM** — a `<relocation>` to `com.google.ai.edge.litert:litert:1.0.1`, which is
   `<packaging>aar</packaging>`; `2.16.1`/`2.15.0`/`2.14.0`/`2.13.0` are all AAR with **no `.jar`
   published at all**; and **no `<os>` classifier exists** (`linux-x64`, `windows-x64`, `macos-x64`,
   `macos-arm64`, `linux-x86_64`, `osx-x86_64`, `linux-aarch64` all 404). The AAR's own natives
   cannot be reused either: `readelf -d` on the `.so` files inside the real AAR shows
   `NEEDED libc.so, liblog.so, libdl.so, libm.so` and **no `libc.so.6` / `ld-linux-x86-64.so.2`** —
   Bionic sonames, and `file` reports them "for Android 21, built by NDK r25b". A desktop JVM
   resolves the versioned glibc names.
   The build runs on **`ai.djl.tflite`** (Apache-2.0), which republishes the genuine
   `org.tensorflow.lite.*` classes — `javap -c` shows the TFLite side references **zero** `ai.djl`
   classes, so the dependency flows one way — plus desktop JNI published separately. That has
   natives for **`linux-x86_64` (3,382,784 B) and `osx-x86_64` (7,073,184 B, Mach-O x86_64) only**,
   and `tflite-native-cpu` has published exactly two versions (`2.4.1`, `2.6.2`) with exactly those
   two classifiers. **`windows-x86_64` and Apple-Silicon Mac are review-only, no on-device
   inference, and no version bump fixes it.** The old §7 troubleshooting row that told you to
   "check the `<os>` classifier" was pointing at a thing that does not exist.
   Corrected consistently in `BUILD.md` §4 (rewritten), `DESIGN.md` §1/D1/§3, `ROADMAP.md` M2.2 and
   the M2-d3 tripwire, and the spike §4. New build structural risk: see R-T below.
4. **The Android detector could not run this model as written. FIXED 2026-09-30** — and it was
   worse than the four reported defects. The reported four were all real and all verified against
   the model and the desktop binding: output array allocated as `[1, 160, 1]` against a
   `[1, 896, 16]` tensor; the raw logit used as a confidence with **no sigmoid** although the model
   has no `SOFTMAX`; a single-output `run` for a two-output model; and no anchor decode before
   reading keypoints. **Five more were found while fixing them**, and one of those is the
   uncomfortable one:

   - the frame was handed to the interpreter as **interleaved `0..255` bytes at the source
     resolution**, where the model's input edge is FLOAT32 `[1, 128, 128, 3]` in `[-1, 1]` needing
     a resize first — and **nothing fails loudly at the call site**, so the detector was scoring
     the wrong pixels at the wrong scale;
   - `apply { numThreads = numThreads; useXNNPACK = true }` **set nothing**: TFLite's `Options`
     exposes setters and no getters, so Kotlin synthesised no property and the left-hand sides
     resolved to the enclosing function's own parameter. The thread count was TFLite's default and
     DESIGN §1's "no delegate enabled" was unenforced;
   - `TfliteFaceModels.embed` called `align(crop)` with too few arguments, so **the file did not
     compile**;
   - a `/*` inside a KDoc glob made the file **lexically invalid** (Kotlin does not allow a nested
     comment opener), surfacing 30 lines from its cause;
   - `defaultDigest()` used `Digest { … }`, a SAM conversion that does not exist, because
     `:core`'s `Digest` is a plain `interface`. **The file did not compile.**

   The last three were the concrete cost of a module that had never been compiled, and none of them
   was visible by reading the code. *(Historical note, added 2026-10-03: the module **does** compile
   now — see §2, `:app-android` — so this sentence describes the state on 2026-09-30, not today.)* `app-android/tools/verify-offline.sh` now has a **tier 2** that
   compiles `TfliteFace.kt` against hand-written stubs of the four `android.*`/`org.tensorflow.*`
   types it touches, which is how those were found. Full record, with the verification method and
   the seven mutation checks, in `eval/fixtures/face/detector_reference.json`
   → `android_detector_defects_fixed`.

   ⚠️ **The binding is now built against the real LiteRT AAR, but it has still never been *run*, and
   the stub signatures remain our reading of the real APIs.** What *is* verified: the decode and
   the input preparation are bit-identical to `:platform`'s, on the detector's real committed output
   and on adversarial tensors, with seven deliberate mutations each turning the suite red — and, as
   of 2026-10-03, the file compiles under AGP against the real Android artefact. What is **not**:
   that a device produces those
   numbers.

### `:eval`

The harness works. Eight suites exist (`mrz`, `qr`, `diary`, `unit-parity`, `latency`,
`macro`, `face`, `parity`); `smoke` runs the first five. Exit codes are distinct on purpose
(0 pass · 2 gate failed · 3 INVALID/report-split tuning · 4 INCOMPLETE/skipped) — see
`eval/README.md`. **Every run available today exits 4**, because a skipped device gate makes
a run INCOMPLETE. That is the designed behaviour, not a failure, and `summary.md` carries an
`INCOMPLETE` banner saying so.

### Tooling, scripts, hardware, CI (this area)

| Thing | State |
|---|---|
| `.github/workflows/ci.yml` | ✅ written, **669 lines** (measured 2026-10-03; 578 on 2026-09-30, and 285 before that), 2 jobs (`verify`, weekly `offline-proof`), parses as YAML. ❌ **it has run — twice, and failed both times** (R-J). **CHANGED 2026-10-03, three things:** (a) the **PII-scrubber step is now a HARD gate** — it carries no `continue-on-error` and fails the build if the scrubber is absent, broken, or silently running zero tests; (b) the size step **delegates to `:app-android:checkApkSize`** instead of `:app-android:bundleRelease`, which no longer runs (ABI splits × AGP 8.9.2), so there is no `.aab` in this build and delivery is APK-only; (c) `verify` can still be green with **two** red gates inside it — lint and magic-thresholds are both `continue-on-error: true`. Remaining known defect is R-J's exec bit |
| `scripts/check_no_network_in_core.sh` | ✅ works, **green**, exit 0 — **93** files scanned (measured 2026-10-03; 78 on 2026-09-30, 75 before that) |
| `scripts/check_no_magic_thresholds.sh` | ✅ works, **red** (**175** lines, exit 1 — measured 2026-10-03; 171 on 2026-09-30) — correctly, see §4.2 |
| `scripts/pii_scrubber_test.sh` | ✅ works and is now **GREEN** (exit 0, 7 suites, 111 tests, measured 2026-10-03). It was **red by design** (exit 1, "PII scrubber NOT IMPLEMENTED YET") until `core/.../log/PiiScrubber.kt` landed; the CI step is now blocking — see §4.1 |
| `scripts/verify_bundle.sh` | ✅ works (exit 1); correctly refuses the unpopulated sample manifest |
| `scripts/fetch_models.sh` | ✅ works (exit 0); correctly refuses the unpopulated sample manifest, no network touched |
| `scripts/provision.sh` | ✅ works (exit 0); 0600 secret, never printed, refuses to write inside the repo, warns loudly on the exFAT volume (R-Q). **Carried forward from the 2026-09-29 session, not re-run — see §4.7** |
| `scripts/purge_volunteer_data.sh` | ✅ works (exit 0 with an id, 2 on usage); scoped to `eval/data/`, `eval/runs/`, `eval/fixtures/`, re-runs smoke |
| `scripts/airplane_install_test.sh` | ✅ works; **exits 1** — measured 2026-10-03. It now gets *past* the artefact step: step A lists all four APKs and prints the SHA-256 of the release arm64 candidate, step C finds no CDN reference and counts 7 packaged model files, and it refuses at **step B** because `scripts/bundle_manifest.sample.txt` holds 2 unpopulated placeholder hashes (by design; that file's header says a green run is impossible until real model hashes exist). **This row said "exits 2 — no APK/AAB build output found" on 2026-09-30; both the exit code and the reason were wrong.** `--skip-bundle` skips B and reaches step D, exiting **2** = INCOMPLETE (no `adb` on PATH, no device). The earlier fix to `scripts/airplane_install_test.sh:89-90` (it no longer claims `settings.gradle.kts` gates `:ui`) still stands |
| `scripts/bundle_manifest.sample.txt` | ✅ **no longer stale.** It used to say `blazeface_short.tflite licence: UNVERIFIED`; it now records `Apache-2.0, VERIFIED on the model's own MediaPipe model card` (line 37), matching `THIRD_PARTY.md` §3. The real pin remains `scripts/models_manifest.tsv` (SHA-256) + `eval/models/manifest.json` |
| `hardware/calibration_card.pdf` | ✅ **exists and compiles** (pdflatex available on this host; 299,935 B, 2 pages) |
| `hardware/clip_bom.md` | ⚠️ written, but **costs are indicative estimates and the stack lands at ~₹445/clip, over the ₹300 claim**. **No clip has ever been built.** R-G |
| `hardware/print_targets.md` | ⚠️ **a specification, not artwork** — no SPECIMEN design files exist. R-L |
| `eval/data/` | ✅ **exists** — manifests, split declarations (`manifests/datasets.json`), the anti-gaming ledger (`threshold_provenance.json`) and the calibration slot, all committed. ⚠️ **zero media, zero rows** in every `manifest.csv`. No D-MACRO, no D-FACE, no D-PASTE, no D-SPOOF. R-E |
| `eval/models/` | ✅ `blazeface_short.tflite` (fetched, hash-pinned) + `svm_print_v1_synthetic.json` (**SYNTHETIC — trained by `eval/tools/synth_macros.py`, explicitly NOT D-MACRO and never gate-eligible**; the harness labels it `SYNTHETIC FALLBACK` at every use) |
| `eval/fixtures/face/` | ✅ `detector_reference.json` (the normative desktop reference + the four Android defects), `manifest.json`, and a synthetic no-face image. **No reference embeddings exist** — there is no embedder |
| `THIRD_PARTY.md`, `CONTRIBUTING.md`, `docs/STATUS.md` | ✅ written; `THIRD_PARTY.md` now lists every `libs.versions.toml` entry including ML Kit and Compose |

---

## 3. Eval status — cited, not hand-typed

**The freshest run, re-executed 2026-09-30T17:25:32Z at commit `9a04f40`.**
⚠️ **Not re-run on 2026-10-03.** Nothing in this section was re-measured in the last pass, so
every gate number below belongs to the run id named beside it and to the tree that produced it.
Re-running writes a *new* `eval/runs/<id>/` directory; the ids here are the provenance for the
numbers printed here, so replacing them without re-measuring would be exactly the hand-typing
AGENTS.md §5 forbids. `:eval`'s 39 **unit** tests were re-run and are green — that is a different
measurement from the harness **suites** below. (`ls eval/runs/` on 2026-10-03 confirmed
`eval-20260930-smoke-93ec`, `eval-20260930-smoke-653a`, `eval-20260929-smoke-a1c7`,
`eval-20260929-full-fd65` and `eval-20260929-parity-c3f9` are all present on disk.)

- **`smoke` → run-id `eval-20260930-smoke-93ec`** — `./gradlew :eval:run --args="smoke"`,
  12 gates: **11 pass, 1 skipped, 0 failed**, status INCOMPLETE, harness exit 4
  (gradlew prints 1 — see the exit-code trap in `BUILD_HANDOFF.md` §2).
  Full detail in `eval/runs/eval-20260930-smoke-93ec/summary.md`.
  Gate lines read straight out of that run's console output:
  `M-GATE-01` 100.000% (6725/6725) · `M-GATE-02` 0/2529 · `M-GATE-03` 746 of 10000 ·
  `Q-GATE-01` 100% (245/245) · `Q-GATE-02` 100/100 · `Q-GATE-03` 100/100 ·
  `D-GATE-01` 23/23 · `D-GATE-02` 17/17 · `D-GATE-03` rank-1 100.00% over 10 probes vs 10001
  distractors · `U-GATE-01` 19/19 · `L-GATE-01` all 6 stages within budget ·
  `L-GATE-02` **SKIPPED — not a NAMED device**.

**The two runs the previous revision of this file cited**, both still on disk and still true of
the commit they were run at:

- **`smoke` → run-id `eval-20260929-smoke-a1c7`** — `./gradlew :eval:run --args="smoke"`,
  12 gates: **11 pass, 1 skipped, 0 failed**, status INCOMPLETE, exit 4.
  Full detail in `eval/runs/eval-20260929-smoke-a1c7/summary.md`.
- **`full` → run-id `eval-20260929-full-fd65`** — `./gradlew :eval:run --args="full"`,
  19 gates: **13 pass, 6 skipped, 0 failed**, status INCOMPLETE, exit 4.
  Full detail in `eval/runs/eval-20260929-full-fd65/summary.md`.

**Both 2026-09-29 suites were re-run to confirm the numbers are reproducible, not one-offs.** The
re-runs (`eval-20260929-smoke-a290`, `eval-20260929-full-8b0b`) produced **identical** gate results
on the same commit, and the 2026-09-30 re-run above reproduced them a third time on a different
commit. `--run-id=auto` derives the id from date + suite + content, so *your* re-run will get a
different id and the same numbers — quote the id you actually produced, and check
`ls eval/runs/<id>/` before you put it in a slide.

> **Correction, still standing.** The run id an earlier revision of this file cited for the smoke
> result, `eval-20260929-smoke-c2f4`, **does not exist in `eval/runs/`** and never did. It was a
> hand-typed id, which is precisely what AGENTS.md §5 forbids. The ids above are directories the
> numbers were read out of, and all of them were confirmed present on 2026-09-30.

### `smoke` gates — `eval-20260930-smoke-93ec` (and identically `eval-20260929-smoke-a1c7`)

| Gate | Status |
|---|---|
| `M-GATE-01` MRZ mutate-catch (detectable mutants) | ✅ pass — 6725/6725 |
| `M-GATE-02` MRZ false positives on valid docs | ✅ pass — 0/2529 |
| `M-GATE-03` MRZ structurally blind cases published | ✅ pass — 746/10000 reported, not hidden |
| `Q-GATE-01` QR tamper-catch | ✅ pass — 245/245 |
| `Q-GATE-02` QR accept rate on genuine signed fixtures | ✅ pass — 100/100 |
| `Q-GATE-03` QR↔print wrong-person catch | ✅ pass — 100/100 |
| `D-GATE-01` diary planted scripts | ✅ pass — 23/23 |
| `D-GATE-02` diary stays GREEN on benign weeks | ✅ pass — 17/17 |
| `D-GATE-03` diary rank-1 over distractors | ✅ pass — 10 probes, 10 001 distractors |
| `U-GATE-01` harness↔`:core` parity | ✅ **pass — 19/19 agree** |
| `L-GATE-01` latency stages within budget | ✅ pass (host JVM) |
| `L-GATE-02` device latency ceiling | ⏭️ **SKIPPED, not passed** — device gate; this host run is not a named device |

**`U-GATE-01` is green, and the defect it found is closed.** The harness originally caught a
genuine `:core` bug: `Verhoeff` implemented `c = D[c][digit]` with no P (permutation) table,
which is a different function, not a faster one. `Verhoeff.kt` now **generates** all three
tables (D, P, INV) from the D5 group definition, re-derives their invariants in an `init`
block so a corrupted generator fails at class-load, and exposes them for a test that pins
them against the published tables (`ChecksTest.kt:27-61`) plus the published `236 → 3`
worked example (`ChecksTest.kt:65-67`). `FormatValidators.validateAadhaar` is downstream of
it and is therefore fixed too. **The Verhoeff path may be quoted as verified.** The harness
suite still carries the wording of the old finding, but it is inside an
`if (mismatches > 0)` branch and no longer renders — see §4.6.

### The other six gates, in `full` — `eval-20260929-full-fd65`

`full` adds `macro`, `face` and `parity`. Of the six extra gates: `MACRO-FEAT` and
`F-GATE-02` pass; `MACRO-CAL`, `MACRO-CLS`, `F-GATE-01`, `F-GATE-03`, `P-GATE-01` are all
**skipped, with reasons, in the run record** — never passed:

- `MACRO-CAL` — refused: no `device_calib.json` for `TEST-HARNESS-JVM`. **This is the
  correct refusal** (EVAL.md §7, ≤7 days old). No macro or face number may be quoted.
- `MACRO-CLS` — synthetic model, n=7 held-out specimens, 3/4 ambiguous specimens abstain
  below 0.15. **Not the D-MACRO gate of SPEC.md §7 and not gate-eligible**; the run says so.
- `F-GATE-01`, `F-GATE-03` — no D-FACE data, and no embedder to make a vector even if there
  were. EVAL.md §4 forbids reporting a zero-filled bucket table, so the buckets are absent.
- `P-GATE-01` — no device attached.

**Calibration:** both runs reported `calib REFUSED` — the correct refusal, for the same
reason. **No macro or face accuracy number exists anywhere in this project** (EVAL.md §7).

**What the passing numbers are and are not.** Every green gate above is logic tested on
**generated** fixtures: the MRZ corpus is generated from a fixed seed, the QR fixtures are
signed by a per-JVM harness stub over a TEST keypair, and the diary scenarios are scripts.
That is a real result about the code, and it is not a result about print substrate, real
faces, or a real signed Aadhaar QR. `qr` in particular certifies `:core`'s key selection and
tamper detection, **not** the production JCA binding.

---

## 4. Known-red gates, and why they are red

These are **documented, not worked around**. Nothing below should be made green
by weakening a check (AGENTS.md §8).

1. **PII scrubber — ✅ CLOSED 2026-10-03. This gate is GREEN.** ⚠️ **This item was "red, exit 1 —
   PII scrubber NOT IMPLEMENTED YET" on 2026-09-30, and that is no longer true.**
   `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt` exists, with seven suites under
   `core/src/commonTest/kotlin/dev/kasoti/log/` (`PiiScrubberTest`,
   `PiiScrubberAdversarialTest`, `PiiScrubberInjectionTest`, `PiiScrubberFalsePositiveTest`,
   `PiiScrubberRuleBoundaryTest`, `PiiScrubberFieldCorpusTest`, `PiiScrubberPolicyTest`).
   Measured: `sh scripts/pii_scrubber_test.sh` → **exit 0**, "7 scrubber suites green — 111
   test(s) executed". `core/src/commonMain/kotlin/dev/kasoti/i18n/RedactionMessages.kt` supplies
   the matching message keys. `.github/workflows/ci.yml` has **promoted this step to a hard
   gate**: it carries no `continue-on-error` and fails the build if the scrubber is absent,
   broken, or silently running zero tests. `app-desktop/.../LogScrubber.kt` remains as the
   module-local consumer.
   **What is still not claimed:** the script's own closing note is the right one and is repeated
   here — *this proves the rules work; it does not prove every call site uses them.* Until every
   log/cache/crash field is routed through `PiiScrubber`, "no PII in logs" for the shared path is
   a design intent with a tested scrubber behind it, not a verified property.
2. **Magic thresholds — red, 175 lines, exit 1.** The check is *correct*; the codebase has not
   been triaged. Many hits are legitimate (hash multipliers, ASCII codes, MRZ field offsets,
   date arithmetic — `IsoInstant.kt:63,66,67,68,76,86,87` is seven of them). Each needs either a
   registered threshold or a justified allowlist rule. **Owner:** maintainer. Do **not** fix
   this by widening the allowlist until the build goes green. (The previous revision of this
   paragraph reported that `.github/workflows/ci.yml` hard-coded "~94 lines" for this gate.
   **That has been fixed**: the step now counts findings out of the script's own output at
   `ci.yml:265-269` and reports the number it read, so the figure cannot go stale again.)
3. **Lint — the tasks EXIST, run, and are RED. They are not wired into `check`, on purpose.**
   ⚠️ **This paragraph previously said "`ktlintCheck` and `detekt` are not runnable — the task does
   not exist", and that was false.** It was true of the tree as of commit `6db63d9` and is not
   true now: `gradle/libs.versions.toml` has a `ktlint` **plugin** row (12.3.0) and a separate
   `ktlintEngine` pin (1.5.0), and the root `build.gradle.kts` applies both plugins to
   `:core :platform :app-desktop :eval :ui` with a 5-line ADR each, as AGENTS.md §5 requires.
   Every Gradle invocation now prints *"KASOTI: ktlintCheck and detekt are available on :core,
   :platform, :app-desktop, :eval, :ui, and are deliberately NOT part of 'check' until their
   backlogs are cleared (AGENTS.md §1)."* `AGENTS.md` §1 was right and this file was the stale one.
   Re-measured 2026-10-03 in the **source checkout** (heavily dirty; see the header):

   | Task | Exit | Result |
   |---|---|---|
   | `sh gradlew ktlintCheck --continue` | **1** | **4 267 violations**; **12 of 35** ktlint Check tasks fail. Per module: `:core` 1 854 · `:eval` 987 · `:app-desktop` 792 · `:platform` 341 · `:ui` 293. Top rules: `standard:function-signature` 1 287, `standard:multiline-expression-wrapping` 1 082, `standard:argument-list-wrapping` 816, `standard:class-signature` 302. Reproduced identically on two consecutive runs. ⚠️ **without `--continue` the same task prints only 3 130** — Gradle stops at the first failing task, so a naive count under-reports by ~1 100. The 9 extra violations `--continue` surfaces live in `.kts` build scripts (`app-desktop/build.gradle.kts` 5, `platform/build.gradle.kts` 4), which is why a `…/src/`-scoped grep misses them. `:ui` and `:eval` `ktlintKotlinScriptCheck` are clean; `:app-desktop`'s and `:platform`'s are not |
   | `sh gradlew detekt` | **1** | **1 240 weighted issues** (was 1 349 before the registry work): `:core` 592, `:eval` 470, `:app-desktop` 88, `:platform` 79, `:ui` 11 — all 5 fail. Top rules: `MagicNumber` 889 (**71.7%**, was 1 004), `MaxLineLength` 116, `ReturnCount` 77, `CyclomaticComplexMethod` 26. ⚠️ detekt counts LITERALS and `check_no_magic_thresholds.sh` counts LINES, and detekt has no notion of a structural exemption, so the two never agree on a total for the same code |

   ⚠️ **`:app-android` is NOT in the linted module list**, deliberately — the root
   `build.gradle.kts` applies both plugins to `:core :platform :app-desktop :eval :ui` only.
   4 267 of its violations are therefore invisible to `ktlintCheck`, and a `detekt`/`ktlint`
   "green" would still say nothing about the field app's 32 Kotlin files. That is a gap, not a
   pass.

   detekt runs on `buildUponDefaultConfig` with **no `config/detekt/detekt.yml`** — confirmed
   absent. **De-caused 2026-10-03, and the earlier reading was wrong:** `fusion/thresholds.v1.json` now
   exists (R-O) and `ThresholdName` is names-only over it, so the numbers left the enum and went
   into the registry file. detekt's `MagicNumber` count went **down** 1 004 → 889 (`:core`
   527 → 412) and the total 1 349 → 1 240. A measurement taken mid-way through the same work read
   968 → 1 004 and concluded "the registry is the diagnosis, not the cure"; that conclusion was
   an artefact of measuring before `ThresholdName` had actually been reduced. **`dev/kasoti/threshold/`
   contributes 0 MagicNumbers now** (it was 110). The remaining ~412 core literals are the classes
   the registry vouches for in its `structural` section — spec-fixed geometry, published algorithms,
   unit conversions, codec radices — and detekt will keep reporting them because it has no notion
   of a structural exemption. `check_no_magic_thresholds.sh` is the tool that does. **Owner:** maintainer. The three steps to clear this are in the `TODO(M2,@build)`
   in `AGENTS.md` §1. **Do not** switch a rule off or commit a baseline to reach green — that is
   weakening a gate (AGENTS.md §5, §8). This is also a *stated* SPEC NFR (NFR-M1: "detekt+ktlint
   clean") and it is currently unmet; CI runs both **advisory** so it reports them without
   blocking.
4. **APK size — ✅ MEASURED AND GREEN. Bundle size — ⛔ NOT MEASURABLE, AND THAT IS A REGRESSION.**
   ⚠️ **This item was "APK / bundle size — not runnable. Needs an Android SDK and a built
   `:app-android`. No size number may be quoted until it runs" on 2026-09-30, and that is now
   false for APKs.** `sh gradlew :app-android:checkApkSize` → `BUILD SUCCESSFUL`, "4 artefact(s)
   measured, all within 35.0 MB", against the measured sizes in §2. The budget is **unchanged at
   35.0 MB** and was not widened to make the numbers fit.
   **The bundle half is a real, deliberate loss.** `sh gradlew :app-android:bundleRelease` fails
   on AGP 8.9.2 with `splits.abi` enabled — `:app-android:buildReleasePreBundle` →
   `Sequence contains more than one matching element.` — so **there is no `.aab`** and no
   worst-case-slice number. The trade was taken knowingly: the all-ABI bundle/release artefact
   measured **77.53 MB**, more than double the budget, and `x86_64` alone measures 35.95 MB. ABI
   splits are the only lever short of dropping a capability. **Consequence: this build is
   APK-only and cannot produce a Play Store bundle**; CI step 8 now delegates to `checkApkSize`.
   **Owner:** maintainer. Resolving it needs an AGP upgrade, not a packaging tweak.
   `scripts/airplane_install_test.sh` is a third, separate state: it now finds the APKs and
   refuses at step B on the unpopulated `bundle_manifest.sample.txt` (exit 1), and reaches the
   device step only with `--skip-bundle` (exit 2 = INCOMPLETE). **No device step has ever been
   run, so NFR-S1's "installable in airplane mode" half is still unmet even though the size half
   is met.**
5. **Desktop packaging — WORKS. The previous revision of this file said "does not exist", and
   that was an overstatement.** `:app-desktop:packageDistributionForCurrentOS` is indeed not a
   Gradle task and never was — that task name was wrong. But `:app-desktop` applies the standard
   `application` plugin, so **`installDist`, `distZip` and `distTar` all exist and work.**
   Measured 2026-09-30, re-verified 2026-10-03:

   | | At commit `9a04f40` | In the working tree, after the packaging fix |
   |---|---|---|
   | `sh gradlew :app-desktop:installDist` | ✅ BUILD SUCCESSFUL → `app-desktop/build/install/app-desktop/` | ✅ BUILD SUCCESSFUL → `app-desktop/build/install/kasoti/` (re-verified 2026-10-03) |
   | Packaged launcher executes | ✅ yes | ✅ yes — and on 2026-10-03 `bin/kasoti help` printed `macro model: …/share/kasoti/models/svm_print_v1_synthetic.json (shipped inside this installation)` |
   | **Model files inside the distribution** | ❌ **NONE** — `find … -name '*.json' -o -name '*.tflite'` returned nothing, while `eval/models/` held both `svm_print_v1_synthetic.json` and `blazeface_short.tflite` | ✅ `share/kasoti/models/svm_print_v1_synthetic.json` + its model card ship inside it |
   | What a packaged run resolves | **silently abstains** — the model lookup is repo-relative, so a run outside a source checkout finds nothing and the print-process layer reads `UNKNOWN` | prints the resolved path **and its origin**: *"(shipped inside this installation)"* |

   **The defect that was there is real and it mattered:** a packaged console and a checkout
   console could return **different verdicts from the same document**, because the macro layer was
   `UNKNOWN` in one and read in the other. That is exactly the class of bug that looks like "the
   model did not load" and is easy to miss because the console abstains *quietly*. It is fixed in
   the working tree (`app-desktop/build.gradle.kts` adds the model to the `installDist` `Sync`, so
   `distZip`, `distTar` and the `shipConsole` task inherit it from one place, plus a
   `ModelLocator` that makes the search order explicit and reports every place it looked).
   Verify with: run `bin/kasoti help` and read the `macro model:` line — the origin must say
   *shipped inside this installation* when run from a distribution and *found in a source
   checkout* when run from Gradle.

   **What is still not proved:** a genuinely clean machine. The distribution was executed from
   `build/install/`, which still sits inside a source checkout, so the "checkout fallback" path is
   never fully removed — an install on a machine with no repo is still unexercised (SPEC §6 M2).
   `bundleOffline` remains not a task in this tree, as before. **Owner:** maintainer.
6. **The Verhoeff finding text — RESOLVED, this row is closed as of 2026-09-30.** ⚠️ The previous
   revision of this row reported that `UnitParitySuite.kt:233-244` still carried an "OUTSTANDING
   :core DEFECT" note and that `eval/README.md` §"Known state" still described the gate as red.
   **Both are now fixed**, verified in the files on 2026-09-30: the block at
   `eval/src/main/kotlin/dev/kasoti/eval/suites/UnitParitySuite.kt:233-234` now *describes the
   closure* ("This block used to carry an 'OUTSTANDING :core DEFECT' paragraph about…") rather
   than asserting the defect, and `eval/README.md:108-109` opens with "**The Verhoeff defect
   described here is closed, and `U-GATE-01` is green.**" Note the path is `suites/`, not
   `suite/` — the earlier citation was also wrong on the directory. Nothing for the maintainer to
   do here.
7. **Not re-verified in the 2026-10-03 session, and therefore not claimed as fresh.**
   These were carried forward, not re-measured, and their previously-measured results stand only
   for the tree they were measured on:

   | Not re-run | Last measured | Carried-forward result |
   |---|---|---|
   | `scripts/provision.sh` | 2026-09-29 session | exit 0; 0600 secret, never printed, refuses to write inside the repo, warns on exFAT. **Not re-executed** — it writes a real secret outside the repo |
   | `sh gradlew :eval:run --args="full"` | run `eval-20260929-full-fd65`, 2026-09-29 | 19 gates: 13 pass, 6 skipped, 0 failed, INCOMPLETE, exit 4 |
   | `sh gradlew :eval:run --args="parity"` | run `eval-20260929-parity-c3f9`, 2026-09-29 | 1 gate, skipped, no device |
   | `sh gradlew :eval:run --args="smoke"` | run `eval-20260930-smoke-93ec`, 2026-09-30 | 12 gates: 11 pass, 1 skipped, 0 failed, INCOMPLETE, exit 4 |
   | Android **device** install / airplane-mode run | never | never run — no `adb`, no device. The proof remains INCOMPLETE |
   | `:app-android` on a real handset (camera, ML Kit OCR, TFLite inference, wipe PIN, keystore) | never | never run. Compilation is not execution |

   The eval numbers above are **not** re-derived against today's tree. `:eval` *unit* tests (39)
   were re-run and are green; the *harness suites* were not, because each run writes a new
   `eval/runs/<id>/` directory and the run ids cited in §3 are the provenance for the gate
   numbers printed there. **Quote them with the run id, or re-run them and quote your own.**
   This paragraph exists so that "we did not measure it" is never mistaken for "we measured it
   and it was fine".

---

## 5. Open risks and deferred items

Ordered by how much they can hurt us, not by how hard they are.

### Blockers for the finals narrative

**Owner column: there is one owner.** The project is solo-maintained
(`docs/HANDOFF.md` §2). The old owners — android ×2, vision, vision+all — do not exist,
and an unowned blocker is an unowned blocker.

| # | Item | Why it hurts | Owner |
|---|---|---|---|
| R-A | ~~**No model weights.**~~ **HALF CLOSED 2026-09-30** — the **detector** is obtained, Apache-2.0-verified, hash-pinned and running (§2.1, spike 01). **`emb_v1.tflite` remains unobtainable**: 7 candidates rejected on licence grounds, then a **licensing decision** (a research-licensed embedder is acceptable **for the prototype** only — no operational deployment, redistribution obligations attach, vendor scope + 5.3 pp skin-tone / 7.5 pp regional recall gaps must be disclosed) and a **second search round** under that decision that checked 4 more candidates and obtained nothing. Nothing fabricated, converted or synthesised. | Face 1:1 is a ● load-bearing layer on every track (FUSION.md §7). The detector half works, so there is a pipeline to demo and `verify_bundle.sh` has something to verify — but a GREEN 1:1 verdict is **still unreachable**, because there is nothing to compare a face against. The licensing question is closed; what remains is a **price tag** (email InsightFace, or train our own embedder on data with clear rights) and the option of shipping detector-only and saying so. | **maintainer** (was vision) |
| R-B | **`uidai_qr_keys.json` provenance is UNVERIFIED / not obtained.** | Signed-QR is ● for the Aadhaar track. Until a confirmed source, a licence that permits bundling, and a pinned hash exist, **we cannot claim the signed-QR layer works against real material**. `:core` only ever exercises TEST keys, and the harness signs with a per-JVM stub verifier — the Q gates certify key selection, not the production binding. | maintainer |
| R-C | ~~**No Android APK has ever been built. `:app-android` has never been compiled.**~~ **✅ MOSTLY CLOSED 2026-10-03 — the module compiles, packages and passes its own size gate.** Root cause was **one character**: `app-android/build.gradle.kts` line 1 began with `#`, a *Groovy* comment marker, in a Kotlin script; the Kotlin script compiler parsed it as source. Fixed (`#` → `//`, the only `#` in the file). With `compileSdk` 34→35 (androidX `activity-compose:1.10.1` / `core-ktx:1.15.0` require it; `minSdk 26`/`targetSdk 34` unchanged) and `splits.abi` on for `armeabi-v7a`+`arm64-v8a`, `sh gradlew :app-android:assembleDebug`/`assembleRelease`/`checkApkSize` all succeed: 33.39 / 26.56 MB debug, 22.97 / 16.14 MB release, **all within the unchanged 35.0 MB budget**. 32 `main` + 8 `test` Kotlin files; the 8 test files are a **separate** defect (below). | **What is closed:** compilation, resource/manifest/Compose/aapt2/d8/R8, packaging, and the size gate — NFR-S1's *size* half. **What is NOT closed, and is what this row now means:** (a) **no device step has ever been run** — no `adb` on PATH, no handset, so the airplane-install proof is INCOMPLETE and `scripts/airplane_install_test.sh` exits 1 (bundle manifest placeholders) or 2 with `--skip-bundle`; (b) **NFR-R1's 50 crash-free runs are still 0**, because the app has never been launched; (c) `:app-android:test` **does not compile** — 160 errors in `ml/BlazeFaceDesktopParityTest.kt` (150) and `ml/BlazeFaceInputTest.kt` (10), which import `:platform`'s JVM classes; (d) **there is no `.aab`** — `bundleRelease` fails on AGP 8.9.2 with ABI splits, so delivery is APK-only; (e) keystore, wipe PIN and camera/ML-Kit runtime behaviour are unexercised. SPEC §6 M1 and NFR-B1 still do not have an exit | maintainer |
| R-D | **No calibration card run on a real device.** | EVAL.md §7 refuses macro/face numbers without ≤7-day calibration. The card now exists as a PDF (`hardware/calibration_card.pdf`, compiles) — nobody has run the routine, so every run reports `calib REFUSED`. | maintainer |
| R-E | **`eval/data/` now exists but contains zero media.** The manifests, `datasets.json` and `threshold_provenance.json` are committed; every `manifest.csv` has a header and **0 rows**. | The macro SVM cannot be trained or tuned on real substrate; `D-MACRO` needs ≥600 patches at M0. The only model in `eval/models/` is **SYNTHETIC** (`svm_print_v1_synthetic.json`, macro-F1 0.8469 on its own report split) and is explicitly never gate-eligible. **Every green gate in §3 is logic tested on generated fixtures, not on real print substrate.** | maintainer |
| R-F | **Zero real-people data has been collected.** | Consent records, `D-FACE` pairs and the deletion drill have never been exercised end to end. `purge_volunteer_data.sh` is written and dry-run tested against a synthetic tree, but never against real rows. | maintainer |

### Structural / process

| # | Item | Note |
|---|---|---|
| R-G | **₹300 clip claim is currently ~₹445 by the BOM, and no clip has been built.** | `hardware/clip_bom.md` prices are indicative estimates, not quotes. Decision needed: cut scope honestly (~₹345–350 with a named trade-off), or change the headline. Do not delete BOM rows to make it read ₹300. `DEMO.md` and `QA_BANK.md` have been corrected to stop saying "₹300" as fact. |
| R-H | **No Gradle dependency verification** (`gradle/verification-metadata.xml`). | Confirmed absent. `BUILD.md` §2 requires `verify-metadata`. A swapped transitive artefact is currently undetected — this is the AT-12 supply-chain residual. |
| R-I | **No `LICENSE` file at the repo root.** | Apache-2.0 code is vendored and we publish our own terms only in prose. Decision needed. |
| R-J | **CI has run twice and failed both times. There is no green run, and the cause is packaging, not code.** | ⚠️ **This row previously said "No CI has ever run", and that was false.** A remote exists and the workflow executes. Verified 2026-09-30 with `gh run list`: run **`36663026772`** on `9a04f40` (2026-09-30T03:08:03Z, 26 s) and run **`36658909646`** on `06bd654` (2026-09-30T02:14:22Z, 36 s), both `conclusion: failure`. Both died at the **first** step, `:core:jvmTest`, with `./gradlew: Permission denied` → `##[error]Process completed with exit code 126.` Root cause is R-Q: git records all 340 tracked files as mode `100644`, so on a fresh Linux runner `gradlew` is not executable. **Nothing in our Kotlin code has ever been exercised by CI** — not one compile has happened there. Also still true: `:eval:run --args="smoke"` exits 4, so the `verify` job's eval-smoke step needs the harness-exit-code parsing the workflow already contains, and `ktlint`/`detekt` are advisory. *(R-S is kept separate: it is now about getting a green run, not about getting a remote.)* |
| R-K | **Offline proof is warm-cache only.** | The weekly job warms the cache then builds `--offline`. A cold-machine offline build is **not** proved and needs a persistent self-hosted runner. |
| R-L | **`FusionEngine` coverage rules and track matrix** were written but have not been exercised against the four real SPECIMEN tracks, because the SPECIMEN cards do not exist (`hardware/print_targets.md` is a specification and says so in its own first line; the artwork is unowned). | |
| R-M | **Kotlin Gradle plugin loaded multiple times** (`:app-desktop`, `:core`, `:eval` all pin the version explicitly). | Warning printed on **every** Gradle invocation; not fatal today, will become one. |
| R-Q | **The repo lives on an exFAT volume that ignores `chmod`, so no tracked file has the exec bit — and this is what is keeping CI red.** ⚠️ **Escalated 2026-09-30: this moved from a local annoyance to the top blocker.** | Measured: `git ls-files -s \| awk '{print $1}' \| sort \| uniq -c` → **`340 100644`**, not one `100755` in the tree — including `gradlew` and all ten `scripts/*.sh` and `app-android/tools/verify-offline.sh`. The working tree *looks* correct (`ls -l gradlew` → `-rwxr-xr-x`) because exFAT forces 755 locally; a fresh clone gets `-rw-r--r--` and `./gradlew` returns `permission denied`. Confirmed on a real `git clone` on 2026-09-30, and confirmed as the cause of both CI failures (R-J). Three further consequences: (1) **any 0600 secret written inside the checkout is world-readable** — `scripts/provision.sh` detects this, prints a loud warning, and refuses the repo by default; (2) because every file is committed `100644`, the exec bit carries no information in this clone at all; (3) any "restrict this file" instruction in a design doc is unenforceable here. **Fix: `git update-index --chmod=+x gradlew scripts/*.sh app-android/tools/*.sh`, which needs a clone on a non-exFAT filesystem — or make CI call `sh gradlew`.** This is the cheapest high-value item in the project: one commit, and it unblocks every automated gate. |
| R-R | **`:ui` is not the Compose Multiplatform module `DESIGN.md` D7 chose.** | It is a plain `kotlin-jvm` module with a `FieldView` interface and **no `@Composable` in `:ui` at all**. The logic is tested (**55 tests**, up from 44). ⚠️ **CHANGED 2026-10-03:** the *Android* renderer — `app-android/.../view/ComposeFieldView.kt` — **has now been compiled** by AGP and the Compose compiler and is inside the shipped APKs, because the module is in the build. The **desktop** renderer still does not exist and is the remaining gap. This is a deliberate, documented trade (spike decision log, `HANDOFF.md` §7) and a real gap in UI coverage: one of the two renderers is compiled but has never been displayed on anything. |
| R-T | **Desktop TFLite has no native for Windows x86-64 or Apple-Silicon Mac.** `ai.djl.tflite:tflite-native-cpu` publishes only `linux-x86_64` and `osx-x86_64`, for both of its two versions; the other two return HTTP 404. | Those platforms are "review-only, no on-device inference" (BUILD.md §4's own prescription, implemented in `TfliteRuntime`). It will not improve by bumping a version. The alternative is ONNX Runtime JVM (main jar 139,129,141 B at 1.22.0 — measured; win/linux/osx natives) which needs the model in ONNX and breaks D1's "same bytes on both platforms". **BUILD.md §4 has now been corrected** — it previously claimed natives for win/linux/mac. |
| R-U | **`ai.djl.tflite` Java↔native pairing is not version-locked.** `tflite-engine:0.27.0` (dated 2024-03-28) and `tflite-native-cpu:2.6.2` (dated 2022-01-12) are tied together by nothing in either POM. | A one-sided version bump is a JNI-ABI break that surfaces as `UnsatisfiedLinkError: TensorFlowLite.nativeRuntimeVersion()` — which reads like an ABI mismatch and is not one, so it will be misdiagnosed. Both versions are pinned in `libs.versions.toml` and recorded in `THIRD_PARTY.md` §1. Nobody should bump one side alone. |
| R-S | **A git remote exists and `main` is in sync with `origin/main`. What does not exist is a green CI run, and the blocker is the exec bit, not the remote.** | ⚠️ **This row previously said "No git remote is configured" and that was false.** Measured 2026-09-30: `git remote -v` → `origin https://github.com/Leo-Expose/Kasoti.git` (fetch + push); `git branch -vv` → `* main 9a04f40 [origin/main]`; `git rev-list --left-right --count main...origin/main` → `0  0`. History is 2 commits (`06bd654`, `9a04f40`). **What is actually missing is a passing CI run**, which is R-J, and its cause is R-Q. `CONTRIBUTING.md` §1's PR workflow is still intent rather than practice, because nothing has ever been reviewed on GitHub — but for the reason in R-J, not the reason in the old version of this row. |
| R-V | **`logs/verification-metadata.xml` and the lint plugins are the same class of gap, unfixed.** | `THIRD_PARTY.md` §1's own "build-tool gaps" note and §4.3 above are the same finding recorded twice. Listed once here to keep a single owner. |

### Known quality debt

| # | Item | Note |
|---|---|---|
| R-N | Corpus/debug helpers live in `commonMain` | `mrz/MrzCorpus.kt` generates the 10k mutate corpus from production code rather than from `:eval`, and the diary test-support helpers have been consolidating. These are test scaffolding in the shipping module. Move them when convenient; do not "fix" them by deleting the corpus. Owner: `:core`. |
| R-O | ~~`fusion/thresholds.v1.json` still does not exist~~ — **✅ CLOSED in the working tree 2026-10-03, with one real caveat** | `core/src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json` now exists (**27 831 B**, `schemaVersion` 1, `version` `v1`, 27 KB) with **49** thresholds, each carrying `name` / `unit` / `default` / `floor` / `ceiling` / `tuningDataRef` / `owner`, plus `units`, `constraints` and a `structural` allowlist section — exactly the five fields AGENTS.md §2 demands. `ThresholdName` is now **names and nothing else** (49 entries, matching the file), `ThresholdSpecSource` embeds the file text verbatim, and `ThresholdSpecFileTest` fails if the two ever diverge. Floors/ceilings are enforced and it is exercised by tests. ⚠️ **The caveat: the file is UNTRACKED** — `git status` reports `?? core/src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json` and `git ls-files` does not list it. So the registry is real in the tree and **absent from every commit, from CI, and from any clone**. Until it is committed, the "registry of record" is a local file. **All 49 defaults are also still untuned** — values a human typed, not values a split chose. **CORRECTED, later the same day:** detekt's `MagicNumber` count **fell** 1 004 → 889 once `ThresholdName` was actually reduced to names-only (`:core` 527 → 412, total 1 349 → 1 240), and `scripts/check_no_magic_thresholds.sh` went from red at 175 lines to **green at 0** (§4.3). The earlier 'rose rather than falling' reading was taken mid-change and is superseded. The registry is now the cure for `:core`, not just the diagnosis. |
| R-P | `:platform` has only JVM implementations | `platform/src/` contains `jvmMain` and `jvmTest` and nothing else. **No Android `actual`s exist**, and `core/src/` still has only `commonMain`/`commonTest` (no `androidMain`) — so `:app-android` carries its own copies of the platform seams. ⚠️ **CHANGED 2026-10-03:** the *Android target of `:core`* is no longer uncompiled — `core/build.gradle.kts` and `platform/build.gradle.kts` both gained a real `android { }` block (`compileSdk = 35`, `minSdk = 26`) when the SDK gate opened, and `:core`'s `commonMain` is compiled into the APK that `assembleDebug` produces. What is still true and still a gap: `:platform` has **no Android implementation at all**, so the duplication it implies is real and the desktop and Android crypto/imaging/OCR code paths are separate. |

---

## 6. What is explicitly deferred (and should stay deferred)

- **NFC / chip reading (P1).** SPEC FR-N1 is stub-first. A SPECIMEN passport must
  be printed with a dead contact pad, not a live one (`hardware/print_targets.md`).
- **Tilt liveness viewer.** Experimental, off the critical path (QA_BANK).
- **Tesseract packaging for Windows.** P1 / M2 per BUILD.md §3. (There is no Windows TFLite
  native either — R-T — so Windows desktop is review-only on two counts.)
- **HTTPS sync transport.** File/USB/QR is sufficient for the demo (SYNC.md §5).
- **A genuinely clean-machine install of the desktop console.** This one is **downgraded from
  "does not exist"**: the distribution builds and its launcher runs (§4.5), and the
  packaged-vs-checkout verdict divergence has been fixed. What is still deferred is the last
  mile — installing on a machine with no source checkout, and exercising `distZip`/`distTar`
  on a non-Linux host. `bundleOffline` remains not a task in this tree, as it never was.
- **STLS server.** ₹0/server is a feature, not a gap.

---

## 7. Next five things, in the order they unblock the most

0. **Commit the exec bit, or make CI call `sh gradlew`** (R-Q → R-J). One commit, and it is
   the only item on this list that unblocks *everything else on it* — CI cannot execute a
   single test until it is fixed, so right now every claim in this file rests on one developer's
   machine. Concretely: from a clone on a filesystem that honours the exec bit, run
   `git update-index --chmod=+x gradlew scripts/*.sh app-android/tools/*.sh` and commit.
   Failing that, one line in `ci.yml` (`sh ./gradlew …`) buys the same thing. **Do this first.**
1. **The embedder: the licence is decided, the weights are not, and now it is a *price tag*
   rather than a permission (R-A).** The decision is made and written down — a research-licensed
   embedder is acceptable **for the prototype**, with three binding consequences (no operational
   deployment; redistribution obligations attach and are a *separate* right from use; the vendor
   scope caveats and the measured 5.3 pp skin-tone / 7.5 pp regional recall gaps must be
   disclosed, not discovered). `docs/spikes/01-face-model.md` §7 item 5. A second search round
   after that decision found nothing (§7 item 6), so "look harder" is no longer on the list.
   What remains: (a) email InsightFace — since 2025-11-24 even open-sourced models route through
   `recognition-oss-pack@insightface.ai`, at an unknown price; (b) train our own 128-d embedder on
   data with clear training rights, a multi-week data project with its own consent burden; or
   (c) ship detector-only and say so in every narrative. **Until one of those happens,
   `emb_v1.tflite` stays empty and GREEN 1:1 stays unreachable** — the decision moved the
   permission, not the implementation.
2. ~~**Install an Android SDK and build `:app-android` once**~~ **✅ DONE 2026-10-03 — rewritten
   below, because this item's premise is now false.** An SDK is installed, the module compiles,
   four APKs exist and `checkApkSize` is green (§2). What replaces it is strictly smaller and
   strictly harder:
   2a. **Put an APK on a real handset in airplane mode.** That is the whole remaining Android
   blocker: `sh scripts/airplane_install_test.sh` already finds the artefact and does its static
   scan, and with `--skip-bundle` it walks all the way to step D and stops at "adb not on PATH".
   It needs `adb`, a device, and a populated `scripts/bundle_manifest.sample.txt`. Until then
   NFR-S1's *installable-offline* half, NFR-R1's 50 crash-free runs and SPEC §6 M1 have no exit,
   and no sentence in any doc may claim the app "works on a phone".
   2b. **Fix `:app-android:test` (160 compile errors, §2).** Two files import `:platform`'s JVM
   classes into an Android test source set. Cheapest correct fix is a `jvmTest`-side source set
   for those two parity suites rather than an Android `actual` for `:platform` — but that is a
   code decision and this pass is documentation-only.
   2c. **Decide what "delivery" means now that there is no `.aab`** (§2, §4.4). APK-only is
   acceptable for a USB/airplane demo and **not** acceptable for Play. Either upgrade AGP or
   record APK-only as a permanent, dated decision in `HANDOFF.md` §7.
3. **Print the SPECIMEN sets from `hardware/print_targets.md` and start
   `D-MACRO` collection** (R-E). Data collection has the longest lead time in the
   project; everything else can be parallelised around it. `eval/data/macro/manifest.csv`
   currently has a header and zero rows. **Unchanged by anything in the 2026-10-03 pass.**
4. **Run the calibration-card routine on both named devices and store
   `device_calib.json`** (R-D). It depends on 2a — the routine is *in the app*, so it cannot run
   until the app runs.
5. ~~**Write `PiiScrubber` in `:core`**~~ **✅ DONE 2026-10-03.** `core/.../log/PiiScrubber.kt`
   exists with seven suites, 111 tests green, and the CI step is **blocking** (§4.1). What
   remains is the second half of that item and it is *not* a code task: **route every
   log/cache/crash field through the scrubber**, then move `:app-desktop`'s `LogScrubber` rules
   into the shared module so there is one implementation. Until every call site uses it,
   AGENTS.md §4's "no PII in logs" is a tested scrubber plus an intent, not a verified property —
   and the script says so in its own closing line.
6. **NEW, and it was not on this list before 2026-10-03: commit `fusion/thresholds.v1.json`,
   the `dev/kasoti/threshold/` loader + tests, and the PII-scrubber work** (R-O, §4.1). The
   registry file exists and is correct — **49** thresholds with name/unit/default/floor/ceiling/
   tuningDataRef/owner, plus a `structural` section with 17 declared non-tunable classes — and it
   is **untracked** (`git status` → `??`), as are `ThresholdSpec.kt`, `ThresholdSpecSource.kt`,
   `ThresholdName.kt` and their tests. `check_no_magic_thresholds.sh` is now **green** (was red at
   175 lines) and detekt's `MagicNumber` count went **down** 1 004 → 889. An untracked registry
   means the "registry of record" exists only on one laptop. **This is a commit, not a design
   problem, and it is still the cheapest item here.**

**Three cheap items that unblock verification rather than features**, listed here because they
are near-free and each closes a specific "we do not know" that currently reads as a risk:

- **One consented face crop** settles two open questions at once: the detector's `[-1,1]` vs
  `[0,1]` input encoding, and the unverified anchor-size parameters. Both are recorded as
  unresolved in `eval/fixtures/face/manifest.json` and `detector_reference.json` precisely so
  that this is a visible, closable item rather than a silent assumption. Cost: one image.
- **`app-android/tools/verify-offline.sh` runs on any machine with no SDK, in two tiers** —
  **201** unit tests plus a stub-compile of the Android-facing sources (measured 2026-10-03,
  exit 0, 201/201; it read 189 on 2026-09-30). It is how the three "this file does not compile"
  defects in §2.1 item 4 were found, and it is **still the only thing that runs the two
  `ml/` parity suites**, because `:app-android:test` does not compile. ⚠️ Its tier-2 closing
  message now overstates its own limits — it says aapt2/d8/R8/Compose and the real SDK
  signatures "are all still unverified", which `assembleDebug` has since disproved. The script
  is a build file in all but name and is **not** edited by this documentation pass; treat its
  summary line as stale and the 201/201 result as real. Anyone touching `app-android` should
  run it; a green run is cheap and a red one is specific.
- **A git remote** (R-S). ⚠️ **This used to be on the list and it is not a gap any more** — the
  remote exists and `main` is in sync with `origin/main`. What is missing is a green CI run, and
  that is a one-commit fix to the exec bit (R-Q), not a piece of infrastructure. See §7's item 0.

---

## 8. The browser demo (`Kasoti-Demo`) — what it is, and what it is not

**There is a second repository: `https://github.com/Leo-Expose/Kasoti-Demo.git`, developed in a
sibling working tree at `/mnt/Lay/Kasoti-Demo`.** It is a static, zero-backend browser page —
one `index.html`, one stylesheet, seven ES modules, a vendored pdf.js and 1.25 MB of recorded
evaluation data — and it is being submitted as the public link. It has **no build step, no
server and no network call at runtime.**

**It is presented under the name "Docuscan." That rename is presentational only.** The product in
this repository is still called **KASOTI** (कसौटी), and it stays called that. Nothing in the
rename touches an identifier, a package, a path, a class or a recorded output — it covers the
page title, the prose and the sample documents the demo generates. The decision, its date and
its reason are in `HANDOFF.md` §7, because a solo project's decisions are only auditable if
they are written down.

**What the demo does:**

- Parses a pasted or dropped **TD1/TD3 MRZ** in the tab and shows every check digit's term-by-term
  working, using a JavaScript port of this repository's `core/src/commonMain/kotlin/dev/kasoti/mrz/`.
  The arithmetic is the **ICAO Doc 9303 published standard** (repeating weights 7-3-1, `A`–`Z` →
  `10`–`35`, filler `<` → `0`, mod 10) — the port exists so it can be inspected and re-run by hand.
- Re-runs the **10 000-row mutate corpus** in the browser and compares row-by-row against a
  recorded run.
- Extracts the text layer of a **local PDF** with pdf.js and scans it for MRZ candidates. The file
  is read with `FileReader` and never leaves the machine.
- **Replays five recorded screening verdicts** — the layers, findings, warnings, policy versions
  and the final `VERDICT` line — captured verbatim from the real `:app-desktop` engine by
  `tools/capture_real_output.py`. The interface is English/Hindi throughout (82 string keys).

**What it deliberately does NOT do — the part that matters for this repo's credibility:**

- **It does not re-implement fusion.** No scoring, no finding codes, no verdict logic, no RED /
  AMBER / GREY decision. `assets/js/` contains none of it. The verdict panel replays decisions
  made elsewhere. A judge watching the page is watching **recorded output**, not a live decision.
- **It does not replace a hands-on device demonstration**, and the project has no device
  demonstration: `:app-android` has never been **run** — it compiles and packages as of
  2026-10-03, but no handset has ever installed it (R-C). The demo proves the
  arithmetic and shows the measurement record; it stops there.
- **It cannot make a screening decision about the user's own document**, because nothing in it
  fuses. The MRZ lab grades a document's arithmetic; it does not decide whether that document is
  a forgery.

**Its numbers are ours, and they carry our caveats.** Every figure is read at render time from
`data/evalrun.json`, a copy of eval run **`eval-20260930-smoke-653a`** — one of ours, confirmed
present in `eval/runs/` on 2026-09-30. Three caveats travel with it and are stated on the page:
the run recorded `commitDirty: true`; the commit it names (`58b8656`) is **no longer resolvable**
in this repository, whose history has been re-committed and now holds two commits, so it
identifies the run record rather than a checkout a reader can make; and **the run's own verdict
is `INCOMPLETE`, exit code 4** — seven thresholds checked, six still at untuned registry
defaults, macro and face metrics uncalibrated. The page shows that record rather than a tidied
version of it.

**One deliberate inconsistency, kept on purpose.** The recorded transcripts still print the
engine's own project name — the banner reads `KASOTI screening RED`, and one layer detail reads
`dev.kasoti.diary`. **Those files are byte-for-byte captures and are not edited**, because the
panel's central claim is that it replays verbatim output; renaming them would make that claim
false to save a reader five seconds of confusion. If you see "KASOTI" inside a Docuscan page,
that is not an oversight. Decision and reason: `HANDOFF.md` §7, 2026-09-30.

**A demo is not a substitute for the gates.** Nothing in this section moves a row in §0 or §4.
`:core` still has 459 tests to pass (896 across the five modules), `:app-android` still has to be
**run on a device** even though it now compiles and packages, and R-A through R-V are still open.

---

## 9. How to keep this file honest

- Every number here comes from a command run on the date in the header, and the eval numbers
  cite a **run-id** whose directory exists under `eval/runs/`. No hand-typed metrics
  (AGENTS.md §5). When a cited run-id is not on disk, the number is not evidence — delete it.
- **Pin every count to a commit.** The reason this file was wrong for two sessions is that a
  count was true once and then got copied forward. `:core` was "270" long after it was 302;
  `ci.yml` was "285 lines" when it was 578; the lint tasks were "not runnable" for a week after
  they were wired. **A number without a commit and a date is a rumour.** §4.7 exists so that
  "not re-measured" never reads as "measured and fine".
- **Absence of evidence is not evidence of absence — check the thing before ruling it out.**
  Two of the worst claims in this file's history were "the task does not exist" and "AuditChain
  has no test file", and both were false at the moment they were written. A grep that returns
  nothing is usually a wrong grep.
- If a command in §1 stops working, fix the row — and fix `AGENTS.md` §1 if it
  is the same command, because a stale command list costs more than a stale
  comment.
- If something in §5 is resolved, delete it or move it to §2. A risk register
  that only grows is a risk register nobody reads.
- **Do not mark something green because it *should* be.** Every red row in §4 has been
  re-measured, not remembered. Two of the three "risks" this file was previously asked to
  close — magic thresholds and the PII scrubber. **One of the two closed on 2026-10-03:** the
  `:core` PII scrubber exists, its 7 suites are green (111 tests) and the CI step is blocking.
  Magic thresholds are **still red** at 175 lines. Only the Verhoeff finding had closed before
  that.
- **Do not soften a limitation to make a number look better.** The value of this file is that a
  judge can check it. Every correction in the 2026-09-30 and 2026-10-03 passes moved a claim
  *toward* the truth, **including the ones that are unflattering, and including the ones that
  made the project look better.** `:app-android` building is genuinely good news and it is
  recorded as such — and in the same pass it also acquired three new red facts that did not exist
  before: `bundleRelease` no longer runs so there is **no `.aab`** and no Play Store bundle;
  `:app-android:test` does not compile (160 errors); and CI's Android size step had to be
  rewritten because its old command stopped working. Along with the pre-existing unflattering
  facts that remain: CI is red on the exec bit, `gradlew` does not run from a clone, lint is red
  at 4 267 / 1 349, magic thresholds are red at 175 lines, and nothing has ever run on a device.
  A reader must be able to trust this file *because* it is unflattering in both directions.
- **Count with the right command, or the number is wrong in the optimistic direction.** Three
  separate traps produced wrong totals in this file's history and all three are now written down:
  `--rerun` vs `--rerun-tasks` (§1), `--continue` on `ktlintCheck` (§0), and `…/src/`-scoped
  greps that miss `.kts` build-script violations (§4.3). A gate that measures less than it
  thinks it measures is worse than no gate.
