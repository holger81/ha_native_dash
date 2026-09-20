package dev.holgerendt.hanative.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.holgerendt.hanative.data.GeoPoint
import dev.holgerendt.hanative.data.LocationGeocoder
import dev.holgerendt.hanative.data.formatTravelDriveLabel
import dev.holgerendt.hanative.data.looksLikeMappableLocation
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Geocodes [locationText] when it looks like an address and shows an OSM/Leaflet
 * map (drag + pinch-zoom) with an optional home marker.
 *
 * When [home] and the event point are both available and [fetchTravelMinutes] is
 * provided, requests drive time once (not on a timer) and shows it under the map.
 */
@Composable
fun LocationMapPreview(
    locationText: String,
    home: GeoPoint?,
    modifier: Modifier = Modifier,
    mapHeight: androidx.compose.ui.unit.Dp = 220.dp,
    fetchTravelMinutes: (suspend (origin: GeoPoint, destination: GeoPoint) -> Int?)? = null,
) {
    val overlay = LocalOverlay.current
    val context = LocalContext.current
    var resolved by remember(locationText) { mutableStateOf<GeoPoint?>(null) }
    var failed by remember(locationText) { mutableStateOf(false) }
    var loading by remember(locationText) { mutableStateOf(false) }
    var travelLabel by remember(locationText) { mutableStateOf<String?>(null) }

    LaunchedEffect(locationText, home) {
        resolved = null
        failed = false
        travelLabel = null
        if (!looksLikeMappableLocation(locationText)) {
            failed = true
            return@LaunchedEffect
        }
        loading = true
        val point = LocationGeocoder.geocode(context, locationText, bias = home)
        loading = false
        if (point == null) {
            failed = true
        } else {
            resolved = point
        }
    }

    val point = resolved
    LaunchedEffect(point, home) {
        travelLabel = null
        val origin = home
        val destination = point
        val fetch = fetchTravelMinutes
        if (origin == null || destination == null || fetch == null) return@LaunchedEffect
        val minutes = runCatching { fetch(origin, destination) }.getOrNull() ?: return@LaunchedEffect
        travelLabel = formatTravelDriveLabel(minutes)
    }

    when {
        loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(overlay.sheet.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = overlay.muted, strokeWidth = 2.dp)
        }
        point != null -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LeafletMap(
                event = point,
                home = home,
                label = locationText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight)
                    .clip(RoundedCornerShape(12.dp)),
            )
            travelLabel?.let { label ->
                Text(
                    text = label,
                    color = overlay.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        failed && looksLikeMappableLocation(locationText) -> Text(
            "Could not place on map",
            color = overlay.muted,
            fontSize = 12.sp,
            modifier = modifier,
        )
        else -> Unit
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun LeafletMap(
    event: GeoPoint,
    home: GeoPoint?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val html = remember(event, home, label) { leafletHtml(event, home, label) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = WebViewClient()
                // Keep pan/pinch gestures on the map instead of the dialog sheet.
                setOnTouchListener { v, motion ->
                    when (motion.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_MOVE,
                        -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                        -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                tag = html
                loadDataWithBaseURL(
                    "https://unpkg.com/",
                    html,
                    "text/html",
                    Charsets.UTF_8.name(),
                    null,
                )
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    "https://unpkg.com/",
                    html,
                    "text/html",
                    Charsets.UTF_8.name(),
                    null,
                )
            }
        },
    )
}

private fun leafletHtml(event: GeoPoint, home: GeoPoint?, label: String): String {
    val payload = buildJsonObject {
        put("eventLat", event.latitude)
        put("eventLng", event.longitude)
        put("label", label)
        if (home != null) {
            put("homeLat", home.latitude)
            put("homeLng", home.longitude)
        }
    }
    val json = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        payload,
    )
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: #e8eef3; }
            .home-pin {
              width: 28px; height: 28px;
              display: flex; align-items: center; justify-content: center;
              background: #fff;
              border: 2px solid #1b6ef3;
              border-radius: 50%;
              box-shadow: 0 1px 4px rgba(0,0,0,0.35);
              font-size: 16px; line-height: 1;
            }
            .leaflet-container { font: 12px/1.4 -apple-system, sans-serif; }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            (function () {
              var d = $json;
              var map = L.map('map', {
                zoomControl: true,
                attributionControl: true,
                dragging: true,
                touchZoom: true,
                scrollWheelZoom: true,
                doubleClickZoom: true,
                boxZoom: false
              });
              L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19,
                attribution: '&copy; OpenStreetMap'
              }).addTo(map);
              var eventLatLng = L.latLng(d.eventLat, d.eventLng);
              L.marker(eventLatLng).addTo(map).bindPopup(d.label || 'Event');
              var bounds = L.latLngBounds([eventLatLng]);
              if (typeof d.homeLat === 'number' && typeof d.homeLng === 'number') {
                var homeIcon = L.divIcon({
                  className: '',
                  html: '<div class="home-pin" title="Home">🏠</div>',
                  iconSize: [28, 28],
                  iconAnchor: [14, 14]
                });
                var homeLatLng = L.latLng(d.homeLat, d.homeLng);
                L.marker(homeLatLng, { icon: homeIcon, title: 'Home' }).addTo(map);
                bounds.extend(homeLatLng);
              }
              if (bounds.getNorthEast().equals(bounds.getSouthWest())) {
                map.setView(eventLatLng, 15);
              } else {
                map.fitBounds(bounds.pad(0.25));
              }
              setTimeout(function () { map.invalidateSize(true); }, 80);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
