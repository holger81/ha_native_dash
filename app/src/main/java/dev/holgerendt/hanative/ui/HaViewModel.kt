package dev.holgerendt.hanative.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.holgerendt.hanative.HaNativeApp
import dev.holgerendt.hanative.data.CalendarInfo
import dev.holgerendt.hanative.data.CameraStreams
import dev.holgerendt.hanative.data.ConnectionState
import dev.holgerendt.hanative.data.CredentialsStore
import dev.holgerendt.hanative.data.DashboardLoader
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.HaClient
import dev.holgerendt.hanative.data.KioskCommand
import dev.holgerendt.hanative.data.KioskCommands
import dev.holgerendt.hanative.data.KioskSnapshot
import dev.holgerendt.hanative.data.LanAddresses
import dev.holgerendt.hanative.data.LiveCameraHub
import dev.holgerendt.hanative.data.LiveCameraView
import dev.holgerendt.hanative.data.ManagementServer
import dev.holgerendt.hanative.data.ManagementTls
import dev.holgerendt.hanative.data.MusicAssistantPlayer
import dev.holgerendt.hanative.data.MusicAssistantQueue
import dev.holgerendt.hanative.data.MassMediaItem
import dev.holgerendt.hanative.data.MassSearchResults
import dev.holgerendt.hanative.data.MmWaveLiveTargets
import dev.holgerendt.hanative.data.MmWaveLiveTracker
import dev.holgerendt.hanative.data.NetworkGuard
import dev.holgerendt.hanative.data.isShuffleOn
import dev.holgerendt.hanative.data.mediaArtist
import dev.holgerendt.hanative.data.mediaPositionSec
import dev.holgerendt.hanative.data.mediaPositionUpdatedAtMs
import dev.holgerendt.hanative.data.mediaTitle
import dev.holgerendt.hanative.data.repeatMode
import dev.holgerendt.hanative.model.ActionNode
import dev.holgerendt.hanative.model.CalendarSourceNode
import dev.holgerendt.hanative.model.DashboardFile
import dev.holgerendt.hanative.model.PersonCameraBinding
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.WidgetNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

data class WeatherPopupContext(
    val focusDate: LocalDate? = null,
    val entityId: String? = null,
    val initialTab: String? = null,
)

private val DEFAULT_MUSIC_SEARCH_TYPES = setOf("track", "album", "playlist", "artist")

