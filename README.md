# Greatroom Wall — native Android

Native Jetpack Compose tablet app that mirrors the Lovelace **greatroom wall** dashboard. It does **not** use a WebView for the wall UI. Widgets talk to Home Assistant over the REST and WebSocket APIs. The Music popup is a native player for Music Assistant `media_player` entities (transport, volume, up-next via `music_assistant.get_queue`).

## What it covers

- Home header: menu, person presence, weather
- Status chips (lock, AQI, laundry, vacuum, solar, grid, battery, …)
- Room tiles with live temperature / humidity
- Room popups: lights (slider + toggle), vents, climate, scenes, media
- Weather, power, cars, vacuum (Staubinator), camera, settings, and a native Music Assistant wall player
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

## Remote camera / kiosk commands

Home Assistant can open the camera popup on the wall panel the same way Fully Kiosk / WallPanel take a `loadUrl` command. Prefer the **HA event** (no panel IP, no certificate). The REST API is there if you already use `rest_command`.

### Event (recommended)

```yaml
actions:
  - event: ha_native_dash
    event_data:
      command: camera
```

Other payloads: `command: home` closes the popup; `command: navigate` + `path: "#power"` opens any wall popup; `command: more_info` + `entity_id: camera.front_door` opens more-info. Optional `panel: greatroom` is ignored unless it names a different panel.

### Input boolean

Create `input_boolean.greatroom_wall_camera`. Turn it **on** to show the camera popup, **off** to close it (if that popup is still open). Example doorbell automation:

```yaml
alias: Greatroom wall camera on doorbell
triggers:
  - trigger: state
    entity_id: binary_sensor.front_doorbell_visitor
    to: "on"
actions:
  - action: input_boolean.turn_on
    target:
      entity_id: input_boolean.greatroom_wall_camera
  - delay: "00:01:00"
  - action: input_boolean.turn_off
    target:
      entity_id: input_boolean.greatroom_wall_camera
```

### REST (Fully / WallPanel style)

HTTPS on port **8765**, authenticated with the wall **PIN** (`pin` query, JSON field, `X-HA-PIN` header, or `Authorization: Bearer <PIN>`).

```yaml
rest_command:
  greatroom_wall:
    url: "https://WALL_PANEL_IP:8765/api/command"
    method: POST
    verify_ssl: false
    headers:
      Content-Type: application/json
    payload: '{"cmd":"{{ cmd }}","path":"{{ path }}","pin":"YOUR_PIN"}'
```

Then `action: rest_command.greatroom_wall` with `cmd: camera` (or `navigate` and `path: "#camerafront_view"`). `GET /api/state?pin=PIN` returns the current popup.

Dashboard layout is generated from `~/Projects/ha_dashboards/greatroom-wall.yaml` into `app/src/main/assets/dashboard.json`. Re-run:

```bash
python3 scripts/extract_dashboard.py ~/Projects/ha_dashboards/greatroom-wall.yaml app/src/main/assets/dashboard.json
```
