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
 * When a Flux outpaint is cached, [AlbumOutpaintHero] places the sharp original
 * cover exactly over the unpadded region so it reads as hovering on the fill.
 * Soft enlarge remains the interim backdrop only.
 */
@Composable
fun OutpaintedAlbumBackdrop(
    coverPath: String?,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    /** False for idle Listen and compact camera-priority strips. */
    extendedBackdrop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        if (extendedBackdrop && !coverPath.isNullOrBlank()) {
            SoftAtmosphereLayer(coverPath = coverPath, viewModel = viewModel)
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
) {
    val ui by viewModel.ui.collectAsState()
    var outpaintFile by remember(coverPath) { mutableStateOf<File?>(null) }
    var layout by remember(coverPath) { mutableStateOf<OutpaintCoverLayout?>(null) }

    LaunchedEffect(coverPath, ui.comfyUiUrl) {
        outpaintFile = null
        layout = null
        if (coverPath.isNullOrBlank() || ui.comfyUiUrl.isBlank()) return@LaunchedEffect
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
        while (true) {
            val hit = runCatching {
                viewModel.albumArtOutpaint.peekOutpaintedFile(coverPath)
            }.getOrNull()
            if (hit != null) {
                val bounds = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(hit.absolutePath, opts)
                    opts.outWidth to opts.outHeight
                }
                val nextLayout = outpaintCoverLayout(bounds.first, bounds.second)
                if (nextLayout != null) {
                    outpaintFile = hit
                    layout = nextLayout
                    return@LaunchedEffect
                }
            }
            delay(2_000L)
        }
    }

    Crossfade(
        targetState = outpaintFile to layout,
        modifier = modifier.fillMaxWidth(),
        label = "album-outpaint-hero",
    ) { (file, geo) ->
        if (file != null && geo != null) {
            HoveringOutpaintStage(
                outpaintFile = file,
                layout = geo,
                coverPath = coverPath,
                viewModel = viewModel,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                MusicCover(
                    path = coverPath,
                    viewModel = viewModel,
                    modifier = Modifier
                        .size(196.dp)
                        .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                        .clip(RoundedCornerShape(20.dp)),
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
    layout: OutpaintCoverLayout,
    coverPath: String?,
    viewModel: HaViewModel,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val coverShape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(layout.outAspectRatio)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(outpaintFile)
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Fit,
            colorFilter = desaturateFilter(0.9f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.88f },
        )
        // Soft veil so the sharp cover reads as a lifted plane.
        Box(
            Modifier
                .fillMaxSize()
                .background(CardLight.copy(alpha = 0.08f)),
        )
        val coverW = maxWidth * layout.coverWidthFrac
        val coverH = maxHeight * layout.coverHeightFrac
        MusicCover(
            path = coverPath,
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = maxWidth * layout.coverLeftFrac,
                    y = maxHeight * layout.coverTopFrac,
                )
                .size(coverW, coverH)
                .shadow(18.dp, coverShape, clip = false)
                .border(1.5.dp, Color.White.copy(alpha = 0.55f), coverShape)
                .clip(coverShape),
            spinnerSize = 22.dp,
            fallbackIconSize = 40.dp,
        )
    }
}

@Composable
private fun BoxScope.SoftAtmosphereLayer(
    coverPath: String,
    viewModel: HaViewModel,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val ui by viewModel.ui.collectAsState()
    var coverUrl by remember(coverPath, viewModel.client.currentBaseUrl) { mutableStateOf<String?>(null) }
    var hasOutpaint by remember(coverPath) { mutableStateOf(false) }

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl) {
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
    }
    LaunchedEffect(coverPath, ui.comfyUiUrl) {
        hasOutpaint = false
        if (ui.comfyUiUrl.isBlank()) return@LaunchedEffect
        while (true) {
            val hit = runCatching {
                viewModel.albumArtOutpaint.peekOutpaintedFile(coverPath)
            }.getOrNull()
            if (hit != null) {
                hasOutpaint = true
                return@LaunchedEffect
            }
            delay(2_000L)
        }
    }

    // Real outpaint hero replaces the soft enlarge; keep soft only while waiting.
    if (hasOutpaint) return
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
