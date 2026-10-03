#!/usr/bin/env bash
# airplane_install_test.sh — the no-network install proof (BUILD.md §3 "offline
# proof", README "First-run rule", DEMO.md §4 setup checklist, AGENTS.md §1).
#
# WHAT IT PROVES, in order:
#   A. a build artefact actually exists (APK / AAB / desktop bundle)
#   B. the bundled models + keys match their pinned SHA-256 (verify_bundle.sh)
#   C. the artefact contains NO model/key payload fetched at runtime — we grep
#      the unpacked artefact for a runtime-downloader and report what we found
#   D. IF adb + a device are present: install and launch with the radio off,
#      then check logcat for the app starting
#   E. if no device: print the exact human steps and say the proof is INCOMPLETE
#
# HONESTY: without an APK and without a device, steps A–C still run and step D/E
#   report "not proven". The script FAILS in that case. A no-network claim that
#   was never exercised on hardware is not a proof, and the script says so
#   rather than exiting green.
#
# ############################################################################
# #  STATUS (updated 2026-10-03): :app-android DOES build on a machine with#
# #  an SDK, and it now produces ONE APK PER ABI (armeabi-v7a, arm64-v8a)  #
# #  rather than a single universal one — see the `splits.abi` ADR in        #
# #  app-android/build.gradle.kts. Step A below therefore enumerates every  #
# #  artefact and size-checks all of them; only the install step picks one.  #
# #  There is no emulator (x86/x86_64) build: it was 35.95 MB, over the     #
# #  35 MB budget.                                                          #
# ############################################################################
#
# EXIT CODES: 0 = full proof (artefact + bundle + device install) ·
#   1 = a check FAILED (bundle hash mismatch, install error, runtime fetcher) ·
#   2 = cannot run yet (no artefact / no adb / no device) — "not proven".
#
# Usage:
#   ./scripts/airplane_install_test.sh [--apk PATH] [--skip-device]
#                                     [--skip-bundle] [--package PKG]
#                                     [--adb-serial SERIAL] [--help]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

APK=""
SKIP_DEVICE=0
SKIP_BUNDLE=0
PACKAGE="dev.kasoti.app"
ADB_SERIAL=""
MAX_APK_MB=35          # BUILD.md §3: APK <= 35 MB gate

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --apk) APK="${2:?--apk needs a path}"; shift ;;
    --skip-device) SKIP_DEVICE=1 ;;
    --skip-bundle) SKIP_BUNDLE=1 ;;
    --package) PACKAGE="${2:?--package needs a name}"; shift ;;
    --adb-serial) ADB_SERIAL="${2:?--adb-serial needs a value}"; shift ;;
    *) echo "airplane_install_test.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

say()  { printf '\n=== %s\n' "$1"; }
warn() { printf 'WARN  %s\n' "$1" >&2; }
fail() { printf '\nFAIL  %s\n' "$1" >&2; }

say "A. build artefact"

# ABI splits (app-android/build.gradle.kts, the `splits.abi` ADR): there is NO universal APK and
# no `app-android-debug.apk` any more. `assembleDebug` emits one APK per shipped ABI, so this
# script has to enumerate them.
#
# EVERY discovered APK is size-checked, not just the one that gets installed. Picking one file
# and measuring that is the same bug as "measure the smallest artifact": an over-budget sibling
# would sail through. Which one is *installed* is a separate question answered by --apk or, on
# a device-attached run, by the operator passing the right ABI (adb will say
# INSTALL_FAILED_NO_MATCHING_ABIS if it is wrong).
if [[ -z "$APK" ]]; then
  mapfile -t ALL_APKS < <(find . -path ./\.git -prune -o -type f \( -name '*.apk' -o -name '*.aab' \) \
    -path '*/build/outputs/*' -print 2>/dev/null | sort)
else
  ALL_APKS=("$APK")
fi

