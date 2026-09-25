package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.max

enum class MediagenOutpaintSource {
    Flux,
    Local,
    Unknown,
}

data class MediagenOutpaintResult(
    val bytes: ByteArray,
    val source: MediagenOutpaintSource,
    val mediaHash: String? = null,
    val layout: OutpaintPadLayout? = null,
)

/**
 * Thin LAN client for mediagen outpaint.
 *
 * Posts the music-player canvas (`out_width`/`out_height`/`x`/`y`) so Flux places
 * the unscaled cover where the UI draws it. Cache hits return JPEG immediately
 * (`200`); Flux jobs return `202` and this client polls until ready.
 */
class MediagenOutpaintClient(
    private val http: OkHttpClient = defaultClient(),
    private val pollTimeoutMs: Long = POLL_TIMEOUT_MS,
    private val defaultRetryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun outpaint(
        baseUrl: String,
        sourceBytes: ByteArray,
        canvas: OutpaintCanvasSpec? = MusicPlayerOutpaint.canvasForSourceBytes(sourceBytes),
    ): MediagenOutpaintResult? =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank() || sourceBytes.isEmpty()) return@withContext null
            val host = NetworkGuard.hostOf(base) ?: return@withContext null
            if (!NetworkGuard.isPrivateHost(host)) return@withContext null

            val layout = canvas?.let { spec ->
                val (w, h) = MusicPlayerOutpaint.sourceSize(sourceBytes) ?: return@let null
                spec.toPads(w, h)
            } ?: MusicPlayerOutpaint.padsForSourceBytes(sourceBytes)

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    if (sourceBytes.firstOrNull() == 0x89.toByte()) "album_cover.png" else "album_cover.jpg",
                    sourceBytes.toRequestBody((if (sourceBytes.firstOrNull() == 0x89.toByte()) "image/png" else "image/jpeg").toMediaType()),
                )
            if (canvas != null) {
                bodyBuilder
                    .addFormDataPart("out_width", canvas.outWidth.toString())
                    .addFormDataPart("out_height", canvas.outHeight.toString())
                    .addFormDataPart("x", canvas.x.toString())
                    .addFormDataPart("y", canvas.y.toString())
            }
            val request = Request.Builder()
                .url("$base/v1/image/outpaint")
                .post(bodyBuilder.build())
                .build()
            val response = runCatching { http.newCall(request).execute() }.getOrNull()
                ?: return@withContext null
            response.use { resp ->
                when (resp.code) {
                    200 -> parseReady(resp, fallbackLayout = layout)
                    202 -> {
                        val generating = parseGenerating(
                            resp,
                            fallbackHash = mediaHash(sourceBytes, layout),
                        )
                        pollUntilReady(base, generating.hash, fallbackLayout = layout)
                    }
                    else -> null
                }
            }
        }

    /**
     * Cache-only probe. Hash includes layout (same identity as mediagen v7+).
     */
    suspend fun getCached(
        baseUrl: String,
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ): MediagenOutpaintResult? =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank() || sourceBytes.isEmpty()) return@withContext null
            val host = NetworkGuard.hostOf(base) ?: return@withContext null
            if (!NetworkGuard.isPrivateHost(host)) return@withContext null
            val hash = mediaHash(sourceBytes, layout)
            when (val outcome = fetchByHash(base, hash, fallbackLayout = layout)) {
                is FetchOutcome.Ready -> outcome.result
                else -> null
            }
        }

    private suspend fun pollUntilReady(
        base: String,
        hash: String,
        fallbackLayout: OutpaintPadLayout,
    ): MediagenOutpaintResult? {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        var consecutiveErrors = 0
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            when (val outcome = fetchByHash(base, hash, fallbackLayout)) {
                is FetchOutcome.Ready -> return outcome.result
                is FetchOutcome.Generating -> {
                    consecutiveErrors = 0
                    val waitMs = max(defaultRetryAfterMs, outcome.retryAfterMs)
                    delay(waitMs.coerceAtMost(remaining))
                }
                FetchOutcome.Missing -> {
                    consecutiveErrors = 0
                    delay(defaultRetryAfterMs.coerceAtMost(remaining))
                }
                FetchOutcome.Error -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 8) return null
                    delay(defaultRetryAfterMs.coerceAtMost(remaining))
                }
            }
        }
        return null
    }

    private fun fetchByHash(
        base: String,
        hash: String,
        fallbackLayout: OutpaintPadLayout,
    ): FetchOutcome {
        val request = Request.Builder()
            .url("$base/v1/image/outpaint/$hash")
            .get()
            .build()
        val response = runCatching { http.newCall(request).execute() }.getOrNull()
            ?: return FetchOutcome.Error
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val ready = parseReady(resp, fallbackLayout) ?: return@use FetchOutcome.Error
                    FetchOutcome.Ready(ready)
                }
                202 -> {
                    val generating = parseGenerating(resp, fallbackHash = hash)
                    FetchOutcome.Generating(
                        hash = generating.hash,
                        retryAfterMs = generating.retryAfterMs,
                    )
                }
                404 -> FetchOutcome.Missing
                else -> FetchOutcome.Error
            }
        }
    }

    private fun parseReady(
        resp: okhttp3.Response,
        fallbackLayout: OutpaintPadLayout,
    ): MediagenOutpaintResult? {
        if (!resp.isSuccessful) return null
        val bytes = resp.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return null
        return MediagenOutpaintResult(
            bytes = bytes,
            source = parseSource(resp.header(HEADER_SOURCE)),
            mediaHash = resp.header(HEADER_HASH)?.trim()?.takeIf { it.isNotEmpty() },
            layout = OutpaintPadLayout.parseHeader(resp.header(HEADER_PAD)) ?: fallbackLayout,
        )
    }

    private data class GeneratingInfo(val hash: String, val retryAfterMs: Long)

    private fun parseGenerating(resp: okhttp3.Response, fallbackHash: String): GeneratingInfo {
        var hash = resp.header(HEADER_HASH)?.trim()?.lowercase()?.takeIf { it.length == 64 }
        var retryAfterMs = resp.header("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.times(1000L)
        val raw = resp.body?.string().orEmpty()
        if (raw.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                if (hash == null) {
                    hash = obj["hash"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.length == 64 }
                }
                if (retryAfterMs == null) {
                    val seconds = obj["retry_after_s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (seconds != null && seconds > 0L) retryAfterMs = seconds * 1000L
                }
            }
        }
        return GeneratingInfo(
            hash = hash ?: fallbackHash,
            retryAfterMs = retryAfterMs ?: defaultRetryAfterMs,
        )
    }

    private sealed class FetchOutcome {
        data class Ready(val result: MediagenOutpaintResult) : FetchOutcome()
        data class Generating(val hash: String, val retryAfterMs: Long) : FetchOutcome()
        data object Missing : FetchOutcome()
        data object Error : FetchOutcome()
    }

    companion object {
        const val HEADER_SOURCE = "X-Outpaint-Source"
        const val HEADER_HASH = "X-Media-Hash"
        const val HEADER_STATUS = "X-Outpaint-Status"
        const val HEADER_PAD = "X-Outpaint-Pad"
        const val HEADER_SIZE = "X-Outpaint-Size"
        /**
         * Overall deadline for Flux generation + polls.
         * Flux Fill model load + first pad often exceeds 3 minutes on the household GPU;
         * keep above mediagen's Comfy poll budget so the client does not abandon early.
         */
        const val POLL_TIMEOUT_MS = 300_000L
        const val DEFAULT_RETRY_AFTER_MS = 5_000L
        /** Per-request HTTP timeouts — polls are short; overall wait is [POLL_TIMEOUT_MS]. */
        const val REQUEST_TIMEOUT_SECONDS = 30L

        /**
         * Same as mediagen: sha256(version || pads:L,T,R,B\0 || fingerprint).
         * Fingerprint prefers decoded RGB (`canon4`); falls back to raw bytes.
         */
        fun mediaHash(
            sourceBytes: ByteArray,
            layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
            cacheVersion: String = OutpaintPads.OUTPAINT_CACHE_VERSION,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(cacheVersion.toByteArray(Charsets.UTF_8))
            digest.update(layout.layoutTag())
            val canonical = canonicalizeRgb(sourceBytes)
            if (canonical != null) {
                digest.update("canon4\u0000".toByteArray(Charsets.UTF_8))
                digest.update(canonical)
            } else {
                digest.update("raw\u0000".toByteArray(Charsets.UTF_8))
                digest.update(sourceBytes)
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        /** Big-endian WxH + RGB bytes — matches mediagen `canonicalize_image_bytes`. */
        fun canonicalizeRgb(sourceBytes: ByteArray): ByteArray? {
            if (sourceBytes.isEmpty()) return null
            return try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts)
                    ?: return null
                try {
                    val w = bitmap.width
                    val h = bitmap.height
                    if (w < 1 || h < 1) return null
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    val rgb = ByteArray(8 + w * h * 3)
                    ByteBuffer.wrap(rgb, 0, 8).order(ByteOrder.BIG_ENDIAN).putInt(w).putInt(h)
                    var i = 8
                    for (p in pixels) {
                        rgb[i++] = ((p ushr 16) and 0xff).toByte()
                        rgb[i++] = ((p ushr 8) and 0xff).toByte()
                        rgb[i++] = (p and 0xff).toByte()
                    }
                    rgb
                } finally {
                    bitmap.recycle()
                }
            } catch (_: Throwable) {
                // JVM unit tests do not mock BitmapFactory — fall back to raw hash.
                null
            }
        }

        fun parseSource(raw: String?): MediagenOutpaintSource =
            when (raw?.trim()?.lowercase()) {
                "flux" -> MediagenOutpaintSource.Flux
                "local" -> MediagenOutpaintSource.Local
                else -> MediagenOutpaintSource.Unknown
            }

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(NetworkGuard.interceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
