#!/usr/bin/env bash
# verify_bundle.sh — SHA-256 integrity gate for bundled model / asset files.
#
# WHY: this is the runtime guard for invariant I12 and red-team attack RT-E8 /
# AT-12 "model swap" (THREAT_MODEL.md §3). KASOTI loads inference weights from
# bundled assets only (BUILD.md §3/§4); if an attacker or a careless build can
# swap a .tflite on disk, every downstream verdict is attacker-chosen and the
# audit chain still records it as trustworthy. The loader refuses mismatched
# hashes (message key err.model_hash) — this script is the same check at the
# door of the release/demo process, and the pre-demo step in DEMO.md §4.
#
# MANIFEST FORMAT (one entry per line, sha256sum(1) compatible):
#     <64 hex chars>  <path relative to --root>
#   * blank lines and lines starting with '#' are ignored
#   * a '*' between hash and path marks sha256sum binary mode and is accepted
#   * the placeholder REPLACE_ME is REJECTED — an unpopulated manifest must
#     fail loudly rather than "verify" nothing
#
# MODES:
#   (default)  verify every listed file against the manifest
#   --init     write/refresh a manifest by hashing the listed files. Every listed
#              path must already exist; nothing is silently skipped, because a
#              manifest that quietly omits a model is the failure we fear.
#
# EXIT CODES: 0 = every file verified · 1 = mismatch / missing / unpopulated
#   manifest · 2 = usage or setup error.
#
# Usage:
#   ./scripts/verify_bundle.sh [--root DIR] [--manifest FILE] [--init]
#                             [--quiet] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$REPO_ROOT"
MANIFEST="$REPO_ROOT/scripts/bundle_manifest.sample.txt"
MODE="verify"
QUIET=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --root) ROOT="${2:?--root needs a directory}"; shift ;;
    --manifest) MANIFEST="${2:?--manifest needs a path}"; shift ;;
    --init) MODE="init" ;;
    --quiet|-q) QUIET=1 ;;
    *) echo "verify_bundle.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

[[ -f "$MANIFEST" ]] || { echo "FAIL: manifest '$MANIFEST' not found." >&2; exit 2; }
[[ -d "$ROOT" ]] || { echo "FAIL: --root '$ROOT' is not a directory." >&2; exit 2; }
command -v sha256sum >/dev/null 2>&1 || { echo "FAIL: sha256sum is not available." >&2; exit 2; }

