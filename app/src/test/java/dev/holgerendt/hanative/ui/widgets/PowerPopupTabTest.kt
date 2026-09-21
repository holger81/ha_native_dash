package dev.holgerendt.hanative.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPopupTabTest {
    private val powerTabs = listOf(
        "Consumption",
        "Live Draw",
        "Solarcells",
        "Battery",
        "Prices",
    )

    @Test fun batteryChipOpensBatteryNotConsumption() {
        assertEquals(3, tabIndexForRequest(powerTabs.size, defaultTab = 1, "Battery", powerTabs))
    }

    @Test fun gridChipOpensConsumptionChartNotLiveDraw() {
        // Net grid kW is the Consumption energy_usage_graph entity. Live Draw
        // excludes sensor.envoy_* and is the next tab after the insert.
        assertEquals(0, tabIndexForRequest(powerTabs.size, defaultTab = 1, "Consumption", powerTabs))
        assertEquals(1, tabIndexForRequest(powerTabs.size, defaultTab = 1, "Live Draw", powerTabs))
    }

    @Test fun solarChipWouldOpenSolarcellsAfterLiveDrawInsert() {
        assertEquals(2, tabIndexForRequest(powerTabs.size, defaultTab = 1, "Solarcells", powerTabs))
    }

    @Test fun dockOpenKeepsDefaultConsumptionTab() {
        assertEquals(0, tabIndexForRequest(powerTabs.size, defaultTab = 1, null, powerTabs))
    }

    @Test fun unknownTitleFallsBackInsteadOfShifting() {
        assertEquals(0, tabIndexForRequest(powerTabs.size, defaultTab = 1, "Missing", powerTabs))
    }
}
