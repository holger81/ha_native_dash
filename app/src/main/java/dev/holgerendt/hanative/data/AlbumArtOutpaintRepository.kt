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
 * 1. Uniform / black-frame covers → instant local solid pad (no mediagen)
 * 2. Pictorial covers → mediagen Flux fill in the background; keep local pad
 *    when the server returns local/fail
 *
 * Priority: now-playing cover first, then upcoming (+1 … +5).
 *
 * Never throws for network/mediagen failures — returns null so UI keeps the interim
 * soft local treatment until a pad is cached.
 *
 * Each cover ref is attempted at most once per process after a hard failure,
 * and warm refs are remembered so flaky re-downloads cannot re-queue the same art.
 */
class AlbumArtOutpaintRepository(
    context: Context,
    private val haClient: HaClient,
    private val mediagenUrl: () -> String,
    private val scope: CoroutineScope,
    private val cache: AlbumArtOutpaintCache = AlbumArtOutpaintCache(
        RecoverableFiles.outpaintCacheDir(context),
    ),
    private val mediagen: MediagenOutpaintClient = MediagenOutpaintClient(),
    private val imageHttp: OkHttpClient = imageFetchClient(),
) {
    private val workerMutex = Mutex()
    private val targets = AtomicReference(OutpaintTargets())
    private var workerJob: Job? = null
    /** Cover refs that already have a successful cache hit or generation this process. */
    private val warmRefs = ConcurrentHashMap.newKeySet<String>()
    /** Cover refs that failed mediagen/fetch this process — do not hammer the LAN API. */
    private val failedRefs = ConcurrentHashMap.newKeySet<String>()
    /** Pictorial covers that already received a Flux upgrade attempt this process. */
    private val fluxAttemptedRefs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Update the generation plan from the **active playlist only**.
     * At most [MAX_PLAYLIST_OUTPAINT] covers total: now-playing (if any) plus the
     * next tracks, never more.
     */
    fun setTargets(currentCover: String?, upcomingCovers: List<String>) {
        if (mediagenUrl().isBlank()) return
        val plan = playlistOutpaintPlan(currentCover, upcomingCovers)
        val next = OutpaintTargets(current = plan.current, upcoming = plan.upcoming)
        // Home media watch reschedules every few seconds; skip churn when the plan is unchanged.
        if (targets.get() == next) {
            kickWorker()
            return
        }
        // New playlist window — allow re-fetch of covers that failed earlier
        // (transient HA/MASS blips used to blacklist a URL for the whole process).
        failedRefs.removeAll(buildSet {
            plan.current?.let { add(it) }
            addAll(plan.upcoming)
        })
        targets.set(next)
        kickWorker()
    }

    /** Cache-only lookup — never starts mediagen. Tries each cover ref until one hits. */
    suspend fun peekOutpaintedFile(coverRef: String?): File? =
        peekOutpaintedFile(listOf(coverRef))

    /**
     * Look up a cached pad by any of [coverRefs] (MASS URL and HA entity_picture
     * often differ for the same track — prefetch may have warmed one while the
     * card peeks the other).
     */
    suspend fun peekOutpaintedFile(coverRefs: Collection<String?>): File? {
        if (mediagenUrl().isBlank()) return null
        val refs = coverRefs.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        if (refs.isEmpty()) return null
        // Stable MASS/HA ids — no network, instant reuse of an already-warmed pad.
        for (ref in refs) {
            cache.cachedFileForCoverRef(ref)?.let { return it }
        }
        for (ref in refs) {
            val source = fetchCoverBytes(ref) ?: continue
            val hit = cache.cachedFile(source) ?: continue
            cache.bindCoverRef(ref, source)
            for (other in refs) {
                if (other == ref) continue
                val otherSource = fetchCoverBytes(other) ?: continue
                if (cache.cachedFile(otherSource)?.absolutePath == hit.absolutePath) {
                    cache.bindCoverRef(other, otherSource)
                }
            }
            return hit
        }
        return null
    }

    /** True when the cached pad was written by Flux (`.flux` sidecar), not a local edge pad. */
    suspend fun peekFluxComplete(coverRef: String?): Boolean =
        peekFluxComplete(listOf(coverRef))

    suspend fun peekFluxComplete(coverRefs: Collection<String?>): Boolean {
        if (mediagenUrl().isBlank()) return false
        val refs = coverRefs.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        if (refs.isEmpty()) return false
        if (refs.any { cache.isFluxCompleteForCoverRef(it) }) return true
        for (ref in refs) {
            val source = fetchCoverBytes(ref) ?: continue
            if (cache.isFluxComplete(source) && cache.cachedFile(source) != null) {
                cache.bindCoverRef(ref, source)
                return true
            }
        }
        return false
    }

    /**
     * Prefer cache; if missing, register [coverRef] as current backfill and poll
     * while the priority worker runs (upcoming still goes first).
     */
    suspend fun getOutpaintedFile(coverRef: String?): File? {
        if (coverRef.isNullOrBlank() || mediagenUrl().isBlank()) return null
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
        val base = mediagenUrl().trim().trimEnd('/')
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
                    cache.bindCoverRef(nextRef, source)
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
     * Instant local edge pad for the UI, then optional mediagen Flux upgrade for
     * pictorial covers (skipped for black/studio mattes). Server-local / failed
     * responses keep the on-device local pad.
     *
     * Disk hits: keep textured / Flux pads; solid local pads still get one Flux
     * upgrade attempt (otherwise the instant pad would freeze forever).
     */
    private suspend fun warmCover(mediagenBase: String, coverRef: String, source: ByteArray): File? {
        // Same MASS proxy id / HA path — reuse without caring that JPEG bytes drifted.
        cache.cachedFileForCoverRef(coverRef)?.let { stableHit ->
            if (cache.isFluxCompleteForCoverRef(coverRef)) {
                fluxAttemptedRefs.add(coverRef)
                cache.bindCoverRef(coverRef, source)
                return stableHit
            }
        }
        val existing = cache.cachedFile(source)
        if (existing != null && isFluxPadSettled(source, existing)) {
            val settledBytes = withContext(Dispatchers.IO) {
                runCatching { existing.readBytes() }.getOrNull()
            }
            // Old / invented Flux pads used to stick forever via the .flux sidecar.
            if (settledBytes != null &&
                settledBytes.isNotEmpty() &&
                !AlbumArtLocalOutpaint.shouldRejectFluxPad(settledBytes, source)
            ) {
                fluxAttemptedRefs.add(coverRef)
                cache.bindCoverRef(coverRef, source)
                return existing
            }
            cache.invalidate(source)
        }
        // Prefer an already-warmed local pad under the stable id over regenerating.
        val stableLocal = cache.cachedFileForCoverRef(coverRef)
        val local = cache.cachedFile(source)
            ?: stableLocal
            ?: cache.getOrEnqueue(source) { bytes ->
                AlbumArtLocalOutpaint.padFromEdges(bytes)
            }
            ?: return null
        cache.bindCoverRef(coverRef, source)
        if (AlbumArtLocalOutpaint.hasUniformEdges(source)) {
            fluxAttemptedRefs.add(coverRef)
            return local
        }
        if (!fluxAttemptedRefs.add(coverRef)) return local
        // If stable id already has Flux we returned above. Only run mediagen when missing.
        if (cache.isFluxCompleteForCoverRef(coverRef)) {
            return cache.cachedFileForCoverRef(coverRef) ?: local
        }
        // One mediagen pass. Trust server gating for Flux; keep on-device local
        // when the API returns local / fails / unknown.
        val result = runCatching { mediagen.outpaint(mediagenBase, source) }.getOrNull()
            ?.takeIf { it.bytes.isNotEmpty() }
        if (result != null && result.source == MediagenOutpaintSource.Flux) {
            return cache.replace(source, result.bytes, markAsFlux = true)?.also {
                cache.bindCoverRef(coverRef, source)
            } ?: local
        }
        return local
    }

    /** True when the on-disk pad should not be regenerated (Flux only). */
    private fun isFluxPadSettled(source: ByteArray, cached: File): Boolean {
        // Only the `.flux` sidecar means a generative upgrade landed. Local edge
        // pads are textured enough to look "done" and used to freeze upgrades.
        if (!cached.isFile || cached.length() <= 0L) return false
        return cache.isFluxComplete(source)
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
        // Flux already on disk for this MASS/HA id — do not re-queue mediagen.
        if (cache.isFluxCompleteForCoverRef(coverRef) &&
            cache.cachedFileForCoverRef(coverRef) != null
        ) {
            warmRefs.add(coverRef)
            fluxAttemptedRefs.add(coverRef)
            return true
        }
        val source = fetchCoverBytes(coverRef) ?: run {
            // Still warm if a stable alias exists (offline / flaky fetch).
            if (cache.cachedFileForCoverRef(coverRef) != null) {
                warmRefs.add(coverRef)
                return true
            }
            failedRefs.add(coverRef)
            return true
        }
        val cached = cache.cachedFile(source) ?: cache.cachedFileForCoverRef(coverRef) ?: return false
        if (AlbumArtLocalOutpaint.hasUniformEdges(source)) {
            warmRefs.add(coverRef)
            fluxAttemptedRefs.add(coverRef)
            cache.bindCoverRef(coverRef, source)
            return true
        }
        if (isFluxPadSettled(source, cached) || cache.isFluxCompleteForCoverRef(coverRef)) {
            warmRefs.add(coverRef)
            fluxAttemptedRefs.add(coverRef)
            cache.bindCoverRef(coverRef, source)
            return true
        }
        // Solid local pad still waiting for a Flux upgrade this process.
        if (fluxAttemptedRefs.contains(coverRef)) {
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
        const val MAX_PLAYLIST_OUTPAINT = 10

        /** @deprecated Use [MAX_PLAYLIST_OUTPAINT]. */
        const val MAX_UPCOMING = MAX_PLAYLIST_OUTPAINT

        /** @deprecated Retries removed; empty-prompt Flux is accepted on first pass. */
        const val FLUX_ATTEMPTS = 1

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
