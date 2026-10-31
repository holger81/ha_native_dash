#!/usr/bin/env bash
# Deploy a built greatroom APK to the wall panel through the UniFi Connect
# console API: the console auto-installs an APK whose package and signing key
# match the app already running on the display (no "Install unknown apps"
# permission and no tap on the wall needed).
#
# Usage: scripts/push_panel.sh [apk-path]
#        scripts/push_panel.sh --check   # login + print panel version only
#
# Credentials: ~/.secrets/unifi_console.env (UOS_USER / UOS_PASS, chmod 600).
# The console account needs the UniFi Connect admin role.
set -euo pipefail

CONSOLE="${UOS_CONSOLE:-https://192.168.40.1}"
PANEL="${PANEL_DIAG:-https://192.168.40.184:8765}"
SECRETS="${UOS_SECRETS:-/home/opencode/.secrets/unifi_console.env}"
DEFAULT_APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/greatroom/debug/ha-native-greatroom-debug.apk"

ARG="${1:-$DEFAULT_APK}"
[ -f "$SECRETS" ] || { echo "missing secrets file: $SECRETS" >&2; exit 1; }
set -a; . "$SECRETS"; set +a

jar=$(mktemp); trap 'rm -f "$jar"' EXIT
csrf=$(curl -sk -c "$jar" -D - -o /dev/null -X POST "$CONSOLE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(UOS_USER="$UOS_USER" UOS_PASS="$UOS_PASS" python3 -c \
        'import json,os;print(json.dumps({"username":os.environ["UOS_USER"],"password":os.environ["UOS_PASS"],"remember":True}))')" \
  | awk 'tolower($1)=="x-csrf-token:"{print $2}' | tr -d '\r')
[ -n "$csrf" ] || { echo "console login failed" >&2; exit 1; }

if [ "$ARG" = "--check" ]; then
  curl -sk "$PANEL/api/diag"; echo
  exit 0
fi

APK="$ARG"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
size=$(stat -c%s "$APK")
md5=$(md5sum "$APK" | cut -d' ' -f1)
b64() { printf '%s' "$1" | base64 -w0; }

# tus resumable upload; the metadata keys are exactly filename/digest/service
# and every value is base64 (digest = base64 of the 32-char md5 hex digest).
loc=$(curl -sk -b "$jar" -D - -o /dev/null -X POST "$CONSOLE/proxy/connect/api/v2/assets" \
  -H "X-CSRF-Token: $csrf" \
  -H "Upload-Length: $size" \
  -H "Upload-Metadata: filename $(b64 "$(basename "$APK")"),digest $(b64 "$md5"),service $(b64 apk)" \
  | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
[ -n "$loc" ] || { echo "upload session failed" >&2; exit 1; }

echo "uploading $APK ($size bytes)…"
curl -sk -b "$jar" -f -o /dev/null -X PATCH "$CONSOLE/proxy/connect$loc" \
  -H "X-CSRF-Token: $csrf" \
  -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" \
  --data-binary "@$APK"
echo "uploaded; the console installs on the display automatically."
sleep 20
curl -sk "$PANEL/api/diag"; echo
