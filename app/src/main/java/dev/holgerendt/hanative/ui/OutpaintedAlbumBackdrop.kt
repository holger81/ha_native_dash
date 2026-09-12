package dev.holgerendt.hanative.ui

import android.os.Build
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
import java.io.File

/**
 * Soft extended album atmosphere behind sharp cover content for the Phase 6 media card.
 *
 * When [extendedBackdrop] is false (idle / camera-priority compact), only [content] is drawn.
 * Otherwise shows a local softened enlarge immediately; if ComfyUI is configured, swaps in the
 * cached/outpainted file when ready without blocking playback.
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
            SoftLocalAlbumAtmosphere(coverPath = coverPath, viewModel = viewModel)
            OutpaintSwapLayer(coverPath = coverPath, viewModel = viewModel)
        }
        content()
    }
}

@Composable
private fun SoftLocalAlbumAtmosphere(
    coverPath: String,
    viewModel: HaViewModel,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    var url by remember(coverPath, viewModel.client.currentBaseUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(coverPath, viewModel.client.currentBaseUrl) {
        url = runCatching { viewModel.client.resolveMusicCoverUrl(coverPath, size = 512) }.getOrNull()
            ?: resolveHaImageUrl(coverPath, viewModel.client.currentBaseUrl)
    }
    val resolved = url ?: return

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(resolved)
            .crossfade(true)
            .build(),
        contentDescription = null,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        colorFilter = desaturateFilter(0.45f),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.35f
                scaleY = 1.35f
                alpha = 0.55f
            }
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(28.dp)
                } else {
                    Modifier
                },
            )
            .fadeEdgesToNeutral(),
    )
}

@Composable
private fun OutpaintSwapLayer(
    coverPath: String,
    viewModel: HaViewModel,
) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val ui by viewModel.ui.collectAsState()
    var outpaintFile by remember(coverPath) { mutableStateOf<File?>(null) }

    LaunchedEffect(coverPath, ui.comfyUiUrl) {
        outpaintFile = null
        if (ui.comfyUiUrl.isBlank()) return@LaunchedEffect
        outpaintFile = runCatching {
            viewModel.albumArtOutpaint.getOutpaintedFile(coverPath)
        }.getOrNull()
    }

    val file = outpaintFile ?: return
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(file)
            .crossfade(400)
            .build(),
        contentDescription = null,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        colorFilter = desaturateFilter(0.55f),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.7f }
            .fadeEdgesToNeutral(),
    )
}

private fun desaturateFilter(saturation: Float): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(saturation.coerceIn(0f, 1f)) }
    return ColorFilter.colorMatrix(matrix)
}

private fun Modifier.fadeEdgesToNeutral(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0xCC1A1A1A), Color(0xF21A1A1A)),
            center = Offset(size.width * 0.35f, size.height * 0.4f),
            radius = size.maxDimension * 0.85f,
        ),
    )
}
