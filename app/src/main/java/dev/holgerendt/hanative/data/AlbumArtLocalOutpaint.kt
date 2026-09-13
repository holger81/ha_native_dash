package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Local (non-Comfy) album outpaint: extend covers with edge-matched solid pads.
 * Prefer this over Flux when edges are mattes/frames or when generative fill
 * would invent beige/gray instead of continuing the cover.
 */
object AlbumArtLocalOutpaint {

    /**
     * Build a padded JPEG matching Comfy pad geometry.
     * 1) Uniform / near-black rim → single solid (pure black when dark).
     * 2) Else each margin uses that side's mean edge color (blue water stays blue).
     */
    fun padFromEdges(
        sourceBytes: ByteArray,
        padLeft: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_LEFT,
        padTop: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_TOP,
        padRight: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_RIGHT,
        padBottom: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_BOTTOM,
    ): ByteArray? {
        if (sourceBytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts) ?: return null
        return try {
            val w = src.width
            val h = src.height
            if (w < 8 || h < 8) return null
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val sides = analyzeSideMeans(w, h, pixels)
            val uniform = analyzeUniformEdgeFillArgb(w, h, pixels)
            val outW = w + padLeft + padRight
            val outH = h + padTop + padBottom
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            if (uniform != null) {
                canvas.drawColor(uniform)
            } else {
                // Per-side mean: continues flat fields without inventing beige.
                paint.color = sides.left
                canvas.drawRect(0f, 0f, padLeft.toFloat(), outH.toFloat(), paint)
                paint.color = sides.right
                canvas.drawRect((outW - padRight).toFloat(), 0f, outW.toFloat(), outH.toFloat(), paint)
                paint.color = sides.top
                canvas.drawRect(padLeft.toFloat(), 0f, (outW - padRight).toFloat(), padTop.toFloat(), paint)
                paint.color = sides.bottom
                canvas.drawRect(
                    padLeft.toFloat(),
                    (outH - padBottom).toFloat(),
                    (outW - padRight).toFloat(),
                    outH.toFloat(),
                    paint,
                )
            }
            canvas.drawBitmap(src, padLeft.toFloat(), padTop.toFloat(), null)
            ByteArrayOutputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, 92, stream)) return null
                stream.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            src.recycle()
        }
    }

    /** @deprecated Prefer [padFromEdges]; kept for callers/tests. */
    fun solidPadIfUniformEdges(
        sourceBytes: ByteArray,
        padLeft: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_LEFT,
        padTop: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_TOP,
        padRight: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_RIGHT,
        padBottom: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_BOTTOM,
    ): ByteArray? {
        if (sourceBytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts) ?: return null
        return try {
            val fill = analyzeUniformEdgeFill(src) ?: return null
            val outW = src.width + padLeft + padRight
            val outH = src.height + padTop + padBottom
            if (outW <= 0 || outH <= 0) return null
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(fill)
            canvas.drawBitmap(src, padLeft.toFloat(), padTop.toFloat(), null)
            ByteArrayOutputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, 92, stream)) return null
                stream.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            src.recycle()
        }
    }

    data class SideMeans(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun analyzeSideMeans(width: Int, height: Int, pixels: IntArray): SideMeans {
        val band = edgeBand(width, height)
        return SideMeans(
            left = meanRect(pixels, width, 0, band, 0, height),
            top = meanRect(pixels, width, 0, width, 0, band),
            right = meanRect(pixels, width, width - band, width, 0, height),
            bottom = meanRect(pixels, width, 0, width, height - band, height),
        )
    }

    /** Visible for tests — sample outer edge strips and decide a solid fill color (ARGB). */
    fun analyzeUniformEdgeFillArgb(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Int? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val band = edgeBand(width, height)
        val samples = ArrayList<Int>((width + height) * band * 2)
        fun addRow(y: Int) {
            val yy = y.coerceIn(0, height - 1)
            for (x in 0 until width) samples.add(pixels[yy * width + x])
        }
        fun addCol(x: Int) {
            val xx = x.coerceIn(0, width - 1)
            for (y in band until height - band) samples.add(pixels[y * width + xx])
        }
        for (i in 0 until band) {
            addRow(i)
            addRow(height - 1 - i)
            addCol(i)
            addCol(width - 1 - i)
        }
        if (samples.size < 16) return null

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (c in samples) {
            sumR += red(c)
            sumG += green(c)
            sumB += blue(c)
        }
        val n = samples.size.toDouble()
        val meanR = sumR / n
        val meanG = sumG / n
        val meanB = sumB / n

        var varAcc = 0.0
        for (c in samples) {
            val dr = red(c) - meanR
            val dg = green(c) - meanG
            val db = blue(c) - meanB
            varAcc += dr * dr + dg * dg + db * db
        }
        val std = sqrt(varAcc / n)
        val luminance = 0.2126 * meanR + 0.7152 * meanG + 0.0722 * meanB

        // Near-black mattes: accept even with JPEG noise on the rim.
        if (luminance <= BLACK_LUMA_MAX && std <= MAX_BLACK_EDGE_STD) {
            return 0xFF000000.toInt()
        }
        if (std > MAX_EDGE_STD) return null

        val fillR = meanR.roundToInt().coerceIn(0, 255)
        val fillG = meanG.roundToInt().coerceIn(0, 255)
        val fillB = meanB.roundToInt().coerceIn(0, 255)
        if (luminance <= BLACK_LUMA_MAX) return 0xFF000000.toInt()
        return (0xFF shl 24) or (fillR shl 16) or (fillG shl 8) or fillB
    }

    private fun analyzeUniformEdgeFill(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return analyzeUniformEdgeFillArgb(w, h, pixels)
    }

    private fun edgeBand(width: Int, height: Int): Int =
        max(2, min(6, min(width, height) / 48))

    private fun meanRect(
        pixels: IntArray,
        stride: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): Int {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var n = 0
        val left = x0.coerceIn(0, stride)
        val right = x1.coerceIn(0, stride)
        val top = y0.coerceAtLeast(0)
        val bottom = y1.coerceAtLeast(0)
        for (y in top until bottom) {
            val row = y * stride
            for (x in left until right) {
                val c = pixels[row + x]
                sumR += red(c)
                sumG += green(c)
                sumB += blue(c)
                n++
            }
        }
        if (n == 0) return 0xFF000000.toInt()
        val r = (sumR / n).roundToInt().coerceIn(0, 255)
        val g = (sumG / n).roundToInt().coerceIn(0, 255)
        val b = (sumB / n).roundToInt().coerceIn(0, 255)
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        if (luma <= BLACK_LUMA_MAX) return 0xFF000000.toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * True when the Flux (or other) padded result fills the margin with a near-flat
     * color that does not match the source cover's edge colors — classic beige fail.
     */
    fun isLazyFlatPad(
        paddedBytes: ByteArray,
        sourceBytes: ByteArray,
        padLeft: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_LEFT,
        padTop: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_TOP,
        padRight: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_RIGHT,
        padBottom: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_BOTTOM,
    ): Boolean {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val padded = BitmapFactory.decodeByteArray(paddedBytes, 0, paddedBytes.size, opts) ?: return false
        val source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts) ?: run {
            padded.recycle()
            return false
        }
        return try {
            val outW = padded.width
            val outH = padded.height
            val srcW = outW - padLeft - padRight
            val srcH = outH - padTop - padBottom
            if (srcW <= 0 || srcH <= 0) return false
            val padPixels = IntArray(outW * outH)
            padded.getPixels(padPixels, 0, outW, 0, 0, outW, outH)
            val srcPixels = IntArray(source.width * source.height)
            source.getPixels(srcPixels, 0, source.width, 0, 0, source.width, source.height)
            val expected = analyzeSideMeans(source.width, source.height, srcPixels)
            val leftMean = meanRect(padPixels, outW, 0, padLeft, 0, outH)
            val rightMean = meanRect(padPixels, outW, outW - padRight, outW, 0, outH)
            val topMean = meanRect(padPixels, outW, padLeft, outW - padRight, 0, padTop)
            val bottomMean = meanRect(padPixels, outW, padLeft, outW - padRight, outH - padBottom, outH)
            val padStd = maxOf(
                colorDistance(leftMean, rightMean),
                colorDistance(topMean, bottomMean),
                colorDistance(leftMean, topMean),
            )
            // Flat invented pad (all margins nearly the same color).
            if (padStd > 28.0) return false
            val padColor = leftMean
            val match = minOf(
                colorDistance(padColor, expected.left),
                colorDistance(padColor, expected.top),
                colorDistance(padColor, expected.right),
                colorDistance(padColor, expected.bottom),
            )
            match > 45.0
        } finally {
            padded.recycle()
            source.recycle()
        }
    }

    fun hasUniformEdges(sourceBytes: ByteArray): Boolean {
        if (sourceBytes.isEmpty()) return false
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts) ?: return false
        return try {
            analyzeUniformEdgeFill(src) != null
        } finally {
            src.recycle()
        }
    }

    private fun colorDistance(a: Int, b: Int): Double {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun red(c: Int): Int = (c ushr 16) and 0xFF
    private fun green(c: Int): Int = (c ushr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF

    /** Max RGB std-dev for a non-black uniform studio/matte rim. */
    const val MAX_EDGE_STD = 36.0

    /** Black frames tolerate more JPEG noise. */
    const val MAX_BLACK_EDGE_STD = 55.0

    /** Luma at or below this → pad with pure black. */
    const val BLACK_LUMA_MAX = 40.0
}
