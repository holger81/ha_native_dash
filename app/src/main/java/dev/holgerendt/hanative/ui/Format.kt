package dev.holgerendt.hanative.ui

import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.model.DisplayNode
import dev.holgerendt.hanative.model.StateFormat
import dev.holgerendt.hanative.model.WidgetNode
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

fun Map<String, EntityState>.getState(id: String?): EntityState? = id?.let { this[it] }

fun Double?.format(decimals: Int = 1, suffix: String = ""): String {
    if (this == null || this.isNaN()) return "—"
    val text = if (decimals <= 0) roundToInt().toString() else String.format("%.${decimals}f", this)
    return text + suffix
}

fun String?.toDoubleOrNullSafe(): Double? = this?.toDoubleOrNull()

fun Map<String, EntityState>.number(id: String?, decimals: Int = 1, suffix: String = "", scale: Double = 1.0): String {
    val value = getState(id)?.state?.toDoubleOrNull()?.times(scale)
    return value.format(decimals, suffix)
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
                getState(t.replace(Regex("_temperature$"), "_humidity"))?.state?.toDoubleOrNull()
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
                getState(t.replace(Regex("_temperature$"), "_humidity"))?.state?.toDoubleOrNull()
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

