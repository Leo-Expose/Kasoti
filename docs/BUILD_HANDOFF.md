# KASOTI — Build Handoff (first written 2026-09-30; **re-measured 2026-10-03**)

> **What this file is.** `HANDOFF.md` is the process doc (roles, rituals, who owns what).
> `STATUS.md` is the truth doc (what works, what is red). This is the third thing: **the state
> of the build as it was left**, what was verified, what was deliberately not done, and the
> traps that will cost someone an hour if they are not written down. Read it before touching
> anything, then read `STATUS.md` §7 for the next five tasks.
>
> Everything in the 2026-09-30 revision was produced in one continuous solo session, and the
> docs were audited afterwards: 23 false claims were corrected then, and a further pass on
> **2026-10-03** corrected the two biggest remaining ones — **`:app-android` now compiles and
> packages** (it had "never been compiled"), and the test counts had drifted by 159. Rows
> touched on 2026-10-03 are marked. **Trust the code and the run records over any prose,
> including this file.**

---

## 1. The one-paragraph version

A complete, tested logic layer exists: ICAO 9303 MRZ with a 10 000-row mutation corpus,
Verhoeff, date and format checks, a secure-QR verification path, FFT/LBP print forensics with
a trained linear classifier, face math and detection curves, a file-backed crossing diary with
alias/travel/facilitator rules, the `kasoti-sync/1` merge protocol, the full FUSION.md verdict
engine, a hash-chained audit log, English and Hindi strings, and an evaluation harness that
refuses to pass a run it cannot justify. **896 Gradle tests and 201 SDK-free Android tests,
zero failures** (measured 2026-10-03: core 459 · platform 137 · app-desktop 206 · eval 39 ·
ui 55, `sh gradlew … --rerun-tasks`, 20 tasks executed).
A desktop console runs the whole cascade, from a checkout **and from a packaged distribution**.
**An Android field app now compiles and packages** — four APKs, all inside the unchanged 35 MB
budget — **and has never been run on a device.** **CI has run twice and failed both times**, at
the first step, because the `gradlew` exec bit is not committed — so nothing here has ever been
verified by a machine other than this one. **There is no `.aab` and no Play Store bundle**:
`:app-android:bundleRelease` fails on AGP 8.9.2 once ABI splits are on, so delivery is APK-only.
There is no face embedder — a licensing wall plus an unpriced licence, not a backlog. The macro
model is trained on synthetic textures and says so on every surface. The macro and face datasets
are zero rows.

---

