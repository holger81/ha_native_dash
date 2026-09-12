package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.MassMediaItem
import dev.holgerendt.hanative.data.mediaArtist
import dev.holgerendt.hanative.data.mediaDurationSec
import dev.holgerendt.hanative.data.mediaPositionSec
import dev.holgerendt.hanative.data.mediaPositionUpdatedAtMs
import dev.holgerendt.hanative.data.mediaTitle
import dev.holgerendt.hanative.data.volumeLevel
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val TV_ENTITY = "media_player.living_room"
private const val APPLE_TV_ENTITY = "media_player.living_room_appletv"

internal enum class HomeMediaKind { Music, Tv, Idle }

internal data class HomeMediaSnapshot(
    val kind: HomeMediaKind,
    val playing: Boolean,
    val paused: Boolean,
    val title: String,
    val subtitle: String,
    val room: String,
    val art: String?,
    val entityId: String?,
    val durationSec: Double?,
    val positionSec: Double?,
    val positionUpdatedAtMs: Long?,
    val volume: Float?,
    val appName: String? = null,
    val remainingLabel: String? = null,
)

/**
 * Phase 6 shared media area for Greatroom Wall.
 * [compact] collapses to a strip under backyard cameras; idle is omitted when compact.
 */
@Composable
fun HomeMediaArea(
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    DisposableEffect(viewModel) {
        viewModel.startHomeMediaWatch()
        onDispose { viewModel.stopHomeMediaWatch() }
    }
    val wall by viewModel.musicWall.collectAsState()
    val musicId = wall.selectedEntityId
    val states by viewModel.entitiesFlow(listOf(musicId, TV_ENTITY, APPLE_TV_ENTITY)).collectAsState()
    val music = musicId?.let { states[it] }
    val apple = states[APPLE_TV_ENTITY]
    val selectedPlayer = wall.players.firstOrNull { it.entityId == musicId }

    val snapshot = remember(wall, music, apple, selectedPlayer) {
        resolveHomeMedia(
            musicState = music?.state,
            musicTitle = wall.queue?.current?.name ?: music?.mediaTitle(),
            musicArtist = wall.queue?.current?.artists ?: music?.mediaArtist(),
            musicArt = music?.entityPicture ?: wall.queue?.current?.imageUrl,
            musicRoom = selectedPlayer?.name ?: music?.friendlyName,
            musicEntityId = musicId,
            musicDuration = wall.queue?.current?.durationSec?.toDouble() ?: music?.mediaDurationSec(),
            musicPosition = music?.mediaPositionSec(),
            musicPositionUpdatedAtMs = music?.mediaPositionUpdatedAtMs(),
            musicVolume = music?.volumeLevel(),
            appleState = apple?.state,
            appleTitle = apple?.mediaTitle() ?: apple?.attrString("media_title"),
            appleApp = apple?.attrString("app_name"),
            appleArt = apple?.entityPicture,
            appleDuration = apple?.mediaDurationSec(),
            applePosition = apple?.mediaPositionSec(),
            applePositionUpdatedAtMs = apple?.mediaPositionUpdatedAtMs(),
            appleVolume = apple?.volumeLevel(),
        )
    }

    if (compact && snapshot.kind == HomeMediaKind.Idle) return

    when {
        compact -> CompactMediaStrip(snapshot, viewModel, modifier)
        snapshot.kind == HomeMediaKind.Idle -> IdleListenCard(
            viewModel = viewModel,
            favorites = wall.discovery.recentlyPlayed.take(3),
            canResume = music?.state == "paused" || apple?.state == "paused",
            resumeLabel = when {
                music?.state == "paused" -> music.mediaTitle() ?: "Resume music"
                apple?.state == "paused" -> apple.mediaTitle() ?: "Resume TV"
                else -> null
            },
            onResume = {
                when {
                    music?.state == "paused" -> viewModel.mediaPlayPause()
                    apple?.state == "paused" -> viewModel.mediaPlayerCommand(APPLE_TV_ENTITY, "media_play_pause")
                }
            },
            modifier = modifier,
        )
        snapshot.kind == HomeMediaKind.Tv -> FullTvCard(snapshot, viewModel, modifier)
        else -> FullMusicCard(snapshot, viewModel, modifier)
    }
}

