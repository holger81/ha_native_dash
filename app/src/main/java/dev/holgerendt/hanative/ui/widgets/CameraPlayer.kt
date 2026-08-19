package dev.holgerendt.hanative.ui.widgets

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.holgerendt.hanative.R
import dev.holgerendt.hanative.data.CameraStreamException
import dev.holgerendt.hanative.data.CameraStreams
import dev.holgerendt.hanative.data.StreamCandidate
import dev.holgerendt.hanative.data.StreamKind
import dev.holgerendt.hanative.data.hasLiveCameraSource
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.LoadingSpinner
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private val CameraShape = RoundedCornerShape(28.dp)

@Composable
fun CameraPopup(popup: PopupNode, viewModel: HaViewModel) {
    val cameras = remember(popup) { CameraStreams.camerasForPopup(popup) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cameras.forEachIndexed { index, widget ->
            CameraCard(
                widget = widget,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(if (index == 0) 2f else 1f)
                    .fillMaxWidth(),
                fill = true,
            )
        }
    }
}

@Composable
fun CameraCard(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val boxModifier = modifier
        .then(if (fill) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
        .clip(CameraShape)
        .background(ChipDark)
        .then(
            if (fill) Modifier else Modifier.clickable {
                widget.entity?.let { viewModel.openMoreInfo(it) }
            },
        )
    if (widget.hasLiveCameraSource()) {
        LiveCameraSurface(widget, viewModel, boxModifier)
    } else {
        SnapshotCameraSurface(widget, viewModel, boxModifier)
    }
}

@Composable
private fun SnapshotCameraSurface(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier,
) {
    var bytes by remember(widget.entity) { mutableStateOf<ByteArray?>(null) }
    var error by remember(widget.entity) { mutableStateOf<String?>(null) }
    LaunchedEffect(widget.entity) {
        val entity = widget.entity
        if (entity == null) {
            error = "No camera entity"
            return@LaunchedEffect
        }
        var failures = 0
        while (true) {
            val next = runCatching { viewModel.client.cameraSnapshot(entity) }.getOrNull()
            if (next != null) {
                bytes = next
                error = null
                failures = 0
            } else {
                failures++
                if (bytes == null && failures >= 2) {
                    val state = viewModel.entity(entity)?.state
                    error = when (state) {
                        "unavailable", "unknown" -> "Camera unavailable"
                        else -> "Can't load camera image"
                    }
                }
            }
            delay(1000)
        }
    }
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = widget.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        when {
            bitmap == null && error != null -> CameraErrorText(error!!)
            bitmap == null -> LoadingSpinner(color = ChipOnDark)
        }
        CameraTitle(widget.name)
    }
}

@Composable
private fun LiveCameraSurface(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier,
) {
    val target = remember(widget) { CameraStreams.fromWidget(widget) }
    var candidates by remember(widget) { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    var index by remember(widget) { mutableIntStateOf(0) }
    var error by remember(widget) { mutableStateOf<String?>(null) }
    var poster by remember(widget) { mutableStateOf<ImageBitmap?>(null) }
    val entityState = widget.entity?.let { viewModel.entity(it)?.state }

    LaunchedEffect(widget.entity) {
        widget.entity?.let { entity ->
            val bytes = runCatching { viewModel.client.cameraSnapshot(entity) }.getOrNull()
            poster = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    LaunchedEffect(target) {
        error = null
        index = 0
        candidates = CameraStreams.resolve(viewModel.client, target)
        if (candidates.isEmpty()) {
            error = when (entityState) {
                "unavailable", "unknown" -> "Camera unavailable"
                else -> "No live stream for ${target.entityId ?: target.name ?: "camera"}"
            }
        }
    }

    val current = candidates.getOrNull(index)
    Box(modifier, contentAlignment = Alignment.Center) {
        val still = poster
        if (still != null && current == null && error == null) {
            Image(
                bitmap = still,
                contentDescription = widget.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        when {
            current?.kind == StreamKind.JPEG -> JpegPollPlayer(
                candidate = current,
                poster = still,
                modifier = Modifier.fillMaxSize(),
                onError = { message ->
                    if (index + 1 < candidates.size) index += 1 else error = message
                },
            )
            current?.kind == StreamKind.MJPEG -> MjpegPlayer(
                candidate = current,
                poster = still,
                modifier = Modifier.fillMaxSize(),
                onError = { message ->
                    if (index + 1 < candidates.size) index += 1 else error = message
                },
            )
            current != null -> HlsPlayer(
                candidate = current,
                muted = target.muted,
                modifier = Modifier.fillMaxSize(),
                onError = { message ->
                    if (index + 1 < candidates.size) index += 1 else error = message
                },
            )
            error != null -> CameraErrorText(error!!)
            still == null -> LoadingSpinner(color = ChipOnDark)
        }
        if (error != null && current != null) {
            CameraErrorText(error!!)
        }
        CameraTitle(widget.name)
    }
}

@Composable
private fun HlsPlayer(
    candidate: StreamCandidate,
    muted: Boolean,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(4_000)
            .setReadTimeoutMs(12_000)
            .setUserAgent("ha-native-dash")
    }
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 8_000, 1_000, 1_000)
            .build()
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }
    val handled = remember(candidate.url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val started = remember(candidate.url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(candidate.url) {
        delay(10_000)
        if (!started.get() && handled.compareAndSet(false, true)) {
            onError("Live stream timed out (${candidate.label})")
        }
    }
    DisposableEffect(candidate.url) {
        dataSourceFactory.setDefaultRequestProperties(candidate.headers)
        handled.set(false)
        started.set(false)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started.set(true)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (!handled.compareAndSet(false, true)) return
                val unauthorized = error.message?.contains("401") == true ||
                    error.message?.contains("403") == true
                onError(
                    if (unauthorized) {
                        CameraStreams.httpErrorMessage(401, null)
                    } else {
                        "Live stream failed (${candidate.label})"
                    },
                )
            }
        }
        player.addListener(listener)
        player.volume = if (muted) 0f else 1f
        if (muted) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        player.playWhenReady = true
        val mime = when (candidate.kind) {
            StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
            StreamKind.MP4 -> MimeTypes.VIDEO_MP4
            else -> null
        }
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(candidate.url)
                .setMimeType(mime)
                .build(),
        )
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.clearMediaItems()
        }
    }
    AndroidView(
        factory = { ctx ->
            val view = LayoutInflater.from(ctx).inflate(R.layout.camera_player_view, null) as PlayerView
            view.player = player
            view.useController = false
            view
        },
        update = { view ->
            view.player = player
            view.useController = false
        },
        modifier = modifier,
    )
}

