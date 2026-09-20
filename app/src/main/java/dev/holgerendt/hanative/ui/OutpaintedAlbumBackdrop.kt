package dev.holgerendt.hanative.ui

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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

/** Shared hero metrics so SoftAtmosphere and the sharp cover share one geometry. */
internal object MusicOutpaintHeroMetrics {
    val StageHeight = 220.dp
    val CoverSize = 196.dp
}

/**
 * Where the pad sits in the card so its baked-in cover region matches the
 * floating sharp cover.
 *
 * The pad spans the full card width (so the outpaint is not a letterboxed
 * island). Cover size is derived from that width via [OutpaintCoverLayout]
 * fractions so seams stay continuous.
 */
internal data class AlignedOutpaintGeom(
    val padLeft: Dp,
    val padTop: Dp,
    val padWidth: Dp,
    val padHeight: Dp,
    val coverLeft: Dp,
    val coverTop: Dp,
    val coverSize: Dp,
)

internal fun alignedOutpaintGeom(
    cardWidth: Dp,
    layout: OutpaintCoverLayout,
    stageHeight: Dp = MusicOutpaintHeroMetrics.StageHeight,
): AlignedOutpaintGeom {
    // Full-bleed horizontally — matches the player card width.
    val padWidth = cardWidth
    val padHeight = padWidth / layout.outAspectRatio
    val coverSize = padWidth * layout.coverWidthFrac
    val coverLeft = padWidth * layout.coverLeftFrac
    // Keep the cover band centered in the hero stage (clamped if oversized).
    val coverTop = ((stageHeight - coverSize) / 2).let { if (it < 0.dp) 0.dp else it }
    val padLeft = 0.dp
    val padTop = coverTop - padHeight * layout.coverTopFrac
    return AlignedOutpaintGeom(
        padLeft = padLeft,
        padTop = padTop,
        padWidth = padWidth,
        padHeight = padHeight,
        coverLeft = coverLeft,
        coverTop = coverTop,
        coverSize = coverSize,
    )
}