internal fun resolveHomeMedia(
    musicState: String?,
    musicTitle: String?,
    musicArtist: String?,
    musicArt: String?,
    musicRoom: String?,
    musicEntityId: String?,
    musicDuration: Double?,
    musicPosition: Double?,
    musicPositionUpdatedAtMs: Long?,
    musicVolume: Float?,
    appleState: String?,
    appleTitle: String?,
    appleApp: String?,
    appleArt: String?,
    appleDuration: Double?,
    applePosition: Double?,
    applePositionUpdatedAtMs: Long?,
    appleVolume: Float?,
): HomeMediaSnapshot {
    val musicActive = musicState == "playing" || (musicState == "paused" && !musicTitle.isNullOrBlank())
    val tvActive = appleState == "playing" || (appleState == "paused" && !appleTitle.isNullOrBlank())
    // Prefer actively playing source; if both paused/playing, music wins (wall dock primary).
    val preferMusic = when {
        musicState == "playing" -> true
        appleState == "playing" && musicState != "playing" -> false
        musicActive -> true
        tvActive -> false
        else -> false
    }
    return when {
        preferMusic && musicActive -> HomeMediaSnapshot(
            kind = HomeMediaKind.Music,
            playing = musicState == "playing",
            paused = musicState == "paused",
            title = musicTitle?.takeIf { it.isNotBlank() } ?: "Music",
            subtitle = musicArtist.orEmpty(),
            room = musicRoom?.takeIf { it.isNotBlank() } ?: "Speakers",
            art = musicArt,
            entityId = musicEntityId,
            durationSec = musicDuration,
            positionSec = musicPosition,
            positionUpdatedAtMs = musicPositionUpdatedAtMs,
            volume = musicVolume,
        )
        tvActive -> {
            val remaining = remainingTimeLabel(appleDuration, applePosition, applePositionUpdatedAtMs, appleState == "playing")
            HomeMediaSnapshot(
                kind = HomeMediaKind.Tv,
                playing = appleState == "playing",
                paused = appleState == "paused",
                title = appleTitle?.takeIf { it.isNotBlank() } ?: "Apple TV",
                subtitle = listOfNotNull(appleApp?.takeIf { it.isNotBlank() }, remaining).joinToString(" · "),
                room = "Living Room",
                art = appleArt,
                entityId = APPLE_TV_ENTITY,
                durationSec = appleDuration,
                positionSec = applePosition,
                positionUpdatedAtMs = applePositionUpdatedAtMs,
                volume = appleVolume,
                appName = appleApp,
                remainingLabel = remaining,
            )
        }
        else -> HomeMediaSnapshot(
            kind = HomeMediaKind.Idle,
            playing = false,
            paused = false,
            title = "Listen",
            subtitle = "",
            room = "",
            art = null,
            entityId = musicEntityId,
            durationSec = null,
            positionSec = null,
            positionUpdatedAtMs = null,
            volume = null,
        )
    }
}

private fun remainingTimeLabel(
    duration: Double?,
    position: Double?,
    updatedAtMs: Long?,
    playing: Boolean,
): String? {
    if (duration == null || duration <= 0 || position == null) return null
    val live = if (playing && updatedAtMs != null) {
        position + (System.currentTimeMillis() - updatedAtMs) / 1000.0
    } else {
        position
    }.coerceIn(0.0, duration)
    val left = (duration - live).coerceAtLeast(0.0).roundToInt()
    if (left <= 0) return null
    val m = left / 60
    val s = left % 60
    return "%d:%02d left".format(m, s)
}

