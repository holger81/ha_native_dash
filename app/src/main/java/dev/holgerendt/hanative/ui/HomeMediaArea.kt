package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.MassMediaItem
import dev.holgerendt.hanative.data.MusicAssistantPlayer
import dev.holgerendt.hanative.data.MusicAssistantQueue
import dev.holgerendt.hanative.data.isMusicAssistantPlayer
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

internal const val TV_ENTITY = "media_player.living_room"
internal const val APPLE_TV_ENTITY = "media_player.living_room_appletv"

internal enum class HomeMediaKind { Music, Tv, Idle }

internal data class HomeMediaSnapshot(
    val kind: HomeMediaKind,
    val playing: Boolean,
    val paused: Boolean,
    val title: String,
    val subtitle: String,
    val room: String,
    val art: String?,
    /** Entity that transport/volume must target (same as displayed session). */
    val entityId: String?,
    val durationSec: Double?,
    val positionSec: Double?,
    val positionUpdatedAtMs: Long?,
    val volume: Float?,
    val appName: String? = null,
    val seriesName: String? = null,
    val seasonEpisode: String? = null,
    val supportsPrevious: Boolean = true,
    val supportsNext: Boolean = true,
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
    val playerIds = remember(wall.players) {
        wall.players.map { it.entityId } + listOf(TV_ENTITY, APPLE_TV_ENTITY)
    }
    val states by viewModel.entitiesFlow(playerIds).collectAsState()

    val snapshot = remember(wall, states) {
        resolveHomeMediaSession(
            players = wall.players,
            states = states,
            browseSelectedId = wall.selectedEntityId,
            queue = wall.queue,
            appleTvEntityId = APPLE_TV_ENTITY,
        )
    }

    val visible by viewModel.homeMediaVisibility.visible.collectAsState()
    LaunchedEffect(snapshot.playing) {
        viewModel.homeMediaVisibility.updatePlaying(snapshot.playing)
    }
    if (!snapshot.playing && !visible) return
    if (compact && snapshot.kind == HomeMediaKind.Idle) return

    when {
        compact -> CompactMediaStrip(snapshot, viewModel, modifier)
        snapshot.kind == HomeMediaKind.Idle -> IdleListenCard(
            viewModel = viewModel,
            recentlyPlayed = wall.discovery.recentlyPlayed.take(3),
            destinationName = wall.players.firstOrNull { it.entityId == wall.selectedEntityId }?.name
                ?: wall.selectedEntityId?.substringAfter('.')?.replace('_', ' '),
            discoveryError = wall.discovery.error,
            modifier = modifier,
        )
        snapshot.kind == HomeMediaKind.Tv -> FullTvCard(snapshot, viewModel, modifier)
        else -> FullMusicCard(snapshot, viewModel, modifier)
    }
}

/**
 * Pick the home media session: active MASS music (not Apple TV) vs Apple TV,
 * with playing beating paused. Browse [browseSelectedId] is only a tie-break when idle.
 */
