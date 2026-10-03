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
#         doc text and message keys never trip it. A "${...}" interpolation is
#         NOT blanked: it is code inside a string, and blanking it would have
#         hidden every literal in a rendered row (MetricSink.kt's decimals).
#      2. Numbers that are part of an identifier (sha256, utf8, q1) are not
#         numeric literals and are skipped. Kotlin's digit separators are part
#         of a literal, not of an identifier: 86_400_000L is one number.
#      3. Hex/binary literals are skipped (bit masks are structural).
#      4. The explicit allowlist below is applied per line.
#
# ---------------------------------------------------------------------------
# ALLOWLIST, in two halves. First match wins, most specific first.
#
# HALF 1 — the structural exemptions declared in the registry itself.
#
#   The registry is not only the tunables. Its `structural` array is the standing
#   answer to the question this gate keeps asking: *why is this number not a
#   tunable?* Each entry there has an id, an owner, a scope and a prose reason,
#   and each `enforcedBy: ["script"]` entry has a matching rule in HALF 2 below.
#
#   The two halves are cross-checked against each other on every run, in BOTH
#   directions, and a mismatch is exit 2 rather than a pass:
#     · an exemption the registry declares but this script cannot enforce, or
#     · a rule this script implements that the registry does not vouch for.
#   So neither file can be widened alone. Adding an `S..` entry to the JSON to
#   silence a finding does nothing until a rule exists here to match it, and
#   adding a rule here does nothing until the JSON states the reason and the
#   owner. That is the whole defence against this check being edited into
#   vacuity (AGENTS.md §5, §8), and it is why the rules live in reviewed awk
#   rather than as regexes loaded from the data file: a JSON edit cannot widen
#   the matching, and a matching change without a reason cannot widen it either.
#
#   HALF 2 rule ids, each mapping to the registry entry of the same id:
#     S01  file is under dev/kasoti/mrz/            ICAO 9303 field geometry
#     S02  CalendarDate.kt or IsoInstant.kt         ISO-8601 / Gregorian calendar
#     S03  literal is an exact scale factor         s/min/h/d/ms/ns, percent, radix
#     S04  line is `31 * x` or `x * 31`             the hashCode mixing idiom
#     S05  file is a wire codec                     RFC 4648 / JSON / IEEE-754 radix
#     S07  line names a coordinate or the radius    geodetic + physical constants
#     S08  Verhoeff.kt, RedactionPolicy.kt,         published format shapes
#          EmbModel.kt                              (ICAO, E.164, int8 quantisation)
#     S09  line is a complement or a rate bound     `1.0 - x`, `x in 0.0..1.0`
#     S10  file is Spectrum.kt                      published DSP window + guards
#     S11  line names NumberFormat. or decimals     display format widths
#          (LINE-scoped, not file-scoped: MetricSink.kt is a renderer, not a
#           formatting module, and exempting the whole file would let anything
#           hide there. Measured — a literal in MetricSink.kt IS flagged.)
#     S14  Percentiles.kt or Classification.kt      report statistic definitions
#     S15  file is Gate.kt                          SPEC.md §7 bars, not re-tunable
#     S16  line names the feature vector            LBP bins, embedding DIM
#     S17  line declares a _BYTES/_SIZE/... const   capacity reserve / headroom
#   S06, S12 and S13 are `enforcedBy: ["review"]` and deliberately have no rule:
#     S06 RedactionFold.kt's Unicode tables are already exempt as hex (A2/A5) —
#         the registry entry exists to record WHY they must never become tunable.
#     S12 the :platform BlazeFace hyperparameters are model-pinned, and this
#         script only scans :core.
#     S13 the MRZ corpus generators live in the S01 package.
#   Run with --explain to see which id allowed which line, with the reason.
#
# HALF 2 — structural, fixed in this file, none of which encodes a decision:
#   A1  0, 1, 2, 0.0*, 1.0*     first/second/last index, boolean-as-int,
#                             "unset", and the additive/multiplicative identity.
#                             (1.0 was added: it is the multiplicative identity
#                             in exactly the same sense 0.0 is the additive one,
#                             and a threshold of 1.0 is not an operating point
#                             for any quantity KASOTI thresholds.)
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
#   HONEST CAVEAT — THE RESIDUAL GAP, AND IT IS NOT SMALL. This script is a BLOCKING CI
#   gate as of 2026-10-03 (promoted from `continue-on-error: true`; decision, date and
#   reason in docs/HANDOFF.md §7). Being blocking does NOT mean this script sees every
#   literal in :core, and the difference is specific and enumerable rather than vague:
#
#   · A5 and A6 overlap with values that COULD be tunables — a brightness ceiling of 128,
#     a cosine gate of 0.11 — and nothing here can tell those apart from a width.
#   · 7 of the 14 script-enforced exemptions in HALF 1 match on PATH, not on the line.
#     A whole file (or package) is waved through, so any number anywhere inside it is
#     invisible to this gate:
#         S01  every file under core/src/commonMain/kotlin/dev/kasoti/mrz/
#         S02  dev/kasoti/time/CalendarDate, dev/kasoti/diary/IsoInstant
#         S05  dev/kasoti/json/{Base64Codec,JsonParser,CanonicalJson,JsonValue},
#              dev/kasoti/diary/Ulid, dev/kasoti/crypto/Primitives,
#              dev/kasoti/factory/ModelJson, dev/kasoti/evalmetrics/MetricJson
#         S08  dev/kasoti/checks/Verhoeff, dev/kasoti/log/RedactionPolicy,
#              dev/kasoti/diary/EmbModel
#         S10  dev/kasoti/factory/Spectrum
#         S14  dev/kasoti/evalmetrics/{Percentiles,Classification}
#         S15  dev/kasoti/evalmetrics/Gate
#     That is 7 exemptions over 17 file paths. (⚠️ an earlier revision of this header,
#     and of docs/HANDOFF.md §7, said "6 of 17" and named NumberFormat.kt. Both were
#     wrong: it is 7, and S11 is matched on the `NumberFormat.`/`decimals` token on the
#     line, not on the file — which is why MetricSink.kt, a renderer, is deliberately
#     NOT exempt and a literal in it is still flagged.)
#     MEASURED, not assumed: injecting a bare `0.42` comparison into each of those 17
#     paths in turn produces exit 0 every time, while the same literal in an unexempted
#     path such as dev/kasoti/probe/ produces exit 1.
#
#   CONSEQUENCE, stated plainly: a tunable added inside one of those 17 paths will NOT
#   fail this gate. detekt would catch it — and detekt is `continue-on-error: true`,
#   still red at ~1.2k issues, and has no notion of a structural exemption, so it is not
#   a net either. There is therefore NO automated net for a magic number in those files.
#   A reviewer must still read a diff that touches them (AGENTS.md §4, §8).
#   Do not treat "not flagged" as "not a threshold".
#
#   NOTE: A6 exists because :core implements published specifications (ICAO
#   9303 MRZ, Verhoeff, base64/byte packing, ISO 18004). Numbers fixed by a
#   standard are not tunables. If you disagree with a specific hit, register
#   the value in fusion/thresholds.v1.json properly rather than editing this
#   allowlist to silence a review.
#
# EXIT CODES: 0 = clean · 1 = magic numbers found · 2 = usage/setup error,
#             which includes the registry cross-check failing.
#
# Usage:
#   ./scripts/check_no_magic_thresholds.sh [--include-tests] [--max-print N]
#                                            [--baseline FILE] [--explain] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_SRC="$REPO_ROOT/core/src"
THRESHOLD_PKG="dev/kasoti/threshold"
REGISTRY_JSON="$CORE_SRC/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json"

