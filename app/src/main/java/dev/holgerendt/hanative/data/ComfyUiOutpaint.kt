package dev.holgerendt.hanative.data

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Calls a LAN ComfyUI instance to outpaint album art via the HTTP API.
 * Blank / unreachable hosts return null; callers keep the local soft treatment.
 *
 * ComfyUI box must have the models/nodes referenced by
 * `assets/comfyui/album_outpaint_api.json` (Flux fill outpaint by default).
 * The app injects [OUTPAINT_PROMPT] into the positive CLIP node so the request
 * asks for edge-faithful fill: solid edge → solid extension; pictorial edge →
 * seamless scene continuation.
 */
class ComfyUiOutpaintClient(
    private val workflowLoader: () -> String,
    private val http: OkHttpClient = defaultClient(),
    private val pollIntervalMs: Long = 1_500L,
    private val pollTimeoutMs: Long = 90_000L,
) {
    constructor(
        assets: AssetManager,
        http: OkHttpClient = defaultClient(),
        pollIntervalMs: Long = 1_500L,
        pollTimeoutMs: Long = 90_000L,
        workflowAssetPath: String = WORKFLOW_ASSET,
    ) : this(
        workflowLoader = {
            assets.open(workflowAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        http = http,
        pollIntervalMs = pollIntervalMs,
        pollTimeoutMs = pollTimeoutMs,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val workflowTemplate: String by lazy { workflowLoader() }

    suspend fun outpaint(baseUrl: String, sourceBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || sourceBytes.isEmpty()) return@withContext null
        val host = NetworkGuard.hostOf(base) ?: return@withContext null
        if (!NetworkGuard.isPrivateHost(host)) return@withContext null

        val uploadedName = uploadImage(base, sourceBytes) ?: return@withContext null
        val promptId = queuePrompt(base, uploadedName) ?: return@withContext null
        val view = waitForOutput(base, promptId) ?: return@withContext null
        downloadView(base, view)
    }

    private fun uploadImage(base: String, sourceBytes: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "album_cover.png",
                sourceBytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .addFormDataPart("type", "input")
            .addFormDataPart("overwrite", "true")
            .build()
        val request = Request.Builder()
            .url("$base/upload/image")
            .post(body)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val subfolder = obj["subfolder"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                when {
                    name.isBlank() -> null
                    subfolder.isNotBlank() -> "$subfolder/$name"
                    else -> name
                }
            }
        }.getOrNull()
    }

    private fun queuePrompt(base: String, imageName: String): String? {
        val workflow = prepareWorkflow(
            json.parseToJsonElement(workflowTemplate).jsonObject,
            imageName = imageName,
            positivePrompt = OUTPAINT_PROMPT,
        )
        val payload = buildJsonObject {
            put("prompt", workflow)
            put("client_id", UUID.randomUUID().toString())
        }
        val request = Request.Builder()
            .url("$base/prompt")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.parseToJsonElement(response.body?.string().orEmpty())
                    .jsonObject["prompt_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private suspend fun waitForOutput(base: String, promptId: String): ViewRef? {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val view = fetchHistoryOutput(base, promptId)
            if (view != null) return view
            delay(pollIntervalMs)
        }
        return null
    }

    private fun fetchHistoryOutput(base: String, promptId: String): ViewRef? {
        val request = Request.Builder()
            .url("$base/history/$promptId")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val entry = root[promptId]?.jsonObject ?: return@use null
                val outputs = entry["outputs"]?.jsonObject ?: return@use null
                for ((_, nodeEl) in outputs) {
                    val node = nodeEl as? JsonObject ?: continue
                    val images = node["images"] as? JsonArray ?: continue
                    for (imgEl in images) {
                        val img = imgEl as? JsonObject ?: continue
                        val filename = img["filename"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (filename.isBlank()) continue
                        return@use ViewRef(
                            filename = filename,
                            subfolder = img["subfolder"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                            type = img["type"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "output",
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun downloadView(base: String, view: ViewRef): ByteArray? {
        val url = buildString {
            append(base)
            append("/view?filename=")
            append(java.net.URLEncoder.encode(view.filename, Charsets.UTF_8.name()))
            append("&type=")
            append(java.net.URLEncoder.encode(view.type, Charsets.UTF_8.name()))
            if (view.subfolder.isNotBlank()) {
                append("&subfolder=")
                append(java.net.URLEncoder.encode(view.subfolder, Charsets.UTF_8.name()))
            }
        }
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private data class ViewRef(
        val filename: String,
        val subfolder: String,
        val type: String,
    )

    companion object {
        const val WORKFLOW_ASSET = "comfyui/album_outpaint_api.json"
        private const val LOAD_IMAGE_NODE_ID = "17"
        private const val POSITIVE_PROMPT_NODE_ID = "23"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Must match `ImagePadForOutpaint` node 44 in [WORKFLOW_ASSET]. */
        const val OUTPAINT_PAD_LEFT = 256
        const val OUTPAINT_PAD_TOP = 128
        const val OUTPAINT_PAD_RIGHT = 256
        const val OUTPAINT_PAD_BOTTOM = 128

        /**
         * Positive CLIP text for Flux fill outpaint.
         * Prefer continuing whatever is visible at the cover edges. Flat color is
         * only correct when the edge itself is already a uniform field — not a
         * default beige/gray pad.
         */
        const val OUTPAINT_PROMPT =
            "Expand the album cover into the empty padded border by continuing " +
                "exactly what is already visible at each edge of the square. " +
                "Copy the edge colors, lighting, and textures outward: blue water " +
                "stays blue water, sky stays sky, photo grain stays photo grain, " +
                "illustration lines keep going. " +
                "If the cover already has a black matte, black frame, or any flat " +
                "uniform border color, fill the pad with that same flat color only — " +
                "do not invent scene content past a matte. " +
                "Never fill with generic beige, cream, gray, white, or paper unless " +
                "that is literally the cover's edge color. " +
                "No new people, objects, text, logos, frames, or borders. " +
                "Keep the original cover pixels unchanged."

        /** Bump when prompt/workflow quality changes so stale disk fills regenerate. */
        const val OUTPAINT_CACHE_VERSION = "flux-fill-v3"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(NetworkGuard.interceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        /** Rewrite LoadImage filename, positive CLIP prompt, and a fresh seed. */
        internal fun prepareWorkflow(
            workflow: JsonObject,
            imageName: String,
            positivePrompt: String = OUTPAINT_PROMPT,
            seed: Long = kotlin.random.Random.nextLong(0, Int.MAX_VALUE.toLong()),
        ): JsonObject {
            val mutable = workflow.toMutableMap()
            mutable.remove("_meta")
            var wroteImage = false
            var wrotePrompt = false
            for ((key, value) in workflow) {
                if (key == "_meta") continue
                val node = value as? JsonObject ?: continue
                val classType = node["class_type"]?.jsonPrimitive?.contentOrNull
                val inputs = (node["inputs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                when (classType) {
                    "LoadImage" -> {
                        inputs["image"] = JsonPrimitive(imageName)
                        mutable[key] = JsonObject(
                            node.toMutableMap().apply { put("inputs", JsonObject(inputs)) },
                        )
                        wroteImage = true
                    }
                    "CLIPTextEncode" -> {
                        if (key == POSITIVE_PROMPT_NODE_ID) {
                            inputs["text"] = JsonPrimitive(positivePrompt)
                            mutable[key] = JsonObject(
                                node.toMutableMap().apply { put("inputs", JsonObject(inputs)) },
                            )
                            wrotePrompt = true
                        }
                    }
                    "KSampler" -> {
                        inputs["seed"] = JsonPrimitive(seed)
                        mutable[key] = JsonObject(
                            node.toMutableMap().apply { put("inputs", JsonObject(inputs)) },
                        )
                    }
                }
            }
            if (!wroteImage) {
                val node = (mutable[LOAD_IMAGE_NODE_ID] as? JsonObject)
                if (node != null) {
                    val inputs = (node["inputs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    inputs["image"] = JsonPrimitive(imageName)
                    mutable[LOAD_IMAGE_NODE_ID] = JsonObject(
                        node.toMutableMap().apply { put("inputs", JsonObject(inputs)) },
                    )
                }
            }
            if (!wrotePrompt) {
                val node = (mutable[POSITIVE_PROMPT_NODE_ID] as? JsonObject)
                if (node != null) {
                    val inputs = (node["inputs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    inputs["text"] = JsonPrimitive(positivePrompt)
                    mutable[POSITIVE_PROMPT_NODE_ID] = JsonObject(
                        node.toMutableMap().apply { put("inputs", JsonObject(inputs)) },
                    )
                }
            }
            return JsonObject(mutable)
        }
    }
}
