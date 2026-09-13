package dev.holgerendt.hanative.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class ComfyUiOutpaintClientTest {
    private lateinit var server: MockWebServer
    private val workflowJson = """
        {
          "_meta": { "title": "test" },
          "10": {
            "class_type": "LoadImage",
            "inputs": { "image": "PLACEHOLDER.png" }
          },
          "9": {
            "class_type": "SaveImage",
            "inputs": { "filename_prefix": "out", "images": ["8", 0] }
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        // Bind loopback so NetworkGuard.isPrivateHost accepts the URL.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun outpaintUploadPromptHistoryView() = runBlocking {
        val base = "http://127.0.0.1:${server.port}"
        assertTrue(NetworkGuard.isPrivateHost("127.0.0.1"))

        server.enqueue(
            MockResponse().setBody("""{"name":"album_cover.png","subfolder":"","type":"input"}"""),
        )
        server.enqueue(MockResponse().setBody("""{"prompt_id":"pid-1"}"""))
        server.enqueue(MockResponse().setBody("""{"pid-1":{"outputs":{}}}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"pid-1":{"outputs":{"9":{"images":[{"filename":"ha_album_outpaint_00001_.png","subfolder":"","type":"output"}]}}}}""",
            ),
        )
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        server.enqueue(MockResponse().setBody(Buffer().write(png)))

        val client = ComfyUiOutpaintClient(
            workflowLoader = { workflowJson },
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            pollIntervalMs = 10L,
            pollTimeoutMs = 5_000L,
        )

        val result = client.outpaint(base, byteArrayOf(1, 2, 3))
        assertNotNull(result)
        assertTrue(result!!.contentEquals(png))

        val upload = server.takeRequest()
        assertEquals("/upload/image", upload.path)
        assertTrue(upload.body.readUtf8().contains("album_cover"))

        val prompt = server.takeRequest()
        assertEquals("/prompt", prompt.path)
        val promptBody = prompt.body.readUtf8()
        assertTrue(promptBody.contains("album_cover.png"))
        assertTrue(promptBody.contains("LoadImage"))

        assertEquals("/history/pid-1", server.takeRequest().path)
        assertEquals("/history/pid-1", server.takeRequest().path)
        val view = server.takeRequest()
        assertTrue(view.path!!.startsWith("/view?"))
        assertTrue(view.path!!.contains("ha_album_outpaint_00001_.png"))
    }

    @Test
    fun prepareWorkflowInjectsImageAndEdgeFaithfulPrompt() {
        val template = """
            {
              "_meta": { "title": "test" },
              "17": {
                "class_type": "LoadImage",
                "inputs": { "image": "PLACEHOLDER.png" }
              },
              "23": {
                "class_type": "CLIPTextEncode",
                "inputs": { "text": "PLACEHOLDER", "clip": ["34", 0] }
              }
            }
        """.trimIndent()
        val prepared = ComfyUiOutpaintClient.prepareWorkflow(
            Json.parseToJsonElement(template).jsonObject,
            imageName = "cover_xyz.png",
        )
        val load = prepared["17"]!!.jsonObject["inputs"]!!.jsonObject
        val clip = prepared["23"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals("cover_xyz.png", load["image"]!!.jsonPrimitive.content)
        assertEquals(ComfyUiOutpaintClient.OUTPAINT_PROMPT, clip["text"]!!.jsonPrimitive.content)
        assertTrue(clip["text"]!!.jsonPrimitive.content.contains("solid color"))
        assertTrue(clip["text"]!!.jsonPrimitive.content.contains("continue that scene"))
        assertNull(prepared["_meta"])
    }

    @Test
    fun blankBaseReturnsNull() = runBlocking {
        val client = ComfyUiOutpaintClient(
            workflowLoader = { workflowJson },
            pollIntervalMs = 10L,
            pollTimeoutMs = 100L,
        )
        assertNull(client.outpaint("", byteArrayOf(1)))
    }
}
