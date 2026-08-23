package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.MassMediaItem
import dev.holgerendt.hanative.data.MusicAssistantQueueItem
import dev.holgerendt.hanative.data.formatMediaClock
import dev.holgerendt.hanative.data.mediaAlbum
import dev.holgerendt.hanative.data.mediaArtist
import dev.holgerendt.hanative.data.mediaDurationSec
import dev.holgerendt.hanative.data.mediaPositionSec
import dev.holgerendt.hanative.data.mediaPositionUpdatedAtMs
import dev.holgerendt.hanative.data.mediaTitle
import dev.holgerendt.hanative.data.repeatMode
import dev.holgerendt.hanative.data.isShuffleOn
import dev.holgerendt.hanative.data.volumeLevel
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.widgets.EntityPicture
import kotlinx.coroutines.delay

/**
 * Native wall Music Assistant player: now playing / queue controls, plus Discover
 * (Apple Music recently played, new music, stations, and search).
 */
@Composable
fun MusicAssistantPopup(popup: PopupNode, viewModel: HaViewModel) {
    val wall by viewModel.musicWall.collectAsState()
    val states by viewModel.states.collectAsState()
    val overlay = LocalOverlay.current

    if (wall.loading && wall.players.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingSpinner()
        }
        return
    }

    if (wall.players.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                wall.error ?: "No Music Assistant players found.",
                color = overlay.muted,
                fontSize = 15.sp,
            )
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MusicTabRow(
            selected = wall.tab,
            onSelect = viewModel::setMusicWallTab,
        )
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Players", color = overlay.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                wall.players.forEach { player ->
                    val state = states[player.entityId]
                    PlayerChip(
                        name = player.name,
                        state = state?.state ?: "idle",
                        selected = player.entityId == wall.selectedEntityId,
                        massLinked = !player.massPlayerId.isNullOrBlank(),
                        onClick = { viewModel.selectMusicPlayer(player.entityId) },
                    )
                }
                val playingElsewhere = wall.players.firstOrNull {
                    it.entityId != wall.selectedEntityId && states[it.entityId]?.state == "playing"
                }
                if (playingElsewhere != null && wall.selectedEntityId != null && wall.tab == "now") {
                    Spacer(Modifier.height(8.dp))
                    Text("Move queue here", color = overlay.muted, fontSize = 12.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(overlay.well)
                            .clickable { viewModel.transferMusicToSelected(playingElsewhere.entityId) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "From ${playingElsewhere.name}",
                            color = overlay.text,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            if (wall.tab == "discover") {
                DiscoverPane(viewModel = viewModel, wall = wall)
            } else {
                NowPlayingPane(viewModel = viewModel, wall = wall, states = states)
            }
        }
    }
}

