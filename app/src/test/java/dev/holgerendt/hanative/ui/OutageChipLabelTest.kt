package dev.holgerendt.hanative.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OutageChipLabelTest {
    @Test fun gridShowsKwWhenEnvoyIsUp() {
        assertEquals("1.25kW", gridStatusChipLabel(1.25, "1.25", discharging = false))
    }

    @Test fun gridShowsOutageWhenDischargingAndNetUnknown() {
        assertEquals(
            "Outage",
            gridStatusChipLabel(null, "unavailable", discharging = true),
        )
    }

    @Test fun gridShowsOfflineWhenNetUnavailableWithoutBattery() {
        assertEquals(
            "Offline",
            gridStatusChipLabel(null, "unavailable", discharging = false),
        )
    }

    @Test fun batteryPrefersRuntimeTemplateText() {
        assertEquals(
            "6h 40m",
            batteryStatusChipLabel(
                runtimeText = "6h 40m",
                storedWh = 4401.0,
                reserveWh = null,
                loadW = 1182.0,
                socPercent = null,
                capacityWh = 20160.0,
                discharging = true,
            ),
        )
    }

    @Test fun batteryFallsBackToEstimatedRuntimeDuringOutage() {
        assertEquals(
            "3h 43m",
            batteryStatusChipLabel(
                runtimeText = "unavailable",
                storedWh = 4401.0,
                reserveWh = null,
                loadW = 1182.0,
                socPercent = null,
                capacityWh = 20160.0,
                discharging = true,
            ),
        )
    }

    @Test fun batteryFallsBackToSocWhenRuntimeCannotBeEstimated() {
        assertEquals(
            "22%",
            batteryStatusChipLabel(
                runtimeText = "unavailable",
                storedWh = 4401.0,
                reserveWh = null,
                loadW = 50.0,
                socPercent = null,
                capacityWh = 20160.0,
                discharging = true,
            ),
        )
    }

    @Test fun batteryShowsOnBatteryWhenNoNumbersRemain() {
        assertEquals(
            "On battery",
            batteryStatusChipLabel(
                runtimeText = "unavailable",
                storedWh = null,
                reserveWh = null,
                loadW = null,
                socPercent = null,
                capacityWh = null,
                discharging = true,
            ),
        )
    }
}
