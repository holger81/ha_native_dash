package dev.holgerendt.hanative.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRuntimeEstimateTest {
    @Test fun matchesHeadlineLoadBasisFromTheBatteryPills() {
        // Stored 11.42 kWh already includes the 4032 Wh reserve. Headline
        // sensor is (stored - reserve) / 1110 W ≈ 6h 40m, not stored / load.
        val estimates = batteryRuntimeEstimates(storedWh = 11420.0, reserveWh = 4032.0, loadW = 1110.0)
        assertEquals("3h 38m", estimates?.reserve)
        assertEquals("10h 17m", estimates?.total)
    }

    @Test fun hidesEstimatesWhenLoadIsBelowTheSensorThreshold() {
        assertNull(batteryRuntimeEstimates(storedWh = 11088.0, reserveWh = 4032.0, loadW = 99.0))
    }

    @Test fun formatsWholeHoursAndSubHourRemainders() {
        assertEquals("2h", formatRuntimeHours(2.0))
        assertEquals("25m", formatRuntimeHours(25.0 / 60.0))
        assertEquals("1h", formatRuntimeHours(59.6 / 60.0))
    }
}
