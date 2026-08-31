#!/usr/bin/env bash
# End-to-end: UC Display emulator + HA connect + screenshot proof.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

HA_URL="${HA_URL:-http://192.168.10.32:8123}"
: "${HA_TOKEN:?Set HA_TOKEN in the environment}"

SHOT="/tmp/ha_dash_verify.png"
UI="/tmp/ha_ui.xml"

start_emulator() {
  if adb devices 2>/dev/null | grep -q 'emulator-.*device'; then
    return 0
  fi
  echo "Starting UC_Display_SE_21 (headless)..."
  nohup "$SDK/emulator/emulator" -avd UC_Display_SE_21 \
    -skin 1080x1920 -dpi-device 160 -gpu swiftshader_indirect \
    -no-boot-anim -no-snapshot -no-audio -no-window \
    >>/tmp/uc-display-emulator.log 2>&1 &
  adb wait-for-device
  for _ in $(seq 1 90); do
    boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "$boot" == "1" ]] && break
    sleep 2
  done
  adb shell wm size 1080x1920 >/dev/null
  adb shell wm density 160 >/dev/null
}

echo "Building APK..."
(cd "$ROOT" && ./gradlew :app:assembleDebug -q)

start_emulator

echo "Installing..."
adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb shell appops set dev.holgerendt.hanative MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
adb shell rm -rf "/sdcard/Documents/HA Native" >/dev/null 2>&1 || true
adb shell pm clear dev.holgerendt.hanative >/dev/null
adb logcat -c
adb shell am start -n dev.holgerendt.hanative/.MainActivity >/dev/null
sleep 6

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml /tmp/ha_ui_setup.xml >/dev/null

python3 <<'PY'
import re, subprocess, os, urllib.parse, http.cookiejar, ssl, sys
xml = open("/tmp/ha_ui_setup.xml").read()
texts = [m.group(1) for m in re.finditer(r'text="([^"]*)"', xml) if m.group(1).strip()]
pin = next((t.strip() for t in texts if re.fullmatch(r"\d{4,8}", t.strip())), None)
if not pin:
    m = re.search(r"PIN[^\d]*(\d{4,8})", xml)
    pin = m.group(1) if m else None
if not pin:
    print("UI texts:", texts[:20], file=sys.stderr)
    sys.exit("Could not read setup PIN from emulator")
print("Using setup PIN from emulator UI")
subprocess.run(["adb", "reverse", "tcp:8765", "tcp:8765"], check=True)
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(jar),
    urllib.request.HTTPSHandler(context=ctx),
)
import urllib.request
login = urllib.parse.urlencode({"pin": pin}).encode()
opener.open(urllib.request.Request("https://127.0.0.1:8765/login", data=login, method="POST"), timeout=8).read()
setup = urllib.parse.urlencode({
    "url": os.environ["HA_URL"],
    "token": os.environ["HA_TOKEN"],
}).encode()
resp = opener.open(urllib.request.Request("https://127.0.0.1:8765/setup", data=setup, method="POST"), timeout=30)
print("Management /setup HTTP", resp.status)
PY

sleep 15
adb shell screencap -p /sdcard/ha_dash.png
adb pull /sdcard/ha_dash.png "$SHOT" >/dev/null
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$UI" >/dev/null

python3 <<'PY'
import re, sys
s = open("/tmp/ha_ui.xml").read()
texts = [m.group(1) for m in re.finditer(r'text="([^"]*)"', s) if m.group(1).strip()]
bad = [t for t in texts if any(x in t.lower() for x in (
    "disconnected", "connecting…", "invalid access", "enter token", "http 401",
))]
print("Sample UI:", texts[:25])
if bad:
    print("FAIL status texts:", bad, file=sys.stderr)
    sys.exit(1)
home_markers = ("great", "room", "°", "holger", "bettina", "jonathan", "office", "kitchen")
if not any(any(m in t.lower() for m in home_markers) for t in texts):
    print("WARN: home dashboard markers not obvious", file=sys.stderr)
print("OK: no HA connection error banner in UI dump")
PY

grep VERSION "$ROOT/app/version.properties"
echo "Screenshot: $SHOT ($(wc -c <"$SHOT") bytes)"
