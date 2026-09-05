# Improvement plan — ha_native_dash

Goal: harden reliability and security, make the UI fast, make the front camera
visible immediately on door detection, and bring the codebase up to a testable,
maintainable state. The door trigger itself is unchanged (HA automation →
`ha_native_dash` event / `input_boolean.greatroom_wall_camera`).

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[−]` won't implement
(wall panel / low ROI; not remaining work)

**Status (2026-09):** Phases 1–2 are effectively done. Phase 3 architecture/tests
and remaining Phase 4 UX polish are marked won't-implement (single-site wall
panel; low ROI / risk). Notable work landed outside the original numbered items:

- Native Music Assistant player (now-playing, Discover/search/browse).
- Vision timeline refresh; backyard cams laid out below the timeline.
- In-app Changelog.
- NetworkGuard: LAN hostname DNS resolution for private-host policy.
- Management PIN sync; credential sealing (see 1.3); WS batching (see 2.3).

## Phase 1 — Reliability & security

- [x] **1.1 WS command lifecycle** (`data/HaClient.kt`)
  - `onFailure()` / `onClosed()` / `auth_invalid`: close socket, drain `pending`
    + forecast subscriptions with an error (gated on socket identity so a stale
    socket's late callback can't clobber the current one).
  - `reconnectLoop()`: removed — dead code; reconnect is driven by
    `HaViewModel.reconnectJob` → `connect()` → `disconnect()` (which drains).
  - `command()`: 30s `withTimeoutOrNull` → `IllegalStateException` on timeout.
- [x] **1.2 De-duplicate subscriptions** (`HaClient.kt`) — fixed at the source:
  `handleMessage()` ignores messages from superseded sockets (`ws !== webSocket`),
  so a zombie socket can no longer re-`auth_ok`/re-subscribe or double-deliver
  kiosk/mmWave events. Per-socket HA subscriptions mean unsubscribing on the new
  socket for old IDs would be a no-op; the identity guard is the real fix.
- [x] **1.3 Encrypt credentials at rest, keep restore**
  - Live prefs: `EncryptedSharedPreferences` (`ha_native_setup_secure`);
    one-time copy from old plaintext `ha_native_setup`, then the plaintext file
    is deleted. Falls back to plaintext prefs if ESP creation fails.
  - Recovery JSON + management PKCS#12 copy in `Documents/HA Native/`:
    AES-256-GCM, blob = `magic(4) || iv(12) || ciphertext` (magics `HNC1`/`HNT1`).
    Key is PBKDF2-SHA256 over `Settings.Secure.ANDROID_ID` — deliberately **not**
    Keystore-bound: Keystore keys are wiped on uninstall, which would break
    restore-after-reinstall. ANDROID_ID is stable across reinstalls of the same
    signed app but differs per app, so other apps on the device can't read the
    files. Factory reset or UniFi re-sign that changes ANDROID_ID breaks recovery
    (unreadable seal is deleted; re-setup required).
  - Keystore now uses a random per-install password (blob = `password(44) || p12`);
    the hardcoded password remains only as a legacy-migration constant for
    existing devices.
  - Legacy plaintext files are read transparently (no magic → parse as-is) and
    re-sealed on the next persist.
- [x] **1.4 Management API hardening** (`data/ManagementServer.kt`)
  - Per-IP lockout (replace single global counter).
  - PIN only in request body, never query string.
  - CORS from `*` to same-origin + panel UI origin.
- [x] **1.5 Scope network trust** (`res/xml/network_security_config.xml`) —
  keep cleartext + user CAs (needed for `http://` HA and self-signed HTTPS) but
  only for private ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `*.local`, localhost.
  - Android XML can't scope to IP ranges, so `NetworkGuard` enforces the private
    policy in code at every egress point: WS connect, setup connect, image
    loader, go2rtc candidates, media video URLs. LAN hostnames are resolved and
    checked against the private ranges (not only literal IPs / `*.local`).
- [x] **1.6 MoreInfo history polling** (`ui/MoreInfo.kt`) — fixed 45s loop;
  entity/lastChanged changes do a debounced one-shot refetch instead of restart.
- [x] **1.7 dashboard.json regeneration safety** (`scripts/extract_dashboard.py`)
  - Hand-added widgets (`mmwave_targets`, `battery_runtime`) move to
    `local_overrides.json` merged by the extractor (or preserved marker section).
  - Check that regenerated output matches committed asset.
  - **Done (2026-08-30):** `scripts/local_overrides.json` holds the `#presence`
    popup, Power battery card/tab, room order, entity order, and extra entities;
    `merge_overrides()` applies them. Regenerated output is byte-identical to
    the committed `dashboard.json`.

## Phase 2 — Performance + camera immediacy

- [x] **2.1 Remove `states` collection from `PopupHost`** (`ui/HaApp.kt`) —
  `WeatherPopup` collects only its weather entity.
  - **Done (2026-08-30):** `HaViewModel.entityFlow()` added; `PopupHost`,
    `WeatherPopup`, `MoreInfoDialog`, `MusicAssistantPopup`, and
    `ScreenTimeoutCard` all collect per-entity flows instead of the whole map.
- [x] **2.2 Scope timer ticks** — music 250ms tick → current-time row only;
  MoreInfo 1s tick → clock row only.
  - **Done (2026-08-30):** `rememberNowTick()` moved into `MoreInfoStateRow`
    and `MoreInfoHistory` individually; music 250ms tick already scoped to
    `liveMediaPosition` inside `NowPlayingPane`.
- [x] **2.3 WS off the reader thread + batching** (`HaClient.kt`) — single
  collector coroutine for JSON handling; coalesce `state_changed` into one
  `_states.update` per ~60–100ms.
  - **Done (2026-08-30):** `onMessage` now just `trySend`s to a buffered
    `Channel`; a single collector coroutine on `Dispatchers.Default` parses
    JSON and dispatches events. `state_changed` entities are buffered in a
    `ConcurrentHashMap` and flushed to `_states` in one `update` every 80ms.
