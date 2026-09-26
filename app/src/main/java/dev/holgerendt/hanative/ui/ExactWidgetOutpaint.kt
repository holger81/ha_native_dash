package dev.holgerendt.hanative.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.holgerendt.hanative.data.WidgetOutpaintGeometry
import dev.holgerendt.hanative.ui.theme.CardLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Flux Fill often invents a distressed white photo/Polaroid frame in the outer
 * pad pixels. SoftAtmosphere (decorative, no sharp hero) may scale by this
 * factor under the card clip to hide that rim.
 *
 * ExactWidgetOutpaint must stay 1:1: scaling around the cover center enlarges
 * the stamped album past the Compose white frame so subjects bleed into the pad.
 */
internal const val OUTPAINT_EDGE_OVERSCAN = 1.07f

internal class ExactWidgetArt {
    var rootCoordinates: LayoutCoordinates? = null
    var coverCoordinates: LayoutCoordinates? = null
    var geometry by mutableStateOf<WidgetOutpaintGeometry?>(null)
    var cover by mutableStateOf<Bitmap?>(null)

    fun measure() {
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return
        val hero = coverCoordinates?.takeIf { it.isAttached } ?: return
        val origin = root.localPositionOf(hero, androidx.compose.ui.geometry.Offset.Zero)
        geometry = runCatching {
            WidgetOutpaintGeometry(root.size.width, root.size.height,
                origin.x.roundToInt(), origin.y.roundToInt(), hero.size.width, hero.size.height)
        }.getOrNull()
    }
}

internal val LocalExactWidgetArt = staticCompositionLocalOf<ExactWidgetArt?> { null }

/** Full music card only: decoded pixels are drawn at the origin with no destination sizing. */
@Composable
internal fun ExactWidgetOutpaint(
    coverPath: String?, viewModel: HaViewModel, modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val art = remember(coverPath) { ExactWidgetArt() }
    val geometry = art.geometry
    val ui by viewModel.ui.collectAsState()
    var background by remember(coverPath, geometry) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverPath, geometry, ui.mediagenUrl) {
        val measured = geometry ?: return@LaunchedEffect
        val ref = coverPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        art.cover = null
        val repository = viewModel.albumArtOutpaint.forWidget(measured)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = ref)
        var previousRevision: Long? = null
        while (true) {
            val frame = withContext(Dispatchers.IO) {
                val source = repository.preparedCover(ref) ?: return@withContext null
                val cover = if (art.cover == null) BitmapFactory.decodeByteArray(source, 0, source.size) else null
                val file = repository.ensureLocalPad(ref) ?: return@withContext Triple(cover, null, null)
                val flux = repository.peekFluxComplete(ref)
                val revision = outpaintFileRevision(file, flux)
                val bitmap = if (revision != previousRevision) BitmapFactory.decodeFile(file.absolutePath)
                    ?.takeIf { measured.acceptsSize(it.width, it.height, null) } else null
                Triple(cover, bitmap, revision)
            }
            if (frame != null) {
                frame.first?.let { art.cover = it }
                frame.second?.let { background = it; previousRevision = frame.third }
            }
            delay(2_000)
        }
    }
    CompositionLocalProvider(LocalExactWidgetArt provides art) {
        Box(modifier.onGloballyPositioned { art.rootCoordinates = it; art.measure() }
            .drawWithContent {
                val bitmap = background
                // A layout change drops the old canvas instead of stretching it during a frame.
                if (bitmap != null && bitmap.width == size.width.roundToInt() && bitmap.height == size.height.roundToInt()) {
                    val img = bitmap.asImageBitmap()
                    val geo = geometry
                    if (geo != null) {
                        // Never blit the square stamp: AlbumOutpaintHero draws the
                        // rounded cover. Drawing stamp pixels lets 90° tips peek past
                        // the white frame (and dark hair reads as square "ears").
                        val coverRect = Rect(
                            geo.coverX.toFloat(),
                            geo.coverY.toFloat(),
                            (geo.coverX + geo.coverWidth).toFloat(),
                            (geo.coverY + geo.coverHeight).toFloat(),
                        )
                        val outsideStamp = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addRect(coverRect)
                        }
                        clipPath(outsideStamp) {
                            drawImage(img)
                        }
                        // Exact hero outer radius (white border sits on this curve).
                        val r = MusicOutpaintHeroMetrics.coverCornerRadiusPx(geo.coverWidth)
                        if (r > 0.5f) {
                            paintExactWidgetCoverChrome(img, geo, r)
                        }
                    } else {
                        drawImage(img)
                    }
                    // Near-transparent wash under the hero — outpaint stays dominant;
                    // metadata readability comes from soft white text glow, not a solid band.
                    val fadeStart = geometry?.let { (it.coverY + it.coverHeight).toFloat() } ?: size.height
                    val fadeEnd = maxOf(size.height, fadeStart + 1f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.20f to CardLight.copy(alpha = 0.04f),
                                0.45f to CardLight.copy(alpha = 0.08f),
                                0.70f to CardLight.copy(alpha = 0.12f),
                                1.00f to CardLight.copy(alpha = 0.16f),
                            ),
                            startY = fadeStart,
                            endY = fadeEnd,
                        ),
                    )
                }
                drawContent()
            }) { content() }
    }
}

