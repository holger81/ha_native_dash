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
import java.util.concurrent.atomic.AtomicInteger

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

    /** Pure accessor: safe to call from composition. Starting a stream is [start]. */
    fun view(target: CameraTarget): StateFlow<LiveCameraView> = session(target).view

    fun start(target: CameraTarget) {
        val session = session(target)
        clearIdleRelease(session)
        if (target.hasLiveSource()) startOne(session)
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
        session.attached.incrementAndGet()
        if (!target.hasLiveSource()) return
        // A viewer is waiting now: retry HLS immediately instead of waiting out
        // the backoff, and reload the decode loop if it is wedged (no player or a
        // dead one), so the popup never sits on a black rectangle.
        session.unblockHls()
        if (session.job?.isActive != true) {
            startOne(session)
            return
        }
        if (session.player == null || session.errored || !session.videoReady.value) {
            reloadSession(session)
            return
        }
        runOnMain {
            val player = session.player ?: return@runOnMain
            val hasItem = player.currentMediaItem != null
            val state = player.playbackState
            if (hasItem && (session.errored || state == Player.STATE_IDLE || state == Player.STATE_ENDED)) {
                session.errored = false
                runCatching {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        }
    }

    fun restorePlaceholder(target: CameraTarget) {
        val session = sessions[key(target)] ?: return
        if (session.attached.updateAndGet { (it - 1).coerceAtLeast(0) } > 0) return
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
            // Wall-panel cameras stay decoding through sleep so door-triggered
            // popups attach an already-live player (no cold HLS start).
            if (session.warm) return@forEach
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
            if (session.warm) {
                // Already kept alive through sleep; nudge playback and heal if dead.
                runOnMain { session.player?.playWhenReady = true }
                if (session.job?.isActive != true) {
                    session.unblockHls()
                    startOne(session)
                }
                return@forEach
            }
            session.unblockHls()
            startOne(session)
            // A pending idle timer aborted while asleep; restart the clock.
            armIdleRelease(session)
        }
    }

    /**
     * Restart any warm (or viewed) session whose decode loop has died **or**
     * whose stream silently stalled: a dead camera behind go2rtc can leave the
     * ExoPlayer neither errored nor advancing, and a JPEG-only fallback never
     * returns to HLS on its own. Sampling compares decode progress between
     * ticks; no progress for [STALL_RELOAD_MS] triggers a full stream reload.
     */
    fun keepWarmAlive() {
        val now = System.currentTimeMillis()
        sessions.values.forEach { session ->
            if (!session.target.hasLiveSource()) return@forEach
            if (!session.warm && session.attached.get() == 0) return@forEach
            if (session.job?.isActive == true) {
                runOnMain { sampleStall(session, now) }
                return@forEach
            }
            session.unblockHls()
            startOne(session)
        }
    }

    /** Main-thread half of [keepWarmAlive]: decide whether this session stalled. */
    private fun sampleStall(session: Session, now: Long) {
        val player = session.player
        if (player != null && !player.playWhenReady) player.playWhenReady = true
        val position = player?.currentPosition ?: -1L
        val videoAdvancing = player != null && player.isPlaying && position > session.lastPosSampleMs
        val bitmap = session.bitmap.value
        val bitmapAdvancing = bitmap != null && bitmap !== session.lastBitmapSample
        session.lastPosSampleMs = position
        session.lastBitmapSample = bitmap
        if (videoAdvancing || bitmapAdvancing) {
            session.stalledSinceMs = 0L
            return
        }
        if (session.stalledSinceMs == 0L) {
            session.stalledSinceMs = now
            return
        }
        if (now - session.stalledSinceMs >= STALL_RELOAD_MS) {
            session.stalledSinceMs = 0L
            reloadSession(session)
        }
    }

    /** Tear the decode loop down and start it fresh; HLS is un-blocked for the retry. */
    private fun reloadSession(session: Session) {
        session.unblockHls()
        session.jpegJob?.cancel()
        session.jpegJob = null
        session.job?.cancel()
        session.job = null
        startOne(session)
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
        if (paused || session.warm || session.attached.get() > 0) return
        clearIdleRelease(session)
        session.idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (paused) return@launch
            if (session.warm || session.attached.get() > 0) return@launch
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
        if (!sessionShouldRun(session)) return
        if (session.job?.isActive == true) return
        session.job = scope.launch { runSession(session) }
    }

    /** Warm wall cameras keep running while the panel sleeps; others pause. */
    private fun sessionShouldRun(session: Session): Boolean =
        session.target.hasLiveSource() && (session.warm || !paused)

    private suspend fun runSession(session: Session) {
        while (currentCoroutineContext().isActive && sessionShouldRun(session)) {
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
                hls != null && !session.hlsBlocked() -> {
                    val ok = playHls(session, hls)
                    if (!ok) session.blockHls()
                }
                mjpeg != null -> playMjpeg(session, mjpeg)
                // Bounded wait: a wedged JPEG poll (camera down) must not strand
                // this loop forever, or HLS can never be retried after recovery.
                else -> withTimeoutOrNull(JPEG_JOIN_RETRY_MS) { session.jpegJob?.join() } ?: delay(5_000)
            }
            if (currentCoroutineContext().isActive && sessionShouldRun(session)) delay(1_500)
        }
    }

    private suspend fun playHls(session: Session, candidate: StreamCandidate): Boolean {
        val firstFrame = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            val player = session.player ?: createPlayer(session).also { session.player = it }
            if (session.attached.get() == 0) attachPlaceholder(session, player)
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
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && !ended.isCompleted) ended.complete(true)
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
        // Propagate the outcome: false means the player errored, so runSession
        // benches HLS (time-boxed) and falls back to MJPEG/JPEG instead of
        // re-trying the same broken HLS URL in a tight loop.
        return ended.await()
    }

    private suspend fun pollJpeg(session: Session, candidate: StreamCandidate) {
        while (currentCoroutineContext().isActive && sessionShouldRun(session)) {
            val frame = runCatching {
                CameraStreams.readJpeg(candidate.url, candidate.headers)
            }.getOrNull()
            if (frame != null) {
                session.bitmap.value = frame
                session.publish()
                delay(300)
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

        /**
         * HLS failures only block HLS until this deadline. A camera drop that
         * errors the player must not permanently strand the warm session on
         * JPEG stills — once the deadline passes, [runSession] retries HLS.
         */
        @Volatile var hlsBlockedUntilMs: Long = 0L
        fun hlsBlocked(): Boolean =
            System.currentTimeMillis() < hlsBlockedUntilMs

        fun blockHls() {
            hlsBlockedUntilMs = System.currentTimeMillis() + HLS_RETRY_BACKOFF_MS
        }

        fun unblockHls() {
            hlsBlockedUntilMs = 0L
        }

        // Stall sampling for [keepWarmAlive]: last observed decode progress.
        @Volatile var lastPosSampleMs: Long = -1L
        @Volatile var lastBitmapSample: Bitmap? = null
        @Volatile var stalledSinceMs: Long = 0L

        val attached = AtomicInteger(0)
        @Volatile var warm: Boolean = false
        @Volatile var errored: Boolean = false
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

        /** How long an HLS failure keeps HLS on the bench before it is retried. */
        const val HLS_RETRY_BACKOFF_MS = 30_000L

        /** Max time the JPEG-only fallback may strand the resolve loop. */
        const val JPEG_JOIN_RETRY_MS = 30_000L

        /** No decode progress for this long (and no fresh JPEG) → reload stream. */
        const val STALL_RELOAD_MS = 20_000L

        fun key(target: CameraTarget): String =
            target.entityId?.takeIf { it.isNotBlank() }
                ?: target.streamName?.takeIf { it.isNotBlank() }
                ?: target.name.orEmpty()
    }
}
