package dev.holgerendt.hanative.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun blankArtRowsDoNotShrinkUpcomingCoverQuota() {
        fun item(name: String, image: String?) = buildJsonObject {
            put("name", name)
            if (image != null) {
                put(
                    "media_item",
                    buildJsonObject {
                        put("name", name)
                        put("image", buildJsonObject { put("url", image) })
                    },
                )
            }
        }
        val dest = linkedSetOf<String>()
        collectUpcomingCoverUrls(
            rows = listOf(
                item("no-art", null),
                item("a", "https://cdn.example/a.jpg"),
                item("also-blank", null),
                item("b", "https://cdn.example/b.jpg"),
                item("c", "https://cdn.example/c.jpg"),
            ),
            dest = dest,
            limit = 3,
        )
        assertEquals(
            listOf(
                "https://cdn.example/a.jpg",
                "https://cdn.example/b.jpg",
                "https://cdn.example/c.jpg",
            ),
            dest.toList(),
        )
    }

    @Test
    fun stableOutpaintKeyIgnoresSizeQueryOnMassProxy() {
        assertEquals(
            "mass:abc-123",
            stableOutpaintCoverKey("mass-imageproxy://abc-123"),
        )
        assertEquals(
            "mass:abc-123",
            stableOutpaintCoverKey("mass-imageproxy://abc-123?size=512"),
        )
        assertEquals(
            stableOutpaintCoverKey("/api/image_proxy/foo?token=1"),
            stableOutpaintCoverKey("/api/image_proxy/foo?token=2"),
        )
    }
}
