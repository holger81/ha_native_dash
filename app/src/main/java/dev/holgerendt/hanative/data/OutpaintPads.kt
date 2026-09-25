package dev.holgerendt.hanative.data

import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared outpaint geometry and cache identity for local pads, on-device cache,
 * cover layout, and the mediagen LAN API (same version string as the server).
 *
 * Prefer [MusicPlayerOutpaint.padsForSource] / canvas fields on mediagen POST so
 * the generated JPEG matches the FullMusicCard aspect with the cover already
 * placed where the UI draws it.
 */
object OutpaintPads {
    /** Fallback pads (mediagen defaults) — used only when source size is unknown. */
    const val OUTPAINT_PAD_LEFT = 256
    const val OUTPAINT_PAD_TOP = 128
    const val OUTPAINT_PAD_RIGHT = 256
    const val OUTPAINT_PAD_BOTTOM = 128

    /**
     * Bump when pad geometry or generative prompt identity changes so stale
     * cache files are not reused. Must stay in sync with mediagen.
     */
    /** Must match mediagen `OUTPAINT_CACHE_VERSION`. */
    const val OUTPAINT_CACHE_VERSION = "no-frame-prompt-v10"
}

/**
 * Four-sided pad around an unscaled cover (mediagen translate-only outpaint).
 */
data class OutpaintPadLayout(
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int,
) {
    fun asTuple(): IntArray = intArrayOf(padLeft, padTop, padRight, padBottom)

    fun headerPad(): String = "$padLeft,$padTop,$padRight,$padBottom"

    /** Same bytes mediagen includes in the content hash. */
    fun layoutTag(): ByteArray = "pads:$padLeft,$padTop,$padRight,$padBottom\u0000".toByteArray(Charsets.UTF_8)

    fun outWidth(srcW: Int): Int = srcW + padLeft + padRight

    fun outHeight(srcH: Int): Int = srcH + padTop + padBottom

    companion object {
        fun defaults(): OutpaintPadLayout = OutpaintPadLayout(
            padLeft = OutpaintPads.OUTPAINT_PAD_LEFT,
            padTop = OutpaintPads.OUTPAINT_PAD_TOP,
            padRight = OutpaintPads.OUTPAINT_PAD_RIGHT,
            padBottom = OutpaintPads.OUTPAINT_PAD_BOTTOM,
        )

        fun parseHeader(raw: String?): OutpaintPadLayout? {
            val parts = raw?.trim()?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 4) return null
            return OutpaintPadLayout(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

/**
 * Canvas form for mediagen: place unscaled source at (x,y) inside out_width×out_height.
 */
data class OutpaintCanvasSpec(
    val outWidth: Int,
    val outHeight: Int,
    val x: Int,
    val y: Int,
) {
    fun toPads(srcW: Int, srcH: Int): OutpaintPadLayout = OutpaintPadLayout(
        padLeft = x,
        padTop = y,
        padRight = outWidth - x - srcW,
        padBottom = outHeight - y - srcH,
    )
}

/**
 * Player-matched outpaint canvas for [FullMusicCard].
 *
 * Cover stays at native pixel size (mediagen does not rescale). The canvas is
 * sized so the cover sits top-center at ~50% of width — matching the floating
 * hero — with room below for title and transport.
 */
object MusicPlayerOutpaint {
    /** Cover width as a fraction of canvas width (centered). */
    const val COVER_WIDTH_FRAC = 0.5f

    /** Top of cover as a fraction of canvas height (hero band). */
    const val COVER_TOP_FRAC = 0.06f

    /** Canvas width / height — portrait player card (~4:5). */
    const val CANVAS_ASPECT = 1024f / 1280f

    const val MAX_PAD = 2048
    const val MAX_OUTPUT_SIDE = 4096

    fun sourceSize(sourceBytes: ByteArray): Pair<Int, Int>? {
        if (sourceBytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w > 0 && h > 0) w to h else null
        } catch (_: Throwable) {
            // Unit tests (JVM) do not mock BitmapFactory.
            null
        }
    }

    fun canvasForSource(srcW: Int, srcH: Int): OutpaintCanvasSpec {
        require(srcW > 0 && srcH > 0)
        var outW = max(srcW, (srcW / COVER_WIDTH_FRAC).roundToInt())
        var outH = max(srcH, (outW / CANVAS_ASPECT).roundToInt())
        // Stay within mediagen caps.
        if (outW > MAX_OUTPUT_SIDE || outH > MAX_OUTPUT_SIDE) {
            val scale = max(outW / MAX_OUTPUT_SIDE.toFloat(), outH / MAX_OUTPUT_SIDE.toFloat())
            outW = (outW / scale).roundToInt().coerceAtMost(MAX_OUTPUT_SIDE)
            outH = (outH / scale).roundToInt().coerceAtMost(MAX_OUTPUT_SIDE)
        }
        if (outW < srcW) outW = srcW
        if (outH < srcH) outH = srcH
        var x = ((outW - srcW) / 2).coerceAtLeast(0)
        var y = (outH * COVER_TOP_FRAC).roundToInt().coerceAtLeast(0)
        if (x + srcW > outW) x = outW - srcW
        if (y + srcH > outH) y = outH - srcH
        // Clamp individual pads to mediagen max.
        val right = outW - x - srcW
        val bottom = outH - y - srcH
        if (x > MAX_PAD || y > MAX_PAD || right > MAX_PAD || bottom > MAX_PAD) {
            // Fall back to symmetric defaults that fit.
            return OutpaintCanvasSpec(
                outWidth = srcW + OutpaintPads.OUTPAINT_PAD_LEFT + OutpaintPads.OUTPAINT_PAD_RIGHT,
                outHeight = srcH + OutpaintPads.OUTPAINT_PAD_TOP + OutpaintPads.OUTPAINT_PAD_BOTTOM,
                x = OutpaintPads.OUTPAINT_PAD_LEFT,
                y = OutpaintPads.OUTPAINT_PAD_TOP,
            )
        }
        return OutpaintCanvasSpec(outWidth = outW, outHeight = outH, x = x, y = y)
    }

    fun padsForSource(srcW: Int, srcH: Int): OutpaintPadLayout =
        canvasForSource(srcW, srcH).toPads(srcW, srcH)

    fun padsForSourceBytes(sourceBytes: ByteArray): OutpaintPadLayout {
        val (w, h) = sourceSize(sourceBytes) ?: return OutpaintPadLayout.defaults()
        return padsForSource(w, h)
    }

    fun canvasForSourceBytes(sourceBytes: ByteArray): OutpaintCanvasSpec? {
        val (w, h) = sourceSize(sourceBytes) ?: return null
        return canvasForSource(w, h)
    }
}
