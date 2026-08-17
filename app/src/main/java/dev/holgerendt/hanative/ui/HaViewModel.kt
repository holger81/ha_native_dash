package dev.holgerendt.hanative.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.holgerendt.hanative.HaNativeApp
import dev.holgerendt.hanative.data.CalendarInfo
import dev.holgerendt.hanative.data.ConnectionState
import dev.holgerendt.hanative.data.CredentialsStore
import dev.holgerendt.hanative.data.DashboardLoader
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.HaClient
import dev.holgerendt.hanative.data.LanAddresses
import dev.holgerendt.hanative.data.ManagementServer
import dev.holgerendt.hanative.data.ManagementTls
import dev.holgerendt.hanative.model.ActionNode
import dev.holgerendt.hanative.model.CalendarSourceNode
import dev.holgerendt.hanative.model.DashboardFile
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.WidgetNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom

data class UiState(
    val dashboard: DashboardFile? = null,
    val showSetup: Boolean = true,
    val drawerOpen: Boolean = false,
    val popupHash: String? = null,
    val moreInfoId: String? = null,
    val setupError: String? = null,
    val setupBusy: Boolean = false,
    val remotePin: String = "",
    val pinIsUserSet: Boolean = false,
    val remoteUrls: List<String> = emptyList(),
    val managementError: String? = null,
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

    private val _ui = MutableStateFlow(
        UiState(showSetup = !credentials.isConfigured),
    )
    val ui: StateFlow<UiState> = _ui

    private var reconnectJob: Job? = null
    private var managementServer: ManagementServer? = null
    private val random = SecureRandom()
    @Volatile private var currentPin: String = credentials.adoptOrCreatePin { newPin() }

    private val _subscribedCalendars = MutableStateFlow(credentials.subscribedCalendars)
    val subscribedCalendars: StateFlow<List<String>?> = _subscribedCalendars

    private val _availableCalendars = MutableStateFlow(listOf<CalendarInfo>())
    val availableCalendars: StateFlow<List<CalendarInfo>> = _availableCalendars

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
        )
        startManagementServer()
        if (credentials.isConfigured) {
            viewModelScope.launch { connect(credentials.baseUrl, credentials.token) }
        }
    }

    override fun onCleared() {
        managementServer?.stop()
        super.onCleared()
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

    fun popup(hash: String?): PopupNode? =
        _ui.value.dashboard?.home?.popups?.firstOrNull { it.hash == hash }

    suspend fun connect(url: String, token: String): Result<Unit> {
        _ui.value = _ui.value.copy(setupBusy = true, setupError = null)
        val result = withContext(Dispatchers.IO) { client.testRest(url, token) }
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Connection failed"
            _ui.value = _ui.value.copy(setupBusy = false, setupError = message)
            return Result.failure(IllegalStateException(message))
        }
        credentials.baseUrl = url
        credentials.token = token
        if (credentials.managementPin.isBlank()) {
            credentials.managementPin = currentPin
        } else {
            currentPin = credentials.managementPin
        }
        client.connect(url, token)
        refreshCalendars()
        _ui.value = _ui.value.copy(
            showSetup = false,
            setupBusy = false,
            setupError = null,
            remotePin = currentPin,
            pinIsUserSet = credentials.managementPin.isNotBlank(),
            drawerOpen = false,
        )
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                val state = client.connection.value
                if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                    runCatching { client.connect(credentials.baseUrl, credentials.token) }
                }
            }
        }
        return Result.success(Unit)
    }

    fun connectFromUi(url: String, token: String) {
        viewModelScope.launch { connect(url, token) }
    }

    val savedUrl: String get() = credentials.baseUrl
    val savedToken: String get() = credentials.token

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
        _ui.value = _ui.value.copy(popupHash = hash, drawerOpen = false)
    }

    fun closePopup() {
        _ui.value = _ui.value.copy(popupHash = null)
    }

    fun openMoreInfo(entityId: String?) {
        if (entityId.isNullOrBlank()) return
        _ui.value = _ui.value.copy(moreInfoId = entityId)
    }

    fun closeMoreInfo() {
        _ui.value = _ui.value.copy(moreInfoId = null)
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
            "toggle" -> entity?.let { id -> viewModelScope.launch { client.toggle(id) } }
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
        viewModelScope.launch { client.toggle(entityId) }
    }

    fun setBrightness(entityId: String, pct: Int) {
        viewModelScope.launch { client.setLightBrightness(entityId, pct) }
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
