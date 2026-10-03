#!/usr/bin/env bash
# pii_scrubber_test.sh — AGENTS.md §1 (CI gate: "PII-scrubber test") and
# AGENTS.md §4 ("No PII in logs/caches — run scrubber test with new fields").
#
# =====================================================================================
# WHAT IT PROVES, PRECISELY
# =====================================================================================
#   1. A PII scrubber exists in :core, at a named path, with a named class. If it does not,
#      this script FAILS with an explicit "not implemented" message and exit 1. It never
#      silently passes an absent scrubber: a missing PII control that reports green is the
#      single most dangerous outcome this script could have.
#   2. Its seven test suites RUN AND PASS — as tests, through Gradle,
#      against the real class. Not a `grep` for the string "PiiScrubber", which would pass
#      against a class whose body is `fun scrub(s: String) = s`.
#   3. All seven suites are the ones expected, by count, from freshly produced XML reports.
#      Gradle can exit 0 with zero matching tests; that is a vacuous pass, so the report
#      directory is deleted first and the count is read back out of the reports afterwards.
#   4. There is exactly ONE definition of the rules: :app-desktop's LogScrubber delegates and
#      does not carry its own patterns.
#
# =====================================================================================
# WHAT IT DOES NOT PROVE — said here so nobody has to infer it from a green tick
# =====================================================================================
#   · That the console actually calls the scrubber on every write path. That is code review.
#   · That no caller logs PII *before* the scrubber sees it. This is a last line of defence;
#     see the class comment on `dev.kasoti.log.PiiScrubber` for why treating it as a first
#     line would make the product worse than having no scrubber at all.
#   · That a name in free prose is removed. It is not, and cannot be — see PiiScrubber's
#     "what it cannot do". This gate proves the rules work, not that the rules are complete.
#
# WHY A GRADLE RUN AND NOT A SOURCE SCAN
# =====================================================================================
#   The previous version of this script located the scrubber by grepping for a class or
#   function whose *name* matched, then derived its `--tests` filters from the *file names* it
#   found. Two things went wrong with that, both observed while this gate was being made real:
#
#     · it found `ZScratchTest` — a leftover scratch file — and ran that, because "a file whose
#       name looks like a test" is not the same as "the test for the scrubber";
#     · the assumption that a Kotlin file's name equals its class name is a convention, not a
#       rule, so renaming one file silently changed which tests ran.
#
#   A filter on the *test class* FQN with a wildcard does not care what anything is called on
#   disk, and the count read back out of the JUnit XML reports is what closes the remaining
#   gap between "Gradle exited 0" and "the tests actually ran".
#
# EXIT CODES: 0 = scrubber present, suites ran, suites green · 1 = scrubber missing, tests
#   failing, tests missing, or the rules are defined twice · 2 = setup error (no Gradle
#   wrapper, no JDK, bad argument).
#
# Usage:  ./scripts/pii_scrubber_test.sh [--smoke] [--dry-run] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SMOKE=0
DRY_RUN=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --smoke) SMOKE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "pii_scrubber_test.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# --- 0. toolchain ----------------------------------------------------------
if [[ ! -f ./gradlew ]]; then
  echo "FAIL: ./gradlew not found at $REPO_ROOT — cannot run the scrubber tests." >&2
  exit 2
fi
if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  echo "FAIL: no JDK on PATH and JAVA_HOME unset. CI uses Temurin 17 (BUILD.md §1)." >&2
  exit 2
fi

# --- 1. preconditions: does the control exist at all? ----------------------
# These are existence checks, not the check. The check is the Gradle run in step 2. A grep
# here that fails means "the control is missing", which is a different sentence from "the
# control is broken", and the exit codes are the same because both are the build's problem.
SCRUB_SRC="core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt"
TEST_DIR="core/src/commonTest/kotlin/dev/kasoti/log"

