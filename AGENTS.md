# KASOTI — AGENTS.md (working agreement for humans + coding agents)

> If you are an AI coding agent: obey this file first. If a request conflicts with it, say so and propose the compliant alternative.

## 1. Commands (must stay true; update file when they change)
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # Gradle needs JDK 17
./gradlew :core:jvmTest                    # fast logic tests (run before every push) — 302 tests
./gradlew :core:allTests                   # all core targets (JVM is the only one configured)
./gradlew :ui:test                         # 44 tests; NOT SDK-gated
./gradlew :platform:jvmTest                # 137 tests
./gradlew :app-desktop:test                # 169 tests
./gradlew :app-desktop:run                 # headless post-console, works from a checkout
./gradlew :eval:run --args="smoke"         # fixtures smoke (~60 s) — EXITS 4, see below
./gradlew :eval:run --args="full"          # full corpora — EXITS 4, see below
./gradlew :eval:run --args="parity"        # Android-vs-desktop; skips loudly without a device
# --- Lint tasks EXIST and are wired (root build.gradle.kts applies both plugins to
#     :core :platform :app-desktop :eval :ui). They are NOT yet blocking. ---
./gradlew ktlintCheck                     # ktlint 1.5.0 via plugin 12.3.0, ktlint_official style
./gradlew detekt                           # detekt 1.23.8, default ruleset + no config file
# TODO(M2,@build): make those two BLOCKING. Both are red on day one and neither is wired
# into `check`, on purpose — see docs/STATUS.md §4.3. To finish, exactly three things:
#   (1) ktlint: `./gradlew ktlintFormat` rewrites the whole tree at once (~3.8k findings,
#       2.9k+ of them in files that are not the formatter's to touch in the same PR). Give
#       the reformat its own commit, review it as a diff with no behaviour claim, then
#       re-run. (2) detekt: ~3/4 of its 1308 findings are MagicNumber — the literals
#       AGENTS.md §2 wants in fusion/thresholds.v1.json, a file that does not exist. Fix
#       the registry first, then triage the rest. Do NOT switch off a rule or commit a
#       baseline to get to green: that is weakening a gate (AGENTS.md §5, §8). (3) Once
#       both are clean, delete the `afterEvaluate` block in build.gradle.kts that detaches
#       them from `check` so `./gradlew check` lints again.
# ./gradlew bundleOffline        -> never an AGP task in this tree; there is no bundle.
# --- SDK-gated: need local.properties sdk.dir= or ANDROID_HOME. Never run: no SDK here. ---
# ./gradlew :app-android:installDebug
# ./gradlew :app-android:bundleRelease
./scripts/airplane_install_test.sh   # no-network install proof; EXITS 2 today (no APK exists)
```
**`:eval:run` exits 4, not 0, whenever a required suite is skipped** — and the device gates are
always skipped without a device. 4 = INCOMPLETE, which is the correct answer, not a broken
build. A run that returned 0 would imply a device was attached. Exit codes: 0 pass · 2 gate
failed · 3 INVALID (report-split tuning) · 4 INCOMPLETE. **Those are the *harness's* codes, not
gradlew's:** gradlew runs it in a child JVM, prints the child's code on stderr
(`> Process '…java'' finished with non-zero exit value 4`) and then exits **1** itself, so
`./gradlew :eval:run …; echo $?` prints 1, never 4. Anything reading these codes has to parse
that stderr line — `.github/workflows/ci.yml` does, and treats an unreadable code as a failure.

CI (`.github/workflows/ci.yml`) runs on every push: build + all five modules' tests + eval
smoke + lint + `:core` network-import ban + PII-scrubber test + the SDK-free Android proof +
APK/bundle size check. **Three facts about it that the file above must not paper over:** the
lint step runs `ktlintCheck` and `detekt` and they are **advisory, not blocking** — both are
red on day one (TODO above) — and the magic-threshold and PII-scrubber steps are advisory for
the same kind of reason, so `verify` can be green with three unlinted/red gates inside it;
the eval-smoke step now honours the harness's own exit codes, so **exit 4 no longer fails the
build** (2 and 3 still do); and `:app-android` is skipped entirely without an SDK, which the
job summary reports as SKIPPED rather than passed. **No CI run has ever happened** — there is no
git remote and no runner (`docs/STATUS.md` R-J, R-S), so every claim above is unproven.

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
- **Solo project (added 2026-09-30):** there is no lead, no second reviewer, and no CI. §3 item 7
  ("review by the owner of the touched area") and §4 are therefore satisfied only by self-review,
  which is a *check*, not an approval — say which one you did. §7's "leads pick" resolves to the
  single maintainer, and the decision it demands must be **written into `docs/HANDOFF.md` §7
  with a date and a reason**, because an undocumented solo decision is unreviewable by
  construction. See `docs/HANDOFF.md` §2.