- [x] **2.4 Camera comes up immediately on door detection**
  - Start `CameraStreams.prefetch()` + `liveCameras.ensureRunning(wallPanelCameras)`
    as soon as credentials exist (go2rtc URLs need no HA token) — before WS auth.
  - Re-ensure camera sessions when the WS reconnects.
  - Wake reliability: if the display entity doesn't flip on within ~3s, retry
    `turn_on` up to 2× (display state already watched).
  - Verify warm resume: players paused-not-released on sleep, `resume()` flips
    playWhenReady, BEHIND_LIVE_WINDOW auto-recovery keeps first frame <1s.
  - **Done (2026-08-30):** `prefetchWallCameras()` runs in `init` (gated on
    `credentials.isConfigured`) and re-fires on every WS reconnect via
     `watchWallCamerasOnReconnect()`; `ensureRunning` runs before the base-URL
    check so go2rtc sessions start pre-auth. `playHls` takes a warm-resume
    fast path (same media item, same `localConfiguration` URI, healthy state →
    `playWhenReady = true` only, no re-prepare); a session that hit a fatal
    `onPlayerError` sets `errored` and always re-prepares, since Media3 has no
    error playback state and playWhenReady alone can't recover one.
    `wakeScreen` uses `commandDisplayOnWithRetry` (3 attempts, 3s state-flip
    window each, job cancelled on sleep). Remaining: on-device check that the
    first frame stays <1s on door-triggered resume.
- [x] **2.5 Camera hygiene** (`LiveCameraHub.kt`, `CameraStreams.kt`)
  - Per-session `DefaultHttpDataSource.Factory` (shared factory is a header race).
  - Refcounted release: no-viewer sessions released after idle timeout;
    wall cameras stay warm.
  - TTL + size cap on stream-URL cache.
  - **Done (2026-08-30):** each `Session` owns its
    `DefaultHttpDataSource.Factory` (player built from it; headers set on the
    session's factory at `prepare`), so one stream's auth headers can no longer
    clobber another's. `LiveCameraHub.setWarmTargets()` (fed by
    `HaViewModel.prefetchWallCameras()` on init + every WS reconnect) marks wall
    cameras warm; when the last viewer leaves a non-warm session it gets a
    60s idle timer (`armIdleRelease`); on fire the timer atomically takes the
    map slot (`ConcurrentHashMap.remove(key) !== session`) and `closeSession`
    cancels jobs, releases player/placeholder on main, and resets the view
    flows. Timers are
    cancelled by `view()`/`markAttached`/`ensureRunning` and re-armed by
    `resume()` after sleep so the clock restarts post-wake; warm sessions and
    sessions that never had a viewer are untouched. `resolveCache` entries now
    carry a 5-minute TTL and the map is capped at 64 entries (clear-all on
    overflow).
- [x] **2.6 go2rtc base URL configurable** — Settings field wired to
  `CredentialsStore.go2rtcUrl`; blank keeps dashboard `stream_server` /
  existing camera fallbacks (no mass-edit of dashboard.json).

## Phase 3 — Architecture & tests

- [−] **3.1 Split `HaViewModel`** — won't implement unless forced (defer permanently).
  Was: focused controllers (setup/connection, display power+brightness,
  presence/mmWave, camera, music, calendar, popups) behind the same facade.
- [−] **3.2 Split `HaClient`** — won't implement unless forced (defer permanently).
  Was: `HaWebSocket`, `HaRest`, `CameraBridge`; keep the `HaClient` facade name.
- [−] **3.3 Externalize site-specific entity IDs** — won't implement (single-site
  wall panel).
- [−] **3.4 Full test suite** — won't implement as plan work (optional tiny
  `NetworkGuard` test later is fine). Was: unit `KioskCommands`,
  `CredentialsStore`, `DashboardLoader`, `CameraStreams`, presence→brightness,
  `HaClient` pending drain/timeout; androidTest smoke connect flow.
- [x] **3.5 Build hygiene** — `WAKE_LOCK` and `ACCESS_WIFI_STATE` must stay in
  the manifest: `LiveCameraHub.createPlayer` sets `C.WAKE_MODE_NETWORK`, so
  Media3 acquires a `PowerManager` WakeLock and a `WifiManager` WifiLock from
  `ExoPlayer.prepare()`. Dropping them threw `SecurityException` on the first
  camera stream (fixed after 1.0.125); grep for the permission name, not just
  `WakeLock`, before trimming. Bump-on-assemble stays intentional (UniFi Connect requires a
  strictly higher `versionCode` per upload; see
  `.cursor/rules/unifi-apk-version.mdc`).
  - [−] R8/minify for release — won't implement (risk to Media3/OkHttp).

## Phase 4 — UX polish

- [−] **4.1** Shared `PopupSheetScaffold` + design tokens in `Theme.kt` — won't
  implement (polish-for-its-own-sake).
- [x] **4.2** `LazyColumn` + `key()` for music search/discovery/browse lists.
- [−] **4.3** Error states with retry for history/calendar/camera fetches —
  won't implement unless real flakiness shows up.
- [−] **4.4** Animations: popup open/close, tab/drawer transitions — won't
  implement (wall panel).
- [−] **4.5** Accessibility labels on icon-only controls — won't implement
  (dedicated wall display).
- [x] **4.6** `screenOrientation="sensorPortrait"` already set in the manifest;
  on-panel orientation check still optional if hardware ever misbehaves.

## Phase 5 — Lean, UI perf, and security hardening (proposed 2026-09)

Fresh read of `data/`, `ui/`, the manifest, and `build.gradle.kts` after Phases
1–4 landed. Items are ordered by impact/effort inside each group. `[−]` entries
were considered and deliberately dropped — they are not backlog.

### Lean

- [x] **5.1 Drop `material-icons-extended`** (`app/build.gradle.kts:110`) — the
  app draws every icon from the MDI webfont (`ui/MdiIcon.kt` +
  `assets/mdi_codepoints.json`); there is not one `Icons.*` reference in
  `app/src/main`. With `isMinifyEnabled = false` the whole artifact ships.
  Biggest APK win available. — effort S · impact high
