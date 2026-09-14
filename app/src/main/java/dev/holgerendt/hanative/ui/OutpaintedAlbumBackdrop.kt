package dev.holgerendt.hanative.ui

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import dev.holgerendt.hanative.data.OutpaintCoverLayout
import dev.holgerendt.hanative.data.outpaintCoverLayout
import dev.holgerendt.hanative.ui.theme.CardLight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Atmosphere / hero art behind Phase 6 music UI.
 *
 * Soft enlarge fills the card until a pad is cached; then that pad (local or
 * Flux) fills the full card background. [AlbumOutpaintHero] still waits for
 * Flux before the hovering cover stage. Compact backyard strips use the same
 * atmosphere when music art is available.
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
 * Full-width album hero: hovering sharp cover on aligned outpaint when ready,
 * otherwise a centered cover (parent may still draw soft atmosphere).
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
    var outpaintFile by remember(coverRefs) { mutableStateOf<File?>(null) }
    var layout by remember(coverRefs) { mutableStateOf<OutpaintCoverLayout?>(null) }
    var fileStamp by remember(coverRefs) { mutableStateOf(0L) }
    var fluxComplete by remember(coverRefs) { mutableStateOf(false) }

    LaunchedEffect(coverRefs, ui.comfyUiUrl) {
        outpaintFile = null
        layout = null
        fileStamp = 0L
        fluxComplete = false
        if (coverRefs.isEmpty() || ui.comfyUiUrl.isBlank()) return@LaunchedEffect
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverRefs.first())
        var lastStamp = 0L
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
                    val bounds = withContext(Dispatchers.IO) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(hit.absolutePath, opts)
                        opts.outWidth to opts.outHeight
                    }
                    val nextLayout = outpaintCoverLayout(bounds.first, bounds.second)
                    if (nextLayout != null) {
                        outpaintFile = hit
                        layout = nextLayout
                        fileStamp = stamp
                    }
                }
            }
            delay(if (fluxDone) 30_000L else 2_000L)
        }
    }

    Crossfade(
        targetState = if (fluxComplete) Triple(outpaintFile, layout, fileStamp) else Triple(null, null, 0L),
        modifier = modifier.fillMaxWidth(),
        label = "album-outpaint-hero",
    ) { (file, geo, stamp) ->
        if (file != null && geo != null) {
            HoveringOutpaintStage(
                outpaintFile = file,
                fileStamp = stamp,
                layout = geo,
                coverPath = coverPath,
                viewModel = viewModel,
                stageHeight = stageHeight,
            )
        } else {
            // Local edge pads look blocky under a floating cover — keep the soft
            // enlarge + centered art until Flux actually lands.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stageHeight ?: 220.dp),
                contentAlignment = Alignment.Center,
            ) {
                MusicCover(
                    path = coverPath,
                    viewModel = viewModel,
                    modifier = Modifier
                        .size(196.dp)
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
    }
}

@Composable
private fun HoveringOutpaintStage(
    outpaintFile: File,
    fileStamp: Long,
    layout: OutpaintCoverLayout,
    coverPath: String?,
    viewModel: HaViewModel,
    stageHeight: Dp? = null,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val coverShape = RoundedCornerShape(22.dp)
    val cacheKey = "outpaint-${outpaintFile.name}-$fileStamp"
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (stageHeight != null) Modifier.height(stageHeight) else Modifier.aspectRatio(layout.outAspectRatio))
            .clip(RoundedCornerShape(16.dp)),
    ) {
        val imageWidth = minOf(maxWidth, maxHeight * layout.outAspectRatio)
        val imageHeight = imageWidth / layout.outAspectRatio
        val imageLeft = (maxWidth - imageWidth) / 2
        val imageTop = (maxHeight - imageHeight) / 2
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaintFile)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            // Preserve the complete outpaint and align the floating cover to its baked-in region.
            contentScale = ContentScale.Fit,
            colorFilter = desaturateFilter(0.9f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.88f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(CardLight.copy(alpha = 0.08f)),
        )
        val coverW = imageWidth * layout.coverWidthFrac
        val coverH = imageHeight * layout.coverHeightFrac
        val coverLeft = imageLeft + imageWidth * layout.coverLeftFrac
        val coverTop = imageTop + imageHeight * layout.coverTopFrac
        // Exact pad region: hide the square cover baked into the outpaint so a
        // lifted rounded overlay cannot reveal a mismatched strip underneath.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = coverLeft, y = coverTop)
                .size(coverW, coverH)
                .background(Color.Black.copy(alpha = 0.55f)),
        )
        // Slight grow only — lift comes from shadow, not Y offset (offset exposed the pad).
        val grow = 0.04f
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = coverLeft - coverW * (grow / 2f),
                    y = coverTop - coverH * (grow / 2f),
                )
                .size(coverW * (1f + grow), coverH * (1f + grow)),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 10.dp, y = 14.dp)
                    .shadow(
                        elevation = 36.dp,
                        shape = coverShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.38f),
                        spotColor = Color.Black.copy(alpha = 0.62f),
                    )
                    .background(Color.Transparent, coverShape),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = 18.dp,
                        shape = coverShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.42f),
                    )
                    .clip(coverShape)
                    .border(1.25.dp, Color.White.copy(alpha = 0.5f), coverShape),
            ) {
                MusicCover(
                    path = coverPath,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                    spinnerSize = 22.dp,
                    fallbackIconSize = 40.dp,
                )
            }
        }
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

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl) {
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
    }
    LaunchedEffect(coverRefs, ui.comfyUiUrl) {
        padFile = null
        padStamp = 0L
        fluxComplete = false
        if (ui.comfyUiUrl.isBlank() || coverRefs.isEmpty()) return@LaunchedEffect
        var lastStamp = 0L
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
            } else {
                padFile = null
                padStamp = 0L
                lastStamp = 0L
            }
            delay(if (padFile != null) 30_000L else 2_000L)
        }
    }

    val outpaint = padFile
    if (outpaint != null) {
        // Any cached pad (local or Flux) fills the card — soft enlarge is interim only.
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
            colorFilter = desaturateFilter(if (vivid) 1f else if (fluxComplete) 0.7f else 0.55f),
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (vivid) 0.85f else if (fluxComplete) 0.55f else 0.4f }
                .then(if (fluxComplete) Modifier else softBlurFallback())
                .then(if (vivid) Modifier else Modifier.fadeSoftAtmosphere()),
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
        colorFilter = desaturateFilter(0.4f),
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                scaleX = 1.25f
                scaleY = 1.25f
                alpha = 0.28f
            }
            .then(softBlurFallback())
            .fadeSoftAtmosphere(),
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
