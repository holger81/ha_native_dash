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
    /** Mass volume 0–100. */
    val massVolume: Int? = null,
    /** Mass group/sync volume 0–100. */
    val massGroupVolume: Int? = null,
    val groupMemberIds: List<String> = emptyList(),
    val syncedToId: String? = null,
    val canGroupWithIds: List<String> = emptyList(),
    val elapsedSec: Double? = null,
    val elapsedUpdatedAtMs: Long? = null,
    val massPlaybackState: String? = null,
) {
    val groupRootId: String?
        get() = syncedToId?.takeIf { it.isNotBlank() }
            ?: massPlayerId?.takeIf { groupMemberIds.size > 1 }
            ?: massPlayerId

    val isGrouped: Boolean
        get() = !syncedToId.isNullOrBlank() || groupMemberIds.size > 1
}

data class MassPlayerInfo(
    val playerId: String,
    val name: String,
    val volume: Int? = null,
    val groupVolume: Int? = null,
    val groupMemberIds: List<String> = emptyList(),
    val syncedToId: String? = null,
    val canGroupWithIds: List<String> = emptyList(),
    val elapsedSec: Double? = null,
    val elapsedUpdatedAtMs: Long? = null,
    val playbackState: String? = null,
)

data class MassMediaItem(
    val uri: String,
    val name: String,
    val mediaType: String,
    val imageUrl: String? = null,
    val subtitle: String? = null,
    val provider: String? = null,
    val itemId: String? = null,
    /** Browse path for folders / Apple Music directory nodes. */
    val browsePath: String? = null,
) {
    val isFolder: Boolean
        get() = mediaType.equals("folder", ignoreCase = true) ||
            mediaType.equals("directory", ignoreCase = true)

    val canBrowse: Boolean
        get() = !browsePath.isNullOrBlank() && (isFolder || uri.isBlank())

    val canPlay: Boolean
        get() = uri.isNotBlank() && !isFolder
}

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
    val elapsedUpdatedAtMs: Long? = null,
    val shuffle: Boolean = false,
    val repeatMode: String? = null,
    val playbackState: String? = null,
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
        name = payload["name"]?.jsonPrimitive?.contentOrNull
            ?: payload["display_name"]?.jsonPrimitive?.contentOrNull,
        itemCount = payload["items"]?.jsonPrimitive?.intOrNull
            ?: payload["items"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0,
        currentIndex = payload["current_index"]?.jsonPrimitive?.intOrNull
            ?: payload["current_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        elapsedSec = payload["elapsed_time"]?.jsonPrimitive?.doubleOrNull
            ?: payload["elapsed_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        elapsedUpdatedAtMs = payload["elapsed_time_last_updated"]?.jsonPrimitive?.doubleOrNull
            ?.let { (it * 1000.0).toLong() }
            ?: payload["elapsed_time_last_updated"]?.jsonPrimitive?.longOrNull,
        shuffle = payload["shuffle_enabled"].toBooleanOrNull() == true,
        repeatMode = payload["repeat_mode"]?.jsonPrimitive?.contentOrNull,
        playbackState = payload["state"]?.jsonPrimitive?.contentOrNull,
        current = parseQueueItem(payload["current_item"]),
        next = parseQueueItem(payload["next_item"]),
    )
}

fun mergeMusicAssistantQueues(
    fromHa: MusicAssistantQueue?,
    fromMass: MusicAssistantQueue?,
): MusicAssistantQueue? {
    if (fromHa == null) return fromMass
    if (fromMass == null) return fromHa
    return fromMass.copy(
        shuffle = fromHa.shuffle,
        repeatMode = fromHa.repeatMode ?: fromMass.repeatMode,
    )
}

fun parseMassPlayerInfo(element: JsonElement?): MassPlayerInfo? {
    val obj = element as? JsonObject ?: return null
    val id = obj["player_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = obj["display_name"]?.jsonPrimitive?.contentOrNull
        ?: obj["name"]?.jsonPrimitive?.contentOrNull
        ?: id
    fun stringList(key: String): List<String> =
        (obj[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
            .orEmpty()
    val elapsed = obj["elapsed_time"]?.jsonPrimitive?.doubleOrNull
        ?: obj["elapsed_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    val elapsedUpdated = obj["elapsed_time_last_updated"]?.jsonPrimitive?.doubleOrNull
        ?.let { (it * 1000.0).toLong() }
    return MassPlayerInfo(
        playerId = id,
        name = name,
        volume = obj["volume_level"]?.jsonPrimitive?.intOrNull
            ?: obj["volume_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: obj["volume_level"]?.jsonPrimitive?.doubleOrNull?.toInt(),
        groupVolume = obj["group_volume"]?.jsonPrimitive?.intOrNull
            ?: obj["group_volume"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: obj["group_volume"]?.jsonPrimitive?.doubleOrNull?.toInt(),
        groupMemberIds = stringList("group_members"),
        syncedToId = obj["synced_to"]?.jsonPrimitive?.contentOrNull,
        canGroupWithIds = stringList("can_group_with"),
        elapsedSec = elapsed,
        elapsedUpdatedAtMs = elapsedUpdated,
        playbackState = obj["playback_state"]?.jsonPrimitive?.contentOrNull
            ?: obj["state"]?.jsonPrimitive?.contentOrNull,
    )
}

fun matchMassPlayerInfo(haName: String, massPlayers: List<MassPlayerInfo>): MassPlayerInfo? {
    val target = normalizeMusicPlayerName(haName)
    if (target.isBlank()) return null
    massPlayers.firstOrNull { normalizeMusicPlayerName(it.name) == target }?.let { return it }
    return massPlayers.firstOrNull {
        val other = normalizeMusicPlayerName(it.name)
        other.contains(target) || target.contains(other)
    }
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
            ?: obj["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: obj["duration"]?.jsonPrimitive?.doubleOrNull?.toInt(),
        imageUrl = extractMassImageUrl(obj)
            ?: media?.let(::extractMassImageUrl),
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
        browsePath = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    )
}

/** Includes folders so Apple Music browse can navigate directory nodes. */
fun parseMassBrowseItem(element: JsonElement?): MassMediaItem? {
    val obj = element as? JsonObject ?: return null
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (uri == null && path == null) return null
    val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull
        ?: if (uri == null) "folder" else "track"
    val artists = (obj["artists"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
    val owner = obj["owner"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val subtitle = when {
        mediaType.equals("folder", ignoreCase = true) -> "Browse"
        mediaType == "track" -> artists
        mediaType == "album" -> artists
        mediaType == "playlist" -> owner ?: "Playlist"
        mediaType == "artist" -> "Artist"
        else -> mediaType.replaceFirstChar { it.uppercase() }
    }
    return MassMediaItem(
        uri = uri ?: path.orEmpty(),
        name = name,
        mediaType = mediaType,
        imageUrl = extractMassImageUrl(obj),
        subtitle = subtitle,
        provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
        itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull,
        browsePath = path,
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

private val APOSTROPHE_REGEX = Regex("['’]")
private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")

fun normalizeMusicPlayerName(name: String): String =
    name.lowercase()
        .replace(APOSTROPHE_REGEX, "")
        .replace(NON_ALPHANUMERIC_REGEX, "")