- [x] **5.2 Delete the duplicated MASS telemetry enricher** (`data/HaClient.kt`)
  — `refreshMusicPlayerTelemetry()` (595–612) and
  `enrichMusicPlayersWithMassIds()` (731–748) have byte-identical bodies apart
  from the `force` flag. Collapse to one function with a parameter (~18 lines).
  — effort S · impact low
- [x] **5.3 Remove unused `ACCESS_WIFI_STATE`** (`AndroidManifest.xml:7`) — no
  `WifiManager`/`ConnectivityManager` use anywhere. Keep
  `ACCESS_NETWORK_STATE`: Coil's `NetworkObserver` needs it.
  — effort S · impact low
- [x] **5.4 Remove redundant `usesCleartextTraffic="true"`**
  (`AndroidManifest.xml:27`) — ignored on API 24+ once
  `networkSecurityConfig` is set, and it contradicts the 1.5 intent of scoping
  cleartext deliberately. — effort S · impact low
- [x] **5.5 Dead declarations sweep** — each verified as definition-only (no
  call sites anywhere in `app/src/main`):
  - `data/`: `CredentialsStore.clear()` (186–196),
    `MusicAssistant.matchMassPlayerId()` (248–257, superseded by
    `matchMassPlayerInfo`), `EntityState.supportsFeature()`
    (`data/MusicAssistant.kt:155-171`), `EntityState.attr()`
    (`data/HaClient.kt:64`), and the pass-through `CameraStreams.fromWidget`
    → `CameraTarget.from` (`data/CameraStreams.kt:98`) — keep one.
  - `ui/HaViewModel.kt`: `savedGo2rtcUrl` (482, UI reads `ui.go2rtcUrl`),
    `refreshMusicDiscovery()` (715–717), `clearMusicBrowse()` (811–817),
    `isCalendarSubscribed()` (1776–1779, `ui/HaApp.kt:795-796` inlines the same
    logic), `AutoBrightnessSnapshot.currentBrightness` (1538/1647, written and
    never read), and the empty `"fire-dom-event"` dispatch branch (1964–1966).
  - `ui/`: `Format.stateOf` (`ui/Format.kt:12`) plus its dead import
    (`ui/widgets/Widgets.kt:113`); theme tokens `PopupOverlay`
    (`ui/theme/Theme.kt:24`), `OverlayMoreInfo` (91) and `OverlayPopup`
    (64–74, only referenced by the dead `OverlayMoreInfo`) — MoreInfo uses
    `OverlayLightPopup`.
  - Dead imports: `kotlinx.coroutines.cancel` (`data/HaClient.kt:6`),
    `theme.accentColor` (`ui/HaApp.kt:84` — the function itself is live in
    `Widgets.kt`), `model.VisibilityNode` (`ui/Format.kt:6`),
    `json.jsonArray` (`data/MmWaveLiveTracker.kt:9`).
  - `DashboardLoader.load()` (`data/DashboardLoader.kt:14-17`) is public but
    only called by `loadOrNull` in the same file — make it private.
  — effort S · impact low
- [~] **5.43 Unread model fields and `dashboard.json` keys**
  (`model/Dashboard.kt`, `assets/dashboard.json`) — verified never read by any
  UI code: `DashboardFile.entities` (15, 157 IDs in the asset),
  `DashboardFile.source` (13), `DashboardFile.version` (12),
  `WidgetNode.background` (90, ~56 occurrences in the asset — every
  `Modifier.background` hit is unrelated), `WidgetNode.cameraView` (83),
  `WidgetNode.hoursToShow` (99), `WidgetNode.style` (109),
  `WidgetNode.path` (87), `WidgetNode.battery` (103),
  `WidgetNode.conditions` (128), `SeriesNode.type` (174). The asset also
  carries a top-level `icons` array (70 entries) that isn't in the model at
  all. `WidgetNode.layout` *is* live — but only as `chip.layout`
  (`ui/widgets/Widgets.kt:283`). Either implement the styling these keys imply
  or stop emitting them from `scripts/extract_dashboard.py` — but the asset
  side must move with the extractor to keep 1.7's byte-identical check.
  — effort S (Kotlin) / M (asset + extractor) · impact low
  - Done (Kotlin side): removed `DashboardFile.version`/`source`/`entities`,
    `WidgetNode.background`/`cameraView`/`hoursToShow`/`style`/`path`/`battery`/
    `conditions`, and `SeriesNode.type`. `DashboardLoader` sets
    `ignoreUnknownKeys = true`, so the asset still parses unchanged.
  - Not done (asset + extractor): left `scripts/extract_dashboard.py` and
    `assets/dashboard.json` alone so 1.7's byte-identical regeneration check
    still passes. Cleaning the emitted keys is a separate, coordinated change.
- [x] **5.44 `PopupScaffold` over-parameterization**
  (`ui/widgets/Widgets.kt:2169-2208`) — `titleOverride` (2175) is declared and
  consumed at 2203 but never passed by any of the call sites; `overlay` is
  always the default `OverlayLightPopup`. `scrollContent`, `denseContent`, and
  `subtitleOverride` are genuinely varied (`ui/HaApp.kt:389-391`) — keep those.
  — effort S · impact low
- [ ] **5.45 Consolidate the copy-pasted popup/media shells** — three near-copies
  of "fetch HA bytes → decode → show or fall back" (`TimelineSnapshot`
  `ui/widgets/Widgets.kt:1019-1035`, `EntityPicture` 2028–2043,
  `MediaImageDialog` 1040–1047) and six hand-rolled detail sheets that predate
  `PopupScaffold` (`MediaImageDialog` 1048–1098, `MediaVideoDialog` ~1134–1186,
  `ui/CalendarEventDialogs.kt:155-185`, `ui/PinGateDialog.kt:47-69`,
  `ui/AddCalendarEventDialog.kt:58-78`, `ui/MoreInfo.kt:135-145`). Also the
  duplicated discovery playing-overlay (`ui/MusicAssistantPopup.kt:917-937` and
  976–994) and the ±0.5 °C climate stepper (`ui/MoreInfo.kt:262-272`,
  `ui/widgets/Widgets.kt:1425-1429`). Roughly 150–200 lines.   Folding the media
  loaders into one composable is also the natural home for 5.47.
  — effort M · impact med
  - Skipped: 150–200 lines of pure re-shaping across nine call sites with no
    behaviour change to verify against, in the middle of a change set that
    already touches all of these files. The 5.47 half landed on its own without
    needing the consolidation. Worth doing, but as its own reviewable change.