## 2. Verification — the exact commands, and what they should print

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
cd /mnt/Lay/Kasoti
```

**JDK 17 is mandatory.** The default `java` on this box is 27 and it breaks the Kotlin
compiler. This is the single most common first-failure.

| Command | Expected |
|---|---|
| `sh gradlew :core:jvmTest :ui:test :platform:jvmTest :app-desktop:test :eval:test --rerun-tasks` | `BUILD SUCCESSFUL` · **896 tests · 0 failures · 0 skipped** (core 459, platform 137, app-desktop 206, eval 39, ui 55). Measured **2026-10-03** in the source checkout (`9a04f40` + a dirty tree), 20 actionable tasks all executed. On a clean clone expect **1 skipped** (`DetectorParityReferenceTest` — the `.tflite` is git-ignored; `sh scripts/fetch_models.sh` makes it run) |
| `sh gradlew :eval:run --args="smoke"` | harness **exits 4** = INCOMPLETE · 12 gates, 11 pass, 1 device-skip, 0 fail. **Last run 2026-09-30**, run id `eval-20260930-smoke-93ec` — not re-run 2026-10-03 |
| `sh app-android/tools/verify-offline.sh` | `201 tests started / 201 successful / 0 failed` · exit 0 (measured 2026-10-03; read 189 on 2026-09-30) |
| `sh scripts/check_no_network_in_core.sh` | exit 0, **93** files scanned (measured 2026-10-03; 78 on 2026-09-30) |
| `sh scripts/check_no_magic_thresholds.sh` | **exit 1**, **175** findings — correctly red, see §6 |
| `sh scripts/pii_scrubber_test.sh` | **exit 0 — 7 suites green, 111 tests.** ⚠️ was exit 1 ("NOT IMPLEMENTED") until `core/.../log/PiiScrubber.kt` landed; the CI step is now **blocking** |
| `sh gradlew ktlintCheck --continue` | **exit 1**, **4 267** findings, 12 of 35 ktlint Check tasks fail. ⚠️ **without `--continue` it prints only 3 130** — Gradle stops at the first failing task. The task exists — see §6 |
| `sh gradlew detekt` | **exit 1**, **1 349** weighted issues. The task exists — see §6 |
| `sh gradlew :app-android:assembleDebug` | `BUILD SUCCESSFUL` · 2 APKs: arm64-v8a **33.39 MB**, armeabi-v7a **26.56 MB** |
| `sh gradlew :app-android:assembleRelease` | `BUILD SUCCESSFUL` · 2 APKs: arm64-v8a **22.97 MB**, armeabi-v7a **16.14 MB** |
| `sh gradlew :app-android:checkApkSize` | `BUILD SUCCESSFUL` · 4 artefacts measured, all within **35.0 MB** |
| `sh gradlew :app-android:bundleRelease` | ⛔ **exit 1** — `:app-android:buildReleasePreBundle` → `Sequence contains more than one matching element.` **No `.aab` exists.** See §5 |
| `sh gradlew :app-android:test` | ⛔ **exit 1** — 160 compile errors, 150 in `ml/BlazeFaceDesktopParityTest.kt` + 10 in `ml/BlazeFaceInputTest.kt`. See §5 |
| `sh scripts/airplane_install_test.sh` | **exit 1** — finds all four APKs, then refuses on the 2 unpopulated hashes in `scripts/bundle_manifest.sample.txt`. With `--skip-bundle`: **exit 2** (INCOMPLETE, no `adb`, no device) |
| `sh gradlew :app-desktop:installDist` | `BUILD SUCCESSFUL` → `app-desktop/build/install/kasoti/`. **This was "does not exist" until 2026-09-30**; see §4.7 |
| `sh gradlew :app-desktop:run --args="sample"` | writes a zero-PII specimen to `demo-specimen/` and prints its field table; screen it with `--fields demo-specimen/specimen.json` |

> ⚠️ **Every row above says `sh gradlew`, not `./gradlew`, and that is not a stylistic choice.**
> Git records all 340 tracked files as mode `100644` — `gradlew` included — because this repo is
> authored on an exFAT volume that cannot hold the exec bit, so on a fresh clone `./gradlew` is
> `permission denied`. That is what killed both CI runs. See `STATUS.md` §0 and §9 (R-Q, R-J).

**JDK 17 is mandatory.** The default `java` on this box is 27 and it breaks the Kotlin
compiler. This is the single most common first-failure.

### The exit-code trap — read this before writing any CI or script

`./gradlew :eval:run` **does not exit with the harness's code.** Gradle runs it in a child JVM
and exits **1** itself. The harness's real code only appears on stderr:

```
> Process 'command '.../bin/java'' finished with non-zero exit value 4
```

So `./gradlew :eval:run --args="smoke"; echo $?` prints **1**, never 4. Anything that reads
`$?` will treat the one documented off-device answer as a failure. The two CI steps already
parse the child code out of the log; `.github/workflows/ci.yml` has the working pattern.
Undocumented in `AGENTS.md` §1 outside that comment — fix it if you touch either.

Semantics: `0` pass · `2` gate failed · `3` INVALID (split discipline violated) ·
`4` INCOMPLETE (a required suite was skipped). **4 is the correct answer off-device.**

---

## 3. Module map as built

| Module | Lines | In the build? | State |
|---|---|---|---|
| `:core` | 15 391 | always | Complete logic. Zero external deps, zero `expect`/`actual`, pure. |
| `:eval` | 7 287 | always | Harness + `eval/tools/` Python (the only Python allowed). |
| `:app-desktop` | 5 408 | always | Post-console CLI. Runs. |
| `:platform` | 4 482 | always | **JVM actuals only** — JCA crypto, ImageIO, Tess4J, hash-pinned model loader, TFLite detector. |
| `:app-android` | 10 632 | **only with an SDK** | ⚠️ **compiles and packages as of 2026-10-03** — 32 main + 8 test Kotlin files, 4 APKs, size gate green. **Never run on a device.** |
| `:ui` | 1 823 | always | Pure presentation state + a `FieldView` interface. **No Compose.** |

**`:ui` is deliberately not Compose.** Compose Multiplatform resolves its plugin at
configuration time for the whole build, so an unresolvable artefact would break
`./gradlew :core:jvmTest` for anyone not doing UI work. The logic that can be wrong lives in
tested pure functions; the Compose binding is a thin layer, separate work.

`:core` declares no `expect`/`actual` at all. Platform seams are plain interfaces in
`dev.kasoti.crypto` (`Digest`, `Hmac`, `SignatureVerifier`) that `:platform` satisfies — which
keeps the audit logic and the sync envelope pure and testable, and keeps hand-rolled
crypto out of `:core` (AGENTS.md §5).

---

## 4. The decisions that will look wrong unless you know why

**1. The MRZ catch rate is not 100%, and the denominator is deliberate.**
6 725 of 6 725 *detectable* mutants are caught. 746 of 10 000 rows are reported separately as
**structurally blind**: TD1 has no composite check digit, and a handful of TD3 offsets are
blind to a recomputed check digit because the composite delta is identically 0 mod 10 there
(DOB offsets 14/15/17/18, expiry 22/25). Those are properties of ICAO 9303, not bugs. They are
the reason the other layers exist. Quoting "100% of 10 000" would be a lie we could prove.

**2. The macro model is synthetic and says so everywhere.**
`eval/models/svm_print_v1_synthetic.json`, 13 718 bytes, held-out macro-F1 0.847 **on
synthetic textures**. That number is a property of `eval/tools/synth_macros.py`; change the
generator and it moves. It is labelled `SYNTHETIC` in the file, the run id, the console, the
gate detail and the model card, and it is **never gate-eligible**. When real patches land in
`eval/data/macro/`, `SvmModelLoader.resolve` prefers the real model automatically.

**3. The face layer refuses rather than guesses.**
`blazeface_short.tflite` (Apache-2.0, hash-pinned) **was** obtained and runs real TFLite
inference on the desktop JVM. No face *embedder* could be sourced — every DESIGN §6 candidate
is deleted, unpublished, non-commercial-research-only, or 512-d. So `face` reports
`UNAVAILABLE` and the layer is fail-closed. **GREEN 1:1 is unreachable because there is no
embedder, not because the licence is undecided.** ⚠️ An earlier revision of this file said
"unreachable until a licence is decided"; that was already stale. The licence question **was**
decided on 2026-09-30 — a research-licensed embedder is acceptable **for the prototype only**
(`docs/spikes/01-face-model.md` §7 item 5) — and a second search round *under that decision*
checked four more candidates and obtained nothing. What remains is a **price tag**, not a
permission: email InsightFace, or train our own on data with clear rights (STATUS R-A).
`docs/spikes/01-face-model.md` has the matrix. Do not let anyone "fix" this by generating
plausible vectors.

**4. Fusion is fail-closed, which means the console returns GREY a lot.**
That is correct (AGENTS.md §4, FUSION.md §7) and it is why the demo looks cautious. Do not
relax it to make the demo look decisive. The correct fix is real data, not a looser gate.

**5. `verify-offline.sh` is a name-resolution gate, not a build — but it is no longer the only
compiler in play.**
It compiles the SDK-free subset against `:core` and runs **201** tests (measured 2026-10-03; it
read 189 on 2026-09-30), then compiles the Android-facing sources against hand-written stubs. That
is how nine defects were found in the Android detector. ⚠️ **Two 2026-10-03 updates, and both cut
in opposite directions:** (a) `assembleDebug`/`assembleRelease` now succeed, so the module *has*
been through a real AGP/aapt2/d8/R8/Compose build and the stub signatures are no longer the only
thing standing between the code and a real compiler — but they are still *our reading* of
`Context`/`Log`/`Interpreter`, and **nothing has been run on hardware**, so no runtime behaviour
is verified; (b) **`:app-android:test` does not compile** (160 errors), which means this script is
currently the **only** thing that runs the two `ml/` parity suites. **Its own closing summary line
is now stale** — it says aapt2/d8/R8/Compose and the real SDK signatures "are all still
unverified", which `assembleDebug` has disproved. The 201/201 result is real; the summary is not.

**6. Verhoeff was wrong and is now generated, not transcribed.**
It was running `c = D[c][digit]` with no `P` table — D5 multiplication standing in for the
checksum, so genuine Aadhaar numbers were rejected. The tables are now built from the D5
definition and pinned by an `init` self-check. No Aadhaar test vector is committed: every
number on the open web is a real person's government identifier. The published `236 → 3`
worked example is the external anchor. **Do not add a "known" Aadhaar number from memory** —
that is exactly the fabrication the eval protocol exists to prevent.

---

## 5. What was never done, and why that is the right list

| Not done | Why |
|---|---|
| ~~`:app-android` compiled~~ → **DONE 2026-10-03** | Was "no Android SDK on this machine". An SDK is now installed and the module compiles and packages. **The one-character root cause was `app-android/build.gradle.kts` line 1 starting with `#`** — a Groovy comment marker, parsed as source by the Kotlin script compiler. With that fixed, plus `compileSdk` 34→35 (an androidx `minCompileSdk` requirement, `minSdk 26`/`targetSdk 34` unchanged) and `splits.abi`, `assembleDebug`/`assembleRelease`/`checkApkSize` all pass |
| **`:app-android` on a device** | **Still not done, and this is the real remaining gap.** No `adb` on `PATH`, no handset. `scripts/airplane_install_test.sh` reaches step D and stops. NFR-S1's *installable-offline* half, NFR-R1's 50 crash-free runs and SPEC §6 M1 have no exit |
| **A `.aab` / Play Store bundle** | ⛔ **Not produced, and currently not producible.** `bundleRelease` fails on AGP 8.9.2 with `splits.abi` enabled (`buildReleasePreBundle` → `Sequence contains more than one matching element`, reproduced with `isUniversalApk` both false and true). The alternative — drop splits, keep bundling — returns the release artefact to **77.53 MB**, over the 35 MB budget, and `x86_64` alone is 35.95 MB. **Delivery is APK-only until AGP is upgraded.** CI step 8 was changed to delegate to `checkApkSize` |
| **`:app-android:test`** | ⛔ **Broken since `06bd654`, unrelated to the build fixes.** 160 compile errors: `ml/BlazeFaceDesktopParityTest.kt` (150) and `ml/BlazeFaceInputTest.kt` (10) import `:platform`'s JVM classes from an Android test source set. `verify-offline.sh` covers both suites on a bare JVM |
| Android/desktop inference parity | Needs both halves. The decode is proven bit-identical between two *implementations*; device parity is unproven. |
| Any real macro or face data | Needs physical documents, the clip, and consented capture. Longest lead time in the project. |
| A face embedder | Licensing wall — see §4.3. |
| UIDAI production keys | Never obtained. The signed-QR layer is only ever exercised against TEST keys and a harness stub verifier. |
| `thresholds.v1.json` | Does not exist. **36** thresholds in `ThresholdRegistry` are at **untuned defaults** (counted from the enum, 2026-09-30). |
| Lint green | Both tasks exist and are wired, and **both are red**: ktlint **4 267** findings, detekt **1 349** (1 004 = **74.4%** `MagicNumber`). Re-measured **2026-10-03** in the source checkout; reproduced identically across two consecutive `ktlintCheck --continue` runs. ⚠️ **`ktlintCheck` without `--continue` under-reports (3 130)**. These are working-tree numbers on a dirty tree, not commit-pinned — see the drift note in STATUS §0. Deliberately detached from `check`; see §6. **`:app-android` is not in the linted module list**, so its 32 Kotlin files are unlinted. |
| **A green PII-scrubber gate** | ⚠️ **CLOSED 2026-10-03.** `core/.../log/PiiScrubber.kt` exists, 7 suites / 111 tests green, and the CI step is **blocking** (no `continue-on-error`). What remains is routing every call site through it — the script says so itself |
| **A green CI run** | ⚠️ **Newly added 2026-09-30.** Two runs exist and both are red, at the first step, on `./gradlew: Permission denied` (exec bit not committed — §9). Nothing in this repository's Kotlin has ever been compiled by CI. |
| SPECIMEN artwork, the clip, calibration, red-team day, failure gallery, demo rehearsal | All require hands and hardware. `docs/REDTEAM.md` has never been run. |
| A second reviewer | Bus factor is 1. `HANDOFF.md` §2 says what that costs. |
| Desktop distribution | ⚠️ **No longer on this list.** `installDist`/`distZip`/`distTar` build and the packaged launcher runs; see §4.7. A genuinely clean-machine install is still deferred. |

