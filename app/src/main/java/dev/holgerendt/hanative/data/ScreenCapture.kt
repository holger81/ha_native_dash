package dev.holgerendt.hanative.data

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class ScreenCapture {
    data class Jpeg(
        val bytes: ByteArray,
        val error: String? = null,
    )

    private val activityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity) {
        activityRef.set(WeakReference(activity))
    }

    fun detach(activity: Activity) {
        val current = activityRef.get()
        val attached = current?.get()
        if (attached == null || attached === activity) {
            activityRef.compareAndSet(current, null)
        }
    }

    fun captureJpeg(): Jpeg = synchronized(lock) {
        val activity = activityRef.get()?.get()
            ?: return fail("Wall panel activity is not available")
        if (activity.isDestroyed || activity.isFinishing) {
            return fail("Wall panel activity is not available")
        }

        val layers = ArrayList<Layer>()
        val lastError = AtomicReference<String?>(null)
        val cancelled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return fail("Screenshot cannot run on the UI thread")
        }

        activity.runOnUiThread {
            try {
                copyWindows(activity, layers, lastError, cancelled) { latch.countDown() }
            } catch (e: Exception) {
                lastError.compareAndSet(null, e.message ?: "Capture failed")
                latch.countDown()
            }
        }

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            cancelled.set(true)
            synchronized(layers) { recycle(layers) }
            return fail("Screenshot timed out")
        }
        if (cancelled.get()) {
            synchronized(layers) { recycle(layers) }
            return fail("Screenshot timed out")
        }

        if (layers.isEmpty()) {
            return fail(lastError.get() ?: "Could not capture the screen")
        }
        return try {
            val snapshot = synchronized(layers) { layers.toList() }
            Jpeg(encode(compose(snapshot)))
        } catch (e: Exception) {
            fail(e.message ?: "Could not encode screenshot")
        } finally {
            synchronized(layers) { recycle(layers) }
        }
    }

    private fun copyWindows(
        activity: Activity,
        layers: MutableList<Layer>,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: () -> Unit,
    ) {
        val roots = collectRootViews(activity)
        if (roots.isEmpty()) {
            lastError.compareAndSet(null, "Window is not ready")
            done()
            return
        }
        copyNext(roots, 0, layers, lastError, cancelled, done)
    }

    private fun copyNext(
        roots: List<View>,
        index: Int,
        layers: MutableList<Layer>,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: () -> Unit,
    ) {
        if (cancelled.get() || index >= roots.size) {
            done()
            return
        }
        val view = roots[index]
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0 || !view.isShown) {
            copyNext(roots, index + 1, layers, lastError, cancelled, done)
            return
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val finishLayer = { bitmap: Bitmap? ->
            synchronized(layers) {
                if (cancelled.get()) {
                    bitmap?.recycle()
                } else if (bitmap != null) {
                    layers += Layer(bitmap, location[0], location[1])
                }
            }
            copyNext(roots, index + 1, layers, lastError, cancelled, done)
        }
        copyRoot(view, width, height, lastError, cancelled) { rootBitmap ->
            if (rootBitmap == null || cancelled.get()) {
                finishLayer(rootBitmap)
                return@copyRoot
            }
            overlayMedia(view, rootBitmap, location, lastError, cancelled, finishLayer)
        }
    }

    private fun copyRoot(
        view: View,
        width: Int,
        height: Int,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: (Bitmap?) -> Unit,
    ) {
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fallback = {
            dest.recycle()
            done(drawFallback(view, width, height))
        }
        val onCopied: (Int) -> Unit = { result ->
            if (result == PixelCopy.SUCCESS && !cancelled.get()) {
                done(dest)
            } else {
                lastError.compareAndSet(null, "PixelCopy failed ($result)")
                fallback()
            }
        }
        try {
            val window = windowOf(view)
            val surface = surfaceOf(view)
            when {
                window != null -> PixelCopy.request(window, dest, onCopied, mainHandler)
                surface != null -> PixelCopy.request(surface, dest, onCopied, mainHandler)
                else -> {
                    lastError.compareAndSet(null, "Window surface is not ready")
                    fallback()
                }
            }
        } catch (e: Exception) {
            lastError.compareAndSet(null, e.message ?: "PixelCopy failed")
            fallback()
        }
    }

    private fun overlayMedia(
        root: View,
        dest: Bitmap,
        rootLoc: IntArray,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: (Bitmap?) -> Unit,
    ) {
        val media = ArrayList<View>()
        collectMediaSurfaces(root, media)
        overlayNext(media, 0, dest, rootLoc, lastError, cancelled, done)
    }

    private fun overlayNext(
        media: List<View>,
        index: Int,
        dest: Bitmap,
        rootLoc: IntArray,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: (Bitmap?) -> Unit,
    ) {
        if (cancelled.get()) {
            dest.recycle()
            done(null)
            return
        }
        if (index >= media.size) {
            done(dest)
            return
        }
        val view = media[index]
        val width = view.width
        val height = view.height
        val continueNext = { overlayNext(media, index + 1, dest, rootLoc, lastError, cancelled, done) }
        if (width <= 0 || height <= 0 || !view.isShown) {
            continueNext()
            return
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val dx = (location[0] - rootLoc[0]).toFloat()
        val dy = (location[1] - rootLoc[1]).toFloat()
        when (view) {
            is TextureView -> {
                val frame = runCatching { view.getBitmap(width, height) }.getOrNull()
                if (frame != null) {
                    Canvas(dest).drawBitmap(frame, dx, dy, null)
                    if (frame !== dest) frame.recycle()
                }
                continueNext()
            }
            is SurfaceView -> {
                val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(view, frame, { result ->
                        if (result == PixelCopy.SUCCESS && !cancelled.get()) {
                            Canvas(dest).drawBitmap(frame, dx, dy, null)
                        } else {
                            lastError.compareAndSet(null, "Surface PixelCopy failed ($result)")
                        }
                        frame.recycle()
                        continueNext()
                    }, mainHandler)
                } catch (e: Exception) {
                    frame.recycle()
                    lastError.compareAndSet(null, e.message ?: "Surface PixelCopy failed")
                    continueNext()
                }
            }
            else -> continueNext()
        }
    }

    private fun compose(layers: List<Layer>): Bitmap {
        val minX = layers.minOf { it.x }
        val minY = layers.minOf { it.y }
        val width = (layers.maxOf { it.x + it.bitmap.width } - minX).coerceAtLeast(1)
        val height = (layers.maxOf { it.y + it.bitmap.height } - minY).coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        for (layer in layers) {
            canvas.drawBitmap(
                layer.bitmap,
                (layer.x - minX).toFloat(),
                (layer.y - minY).toFloat(),
                null,
            )
        }
        return out
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val out = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                "JPEG compress failed"
            }
            out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun fail(message: String): Jpeg = errorJpeg(message)

    private data class Layer(val bitmap: Bitmap, val x: Int, val y: Int)

    companion object {
        private const val MAX_SIDE = 800
        private const val QUALITY = 70
        private const val TIMEOUT_MS = 5_000L

        fun errorJpeg(message: String): Jpeg = Jpeg(placeholder, message)

        private val placeholder: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
            bitmap.recycle()
            out.toByteArray()
        }

        private fun collectRootViews(activity: Activity): List<View> {
            val fromWm = windowManagerViews()
                .filter { it.isShown && it.width > 0 && it.height > 0 }
                .distinct()
            if (fromWm.isNotEmpty()) return fromWm
            val view = activity.window?.decorView ?: return emptyList()
            return if (view.width > 0 && view.height > 0) listOf(view) else emptyList()
        }

        private fun collectMediaSurfaces(view: View, out: MutableList<View>) {
            when (view) {
                is TextureView, is SurfaceView -> if (view.isShown) out += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collectMediaSurfaces(view.getChildAt(i), out)
                }
            }
        }

        private fun drawFallback(view: View, width: Int, height: Int): Bitmap? = runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()

        private fun recycle(layers: List<Layer>) {
            layers.forEach { layer ->
                if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
            }
        }

        private fun windowOf(view: View): Window? {
            var type: Class<*>? = view.javaClass
            while (type != null) {
                val field = runCatching {
                    type.getDeclaredField("mWindow").apply { isAccessible = true }
                }.getOrNull()
                if (field != null) {
                    return runCatching { field.get(view) as? Window }.getOrNull()
                }
                type = type.superclass
            }
            return runCatching {
                view.javaClass.getMethod("getWindow").invoke(view) as? Window
            }.getOrNull()
        }

        @Suppress("PrivateApi")
        private fun surfaceOf(view: View): Surface? {
            val root = view.rootView
            val viewRootImpl = runCatching {
                View::class.java.getDeclaredMethod("getViewRootImpl")
                    .apply { isAccessible = true }
                    .invoke(root)
            }.getOrNull() ?: return null
            runCatching {
                viewRootImpl.javaClass.methods.firstOrNull {
                    it.name == "getSurface" && it.parameterCount == 0
                }?.invoke(viewRootImpl) as? Surface
            }.getOrNull()?.takeIf { it.isValid }?.let { return it }
            var type: Class<*>? = viewRootImpl.javaClass
            while (type != null) {
                val field = runCatching {
                    type.getDeclaredField("mSurface").apply { isAccessible = true }
                }.getOrNull()
                if (field != null) {
                    return runCatching { field.get(viewRootImpl) as? Surface }.getOrNull()
                        ?.takeIf { it.isValid }
                }
                type = type.superclass
            }
            return null
        }

        @Suppress("PrivateApi", "UNCHECKED_CAST")
        private fun windowManagerViews(): List<View> {
            val wmg = runCatching { Class.forName("android.view.WindowManagerGlobal") }.getOrNull()
                ?: return emptyList()
            val instance = runCatching { wmg.getMethod("getInstance").invoke(null) }.getOrNull()
                ?: return emptyList()
            val viaMethod = runCatching {
                wmg.getMethod("getWindowViews").invoke(instance) as? List<View>
            }.getOrNull()?.toList().orEmpty()
            if (viaMethod.isNotEmpty()) return viaMethod
            val viaField = runCatching {
                val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
                when (val views = field.get(instance)) {
                    is List<*> -> views.filterIsInstance<View>()
                    is Array<*> -> views.filterIsInstance<View>()
                    else -> emptyList()
                }
            }.getOrNull().orEmpty()
            return viaField.toList()
        }
    }
}
