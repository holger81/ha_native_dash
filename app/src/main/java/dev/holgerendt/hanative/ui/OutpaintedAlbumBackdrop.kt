package dev.holgerendt.hanative.ui

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.holgerendt.hanative.ui.theme.CardLight
import java.io.File
import dev.holgerendt.hanative.data.OutpaintCoverLayout
import dev.holgerendt.hanative.data.alignedOutpaintPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Shared hero metrics: cover position/size is identical with or without outpaint. */
internal object MusicOutpaintHeroMetrics {
    val StageHeight = 220.dp
    val CoverSize = 196.dp
}

/**
 * Atmosphere / hero art behind Phase 6 music UI.
 *
 * The full music hero registers the pad's original-cover region to the fixed
 * sharp cover. Compact strips can continue using a decorative atmosphere.
 */
@Composable
fun OutpaintedAlbumBackdrop(
    coverPath: String?,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    /** False when there is no music art to extend (e.g. idle / TV without poster). */
    extendedBackdrop: Boolean = true,
    /** Extra cover refs (e.g. MASS queue art) to try when looking up a cached pad. */
    coverAlternates: List<String> = emptyList(),
    vivid: Boolean = false,
    alignWithHero: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        if (extendedBackdrop && !coverPath.isNullOrBlank()) {
            SoftAtmosphereLayer(
                coverPath = coverPath,
                coverAlternates = coverAlternates,
                viewModel = viewModel,
                vivid = vivid,
                alignWithHero = alignWithHero,
            )
        }
        content()
    }
}

/**
 * Top-stage album hero: always a centered [MusicOutpaintHeroMetrics.CoverSize]
 * cover in [stageHeight] — identical whether Flux outpaint is ready or not.
 */
@Composable
fun AlbumOutpaintHero(
    coverPath: String?,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    coverAlternates: List<String> = emptyList(),
    stageHeight: Dp? = null,
) {
    val ui by viewModel.ui.collectAsState()
    val coverRefs = remember(coverPath, coverAlternates) {
        (listOfNotNull(coverPath) + coverAlternates)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val resolvedStage = stageHeight ?: MusicOutpaintHeroMetrics.StageHeight

    LaunchedEffect(coverRefs, ui.mediagenUrl) {
        if (coverRefs.isEmpty()) return@LaunchedEffect
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverRefs.first())
        runCatching { viewModel.albumArtOutpaint.ensureLocalPad(coverRefs.first()) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(resolvedStage),
        contentAlignment = Alignment.Center,
    ) {
        MusicCover(
            path = coverPath,
            viewModel = viewModel,
            modifier = Modifier
                .size(MusicOutpaintHeroMetrics.CoverSize)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(22.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.15f),
                    spotColor = Color.Black.copy(alpha = 0.25f),
                )
                .clip(RoundedCornerShape(22.dp)),
            spinnerSize = 28.dp,
            fallbackIconSize = 64.dp,
        )
    }
}

@Composable
private fun BoxScope.SoftAtmosphereLayer(
    coverPath: String,
    coverAlternates: List<String>,
    viewModel: HaViewModel,
    vivid: Boolean,
    alignWithHero: Boolean,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val ui by viewModel.ui.collectAsState()
    val wall by viewModel.musicWall.collectAsState()
    val coverRefs = remember(coverPath, coverAlternates, wall.queue?.current?.imageUrl) {
        (listOf(coverPath) + coverAlternates + listOfNotNull(wall.queue?.current?.imageUrl))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    var coverUrl by remember(coverPath, viewModel.client.currentBaseUrl) { mutableStateOf<String?>(null) }
    var padFile by remember(coverRefs) { mutableStateOf<File?>(null) }
    var padStamp by remember(coverRefs) { mutableStateOf(0L) }
    var fluxComplete by remember(coverRefs) { mutableStateOf(false) }

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl, ui.mediagenUrl) {
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
    }
    LaunchedEffect(coverRefs, ui.mediagenUrl) {
        padFile = null
        padStamp = 0L
        fluxComplete = false
        if (coverRefs.isEmpty()) return@LaunchedEffect
        val primary = coverRefs.first()
        val local = runCatching {
            viewModel.albumArtOutpaint.ensureLocalPad(primary)
        }.getOrNull()
        if (local != null) {
            padFile = local
            padStamp = local.length() xor local.lastModified()
        }
        for (ref in coverRefs.drop(1)) {
            runCatching { viewModel.albumArtOutpaint.ensureLocalPad(ref) }
        }
        var lastStamp = padStamp
        while (true) {
            val hit = runCatching {
                viewModel.albumArtOutpaint.peekOutpaintedFile(coverRefs)
            }.getOrNull()
            val fluxDone = runCatching {
                viewModel.albumArtOutpaint.peekFluxComplete(coverRefs)
            }.getOrDefault(false)
            fluxComplete = fluxDone
            if (hit != null) {
                val stamp = hit.length() xor hit.lastModified()
                if (stamp != lastStamp) {
                    lastStamp = stamp
                    padFile = hit
                    padStamp = stamp
                }
            }
            delay(if (fluxDone) 30_000L else 2_000L)
        }
    }

    val outpaint = padFile
    // Map the pad's baked cover region onto the fixed 196/220 hero — never FillBounds-stretch.
    if (outpaint != null && alignWithHero) {
        val aligned = rememberAlignedHeroFrame(outpaint, padStamp, viewModel)
        if (aligned != null) {
            AlignedHeroBackdrop(aligned.first, aligned.second)
            return
        }
        // While decoding, keep soft atmosphere below rather than a blank flash.
    }
    if (outpaint != null && fluxComplete && !vivid && !alignWithHero) {
        // Compact / non-hero surfaces: decorative full-bleed pad (aspect may not match cover).
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaint)
                .memoryCacheKey("outpaint-player-${outpaint.name}-$padStamp")
                .diskCacheKey("outpaint-player-${outpaint.name}-$padStamp")
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            colorFilter = desaturateFilter(0.92f),
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 0.94f },
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to CardLight.copy(alpha = 0.06f),
                            0.45f to CardLight.copy(alpha = 0.10f),
                            0.72f to CardLight.copy(alpha = 0.28f),
                            1.00f to CardLight.copy(alpha = 0.42f),
                        ),
                    ),
                ),
        )
        return
    }
    if (outpaint != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaint)
                .memoryCacheKey("outpaint-bg-${outpaint.name}-$padStamp")
                .diskCacheKey("outpaint-bg-${outpaint.name}-$padStamp")
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            colorFilter = desaturateFilter(
                when {
                    vivid -> 1f
                    fluxComplete -> 0.85f
                    else -> 0.7f
                },
            ),
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = when {
                        vivid -> 0.85f
                        fluxComplete -> 0.78f
                        else -> 0.68f
                    }
                }
                .then(if (fluxComplete || vivid) Modifier else softBlurFallback())
                .then(
                    when {
                        vivid || fluxComplete -> Modifier
                        else -> Modifier.fadeLocalAtmosphere()
                    },
                ),
        )
        return
    }

    val url = coverUrl ?: return
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .build(),
        contentDescription = null,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        colorFilter = desaturateFilter(if (vivid) 0.75f else 0.4f),
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                scaleX = 1.25f
                scaleY = 1.25f
                alpha = if (vivid) 0.5f else 0.28f
            }
            .then(softBlurFallback())
            .then(if (vivid) Modifier else Modifier.fadeSoftAtmosphere()),
    )
}

