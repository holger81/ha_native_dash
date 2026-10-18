package dev.holgerendt.hanative.data

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.holgerendt.hanative.PanelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Front-camera frame differencing. No preview, no recording — occupancy only.
 */
class TabletMotionDetector(
    context: Context,
    sensitivityLevel: Int = PanelConfig.DEFAULT_MOTION_SENSITIVITY,
    private val onMotion: (Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var previous: ByteArray? = null
    private var settleFrames = 0
    private var lastMotionAtMs = 0L
    private var reported = false

    @Volatile private var motionFraction: Float = fractionFor(sensitivityLevel)
    @Volatile private var pixelDelta: Int = deltaFor(sensitivityLevel)

    /** Applies one of the Settings sensitivity levels; takes effect on the next analyzed frame. */
    fun setSensitivity(level: Int) {
        val fraction = fractionFor(level)
        val delta = deltaFor(level)
        if (fraction == motionFraction && delta == pixelDelta) return
        motionFraction = fraction
        pixelDelta = delta
        previous = null
        settleFrames = 0
    }

    fun start(owner: LifecycleOwner) {
        if (!running.compareAndSet(false, true)) return
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener(
            {
                if (!running.get()) return@addListener
                val provider = runCatching { future.get() }.getOrNull() ?: run {
                    running.set(false)
                    return@addListener
                }
                cameraProvider = provider
                bind(owner, provider)
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    fun stop() {
        val wasReported = reported
        running.set(false)
        reported = false
        previous = null
        settleFrames = 0
        val provider = cameraProvider
        cameraProvider = null
        ContextCompat.getMainExecutor(app).execute {
            runCatching { provider?.unbindAll() }
        }
        if (wasReported) onMotion(false)
    }

    private fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, ::analyze)
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }.onFailure {
            running.set(false)
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!running.get()) return
            val sample = sampleLuma(image) ?: return
            val prior = previous
            previous = sample
            if (prior == null || prior.size != sample.size) {
                settleFrames = 0
                return
            }
            if (settleFrames < SETTLE_FRAMES) {
                settleFrames++
                return
            }
            var changed = 0
            val delta = pixelDelta
            for (i in sample.indices) {
                if (abs((sample[i].toInt() and 0xFF) - (prior[i].toInt() and 0xFF)) >= delta) {
                    changed++
                }
            }
            val now = System.currentTimeMillis()
            if (changed.toFloat() / sample.size >= motionFraction) {
                lastMotionAtMs = now
                setReported(true)
            } else if (reported && now - lastMotionAtMs >= CLEAR_AFTER_MS) {
                setReported(false)
            }
        } finally {
            image.close()
        }
    }

    private fun setReported(active: Boolean) {
        if (reported == active) return
        reported = active
        onMotion(active)
    }

    private fun sampleLuma(image: ImageProxy): ByteArray? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val width = image.width
        val height = image.height
        if (width < GRID_W || height < GRID_H) return null
        val out = ByteArray(GRID_W * GRID_H)
        val xStep = width / GRID_W
        val yStep = height / GRID_H
        for (row in 0 until GRID_H) {
            val y = row * yStep
            for (col in 0 until GRID_W) {
                val x = col * xStep
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    out[row * GRID_W + col] = buffer.get(index)
                }
            }
        }
        return out
    }

    companion object {
        /** Settings levels for the front-camera wake threshold. */
        const val SENSITIVITY_LOW = 0
        const val SENSITIVITY_NORMAL = 1
        const val SENSITIVITY_HIGH = 2

        /** Share of the 48x36 grid that must change within one frame to count as motion. */
        private fun fractionFor(level: Int): Float = when (level) {
            SENSITIVITY_LOW -> 0.05f
            SENSITIVITY_HIGH -> 0.012f
            else -> 0.025f
        }

        /** Minimum luma step per sampled pixel before it counts as changed. */
        private fun deltaFor(level: Int): Int = when (level) {
            SENSITIVITY_LOW -> 24
            SENSITIVITY_HIGH -> 12
            else -> 16
        }

        private const val GRID_W = 48
        private const val GRID_H = 36
        private const val SETTLE_FRAMES = 8
        private const val CLEAR_AFTER_MS = 20_000L
    }
}
