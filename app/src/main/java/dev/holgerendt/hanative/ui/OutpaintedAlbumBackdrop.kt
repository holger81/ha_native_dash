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
import androidx.compose.ui.graphics.Brush
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
 * Atmosphere behind the sharp Phase 6 album cover.
 *
 * **Goal:** show a ComfyUI Flux fill of the cover (edge-faithful outpaint).
 * Solid cover borders may correctly extend as the same flat color; pictorial
 * edges should continue the scene. Soft local enlarge is only the interim layer
 * until [AlbumArtOutpaintRepository] has a cached outpaint (or when ComfyUI is
 * unset/unavailable).
 *
 * Decorative layers use [matchParentSize] so they never inflate the card.
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
        val isOutpaint = file != null
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .crossfade(false)
                .build(),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            // Soft local stays muted; real outpaint keeps most of its color.
            colorFilter = desaturateFilter(if (isOutpaint) 0.82f else 0.4f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Outpaint is already padded — avoid extra zoom that crops the fill.
                    val scale = if (isOutpaint) 1.02f else 1.25f
                    scaleX = scale
                    scaleY = scale
                    alpha = if (isOutpaint) 0.72f else 0.28f
                }
                // Blur only the interim soft enlarge; keep Flux fill crisp.
                .then(if (isOutpaint) Modifier else softBlurFallback())
                .fadeAtmosphereToLightCard(outpaint = isOutpaint),
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

/**
 * Keep dark text readable without burying the atmosphere.
 * Real outpaint: light veil + stronger wash only on the lower text/controls band.
 * Soft fallback: heavier overall wash (it is only a temporary stand-in).
 */
private fun Modifier.fadeAtmosphereToLightCard(outpaint: Boolean): Modifier = drawWithContent {
    drawContent()
    val card = CardLight
    if (outpaint) {
        drawRect(card.copy(alpha = 0.12f))
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to card.copy(alpha = 0.05f),
                    0.40f to card.copy(alpha = 0.12f),
                    0.62f to card.copy(alpha = 0.38f),
                    0.82f to card.copy(alpha = 0.68f),
                    1.00f to card.copy(alpha = 0.82f),
                ),
            ),
        )
    } else {
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
}
