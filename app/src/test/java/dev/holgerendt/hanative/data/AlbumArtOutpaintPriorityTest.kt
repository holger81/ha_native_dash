package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumArtOutpaintPriorityTest {
    @Test
    fun prefersCurrentBeforeUpcoming() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { false },
        )
        assertEquals("now", next)
    }

    @Test
    fun prefersFirstUncachedUpcomingAfterCurrentWarm() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { it == "now" || it == "a" },
        )
        assertEquals("b", next)
    }

    @Test
    fun skipsWhenAllWarm() {
        val next = nextOutpaintTarget(
            upcoming = listOf("a", "b"),
            current = "now",
            isCached = { true },
        )
        assertNull(next)
    }

    @Test
    fun upcomingWhenNoCurrent() {
        val next = nextOutpaintTarget(
            upcoming = listOf("next1"),
            current = null,
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

    @Test
    fun playlistPlanCapsAtTenIncludingCurrent() {
        val plan = playlistOutpaintPlan(
            currentCover = "now",
            upcomingCovers = (1..12).map { "$it" },
        )
        assertEquals("now", plan.current)
        assertEquals((1..9).map { "$it" }, plan.upcoming)
        assertEquals(10, (listOfNotNull(plan.current) + plan.upcoming).size)
    }

    @Test
    fun playlistPlanAllowsTenUpcomingWhenIdle() {
        val plan = playlistOutpaintPlan(
            currentCover = null,
            upcomingCovers = (1..12).map { "$it" },
        )
        assertNull(plan.current)
        assertEquals((1..10).map { "$it" }, plan.upcoming)
    }

    @Test
    fun playlistPlanDropsDuplicateCurrentFromUpcoming() {
        val plan = playlistOutpaintPlan(
            currentCover = "now",
            upcomingCovers = listOf("now", "1", "2"),
        )
        assertEquals(listOf("1", "2"), plan.upcoming)
    }
}
