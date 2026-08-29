package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.TextMuted

/** Cached cover art via Coil (memory + disk). Spinner while loading; note icon on miss/fail. */
@Composable
fun MusicCover(
    path: String?,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    spinnerSize: Dp = 22.dp,
    fallbackIconSize: Dp = 28.dp,
) {
    val overlay = LocalOverlay.current
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val url = resolveHaImageUrl(path, viewModel.client.currentBaseUrl)

    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(overlay.well), contentAlignment = Alignment.Center) {
            MdiIcon("mdi:music-note", tint = overlay.muted, size = fallbackIconSize)
        }
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        imageLoader = loader,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty,
            -> {
                Box(Modifier.fillMaxSize().background(overlay.well), contentAlignment = Alignment.Center) {
                    LoadingSpinner(color = TextMuted, indicatorSize = spinnerSize)
                }
            }
            is AsyncImagePainter.State.Error -> {
                Box(Modifier.fillMaxSize().background(overlay.well), contentAlignment = Alignment.Center) {
                    MdiIcon("mdi:music-note", tint = overlay.muted, size = fallbackIconSize)
                }
            }
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
        }
    }
}