- [x] **5.5b `CredentialsStore` fields audited — none unused** — every persisted
  property (`baseUrl`, `token`, `go2rtcUrl`, `subscribedCalendars`,
  `screenTimeoutSeconds`, the three `display*Entity` fields,
  `musicPlayerEntity`, `managementPin`/`isGeneratedPin`) and every companion
  helper has live readers. The legacy migration paths
  (`LEGACY_ENCRYPTED_PREFS`, `KEY_TIMEOUT_MINUTES_LEGACY`,
  `screen_timeout_minutes`) are still exercised on upgrade and must stay until
  the panel is known-migrated. Only `clear()` is dead (5.5).
- [x] **5.6 `NetworkGuard.clearCache()` is unreachable**
  (`data/NetworkGuard.kt:46-48`) — either delete it or wire it up, which 5.27
  needs anyway. Resolve together. — effort S · impact low
- [x] **5.7 `proguard-rules.pro` is referenced but absent** — misdiagnosed; no
  change needed. `app/proguard-rules.pro` has been tracked since the initial
  commit (`7da1efe`) and already carries the kotlinx.serialization keeps. The
  `proguardFiles` block at `app/build.gradle.kts:76-79` resolves fine.
- [−] **5.8 Externalize hardcoded site entity IDs** — `wallPanelCameras`
  (`data/CameraStreams.kt:66-82`), `MMWAVE_*_ENTITY`
  (`ui/HaViewModel.kt:2020-2021`), `DEFAULT_DISPLAY_*`
  (`data/CredentialsStore.kt:381-383`) — won't implement; same reasoning as 3.3
  (single-site wall panel).

### UI perf

- [x] **5.9 One shared Coil `ImageLoader`** (`ui/HaImageLoader.kt:64-68`,
  `ui/MusicCover.kt:33`) — `rememberHaImageLoader` uses `remember(client)`,
  which is per-composable-instance, and `MusicCover` is called per cover art
  tile (4 call sites in `MusicAssistantPopup.kt`, incl. list items). Every tile
  therefore builds its own `ImageLoader` with `maxSizePercent(0.18)`, its own
  OkHttp pool, and its own handle on the same disk-cache directory. Hoist to a
  single app-scoped loader (`HaNativeApp` as `ImageLoaderFactory`, or a `lazy`
  singleton). — effort S · impact high
- [x] **5.10 Per-entity flows for the dashboard grid**
  (`ui/widgets/Widgets.kt`) — 2.1 converted the popups, but
  `viewModel.states.collectAsState()` still appears at ~18 sites in
  `Widgets.kt` (184, 268, 330, 355, 375, 876, 1245, 1340, 1365, 1403, 1440,
  1461, 1496, 1533, 1562, 1622, 1803, 1894). Each reads the whole map, so every
  80 ms `_states` flush recomposes every widget on the home screen. This is the
  largest remaining recomposition source. Migrate to
  `HaViewModel.entityFlow()`. — effort M · impact high
- [x] **5.11 Fix `entityFlow(null)` allocation leak**
  (`ui/HaViewModel.kt:157-165`) — the null branch builds a brand-new
  `flowOf(null).stateIn(viewModelScope, Eagerly, null)` on *every* call, each
  starting a coroutine that lives for the ViewModel's lifetime; callers hit it
  from composition (e.g. `PopupHost` at `ui/HaApp.kt:380`). Return a shared
  immutable `StateFlow(null)`. Same function: `entityFlows` is a plain
  `HashMap` with `getOrPut`, unsynchronized. — effort S · impact high
- [x] **5.12 Stop boxing every byte of every camera frame**
  (`data/CameraStreams.kt:257-263`) — `looksLikeHtmlOrJson` does
  `data.dropWhile { … }.take(16)`, and `dropWhile` on a `ByteArray` materializes
  a `List<Byte>` of the *whole* array first. It runs on every polled JPEG
  (`readJpeg`, line 187) at up to 20 fps, so a 200 KB frame boxes ~200 k
  `Byte`s per call. Index the first non-whitespace bytes directly.
  — effort S · impact high
- [x] **5.13 `remember` the popup node** (`ui/HaApp.kt:148`,
  `ui/HaViewModel.kt:390-406`) — `viewModel.popup(hash)` constructs a fresh
  `PopupNode` for `#music`/`#changelog` on every recomposition. `PopupNode`
  holds `List<WidgetNode>`, so strong skipping compares it by identity and
  `PopupHost` (and the whole music popup subtree) can never skip. Wrap in
  `remember(ui.popupHash)`. — effort S · impact med
- [x] **5.14 Narrow the whole-`UiState` reads in the shell**
  (`ui/HaApp.kt:245` `HomeScreen`, `ui/HaApp.kt:228` `DrawerMenu`) — both
  collect all of `UiState`, so opening a more-info dialog or a media preview
  recomposes the room grid, week planner, and timeline behind it. Pass
  `home`/`remoteUrls` in, or collect narrowed flows. — effort M · impact med
- [x] **5.15 Hoist `DateTimeFormatter.ofPattern`** — built inside composable
  bodies and per-item loops at ~14 sites (`ui/widgets/Widgets.kt:568`, `754`,
  `942`, `1200`, `1231`, `1923`; `ui/MoreInfo.kt:367-368`, `555`;
  `ui/WeatherPopup.kt:425`, `509`, `561`). Each call re-parses the pattern.
  Move to top-level `private val`s. — effort S · impact low
