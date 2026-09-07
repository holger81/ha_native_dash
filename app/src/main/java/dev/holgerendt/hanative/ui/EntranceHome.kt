package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.LightAllowlist
import dev.holgerendt.hanative.model.HomeDashboard
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.theme.AccentGreen
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.theme.ThemeBlack
import dev.holgerendt.hanative.ui.widgets.CameraCard
import dev.holgerendt.hanative.ui.widgets.ChipRow
import dev.holgerendt.hanative.ui.widgets.WeekPlanner
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ChipShape = RoundedCornerShape(20.dp)

private data class StatusItem(
    val id: String,
    val name: String,
    val isLock: Boolean = false,
)

@Composable
fun EntranceHomeScreen(home: HomeDashboard, viewModel: HaViewModel) {
    val menu = home.header.firstOrNull { it.type == "menu_button" }
    val showClock = home.header.any { it.type == "clock" }
    val lock = home.header.firstOrNull { it.type == "chip_row" }
    val occupied by viewModel.occupancyActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .pointerInput(menu) {
                        detectTapGestures(
                            onTap = { viewModel.setDrawer(true) },
                            onLongPress = { menu?.let { viewModel.onHold(it) } },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon(menu?.icon ?: "mdi:menu", tint = TextDark, size = 28.dp)
            }
            if (showClock) {
                Spacer(Modifier.weight(1f))
                WallClock()
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            lock?.let { ChipRow(it, viewModel) }
        }

        Spacer(Modifier.height(10.dp))

        // Main 3-Column Content: Cameras | Calendar | Status & Actions
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Column 1: Live Cameras (stacked vertically)
            if (home.heroCameras.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    home.heroCameras.forEach { camera ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { viewModel.openPopup("#camera_alert") }
                                .then(
                                    if (occupied) {
                                        Modifier.border(2.dp, ActiveYellow, RoundedCornerShape(18.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            CameraCard(
                                widget = camera,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                                fill = true,
                            )
                            if (occupied) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ActiveYellow)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    MdiIcon("mdi:motion-sensor", tint = ThemeBlack, size = 12.dp)
                                    Text(
                                        text = "Motion",
                                        color = ThemeBlack,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Column 2: Today's Calendar Planner
            if (home.calendar != null) {
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                ) {
                    WeekPlanner(
                        widget = home.calendar,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Column 3: Listed Entities (Status) & Actions
            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (home.status != null) {
                    OpenStatusCard(
                        widget = home.status,
                        viewModel = viewModel,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                if (home.actions.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        home.actions.forEach { action ->
                            EntranceActionChip(
                                widget = action,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallClock() {
    val zone = remember { ZoneId.systemDefault() }
    var now by remember { mutableStateOf(LocalTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            now = LocalTime.now(zone)
            delay(1_000)
        }
    }
    Text(
        text = now.format(TimeFormat),
        color = TextDark,
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenStatusCard(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val doorLocks = widget.doorLocks.orEmpty()
    val windowCovers = widget.windowCovers.orEmpty()
    val storedLights by viewModel.monitoredLights.collectAsState()
    val states by viewModel.states.collectAsState()
    val doors = remember(states, doorLocks) { openDoors(states, doorLocks) }
    val windows = remember(states, windowCovers) { openWindows(states, windowCovers) }
    val lights = remember(states, storedLights) {
        LightAllowlist.currentlyOn(storedLights, states).map { id ->
            id to (states[id]?.friendlyName ?: id.substringAfter('.').replace('_', ' '))
        }.sortedBy { it.second }
    }
    val allClosed = doors.isEmpty() && windows.isEmpty() && lights.isEmpty()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardLight)
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MdiIcon(
                    if (allClosed) "mdi:shield-check" else "mdi:shield-alert",
                    tint = if (allClosed) Color(0xFF2E7D32) else ActiveYellow,
                    size = 20.dp,
                )
                Text(
                    text = if (allClosed) "All Closed" else "House Status",
                    color = TextDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (allClosed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusRowItem("mdi:lock-outline", "Doors locked")
                    StatusRowItem("mdi:window-closed-variant", "Windows closed")
                    StatusRowItem("mdi:lightbulb-outline", "All lights off")
                }
            } else {
                if (doors.isNotEmpty()) {
                    StatusGroup(
                        title = "Doors",
                        count = doors.size,
                        icon = "mdi:door-open",
                        items = doors,
                        onItemClick = { item ->
                            if (item.isLock) {
                                viewModel.callEntityService(item.id, "lock", "lock")
                            } else {
                                viewModel.callEntityService(item.id, "close_cover", "cover")
                            }
                        },
                    )
                }

                if (windows.isNotEmpty()) {
                    StatusGroup(
                        title = "Windows",
                        count = windows.size,
                        icon = "mdi:window-open-variant",
                        items = windows,
                        onItemClick = { item ->
                            viewModel.callEntityService(item.id, "close_cover", "cover")
                        },
                    )
                }

                if (lights.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MdiIcon("mdi:lightbulb-on", tint = ActiveYellow, size = 16.dp)
                            Text(
                                text = "Lights On (${lights.size})",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            lights.forEach { (id, name) ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(ActiveYellow.copy(alpha = 0.22f))
                                        .clickable { viewModel.turnOffEntity(id) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    MdiIcon("mdi:lightbulb", tint = ThemeBlack, size = 14.dp)
                                    Text(
                                        text = name,
                                        color = TextDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusGroup(
    title: String,
    count: Int,
    icon: String,
    items: List<StatusItem>,
    onItemClick: (StatusItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MdiIcon(icon, tint = ActiveYellow, size = 16.dp)
            Text(
                text = "$title ($count)",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ActiveYellow.copy(alpha = 0.22f))
                        .clickable { onItemClick(item) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MdiIcon(
                        if (item.isLock) {
                            "mdi:lock-open-outline"
                        } else if ("garage" in item.id.lowercase()) {
                            "mdi:garage-open"
                        } else {
                            icon
                        },
                        tint = ThemeBlack,
                        size = 14.dp,
                    )
                    Text(
                        text = item.name,
                        color = TextDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRowItem(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(icon, tint = TextMuted, size = 16.dp)
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun EntranceActionChip(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val storedLights by viewModel.monitoredLights.collectAsState()
    val allStates by viewModel.states.collectAsState()
    val entityIds = widget.tap?.entityIds().orEmpty()
    val trackedStates by viewModel.entitiesFlow(entityIds).collectAsState()
    val active = if (widget.type == "lights_off") {
        LightAllowlist.currentlyOn(storedLights, allStates).isNotEmpty()
    } else {
        entityIds.any { id ->
            val state = trackedStates[id]?.state
            state == "on" || state == "open" || state == "opening"
        }
    }
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(if (active) ActiveYellow else ChipDark)
            .clickable { viewModel.onTap(widget) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (active) ThemeBlack else ChipOnDark
        MdiIcon(widget.icon, tint = tint, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = widget.name.orEmpty(),
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun openDoors(states: Map<String, EntityState>, lockIds: List<String>): List<StatusItem> {
    val items = mutableListOf<StatusItem>()
    lockIds.forEach { id ->
        val entity = states[id]
        if (entity?.state == "unlocked") {
            val name = entity.friendlyName.ifBlank { id.substringAfter('.').replace('_', ' ') }
            items += StatusItem(id, name, isLock = true)
        }
    }
    states.values.filter { entity ->
        entity.entityId.startsWith("cover.") &&
            "garage" in entity.entityId &&
            "model_3" !in entity.entityId &&
            entity.state in setOf("open", "opening")
    }.forEach { entity ->
        val name = entity.friendlyName.ifBlank { "Garage" }
        items += StatusItem(entity.entityId, name, isLock = false)
    }
    return items
}

private fun openWindows(states: Map<String, EntityState>, coverIds: List<String>): List<StatusItem> =
    coverIds.mapNotNull { id ->
        val entity = states[id] ?: return@mapNotNull null
        if (entity.state in setOf("open", "opening")) {
            val raw = entity.friendlyName.ifBlank { id.substringAfter('.').replace('_', ' ') }
            val cleanName = raw.replace(Regex("\\s*-?\\s*vent$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-[a-f0-9]{4}$", RegexOption.IGNORE_CASE), "")
                .trim()
            StatusItem(id, cleanName)
        } else {
            null
        }
    }.sortedBy { it.name }