---

## 6. Two gates that are red on purpose

**`check_no_magic_thresholds.sh` → 175 findings.** The genuine hits are the Verhoeff group
constant, ASCII offsets and `MAX_PLAUSIBLE_AGE`. They exist because `thresholds.v1.json` does
not. Do **not** delete the script, add to its allowlist to reach green, or commit a baseline —
that is gate-weakening (AGENTS.md §5, §8). Fix the registry first.

**`ktlintCheck` / `detekt` exist but are detached from `check`.** Both were wired properly
(this build previously had neither task, and `AGENTS.md` §1 advertised both — `docs/STATUS.md`
carried the *wrong* half of that story and has now been corrected). Re-measured **2026-10-03** in
the source checkout: `sh gradlew ktlintCheck --continue` reports **4 267** findings across **12 of
35** ktlint Check tasks, `sh gradlew detekt` reports **1 349**, of which **1 004 (74.4%) are
`MagicNumber`**. Per-module detekt: `:core` 701, `:eval` 470, `:app-desktop` 88, `:platform` 79,
`:ui` 11. Per-module ktlint: `:core` 1 854, `:eval` 987, `:app-desktop` 792, `:platform` 341,
`:ui` 293. Top ktlint rules: `standard:function-signature` 1 287,
`standard:multiline-expression-wrapping` 1 082, `standard:argument-list-wrapping` 816,
`standard:class-signature` 302. ⚠️ **Two counting traps, both of which have already produced a
wrong number in this file's history:** (a) `ktlintCheck` **without `--continue`** stops at the
first failing task and prints **3 130**; (b) a `…/src/`-scoped grep misses the 9 violations in
`.kts` build scripts (`app-desktop/build.gradle.kts` 5, `platform/build.gradle.kts` 4). These are
**working-tree** numbers on a heavily dirty tree, not commit-pinned — **quote them with the date
and say "working tree", and expect drift.** `:app-android` is deliberately not linted.
The three steps to finish are in the `TODO(M2,@build)` in `AGENTS.md` §1. Until then CI runs both
**advisory with counts read from the tools' own reports** — visible, not blocking, and not hidden.

