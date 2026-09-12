package dev.holgerendt.hanative.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves cover art, checks the on-disk outpaint cache, and optionally queues ComfyUI.
 * Never throws for network/Comfy failures — returns null so UI keeps the soft local treatment.
 */
class AlbumArtOutpaintRepository(
    context: Context,
    private val haClient: HaClient,
    private val comfyUiUrl: () -> String,
    private val cache: AlbumArtOutpaintCache = AlbumArtOutpaintCache(
        File(context.applicationContext.filesDir, AlbumArtOutpaintCache.DIR_NAME),
    ),
    private val comfy: ComfyUiOutpaintClient = ComfyUiOutpaintClient(context.applicationContext.assets),
    private val imageHttp: OkHttpClient = imageFetchClient(),
) {
    suspend fun getOutpaintedFile(coverRef: String?): File? {
        val base = comfyUiUrl().trim().trimEnd('/')
        if (base.isBlank() || coverRef.isNullOrBlank()) return null
        val source = fetchCoverBytes(coverRef) ?: return null
        return cache.getOrEnqueue(source) { bytes ->
            comfy.outpaint(base, bytes)
        }
    }

    /** Cache-only lookup after bytes are known (tests / warm path). */
    suspend fun getOrGenerate(
        sourceBytes: ByteArray,
        generate: suspend (ByteArray) -> ByteArray?,
    ): File? = cache.getOrEnqueue(sourceBytes, generate)

    fun peekCached(sourceBytes: ByteArray): File? = cache.cachedFile(sourceBytes)

    private suspend fun fetchCoverBytes(coverRef: String): ByteArray? = withContext(Dispatchers.IO) {
        if (coverRef.startsWith("data:image")) {
            val comma = coverRef.indexOf(',')
            if (comma < 0) return@withContext null
            return@withContext runCatching {
                android.util.Base64.decode(coverRef.substring(comma + 1), android.util.Base64.DEFAULT)
            }.getOrNull()
        }
        val url = haClient.resolveMusicCoverUrl(coverRef, size = 512)
            ?: resolveRelative(coverRef)
            ?: return@withContext null
        if (url.startsWith("data:image")) {
            val comma = url.indexOf(',')
            if (comma < 0) return@withContext null
            return@withContext runCatching {
                android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
            }.getOrNull()
        }
        // Prefer HA-authenticated path for same-origin / private hosts.
        haClient.authenticatedBytes(url)?.let { return@withContext it }
        // Public CDN covers (Apple Music, etc.): image GET only, no HA token.
        runCatching {
            val builder = Request.Builder().url(url).get()
            val base = haClient.currentBaseUrl.trimEnd('/')
            if (base.isNotBlank() && url.startsWith(base)) {
                haClient.bearerHeaders().forEach { (k, v) -> builder.header(k, v) }
                haClient.massIngressHeaders().forEach { (k, v) -> builder.header(k, v) }
            } else if (url.contains("/api/") || url.contains("/imageproxy")) {
                // MASS ingress on HA host may still need cookie when URL is absolute.
                haClient.massIngressHeaders().forEach { (k, v) -> builder.header(k, v) }
                haClient.bearerHeaders().forEach { (k, v) -> builder.header(k, v) }
            }
            imageHttp.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun resolveRelative(raw: String): String? {
        val path = raw.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = haClient.currentBaseUrl.trimEnd('/')
        if (base.isBlank()) return null
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    companion object {
        fun imageFetchClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val privateHost = NetworkGuard.isPrivateHost(host)
                // Image GETs may hit public CDNs; everything else stays LAN-only.
                if (!privateHost &&
                    !(request.method == "GET" &&
                        (request.url.scheme == "https" || request.url.scheme == "http"))
                ) {
                    throw java.io.IOException("Blocked: host '$host' is not on the local network")
                }
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