- [x] **5.16 `remember` the entity-picker choice lists**
  (`ui/HaApp.kt:555`/`564`/`573` → `ui/HaViewModel.kt:1397-1403`) — three
  `entityChoices()` calls in the settings sheet body each filter *and sort* the
  entire entity map on every recomposition. Bounded to the settings popup, but
  free to fix. — effort S · impact low
- [x] **5.17 `remember` gradient brushes** (`ui/widgets/Widgets.kt:1956`,
  `2058`; `ui/theme/Theme.kt:107`) — `Brush.*Gradient(...)` in a composable body
  allocates a new brush per recomposition and defeats the draw cache.
  Also: the codebase has zero `derivedStateOf` — worth reaching for once 5.10
  lands. — effort S · impact low
- [x] **5.46 The 250 ms music tick is not actually scoped — corrects 2.2**
  (`ui/MusicAssistantPopup.kt:1583-1626`, called at 285–291) —
  `liveMediaPosition` **returns a value**, so the Compose compiler marks it
  non-restartable and it gets no recompose scope of its own. Its internal
  `nowMs` state read therefore invalidates the *caller*, and the `delay(250)`
  loop recomposes all of `NowPlayingPane` — cover art, metadata, volume
  sliders, and the sibling queue column — four times a second while playing.
  2.2 recorded this as already scoped; it isn't. Fix by returning the value as
  a `State<Double?>` read inside the progress row, or by moving the tick into a
  restartable child that wraps only `ProgressRow`. — effort S · impact high
- [~] **5.47 Route HA thumbnails through Coil** (`ui/widgets/Widgets.kt:2027-2043`
  `EntityPicture`, 1019–1036 `TimelineSnapshot`; `ui/MoreInfo.kt:568-583`) —
  all three call `client.authenticatedBytes` and then
  `BitmapFactory.decodeByteArray` at full source resolution to fill a 44–59 dp
  thumbnail, with no memory or disk cache and a re-fetch on every path change.
  The timeline draws up to 5 of them per refresh. Move them onto the shared
  loader from 5.9 with an explicit `.size()`. — effort M · impact med
  - Done: `EntityPicture` and `TimelineSnapshot` now go through the shared loader
    via `SubcomposeAsyncImage`, so they get memory + disk caching and Coil sizes
    the decode to the measured constraints. `TimelineSnapshot` still resolves
    `media-source://` paths over the websocket first and hands Coil the URL.
  - Not done: `MoreInfo.CameraSnapshot` still decodes bytes itself. It is one
    full-width 280 dp image in a dialog, not a thumbnail, and `cameraSnapshot`
    already TTL-caches the bytes after 5.33, so there is little left to win.
- [~] **5.48 Constrain Coil decode size; drop the double crossfade**
  (`ui/MusicCover.kt:44-47`, `ui/HaImageLoader.kt:23-24`) — cover art is
  decoded at full remote resolution into 56–240 dp tiles because no `.size()`
  is set, and `crossfade(true)` is configured on both the loader and every
  request, so Discover shelves animate twice while scrolling.
  — effort S · impact med
  - Done: dropped the loader-level `crossfade(true)`; it is configured per
    `ImageRequest` only, so nothing animates twice.
  - Not needed: the explicit `.size()` half was a misread. `SubcomposeAsyncImage`
    installs Coil's `ConstraintsSizeResolver`, so the decode is already sized to
    the measured tile. Adding `.size()` would only hard-code what layout knows.
- [x] **5.49 `remember` the per-item date work in the planner and forecast**
  (`ui/widgets/Widgets.kt:601-602`; `ui/WeatherPopup.kt:413-440`) — each of the
  planner's 10 day columns re-`filter`s and `sortedWith`s the whole event list
  in its composition body, and `HourlyForecastRow` rebuilds its parsed
  `buildList` (with inline formatters) on every composition. Pre-index events
  by date in the `LaunchedEffect` that loads them, and wrap the forecast list
  in `remember(forecasts)`. — effort S · impact med
- [x] **5.50 Turn on Compose compiler reports** (`app/build.gradle.kts`) — there
  is no `composeCompiler { }` block, so there are no skippability/restartability
  metrics to check any of the above against. Add `metricsDestination` /
  `reportsDestination` before starting 5.10 so the win is measurable rather
  than assumed. — effort S · impact tooling
- [−] **5.51 Split the home screen into a `LazyColumn`** (`ui/HaApp.kt:249-314`)
  — won't implement. The content is a fixed, fully-visible dashboard (~9 room
  cards, planner, timeline), so virtualization buys nothing, and the
  `Modifier.weight(1f)` calls in that `Row` distribute *width* — they are not
  the unbounded-height problem they look like inside a `verticalScroll`. 5.10
  addresses the actual cost, which is recomposition fan-out, not layout.
- [−] **5.52 Guard the camera `AndroidView` update block**
  (`ui/widgets/Widgets.kt` / `ui/widgets/CameraPlayer.kt:184-193`) — won't
  implement. `update` does re-run on unrelated `LiveCameraView` emissions, but
  `PlayerView.setPlayer` already early-outs when the player is unchanged, so
  there is no redundant surface work to remove.
- [−] **5.18 `@Immutable` on `model/Dashboard.kt` nodes** — won't implement.
  Kotlin 2.0.21 means strong skipping is on, so `WidgetNode`/`PopupNode` are
  already skippable via identity comparison, and dashboard instances are stable
  for the process lifetime. Annotating them would switch Compose to deep
  `equals` over 13 nested `List` fields — likely slower, not faster. Fix
  per-recomposition node *construction* instead (5.13).

### Security

