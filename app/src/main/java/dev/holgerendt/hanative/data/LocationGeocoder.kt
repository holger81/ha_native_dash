package dev.holgerendt.hanative.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

object LocationGeocoder {
    private const val TAG = "LocationGeocoder"
    private const val PLATFORM_TIMEOUT_MS = 8_000L
    private const val NOMINATIM_TIMEOUT_MS = 12_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Resolve a free-text address to coordinates. Tries the platform [Geocoder]
     * first, then OpenStreetMap Nominatim (wall tablets often lack Play Services
     * geocoding). Returns null when nothing resolves.
     */
    suspend fun geocode(
        context: Context,
        query: String,
        bias: GeoPoint? = null,
    ): GeoPoint? = withContext(Dispatchers.IO) {
        val trimmed = normalizeLocationQuery(query) ?: return@withContext null
        parseCoordinates(trimmed)?.let { return@withContext it }
        platformGeocode(context, trimmed)
            ?: nominatimGeocode(trimmed, bias)
    }

    private suspend fun platformGeocode(context: Context, query: String): GeoPoint? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
            val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(query, 1) { addresses ->
                            val first = addresses.firstOrNull()
                            cont.resume(
                                first?.let {
                                    GeoPoint(it.latitude, it.longitude).takeIf { p -> p.isValid() }
                                },
                            )
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 1)
                        ?.firstOrNull()
                        ?.let { GeoPoint(it.latitude, it.longitude).takeIf { p -> p.isValid() } }
                }
            }.onFailure { Log.d(TAG, "Platform geocode failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Nominatim over [HttpURLConnection] so NetworkGuard (OkHttp) does not block
     * the public OSM endpoint. Respects Nominatim's User-Agent requirement.
     */
    private fun nominatimGeocode(query: String, bias: GeoPoint?): GeoPoint? {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val biasParams = bias?.takeIf { it.isValid() }?.let { home ->
            val delta = 0.75
            val left = (home.longitude - delta).coerceIn(-180.0, 180.0)
            val right = (home.longitude + delta).coerceIn(-180.0, 180.0)
            val top = (home.latitude + delta).coerceIn(-90.0, 90.0)
            val bottom = (home.latitude - delta).coerceIn(-90.0, 90.0)
            "&viewbox=$left,$top,$right,$bottom&bounded=0"
        }.orEmpty()
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1" +
                "&addressdetails=0&q=$encoded$biasParams",
        )
        return runCatching {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = NOMINATIM_TIMEOUT_MS
                readTimeout = NOMINATIM_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "ha-native-dash/1.0 (wall tablet calendar map; local household use)",
                )
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.d(TAG, "Nominatim HTTP ${conn.responseCode}")
                    return@runCatching null
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseNominatimResponse(body)
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.d(TAG, "Nominatim geocode failed: ${it.message}") }
            .getOrNull()
    }

    internal fun parseNominatimResponse(body: String): GeoPoint? {
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        val first = arr.firstOrNull()?.jsonObject ?: return null
        val lat = first["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: first["lat"]?.jsonPrimitive?.doubleOrNull
            ?: return null
        val lon = first["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: first["lon"]?.jsonPrimitive?.doubleOrNull
            ?: return null
        return GeoPoint(lat, lon).takeIf { it.isValid() }
    }
}

/** Collapse calendar location fluff into a geocoder-friendly single line. */
fun normalizeLocationQuery(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    return s
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}
