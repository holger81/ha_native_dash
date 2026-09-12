package dev.holgerendt.hanative.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonPresenceLabelTest {
    @Test
    fun homeLabel() {
        assertEquals("Home", personPresenceLabel(home = true, personState = "home", minutesRaw = "12"))
    }

    @Test
    fun awayWithDuration() {
        assertEquals("Away · 45m", personPresenceLabel(home = false, personState = "not_home", minutesRaw = "45"))
        assertEquals("Away · 3h", personPresenceLabel(home = false, personState = "away", minutesRaw = "180"))
        assertEquals("Away · 33d", personPresenceLabel(home = false, personState = "not_home", minutesRaw = "47558"))
    }

    @Test
    fun namedPlaceWithDuration() {
        assertEquals(
            "Work · 2h",
            personPresenceLabel(home = false, personState = "work", minutesRaw = "125"),
        )
    }

    @Test
    fun unknownWithoutDuration() {
        assertEquals("Away", personPresenceLabel(home = false, personState = "unknown", minutesRaw = null))
        assertEquals("Away", personPresenceLabel(home = false, personState = null, minutesRaw = "bad"))
    }
}