INCLUDE_TESTS=0
MAX_PRINT=40
BASELINE=""
EXPLAIN=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --include-tests) INCLUDE_TESTS=1 ;;
    --explain) EXPLAIN=1 ;;
    --max-print) MAX_PRINT="${2:?--max-print needs a number}"; shift ;;
    --baseline) BASELINE="${2:?--baseline needs a path}"; shift ;;
    *) echo "check_no_magic_thresholds.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

[[ -d "$CORE_SRC" ]] || { echo "FAIL: core/src not found at $CORE_SRC" >&2; exit 2; }
[[ -f "$REGISTRY_JSON" ]] || {
  echo "FAIL: the threshold registry is missing at $REGISTRY_JSON." >&2
  echo "      AGENTS.md §2 requires it, and the structural exemptions below are" >&2
  echo "      declared in it — without it this script cannot check its own allowlist." >&2
  exit 2
}

AWK_PROG="$(mktemp "${TMPDIR:-/tmp}/kasoti_magic_scan.XXXXXX.awk")"
REG_PROG="$(mktemp "${TMPDIR:-/tmp}/kasoti_magic_registry.XXXXXX.awk")"
trap 'rm -f "$AWK_PROG" "$REG_PROG"' EXIT

# ---------------------------------------------------------------------------
# The registry side of the cross-check: tab-separated id, enforcers, reason.
# Line-oriented on purpose — the file is a data file, and the only property this
# script needs from it is "does an S.. id exist, who enforces it, and why". If
# the layout ever stops yielding ids, the count check below fails closed.
# ---------------------------------------------------------------------------
cat > "$REG_PROG" <<'REG'
/"id"[[:space:]]*:[[:space:]]*"/ {
  line = $0
  sub(/^.*"id"[[:space:]]*:[[:space:]]*"/, "", line)
  sub(/".*$/, "", line)
  pending = line
  next
}
/"enforcedBy"[[:space:]]*:/ {
  by = ($0 ~ /"script"/) ? "script" : ""
  if ($0 ~ /"review"/) by = (by == "") ? "review" : "script,review"
  if (pending != "") print pending "\t" by
  next
}
/"reason"[[:space:]]*:[[:space:]]*"/ {
  if (pending == "") next
  r = $0
  sub(/^.*"reason"[[:space:]]*:[[:space:]]*"/, "", r)
  sub(/"[[:space:]]*,?[[:space:]]*$/, "", r)
  reason[pending] = r
  next
}
END { for (id in reason) print id "\tREASON\t" reason[id] }
REG

REGISTRY_IDS="$(awk -f "$REG_PROG" "$REGISTRY_JSON" | cut -f1 | grep '^S[0-9]' | sort -u || true)"
DECLARED_SCRIPT="$(awk -f "$REG_PROG" "$REGISTRY_JSON" | awk -F'\t' '$2 ~ /script/ {print $1}' | sort -u || true)"

[[ -n "$REGISTRY_IDS" ]] || {
  echo "FAIL: no structural exemptions could be read out of $REGISTRY_JSON." >&2
  echo "      Expected entries of the form \"id\": \"S01-...\"." >&2
  echo "      Refusing to continue: without them this script cannot verify its" >&2
  echo "      own allowlist against the registry, and an unchecked allowlist is" >&2
  echo "      indistinguishable from one that was widened." >&2
  exit 2
}

# ---------------------------------------------------------------------------
# The script side: the ids HALF 2 actually implements.
# ---------------------------------------------------------------------------
IMPLEMENTED_SCRIPT=(
  S01-icao-9303-mrz-layout
  S02-iso-8601-gregorian-calendar
  S03-exact-scale-factor
  S04-hash-mix-multiplier
  S05-wire-codec-radic
  S07-geodetic-physical-constant
  S08-published-format-shape
  S09-percent-unity-conversion
  S10-published-dsp-window
  S11-display-format-width
  S14-report-statistic-definition
  S15-spec-fixed-gate-bar
  S16-feature-vector-layout
  S17-capacity-reserve
)

CROSSCHECK_FAILED=0
while read -r id; do
  [[ -z "$id" ]] && continue
  if ! printf '%s\n' "${IMPLEMENTED_SCRIPT[@]}" | grep -qxF "$id"; then
    echo "FAIL: the registry declares structural exemption '$id' with enforcedBy [\"script\"]," >&2
    echo "      but scripts/check_no_magic_thresholds.sh implements no rule for it." >&2
    echo "      Either the exemption is not actually being enforced — in which case" >&2
    echo "      declaring it is a claim the gate does not honour — or the rule was" >&2
    echo "      removed from the script and the exemption should drop \"script\"." >&2
    CROSSCHECK_FAILED=1
  fi
done <<< "$DECLARED_SCRIPT"

for id in "${IMPLEMENTED_SCRIPT[@]}"; do
  if ! grep -qxF "$id" <<< "$DECLARED_SCRIPT"; then
    echo "FAIL: this script implements an allowlist rule for '$id' that the registry does" >&2
    echo "      not declare as script-enforced." >&2
    echo "      A rule with no entry in fusion/thresholds.v1.json is an undocumented" >&2
    echo "      exemption: it silences findings without an owner or a reason, which is" >&2
    echo "      exactly what AGENTS.md §8 forbids." >&2
    CROSSCHECK_FAILED=1
  fi
done

[[ "$CROSSCHECK_FAILED" -eq 0 ]] || exit 2

cat > "$AWK_PROG" <<'AWK'
# Scale factors that are exact by definition: unit conversions between
# s/min/h/d/ms/us, the percent factor, and the decimal radix. A wrong value here
# is a units bug, not a tuning decision (S03).
#
# Written as explicit assignments rather than a split loop on purpose: this awk is
# a gawk build without `nsplit`, and an allowlist that only loads on one awk is
# not an allowlist. Underscores are already stripped by the tokeniser, so the
# underscored spellings (86_400_000) arrive here as 86400000.
BEGIN {
  scale["10"] = 1; scale["60"] = 1; scale["100"] = 1; scale["1000"] = 1
  scale["3600"] = 1; scale["60000"] = 1; scale["1000000"] = 1
  scale["3600000"] = 1; scale["86400000"] = 1

  scale["10.0"] = 1; scale["60.0"] = 1; scale["100.0"] = 1; scale["1000.0"] = 1
  scale["3600.0"] = 1; scale["60000.0"] = 1; scale["1000000.0"] = 1
  scale["3600000.0"] = 1; scale["86400000.0"] = 1
}

# Returns the registry id of the structural exemption that allows this literal
# on this line, or "" when nothing does. Most specific rule first: the file- and
# line-scoped exemptions are consulted before the two value-set rules so that the
# reason reported by --explain is the real one rather than a coincidence.
function structural(lit, line, file) {
  # S01 — the ICAO 9303 machine-readable zone. Field geometry, line lengths and
  # check-digit weights are fixed by the standard, not chosen.
  if (file ~ /\/dev\/kasoti\/mrz\//) return "S01-icao-9303-mrz-layout"
  # S02 — the calendar codecs. Published algorithm constants and Gregorian rules.
  if (file ~ /\/dev\/kasoti\/(time\/CalendarDate|diary\/IsoInstant)\.kt$/)
    return "S02-iso-8601-gregorian-calendar"
  # S15 — SPEC.md §7 ship blockers. Fixed by the spec, and the policy bars that
  # are not reach this file as arguments.
  if (file ~ /\/dev\/kasoti\/evalmetrics\/Gate\.kt$/) return "S15-spec-fixed-gate-bar"
  # S10 — the published Hann window, the radix-2 transform, and the numerical
  # guards that keep a flat spectrum from producing a NaN. The one band choice
  # in the file is registered (HALFTONE_HIGH_FREQ_RADIUS) and lives elsewhere.
  if (file ~ /\/dev\/kasoti\/factory\/Spectrum\.kt$/) return "S10-published-dsp-window"
  # S11 — a literal that is the width argument of a formatting call.
  # Anchored on the call rather than on the file: MetricSink.kt is a renderer,
  # not a formatting module, and exempting the whole of it would let any future
  # number hide there. It is reachable only through a ${} interpolation — see the
  # scanner note in the header.
  if (line ~ /NumberFormat[.]|decimals/) return "S11-display-format-width"
  # S14 — numbers that define a summary statistic rather than an operating point.
  if (file ~ /\/dev\/kasoti\/evalmetrics\/(Percentiles|Classification)\.kt$/)
    return "S14-report-statistic-definition"
  # S08 — shapes fixed by a published specification or a storage format.
  if (file ~ /\/dev\/kasoti\/(checks\/Verhoeff|log\/RedactionPolicy|diary\/EmbModel)\.kt$/)
    return "S08-published-format-shape"
  # S05 — wire codecs: RFC 4648 group sizes, JSON \u escape width, IEEE-754.
  if (file ~ /\/dev\/kasoti\/(json\/(Base64Codec|JsonParser|CanonicalJson|JsonValue)|diary\/Ulid|crypto\/Primitives|factory\/ModelJson|evalmetrics\/MetricJson)\.kt$/)
    return "S05-wire-codec-radic"
  # S07 — the definition of the coordinate system, and the IUGG mean radius.
  if (line ~ /latDeg|lonDeg|EARTH_MEAN_RADIUS/) return "S07-geodetic-physical-constant"
  # S16 — the feature vector's shape. The extractor and the trained model share
  # one width; moving it desynchronises them rather than tuning them.
  if (line ~ /LBP_BINS|bin[[:space:]]*==[[:space:]]*[0-9]|DIM[[:space:]]*[:=]/)
    return "S16-feature-vector-layout"
  # S17 — a declared reserve, sized with headroom rather than measured.
  if (line ~ /_(BYTES|SIZE|CAPACITY|OVERHEAD|LENGTH)[[:space:]]*[:=]/)
    return "S17-capacity-reserve"
  # S04 — the 31-multiplier hashCode idiom Kotlin's data classes generate.
  if (line ~ /31[[:space:]]*\*|[^0-9a-zA-Z_.]31[^0-9]/) return "S04-hash-mix-multiplier"
  # S09 — a complement of a rate, or the closed unit interval it lives in.
  if (line ~ /1\.0[[:space:]]*-|1\.0[[:space:]]*\)|-[[:space:]]*1\.0|0\.0[[:space:]]*\.\.[[:space:]]*1\.0/)
    return "S09-percent-unity-conversion"
  # S03 — exact scale factors.
  if (lit in scale) return "S03-exact-scale-factor"
  return ""
}

function allowed(lit, line, file) {
  # HALF 1 — the registry's own structural exemptions.
  if (structural(lit, line, file) != "") return 1
  # A1 — structural zero/one/two and the floating-point identity
  if (lit == "0" || lit == "1" || lit == "2") return 1
  if (lit == "0.0" || lit == "0.0f" || lit == "0.0F" || lit == "0f" || lit == "0F") return 1
  if (lit == "1.0" || lit == "1.0f" || lit == "1.0F" || lit == "1f" || lit == "1F") return 1
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
        # A "${...}" interpolation is CODE inside a string, not string text. Blanking the
        # whole literal would hide the literals in it -- which is how `"| $n |
        # ${NumberFormat.fixed(v, 6)} |"` was invisible to this script while detekt counted
        # it, and why the two tools disagreed about MetricSink.kt. Copy the expression body
        # into the code stream instead of discarding it.
        if (cj == "{" && substr(line, j - 1, 1) == "$") {
          code = code " "
          k = j + 1
          depth = 1
          while (k <= n && depth > 0) {
            ck = substr(line, k, 1)
            if (ck == "{") depth++
            else if (ck == "}") depth--
            if (depth > 0) code = code ck
            k++
          }
          j = k
          continue
        }
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
  #
  # Underscores are part of a numeric literal in Kotlin (86_400_000L), so they are
  # consumed here and stripped before the allowlist is consulted. Without that the
  # tokeniser would see 86_400_000L as 86, 400 and 000, and would both report a
  # nonsense literal and miss the allowlist entry for the milliseconds in a day.
  rest = code
  lits = ""
  while (match(rest, /(^|[^A-Za-z0-9_])[0-9][0-9_]*(\.[0-9][0-9_]*)?([eE][+-]?[0-9]+)?[fFdDlL]?/)) {
    m = substr(rest, RSTART, RLENGTH)
    pre = substr(m, 1, 1)
    lit = (pre == "") ? m : substr(m, 2)
    key = lit
    gsub(/_/, "", key)
    if (!allowed(key, code, FILENAME)) lits = lits (lits == "" ? "" : " ") lit
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

# ---------------------------------------------------------------------------
# SELF-TEST: prove the scanner still flags a tunable before trusting its silence.
#
# This gate's worst failure mode is not a false positive, it is a false PASS — an
# allowlist edit, a broken tokeniser or an awk syntax error that leaves the scan
# finding nothing, which reads exactly like a clean tree. The first version of
# this script had precisely that hole: `| sort || true` swallowed a hard awk
# failure and printed PASS over it. So the scanner is run against a synthetic
# file containing a number that no allowlist rule can claim, and the run aborts
# unless that number is reported.
# ---------------------------------------------------------------------------
SELFTEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kasoti_magic_selftest.XXXXXX")"
trap 'rm -f "$AWK_PROG" "$REG_PROG"; rm -rf "$SELFTEST_DIR"' EXIT
SELFTEST_FILE="$SELFTEST_DIR/SelfTestProbe.kt"
cat > "$SELFTEST_FILE" <<'PROBE'
package dev.kasoti.selftest

internal object SelfTestProbe {
    fun probe(raw: Double): Boolean = raw > 0.42
}
PROBE

if ! SELFTEST_OUT="$(awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "$SELFTEST_FILE")"; then
  echo "FAIL: the scanner itself errored on its self-test input. That is a bug in this" >&2
  echo "      script, and reporting a clean tree over it would be the one failure mode" >&2
  echo "      this gate must never have. Nothing below can be trusted." >&2
  awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "$SELFTEST_FILE" >&2 || true
  exit 2
fi

if [[ -z "$SELFTEST_OUT" ]]; then
  echo "FAIL: the scanner did not flag a bare 0.42 in a plain comparison. The allowlist" >&2
  echo "      has become vacuous, or the tokeniser is broken, so a PASS from this run" >&2
  echo "      would mean nothing. Refusing to report a result (AGENTS.md §8)." >&2
  exit 2
fi

# And the other direction: a structural exemption must not leak. HALF 2 exempts the
# 31-multiplier idiom; if that stopped matching, the gate would be noisy rather than
# wrong, so this is a tripwire on the rule table rather than a correctness gate.
cat > "$SELFTEST_FILE" <<'PROBE2'
package dev.kasoti.selftest

internal object SelfTestProbe {
    fun mix(a: Int, b: Int): Int = 31 * a + b
}
PROBE2
if [[ -n "$(awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "$SELFTEST_FILE" || true)" ]]; then
  echo "FAIL: the declared structural exemptions no longer match their own probes (the" >&2
  echo "      31-multiplier probe was flagged). Either an exemption id lost its rule or a" >&2
  echo "      rule was dropped; the registry cross-check above should have caught it." >&2
  exit 2
fi

# Third probe: a literal inside a string interpolation. "${...}" is code inside a
# string, and an earlier version of this scanner blanked it along with the string,
# which hid every formatting width in MetricSink.kt from this check while detekt
# counted them -- the concrete reason the two tools used to disagree.
cat > "$SELFTEST_FILE" <<'PROBE3'
package dev.kasoti.selftest

internal object SelfTestProbe {
    fun render(name: String, value: Double): String = "| $name | ${value.toString(4)} |"
}
PROBE3
if [[ -z "$(awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "$SELFTEST_FILE" || true)" ]]; then
  echo "FAIL: the scanner does not look inside a string interpolation. A literal in" >&2
  echo "      \${...} is invisible to it, which is a hole rather than an exemption:" >&2
  echo "      anything can be hidden by wrapping it in a template." >&2
  exit 2
fi

RESULTS="$(awk -f "$AWK_PROG" --threshold-pkg "$THRESHOLD_PKG" "${FILES[@]}" \
  | sort -t"$(printf '\034')" -k1,1 -k2,2n)" || {
  echo "FAIL: the scan over ${#FILES[@]} source file(s) errored." >&2
  echo "      That is a bug or an unreadable file, NOT a clean result. Exiting 2 rather" >&2
  echo "      than reporting a pass (see the self-test note in this file)." >&2
  exit 2
}

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

if [[ $EXPLAIN -eq 1 ]]; then
  {
    echo "structural exemptions in force, as declared by fusion/thresholds.v1.json:"
    while read -r id enforcers; do
      reason="$(awk -f "$REG_PROG" "$REGISTRY_JSON" | awk -F'\t' -v k="$id" '$1 == k && $2 == "REASON" {print $3}')"
      printf '  %-36s [%s]\n      %s\n' "$id" "$enforcers" "$reason"
    done <<< "$(awk -f "$REG_PROG" "$REGISTRY_JSON" | awk -F'\t' '$2 != "REASON" {print $1 "\t" $2}' | sort -u)"
  } >&2
fi

if [[ -z "$NEW" ]]; then
  echo "PASS  no unregistered numeric literals in ${SEARCH_DIRS[*]##*/} (${#FILES[@]} files scanned)."
  echo "      Allowlist: A1-A9 in this script + $(printf '%s\n' "${IMPLEMENTED_SCRIPT[@]}" | grep -c .) registry-declared exemptions."
  [[ -n "$BASELINE" ]] && echo "      all findings are inside the agreed baseline ($BASELINE)."
  exit 0
fi

COUNT="$(printf '%s\n' "$NEW" | grep -c . || true)"
{
  echo "FAIL  $COUNT source line(s) carry unregistered numeric literals in :core product"
  echo "      code (AGENTS.md §2, FR-R3)."
  echo "      Threshold tunables belong in fusion/thresholds.v1.json"
  echo "      (name, default, unit, tuning-data ref, owner). A number that is NOT a"
  echo "      tunable needs a structural exemption in the same file, with a reason and"
  echo "      an owner — not an edit to this script's allowlist (AGENTS.md §8)."
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
  echo "If a finding is legitimate, add the threshold or a justified structural exemption"
  echo "to fusion/thresholds.v1.json — and record the reason in the PR, not in your head."
} >&2
exit 1