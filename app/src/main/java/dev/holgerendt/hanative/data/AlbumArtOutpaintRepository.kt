package dev.holgerendt.hanative.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves cover art, checks the on-disk outpaint cache, and queues **real**
 * ComfyUI Flux fill outpainting (generative scene extension), not soft blur.
 *
 * Generation priority (one ComfyUI job at a time):
 * 1. Upcoming covers (playlist +1 … +5)
 * 2. Current cover backfill — only after upcoming are cached or absent
 *
 * Never throws for network/Comfy failures — returns null so UI keeps the interim
 * soft local treatment until a real outpaint is cached.
 *
 * Each cover ref is attempted at most once per process after a failed Comfy job,
 * and warm refs are remembered so flaky re-downloads cannot re-queue the same art.
 */
class AlbumArtOutpaintRepository(
    context: Context,
    private val haClient: HaClient,
    private val comfyUiUrl: () -> String,
    private val scope: CoroutineScope,
    private val cache: AlbumArtOutpaintCache = AlbumArtOutpaintCache(
        File(context.applicationContext.filesDir, AlbumArtOutpaintCache.DIR_NAME),
    ),
    private val comfy: ComfyUiOutpaintClient = ComfyUiOutpaintClient(context.applicationContext.assets),
    private val imageHttp: OkHttpClient = imageFetchClient(),
) {
    private val workerMutex = Mutex()
    private val targets = AtomicReference(OutpaintTargets())
    private var workerJob: Job? = null
    /** Cover refs that already have a successful cache hit or generation this process. */
    private val warmRefs = ConcurrentHashMap.newKeySet<String>()
    /** Cover refs that failed Comfy/fetch this process — do not hammer ComfyUI. */
    private val failedRefs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Update the generation plan. [upcomingCovers] should be next tracks first
     * (+1 … +[MAX_UPCOMING]). [currentCover] is backfilled only after upcoming are cached.
     */
    fun setTargets(currentCover: String?, upcomingCovers: List<String>) {
        if (comfyUiUrl().isBlank()) return
        val upcoming = upcomingCovers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it != currentCover?.trim() }
            .take(MAX_UPCOMING)
        val current = currentCover?.trim()?.takeIf { it.isNotBlank() }
        targets.set(OutpaintTargets(current = current, upcoming = upcoming))
        kickWorker()
    }

    /** Cache-only lookup — never starts ComfyUI. */
    suspend fun peekOutpaintedFile(coverRef: String?): File? {
        if (coverRef.isNullOrBlank() || comfyUiUrl().isBlank()) return null
        val source = fetchCoverBytes(coverRef) ?: return null
        return cache.cachedFile(source)
    }

    /**
     * Prefer cache; if missing, register [coverRef] as current backfill and poll
     * while the priority worker runs (upcoming still goes first).
     */
    suspend fun getOutpaintedFile(coverRef: String?): File? {
        if (coverRef.isNullOrBlank() || comfyUiUrl().isBlank()) return null
        peekOutpaintedFile(coverRef)?.let { return it }
        val existing = targets.get()
        setTargets(currentCover = coverRef, upcomingCovers = existing.upcoming)
        repeat(45) {
            peekOutpaintedFile(coverRef)?.let { return it }
            delay(2_000L)
        }
        return peekOutpaintedFile(coverRef)
    }

    suspend fun getOrGenerate(
        sourceBytes: ByteArray,
        generate: suspend (ByteArray) -> ByteArray?,
    ): File? = cache.getOrEnqueue(sourceBytes, generate)

    fun peekCached(sourceBytes: ByteArray): File? = cache.cachedFile(sourceBytes)

    private fun kickWorker() {
        scope.launch {
            workerMutex.withLock {
                if (workerJob?.isActive == true) return@withLock
                workerJob = scope.launch(Dispatchers.IO) {
                    try {
                        drainQueue()
                    } finally {
                        workerMutex.withLock {
                            workerJob = null
                        }
                        // Restart only when setTargets raced us and left real uncached work.
                        if (hasUncachedWork(targets.get())) {
                            kickWorker()
                        }
                    }
                }
            }
        }
    }

    private suspend fun drainQueue() {
        // Feature gated on Comfy URL (outpaint enabled), but generation is local edge pad.
        if (comfyUiUrl().isBlank()) return
        while (true) {
            val plan = targets.get()
            val nextRef = pickUncachedTarget(plan) ?: break
            val generated = runCatching {
                val source = fetchCoverBytes(nextRef)
                if (source == null) {
                    failedRefs.add(nextRef)
                    return@runCatching false
                }
                if (cache.cachedFile(source) != null) {
                    warmRefs.add(nextRef)
                    return@runCatching true
                }
                val file = cache.getOrEnqueue(source) { bytes ->
                    // Local edge pad only — Flux often invents beige and thrashes Comfy.
                    AlbumArtLocalOutpaint.padFromEdges(bytes)
                }
                if (file != null) {
                    warmRefs.add(nextRef)
                    true
                } else {
                    failedRefs.add(nextRef)
                    false
                }
            }.getOrDefault(false)
            if (!generated && !warmRefs.contains(nextRef)) {
                failedRefs.add(nextRef)
            }
        }
    }

    private suspend fun hasUncachedWork(plan: OutpaintTargets): Boolean =
        pickUncachedTarget(plan) != null

    private suspend fun pickUncachedTarget(plan: OutpaintTargets): String? {
        for (ref in plan.upcoming) {
            if (!isCoverCached(ref)) return ref
        }
        val current = plan.current ?: return null
        return if (!isCoverCached(current)) current else null
    }

    private suspend fun isCoverCached(coverRef: String): Boolean {
        if (warmRefs.contains(coverRef) || failedRefs.contains(coverRef)) return true
        val source = fetchCoverBytes(coverRef) ?: run {
            failedRefs.add(coverRef)
            return true // unresolvable → skip
        }
        val hit = cache.cachedFile(source) != null
        if (hit) warmRefs.add(coverRef)
        return hit
    }

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
        haClient.authenticatedBytes(url)?.let { return@withContext it }
        runCatching {
            val builder = Request.Builder().url(url).get()
            val base = haClient.currentBaseUrl.trimEnd('/')
            if (base.isNotBlank() && url.startsWith(base)) {
                haClient.bearerHeaders().forEach { (k, v) -> builder.header(k, v) }
                haClient.massIngressHeaders().forEach { (k, v) -> builder.header(k, v) }
            } else if (url.contains("/api/") || url.contains("/imageproxy")) {
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

    private data class OutpaintTargets(
        val current: String? = null,
        val upcoming: List<String> = emptyList(),
    )

    companion object {
        const val MAX_UPCOMING = 5

        fun imageFetchClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val privateHost = NetworkGuard.isPrivateHost(host)
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

/**
 * Pick the next cover to generate: first uncached upcoming, else uncached current.
 * When upcoming entries are already cached, [current] is chosen (backfill).
 */
fun nextOutpaintTarget(
    upcoming: List<String>,
    current: String?,
    isCached: (String) -> Boolean,
): String? {
    for (ref in upcoming) {
        if (!isCached(ref)) return ref
    }
    val cur = current?.takeIf { it.isNotBlank() } ?: return null
    return if (!isCached(cur)) cur else null
}