/**
 * Fill square−round ear wedges with true outside-corner pad chrome, then paint a
 * soft gradient contact shadow into the pad so lift reads on busy Flux art
 * (Material elevation alone vanishes on identical stamp pixels).
 *
 * Edge-strip stretches sampled dark hair next to the stamp and recreated square
 * ears; corner patches from the pad *outside* the cover match the visible backdrop.
 */
internal fun DrawScope.paintExactWidgetCoverChrome(
    pad: ImageBitmap,
    geo: WidgetOutpaintGeometry,
    radius: Float,
) {
    val x0 = geo.coverX.toFloat()
    val y0 = geo.coverY.toFloat()
    val x1 = (geo.coverX + geo.coverWidth).toFloat()
    val y1 = (geo.coverY + geo.coverHeight).toFloat()
    val coverRect = Rect(x0, y0, x1, y1)
    val corner = CornerRadius(radius, radius)

    val earPath = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(coverRect)
        addRoundRect(RoundRect(coverRect, corner))
    }
    val ri = radius.roundToInt().coerceAtLeast(1)
    clipPath(earPath) {
        fillCoverCornerEarsFromPad(pad, geo, ri)
    }

    // Soft dark falloff only outside the rounded cover — round silhouette, no
    // stepped filled rings (those read as concentric contour bands on device).
    val outsideHero = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(coverRect, corner))
    }
    clipPath(outsideHero) {
        paintSoftContactShadow(x0, y0, x1, y1, radius)
    }
}

/**
 * Four corner wedges only (square − roundrect). Sample the pad block diagonally
 * outside each corner so Flux/hair at the stamp rim never paints the ear.
 */
