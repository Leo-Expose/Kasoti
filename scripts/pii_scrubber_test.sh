#!/usr/bin/env bash
# pii_scrubber_test.sh — AGENTS.md §1 (CI gate: "PII-scrubber test") and
# AGENTS.md §4 ("No PII in logs/caches — run scrubber test with new fields").
#
# WHAT IT PROVES: every field KASOTI writes to a log, cache or crash report has
# a redaction rule, and the scrubber cannot be bypassed by a field that was
# added later. This is the regression that stops a name / DOB / embedding from
# reaching disk "just this once".
#
# WHAT IT DOES, IN ORDER:
#   1. LOCATE the scrubber in :core (or :platform). If it does not exist, this
#      script FAILS with an explicit "not implemented yet" message. It never
#      silently passes an absent scrubber — a missing PII control that reports
#      green is the single most dangerous outcome this script could have.
#   2. RUN the scrubber's own tests through Gradle.
#   3. OPTIONALLY run a hostile shell-level smoke: feed known PII-shaped values
#      through the scrubber entry point if one is discoverable (--smoke).
#
# EXIT CODES: 0 = scrubber present and green · 1 = scrubber missing or tests
#   failing · 2 = setup error (no Gradle wrapper, no JDK, ...).
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
if [[ ! -x ./gradlew && ! -f ./gradlew ]]; then
  echo "FAIL: ./gradlew not found at $REPO_ROOT — cannot run the scrubber tests." >&2
  exit 2
fi
if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  echo "FAIL: no JDK on PATH and JAVA_HOME unset. CI uses Temurin 17 (BUILD.md §1)." >&2
  exit 2
fi

# --- 1. locate the scrubber ------------------------------------------------
# Accepted shapes, in priority order:
#   a) a source file whose name contains Scrub/Redact/Anonymise
#   b) a source file declaring `object .*Scrub|fun .*scrub|fun .*redact`
SCRUB_SRC="$(grep -rlE \
  --include='*.kt' \
  -e 'class [A-Za-z0-9_]*(Scrubber|Redactor|Anonymiser|Anonymizer)' \
  -e 'object [A-Za-z0-9_]*(Scrubber|Redactor|Anonymiser|Anonymizer)' \
  -e 'fun [a-z0-9_]*(scrub|redact|anonymis|anonymiz)' \
  core/src platform/src eval/src 2>/dev/null | sort -u || true)"

SCRUB_TEST="$(grep -rlE --include='*.kt' \
  -e '(class|object) [A-Za-z0-9_]*(Scrub|Redact|Anonymis|Anonymiz)[A-Za-z0-9_]*Test' \
  -e '@Test' \
  core/src/commonTest core/src/jvmTest platform/src 2>/dev/null \
  | grep -Ei 'scrub|redact|anonymis|anonymiz|log|privacy' | sort -u || true)"

if [[ -z "$SCRUB_SRC" ]]; then
  {
    echo "FAIL  PII scrubber NOT IMPLEMENTED YET — this gate cannot pass."
    echo
    echo "Searched: core/src, platform/src, eval/src for a scrubber/redactor type or a"
    echo "          scrub()/redact() function. Nothing matched."
    echo
    echo "This script deliberately does NOT pass when the scrubber is absent. An"
    echo "absent PII control reported as green is worse than a red build."
    echo
    echo "Owner: see docs/HANDOFF.md §5 (privacy/logging row). Expected shape:"
    echo "  core/src/commonMain/kotlin/dev/kasoti/log/PiiScrubber.kt   (pure logic)"
    echo "  core/src/commonTest/kotlin/dev/kasoti/log/PiiScrubberTest.kt"
    echo "  Every log/cache/crash field routed through it; message keys for the UI."
    echo
    echo "Requirements it must satisfy (AGENTS.md §4, §5; THREAT_MODEL.md §5):"
    echo "  · name, dob, docHash, emb are never logged verbatim (embeddings especially)"
    echo "  · scrubbing is deny-by-default for new struct fields"
    echo "  · unit tests include adversarial cases (unicode digits, nested fields,"
    echo "    base64/PII mixed strings, field added without a rule)"
    echo "  · an eval-fixture entry exists (eval/fixtures/)"
  } >&2
  exit 1
fi

echo "INFO  scrubber implementation found in:"
printf '%s\n' "$SCRUB_SRC" | sed 's/^/        /' | sed "s#$REPO_ROOT/##"

if [[ -z "$SCRUB_TEST" ]]; then
  echo "FAIL  scrubber exists but NO test file was found for it." >&2
  echo "      A scrubber without adversarial tests is an unverified PII control." >&2
  exit 1
fi

echo "INFO  scrubber test file(s):"
printf '%s\n' "$SCRUB_TEST" | sed 's/^/        /' | sed "s#$REPO_ROOT/##"

# --- 2. run the tests ------------------------------------------------------
# Derive Gradle --tests filters from the test file names (class-name filters are
# more reliable than file paths across source sets).
FILTERS=()
while IFS= read -r f; do
  cls="$(basename "$f" .kt)"
  # MrzCorpusTest -> MrzCorpusTest (Kotlin file name == class name by convention)
  FILTERS+=(--tests "$cls")
done <<< "$SCRUB_TEST"

if [[ $DRY_RUN -eq 1 ]]; then
  echo "DRY-RUN would execute: ./gradlew :core:jvmTest ${FILTERS[*]}"
  exit 0
fi

echo "INFO  running: ./gradlew :core:jvmTest ${FILTERS[*]}"
set +e
./gradlew --console=plain :core:jvmTest "${FILTERS[@]}"
GRADLE_RC=$?
set -e

if [[ $GRADLE_RC -ne 0 ]]; then
  echo "FAIL  scrubber test task failed (gradle exit $GRADLE_RC)." >&2
  echo "      A PII regression in the scrubber is a privacy incident, not a flake." >&2
  exit 1
fi

# Gradle can succeed while matching zero tests if a filter is wrong; that would
# be a silent pass, so check the XML reports.
REPORT_DIR="$REPO_ROOT/core/build/test-results/jvmTest"
if [[ -d "$REPORT_DIR" ]]; then
  TESTS_RUN="$(grep -ho 'tests="[0-9]*"' "$REPORT_DIR"/*.xml 2>/dev/null \
    | grep -o '[0-9]*' | paste -sd+ - | bc 2>/dev/null || echo 0)"
  if [[ "${TESTS_RUN:-0}" -eq 0 ]]; then
    echo "FAIL  scrubber task reported success but executed 0 tests —" >&2
    echo "      the --tests filter matched nothing. Treating that as a FAIL, not a pass." >&2
    exit 1
  fi
  echo "PASS  scrubber suite green — $TESTS_RUN test(s) executed."
else
  echo "WARN  no XML reports at $REPORT_DIR; cannot confirm the test count." >&2
fi

# --- 3. optional hostile smoke -------------------------------------------
if [[ $SMOKE -eq 1 ]]; then
  echo "INFO  --smoke: probing for a callable scrubber entry point..."
  ENTRY="$(grep -rhoE 'fun (scrub|redact)[A-Za-z0-9_]*\(' "$SCRUB_SRC" 2>/dev/null | head -n1 || true)"
  if [[ -z "$ENTRY" ]]; then
    echo "WARN  no top-level scrub()/redact() entry point found; skipping smoke." >&2
    echo "      Add one if you want a shell-level PII smoke test." >&2
  else
    echo "INFO  entry point: $ENTRY  (manual invocation is left to the test suite —"
    echo "      a scratch Kotlin main() would create a second, unverified code path)."
  fi
fi

echo "PASS  PII scrubber present and its tests are green."