@Composable
private fun rememberAlignedHeroFrame(
    file: File,
    stamp: Long,
    viewModel: HaViewModel,
): Pair<Bitmap, OutpaintCoverLayout>? {
    var frame by remember(file, stamp) { mutableStateOf<Pair<Bitmap, OutpaintCoverLayout>?>(null) }
    LaunchedEffect(file, stamp) {
        frame = withContext(Dispatchers.IO) {
            val layout = viewModel.albumArtOutpaint.outpaintLayoutForFile(file) ?: return@withContext null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            bitmap to layout
        }
    }
    return frame
}

/**
 * Scale/translate the pad so its baked-in cover region coincides with the sharp
 * [MusicOutpaintHeroMetrics] rect. Clamp edge pixels only where the pad does not
 * yet reach the card bounds (older/smaller pads).
 */
@Composable
private fun BoxScope.AlignedHeroBackdrop(bitmap: Bitmap, layout: OutpaintCoverLayout) {
    Box(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val placement = alignedOutpaintPlacement(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    layout = layout,
                    cardWidth = size.width,
                    coverSize = MusicOutpaintHeroMetrics.CoverSize.toPx(),
                    stageHeight = MusicOutpaintHeroMetrics.StageHeight.toPx(),
                )
                val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(placement.scale, placement.scale)
                            postTranslate(placement.left, placement.top)
                        },
                    )
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                }
                // Keep the hero band untinted so horizon/wall lines match the cover.
                val fadeStart =
                    ((MusicOutpaintHeroMetrics.StageHeight + MusicOutpaintHeroMetrics.CoverSize) / 2).toPx()
                val wash = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, CardLight.copy(alpha = 0.85f)),
                    startY = fadeStart,
                    endY = maxOf(size.height, fadeStart + 1f),
                )
                onDrawBehind {
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                    drawRect(wash)
                }
            },
    )
}

private fun softBlurFallback(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(28.dp)
    } else {
        Modifier
    }

private fun desaturateFilter(saturation: Float): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(saturation.coerceIn(0f, 1f)) }
    return ColorFilter.colorMatrix(matrix)
}

private fun Modifier.fadeSoftAtmosphere(): Modifier = drawWithContent {
    drawContent()
    val card = CardLight
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to card.copy(alpha = 0.35f),
                0.50f to card.copy(alpha = 0.55f),
                0.75f to card.copy(alpha = 0.78f),
                1.00f to card.copy(alpha = 0.92f),
            ),
        ),
    )
}

/** Lighter wash so local edge pads still fill the card width (not grey pillars). */
private fun Modifier.fadeLocalAtmosphere(): Modifier = drawWithContent {
    drawContent()
    val card = CardLight
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to card.copy(alpha = 0.12f),
                0.55f to card.copy(alpha = 0.22f),
                0.82f to card.copy(alpha = 0.45f),
                1.00f to card.copy(alpha = 0.68f),
            ),
        ),
    )
}