@Composable
private fun JpegPollPlayer(
    candidate: StreamCandidate,
    poster: ImageBitmap?,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val viewRef = remember { AtomicReference<ImageView?>(null) }
    var showing by remember(candidate.url) { mutableStateOf(false) }
    LaunchedEffect(candidate.url) {
        showing = false
        var failures = 0
        while (isActive) {
            try {
                val frame = CameraStreams.readJpeg(candidate.url, candidate.headers)
                if (frame != null) {
                    failures = 0
                    withContext(Dispatchers.Main.immediate) {
                        viewRef.get()?.setImageBitmap(frame)
                        showing = true
                    }
                } else {
                    failures++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                if (!showing && failures >= 2) {
                    onError(error.message ?: "Camera snapshots failed")
                    return@LaunchedEffect
                }
                delay(400)
            }
        }
    }
    StillCameraBox(viewRef, poster, showing, modifier)
}

@Composable
private fun MjpegPlayer(
    candidate: StreamCandidate,
    poster: ImageBitmap?,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val viewRef = remember { AtomicReference<ImageView?>(null) }
    var showing by remember(candidate.url) { mutableStateOf(false) }
    LaunchedEffect(candidate.url) {
        showing = false
        var failures = 0
        while (isActive) {
            try {
                CameraStreams.readMjpeg(candidate.url, candidate.headers) { next ->
                    withContext(Dispatchers.Main.immediate) {
                        viewRef.get()?.setImageBitmap(next)
                        showing = true
                    }
                }
                if (!showing) {
                    failures++
                    if (failures >= 2) {
                        onError("MJPEG stream ended")
                        return@LaunchedEffect
                    }
                }
                delay(400)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stream: CameraStreamException) {
                failures++
                if (!showing && failures >= 2) {
                    onError(stream.message ?: "MJPEG stream failed")
                    return@LaunchedEffect
                }
                delay(400)
            } catch (error: Exception) {
                failures++
                if (!showing && failures >= 2) {
                    onError(error.message ?: "MJPEG stream failed")
                    return@LaunchedEffect
                }
                delay(400)
            }
        }
    }
    StillCameraBox(viewRef, poster, showing, modifier)
}

@Composable
private fun StillCameraBox(
    viewRef: AtomicReference<ImageView?>,
    poster: ImageBitmap?,
    showing: Boolean,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    viewRef.set(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!showing) {
            if (poster != null) {
                Image(
                    bitmap = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                LoadingSpinner(color = ChipOnDark)
            }
        }
    }
}

@Composable
private fun BoxScope.CameraTitle(name: String?) {
    if (name.isNullOrBlank()) return
    Text(
        text = name,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(12.dp),
    )
}

@Composable
private fun CameraErrorText(message: String) {
    Text(
        text = message,
        color = ChipOnDark,
        fontSize = 14.sp,
        modifier = Modifier.padding(16.dp),
    )
}
