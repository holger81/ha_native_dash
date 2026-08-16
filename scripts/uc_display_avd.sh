#!/usr/bin/env bash
# Create / launch an AVD that matches the UniFi Connect Display SE 21
# (UC-Display-SE-21): 21.5" 1920x1080 FHD, portrait wall panel, ~102 ppi.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AVD_NAME="${AVD_NAME:-UC_Display_SE_21}"
SYS_IMAGE="system-images;android-34;google_apis;arm64-v8a"
AVD_DIR="${HOME}/.android/avd/${AVD_NAME}.avd"
CONFIG="${AVD_DIR}/config.ini"

# Portrait FHD on a 21.5" 16:9 panel. Physical density is ~102 ppi;
# Android logical density 160 (mdpi) is what these kiosk panels use, so
# 1080px == 1080dp and layouts match the real wall display.
WIDTH=1080
HEIGHT=1920
DENSITY=160

AVDMANAGER="${SDK}/cmdline-tools/latest/bin/avdmanager"
EMULATOR="${SDK}/emulator/emulator"
ADB="${SDK}/platform-tools/adb"

die() { echo "error: $*" >&2; exit 1; }
[[ -x "$AVDMANAGER" ]] || die "avdmanager not found under $SDK"
[[ -x "$EMULATOR" ]] || die "emulator not found under $SDK"

patch_config() {
  python3 - "$CONFIG" "$WIDTH" "$HEIGHT" "$DENSITY" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
width, height, density = sys.argv[2], sys.argv[3], sys.argv[4]
updates = {
    "hw.lcd.width": width,
    "hw.lcd.height": height,
    "hw.lcd.density": density,
    "hw.lcd.vsync": "60",
    "hw.ramSize": "2048",
    "hw.cpu.ncore": "4",
    "hw.battery": "no",
    "hw.gsmModem": "no",
    "hw.gps": "no",
    "hw.keyboard": "yes",
    "hw.mainKeys": "yes",
    "hw.sdCard": "no",
    "hw.initialOrientation": "portrait",
    "hw.device.manufacturer": "Ubiquiti",
    "hw.device.name": "uc_display_se_21",
    "showDeviceFrame": "no",
    "skin.dynamic": "yes",
    "skin.name": f"{width}x{height}",
    "skin.path": f"{width}x{height}",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "auto",
    "disk.dataPartition.size": "8G",
    "sdcard.size": "0",
    "fastboot.forceColdBoot": "yes",
    "fastboot.forceFastBoot": "no",
    "firstboot.bootFromDownloadableSnapshot": "no",
    "firstboot.bootFromLocalSnapshot": "no",
    "firstboot.saveToLocalSnapshot": "no",
}
text = path.read_text() if path.exists() else ""
keys_seen = set()
out = []
for line in text.splitlines():
    if "=" in line and not line.strip().startswith("#"):
        key = line.split("=", 1)[0]
        if key in updates:
            out.append(f"{key}={updates[key]}")
            keys_seen.add(key)
            continue
    out.append(line)
for key, value in updates.items():
    if key not in keys_seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n")
print(f"patched {path}")
PY
}

create_avd() {
  echo "Creating AVD ${AVD_NAME} from ${SYS_IMAGE}..."
  echo no | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYS_IMAGE" \
    --device "medium_tablet" \
    --force
  patch_config
}

wait_for_boot() {
  local i=0
  while [[ "$i" -lt 120 ]]; do
    local boot=""
    boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == "1" ]]; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  die "emulator did not finish booting"
}

apply_runtime() {
  "$ADB" shell wm size "${WIDTH}x${HEIGHT}" >/dev/null
  "$ADB" shell wm density "$DENSITY" >/dev/null
  "$ADB" shell settings put system accelerometer_rotation 0 >/dev/null
  "$ADB" shell settings put system user_rotation 0 >/dev/null
  "$ADB" shell settings put global policy_control 'immersive.full=*' >/dev/null || true
}

install_app() {
  local apk="${1:-}"
  if [[ -z "$apk" ]]; then
    apk="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk"
  fi
  [[ -f "$apk" ]] || die "APK not found: $apk"
  "$ADB" install -r -t "$apk"
  "$ADB" shell monkey -p dev.holgerendt.hanative -c android.intent.category.LAUNCHER 1 >/dev/null
}

cmd="${1:-create}"
case "$cmd" in
  create)
    create_avd
    ;;
  patch)
    [[ -f "$CONFIG" ]] || die "AVD config missing: $CONFIG"
    patch_config
    ;;
  launch)
    [[ -f "$CONFIG" ]] || create_avd
    patch_config
    if "$ADB" devices 2>/dev/null | grep -q emulator-; then
      echo "Stopping existing emulator..."
      "$ADB" emu kill >/dev/null 2>&1 || true
      sleep 2
    fi
    echo "Launching ${AVD_NAME} (${WIDTH}x${HEIGHT} @ ${DENSITY}dpi, portrait)..."
    nohup "$EMULATOR" -avd "$AVD_NAME" \
      -skin "${WIDTH}x${HEIGHT}" \
      -dpi-device "$DENSITY" \
      -gpu auto \
      -no-boot-anim \
      -netdelay none \
      -netspeed full \
      >/tmp/uc-display-emulator.log 2>&1 &
    echo "emulator pid $!  log /tmp/uc-display-emulator.log"
    wait_for_boot
    apply_runtime
    echo "Booted. Display: $("$ADB" shell wm size | tr -d '\r')"
    echo "Density: $("$ADB" shell wm density | tr -d '\r')"
    ;;
  install)
    wait_for_boot
    apply_runtime
    install_app "${2:-}"
    ;;
  *)
    die "usage: $0 create|patch|launch|install [apk]"
    ;;
esac