data class MusicBrowseFrame(
    val title: String,
    val path: String,
    val items: List<MassMediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class MusicDiscoveryState(
    val loading: Boolean = false,
    val recentlyPlayed: List<MassMediaItem> = emptyList(),
    val newMusic: List<MassMediaItem> = emptyList(),
    val stationsForYou: List<MassMediaItem> = emptyList(),
    val searchQuery: String = "",
    val searchTypes: Set<String> = DEFAULT_MUSIC_SEARCH_TYPES,
    val searchLoading: Boolean = false,
    val searchResults: MassSearchResults? = null,
    val browseStack: List<MusicBrowseFrame> = emptyList(),
    val error: String? = null,
    val playingUri: String? = null,
)

data class MusicWallState(
    val loading: Boolean = true,
    val players: List<MusicAssistantPlayer> = emptyList(),
    val selectedEntityId: String? = null,
    val queue: MusicAssistantQueue? = null,
    val error: String? = null,
    val tab: String = "now",
    val discovery: MusicDiscoveryState = MusicDiscoveryState(),
)

data class UiState(
    val dashboard: DashboardFile? = null,
    val showSetup: Boolean = true,
    val drawerOpen: Boolean = false,
    val popupHash: String? = null,
    val weatherPopupContext: WeatherPopupContext? = null,
    val moreInfoId: String? = null,
    val mediaPreview: MediaPreview? = null,
    val setupError: String? = null,
    val setupBusy: Boolean = false,
    val remotePin: String = "",
    val pinIsUserSet: Boolean = false,
    val remoteUrls: List<String> = emptyList(),
    val managementError: String? = null,
    val screenTimeoutSeconds: Int = 0,
    val screenAsleep: Boolean = false,
    val displayOffEntity: String = "",
    val displayBrightnessEntity: String = "",
    val displayIlluminanceEntity: String = "",
)

data class MediaPreview(
    val path: String,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val isVideo: Boolean = false,
)

class HaViewModel(
    private val app: Application,
) : ViewModel() {
    private val credentials = CredentialsStore(app)
    val client = HaClient()

    val states: StateFlow<Map<String, EntityState>> = client.states
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val connection: StateFlow<ConnectionState> = client.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    private val entityFlows = HashMap<String, StateFlow<EntityState?>>()
    fun entityFlow(entityId: String?): StateFlow<EntityState?> {
        if (entityId == null) return flowOf(null).stateIn(viewModelScope, SharingStarted.Eagerly, null)
        return entityFlows.getOrPut(entityId) {
            client.states
                .map { it[entityId] }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Lazily, null)
        }
    }

    private val _ui = MutableStateFlow(
        UiState(showSetup = !credentials.isConfigured),
    )
    val ui: StateFlow<UiState> = _ui

    private var reconnectJob: Job? = null
    private var displayWakeJob: Job? = null
    private var managementServer: ManagementServer? = null
    private val random = SecureRandom()
    @Volatile private var currentPin: String = credentials.adoptOrCreatePin { newPin() }

    private val _subscribedCalendars = MutableStateFlow(credentials.subscribedCalendars)
    val subscribedCalendars: StateFlow<List<String>?> = _subscribedCalendars

    private val _mmWaveLive = MutableStateFlow(MmWaveLiveTargets())
    val mmWaveLive: StateFlow<MmWaveLiveTargets> = _mmWaveLive

    private val _availableCalendars = MutableStateFlow(listOf<CalendarInfo>())
    val availableCalendars: StateFlow<List<CalendarInfo>> = _availableCalendars

    private val _calendarEventsRevision = MutableStateFlow(0)
    val calendarEventsRevision: StateFlow<Int> = _calendarEventsRevision

    private val _activePersonCameras = MutableStateFlow<List<WidgetNode>>(emptyList())
    val activePersonCameras: StateFlow<List<WidgetNode>> = _activePersonCameras
    private val _debugPersonCamerasEnabled = MutableStateFlow(false)
    val debugPersonCamerasEnabled: StateFlow<Boolean> = _debugPersonCamerasEnabled
    private var personCameraCooldownJob: Job? = null

    private var lastActivityMs = System.currentTimeMillis()
    private var sleptAtMs = 0L
    private val liveCameras = LiveCameraHub(app, client, viewModelScope)
    /** Lux-mapped brightness the ramp is easing toward; null while auto-brightness is idle. */
    private val autoBrightnessDesired = MutableStateFlow<Int?>(null)
    private var autoBrightnessApplied: Int? = null

    private val extraCalendarColors = listOf(
        "var(--blue)",
        "var(--orange)",
        "var(--green)",
        "var(--purple)",
        "var(--pink)",
        "var(--yellow)",
    )

    init {
        val (dashboard, loadError) = DashboardLoader.loadOrNull(app)
        _ui.value = _ui.value.copy(
            dashboard = dashboard,
            showSetup = !credentials.isConfigured,
            setupError = loadError,
            remotePin = currentPin,
            pinIsUserSet = credentials.managementPin.isNotBlank(),
            screenTimeoutSeconds = credentials.screenTimeoutSeconds,
            displayOffEntity = credentials.displayOffEntity,
            displayBrightnessEntity = credentials.displayBrightnessEntity,
            displayIlluminanceEntity = credentials.displayIlluminanceEntity,
        )
        startManagementServer()
        client.onKioskEvent = { params ->
            if (KioskCommands.panelAllowed(params)) {
                KioskCommands.fromParams(params)?.let { applyKioskCommand(it) }
            }
        }
        client.onMmWaveTargetEvent = { event ->
            _mmWaveLive.value = MmWaveLiveTracker.merge(_mmWaveLive.value, event)
        }
        watchCameraFlag()
        watchPersonCameras()
        watchMmWaveClear()
        watchIdleTimeout()
        watchDisplayPower()
        watchAutoDisplayBrightness()
        watchPresenceScreen()
        watchWallCamerasOnReconnect()
        if (credentials.isConfigured) {
            prefetchWallCameras()
            viewModelScope.launch { connect(credentials.baseUrl, credentials.token) }
        }
    }

    override fun onCleared() {
        liveCameras.release()
        managementServer?.stop()
        super.onCleared()
    }

    fun liveCamera(widget: WidgetNode): StateFlow<LiveCameraView> =
        liveCameras.view(CameraStreams.fromWidget(widget))

    fun attachCameraSurface(widget: WidgetNode) {
        liveCameras.markAttached(CameraStreams.fromWidget(widget))
    }

    fun restoreCameraSurface(widget: WidgetNode) {
        liveCameras.restorePlaceholder(CameraStreams.fromWidget(widget))
    }

    private fun newPin(): String = "%06d".format(random.nextInt(1_000_000))

    fun setManagementPin(pin: String, confirm: String): Result<Unit> {
        if (pin.trim() != confirm.trim()) {
            return Result.failure(IllegalArgumentException("PINs do not match"))
        }
        CredentialsStore.pinError(pin)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val normalized = pin.trim()
        credentials.managementPin = normalized
        currentPin = normalized
        _ui.value = _ui.value.copy(remotePin = currentPin, pinIsUserSet = true)
        return Result.success(Unit)
    }

    fun verifyManagementPin(pin: String): Boolean {
        return credentials.managementPin.isNotBlank() && pin.trim() == credentials.managementPin
    }

    private var calendarManagementUnlockedUntilMs: Long = 0

    fun isCalendarManagementUnlocked(): Boolean {
        return System.currentTimeMillis() < calendarManagementUnlockedUntilMs
    }

    fun unlockCalendarManagement(durationMs: Long = 5 * 60 * 1000) {
        calendarManagementUnlockedUntilMs = System.currentTimeMillis() + durationMs
    }

    fun retryRestoreIfNeeded() {
        if (credentials.isConfigured && credentials.managementPin.isNotBlank()) return
        credentials.reloadFromExternal()
        currentPin = credentials.adoptOrCreatePin { currentPin.ifBlank { newPin() } }
        if (currentPin != _ui.value.remotePin || credentials.managementPin.isNotBlank() != _ui.value.pinIsUserSet) {
            _ui.value = _ui.value.copy(
                remotePin = currentPin,
                pinIsUserSet = credentials.managementPin.isNotBlank(),
            )
        }
        if (credentials.isConfigured && _ui.value.showSetup) {
            viewModelScope.launch { connect(credentials.baseUrl, credentials.token) }
        }
    }

    private fun startManagementServer() {
        refreshLanUrls()
        val capture = (app as HaNativeApp).screenCapture
        val ssl = runCatching { ManagementTls(app).sslServerSocketFactory() }
        if (ssl.isFailure) {
            _ui.value = _ui.value.copy(
                managementError = "Could not enable HTTPS for remote setup",
            )
            return
        }
        val server = ManagementServer(
            pinProvider = { currentPin },
            savedUrlProvider = { credentials.baseUrl },
            screenshotProvider = { capture.captureJpeg() },
            onSubmit = { _, url, token ->
                runBlocking {
                    withTimeout(20_000) { connect(url, token) }
                }
            },
            onCommand = { applyKioskCommand(it) },
            kioskStateProvider = {
                KioskSnapshot(
                    popup = _ui.value.popupHash,
                    connected = client.connection.value is ConnectionState.Connected,
                    screenAsleep = _ui.value.screenAsleep,
                )
            },
            sslSocketFactory = ssl.getOrThrow(),
        )
        val started = runCatching { server.start(5000, false) }
        managementServer = if (started.isSuccess) server else null
        _ui.value = _ui.value.copy(
            managementError = started.exceptionOrNull()?.message,
        )
        viewModelScope.launch {
            while (true) {
                refreshLanUrls()
                delay(15_000)
            }
        }
    }

    private fun refreshLanUrls() {
        val urls = LanAddresses.ipv4().map { "https://$it:${ManagementServer.PORT}" }
        if (urls != _ui.value.remoteUrls) {
            _ui.value = _ui.value.copy(remoteUrls = urls)
        }
    }

    fun entity(id: String?): EntityState? = id?.let { states.value[it] }

    fun popup(hash: String?): PopupNode? {
        if (hash == "#music") {
            return PopupNode(
                name = "Music Assistant",
                icon = "mdi:music-note",
                hash = "#music",
            )
        }
        if (hash == "#changelog") {
            return PopupNode(
                name = "Changelog",
                icon = "mdi:information",
                hash = "#changelog",
            )
        }
        return _ui.value.dashboard?.home?.popups?.firstOrNull { it.hash == hash }
    }

    suspend fun connect(url: String, token: String): Result<Unit> {
        _ui.value = _ui.value.copy(setupBusy = true, setupError = null)
        val trimmedUrl = url.trim().trimEnd('/')
        val trimmedToken = token.trim()
        val host = withContext(Dispatchers.IO) { NetworkGuard.hostOf(trimmedUrl) }
        val privateHost = withContext(Dispatchers.IO) {
            host != null && NetworkGuard.isPrivateHost(host)
        }
        if (!privateHost) {
            val message = "Home Assistant must be on the local network (private IP or LAN name), not '$host'"
            _ui.value = _ui.value.copy(setupBusy = false, setupError = message)
            return Result.failure(IllegalStateException(message))
        }
        val result = withContext(Dispatchers.IO) { client.testRest(trimmedUrl, trimmedToken) }
        // Always keep URL/token and retry — HA may be briefly unreachable at boot (VLAN/DNS).
        credentials.baseUrl = trimmedUrl
        credentials.token = trimmedToken
        if (credentials.managementPin.isBlank()) {
            credentials.managementPin = currentPin
        } else {
            currentPin = credentials.managementPin
        }
        runCatching { client.connect(trimmedUrl, trimmedToken) }
        ensureReconnectLoop()
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Connection failed"
            _ui.value = _ui.value.copy(
                showSetup = false,
                setupBusy = false,
                setupError = message,
                remotePin = currentPin,
                pinIsUserSet = credentials.managementPin.isNotBlank(),
                drawerOpen = false,
            )
            return Result.failure(IllegalStateException(message))
        }
        refreshCalendars()
        _ui.value = _ui.value.copy(
            showSetup = false,
            setupBusy = false,
            setupError = null,
            remotePin = currentPin,
            pinIsUserSet = credentials.managementPin.isNotBlank(),
            drawerOpen = false,
        )
        return Result.success(Unit)
    }

    private fun ensureReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                val state = client.connection.value
                if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                    val url = credentials.baseUrl
                    val token = credentials.token
                    if (url.isBlank() || token.isBlank()) continue
                    runCatching { client.connect(url, token) }
                }
            }
        }
    }

    fun connectFromUi(url: String, token: String) {
        viewModelScope.launch { connect(url, token) }
    }

    val savedUrl: String get() = credentials.baseUrl
    val savedToken: String get() = credentials.token

    private val _musicWall = MutableStateFlow(MusicWallState(selectedEntityId = credentials.musicPlayerEntity.ifBlank { null }))
    val musicWall: StateFlow<MusicWallState> = _musicWall
    private var musicWallJob: Job? = null
    private var musicMediaWatchJob: Job? = null
    private var musicDiscoveryJob: Job? = null
    private var musicSearchJob: Job? = null
    private var musicBrowseJob: Job? = null
    private var musicVolumeDebounceJob: Job? = null
    private var pendingMusicVolume: Pair<Float, String>? = null
    private var pendingMemberVolume: Pair<String, Float>? = null
    private var volumeRequestSeq = 0
    private var musicGroupJob: Job? = null
    /** One automatic retry when Discover only got Recently played. */
    private var musicDiscoveryPartialRetryDone = false

    fun selectMusicPlayer(entityId: String) {
        val normalized = CredentialsStore.normalizeEntityId(entityId)
        if (normalized.isBlank()) return
        credentials.musicPlayerEntity = normalized
        _musicWall.value = _musicWall.value.copy(selectedEntityId = normalized, error = null)
        refreshMusicQueue()
    }

    fun setPlayerGrouped(massPlayerId: String, grouped: Boolean) {
        val wall = _musicWall.value
        val selected = wall.players.firstOrNull { it.entityId == wall.selectedEntityId } ?: return
        val rootId = selected.groupRootId ?: selected.massPlayerId ?: return
        if (massPlayerId == rootId) return
        musicGroupJob?.cancel()
        musicGroupJob = viewModelScope.launch {
            runCatching {
                if (grouped) {
                    client.setMassGroupMembers(targetPlayerId = rootId, addIds = listOf(massPlayerId))
                } else {
                    client.ungroupMassPlayer(massPlayerId)
                }
            }.onFailure { error ->
                if (!isBenignVolumeError(error)) {
                    _musicWall.value = _musicWall.value.copy(
                        error = error.message ?: "Could not update player group",
                    )
                }
            }
            delay(250)
            refreshMusicWall(forcePlayers = true)
        }
    }

    fun setMusicVolume(level: Float, mode: String = "auto") {
        pendingMemberVolume = null
        pendingMusicVolume = level.coerceIn(0f, 1f) to mode
        _musicWall.value = _musicWall.value.copy(error = null)
        scheduleVolumeApply()
    }

    fun setMemberVolume(massPlayerId: String, level: Float) {
        pendingMusicVolume = null
        pendingMemberVolume = massPlayerId to level.coerceIn(0f, 1f)
        _musicWall.value = _musicWall.value.copy(error = null)
        scheduleVolumeApply()
    }

    private fun scheduleVolumeApply() {
        musicVolumeDebounceJob?.cancel()
        musicVolumeDebounceJob = viewModelScope.launch {
            delay(45)
            launchVolumeApply()
        }
    }

    private fun launchVolumeApply() {
        val requestId = ++volumeRequestSeq
        viewModelScope.launch {
            runCatching {
                when (val member = pendingMemberVolume) {
                    null -> {
                        val pending = pendingMusicVolume ?: return@runCatching
                        applyMusicVolumeRequest(pending.first, pending.second)
                    }
                    else -> client.setMassPlayerVolume(member.first, (member.second * 100).toInt())
                }
            }.onFailure { error ->
                if (!isBenignVolumeError(error) && requestId == volumeRequestSeq) {
                    _musicWall.value = _musicWall.value.copy(
                        error = error.message ?: "Volume change failed",
                    )
                }
            }.onSuccess {
                if (requestId == volumeRequestSeq) {
                    _musicWall.value = _musicWall.value.copy(error = null)
                }
            }
        }
    }

    private suspend fun applyMusicVolumeRequest(level: Float, mode: String) {
        val wall = _musicWall.value
        val selected = wall.players.firstOrNull { it.entityId == wall.selectedEntityId }
            ?: error("No player selected")
        when (mode) {
            "group" -> {
                val root = selected.groupRootId ?: selected.massPlayerId
                    ?: error("No Music Assistant group player")
                client.setMassGroupVolume(root, (level * 100).toInt())
            }
            "player" -> {
                val massId = selected.massPlayerId
                if (massId != null) {
                    client.setMassPlayerVolume(massId, (level * 100).toInt())
                } else {
                    client.setMediaVolume(selected.entityId, level)
                }
            }
            else -> {
                val root = selected.groupRootId
                if (selected.isGrouped && root != null) {
                    client.setMassGroupVolume(root, (level * 100).toInt())
                } else if (selected.massPlayerId != null) {
                    client.setMassPlayerVolume(selected.massPlayerId, (level * 100).toInt())
                } else {
                    client.setMediaVolume(selected.entityId, level)
                }
            }
        }
    }

    private fun isBenignVolumeError(error: Throwable): Boolean {
        if (error is CancellationException) return true
        val message = error.message.orEmpty()
        return message.contains("cancel", ignoreCase = true) ||
            message.contains("coroutine", ignoreCase = true) ||
            message.equals("Canceled", ignoreCase = true)
    }

    fun setMusicWallTab(tab: String) {
        val normalized = if (tab == "discover") "discover" else "now"
        _musicWall.value = _musicWall.value.copy(tab = normalized)
        if (normalized == "discover") {
            val discovery = _musicWall.value.discovery
            val emptyShelves = discovery.recentlyPlayed.isEmpty() &&
                discovery.newMusic.isEmpty() &&
                discovery.stationsForYou.isEmpty()
            // Retry when last load failed, nothing loaded, or shelves are only partially filled.
            if (!discovery.loading && (discovery.error != null || emptyShelves || discoveryShelvesIncomplete(discovery))) {
                loadMusicDiscovery()
            }
        }
    }

    fun setMusicSearchQuery(query: String) {
        _musicWall.value = _musicWall.value.copy(
            discovery = _musicWall.value.discovery.copy(searchQuery = query),
        )
        scheduleMusicSearch()
    }

    fun setMusicSearchTypes(types: Set<String>) {
        val normalized = types.map { it.lowercase() }.filter { it in DEFAULT_MUSIC_SEARCH_TYPES }.toSet()
            .ifEmpty { DEFAULT_MUSIC_SEARCH_TYPES }
        _musicWall.value = _musicWall.value.copy(
            discovery = _musicWall.value.discovery.copy(searchTypes = normalized),
        )
        scheduleMusicSearch()
    }

    fun toggleMusicSearchType(type: String) {
        val key = type.lowercase()
        if (key !in DEFAULT_MUSIC_SEARCH_TYPES) return
        val current = _musicWall.value.discovery.searchTypes
        val next = if (key in current) {
            if (current.size <= 1) current else current - key
        } else {
            current + key
        }
        setMusicSearchTypes(next)
    }

    private fun scheduleMusicSearch() {
        musicSearchJob?.cancel()
        val discovery = _musicWall.value.discovery
        val trimmed = discovery.searchQuery.trim()
        if (trimmed.length < 2) {
            val emptyShelves = discovery.recentlyPlayed.isEmpty() &&
                discovery.newMusic.isEmpty() &&
                discovery.stationsForYou.isEmpty()
            val looksPartial = discovery.recentlyPlayed.isNotEmpty() &&
                discovery.newMusic.isEmpty() &&
                discovery.stationsForYou.isEmpty()
            _musicWall.value = _musicWall.value.copy(
                discovery = discovery.copy(
                    searchLoading = false,
                    searchResults = null,
                    // Search failures must not stick on Discover shelves after clear.
                    error = null,
                ),
            )
            if (!discovery.loading && (emptyShelves || looksPartial)) {
                musicDiscoveryPartialRetryDone = false
                loadMusicDiscovery()
            }
            return
        }
        val types = discovery.searchTypes
        musicSearchJob = viewModelScope.launch {
            _musicWall.value = _musicWall.value.copy(
                discovery = _musicWall.value.discovery.copy(searchLoading = true, error = null),
            )
            delay(350)
            try {
                val results = client.musicSearch(trimmed, mediaTypes = types)
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(
                        searchLoading = false,
                        searchResults = results,
                        error = null,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(
                        searchLoading = false,
                        searchResults = null,
                        error = error.message ?: "Search failed",
                    ),
                )
            }
        }
    }

    fun refreshMusicDiscovery() {
        loadMusicDiscovery()
    }

    fun openAppleMusicBrowse() {
        viewModelScope.launch {
            val path = runCatching { client.musicAppleMusicRootPath() }.getOrElse { "apple_music://" }
            openMusicBrowse(path = path, title = "Apple Music", replaceStack = true)
        }
    }

    fun openAppleMusicSeeAll(shelf: String) {
        viewModelScope.launch {
            val root = runCatching { client.musicAppleMusicRootPath() }.getOrElse { "apple_music://" }
            when (shelf) {
                "new_music" -> {
                    val path = runCatching { client.musicAppleMusicChildPath("playlists") }.getOrElse {
                        massBrowseFallback(root, "playlists")
                    }
                    openMusicBrowse(path = path, title = "Playlists", replaceStack = true)
                }
                "stations" -> {
                    val path = runCatching { client.musicAppleMusicChildPath("radio") }.getOrElse {
                        massBrowseFallback(root, "radio")
                    }
                    openMusicBrowse(path = path, title = "Radio", replaceStack = true)
                }
                else -> openMusicBrowse(path = root, title = "Apple Music", replaceStack = true)
            }
        }
    }

    private fun massBrowseFallback(root: String, child: String): String = when {
        root.endsWith("://") -> "$root$child"
        root.endsWith("/") -> "$root$child"
        else -> "$root/$child"
    }

    fun openMusicBrowse(path: String, title: String, replaceStack: Boolean = false) {
        musicBrowseJob?.cancel()
        val frame = MusicBrowseFrame(title = title, path = path, loading = true)
        val stack = if (replaceStack) {
            listOf(frame)
        } else {
            _musicWall.value.discovery.browseStack + frame
        }
        _musicWall.value = _musicWall.value.copy(
            discovery = _musicWall.value.discovery.copy(
                browseStack = stack,
                searchResults = null,
                searchLoading = false,
                error = null,
            ),
        )
        musicBrowseJob = viewModelScope.launch {
            try {
                val items = client.musicBrowse(path)
                val current = _musicWall.value.discovery.browseStack.toMutableList()
                if (current.isEmpty()) return@launch
                val idx = current.indexOfLast { it.path == path }
                if (idx < 0) return@launch
                current[idx] = current[idx].copy(
                    items = items,
                    loading = false,
                    error = if (items.isEmpty()) "Nothing here" else null,
                )
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(browseStack = current),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = _musicWall.value.discovery.browseStack.toMutableList()
                if (current.isEmpty()) return@launch
                val idx = current.indexOfLast { it.path == path }
                if (idx < 0) return@launch
                current[idx] = current[idx].copy(
                    loading = false,
                    error = error.message ?: "Browse failed",
                )
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(browseStack = current),
                )
            }
        }
    }

    fun browseMusicBack() {
        musicBrowseJob?.cancel()
        val stack = _musicWall.value.discovery.browseStack
        if (stack.isEmpty()) return
        _musicWall.value = _musicWall.value.copy(
            discovery = _musicWall.value.discovery.copy(browseStack = stack.dropLast(1)),
        )
    }

    fun clearMusicBrowse() {
        musicBrowseJob?.cancel()
        musicBrowseJob = null
        _musicWall.value = _musicWall.value.copy(
            discovery = _musicWall.value.discovery.copy(browseStack = emptyList()),
        )
    }

    fun onMusicBrowseItem(item: MassMediaItem) {
        when {
            item.canBrowse -> {
                val path = item.browsePath ?: return
                openMusicBrowse(path = path, title = item.name, replaceStack = false)
            }
            item.canPlay -> playMusicDiscoveryItem(item)
            !item.browsePath.isNullOrBlank() -> {
                openMusicBrowse(path = item.browsePath, title = item.name, replaceStack = false)
            }
        }
    }

    fun playMusicDiscoveryItem(item: MassMediaItem) {
        if (!item.canPlay) {
            if (!item.browsePath.isNullOrBlank()) {
                openMusicBrowse(path = item.browsePath, title = item.name, replaceStack = false)
            }
            return
        }
        val wall = _musicWall.value
        val selected = wall.players.firstOrNull { it.entityId == wall.selectedEntityId }
        val queueId = selected?.massPlayerId
        if (queueId.isNullOrBlank()) {
            _musicWall.value = wall.copy(
                discovery = wall.discovery.copy(
                    error = "Select a Music Assistant player to play from Discover.",
                ),
            )
            return
        }
        viewModelScope.launch {
            _musicWall.value = _musicWall.value.copy(
                discovery = _musicWall.value.discovery.copy(playingUri = item.uri, error = null),
                tab = "now",
            )
            runCatching { client.playMassMedia(queueId, item.uri) }
                .onFailure { error ->
                    _musicWall.value = _musicWall.value.copy(
                        discovery = _musicWall.value.discovery.copy(
                            playingUri = null,
                            error = error.message ?: "Could not play ${item.name}",
                        ),
                        tab = "discover",
                    )
                }
                .onSuccess {
                    delay(500)
                    refreshMusicWall(forcePlayers = false)
                    _musicWall.value = _musicWall.value.copy(
                        discovery = _musicWall.value.discovery.copy(playingUri = null),
                    )
                }
        }
    }

    fun mediaPlayPause() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.mediaPlayerCommand(entityId, "media_play_pause") }
            refreshMusicQueueSoon()
        }
    }

    fun mediaNext() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.mediaPlayerCommand(entityId, "media_next_track") }
            refreshMusicQueueSoon()
        }
    }

    fun mediaPrevious() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.mediaPlayerCommand(entityId, "media_previous_track") }
            refreshMusicQueueSoon()
        }
    }

    fun mediaStop() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.mediaPlayerCommand(entityId, "media_stop") }
            refreshMusicQueueSoon()
        }
    }

    fun toggleMusicShuffle() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        val current = client.state(entityId)?.isShuffleOn()
            ?: _musicWall.value.queue?.shuffle
            ?: false
        viewModelScope.launch {
            runCatching { client.setMediaShuffle(entityId, !current) }
            refreshMusicQueueSoon()
        }
    }

    fun cycleMusicRepeat() {
        val entityId = _musicWall.value.selectedEntityId ?: return
        val current = client.state(entityId)?.repeatMode()
            ?: _musicWall.value.queue?.repeatMode?.lowercase()
            ?: "off"
        val next = when (current) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        viewModelScope.launch {
            runCatching { client.setMediaRepeat(entityId, next) }
            refreshMusicQueueSoon()
        }
    }

    fun seekMusic(positionSec: Double) {
        val entityId = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.seekMedia(entityId, positionSec) }
        }
    }

    fun transferMusicToSelected(sourceEntityId: String? = null) {
        val target = _musicWall.value.selectedEntityId ?: return
        viewModelScope.launch {
            runCatching { client.transferMusicQueue(target, sourceEntityId, autoPlay = true) }
                .onFailure { error ->
                    _musicWall.value = _musicWall.value.copy(error = error.message ?: "Transfer failed")
                }
            refreshMusicQueueSoon()
        }
    }

    private fun openMusicWall() {
        musicWallJob?.cancel()
        musicMediaWatchJob?.cancel()
        _musicWall.value = _musicWall.value.copy(loading = true, error = null)
        musicDiscoveryPartialRetryDone = false
        loadMusicDiscovery()
        musicMediaWatchJob = viewModelScope.launch {
            combine(states, _musicWall) { map, wall ->
                wall.players.joinToString("|") { player ->
                    val entity = map[player.entityId]
                    listOf(
                        player.entityId,
                        entity?.state,
                        entity?.mediaTitle(),
                        entity?.mediaArtist(),
                        entity?.mediaPositionSec(),
                        entity?.mediaPositionUpdatedAtMs(),
                    ).joinToString(",")
                }
            }
                .distinctUntilChanged()
                .debounce(150)
                .collect {
                    if (_ui.value.popupHash == "#music") {
                        refreshMusicWall(forcePlayers = false)
                    }
                }
        }
        musicWallJob = viewModelScope.launch {
            var tick = 0
            while (_ui.value.popupHash == "#music") {
                val forcePlayers = tick == 0 || tick % 5 == 0
                refreshMusicWall(forcePlayers = forcePlayers)
                val interval = when (_musicWall.value.tab) {
                    "now" -> 1_000L
                    else -> 3_000L
                }
                delay(interval)
                tick++
            }
        }
    }

    private fun closeMusicWall() {
        musicWallJob?.cancel()
        musicWallJob = null
        musicMediaWatchJob?.cancel()
        musicMediaWatchJob = null
        musicDiscoveryJob?.cancel()
        musicDiscoveryJob = null
        musicSearchJob?.cancel()
        musicSearchJob = null
        musicBrowseJob?.cancel()
        musicBrowseJob = null
        musicVolumeDebounceJob?.cancel()
        musicVolumeDebounceJob = null
        musicGroupJob?.cancel()
        musicGroupJob = null
        musicDiscoveryPartialRetryDone = false
    }

    private fun discoveryShelvesIncomplete(discovery: MusicDiscoveryState): Boolean =
        // Recently played alone often succeeds while New music / stations fail transiently.
        !musicDiscoveryPartialRetryDone &&
            discovery.recentlyPlayed.isNotEmpty() &&
            discovery.newMusic.isEmpty() &&
            discovery.stationsForYou.isEmpty() &&
            discovery.error == null &&
            !discovery.loading

    private fun loadMusicDiscovery() {
        musicDiscoveryJob?.cancel()
        musicDiscoveryJob = viewModelScope.launch {
            _musicWall.value = _musicWall.value.copy(
                discovery = _musicWall.value.discovery.copy(loading = true, error = null),
            )
            try {
                val recently = softMass(emptyList()) { client.musicDiscoveryRecentlyPlayed() }
                val recommendations = softMass(emptyList()) { client.musicDiscoveryRecommendations() }
                val newMusicFromBrowse = softMass(emptyList()) { client.musicDiscoveryNewMusicTracks() }
                val appleRecently = recommendations
                    .firstOrNull {
                        it.name.equals("Recently Played", ignoreCase = true) &&
                            it.provider?.contains("apple_music", ignoreCase = true) == true
                    }
                    ?.items
                    .orEmpty()
                val stations = recommendations
                    .firstOrNull {
                        it.name.equals("Stations for You", ignoreCase = true)
                    }
                    ?.items
                    .orEmpty()
                val newMusicFallback = recommendations
                    .firstOrNull { section ->
                        val name = section.name
                        name.equals("New Music", ignoreCase = true) ||
                            name.contains("New Music", ignoreCase = true)
                    }
                    ?.items
                    .orEmpty()
                val newMusic = newMusicFromBrowse.ifEmpty { newMusicFallback }
                val mergedRecent = (recently + appleRecently)
                    .distinctBy { it.uri }
                if (mergedRecent.isNotEmpty() && newMusic.isEmpty() && stations.isEmpty()) {
                    musicDiscoveryPartialRetryDone = true
                } else if (newMusic.isNotEmpty() || stations.isNotEmpty()) {
                    musicDiscoveryPartialRetryDone = false
                }
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(
                        loading = false,
                        recentlyPlayed = mergedRecent,
                        newMusic = newMusic,
                        stationsForYou = stations,
                        error = when {
                            mergedRecent.isEmpty() && newMusic.isEmpty() && stations.isEmpty() ->
                                "Could not load Apple Music discovery from Music Assistant."
                            else -> null
                        },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _musicWall.value = _musicWall.value.copy(
                    discovery = _musicWall.value.discovery.copy(
                        loading = false,
                        error = error.message ?: "Discovery failed to load",
                    ),
                )
            }
        }
    }

    /** Soft-fail one MASS call without treating cancellation as an empty result. */
    private suspend fun <T> softMass(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallback
        }

    private fun refreshMusicQueue() {
        viewModelScope.launch { refreshMusicWall(forcePlayers = false) }
    }

    private fun refreshMusicQueueSoon() {
        viewModelScope.launch {
            delay(350)
            refreshMusicWall(forcePlayers = false)
        }
    }

    private suspend fun refreshMusicWall(forcePlayers: Boolean) {
        try {
            val current = _musicWall.value
            val players = when {
                forcePlayers || current.players.isEmpty() -> {
                    runCatching { client.musicAssistantPlayers() }
                        .getOrElse { error ->
                            _musicWall.value = current.copy(
                                loading = false,
                                error = error.message ?: "Could not load media players",
                            )
                            return
                        }
                }
                else -> runCatching { client.refreshMusicPlayerTelemetry(current.players) }
                    .getOrElse { current.players }
            }
            val preferred = current.selectedEntityId
                ?: credentials.musicPlayerEntity.takeIf { it.isNotBlank() }
            val selected = when {
                preferred != null && players.any { it.entityId == preferred } -> preferred
                players.isEmpty() -> null
                else -> {
                    players.firstOrNull { client.state(it.entityId)?.state == "playing" }?.entityId
                        ?: players.firstOrNull { client.state(it.entityId)?.state == "paused" }?.entityId
                        ?: players.first().entityId
                }
            }
            if (selected != null && selected != credentials.musicPlayerEntity) {
                credentials.musicPlayerEntity = selected
            }
            val queue = if (selected != null) {
                runCatching { client.musicAssistantQueue(selected, players) }.getOrNull()
            } else {
                null
            }
            _musicWall.value = current.copy(
                loading = false,
                players = players,
                selectedEntityId = selected,
                queue = queue,
                error = when {
                    players.isEmpty() ->
                        "No media players found. Add the Music Assistant integration in Home Assistant for the full wall player."
                    else -> null
                },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _musicWall.value = _musicWall.value.copy(
                loading = false,
                error = error.message ?: "Music player failed to load",
            )
        }
    }

    fun openSetup() {
        _ui.value = _ui.value.copy(showSetup = true, drawerOpen = false)
    }

    fun closeSetup() {
        if (credentials.isConfigured) {
            _ui.value = _ui.value.copy(showSetup = false)
        }
    }

    fun setDrawer(open: Boolean) {
        _ui.value = _ui.value.copy(drawerOpen = open)
    }

    fun openPopup(hash: String?) {
        val previous = _ui.value.popupHash
        _ui.value = _ui.value.copy(
            popupHash = hash,
            drawerOpen = false,
            weatherPopupContext = null,
        )
        when {
            hash == "#music" -> openMusicWall()
            previous == "#music" -> closeMusicWall()
        }
    }

    fun openWeatherPopup(
        focusDate: LocalDate? = null,
        entityId: String? = null,
        initialTab: String? = null,
    ) {
        _ui.value = _ui.value.copy(
            popupHash = "#weather",
            drawerOpen = false,
            weatherPopupContext = WeatherPopupContext(focusDate, entityId, initialTab),
        )
    }

    fun closePopup() {
        closeMusicWall()
        _ui.value = _ui.value.copy(popupHash = null, weatherPopupContext = null)
    }

    fun openMoreInfo(entityId: String?) {
        if (entityId.isNullOrBlank()) return
        _ui.value = _ui.value.copy(moreInfoId = entityId)
    }

    fun closeMoreInfo() {
        _ui.value = _ui.value.copy(moreInfoId = null)
    }

    fun openMedia(
        path: String?,
        title: String? = null,
        subtitle: String? = null,
        description: String? = null,
        isVideo: Boolean = false,
    ) {
        if (path.isNullOrBlank()) return
        _ui.value = _ui.value.copy(
            mediaPreview = MediaPreview(path, title, subtitle, description, isVideo),
        )
    }

    fun openVideo(
        path: String?,
        title: String? = null,
        subtitle: String? = null,
        description: String? = null,
    ) = openMedia(path, title, subtitle, description, isVideo = true)

    fun closeMedia() {
        _ui.value = _ui.value.copy(mediaPreview = null)
    }

    fun applyKioskCommand(command: KioskCommand) {
        when (command) {
            is KioskCommand.Home -> {
                wakeScreen()
                closePopup()
                closeMoreInfo()
                closeMedia()
            }
            is KioskCommand.Navigate -> {
                wakeScreen()
                closeMoreInfo()
                closeMedia()
                openPopup(command.hash)
            }
            is KioskCommand.MoreInfo -> {
                wakeScreen()
                closePopup()
                openMoreInfo(command.entityId)
            }
            is KioskCommand.Sleep -> sleepScreen()
            is KioskCommand.Wake -> wakeScreen()
        }
    }

    fun noteUserActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun onHostResumed() {
        if (_ui.value.showSetup) return
        if (!_ui.value.screenAsleep) {
            noteUserActivity()
            return
        }
        // Turning the UniFi panel off can pause/resume us; ignore that bounce.
        if (System.currentTimeMillis() - sleptAtMs < 1_500L) return
        wakeScreen()
    }

    fun setScreenTimeoutSeconds(seconds: Int): Result<Unit> {
        if (seconds !in 0..CredentialsStore.MAX_SCREEN_TIMEOUT_SECONDS) {
            return Result.failure(
                IllegalArgumentException("Enter 0 to ${CredentialsStore.MAX_SCREEN_TIMEOUT_SECONDS} seconds"),
            )
        }
        credentials.screenTimeoutSeconds = seconds
        lastActivityMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(screenTimeoutSeconds = seconds)
        return Result.success(Unit)
    }

    fun sleepScreen(commandDisplay: Boolean = true) {
        if (_ui.value.screenAsleep || _ui.value.showSetup) return
        sleptAtMs = System.currentTimeMillis()
        displayWakeJob?.cancel()
        if (commandDisplay && connection.value is ConnectionState.Connected) {
            _ui.value.displayOffEntity.takeIf { it.isNotBlank() }?.let { entityId ->
                viewModelScope.launch { runCatching { client.setEntityPower(entityId, on = false) } }
            }
        }
        _ui.value = _ui.value.copy(screenAsleep = true, drawerOpen = false)
        liveCameras.pause()
    }

    fun wakeScreen(commandDisplay: Boolean = true) {
        lastActivityMs = System.currentTimeMillis()
        if (commandDisplay && connection.value is ConnectionState.Connected) {
            _ui.value.displayOffEntity.takeIf { it.isNotBlank() }?.let { entityId ->
                commandDisplayOnWithRetry(entityId)
            }
        }
        if (_ui.value.screenAsleep) {
            _ui.value = _ui.value.copy(screenAsleep = false)
            liveCameras.resume()
        }
    }

    private fun commandDisplayOnWithRetry(entityId: String) {
        displayWakeJob?.cancel()
        displayWakeJob = viewModelScope.launch {
            repeat(3) { attempt ->
                if (states.value[entityId]?.state == "on") return@launch
                runCatching { client.setEntityPower(entityId, on = true) }
                if (attempt == 2) return@launch
                val flipped = withTimeoutOrNull(3_000) {
                    states.filter { all -> all[entityId]?.state == "on" }.first()
                } != null
                if (flipped) return@launch
            }
        }
    }

    fun setDisplayOffEntity(entityId: String): Result<Unit> {
        CredentialsStore.entityIdError(entityId)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val normalized = CredentialsStore.normalizeEntityId(entityId)
        credentials.displayOffEntity = normalized
        _ui.value = _ui.value.copy(displayOffEntity = normalized)
        return Result.success(Unit)
    }

    fun setDisplayBrightnessEntity(entityId: String): Result<Unit> {
        CredentialsStore.entityIdError(entityId)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val normalized = CredentialsStore.normalizeEntityId(entityId)
        credentials.displayBrightnessEntity = normalized
        _ui.value = _ui.value.copy(displayBrightnessEntity = normalized)
        return Result.success(Unit)
    }

    fun setDisplayBrightness(value: Float) {
        val entityId = _ui.value.displayBrightnessEntity.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            runCatching { client.setNumericEntityValue(entityId, value.toDouble()) }
        }
    }

    fun displayOffEntityChoices(): List<Pair<String, String>> =
        entityChoices(setOf("switch", "input_boolean", "script", "button", "light"))

    fun displayBrightnessEntityChoices(): List<Pair<String, String>> =
        entityChoices(setOf("number", "light"))

    fun setDisplayIlluminanceEntity(entityId: String): Result<Unit> {
        CredentialsStore.entityIdError(entityId)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val normalized = CredentialsStore.normalizeEntityId(entityId)
        credentials.displayIlluminanceEntity = normalized
        _ui.value = _ui.value.copy(displayIlluminanceEntity = normalized)
        return Result.success(Unit)
    }

    fun displayIlluminanceEntityChoices(): List<Pair<String, String>> =
        entityChoices(setOf("sensor"))

    private fun entityChoices(domains: Set<String>): List<Pair<String, String>> =
        states.value.entries
            .asSequence()
            .filter { it.key.substringBefore('.') in domains }
            .map { it.key to it.value.friendlyName }
            .sortedBy { it.second.lowercase() }
            .toList()

    private fun wallCameraWidgets(): List<WidgetNode> =
        _ui.value.dashboard?.home?.popups
            ?.firstOrNull { it.hash == KioskCommands.CAMERA_POPUP }
            ?.let { popup -> CameraStreams.camerasForPopup(popup) }
            ?: CameraStreams.wallPanelCameras

    private fun prefetchWallCameras() {
        viewModelScope.launch {
            val widgets = wallCameraWidgets()
            liveCameras.ensureRunning(widgets.map { CameraStreams.fromWidget(it) })
            if (client.currentBaseUrl.isBlank()) return@launch
            runCatching { client.prefetchCameraSnapshots(widgets.mapNotNull { it.entity }) }
            runCatching { CameraStreams.prefetch(client, widgets.map { CameraStreams.fromWidget(it) }) }
        }
    }

    private fun watchWallCamerasOnReconnect() {
        viewModelScope.launch {
            var wasConnected = connection.value is ConnectionState.Connected
            connection.collect { state ->
                val connected = state is ConnectionState.Connected
                if (connected && !wasConnected) prefetchWallCameras()
                wasConnected = connected
            }
        }
    }

    private fun watchIdleTimeout() {
        viewModelScope.launch {
            while (true) {
                val seconds = _ui.value.screenTimeoutSeconds
                if (seconds <= 0 || _ui.value.screenAsleep || _ui.value.showSetup) {
                    delay(1_000)
                    continue
                }
                if (peoplePresent(states.value, _mmWaveLive.value)) {
                    lastActivityMs = System.currentTimeMillis()
                    delay(1_000)
                    continue
                }
                val wait = lastActivityMs + seconds * 1_000L - System.currentTimeMillis()
                if (wait <= 0) {
                    sleepScreen()
                } else {
                    delay(wait.coerceAtMost(15_000L))
                }
            }
        }
    }

    private fun watchDisplayPower() {
        viewModelScope.launch {
            var previousEntity = ""
            var previousState: String? = null
            combine(
                _ui.map { it.displayOffEntity }.distinctUntilChanged(),
                states,
            ) { entityId, all ->
                entityId to entityId.takeIf { it.isNotBlank() }?.let { all[it]?.state }
            }.distinctUntilChanged().collect { (entityId, state) ->
                if (entityId != previousEntity) {
                    previousEntity = entityId
                    previousState = state
                    return@collect
                }
                val last = previousState
                previousState = state
                if (state.isNullOrBlank() || last.isNullOrBlank() || state == last) return@collect
                when {
                    state == "on" && last != "on" -> wakeScreen(commandDisplay = false)
                    state == "off" && last == "on" -> sleepScreen(commandDisplay = false)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun watchAutoDisplayBrightness() {
        viewModelScope.launch {
            combine(
                _ui.map { Triple(it.displayIlluminanceEntity, it.displayBrightnessEntity, it.screenAsleep) }
                    .distinctUntilChanged(),
                states,
            ) { (illumEntity, brightEntity, asleep), allStates ->
                AutoBrightnessSnapshot(
                    illuminanceEntity = illumEntity,
                    brightnessEntity = brightEntity,
                    asleep = asleep,
                    luxState = illumEntity.takeIf { it.isNotBlank() }?.let { allStates[it]?.state },
                    currentBrightness = brightEntity.takeIf { it.isNotBlank() }
                        ?.let { allStates[it]?.state?.toFloatOrNull() },
                )
            }
                .distinctUntilChanged { a, b ->
                    a.asleep == b.asleep &&
                        a.illuminanceEntity == b.illuminanceEntity &&
                        a.brightnessEntity == b.brightnessEntity &&
                        a.luxState == b.luxState
                }
                .debounce(400)
                .collect { snap ->
                    if (snap.asleep ||
                        snap.illuminanceEntity.isBlank() ||
                        snap.brightnessEntity.isBlank()
                    ) {
                        autoBrightnessDesired.value = null
                        autoBrightnessApplied = null
                        return@collect
                    }
                    val lux = snap.luxState?.toDoubleOrNull()
                    if (lux == null || lux <= 0) {
                        autoBrightnessDesired.value = null
                        return@collect
                    }
                    autoBrightnessDesired.value = luxToDisplayBrightness(lux)
                }
        }
        viewModelScope.launch { rampAutoDisplayBrightness() }
    }

    private suspend fun rampAutoDisplayBrightness() {
        while (true) {
            val desired = autoBrightnessDesired.value
            if (desired == null || _ui.value.screenAsleep) {
                delay(AUTO_BRIGHTNESS_RAMP_MS)
                continue
            }
            val entityId = _ui.value.displayBrightnessEntity.takeIf { it.isNotBlank() }
            if (entityId == null) {
                delay(AUTO_BRIGHTNESS_RAMP_MS)
                continue
            }
            val live = states.value[entityId]?.state?.toFloatOrNull()?.roundToInt()
            val current = autoBrightnessApplied ?: live ?: desired
            val delta = desired - current
            if (abs(delta) < 2) {
                if (live == null || abs(desired - live) >= 2) {
                    setDisplayBrightness(desired.toFloat())
                    autoBrightnessApplied = desired
                }
                delay(AUTO_BRIGHTNESS_RAMP_MS)
                continue
            }
            val step = brightnessRampStep(abs(delta)).coerceAtMost(abs(delta))
            val next = (current + if (delta > 0) step else -step).coerceIn(30, 255)
            setDisplayBrightness(next.toFloat())
            autoBrightnessApplied = next
            delay(AUTO_BRIGHTNESS_RAMP_MS)
        }
    }

    private fun brightnessRampStep(distance: Int): Int = when {
        distance > 60 -> 10
        distance > 25 -> 6
        distance > 10 -> 3
        else -> 2
    }

    private fun luxToDisplayBrightness(lux: Double): Int {
        val clampedLux = lux.coerceIn(1.0, 400.0)
        val scaled = 30 + (clampedLux - 1) * (255 - 30) / (400 - 1)
        return scaled.roundToInt().coerceIn(30, 255)
    }

    private fun watchPresenceScreen() {
        viewModelScope.launch {
            combine(
                _ui.map { it.showSetup }.distinctUntilChanged(),
                states,
                mmWaveLive,
            ) { showSetup, allStates, live ->
                !showSetup && peoplePresent(allStates, live)
            }
                .distinctUntilChanged()
                .collect { present ->
                    if (!present) return@collect
                    lastActivityMs = System.currentTimeMillis()
                    if (_ui.value.screenAsleep) {
                        wakeScreen()
                    }
                }
        }
    }

    private fun peoplePresent(
        allStates: Map<String, EntityState>,
        live: MmWaveLiveTargets,
    ): Boolean {
        if (isOn(allStates[MMWAVE_OCCUPANCY_ENTITY]?.state)) return true
        if (live.count > 0 || live.slots.isNotEmpty()) return true
        val helperCount = allStates[MMWAVE_TARGET_COUNT_ENTITY]?.state?.toDoubleOrNull()?.roundToInt() ?: 0
        return helperCount > 0
    }

    private data class AutoBrightnessSnapshot(
        val illuminanceEntity: String,
        val brightnessEntity: String,
        val asleep: Boolean,
        val luxState: String?,
        val currentBrightness: Float?,
    )

    private fun watchMmWaveClear() {
        viewModelScope.launch {
            states.map { it[MMWAVE_OCCUPANCY_ENTITY]?.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (!isOn(state)) {
                        _mmWaveLive.value = MmWaveLiveTargets()
                    }
                }
        }
    }

    private fun watchCameraFlag() {
        viewModelScope.launch {
            var previous: String? = null
            states.map { it[KioskCommands.CAMERA_FLAG]?.state }.distinctUntilChanged().collect { state ->
                val last = previous
                previous = state
                if (state == "on" && last != "on") {
                    applyKioskCommand(KioskCommand.Navigate(KioskCommands.CAMERA_POPUP))
                } else if (state == "off" && last == "on" && _ui.value.popupHash == KioskCommands.CAMERA_POPUP) {
                    closePopup()
                }
            }
        }
    }

    fun setDebugPersonCamerasEnabled(enabled: Boolean) {
        _debugPersonCamerasEnabled.value = enabled
        if (!enabled) {
            clearActivePersonCamerasImmediate()
        }
    }

    private fun watchPersonCameras() {
        viewModelScope.launch {
            combine(
                states,
                _ui.map { it.dashboard?.home?.personCameras }.distinctUntilChanged(),
                _debugPersonCamerasEnabled,
            ) { allStates, config, debugEnabled ->
                Triple(config, allStates, debugEnabled)
            }.collect { (config, allStates, debugEnabled) ->
                if (config == null || config.bindings.isEmpty()) {
                    personCameraCooldownJob?.cancel()
                    personCameraCooldownJob = null
                    _activePersonCameras.value = emptyList()
                    return@collect
                }
                val active = if (debugEnabled) {
                    config.bindings.map { it.toCameraWidget() }
                } else {
                    config.bindings
                        .filter { binding -> personSensorActive(binding, allStates) }
                        .map { it.toCameraWidget() }
                }
                updateActivePersonCameras(
                    active,
                    if (debugEnabled) 0 else config.cooldownSeconds,
                    ignoreCooldown = debugEnabled,
                )
            }
        }
    }

    private fun personSensorActive(binding: PersonCameraBinding, allStates: Map<String, EntityState>): Boolean {
        val state = allStates[binding.sensor]?.state ?: return false
        if (state == "on") return true
        return state.toIntOrNull()?.let { it > 0 } == true
    }

    private fun clearActivePersonCamerasImmediate() {
        personCameraCooldownJob?.cancel()
        personCameraCooldownJob = null
        val previous = _activePersonCameras.value
        if (previous.isEmpty()) return
        _activePersonCameras.value = emptyList()
        viewModelScope.launch {
            liveCameras.stopTargets(previous.map { CameraStreams.fromWidget(it) })
        }
    }

    private fun updateActivePersonCameras(
        cameras: List<WidgetNode>,
        cooldownSeconds: Int,
        ignoreCooldown: Boolean = false,
    ) {
        if (cameras.isNotEmpty()) {
            personCameraCooldownJob?.cancel()
            personCameraCooldownJob = null
            val wasEmpty = _activePersonCameras.value.isEmpty()
            if (cameras != _activePersonCameras.value) {
                _activePersonCameras.value = cameras
                viewModelScope.launch {
                    liveCameras.ensureRunning(cameras.map { CameraStreams.fromWidget(it) })
                }
            }
            if (wasEmpty) wakeScreen()
            lastActivityMs = System.currentTimeMillis()
            return
        }
        if (ignoreCooldown) {
            clearActivePersonCamerasImmediate()
            return
        }
        if (_activePersonCameras.value.isEmpty()) return
        if (personCameraCooldownJob?.isActive == true) return
        personCameraCooldownJob = viewModelScope.launch {
            delay(cooldownSeconds.coerceAtLeast(0) * 1_000L)
            clearActivePersonCamerasImmediate()
        }
    }

    fun plannerCalendars(defaults: List<CalendarSourceNode>): List<CalendarSourceNode> {
        val selected = _subscribedCalendars.value ?: defaults.mapNotNull { it.entity }
        val byEntity = defaults.associateBy { it.entity }
        return selected.mapIndexed { index, entityId ->
            byEntity[entityId] ?: CalendarSourceNode(
                entity = entityId,
                color = extraCalendarColors[index % extraCalendarColors.size],
            )
        }
    }

    fun isCalendarSubscribed(entityId: String, defaults: List<CalendarSourceNode>): Boolean {
        val selected = _subscribedCalendars.value ?: defaults.mapNotNull { it.entity }
        return entityId in selected
    }

    fun setCalendarSubscribed(entityId: String, enabled: Boolean) {
        val defaults = _ui.value.dashboard?.home?.calendar?.calendars?.mapNotNull { it.entity }.orEmpty()
        val current = (_subscribedCalendars.value ?: defaults).toMutableList()
        if (enabled && entityId !in current) current += entityId
        if (!enabled) current.removeAll { it == entityId }
        credentials.subscribedCalendars = current
        _subscribedCalendars.value = current
    }

    fun resetCalendarSubscriptions() {
        credentials.subscribedCalendars = null
        _subscribedCalendars.value = null
    }

    fun refreshCalendars() {
        viewModelScope.launch {
            val fromHa = runCatching { client.listCalendars() }.getOrDefault(emptyList())
            val defaults = _ui.value.dashboard?.home?.calendar?.calendars.orEmpty()
            val extra = defaults.mapNotNull { source ->
                val id = source.entity ?: return@mapNotNull null
                CalendarInfo(id, client.state(id)?.friendlyName ?: id.substringAfter('.').replace('_', ' '))
            }
            val hidden = setOf("calendar.llm_vision_timeline")
            _availableCalendars.value = (fromHa + extra)
                .distinctBy { it.entityId }
                .filter { it.entityId !in hidden }
                .sortedBy { it.name.lowercase() }
        }
    }

    fun createCalendarEvent(
        entityId: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        allDay: Boolean,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCalendarEventMutation {
                createCalendarEventPayload(entityId, title, date, startTime, endTime, allDay)
            }
            onResult(result)
        }
    }

    fun updateCalendarEvent(
        entityId: String,
        uid: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        allDay: Boolean,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCalendarEventMutation {
                updateCalendarEventPayload(entityId, uid, title, date, startTime, endTime, allDay)
            }
            onResult(result)
        }
    }

    fun deleteCalendarEvent(
        entityId: String,
        uid: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCalendarEventMutation {
                client.deleteCalendarEvent(entityId = entityId, uid = uid)
            }
            onResult(result)
        }
    }

    private suspend fun runCalendarEventMutation(block: suspend () -> Unit): Result<Unit> {
        val result = runCatching { block() }
        if (result.isSuccess) {
            _calendarEventsRevision.value++
        }
        return result
    }

    private suspend fun createCalendarEventPayload(
        entityId: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        allDay: Boolean,
    ) {
        if (allDay || startTime == null) {
            client.createCalendarEvent(
                entityId = entityId,
                title = title,
                startDate = date,
                endDate = date.plusDays(1),
                allDay = true,
            )
        } else {
            val zone = ZoneId.systemDefault()
            val end = endTime ?: startTime.plusHours(1)
            val startInstant = date.atTime(startTime).atZone(zone).toInstant()
            val endInstant = date.atTime(end).atZone(zone).toInstant()
            client.createCalendarEvent(
                entityId = entityId,
                title = title,
                start = startInstant,
                end = endInstant,
                allDay = false,
            )
        }
    }

    private suspend fun updateCalendarEventPayload(
        entityId: String,
        uid: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        allDay: Boolean,
    ) {
        if (allDay || startTime == null) {
            client.updateCalendarEvent(
                entityId = entityId,
                uid = uid,
                title = title,
                startDate = date,
                endDate = date.plusDays(1),
                allDay = true,
            )
        } else {
            val zone = ZoneId.systemDefault()
            val end = endTime ?: startTime.plusHours(1)
            val startInstant = date.atTime(startTime).atZone(zone).toInstant()
            val endInstant = date.atTime(end).atZone(zone).toInstant()
            client.updateCalendarEvent(
                entityId = entityId,
                uid = uid,
                title = title,
                start = startInstant,
                end = endInstant,
                allDay = false,
            )
        }
    }

    fun onTap(widget: WidgetNode) {
        val action = widget.tap ?: ActionNode(type = "more_info")
        dispatch(action, widget.entity)
    }

    fun onHold(widget: WidgetNode) {
        val action = widget.hold ?: return
        dispatch(action, widget.entity)
    }

    fun dispatch(action: ActionNode?, fallbackEntity: String? = null) {
        if (action == null || action.type == "none") return
        val entity = action.entity ?: fallbackEntity
        when (action.type) {
            "menu_toggle" -> setDrawer(!_ui.value.drawerOpen)
            "navigate" -> openPopup(action.hash)
            "more_info" -> openMoreInfo(entity)
            "toggle" -> entity?.let { toggleEntity(it) }
            "vent_tilt_toggle" -> entity?.let { id ->
                viewModelScope.launch {
                    val open = client.state(id)?.state in setOf("open", "opening")
                    client.tiltVents(listOf(id), open = !open)
                }
            }
            "call_service" -> viewModelScope.launch {
                val service = action.service ?: return@launch
                val domain = service.substringBefore('.')
                val name = service.substringAfter('.')
                val ids = action.entityIds().ifEmpty { listOfNotNull(entity) }
                val data = action.data?.mapValues { it.value } ?: emptyMap()
                client.callService(domain, name, ids.ifEmpty { null }, data)
            }
            "fire-dom-event" -> {
                // Weather now/today swap is handled natively in the weather popup.
            }
        }
    }

    fun callEntityService(entityId: String, service: String, domain: String? = null) {
        viewModelScope.launch {
            val resolved = domain ?: entityId.substringBefore('.')
            client.callService(resolved, service, listOf(entityId))
        }
    }

    fun toggleEntity(entityId: String) {
        viewModelScope.launch {
            when (entityId.substringBefore('.')) {
                "light", "switch", "input_boolean", "fan" -> {
                    val current = client.state(entityId)
                    val on = current?.state == "on"
                    client.applyOptimisticState(entityId, if (on) "off" else "on")
                }
            }
            client.toggle(entityId)
        }
    }

    fun setBrightness(entityId: String, pct: Int) {
        viewModelScope.launch {
            val clamped = pct.coerceIn(0, 100)
            if (clamped <= 0) {
                client.applyOptimisticState(entityId, "off")
            } else {
                val brightness = ((clamped / 100.0) * 255.0).roundToInt().coerceIn(1, 255)
                client.applyOptimisticState(
                    entityId,
                    "on",
                    mapOf("brightness" to JsonPrimitive(brightness)),
                )
            }
            client.setLightBrightness(entityId, clamped)
        }
    }

    fun setTemperature(entityId: String, temperature: Double) {
        viewModelScope.launch { client.setTemperature(entityId, temperature) }
    }

    fun tiltGroup(entityIds: List<String>) {
        viewModelScope.launch {
            val anyOpen = entityIds.any { client.state(it)?.state in setOf("open", "opening") }
            client.tiltVents(entityIds, open = !anyOpen)
        }
    }

    companion object {
        private const val AUTO_BRIGHTNESS_RAMP_MS = 90L
        private const val MMWAVE_OCCUPANCY_ENTITY = "binary_sensor.secondary_living_room_switch_occupancy"
        private const val MMWAVE_TARGET_COUNT_ENTITY = "input_number.secondary_living_room_mmwave_target_count"

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HaViewModel(app) as T
                }
            }
    }
}

fun ActionNode.entityIds(): List<String> {
    val element = entityId ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }
}