/**
 * Atmosphere / hero art behind Phase 6 music UI.
 *
 * Soft enlarge fills the card until a pad is cached; then that pad (local or
 * Flux) fills the full card background. Flux pads are laid out so the baked-in
 * cover region lines up with [AlbumOutpaintHero]'s sharp cover.
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
    val resolvedStage = stageHeight ?: MusicOutpaintHeroMetrics.StageHeight

    LaunchedEffect(coverRefs, ui.mediagenUrl) {
        outpaintFile = null
        layout = null
        fileStamp = 0L
        fluxComplete = false
        if (coverRefs.isEmpty()) return@LaunchedEffect
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverRefs.first())
        runCatching { viewModel.albumArtOutpaint.ensureLocalPad(coverRefs.first()) }
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
        targetState = if (fluxComplete && outpaintFile != null && layout != null) {
            Triple(outpaintFile, layout, fileStamp)
        } else {
            Triple(null, null, 0L)
        },
        modifier = modifier.fillMaxWidth(),
        label = "album-outpaint-hero",
    ) { (file, geo, _) ->
        if (file != null && geo != null) {
            // SoftAtmosphere already draws the full-bleed aligned pad — hero is cover only.
            AlignedCoverOnly(
                layout = geo,
                coverPath = coverPath,
                viewModel = viewModel,
                stageHeight = resolvedStage,
            )
        } else {
            // Local edge pads look blocky under a floating cover — keep the soft
            // enlarge + centered art until Flux actually lands.
            Box(
                modifier = Modifier
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
    }
}

@Composable
private fun AlignedCoverOnly(
    layout: OutpaintCoverLayout,
    coverPath: String?,
    viewModel: HaViewModel,
    stageHeight: Dp,
) {
    val coverShape = RoundedCornerShape(22.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(stageHeight),
    ) {
        val geom = alignedOutpaintGeom(
            cardWidth = maxWidth,
            layout = layout,
            stageHeight = stageHeight,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = geom.coverLeft, y = geom.coverTop)
                .size(geom.coverSize)
                .shadow(
                    elevation = 28.dp,
                    shape = coverShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
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
    var padLayout by remember(coverRefs) { mutableStateOf<OutpaintCoverLayout?>(null) }
    var fluxComplete by remember(coverRefs) { mutableStateOf(false) }

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl, ui.mediagenUrl) {
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
    }
    LaunchedEffect(coverRefs, ui.mediagenUrl) {
        padFile = null
        padStamp = 0L
        padLayout = null
        fluxComplete = false
        if (coverRefs.isEmpty()) return@LaunchedEffect
        // Build a local pad immediately so soft-enlarge is not stuck waiting on the worker.
        val primary = coverRefs.first()
        val local = runCatching {
            viewModel.albumArtOutpaint.ensureLocalPad(primary)
        }.getOrNull()
        if (local != null) {
            padFile = local
            padStamp = local.length() xor local.lastModified()
            padLayout = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(local.absolutePath, opts)
                outpaintCoverLayout(opts.outWidth, opts.outHeight)
            }
        }
        // Bind alternates so later peeks hit the same pad.
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
                    padLayout = withContext(Dispatchers.IO) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(hit.absolutePath, opts)
                        outpaintCoverLayout(opts.outWidth, opts.outHeight)
                    }
                }
            }
            delay(if (fluxDone) 30_000L else 2_000L)
        }
    }

    val outpaint = padFile
    val layout = padLayout
    if (outpaint != null && fluxComplete && layout != null && !vivid) {
        // Full-card Flux pad: scale so the baked-in cover matches the hero cover,
        // then enlarge around that cover center until the pad covers the whole card.
        FullBleedAlignedOutpaint(
            outpaint = outpaint,
            padStamp = padStamp,
            layout = layout,
            loader = loader,
        )
        return
    }
    if (outpaint != null) {
        // Local / TV vivid: Crop fill is fine — no hovering cover to align with.
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

/**
 * Draws the Flux pad across the whole card so the baked-in cover region sits
 * exactly under the sharp hero cover (same size and position).
 *
 * Exact cover-matched scale keeps scene seams continuous at the cover edge. A
 * soft Crop underpaint fills any card edges the aligned pad does not reach.
 */
@Composable
private fun BoxScope.FullBleedAlignedOutpaint(
    outpaint: File,
    padStamp: Long,
    layout: OutpaintCoverLayout,
    loader: coil.ImageLoader,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val geom = alignedOutpaintGeom(cardWidth = maxWidth, layout = layout)

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaint)
                .memoryCacheKey("outpaint-under-${outpaint.name}-$padStamp")
                .diskCacheKey("outpaint-under-${outpaint.name}-$padStamp")
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            colorFilter = desaturateFilter(0.7f),
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 0.5f }
                .then(softBlurFallback()),
        )

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaint)
                .memoryCacheKey("outpaint-fullbleed-${outpaint.name}-$padStamp")
                .diskCacheKey("outpaint-fullbleed-${outpaint.name}-$padStamp")
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.FillBounds,
            colorFilter = desaturateFilter(0.92f),
            modifier = Modifier
                .offset(x = geom.padLeft, y = geom.padTop)
                .size(width = geom.padWidth, height = geom.padHeight)
                .graphicsLayer { alpha = 0.94f },
        )

        Box(
            modifier = Modifier
                .offset(x = geom.coverLeft, y = geom.coverTop)
                .size(geom.coverSize)
                .background(Color.Black.copy(alpha = 0.35f)),
        )

        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to CardLight.copy(alpha = 0.06f),
                            0.45f to CardLight.copy(alpha = 0.10f),
                            0.78f to CardLight.copy(alpha = 0.26f),
                            1.00f to CardLight.copy(alpha = 0.40f),
                        ),
                    ),
                ),
        )
    }
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