- [~] **5.19 Escalating management-PIN lockout**
  (`data/ManagementServer.kt:298-324`) — the lockout resets `count = 0` and
  parks for a flat 30 s, so a LAN client gets 5 guesses per 30 s indefinitely
  with no escalation. Against the 4-digit floor allowed by
  `CredentialsStore.pinError` (`data/CredentialsStore.kt:397-406`) that is
  ~10 k guesses ≈ under a day, and a handful of source IPs divides it further.
  A successful guess yields the live-screenshot feed and the HA URL/token form.
  Add exponential backoff per IP and raise the minimum to 6 digits (`newPin()`
  already generates 6). — effort S · impact med
  - Done: per-IP backoff now doubles per lockout round (30 s → 60 s → … capped
    at 15 min) in `ManagementServer.apiPinError`/`lockoutMs`.
  - Not done: `CredentialsStore.PIN_PATTERN` still accepts 4–8 digits. Raising
    the floor to 6 would reject PINs already set on the panel and lock the owner
    out of their own admin page with no recovery path. Needs a migration
    (force-rotate on next unlock) rather than a one-line regex change.
- [x] **5.20 Don't build JSON with the HTML escaper**
  (`data/ManagementServer.kt:109`, `130`, `162`, `521-525`) — `escape()` only
  handles `& < > "`. It happens to keep `snapshotJson()` parseable because `"`
  becomes `&quot;`, but a popup hash containing a backslash or control
  character emits invalid JSON. Use `org.json` (already a dependency) for the
  `/api/state` and `/api/command` bodies. — effort S · impact low
- [x] **5.21 Regenerate the management cert when the LAN IP changes**
  (`data/ManagementTls.kt:113-137`) — SANs are frozen at first generation from
  `LanAddresses.ipv4()`. After a DHCP change the cert name no longer matches,
  so the browser warning users are told to accept once returns permanently and
  stops being a meaningful signal. Regenerate when the current IP isn't in the
  SAN list. — effort S · impact low
- [x] **5.53 Stop honouring plaintext recovery files**
  (`data/CredentialsStore.kt:271-286`) — when the blob in
  `Documents/HA Native/credentials.json` has no `HNC1` magic,
  `readRecoverableObject` parses it as plaintext JSON and adopts the HA URL,
  the long-lived token, *and* the management PIN, only re-sealing on the next
  `persist()`. 1.3 kept that path for a temporary revert, but it means a
  plaintext file dropped into public Documents is trusted verbatim. Drop the
  fallback (or gate it behind an explicit one-shot migration) now that the seal
  has shipped. — effort S · impact med
  - Took the one-shot-migration option rather than a hard drop: a `recovery_sealed`
    pref flips the first time `persistRecoverable()` writes a seal successfully,
    and after that an unsealed `credentials.json` is ignored. Genuinely legacy
    files still restore once; a plaintext file dropped in later cannot inject a
    URL, token, or PIN.
- [~] **5.54 Stop printing the management PIN on the wall**
  (`ui/HaApp.kt:229-234`, and the setup screen ~1084–1092) — the drawer renders
  `PIN ${ui.remotePin}` at 18 sp permanently. Anyone in the room, or any photo
  of the panel, hands over the admin credential without touching the HTTPS
  surface at all — which makes 5.19's brute-force arithmetic beside the point.
  Show it only in Settings behind the existing local PIN gate, or pair via the
  QR code that `data/Lan.kt` already generates. — effort S · impact med
  - Done: the drawer no longer renders the PIN at all, and Settings masks it
    behind a "Show PIN" reveal toggle.
  - Kept deliberately: the first-run setup screen still prints it. Before HA is
    configured there is no other channel to hand the PIN to the operator, and the
    panel is not yet showing anything worth protecting.
- [x] **5.55 `setGo2rtcUrl` skips the private-host check**
  (`ui/HaViewModel.kt:1293-1307`) — it validates the scheme and that
  `NetworkGuard.hostOf` parses, but never calls `isPrivateHost`, so a public
  go2rtc URL can be saved. No egress actually leaks (the candidate filter at
  `data/CameraStreams.kt:170-171` drops it later), but the validation is
  inconsistent with `connect()` and the failure surfaces as a dead camera
  instead of a clear error. — effort S · impact low
- [x] **5.56 `EncryptedSharedPreferences` fails open to plaintext**
  (`data/CredentialsStore.kt:14-25`) — if `MasterKey`/Keystore setup throws,
  `createSecurePrefs` silently returns plain
  `getSharedPreferences(PREFS_NAME, MODE_PRIVATE)` and the token and PIN are
  stored in cleartext with no user-visible signal. 1.3 documented the fallback
  as intentional; at minimum surface it in `UiState.managementError` so a panel
  in that state is diagnosable. — effort S · impact low
- [x] **5.57 Centralize NetworkGuard in an OkHttp interceptor** — 5.24 confirmed
  there is no unguarded egress *today*, but the guarantee is spread across five
  call sites and two "the caller already checked" assumptions
  (`data/HaClient.kt:864-870`, `1055-1059`, `1481-1487`;
  `data/CameraStreams.kt:174-203`; `data/HaClient.kt:1434-1443` guarded at
  `ui/widgets/Widgets.kt:1109-1111`). One interceptor on the shared clients
  makes the next new request path safe by default. — effort M · impact low
- [x] **5.58 Rate-limit the on-device PIN gate**
  (`ui/PinGateDialog.kt`, `ui/HaViewModel.kt:305-307`) —
  `verifyManagementPin` has no attempt counter or backoff, unlike the HTTP
  path. It only guards calendar management on a panel someone is already
  standing at, so this is small — but it is the same secret as 5.19.
  — effort S · impact low
- [−] **5.59 Drop `<certificates src="user" />`**
  (`res/xml/network_security_config.xml:9-13`) — won't implement. It is what
  makes a self-signed HTTPS Home Assistant work, which 1.5 deliberately kept.
  Removing it only helps against an attacker who can already install a CA on
  the panel, i.e. who already owns the device.
- [−] **5.60 Set `FLAG_SECURE`** (`MainActivity.kt:29-44`) — won't implement.
  Screen content is captured on purpose (`/screenshot`), the capture path is
  `PixelCopy` rather than MediaProjection so `FLAG_SECURE` wouldn't gate it
  anyway, and sideloading a capture app onto a locked UniFi profile is outside
  the model.
- [−] **5.22 Constant-time PIN comparison**
  (`data/ManagementServer.kt:300`, `ui/HaViewModel.kt:305-307`) — won't
  implement. Remote timing resolution over LAN HTTPS is far coarser than a
  6-digit search space; 5.19 is the real control.
