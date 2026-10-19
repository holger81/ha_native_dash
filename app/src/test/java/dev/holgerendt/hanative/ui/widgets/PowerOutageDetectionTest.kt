package dev.holgerendt.hanative.ui.widgets

import dev.holgerendt.hanative.data.EntityState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerOutageDetectionTest {
    private val outage = "binary_sensor.power_outage"

    private fun states(vararg pairs: Pair<String, String>): Map<String, EntityState> =
        pairs.associate { (id, state) -> id to EntityState(entityId = id, state = state) }

    @Test
    fun trustsHaSensorOn() {
        assertTrue(isPowerOutage(states(outage to "on")))
    }

    @Test
    fun trustsHaSensorOff() {
        assertFalse(isPowerOutage(states(outage to "off")))
    }

    @Test
    fun unavailableOrMissingIsNotOutage() {
        assertFalse(isPowerOutage(states(outage to "unavailable")))
        assertFalse(isPowerOutage(emptyMap()))
    }
}
