# Greatroom Wall — native Android

Native Jetpack Compose tablet app that mirrors the Lovelace **greatroom wall** dashboard. It does **not** use a WebView. Widgets talk to Home Assistant over the REST and WebSocket APIs.

## What it covers

- Home header: menu, person presence, weather
- Status chips (lock, AQI, laundry, vacuum, solar, grid, battery, …)
- Room tiles with live temperature / humidity
- Room popups: lights (slider + toggle), vents, climate, scenes, media
- Weather, power, cars, vacuum (Staubinator), camera, and settings popups
- Real-time state updates via `subscribe_events`
- Home Assistant URL, long-lived token, and optional management PIN stored so they survive uninstall/reinstall on the same device (never committed)

## Build

1. Install Android Studio (or the Android SDK + JDK 17).
2. Open this folder.
3. Create a long-lived token in Home Assistant: **Profile → Long-Lived Access Tokens**.
4. Run on a landscape tablet / wall panel (or an emulator).

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## First launch

The wall panel is awkward for typing a token. Use **remote setup** from your phone on the same Wi‑Fi:

1. The tablet shows a QR code, a URL like `https://192.168.1.50:8765`, and a 4–8 digit PIN.
2. Scan the QR (or open the URL) and paste the Home Assistant URL plus a long-lived token (**Profile → Long-Lived Access Tokens**).
3. The PIN must match the wall panel. After a successful save, a generated PIN rotates; a PIN you set in **Settings** stays.

The management page is **HTTPS only** on port **8765** (no HTTP listener). The panel generates a self-signed certificate. On your phone, accept the certificate warning once. The same cert is kept across app restarts and reinstalls when setup data is restored, so you usually will not have to accept it again.

The management page stays available while the app is running. Open the menu later to see the current URL and PIN if you need to change the token. After you enter the PIN, the same page shows a live screenshot of the wall panel.

On-panel typing is still there as a fallback. URL, token, and a user-set PIN are stored in app SharedPreferences (so Android 10+ **Keep app data** on uninstall works) and also copied to `Documents/HA Native/` so they can be restored even if you do not keep app data. The token is never written to git, logs, or crash reports.

On Android 11+, grant **All files access** after a reinstall if you skipped Keep app data, so the app can read `Documents/HA Native/`. On Android 10 and older, allow storage access when prompted.

Set a lasting PIN from **Settings → Remote setup PIN** (4–8 digits). Until you set one, remote setup uses a generated PIN as before.

The app keeps the screen on and prefers landscape, like the kiosk wall dashboard.

- **Menu** opens Weather / Power / Cars / Vacuum / Camera / Settings, plus the remote-setup PIN
- **Hold menu** toggles `input_boolean.kiosk_mode_greatroom` (same as the Lovelace hold action)
- Tap a room tile to open that room’s controls

Dashboard layout is generated from `~/Projects/ha_dashboards/greatroom-wall.yaml` into `app/src/main/assets/dashboard.json`. Re-run:

```bash
python3 scripts/extract_dashboard.py ~/Projects/ha_dashboards/greatroom-wall.yaml app/src/main/assets/dashboard.json
```
