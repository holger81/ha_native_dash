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

    @Test fun keepsTotalRuntimeWhenReserveIsUnavailable() {
        val estimates = batteryRuntimeEstimates(storedWh = 4401.0, reserveWh = Double.NaN, loadW = 1182.0)
        assertEquals("—", estimates?.reserve)
        assertEquals("3h 43m", estimates?.total)
    }

    @Test fun headlineUsesStoredWhenReserveUnknown() {
        assertEquals("3h 43m", batteryHeadlineRuntime(storedWh = 4401.0, reserveWh = null, loadW = 1182.0))
        // (11420 - 4032) / 1110 ≈ 6.66h → 6h 39m with the shared formatter.
        assertEquals(
            "6h 39m",
            batteryHeadlineRuntime(storedWh = 11420.0, reserveWh = 4032.0, loadW = 1110.0),
        )
    }

    @Test fun formatsWholeHoursAndSubHourRemainders() {
        assertEquals("2h", formatRuntimeHours(2.0))
        assertEquals("25m", formatRuntimeHours(25.0 / 60.0))
        assertEquals("1h", formatRuntimeHours(59.6 / 60.0))
    }
}
