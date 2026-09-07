package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.LightAllowlist
import dev.holgerendt.hanative.model.HomeDashboard
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.theme.ActiveYellow
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
private val ChipShape = RoundedCornerShape(24.dp)

@Composable
fun EntranceHomeScreen(home: HomeDashboard, viewModel: HaViewModel) {
    val menu = home.header.firstOrNull { it.type == "menu_button" }
    val showClock = home.header.any { it.type == "clock" }
    val lock = home.header.firstOrNull { it.type == "chip_row" }
    val occupied by viewModel.occupancyActive.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
    ) {
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
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (occupied && home.heroCameras.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    home.heroCameras.forEach { camera ->
                        CameraCard(
                            widget = camera,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f),
                            fill = true,
                        )
                    }
                }
            } else {
                home.calendar?.let { WeekPlanner(it, viewModel, Modifier.fillMaxSize()) }
            }
        }
        home.status?.let { status ->
            Spacer(Modifier.height(8.dp))
            OpenStatusPanel(status, viewModel, Modifier.fillMaxWidth().heightIn(max = 180.dp))
        }
        if (home.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                home.actions.forEach { action ->
                    EntranceActionChip(
                        widget = action,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
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

@Composable
private fun OpenStatusPanel(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val doorLocks = widget.doorLocks.orEmpty()
    val windowCovers = widget.windowCovers.orEmpty()
    val storedLights by viewModel.monitoredLights.collectAsState()
    val states by viewModel.states.collectAsState()
    val doors = remember(states, doorLocks) { openDoors(states, doorLocks) }
    val windows = remember(states, windowCovers) { openWindows(states, windowCovers) }
    val lights = remember(states, storedLights) {
        LightAllowlist.currentlyOn(storedLights, states).map { id ->
            id to (states[id]?.friendlyName ?: id.substringAfter('.').replace('_', ' '))
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (doors.isEmpty() && windows.isEmpty() && lights.isEmpty()) {
            Text("All closed", color = TextMuted.copy(alpha = 0.7f), fontSize = 13.sp)
        } else {
            if (doors.isNotEmpty()) StatusSection("Doors", doors.joinToString(", "))
            if (windows.isNotEmpty()) StatusSection("Windows", windows.joinToString(", "))
            if (lights.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Lights", color = TextMuted.copy(alpha = 0.8f), fontSize = 12.sp)
                    lights.forEach { (id, name) ->
                        Text(
                            text = name,
                            color = TextDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.turnOffEntity(id) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(title: String, body: String) {
    Column {
        Text(title, color = TextMuted.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(body, color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (active) ThemeBlack else ChipOnDark
        MdiIcon(widget.icon, tint = tint, size = 22.dp)
        Text(
            text = widget.name.orEmpty(),
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun openDoors(states: Map<String, EntityState>, lockIds: List<String>): List<String> {
    val names = mutableListOf<String>()
    lockIds.filter { states[it]?.state == "unlocked" }.forEach { id ->
        names += states[id]?.friendlyName ?: id.substringAfter('.').replace('_', ' ')
    }
    states.values.filter { entity ->
        entity.entityId.startsWith("cover.") &&
            "garage" in entity.entityId &&
            "model_3" !in entity.entityId &&
            entity.state in setOf("open", "opening")
    }.forEach { entity ->
        names += entity.friendlyName.ifBlank { "Garage" }
    }
    return names
}

private fun openWindows(states: Map<String, EntityState>, coverIds: List<String>): List<String> =
    coverIds.filter { states[it]?.state in setOf("open", "opening") }
        .map { id ->
            val raw = states[id]?.friendlyName ?: id.substringAfter('.').replace('_', ' ')
            raw.replace(Regex("\\s*-?\\s*vent$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-[a-f0-9]{4}$", RegexOption.IGNORE_CASE), "")
                .trim()
        }
        .sorted()
