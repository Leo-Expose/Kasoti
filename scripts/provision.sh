#!/usr/bin/env bash
# provision.sh — generate per-deployment sync credentials (SYNC.md §6).
#
# ############################################################################
# #  THIS SCRIPT WRITES A SECRET.                                             #
# #  It generates a 256-bit HMAC deployment secret and a device UUID. The      #
# #  secret is the root of trust for kasoti-sync/1 (SYNC.md §2, threat AT-10): #
# #  anyone holding it can forge sync bundles. It is NEVER printed to stdout,  #
# #  never logged, and never committed.                                       #
# ############################################################################
#
# WHAT IT PRODUCES (per device):
#   <out>/<uuid>/deployment_secret.key   256-bit HMAC secret, base64, mode 0600
#   <out>/<uuid>/device.json             {device, kid, createdUtc, secretSha256}
#   <out>/<uuid>/HELLO.bundle.json       seq-0 handshake stub (SYNC.md §6)
#
# secretSha256 in device.json is a *fingerprint* so two humans can compare
# secrets out of band without either of them reading the secret aloud. It is
# deliberately a salted-free hash of the key material; treat the fingerprint as
# sensitive-adjacent and do not publish it.
#
# WHERE IT WRITES: by default ~/.kasoti/provisioning/<uuid>/ — deliberately
#   OUTSIDE the git working tree. Writing secrets into a repo checkout is how
#   they end up in a commit. If you pass --out-dir inside the repo you must
#   also pass --i-know-this-is-in-the-repo.
#
# IDEMPOTENCE: a plain re-run NEVER overwrites an existing secret. Each run
#   mints a NEW device uuid and a NEW directory, so re-running cannot silently
#   rotate a deployment key and orphan every historical bundle. Use --rotate to
#   change an existing device's secret on purpose; SYNC.md §6 requires that to
#   happen for ALL devices in the same window, keeping the old kid readable for
#   history (it never deletes the previous key without printing where it went).
#
# EXIT CODES: 0 = provisioned · 1 = refused / already provisioned · 2 = usage.
#
# Usage:
#   ./scripts/provision.sh [--label NAME] [--out-dir DIR] [--kid KID]
#                         [--rotate] [--i-know-this-is-in-the-repo] [--help]

set -euo pipefail
umask 077                      # nothing we create is readable by group/other

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_BASE="${KASOTI_PROVISION_DIR:-$HOME/.kasoti/provisioning}"
LABEL="device"
KID="k1"
ROTATE=0
INSIDE_REPO_OK=0

usage() {
  awk '/^#!/ { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --label) LABEL="${2:?--label needs a value}"; shift ;;
    --out-dir) OUT_BASE="${2:?--out-dir needs a path}"; shift ;;
    --kid) KID="${2:?--kid needs a value}"; shift ;;
    --rotate) ROTATE=1 ;;
    --i-know-this-is-in-the-repo) INSIDE_REPO_OK=1 ;;
    *) echo "provision.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

