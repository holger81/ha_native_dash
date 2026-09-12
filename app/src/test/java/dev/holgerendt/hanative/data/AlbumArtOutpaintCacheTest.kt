package dev.holgerendt.hanative.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class AlbumArtOutpaintCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missGeneratesAndHitSkipsGenerator() = runBlocking {
        val cache = AlbumArtOutpaintCache(tmp.newFolder("outpaint"))
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val calls = AtomicInteger(0)
        val first = cache.getOrEnqueue(source) {
            calls.incrementAndGet()
            byteArrayOf(9, 9, 9)
        }
        assertNotNull(first)
        assertEquals(1, calls.get())
        val second = cache.getOrEnqueue(source) {
            calls.incrementAndGet()
            byteArrayOf(1)
        }
        assertNotNull(second)
        assertEquals(first!!.absolutePath, second!!.absolutePath)
        assertEquals(1, calls.get())
        assertTrue(second.readBytes().contentEquals(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun generatorNullLeavesCacheEmpty() = runBlocking {
        val cache = AlbumArtOutpaintCache(tmp.newFolder("outpaint"))
        val source = byteArrayOf(7, 7, 7)
        val result = cache.getOrEnqueue(source) { null }
        assertNull(result)
        assertNull(cache.cachedFile(source))
    }

    @Test
    fun singleFlightSharesOneGenerator() = runBlocking {
        val cache = AlbumArtOutpaintCache(tmp.newFolder("outpaint"))
        val source = byteArrayOf(4, 5, 6)
        val calls = AtomicInteger(0)
        val jobs = (1..8).map {
            async {
                cache.getOrEnqueue(source) {
                    calls.incrementAndGet()
                    delay(80)
                    byteArrayOf(42)
                }
            }
        }
        val files = jobs.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(files.all { it != null })
        assertEquals(1, files.map { it!!.absolutePath }.distinct().size)
    }

    @Test
    fun enforcesMaxFileCount() = runBlocking {
        val dir = tmp.newFolder("outpaint")
        val cache = AlbumArtOutpaintCache(dir, maxFiles = 3, maxBytes = 10_000_000L)
        repeat(5) { i ->
            cache.getOrEnqueue(byteArrayOf(i.toByte())) { byteArrayOf((i + 100).toByte()) }
            // Distinct mtimes so FIFO order is stable.
            Thread.sleep(5)
        }
        val remaining = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.size ?: 0
        assertEquals(3, remaining)
    }
}
