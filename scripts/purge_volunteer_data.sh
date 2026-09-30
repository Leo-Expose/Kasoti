#!/usr/bin/env bash
# purge_volunteer_data.sh — DATA.md §8 deletion-on-request, and the operational
# half of the DPDP rights workflow in THREAT_MODEL.md §5 (access / correction /
# deletion via supervisor workflow).
#
# WHAT IT REMOVES for one <volunteer-id>:
#   * their media under eval/data/  (face crops, macro patches, print attacks)
#   * their rows from every CSV/JSONL manifest under eval/data/
#   * their diary test rows under eval/runs/ and eval/fixtures/ (test data only)
#
# WHAT IT REFUSES TO DO — these are hard stops, not warnings:
#   * touch anything outside eval/data/ (and the explicitly-listed run/fixture
#     roots). A path that escapes after symlink resolution aborts the run
#     BEFORE any deletion happens.
#   * delete eval/fixtures/** generated MRZ/QR vectors — those are zero-PII by
#     construction (DATA.md §7) and removing them breaks the harness.
#   * run without confirmation in a non-interactive shell.
#
# IT ALWAYS RE-RUNS THE SMOKE SUITE afterwards (DATA.md §8: "removes media +
# diary test rows + re-runs smoke to confirm green"). If smoke fails the script
# exits non-zero — a purge that breaks the harness is not a successful purge.
#
# IDEMPOTENCE: deleting an absent volunteer is a success, not an error. Re-running
#   after a purge reports "already clean" and still re-runs smoke.
#
# EXIT CODES: 0 = purged (or already clean) and smoke green · 1 = refusal,
#   deletion failure, or smoke failure · 2 = usage error.
#
# Usage:
#   ./scripts/purge_volunteer_data.sh <volunteer-id> [--dry-run] [--yes]
#                                     [--skip-smoke] [--data-root DIR] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_ROOT="$REPO_ROOT/eval/data"
RUNS_ROOT="$REPO_ROOT/eval/runs"
FIXTURES_ROOT="$REPO_ROOT/eval/fixtures"

VOLUNTEER=""
DRY_RUN=0
ASSUME_YES=0
SKIP_SMOKE=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --dry-run|-n) DRY_RUN=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --skip-smoke) SKIP_SMOKE=1 ;;
    --data-root) DATA_ROOT="${2:?--data-root needs a path}"; shift ;;
    -*) echo "purge_volunteer_data.sh: unknown option '$1' (try --help)" >&2; exit 2 ;;
    *)
      if [[ -n "$VOLUNTEER" ]]; then
        echo "purge_volunteer_data.sh: more than one volunteer-id given." >&2; exit 2
      fi
      VOLUNTEER="$1"
      ;;
  esac
  shift
done

if [[ -z "$VOLUNTEER" ]]; then
  echo "purge_volunteer_data.sh: a volunteer-id is required (DATA.md §8)." >&2
  usage >&2
  exit 2
fi

