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
 *
 * Sessions are refcounted via [markAttached]/[restorePlaceholder]. Wall cameras
 * ([setWarmTargets]) stay warm on a placeholder so a door-triggered popup is
 * instant; other sessions are released [IDLE_RELEASE_MS] after the last viewer
 * leaves.
 */
class LiveCameraHub(
    private val app: Application,
    private val client: HaClient,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var paused = false
    @Volatile private var warmKeys: Set<String> = emptySet()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun view(target: CameraTarget): StateFlow<LiveCameraView> {
        val session = session(target)
        clearIdleRelease(session)
        if (target.hasLiveSource()) startOne(session)
        return session.view
    }

    fun ensureRunning(targets: Collection<CameraTarget>) {
        targets.filter { it.hasLiveSource() }.forEach { target ->
            val session = session(target)
            clearIdleRelease(session)
            startOne(session)
        }
    }

    /** Cameras that must stay warm (placeholder, never idle-released). */
    fun setWarmTargets(targets: Collection<CameraTarget>) {
        warmKeys = targets.mapTo(mutableSetOf()) { key(it) }
        sessions.values.forEach { it.warm = warmKeys.contains(key(it.target)) }
    }

    fun stopTargets(targets: Collection<CameraTarget>) {
        targets.forEach { target ->
            val session = sessions.remove(key(target)) ?: return@forEach
            closeSession(session)
        }
    }

    fun markAttached(target: CameraTarget) {
        val session = session(target)
        clearIdleRelease(session)
        session.attached += 1
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
        armIdleRelease(session)
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
            // A pending idle timer aborted while asleep; restart the clock.
            armIdleRelease(session)
        }
    }

    fun release() {
        paused = true
        val closing = sessions.values.toList()
        sessions.clear()
        closing.forEach { closeSession(it) }
    }

    private fun session(target: CameraTarget): Session {
        val id = key(target)
        val session = sessions.getOrPut(id) { Session(target) }
        session.target = target
        session.warm = warmKeys.contains(id)
        return session
    }

    private fun clearIdleRelease(session: Session) {
        session.idleJob?.cancel()
        session.idleJob = null
    }

    /**
     * Releases [session] after the last viewer has been gone for
     * [IDLE_RELEASE_MS], unless it is warm, re-attached, or the screen sleeps
     * in the meantime (a sleeping panel keeps paused players alive and
     * [resume] re-arms the timer).
     */
    private fun armIdleRelease(session: Session) {
        if (paused || session.warm || session.attached > 0) return
        clearIdleRelease(session)
        session.idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (paused) return@launch
            if (session.warm || session.attached > 0) return@launch
            // Atomic take: if we didn't own the map slot, a newer session or a
            // concurrent stop/release owns this camera now.
            if (sessions.remove(key(session.target)) !== session) return@launch
            closeSession(session)
        }
    }

    private fun closeSession(session: Session) {
        clearIdleRelease(session)
        session.job?.cancel()
        session.jpegJob?.cancel()
        session.job = null
        session.jpegJob = null
        runOnMain {
            session.listener?.let { listener -> session.player?.removeListener(listener) }
            session.player?.release()
            session.placeholder?.release()
            session.player = null
            session.placeholder = null
            session.listener = null
            session.videoReady.value = false
            session.bitmap.value = null
            session.publish()
        }
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
            val player = session.player ?: createPlayer(session).also { session.player = it }
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
                    // Media3 has no error playback state; remember it so the
                    // same-item fast path never skips the re-prepare.
                    session.errored = true
                    session.videoReady.value = false
                    session.publish()
                    if (!ended.isCompleted) ended.complete(false)
                }
            }
            session.listener = listener
            player.addListener(listener)
            player.volume = 0f
            val state = player.playbackState
            // An errored player is terminal: playWhenReady alone won't
            // recover, so it always takes the re-prepare path.
            val sameItem = player.currentMediaItem?.localConfiguration?.uri?.toString() == candidate.url &&
                state != Player.STATE_IDLE &&
                state != Player.STATE_ENDED &&
                !session.errored
            if (sameItem) {
                player.playWhenReady = true
                session.publish()
                firstFrame.complete(Unit)
            } else {
                session.errored = false
                // Per-session factory: this stream's headers can't be clobbered
                // by another session setting its own between here and load.
                session.factory.setDefaultRequestProperties(candidate.headers)
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

    private fun createPlayer(session: Session): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 8_000, 1_000, 1_000)
            .build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(session.factory))
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
        // DefaultHttpDataSource.Factory is mutable; each session owns one so
        // per-stream auth headers are set on this session's factory only.
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(4_000)
            .setReadTimeoutMs(12_000)
            .setUserAgent("ha-native-dash")
        var player: ExoPlayer? = null
        var placeholder: PlaceholderSurface? = null
        var listener: Player.Listener? = null
        var job: Job? = null
        var jpegJob: Job? = null
        var idleJob: Job? = null
        var skipHls: Boolean = false
        var attached: Int = 0
        var warm: Boolean = false
        var errored: Boolean = false
        val bitmap = MutableStateFlow<Bitmap?>(null)
        val videoReady = MutableStateFlow(false)
        val view = MutableStateFlow(LiveCameraView())

        fun publish() {
            view.value = LiveCameraView(player, bitmap.value, videoReady.value)
        }
    }

    companion object {
        /** Idle (no viewer, non-warm) sessions are released after this long. */
        const val IDLE_RELEASE_MS = 60_000L

        fun key(target: CameraTarget): String =
            target.entityId?.takeIf { it.isNotBlank() }
                ?: target.streamName?.takeIf { it.isNotBlank() }
                ?: target.name.orEmpty()
    }
}