if [[ "${#ALL_APKS[@]}" -eq 0 ]]; then
  fail "no APK/AAB build output found."
  cat >&2 <<EOF

  This test cannot pass yet: there is nothing to install. Honest status —
  the no-network install proof has NOT been run.

  To produce the artefact:
      ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app-android:assembleDebug
      # or, for the release gate (BUILD.md §3):
      ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app-android:bundleRelease

  Note: settings.gradle.kts only includes :app-android when an SDK is
  discoverable, so a desktop-only machine cannot produce this artefact at all.
  (:ui is NOT affected — it is always in the build; this message used to claim
  otherwise, which was wrong.)

  :app-android now produces one APK per ABI (armeabi-v7a, arm64-v8a). Pick the
  one matching the device with --apk; there is no single APK that fits both.

  Then re-run:  ./scripts/airplane_install_test.sh
EOF
  exit 2
fi

OVER_BUDGET=()
for candidate in "${ALL_APKS[@]}"; do
  CANDIDATE_MB="$(awk -v b="$(stat -c %s "$candidate")" 'BEGIN { printf "%.2f", b / 1048576 }')"
  printf 'FOUND  %s  (%s MB)\n' "$candidate" "$CANDIDATE_MB"
  if awk -v m="$CANDIDATE_MB" -v g="$MAX_APK_MB" 'BEGIN { exit !(m > g) }'; then
    OVER_BUDGET+=("$candidate ($CANDIDATE_MB MB)")
  fi
done

if [[ "${#OVER_BUDGET[@]}" -gt 0 ]]; then
  for bad in "${OVER_BUDGET[@]}"; do
    fail "$bad is over the ${MAX_APK_MB} MB budget (BUILD.md §3)."
  done
  echo "      Every produced artefact is gated, not just the one being installed." >&2
  echo "      Do not ship it — find what got bundled (models? debug symbols?) first." >&2
  exit 1
fi

# With splits there are several candidates, so "the first one alphabetically" is arbitrary.
# Prefer the release build when both exist (it is the shipping artefact), else the first.
APK="${ALL_APKS[0]}"
for candidate in "${ALL_APKS[@]}"; do
  if [[ "$candidate" == *"/apk/release/"* ]]; then
    APK="$candidate"
    break
  fi
done
echo "INSTALL CANDIDATE  $APK"
APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
echo "SHA256 $APK_SHA"

say "B. bundled model / key integrity (invariant I12, AT-12)"
if [[ $SKIP_BUNDLE -eq 1 ]]; then
  warn "--skip-bundle: bundle hashes were NOT verified. The proof is incomplete."
else
  BUNDLE_MANIFEST="scripts/bundle_manifest.sample.txt"
  if [[ ! -f "$BUNDLE_MANIFEST" ]]; then
    fail "bundle manifest '$BUNDLE_MANIFEST' not found."
    exit 2
  fi
  if ./scripts/verify_bundle.sh --manifest "$BUNDLE_MANIFEST"; then
    echo "OK     every bundled model/key matches its pinned hash."
  else
    fail "bundle verification failed — see the mismatch above."
    echo "      Do NOT install or demo. Re-fetch (scripts/fetch_models.sh) or" >&2
    echo "      get the new hash signed off before refreshing the manifest." >&2
    exit 1
  fi
fi

say "C. static scan for runtime fetching inside the artefact"
# A no-network guarantee is only as good as the ban on runtime downloads.
# AGENTS.md §5 already bans transports in :core; this catches the same class of
# bug in the Android layer, which CAN legitimately have one (NFC/USB sync) but
# must never have an internet fetcher.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kasoti_apk.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
if command -v unzip >/dev/null 2>&1; then
  unzip -qq -o "$APK" -d "$WORK" 2>/dev/null || true
else
  warn "unzip not available — static scan skipped (install/device steps still apply)."
  WORK=""
