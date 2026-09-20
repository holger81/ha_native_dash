package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/** Physical pixels measured by Compose, shared by generation and the 1:1 renderer. */
data class WidgetOutpaintGeometry(
    val width: Int, val height: Int,
    val coverX: Int, val coverY: Int, val coverWidth: Int, val coverHeight: Int,
) {
    init {
        require(width > 0 && height > 0 && coverWidth > 0 && coverHeight > 0)
        require(coverX >= 0 && coverY >= 0)
        require(coverX + coverWidth <= width && coverY + coverHeight <= height)
    }
    val key get() = "$width-$height-$coverX-$coverY-$coverWidth-$coverHeight"
    val canvas get() = OutpaintCanvasSpec(width, height, coverX, coverY)
    val pads get() = canvas.toPads(coverWidth, coverHeight)

    fun acceptsSize(w: Int, h: Int, returnedPads: OutpaintPadLayout?) =
        w == width && h == height && (returnedPads == null || returnedPads == pads)

    fun accepts(bytes: ByteArray, returnedPads: OutpaintPadLayout?): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return acceptsSize(options.outWidth, options.outHeight, returnedPads)
    }

    /** One-time source preparation. Both the foreground and generator use these same pixels. */
    fun prepareCover(bytes: ByteArray): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val result = Bitmap.createBitmap(coverWidth, coverHeight, Bitmap.Config.ARGB_8888)
        val scale = maxOf(coverWidth.toFloat() / source.width, coverHeight.toFloat() / source.height)
        val cropWidth = (coverWidth / scale).toInt().coerceAtLeast(1)
        val cropHeight = (coverHeight / scale).toInt().coerceAtLeast(1)
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        Canvas(result).drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight),
            Rect(0, 0, coverWidth, coverHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        return result.png().also { source.recycle(); result.recycle() }
    }

    /** Restore the untouched source after generation; never resize the returned canvas. */
    fun restoreCover(bytes: ByteArray, source: ByteArray): ByteArray {
        require(accepts(bytes, null))
        val background = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val cover = BitmapFactory.decodeByteArray(source, 0, source.size)
        result.density = Bitmap.DENSITY_NONE
        cover.density = Bitmap.DENSITY_NONE
        Canvas(result).drawBitmap(cover, coverX.toFloat(), coverY.toFloat(), null)
        return result.png().also { background.recycle(); result.recycle(); cover.recycle() }
    }
}

private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}