- [−] **5.23 Narrow `MANAGE_EXTERNAL_STORAGE`** (`AndroidManifest.xml:10-12`) —
  won't implement. All-Files Access is what makes reading `credentials.json`
  back *after reinstall* work: MediaStore ownership resets with the UID, so
  scoped storage can't re-read a file the previous install created. The blast
  radius is already contained by the AES-GCM seal from 1.3.
- [x] **5.24 NetworkGuard egress coverage re-verified** — every HTTP/WS path
  either guards explicitly (`HaClient.connect:230-237`,
  `authenticatedBytes:1277-1279`, the Coil interceptor
  `ui/HaImageLoader.kt:44-47`, `CameraStreams.resolveUncached:170-171`) or is
  built from the already-guarded `baseUrl` (`restGet`, `history`,
  `forecastViaRest`, `massCommand`, `ensureMassIngress`). No unguarded egress
  found. The real weakness is the cache behind it — see 5.25.
- [x] **5.25 Screenshot path reviewed — no MediaProjection**
  (`data/ScreenCapture.kt`) — capture is `PixelCopy` plus hidden-API
  reflection, bitmaps stay in memory and are recycled
  (`encode:294-303`, `recycle:352-356`), nothing is written to disk, and there
  is no persistent screen-record consent or foreground-service requirement.
  Fragile against future SDK hidden-API tightening, but it degrades to
  `drawFallback` rather than failing. No change needed.
- [x] **5.26 No token/PIN leakage in logs** — zero `Log.*`, `println`, or
  `printStackTrace` anywhere in `app/src/main`; the admin page renders the saved
  URL but never the token (`data/ManagementServer.kt:433-435`). No change
  needed.

### Reliability

- [x] **5.27 NetworkGuard caches DNS failures forever**
  (`data/NetworkGuard.kt:36-48`) — `isPrivateHost` memoizes negatives as well as
  positives with no TTL, and `clearCache()` is never called. If the panel boots
  before the VLAN/DNS is up, `homeassistant.local` resolves to nothing, gets
  pinned as "not private", and every later attempt — including
  `ensureReconnectLoop` — fails the guard instead of the network. The app wedges
  into a permanent `Error` state until it is restarted, which is exactly the
  boot-time case 2.4 tries to survive. Cache only positive results (or TTL the
  negatives) and clear on connectivity change. — effort S · impact high
- [x] **5.28 Get crypto and keygen off the main thread**
  (`ui/HaViewModel.kt:221-257`, `339-378`; `data/ManagementTls.kt:41-61`;
  `data/SecureRecovery.kt:31`, `77-88`; `data/CredentialsStore.kt:206-220`) —
  `HaViewModel.init` runs synchronously on the main thread and does, in order:
  `CredentialsStore` construction (recovery-file read + reseal) and
  `startManagementServer()` → `ManagementTls.sslServerSocketFactory()`, which on
  first launch generates an RSA-2048 keypair and signs a certificate.
  `SecureRecovery.keyFor` re-derives a PBKDF2-SHA256 key at **100 000
  iterations on every call** and caches nothing, so each `persist()` costs two
  derivations (decrypt + encrypt) plus MediaStore I/O. Worse,
  `MainActivity.onResume` (`MainActivity.kt:57`) →
  `retryRestoreIfNeeded` → `reloadFromExternal` repeats that on every resume.
  Cache the derived `SecretKey` (`ANDROID_ID` + fixed salt never change) and
  move server start + sealing to `Dispatchers.IO`. — effort M · impact high
- [x] **5.29 `watchPersonCameras` has no `distinctUntilChanged`**
  (`ui/HaViewModel.kt:1686-1714`) — unlike its sibling watchers it combines the
  raw `states` map and collects unconditionally, so it rebuilds the binding
  list and calls `updateActivePersonCameras` on every 80 ms flush. The
  downstream `!=` guard prevents visible churn but not the work.
  — effort S · impact med
- [x] **5.30 Idle loops that never park**
  (`ui/HaViewModel.kt:1569-1598`, `1476-1497`) —
  `rampAutoDisplayBrightness` spins at `delay(90)` for the process lifetime even
  when `autoBrightnessDesired` is null or the panel is asleep (~11 wakeups/s
  forever), and `watchIdleTimeout` polls at 1 s even when the timeout is
  disabled. Suspend on the upstream flow instead. — effort S · impact med
- [~] **5.31 `runBlocking` on a NanoHTTPD worker thread**
  (`ui/HaViewModel.kt:353-357`) — the `/setup` handler blocks its request thread
  for up to 20 s inside `withTimeout`. NanoHTTPD survives it (thread per
  request), but the endpoint can't be cancelled and the first `pin` parameter of
  `onSubmit` is ignored. Make the callback suspend or hand off and return
  "connecting". — effort S · impact med
  - Done: the unused `pin` parameter is gone from the `onSubmit` signature.
  - Kept deliberately: the `runBlocking`/`withTimeout(20_000)` stays. The admin
    page renders the success or failure of *that* connect attempt, so handing off
    and returning "connecting" would remove the only feedback the setup flow has.
    NanoHTTPD is thread-per-request and the timeout bounds it; documented inline.
- [x] **5.32 Camera session state is not thread-safe**
  (`data/LiveCameraHub.kt:87-99`, `150-160`, `350-370`) — `Session.attached`,
  `warm`, `skipHls`, and `errored` are plain non-volatile `var`s, but
  `markAttached`/`restorePlaceholder` run on the main thread while
  `armIdleRelease`/`runSession` read them from `Dispatchers.Default`. A stale
  read can idle-release a camera that has a viewer, or strand one that doesn't.
  Use `AtomicInteger` for the refcount and `@Volatile` for the flags.
  — effort S · impact med