@Composable
private fun FullMusicCard(
    snapshot: HomeMediaSnapshot,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val overlay = LocalOverlay.current
    var volume by remember(snapshot.volume) { mutableFloatStateOf(snapshot.volume ?: 0.4f) }
    LaunchedEffect(snapshot.volume) {
        snapshot.volume?.let { volume = it }
    }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(snapshot.playing) {
        while (snapshot.playing) {
            delay(1000)
            tick++
        }
    }
    val position = livePosition(snapshot, tick)

    OutpaintedAlbumBackdrop(
        coverPath = snapshot.art,
        viewModel = viewModel,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardLight),
        extendedBackdrop = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openPopup("#music") }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicCover(
                    path = snapshot.art,
                    viewModel = viewModel,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    spinnerSize = 22.dp,
                    fallbackIconSize = 40.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (snapshot.paused) {
                        Text("Paused", color = ActiveYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        snapshot.title,
                        color = TextDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snapshot.subtitle.isNotBlank()) {
                        Text(
                            snapshot.subtitle,
                            color = TextMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(snapshot.room, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HomeProgressBar(position = position, duration = snapshot.durationSec)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaIconButton("mdi:skip-previous", onClick = viewModel::mediaPrevious)
                MediaIconButton(
                    if (snapshot.playing) "mdi:pause" else "mdi:play",
                    filled = true,
                    onClick = viewModel::mediaPlayPause,
                )
                MediaIconButton("mdi:skip-next", onClick = viewModel::mediaNext)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MdiIcon("mdi:volume-medium", tint = TextMuted, size = 20.dp)
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        viewModel.setMusicVolume(it)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = ActiveYellow,
                        activeTrackColor = ActiveYellow,
                        inactiveTrackColor = overlay.well,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FullTvCard(
    snapshot: HomeMediaSnapshot,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    OutpaintedAlbumBackdrop(
        coverPath = snapshot.art,
        viewModel = viewModel,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardLight),
        extendedBackdrop = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openMoreInfo(TV_ENTITY) }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!snapshot.art.isNullOrBlank()) {
                    MusicCover(
                        path = snapshot.art,
                        viewModel = viewModel,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x22000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MdiIcon("mdi:television-classic", tint = TextMuted, size = 40.dp)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (snapshot.paused) {
                        Text("Paused", color = ActiveYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        snapshot.title,
                        color = TextDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snapshot.subtitle.isNotBlank()) {
                        Text(snapshot.subtitle, color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(snapshot.room, color = TextMuted, fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaIconButton(
                    "mdi:rewind",
                    onClick = { viewModel.mediaPlayerCommand(APPLE_TV_ENTITY, "media_previous_track") },
                )
                MediaIconButton(
                    if (snapshot.playing) "mdi:pause" else "mdi:play",
                    filled = true,
                    onClick = { viewModel.mediaPlayerCommand(APPLE_TV_ENTITY, "media_play_pause") },
                )
                MediaIconButton(
                    "mdi:fast-forward",
                    onClick = { viewModel.mediaPlayerCommand(APPLE_TV_ENTITY, "media_next_track") },
                )
            }
        }
    }
}

@Composable
private fun CompactMediaStrip(
    snapshot: HomeMediaSnapshot,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val onOpen = {
        when (snapshot.kind) {
            HomeMediaKind.Music -> viewModel.openPopup("#music")
            HomeMediaKind.Tv -> viewModel.openMoreInfo(TV_ENTITY)
            HomeMediaKind.Idle -> Unit
        }
    }
    val onToggle = {
        when (snapshot.kind) {
            HomeMediaKind.Music -> viewModel.mediaPlayPause()
            HomeMediaKind.Tv -> viewModel.mediaPlayerCommand(APPLE_TV_ENTITY, "media_play_pause")
            HomeMediaKind.Idle -> Unit
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardLight)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MusicCover(
            path = snapshot.art,
            viewModel = viewModel,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
            spinnerSize = 16.dp,
            fallbackIconSize = 22.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                snapshot.title,
                color = TextDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    snapshot.room.takeIf { it.isNotBlank() },
                    if (snapshot.paused) "Paused" else null,
                ).joinToString(" · "),
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MediaIconButton(
            if (snapshot.playing) "mdi:pause" else "mdi:play",
            size = 40.dp,
            iconSize = 22.dp,
            filled = true,
            onClick = onToggle,
        )
    }
}

@Composable
private fun IdleListenCard(
    viewModel: HaViewModel,
    favorites: List<MassMediaItem>,
    canResume: Boolean,
    resumeLabel: String?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardLight)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Listen", color = TextDark, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Browse",
                color = ActiveYellow,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewModel.openPopup("#music") }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (canResume && !resumeLabel.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x14000000))
                    .clickable(onClick = onResume)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MdiIcon("mdi:play-circle", tint = ActiveYellow, size = 28.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Resume", color = TextMuted, fontSize = 12.sp)
                    Text(resumeLabel, color = TextDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (favorites.isNotEmpty()) {
            Text("Favorites", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            favorites.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.playMusicDiscoveryItem(item) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MusicCover(
                        path = item.imageUrl,
                        viewModel = viewModel,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        spinnerSize = 14.dp,
                        fallbackIconSize = 18.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, color = TextDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = item.subtitle.orEmpty()
                        if (sub.isNotBlank()) {
                            Text(sub, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeProgressBar(position: Double?, duration: Double?) {
    val progress = if (duration != null && duration > 0 && position != null) {
        (position / duration).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x22000000)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(ActiveYellow),
            )
        }
        if (duration != null && position != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(position), color = TextMuted, fontSize = 11.sp)
                Text(formatClock(duration), color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MediaIconButton(
    icon: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) ActiveYellow else Color(0x14000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(icon, tint = if (filled) Color.Black else TextDark, size = iconSize)
    }
}

private fun livePosition(snapshot: HomeMediaSnapshot, tick: Int): Double? {
    @Suppress("UNUSED_VARIABLE")
    val unused = tick
    val base = snapshot.positionSec ?: return null
    if (!snapshot.playing) return base
    val updated = snapshot.positionUpdatedAtMs ?: return base
    return base + (System.currentTimeMillis() - updated) / 1000.0
}

private fun formatClock(sec: Double): String {
    val total = sec.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
