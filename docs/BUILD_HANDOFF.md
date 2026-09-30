# KASOTI — Build Handoff (2026-09-30)

> **What this file is.** `HANDOFF.md` is the process doc (roles, rituals, who owns what).
> `STATUS.md` is the truth doc (what works, what is red). This is the third thing: **the state
> of the build as it was left**, what was verified, what was deliberately not done, and the
> traps that will cost someone an hour if they are not written down. Read it before touching
> anything, then read `STATUS.md` §7 for the next five tasks.
>
> Everything below was produced in one continuous solo session. The docs were audited
> afterwards and 23 false claims were corrected — so where this file and an older doc
> disagree, this file is newer and both are committed. Trust the code and the run records.

---

## 1. The one-paragraph version

A complete, tested logic layer exists: ICAO 9303 MRZ with a 10 000-row mutation corpus,
Verhoeff, date and format checks, a secure-QR verification path, FFT/LBP print forensics with
a trained linear classifier, face math and detection curves, a file-backed crossing diary with
alias/travel/facilitator rules, the `kasoti-sync/1` merge protocol, the full FUSION.md verdict
engine, a hash-chained audit log, English and Hindi strings, and an evaluation harness that
refuses to pass a run it cannot justify. **691 Gradle tests and 189 SDK-free Android tests,
zero failures.** A desktop console runs the whole cascade. An Android field app is written but
has never been compiled. There is no face embedder — a licensing wall, not a backlog. The
macro model is trained on synthetic textures and says so on every surface. The macro and face
datasets are zero rows.

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
| `./gradlew :core:jvmTest :platform:jvmTest :app-desktop:test :eval:test :ui:test` | `BUILD SUCCESSFUL` · 691 tests · 0 failures (core 302, platform 137, app-desktop 169, eval 39, ui 44) |
| `./gradlew :eval:run --args="smoke"` | harness **exits 4** = INCOMPLETE · 12 gates, 11 pass, 1 device-skip, 0 fail |
| `app-android/tools/verify-offline.sh` | `189 tests found / 0 failed` · exit 0 |
| `./scripts/check_no_network_in_core.sh` | exit 0, ~78 files scanned |
| `./scripts/check_no_magic_thresholds.sh` | **exit 1**, 171 findings — correctly red, see §6 |
| `./gradlew :app-desktop:run --args="sample"` | writes a zero-PII specimen to `demo-specimen/` and prints its field table; screen it with `--fields demo-specimen/specimen.json` |

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
| `:app-android` | 10 632 | **only with an SDK** | Written, **never compiled**. |
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
`UNAVAILABLE` and the layer is fail-closed. **GREEN 1:1 is unreachable until a licence is
decided.** `docs/spikes/01-face-model.md` has the matrix; the decision is recorded there and
in `STATUS.md` §2.1. Do not let anyone "fix" this by generating plausible vectors.

**4. Fusion is fail-closed, which means the console returns GREY a lot.**
That is correct (AGENTS.md §4, FUSION.md §7) and it is why the demo looks cautious. Do not
relax it to make the demo look decisive. The correct fix is real data, not a looser gate.

**5. `verify-offline.sh` is a name-resolution gate, not a build.**
It compiles the SDK-free subset against `:core` and runs 189 tests, then compiles
`TfliteFace.kt` against hand-written stubs. That is how nine defects were found in the Android
detector. But the stub signatures are *our reading* of `Context`/`Log`/`Interpreter` — a member
we read wrong type-checks here and fails on a device. Expect the first real `assembleDebug` to
need fixes.

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
| `:app-android` compiled | No Android SDK on this machine. External dependency. |
| Android/desktop inference parity | Needs both halves. The decode is proven bit-identical between two *implementations*; device parity is unproven. |
| Any real macro or face data | Needs physical documents, the clip, and consented capture. Longest lead time in the project. |
| A face embedder | Licensing wall — see §4.3. |
| UIDAI production keys | Never obtained. The signed-QR layer is only ever exercised against TEST keys and a harness stub verifier. |
| `thresholds.v1.json` | Does not exist. 34 thresholds in `ThresholdRegistry` are at **untuned defaults**. |
| Lint green | ktlint 3 844 findings, detekt 1 308 (74% `MagicNumber` — the literals that file should hold). Deliberately detached from `check`; see §6. |
| SPECIMEN artwork, the clip, calibration, red-team day, failure gallery, demo rehearsal | All require hands and hardware. `docs/REDTEAM.md` has never been run. |
| A second reviewer | Bus factor is 1. `HANDOFF.md` §2 says what that costs. |

---

## 6. Two gates that are red on purpose

**`check_no_magic_thresholds.sh` → 171 findings.** The genuine hits are the Verhoeff group
constant, ASCII offsets and `MAX_PLAUSIBLE_AGE`. They exist because `thresholds.v1.json` does
not. Do **not** delete the script, add to its allowlist to reach green, or commit a baseline —
that is gate-weakening (AGENTS.md §5, §8). Fix the registry first.

**`ktlintCheck` / `detekt` exist but are detached from `check`.** Both were wired properly
(this build previously had neither task, and `AGENTS.md` §1 advertised both). ktlint reports
3 844 findings, 2 886 of them in files a scoped change may not touch; detekt's 1 308 are 74%
`MagicNumber`. The three steps to finish are in the `TODO(M2,@build)` in `AGENTS.md` §1. Until
then CI runs both **advisory with counts read from the tools' own reports** — visible, not
blocking, and not hidden.

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
3. **Install the Android SDK** (`local.properties` → `sdk.dir=`, or `ANDROID_HOME`) and run
   `./gradlew :app-android:assembleDebug`. Nothing about the field app has ever been compiled.
   This is the single biggest unknown and the longest pole.
4. **Start the macro capture.** It needs documents, the clip and consent, and nothing else
   unblocks the macro layer. The collector (`dev.kasoti.platform.collector.MacroCollector`)
   writes DATA.md §3's exact layout and is ready to use.
5. Decide the face embedder licence, or accept that 1:1 stays UNAVAILABLE. Either is fine;
   leaving it undecided is not.
6. When you add a `FindingCode`, the `i18n` test will fail until English and Hindi strings
   exist. That is intentional and is the cheapest guard in the repo.

---

## 9. Provenance

- Branch `main`, 8 commits, no remote configured. `git remote -v` is empty, so **CI has never
  run** — `ci.yml` is reviewed YAML, not observed behaviour.
- The repository sits on an **exFAT volume that ignores `chmod`** (every file reports 755).
  `scripts/provision.sh` verifies the mode of a written secret and warns loudly rather than
  assuming. Treat 0600 on this filesystem as not-enforced.
- `eval/runs/` is git-ignored, so run records are local. The run id cited in this file,
  `eval-20260930-smoke-653a`, exists on this machine only. **If you need a run in a commit,
  force-add it** — and expect the diff to be large.
- All work in this session was done without a second reviewer. AGENTS.md §3 item 7 and §4 are
  satisfied here only by self-review, which is a *check*, not an approval.
