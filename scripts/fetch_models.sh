#!/usr/bin/env bash
# fetch_models.sh — download the hash-pinned model corpus (BUILD.md §3).
#
# WHY: model binaries are deliberately NOT in git (.gitignore excludes
#   *.tflite and eval/models/). They are fetch artefacts, and every fetch is
#   verified against a pinned SHA-256 so a swapped or truncated model cannot
#   reach a verdict (invariant I12, AT-12, RT-E8). Apps then load ONLY from
#   bundled assets — nothing is ever fetched at runtime.
#
# ############################################################################
# #  STATUS: POPULATED as of 2026-09-30 (docs/spikes/01-face-model.md).         #
# #  The real manifest is scripts/models_manifest.tsv and it contains one       #
# #  entry: blazeface_short.tflite, Apache-2.0, source URL and measured         #
# #  SHA-256 recorded. The `emb_v1.tflite` embedding slot is deliberately        #
# #  EMPTY — seven candidates were rejected on licence grounds and no weights   #
# #  were fabricated. The face layer therefore locates a face but cannot        #
# #  compare one, and a GREEN 1:1 verdict stays unreachable. That is the       #
# #  correct fail-closed outcome, not a gap in this script.                     #
# #                                                                          #
# #  The manifest next to this file, models_manifest.sample.tsv, is still the   #
# #  refusal-path demo: every field is REPLACE_ME, so running the script       #
# #  against it still fails with "UNPOPULATED manifest entry" and performs no  #
# #  network access. Both behaviours are tested by scripts/verify_bundle.sh.    #
# ############################################################################
#
# MANIFEST FORMAT (one entry per line, tab- or space-separated):
#     <sha256>  <url>  <destination path relative to the repo root>
#   * '#' comments and blank lines are ignored
#   * REPLACE_ME in ANY field is a hard error — an unpopulated manifest never
#     reaches the network
#   * the URL must be https (a plaintext http model URL is a supply-chain hole)
#
# WHAT IT DOES PER ENTRY: download to a temp file, verify SHA-256, then move
#   into place. A file that fails verification is DELETED, never installed.
#   Already-present files whose hash matches are left alone (idempotent).
#
# EXIT CODES: 0 = all entries present and verified · 1 = manifest unpopulated /
#   download or hash failure · 2 = usage or setup error.
#
# Usage:
#   ./scripts/fetch_models.sh [manifest] [--dest-root DIR] [--dry-run]
#                             [--only SUBSTR] [--record] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Default to the REAL manifest when it exists, and fall back to the sample (which still
# demonstrates the refusal path). Precedence: explicit arg > real manifest > sample.
REAL_MANIFEST="$REPO_ROOT/scripts/models_manifest.tsv"
SAMPLE_MANIFEST="$REPO_ROOT/scripts/models_manifest.sample.tsv"
if [[ -f "$REAL_MANIFEST" ]]; then
  DEFAULT_MANIFEST="$REAL_MANIFEST"
else
  DEFAULT_MANIFEST="$SAMPLE_MANIFEST"
fi

MANIFEST=""
DEST_ROOT="$REPO_ROOT"
DRY_RUN=0
ONLY=""
RECORD=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --dest-root) DEST_ROOT="${2:?--dest-root needs a path}"; shift ;;
    --dry-run) DRY_RUN=1 ;;
    --only) ONLY="${2:?--only needs a substring}"; shift ;;
    --record) RECORD=1 ;;
    -*) echo "fetch_models.sh: unknown option '$1' (try --help)" >&2; exit 2 ;;
    *)
      if [[ -n "$MANIFEST" ]]; then
        echo "fetch_models.sh: more than one manifest given ('$MANIFEST' and '$1')." >&2
        exit 2
      fi
      MANIFEST="$1"
      ;;
  esac
  shift
done

MANIFEST="${MANIFEST:-$DEFAULT_MANIFEST}"

[[ -f "$MANIFEST" ]] || {
  {
    echo "FAIL: model manifest '$MANIFEST' not found."
    echo "      Expected a populated manifest (sha256, url, destination) — see the"
    echo "      sample next to this script for the format."
  } >&2
  exit 2
}
[[ -d "$DEST_ROOT" ]] || { echo "FAIL: --dest-root '$DEST_ROOT' is not a directory." >&2; exit 2; }

