package dev.holgerendt.hanative.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for outpainted album art (local pad and mediagen Flux).
 * Keyed by [OutpaintPads.OUTPAINT_CACHE_VERSION] + layout tag + source bytes;
 * single-flight per hash. Writes a `.pads` sidecar so the UI can place the
 * floating cover on the baked-in region.
 */
class AlbumArtOutpaintCache(
    private val directory: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Long = MAX_BYTES,
    private val cacheVersion: String = OutpaintPads.OUTPAINT_CACHE_VERSION,
    private val useCoverAliases: Boolean = true,
) {
    private val dirMutex = Mutex()
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    init {
        directory.mkdirs()
    }

    fun cachedFile(
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ): File? {
        val hash = cacheKey(sourceBytes, layout)
        val file = fileFor(hash)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Remember which cover ref produced [sourceBytes] so a later lookup with the
     * same ref (or stable MASS/HA id) can find the pad without re-fetching.
     */
    fun bindCoverRef(coverRef: String, sourceBytes: ByteArray, layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes)) {
        if (!useCoverAliases) return
        val trimmed = coverRef.trim()
        if (trimmed.isEmpty()) return
        val hash = cacheKey(sourceBytes, layout)
        if (cachedFileForHash(hash) == null) return
        runCatching {
            directory.mkdirs()
            refFileFor(trimmed).writeText(hash)
            stableOutpaintCoverKey(trimmed)?.let { stable ->
                stableFileFor(stable).writeText(hash)
            }
        }
    }

    /** Lookup by cover ref alias written in [bindCoverRef]. */
    fun cachedFileForCoverRef(coverRef: String): File? {
        if (!useCoverAliases) return null
        val trimmed = coverRef.trim()
        if (trimmed.isEmpty()) return null
        cachedFileForRefHash(refFileFor(trimmed))?.let { return it }
        return stableOutpaintCoverKey(trimmed)?.let { cachedFileForStableKey(it) }
    }

    fun cachedFileForStableKey(stableKey: String): File? =
        cachedFileForRefHash(stableFileFor(stableKey))

    fun isFluxCompleteForCoverRef(coverRef: String): Boolean {
        if (!useCoverAliases) return false
        val trimmed = coverRef.trim()
        if (trimmed.isEmpty()) return false
        if (isFluxCompleteForRefFile(refFileFor(trimmed))) return true
        val stable = stableOutpaintCoverKey(trimmed) ?: return false
        return isFluxCompleteForStableKey(stable)
    }

    fun isFluxCompleteForStableKey(stableKey: String): Boolean =
        isFluxCompleteForRefFile(stableFileFor(stableKey))

    private fun cachedFileForRefHash(aliasFile: File): File? {
        val hash = runCatching {
            aliasFile.takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return cachedFileForHash(hash)
    }

    private fun isFluxCompleteForRefFile(aliasFile: File): Boolean {
        val hash = runCatching {
            aliasFile.takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        return fluxMarkerFor(hash).isFile && cachedFileForHash(hash) != null
    }

    /** True after a successful Flux upgrade was written for this source. */
    fun isFluxComplete(
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ): Boolean = fluxMarkerFor(cacheKey(sourceBytes, layout)).isFile

    fun markFluxComplete(
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ) {
        val hash = cacheKey(sourceBytes, layout)
        if (cachedFileForHash(hash) == null) return
        runCatching {
            directory.mkdirs()
            fluxMarkerFor(hash).createNewFile()
        }
    }

    /** Pads used when [file] was written (from `.pads` sidecar). */
    fun readPadsForFile(file: File): OutpaintPadLayout? {
        val hash = file.name.removeSuffix(".jpg")
        if (hash.isEmpty() || hash == file.name) return null
        return readPadsForHash(hash)
    }

    fun readPadsForHash(hash: String): OutpaintPadLayout? {
        val path = padsMarkerFor(hash)
        if (!path.isFile) return null
        return OutpaintPadLayout.parseHeader(runCatching { path.readText() }.getOrNull())
    }

    /**
     * Returns a cached outpaint file, or generates and stores one.
     * [generate] returning null leaves the cache empty (caller keeps soft local treatment).
     */
    suspend fun getOrEnqueue(
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
        generate: suspend (ByteArray) -> ByteArray?,
    ): File? {
        val hash = cacheKey(sourceBytes, layout)
        cachedFileForHash(hash)?.let { return it }

        val flight = inFlight.getOrPut(hash) { Mutex() }
        return try {
            flight.withLock {
                cachedFileForHash(hash)?.let { return@withLock it }
                val generated = generate(sourceBytes) ?: return@withLock null
                writeBytesLocked(hash, generated, layout)
            }
        } finally {
            inFlight.remove(hash, flight)
        }
    }

    /** Overwrite an existing cache entry (e.g. Flux upgrade after a local pad). */
    suspend fun replace(
        sourceBytes: ByteArray,
        generated: ByteArray,
        markAsFlux: Boolean = true,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ): File? {
        if (generated.isEmpty()) return null
        val hash = cacheKey(sourceBytes, layout)
        val flight = inFlight.getOrPut(hash) { Mutex() }
        return try {
            flight.withLock {
                writeBytesLocked(hash, generated, layout)?.also {
                    if (markAsFlux) fluxMarkerFor(hash).createNewFile()
                }
            }
        } finally {
            inFlight.remove(hash, flight)
        }
    }

    /** Drop a bad Flux (or local) pad so the next warm can regenerate. */
    suspend fun invalidate(
        sourceBytes: ByteArray,
        layout: OutpaintPadLayout = MusicPlayerOutpaint.padsForSourceBytes(sourceBytes),
    ) {
        val hash = cacheKey(sourceBytes, layout)
        val flight = inFlight.getOrPut(hash) { Mutex() }
        try {
            flight.withLock {
                dirMutex.withLock {
                    fileFor(hash).delete()
                    fluxMarkerFor(hash).delete()
                    padsMarkerFor(hash).delete()
                }
            }
        } finally {
            inFlight.remove(hash, flight)
        }
    }

    private suspend fun writeBytesLocked(
        hash: String,
        generated: ByteArray,
        layout: OutpaintPadLayout,
    ): File? {
        if (generated.isEmpty()) return null
        return dirMutex.withLock {
            directory.mkdirs()
            val target = fileFor(hash)
            val tmp = File(directory, "$hash.tmp")
            tmp.writeBytes(generated)
            if (!tmp.renameTo(target)) {
                target.writeBytes(generated)
                tmp.delete()
            }
            // Force a distinct mtime so SoftAtmosphere's length⊕mtime stamp moves even when
            // the replacement JPEG is the same size as the local pad (common on 1s FS clocks).
            target.setLastModified(System.currentTimeMillis())
            padsMarkerFor(hash).writeText(layout.headerPad())
            enforceLimitsLocked()
            target.takeIf { it.isFile && it.length() > 0L }
        }
    }

    private fun cachedFileForHash(hash: String): File? {
        val file = fileFor(hash)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun fileFor(hash: String): File = File(directory, "$hash.jpg")

    private fun fluxMarkerFor(hash: String): File = File(directory, "$hash.flux")

    private fun padsMarkerFor(hash: String): File = File(directory, "$hash.pads")

    private fun cacheKey(sourceBytes: ByteArray, layout: OutpaintPadLayout): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(cacheVersion.toByteArray(Charsets.UTF_8))
        digest.update(layout.layoutTag())
        digest.update(sourceBytes)
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun enforceLimitsLocked() {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
            .toMutableList()
        var total = files.sumOf { it.length() }
        while (files.size > maxFiles || total > maxBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            total -= oldest.length()
            val hash = oldest.name.removeSuffix(".jpg")
            oldest.delete()
            File(directory, "$hash.flux").delete()
            File(directory, "$hash.pads").delete()
        }
    }

    private fun refFileFor(coverRef: String): File =
        File(directory, "${sha256Hex(coverRef.toByteArray(Charsets.UTF_8))}.ref")

    private fun stableFileFor(stableKey: String): File =
        File(directory, "${sha256Hex(stableKey.toByteArray(Charsets.UTF_8))}.sid")

    companion object {
        const val MAX_FILES = 100
        const val MAX_BYTES = 200L * 1024L * 1024L
        const val DIR_NAME = "outpaint_cache"

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
