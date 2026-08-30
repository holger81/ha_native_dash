package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.WidgetNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class StreamKind { HLS, MP4, MJPEG, JPEG }

data class StreamCandidate(
    val kind: StreamKind,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val label: String,
)

data class CameraTarget(
    val name: String?,
    val entityId: String?,
    val streamServer: String?,
    val streamName: String?,
    val muted: Boolean = true,
) {
    fun hasLiveSource(): Boolean =
        !streamName.isNullOrBlank() || entityId?.startsWith("camera.") == true

    companion object {
        fun from(widget: WidgetNode): CameraTarget = CameraTarget(
            name = widget.name,
            entityId = widget.entity,
            streamServer = widget.streamServer,
            streamName = widget.streamName,
            muted = widget.muted != false,
        )
    }
}

class CameraStreamException(message: String) : Exception(message)

object CameraStreams {
    private val jpegStart = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jpegEnd = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val resolveCache = ConcurrentHashMap<String, List<StreamCandidate>>()

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    val wallPanelCameras: List<WidgetNode> = listOf(
        WidgetNode(
            type = "camera",
            name = "Front door",
            entity = "camera.reolink_video_doorbell_poe_fluent",
            streamServer = "http://192.168.10.31:1984/",
            streamName = "frontdoor_sub",
            muted = true,
        ),
        WidgetNode(
            type = "camera",
            name = "Garage",
            entity = "camera.garagefront_2",
            streamServer = "http://192.168.10.31:1984/",
            streamName = "garagefront_sub",
            muted = true,
        ),
    )

    fun camerasForPopup(popup: PopupNode): List<WidgetNode> {
        val found = popup.cards.flatMap { collectCameras(it) }
        return if (found.any { fromWidget(it).hasLiveSource() }) found else wallPanelCameras
    }

    fun collectCameras(widget: WidgetNode): List<WidgetNode> {
        val children = widget.cards.flatMap { collectCameras(it) }
        if (children.isNotEmpty()) return children
        val self = widget.type == "camera" ||
            widget.cardType == "custom:webrtc-camera" ||
            fromWidget(widget).hasLiveSource()
        return if (self) listOf(widget) else emptyList()
    }

    fun fromWidget(widget: WidgetNode): CameraTarget = CameraTarget.from(widget)

    fun httpErrorMessage(code: Int, entityId: String?): String = when (code) {
        401, 403 -> "Camera unauthorized — check the Home Assistant token"
        404 -> "Camera not found${entityId?.let { ": $it" } ?: ""}"
        502, 503, 504 -> "Home Assistant or camera is unavailable"
        else -> "Camera stream failed (HTTP $code)"
    }

    suspend fun resolve(client: HaClient, target: CameraTarget): List<StreamCandidate> {
        val key = listOf(
            "hls-jpeg-v1",
            target.streamServer.orEmpty(),
            target.streamName.orEmpty(),
            target.entityId.orEmpty(),
            client.currentBaseUrl,
        ).joinToString("|")
        resolveCache[key]?.let { return it }
        val result = resolveUncached(client, target)
        if (result.isNotEmpty()) resolveCache[key] = result
        return result
    }

    /** Warm stream URLs while the wall is idle so the camera popup opens faster. */
    suspend fun prefetch(client: HaClient, targets: Collection<CameraTarget>) {
        withContext(Dispatchers.IO) {
            targets.forEach { target ->
                if (target.hasLiveSource()) runCatching { resolve(client, target) }
            }
        }
    }