- [x] **5.33 Bound and refresh `HaClient.snapshotCache`**
  (`data/HaClient.kt:194`, `1218-1233`) — full-resolution camera/image JPEGs are
  kept in a `ConcurrentHashMap<String, ByteArray>` with no size cap, no TTL, and
  no eviction, and `disconnect()` doesn't clear it. Memory grows with the number
  of camera entities ever viewed, and the popup poster is pinned to the very
  first frame fetched. Add an LRU cap plus a TTL like
  `CameraStreams.resolveCache`. — effort S · impact med
- [x] **5.34 Throttle JPEG poster polling**
  (`data/LiveCameraHub.kt:295-308`) — `pollJpeg` loops at `delay(50)`, decoding
  a fresh `Bitmap` per frame with no reuse, purely to have a poster until HLS
  renders its first frame (up to 10 s, plus a fresh burst on every 1.5 s retry).
  200–400 ms is plenty for a still. — effort S · impact med
- [x] **5.35 Persist `subscribedCalendars` to prefs**
  (`data/CredentialsStore.kt:68-73`, `206-220`) — the setter is the only writer
  and `persist()` never puts it in `SharedPreferences`; it only reaches the
  Documents recovery JSON. On a normal restart with storage access missing or
  the recovery file unreadable, calendar subscriptions silently revert to the
  Lovelace defaults. — effort S · impact med
- [x] **5.36 DNS off the caller's thread on the rejection path**
  (`data/NetworkGuard.kt:50-67`, called from `data/HaClient.kt:235` and
  `ui/HaViewModel.kt:417`) — both call sites invoke `hostRejectionReason`
  *outside* their `withContext(Dispatchers.IO)` block, and it does a fresh
  uncached `resolveAddresses()`. On the main thread that's a StrictMode
  violation and a stall right when the network is already misbehaving.
  — effort S · impact med
- [x] **5.37 Cache the name-normalizing regexes**
  (`data/MusicAssistant.kt:418-421`) — `normalizeMusicPlayerName` compiles two
  `Regex` objects per invocation, and `matchMassPlayerInfo` calls it O(players ×
  massPlayers) inside the 1 s music refresh loop. Hoist to top-level `val`s.
  — effort S · impact low
- [x] **5.38 Thin out the music-popup poll loop**
  (`ui/HaViewModel.kt:980-992`, `data/HaClient.kt:555-581`) — while `#music` is
  open, `refreshMusicWall` runs every second *and* a debounced `states` watcher
  fires; each pass does `players/all`, a `get_queue` service call, and
  `player_queues/get`, and `musicAssistantQueue` re-fetches the MASS player list
  the caller just loaded. That's roughly 3–4 ingress round trips per second.
  Pass the already-loaded players through and lengthen the tick.
  — effort M · impact med
- [x] **5.39 `liveCamera()` mutates hub state from composition**
  (`ui/HaViewModel.kt:265-266` → `data/LiveCameraHub.kt:57-61`) —
  `view(target)` cancels the idle-release timer and starts a session as a side
  effect of being *read* during composition, so recomposition drives stream
  lifecycle. `startOne` is idempotent, but `clearIdleRelease` can keep a camera
  alive that nothing is showing. Move the effects into a `LaunchedEffect` /
  `DisposableEffect` keyed on the target. Needs care — the warm-path timing from
  2.4/2.5 must not regress. — effort M · impact med
- [x] **5.40 `HaClient.scope` is never cancelled** (`data/HaClient.kt:183`) —
  the message-collector coroutine and every `scheduleBatchFlush` job outlive
  `disconnect()`; only `messageCollectorStarted` stops a second collector.
  Harmless for a process-lifetime singleton, but `HaViewModel.onCleared` should
  cancel it so the object is actually releasable. — effort S · impact low
- [x] **5.61 Snapshot cameras poll once a second and achieve nothing**
  (`ui/widgets/CameraPlayer.kt:113-137`) — `SnapshotCameraSurface` runs
  `while (true) { cameraSnapshot(entity); delay(1000) }`, but
  `HaClient.cameraSnapshot` returns the cached `ByteArray` for the lifetime of
  the process (5.33). After the first fetch the loop re-assigns the identical
  reference every second forever — no recomposition, no decode, and no
  refreshed image either. So the still is frozen at the first frame ever
  fetched *and* there is a per-camera 1 Hz wakeup for it. Fixing 5.33 makes
  this loop meaningful; until then it is pure overhead.
  — effort S · impact med
- [ ] **5.62 Single source of truth for settings state**
  (`ui/HaViewModel.kt:128-134`, `177-178`, `229-233`) — the display entities,
  timeout, go2rtc URL, and calendar subscriptions each exist twice: in
  `CredentialsStore` and mirrored into `UiState`/`_subscribedCalendars`, with
  every setter writing both. It works, but it is why 5.35's missing
  `SharedPreferences` write went unnoticed. Expose read-only flows from the
  store instead of copying on each change. Do this *after* 5.35.
  — effort M · impact low
  - Skipped for now: 5.35 (its stated prerequisite) landed in this pass, so the
    concrete bug it was meant to prevent is fixed. Inverting the settings data
    flow rewires every setter in `HaViewModel` plus the settings sheet for no
    user-visible gain, and it is the kind of change that wants to be the only
    thing in its diff.
- [−] **5.41 Avoid the full-map copy in `flushStateBatch`**
  (`data/HaClient.kt:411-417`) — won't implement. `current + batch` does copy
  every entity every 80 ms, but it is the idiomatic immutable-snapshot pattern
  and 5.10 removes the consumers that make the copy expensive. Revisit only if
  profiling still shows it after 5.10.
- [−] **5.42 Tests for the above** — won't implement as plan work; same call as
  3.4. A `NetworkGuard` cache test alongside 5.27 would be cheap if someone
  wants one.

## Verification

- `./gradlew test` + `./gradlew assembleRelease` after each phase.
- On-device checklist:
  - Kill network mid-command → no hang; reconnect → no duplicate events.
  - Uninstall + reinstall → credentials restore.
  - 5 bad PINs from one IP don't lock other clients.
  - Doorbell event with screen asleep → camera popup with live stream <1–2s.
  - Other popups no longer recompose on unrelated state changes.
  - Dashboard renders identically (diff `dashboard.json` output).
