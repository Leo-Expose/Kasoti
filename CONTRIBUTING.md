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

**Before opening a PR:** rebase on the latest `main`, re-run `./gradlew
:core:jvmTest` locally (AGENTS.md §1 — it takes seconds and it is the cheapest
gate we have), and make sure you did not touch a file outside the areas you own.

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
2. **Eval impact run.** `./gradlew :eval:run --args="smoke"` minimum. Put the
   metric deltas in the PR body. If a number moved, say which way and why.
3. **Thresholds registered.** Any tunable goes into the registry with name,
   default, unit, tuning-data reference and owner. Magic numbers elsewhere are
   a review fail, and CI has a check for it
   (`scripts/check_no_magic_thresholds.sh`).
4. **Strings added** — English **and** Hindi. Tag Nepali as a todo if the
   feature is P1. Never show a raw string to the UI; every finding code needs
   both languages.
5. **Docs touched** as applicable — SPEC / DESIGN / FUSION / EVAL / THREAT.
   **The PR lists which files changed and why.** Docs are code (AGENTS.md §8).
6. **Demo-mode and airplane behaviour considered.** If your feature needs the
   network, you have not finished it — no KASOTI verdict may require one.
   Say explicitly in the PR whether demo-mode and airplane mode still work.
7. **Reviewed by the owner of the touched area** (docs/HANDOFF.md §2 roles
   table), and lint clean. ⚠️ **Currently unmeetable in two ways:** the only
   owner is you (§"solo" above), and **lint is not wired** — `ktlintCheck` and
   `detekt` are not Gradle tasks in this tree, so "lint clean" cannot be checked
   at all. `docs/STATUS.md` §4.3, and it is a stated SPEC NFR (NFR-M1) that is
   currently unmet. Substitute `./gradlew :core:jvmTest` for the moment and say
   in the description that lint was skipped and why.

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
  which fails loudly if no scrubber exists. It **still fails**, because there is
  no scrubber in `:core`. That failure is real; do not review it away. (Nuance
  worth knowing so you don't over- or under-claim: a working `LogScrubber` and
  its adversarial tests *do* exist in `:app-desktop`; the gate looks in
  `:core`/`:platform`/`eval/src`, and the shared path is what needs one.)
- **"Size impact"** needs a measured number, not an estimate. If you cannot
  measure it, say so in the PR — an unmeasured size claim is a review fail in
  the same way an unmeasured accuracy claim is. ⚠️ Right now **no APK exists**,
  so no size can be measured for Android work at all; "not measurable, no
  SDK/device" is the correct entry.

---

## 5. Local setup and commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # or your Temurin 17 — Gradle needs JDK 17

./gradlew :core:jvmTest                          # fast logic tests — 270 tests
./gradlew :ui:test                               # 44 tests
./gradlew :platform:jvmTest                      # 137 tests
./gradlew :app-desktop:test                      # 169 tests
./gradlew :app-desktop:run                       # headless post-console
./gradlew :eval:run --args="smoke"               # fixtures harness (~60 s) — EXITS 4
./gradlew :eval:run --args="full"                # full corpora — EXITS 4

./scripts/check_no_network_in_core.sh            # hard gate — currently GREEN (75 files)
./scripts/check_no_magic_thresholds.sh           # advisory — currently RED (171 lines)
./scripts/pii_scrubber_test.sh                   # currently FAILS: no :core scrubber

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
