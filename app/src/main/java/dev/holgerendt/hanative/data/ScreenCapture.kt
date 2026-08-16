package dev.holgerendt.hanative.data

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
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
        val windows = collectWindows(activity)
        if (windows.isEmpty()) {
            lastError.compareAndSet(null, "Window is not ready")
            done()
            return
        }
        copyNext(windows, 0, layers, lastError, cancelled, done)
    }

    private fun copyNext(
        windows: List<Window>,
        index: Int,
        layers: MutableList<Layer>,
        lastError: AtomicReference<String?>,
        cancelled: AtomicBoolean,
        done: () -> Unit,
    ) {
        if (cancelled.get() || index >= windows.size) {
            done()
            return
        }
        val window = windows[index]
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            copyNext(windows, index + 1, layers, lastError, cancelled, done)
            return
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val finishLayer = { bitmap: Bitmap? ->
            synchronized(layers) {
                if (cancelled.get()) {
                    bitmap?.recycle()
                } else if (bitmap != null) {
                    layers += Layer(bitmap, location[0], location[1])
                }
            }
            copyNext(windows, index + 1, layers, lastError, cancelled, done)
        }
        try {
            PixelCopy.request(window, dest, { result ->
                if (result == PixelCopy.SUCCESS && !cancelled.get()) {
                    finishLayer(dest)
                } else {
                    dest.recycle()
                    lastError.compareAndSet(null, "PixelCopy failed ($result)")
                    finishLayer(drawFallback(view, width, height))
                }
            }, mainHandler)
        } catch (e: Exception) {
            dest.recycle()
            lastError.compareAndSet(null, e.message ?: "PixelCopy failed")
            finishLayer(drawFallback(view, width, height))
        }
    }

    private fun collectWindows(activity: Activity): List<Window> {
        val fromWm = windowManagerViews().mapNotNull { windowOf(it) }
            .filter { it.decorView.isShown && it.decorView.width > 0 && it.decorView.height > 0 }
            .distinct()
        if (fromWm.isNotEmpty()) return fromWm
        val window = activity.window ?: return emptyList()
        val view = window.decorView
        return if (view.width > 0 && view.height > 0) listOf(window) else emptyList()
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
        private const val TIMEOUT_MS = 3_000L

        fun errorJpeg(message: String): Jpeg = Jpeg(placeholder, message)

        private val placeholder: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
            bitmap.recycle()
            out.toByteArray()
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
            return (view.context as? Activity)?.window
        }

        @Suppress("UNCHECKED_CAST")
        private fun windowManagerViews(): List<View> = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmg.getMethod("getInstance").invoke(null)
            wmg.getMethod("getWindowViews").invoke(instance) as? List<View>
        }.getOrNull()?.toList().orEmpty()
    }
}
