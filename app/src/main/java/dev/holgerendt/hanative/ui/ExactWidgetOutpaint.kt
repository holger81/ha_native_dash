package dev.holgerendt.hanative.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
                    // Pixel-exact: stamped cover aligns with AlbumOutpaintHero's framed box.
                    drawImage(img)
                    val geo = geometry
                    if (geo != null) {
                        // Over-punch slightly past the white frame so AA fringes cannot
                        // leave square stamp ears outside the curve.
                        val r = MusicOutpaintHeroMetrics.coverCornerRadiusPx(geo.coverWidth) + 1.5f
                        if (r > 0.5f) {
                            paintExactWidgetCoverChrome(img, geo, r)
                        }
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
 * Hide square stamp corners that peek past the rounded hero, then paint a
 * contact shadow *into* the pad so lift reads on busy Flux art (Material
 * elevation alone vanishes on identical stamp pixels).
 *
 * Solid single-pixel corner fills were worse than nothing: sampling dark hair
 * just outside the stamp and painting r×r blocks recreated triangular "ears".
 * Stretch pad edge strips into the wedges instead, then darken with a soft
 * round contact ring (no square silhouette).
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
    val padW = pad.width
    val padH = pad.height
    clipPath(earPath) {
        // Stretch true pad strips (outside the stamp) into the ear wedges so
        // thin top/side chrome still covers square stamp corners.
        if (geo.coverY > 0) {
            val th = geo.coverY.coerceAtMost(ri).coerceAtLeast(1)
            drawImage(
                image = pad,
                srcOffset = IntOffset(geo.coverX.coerceIn(0, padW - 1), 0),
                srcSize = IntSize(geo.coverWidth.coerceAtMost(padW - geo.coverX), th),
                dstOffset = IntOffset(geo.coverX, geo.coverY),
                dstSize = IntSize(geo.coverWidth, ri),
            )
        }
        if (geo.coverX > 0) {
            val lw = geo.coverX.coerceAtMost(ri).coerceAtLeast(1)
            drawImage(
                image = pad,
                srcOffset = IntOffset(0, geo.coverY.coerceIn(0, padH - 1)),
                srcSize = IntSize(lw, geo.coverHeight.coerceAtMost(padH - geo.coverY)),
                dstOffset = IntOffset(geo.coverX, geo.coverY),
                dstSize = IntSize(ri, geo.coverHeight),
            )
        }
        val rightPad = (padW - geo.coverX - geo.coverWidth).coerceAtLeast(0)
        if (rightPad > 0) {
            val rw = rightPad.coerceAtMost(ri).coerceAtLeast(1)
            val srcX = (geo.coverX + geo.coverWidth).coerceIn(0, padW - 1)
            drawImage(
                image = pad,
                srcOffset = IntOffset(srcX, geo.coverY.coerceIn(0, padH - 1)),
                srcSize = IntSize(rw.coerceAtMost(padW - srcX), geo.coverHeight.coerceAtMost(padH - geo.coverY)),
                dstOffset = IntOffset(geo.coverX + geo.coverWidth - ri, geo.coverY),
                dstSize = IntSize(ri, geo.coverHeight),
            )
        }
        val bottomPad = (padH - geo.coverY - geo.coverHeight).coerceAtLeast(0)
        if (bottomPad > 0) {
            val bh = bottomPad.coerceAtMost(ri).coerceAtLeast(1)
            val srcY = (geo.coverY + geo.coverHeight).coerceIn(0, padH - 1)
            drawImage(
                image = pad,
                srcOffset = IntOffset(geo.coverX.coerceIn(0, padW - 1), srcY),
                srcSize = IntSize(geo.coverWidth.coerceAtMost(padW - geo.coverX), bh.coerceAtMost(padH - srcY)),
                dstOffset = IntOffset(geo.coverX, geo.coverY + geo.coverHeight - ri),
                dstSize = IntSize(geo.coverWidth, ri),
            )
        }
    }

    // Soft dark ring only outside the rounded cover — round silhouette so the
    // ear wedges never read as a 90° stamp corner, and lift stays visible on
    // busy / identical Flux pixels where Material elevation disappears.
    val outsideHero = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(coverRect, corner))
    }
    clipPath(outsideHero) {
        val layers = listOf(
            2f to 0.48f,
            5f to 0.34f,
            11f to 0.22f,
            22f to 0.12f,
            38f to 0.06f,
        )
        for ((expand, alpha) in layers) {
            val drop = expand * 0.55f
            drawRoundRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(x0 - expand, y0 - expand + drop),
                size = Size(x1 - x0 + 2f * expand, y1 - y0 + 2f * expand),
                cornerRadius = CornerRadius(radius + expand, radius + expand),
            )
        }
    }
}