    private suspend fun resolveUncached(client: HaClient, target: CameraTarget): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            val headers = client.bearerHeaders()
            val out = mutableListOf<StreamCandidate>()
            val server = target.streamServer?.trim()?.trimEnd('/')
            val src = target.streamName?.trim()?.takeIf { it.isNotEmpty() }
            if (!server.isNullOrBlank() && src != null) {
                val encoded = URLEncoder.encode(src, Charsets.UTF_8.name())
                // HLS first: this go2rtc build serves H264, and /api/stream.mjpeg returns an empty 200.
                // Skip live MP4 — ExoPlayer often buffers forever on infinite fMP4 without erroring.
                out += StreamCandidate(
                    StreamKind.HLS,
                    "$server/api/stream.m3u8?src=$encoded",
                    emptyMap(),
                    "go2rtc HLS",
                )
                out += StreamCandidate(
                    StreamKind.JPEG,
                    "$server/api/frame.jpeg?src=$encoded",
                    emptyMap(),
                    "go2rtc JPEG",
                )
            }
            val entity = target.entityId
            if (entity?.startsWith("camera.") == true) {
                val mjpeg = "${client.currentBaseUrl}/api/camera_proxy_stream/$entity"
                out += StreamCandidate(StreamKind.MJPEG, mjpeg, headers, "Home Assistant MJPEG")
                if (out.none { it.kind == StreamKind.HLS }) {
                    client.cameraHlsUrl(entity)?.let { url ->
                        out += StreamCandidate(StreamKind.HLS, url, headers, "Home Assistant HLS")
                    }
                }
            }
            out.distinctBy { it.url }
                .filter { candidate -> NetworkGuard.hostOf(candidate.url)?.let(NetworkGuard::isPrivateHost) == true }
        }

    suspend fun readJpeg(url: String, headers: Map<String, String>): Bitmap? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        val call = streamClient.newCall(request)
        call.timeout().timeout(10, TimeUnit.SECONDS)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                throw CameraStreamException(httpErrorMessage(response.code, null))
            }
            val bytes = response.body?.bytes() ?: return@withContext null
            if (bytes.isEmpty() || looksLikeHtmlOrJson(bytes)) return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            call.cancel()
        }
    }

    suspend fun readMjpeg(
        url: String,
        headers: Map<String, String>,
        onFrame: suspend (Bitmap) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }.build()
            val call = streamClient.newCall(request)
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    throw CameraStreamException(httpErrorMessage(response.code, null))
                }
                val body = response.body ?: throw CameraStreamException("Empty camera stream")
                val source = body.source()
                source.timeout().timeout(12, TimeUnit.SECONDS)
                val chunk = ByteArray(16 * 1024)
                val acc = ByteArrayOutputStream()
                var frames = 0
                coroutineContext.job.invokeOnCompletion { call.cancel() }
                while (currentCoroutineContext().isActive) {
                    val n = source.read(chunk)
                    if (n < 0) break
                    acc.write(chunk, 0, n)
                    val data = acc.toByteArray()
                    if (frames == 0 && looksLikeHtmlOrJson(data)) {
                        throw CameraStreamException("Camera stream returned an error page")
                    }
                    var searchFrom = 0
                    while (true) {
                        val soi = indexOf(data, jpegStart, searchFrom)
                        if (soi < 0) break
                        val eoi = indexOf(data, jpegEnd, soi + 2)
                        if (eoi < 0) break
                        val jpeg = data.copyOfRange(soi, eoi + 2)
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bitmap != null) {
                            frames++
                            onFrame(bitmap)
                        }
                        searchFrom = eoi + 2
                    }
                    if (searchFrom > 0) {
                        acc.reset()
                        if (searchFrom < data.size) {
                            acc.write(data, searchFrom, data.size - searchFrom)
                        }
                    } else if (acc.size() > 2_000_000) {
                        acc.reset()
                    }
                    ensureActive()
                }
                if (frames == 0) {
                    throw CameraStreamException("No video frames in camera stream")
                }
            } finally {
                call.cancel()
            }
        }
    }

    private fun looksLikeHtmlOrJson(data: ByteArray): Boolean {
        val start = data.dropWhile { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() }
            .take(16)
            .map { it.toInt().toChar() }
            .joinToString("")
        return start.startsWith("<") || start.startsWith("{") || start.startsWith("[")
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, start: Int): Int {
        val last = data.size - pattern.size
        if (start > last) return -1
        outer@ for (i in start..last) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

fun WidgetNode.hasLiveCameraSource(): Boolean = CameraStreams.fromWidget(this).hasLiveSource()