internal fun resolveHomeMediaSession(
    players: List<MusicAssistantPlayer>,
    states: Map<String, EntityState>,
    browseSelectedId: String?,
    queue: MusicAssistantQueue?,
    appleTvEntityId: String = APPLE_TV_ENTITY,
): HomeMediaSnapshot {
    val apple = states[appleTvEntityId]
    val appleState = apple?.state
    val appleTitle = apple?.mediaTitle()
    val tvActive = appleState == "playing" || (appleState == "paused" && !appleTitle.isNullOrBlank())

    val musicCandidates = players.filter { player ->
        player.entityId != appleTvEntityId &&
            player.entityId != TV_ENTITY &&
            (states[player.entityId]?.isMusicAssistantPlayer() == true ||
                player.massPlayerId != null ||
                player.massPlaybackState != null)
    }

    data class MusicHit(
        val player: MusicAssistantPlayer,
        val state: EntityState?,
        val playing: Boolean,
        val paused: Boolean,
    )

    val musicHits = musicCandidates.mapNotNull { player ->
        val st = states[player.entityId]
        val haState = st?.state
        val massState = player.massPlaybackState?.lowercase()
        val playing = haState == "playing" || massState == "playing"
        val paused = !playing && (
            haState == "paused" || massState == "paused"
            ) && (!st?.mediaTitle().isNullOrBlank() || queue?.current != null && player.entityId == browseSelectedId)
        if (!playing && !paused) return@mapNotNull null
        // Paused without title and not owning the queue → ignore
        if (paused && st?.mediaTitle().isNullOrBlank() && queue?.current == null) return@mapNotNull null
        MusicHit(player, st, playing, paused)
    }

    val bestMusic = musicHits.firstOrNull { it.playing }
        ?: musicHits.firstOrNull { it.paused && it.player.entityId == browseSelectedId }
        ?: musicHits.firstOrNull { it.paused }

    val preferMusic = when {
        bestMusic?.playing == true -> true
        appleState == "playing" && bestMusic?.playing != true -> false
        bestMusic != null -> true
        tvActive -> false
        else -> false
    }

    return when {
        preferMusic && bestMusic != null -> {
            val player = bestMusic.player
            val st = bestMusic.state
            val useQueue = queue != null && browseSelectedId == player.entityId
            val title = (if (useQueue) queue?.current?.name else null)
                ?: st?.mediaTitle()
                ?: "Music"
            val artist = (if (useQueue) queue?.current?.artists else null)
                ?: st?.mediaArtist()
                ?: ""
            val art = st?.entityPicture
                ?: (if (useQueue) queue?.current?.imageUrl else null)
            HomeMediaSnapshot(
                kind = HomeMediaKind.Music,
                playing = bestMusic.playing,
                paused = bestMusic.paused,
                title = title,
                subtitle = artist,
                room = formatPlayerRoom(player, players),
                art = art,
                entityId = player.entityId,
                durationSec = (if (useQueue) queue?.current?.durationSec?.toDouble() else null)
                    ?: st?.mediaDurationSec(),
                positionSec = st?.mediaPositionSec() ?: (if (useQueue) queue?.elapsedSec else null),
                positionUpdatedAtMs = st?.mediaPositionUpdatedAtMs()
                    ?: (if (useQueue) queue?.elapsedUpdatedAtMs else null),
                volume = st?.volumeLevel(),
            )
        }
        tvActive -> {
            val season = apple?.attrString("media_season")
            val episode = apple?.attrString("media_episode")
            val seasonEpisode = listOfNotNull(
                season?.takeIf { it.isNotBlank() }?.let { "S$it" },
                episode?.takeIf { it.isNotBlank() }?.let { "E$it" },
            ).joinToString("").ifBlank { null }
            val series = apple?.attrString("media_series_title")?.takeIf { it.isNotBlank() }
            val app = apple?.attrString("app_name")?.takeIf { it.isNotBlank() }
            HomeMediaSnapshot(
                kind = HomeMediaKind.Tv,
                playing = appleState == "playing",
                paused = appleState == "paused",
                title = appleTitle?.takeIf { it.isNotBlank() } ?: "Apple TV",
                subtitle = listOfNotNull(app, series, seasonEpisode).joinToString(" · "),
                room = "Living Room",
                art = apple?.entityPicture,
                entityId = appleTvEntityId,
                durationSec = apple?.mediaDurationSec(),
                positionSec = apple?.mediaPositionSec(),
                positionUpdatedAtMs = apple?.mediaPositionUpdatedAtMs(),
                volume = apple?.volumeLevel(),
                appName = app,
                seriesName = series,
                seasonEpisode = seasonEpisode,
                supportsPrevious = false,
                supportsNext = false,
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
            entityId = browseSelectedId,
            durationSec = null,
            positionSec = null,
            positionUpdatedAtMs = null,
            volume = null,
        )
    }
}

/** @deprecated Use [resolveHomeMediaSession]; kept for older unit tests. */
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
    val fakePlayer = musicEntityId?.let {
        MusicAssistantPlayer(entityId = it, name = musicRoom ?: "Speakers")
    }
    val musicEntity = musicEntityId?.let { id ->
        EntityState(
            entityId = id,
            state = musicState ?: "idle",
            attributes = buildMap {
                musicTitle?.let { put("media_title", JsonPrimitive(it)) }
                musicArtist?.let { put("media_artist", JsonPrimitive(it)) }
                musicArt?.let { put("entity_picture", JsonPrimitive(it)) }
                musicDuration?.let { put("media_duration", JsonPrimitive(it)) }
                musicPosition?.let { put("media_position", JsonPrimitive(it)) }
                musicVolume?.let { put("volume_level", JsonPrimitive(it.toDouble())) }
                put("mass_player_type", JsonPrimitive("player"))
            },
        )
    }
    val appleEntity = EntityState(
        entityId = APPLE_TV_ENTITY,
        state = appleState ?: "idle",
        attributes = buildMap {
            appleTitle?.let { put("media_title", JsonPrimitive(it)) }
            appleApp?.let { put("app_name", JsonPrimitive(it)) }
            appleArt?.let { put("entity_picture", JsonPrimitive(it)) }
            appleDuration?.let { put("media_duration", JsonPrimitive(it)) }
            applePosition?.let { put("media_position", JsonPrimitive(it)) }
            appleVolume?.let { put("volume_level", JsonPrimitive(it.toDouble())) }
        },
    )
    return resolveHomeMediaSession(
        players = listOfNotNull(fakePlayer),
        states = buildMap {
            musicEntity?.let { put(it.entityId, it) }
            put(APPLE_TV_ENTITY, appleEntity)
        },
        browseSelectedId = musicEntityId,
        queue = null,
    )
}

