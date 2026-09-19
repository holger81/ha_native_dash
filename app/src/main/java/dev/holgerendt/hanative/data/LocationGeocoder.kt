package dev.holgerendt.hanative.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object LocationGeocoder {
    /**
     * Resolve a free-text address to coordinates. Returns null when the platform
     * has no geocoder, the query is blank, or no result is found.
     */
    suspend fun geocode(context: Context, query: String): GeoPoint? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext null
        parseCoordinates(trimmed)?.let { return@withContext it }
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(trimmed, 1) { addresses ->
                        val first = addresses.firstOrNull()
                        cont.resume(
                            first?.let { GeoPoint(it.latitude, it.longitude).takeIf { p -> p.isValid() } },
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(trimmed, 1)
                    ?.firstOrNull()
                    ?.let { GeoPoint(it.latitude, it.longitude).takeIf { p -> p.isValid() } }
            }
        }.getOrNull()
    }
}
