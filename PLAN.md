# Improvement plan — ha_native_dash

Goal: harden reliability and security, make the UI fast, make the front camera
visible immediately on door detection, and bring the codebase up to a testable,
maintainable state. The door trigger itself is unchanged (HA automation →
`ha_native_dash` event / `input_boolean.greatroom_wall_camera`).

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

## Phase 1 — Reliability & security

- [ ] **1.1 WS command lifecycle** (`data/HaClient.kt`)
  - `onFailure()` / `auth_invalid`: close socket, drain `pending` with an error.
  - `reconnectLoop()`: disconnect the old socket before opening a new one.
  - `command()`: per-command timeout (~30s) so a dropped socket can't hang callers.
- [ ] **1.2 De-duplicate subscriptions** (`HaClient.kt`) — track subscription IDs;
  `unsubscribe_events` for prior IDs on `auth_ok` before re-subscribing.
- [ ] **1.3 Encrypt credentials at rest, keep restore**
  - `EncryptedSharedPreferences` (already imported, unused).
  - `Documents/HA Native/` recovery JSON: AES-GCM with Keystore-bound key.
  - Copy of management PKCS#12 keystore: encrypt; drop hardcoded password.
  - One-time legacy plaintext → encrypted migration; wipe plaintext.
- [ ] **1.4 Management API hardening** (`data/ManagementServer.kt`)
  - Per-IP lockout (replace single global counter).
  - PIN only in request body, never query string.
  - CORS from `*` to same-origin + panel UI origin.
- [ ] **1.5 Scope network trust** (`res/xml/network_security_config.xml`) —
  keep cleartext + user CAs (needed for `http://` HA and self-signed HTTPS) but
  only for private ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `*.local`, localhost.
- [ ] **1.6 MoreInfo history polling** (`ui/MoreInfo.kt`) — fixed 45s loop;
  entity/lastChanged changes do a debounced one-shot refetch instead of restart.
- [ ] **1.7 dashboard.json regeneration safety** (`scripts/extract_dashboard.py`)
  - Hand-added widgets (`mmwave_targets`, `battery_runtime`) move to
    `local_overrides.json` merged by the extractor (or preserved marker section).
  - Check that regenerated output matches committed asset.

## Phase 2 — Performance + camera immediacy

- [ ] **2.1 Remove `states` collection from `PopupHost`** (`ui/HaApp.kt`) —
  `WeatherPopup` collects only its weather entity.
- [ ] **2.2 Scope timer ticks** — music 250ms tick → current-time row only;
  MoreInfo 1s tick → clock row only.
- [ ] **2.3 WS off the reader thread + batching** (`HaClient.kt`) — single
  collector coroutine for JSON handling; coalesce `state_changed` into one
  `_states.update` per ~60–100ms.
- [ ] **2.4 Camera comes up immediately on door detection**
  - Start `CameraStreams.prefetch()` + `liveCameras.ensureRunning(wallPanelCameras)`
    as soon as credentials exist (go2rtc URLs need no HA token) — before WS auth.
  - Re-ensure camera sessions when the WS reconnects.
  - Wake reliability: if the display entity doesn't flip on within ~3s, retry
    `turn_on` up to 2× (display state already watched).
  - Verify warm resume: players paused-not-released on sleep, `resume()` flips
    playWhenReady, BEHIND_LIVE_WINDOW auto-recovery keeps first frame <1s.
- [ ] **2.5 Camera hygiene** (`LiveCameraHub.kt`, `CameraStreams.kt`)
  - Per-session `DefaultHttpDataSource.Factory` (shared factory is a header race).
  - Refcounted release: no-viewer sessions released after idle timeout;
    wall cameras stay warm.
  - TTL + size cap on stream-URL cache.
- [ ] **2.6 go2rtc base URL configurable** — settings field, default
  `http://192.168.10.31:1984/`; remove hardcoded IP.

## Phase 3 — Architecture & tests

- [ ] **3.1 Split `HaViewModel`** into focused controllers (setup/connection,
  display power+brightness, presence/mmWave, camera, music, calendar, popups)
  behind the same facade.
- [ ] **3.2 Split `HaClient`** into `HaWebSocket`, `HaRest`, `CameraBridge`;
  keep the `HaClient` facade name.
- [ ] **3.3 Externalize site-specific entity IDs** into dashboard data/settings.
- [ ] **3.4 Tests** — unit: `KioskCommands`, `CredentialsStore` (encrypted
  round-trip + migration), `DashboardLoader`, `CameraStreams`,
  presence→brightness, `HaClient` pending drain/timeout; androidTest smoke:
  connect flow.
- [ ] **3.5 Build hygiene** — explicit `bumpVersion` task instead of
  bump-on-assemble; R8 for release; remove unused `WAKE_LOCK` permission.

## Phase 4 — UX polish

- [ ] **4.1** Shared `PopupSheetScaffold` + design tokens in `Theme.kt`.
- [ ] **4.2** `LazyColumn` + `key()` for music search/discovery/queue lists.
- [ ] **4.3** Error states with retry for history/calendar/camera fetches.
- [ ] **4.4** Animations: popup open/close, tab/drawer transitions.
- [ ] **4.5** Accessibility labels on icon-only controls.
- [ ] **4.6** Verify `screenOrientation="sensorPortrait"` on the panel hardware.

## Verification

- `./gradlew test` + `./gradlew assembleRelease` after each phase.
- On-device checklist:
  - Kill network mid-command → no hang; reconnect → no duplicate events.
  - Uninstall + reinstall → credentials restore.
  - 5 bad PINs from one IP don't lock other clients.
  - Doorbell event with screen asleep → camera popup with live stream <1–2s.
  - Other popups no longer recompose on unrelated state changes.
  - Dashboard renders identically (diff `dashboard.json` output).
