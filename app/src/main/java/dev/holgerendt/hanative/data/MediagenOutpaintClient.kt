package dev.holgerendt.hanative.data

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
)

/**
 * Thin LAN client for mediagen outpaint.
 *
 * Cache hits / uniform local-only pads return JPEG immediately (`200`).
 * Pictorial Flux jobs return `202` with `{status, hash, retry_after_s}`; this
 * client polls `GET /v1/image/outpaint/{hash}` until ready or [pollTimeoutMs].
 */
class MediagenOutpaintClient(
    private val http: OkHttpClient = defaultClient(),
    private val pollTimeoutMs: Long = POLL_TIMEOUT_MS,
    private val defaultRetryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun outpaint(baseUrl: String, sourceBytes: ByteArray): MediagenOutpaintResult? =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank() || sourceBytes.isEmpty()) return@withContext null
            val host = NetworkGuard.hostOf(base) ?: return@withContext null
            if (!NetworkGuard.isPrivateHost(host)) return@withContext null

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "album_cover.jpg",
                    sourceBytes.toRequestBody("image/jpeg".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url("$base/v1/image/outpaint")
                .post(body)
                .build()
            val response = runCatching { http.newCall(request).execute() }.getOrNull()
                ?: return@withContext null
            response.use { resp ->
                when (resp.code) {
                    200 -> parseReady(resp)
                    202 -> {
                        val generating = parseGenerating(resp, fallbackHash = mediaHash(sourceBytes))
                        pollUntilReady(base, generating.hash)
                    }
                    else -> null
                }
            }
        }

    /**
     * Cache-only probe. Uses the same versioned hash identity as mediagen
     * ([OutpaintPads.OUTPAINT_CACHE_VERSION]). Returns null while generating.
     */
    suspend fun getCached(baseUrl: String, sourceBytes: ByteArray): MediagenOutpaintResult? =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank() || sourceBytes.isEmpty()) return@withContext null
            val host = NetworkGuard.hostOf(base) ?: return@withContext null
            if (!NetworkGuard.isPrivateHost(host)) return@withContext null
            val hash = mediaHash(sourceBytes)
            when (val outcome = fetchByHash(base, hash)) {
                is FetchOutcome.Ready -> outcome.result
                else -> null
            }
        }

    private suspend fun pollUntilReady(base: String, hash: String): MediagenOutpaintResult? {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        var consecutiveErrors = 0
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            when (val outcome = fetchByHash(base, hash)) {
                is FetchOutcome.Ready -> return outcome.result
                is FetchOutcome.Generating -> {
                    consecutiveErrors = 0
                    val waitMs = max(defaultRetryAfterMs, outcome.retryAfterMs)
                    delay(waitMs.coerceAtMost(remaining))
                }
                FetchOutcome.Missing -> {
                    // Job may not be visible yet right after 202 — keep polling.
                    consecutiveErrors = 0
                    delay(defaultRetryAfterMs.coerceAtMost(remaining))
                }
                FetchOutcome.Error -> {
                    consecutiveErrors++
                    // One flaky GET used to abort the whole Flux wait; tolerate blips.
                    if (consecutiveErrors >= 8) return null
                    delay(defaultRetryAfterMs.coerceAtMost(remaining))
                }
            }
        }
        return null
    }

    private fun fetchByHash(base: String, hash: String): FetchOutcome {
        val request = Request.Builder()
            .url("$base/v1/image/outpaint/$hash")
            .get()
            .build()
        val response = runCatching { http.newCall(request).execute() }.getOrNull()
            ?: return FetchOutcome.Error
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val ready = parseReady(resp) ?: return@use FetchOutcome.Error
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

    private fun parseReady(resp: okhttp3.Response): MediagenOutpaintResult? {
        if (!resp.isSuccessful) return null
        val bytes = resp.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return null
        return MediagenOutpaintResult(
            bytes = bytes,
            source = parseSource(resp.header(HEADER_SOURCE)),
            mediaHash = resp.header(HEADER_HASH)?.trim()?.takeIf { it.isNotEmpty() },
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
        /** Overall deadline for Flux generation + polls (match mediagen poll timeout). */
        const val POLL_TIMEOUT_MS = 180_000L
        const val DEFAULT_RETRY_AFTER_MS = 5_000L
        /** Per-request HTTP timeouts — polls are short; overall wait is [POLL_TIMEOUT_MS]. */
        const val REQUEST_TIMEOUT_SECONDS = 30L

        /** Same as mediagen: sha256(cache_version UTF-8 || source_bytes). */
        fun mediaHash(sourceBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(OutpaintPads.OUTPAINT_CACHE_VERSION.toByteArray(Charsets.UTF_8))
            digest.update(sourceBytes)
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
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
                // Per-request only (POST or one poll). Overall Flux wait is [POLL_TIMEOUT_MS].
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
