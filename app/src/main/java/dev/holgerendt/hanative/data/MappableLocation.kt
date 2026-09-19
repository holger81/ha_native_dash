package dev.holgerendt.hanative.data

/**
 * Pure helpers for deciding whether a calendar "location" string is worth mapping,
 * and for parsing already-numeric coordinates (geo: / lat,lng).
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
}

private val COORD_PAIR = Regex(
    """^\s*([+-]?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*([+-]?\d{1,3}(?:\.\d+)?)\s*$""",
)

private val GEO_URI = Regex(
    """(?i)^geo:([+-]?\d+(?:\.\d+)?),([+-]?\d+(?:\.\d+)?)(?:[;?].*)?$""",
)

private val NON_ADDRESS_HINTS = listOf(
    "zoom.us",
    "meet.google",
    "teams.microsoft",
    "webex.com",
    "skype.com",
    "facetime",
)

/** True when [raw] looks like a street/place address or explicit coordinates. */
fun looksLikeMappableLocation(raw: String?): Boolean {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return false
    if (parseCoordinates(s) != null) return true
    val lower = s.lowercase()
    if (lower == "online" || lower == "remote" || lower == "tbd" || lower == "n/a") return false
    if (NON_ADDRESS_HINTS.any { lower.contains(it) }) return false
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
        return false
    }
    val hasDigit = s.any { it.isDigit() }
    val commaParts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // Street number, or "City, ST" / multi-part place names.
    return hasDigit || commaParts.size >= 2
}

/** Parse `geo:lat,lng` or bare `lat,lng` / `lat;lng`. */
fun parseCoordinates(raw: String): GeoPoint? {
    val s = raw.trim()
    GEO_URI.matchEntire(s)?.let { match ->
        val point = GeoPoint(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        return point.takeIf { it.isValid() }
    }
    COORD_PAIR.matchEntire(s)?.let { match ->
        val point = GeoPoint(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        return point.takeIf { it.isValid() }
    }
    return null
}

fun EntityState?.homeGeoPoint(): GeoPoint? {
    if (this == null) return null
    val lat = attrDouble("latitude") ?: return null
    val lon = attrDouble("longitude") ?: return null
    return GeoPoint(lat, lon).takeIf { it.isValid() }
}