**`check_no_magic_thresholds.sh` → 175 findings** (was 171 on 2026-09-30). ⚠️ **`MagicNumber`
went *up*, not down, even though `fusion/thresholds.v1.json` now exists** (49 thresholds, name /
unit / default / floor / ceiling / tuning-data ref / owner — §5). The registry was the *diagnosis*
for this gate and is not yet the cure: `dev/kasoti/threshold/` is exempt from the scan, but the
registry work added code with literals in it. Two further facts: the registry file is
**untracked** (`git status` → `??`), so the "registry of record" exists only on one machine; and
**all 48 defaults are untuned**.

---

## 4.7 The desktop distribution — it exists, and it had a real defect

⚠️ **This file, `BUILD.md` §3, `STATUS.md` §4.5 and `ROADMAP.md` M2-d3 all said desktop packaging
did not exist. That was an overstatement built on a wrong task name.**
`:app-desktop:packageDistributionForCurrentOS` is indeed not a task and never was — but
`:app-desktop` applies the standard `application` plugin, so **`installDist`, `distZip` and
`distTar` exist and work.** Verified 2026-09-30: `installDist` succeeds and
`app-desktop/build/install/kasoti/bin/kasoti` executes and prints its command table.

**The defect that was hiding behind the wrong name, and it was worth finding.** The console's
model lookup is repo-relative (`SvmModelFile.DEFAULT_PATH`), and `application` packages only
`lib/` — **no data files**. At commit `9a04f40` the distribution therefore contained **zero**
`.json` and zero `.tflite` files while `eval/models/` held both. A packaged console found no
model, **abstained quietly on the print-process layer, and could return a different verdict from
a checkout console on the same document.** That is precisely the "the model did not load" bug the
troubleshooting table describes, and it is invisible until someone runs the artefact instead of
the Gradle task.

