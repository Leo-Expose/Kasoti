# Contributing to KASOTI

KASOTI is an SIH prototype. That shapes
everything below: the process is short, the gates are real, and nobody has time
to read a 40-page contribution guide. **AGENTS.md is binding** — read it before
you read this. Where this file and AGENTS.md disagree, AGENTS.md wins and this
file is the bug.

> **Two consequences of "solo", stated up front so you are not misled by the rest of
> this file.** (1) There is no team to review your work and no PR workflow in
> practice: `git remote -v` is empty, so PRs cannot be opened and CI has never run
> (`docs/STATUS.md` R-J, R-S). §1's branch strategy is the *intent*. (2) The
> "reviewer" in §2 item 7 and §4 is **you**, on a later pass. Write that review as
> if a hostile stranger would read it, because nobody else will.

Read in this order (docs/HANDOFF.md §3): `docs/README.md` → `docs/SPEC.md` →
`docs/DESIGN.md` → `AGENTS.md`.

---

## 1. Branch strategy

| Branch | Rule |
|---|---|
| `main` | **Gated.** Never push a commit directly to `main` while `main` is the integration branch. Every change lands by PR, and CI must be green (or an explicit, linked waiver recorded in HANDOFF.md §7). ⚠️ **Not currently exercisable** — there is no git remote, so there is no `main` to protect and no PR to open. Treat the column as the convention to adopt when a remote exists. |
| `mX/*` feature branches | One branch per roadmap item, named after the milestone task id: `m0/macro-svm`, `m1/android-e2e`, `m2/desktop-console`. Short-lived (days, not weeks). |
| `fix/*` | Short bug fixes off a milestone branch. A P0 bug (crash / data loss / false-RED path) gets its own branch **today** — AGENTS.md §9 is a stop-line, features wait. |
| `chore/*` | Toolchain, CI, docs, scripts. No product behaviour. |
| `spike/NN-*` | Timeboxed experiments. ≤2 open per milestone (AGENTS.md §7). A spike that reaches its timebox undecided goes to the lead with a written recommendation, not another week of research. |

Branch naming convention, full: `m<N>/<task-id>-<short-slug>` where `<task-id>`
is the ROADMAP.md row you are working on. Put the task id in the PR title so
the milestone board can be updated mechanically.

**Before opening a PR:** rebase on the latest `main`, re-run `sh gradlew
:core:jvmTest` locally (AGENTS.md §1 — it takes seconds and it is the cheapest
gate we have), and make sure you did not touch a file outside the areas you own.
⚠️ **`sh gradlew`, not `./gradlew`**, until the exec bit is committed: git records
all 340 tracked files as mode `100644`, so on any fresh clone `./gradlew` returns
`permission denied`. That is also why both CI runs are red (`docs/STATUS.md` R-J, R-Q).

**Before calling anything done**, on a solo project, add one step the team version did not need:
re-read your own diff *as a reviewer* against §4 below, and record the verdict — including the
ones you talked yourself out of. `docs/STATUS.md` §8 asks for the same thing, and it is the only
review mechanism that exists here.

---

## 2. Definition of Done (AGENTS.md §3)

Every PR must satisfy all seven. "Done" is not a judgement call.

1. **Tests added and green.** `:core` ≥ 80 % line coverage on the packages you
   touched. At least one **adversarial or mutated** case, not just the happy
   path — a test that only proves the obvious is a demo, not a test.
2. **Eval impact run.** `sh gradlew :eval:run --args="smoke"` minimum. Put the
   metric deltas in the PR body. If a number moved, say which way and why.
3. **Thresholds registered.** Any tunable goes into the registry with name,
   default, unit, tuning-data reference and owner. Magic numbers elsewhere are
   a review fail, and CI now **fails the build** on one
   (`scripts/check_no_magic_thresholds.sh`, blocking as of 2026-10-03). Fix it in
   the registry — never by widening the script's allowlist.
4. **Strings added** — English **and** Hindi. Tag Nepali as a todo if the
   feature is P1. Never show a raw string to the UI; every finding code needs
   both languages.
5. **Docs touched** as applicable — SPEC / DESIGN / FUSION / EVAL / THREAT.
   **The PR lists which files changed and why.** Docs are code (AGENTS.md §8).
6. **Demo-mode and airplane behaviour considered.** If your feature needs the
   network, you have not finished it — no KASOTI verdict may require one.
   Say explicitly in the PR whether demo-mode and airplane mode still work.
