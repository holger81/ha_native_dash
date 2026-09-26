package dev.holgerendt.hanative.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.holgerendt.hanative.ui.theme.CardLight
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Shared hero metrics: cover position/size is identical with or without outpaint. */
internal object MusicOutpaintHeroMetrics {
    val StageHeight = 220.dp
    val CoverSize = 196.dp
    /** Must match [AlbumOutpaintHero] clip / border and ExactWidgetOutpaint corner masks. */
    val CoverCornerRadius = 22.dp

    /** Pixel radius for a cover that is [coverWidthPx] wide (scales with measured hero). */
    fun coverCornerRadiusPx(coverWidthPx: Int): Float =
        coverWidthPx * (CoverCornerRadius.value / CoverSize.value)
}

/**
 * Atmosphere / hero art behind Phase 6 music UI.
 *
 * The full music hero uses a measured, pixel-exact canvas. Compact strips
 * retain their decorative atmosphere.
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
    if (alignWithHero && extendedBackdrop) {
        ExactWidgetOutpaint(coverPath, viewModel, modifier, content)
        return
    }
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
    val exact = LocalExactWidgetArt.current
    val ui by viewModel.ui.collectAsState()
    val coverRefs = remember(coverPath, coverAlternates) {
        (listOfNotNull(coverPath) + coverAlternates)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val resolvedStage = stageHeight ?: MusicOutpaintHeroMetrics.StageHeight

    LaunchedEffect(coverRefs, ui.mediagenUrl) {
        if (exact != null || coverRefs.isEmpty()) return@LaunchedEffect
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverRefs.first())
        runCatching { viewModel.albumArtOutpaint.ensureLocalPad(coverRefs.first()) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(resolvedStage),
        contentAlignment = Alignment.Center,
    ) {
        val coverShape = RoundedCornerShape(MusicOutpaintHeroMetrics.CoverCornerRadius)
        // Contact cushions sit behind the measured cover. Offsets never change the
        // source rectangle ExactWidget registers for the stamp — only the draw.
        val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (supportsBlur) {
            Box(
                Modifier
                    .size(MusicOutpaintHeroMetrics.CoverSize)
                    .offset(y = 16.dp)
                    .blur(40.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(Color.Black.copy(alpha = 0.48f), coverShape),
            )
            Box(
                Modifier
                    .size(MusicOutpaintHeroMetrics.CoverSize)
                    .offset(y = 5.dp)
                    .blur(10.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(Color.Black.copy(alpha = 0.36f), coverShape),
            )
        } else {
            Box(
                Modifier
                    .size(MusicOutpaintHeroMetrics.CoverSize)
                    .offset(y = 12.dp)
                    .background(Color.Black.copy(alpha = 0.40f), coverShape),
            )
        }
        // Outer shell: layout + measure + elevation. clip=false so the soft
        // penumbra is not truncated at the cover edge.
        Box(
            Modifier
                .size(MusicOutpaintHeroMetrics.CoverSize)
                .then(
                    if (exact != null) {
                        Modifier.onGloballyPositioned {
                            exact.coverCoordinates = it
                            exact.measure()
                        }
                    } else {
                        Modifier
                    },
                )
                .shadow(
                    elevation = 44.dp,
                    shape = coverShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.82f),
                ),
        ) {
            // Inner frame: clip is a separate child layer so square bitmap /
            // AsyncImage pixels cannot bleed past the rounded white border.
            // (shadow(clip=false) + same-chain clip was leaving ExactWidget
            // drawImage corners unclipped over the outpaint stamp.)
            val frame = Modifier
                .fillMaxSize()
                .clip(coverShape)
                .border(1.25.dp, Color.White.copy(alpha = 0.55f), coverShape)
            if (exact != null && exact.cover != null) {
                val coverBmp = exact.cover!!
                Box(
                    frame.drawWithContent {
                        val radius = MusicOutpaintHeroMetrics.CoverCornerRadius.toPx()
                        clipPath(
                            Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        rect = Rect(Offset.Zero, size),
                                        cornerRadius = CornerRadius(radius, radius),
                                    ),
                                )
                            },
                        ) {
                            drawImage(
                                image = coverBmp.asImageBitmap(),
                                dstSize = IntSize(
                                    size.width.roundToInt(),
                                    size.height.roundToInt(),
                                ),
                            )
                        }
                    },
                )
            } else {
                MusicCover(
                    path = coverPath,
                    viewModel = viewModel,
                    modifier = frame,
                    spinnerSize = 28.dp,
                    fallbackIconSize = 64.dp,
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

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl, ui.mediagenUrl) {
        // Same size as MusicCover so Coil's warmed MASS proxy entry is reused.
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 256) }.getOrNull()
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
            padStamp = outpaintFileRevision(local, fluxComplete = false)
        }
        for (ref in coverRefs.drop(1)) {
            runCatching { viewModel.albumArtOutpaint.ensureLocalPad(ref) }
        }
        var lastStamp = padStamp
        var lastFluxDone = false
        while (true) {
            val hit = runCatching {
                viewModel.albumArtOutpaint.peekOutpaintedFile(coverRefs)
            }.getOrNull()
            val fluxDone = runCatching {
                viewModel.albumArtOutpaint.peekFluxComplete(coverRefs)
            }.getOrDefault(false)
            // Flip fluxComplete before stamp so aligned/Coil keys see Flux in the same frame.
            fluxComplete = fluxDone
            if (hit != null) {
                // Include .flux in the stamp: replace() writes the same path, and length/mtime
                // alone can collide (1s FS resolution + similar JPEG sizes) so SoftAtmosphere
                // would keep the decoded local pad until a forced remount.
                val stamp = outpaintFileRevision(hit, fluxDone)
                if (stamp != lastStamp || fluxDone != lastFluxDone) {
                    lastStamp = stamp
                    lastFluxDone = fluxDone
                    padFile = hit
                    padStamp = stamp
                }
            } else if (fluxDone != lastFluxDone) {
                lastFluxDone = fluxDone
            }
            delay(if (fluxDone) 30_000L else 2_000L)
        }
    }

    val outpaint = padFile
    if (outpaint != null && fluxComplete && !vivid) {
        // Compact / non-hero surfaces: decorative full-bleed pad (aspect may not match cover).
        // Slight overscan crops Flux-invented white photo-frame rims under the card clip.
        // (Hero ExactWidgetOutpaint stays 1:1 so the stamped album cannot spill past its frame.)
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
                .graphicsLayer {
                    alpha = 0.94f
                    scaleX = OUTPAINT_EDGE_OVERSCAN
                    scaleY = OUTPAINT_EDGE_OVERSCAN
                },
        )
        // Near-transparent wash so Flux outpaint shows through; text glow carries readability.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to CardLight.copy(alpha = 0.02f),
                            0.40f to CardLight.copy(alpha = 0.04f),
                            0.58f to CardLight.copy(alpha = 0.08f),
                            0.78f to CardLight.copy(alpha = 0.12f),
                            1.00f to CardLight.copy(alpha = 0.16f),
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
                    else -> 0.78f
                },
            ),
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = when {
                        vivid -> 0.85f
                        fluxComplete -> 0.78f
                        else -> 0.82f
                    }
                    if (fluxComplete) {
                        scaleX = OUTPAINT_EDGE_OVERSCAN
                        scaleY = OUTPAINT_EDGE_OVERSCAN
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
        colorFilter = desaturateFilter(if (vivid) 0.75f else 0.55f),
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                scaleX = 1.25f
                scaleY = 1.25f
                alpha = if (vivid) 0.5f else 0.42f
            }
            .then(softBlurFallback())
            .then(if (vivid) Modifier else Modifier.fadeSoftAtmosphere()),
    )
}

/**
 * Disk revision for a cached pad. Mixing in [fluxComplete] ensures an in-place Flux
 * [AlbumArtOutpaintCache.replace] always invalidates SoftAtmosphere / Coil keys even when
 * length and mtime are unchanged.
 */
internal fun outpaintFileRevision(file: File, fluxComplete: Boolean): Long {
    val fluxBit = if (fluxComplete) 1L shl 62 else 0L
    return file.length() xor file.lastModified() xor fluxBit
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
                0.00f to card.copy(alpha = 0.04f),
                0.45f to card.copy(alpha = 0.08f),
                0.70f to card.copy(alpha = 0.14f),
                1.00f to card.copy(alpha = 0.20f),
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
                0.00f to card.copy(alpha = 0.03f),
                0.50f to card.copy(alpha = 0.06f),
                0.75f to card.copy(alpha = 0.12f),
                1.00f to card.copy(alpha = 0.18f),
            ),
        ),
    )
}
