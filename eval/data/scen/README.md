# D-SCEN — scripted diary scenarios

40 scripts, 100% on script (EVAL.md §2). **This is the one dataset that is committed in
full**, because every row is synthetic by construction: a script states what it plants, so
there is no distribution to leak from and nothing to protect.

**Every smoke run generates them in-process** — `dev.kasoti.eval.fixtures.DiaryScenarios`,
40 scripts across alias / travel / facilitator / watchlist / benign — and runs the real
`dev.kasoti.diary` rules plus `FusionEngine` over each. This directory is for anything a
scenario needs that is *not* code: an expected verdict a reviewer disagrees with, a note on
why a script plants what it plants, a new scenario nobody thought of in Kotlin yet.

## The two halves, and why the second is not optional

- **Planted scripts** (23) — alias similarities straddling `T_ALIAS_HI`; travel intervals
  straddling `Vmax` including a row that must *not* fire; facilitator cohort counts
  straddling `FACILITATOR_MIN_GROUPS`; watchlist similarities straddling `T_WL`.
- **Benign scripts** (17) — ordinary multi-post weeks that must stay GREEN.

A diary suite that only checks that planted attacks are caught is measuring recall and not
the false-alarm rate that decides whether a rule is safe to ship. The benign half is the half
that keeps the other half honest, and `D-GATE-02` fails the run if any of them fires.

## A note on how a rule disagreement gets resolved

An earlier draft of the alias scripts expected AMBER for a DOB-only mismatch. The rule says
RED (FUSION.md §2 gates `R-ALIAS-01` on a name-or-DOB difference, with supervisor
confirmation). The scripts were changed to match the rule, not the rule to match the scripts.
That is recorded because the temptation to edit the product until a hand-written expectation
goes green is the exact failure AGENTS.md §8 warns about.

`manifest.csv` columns: `scenario_id`, `category`, `expected_verdict`, `expected_codes`,
`planted`, `rationale`, `owner`