private fun DrawScope.fillCoverCornerEarsFromPad(
    pad: ImageBitmap,
    geo: WidgetOutpaintGeometry,
    ri: Int,
) {
    val padW = pad.width
    val padH = pad.height
    val cx = geo.coverX
    val cy = geo.coverY
    val cw = geo.coverWidth
    val ch = geo.coverHeight
    val rightPad = (padW - cx - cw).coerceAtLeast(0)
    val bottomPad = (padH - cy - ch).coerceAtLeast(0)

    // TL — northwest of cover.
    if (cx > 0 && cy > 0) {
        val srcW = cx.coerceAtMost(ri).coerceAtLeast(1)
        val srcH = cy.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx - srcW, cy - srcH),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset(cx, cy),
            dstSize = IntSize(ri, ri),
        )
    } else if (cy > 0) {
        val srcH = cy.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx.coerceIn(0, padW - 1), 0),
            srcSize = IntSize(cw.coerceAtMost(padW - cx).coerceAtLeast(1), srcH),
            dstOffset = IntOffset(cx, cy),
            dstSize = IntSize(ri.coerceAtMost(cw), ri),
        )
    } else if (cx > 0) {
        val srcW = cx.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(0, cy.coerceIn(0, padH - 1)),
            srcSize = IntSize(srcW, ch.coerceAtMost(padH - cy).coerceAtLeast(1)),
            dstOffset = IntOffset(cx, cy),
            dstSize = IntSize(ri, ri.coerceAtMost(ch)),
        )
    }

    // TR — northeast of cover.
    if (rightPad > 0 && cy > 0) {
        val srcW = rightPad.coerceAtMost(ri).coerceAtLeast(1)
        val srcH = cy.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx + cw, cy - srcH),
            srcSize = IntSize(srcW.coerceAtMost(padW - cx - cw), srcH),
            dstOffset = IntOffset(cx + cw - ri, cy),
            dstSize = IntSize(ri, ri),
        )
    } else if (cy > 0) {
        val srcH = cy.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx.coerceIn(0, padW - 1), 0),
            srcSize = IntSize(cw.coerceAtMost(padW - cx).coerceAtLeast(1), srcH),
            dstOffset = IntOffset(cx + cw - ri, cy),
            dstSize = IntSize(ri.coerceAtMost(cw), ri),
        )
    } else if (rightPad > 0) {
        val srcW = rightPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx + cw, cy.coerceIn(0, padH - 1)),
            srcSize = IntSize(srcW.coerceAtMost(padW - cx - cw), ch.coerceAtMost(padH - cy).coerceAtLeast(1)),
            dstOffset = IntOffset(cx + cw - ri, cy),
            dstSize = IntSize(ri, ri.coerceAtMost(ch)),
        )
    }

    // BL — southwest of cover.
    if (cx > 0 && bottomPad > 0) {
        val srcW = cx.coerceAtMost(ri).coerceAtLeast(1)
        val srcH = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx - srcW, cy + ch),
            srcSize = IntSize(srcW, srcH.coerceAtMost(padH - cy - ch)),
            dstOffset = IntOffset(cx, cy + ch - ri),
            dstSize = IntSize(ri, ri),
        )
    } else if (bottomPad > 0) {
        val srcH = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx.coerceIn(0, padW - 1), cy + ch),
            srcSize = IntSize(cw.coerceAtMost(padW - cx).coerceAtLeast(1), srcH.coerceAtMost(padH - cy - ch)),
            dstOffset = IntOffset(cx, cy + ch - ri),
            dstSize = IntSize(ri.coerceAtMost(cw), ri),
        )
    } else if (cx > 0) {
        val srcW = cx.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(0, cy.coerceIn(0, padH - 1)),
            srcSize = IntSize(srcW, ch.coerceAtMost(padH - cy).coerceAtLeast(1)),
            dstOffset = IntOffset(cx, cy + ch - ri),
            dstSize = IntSize(ri, ri.coerceAtMost(ch)),
        )
    }

    // BR — southeast of cover.
    if (rightPad > 0 && bottomPad > 0) {
        val srcW = rightPad.coerceAtMost(ri).coerceAtLeast(1)
        val srcH = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx + cw, cy + ch),
            srcSize = IntSize(
                srcW.coerceAtMost(padW - cx - cw),
                srcH.coerceAtMost(padH - cy - ch),
            ),
            dstOffset = IntOffset(cx + cw - ri, cy + ch - ri),
            dstSize = IntSize(ri, ri),
        )
    } else if (bottomPad > 0) {
        val srcH = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx.coerceIn(0, padW - 1), cy + ch),
            srcSize = IntSize(cw.coerceAtMost(padW - cx).coerceAtLeast(1), srcH.coerceAtMost(padH - cy - ch)),
            dstOffset = IntOffset(cx + cw - ri, cy + ch - ri),
            dstSize = IntSize(ri.coerceAtMost(cw), ri),
        )
    } else if (rightPad > 0) {
        val srcW = rightPad.coerceAtMost(ri).coerceAtLeast(1)
        drawImage(
            image = pad,
            srcOffset = IntOffset(cx + cw, cy.coerceIn(0, padH - 1)),
            srcSize = IntSize(srcW.coerceAtMost(padW - cx - cw), ch.coerceAtMost(padH - cy).coerceAtLeast(1)),
            dstOffset = IntOffset(cx + cw - ri, cy + ch - ri),
            dstSize = IntSize(ri, ri.coerceAtMost(ch)),
        )
    }
}

/**
 * Smooth float shadow: BlurMaskFilter penumbra on API 28+ (HW blur supported),
 * dense stroked rings with quadratic alpha falloff on older devices.
 */
private fun DrawScope.paintSoftContactShadow(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    radius: Float,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            // Wide soft penumbra — no hard outer contour.
            paint.color = android.graphics.Color.argb(88, 0, 0, 0)
            paint.maskFilter = BlurMaskFilter(26f, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawRoundRect(
                x0 - 3f,
                y0 - 3f + 10f,
                x1 + 3f,
                y1 + 3f + 10f,
                radius + 3f,
                radius + 3f,
                paint,
            )
            // Tighter umbra near the frame for readable lift on busy Flux.
            paint.color = android.graphics.Color.argb(64, 0, 0, 0)
            paint.maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawRoundRect(
                x0 - 0.5f,
                y0 - 0.5f + 3f,
                x1 + 0.5f,
                y1 + 0.5f + 3f,
                radius + 0.5f,
                radius + 0.5f,
                paint,
            )
        }
        return
    }
    val maxExpand = 36f
    val steps = 36
    val peakAlpha = 0.38f
    val stroke = (maxExpand / steps) * 1.45f
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val expand = maxExpand * t
        val alpha = peakAlpha * (1f - t) * (1f - t)
        val drop = expand * 0.45f
        drawRoundRect(
            color = Color.Black.copy(alpha = alpha),
            topLeft = Offset(x0 - expand, y0 - expand + drop),
            size = Size(x1 - x0 + 2f * expand, y1 - y0 + 2f * expand),
            cornerRadius = CornerRadius(radius + expand, radius + expand),
            style = Stroke(width = stroke),
        )
    }
}