7. **Reviewed by the owner of the touched area** (docs/HANDOFF.md §2 roles
   table), and lint clean. ⚠️ **Still unmeetable, for two different reasons — and the first half of this paragraph used to state a false one.** It used to say "`ktlintCheck` and `detekt` are not Gradle tasks in this tree". **They are**: both are wired (root `build.gradle.kts` applies them to `:core :platform :app-desktop :eval :ui`) and both **run** — they simply **fail**, and they are deliberately detached from `check` so they report without blocking. So: (a) the only reviewer is you (§"solo" above), which no amount of tooling fixes; and (b) "lint clean" **can** now be checked and **is not clean** — `sh gradlew ktlintCheck --continue` reports **4 267** violations and `sh gradlew detekt` reports **1 349** weighted issues (measured 2026-10-03), and `:app-android` is not in the linted module list at all. NFR-M1 ("detekt+ktlint clean") is genuinely unmet; clearing it is the `TODO(M2,@build)` in `AGENTS.md` §1. **Do not** reach green by disabling a rule or committing a baseline — that is weakening a gate. Run the tasks, report the numbers, and say in the description that lint is red and by how much.

---

## 3. New dependencies need an ADR (AGENTS.md §5)

Adding a dependency without a written justification is a **review fail**. The
ADR does not have to be a document — five lines in the PR description is
enough — but it must answer all five:

```
1. WHAT     the exact group:artifact and version
2. WHY      what it buys us, in one sentence, with the alternative we rejected
3. SIZE     bytes added to the APK / bundle (measure it; do not estimate)
4. LICENCE  SPDX id, and the row added to THIRD_PARTY.md
5. ALT      the alternative considered and why it lost
```

Extra rules for dependencies:

- **Every version goes in `gradle/libs.versions.toml`.** Nothing may pin a
  version inline in a module's `build.gradle.kts`.
- **No network client may enter `:core`.** Ever. `scripts/check_no_network_in_core.sh`
  is a hard CI gate; if a new dependency drags a transport into `:core`, move
  the call behind an `expect/actual` in `:platform` or drop the dependency.
- **No new dependency in `:core` without the lead's OK.** `:core` is meant to be
  near-zero-dependency pure logic.
- **Re-weigh it before the finals.** Unused entries in the version catalog are
  dead weight (AGENTS.md §5, "dead flags older than one milestone") and unused
  licences are unverified licences (THIRD_PARTY.md).

---

## 4. Reviewer checklist (AGENTS.md §4)

The reviewer pastes this into the PR and fills in the verdict. A reviewer who
ticks a box they did not check is worse than a reviewer who blocks.

```markdown
## Review verdict — <PR> — <reviewer> — <date>

- [ ] Verdict path has zero network (trace it, don't assume)
- [ ] No PII in logs/caches (ran the scrubber test with the new fields)
- [ ] GREY / fail-closed on low quality (no silent default-pass)
- [ ] Thresholds registered, not hardcoded
- [ ] Tests include at least one adversarial/mutated case
- [ ] Metrics claim? harness command + output attached
- [ ] Size impact noted (APK/bundle delta)
- [ ] i18n keys added; RTL and large font not broken (screenshot for UI PRs)

Verdict: APPROVE / CHANGES REQUESTED / BLOCKED
Blocking issues:
```

Notes on the two that people skip:

- **"No PII in logs"** is checked by running `./scripts/pii_scrubber_test.sh`,
  which fails loudly if no scrubber exists. ⚠️ **As of 2026-10-03 it PASSES** —
  `core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt` exists, seven suites
  run 111 tests, and the CI step is now **blocking**. If you see it fail, read the
  failure before touching it: a red PII gate is a privacy incident, not a flake.
  ⚠️ **it needs `JAVA_HOME` set**, or it fails with a bare `What went wrong: 27`
  that looks like a scrubber failure and is not one. (The gap that remains is
  coverage, not existence: the gate proves the *rules* work, not that every
  log/cache/crash field routes through them.)
- **"Size impact"** needs a measured number, not an estimate. If you cannot
  measure it, say so in the PR — an unmeasured size claim is a review fail in
  the same way an unmeasured accuracy claim is. ✅ **Since 2026-10-03 Android sizes
  ARE measurable**: `sh gradlew :app-android:checkApkSize` reports every produced
  per-ABI APK against the 35.0 MB budget (measured 2026-10-03: 33.39 MB debug
  arm64, 26.56 MB debug armeabi-v7a, 22.97 MB release arm64, 16.14 MB release
  armeabi-v7a). Cite that task and its output. ⚠️ Two Android sizes are still
  **not** measurable and must be recorded as such rather than estimated: the
  **`.aab`** (there is none — `bundleRelease` fails on AGP 8.9.2 with ABI splits,
  so delivery is APK-only), and anything requiring a **device** (no `adb`, no
  handset: install size, battery, crash-freedom).

---

## 5. Local setup and commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # or your Temurin 17 — Gradle needs JDK 17