@Composable
private fun MusicTabRow(selected: String, onSelect: (String) -> Unit) {
    val overlay = LocalOverlay.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(overlay.well)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("now" to "Now playing", "discover" to "Discover").forEach { (id, label) ->
            val active = selected == id
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) ActiveYellow else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) Color.Black else overlay.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingPane(
    viewModel: HaViewModel,
    wall: MusicWallState,
    states: Map<String, EntityState>,
) {
    val overlay = LocalOverlay.current
    val selectedId = wall.selectedEntityId
    val entity = selectedId?.let { states[it] }
    val playing = entity?.state == "playing"
    val title = wall.queue?.current?.streamTitle?.takeIf { it.isNotBlank() }
        ?: wall.queue?.current?.name
        ?: entity?.mediaTitle()
        ?: "Nothing playing"
    val artist = wall.queue?.current?.artists
        ?: entity?.mediaArtist()
        ?: entity?.state?.replaceFirstChar { it.uppercase() }
        ?: ""
    val album = wall.queue?.current?.album ?: entity?.mediaAlbum().orEmpty()
    val art = wall.queue?.current?.imageUrl ?: entity?.entityPicture
    val duration = wall.queue?.current?.durationSec?.toDouble() ?: entity?.mediaDurationSec()
    val position = liveMediaPosition(entity, wall.queue?.elapsedSec, playing)
    val volume = entity?.volumeLevel() ?: 0f
    var volumeSlider by remember(selectedId) { mutableFloatStateOf(volume) }
    LaunchedEffect(volume) {
        volumeSlider = volume
    }

    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!art.isNullOrBlank()) {
                EntityPicture(
                    path = art,
                    viewModel = viewModel,
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(28.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(overlay.well),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon("mdi:music-note", tint = overlay.muted, size = 72.dp)
                }
            }
            Text(
                title,
                color = overlay.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    color = overlay.muted,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (album.isNotBlank()) {
                Text(
                    album,
                    color = overlay.muted.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressRow(
                position = position,
                duration = duration,
                onSeek = { viewModel.seekMusic(it) },
            )
            TransportRow(
                playing = playing,
                shuffle = entity?.isShuffleOn() ?: wall.queue?.shuffle ?: false,
                repeat = entity?.repeatMode() ?: wall.queue?.repeatMode?.lowercase() ?: "off",
                onPrev = viewModel::mediaPrevious,
                onPlayPause = viewModel::mediaPlayPause,
                onNext = viewModel::mediaNext,
                onStop = viewModel::mediaStop,
                onShuffle = viewModel::toggleMusicShuffle,
                onRepeat = viewModel::cycleMusicRepeat,
            )
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MdiIcon("mdi:volume-high", tint = overlay.muted, size = 22.dp)
                Slider(
                    value = volumeSlider,
                    onValueChange = { volumeSlider = it },
                    onValueChangeFinished = { viewModel.setMusicVolume(volumeSlider) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = ActiveYellow,
                        activeTrackColor = ActiveYellow,
                    ),
                )
            }
            wall.error?.let {
                Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
            }
        }

        Column(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(overlay.well)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Up next", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val count = wall.queue?.itemCount ?: 0
            Text(
                if (count > 0) "$count in queue" else "Queue is empty",
                color = overlay.muted,
                fontSize = 13.sp,
            )
            val next = wall.queue?.next
            if (next != null) {
                QueueItemRow(next, viewModel)
            } else {
                Text("Nothing queued after this track.", color = overlay.muted, fontSize = 14.sp)
            }
            wall.queue?.current?.let { current ->
                Spacer(Modifier.height(8.dp))
                Text("Now", color = overlay.muted, fontSize = 12.sp)
                QueueItemRow(current, viewModel, muted = true)
            }
        }
    }
}

