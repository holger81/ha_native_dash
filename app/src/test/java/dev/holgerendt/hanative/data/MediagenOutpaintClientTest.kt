package dev.holgerendt.hanative.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class MediagenOutpaintClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        pollTimeoutMs: Long = 5_000L,
        defaultRetryAfterMs: Long = 50L,
    ): MediagenOutpaintClient =
        MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            pollTimeoutMs = pollTimeoutMs,
            defaultRetryAfterMs = defaultRetryAfterMs,
        )

    @Test
    fun outpaintPostsMultipartAndParsesFluxHeaders() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 1, 2, 3)
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "flux")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, "abc123")
                .setHeader(MediagenOutpaintClient.HEADER_STATUS, "ready")
                .setBody(Buffer().write(jpeg)),
        )
        val base = "http://127.0.0.1:${server.port}"
        val result = client().outpaint(base, byteArrayOf(1, 2, 3, 4))
        assertNotNull(result)
        assertTrue(result!!.bytes.contentEquals(jpeg))
        assertEquals(MediagenOutpaintSource.Flux, result.source)
        assertEquals("abc123", result.mediaHash)

        val request = server.takeRequest()
        assertEquals("/v1/image/outpaint", request.path)
        assertTrue(request.method == "POST")
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"image\""))
        assertTrue(body.contains("album_cover.jpg"))
    }

    @Test
    fun outpaintParsesLocalSource() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "local")
                .setBody(Buffer().write(byteArrayOf(9, 8, 7))),
        )
        val result = client().outpaint("http://127.0.0.1:${server.port}", byteArrayOf(1))
        assertEquals(MediagenOutpaintSource.Local, result!!.source)
    }

    @Test
    fun outpaintPollsAfter202Generating() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 4, 5)
        val hash = "b".repeat(64)
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Retry-After", "1")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, hash)
                .setHeader(MediagenOutpaintClient.HEADER_STATUS, "generating")
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"generating","hash":"$hash","retry_after_s":1}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Retry-After", "1")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, hash)
                .setBody("""{"status":"generating","hash":"$hash","retry_after_s":1}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "flux")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, hash)
                .setHeader(MediagenOutpaintClient.HEADER_STATUS, "ready")
                .setBody(Buffer().write(jpeg)),
        )

        val result = client(defaultRetryAfterMs = 20L)
            .outpaint("http://127.0.0.1:${server.port}", byteArrayOf(9, 9, 9))
        assertNotNull(result)
        assertTrue(result!!.bytes.contentEquals(jpeg))
        assertEquals(MediagenOutpaintSource.Flux, result.source)
        assertEquals(hash, result.mediaHash)

        assertEquals("POST", server.takeRequest().method)
        val firstGet = server.takeRequest()
        assertEquals("GET", firstGet.method)
        assertEquals("/v1/image/outpaint/$hash", firstGet.path)
        val secondGet = server.takeRequest()
        assertEquals("GET", secondGet.method)
        assertEquals("/v1/image/outpaint/$hash", secondGet.path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun blankBaseReturnsNull() = runBlocking {
        assertNull(client().outpaint("", byteArrayOf(1)))
    }

    @Test
    fun publicHostReturnsNullWithoutRequest() = runBlocking {
        val guarded = MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .addInterceptor(NetworkGuard.interceptor)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
        assertNull(guarded.outpaint("https://example.com", byteArrayOf(1, 2, 3)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun defaultClientUsesShortPerRequestTimeout() {
        val http = MediagenOutpaintClient.defaultClient()
        assertEquals(
            MediagenOutpaintClient.REQUEST_TIMEOUT_SECONDS * 1000L,
            http.callTimeoutMillis.toLong(),
        )
    }

    @Test
    fun mediaHashMatchesConcatVersionAndBytes() {
        val a = MediagenOutpaintClient.mediaHash(byteArrayOf(1, 2, 3))
        val b = MediagenOutpaintClient.mediaHash(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(64, a.length)
        // Must not insert a separator byte (matches mediagen sha256(version||bytes)).
        val other = MediagenOutpaintClient.mediaHash(byteArrayOf(1, 2, 3, 0))
        assertTrue(a != other)
    }

    @Test
    fun getCachedHitsVersionedPath() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val source = byteArrayOf(4, 5, 6)
        val hash = MediagenOutpaintClient.mediaHash(source)
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "local")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, hash)
                .setBody(Buffer().write(jpeg)),
        )
        val result = client().getCached("http://127.0.0.1:${server.port}", source)
        assertNotNull(result)
        assertEquals("/v1/image/outpaint/$hash", server.takeRequest().path)
    }

    @Test
    fun getCachedReturnsNullWhileGenerating() = runBlocking {
        val source = byteArrayOf(7, 8, 9)
        val hash = MediagenOutpaintClient.mediaHash(source)
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader(MediagenOutpaintClient.HEADER_HASH, hash)
                .setBody("""{"status":"generating","hash":"$hash","retry_after_s":5}"""),
        )
        assertNull(client().getCached("http://127.0.0.1:${server.port}", source))
    }
}
