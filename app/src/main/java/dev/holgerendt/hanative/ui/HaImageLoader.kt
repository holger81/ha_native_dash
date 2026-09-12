package dev.holgerendt.hanative.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dev.holgerendt.hanative.data.HaClient
import dev.holgerendt.hanative.data.NetworkGuard
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val DISK_CACHE_DIR = "ha_image_cache"
private const val DISK_CACHE_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Coil ImageLoader with memory + disk cache; HA bearer + MASS ingress cookie for same-origin URLs.
 *
 * Cover art from Music Assistant often lives on public CDNs (Apple Music, etc.). The main
 * [NetworkGuard] client still blocks non-LAN API egress; this loader allows HTTPS/HTTP GET to
 * public hosts for images only, and never attaches the HA token to those hosts.
 *
 * One instance per process: each loader owns an 18%-of-heap memory cache, an OkHttp pool, and a
 * handle on the same disk-cache directory, so building one per cover-art tile is not viable.
 * Crossfade is configured per request instead of here to avoid animating twice.
 */
fun haImageLoader(context: Context, client: HaClient): ImageLoader {
    val appContext = context.applicationContext
    return ImageLoader.Builder(appContext)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder(appContext)
                .maxSizePercent(0.18)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(appContext.cacheDir, DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                .build()
        }
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    val privateHost = NetworkGuard.isPrivateHost(url.host)
                    // Image GETs may hit public CDNs; everything else stays LAN-only.
                    if (!privateHost &&
                        !(request.method == "GET" && (url.scheme == "https" || url.scheme == "http"))
                    ) {
                        throw IOException("Blocked: host '${url.host}' is not on the local network")
                    }
                    val urlText = url.toString()
                    val base = client.currentBaseUrl.trimEnd('/')
                    val sameOrigin = base.isNotBlank() && urlText.startsWith(base)
                    val builder = request.newBuilder()
                    if (sameOrigin) {
                        client.bearerHeaders().forEach { (key, value) ->
                            builder.header(key, value)
                        }
                        client.massIngressHeaders().forEach { (key, value) ->
                            builder.header(key, value)
                        }
                    }
                    chain.proceed(builder.build())
                }
                .build()
        }
        .build()
}

private val sharedLoaders = ConcurrentHashMap<HaClient, ImageLoader>()

@Composable
fun rememberHaImageLoader(client: HaClient): ImageLoader {
    val context = LocalContext.current
    return remember(client) {
        sharedLoaders.getOrPut(client) { haImageLoader(context, client) }
    }
}

fun resolveHaImageUrl(path: String?, baseUrl: String): String? {
    val raw = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
    val base = baseUrl.trimEnd('/')
    if (base.isBlank()) return null
    return if (raw.startsWith("/")) "$base$raw" else "$base/$raw"
}
