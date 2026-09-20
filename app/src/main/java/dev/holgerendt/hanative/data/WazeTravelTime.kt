package dev.holgerendt.hanative.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Format accepted by `waze_travel_time` origin/destination fields. */
fun GeoPoint.toWazeCoord(): String = "$latitude,$longitude"

/**
 * Best (shortest) drive duration in whole minutes from a
 * `waze_travel_time.get_travel_times` service response.
 *
 * Accepts either the raw `result.response` payload or a result that already
 * contains a top-level `routes` array.
 */
fun parseWazeTravelMinutes(response: JsonElement?): Int? {
    val root = response as? JsonObject ?: return null
    val routes = root["routes"]?.jsonArray
        ?: root["response"]?.jsonObject?.get("routes")?.jsonArray
        ?: return null
    val durations = routes.mapNotNull { route ->
        val obj = route as? JsonObject ?: return@mapNotNull null
        val duration = obj["duration"]?.jsonPrimitive ?: return@mapNotNull null
        duration.doubleOrNull ?: duration.contentOrNull?.toDoubleOrNull()
    }
    val best = durations.minOrNull() ?: return null
    if (!best.isFinite() || best < 0) return null
    return best.roundToInt().coerceAtLeast(0)
}

fun formatTravelDriveLabel(minutes: Int): String =
    when {
        minutes <= 0 -> "~1 min drive"
        else -> "~$minutes min drive"
    }