# Normalise into parallel arrays WANT[] / PATHS[] / PLACE[]. Comments, blanks,
# trailing CR and sha256sum binary-mode '*' are stripped. A line whose first
# token is neither 64 hex chars nor a known placeholder is a hard error — a
# typo'd manifest that silently verifies nothing is the failure this script
# exists to prevent.
WANT=(); PATHS=(); PLACE=(); MALFORMED=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ -z "${line//[[:space:]]/}" ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  read -r hash rest <<< "$line"
  [[ -z "${rest:-}" ]] && rest="$hash"
  rest="${rest#\*}"
  rest="${rest#"${rest%%[![:space:]]*}"}"
  if [[ "$hash" == "REPLACE_ME" || "$hash" == "TODO" || "$hash" =~ ^0{64}$ ]]; then
    # placeholder: acceptable input to --init, never acceptable to verify
    PLACE+=(1); WANT+=(""); PATHS+=("$rest"); continue
  fi
  if [[ ! "$hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'FAIL  manifest line is malformed (expected 64 hex chars, got %q): %s\n' \
      "$hash" "$line" >&2
    MALFORMED=1
    continue
  fi
  PLACE+=(0); WANT+=("${hash,,}"); PATHS+=("$rest")
done < "$MANIFEST"

if [[ $MALFORMED -ne 0 ]]; then
  echo "      refusing to continue with a manifest we cannot parse." >&2
  exit 1
fi

if [[ ${#PATHS[@]} -eq 0 ]]; then
  echo "FAIL: manifest '$MANIFEST' has no entries at all — it verifies nothing." >&2
  echo "      A manifest must list every bundled file; 'no entries' is not 'all good'." >&2
  exit 1
fi

UNPOPULATED=0
for p in "${PLACE[@]}"; do UNPOPULATED=$((UNPOPULATED + p)); done

if [[ "$MODE" != "init" && $UNPOPULATED -gt 0 ]]; then
  {
    echo "FAIL: manifest '$MANIFEST' is NOT populated — $UNPOPULATED placeholder hash(es)."
    echo "      Refusing to report a bundle as verified when no real hash is pinned."
    echo "      Populate with:  $0 --init --manifest $MANIFEST"
    echo "      (--init works only once the model/asset files actually exist on disk;"
    echo "       obtain the weights, record provenance in THIRD_PARTY.md, then pin.)"
  } >&2
  exit 1
fi

if [[ "$MODE" == "init" ]]; then
  MISSING="$(mktemp "${TMPDIR:-/tmp}/kasoti_missing.XXXXXX")"
  OUT="$(mktemp "${TMPDIR:-/tmp}/kasoti_manifest.XXXXXX")"
  trap 'rm -f "$MISSING" "$OUT"' EXIT
  {
    printf '# KASOTI bundle manifest — generated %s UTC by scripts/verify_bundle.sh --init\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '# format: <sha256>  <path relative to %s>\n' "$ROOT"
    printf '# Regenerate only after the file legitimately changes; record why in the PR.\n'
  } > "$OUT"
  for i in "${!PATHS[@]}"; do
    path="${PATHS[$i]}"
    if [[ ! -f "$ROOT/$path" ]]; then
      printf '%s\n' "$path" >> "$MISSING"
      continue
    fi
    printf '%s  %s\n' "$(sha256sum "$ROOT/$path" | awk '{print $1}')" "$path" >> "$OUT"
  done
  if [[ -s "$MISSING" ]]; then
    echo "FAIL: these listed files do not exist — refusing to emit a partial manifest:" >&2
    sed 's/^/        /' "$MISSING" >&2
    echo "      Fetch them first (scripts/fetch_models.sh) or remove the entry in a PR." >&2
    exit 1
  fi
  mv "$OUT" "$MANIFEST"
  trap - EXIT
  echo "WROTE $MANIFEST ($(grep -cvE '^[[:space:]]*(#|$)' "$MANIFEST") entries)"
  exit 0
fi

REPORT="$(mktemp "${TMPDIR:-/tmp}/kasoti_verify.XXXXXX")"
trap 'rm -f "$REPORT"' EXIT

OK=0; BAD=0; MISSINGN=0
for i in "${!PATHS[@]}"; do
  want="${WANT[$i]}"
  path="${PATHS[$i]}"
  if [[ ! -f "$ROOT/$path" ]]; then
    printf 'MISSING   %s\n' "$path" >> "$REPORT"
    MISSINGN=$((MISSINGN + 1))
    continue
  fi
  got="$(sha256sum "$ROOT/$path" | awk '{print $1}')"
  if [[ "$got" == "$want" ]]; then
    printf 'OK        %s\n' "$path" >> "$REPORT"
    OK=$((OK + 1))
  else
    printf 'MISMATCH  %s\n          expected %s\n          actual   %s\n' \
      "$path" "$want" "$got" >> "$REPORT"
    BAD=$((BAD + 1))
  fi
done

[[ $QUIET -eq 0 ]] && cat "$REPORT"
TOTAL=$((OK + BAD + MISSINGN))

if [[ $BAD -eq 0 && $MISSINGN -eq 0 ]]; then
  echo "PASS  bundle verified — $TOTAL/$TOTAL file(s) match their pinned SHA-256."
  exit 0
fi

{
  echo
  echo "FAIL  bundle integrity: $BAD mismatch(es), $MISSINGN missing, $OK ok (of $TOTAL)."
  echo "      This is invariant I12 / AT-12 (model swap). DO NOT demo, do NOT deploy."
  echo "      Re-fetch with scripts/fetch_models.sh. If the hash legitimately changed,"
  echo "      get it signed off before refreshing the manifest (THIRD_PARTY.md)."
} >&2
exit 1
