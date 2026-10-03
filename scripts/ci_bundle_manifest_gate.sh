#!/usr/bin/env bash
# ci_bundle_manifest_gate.sh — classify the result of scripts/verify_bundle.sh for CI.
#
# WHY THIS EXISTS: `verify_bundle.sh` deliberately conflates two states that CI must not
# treat alike, and it has one exit code for both.
#
#   (a) The manifest is POPULATED and a hash does not match, or a listed file is missing
#       while the manifest claims it is pinned. That is invariant I12 / AT-12 "model
#       swap" — a real integrity failure. BLOCKING.
#
#   (b) The manifest is NOT POPULATED (REPLACE_ME placeholders), or every entry it lists
#       is a file this checkout does not carry. Nothing was hashed, so the gate verified
#       NOTHING. Reporting that as a pass is the failure mode this script exists to
#       prevent — the same reason CI's PII step treats "ran zero tests" as FAIL rather
#       than green (AGENTS.md §8: never weaken a gate to make a build green).
#
# Until 2026-10-03 CI wired (b) straight into a `continue-on-error: true` step, so the
# bundle-integrity invariant was reported `success` on every run while being impossible
# to satisfy: scripts/bundle_manifest.sample.txt carries two REPLACE_ME placeholders by
# design, and the one real entry, eval/models/blazeface_short.tflite, is git-ignored
# (.gitignore:53) so it is absent from a fresh CI checkout. Evidence: run 37112766189,
# step "Bundle manifest integrity" printed
#     FAIL: manifest '.../bundle_manifest.sample.txt' is NOT populated — 2 placeholder hash(es).
#     ##[error]Process completed with exit code 1.
# and the run was green.
#
# So this script does not re-implement the hashing. It runs the real gate, unmodified,
# and reports WHICH of the two states occurred, on distinct exit codes, so the caller
# cannot collapse them:
#
#   0  VERIFIED       every listed file was hashed and matched (and at least one was)
#   1  FAILED         populated manifest, real mismatch / missing / malformed line — BLOCKING
#   2  SETUP_ERROR    the gate could not be run or its verdict was unreadable — BLOCKING
#   3  NOT_SATISFIED  the gate verified nothing (unpopulated manifest, or nothing vendored)
#
# 3 is deliberately NOT 0. A caller that wants "skip loudly" must handle it explicitly;
# there is no code path here that turns "checked nothing" into "checked and passed".
#
# NON-VACUITY: a `verify_bundle.sh` exit 0 is accepted only if it also printed
#     PASS  bundle verified — N/N file(s) match their pinned SHA-256.
# with N >= 1. An exit 0 with no such line is reported as SETUP_ERROR (2), not VERIFIED —
# a scanner that measured nothing and said nothing is not a pass (see the magic-threshold
# step's identical check in ci.yml).
#
# Usage:
#   ./scripts/ci_bundle_manifest_gate.sh [--root DIR] [--manifest FILE]
#
# Deliberately does NOT touch $GITHUB_STEP_SUMMARY or any other GitHub variable: the
# caller writes the summary row. That is what lets the negative test drive this script
# in a temp directory, which is how CI proves this logic can report a failure.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$REPO_ROOT"
MANIFEST="$REPO_ROOT/scripts/bundle_manifest.sample.txt"
VERIFY="$REPO_ROOT/scripts/verify_bundle.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) ROOT="${2:?--root needs a directory}"; shift ;;
    --manifest) MANIFEST="${2:?--manifest needs a path}"; shift ;;
    *) echo "ci_bundle_manifest_gate.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done

[[ -f "$VERIFY" ]] || { echo "FAIL: $VERIFY not found." >&2; exit 2; }
[[ -f "$MANIFEST" ]] || { echo "FAIL: manifest '$MANIFEST' not found." >&2; exit 2; }
[[ -d "$ROOT" ]] || { echo "FAIL: --root '$ROOT' is not a directory." >&2; exit 2; }

set +e
OUT="$("$VERIFY" --root "$ROOT" --manifest "$MANIFEST" 2>&1)"
RC=$?
set -e
printf '%s\n' "$OUT"

# ---- FAILED: populated manifest, real integrity problem. Blocking. ----------------
# Anything that is not one of the two "verified nothing" shapes below is treated as a
# real failure on purpose: verify_bundle.sh reserves exit 2 for setup errors and prints
# its reason, so an unrecognised message means the gate changed underneath this script,
# and guessing would be exactly the silent-pass this file exists to prevent.
if [[ $RC -eq 1 ]] \
   && ! printf '%s\n' "$OUT" | grep -qE 'is NOT populated — [1-9][0-9]* placeholder hash' \
   && ! printf '%s\n' "$OUT" | grep -q 'has no entries at all'; then
  echo "STATE: FAILED — the bundle manifest is populated and an entry did not verify (invariant I12 / AT-12, model swap)." >&2
  exit 1
fi

# ---- SETUP_ERROR: the gate ran but its verdict is unusable. Blocking. ---------------
if [[ $RC -eq 2 ]]; then
  echo "STATE: SETUP_ERROR — verify_bundle.sh could not run (exit 2); see its message above." >&2
  exit 2
fi

if [[ $RC -ne 0 && $RC -ne 1 ]]; then
  echo "STATE: SETUP_ERROR — verify_bundle.sh exited $RC, which is not a code it documents (0/1/2)." >&2
  exit 2
fi

# ---- NOT_SATISFIED: exit 1 for want of a hash, or for want of the file. Not a pass. --
if [[ $RC -eq 1 ]]; then
  PLACEHOLDERS="$(printf '%s\n' "$OUT" | sed -n 's/.*is NOT populated — \([0-9][0-9]*\) placeholder hash.*/\1/p' | head -n 1)"
  ABSENT="$(printf '%s\n' "$OUT" | grep -cE '^MISSING ' || true)"
  if [ -n "$PLACEHOLDERS" ]; then
    echo "STATE: NOT_SATISFIED — $PLACEHOLDERS manifest entr(y/ies) carry REPLACE_ME, so no hash was compared." >&2
  elif printf '%s\n' "$OUT" | grep -q 'has no entries at all'; then
    echo "STATE: NOT_SATISFIED — the manifest lists no files, so there is nothing to hash." >&2
  fi
  if [ "${ABSENT:-0}" -gt 0 ]; then
    echo "         additionally, $ABSENT listed file(s) are absent from this checkout, so even a" >&2
    echo "         populated manifest would verify nothing here. eval/models/**/*.tflite is" >&2
    echo "         git-ignored (.gitignore:53); run scripts/fetch_models.sh to obtain it." >&2
  fi
  exit 3
fi

# ---- VERIFIED, but only on a non-vacuous exit 0. ------------------------------------
PASS_N="$(printf '%s\n' "$OUT" | sed -n 's/^PASS  bundle verified — \([0-9][0-9]*\)\/[0-9][0-9]* file.*/\1/p' | head -n 1)"
if [ -z "$PASS_N" ] || [ "$PASS_N" -lt 1 ]; then
  echo "STATE: SETUP_ERROR — verify_bundle.sh exited 0 but did not report 'PASS  bundle verified — N/N'" >&2
  echo "         with N >= 1 (got '${PASS_N:-no count}'). An exit 0 that hashed nothing is a" >&2
  echo "         vacuous pass, which is a failure here, not a pass." >&2
  exit 2
fi
echo "STATE: VERIFIED — $PASS_N file(s) hashed and matched their pinned SHA-256."
exit 0
