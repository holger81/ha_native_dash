package dev.holgerendt.hanative.ui.widgets

import dev.holgerendt.hanative.data.EntityState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerOutageDetectionTest {
    private val enpower = "binary_sensor.enpower_122216008760_grid_status"
    private val emporia = "binary_sensor.power_outage"
    private val net = "sensor.envoy_202234122877_current_net_power_consumption"
    private val discharging = "binary_sensor.envoy_battery_discharging"

    private fun states(vararg pairs: Pair<String, String>): Map<String, EntityState> =
        pairs.associate { (id, state) -> id to EntityState(entityId = id, state = state) }

    @Test
    fun enpowerOnSuppressesEmporiaFalsePositive() {
        assertFalse(
            isPowerOutage(
                states(
                    enpower to "on",
                    emporia to "on",
                    discharging to "on",
                    net to "0.03",
                ),
            ),
        )
    }

    @Test
    fun enpowerOffMeansOutageEvenIfEmporiaOff() {
        assertTrue(
            isPowerOutage(
                states(
                    enpower to "off",
                    emporia to "off",
                    net to "unavailable",
                ),
            ),
        )
    }

    @Test
    fun emporiaUsedWhenEnpowerUnknown() {
        assertTrue(
            isPowerOutage(
                states(
                    enpower to "unavailable",
                    emporia to "on",
                ),
            ),
        )
        assertFalse(
            isPowerOutage(
                states(
                    enpower to "unavailable",
                    emporia to "off",
                    discharging to "on",
                    net to "0.1",
                ),
            ),
        )
    }

    @Test
    fun legacyUnavailableNetWhileDischarging() {
        assertTrue(
            isPowerOutage(
                states(
                    discharging to "on",
                    net to "unavailable",
                ),
            ),
        )
        assertFalse(
            isPowerOutage(
                states(
                    discharging to "off",
                    net to "unavailable",
                ),
            ),
        )
    }
}
