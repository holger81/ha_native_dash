package dev.holgerendt.hanative.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class MusicAssistantPlayer(
    val entityId: String,
    val name: String,
    val massPlayerType: String? = null,
    /** Music Assistant player/queue id when matched via the MA API. */
    val massPlayerId: String? = null,
)

data class MassMediaItem(
    val uri: String,
    val name: String,
    val mediaType: String,
    val imageUrl: String? = null,
    val subtitle: String? = null,
    val provider: String? = null,
    val itemId: String? = null,
)

data class MassRecommendationSection(
    val name: String,
    val provider: String? = null,
    val items: List<MassMediaItem> = emptyList(),
)

data class MassSearchResults(
    val artists: List<MassMediaItem> = emptyList(),
    val albums: List<MassMediaItem> = emptyList(),
    val tracks: List<MassMediaItem> = emptyList(),
    val playlists: List<MassMediaItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

data class MusicAssistantQueueItem(
    val queueItemId: String? = null,
    val name: String,
    val durationSec: Int? = null,
    val imageUrl: String? = null,
    val artists: String? = null,
    val album: String? = null,
    val mediaUri: String? = null,
    val streamTitle: String? = null,
)

data class MusicAssistantQueue(
    val queueId: String? = null,
    val name: String? = null,
    val itemCount: Int = 0,
    val currentIndex: Int? = null,
    val elapsedSec: Double? = null,
    val shuffle: Boolean = false,
    val repeatMode: String? = null,
    val current: MusicAssistantQueueItem? = null,
    val next: MusicAssistantQueueItem? = null,
)

fun EntityState.isMusicAssistantPlayer(): Boolean {
    if (!entityId.startsWith("media_player.")) return false
    return attrString("mass_player_type") != null ||
        attributes.containsKey("active_queue") ||
        attrString("app_id")?.contains("music_assistant", ignoreCase = true) == true
}

fun EntityState.mediaTitle(): String? =
    attrString("media_title")?.takeIf { it.isNotBlank() }
        ?: attrString("media_content_id")?.takeIf { it.isNotBlank() }

fun EntityState.mediaArtist(): String? =
    attrString("media_artist")?.takeIf { it.isNotBlank() }

fun EntityState.mediaAlbum(): String? =
    attrString("media_album_name")?.takeIf { it.isNotBlank() }

fun EntityState.mediaDurationSec(): Double? =
    attrDouble("media_duration")?.takeIf { it.isFinite() && it > 0 }

fun EntityState.mediaPositionSec(): Double? =
    attrDouble("media_position")?.takeIf { it.isFinite() && it >= 0 }

fun EntityState.mediaPositionUpdatedAtMs(): Long? {
    val raw = attributes["media_position_updated_at"] ?: return null
    val primitive = raw as? JsonPrimitive ?: return null
    val text = primitive.contentOrNull ?: return null
    return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
}

fun EntityState.volumeLevel(): Float? =
    attrDouble("volume_level")?.toFloat()?.coerceIn(0f, 1f)

fun EntityState.isShuffleOn(): Boolean =
    attributes["shuffle"].toBooleanOrNull() == true

fun EntityState.repeatMode(): String =
    attrString("repeat")?.lowercase() ?: "off"

fun EntityState.supportsFeature(feature: Long): Boolean {
    val supported = attributes["supported_features"] ?: return true
    val bits = when (supported) {
        is JsonPrimitive -> supported.longOrNull ?: supported.contentOrNull?.toLongOrNull()
        else -> null
    } ?: return true
    return bits and feature != 0L
}

private fun JsonElement?.toBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return when (primitive.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

fun parseMusicAssistantQueue(response: JsonElement?, entityId: String): MusicAssistantQueue? {
    val root = response as? JsonObject ?: return null
    val payload = root[entityId]?.jsonObject
        ?: root["response"]?.jsonObject?.get(entityId)?.jsonObject
        ?: root["response"]?.jsonObject?.entries?.firstOrNull()?.value?.jsonObject
        ?: root.entries.firstOrNull { it.key.startsWith("media_player.") }?.value?.jsonObject
        ?: root.takeIf { it.containsKey("current_item") || it.containsKey("queue_id") }
    payload ?: return null
    return MusicAssistantQueue(
        queueId = payload["queue_id"]?.jsonPrimitive?.contentOrNull,
        name = payload["name"]?.jsonPrimitive?.contentOrNull,
        itemCount = payload["items"]?.jsonPrimitive?.intOrNull
            ?: payload["items"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0,
        currentIndex = payload["current_index"]?.jsonPrimitive?.intOrNull
            ?: payload["current_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        elapsedSec = payload["elapsed_time"]?.jsonPrimitive?.doubleOrNull
            ?: payload["elapsed_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        shuffle = payload["shuffle_enabled"].toBooleanOrNull() == true,
        repeatMode = payload["repeat_mode"]?.jsonPrimitive?.contentOrNull,
        current = parseQueueItem(payload["current_item"]),
        next = parseQueueItem(payload["next_item"]),
    )
}

private fun parseQueueItem(element: JsonElement?): MusicAssistantQueueItem? {
    val obj = element as? JsonObject ?: return null
    val media = obj["media_item"] as? JsonObject
    val artists = (media?.get("artists") as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
    val album = (media?.get("album") as? JsonObject)
        ?.get("name")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull
        ?: media?.get("name")?.jsonPrimitive?.contentOrNull
        ?: return null
    return MusicAssistantQueueItem(
        queueItemId = obj["queue_item_id"]?.jsonPrimitive?.contentOrNull,
        name = name,
        durationSec = obj["duration"]?.jsonPrimitive?.intOrNull
            ?: obj["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        imageUrl = media?.get("image")?.jsonPrimitive?.contentOrNull
            ?: media?.get("image_url")?.jsonPrimitive?.contentOrNull,
        artists = artists,
        album = album,
        mediaUri = media?.get("uri")?.jsonPrimitive?.contentOrNull,
        streamTitle = obj["stream_title"]?.jsonPrimitive?.contentOrNull,
    )
}

fun formatMediaClock(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0) return "0:00"
    val total = seconds.toInt()
    val mins = total / 60
    val secs = total % 60
    return "%d:%02d".format(mins, secs)
}

fun parseMassMediaItem(element: JsonElement?): MassMediaItem? {
    val obj = element as? JsonObject ?: return null
    val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "track"
    if (mediaType == "folder") return null
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val artists = (obj["artists"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
    val owner = obj["owner"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val version = obj["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val subtitle = when (mediaType) {
        "track" -> artists
        "album" -> listOfNotNull(artists, version).joinToString(" · ").ifBlank { null }
        "playlist" -> owner ?: mediaType.replaceFirstChar { it.uppercase() }
        "artist" -> "Artist"
        else -> mediaType.replaceFirstChar { it.uppercase() }
    }
    return MassMediaItem(
        uri = uri,
        name = name,
        mediaType = mediaType,
        imageUrl = extractMassImageUrl(obj),
        subtitle = subtitle,
        provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
        itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull,
    )
}

fun parseMassRecommendationSections(element: JsonElement?): List<MassRecommendationSection> {
    val rows = element as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val items = (obj["items"] as? JsonArray)
            ?.mapNotNull(::parseMassMediaItem)
            .orEmpty()
        if (items.isEmpty()) return@mapNotNull null
        MassRecommendationSection(
            name = name,
            provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
            items = items,
        )
    }
}

fun parseMassSearchResults(element: JsonElement?): MassSearchResults {
    val obj = element as? JsonObject ?: return MassSearchResults()
    fun list(key: String): List<MassMediaItem> =
        (obj[key] as? JsonArray)?.mapNotNull(::parseMassMediaItem).orEmpty()
    return MassSearchResults(
        artists = list("artists"),
        albums = list("albums"),
        tracks = list("tracks"),
        playlists = list("playlists"),
    )
}

fun extractMassImageUrl(obj: JsonObject): String? {
    when (val image = obj["image"]) {
        is JsonPrimitive -> image.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        is JsonObject -> {
            image["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            image["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        else -> Unit
    }
    val metadata = obj["metadata"] as? JsonObject
    val images = metadata?.get("images") as? JsonArray
    val first = images?.firstOrNull() as? JsonObject
    return first?.get("path")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: first?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

fun normalizeMusicPlayerName(name: String): String =
    name.lowercase()
        .replace(Regex("['’]"), "")
        .replace(Regex("[^a-z0-9]+"), "")

fun matchMassPlayerId(haName: String, massPlayers: List<Pair<String, String>>): String? {
    val target = normalizeMusicPlayerName(haName)
    if (target.isBlank()) return null
    massPlayers.firstOrNull { normalizeMusicPlayerName(it.second) == target }?.first?.let { return it }
    massPlayers.firstOrNull {
        val other = normalizeMusicPlayerName(it.second)
        other.contains(target) || target.contains(other)
    }?.first?.let { return it }
    return null
}
