package dev.holgerendt.hanative.ui

import android.os.Build
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.holgerendt.hanative.ui.theme.CardLight
import java.io.File
import kotlinx.coroutines.delay

/** Shared hero metrics: cover position/size is identical with or without outpaint. */
internal object MusicOutpaintHeroMetrics {
    val StageHeight = 220.dp
    val CoverSize = 196.dp
}

/**
 * Atmosphere / hero art behind Phase 6 music UI.
 *
 * Soft enlarge fills the card until a pad is cached; Flux pads are drawn as a
 * full-bleed card backdrop only. The floating sharp cover always uses fixed
 * [MusicOutpaintHeroMetrics] — never mediagen canvas fractions.
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
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        if (extendedBackdrop && !coverPath.isNullOrBlank()) {
            SoftAtmosphereLayer(
                coverPath = coverPath,
                coverAlternates = coverAlternates,
                viewModel = viewModel,
                vivid = vivid,
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
                    elevation = 28.dp,
                    shape = RoundedCornerShape(22.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
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
    if (outpaint != null && fluxComplete && !vivid) {
        // Full-bleed Flux pad as card backdrop only (cover position is fixed in the hero).
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaint)
                .memoryCacheKey("outpaint-player-${outpaint.name}-$padStamp")
                .diskCacheKey("outpaint-player-${outpaint.name}-$padStamp")
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.FillBounds,
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
