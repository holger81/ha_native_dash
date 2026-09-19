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

    @Test
    fun outpaintPostsMultipartAndParsesFluxHeaders() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 1, 2, 3)
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "flux")
                .setHeader(MediagenOutpaintClient.HEADER_HASH, "abc123")
                .setBody(Buffer().write(jpeg)),
        )
        val client = MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        val base = "http://127.0.0.1:${server.port}"
        val result = client.outpaint(base, byteArrayOf(1, 2, 3, 4))
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
        val client = MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        val result = client.outpaint("http://127.0.0.1:${server.port}", byteArrayOf(1))
        assertEquals(MediagenOutpaintSource.Local, result!!.source)
    }

    @Test
    fun blankBaseReturnsNull() = runBlocking {
        val client = MediagenOutpaintClient()
        assertNull(client.outpaint("", byteArrayOf(1)))
    }

    @Test
    fun publicHostReturnsNullWithoutRequest() = runBlocking {
        val client = MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .addInterceptor(NetworkGuard.interceptor)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
        assertNull(client.outpaint("https://example.com", byteArrayOf(1, 2, 3)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun defaultClientUsesLongCallTimeout() {
        val client = MediagenOutpaintClient.defaultClient()
        assertEquals(
            MediagenOutpaintClient.CALL_TIMEOUT_SECONDS * 1000L,
            client.callTimeoutMillis.toLong(),
        )
    }

    @Test
    fun mediaHashIsStableForSameBytes() {
        val a = MediagenOutpaintClient.mediaHash(byteArrayOf(1, 2, 3))
        val b = MediagenOutpaintClient.mediaHash(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    @Test
    fun getCachedHitsVersionedPath() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val source = byteArrayOf(4, 5, 6)
        val hash = MediagenOutpaintClient.mediaHash(source)
        server.enqueue(
            MockResponse()
                .setHeader(MediagenOutpaintClient.HEADER_SOURCE, "flux")
                .setBody(Buffer().write(jpeg)),
        )
        val client = MediagenOutpaintClient(
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        val result = client.getCached("http://127.0.0.1:${server.port}", source)
        assertNotNull(result)
        assertEquals(MediagenOutpaintSource.Flux, result!!.source)
        assertEquals("/v1/image/outpaint/$hash", server.takeRequest().path)
    }
}
