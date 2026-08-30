package dev.holgerendt.hanative.data

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.PlaceholderSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class LiveCameraView(
    val player: ExoPlayer? = null,
    val bitmap: Bitmap? = null,
    val videoReady: Boolean = false,
)

/**
 * Keeps wall-camera streams running while the home screen is up so the camera
 * popup can attach instead of starting a cold HLS session.
 */
class LiveCameraHub(
    private val app: Application,
    private val client: HaClient,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var paused = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(4_000)
        .setReadTimeoutMs(12_000)
        .setUserAgent("ha-native-dash")

    fun view(target: CameraTarget): StateFlow<LiveCameraView> {
        val session = session(target)
        if (target.hasLiveSource()) startOne(session)
        return session.view
    }

    fun ensureRunning(targets: Collection<CameraTarget>) {
        targets.filter { it.hasLiveSource() }.forEach { startOne(session(it)) }
    }

    fun stopTargets(targets: Collection<CameraTarget>) {
        targets.forEach { target ->
            val session = sessions.remove(key(target)) ?: return@forEach
            session.job?.cancel()
            session.jpegJob?.cancel()
            runOnMain {
                session.listener?.let { listener -> session.player?.removeListener(listener) }
                session.player?.release()
                session.placeholder?.release()
                session.player = null
                session.placeholder = null
            }
        }
    }

    fun markAttached(target: CameraTarget) {
        session(target).attached += 1
    }

    fun restorePlaceholder(target: CameraTarget) {
        val session = sessions[key(target)] ?: return
        session.attached = (session.attached - 1).coerceAtLeast(0)
        if (session.attached > 0) return
        runOnMain {
            val player = session.player ?: return@runOnMain
            attachPlaceholder(session, player)
            if (!paused) player.playWhenReady = true
        }
    }

    fun pause() {
        paused = true
        sessions.values.forEach { session ->
            session.job?.cancel()
            session.jpegJob?.cancel()
            session.job = null
            session.jpegJob = null
            runOnMain { session.player?.playWhenReady = false }
        }
    }

    fun resume() {
        paused = false
        sessions.values.forEach { session ->
            session.skipHls = false
            startOne(session)
        }
    }

    fun release() {
        paused = true
        val closing = sessions.values.toList()
        sessions.clear()
        closing.forEach {
            it.job?.cancel()
            it.jpegJob?.cancel()
        }
        runOnMain {
            closing.forEach { session ->
                session.listener?.let { listener -> session.player?.removeListener(listener) }
                session.player?.release()
                session.placeholder?.release()
                session.player = null
                session.placeholder = null
            }
        }
    }

    private fun session(target: CameraTarget): Session {
        val id = key(target)
        val session = sessions.getOrPut(id) { Session(target) }
        session.target = target
        return session
    }

    private fun startOne(session: Session) {
        if (paused || !session.target.hasLiveSource()) return
        if (session.job?.isActive == true) return
        session.job = scope.launch { runSession(session) }
    }

    private suspend fun runSession(session: Session) {
        while (currentCoroutineContext().isActive && !paused) {
            val candidates = runCatching {
                CameraStreams.resolve(client, session.target)
            }.getOrDefault(emptyList())
            if (candidates.isEmpty()) {
                delay(5_000)
                continue
            }
            val jpeg = candidates.firstOrNull { it.kind == StreamKind.JPEG }
            session.jpegJob?.cancel()
            if (jpeg != null) {
                session.jpegJob = scope.launch { pollJpeg(session, jpeg) }
            }
            val hls = candidates.firstOrNull {
                it.kind == StreamKind.HLS || it.kind == StreamKind.MP4
            }
            val mjpeg = candidates.firstOrNull { it.kind == StreamKind.MJPEG }
            when {
                hls != null && !session.skipHls -> {
                    val ok = playHls(session, hls)
                    if (!ok) session.skipHls = true
                }
                mjpeg != null -> playMjpeg(session, mjpeg)
                else -> session.jpegJob?.join() ?: delay(5_000)
            }
            if (currentCoroutineContext().isActive && !paused) delay(1_500)
        }
    }

    private suspend fun playHls(session: Session, candidate: StreamCandidate): Boolean {
        val firstFrame = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            val player = session.player ?: createPlayer().also { session.player = it }
            if (session.attached == 0) attachPlaceholder(session, player)
            session.listener?.let { player.removeListener(it) }
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    session.videoReady.value = true
                    session.publish()
                    if (!firstFrame.isCompleted) firstFrame.complete(Unit)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        player.seekToDefaultPosition()
                        player.prepare()
                        return
                    }
                    session.videoReady.value = false
                    session.publish()
                    if (!ended.isCompleted) ended.complete(false)
                }
            }
            session.listener = listener
            player.addListener(listener)
            player.volume = 0f
            val state = player.playbackState
            val sameItem = player.currentMediaItem?.uri?.toString() == candidate.url &&
                state != Player.STATE_IDLE &&
                state != Player.STATE_ENDED &&
                state != Player.STATE_ERROR
            if (sameItem) {
                player.playWhenReady = true
                session.publish()
                firstFrame.complete(Unit)
            } else {
                dataSourceFactory.setDefaultRequestProperties(candidate.headers)
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
                player.playWhenReady = true
                session.publish()
            }
        }
        val gotFrame = withTimeoutOrNull(10_000) { firstFrame.await() } != null
        if (!gotFrame) {
            withContext(Dispatchers.Main.immediate) {
                session.player?.stop()
                session.player?.clearMediaItems()
                session.videoReady.value = false
                session.publish()
            }
            return false
        }
        session.jpegJob?.cancel()
        ended.await()
        return true
    }

    private suspend fun pollJpeg(session: Session, candidate: StreamCandidate) {
        while (currentCoroutineContext().isActive && !paused) {
            val frame = runCatching {
                CameraStreams.readJpeg(candidate.url, candidate.headers)
            }.getOrNull()
            if (frame != null) {
                session.bitmap.value = frame
                session.publish()
                delay(50)
            } else {
                delay(400)
            }
        }
    }

    private suspend fun playMjpeg(session: Session, candidate: StreamCandidate) {
        runCatching {
            CameraStreams.readMjpeg(candidate.url, candidate.headers) { frame ->
                session.bitmap.value = frame
                session.publish()
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 8_000, 1_000, 1_000)
            .build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private fun attachPlaceholder(session: Session, player: ExoPlayer) {
        val surface = session.placeholder ?: runCatching {
            PlaceholderSurface.newInstance(app, false)
        }.getOrNull()?.also { session.placeholder = it }
        if (surface != null) player.setVideoSurface(surface)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private class Session(var target: CameraTarget) {
        var player: ExoPlayer? = null
        var placeholder: PlaceholderSurface? = null
        var listener: Player.Listener? = null
        var job: Job? = null
        var jpegJob: Job? = null
        var skipHls: Boolean = false
        var attached: Int = 0
        val bitmap = MutableStateFlow<Bitmap?>(null)
        val videoReady = MutableStateFlow(false)
        val view = MutableStateFlow(LiveCameraView())

        fun publish() {
            view.value = LiveCameraView(player, bitmap.value, videoReady.value)
        }
    }

    companion object {
        fun key(target: CameraTarget): String =
            target.entityId?.takeIf { it.isNotBlank() }
                ?: target.streamName?.takeIf { it.isNotBlank() }
                ?: target.name.orEmpty()
    }
}
