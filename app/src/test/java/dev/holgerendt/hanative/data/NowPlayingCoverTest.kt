package dev.holgerendt.hanative.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingCoverTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun prefersQueueArtOverStaleEntityPicture() {
        val queue = MusicAssistantQueueItem(
            name = "Hit the Wall",
            imageUrl = "mass-imageproxy://track-art",
            artists = "Gracie Abrams",
        )
        assertEquals(
            "mass-imageproxy://track-art",
            resolveNowPlayingCover(queue, "/api/media_player_proxy/media_player.office?cache=old"),
        )
    }

    @Test
    fun fallsBackToEntityPictureWhenQueueHasNoArt() {
        val queue = MusicAssistantQueueItem(name = "Hit the Wall", imageUrl = null)
        assertEquals(
            "/api/media_player_proxy/x",
            resolveNowPlayingCover(queue, "/api/media_player_proxy/x"),
        )
        assertNull(resolveNowPlayingCover(null, null))
    }

    @Test
    fun parseQueueItemPrefersMediaItemImageOverRowImage() {
        val raw = buildJsonObject {
            put("name", "Hit the Wall")
            putJsonObject("image") {
                put("path", "https://cdn.example/station.jpg")
                put("provider", "radio")
            }
            putJsonObject("media_item") {
                put("name", "Hit the Wall")
                putJsonObject("image") {
                    put("path", "https://cdn.example/track.jpg")
                    put("provider", "apple_music")
                }
            }
        }
        val item = parseQueueItem(raw)!!
        assertEquals("https://cdn.example/track.jpg", item.imageUrl)
    }

    @Test
    fun parseQueueItemUsesRowImageWhenMediaItemHasNone() {
        val raw = json.decodeFromString<JsonObject>(
            """
            {
              "name": "Station",
              "image": { "path": "https://cdn.example/station.jpg", "provider": "radio" }
            }
            """.trimIndent(),
        )
        val item = parseQueueItem(raw)!!
        assertEquals("https://cdn.example/station.jpg", item.imageUrl)
    }
}