if [[ $DRY_RUN -eq 0 ]]; then
  command -v curl >/dev/null 2>&1 || { echo "FAIL: curl is required to fetch models." >&2; exit 2; }
fi
command -v sha256sum >/dev/null 2>&1 || { echo "FAIL: sha256sum is required." >&2; exit 2; }

# --- parse ------------------------------------------------------------------
WANT=(); URLS=(); DESTS=(); BAD=0; COUNT=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ -z "${line//[[:space:]]/}" ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  read -r a b c <<< "$line"
  if [[ -z "$b" || -z "$c" ]]; then
    printf 'FAIL  malformed manifest line (want: sha256, url, dest): %s\n' "$line" >&2
    BAD=1; continue
  fi
  COUNT=$((COUNT + 1))
  if [[ "$a" == "REPLACE_ME" && $RECORD -eq 1 ]]; then
    # --record is the bootstrap path: Google publishes no .sha256 sidecar for the BlazeFace
    # model, so the pin has to be MEASURED from the bytes we fetch. Tolerating a placeholder
    # hash here is safe precisely because --record never installs anything; it only prints.
    # The URL and destination must still be real, so a record run can never be pointed at a
    # remembered URL by accident.
    a=""
  elif [[ "$a" == "REPLACE_ME" || "$b" == "REPLACE_ME" || "$c" == "REPLACE_ME" ]]; then
    printf 'FAIL  UNPOPULATED manifest entry (REPLACE_ME): %s\n' "$line" >&2
    BAD=1; continue
  fi
  if [[ -n "$a" && ! "$a" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'FAIL  manifest entry has no real SHA-256: %s\n' "$line" >&2
    BAD=1; continue
  fi
  if [[ "$b" != https://* ]]; then
    printf 'FAIL  refusing non-https model URL (supply chain, AT-12): %s\n' "$b" >&2
    BAD=1; continue
  fi
  if [[ -n "$ONLY" && "$c" != *"$ONLY"* ]]; then continue; fi
  WANT+=("${a,,}"); URLS+=("$b"); DESTS+=("$c")
done < "$MANIFEST"

if [[ $BAD -ne 0 ]]; then
  {
    echo
    echo "      Refusing to download anything from an unpopulated/invalid manifest."
    echo "      No model source has been confirmed yet — obtain the weights, record"
    echo "      provenance and licence in THIRD_PARTY.md (BUILD.md §6), pin the SHA-256"
    echo "      here, and re-run. Do NOT paste a URL from memory (AGENTS.md §8)."
  } >&2
  exit 1
fi

if [[ $COUNT -eq 0 ]]; then
  echo "FAIL: manifest '$MANIFEST' contains no entries." >&2
  exit 1
fi

if [[ ${#DESTS[@]} -eq 0 ]]; then
  echo "FAIL: --only '$ONLY' matched none of the $COUNT manifest entries." >&2
  exit 1
fi

# --- plan -------------------------------------------------------------------
echo "PLAN  ${#DESTS[@]} model file(s) from $MANIFEST"
for i in "${!DESTS[@]}"; do printf '        %s\n' "${DESTS[$i]}"; done

if [[ $DRY_RUN -eq 1 ]]; then
  echo "DRY-RUN: no network access performed."
  exit 0
fi

# --- record: measure digests, install nothing ----------------------------------
#
# The provenance story for every model pin in this repo goes through here. Google ships no
# .sha256 sidecar for the BlazeFace model, so the pinned digest in models_manifest.tsv was
# produced by downloading the file and hashing it. AGENTS.md §8 forbids inventing a hash,
# and the difference between a measured pin and a remembered one is invisible to every
# other check in this script — so this mode exists to make the measured path the easy one.
if [[ $RECORD -eq 1 ]]; then
  TMPDIR_FETCH="$(mktemp -d "${TMPDIR:-/tmp}/kasoti_models.XXXXXX")"
  trap 'rm -rf "$TMPDIR_FETCH"' EXIT
  echo "RECORD: measuring digests. NOTHING will be installed."
  RECFAIL=0
  for i in "${!DESTS[@]}"; do
    want="${WANT[$i]}"; url="${URLS[$i]}"; dest="${DESTS[$i]}"
    tmpf="$TMPDIR_FETCH/$(basename "$dest").part"
    if ! curl --fail --silent --show-error --location \
          --proto '=https' --tlsv1.2 --max-time 300 --retry 2 --retry-delay 2 \
          -o "$tmpf" "$url"; then
      printf 'FETCH-FAIL %s (%s)\n' "$dest" "$url" >&2
      RECFAIL=$((RECFAIL + 1)); continue
    fi
    got="$(sha256sum "$tmpf" | awk '{print $1}')"
    size="$(wc -c < "$tmpf" | tr -d ' ')"
    if [[ -n "$want" && "$want" != "$got" ]]; then
      printf 'DRIFT     %s\n          manifest %s\n          fetched  %s\n' \
        "$dest" "$want" "$got" >&2
      printf '          The upstream artefact CHANGED. Re-read the licence and the model\n'
      printf '          card before re-pinning; a changed digest is a supply-chain event.\n' >&2
      RECFAIL=$((RECFAIL + 1)); continue
    fi
    printf 'RECORDED  %s  %s  %s  (%s bytes)\n' "$got" "$url" "$dest" "$size"
  done
  echo
  if [[ $RECFAIL -ne 0 ]]; then
    echo "FAIL: $RECFAIL entr(ies) could not be recorded." >&2
    exit 1
  fi
  echo "PASS  digests measured. Paste them into the manifest; nothing was installed."
  exit 0
fi

# --- fetch + verify ---------------------------------------------------------
TMPDIR_FETCH="$(mktemp -d "${TMPDIR:-/tmp}/kasoti_models.XXXXXX")"
trap 'rm -rf "$TMPDIR_FETCH"' EXIT

OK=0; SKIP=0; FAILN=0
for i in "${!DESTS[@]}"; do
  want="${WANT[$i]}"; url="${URLS[$i]}"; dest="${DESTS[$i]}"
  full="$DEST_ROOT/$dest"

  if [[ -f "$full" ]]; then
    have="$(sha256sum "$full" | awk '{print $1}')"
    if [[ "$have" == "$want" ]]; then
      printf 'CACHED    %s\n' "$dest"
      OK=$((OK + 1)); continue
    fi
    printf 'STALE     %s (hash differs — refetching)\n' "$dest"
  fi

  tmpf="$TMPDIR_FETCH/$(basename "$dest").part"
  mkdir -p "$(dirname "$full")"
  if ! curl --fail --silent --show-error --location \
        --proto '=https' --tlsv1.2 --max-time 300 --retry 2 --retry-delay 2 \
        -o "$tmpf" "$url"; then
    printf 'FETCH-FAIL %s (%s)\n' "$dest" "$url" >&2
    FAILN=$((FAILN + 1)); continue
  fi

  got="$(sha256sum "$tmpf" | awk '{print $1}')"
  if [[ "$got" != "$want" ]]; then
    printf 'HASH-FAIL %s\n          expected %s\n          actual   %s\n' \
      "$dest" "$want" "$got" >&2
    printf '          the partial download was DISCARDED and not installed.\n' >&2
    FAILN=$((FAILN + 1)); continue
  fi

  mv "$tmpf" "$full"
  printf 'FETCHED   %s\n' "$dest"
  OK=$((OK + 1))
done

echo
echo "SUMMARY  ${OK} verified · ${SKIP} skipped · ${FAILN} failed"
if [[ $FAILN -ne 0 ]]; then
  {
    echo "FAIL: $FAILN model file(s) not obtained or not verified."
    echo "      Nothing unverified was installed. Do not build, do not demo."
    echo "      Once the manifest is populated, verify the bundle with:"
    echo "        ./scripts/verify_bundle.sh"
  } >&2
  exit 1
fi
echo "PASS  every requested model is present and hash-verified."
echo "      Next: ./scripts/verify_bundle.sh"