# The test classes this gate requires. Named explicitly, not globbed: a glob would let a
# deleted or renamed suite turn the gate green by removing its own coverage.
EXPECTED_SUITES=(
  "dev.kasoti.log.PiiScrubberTest"
  "dev.kasoti.log.PiiScrubberAdversarialTest"
  "dev.kasoti.log.PiiScrubberInjectionTest"
  "dev.kasoti.log.PiiScrubberFalsePositiveTest"
  "dev.kasoti.log.PiiScrubberRuleBoundaryTest"
  "dev.kasoti.log.PiiScrubberFieldCorpusTest"
  "dev.kasoti.log.PiiScrubberPolicyTest"
)

if [[ ! -f "$SCRUB_SRC" ]]; then
  {
    echo "FAIL  PII scrubber NOT IMPLEMENTED in :core — this gate cannot pass."
    echo
    echo "Expected: $SCRUB_SRC"
    echo
    echo "This script deliberately does NOT pass when the scrubber is absent. An absent PII"
    echo "control reported as green is worse than a red build."
    echo
    echo "Requirements it must satisfy (AGENTS.md §4, §5; THREAT_MODEL.md §5):"
    echo "  · name, dob, docHash, emb are never logged verbatim (embeddings especially)"
    echo "  · scrubbing fails closed for values that arrived without a key"
    echo "  · unit tests include adversarial cases (unicode digits, confusable keys, nested"
    echo "    fields, base64/PII mixed strings, a field added without a rule)"
    echo "  · a NEGATIVE suite exists too: a scrubber that eats hashes and ids is worse"
    echo "    than no scrubber, and only a test proves it does not"
  } >&2
  exit 1
fi

if ! grep -qE '^class PiiScrubber\b' "$SCRUB_SRC"; then
  echo "FAIL  $SCRUB_SRC exists but does not declare 'class PiiScrubber'." >&2
  echo "      A file at the expected path is not a PII control." >&2
  exit 1
fi

missing_suites=()
for suite in "${EXPECTED_SUITES[@]}"; do
  file="$TEST_DIR/${suite##*.}.kt"
  [[ -f "$file" ]] || missing_suites+=("${suite##*.}.kt")
