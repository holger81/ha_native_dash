package dev.holgerendt.hanative.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMediaResolveTest {
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
    }
}