sh gradlew :core:jvmTest                         # fast logic tests — 459 tests
sh gradlew :ui:test                              # 55 tests
sh gradlew :platform:jvmTest                     # 137 tests
sh gradlew :app-desktop:test                     # 206 tests
sh gradlew :eval:test                            # 39 tests   (896 across the five)
sh gradlew :app-desktop:run                      # headless post-console
sh gradlew :eval:run --args="smoke"              # fixtures harness (~60 s) — EXITS 4
sh gradlew :eval:run --args="full"               # full corpora — EXITS 4

./scripts/check_no_network_in_core.sh            # hard gate — GREEN (93 files, 2026-10-03)
./scripts/check_no_magic_thresholds.sh           # hard gate — GREEN (69 files, 0 findings) and BLOCKING
                                             # (was RED at 175 lines earlier on 2026-10-03).
                                             # Green is not complete: 17 file paths are exempt by
                                             # path, so a reviewer still reads diffs that touch them.
./scripts/pii_scrubber_test.sh                   # GREEN (7 suites / 111 tests) and BLOCKING

# android — needs an SDK in local.properties or ANDROID_HOME
sh gradlew :app-android:assembleDebug            # 2 APKs: 33.39 MB / 26.56 MB
sh gradlew :app-android:checkApkSize             # the 35 MB gate over every artefact
# ⛔ :app-android:bundleRelease fails (AGP 8.9.2 x ABI splits) -> no .aab, APK-only
# ⛔ :app-android:test fails: 160 compile errors in the two ml/ parity test files

# hardware
cd hardware && pdflatex calibration_card.tex    # prints the calibration card
```

All of the above were re-measured on 2026-09-29; `docs/STATUS.md` §1 is the
authoritative table and `docs/BUILD.md` §3 the authoritative command list. Note
that `:eval:run` exits **4**, not 0: a skipped suite (always the device gates, without a
device) makes the run INCOMPLETE. That is the designed answer, and it is also why CI's
`verify` job cannot currently go green.

`docs/STATUS.md` is the living "what actually works right now" file. If your
change flips something in it, update it in the same PR.

---

## 6. Commit messages

Short, imperative, scoped. The repo history uses Conventional Commits, so keep
doing that:

```
feat(core): ICAO 9303 TD1/TD3 parse with per-field fail attribution
fix(eval): smoke corpus leaked same-source patches across the split
chore(ci): guard lint step so it runs strictly once the plugins land
docs(hardware): calibration card rev A, 4 wedges + rosette explainer
```

One logical change per commit. **Never** mix a refactor with a feature — if the
diff makes review hard, split it (AGENTS.md §8, <300 lines per work block).

---

## 7. What will get a PR blocked

Straight from AGENTS.md §5 — these are not style opinions:

- a network client anywhere in `:core`
- hand-typed accuracy or performance numbers without an eval run id — and
  "without a run id" includes *citing a run id that does not resolve to a
  directory under `eval/runs/`*, which is exactly the error found in
  `docs/STATUS.md` during the 2026-09-29 audit
- a new dependency with no ADR and no `THIRD_PARTY.md` row
- a weakened gate (threshold, GREY floor, auth) to make a test pass — report
  the conflict instead
- a raw biometric persisted by default
- `// TODO` without an owner and a milestone
- commented-out code
- a false-RED path that is not fixed before new features start

---

## 8. Data and privacy — non-negotiables

- **No real third-party IDs, ever.** SPECIMEN and consented teammate data only
  (docs/DATA.md §1). A violation is delete-plus-incident-note, not a quiet fix.
- **Macro patches only.** 2 mm texture crops, visually checked to contain no
  readable characters or faces before commit.
- **Face data is consented and cropped.** Consent covers capture, on-device and
  repo storage of crops, eval use, and deletion on request within 24 h
  (DATA.md §6). Deletion is `./scripts/purge_volunteer_data.sh <volunteer-id>`.
- **No production keys, ever**, in any artefact. QR test vectors are signed
  with the test keypair only.
- **Never commit** model binaries, secrets, `deployment_secret.key`, or
  provisioning output.

---

## 9. Getting help

There is no standup and no one to ask. Substitute, in this order:
- **Blocked on something you wrote >30 minutes ago** → write the file and the line
  into `docs/STATUS.md` §5 as a numbered risk with an owner. Naming it in prose
  does not count; a row does, because §8 makes the register the thing you
  re-read. Do not silently refactor around it.
- **A command in AGENTS.md §1 does not work** → **fix AGENTS.md §1** as part of
  the PR that changed it. A stale command list costs more than a stale comment.
  This is not hypothetical: a docs audit found `ktlintCheck`, `detekt` and
  `bundleOffline` advertised there and not present in the build.
- **Unsure whether something is P0 (stop-line)** → it probably is. Stop the line,
  say so, and re-rank it yourself the way a lead would.
