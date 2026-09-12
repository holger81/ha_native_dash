package dev.holgerendt.hanative.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
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
import dev.holgerendt.hanative.ui.theme.CardLight
import java.io.File
import kotlinx.coroutines.delay

/**
 * Soft extended album atmosphere behind sharp cover content for the Phase 6 media card.
 *
 * Decorative layers use [matchParentSize] so they never inflate the card. One atmosphere
 * source is shown at a time (local soft enlarge, then outpaint) and fades into the light card.
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
            AtmosphereLayer(coverPath = coverPath, viewModel = viewModel)
        }
        content()
    }
}

@Composable
private fun BoxScope.AtmosphereLayer(
    coverPath: String,
    viewModel: HaViewModel,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val ui by viewModel.ui.collectAsState()
    var coverUrl by remember(coverPath, viewModel.client.currentBaseUrl) { mutableStateOf<String?>(null) }
    var outpaintFile by remember(coverPath) { mutableStateOf<File?>(null) }

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl) {
        coverUrl = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
    }
    LaunchedEffect(coverPath, ui.comfyUiUrl) {
        outpaintFile = null
        if (ui.comfyUiUrl.isBlank()) return@LaunchedEffect
        outpaintFile = runCatching {
            viewModel.albumArtOutpaint.peekOutpaintedFile(coverPath)
        }.getOrNull()
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = coverPath)
        if (outpaintFile != null) return@LaunchedEffect
        while (true) {
            delay(2_000L)
            val hit = runCatching {
                viewModel.albumArtOutpaint.peekOutpaintedFile(coverPath)
            }.getOrNull()
            if (hit != null) {
                outpaintFile = hit
                return@LaunchedEffect
            }
        }
    }

    Crossfade(
        targetState = outpaintFile,
        modifier = Modifier.matchParentSize(),
        label = "album-atmosphere",
    ) { file ->
        val model: Any? = file ?: coverUrl
        if (model == null) {
            Box(Modifier.fillMaxSize().drawWithContent { /* light card shows through */ })
            return@Crossfade
        }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            colorFilter = desaturateFilter(0.35f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                    // ~15–25% visible image contribution before the light fade.
                    alpha = if (file != null) 0.22f else 0.18f
                }
                .then(softBlurFallback())
                .fadeEdgesToLightCard(),
        )
    }
}

private fun softBlurFallback(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(32.dp)
    } else {
        // Pre-S: extra desaturation/alpha already softens; slight extra scale via parent.
        Modifier
    }

private fun desaturateFilter(saturation: Float): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(saturation.coerceIn(0f, 1f)) }
    return ColorFilter.colorMatrix(matrix)
}

/** Fade atmosphere into the light media card so dark text stays readable. */
private fun Modifier.fadeEdgesToLightCard(): Modifier = drawWithContent {
    drawContent()
    val card = CardLight
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to card.copy(alpha = 0.35f),
                0.45f to card.copy(alpha = 0.72f),
                0.75f to card.copy(alpha = 0.92f),
                1.0f to card.copy(alpha = 1.0f),
            ),
            center = Offset(size.width * 0.32f, size.height * 0.28f),
            radius = size.maxDimension * 0.95f,
        ),
    )
}
