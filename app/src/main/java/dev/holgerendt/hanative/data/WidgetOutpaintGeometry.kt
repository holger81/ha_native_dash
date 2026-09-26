package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

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

    /**
     * Restore the untouched source after generation; never resize the returned canvas.
     *
     * Stamp uses the same corner radius fraction as the AlbumOutpaintHero frame.
     * Before stamping, square−round ear wedges are filled from pad pixels *outside*
     * the cover rect so Flux's square bake cannot peek past the white frame.
     */
    fun restoreCover(bytes: ByteArray, source: ByteArray): ByteArray {
        require(accepts(bytes, null))
        val background = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val cover = BitmapFactory.decodeByteArray(source, 0, source.size)
        result.density = Bitmap.DENSITY_NONE
        cover.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(result)
        val radius = coverWidth * COVER_CORNER_RADIUS_FRAC
        if (radius > 0f) {
            punchCoverCornerEars(canvas, background, radius)
            val path = Path().apply {
                addRoundRect(
                    RectF(
                        coverX.toFloat(),
                        coverY.toFloat(),
                        (coverX + coverWidth).toFloat(),
                        (coverY + coverHeight).toFloat(),
                    ),
                    radius,
                    radius,
                    Path.Direction.CW,
                )
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(cover, coverX.toFloat(), coverY.toFloat(), null)
            canvas.restore()
        } else {
            canvas.drawBitmap(cover, coverX.toFloat(), coverY.toFloat(), null)
        }
        return result.png().also { background.recycle(); result.recycle(); cover.recycle() }
    }

    /**
     * Overwrite the four square-minus-roundrect wedges using pad blocks taken
     * diagonally *outside* each cover corner (reads [source], writes via [canvas]).
     * Edge-strip stretches sampled dark stamp-adjacent hair and recreated square
     * ears; outside-corner patches match the visible outpaint chrome.
     */
    internal fun punchCoverCornerEars(canvas: Canvas, source: Bitmap, radius: Float) {
        val ri = radius.roundToInt().coerceAtLeast(1)
        val coverRect = RectF(
            coverX.toFloat(),
            coverY.toFloat(),
            (coverX + coverWidth).toFloat(),
            (coverY + coverHeight).toFloat(),
        )
        val earPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(coverRect, Path.Direction.CW)
            addRoundRect(coverRect, radius, radius, Path.Direction.CW)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val rightPad = (source.width - coverX - coverWidth).coerceAtLeast(0)
        val bottomPad = (source.height - coverY - coverHeight).coerceAtLeast(0)
        canvas.save()
        canvas.clipPath(earPath)
        // TL
        if (coverX > 0 && coverY > 0) {
            val sw = coverX.coerceAtMost(ri).coerceAtLeast(1)
            val sh = coverY.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX - sw, coverY - sh, coverX, coverY),
                Rect(coverX, coverY, coverX + ri, coverY + ri),
                paint,
            )
        } else if (coverY > 0) {
            val sh = coverY.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX, 0, coverX + coverWidth, sh),
                Rect(coverX, coverY, coverX + ri.coerceAtMost(coverWidth), coverY + ri),
                paint,
            )
        } else if (coverX > 0) {
            val sw = coverX.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(0, coverY, sw, coverY + coverHeight),
                Rect(coverX, coverY, coverX + ri, coverY + ri.coerceAtMost(coverHeight)),
                paint,
            )
        }
        // TR
        if (rightPad > 0 && coverY > 0) {
            val sw = rightPad.coerceAtMost(ri).coerceAtLeast(1)
            val sh = coverY.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX + coverWidth, coverY - sh, coverX + coverWidth + sw, coverY),
                Rect(coverX + coverWidth - ri, coverY, coverX + coverWidth, coverY + ri),
                paint,
            )
        } else if (coverY > 0) {
            val sh = coverY.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX, 0, coverX + coverWidth, sh),
                Rect(coverX + coverWidth - ri, coverY, coverX + coverWidth, coverY + ri),
                paint,
            )
        } else if (rightPad > 0) {
            val sw = rightPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX + coverWidth, coverY, coverX + coverWidth + sw, coverY + coverHeight),
                Rect(coverX + coverWidth - ri, coverY, coverX + coverWidth, coverY + ri.coerceAtMost(coverHeight)),
                paint,
            )
        }
        // BL
        if (coverX > 0 && bottomPad > 0) {
            val sw = coverX.coerceAtMost(ri).coerceAtLeast(1)
            val sh = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX - sw, coverY + coverHeight, coverX, coverY + coverHeight + sh),
                Rect(coverX, coverY + coverHeight - ri, coverX + ri, coverY + coverHeight),
                paint,
            )
        } else if (bottomPad > 0) {
            val sh = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX, coverY + coverHeight, coverX + coverWidth, coverY + coverHeight + sh),
                Rect(coverX, coverY + coverHeight - ri, coverX + ri.coerceAtMost(coverWidth), coverY + coverHeight),
                paint,
            )
        } else if (coverX > 0) {
            val sw = coverX.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(0, coverY, sw, coverY + coverHeight),
                Rect(coverX, coverY + coverHeight - ri, coverX + ri, coverY + coverHeight),
                paint,
            )
        }
        // BR
        if (rightPad > 0 && bottomPad > 0) {
            val sw = rightPad.coerceAtMost(ri).coerceAtLeast(1)
            val sh = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(
                    coverX + coverWidth,
                    coverY + coverHeight,
                    coverX + coverWidth + sw,
                    coverY + coverHeight + sh,
                ),
                Rect(
                    coverX + coverWidth - ri,
                    coverY + coverHeight - ri,
                    coverX + coverWidth,
                    coverY + coverHeight,
                ),
                paint,
            )
        } else if (bottomPad > 0) {
            val sh = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX, coverY + coverHeight, coverX + coverWidth, coverY + coverHeight + sh),
                Rect(coverX + coverWidth - ri, coverY + coverHeight - ri, coverX + coverWidth, coverY + coverHeight),
                paint,
            )
        } else if (rightPad > 0) {
            val sw = rightPad.coerceAtMost(ri).coerceAtLeast(1)
            canvas.drawBitmap(
                source,
                Rect(coverX + coverWidth, coverY, coverX + coverWidth + sw, coverY + coverHeight),
                Rect(coverX + coverWidth - ri, coverY + coverHeight - ri, coverX + coverWidth, coverY + coverHeight),
                paint,
            )
        }
        canvas.restore()
    }

    companion object {
        /** 22.dp / 196.dp — matches AlbumOutpaintHero cover shape. */
        const val COVER_CORNER_RADIUS_FRAC = 22f / 196f
    }
}

private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}
