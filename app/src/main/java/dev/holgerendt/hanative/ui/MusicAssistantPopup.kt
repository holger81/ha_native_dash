package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
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
import dev.holgerendt.hanative.data.MusicAssistantPlayer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Native wall Music Assistant player: now playing / grouping / volumes,
 * plus Discover (Apple Music recently played, new music, stations, search).
 */
@Composable
fun MusicAssistantPopup(popup: PopupNode, viewModel: HaViewModel) {
    val wall by viewModel.musicWall.collectAsState()
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
        if (wall.tab == "discover") {
            DiscoverPane(
                viewModel = viewModel,
                wall = wall,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PlayersSidebar(viewModel = viewModel, wall = wall)
                NowPlayingPane(viewModel = viewModel, wall = wall)
            }
        }
    }
}

@Composable
private fun PlayersSidebar(
    viewModel: HaViewModel,
    wall: MusicWallState,
) {
    val overlay = LocalOverlay.current
    val selected = wall.players.firstOrNull { it.entityId == wall.selectedEntityId }
    val rootId = selected?.groupRootId
    val groupedIds = remember(selected, wall.players) {
        resolveGroupedMassIds(selected, wall.players)
    }
    val groupableIds = remember(selected, wall.players) {
        resolveGroupableMassIds(selected, wall.players)
    }
    val (groupSections, standalonePlayers) = remember(wall.players) {
        organizePlayerSections(wall.players)
    }

    Column(
        modifier = Modifier
            .width(268.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Players", color = overlay.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            "Tap a player to select it. Check others to sync into its group.",
            color = overlay.muted,
            fontSize = 11.sp,
        )
        if (groupSections.isNotEmpty()) {
            Text(
                "Grouped",
                color = overlay.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            groupSections.forEach { section ->
                PlayerGroupBlock(
                    viewModel = viewModel,
                    section = section,
                    selectedEntityId = wall.selectedEntityId,
                    selectedRootId = rootId,
                    groupedIds = groupedIds,
                    groupableIds = groupableIds,
                    onSelect = viewModel::selectMusicPlayer,
                    onGroupChange = { massId, checked -> viewModel.setPlayerGrouped(massId, checked) },
                )
            }
        }
        if (standalonePlayers.isNotEmpty()) {
            if (groupSections.isNotEmpty()) {
                Text(
                    "Standalone",
                    color = overlay.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            standalonePlayers.forEach { player ->
                SidebarPlayerChip(
                    player = player,
                    stateFlow = viewModel.entityFlow(player.entityId),
                    selectedEntityId = wall.selectedEntityId,
                    selectedRootId = rootId,
                    groupedIds = groupedIds,
                    groupableIds = groupableIds,
                    role = PlayerGroupRole.Standalone,
                    onSelect = { viewModel.selectMusicPlayer(player.entityId) },
                    onGroupChange = { massId, checked -> viewModel.setPlayerGrouped(massId, checked) },
                )
            }
        }
        val playingElsewhere = wall.players.firstOrNull {
            it.entityId != wall.selectedEntityId && it.massPlaybackState.equals("playing", ignoreCase = true)
        }
        if (playingElsewhere != null && wall.selectedEntityId != null) {
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
) {
    val overlay = LocalOverlay.current
    val selectedId = wall.selectedEntityId
    val selected = wall.players.firstOrNull { it.entityId == selectedId }
    val entity by viewModel.entityFlow(selectedId).collectAsState()
    val playing = entity?.state == "playing" ||
        entity?.state == "paused" ||
        selected?.massPlaybackState.equals("playing", ignoreCase = true) ||
        wall.queue?.playbackState.equals("playing", ignoreCase = true)
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
    val position = liveMediaPosition(
        entity = entity,
        queueElapsed = wall.queue?.elapsedSec,
        queueElapsedUpdatedAtMs = wall.queue?.elapsedUpdatedAtMs,
        playerElapsed = selected?.elapsedSec,
        playerElapsedUpdatedAtMs = selected?.elapsedUpdatedAtMs,
        playing = playing,
    )

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
            val artMod = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(28.dp))
            when {
                !art.isNullOrBlank() -> MusicCover(
                    path = art,
                    viewModel = viewModel,
                    modifier = artMod,
                    spinnerSize = 28.dp,
                    fallbackIconSize = 72.dp,
                )
                wall.loading -> Box(
                    modifier = artMod.background(overlay.well),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(indicatorSize = 28.dp)
                }
                else -> Box(
                    modifier = artMod.background(overlay.well),
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
            SyncedSpeakersRow(selected = selected, players = wall.players)
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
            VolumeControls(
                viewModel = viewModel,
                wall = wall,
                selected = selected,
                entity = entity,
            )
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
private fun VolumeControls(
    viewModel: HaViewModel,
    wall: MusicWallState,
    selected: MusicAssistantPlayer?,
    entity: EntityState?,
) {
    val overlay = LocalOverlay.current
    val grouped = selected?.isGrouped == true
    val members = remember(selected, wall.players) {
        if (selected == null) emptyList()
        else wall.players.filter { player ->
            val id = player.massPlayerId ?: return@filter false
            id == selected.groupRootId || id in selected.groupMemberIds ||
                player.syncedToId == selected.groupRootId ||
                player.syncedToId == selected.massPlayerId
        }.distinctBy { it.massPlayerId }
    }
    var showMembers by remember(selected?.entityId, grouped) { mutableStateOf(grouped) }

    val groupVolume = (
        selected?.massGroupVolume?.div(100f)
            ?: selected?.massVolume?.div(100f)
            ?: entity?.volumeLevel()
            ?: 0f
        ).coerceIn(0f, 1f)
    var groupSlider by remember(selected?.entityId) { mutableFloatStateOf(groupVolume) }
    var draggingGroup by remember { mutableStateOf(false) }
    LaunchedEffect(groupVolume, draggingGroup) {
        if (!draggingGroup) groupSlider = groupVolume
    }

    Column(
        modifier = Modifier.fillMaxWidth(0.92f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MdiIcon(
                if (grouped) "mdi:speaker-multiple" else "mdi:volume-high",
                tint = overlay.muted,
                size = 22.dp,
            )
            Text(
                if (grouped) "Group volume" else "Volume",
                color = overlay.muted,
                fontSize = 13.sp,
                modifier = Modifier.width(110.dp),
            )
            Slider(
                value = groupSlider,
                onValueChange = {
                    draggingGroup = true
                    groupSlider = it
                    viewModel.setMusicVolume(it, mode = if (grouped) "group" else "auto")
                },
                onValueChangeFinished = {
                    draggingGroup = false
                    viewModel.setMusicVolume(groupSlider, mode = if (grouped) "group" else "auto")
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = ActiveYellow,
                    activeTrackColor = ActiveYellow,
                ),
            )
        }

        if (grouped && members.size > 1) {
            Text(
                if (showMembers) "Hide individual volumes" else "Individual volumes",
                color = ActiveYellow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showMembers = !showMembers },
            )
            if (showMembers) {
                members.forEach { member ->
                    val memberVol = (member.massVolume?.div(100f) ?: 0f).coerceIn(0f, 1f)
                    var slider by remember(member.massPlayerId) { mutableFloatStateOf(memberVol) }
                    var dragging by remember { mutableStateOf(false) }
                    LaunchedEffect(memberVol, dragging) {
                        if (!dragging) slider = memberVol
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            member.name,
                            color = overlay.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(110.dp),
                        )
                        Slider(
                            value = slider,
                            onValueChange = {
                                dragging = true
                                slider = it
                                member.massPlayerId?.let { id -> viewModel.setMemberVolume(id, it) }
                            },
                            onValueChangeFinished = {
                                dragging = false
                                member.massPlayerId?.let { id -> viewModel.setMemberVolume(id, slider) }
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = ActiveYellow,
                                activeTrackColor = ActiveYellow,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPane(
    viewModel: HaViewModel,
    wall: MusicWallState,
    modifier: Modifier = Modifier,
) {
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
    val selected = wall.players.firstOrNull { it.entityId == wall.selectedEntityId }
    val selectedHasMass = !selected?.massPlayerId.isNullOrBlank()
    val browsing = discovery.browseStack.isNotEmpty()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Play on", color = overlay.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            wall.players.filter { !it.massPlayerId.isNullOrBlank() }.ifEmpty { wall.players }.forEach { player ->
                val active = player.entityId == wall.selectedEntityId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) ActiveYellow else overlay.card)
                        .clickable { viewModel.selectMusicPlayer(player.entityId) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        player.name,
                        color = if (active) Color.Black else overlay.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        if (!browsing) {
            OutlinedTextField(
                value = discovery.searchQuery,
                onValueChange = viewModel::setMusicSearchQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(searchPlaceholder(discovery.searchTypes)) },
                shape = RoundedCornerShape(18.dp),
                colors = fieldColors,
                leadingIcon = {
                    MdiIcon("mdi:magnify", tint = overlay.muted, size = 22.dp)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "track" to "Tracks",
                    "album" to "Albums",
                    "playlist" to "Playlists",
                    "artist" to "Artists",
                ).forEach { (type, label) ->
                    val active = type in discovery.searchTypes
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) ActiveYellow else overlay.card)
                            .clickable { viewModel.toggleMusicSearchType(type) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            color = if (active) Color.Black else overlay.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(overlay.card)
                    .clickable { viewModel.openAppleMusicBrowse() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("Browse Apple Music", color = overlay.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (!selectedHasMass) {
            Text(
                "Pick a Music Assistant player above so Discover can play to it.",
                color = overlay.muted,
                fontSize = 13.sp,
            )
        }

        discovery.error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
        }

        when {
            browsing -> {
                MusicBrowsePane(viewModel = viewModel, discovery = discovery)
            }
            discovery.searchLoading -> {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    LoadingSpinner()
                }
            }
            discovery.searchResults != null -> {
                val results = discovery.searchResults
                val types = discovery.searchTypes
                if (results.isEmpty) {
                    Text("No matches for “${discovery.searchQuery.trim()}”.", color = overlay.muted, fontSize = 14.sp)
                } else {
                    if ("track" in types) {
                        SearchResultSection("Tracks", results.tracks, discovery.playingUri, viewModel)
                    }
                    if ("album" in types) {
                        SearchResultSection("Albums", results.albums, discovery.playingUri, viewModel)
                    }
                    if ("playlist" in types) {
                        SearchResultSection("Playlists", results.playlists, discovery.playingUri, viewModel)
                    }
                    if ("artist" in types) {
                        SearchResultSection("Artists", results.artists, discovery.playingUri, viewModel)
                    }
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
                    onSeeAll = { viewModel.openAppleMusicSeeAll("recent") },
                )
                DiscoveryShelf(
                    title = "New music",
                    subtitle = "Apple Music Friday refresh",
                    items = discovery.newMusic,
                    playingUri = discovery.playingUri,
                    viewModel = viewModel,
                    onSeeAll = { viewModel.openAppleMusicSeeAll("new_music") },
                )
                DiscoveryShelf(
                    title = "Stations for you",
                    subtitle = "Apple Music radio",
                    items = discovery.stationsForYou,
                    playingUri = discovery.playingUri,
                    viewModel = viewModel,
                    onSeeAll = { viewModel.openAppleMusicSeeAll("stations") },
                )
            }
        }
    }
}

@Composable
private fun MusicBrowsePane(viewModel: HaViewModel, discovery: MusicDiscoveryState) {
    val overlay = LocalOverlay.current
    val frame = discovery.browseStack.lastOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(overlay.card)
                    .clickable { viewModel.browseMusicBack() },
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon("mdi:chevron-left", tint = overlay.text, size = 24.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    frame.title,
                    color = overlay.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Apple Music", color = overlay.muted, fontSize = 12.sp)
            }
        }
        when {
            frame.loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                LoadingSpinner()
            }
            frame.error != null && frame.items.isEmpty() -> Text(frame.error, color = Color(0xFFFF8A80), fontSize = 13.sp)
            frame.items.isEmpty() -> Text("Nothing here", color = overlay.muted, fontSize = 14.sp)
            else -> {
                frame.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                frame.items.forEach { item ->
                    DiscoveryListRow(
                        item = item,
                        playing = item.uri == discovery.playingUri,
                        onClick = { viewModel.onMusicBrowseItem(item) },
                        viewModel = viewModel,
                        showPlayIcon = item.canPlay,
                    )
                }
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
    onSeeAll: (() -> Unit)? = null,
) {
    val overlay = LocalOverlay.current
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = overlay.muted, fontSize = 13.sp)
            }
            if (onSeeAll != null) {
                Text(
                    "See all",
                    color = ActiveYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onSeeAll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
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
        Box {
            MusicCover(
                path = item.imageUrl,
                viewModel = viewModel,
                modifier = Modifier
                    .size(128.dp)
                    .clip(RoundedCornerShape(14.dp)),
                spinnerSize = 20.dp,
                fallbackIconSize = 36.dp,
            )
            if (playing) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(color = Color.White, indicatorSize = 24.dp)
                }
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
    showPlayIcon: Boolean = true,
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
        Box {
            MusicCover(
                path = item.imageUrl,
                viewModel = viewModel,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                spinnerSize = 16.dp,
                fallbackIconSize = 22.dp,
            )
            if (playing) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(color = Color.White, indicatorSize = 18.dp)
                }
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
        when {
            playing -> LoadingSpinner(indicatorSize = 22.dp)
            item.canBrowse -> MdiIcon("mdi:chevron-right", tint = overlay.muted, size = 24.dp)
            showPlayIcon && item.canPlay -> MdiIcon("mdi:play-circle", tint = overlay.muted, size = 28.dp)
        }
    }
}

private fun searchPlaceholder(types: Set<String>): String {
    val labels = listOf(
        "track" to "tracks",
        "album" to "albums",
        "playlist" to "playlists",
        "artist" to "artists",
    ).filter { it.first in types }.map { it.second }
    return when {
        labels.isEmpty() -> "Search…"
        labels.size == 4 -> "Search tracks, albums, playlists, artists…"
        else -> "Search ${labels.joinToString(", ")}…"
    }
}

private enum class PlayerGroupRole { Leader, Member, Standalone }

private data class PlayerGroupSection(
    val rootMassId: String,
    val players: List<MusicAssistantPlayer>,
)

private fun organizePlayerSections(
    players: List<MusicAssistantPlayer>,
): Pair<List<PlayerGroupSection>, List<MusicAssistantPlayer>> {
    val assigned = mutableSetOf<String>()
    val groups = mutableListOf<PlayerGroupSection>()

    players.forEach { candidate ->
        val massId = candidate.massPlayerId ?: return@forEach
        if (massId in assigned) return@forEach
        val rootId = candidate.groupRootId ?: massId
        val rootPlayer = players.firstOrNull { it.massPlayerId == rootId } ?: candidate
        val memberIds = resolveGroupedMassIds(rootPlayer, players)
        if (memberIds.size <= 1) return@forEach
        val ordered = players
            .filter { it.massPlayerId in memberIds }
            .sortedWith(compareBy({ if (it.massPlayerId == rootId) 0 else 1 }, { it.name.lowercase() }))
        groups += PlayerGroupSection(rootMassId = rootId, players = ordered)
        assigned.addAll(memberIds)
    }

    val standalone = players.filter { player ->
        val id = player.massPlayerId
        id == null || id !in assigned
    }
    return groups.sortedBy { it.players.firstOrNull()?.name?.lowercase().orEmpty() } to standalone
}

private fun resolveGroupMembers(
    selected: MusicAssistantPlayer?,
    players: List<MusicAssistantPlayer>,
): List<MusicAssistantPlayer> {
    if (selected == null || !selected.isGrouped) return emptyList()
    val groupedIds = resolveGroupedMassIds(selected, players)
    return players
        .filter { player -> player.massPlayerId in groupedIds }
        .sortedWith(compareBy({ if (it.massPlayerId == selected.groupRootId) 0 else 1 }, { it.name.lowercase() }))
}

@Composable
private fun PlayerGroupBlock(
    viewModel: HaViewModel,
    section: PlayerGroupSection,
    selectedEntityId: String?,
    selectedRootId: String?,
    groupedIds: Set<String>,
    groupableIds: Set<String>,
    onSelect: (String) -> Unit,
    onGroupChange: (String, Boolean) -> Unit,
) {
    val overlay = LocalOverlay.current
    val containsSelected = section.players.any { it.entityId == selectedEntityId }
    val groupTint = if (containsSelected) {
        ActiveYellow.copy(alpha = 0.18f)
    } else {
        ActiveYellow.copy(alpha = 0.08f)
    }
    val borderTint = if (containsSelected) ActiveYellow.copy(alpha = 0.65f) else ActiveYellow.copy(alpha = 0.28f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, borderTint, RoundedCornerShape(20.dp))
            .background(groupTint)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MdiIcon("mdi:speaker-multiple", tint = ActiveYellow, size = 18.dp)
            Text(
                "${section.players.size} speakers",
                color = overlay.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (containsSelected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ActiveYellow.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Selected group", color = overlay.text, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        section.players.forEach { player ->
            val role = if (player.massPlayerId == section.rootMassId) {
                PlayerGroupRole.Leader
            } else {
                PlayerGroupRole.Member
            }
            SidebarPlayerChip(
                player = player,
                stateFlow = viewModel.entityFlow(player.entityId),
                selectedEntityId = selectedEntityId,
                selectedRootId = selectedRootId,
                groupedIds = groupedIds,
                groupableIds = groupableIds,
                role = role,
                onSelect = { onSelect(player.entityId) },
                onGroupChange = onGroupChange,
            )
        }
    }
}

@Composable
private fun SidebarPlayerChip(
    player: MusicAssistantPlayer,
    stateFlow: StateFlow<EntityState?>,
    selectedEntityId: String?,
    selectedRootId: String?,
    groupedIds: Set<String>,
    groupableIds: Set<String>,
    role: PlayerGroupRole,
    onSelect: () -> Unit,
    onGroupChange: (String, Boolean) -> Unit,
) {
    val state by stateFlow.collectAsState()
    val massId = player.massPlayerId
    val inGroup = massId != null && massId in groupedIds
    val canToggle = massId != null && (
        massId == selectedRootId ||
            massId in groupableIds ||
            inGroup
        )
    PlayerChip(
        name = player.name,
        state = state?.state ?: player.massPlaybackState ?: "idle",
        selected = player.entityId == selectedEntityId,
        massLinked = massId != null,
        grouped = inGroup,
        groupEnabled = canToggle && massId != selectedRootId,
        role = role,
        onSelect = onSelect,
        onGroupChange = { checked ->
            if (massId != null) onGroupChange(massId, checked)
        },
    )
}

@Composable
private fun SyncedSpeakersRow(
    selected: MusicAssistantPlayer?,
    players: List<MusicAssistantPlayer>,
) {
    val overlay = LocalOverlay.current
    val members = remember(selected, players) { resolveGroupMembers(selected, players) }
    if (members.size <= 1) return

    Column(
        modifier = Modifier.fillMaxWidth(0.92f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MdiIcon("mdi:speaker-multiple", tint = ActiveYellow, size = 18.dp)
            Text("Playing on", color = overlay.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            members.forEach { member ->
                val isLeader = member.massPlayerId == selected?.groupRootId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isLeader) ActiveYellow.copy(alpha = 0.35f) else overlay.well,
                        )
                        .border(
                            width = 1.dp,
                            color = if (isLeader) ActiveYellow.copy(alpha = 0.7f) else overlay.muted.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MdiIcon(
                        if (isLeader) "mdi:speaker-multiple" else "mdi:speaker",
                        tint = if (isLeader) Color.Black else overlay.muted,
                        size = 16.dp,
                    )
                    Text(
                        member.name,
                        color = overlay.text,
                        fontSize = 13.sp,
                        fontWeight = if (isLeader) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerChip(
    name: String,
    state: String,
    selected: Boolean,
    massLinked: Boolean,
    grouped: Boolean,
    groupEnabled: Boolean,
    role: PlayerGroupRole = PlayerGroupRole.Standalone,
    onSelect: () -> Unit,
    onGroupChange: (Boolean) -> Unit,
) {
    val overlay = LocalOverlay.current
    val background = when {
        selected -> ActiveYellow
        role == PlayerGroupRole.Member -> overlay.card.copy(alpha = 0.92f)
        else -> overlay.card
    }
    val tint = if (selected) Color.Black else overlay.text
    val memberIndent = if (role == PlayerGroupRole.Member) 14.dp else 0.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = memberIndent),
        verticalAlignment = Alignment.Top,
    ) {
        if (role == PlayerGroupRole.Member) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(48.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(ActiveYellow.copy(alpha = 0.45f)),
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(background)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GroupCheckbox(
                checked = grouped,
                enabled = groupEnabled || grouped,
                selectedRow = selected,
                onCheckedChange = onGroupChange,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        name,
                        color = tint,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (role == PlayerGroupRole.Leader) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) Color.Black.copy(alpha = 0.12f)
                                    else ActiveYellow.copy(alpha = 0.22f),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            MdiIcon(
                                "mdi:speaker-multiple",
                                tint = if (selected) Color.Black else ActiveYellow,
                                size = 14.dp,
                            )
                            Text(
                                "Group",
                                color = tint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
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
                    when (role) {
                        PlayerGroupRole.Leader -> "Group leader · ${state.replaceFirstChar { it.uppercase() }}"
                        PlayerGroupRole.Member -> "Synced · ${state.replaceFirstChar { it.uppercase() }}"
                        PlayerGroupRole.Standalone -> state.replaceFirstChar { it.uppercase() }
                    },
                    color = if (selected) Color.Black.copy(alpha = 0.65f) else overlay.muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun GroupCheckbox(
    checked: Boolean,
    enabled: Boolean,
    selectedRow: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val overlay = LocalOverlay.current
    val border = if (selectedRow) Color.Black.copy(alpha = 0.55f) else overlay.muted
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, border, RoundedCornerShape(6.dp))
            .background(
                when {
                    checked && selectedRow -> Color.Black
                    checked -> ActiveYellow
                    else -> Color.Transparent
                },
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            MdiIcon(
                "mdi:check",
                tint = if (selectedRow) ActiveYellow else Color.Black,
                size = 16.dp,
            )
        }
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
        MusicCover(
            path = item.imageUrl,
            viewModel = viewModel,
            modifier = thumbMod,
            spinnerSize = 18.dp,
            fallbackIconSize = 24.dp,
        )
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
    var dragging by remember { mutableStateOf(false) }
    var slider by remember(max) { mutableFloatStateOf(live.toFloat()) }
    LaunchedEffect(live, dragging) {
        if (!dragging) slider = live.toFloat()
    }
    Column(Modifier.fillMaxWidth(0.9f)) {
        if (max > 0) {
            Slider(
                value = slider.coerceIn(0f, max.toFloat()),
                onValueChange = {
                    dragging = true
                    slider = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(slider.toDouble())
                },
                valueRange = 0f..max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = ActiveYellow,
                    activeTrackColor = ActiveYellow,
                ),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatMediaClock(if (dragging) slider.toDouble() else live),
                color = overlay.muted,
                fontSize = 12.sp,
            )
            Text(
                if (max > 0) formatMediaClock(max) else "--:--",
                color = overlay.muted,
                fontSize = 12.sp,
            )
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

private data class PlaybackAnchor(val base: Double, val updatedAtMs: Long)

@Composable
private fun liveMediaPosition(
    entity: EntityState?,
    queueElapsed: Double?,
    queueElapsedUpdatedAtMs: Long?,
    playerElapsed: Double?,
    playerElapsedUpdatedAtMs: Long?,
    playing: Boolean,
): Double? {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(playing, entity?.entityId, queueElapsed, playerElapsed) {
        while (playing) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
        nowMs = System.currentTimeMillis()
    }

    val massAnchor = when {
        playerElapsed != null && playerElapsedUpdatedAtMs != null ->
            PlaybackAnchor(playerElapsed, playerElapsedUpdatedAtMs)
        queueElapsed != null && queueElapsedUpdatedAtMs != null ->
            PlaybackAnchor(queueElapsed, queueElapsedUpdatedAtMs)
        playerElapsed != null -> PlaybackAnchor(playerElapsed, nowMs)
        queueElapsed != null -> PlaybackAnchor(queueElapsed, nowMs)
        else -> null
    }
    val haPosition = entity?.mediaPositionSec()
    val haUpdatedAt = entity?.mediaPositionUpdatedAtMs()
    // Sonos/MA often reports media_position=0 with a refreshed timestamp; prefer Mass elapsed.
    val haUsable = haPosition != null && haPosition > 0.5 && haUpdatedAt != null
    val anchor = when {
        massAnchor != null && (massAnchor.base > 0.2 || !haUsable) -> massAnchor
        haUsable -> PlaybackAnchor(haPosition!!, haUpdatedAt!!)
        massAnchor != null -> massAnchor
        haPosition != null -> PlaybackAnchor(haPosition, haUpdatedAt ?: nowMs)
        else -> return null
    }
    if (!playing) return anchor.base
    val elapsed = ((nowMs - anchor.updatedAtMs).coerceAtLeast(0) / 1000.0)
    val duration = entity?.mediaDurationSec()
    val live = anchor.base + elapsed
    return if (duration != null) live.coerceAtMost(duration) else live
}

private fun resolveGroupedMassIds(
    selected: MusicAssistantPlayer?,
    players: List<MusicAssistantPlayer>,
): Set<String> {
    selected ?: return emptySet()
    val root = selected.groupRootId ?: selected.massPlayerId ?: return emptySet()
    val rootPlayer = players.firstOrNull { it.massPlayerId == root }
    val members = buildSet {
        add(root)
        addAll(selected.groupMemberIds)
        addAll(rootPlayer?.groupMemberIds.orEmpty())
        players.forEach { player ->
            val id = player.massPlayerId ?: return@forEach
            if (player.syncedToId == root || player.syncedToId == selected.massPlayerId) add(id)
        }
    }
    return members
}

private fun resolveGroupableMassIds(
    selected: MusicAssistantPlayer?,
    players: List<MusicAssistantPlayer>,
): Set<String> {
    selected ?: return emptySet()
    val root = selected.groupRootId ?: selected.massPlayerId ?: return emptySet()
    val rootPlayer = players.firstOrNull { it.massPlayerId == root } ?: selected
    return (rootPlayer.canGroupWithIds + resolveGroupedMassIds(selected, players)).toSet()
}