@Composable
private fun DiscoverPane(viewModel: HaViewModel, wall: MusicWallState) {
    val overlay = LocalOverlay.current
    val discovery = wall.discovery
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = overlay.text,
        unfocusedTextColor = overlay.text,
        focusedBorderColor = ActiveYellow,
        unfocusedBorderColor = overlay.muted.copy(alpha = 0.35f),
        cursorColor = ActiveYellow,
        focusedContainerColor = overlay.card,
        unfocusedContainerColor = overlay.card,
        focusedPlaceholderColor = overlay.muted,
        unfocusedPlaceholderColor = overlay.muted,
    )
    val selectedHasMass = wall.players.any {
        it.entityId == wall.selectedEntityId && !it.massPlayerId.isNullOrBlank()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = discovery.searchQuery,
            onValueChange = viewModel::setMusicSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search tracks, albums, playlists…") },
            shape = RoundedCornerShape(18.dp),
            colors = fieldColors,
            leadingIcon = {
                MdiIcon("mdi:magnify", tint = overlay.muted, size = 22.dp)
            },
        )

        if (!selectedHasMass) {
            Text(
                "Tip: pick a player with a Music Assistant link (yellow dot) so Discover can play to it.",
                color = overlay.muted,
                fontSize = 13.sp,
            )
        }

        discovery.error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
        }

        when {
            discovery.searchLoading -> {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    LoadingSpinner()
                }
            }
            discovery.searchResults != null -> {
                val results = discovery.searchResults
                if (results.isEmpty) {
                    Text("No matches for “${discovery.searchQuery.trim()}”.", color = overlay.muted, fontSize = 14.sp)
                } else {
                    SearchResultSection("Tracks", results.tracks, discovery.playingUri, viewModel)
                    SearchResultSection("Albums", results.albums, discovery.playingUri, viewModel)
                    SearchResultSection("Playlists", results.playlists, discovery.playingUri, viewModel)
                    SearchResultSection("Artists", results.artists, discovery.playingUri, viewModel)
                }
            }
            discovery.loading -> {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    LoadingSpinner()
                }
            }
            else -> {
                DiscoveryShelf(
                    title = "Recently played",
                    subtitle = "Apple Music & last queues",
                    items = discovery.recentlyPlayed,
                    playingUri = discovery.playingUri,
                    viewModel = viewModel,
                )
                DiscoveryShelf(
                    title = "New music",
                    subtitle = "Apple Music Friday refresh",
                    items = discovery.newMusic,
                    playingUri = discovery.playingUri,
                    viewModel = viewModel,
                )
                DiscoveryShelf(
                    title = "Stations for you",
                    subtitle = "Apple Music radio",
                    items = discovery.stationsForYou,
                    playingUri = discovery.playingUri,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryShelf(
    title: String,
    subtitle: String,
    items: List<MassMediaItem>,
    playingUri: String?,
    viewModel: HaViewModel,
) {
    val overlay = LocalOverlay.current
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = overlay.muted, fontSize = 13.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item ->
                DiscoveryTile(
                    item = item,
                    playing = item.uri == playingUri,
                    onClick = { viewModel.playMusicDiscoveryItem(item) },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SearchResultSection(
    title: String,
    items: List<MassMediaItem>,
    playingUri: String?,
    viewModel: HaViewModel,
) {
    val overlay = LocalOverlay.current
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = overlay.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            DiscoveryListRow(
                item = item,
                playing = item.uri == playingUri,
                onClick = { viewModel.playMusicDiscoveryItem(item) },
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun DiscoveryTile(
    item: MassMediaItem,
    playing: Boolean,
    onClick: () -> Unit,
    viewModel: HaViewModel,
) {
    val overlay = LocalOverlay.current
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (playing) ActiveYellow.copy(alpha = 0.28f) else overlay.card)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val thumb = Modifier
            .size(128.dp)
            .clip(RoundedCornerShape(14.dp))
        if (!item.imageUrl.isNullOrBlank()) {
            EntityPicture(path = item.imageUrl, viewModel = viewModel, modifier = thumb)
        } else {
            Box(modifier = thumb.background(overlay.well), contentAlignment = Alignment.Center) {
                MdiIcon("mdi:music-note", tint = overlay.muted, size = 36.dp)
            }
        }
        Text(
            item.name,
            color = overlay.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle ?: item.mediaType.replaceFirstChar { it.uppercase() },
            color = overlay.muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscoveryListRow(
    item: MassMediaItem,
    playing: Boolean,
    onClick: () -> Unit,
    viewModel: HaViewModel,
) {
    val overlay = LocalOverlay.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (playing) ActiveYellow.copy(alpha = 0.28f) else overlay.card)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
        if (!item.imageUrl.isNullOrBlank()) {
            EntityPicture(path = item.imageUrl, viewModel = viewModel, modifier = thumb)
        } else {
            Box(modifier = thumb.background(overlay.well), contentAlignment = Alignment.Center) {
                MdiIcon("mdi:music-note", tint = overlay.muted, size = 22.dp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = overlay.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle ?: item.mediaType.replaceFirstChar { it.uppercase() },
                color = overlay.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MdiIcon("mdi:play-circle", tint = if (playing) Color.Black else overlay.muted, size = 28.dp)
    }
}

@Composable
private fun PlayerChip(
    name: String,
    state: String,
    selected: Boolean,
    massLinked: Boolean,
    onClick: () -> Unit,
) {
    val overlay = LocalOverlay.current
    val background = when {
        selected -> ActiveYellow
        else -> overlay.card
    }
    val tint = if (selected) Color.Black else overlay.text
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name,
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (massLinked) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.Black else ActiveYellow),
                )
            }
        }
        Text(
            state.replaceFirstChar { it.uppercase() },
            color = if (selected) Color.Black.copy(alpha = 0.65f) else overlay.muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun QueueItemRow(item: MusicAssistantQueueItem, viewModel: HaViewModel, muted: Boolean = false) {
    val overlay = LocalOverlay.current
    val subtitle = listOfNotNull(item.artists, item.album).joinToString(" · ")
    val heading = item.streamTitle?.takeIf { it.isNotBlank() } ?: item.name
    val durationLabel = item.durationSec?.let { formatMediaClock(it.toDouble()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbMod = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
        if (!item.imageUrl.isNullOrBlank()) {
            EntityPicture(path = item.imageUrl, viewModel = viewModel, modifier = thumbMod)
        } else {
            Box(modifier = thumbMod.background(overlay.card), contentAlignment = Alignment.Center) {
                MdiIcon("mdi:music-note", tint = overlay.muted, size = 24.dp)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            Column {
                Text(
                    text = heading,
                    color = if (muted) overlay.muted else overlay.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = overlay.muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (durationLabel != null) {
                    Text(text = durationLabel, color = overlay.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(
    position: Double?,
    duration: Double?,
    onSeek: (Double) -> Unit,
) {
    val overlay = LocalOverlay.current
    val max = (duration ?: 0.0).coerceAtLeast(0.0)
    val live = (position ?: 0.0).coerceIn(0.0, if (max > 0) max else Double.MAX_VALUE)
    var slider by remember(max) { mutableFloatStateOf(live.toFloat()) }
    LaunchedEffect(live) {
        slider = live.toFloat()
    }
    Column(modifier = Modifier.fillMaxWidth(0.9f)) {
        if (max > 0) {
            Slider(
                value = slider.coerceIn(0f, max.toFloat()),
                onValueChange = { slider = it },
                onValueChangeFinished = { onSeek(slider.toDouble()) },
                valueRange = 0f..max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = ActiveYellow,
                    activeTrackColor = ActiveYellow,
                ),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMediaClock(live), color = overlay.muted, fontSize = 12.sp)
            Text(if (max > 0) formatMediaClock(max) else "--:--", color = overlay.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TransportRow(
    playing: Boolean,
    shuffle: Boolean,
    repeat: String,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIconButton(
            icon = if (shuffle) "mdi:shuffle-variant" else "mdi:shuffle-disabled",
            active = shuffle,
            onClick = onShuffle,
        )
        TransportIconButton(icon = "mdi:skip-previous", onClick = onPrev, size = 44.dp)
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(ActiveYellow)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(
                if (playing) "mdi:pause" else "mdi:play",
                tint = Color.Black,
                size = 36.dp,
            )
        }
        TransportIconButton(icon = "mdi:skip-next", onClick = onNext, size = 44.dp)
        TransportIconButton(icon = "mdi:stop", onClick = onStop)
        TransportIconButton(
            icon = when (repeat) {
                "one" -> "mdi:repeat-once"
                "all" -> "mdi:repeat"
                else -> "mdi:repeat-off"
            },
            active = repeat != "off",
            onClick = onRepeat,
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: String,
    onClick: () -> Unit,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val overlay = LocalOverlay.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) ActiveYellow.copy(alpha = 0.35f) else overlay.card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(icon, tint = if (active) Color.Black else overlay.text, size = size * 0.55f)
    }
}

@Composable
private fun liveMediaPosition(
    entity: EntityState?,
    queueElapsed: Double?,
    playing: Boolean,
): Double? {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(playing, entity?.entityId) {
        while (playing) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }
    val base = entity?.mediaPositionSec() ?: queueElapsed ?: return null
    if (!playing) return base
    val updatedAt = entity?.mediaPositionUpdatedAtMs() ?: return base
    val elapsed = ((nowMs - updatedAt).coerceAtLeast(0) / 1000.0)
    val duration = entity.mediaDurationSec()
    val live = base + elapsed
    return if (duration != null) live.coerceAtMost(duration) else live
}