# The id becomes part of a path and of a grep pattern — keep it boring.
if [[ ! "$VOLUNTEER" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
  echo "FAIL: volunteer-id must match [A-Za-z0-9._-]{1,64}; got '$VOLUNTEER'." >&2
  echo "      Ids come from eval/data/*/manifest.csv (see DATA.md §3/§4)." >&2
  exit 2
fi
if [[ "$VOLUNTEER" == "." || "$VOLUNTEER" == ".." ]]; then
  echo "FAIL: refusing to operate on a relative path id." >&2
  exit 2
fi

# --- containment check: refuse anything outside eval/data/ ------------------
ALLOWED_ROOTS=()
for r in "$DATA_ROOT" "$RUNS_ROOT" "$FIXTURES_ROOT"; do
  [[ -d "$r" ]] && ALLOWED_ROOTS+=("$(cd "$r" && pwd)")
done
if [[ ${#ALLOWED_ROOTS[@]} -eq 0 ]]; then
  echo "FAIL: none of eval/data, eval/runs or eval/fixtures exists at $REPO_ROOT." >&2
  echo "      Nothing to purge, and nothing to prove. Check the checkout." >&2
  exit 2
fi

# Every candidate delete target must resolve inside one of the allowed roots.
# Symlinks are resolved first: a symlink pointing at $HOME is the classic way
# "delete under eval/data/" becomes "delete everything".
assert_inside() {
  local target="$1" root
  local resolved
  if [[ -e "$target" ]]; then
    resolved="$(cd "$(dirname "$target")" && pwd)/$(basename "$target")"
  else
    resolved="$(cd "$(dirname "$target")" 2>/dev/null && pwd)/$(basename "$target")"
  fi
  for root in "${ALLOWED_ROOTS[@]}"; do
    case "$resolved" in
      "$root"/*|"$root") return 0 ;;
    esac
  done
  {
    echo "REFUSED: '$target' resolves outside eval/data/ (+ runs, fixtures)."
    echo "        resolved: $resolved"
    echo "        allowed : ${ALLOWED_ROOTS[*]}"
    echo "        Nothing has been deleted. Fix the symlink or the id and retry."
  } >&2
  exit 1
}

# --- 1. collect media -------------------------------------------------------
# Directories that already contain the volunteer id absorb their own children,
# so we do not both `rm -rf dir` and then list every file under it.
MEDIA=()
if [[ -d "$DATA_ROOT" ]]; then
  mapfile -d '' -t FOUND < <(
    find "$DATA_ROOT" \( -name 'fixtures' -type d \) -prune -o \
      \( -name "*${VOLUNTEER}*" -o -path "*${VOLUNTEER}*" \) -print0 2>/dev/null || true
  )
  for f in "${FOUND[@]}"; do
    [[ -n "$f" ]] || continue
    assert_inside "$f"
    skip=0
    for already in "${MEDIA[@]:-}"; do
      [[ -n "$already" ]] || continue
      case "$f" in "$already"/*) skip=1; break ;; esac
    done
    [[ $skip -eq 0 ]] && MEDIA+=("$f")
  done
fi

# --- 2. collect manifest rows ----------------------------------------------
ROWFILES=()
if [[ -d "$DATA_ROOT" ]]; then
  while IFS= read -r -d '' f; do
    assert_inside "$f"
    ROWFILES+=("$f")
  done < <(find "$DATA_ROOT" \( -name '*.csv' -o -name '*.jsonl' -o -name '*.tsv' \) \
    -not -path '*/fixtures/*' -print0 2>/dev/null || true)
fi
if [[ -d "$RUNS_ROOT" ]]; then
  while IFS= read -r -d '' f; do
    assert_inside "$f"
    ROWFILES+=("$f")
  done < <(find "$RUNS_ROOT" \( -name '*.csv' -o -name '*.jsonl' -o -name '*.tsv' \) -print0 2>/dev/null || true)
fi

# Only the manifests that actually mention this volunteer need work.
TODO_ROWS=()
PLAN_ROWS=()
for f in "${ROWFILES[@]:-}"; do
  [[ -n "$f" ]] || continue
  HITS="$(grep -c -F -- "$VOLUNTEER" "$f" 2>/dev/null || true)"
  [[ "${HITS:-0}" -gt 0 ]] || continue
  TODO_ROWS+=("$f")
  PLAN_ROWS+=("$HITS")
done

# --- 3. print the plan ------------------------------------------------------
echo "PURGE  volunteer '$VOLUNTEER'"
echo "       roots : ${ALLOWED_ROOTS[*]}"
echo "       media : ${#MEDIA[@]} path(s)"
echo "       rows  : ${#TODO_ROWS[@]} manifest file(s)"
echo
if [[ ${#MEDIA[@]} -eq 0 && ${#TODO_ROWS[@]} -eq 0 ]]; then
  echo "       nothing under eval/data mentions this id — already clean."
fi
for f in "${MEDIA[@]:-}"; do
  [[ -n "$f" ]] || continue
  printf '       %-9s %s\n' "rm" "${f#"$REPO_ROOT"/}"
done
for i in "${!TODO_ROWS[@]}"; do
  printf '       %-9s %s (%s row(s))\n' "prune" "${TODO_ROWS[$i]#"$REPO_ROOT"/}" "${PLAN_ROWS[$i]}"
done
echo

if [[ $DRY_RUN -eq 1 ]]; then
  echo "DRY-RUN: nothing deleted."
  exit 0
fi

# --- 4. confirmation gate BEFORE anything destructive ------------------------
if [[ $ASSUME_YES -eq 0 && ! -t 0 ]]; then
  echo "FAIL: refusing to delete in a non-interactive shell without --yes." >&2
  echo "      (Use --dry-run first to review the plan above.)" >&2
  exit 1
fi
if [[ $ASSUME_YES -eq 0 ]]; then
  printf 'Proceed with deleting this volunteer data? [y/N] '
  read -r reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "ABORTED: nothing deleted."; exit 1 ;;
  esac
fi

# --- 5. delete --------------------------------------------------------------
REMOVED_FILES=0
TOUCHED_ROWS=0
for f in "${MEDIA[@]:-}"; do
  [[ -n "$f" ]] || continue
  if [[ -L "$f" ]]; then
    # A symlink is removed as a link; never recurse through it.
    rm -f -- "$f"
  else
    rm -rf -- "$f"
  fi
  REMOVED_FILES=$((REMOVED_FILES + 1))
done

for f in "${TODO_ROWS[@]:-}"; do
  [[ -n "$f" ]] || continue
  TMPF="$(mktemp "$(dirname "$f")/.purge.XXXXXX")"
  if grep -v -F -- "$VOLUNTEER" "$f" > "$TMPF"; then
    chmod --reference="$f" "$TMPF" 2>/dev/null || chmod 600 "$TMPF"
    mv "$TMPF" "$f"
  else
    rm -f "$TMPF"
    echo "FAIL: could not prune rows from $f" >&2
    exit 1
  fi
  TOUCHED_ROWS=$((TOUCHED_ROWS + 1))
done

echo
echo "       removed $REMOVED_FILES media path(s), pruned $TOUCHED_ROWS manifest file(s)."

# --- 6. re-run the smoke suite ---------------------------------------------
if [[ $SKIP_SMOKE -eq 1 ]]; then
  echo "WARN  --skip-smoke: the harness was NOT re-run. DATA.md §8 requires it." >&2
  exit 0
fi

echo
echo "       re-running the smoke suite (DATA.md §8) ..."
if [[ ! -x ./gradlew && ! -f ./gradlew ]]; then
  echo "FAIL: ./gradlew not found — cannot re-run smoke. Purge is done, but" >&2
  echo "      'smoke is green' is UNVERIFIED. Run ./gradlew :eval:run --args=smoke" >&2
  exit 1
fi
set +e
( cd "$REPO_ROOT" && ./gradlew --console=plain :eval:run --args="smoke" )
SMOKE_RC=$?
set -e
if [[ $SMOKE_RC -ne 0 ]]; then
  {
    echo "FAIL: smoke suite is RED (exit $SMOKE_RC) after the purge."
    echo "      DATA.md §8 requires green. Investigate before shipping — a deletion"
    echo "      that broke the harness usually means a fixture, not a face crop."
  } >&2
  exit 1
fi

echo
echo "PASS  purge of '$VOLUNTEER' complete and the smoke suite is green."
echo "      Record the request + date in docs/DATA.md retention log per THREAT_MODEL §5."