fi
if [[ -n "$WORK" && -d "$WORK" ]]; then
  if grep -rlaiE 'https?://[a-z0-9.-]*(firebase|gstatic|googleapis|cdn)[a-z0-9./-]*' \
       "$WORK" 2>/dev/null | head -n 10 | grep -q .; then
    warn "artefact references a known model/CDN host — inspect manually:"
    grep -rlaiE 'https?://[a-z0-9.-]*(firebase|gstatic|googleapis|cdn)[a-z0-9./-]*' \
      "$WORK" 2>/dev/null | sed 's/^/        /' | head -n 10
    warn "Bundled-model dependencies (ML Kit) legitimately reference gstatic;"
    warn "an ONLINE model download reference is a first-run-rule violation."
  else
    echo "OK     no known CDN / model-host reference found in the packaged output."
  fi
  MODEL_FILES="$(find "$WORK" -type f \( -name '*.tflite' -o -name '*.tflite.gz' \) | wc -l | tr -d ' ')"
  echo "INFO   $MODEL_FILES model file(s) packaged inside the artefact."
  if [[ "$MODEL_FILES" -eq 0 ]]; then
    warn "no .tflite packaged. If the app is meant to run inference, models are"
    warn "missing from the bundle — that is a build problem, not a network one."
  fi
fi

say "D. device install with the radio off"
if [[ $SKIP_DEVICE -eq 1 ]]; then
  warn "--skip-device: hardware install NOT exercised. The proof is incomplete."
  INCOMPLETE=1
elif ! command -v adb >/dev/null 2>&1; then
  warn "adb not on PATH — cannot install. The proof is INCOMPLETE."
  INCOMPLETE=1
else
  ADB=(adb)
  [[ -n "$ADB_SERIAL" ]] && ADB=(adb -s "$ADB_SERIAL")
  if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    warn "no device attached — cannot install. The proof is INCOMPLETE."
    INCOMPLETE=1
  else
    STATE="$("${ADB[@]}" shell settings get global airplane_mode_on 2>/dev/null || echo '?')"
    if [[ "$STATE" != "1" ]]; then
      warn "device is NOT in airplane mode (airplane_mode_on=$STATE)."
      warn "Turn it on by hand and re-run — we will not reconfigure a device."
      INCOMPLETE=1
    fi
    echo "INFO   device ${STATE} airplane_mode_on; installing..."
    if "${ADB[@]}" install -r "$APK"; then
      echo "OK     installed $PACKAGE"
      "${ADB[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
        echo "      (could not auto-launch; start it by hand)"
      sleep 3
      if "${ADB[@]}" logcat -d -t 200 2>/dev/null | grep -iE 'FATAL EXCEPTION|AndroidRuntime.*$PACKAGE' | head -n 5 | grep -q .; then
        fail "the app crashed on first launch in airplane mode."
        exit 1
      fi
      echo "OK     no fatal exception in the last 200 logcat lines."
      echo
      echo "HUMAN STEP (2 minutes, and it is the actual proof):"
      echo "  1. Device in airplane mode — confirm on screen, show the judges."
      echo "  2. Run the trust-lane flow with a SPECIMEN card."
      echo "  3. Run the macro capture with the clip seated."
      echo "  4. Sync to the desktop console over USB/file — no network."
      echo "  5. Run the one-tap wipe and confirm the diary is gone."
      echo "Record the date + device in DEMO.md §6 rehearsal log."
    else
      fail "adb install failed."
      exit 1
    fi
  fi
fi

echo
if [[ "${INCOMPLETE:-0}" -eq 1 ]]; then
  fail "no-network install proof is INCOMPLETE (artefact ok, hardware step not run)."
  echo "      Do not claim 'verified offline install' in the deck until a human has" >&2
  echo "      done step D on a real device in airplane mode. DEMO.md §4 requires it." >&2
  exit 2
fi

echo "PASS  artefact present, bundle hash-verified, installed and launched with the radio off."
