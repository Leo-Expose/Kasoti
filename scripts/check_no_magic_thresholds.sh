#!/usr/bin/env bash
# check_no_magic_thresholds.sh — enforces AGENTS.md §2 / FR-R3:
#   "One ThresholdRegistry (core/.../fusion/thresholds.v1.json). Adding a tunable?
#    Add it there with name, default, unit, tuning-data ref, owner.
#    Magic numbers elsewhere = review fail."
#
# WHAT IT FLAGS: bare numeric literals in :core *product* sources
#      (core/src/commonMain/**.kt), except the threshold package
#      (dev/kasoti/threshold/, which *is* the registry).
#      core/src/commonTest is exempt — test fixtures must be concrete numbers or
#      they test nothing. Pass --include-tests to widen the scan anyway.
#
# HOW IT IS PRECISE:
#      1. Comments and string-literal contents are blanked before matching, so
#         doc text and message keys never trip it.
#      2. Numbers that are part of an identifier (sha256, utf8, q1) are not
#         numeric literals and are skipped.
#      3. Hex/binary literals are skipped (bit masks are structural).
#      4. The explicit allowlist below is applied per line.
#
# ---------------------------------------------------------------------------
# ALLOWLIST (every rule below is structural — none of them encodes a decision):
#   A1  0, 1, 2 and 0.0*      first/second/last index, boolean-as-int, "unset",
#                             floating-point zero accumulator
#   A2  0x.. 0b.. ..b ..L ..F hex / bit masks, radix, Long & Float suffixes
#   A3  loop bounds           until, downTo, step, rangeTo, indices, lastIndex,
#                             repeat(..), coerce*  — and bit-shift amounts
#                             (shl / shr / ushr) used by nibble/byte packing
#   A4  sizes / capacities    .size .length .capacity .count( Array(..) arrayOf
#                             List(..) CharArray(..) Buffer.allocate(..)
#   A5  bit/byte/block widths 8 16 24 32 64 128 255 256
#   A6  spec-fixed wire       7 9 10 11 12 — ISO 7813 / ICAO 9303 field
#                             positions and check-digit multipliers, Aadhaar
#                             12-digit length. Fixed by the standard.
#   A7  annotation lines      anything whose first token is '@'
#   A8  lookup-table rows     a line that is only digits/separators inside a
#                             constructor call (Verhoeff D-table, Verifier D,
#                             base64 alphabet). Data, not a tunable.
#   A9  array subscripts      foo[3] is a layout index, not an operating point
#
#   CAVEAT, stated honestly: A5 and A6 overlap with values that COULD be
#   tunables (a brightness ceiling of 128, a cosine gate of 0.11). The script
#   declines to nag about them; the reviewer still must. Do not treat "not
#   flagged" as "not a threshold".
#
#   NOTE: A6 exists because :core implements published specifications (ICAO
#   9303 MRZ, Verhoeff, base64/byte packing, ISO 18004). Numbers fixed by a
#   standard are not tunables. If you disagree with a specific hit, register
#   the value in ThresholdName/ThresholdRegistry.kt properly rather than
#   editing this allowlist to silence a review.
#
# STATUS: this check FAILS on the current tree. That is the honest result, not
#   a bug — see docs/STATUS.md. In CI it runs advisory (continue-on-error)
#   until the lead has triaged and baselined it.
#
# EXIT CODES: 0 = clean · 1 = magic numbers found · 2 = usage/setup error.
#
# Usage:
#   ./scripts/check_no_magic_thresholds.sh [--include-tests] [--max-print N]
#                                            [--baseline FILE] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_SRC="$REPO_ROOT/core/src"
THRESHOLD_PKG="dev/kasoti/threshold"

INCLUDE_TESTS=0
MAX_PRINT=40
BASELINE=""

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --include-tests) INCLUDE_TESTS=1 ;;
    --max-print) MAX_PRINT="${2:?--max-print needs a number}"; shift ;;
    --baseline) BASELINE="${2:?--baseline needs a path}"; shift ;;
    *) echo "check_no_magic_thresholds.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

[[ -d "$CORE_SRC" ]] || { echo "FAIL: core/src not found at $CORE_SRC" >&2; exit 2; }

