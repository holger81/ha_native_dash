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
)

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
