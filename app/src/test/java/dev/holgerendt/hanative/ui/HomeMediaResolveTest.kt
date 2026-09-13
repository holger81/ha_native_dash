package dev.holgerendt.hanative.ui

import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.MusicAssistantPlayer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMediaResolveTest {
    @Test
    fun prefersPlayingTvOverPlayingMusic() {
        val office = MusicAssistantPlayer(entityId = "media_player.office", name = "Office")
        val snap = resolveHomeMediaSession(
            players = listOf(office),
            states = mapOf(
                "media_player.office" to EntityState(
                    entityId = "media_player.office",
                    state = "playing",
                    attributes = mapOf(
                        "mass_player_type" to JsonPrimitive("player"),
                        "media_title" to JsonPrimitive("Song"),
                    ),
                ),
                APPLE_TV_ENTITY to EntityState(
                    entityId = APPLE_TV_ENTITY,
                    state = "playing",
                    attributes = mapOf(
                        "media_title" to JsonPrimitive("NCIS"),
                        "app_name" to JsonPrimitive("Paramount+"),
                    ),
                ),
            ),
            browseSelectedId = "media_player.office",
            queue = null,
        )
        assertEquals(HomeMediaKind.Tv, snap.kind)
        assertEquals("NCIS", snap.title)
        assertEquals(APPLE_TV_ENTITY, snap.entityId)
    }

    @Test
    fun prefersPlayingMusicOverPausedTv() {
        val snap = resolveHomeMedia(
            musicState = "playing",
            musicTitle = "Song",
            musicArtist = "Artist",
            musicArt = "/art",
            musicRoom = "Office",
            musicEntityId = "media_player.office",
            musicDuration = 100.0,
            musicPosition = 10.0,
            musicPositionUpdatedAtMs = null,
            musicVolume = 0.4f,
            appleState = "paused",
            appleTitle = "Show",
            appleApp = "TV",
            appleArt = null,
            appleDuration = null,
            applePosition = null,
            applePositionUpdatedAtMs = null,
            appleVolume = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertTrue(snap.playing)
        assertEquals("Song", snap.title)
        assertEquals("media_player.office", snap.entityId)
    }

    @Test
    fun pausedTvAloneIsIdle() {
        val snap = resolveHomeMedia(
            musicState = "idle",
            musicTitle = null,
            musicArtist = null,
            musicArt = null,
            musicRoom = null,
            musicEntityId = null,
            musicDuration = null,
            musicPosition = null,
            musicPositionUpdatedAtMs = null,
            musicVolume = null,
            appleState = "paused",
            appleTitle = "Show",
            appleApp = "TV",
            appleArt = "/tv",
            appleDuration = 3600.0,
            applePosition = 100.0,
            applePositionUpdatedAtMs = null,
            appleVolume = 0.5f,
        )
        assertEquals(HomeMediaKind.Idle, snap.kind)
        assertFalse(snap.playing)
    }

    @Test
    fun prefersPlayingMusicOverIdleTv() {
        val snap = resolveHomeMedia(
            musicState = "playing",
            musicTitle = "Song",
            musicArtist = "Artist",
            musicArt = "/art",
            musicRoom = "Office",
            musicEntityId = "media_player.office",
            musicDuration = 100.0,
            musicPosition = 10.0,
            musicPositionUpdatedAtMs = null,
            musicVolume = 0.4f,
            appleState = "standby",
            appleTitle = null,
            appleApp = null,
            appleArt = null,
            appleDuration = null,
            applePosition = null,
            applePositionUpdatedAtMs = null,
            appleVolume = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertTrue(snap.playing)
        assertEquals("Song", snap.title)
        assertEquals("media_player.office", snap.entityId)
    }

    @Test
    fun prefersPlayingTvWhenMusicIdle() {
        val snap = resolveHomeMedia(
            musicState = "idle",
            musicTitle = null,
            musicArtist = null,
            musicArt = null,
            musicRoom = null,
            musicEntityId = null,
            musicDuration = null,
            musicPosition = null,
            musicPositionUpdatedAtMs = null,
            musicVolume = null,
            appleState = "playing",
            appleTitle = "Severance",
            appleApp = "Apple TV",
            appleArt = "/tv",
            appleDuration = 3600.0,
            applePosition = 100.0,
            applePositionUpdatedAtMs = System.currentTimeMillis(),
            appleVolume = 0.5f,
        )
        assertEquals(HomeMediaKind.Tv, snap.kind)
        assertEquals("Severance", snap.title)
        assertTrue(snap.playing)
        assertEquals(APPLE_TV_ENTITY, snap.entityId)
        assertFalse(snap.supportsPrevious)
        assertFalse(snap.supportsNext)
    }

    @Test
    fun idleWhenNeitherActive() {
        val snap = resolveHomeMedia(
            musicState = "off",
            musicTitle = null,
            musicArtist = null,
            musicArt = null,
            musicRoom = null,
            musicEntityId = "media_player.office",
            musicDuration = null,
            musicPosition = null,
            musicPositionUpdatedAtMs = null,
            musicVolume = null,
            appleState = "standby",
            appleTitle = null,
            appleApp = null,
            appleArt = null,
            appleDuration = null,
            applePosition = null,
            applePositionUpdatedAtMs = null,
            appleVolume = null,
        )
        assertEquals(HomeMediaKind.Idle, snap.kind)
        assertFalse(snap.playing)
    }

    @Test
    fun pausedMusicCountsAsActive() {
        val snap = resolveHomeMedia(
            musicState = "paused",
            musicTitle = "Ballad",
            musicArtist = "X",
            musicArt = null,
            musicRoom = "Kitchen",
            musicEntityId = "media_player.kitchen",
            musicDuration = 200.0,
            musicPosition = 50.0,
            musicPositionUpdatedAtMs = null,
            musicVolume = 0.2f,
            appleState = "idle",
            appleTitle = null,
            appleApp = null,
            appleArt = null,
            appleDuration = null,
            applePosition = null,
            applePositionUpdatedAtMs = null,
            appleVolume = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertTrue(snap.paused)
        assertEquals("media_player.kitchen", snap.entityId)
    }

    @Test
    fun prefersPlayingPlayerOverIdleBrowseSelection() {
        val office = MusicAssistantPlayer(entityId = "media_player.office", name = "Office")
        val kitchen = MusicAssistantPlayer(
            entityId = "media_player.kitchen",
            name = "Kitchen",
            massPlaybackState = "playing",
        )
        val snap = resolveHomeMediaSession(
            players = listOf(office, kitchen),
            states = mapOf(
                "media_player.office" to EntityState(
                    entityId = "media_player.office",
                    state = "idle",
                    attributes = mapOf("mass_player_type" to JsonPrimitive("player")),
                ),
                "media_player.kitchen" to EntityState(
                    entityId = "media_player.kitchen",
                    state = "playing",
                    attributes = mapOf(
                        "mass_player_type" to JsonPrimitive("player"),
                        "media_title" to JsonPrimitive("Kitchen Track"),
                        "media_artist" to JsonPrimitive("Band"),
                    ),
                ),
                APPLE_TV_ENTITY to EntityState(APPLE_TV_ENTITY, "standby"),
            ),
            browseSelectedId = "media_player.office",
            queue = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertEquals("media_player.kitchen", snap.entityId)
        assertEquals("Kitchen Track", snap.title)
        assertEquals("Kitchen", snap.room)
        assertTrue(snap.playing)
    }

    @Test
    fun appleTvIsNotClassifiedAsMusic() {
        val appleAsPlayer = MusicAssistantPlayer(
            entityId = APPLE_TV_ENTITY,
            name = "Apple TV",
            massPlaybackState = "playing",
        )
        val snap = resolveHomeMediaSession(
            players = listOf(appleAsPlayer),
            states = mapOf(
                APPLE_TV_ENTITY to EntityState(
                    entityId = APPLE_TV_ENTITY,
                    state = "playing",
                    attributes = mapOf(
                        "media_title" to JsonPrimitive("Show"),
                        "app_name" to JsonPrimitive("TV"),
                        "mass_player_type" to JsonPrimitive("player"),
                    ),
                ),
            ),
            browseSelectedId = APPLE_TV_ENTITY,
            queue = null,
        )
        assertEquals(HomeMediaKind.Tv, snap.kind)
        assertEquals(APPLE_TV_ENTITY, snap.entityId)
    }

    @Test
    fun playingBeatsPausedAcrossPlayers() {
        val paused = MusicAssistantPlayer(entityId = "media_player.office", name = "Office")
        val playing = MusicAssistantPlayer(entityId = "media_player.kitchen", name = "Kitchen")
        val snap = resolveHomeMediaSession(
            players = listOf(paused, playing),
            states = mapOf(
                "media_player.office" to EntityState(
                    entityId = "media_player.office",
                    state = "paused",
                    attributes = mapOf(
                        "mass_player_type" to JsonPrimitive("player"),
                        "media_title" to JsonPrimitive("Paused Song"),
                    ),
                ),
                "media_player.kitchen" to EntityState(
                    entityId = "media_player.kitchen",
                    state = "playing",
                    attributes = mapOf(
                        "mass_player_type" to JsonPrimitive("player"),
                        "media_title" to JsonPrimitive("Live Song"),
                    ),
                ),
                APPLE_TV_ENTITY to EntityState(APPLE_TV_ENTITY, "idle"),
            ),
            browseSelectedId = "media_player.office",
            queue = null,
        )
        assertEquals("media_player.kitchen", snap.entityId)
        assertTrue(snap.playing)
        assertEquals("Live Song", snap.title)
    }

    @Test
    fun haPausedBeatsStaleMassPlaying() {
        val office = MusicAssistantPlayer(
            entityId = "media_player.office",
            name = "Office",
            massPlayerId = "office",
            massPlaybackState = "playing",
        )
        val snap = resolveHomeMediaSession(
            players = listOf(office),
            states = mapOf(
                "media_player.office" to EntityState(
                    entityId = "media_player.office",
                    state = "paused",
                    attributes = mapOf(
                        "mass_player_type" to JsonPrimitive("player"),
                        "media_title" to JsonPrimitive("Paused Song"),
                    ),
                ),
                APPLE_TV_ENTITY to EntityState(APPLE_TV_ENTITY, "standby"),
            ),
            browseSelectedId = "media_player.office",
            queue = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertFalse(snap.playing)
        assertTrue(snap.paused)
        assertEquals("Paused Song", snap.title)
    }

    @Test
    fun massOnlyPlayingStateIsRepresented() {
        val office = MusicAssistantPlayer(
            entityId = "media_player.office",
            name = "Office",
            massPlayerId = "office",
            massPlaybackState = "playing",
        )
        val snap = resolveHomeMediaSession(
            players = listOf(office),
            states = mapOf(
                "media_player.office" to EntityState(
                    entityId = "media_player.office",
                    state = "idle",
                    attributes = mapOf(
                        "media_title" to JsonPrimitive("Mass Track"),
                    ),
                ),
                APPLE_TV_ENTITY to EntityState(APPLE_TV_ENTITY, "off"),
            ),
            browseSelectedId = "media_player.kitchen",
            queue = null,
        )
        assertEquals(HomeMediaKind.Music, snap.kind)
        assertEquals("media_player.office", snap.entityId)
        assertTrue(snap.playing)
    }

    @Test
    fun groupedRoomLabelUsesMemberNames() {
        val root = MusicAssistantPlayer(
            entityId = "media_player.office",
            name = "Office",
            massPlayerId = "office",
            groupMemberIds = listOf("office", "kitchen"),
        )
        val kitchen = MusicAssistantPlayer(
            entityId = "media_player.kitchen",
            name = "Kitchen",
            massPlayerId = "kitchen",
            syncedToId = "office",
        )
        assertEquals("Office + Kitchen", formatPlayerRoom(root, listOf(root, kitchen)))
        assertEquals(
            "Office + 2",
            formatPlayerRoom(
                root.copy(groupMemberIds = listOf("office", "kitchen", "patio")),
                listOf(
                    root,
                    kitchen,
                    MusicAssistantPlayer(
                        entityId = "media_player.patio",
                        name = "Patio",
                        massPlayerId = "patio",
                    ),
                ),
            ),
        )
    }
}