done
if [[ ${#missing_suites[@]} -ne 0 ]]; then
  {
    echo "FAIL  the scrubber exists but required test suite(s) are missing:"
    printf '        %s\n' "${missing_suites[@]}"
    echo
    echo "A scrubber without adversarial and negative tests is an unverified PII control, and"
    echo "deleting a suite must not turn this gate green."
  } >&2
  exit 1
fi

echo "INFO  scrubber: $SCRUB_SRC"
echo "INFO  required suites (${#EXPECTED_SUITES[@]}):"
for suite in "${EXPECTED_SUITES[@]}"; do printf '        %s\n' "$suite"; done

# --- 2. one definition -----------------------------------------------------
# The desktop console used to carry its own rule set. Two rule sets is the worst arrangement
# available: the console's copy is the one nobody reviews for privacy, because the gate looks
# in :core. So the delegation is asserted, not assumed.
CONSOLE_SCRUBBER="app-desktop/src/main/kotlin/dev/kasoti/desktop/LogScrubber.kt"
if [[ -f "$CONSOLE_SCRUBBER" ]] && grep -qE 'Regex\(|Regex\.compile' "$CONSOLE_SCRUBBER"; then
  {
    echo "FAIL  $CONSOLE_SCRUBBER defines its own patterns." >&2
    echo "      The rules live in :core. A second definition in the console is a rule set" >&2
    echo "      that no privacy review looks at, and the two will drift." >&2
    echo "      Make it delegate to dev.kasoti.log.PiiScrubber." >&2
  }
  exit 1
fi

# --- 3. run the suites -----------------------------------------------------
# The report directory is deleted so the count in step 4 can only come from THIS run. Without
# that, a previous run's XML satisfies the check even if this run matched nothing — which is
# the difference between "this step checked something" and "this step reported success".
REPORT_DIR="core/build/test-results/jvmTest"

# One wildcard over the class FQN, not one filter per file name: it is independent of what
# anything is called on disk. The i18n suite is included because a redaction rule without a
# Hindi string is a build failure, not a demo-day surprise (AGENTS.md §3 item 4).
FILTERS=(--tests 'dev.kasoti.log.PiiScrubber*' --tests 'dev.kasoti.i18n.Messages*')

if [[ $DRY_RUN -eq 1 ]]; then
  echo "DRY-RUN would execute: ./gradlew --console=plain :core:jvmTest ${FILTERS[*]}"
  echo "DRY-RUN would then verify $((${#EXPECTED_SUITES[@]})) suite reports in $REPORT_DIR"
  exit 0
fi

rm -rf "$REPORT_DIR"

echo "INFO  running: ./gradlew --console=plain :core:jvmTest ${FILTERS[*]}"
set +e
./gradlew --console=plain :core:jvmTest "${FILTERS[@]}"
GRADLE_RC=$?
set -e

if [[ $GRADLE_RC -ne 0 ]]; then
  echo "FAIL  scrubber test task failed (gradle exit $GRADLE_RC)." >&2
  echo "      A PII regression in the scrubber is a privacy incident, not a flake." >&2
  exit 1
fi

# Gradle can exit 0 while matching zero tests, and a filter that matches nothing leaves the
# report directory empty rather than wrong. Both are checked, from the fresh reports.
if [[ ! -d "$REPORT_DIR" ]]; then
  echo "FAIL  no XML reports at $REPORT_DIR after a successful run." >&2
  echo "      The filter matched zero tests. Treating that as a FAIL, not a pass." >&2
  exit 1
fi

total_tests=0
total_failures=0
for suite in "${EXPECTED_SUITES[@]}"; do
  report="$REPORT_DIR/TEST-${suite}.xml"
  if [[ ! -f "$report" ]]; then
    echo "FAIL  no report for $suite — it did not run." >&2
    exit 1
  fi
  # awk rather than bc: `bc` is not installed on a stock GitHub runner, and a gate that
  # dies with "bc: command not found" is indistinguishable from a gate that failed.
  line="$(awk 'match($0, /tests="[0-9]+"/) { print substr($0, RSTART+7, RLENGTH-8); exit }' "$report")"
  suite_failures="$(grep -oE '(failures|errors)="[0-9]+"' "$report" \
    | grep -oE '[0-9]+' | awk '{ total += $1 } END { print total + 0 }')"
  if [[ "${suite_failures:-0}" -ne 0 ]]; then
    echo "FAIL  $suite reported $suite_failures failure(s)/error(s)." >&2
    exit 1
  fi
  if [[ "${line:-0}" -eq 0 ]]; then
    echo "FAIL  $suite reported success but executed 0 tests." >&2
    echo "      A green suite that asserts nothing is not a suite." >&2
    exit 1
  fi
  total_tests=$((total_tests + line))
  printf '        %-56s %3d test(s) green\n' "$suite" "$line"
done
echo "PASS  ${#EXPECTED_SUITES[@]} scrubber suites green — $total_tests test(s) executed."

# --- 4. optional hostile smoke ---------------------------------------------
if [[ $SMOKE -eq 1 ]]; then
  # Deliberately NOT a scratch main(). A second, hand-written entry point into the scrubber is
  # a second code path that no test covers and that nobody will keep in step — the failure mode
  # this whole script exists to prevent. The adversarial suite already runs hostile input
  # through the real class; this section only reports what is there.
  ENTRY="$(grep -rhoE 'fun (scrub|neutraliseControlCharacters)[A-Za-z0-9_]*\(' "$SCRUB_SRC" 2>/dev/null | head -n1 || true)"
  if [[ -z "$ENTRY" ]]; then
    echo "WARN  no scrub()/neutraliseControlCharacters() entry point found." >&2
  else
    echo "INFO  entry point: $ENTRY (exercised by the suites above, not re-invoked here)"
  fi
fi

echo "PASS  PII scrubber present, its suites ran, and they are green."
echo "NOTE  this proves the RULES work. It does not prove every call site uses them — see"
echo "      the header and dev.kasoti.log.PiiScrubber's class comment."
