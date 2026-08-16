package dev.holgerendt.hanative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
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
    val cameraName: String? = null,
    val category: String? = null,
    val label: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class HaClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var baseUrl: String = ""
    private var token: String = ""
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Result<JsonElement>) -> Unit>()

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection

    val currentBaseUrl: String get() = baseUrl
    val currentToken: String get() = token

    fun state(entityId: String?): EntityState? = entityId?.let { _states.value[it] }

    suspend fun connect(url: String, accessToken: String) {
        disconnect()
        baseUrl = url.trim().trimEnd('/')
        token = accessToken.trim()
        _connection.value = ConnectionState.Connecting
        openSocket()
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        pending.values.forEach { it(Result.failure(IllegalStateException("Disconnected"))) }
        pending.clear()
        _connection.value = ConnectionState.Disconnected
    }

    private fun openSocket() {
        val wsUrl = baseUrl
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://") + "/api/websocket"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connection.value = ConnectionState.Error(t.message ?: "WebSocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_connection.value is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> {
                ws.send(buildJsonObject {
                    put("type", "auth")
                    put("access_token", token)
                }.toString())
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
                    put("type", "get_states")
                })
            }
            "auth_invalid" -> {
                _connection.value = ConnectionState.Error("Invalid access token")
            }
            "result" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val success = obj["success"]?.jsonPrimitive?.contentOrNull != "false"
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
                val event = obj["event"]?.jsonObject ?: return
                if (event["event_type"]?.jsonPrimitive?.contentOrNull == "state_changed") {
                    val data = event["data"]?.jsonObject ?: return
                    val newState = data["new_state"]
                    if (newState is JsonObject) {
                        parseEntity(newState)?.let { entity ->
                            _states.update { it + (entity.entityId to entity) }
                        }
                    }
                }
            }
        }
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
        return EntityState(id, state, attributes)
    }

    private fun send(obj: JsonObject) {
        webSocket?.send(obj.toString())
    }

    private suspend fun command(builder: JsonObjectBuilder.(Int) -> Unit): JsonElement {
        val id = nextId.getAndIncrement()
        return suspendCancellableCoroutine { cont ->
            pending[id] = { result ->
                result.fold(
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

    suspend fun callService(
        domain: String,
        service: String,
        entityId: List<String>? = null,
        data: Map<String, JsonElement> = emptyMap(),
    ) {
        command {
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
        }
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

    suspend fun tiltVents(entityIds: List<String>, open: Boolean) {
        val service = if (open) "open_cover_tilt" else "close_cover_tilt"
        callService("cover", service, entityIds)
    }

    suspend fun history(entityId: String, hours: Int = 12): List<Pair<Long, Double>> =
        withContext(Dispatchers.IO) {
            val start = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS).toString()
            val url = "$baseUrl/api/history/period/$start?filter_entity_id=$entityId&minimal_response&significant_changes_only=0"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body)
                val series = (root as? JsonArray)?.firstOrNull() as? JsonArray ?: return@withContext emptyList()
                series.mapNotNull { point ->
                    val obj = point as? JsonObject ?: return@mapNotNull null
                    val state = obj["state"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                    val lastChanged = obj["last_changed"]?.jsonPrimitive?.contentOrNull
                        ?: obj["last_updated"]?.jsonPrimitive?.contentOrNull
                    val millis = runCatching { Instant.parse(lastChanged).toEpochMilli() }.getOrNull()
                        ?: return@mapNotNull null
                    millis to state
                }
            }
        }

    suspend fun cameraSnapshot(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = if (entityId.startsWith("camera.")) {
            "/api/camera_proxy/$entityId"
        } else {
            "/api/image_proxy/$entityId"
        }
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }

    /** HLS playlist path from the stream integration (`camera/stream`). Null if unavailable. */
    suspend fun cameraHlsUrl(entityId: String): String? {
        if (!entityId.startsWith("camera.") || baseUrl.isBlank()) return null
        return try {
            withTimeout(15_000) {
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
        val url = if (path.startsWith("http")) path else baseUrl + path
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }

    suspend fun calendarEvents(entityId: String, start: Instant, end: Instant): List<HaCalendarEvent> {
        val startEnc = URLEncoder.encode(start.toString(), "UTF-8")
        val endEnc = URLEncoder.encode(end.toString(), "UTF-8")
        val root = restGet("/api/calendars/$entityId?start=$startEnc&end=$endEnc") as? JsonArray
            ?: return emptyList()
        return root.mapNotNull { parseCalendarEvent(it as? JsonObject ?: return@mapNotNull null, entityId) }
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

    suspend fun resolveMediaUrl(path: String): String? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
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
        val url = resolveMediaUrl(path) ?: return null
        return authenticatedBytes(url) ?: authenticatedBytes(path)
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
        return HaCalendarEvent(
            entityId = "calendar.llm_vision_timeline",
            summary = summary,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            start = startBound.first,
            end = endBound.first,
            allDay = false,
            startDate = startBound.second,
            endDate = endBound.second,
            uid = obj["uid"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull,
            keyFrame = obj["key_frame"]?.jsonPrimitive?.contentOrNull,
            cameraName = obj["camera_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["cameraName"]?.jsonPrimitive?.contentOrNull,
            category = obj["category"]?.jsonPrimitive?.contentOrNull,
            label = obj["label"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseTimeBound(element: JsonElement?): Pair<Instant?, LocalDate?> {
        if (element == null || element is JsonNull) return null to null
        if (element is JsonPrimitive) {
            return parseInstantOrDate(element) to null
        }
        val obj = element as? JsonObject ?: return null to null
        obj["dateTime"]?.jsonPrimitive?.let { return parseInstantOrDate(it) to null }
        obj["date"]?.jsonPrimitive?.contentOrNull?.let { text ->
            val date = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
            return null to date
        }
        return parseInstantOrDate(obj["start"]?.jsonPrimitive ?: obj["value"]?.jsonPrimitive) to null
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
        val text = primitive.contentOrNull ?: return null
        return runCatching { Instant.parse(text) }.getOrElse {
            runCatching { Instant.parse(text.replace(" ", "T")) }.getOrNull()
        }
    }

    suspend fun weatherForecast(entityId: String): List<JsonObject> {
        val result = command {
            put("type", "weather/get_forecasts")
            put("entity_id", entityId)
            put("forecast_type", "daily")
        }
        val forecasts = result.jsonObject[entityId]?.jsonObject?.get("forecast") as? JsonArray
        return forecasts?.mapNotNull { it as? JsonObject }.orEmpty()
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

    suspend fun reconnectLoop() {
        var attempt = 0
        while (true) {
            try {
                if (_connection.value !is ConnectionState.Connected && baseUrl.isNotBlank() && token.isNotBlank()) {
                    openSocket()
                }
                delay(if (_connection.value is ConnectionState.Connected) 15_000 else (2000L * (attempt + 1)).coerceAtMost(15_000))
                if (_connection.value is ConnectionState.Connected) {
                    attempt = 0
                } else {
                    attempt++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(3000)
            }
        }
    }
}