[[ "$LABEL" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || {
  echo "FAIL: --label must match [A-Za-z0-9._-]{1,32} (it becomes a directory name)." >&2; exit 2; }
[[ "$KID" =~ ^[A-Za-z0-9._-]{1,16}$ ]] || {
  echo "FAIL: --kid must match [A-Za-z0-9._-]{1,16} (it goes into the sync envelope)." >&2; exit 2; }

# --- secrets source ---------------------------------------------------------
if command -v openssl >/dev/null 2>&1; then
  SECRET="$(openssl rand -base64 32)"          # 32 bytes = 256 bits
elif [[ -r /dev/urandom ]]; then
  SECRET="$(LC_ALL=C tr -dc 'A-Za-z0-9+/' < /dev/urandom 2>/dev/null | head -c 32 | base64 -w0)"
else
  echo "FAIL: neither openssl nor /dev/urandom is available — refusing to make a weak secret." >&2
  exit 2
fi
[[ ${#SECRET} -ge 43 ]] || { echo "FAIL: generated secret is too short — entropy source is broken." >&2; exit 2; }

# --- device uuid ------------------------------------------------------------
if command -v uuidgen >/dev/null 2>&1; then
  DEVICE_UUID="$(uuidgen | tr 'A-Z' 'a-z')"
elif [[ -r /proc/sys/kernel/random/uuid ]]; then
  DEVICE_UUID="$(tr 'A-Z' 'a-z' < /proc/sys/kernel/random/uuid)"
else
  RAND16="$(LC_ALL=C tr -dc 'a-f0-9' < /dev/urandom 2>/dev/null | head -c 32)"
  DEVICE_UUID="${RAND16:0:8}-${RAND16:8:4}-4${RAND16:13:3}-a${RAND16:17:3}-${RAND16:20:12}"
fi

# --- destination checks -----------------------------------------------------
mkdir -p "$OUT_BASE"
OUT_DIR=""

# --rotate re-provisions the most recently touched device for this label.
if [[ $ROTATE -eq 1 ]]; then
  PRIOR="$(ls -1dt "$OUT_BASE/${LABEL}-"* 2>/dev/null | head -n 1 || true)"
  if [[ -n "$PRIOR" && -d "$PRIOR" ]]; then
    OUT_DIR="$PRIOR"
    DEVICE_UUID="$(basename "$OUT_DIR")"
    DEVICE_UUID="${DEVICE_UUID#"${LABEL}-"}"
    echo "ROTATING existing device $DEVICE_UUID in $OUT_DIR"
  else
    echo "INFO: no existing device for label '$LABEL' — --rotate will provision a new one."
  fi
fi

if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$(cd "$OUT_BASE" && pwd)/${LABEL}-${DEVICE_UUID}"
fi

case "$OUT_DIR" in
  "$REPO_ROOT"/*)
    if [[ $INSIDE_REPO_OK -ne 1 ]]; then
      {
        echo "REFUSED: refusing to write a secret inside the git working tree:"
        echo "        $OUT_DIR"
        echo "        Secrets in a checkout get committed by accident. Use the default"
        echo "        (~/.kasoti/provisioning) or pass --i-know-this-is-in-the-repo"
        echo "        ONLY if .gitignore already covers that path."
      } >&2
      exit 1
    fi
    ;;
esac

if [[ -e "$OUT_DIR/deployment_secret.key" && $ROTATE -eq 0 ]]; then
  {
    echo "REFUSED: $OUT_DIR/deployment_secret.key already exists."
    echo "        Replacing a deployment secret silently orphans every bundle already"
    echo "        signed with it (SYNC.md §6)."
    echo "        Re-run with --rotate to rotate deliberately — and re-provision ALL"
    echo "        devices in the same window, not just this one."
  } >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

# Keep the outgoing key so historical bundles stay verifiable under their old
# kid (SYNC.md §6/§7: retired keys are retained read-only for history).
if [[ -f "$OUT_DIR/deployment_secret.key" ]]; then
  RETIRED="$OUT_DIR/deployment_secret.key.retired-$(date -u +%Y%m%dT%H%M%SZ)"
  mv "$OUT_DIR/deployment_secret.key" "$RETIRED"
  chmod 600 "$RETIRED"
  echo "ROTATE: previous key retained read-only at:"
  echo "        $RETIRED"
  echo "        Bunders signed with it remain verifiable under the old kid. Delete it"
  echo "        only after the retention window closes."
fi

CREATED_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FINGERPRINT="$(printf '%s' "$SECRET" | sha256sum | awk '{print $1}')"

# --- write the secret -------------------------------------------------------
# printf to a temp file in the same dir, then rename: an interrupted run can
# never leave a half-written key behind.
TMP_KEY="$(mktemp "$OUT_DIR/.secret.XXXXXX")"
printf '%s\n' "$SECRET" > "$TMP_KEY"
chmod 600 "$TMP_KEY"
mv "$TMP_KEY" "$OUT_DIR/deployment_secret.key"
chmod 600 "$OUT_DIR/deployment_secret.key"

# Some filesystems (exFAT, some network shares, some Docker volume drivers on
# macOS/Windows) silently IGNORE chmod. On those, a "0600" secret is in fact
# world-readable and the chmod above is a no-op that leaves us believing we are
# safe. Verify, do not assume — a silent permission failure here means anybody
# on the box can forge sync bundles (AT-10).
KEY_MODE="$(stat -c '%a' "$OUT_DIR/deployment_secret.key" 2>/dev/null \
  || stat -f '%Lp' "$OUT_DIR/deployment_secret.key" 2>/dev/null || echo 'unknown')"
case "$KEY_MODE" in
  600|400) : ;;                       # the modes we actually asked for
  *)
    {
      echo
      echo "##################################################################"
      echo "#  SECURITY WARNING — chmod did not take effect on this filesystem"
      echo "##################################################################"
      echo
      echo "  file      : $OUT_DIR/deployment_secret.key"
      echo "  mode now  : $KEY_MODE   (asked for 600)"
      echo
      echo "  This filesystem is not honouring POSIX permissions (exFAT, some"
      echo "  network shares and some container volumes all behave this way)."
      echo "  The deployment secret is currently readable by any user on this"
      echo "  machine. Anyone who reads it can forge sync bundles."
      echo
      echo "  Fix one of:"
      echo "    * move provisioning to a filesystem that supports chmod"
      echo "      (an ext4/APFS/NTFS-with-permissions volume, or ~/.ssh-style"
      echo "      per-user storage), and re-run with --rotate;"
      echo "    * or keep the secret in an OS keystore / password manager and"
      echo "      export it into memory at provisioning time;"
      echo "    * or accept the risk in writing, with the lead's name on it."
      echo
      echo "  This warning is printed on every run until the mode is honoured."
      echo "##################################################################"
      echo
    } >&2
    ;;
esac

# --- write device.json ------------------------------------------------------
cat > "$OUT_DIR/device.json" <<EOF
{
  "device": "$DEVICE_UUID",
  "label": "$LABEL",
  "kid": "$KID",
  "createdUtc": "$CREATED_UTC",
  "secretSha256": "$FINGERPRINT",
  "protocol": "kasoti-sync/1",
  "note": "secretSha256 is a compare-out-of-band fingerprint, not the secret. Transfer the key itself over supervised QR or USB (SYNC.md §5)."
}
EOF
chmod 600 "$OUT_DIR/device.json"

# --- seq-0 HELLO stub -------------------------------------------------------
cat > "$OUT_DIR/HELLO.bundle.json" <<EOF
{
  "v": 1,
  "device": "$DEVICE_UUID",
  "kid": "$KID",
  "seqStart": 0,
  "seqEnd": 0,
  "ts": "$CREATED_UTC",
  "records": [],
  "hmac": "COMPUTE-OFFLINE-THEN-INSTALL-NEVER-COMMIT",
  "_kasoti": "HELLO stub from scripts/provision.sh. Sign it with the deployment secret before installing; an unsigned HELLO must be rejected by the peer (SYNC.md §4 step 1)."
}
EOF
chmod 600 "$OUT_DIR/HELLO.bundle.json"

unset SECRET
TMP_KEY=""

cat <<EOF

PROVISIONED  $LABEL
  device uuid : $DEVICE_UUID
  kid         : $KID
  created     : $CREATED_UTC
  directory   : $OUT_DIR
  fingerprint : sha256:$FINGERPRINT   (compare out of band; NOT the secret)

  The 256-bit HMAC secret was written to deployment_secret.key with mode 0600
  and was NOT printed. Transfer it to the device by supervised QR or USB
  (SYNC.md §5). Never commit it. Never send it over chat.

  Next: exchange HELLO bundles both ways and confirm both merge reports are
  clean before this device is used for a real screening (SYNC.md §6).
EOF
exit 0
