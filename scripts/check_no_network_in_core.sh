#!/usr/bin/env bash
# check_no_network_in_core.sh — AGENTS.md §5 "network-import ban" for :core.
#
# WHY: KASOTI's first principle is that no verdict path ever needs a network
#      (README "Ten principles" #1, THREAT_MODEL §1 A6). :core is the pure
#      verification module and must stay transport-free, so a reviewer can prove
#      "zero network" by inspection instead of by tracing a runtime. This script
#      is that proof, and it is a hard gate: exit 1 on the first offending
#      construct.
#
# WHAT IT SCANS: *.kt / *.kts under core/ (skipping build/ and .gradle/), i.e.
#      the product sources of :core. Tests are scanned too — a network client in
#      a test still drags the dependency into the module graph.
#
# BANNED (AGENTS.md §5): okhttp · ktor-client · HttpURLConnection ·
#      java.net.Socket · URL( · okio
#
# PRECISION: comments and string-literal contents are blanked out before the
#      match, so `// never add okhttp here` or a doc string naming okhttp does
#      NOT fail the build, while `import okhttp3.OkHttpClient` does. String
#      literals mentioning a banned term are reported INFORMATIONALLY under
#      --verbose and never fail the gate.
#
# EXIT CODES: 0 = clean · 1 = banned construct found in code · 2 = setup error.
#
# Usage:  ./scripts/check_no_network_in_core.sh [--quiet] [--verbose] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$REPO_ROOT/core"

VERBOSE=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --quiet|-q) VERBOSE=0 ;;
    --verbose|-v) VERBOSE=1 ;;
    *) echo "check_no_network_in_core.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# Banned patterns: <label>|<POSIX ERE>.
#   The java.net.* family is listed in full because java.net is entirely a
#   transport package; listing only Socket would let SocketChannel through.
BANNED=(
  'okhttp|okhttp'
  'ktor-client|ktor-client|kotlinx\.ktor\.client|io\.ktor\.client'
  'HttpURLConnection|HttpURLConnection'
  'java.net.Socket|java\.net\.(Socket|SocketChannel|ServerSocket|DatagramSocket|MulticastSocket|InetAddress|InetSocketAddress|URLConnection|URI)'
  'URL-constructor|(^|[^A-Za-z0-9_])URL[[:space:]]*\('
  'okio|(^|[^A-Za-z0-9_])okio([.]|$)'
)

[[ -d "$CORE_DIR" ]] || { echo "FAIL: :core not found at $CORE_DIR" >&2; exit 2; }

AWK_PROG="$(mktemp "${TMPDIR:-/tmp}/kasoti_net_scan.XXXXXX.awk")"
trap 'rm -f "$AWK_PROG"' EXIT

cat > "$AWK_PROG" <<'AWK'
BEGIN {
  inblock = 0
  npat = 0
  for (i = 1; i < ARGC; i++) {
    if (ARGV[i] == "--pattern") {
      npat++
      label[npat] = ARGV[i + 1]
      pat[npat] = ARGV[i + 2]
      ARGV[i] = ""; ARGV[i + 1] = ""; ARGV[i + 2] = ""
    }
  }
  ARGV[1] = ""
}

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
    if (ch == "\"") {                       # string literal -> record + blank
      j = i + 1
      lit = ""
      while (j <= n) {
        cj = substr(line, j, 1)
        if (cj == "\\" && j < n) { lit = lit cj substr(line, j + 1, 1); j += 2; continue }
        if (cj == "\"") break
        lit = lit cj
        j++
      }
      printf "%s:%d:STRING:%s\n", FILENAME, FNR, lit
      code = code " "
      i = j + 1
      continue
    }
    if (ch == "/" && nx == "/") break                       # line comment
    if (ch == "/" && nx == "*") { inblock = 1; code = code " "; i += 2; continue }
    code = code ch
    i++
  }

  for (p = 1; p <= npat; p++) {
    if (code ~ pat[p]) printf "%s:%d:CODE:%s\n", FILENAME, FNR, label[p]
  }
}
AWK

AWK_ARGS=()
for entry in "${BANNED[@]}"; do
  AWK_ARGS+=(--pattern "${entry%%|*}" "${entry#*|}")
done

mapfile -d '' -t FILES < <(
  find "$CORE_DIR" \( -type d -name build -o -type d -name '.gradle' \) -prune -o \
    -type f \( -name '*.kt' -o -name '*.kts' \) -print0 | sort -z
)

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "FAIL: no Kotlin sources under $CORE_DIR — the ban check has nothing to prove." >&2
  echo "      Report this honestly; do not treat an empty tree as a pass." >&2
  exit 2
fi

# tr -d '\000': a file being edited concurrently can momentarily contain a NUL;
# command substitution would then emit a shell warning and quietly drop bytes.
RESULTS="$(awk -f "$AWK_PROG" "${AWK_ARGS[@]}" "${FILES[@]}" | tr -d '\000' \
  | sort -t: -k1,1 -k2,2n || true)"

CODE_HITS="$(printf '%s\n' "$RESULTS" | grep -E ':CODE:' || true)"

if [[ $VERBOSE -eq 1 ]]; then
  printf '%s\n' "$RESULTS" | grep -E ':STRING:' | while IFS=: read -r f l kind lit; do
    case "$lit" in
      *okhttp*|*ktor*|*HttpURLConnection*|*okio*|*URL*|*Socket*)
        printf 'INFO  %s:%s: string literal mentions a banned term: %s\n' \
          "${f#"$REPO_ROOT"/}" "$l" "$lit" ;;
    esac
  done
fi

if [[ -z "$CODE_HITS" ]]; then
  echo "PASS  :core is network-free — ${#FILES[@]} Kotlin files scanned."
  echo "      banned: okhttp · ktor-client · HttpURLConnection · java.net.Socket · URL( · okio"
  exit 0
fi

COUNT="$(printf '%s\n' "$CODE_HITS" | wc -l | tr -d ' ')"
{
  echo "FAIL  $COUNT banned network construct(s) in :core (AGENTS.md §5):"
  printf '%s\n' "$CODE_HITS" | awk -F: '{printf "  %s:%s  [%s]\n", $1, $2, $4}' \
    | sed "s#${REPO_ROOT}/##"
  echo
  echo "Why hard: no KASOTI verdict may require a network (README principle 1)."
  echo "Fix : move the call behind an expect/actual in :platform, or delete it."
  echo "      Do NOT widen the ban list to make this pass."
} >&2
exit 1
