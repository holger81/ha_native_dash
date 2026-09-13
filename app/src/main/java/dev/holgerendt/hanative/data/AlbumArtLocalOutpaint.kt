package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * When a cover already has a uniform border (black matte, studio backdrop, etc.),
 * extend with a local solid pad instead of Flux fill — avoids stretch/text bleed.
 */
object AlbumArtLocalOutpaint {

    /**
     * If all four outer edges are a uniform color (including black frames),
     * return a JPEG the same geometry as Comfy pad outpaint. Otherwise null.
     */
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

    /** Visible for tests — sample outer edge strips and decide a solid fill color (ARGB). */
    fun analyzeUniformEdgeFillArgb(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Int? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        // Prefer the outermost rim (thin black mattes are often only a few px).
        val band = max(2, min(4, min(width, height) / 64))
        val samples = ArrayList<Int>((width + height) * band * 2)
        fun addRow(y: Int) {
            val yy = y.coerceIn(0, height - 1)
            for (x in 0 until width) samples.add(pixels[yy * width + x])
        }
        fun addCol(x: Int) {
            val xx = x.coerceIn(0, width - 1)
            // Skip corners already counted in rows to avoid overweighting.
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

        // Pictorial edges (photo, illustration) have high chroma variation.
        if (std > MAX_EDGE_STD) return null

        val fillR = meanR.roundToInt().coerceIn(0, 255)
        val fillG = meanG.roundToInt().coerceIn(0, 255)
        val fillB = meanB.roundToInt().coerceIn(0, 255)

        // Near-black frames → pure black (cleaner than muddy dark gray).
        val luminance = 0.2126 * fillR + 0.7152 * fillG + 0.0722 * fillB
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

    private fun red(c: Int): Int = (c ushr 16) and 0xFF
    private fun green(c: Int): Int = (c ushr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF

    /** Max RGB std-dev across edge samples for "uniform border". */
    const val MAX_EDGE_STD = 28.0

    /** Luma at or below this → pad with pure black. */
    const val BLACK_LUMA_MAX = 32.0
}