internal fun formatPlayerRoom(player: MusicAssistantPlayer, all: List<MusicAssistantPlayer>): String {
    if (!player.isGrouped) return player.name
    val memberNames = player.groupMemberIds.mapNotNull { id ->
        all.firstOrNull { it.massPlayerId == id }?.name
    }.filter { it != player.name }
    return when {
        memberNames.isEmpty() -> player.name
        memberNames.size == 1 -> "${player.name} + ${memberNames[0]}"
        else -> "${player.name} + ${memberNames.size}"
    }
}

@Composable
private fun FullMusicCard(
    snapshot: HomeMediaSnapshot,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val overlay = LocalOverlay.current
    val entityId = snapshot.entityId
    var volume by remember(snapshot.volume, entityId) { mutableFloatStateOf(snapshot.volume ?: 0.4f) }
    LaunchedEffect(snapshot.volume) { snapshot.volume?.let { volume = it } }

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
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (snapshot.paused) {
                Text("Paused", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            AlbumOutpaintHero(
                coverPath = snapshot.art,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                snapshot.title,
                color = TextDark,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot.subtitle.isNotBlank()) {
                Text(
                    snapshot.subtitle,
                    color = TextMuted,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(snapshot.room, color = TextMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MediaProgressRow(
                positionSec = snapshot.positionSec,
                durationSec = snapshot.durationSec,
                positionUpdatedAtMs = snapshot.positionUpdatedAtMs,
                playing = snapshot.playing,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaIconButton(
                    "mdi:skip-previous",
                    label = "Previous track",
                    onClick = { entityId?.let { viewModel.homeMediaCommand(it, "media_previous_track") } },
                )
                MediaIconButton(
                    if (snapshot.playing) "mdi:pause" else "mdi:play",
                    label = if (snapshot.playing) "Pause" else "Play",
                    filled = true,
                    size = 60.dp,
                    iconSize = 32.dp,
                    onClick = { entityId?.let { viewModel.homeMediaCommand(it, "media_play_pause") } },
                )
                MediaIconButton(
                    "mdi:skip-next",
                    label = "Next track",
                    onClick = { entityId?.let { viewModel.homeMediaCommand(it, "media_next_track") } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon("mdi:volume-medium", tint = TextMuted, size = 22.dp)
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        entityId?.let { id -> viewModel.homeMediaSetVolume(id, it) }
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
    val entityId = snapshot.entityId ?: APPLE_TV_ENTITY
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
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (snapshot.paused) {
                Text("Paused", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!snapshot.art.isNullOrBlank()) {
                MusicCover(
                    path = snapshot.art,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x14000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon("mdi:television-classic", tint = TextMuted, size = 56.dp)
                }
            }
            Text(
                snapshot.title,
                color = TextDark,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot.subtitle.isNotBlank()) {
                Text(snapshot.subtitle, color = TextMuted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(snapshot.room, color = TextMuted, fontSize = 14.sp)
            MediaProgressRow(
                positionSec = snapshot.positionSec,
                durationSec = snapshot.durationSec,
                positionUpdatedAtMs = snapshot.positionUpdatedAtMs,
                playing = snapshot.playing,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaIconButton(
                    if (snapshot.playing) "mdi:pause" else "mdi:play",
                    label = if (snapshot.playing) "Pause" else "Play",
                    filled = true,
                    size = 60.dp,
                    iconSize = 32.dp,
                    onClick = { viewModel.homeMediaCommand(entityId, "media_play_pause") },
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
    val entityId = snapshot.entityId
    val onOpen = {
        when (snapshot.kind) {
            HomeMediaKind.Music -> viewModel.openPopup("#music")
            HomeMediaKind.Tv -> viewModel.openMoreInfo(TV_ENTITY)
            HomeMediaKind.Idle -> Unit
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardLight)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (snapshot.kind == HomeMediaKind.Tv && snapshot.art.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x14000000)),
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon("mdi:television-classic", tint = TextMuted, size = 28.dp)
            }
        } else {
            MusicCover(
                path = snapshot.art,
                viewModel = viewModel,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                spinnerSize = 16.dp,
                fallbackIconSize = 22.dp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                snapshot.title,
                color = TextDark,
                fontSize = 18.sp,
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
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MediaIconButton(
            if (snapshot.playing) "mdi:pause" else "mdi:play",
            label = if (snapshot.playing) "Pause" else "Play",
            size = 48.dp,
            iconSize = 24.dp,
            filled = true,
            onClick = {
                entityId?.let { viewModel.homeMediaCommand(it, "media_play_pause") }
            },
        )
    }
}

@Composable
private fun IdleListenCard(
    viewModel: HaViewModel,
    recentlyPlayed: List<MassMediaItem>,
    destinationName: String?,
    discoveryError: String?,
    modifier: Modifier = Modifier,
) {
    var launchError by remember { mutableStateOf<String?>(null) }
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
            Text("Listen", color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Browse",
                color = TextDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        viewModel.setMusicWallTab("discover")
                        viewModel.openPopup("#music")
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Browse music" },
            )
        }
        if (!destinationName.isNullOrBlank()) {
            Text("Playing on $destinationName", color = TextMuted, fontSize = 13.sp)
        }
        launchError?.let { Text(it, color = Color(0xFFC62828), fontSize = 13.sp) }
        discoveryError?.let { Text(it, color = TextMuted, fontSize = 13.sp) }
        if (recentlyPlayed.isNotEmpty()) {
            Text("Recently played", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                recentlyPlayed.forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (!item.canPlay) {
                                    viewModel.setMusicWallTab("discover")
                                    viewModel.openPopup("#music")
                                    return@clickable
                                }
                                launchError = null
                                viewModel.playMusicDiscoveryItem(item)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MusicCover(
                            path = item.imageUrl,
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            spinnerSize = 16.dp,
                            fallbackIconSize = 28.dp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.name,
                            color = TextDark,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaProgressRow(
    positionSec: Double?,
    durationSec: Double?,
    positionUpdatedAtMs: Long?,
    playing: Boolean,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(playing, positionSec, positionUpdatedAtMs) {
        while (playing) {
            delay(1000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val unused = tick
    val duration = durationSec?.takeIf { it > 0 }
    if (duration == null || positionSec == null) return

    val live = if (playing && positionUpdatedAtMs != null) {
        positionSec + (System.currentTimeMillis() - positionUpdatedAtMs) / 1000.0
    } else {
        positionSec
    }.coerceIn(0.0, duration)
    val progress = (live / duration).toFloat().coerceIn(0f, 1f)

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(live), color = TextMuted, fontSize = 12.sp)
            Text(formatClock(duration), color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MediaIconButton(
    icon: String,
    onClick: () -> Unit,
    label: String,
    filled: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) ActiveYellow else Color(0x14000000))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(icon, tint = if (filled) Color.Black else TextDark, size = iconSize)
    }
}

private fun formatClock(sec: Double): String {
    val total = sec.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
