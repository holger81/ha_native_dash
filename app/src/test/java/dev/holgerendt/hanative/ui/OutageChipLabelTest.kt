package dev.holgerendt.hanative.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OutageChipLabelTest {
    @Test fun gridShowsKwWhenEnvoyIsUp() {
        assertEquals("1.25kW", gridStatusChipLabel(1.25, "1.25", gridOutage = false))
    }

    @Test fun gridShowsOutageWhenGridIsDownEvenIfNetReadsZero() {
        assertEquals(
            "Outage",
            gridStatusChipLabel(0.0, "0.0", gridOutage = true),
        )
    }

    @Test fun gridShowsOutageWhenNetUnavailableDuringOutage() {
        assertEquals(
            "Outage",
            gridStatusChipLabel(null, "unavailable", gridOutage = true),
        )
    }

    @Test fun gridShowsOfflineWhenNetUnavailableWithoutOutage() {
        assertEquals(
            "Offline",
            gridStatusChipLabel(null, "unavailable", gridOutage = false),
        )
    }

    @Test fun batteryPrefersRuntimeTemplateTextWhenGridIsUp() {
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
                gridOutage = false,
            ),
        )
    }

    @Test fun batteryIgnoresAboveReserveTemplateDuringOutage() {
        // Live HA template reports (stored - reserve) / load ≈ 50m while islanded;
        // chip must use total pack time from helper + load instead.
        assertEquals(
            "4h 42m",
            batteryStatusChipLabel(
                runtimeText = "50m",
                storedWh = 4905.0,
                reserveWh = 4032.0,
                loadW = 1044.0,
                socPercent = 24.0,
                capacityWh = 20160.0,
                discharging = true,
                gridOutage = true,
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
                gridOutage = true,
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
                gridOutage = true,
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
                gridOutage = true,
            ),
        )
    }
}
