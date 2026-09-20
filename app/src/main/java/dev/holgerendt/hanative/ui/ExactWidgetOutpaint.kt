package dev.holgerendt.hanative.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.holgerendt.hanative.data.WidgetOutpaintGeometry
import dev.holgerendt.hanative.ui.theme.CardLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class ExactWidgetArt {
    var rootCoordinates: LayoutCoordinates? = null
    var coverCoordinates: LayoutCoordinates? = null
    var geometry by mutableStateOf<WidgetOutpaintGeometry?>(null)
    var cover by mutableStateOf<Bitmap?>(null)

    fun measure() {
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return
        val hero = coverCoordinates?.takeIf { it.isAttached } ?: return
        val origin = root.localPositionOf(hero, androidx.compose.ui.geometry.Offset.Zero)
        geometry = runCatching {
            WidgetOutpaintGeometry(root.size.width, root.size.height,
                origin.x.roundToInt(), origin.y.roundToInt(), hero.size.width, hero.size.height)
        }.getOrNull()
    }
}

internal val LocalExactWidgetArt = staticCompositionLocalOf<ExactWidgetArt?> { null }

/** Full music card only: decoded pixels are drawn at the origin with no destination sizing. */
@Composable
internal fun ExactWidgetOutpaint(
    coverPath: String?, viewModel: HaViewModel, modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val art = remember(coverPath) { ExactWidgetArt() }
    val geometry = art.geometry
    val ui by viewModel.ui.collectAsState()
    var background by remember(coverPath, geometry) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverPath, geometry, ui.mediagenUrl) {
        val measured = geometry ?: return@LaunchedEffect
        val ref = coverPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        art.cover = null
        val repository = viewModel.albumArtOutpaint.forWidget(measured)
        viewModel.scheduleAlbumArtOutpaintPrefetch(currentCoverOverride = ref)
        var previousRevision: Long? = null
        while (true) {
            val frame = withContext(Dispatchers.IO) {
                val source = repository.preparedCover(ref) ?: return@withContext null
                val cover = if (art.cover == null) BitmapFactory.decodeByteArray(source, 0, source.size) else null
                val file = repository.ensureLocalPad(ref) ?: return@withContext Triple(cover, null, null)
                val flux = repository.peekFluxComplete(ref)
                val revision = outpaintFileRevision(file, flux)
                val bitmap = if (revision != previousRevision) BitmapFactory.decodeFile(file.absolutePath)
                    ?.takeIf { measured.acceptsSize(it.width, it.height, null) } else null
                Triple(cover, bitmap, revision)
            }
            if (frame != null) {
                frame.first?.let { art.cover = it }
                frame.second?.let { background = it; previousRevision = frame.third }
            }
            delay(2_000)
        }
    }
    CompositionLocalProvider(LocalExactWidgetArt provides art) {
        Box(modifier.onGloballyPositioned { art.rootCoordinates = it; art.measure() }
            .drawWithContent {
                val bitmap = background
                // A layout change drops the old canvas instead of stretching it during a frame.
                if (bitmap != null && bitmap.width == size.width.roundToInt() && bitmap.height == size.height.roundToInt()) {
                    drawImage(bitmap.asImageBitmap())
                    // Readable band under the hero: transparent at cover bottom, then
                    // opaque CardLight behind title / room / progress / controls so
                    // TextDark / TextMuted stay legible on dark (or light) outpaint.
                    val fadeStart = geometry?.let { (it.coverY + it.coverHeight).toFloat() } ?: size.height
                    val fadeEnd = maxOf(size.height, fadeStart + 1f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.10f to CardLight.copy(alpha = 0.78f),
                                0.28f to CardLight.copy(alpha = 0.94f),
                                0.55f to CardLight,
                                1.00f to CardLight,
                            ),
                            startY = fadeStart,
                            endY = fadeEnd,
                        ),
                    )
                }
                drawContent()
            }) { content() }
    }
}
