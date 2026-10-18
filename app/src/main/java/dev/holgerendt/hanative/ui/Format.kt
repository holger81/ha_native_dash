package dev.holgerendt.hanative.ui

import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.model.DisplayNode
import dev.holgerendt.hanative.model.StateFormat
import dev.holgerendt.hanative.model.WidgetNode
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

private val HUMIDITY_SUFFIX = Regex("_temperature$")

fun Map<String, EntityState>.getState(id: String?): EntityState? = id?.let { this[it] }

fun Double?.format(decimals: Int = 1, suffix: String = ""): String {
    if (this == null || this.isNaN()) return "—"
    val text = if (decimals <= 0) roundToInt().toString() else String.format("%.${decimals}f", this)
    return text + suffix
}

fun String?.toDoubleOrNullSafe(): Double? = this?.toDoubleOrNull()

fun isUnavailableState(state: String?): Boolean =
    state.isNullOrBlank() || state in setOf("unavailable", "unknown", "none")

fun Map<String, EntityState>.number(id: String?, decimals: Int = 1, suffix: String = "", scale: Double = 1.0): String {
    val value = getState(id)?.state?.toDoubleOrNull()?.times(scale)
    return value.format(decimals, suffix)
}

/**
 * "6h 40m" style used by sensor.battery_runtime_remaining.
 * Hours are truncated; leftover minutes are rounded, with a 60-minute carry.
 */
