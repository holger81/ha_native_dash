package dev.holgerendt.hanative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
    val lastChanged: Instant? = null,
    val lastUpdated: Instant? = null,
) {
    fun attr(name: String): JsonElement? = attributes[name]

    fun attrString(name: String): String? = attributes[name]?.jsonPrimitive?.contentOrNull

    fun attrDouble(name: String): Double? {
        val value = attributes[name] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    val friendlyName: String
        get() = attrString("friendly_name") ?: entityId.substringAfter('.')

    val entityPicture: String?
        get() = attrString("entity_picture")
}

data class HistoryBucket(
    val startMs: Long,
    val mean: Double,
    val min: Double,
    val max: Double,
)

data class CalendarInfo(
    val entityId: String,
    val name: String,
)

data class HaCalendarEvent(
    val entityId: String = "",
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val start: Instant? = null,
    val end: Instant? = null,
    val allDay: Boolean = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val uid: String? = null,
    val keyFrame: String? = null,
    val clipPath: String? = null,
    val cameraName: String? = null,
    val category: String? = null,
    val label: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

private val CLIP_LINE_REGEX = Regex("""(?im)^clip:\s*(\S+)\s*$""")
private val CLIP_URL_REGEX = Regex("""(/api/frigate/notifications/[^/\s"']+/clip\.mp4|/api/events/[^/\s"']+/clip\.mp4)""")

internal fun splitClipFromDescription(description: String?): Pair<String?, String?> {
    if (description.isNullOrBlank()) return null to null
    var clipPath = CLIP_LINE_REGEX.find(description)?.groupValues?.get(1)?.trim()
    if (clipPath.isNullOrBlank()) {
        clipPath = CLIP_URL_REGEX.find(description)?.groupValues?.get(1)
    }
    val displayDescription = description.lines()
        .filterNot { line -> CLIP_LINE_REGEX.containsMatchIn(line) }
        .joinToString("\n")
        .replace(CLIP_URL_REGEX, "")
        .trim()
        .ifBlank { null }
    return displayDescription to clipPath
}

internal fun frigateSnapshotFromClip(clipPath: String): String? {
    val trimmed = clipPath.trimEnd('/')
    val clipSuffix = "/clip.mp4"
    if (!trimmed.endsWith(clipSuffix, ignoreCase = true)) return null
    return trimmed.dropLast(clipSuffix.length) + "/snapshot.jpg"
}

internal fun timelineSnapshotPath(keyFrame: String?, clipPath: String?): String? {
    val frame = keyFrame?.trim()?.takeIf { it.isNotBlank() }
    if (frame != null) {
        return frigateSnapshotFromClip(frame) ?: frame
    }
    return clipPath?.let(::frigateSnapshotFromClip)
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class HaClient {
    @Volatile var onKioskEvent: ((Map<String, String>) -> Unit)? = null
    @Volatile var onMmWaveTargetEvent: ((MmWaveTargetEvent) -> Unit)? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val massHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MASS_COMMAND_MAX_ATTEMPTS = 3
        private const val MASS_COMMAND_RETRY_BASE_MS = 400L
        private const val COMMAND_TIMEOUT_MS = 30_000L
    }

    private var webSocket: WebSocket? = null
    private var baseUrl: String = ""
    private var token: String = ""
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Result<JsonElement>) -> Unit>()
    private val forecastSubscriptions = ConcurrentHashMap<Int, (List<JsonObject>) -> Unit>()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateBatch = ConcurrentHashMap<String, EntityState>()
    @Volatile private var batchScheduled = false

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection
    private val deviceNameCache = ConcurrentHashMap<String, String?>()
    private val snapshotCache = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var massIngress: MassIngressSession? = null
    @Volatile private var massAddonSlug: String? = null
    @Volatile private var massPlayersCache: Pair<Long, List<MassPlayerInfo>>? = null

    val currentBaseUrl: String get() = baseUrl
    val currentToken: String get() = token

    private data class MassIngressSession(
        val session: String,
        val apiUrl: String,
        val expiresAtMs: Long,
    )

    fun state(entityId: String?): EntityState? = entityId?.let { _states.value[it] }

    fun applyOptimisticState(
        entityId: String,
        state: String,
        attributes: Map<String, JsonElement>? = null,
    ) {
        _states.update { current ->
            val existing = current[entityId] ?: EntityState(entityId = entityId, state = state)
            current + (entityId to existing.copy(
                state = state,
                attributes = if (attributes != null) existing.attributes + attributes else existing.attributes,
                lastChanged = Instant.now(),
                lastUpdated = Instant.now(),
            ))
        }
    }

    suspend fun connect(url: String, accessToken: String) {
        disconnect()
        baseUrl = url.trim().trimEnd('/')
        token = accessToken.trim()
        val host = NetworkGuard.hostOf(baseUrl)
        if (host == null || !NetworkGuard.isPrivateHost(host)) {
            _connection.value = ConnectionState.Error(
                "Home Assistant must be on the local network (private IP or .local name), not '$host'",
            )
            return
        }
        _connection.value = ConnectionState.Connecting
        openSocket()
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        drainPending(IllegalStateException("Disconnected"))
        massIngress = null
        massAddonSlug = null
        massPlayersCache = null
        stateBatch.clear()
        batchScheduled = false
        _connection.value = ConnectionState.Disconnected
    }

    private fun drainPending(error: Throwable) {
        pending.values.forEach { it(Result.failure(error)) }
        pending.clear()
        forecastSubscriptions.values.forEach { it(emptyList()) }
        forecastSubscriptions.clear()
    }

    private fun openSocket() {
        val wsUrl = baseUrl
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://") + "/api/websocket"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@HaClient.webSocket) return
                messageChannel.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@HaClient.webSocket !== webSocket) return
                this@HaClient.webSocket = null
                drainPending(t)
                _connection.value = ConnectionState.Error(t.message ?: "WebSocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@HaClient.webSocket !== webSocket) return
                this@HaClient.webSocket = null
                drainPending(IllegalStateException("Connection closed ($code: $reason)"))
                if (_connection.value is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Disconnected
                }
            }
        })
        startMessageCollector()
    }

    private fun startMessageCollector() {
        scope.launch {
            while (true) {
                val text = messageChannel.receive()
                handleMessage(text)
            }
        }
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> {
                send(buildJsonObject {
                    put("type", "auth")
                    put("access_token", token)
                })
            }
            "auth_ok" -> {
                _connection.value = ConnectionState.Connected
                send(buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("type", "subscribe_events")
                    put("event_type", "state_changed")
                })
                send(buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("type", "subscribe_events")
                    put("event_type", KioskCommands.EVENT)
                })
                send(buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("type", "subscribe_events")
                    put("event_type", "zha_event")
                })
                send(buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("type", "get_states")
                })
            }
            "auth_invalid" -> {
                webSocket?.close(4001, "auth_invalid")
                webSocket = null
                drainPending(IllegalStateException("Invalid access token"))
                _connection.value = ConnectionState.Error("Invalid access token")
            }
            "result" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val success = obj["success"]?.jsonPrimitive?.contentOrNull != "false"
                val forecastCallback = forecastSubscriptions[id]
                if (forecastCallback != null) {
                    if (!success) {
                        forecastSubscriptions.remove(id)
                        forecastCallback(emptyList())
                    }
                    return
                }
                val callback = pending.remove(id)
                if (callback != null) {
                    if (success) {
                        callback(Result.success(obj["result"] ?: JsonNull))
                    } else {
                        val message = obj["error"]?.jsonObject?.get("message")
                            ?.jsonPrimitive?.contentOrNull ?: "Command failed"
                        callback(Result.failure(IllegalStateException(message)))
                    }
                } else if (success) {
                    obj["result"]?.let { maybeLoadStates(it) }
                }
            }
            "event" -> {
                val subscriptionId = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val forecastCallback = subscriptionId?.let { forecastSubscriptions.remove(it) }
                if (forecastCallback != null) {
                    val event = obj["event"]?.jsonObject
                    val forecast = (event?.get("forecast") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    forecastCallback(forecast)
                    unsubscribe(subscriptionId)
                    return
                }
                val event = obj["event"]?.jsonObject ?: return
                when (event["event_type"]?.jsonPrimitive?.contentOrNull) {
                    "state_changed" -> {
                        val data = event["data"]?.jsonObject ?: return
                        val newState = data["new_state"]
                        if (newState is JsonObject) {
                            parseEntity(newState)?.let { entity ->
                                stateBatch[entity.entityId] = entity
                                scheduleBatchFlush()
                            }
                        }
                    }
                    KioskCommands.EVENT -> {
                        val data = event["data"]?.jsonObject ?: return
                        onKioskEvent?.invoke(jsonMap(data))
                    }
                    "zha_event" -> {
                        val data = event["data"]?.jsonObject ?: return
                        MmWaveLiveTracker.parseZhaEvent(data)?.let { onMmWaveTargetEvent?.invoke(it) }
                    }
                }
            }
        }
    }

    private fun scheduleBatchFlush() {
        if (batchScheduled) return
        batchScheduled = true
        scope.launch {
            delay(80)
            batchScheduled = false
            flushStateBatch()
        }
    }

    private fun flushStateBatch() {
        if (stateBatch.isEmpty()) return
        val batch = stateBatch.toMap()
        stateBatch.clear()
        _states.update { current -> current + batch }
    }

    private fun maybeLoadStates(result: JsonElement) {
        if (result !is JsonArray) return
        val mapped = result.mapNotNull { element ->
            (element as? JsonObject)?.let(::parseEntity)
        }.associateBy { it.entityId }
        if (mapped.isNotEmpty()) {
            _states.value = mapped
        }
    }

    private fun parseEntity(obj: JsonObject): EntityState? {
        val id = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val attributes = obj["attributes"]?.jsonObject ?: JsonObject(emptyMap())
        return EntityState(
            entityId = id,
            state = state,
            attributes = attributes,
            lastChanged = parseInstantOrDate(obj["last_changed"] as? JsonPrimitive),
            lastUpdated = parseInstantOrDate(obj["last_updated"] as? JsonPrimitive),
        )
    }

    private fun jsonMap(obj: JsonObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        obj.forEach { (key, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { out[key] = it }
                is JsonObject -> out.putAll(jsonMap(value))
                else -> Unit
            }
        }
        return out
    }

    private fun send(obj: JsonObject) {
        webSocket?.send(obj.toString())
    }

    private fun unsubscribe(subscriptionId: Int) {
        send(buildJsonObject {
            put("id", nextId.getAndIncrement())
            put("type", "unsubscribe_events")
            put("subscription", subscriptionId)
        })
    }

    private suspend fun command(builder: JsonObjectBuilder.(Int) -> Unit): JsonElement {
        val id = nextId.getAndIncrement()
        val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<JsonElement> { cont ->
                pending[id] = { r ->
                    r.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
                val payload = buildJsonObject {
                    put("id", id)
                    builder(id)
                }
                if (webSocket?.send(payload.toString()) != true) {
                    pending.remove(id)
                    cont.resumeWithException(IllegalStateException("Not connected"))
                }
                cont.invokeOnCancellation { pending.remove(id) }
            }
        }
        return result ?: throw IllegalStateException("Command timed out after ${COMMAND_TIMEOUT_MS}ms")
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: List<String>? = null,
        data: Map<String, JsonElement> = emptyMap(),
        returnResponse: Boolean = false,
    ): JsonElement {
        return command {
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            if (!entityId.isNullOrEmpty()) {
                put("target", buildJsonObject {
                    put("entity_id", JsonArray(entityId.map { JsonPrimitive(it) }))
                })
            }
            if (data.isNotEmpty()) {
                put("service_data", JsonObject(data))
            }
            if (returnResponse) {
                put("return_response", true)
            }
        }
    }

    suspend fun musicAssistantPlayers(): List<MusicAssistantPlayer> {
        val states = _states.value.values
        val fromMass = states
            .asSequence()
            .filter { it.isMusicAssistantPlayer() }
            .map {
                MusicAssistantPlayer(
                    entityId = it.entityId,
                    name = it.friendlyName,
                    massPlayerType = it.attrString("mass_player_type"),
                )
            }
            .toList()
        val base = if (fromMass.isNotEmpty()) {
            fromMass
        } else {
            // Ordinary media players until the Music Assistant integration exposes MA entities.
            states
                .asSequence()
                .filter { it.entityId.startsWith("media_player.") }
                .filter { it.state !in setOf("unavailable", "unknown") }
                .map {
                    MusicAssistantPlayer(
                        entityId = it.entityId,
                        name = it.friendlyName,
                    )
                }
                .toList()
        }
        return sortMusicPlayers(enrichMusicPlayersWithMassIds(base))
    }

    private fun sortMusicPlayers(players: List<MusicAssistantPlayer>): List<MusicAssistantPlayer> =
        players.sortedWith(
            compareByDescending<MusicAssistantPlayer> { player ->
                val state = state(player.entityId)?.state
                state == "playing" || state == "paused"
            }.thenBy { it.name.lowercase() },
        )

    suspend fun musicAssistantQueue(
        entityId: String,
        players: List<MusicAssistantPlayer> = emptyList(),
    ): MusicAssistantQueue? {
        val player = players.firstOrNull { it.entityId == entityId }
        val massPlayers = runCatching { musicAssistantMassPlayers() }.getOrElse { emptyList() }
        val massQueueId = resolveMassQueueId(entityId, player, massPlayers)

        val fromMass = if (!massQueueId.isNullOrBlank()) {
            runCatching { musicAssistantMassQueue(massQueueId) }.getOrNull()
        } else {
            null
        }

        val fromHa = runCatching {
            withTimeout(2_500) {
                val result = callService(
                    domain = "music_assistant",
                    service = "get_queue",
                    entityId = listOf(entityId),
                    returnResponse = true,
                )
                parseMusicAssistantQueue(result, entityId)
            }
        }.getOrNull()

        return mergeMusicAssistantQueues(fromHa, fromMass)
    }

    private fun resolveMassQueueId(
        entityId: String,
        player: MusicAssistantPlayer?,
        massPlayers: List<MassPlayerInfo>,
    ): String? {
        player?.groupRootId?.takeIf { it.isNotBlank() }?.let { return it }
        player?.massPlayerId?.takeIf { it.isNotBlank() }?.let { return it }
        val name = state(entityId)?.friendlyName
        return name?.let { matchMassPlayerInfo(it, massPlayers)?.playerId }
    }

    suspend fun refreshMusicPlayerTelemetry(players: List<MusicAssistantPlayer>): List<MusicAssistantPlayer> {
        val massPlayers = runCatching { musicAssistantMassPlayers(force = true) }.getOrElse { emptyList() }
        if (massPlayers.isEmpty()) return players
        return players.map { player ->
            val matched = matchMassPlayerInfo(player.name, massPlayers) ?: return@map player
            player.copy(
                massPlayerId = matched.playerId,
                massVolume = matched.volume,
                massGroupVolume = matched.groupVolume,
                groupMemberIds = matched.groupMemberIds,
                syncedToId = matched.syncedToId,
                canGroupWithIds = matched.canGroupWithIds,
                elapsedSec = matched.elapsedSec,
                elapsedUpdatedAtMs = matched.elapsedUpdatedAtMs,
                massPlaybackState = matched.playbackState,
            )
        }
    }

    suspend fun musicAssistantMassQueue(queueId: String): MusicAssistantQueue? {
        val result = massCommand(
            "player_queues/get",
            buildJsonObject { put("queue_id", queueId) },
        )
        return parseMusicAssistantQueue(result, queueId)
            ?: parseMusicAssistantQueue(
                buildJsonObject { put(queueId, result) },
                queueId,
            )
    }

    suspend fun mediaPlayerCommand(entityId: String, service: String, data: Map<String, JsonElement> = emptyMap()) {
        callService("media_player", service, listOf(entityId), data)
    }

    suspend fun setMediaVolume(entityId: String, level: Float) {
        mediaPlayerCommand(
            entityId,
            "volume_set",
            mapOf("volume_level" to JsonPrimitive(level.coerceIn(0f, 1f).toDouble())),
        )
    }

    suspend fun setMassPlayerVolume(playerId: String, levelPercent: Int) {
        massCommand(
            "players/cmd/volume_set",
            buildJsonObject {
                put("player_id", playerId)
                put("volume_level", levelPercent.coerceIn(0, 100))
            },
        )
        massPlayersCache = null
    }

    suspend fun setMassGroupVolume(playerId: String, levelPercent: Int) {
        massCommand(
            "players/cmd/group_volume",
            buildJsonObject {
                put("player_id", playerId)
                put("volume_level", levelPercent.coerceIn(0, 100))
            },
        )
        massPlayersCache = null
    }

    suspend fun setMassGroupMembers(
        targetPlayerId: String,
        addIds: List<String> = emptyList(),
        removeIds: List<String> = emptyList(),
    ) {
        massCommand(
            "players/cmd/set_members",
            buildJsonObject {
                put("target_player", targetPlayerId)
                if (addIds.isNotEmpty()) {
                    put("player_ids_to_add", JsonArray(addIds.map { JsonPrimitive(it) }))
                }
                if (removeIds.isNotEmpty()) {
                    put("player_ids_to_remove", JsonArray(removeIds.map { JsonPrimitive(it) }))
                }
            },
        )
        massPlayersCache = null
    }

    suspend fun ungroupMassPlayer(playerId: String) {
        massCommand(
            "players/cmd/ungroup",
            buildJsonObject { put("player_id", playerId) },
        )
        massPlayersCache = null
    }

    suspend fun setMediaShuffle(entityId: String, shuffle: Boolean) {
        mediaPlayerCommand(entityId, "shuffle_set", mapOf("shuffle" to JsonPrimitive(shuffle)))
    }

    suspend fun setMediaRepeat(entityId: String, repeat: String) {
        mediaPlayerCommand(entityId, "repeat_set", mapOf("repeat" to JsonPrimitive(repeat)))
    }

    suspend fun seekMedia(entityId: String, positionSec: Double) {
        mediaPlayerCommand(
            entityId,
            "media_seek",
            mapOf("seek_position" to JsonPrimitive(positionSec.coerceAtLeast(0.0))),
        )
    }

    suspend fun transferMusicQueue(targetEntityId: String, sourceEntityId: String? = null, autoPlay: Boolean = true) {
        val data = buildMap {
            if (!sourceEntityId.isNullOrBlank()) {
                put("source_player", JsonPrimitive(sourceEntityId))
            }
            put("auto_play", JsonPrimitive(autoPlay))
        }
        callService(
            domain = "music_assistant",
            service = "transfer_queue",
            entityId = listOf(targetEntityId),
            data = data,
        )
    }

    suspend fun musicAssistantMassPlayers(force: Boolean = false): List<MassPlayerInfo> = withContext(Dispatchers.IO) {
        val cached = massPlayersCache
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 2_500L) {
            return@withContext cached.second
        }
        val result = massCommand("players/all")
        val rows = result as? JsonArray ?: return@withContext emptyList()
        val players = rows.mapNotNull(::parseMassPlayerInfo)
        massPlayersCache = System.currentTimeMillis() to players
        players
    }

    suspend fun enrichMusicPlayersWithMassIds(players: List<MusicAssistantPlayer>): List<MusicAssistantPlayer> {
        val massPlayers = runCatching { musicAssistantMassPlayers(force = true) }.getOrElse { emptyList() }
        if (massPlayers.isEmpty()) return players
        return players.map { player ->
            val matched = matchMassPlayerInfo(player.name, massPlayers) ?: return@map player
            player.copy(
                massPlayerId = matched.playerId,
                massVolume = matched.volume,
                massGroupVolume = matched.groupVolume,
                groupMemberIds = matched.groupMemberIds,
                syncedToId = matched.syncedToId,
                canGroupWithIds = matched.canGroupWithIds,
                elapsedSec = matched.elapsedSec,
                elapsedUpdatedAtMs = matched.elapsedUpdatedAtMs,
                massPlaybackState = matched.playbackState,
            )
        }
    }

    suspend fun musicDiscoveryRecentlyPlayed(limit: Int = 12): List<MassMediaItem> {
        val result = massCommand(
            "music/recently_played_items",
            buildJsonObject {
                put("limit", limit)
                put("media_types", JsonArray(listOf(JsonPrimitive("playlist"), JsonPrimitive("album"), JsonPrimitive("track"))))
            },
        )
        return (result as? JsonArray)?.mapNotNull(::parseMassMediaItem).orEmpty()
    }

    suspend fun musicDiscoveryRecommendations(): List<MassRecommendationSection> {
        return parseMassRecommendationSections(massCommand("music/recommendations"))
    }

    suspend fun musicDiscoveryNewMusicTracks(limit: Int = 20): List<MassMediaItem> {
        val root = findAppleMusicBrowseRoot()
        val playlists = massCommand(
            "music/browse",
            buildJsonObject { put("path", massBrowseChildPath(root, "playlists")) },
        ) as? JsonArray
        val playlist = playlists
            ?.mapNotNull(::parseMassMediaItem)
            ?.firstOrNull { it.name.equals("New Music", ignoreCase = true) }
            ?: return emptyList()
        val provider = playlist.provider
            ?: playlist.uri.substringBefore("://", missingDelimiterValue = "apple_music")
        val itemId = playlist.itemId
            ?: playlist.uri.substringAfterLast('/')
        val tracks = massCommand(
            "music/playlists/playlist_tracks",
            buildJsonObject {
                put("item_id", itemId)
                put("provider_instance_id_or_domain", provider)
                put("allow_dynamic_tracks", true)
            },
        )
        val parsed = (tracks as? JsonArray)?.mapNotNull(::parseMassMediaItem).orEmpty()
        return listOf(playlist) + parsed.take(limit)
    }

    suspend fun musicSearch(
        query: String,
        limit: Int = 8,
        mediaTypes: Collection<String> = listOf("track", "album", "playlist", "artist"),
    ): MassSearchResults {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return MassSearchResults()
        val types = mediaTypes.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf("track", "album", "playlist", "artist") }
        val result = massCommand(
            "music/search",
            buildJsonObject {
                put("search_query", trimmed)
                put("limit", limit)
                put("media_types", JsonArray(types.map { JsonPrimitive(it) }))
            },
        )
        return parseMassSearchResults(result)
    }

    suspend fun musicBrowse(path: String? = null): List<MassMediaItem> {
        val result = if (path.isNullOrBlank()) {
            massCommand("music/browse", buildJsonObject {})
        } else {
            massCommand("music/browse", buildJsonObject { put("path", path) })
        }
        return (result as? JsonArray)?.mapNotNull(::parseMassBrowseItem).orEmpty()
    }

    suspend fun musicAppleMusicRootPath(): String = findAppleMusicBrowseRoot()

    suspend fun musicAppleMusicChildPath(child: String): String =
        massBrowseChildPath(findAppleMusicBrowseRoot(), child)

    suspend fun playMassMedia(queueId: String, mediaUri: String, option: String = "replace") {
        massCommand(
            "player_queues/play_media",
            buildJsonObject {
                put("queue_id", queueId)
                put("media", mediaUri)
                put("option", option)
            },
        )
    }

    private suspend fun findAppleMusicBrowseRoot(): String {
        val root = massCommand("music/browse", buildJsonObject {}) as? JsonArray ?: return "apple_music://"
        val apple = root.mapNotNull { it as? JsonObject }.firstOrNull {
            it["name"]?.jsonPrimitive?.contentOrNull?.equals("Apple Music", ignoreCase = true) == true ||
                it["provider"]?.jsonPrimitive?.contentOrNull?.contains("apple_music") == true ||
                it["path"]?.jsonPrimitive?.contentOrNull?.contains("apple_music") == true ||
                it["uri"]?.jsonPrimitive?.contentOrNull?.contains("apple_music") == true
        }
        return apple?.get("path")?.jsonPrimitive?.contentOrNull
            ?: apple?.get("uri")?.jsonPrimitive?.contentOrNull
            ?: "apple_music://"
    }

    private fun massBrowseChildPath(root: String, child: String): String {
        return when {
            root.endsWith("://") -> "$root$child"
            root.endsWith("/") -> "$root$child"
            else -> "$root/$child"
        }
    }

    private suspend fun massCommand(commandName: String, args: JsonObject? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("command", commandName)
                if (args != null) put("args", args)
            }.toString().toRequestBody(mediaType)
            fun execute(session: MassIngressSession): Pair<Int, String> {
                val request = Request.Builder()
                    .url(session.apiUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", "ingress_session=${session.session}")
                    .post(body)
                    .build()
                return massHttp.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    response.code to text
                }
            }
            var lastFailure: Exception? = null
            repeat(MASS_COMMAND_MAX_ATTEMPTS) { attempt ->
                try {
                    var session = ensureMassIngress()
                    var (code, text) = execute(session)
                    if (code == 401 || code == 403) {
                        massIngress = null
                        session = ensureMassIngress(force = true)
                        val retryAuth = execute(session)
                        code = retryAuth.first
                        text = retryAuth.second
                    }
                    if (code in 200..299) {
                        if (text.isBlank()) return@withContext JsonNull
                        return@withContext json.parseToJsonElement(text)
                    }
                    val message = massErrorMessage(text, code)
                    val failure = IllegalStateException(message)
                    val canRetry = code in 500..599 && attempt < MASS_COMMAND_MAX_ATTEMPTS - 1
                    if (!canRetry) throw failure
                    lastFailure = failure
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalStateException) {
                    throw error
                } catch (error: Exception) {
                    // IO / unexpected: retry a few times.
                    if (attempt >= MASS_COMMAND_MAX_ATTEMPTS - 1) throw error
                    lastFailure = error
                }
                delay(MASS_COMMAND_RETRY_BASE_MS * (1L shl attempt))
            }
            throw lastFailure ?: IllegalStateException("Music Assistant request failed")
        }

    private fun massErrorMessage(body: String, code: Int): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return "Music Assistant request failed ($code)"
        runCatching { json.parseToJsonElement(trimmed) }.getOrNull()?.let { element ->
            val obj = element as? JsonObject ?: return@let
            obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            obj["detail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return trimmed.take(240)
    }

    private suspend fun ensureMassIngress(force: Boolean = false): MassIngressSession {
        val cached = massIngress
        if (!force && cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return cached
        }
        val sessionResult = command {
            put("type", "supervisor/api")
            put("endpoint", "/ingress/session")
            put("method", "post")
        }.jsonObject
        val session = sessionResult["session"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Could not create Home Assistant ingress session")
        val slug = resolveMusicAssistantAddonSlug()
        val info = command {
            put("type", "supervisor/api")
            put("endpoint", "/addons/$slug/info")
            put("method", "get")
        }.jsonObject
        val ingressUrl = info["ingress_url"]?.jsonPrimitive?.contentOrNull?.trimEnd('/')
            ?: throw IllegalStateException("Music Assistant addon has no ingress URL")
        val apiUrl = "$baseUrl$ingressUrl/api"
        val created = MassIngressSession(
            session = session,
            apiUrl = apiUrl,
            expiresAtMs = System.currentTimeMillis() + 45 * 60_000L,
        )
        massIngress = created
        return created
    }

    private suspend fun resolveMusicAssistantAddonSlug(): String {
        massAddonSlug?.let { return it }
        val addonsResult = command {
            put("type", "supervisor/api")
            put("endpoint", "/addons")
            put("method", "get")
        }.jsonObject
        val addons = addonsResult["addons"] as? JsonArray
            ?: throw IllegalStateException("Could not list Home Assistant addons")
        val slug = addons.mapNotNull { it as? JsonObject }.firstOrNull { addon ->
            val candidate = addon["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = addon["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            candidate.contains("music_assistant", ignoreCase = true) ||
                name.contains("Music Assistant", ignoreCase = true)
        }?.get("slug")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Music Assistant addon not found")
        massAddonSlug = slug
        return slug
    }

    suspend fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        val current = state(entityId)?.state
        when (domain) {
            "lock" -> {
                val service = if (current == "locked") "unlock" else "lock"
                callService("lock", service, listOf(entityId))
            }
            "cover" -> {
                val service = if (current == "open" || current == "opening") "close_cover" else "open_cover"
                callService("cover", service, listOf(entityId))
            }
            "vacuum" -> {
                val service = if (current in setOf("cleaning", "returning")) "return_to_base" else "start"
                callService("vacuum", service, listOf(entityId))
            }
            "scene" -> callService("scene", "turn_on", listOf(entityId))
            "script" -> callService("script", "turn_on", listOf(entityId))
            else -> callService(domain, "toggle", listOf(entityId))
        }
    }

    suspend fun setLightBrightness(entityId: String, pct: Int) {
        if (pct <= 0) {
            callService("light", "turn_off", listOf(entityId))
        } else {
            callService(
                "light",
                "turn_on",
                listOf(entityId),
                mapOf("brightness_pct" to JsonPrimitive(pct)),
            )
        }
    }

    suspend fun setTemperature(entityId: String, temperature: Double) {
        callService(
            "climate",
            "set_temperature",
            listOf(entityId),
            mapOf("temperature" to JsonPrimitive(temperature)),
        )
    }

    suspend fun setEntityPower(entityId: String, on: Boolean) {
        val domain = entityId.substringBefore('.')
        when (domain) {
            "script" -> callService("script", "turn_on", listOf(entityId))
            "button" -> callService("button", "press", listOf(entityId))
            else -> callService(domain, if (on) "turn_on" else "turn_off", listOf(entityId))
        }
    }

    suspend fun setNumericEntityValue(entityId: String, value: Double) {
        when (entityId.substringBefore('.')) {
            "number" -> callService(
                "number",
                "set_value",
                listOf(entityId),
                mapOf("value" to JsonPrimitive(value)),
            )
            "light" -> setLightBrightness(entityId, value.roundToInt().coerceIn(0, 100))
            else -> callService(
                entityId.substringBefore('.'),
                "set_value",
                listOf(entityId),
                mapOf("value" to JsonPrimitive(value)),
            )
        }
    }

    suspend fun tiltVents(entityIds: List<String>, open: Boolean) {
        val service = if (open) "open_cover_tilt" else "close_cover_tilt"
        callService("cover", service, entityIds)
    }

    suspend fun history(entityId: String, hours: Int = 12): List<Pair<Long, Double>> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank() || entityId.isBlank()) return@withContext emptyList()
            val start = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS)
            val encodedEntity = URLEncoder.encode(entityId, "UTF-8")
            val startEnc = URLEncoder.encode(start.toString(), "UTF-8")
            val url = "$baseUrl/api/history/period/$startEnc?filter_entity_id=$encodedEntity&significant_changes_only=0&minimal_response=0"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val fromRest = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body)
                val series = (root as? JsonArray)?.firstOrNull() as? JsonArray ?: return@use emptyList()
                parseHistoryPoints(series)
            }
            if (fromRest.size >= 2) return@withContext fromRest
            val fromWs = runCatching { websocketHistory(entityId, hours) }.getOrDefault(emptyList())
            fromWs.ifEmpty { fromRest }
        }

    private suspend fun websocketHistory(entityId: String, hours: Int): List<Pair<Long, Double>> {
        val end = Instant.now()
        val start = end.minus(hours.toLong(), ChronoUnit.HOURS)
        val result = command {
            put("type", "history/history_during_period")
            put("start_time", start.toString())
            put("end_time", end.toString())
            put("significant_changes_only", false)
            put("minimal_response", false)
            put("no_attributes", true)
            put("entity_ids", JsonArray(listOf(JsonPrimitive(entityId))))
        }
        val rows = when (result) {
            is JsonObject -> result[entityId] as? JsonArray
            is JsonArray -> result
            else -> null
        } ?: return emptyList()
        return parseHistoryPoints(rows)
    }

    private fun parseHistoryPoints(series: JsonArray): List<Pair<Long, Double>> =
        series.mapNotNull { point ->
            val obj = point as? JsonObject ?: return@mapNotNull null
            val raw = obj["state"]?.jsonPrimitive?.contentOrNull
                ?: obj["s"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val value = historyValue(raw) ?: return@mapNotNull null
            val time = obj["last_changed"] ?: obj["last_updated"] ?: obj["lu"] ?: obj["last_changed"]
            val millis = (time as? JsonPrimitive)?.let { parseInstantOrDate(it)?.toEpochMilli() }
                ?: return@mapNotNull null
            millis to value
        }

    /** HA more-info history: 5-minute mean with min/max band; falls back to raw history. */
    suspend fun historyBuckets(entityId: String, hours: Int = 24): List<HistoryBucket> {
        val fiveMin = runCatching { statisticsDuringPeriod(entityId, hours, "5minute") }.getOrDefault(emptyList())
        if (fiveMin.size >= 2) return fiveMin
        val hourly = runCatching { statisticsDuringPeriod(entityId, hours, "hour") }.getOrDefault(emptyList())
        if (hourly.size >= 2) return hourly
        val points = history(entityId, hours)
        if (points.size == 1) {
            val endMs = Instant.now().toEpochMilli()
            val startMs = endMs - hours.toLong() * 60L * 60L * 1000L
            val value = points.first().second
            return listOf(
                HistoryBucket(startMs, value, value, value),
                HistoryBucket(points.first().first, value, value, value),
                HistoryBucket(endMs, value, value, value),
            )
        }
        return aggregateHistory(points, hours)
    }

    suspend fun deviceNameFor(entityId: String): String? {
        if (entityId.isBlank()) return null
        if (deviceNameCache.containsKey(entityId)) return deviceNameCache[entityId]
        val name = runCatching { fetchDeviceName(entityId) }.getOrNull()
        deviceNameCache[entityId] = name
        return name
    }

    private suspend fun fetchDeviceName(entityId: String): String? {
        val entity = command {
            put("type", "config/entity_registry/get")
            put("entity_id", entityId)
        }.jsonObject
        val deviceId = entity["device_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val devices = command { put("type", "config/device_registry/list") }.jsonArray
        val device = devices.mapNotNull { it as? JsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == deviceId } ?: return null
        return device["name_by_user"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: device["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private suspend fun statisticsDuringPeriod(entityId: String, hours: Int, period: String): List<HistoryBucket> {
        if (entityId.isBlank()) return emptyList()
        val end = Instant.now()
        val start = end.minus(hours.toLong(), ChronoUnit.HOURS)
        val result = command {
            put("type", "recorder/statistics_during_period")
            put("start_time", start.toString())
            put("end_time", end.toString())
            put("period", period)
            put("statistic_ids", JsonArray(listOf(JsonPrimitive(entityId))))
            put("types", JsonArray(listOf("mean", "min", "max", "state").map { JsonPrimitive(it) }))
        }
        val rows = when (result) {
            is JsonObject -> result[entityId] as? JsonArray
            is JsonArray -> result
            else -> null
        } ?: return emptyList()
        return rows.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val startMs = obj["start"]?.asEpochMs() ?: return@mapNotNull null
            val min = obj.num("min")
            val max = obj.num("max")
            val mean = obj.num("mean")
                ?: obj.num("state")
                ?: listOfNotNull(min, max).takeIf { it.isNotEmpty() }?.average()
                ?: return@mapNotNull null
            HistoryBucket(
                startMs = startMs,
                mean = mean,
                min = min ?: mean,
                max = max ?: mean,
            )
        }.sortedBy { it.startMs }
    }

    private fun aggregateHistory(points: List<Pair<Long, Double>>, hours: Int): List<HistoryBucket> {
        if (points.isEmpty()) return emptyList()
        val periodMs = 5L * 60L * 1000L
        val endMs = Instant.now().toEpochMilli()
        val startMs = endMs - hours.toLong() * 60L * 60L * 1000L
        val buckets = LinkedHashMap<Long, MutableList<Double>>()
        points.forEach { (time, value) ->
            if (time < startMs) return@forEach
            val key = ((time - startMs) / periodMs) * periodMs + startMs
            buckets.getOrPut(key) { mutableListOf() }.add(value)
        }
        return buckets.map { (key, values) ->
            HistoryBucket(key, values.average(), values.min(), values.max())
        }.sortedBy { it.startMs }
    }

    private fun JsonObject.num(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun JsonElement.asEpochMs(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { value ->
            return if (value < 100_000_000_000L) value * 1000 else value
        }
        return primitive.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }

    private fun historyValue(raw: String): Double? {
        raw.toDoubleOrNull()?.let { return it }
        return when (raw.lowercase()) {
            "on", "home", "open", "opening", "locked", "true", "active", "cleaning", "playing" -> 1.0
            "off", "not_home", "closed", "closing", "unlocked", "false", "idle", "docked", "paused" -> 0.0
            else -> null
        }
    }

    suspend fun cameraSnapshot(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        snapshotCache[entityId]?.let { return@withContext it }
        val fresh = fetchCameraSnapshot(entityId) ?: return@withContext null
        snapshotCache[entityId] = fresh
        fresh
    }

    /** Prefetch stills so the camera popup can show a poster immediately. */
    suspend fun prefetchCameraSnapshots(entityIds: Collection<String>) {
        withContext(Dispatchers.IO) {
            entityIds.distinct().forEach { entityId ->
                if (snapshotCache.containsKey(entityId)) return@forEach
                fetchCameraSnapshot(entityId)?.let { snapshotCache[entityId] = it }
            }
        }
    }

    private suspend fun fetchCameraSnapshot(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        val picture = state(entityId)?.entityPicture
        val paths = buildList {
            if (entityId.startsWith("camera.")) add("/api/camera_proxy/$entityId")
            if (entityId.startsWith("image.")) add("/api/image_proxy/$entityId")
            if (picture != null) add(picture)
            if (!entityId.startsWith("camera.") && !entityId.startsWith("image.")) {
                add("/api/camera_proxy/$entityId")
                add("/api/image_proxy/$entityId")
            }
        }.distinct()
        for (path in paths) {
            authenticatedBytes(path)?.let { return@withContext it }
        }
        null
    }

    /** HLS playlist path from the stream integration (`camera/stream`). Null if unavailable. */
    suspend fun cameraHlsUrl(entityId: String): String? {
        if (!entityId.startsWith("camera.") || baseUrl.isBlank()) return null
        return try {
            withTimeout(5_000) {
                val result = command {
                    put("type", "camera/stream")
                    put("entity_id", entityId)
                    put("format", "hls")
                }
                val path = result.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: return@withTimeout null
                if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl$path"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun bearerHeaders(): Map<String, String> =
        if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    suspend fun authenticatedBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!path.startsWith("http") && (baseUrl.isBlank() || token.isBlank())) return@withContext null
        val url = if (path.startsWith("http")) path else baseUrl + path
        val isHaUrl = !path.startsWith("http") || url.startsWith(baseUrl)
        runCatching {
            val builder = Request.Builder().url(url)
            if (isHaUrl && token.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $token")
            }
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    suspend fun listCalendars(): List<CalendarInfo> {
        val fromApi = (restGet("/api/calendars") as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: state(id)?.friendlyName
                ?: id.substringAfter('.')
            CalendarInfo(id, name)
        }
        val fromStates = _states.value.values
            .filter { it.entityId.startsWith("calendar.") }
            .map { CalendarInfo(it.entityId, it.friendlyName) }
        return (fromApi + fromStates).distinctBy { it.entityId }.sortedBy { it.name.lowercase() }
    }

    suspend fun calendarEvents(entityId: String, start: Instant, end: Instant): List<HaCalendarEvent> {
        val startEnc = URLEncoder.encode(start.toString(), "UTF-8")
        val endEnc = URLEncoder.encode(end.toString(), "UTF-8")
        val rest = restGet("/api/calendars/$entityId?start=$startEnc&end=$endEnc")
        val rows = when (rest) {
            is JsonArray -> rest
            is JsonObject -> rest["events"] as? JsonArray
            else -> null
        }
        if (rows != null) {
            return rows.mapNotNull { parseCalendarEvent(it as? JsonObject ?: return@mapNotNull null, entityId) }
        }
        return websocketCalendarEvents(entityId, start, end)
    }

    private suspend fun websocketCalendarEvents(entityId: String, start: Instant, end: Instant): List<HaCalendarEvent> {
        val result = runCatching {
            command {
                put("type", "calendar/events")
                put("entity_id", entityId)
                put("start_date_time", start.toString())
                put("end_date_time", end.toString())
            }
        }.getOrNull() ?: return emptyList()
        val rows = when (result) {
            is JsonArray -> result
            is JsonObject -> result["events"] as? JsonArray ?: result["response"] as? JsonArray
            else -> null
        } ?: return emptyList()
        return rows.mapNotNull { parseCalendarEvent(it as? JsonObject ?: return@mapNotNull null, entityId) }
    }

    suspend fun createCalendarEvent(
        entityId: String,
        title: String,
        start: Instant? = null,
        end: Instant? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        allDay: Boolean = false,
    ) {
        val data = buildMap<String, JsonElement> {
            put("summary", JsonPrimitive(title))
            if (allDay) {
                requireNotNull(startDate) { "startDate required for all-day events" }
                requireNotNull(endDate) { "endDate required for all-day events" }
                put("start_date", JsonPrimitive(startDate.toString()))
                put("end_date", JsonPrimitive(endDate.toString()))
            } else {
                requireNotNull(start) { "start required for timed events" }
                requireNotNull(end) { "end required for timed events" }
                put("start_date_time", JsonPrimitive(formatHaLocalDateTime(start)))
                put("end_date_time", JsonPrimitive(formatHaLocalDateTime(end)))
            }
        }
        callService("calendar", "create_event", listOf(entityId), data)
    }

    suspend fun deleteCalendarEvent(entityId: String, uid: String) {
        command {
            put("type", "calendar/event/delete")
            put("entity_id", entityId)
            put("uid", uid)
        }
    }

    suspend fun updateCalendarEvent(
        entityId: String,
        uid: String,
        title: String,
        start: Instant? = null,
        end: Instant? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        allDay: Boolean = false,
    ) {
        val eventData = buildMap<String, JsonElement> {
            put("summary", JsonPrimitive(title))
            if (allDay) {
                requireNotNull(startDate) { "startDate required for all-day events" }
                requireNotNull(endDate) { "endDate required for all-day events" }
                put("start", JsonPrimitive(startDate.toString()))
                put("end", JsonPrimitive(endDate.toString()))
            } else {
                requireNotNull(start) { "start required for timed events" }
                requireNotNull(end) { "end required for timed events" }
                put("start", JsonPrimitive(formatHaLocalDateTime(start)))
                put("end", JsonPrimitive(formatHaLocalDateTime(end)))
            }
        }
        command {
            put("type", "calendar/event/update")
            put("entity_id", entityId)
            put("uid", uid)
            put("event", JsonObject(eventData))
        }
    }

    suspend fun llmVisionEvents(
        entityId: String = "calendar.llm_vision_timeline",
        limit: Int = 5,
        hours: Int? = 24,
        days: Int? = null,
    ): List<HaCalendarEvent> {
        val params = buildList {
            add("limit=$limit")
            if (hours != null && hours > 0) add("hours=$hours")
            if (days != null && days > 0) add("days=$days")
            add("include_no_activity=false")
        }.joinToString("&")
        val root = restGet("/api/llmvision/timeline/events?$params")
        val items = when (root) {
            is JsonObject -> root["events"] as? JsonArray
            is JsonArray -> root
            else -> null
        }
        if (items != null) {
            return items.mapNotNull { parseLlmVisionEvent(it as? JsonObject ?: return@mapNotNull null) }
        }
        val now = Instant.now()
        val lookbackHours = (hours ?: ((days ?: 1) * 24)).coerceAtLeast(1)
        return calendarEvents(entityId, now.minus(lookbackHours.toLong(), ChronoUnit.HOURS), now)
            .sortedByDescending { it.start ?: Instant.EPOCH }
            .take(limit)
    }

    suspend fun authenticatedMediaUrl(path: String): String? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("/api/")) return "$baseUrl$path"
        val resolved = resolveMediaUrl(path) ?: return null
        return if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
            resolved
        } else {
            "$baseUrl$resolved"
        }
    }

    suspend fun resolveMediaUrl(path: String): String? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("/api/")) return path
        val mediaId = when {
            path.startsWith("media-source://") -> path
            path.startsWith("/media/") -> "media-source://media_source/local/" + path.removePrefix("/media/")
            path.startsWith("media/") -> "media-source://media_source/local/" + path.removePrefix("media/")
            else -> "media-source://media_source/local/$path"
        }
        return try {
            val result = command {
                put("type", "media_source/resolve_media")
                put("media_content_id", mediaId)
                put("expires", 60 * 60 * 3)
            }
            val url = result.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: return path
            if (url.startsWith("http://") || url.startsWith("https://")) url else "$baseUrl$url"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (path.startsWith("/")) "$baseUrl$path" else path
        }
    }

    suspend fun mediaBytes(path: String): ByteArray? {
        if (path.isBlank()) return null
        val fetchPath = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/api/") -> path
            else -> resolveMediaUrl(path) ?: return null
        }
        return authenticatedBytes(fetchPath)
    }

    private suspend fun restGet(path: String): JsonElement? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || token.isBlank()) return@withContext null
        val request = Request.Builder()
            .url(if (path.startsWith("http")) path else "$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            json.parseToJsonElement(body)
        }
    }

    private fun parseCalendarEvent(obj: JsonObject, entityId: String): HaCalendarEvent? {
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
            ?: obj["title"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val startBound = parseTimeBound(obj["start"])
        val endBound = parseTimeBound(obj["end"])
        return HaCalendarEvent(
            entityId = entityId,
            summary = summary,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            location = obj["location"]?.jsonPrimitive?.contentOrNull,
            start = startBound.first,
            end = endBound.first,
            allDay = startBound.second != null && startBound.first == null,
            startDate = startBound.second,
            endDate = endBound.second,
            uid = obj["uid"]?.jsonPrimitive?.contentOrNull,
            keyFrame = obj["key_frame"]?.jsonPrimitive?.contentOrNull
                ?: obj["location"]?.jsonPrimitive?.contentOrNull?.takeIf { it.contains("/media/") },
            cameraName = obj["camera_name"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseLlmVisionEvent(obj: JsonObject): HaCalendarEvent? {
        val summary = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["summary"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val startBound = parseTimeBound(obj["start"] ?: obj["startTime"])
        val endBound = parseTimeBound(obj["end"] ?: obj["endTime"])
        val rawDescription = obj["description"]?.jsonPrimitive?.contentOrNull
        val (displayDescription, clipPath) = splitClipFromDescription(rawDescription)
        val rawKeyFrame = obj["key_frame"]?.jsonPrimitive?.contentOrNull
            ?: obj["keyFrame"]?.jsonPrimitive?.contentOrNull
            ?: obj["image"]?.jsonPrimitive?.contentOrNull
            ?: obj["snapshot"]?.jsonPrimitive?.contentOrNull
        val keyFrame = timelineSnapshotPath(rawKeyFrame, clipPath)
        return HaCalendarEvent(
            entityId = "calendar.llm_vision_timeline",
            summary = summary,
            description = displayDescription,
            start = startBound.first,
            end = endBound.first,
            allDay = false,
            startDate = startBound.second,
            endDate = endBound.second,
            uid = obj["uid"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull,
            keyFrame = keyFrame,
            clipPath = clipPath,
            cameraName = obj["camera_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["cameraName"]?.jsonPrimitive?.contentOrNull,
            category = obj["category"]?.jsonPrimitive?.contentOrNull,
            label = obj["label"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseTimeBound(element: JsonElement?): Pair<Instant?, LocalDate?> {
        if (element == null || element is JsonNull) return null to null
        if (element is JsonPrimitive) {
            return parseDateTimePrimitive(element) ?: (null to null)
        }
        val obj = element as? JsonObject ?: return null to null
        (obj["dateTime"] ?: obj["date_time"])?.let { value ->
            if (value is JsonPrimitive) parseDateTimePrimitive(value)?.let { return it }
        }
        obj["date"]?.jsonPrimitive?.contentOrNull?.let { text ->
            if ('T' in text || text.length > 10) {
                parseDateTimePrimitive(obj["date"]?.jsonPrimitive)?.let { return it }
            }
            val date = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
            if (date != null) return null to date
        }
        return parseDateTimePrimitive(obj["start"]?.jsonPrimitive ?: obj["value"]?.jsonPrimitive) ?: (null to null)
    }

    private fun parseDateTimePrimitive(primitive: JsonPrimitive?): Pair<Instant?, LocalDate?>? {
        if (primitive == null) return null
        parseInstantOrDate(primitive)?.let { return it to null }
        val text = primitive.contentOrNull ?: return null
        val date = runCatching { LocalDate.parse(text.take(10)) }.getOrNull() ?: return null
        return null to date
    }

    private fun parseInstantOrDate(primitive: JsonPrimitive?): Instant? {
        if (primitive == null) return null
        primitive.longOrNull?.let { epoch ->
            return if (epoch > 10_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
        }
        primitive.doubleOrNull?.let { epoch ->
            val value = epoch.toLong()
            return if (value > 10_000_000_000L) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)
        }
        val text = primitive.contentOrNull?.replace(' ', 'T') ?: return null
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()?.let { return it }
        return null
    }

    private fun formatHaLocalDateTime(instant: Instant): String =
        LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    suspend fun weatherForecast(entityId: String, forecastType: String = "daily"): List<JsonObject> {
        val fromRest = runCatching { forecastViaRest(entityId, forecastType) }.getOrDefault(emptyList())
        if (fromRest.isNotEmpty()) return fromRest

        val fromSubscribe = runCatching { forecastViaSubscribe(entityId, forecastType) }.getOrDefault(emptyList())
        if (fromSubscribe.isNotEmpty()) return fromSubscribe

        if (forecastType == "daily") {
            forecastFromAttributes(entityId).takeIf { it.isNotEmpty() }?.let { return it }
            return forecastFromAttributes("sensor.weather_forecast_daily")
        }
        return emptyList()
    }

    private suspend fun forecastViaRest(entityId: String, forecastType: String): List<JsonObject> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank()) return@withContext emptyList()
            val body = buildJsonObject {
                put("entity_id", JsonArray(listOf(JsonPrimitive(entityId))))
                put("type", forecastType)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/services/weather/get_forecasts?return_response=true")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(mediaType))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val root = runCatching {
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }.getOrNull() ?: return@use emptyList()
                val forecast = root[entityId]?.jsonObject?.get("forecast") as? JsonArray
                forecast?.mapNotNull { it as? JsonObject } ?: emptyList()
            }
        }

    private suspend fun forecastViaSubscribe(entityId: String, forecastType: String): List<JsonObject> {
        if (webSocket == null) return emptyList()
        val id = nextId.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            forecastSubscriptions[id] = { forecast ->
                cont.resume(forecast)
            }
            cont.invokeOnCancellation { forecastSubscriptions.remove(id) }
            val sent = webSocket?.send(
                buildJsonObject {
                    put("id", id)
                    put("type", "weather/subscribe_forecast")
                    put("entity_id", entityId)
                    put("forecast_type", forecastType)
                }.toString(),
            ) == true
            if (!sent) {
                forecastSubscriptions.remove(id)
                cont.resume(emptyList())
            }
        }
    }

    private fun forecastFromAttributes(entityId: String): List<JsonObject> {
        val forecast = state(entityId)?.attributes?.get("forecast") as? JsonArray ?: return emptyList()
        return forecast.mapNotNull { it as? JsonObject }
    }

    suspend fun testRest(url: String, accessToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url.trim().trimEnd('/') + "/api/")
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${response.message}")
                }
            }
        }
    }

}
