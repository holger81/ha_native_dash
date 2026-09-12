package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumArtOutpaintPriorityTest {
    @Test
    fun prefersFirstUncachedUpcoming() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { it == "a" },
        )
        assertEquals("b", next)
    }

    @Test
    fun backfillsCurrentWhenUpcomingCached() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { it == "a" || it == "b" },
        )
        assertEquals("now", next)
    }

    @Test
    fun skipsUpcomingWhenAllWarmAndCurrentWarm() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { true },
        )
        assertNull(next)
    }

    @Test
    fun usesUpcomingBeforeCurrentEvenIfCurrentUncached() {
        val next = nextOutpaintTarget(
            upcoming = listOf("next1"),
            current = "now",
            isCached = { false },
        )
        assertEquals("next1", next)
    }

    @Test
    fun currentOnlyWhenNoUpcoming() {
        val next = nextOutpaintTarget(
            upcoming = emptyList(),
            current = "now",
            isCached = { false },
        )
        assertEquals("now", next)
    }
}