AWK_PROG="$(mktemp "${TMPDIR:-/tmp}/kasoti_magic_scan.XXXXXX.awk")"
trap 'rm -f "$AWK_PROG"' EXIT

cat > "$AWK_PROG" <<'AWK'
function allowed(lit, line) {
  # A1 — structural zero/one/two and the floating-point zero accumulator
  if (lit == "0" || lit == "1" || lit == "2") return 1
  if (lit == "0.0" || lit == "0.0f" || lit == "0.0F" || lit == "0f" || lit == "0F") return 1
  # A2 — radix / bit-mask / Long-Float suffix forms
  if (lit ~ /^0[xX][0-9a-fA-F]+/) return 1
  if (lit ~ /^0[bB][01]+/) return 1
  if (lit ~ /[bBsSlLfF]$/) return 1
  # A3 — loop bounds, iteration and bit-shift structure
  if (line ~ /until|downTo|\bstep\b|rangeTo|\.indices|lastIndexOf|repeat[[:space:]]*\(/) return 1
  if (line ~ /coerceIn|coerceAtLeast|coerceAtMost/) return 1
  if (line ~ /ushr|shl|[^a-zA-Z]shr[^a-zA-Z]/) return 1
  # A4 — sizes and capacities
  if (line ~ /\.size|\.length|\.capacity|\.count\(|\.totalSize|Array[[:space:]]*\(|arrayOf[[:space:]]*\(|List[[:space:]]*\(|Buffer\.allocate|buildList[[:space:]]*\(|CharArray[[:space:]]*\(/) return 1
  # A5 — bit/byte/block widths
  if (lit == "8" || lit == "16" || lit == "24" || lit == "32" || lit == "64" ||
      lit == "128" || lit == "255" || lit == "256") return 1
  # A6 — spec-fixed wire widths (ISO 7813 / ICAO 9303 field positions and
  #      check-digit multipliers; Aadhaar 12-digit length)
  if (lit == "7" || lit == "9" || lit == "10" || lit == "11" || lit == "12") return 1
  # A7 — annotation lines
  if (line ~ /^[[:space:]]*@/) return 1
  # A9 — array subscript: foo[3] is a layout index, not a tunable
  if (line ~ ("\\[[[:space:]]*" lit "[[:space:]]*\\]")) return 1
  # A8 — lookup-table row: the whole line is only digits, separators and a
  #      constructor call (Verhoeff D-table, Verifier D, base64 alphabet, ...).
  #      A published table is data, not a tunable; a tuning constant never lives
  #      alone on a line like this.
  if (line ~ /^[[:space:]]*[A-Za-z_.]*[[:space:]]*\([0-9,[:space:]_.+-]*\)[[:space:]]*,?[[:space:]]*$/) return 1
  if (line ~ /^[[:space:]]*[0-9,[:space:]_.+-]+,[[:space:]]*$/) return 1
  return 0
}

BEGIN {
  inblock = 0
  for (i = 1; i < ARGC; i++) if (ARGV[i] == "--threshold-pkg") { threshpkg = ARGV[i + 1]; ARGV[i] = ""; ARGV[i + 1] = "" }
  ARGV[1] = ""
}

FNR == 1 { inmodel = (index(FILENAME, threshpkg) > 0) ? 1 : 0 }

{
  line = $0
  n = length(line)
  i = 1
  code = ""
  while (i <= n) {
    ch = substr(line, i, 1)
    nx = (i < n) ? substr(line, i + 1, 1) : ""

    if (inblock) {
      if (ch == "*" && nx == "/") { inblock = 0; i += 2 } else { code = code " "; i++ }
      continue
    }
    if (ch == "\"") {
      j = i + 1
      while (j <= n) {
        cj = substr(line, j, 1)
        if (cj == "\\" && j < n) { j += 2; continue }
        if (cj == "\"") break
        j++
      }
      code = code " "
      i = j + 1
      continue
    }
    if (ch == "/" && nx == "/") break
    if (ch == "/" && nx == "*") { inblock = 1; code = code " "; i += 2; continue }
    code = code ch
    i++
  }

  if (inmodel) next

  # tokenise the comment/string-stripped line; collect the literals that are not
  # allowlisted, then emit ONE record per offending line (readable triage).
  rest = code
  lits = ""
  while (match(rest, /(^|[^A-Za-z0-9_])[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?[fFdDlL]?/)) {
    m = substr(rest, RSTART, RLENGTH)
    pre = substr(m, 1, 1)
    lit = (pre == "") ? m : substr(m, 2)
    if (!allowed(lit, code)) lits = lits (lits == "" ? "" : " ") lit
    rest = substr(rest, RSTART + RLENGTH)
  }
  if (lits != "") printf "%s\034%d\034%s\034%s\n", FILENAME, FNR, lits, code
}
AWK

SEARCH_DIRS=("$CORE_SRC/commonMain")
[[ $INCLUDE_TESTS -eq 1 ]] && SEARCH_DIRS+=("$CORE_SRC/commonTest")
for d in "${SEARCH_DIRS[@]}"; do
  [[ -d "$d" ]] || { echo "FAIL: source set '$d' does not exist yet." >&2; exit 2; }
done

mapfile -d '' -t FILES < <(
  for d in "${SEARCH_DIRS[@]}"; do
    find "$d" -type f -name '*.kt' -print0
  done | sort -z
)

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "FAIL: no Kotlin sources in ${SEARCH_DIRS[*]} — nothing proven." >&2
  exit 2
fi

RESULTS="$(awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "${FILES[@]}" \
  | sort -t"$(printf '\034')" -k1,1 -k2,2n || true)"

if [[ -n "$BASELINE" ]]; then
  if [[ ! -f "$BASELINE" ]]; then
    echo "FAIL: baseline '$BASELINE' does not exist. Generate one deliberately, do not" >&2
    echo "      auto-create it in CI — a baseline that appears on its own is a weakened gate." >&2
    exit 2
  fi
  NEW="$(comm -23 <(printf '%s\n' "$RESULTS" | sed "s#${REPO_ROOT}/##" | sort -u) <(sort -u "$BASELINE") || true)"
else
  NEW="$(printf '%s\n' "$RESULTS" | sed "s#${REPO_ROOT}/##" | sort -u || true)"
fi

if [[ -z "$NEW" ]]; then
  echo "PASS  no unregistered numeric literals in ${SEARCH_DIRS[*]##*/} (${#FILES[@]} files scanned)."
  [[ -n "$BASELINE" ]] && echo "      all findings are inside the agreed baseline ($BASELINE)."
  exit 0
fi

COUNT="$(printf '%s\n' "$NEW" | grep -c . || true)"
{
  echo "FAIL  $COUNT source line(s) carry unregistered numeric literals in :core product"
  echo "      code (AGENTS.md §2, FR-R3)."
  echo "      Threshold tunables belong in dev/kasoti/threshold/ThresholdRegistry.kt +"
  echo "      fusion/thresholds.v1.json (name, default, unit, tuning-data ref, owner)."
  echo "      Structural numbers (loop bound, array size, spec field width, table row) are"
  echo "      already allowlisted; see the script header before extending that list."
  echo
  echo "  worst files:"
  # The row limit is applied inside the final awk, not with `head`. `head` closes the pipe
  # after N lines, the upstream stages die on SIGPIPE, and `set -o pipefail` then aborts the
  # script before it can reach `exit 1` — so the script reported 141 and never printed the
  # guidance below. An awk at the end of the pipeline reads all its input and just stops
  # printing.
  printf '%s\n' "$NEW" | awk -F"$(printf '\034')" '{print $1}' | sort | uniq -c \
    | sort -rn | awk 'NR <= 8 {printf "    %3d  %s\n", $1, $2}'
  echo
  printf '%s\n' "$NEW" \
    | awk -F"$(printf '\034')" -v max="$MAX_PRINT" \
        'NR <= max {printf "  %s:%s  [%s]\n      %s\n", $1, $2, $3, $4}'
  [[ "$COUNT" -gt "$MAX_PRINT" ]] && echo "  ... and $((COUNT - MAX_PRINT)) more line(s) (raise --max-print)"
  echo
  echo "Never silence this by widening the allowlist to make a build green (AGENTS.md §8)."
  echo "If a finding is legitimate, register the threshold or add a justified allowlist"
  echo "rule — and record the reason in the PR, not in your head."
} >&2
exit 1
