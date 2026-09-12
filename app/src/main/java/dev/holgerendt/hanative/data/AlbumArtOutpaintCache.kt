package dev.holgerendt.hanative.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for ComfyUI outpainted album art.
 * Keyed by SHA-256 of the source cover bytes; single-flight per hash.
 */
class AlbumArtOutpaintCache(
    private val directory: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val dirMutex = Mutex()
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    init {
        directory.mkdirs()
    }

    fun cachedFile(sourceBytes: ByteArray): File? {
        val hash = sha256Hex(sourceBytes)
        val file = fileFor(hash)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Returns a cached outpaint file, or generates and stores one.
     * [generate] returning null leaves the cache empty (caller keeps soft local treatment).
     */
    suspend fun getOrEnqueue(
        sourceBytes: ByteArray,
        generate: suspend (ByteArray) -> ByteArray?,
    ): File? {
        val hash = sha256Hex(sourceBytes)
        cachedFileForHash(hash)?.let { return it }

        val flight = inFlight.getOrPut(hash) { Mutex() }
        return try {
            flight.withLock {
                cachedFileForHash(hash)?.let { return@withLock it }
                val generated = generate(sourceBytes) ?: return@withLock null
                if (generated.isEmpty()) return@withLock null
                dirMutex.withLock {
                    directory.mkdirs()
                    val target = fileFor(hash)
                    val tmp = File(directory, "$hash.tmp")
                    tmp.writeBytes(generated)
                    if (!tmp.renameTo(target)) {
                        target.writeBytes(generated)
                        tmp.delete()
                    }
                    enforceLimitsLocked()
                    target.takeIf { it.isFile && it.length() > 0L }
                }
            }
        } finally {
            inFlight.remove(hash, flight)
        }
    }

    private fun cachedFileForHash(hash: String): File? {
        val file = fileFor(hash)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun fileFor(hash: String): File = File(directory, "$hash.jpg")

    private fun enforceLimitsLocked() {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
            .toMutableList()
        var total = files.sumOf { it.length() }
        while (files.size > maxFiles || total > maxBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            total -= oldest.length()
            oldest.delete()
        }
    }

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