fun formatRuntimeHours(hours: Double): String {
    if (!hours.isFinite() || hours < 0.0) return "—"
    var h = hours.toInt()
    var m = ((hours - h) * 60.0).roundToInt()
    if (m >= 60) {
        h += m / 60
        m %= 60
    }
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

data class BatteryRuntimeEstimates(val reserve: String, val total: String)

/**
 * Same load basis as sensor.battery_runtime_remaining: Wh / W, and unknown
 * when the 1h-mean load is under 100 W.
 *
 * Stored (battery_energy_helper) is available energy and already includes
 * reserve. The headline sensor is (stored − reserve) / load. Reserve runtime
 * is reserve / load. Total runtime is stored / load — time to empty the pack,
 * including the reserve — not (stored + reserve), which would double-count.
 *
 * When reserve is unavailable (common during Envoy outages), reserve shows "—"
 * but total runtime is still estimated from stored / load.
 */
fun batteryRuntimeEstimates(storedWh: Double, reserveWh: Double, loadW: Double): BatteryRuntimeEstimates? {
    if (!storedWh.isFinite() || !loadW.isFinite() || loadW < 100.0) return null
    val reserveOk = reserveWh.isFinite()
    return BatteryRuntimeEstimates(
        reserve = if (reserveOk) formatRuntimeHours(reserveWh.coerceAtLeast(0.0) / loadW) else "—",
        total = formatRuntimeHours(storedWh.coerceAtLeast(0.0) / loadW),
    )
}

/**
 * Headline runtime matching sensor.battery_runtime_remaining when the grid is
 * up: (stored − reserve) / load. During a grid outage the battery drains into
 * reserve, so pass reserveWh=null to report total pack time (stored / load)
 * from battery_energy_helper — the HA template's above-reserve figure is
 * misleading while islanded (e.g. ~50m vs ~4h).
 */
fun batteryHeadlineRuntime(storedWh: Double, reserveWh: Double?, loadW: Double): String? {
    if (!storedWh.isFinite() || !loadW.isFinite() || loadW < 100.0) return null
    val usable = if (reserveWh != null && reserveWh.isFinite()) {
        (storedWh - reserveWh).coerceAtLeast(0.0)
    } else {
        storedWh.coerceAtLeast(0.0)
    }
    return formatRuntimeHours(usable / loadW)
}

fun estimatedBatterySocPercent(storedWh: Double, capacityWh: Double): Int? {
    if (!storedWh.isFinite() || !capacityWh.isFinite() || capacityWh <= 0.0) return null
    return ((storedWh / capacityWh) * 100.0).roundToInt().coerceIn(0, 100)
}

/** Grid status chip: kW when known; "Outage" when islanded; Offline when Envoy is down. */
fun gridStatusChipLabel(
    netPowerKw: Double?,
    netPowerRaw: String?,
    gridOutage: Boolean,
    decimals: Int = 2,
    suffix: String = "kW",
): String {
    if (gridOutage) return "Outage"
    if (netPowerKw != null && netPowerKw.isFinite()) return netPowerKw.format(decimals, suffix)
    return when {
        isUnavailableState(netPowerRaw) -> "Offline"
        else -> "—"
    }
}

/**
 * Battery status chip: when the grid is up, prefer the HA above-reserve template.
 * During a grid outage ignore that template (it understates islanded runtime)
 * and estimate total pack time from helper + load; else SoC / "On battery".
 */
fun batteryStatusChipLabel(
    runtimeText: String?,
    storedWh: Double?,
    reserveWh: Double?,
    loadW: Double?,
    socPercent: Double?,
    capacityWh: Double?,
    discharging: Boolean,
    gridOutage: Boolean = false,
): String {
    if (!gridOutage) {
        runtimeText
            ?.takeIf { it.isNotBlank() && !isUnavailableState(it) }
            ?.let { return it }
    }
    batteryHeadlineRuntime(
        storedWh = storedWh ?: Double.NaN,
        // Islanded: drain includes reserve — do not subtract it.
        reserveWh = if (gridOutage) null else reserveWh,
        loadW = loadW ?: Double.NaN,
    )?.let { return it }
    socPercent?.takeIf { it.isFinite() }?.let { return "${it.roundToInt()}%" }
    estimatedBatterySocPercent(storedWh ?: Double.NaN, capacityWh ?: Double.NaN)
        ?.let { return "$it%" }
    return if (discharging || gridOutage) "On battery" else ""
}

fun Map<String, EntityState>.tempHum(display: DisplayNode?): String {
    if (display == null) return "—"
    val climate = getState(display.climateEntity)
    val temp = climate?.attrDouble("current_temperature")
        ?: getState(display.tempEntity)?.state?.toDoubleOrNull()
    val hum = climate?.attrDouble("current_humidity")
        ?: getState(display.humEntity)?.state?.toDoubleOrNull()
        ?: run {
            val t = display.tempEntity
            if (t?.endsWith("_temperature") == true) {
                getState(t.replace(HUMIDITY_SUFFIX, "_humidity"))?.state?.toDoubleOrNull()
            } else null
        }
    if (temp == null) return "Unknown"
    return if (hum == null) temp.format(1, "°") else "${temp.format(1, "°")}  ${hum.format(0, "%")}"
}

fun Map<String, EntityState>.roomTemp(display: DisplayNode?, fallbackEntity: String? = null): Double? {
    val climate = getState(display?.climateEntity)
    return climate?.attrDouble("current_temperature")
        ?: getState(display?.tempEntity ?: fallbackEntity)?.state?.toDoubleOrNull()
}

fun Map<String, EntityState>.roomHum(display: DisplayNode?, fallbackEntity: String? = null): Double? {
    val climate = getState(display?.climateEntity)
    return climate?.attrDouble("current_humidity")
        ?: getState(display?.humEntity)?.state?.toDoubleOrNull()
        ?: run {
            val t = display?.tempEntity ?: fallbackEntity
            if (t?.endsWith("_temperature") == true) {
                getState(t.replace(HUMIDITY_SUFFIX, "_humidity"))?.state?.toDoubleOrNull()
            } else null
        }
}

fun Map<String, EntityState>.formatState(format: StateFormat?, fallback: String? = null): String {
    val entityId = format?.entity ?: return fallback ?: ""
    val entity = getState(entityId)
    return when (format.kind) {
        "attribute" -> entity?.attrString(format.attribute ?: "") ?: fallback.orEmpty()
        "number" -> number(entityId, format.decimals ?: 1, format.suffix.orEmpty(), format.scale ?: 1.0)
        "text" -> entity?.state?.takeIf { it.isNotBlank() && it !in setOf("unknown", "unavailable") } ?: fallback.orEmpty()
        "minutes_from_hours" -> {
            val hours = entity?.state?.toDoubleOrNull()
            if (hours == null) "—" else "${(hours * 60).roundToInt()}min"
        }
        else -> entity?.state?.replaceFirstChar { it.uppercase() } ?: fallback.orEmpty()
    }
}

fun Map<String, EntityState>.isVisible(node: WidgetNode): Boolean {
    val visibility = node.visibility ?: return true
    val entity = getState(visibility.entity)
    val value = entity?.state
    return when (visibility.kind) {
        "always" -> true
        "state_in" -> value in visibility.states
        "state_not" -> value !in visibility.states
        "numeric_gte" -> (value?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY) >= (visibility.value ?: 0.0)
        else -> true
    }
}

fun Map<String, EntityState>.brightnessPct(entityId: String?): Int = this[entityId]?.brightnessPct() ?: 0

fun EntityState?.brightnessPct(): Int {
    if (this?.entityId?.startsWith("fan.") == true) {
        val pct = attrDouble("percentage") ?: 0.0
        return pct.roundToInt().coerceIn(0, 100)
    }
    val brightness = this?.attrDouble("brightness") ?: return 0
    return ((brightness / 255.0) * 100.0).roundToInt().coerceIn(0, 100)
}

fun isOn(state: String?): Boolean = state in setOf("on", "open", "opening", "unlocked", "playing", "cleaning")

fun Instant.relativeToNow(now: Instant = Instant.now()): String {
    val seconds = Duration.between(this, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> if (seconds <= 1) "1 second ago" else "$seconds seconds ago"
        seconds < 90 -> "1 minute ago"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        seconds < 5400 -> "1 hour ago"
        seconds < 86400 -> "${seconds / 3600} hours ago"
        seconds < 172800 -> "1 day ago"
        else -> "${seconds / 86400} days ago"
    }
}