Fixed in the working tree on 2026-09-30: `installDist` is a `Sync` that copies the model and its
model card to `share/kasoti/models/`, so `distZip`, `distTar` and `shipConsole` all inherit it
from one place and there is no second packaging path that can forget it; and a `ModelLocator`
makes the search order explicit (`--svm` → environment → packaged → repository) and **prints the
origin**, so "shipped inside this installation" is distinguishable from "found in a source
checkout" instead of both looking like silence.

**Check it in one command:** run the packaged launcher's `help` and read the `macro model:` line.

**Still not proved:** an install on a machine with no source checkout at all. The distribution was
exercised from inside `build/install/`, so the repository fallback was never fully removed
(SPEC §6 M2). `bundleOffline` remains not a task in this tree.

---

## 7. Defects found and fixed in this session, worth knowing about

Recording these because each was found by a test that was designed to fail, and each says
something about where the risk is.

- **Verhoeff had no `P` table.** Load-bearing for Aadhaar. Found by the eval harness's
  unit-parity gate, not by review.
- **The audit chain's `canonical()` did not escape.** `findingCodes [A,B]` and `[A|B]`
  serialised identically, so a record could be swapped for its twin with every hash intact —
  invariant I6 **failing open**, the one direction it must not. Now goes through `CanonicalJson`
  with a real JSON array.
