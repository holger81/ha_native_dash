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
 * Resolves cover art, checks the on-disk outpaint cache, and generates pads:
 * 1. Uniform / black-frame covers → instant local solid pad (no Comfy)
 * 2. Pictorial covers → ComfyUI Flux fill in the background; reject lazy beige
 *    and fall back to local edge-mean pads
 *
 * Priority: now-playing cover first, then upcoming (+1 … +5).
 *
 * Never throws for network/Comfy failures — returns null so UI keeps the interim
 * soft local treatment until a pad is cached.
 *
 * Each cover ref is attempted at most once per process after a hard failure,
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
    /** Pictorial covers that already received a Flux upgrade attempt this process. */
    private val fluxAttemptedRefs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Update the generation plan from the **active playlist only**.
     * At most [MAX_PLAYLIST_OUTPAINT] covers total: now-playing (if any) plus the
     * next tracks, never more.
     */
    fun setTargets(currentCover: String?, upcomingCovers: List<String>) {
        if (comfyUiUrl().isBlank()) return
        val plan = playlistOutpaintPlan(currentCover, upcomingCovers)
        targets.set(OutpaintTargets(current = plan.current, upcoming = plan.upcoming))
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
        val base = comfyUiUrl().trim().trimEnd('/')
        if (base.isBlank()) return
        while (true) {
            val plan = targets.get()
            val nextRef = pickUncachedTarget(plan) ?: break
            val generated = runCatching {
                val source = fetchCoverBytes(nextRef)
                if (source == null) {
                    failedRefs.add(nextRef)
                    return@runCatching false
                }
                val file = warmCover(base, nextRef, source)
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

    /**
     * Instant local edge pad for the UI, then optional Comfy Flux upgrade for
     * pictorial covers (skipped for black/studio mattes). Lazy beige fills are rejected.
     */
    private suspend fun warmCover(comfyBase: String, coverRef: String, source: ByteArray): File? {
        val local = cache.getOrEnqueue(source) { bytes ->
            AlbumArtLocalOutpaint.padFromEdges(bytes)
        } ?: return null
        if (AlbumArtLocalOutpaint.hasUniformEdges(source)) {
            fluxAttemptedRefs.add(coverRef)
            return local
        }
        if (!fluxAttemptedRefs.add(coverRef)) return local
        // Empty-prompt Flux is usually good but seed-dependent; retry a few times
        // before keeping the local edge pad (never accept invented cream).
        repeat(FLUX_ATTEMPTS) {
            val flux = runCatching { comfy.outpaint(comfyBase, source) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@repeat
            if (!AlbumArtLocalOutpaint.isPadColorMismatch(flux, source)) {
                return cache.replace(source, flux) ?: local
            }
        }
        return local
    }

    private suspend fun hasUncachedWork(plan: OutpaintTargets): Boolean =
        pickUncachedTarget(plan) != null

    private suspend fun pickUncachedTarget(plan: OutpaintTargets): String? {
        // Now-playing first so the home card warms before queue prefetch.
        val current = plan.current
        if (current != null && !isCoverFullyWarm(current)) return current
        for (ref in plan.upcoming) {
            if (!isCoverFullyWarm(ref)) return ref
        }
        return null
    }

    private suspend fun isCoverFullyWarm(coverRef: String): Boolean {
        if (warmRefs.contains(coverRef) || failedRefs.contains(coverRef)) return true
        val source = fetchCoverBytes(coverRef) ?: run {
            failedRefs.add(coverRef)
            return true
        }
        val hit = cache.cachedFile(source) ?: return false
        // Local pad present: still need one Flux attempt for pictorial covers.
        if (AlbumArtLocalOutpaint.hasUniformEdges(source) || fluxAttemptedRefs.contains(coverRef)) {
            warmRefs.add(coverRef)
            return true
        }
        return false
    }

    private suspend fun isCoverCached(coverRef: String): Boolean = isCoverFullyWarm(coverRef)

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
        /**
         * Hard cap on covers warmed from the active playlist (now-playing + next).
         * Also the max upcoming rows fetched from Music Assistant.
         */
        const val MAX_PLAYLIST_OUTPAINT = 5

        /** @deprecated Use [MAX_PLAYLIST_OUTPAINT]. */
        const val MAX_UPCOMING = MAX_PLAYLIST_OUTPAINT

        /** Max Flux tries per cover before keeping the local edge pad. */
        const val FLUX_ATTEMPTS = 3

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
 * Cap playlist outpaint targets to [maxTotal] covers: [current] (optional) plus
 * the next distinct upcoming URLs, in order.
 */
fun playlistOutpaintPlan(
    currentCover: String?,
    upcomingCovers: List<String>,
    maxTotal: Int = AlbumArtOutpaintRepository.MAX_PLAYLIST_OUTPAINT,
): PlaylistOutpaintPlan {
    val current = currentCover?.trim()?.takeIf { it.isNotBlank() }
    val upcomingBudget = if (current != null) (maxTotal - 1).coerceAtLeast(0) else maxTotal
    val upcoming = upcomingCovers
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .filter { it != current }
        .take(upcomingBudget)
    return PlaylistOutpaintPlan(current = current, upcoming = upcoming)
}

data class PlaylistOutpaintPlan(
    val current: String?,
    val upcoming: List<String>,
)

/**
 * Pick the next cover to generate: now-playing first, then first uncached upcoming.
 */
fun nextOutpaintTarget(
    upcoming: List<String>,
    current: String?,
    isCached: (String) -> Boolean,
): String? {
    val cur = current?.takeIf { it.isNotBlank() }
    if (cur != null && !isCached(cur)) return cur
    for (ref in upcoming) {
        if (!isCached(ref)) return ref
    }
    return null
}
