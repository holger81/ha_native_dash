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
import java.util.concurrent.ConcurrentHashMap

private const val DISK_CACHE_DIR = "ha_image_cache"
private const val DISK_CACHE_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Coil ImageLoader with memory + disk cache; HA bearer auth for same-origin URLs only.
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
                .addInterceptor(NetworkGuard.interceptor)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val urlText = request.url.toString()
                    val base = client.currentBaseUrl.trimEnd('/')
                    val needsAuth = base.isNotBlank() && urlText.startsWith(base)
                    val builder = request.newBuilder()
                    if (needsAuth) {
                        client.bearerHeaders().forEach { (key, value) ->
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
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    val base = baseUrl.trimEnd('/')
    if (base.isBlank()) return null
    return if (raw.startsWith("/")) "$base$raw" else "$base/$raw"
}