- **`verifyExternal(emptyList())` verified against a non-empty chain.** Deleting *every*
  decision was undetectable. Now requires matching lengths.
- **The Android BlazeFace binding had nine defects**, four reported and five found while
  verifying: wrong output tensor shape, a raw logit used as a confidence with no sigmoid, a
  single-output `run` on a two-output model, no anchor decode, a frame handed in as 0–255
  bytes at source resolution, interpreter settings that set nothing, a wrong-arity call, a
  KDoc comment the lexer rejected, and a `fun interface` assumption that does not hold.
- **`BUILD.md` §4 was factually wrong.** There is no first-party TFLite JVM artefact at all
  (`org.tensorflow:tensorflow-lite:2.17.0` is a 1 411-byte relocation stub to an AAR; no `<os>`
  classifier exists) and the bound `ai.djl.tflite` has natives for `linux-x86_64` and
  `osx-x86_64` only. **Windows and Apple-Silicon Mac are review-only.**
- **CI read the harness exit code from `$?`.** It always saw 1. Now parses the child code.
- **`check_no_magic_thresholds.sh` exited 141, not 1.** `| head -n 8` closed the pipe, the
  upstream stages died on SIGPIPE, and `set -o pipefail` + `set -e` aborted the script before
  it reached `exit 1` — so it reported a signal instead of its own verdict, and never printed
  the guidance it ends with. The limit now lives in a trailing `awk`, which reads all its
  input. Worth checking any other script that pipes into `head` under `pipefail`.
- **The MRZ headline printed `100.0000%%`.** `pct()` appended a literal `%` in its format
  string *and* concatenated another, so the trailing-zero trim could never reach the digits.
  Cosmetic, but it was in the one number a judge would read.
- **The demo classifier and fusion were correct; the *demo* was unreachable.** An untrained
  SVM was printing `OFFSET` as if it meant something. It now abstains to `UNKNOWN` with a low
  margin, which surfaces as `A-WORN-01` and a GREY verdict — the right answer to "we cannot
  read this document".

---

## 8. If you are picking this up, do this in order

1. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`, then the §2 table. If those five commands
   do not produce exactly those results, stop and find out why before changing anything.
2. Read `docs/STATUS.md` §7 — it names the next five tasks in order.
3. ~~**Install the Android SDK and run `:app-android:assembleDebug`.**~~ **Done 2026-10-03** —
   the SDK is installed and the module builds; the one-character root cause is in §5. **The
   remaining Android work is smaller and harder: put an APK on a real handset in airplane mode**
   (`scripts/airplane_install_test.sh --skip-bundle` already walks to step D and stops at
   "adb not on PATH"), **fix `:app-android:test`** (160 compile errors), and **decide what
   "delivery" means with no `.aab`** — APK-only is fine for a USB demo and not fine for Play.
   Until one of those happens, "the field app works" is still a claim.
4. **Start the macro capture.** It needs documents, the clip and consent, and nothing else
   unblocks the macro layer. The collector (`dev.kasoti.platform.collector.MacroCollector`)
   writes DATA.md §3's exact layout and is ready to use.
5. Decide the face embedder **price** — email InsightFace (even open-sourced models have routed
   through `recognition-oss-pack@insightface.ai` since 2025-11-24, at an unknown price), or train
   our own on data with clear rights, or ship detector-only and say so in every narrative.
   ⚠️ The *licence* question is already decided (prototype-only, 2026-09-30); leaving **that**
   undecided is no longer on the table.
6. When you add a `FindingCode`, the `i18n` test will fail until English and Hindi strings
   exist. That is intentional and is the cheapest guard in the repo.

---

## 9. Provenance

- Branch `main`, **2 commits** (`06bd654`, `9a04f40`). ⚠️ **This section previously said
  "8 commits, no remote configured" and "`git remote -v` is empty, so CI has never run". Both
  halves are false and were corrected on 2026-09-30.** Measured: `git remote -v` →
  `origin https://github.com/Leo-Expose/Kasoti.git`; `git branch -vv` → `* main 9a04f40
  [origin/main]`; `git rev-list --left-right --count main...origin/main` → `0  0`.
- **CI has run — twice — and both runs failed**, so "no green run" is the accurate statement,
  not "no run". `gh run list`: run `36663026772` on `9a04f40` (2026-09-30T03:08:03Z, 26 s) and
  run `36658909646` on `06bd654` (2026-09-30T02:14:22Z, 36 s), both `conclusion: failure`. Both
  died at the **first** step (`:core:jvmTest`) with `./gradlew: Permission denied` →
  `##[error]Process completed with exit code 126.` **No Kotlin code has ever been compiled on CI.**
  Cause is the exec bit: git records all 340 tracked files as `100644` (R-Q), so on a fresh Linux
  runner `gradlew` is not executable. Reproduced locally on a real `git clone` on 2026-09-30.
  **This is the cheapest high-value fix in the project.**
- The repository sits on an **exFAT volume that ignores `chmod`** (every file reports 755 locally
  regardless of intent). `scripts/provision.sh` verifies the mode of a written secret and warns
  loudly rather than assuming. Treat 0600 on this filesystem as not-enforced — and treat the
  committed mode bits as unreliable, which is the same defect seen from the other side.
- `eval/runs/` is git-ignored (`.gitignore:59`, `!eval/runs/**/.gitkeep`), so run records are
  local. **Confirmed on 2026-09-30 that `eval-20260930-smoke-653a` — the run the public browser
  demo cites — does exist here**, as do `eval-20260929-smoke-a1c7` and
  `eval-20260929-full-fd65`. **If you need a run in a commit, force-add it** — and expect the
  diff to be large.
- All work in this session was done without a second reviewer. AGENTS.md §3 item 7 and §4 are
  satisfied here only by self-review, which is a *check*, not an approval.
- **2026-10-03 re-measurement provenance.** Everything marked "measured 2026-10-03" above was run
  in this working tree on branch `main` at HEAD `9a04f40` with **40 modified tracked files and 14
  untracked paths** — the Android build fixes, the PII scrubber, the threshold registry and the
  desktop distribution work are all **uncommitted**. So those numbers describe *this tree*, not a
  commit, and **not any clone**. UTC at the time of measurement was 2026-10-02T19:4xZ; the
  calendar date used throughout is the local one (IST, UTC+05:30). The earlier 2026-09-30 figures
  that were commit-pinned from a fresh clone of `9a04f40` are kept beside the new ones rather than
  deleted, because the difference between them is itself information: it is what "uncommitted
  work" looks like from inside the tree.
- **Two files in this tree contain claims this pass found to be false and did not edit**, because
  this pass is documentation-only and those are build/tooling files:
  `app-android/build.gradle.kts` (its "VERIFICATION STATUS" header still says the module has
  never been compiled, still quotes 145/189 tests, and still says `provision.sh` publishes an
  `.aab`) and `app-android/tools/verify-offline.sh` (its tier-2 closing line still says aapt2,
  d8, R8, Compose and the real SDK signatures are unverified). Both are listed in `STATUS.md` §2.
