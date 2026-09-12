@file:OptIn(ExperimentalFoundationApi::class)

package dev.holgerendt.hanative.ui.widgets

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import dev.holgerendt.hanative.PanelConfig
import dev.holgerendt.hanative.R
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.HaCalendarEvent
import dev.holgerendt.hanative.data.KioskCommands
import dev.holgerendt.hanative.data.NetworkGuard
import dev.holgerendt.hanative.data.hasLiveCameraSource
import dev.holgerendt.hanative.data.timelineSnapshotPath
import dev.holgerendt.hanative.model.DisplayNode
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.StateFormat
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.AddCalendarEventDialog
import dev.holgerendt.hanative.ui.CalendarEventActionDialog
import dev.holgerendt.hanative.ui.CalendarManageAction
import dev.holgerendt.hanative.ui.CalendarMessageDialog
import dev.holgerendt.hanative.ui.DeleteCalendarEventDialog
import dev.holgerendt.hanative.ui.EditCalendarEventDialog
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.InWindowOverlay
import dev.holgerendt.hanative.ui.LoadingSpinner
import dev.holgerendt.hanative.ui.MdiIcon
import dev.holgerendt.hanative.ui.MediaPreview
import dev.holgerendt.hanative.ui.PinGateDialog
import dev.holgerendt.hanative.ui.brightnessPct
import dev.holgerendt.hanative.ui.format
import dev.holgerendt.hanative.ui.formatState
import dev.holgerendt.hanative.ui.isOn
import dev.holgerendt.hanative.ui.isVisible
import dev.holgerendt.hanative.ui.number
import dev.holgerendt.hanative.ui.relativeToNow
import dev.holgerendt.hanative.ui.rememberHaImageLoader
import dev.holgerendt.hanative.ui.resolveHaImageUrl
import dev.holgerendt.hanative.ui.roomHum
import dev.holgerendt.hanative.ui.roomTemp
import dev.holgerendt.hanative.ui.tempHum
import dev.holgerendt.hanative.ui.timelineEventStyle
import dev.holgerendt.hanative.ui.toDoubleOrNullSafe
import dev.holgerendt.hanative.ui.theme.AccentBlue
import dev.holgerendt.hanative.ui.theme.Gray000
import dev.holgerendt.hanative.ui.theme.Gray800
import dev.holgerendt.hanative.ui.theme.ThemeBlack
import dev.holgerendt.hanative.ui.theme.ThemeWhite
import dev.holgerendt.hanative.ui.theme.activeBigBrush
import dev.holgerendt.hanative.ui.theme.AccentRed
import dev.holgerendt.hanative.ui.theme.AccentYellow
import dev.holgerendt.hanative.ui.theme.ActiveLight
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.HistoryGraph
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import dev.holgerendt.hanative.ui.theme.OverlayLightPopup
import dev.holgerendt.hanative.ui.theme.PopupScrim
import dev.holgerendt.hanative.ui.theme.ScreenBackground
import dev.holgerendt.hanative.ui.theme.TabActiveEnd
import dev.holgerendt.hanative.ui.theme.TabActiveStart
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.theme.VacuumStart
import dev.holgerendt.hanative.ui.theme.VacuumStop
import dev.holgerendt.hanative.ui.theme.accentColor
import dev.holgerendt.hanative.ui.weatherIcon
import dev.holgerendt.hanative.ui.weatherTint
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)
private val ChipShape = RoundedCornerShape(24.dp)

private val TabActiveBrush = Brush.horizontalGradient(listOf(TabActiveStart, TabActiveEnd))
private val PopupSheetSheenBrush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.42f),
    0.2f to Color.Transparent,
)

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM")
private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEEE")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d")

private val BatteryRuntimeEntities = listOf(
    "binary_sensor.envoy_battery_discharging",
    "sensor.battery_runtime_remaining",
    "sensor.housepanel_total_consumption_house_consumption_1h_mean",
    "input_number.battery_energy_helper",
    "sensor.envoy_202234122877_reserve_battery_energy",
)

private val EnergyStatsEntities = listOf(
    "sensor.envoy_202234122877_current_power_production",
    "sensor.envoy_202234122877_current_net_power_consumption",
    "input_number.battery_energy_helper",
)

private val MmWaveEntities = listOf(
    "binary_sensor.secondary_living_room_switch_occupancy",
    "input_number.secondary_living_room_mmwave_target_count",
    "number.secondary_living_room_switch_mmwave_width_minimum_left",
    "number.secondary_living_room_switch_mmwave_width_maximum_right",
    "number.secondary_living_room_switch_mmwave_depth_minimum_near",
    "number.secondary_living_room_switch_mmwave_depth_maximum_far",
) + (1..4).flatMap { index ->
    listOf("x", "y", "z").map { axis ->
        "input_number.secondary_living_room_mmwave_target_${index}_$axis"
    }
}

private fun DisplayNode?.entityIds(): List<String?> =
    listOf(this?.climateEntity, this?.tempEntity, this?.humEntity)

private fun Modifier.widgetClicks(widget: WidgetNode, viewModel: HaViewModel): Modifier {
    val canHold = widget.hold != null && widget.hold.type != "none"
    return combinedClickable(
        onClick = { viewModel.onTap(widget) },
        onLongClick = if (canHold) ({ viewModel.onHold(widget) }) else null,
    )
}

@Composable
fun WidgetTree(
    cards: List<WidgetNode>,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { WidgetItem(it, viewModel, Modifier.fillMaxWidth()) }
    }
}

@Composable
fun WidgetItem(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    when (widget.type) {
        "gap" -> Spacer(modifier.height((widget.height ?: 8).dp))
        "vertical_stack" -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            widget.cards.forEach { WidgetItem(it, viewModel, Modifier.fillMaxWidth()) }
        }
        "horizontal_stack" -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            widget.cards.forEach { WidgetItem(it, viewModel, Modifier.weight(1f)) }
        }
        "grid" -> {
            val columns = widget.columnCount()
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                widget.cards.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { child ->
                            WidgetItem(child, viewModel, Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        "layout_grid" -> WidgetTree(widget.cards, viewModel, modifier)
        "swipe" -> Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            widget.cards.forEach { WidgetItem(it, viewModel, Modifier.width(280.dp)) }
        }
        "tabs" -> TabsWidget(widget, viewModel, modifier)
        "chip_row" -> ChipRow(widget, viewModel, modifier)
        "person" -> PersonCard(widget, viewModel, modifier)
        "weather_header" -> WeatherHeader(widget, viewModel, modifier)
        "room_card" -> RoomCard(widget, viewModel, modifier)
        "week_planner" -> WeekPlanner(widget, viewModel, modifier)
        "vision_timeline" -> VisionTimeline(widget, viewModel, modifier)
        "light_slider" -> LightSlider(widget, viewModel, modifier)
        "fan_slider" -> LightSlider(widget, viewModel, modifier)
        "section_header" -> SectionHeader(widget.name.orEmpty(), modifier)
        "light_toggle", "cover_toggle", "entity_button" -> {
            if (widget.entity.isNullOrBlank() && widget.icon.isNullOrBlank() && (widget.tap == null || widget.tap.type in setOf("none", "more_info"))) {
                SectionHeader(widget.name.orEmpty(), modifier)
            } else {
                ToggleRow(widget, viewModel, modifier)
            }
        }
        "vent_toggle" -> VentRow(widget, viewModel, modifier, listOfNotNull(widget.entity))
        "vents_group" -> VentRow(widget, viewModel, modifier, widget.entityIds.orEmpty())
        "climate" -> ClimateCard(widget, viewModel, modifier)
        "room_conditions" -> RoomConditions(widget, viewModel, modifier)
        "sensor_big", "sensor_big_2columns", "sensor_graph", "sensor_percentage", "sensor_small" ->
            SensorCard(widget, viewModel, modifier)
        "button_toggle", "button_toggle_small" -> ButtonToggle(widget, viewModel, modifier)
        "button_trigger", "action_chip" -> ActionChip(widget, viewModel, modifier)
        "vacuum_button" -> VacuumButton(widget, viewModel, modifier)
        "media_player" -> MediaCard(widget, viewModel, modifier)
        "camera" -> CameraCard(widget, viewModel, modifier)
        "generic" -> if (widget.cardType == "custom:webrtc-camera" || widget.hasLiveCameraSource()) {
            CameraCard(widget, viewModel, modifier)
        } else if (widget.cards.isNotEmpty()) {
            WidgetTree(widget.cards, viewModel, modifier)
        } else if (widget.entity != null || widget.name != null) {
            ToggleRow(widget, viewModel, modifier)
        }
        "chart", "mini_graph", "energy_usage_graph", "energy_solar_graph" -> HistoryChart(widget, viewModel, modifier)
        "auto_entities", "power_consumers" -> AutoEntitiesWidget(widget, viewModel, modifier)
        "energy_date_selection" -> EnergyDateBar(modifier)
        "energy_sources_table", "energy_solar_consumed_gauge", "energy_self_sufficiency_gauge" ->
            EnergyStats(viewModel, modifier)
        "battery_runtime" -> BatteryRuntimePanel(viewModel, modifier)
        "mmwave_targets" -> MmWaveTargetsPanel(viewModel, modifier)
        "markdown" -> {
            val overlay = LocalOverlay.current
            Text(
                text = widget.content.orEmpty().replace(Regex("[{}|]"), "").take(400),
                color = overlay.text,
                modifier = modifier
                    .clip(CardShape)
                    .background(overlay.card)
                    .padding(16.dp),
            )
        }
        "heading" -> {
            val overlay = LocalOverlay.current
            Text(widget.name.orEmpty(), color = overlay.text, fontWeight = FontWeight.Medium, modifier = modifier.padding(8.dp))
        }
        else -> if (widget.cards.isNotEmpty()) {
            WidgetTree(widget.cards, viewModel, modifier)
        } else if (widget.entity != null || widget.name != null) {
            ToggleRow(widget, viewModel, modifier)
        }
    }
}

@Composable
fun ChipRow(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val chipEntities = remember(widget) {
        widget.chips.flatMap { listOf(it.entity, it.state?.entity, it.visibility?.entity) }
    }
    val states by viewModel.entitiesFlow(chipEntities).collectAsState()
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widget.chips.filter { states.isVisible(it) }.forEach { chip ->
            val entity = states[chip.entity]
            val unlocked = chip.emphasizeUnlocked == true && entity?.state == "unlocked"
            val highlighted = when {
                unlocked -> true
                chip.accent == "active" || chip.accent == "active-big" -> true
                else -> false
            }
            val label = when {
                chip.layout == "icon|name" -> chip.name
                chip.state != null -> states.formatState(chip.state)
                else -> chip.name
            }
            val iconCircle = when {
                unlocked -> ThemeWhite
                else -> Gray000
            }
            Row(
                modifier = Modifier
                    .clip(ChipShape)
                    .then(
                        if (highlighted) {
                            Modifier.background(activeBigBrush())
                        } else {
                            Modifier.background(Gray800)
                        },
                    )
                    .widgetClicks(chip, viewModel)
                    .padding(end = 12.dp, start = 2.dp, top = 2.dp, bottom = 2.dp)
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconCircle),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon(chip.icon, tint = Gray800, size = 22.dp)
                }
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        color = if (highlighted) ThemeBlack else Gray000,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PersonCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.entitiesFlow(listOf(widget.entity, widget.homeSensor)).collectAsState()
    val person = states[widget.entity]
    val home = person?.state == "home"
    val minutesRaw = states[widget.homeSensor]?.state
    val label = personPresenceLabel(home = home, personState = person?.state, minutesRaw = minutesRaw)
    Column(
        modifier = modifier
            .width(72.dp)
            .widgetClicks(widget, viewModel),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntityPicture(
            path = person?.entityPicture,
            viewModel = viewModel,
            modifier = Modifier
                .size(44.dp)
                .border(2.dp, if (home) Color(0xFF8BC34A) else Color(0xFFE57373), CircleShape)
                .clip(CircleShape),
        )
        Text(label, color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

internal fun personPresenceLabel(home: Boolean, personState: String?, minutesRaw: String?): String {
    if (home) return "Home"
    val duration = formatPresenceDuration(minutesRaw)
    val place = personState
        ?.takeIf { it.isNotBlank() && it != "home" && it != "not_home" && it != "away" && it != "unknown" && it != "unavailable" }
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
    return when {
        place != null && duration != null -> "$place · $duration"
        place != null -> place
        duration != null -> "Away · $duration"
        else -> "Away"
    }
}

internal fun formatPresenceDuration(minutesRaw: String?): String? {
    val minutes = minutesRaw?.toDoubleOrNull()?.roundToInt()?.takeIf { it >= 0 } ?: return null
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}

@Composable
fun WeatherHeader(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val tempEntity = widget.tempEntity ?: "sensor.st_00063154_temperature"
    val sunEntity = widget.sunEntity ?: "sun.sun"
    val states by viewModel.entitiesFlow(listOf(widget.entity, tempEntity, sunEntity)).collectAsState()
    val weather = states[widget.entity]
    val temp = states[tempEntity]?.state?.toDoubleOrNull()
    val day = states[sunEntity]?.state == "above_horizon"
    val condition = weather?.state?.replace("sunny", "clear")?.replace('-', ' ') ?: ""
    Row(
        modifier = modifier.widgetClicks(widget, viewModel),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(weatherIcon(weather?.state, day), tint = weatherTint(weather?.state, day), size = 48.dp)
        Column(horizontalAlignment = Alignment.End) {
            Text(condition.replaceFirstChar { it.uppercase() }, color = TextMuted, fontSize = 14.sp)
            Text(temp.format(1, "°C"), color = TextDark, fontSize = 26.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun RoomCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.entitiesFlow(widget.display.entityIds()).collectAsState()
    val radii = parseRadius(widget.radius)
    val shape = RoundedCornerShape(radii[0], radii[1], radii[2], radii[3])
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(CardLight)
            .widgetClicks(widget, viewModel)
            .padding(12.dp),
    ) {
        Text(
            text = widget.name.orEmpty(),
            color = TextDark,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(56.dp)
                .clip(CircleShape)
                .background(accentColor(widget.accent)),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(widget.icon, tint = Color.Black, size = 28.dp)
        }
        Text(
            text = states.tempHum(widget.display),
            color = TextDark,
            fontSize = 36.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
fun RoomGrid(rooms: List<WidgetNode>, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val byArea = rooms.associateBy { it.gridArea }
    Layout(
        modifier = modifier,
        content = {
            listOf("emilia", "greatroom", "jonathan", "mainbed", "office", "hallway", "mainbath", "guestroom", "secondbath")
                .mapNotNull { byArea[it] }
                .forEach { RoomCard(it, viewModel, Modifier.fillMaxSize()) }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
            .takeUnless { it == Constraints.Infinity }
            ?.coerceAtLeast(1)
            ?: constraints.minWidth.coerceAtLeast(1)
        // Lovelace rooms mosaic: 1fr 1fr / 146px 70px 146px 146px 146px 70px 146px
        val rowHeights = listOf(146.dp, 70.dp, 146.dp, 146.dp, 146.dp, 70.dp, 146.dp).map { it.roundToPx() }
        fun yOf(row: Int) = rowHeights.take(row).sum() + gap * row
        fun hOf(from: Int, toExclusive: Int): Int {
            val count = (toExclusive - from).coerceAtLeast(1)
            return rowHeights.subList(from, toExclusive).sum() + gap * (count - 1)
        }
        val col = ((width - gap) / 2).coerceAtLeast(1)
        val specs = listOf(
            Triple("emilia", 0 to yOf(0), col to hOf(0, 2)),
            Triple("greatroom", col + gap to yOf(0), col to hOf(0, 1)),
            Triple("jonathan", col + gap to yOf(1), col to hOf(1, 3)),
            Triple("mainbed", 0 to yOf(2), col to hOf(2, 3)),
            Triple("office", 0 to yOf(3), width to hOf(3, 4)),
            Triple("hallway", 0 to yOf(4), col to hOf(4, 6)),
            Triple("mainbath", col + gap to yOf(4), col to hOf(4, 5)),
            Triple("guestroom", col + gap to yOf(5), col to hOf(5, 7)),
            Triple("secondbath", 0 to yOf(6), col to hOf(6, 7)),
        )
        val height = rowHeights.sum() + gap * (rowHeights.size - 1)
        val placeable = measurables.mapIndexed { index, measurable ->
            val spec = specs.getOrNull(index) ?: specs.last()
            val childWidth = spec.third.first.coerceAtLeast(1)
            val childHeight = spec.third.second.coerceAtLeast(1)
            measurable.measure(Constraints.fixed(childWidth, childHeight)) to spec.second
        }
        layout(width, height) {
            placeable.forEach { (p, origin) -> p.place(origin.first, origin.second) }
        }
    }
}

/** Greatroom Phase 6: one five-day row stays compact; multi-row planners keep taller day wells. */
private fun weekPlannerDayMinHeight(days: Int): Dp = when {
    days <= 2 -> 140.dp
    days <= 5 && !PanelConfig.IS_ENTRANCE -> 132.dp
    else -> 280.dp
}

@Composable
fun WeekPlanner(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val columns = (if (widget.columns == null) 5 else widget.columnCount()).coerceIn(1, 7)
    val dayCount = widget.days ?: 10
    val subscribed by viewModel.subscribedCalendars.collectAsState()
    val availableCalendars by viewModel.availableCalendars.collectAsState()
    val calendarRevision by viewModel.calendarEventsRevision.collectAsState()
    val sources = remember(subscribed, widget.calendars) { viewModel.plannerCalendars(widget.calendars) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf(listOf<HaCalendarEvent>()) }
    var forecasts by remember { mutableStateOf(listOf<Map<String, String>>()) }
    var loaded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogDate by remember { mutableStateOf(LocalDate.now(zone)) }
    var manageOverlay by remember { mutableStateOf<WeekPlannerManageOverlay?>(null) }
    val ui by viewModel.ui.collectAsState()
    val plannerCalendars = remember(sources, availableCalendars) {
        sources.mapNotNull { source ->
            val entity = source.entity ?: return@mapNotNull null
            val name = availableCalendars.firstOrNull { it.entityId == entity }?.name
                ?: entity.removePrefix("calendar.").replace('_', ' ')
            entity to name
        }
    }
    val weatherEntity = widget.weatherEntity ?: "weather.forecast_tankerland_ct"
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshCalendars() }
    LaunchedEffect(sources, dayOffset, calendarRevision, viewModel.client.currentBaseUrl) {
        loaded = false
        while (true) {
            if (viewModel.client.currentBaseUrl.isBlank()) {
                delay(400)
                continue
            }
            val startDay = LocalDate.now(zone).plusDays(dayOffset.toLong())
            val rangeStart = startDay.atStartOfDay(zone).toInstant()
            val rangeEnd = startDay.plusDays(dayCount.toLong()).atStartOfDay(zone).toInstant()
            val loadedEvents = sources.flatMap { source ->
                val entity = source.entity ?: return@flatMap emptyList()
                runCatching { viewModel.client.calendarEvents(entity, rangeStart, rangeEnd) }
                    .getOrDefault(emptyList())
                    .map { event -> event.copy(color = source.color, icon = source.icon, entityId = entity) }
            }
            events = if (widget.combineSimilar == true) {
                loadedEvents.distinctBy { Triple(it.start, it.end, it.summary.lowercase()) }
            } else {
                loadedEvents
            }
            if (widget.showCondition != false || widget.showTemperature == true) {
                val raw = runCatching { viewModel.client.weatherForecast(weatherEntity) }.getOrDefault(emptyList())
                forecasts = raw.map { obj ->
                    mapOf(
                        "condition" to (obj["condition"]?.let { primitiveContent(it) } ?: ""),
                        "temp" to (obj["temperature"]?.let { primitiveContent(it) } ?: ""),
                        "templow" to (obj["templow"]?.let { primitiveContent(it) } ?: ""),
                        "datetime" to (obj["datetime"]?.let { primitiveContent(it) } ?: ""),
                    )
                }
            }
            loaded = true
            delay(60_000)
        }
    }
    val today = now.atZone(zone).toLocalDate()
    val days = (0 until dayCount).map { today.plusDays(dayOffset.toLong() + it) }
    // Pre-index so the day columns don't each re-filter and re-sort the whole event list.
    val eventsByDay = remember(events, days, zone) {
        days.associateWith { day ->
            events.filter { eventOverlapsDay(it, day, zone) }
                .sortedWith(compareBy<HaCalendarEvent> { !it.allDay }.thenBy { it.start ?: Instant.EPOCH })
        }
    }
    val canCreateEvents = PanelConfig.ALLOW_CALENDAR_CREATE
    val openAddDialog: (LocalDate) -> Unit = { date ->
        addDialogDate = date
        showAddDialog = true
    }
    fun proceedAfterPin(event: HaCalendarEvent, action: CalendarManageAction) {
        manageOverlay = when (action) {
            CalendarManageAction.Edit -> WeekPlannerManageOverlay.Edit(event)
            CalendarManageAction.Delete -> WeekPlannerManageOverlay.DeleteConfirm(event)
        }
    }
    fun requestManageAction(event: HaCalendarEvent, action: CalendarManageAction) {
        if (event.uid.isNullOrBlank()) {
            manageOverlay = WeekPlannerManageOverlay.Message(
                title = "Not supported",
                message = "This event cannot be edited or deleted.",
            )
            return
        }
        if (!ui.pinIsUserSet) {
            manageOverlay = WeekPlannerManageOverlay.Message(
                title = "PIN required",
                message = "Set a PIN in Settings first.",
            )
            return
        }
        if (viewModel.isCalendarManagementUnlocked()) {
            proceedAfterPin(event, action)
        } else {
            manageOverlay = WeekPlannerManageOverlay.PinGate(event, action)
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (widget.showNavigation == true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CalendarNavIcon(left = true, onClick = { dayOffset -= dayCount })
                CalendarTodayIcon(onClick = { dayOffset = 0 })
                CalendarNavIcon(left = false, onClick = { dayOffset += dayCount })
                Text(
                    text = days.firstOrNull()?.format(MONTH_FORMAT) ?: "",
                    color = TextDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (canCreateEvents) {
                    CalendarAddIcon(onClick = { openAddDialog(today) })
                }
            }
        } else if (canCreateEvents && plannerCalendars.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                CalendarAddIcon(onClick = { openAddDialog(today) })
            }
        }
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                LoadingSpinner(color = TextDark, indicatorSize = 36.dp)
            }
        } else {
            days.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { day ->
                        WeekPlannerDay(
                            day = day,
                            today = today,
                            events = eventsByDay[day].orEmpty(),
                            forecast = forecasts.firstOrNull { it["datetime"].orEmpty().startsWith(day.toString()) },
                            showCondition = widget.showCondition != false,
                            showTemperature = widget.showTemperature == true,
                            showLowTemperature = widget.showLowTemperature == true,
                            loading = false,
                            viewModel = viewModel,
                            weatherEntity = weatherEntity,
                            onAddEvent = if (canCreateEvents && plannerCalendars.isNotEmpty()) {
                                { openAddDialog(day) }
                            } else {
                                null
                            },
                            onEventClick = { event ->
                                manageOverlay = WeekPlannerManageOverlay.ChooseAction(event)
                            },
                            onShowMore = { dayEvents ->
                                manageOverlay = WeekPlannerManageOverlay.DayEvents(day, dayEvents)
                            },
                            maxVisibleEvents = if (!PanelConfig.IS_ENTRANCE && dayCount <= 5) 2 else null,
                            now = now,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .heightIn(
                                    min = weekPlannerDayMinHeight(widget.days ?: 10),
                                    max = if (!PanelConfig.IS_ENTRANCE && dayCount <= 5) 200.dp else Dp.Unspecified,
                                ),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (showAddDialog && canCreateEvents) {
        AddCalendarEventDialog(
            viewModel = viewModel,
            calendars = plannerCalendars,
            initialDate = addDialogDate,
            onDismiss = { showAddDialog = false },
        )
    }
    when (val overlay = manageOverlay) {
        null -> Unit
        is WeekPlannerManageOverlay.ChooseAction -> CalendarEventActionDialog(
            event = overlay.event,
            onDismiss = { manageOverlay = null },
            onEdit = { requestManageAction(overlay.event, CalendarManageAction.Edit) },
            onDelete = { requestManageAction(overlay.event, CalendarManageAction.Delete) },
        )
        is WeekPlannerManageOverlay.PinGate -> PinGateDialog(
            viewModel = viewModel,
            onDismiss = { manageOverlay = WeekPlannerManageOverlay.ChooseAction(overlay.event) },
            onVerified = {
                proceedAfterPin(overlay.event, overlay.action)
            },
        )
        is WeekPlannerManageOverlay.Edit -> EditCalendarEventDialog(
            viewModel = viewModel,
            event = overlay.event,
            calendars = plannerCalendars,
            onDismiss = { manageOverlay = null },
        )
        is WeekPlannerManageOverlay.DeleteConfirm -> DeleteCalendarEventDialog(
            viewModel = viewModel,
            event = overlay.event,
            onDismiss = { manageOverlay = null },
            onDeleted = { manageOverlay = null },
        )
        is WeekPlannerManageOverlay.Message -> CalendarMessageDialog(
            title = overlay.title,
            message = overlay.message,
            onDismiss = { manageOverlay = null },
        )
        is WeekPlannerManageOverlay.DayEvents -> DayEventsDialog(
            day = overlay.day,
            events = overlay.events,
            onDismiss = { manageOverlay = null },
            onEventClick = { event ->
                manageOverlay = WeekPlannerManageOverlay.ChooseAction(event)
            },
        )
    }
}

private sealed interface WeekPlannerManageOverlay {
    data class ChooseAction(val event: HaCalendarEvent) : WeekPlannerManageOverlay
    data class PinGate(val event: HaCalendarEvent, val action: CalendarManageAction) : WeekPlannerManageOverlay
    data class Edit(val event: HaCalendarEvent) : WeekPlannerManageOverlay
    data class DeleteConfirm(val event: HaCalendarEvent) : WeekPlannerManageOverlay
    data class Message(val title: String, val message: String) : WeekPlannerManageOverlay
    data class DayEvents(val day: LocalDate, val events: List<HaCalendarEvent>) : WeekPlannerManageOverlay
}

@Composable
private fun DayEventsDialog(
    day: LocalDate,
    events: List<HaCalendarEvent>,
    onDismiss: () -> Unit,
    onEventClick: (HaCalendarEvent) -> Unit,
) {
    val title = day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    InWindowOverlay(
        onDismiss = onDismiss,
        dismissOnScrim = true,
        scrim = PopupScrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(CardLight)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextDark, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                events.forEach { event ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ScreenBackground)
                            .clickable { onEventClick(event) }
                            .padding(12.dp),
                    ) {
                        Text(eventTimeLabel(event), color = TextMuted, fontSize = 12.sp)
                        Text(event.summary, color = TextDark, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Text(
                "Close",
                color = TextDark,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun CalendarNavIcon(left: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp, 18.dp)) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
            val path = Path()
            if (left) {
                path.moveTo(size.width * 0.78f, 1.5.dp.toPx())
                path.lineTo(size.width * 0.18f, size.height / 2f)
                path.lineTo(size.width * 0.78f, size.height - 1.5.dp.toPx())
            } else {
                path.moveTo(size.width * 0.22f, 1.5.dp.toPx())
                path.lineTo(size.width * 0.82f, size.height / 2f)
                path.lineTo(size.width * 0.22f, size.height - 1.5.dp.toPx())
            }
            drawPath(path, TextDark, style = stroke)
        }
    }
}

@Composable
private fun CalendarTodayIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            drawRect(TextDark, size = Size(size.width, size.height))
        }
    }
}

@Composable
private fun CalendarAddIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            drawLine(TextMuted, Offset(centerX, 2.dp.toPx()), Offset(centerX, size.height - 2.dp.toPx()), strokeWidth = stroke.width, cap = stroke.cap)
            drawLine(TextMuted, Offset(2.dp.toPx(), centerY), Offset(size.width - 2.dp.toPx(), centerY), strokeWidth = stroke.width, cap = stroke.cap)
        }
    }
}

@Composable
private fun WeekPlannerDay(
    day: LocalDate,
    today: LocalDate,
    events: List<HaCalendarEvent>,
    forecast: Map<String, String>?,
    showCondition: Boolean,
    showTemperature: Boolean,
    showLowTemperature: Boolean,
    loading: Boolean,
    viewModel: HaViewModel,
    weatherEntity: String,
    onAddEvent: (() -> Unit)? = null,
    onEventClick: ((HaCalendarEvent) -> Unit)? = null,
    onShowMore: ((List<HaCalendarEvent>) -> Unit)? = null,
    maxVisibleEvents: Int? = null,
    now: Instant = Instant.now(),
    modifier: Modifier = Modifier,
) {
    val weekday = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(WEEKDAY_FORMAT)
    }
    val high = forecastC(forecast?.get("temp"))
    val low = forecastC(forecast?.get("templow"))
    val temp = buildString {
        if (showTemperature && !high.isNullOrBlank()) append(high)
        if (showTemperature && showLowTemperature && !low.isNullOrBlank()) {
            if (isNotEmpty()) append(" / ")
            append(low)
        }
    }
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                day.dayOfMonth.toString(),
                color = TextDark,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                weekday,
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(top = 10.dp).weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                if (showCondition || showTemperature) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable(
                            enabled = showCondition,
                            onClick = { viewModel.openWeatherPopup(focusDate = day, entityId = weatherEntity) },
                        ),
                    ) {
                        if (loading && forecast == null) {
                            LoadingSpinner(indicatorSize = 18.dp)
                        } else {
                            if (temp.isNotBlank()) {
                                Text(temp, color = TextMuted, fontSize = 11.sp, maxLines = 1)
                            }
                            if (showCondition) {
                                MdiIcon(
                                    weatherIcon(forecast?.get("condition"), true),
                                    tint = weatherTint(forecast?.get("condition")),
                                    size = 22.dp,
                                )
                            }
                        }
                    }
                }
                if (onAddEvent != null) {
                    CalendarAddIcon(onClick = onAddEvent)
                }
            }
        }
        when {
            loading && events.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardLight)
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                LoadingSpinner(indicatorSize = 18.dp)
            }
            events.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardLight)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Text("No events", color = TextMuted, fontSize = 13.sp)
            }
            else -> {
                val visibleLimit = maxVisibleEvents?.coerceAtLeast(0)
                val visible = if (visibleLimit == null) events else events.take(visibleLimit)
                val overflow = if (visibleLimit == null) 0 else (events.size - visible.size).coerceAtLeast(0)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.forEach { event ->
                        val isPast = isPastCalendarEvent(event, day, today, now)
                        val stripe = accentColor(event.color?.removePrefix("var(--")?.removeSuffix(")"))
                            .takeIf { event.color != null } ?: AccentBlue
                        val stripeColor = if (isPast) stripe.copy(alpha = 0.35f) else stripe
                        val cardBackground = if (isPast) CardLight.copy(alpha = 0.55f) else CardLight
                        val timeColor = if (isPast) TextMuted.copy(alpha = 0.45f) else TextMuted
                        val summaryColor = if (isPast) TextDark.copy(alpha = 0.45f) else TextDark
                        val summaryWeight = if (isPast) FontWeight.Normal else FontWeight.Medium
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cardBackground)
                                .clickable(enabled = onEventClick != null) {
                                    onEventClick?.invoke(event)
                                },
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(stripeColor))
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).weight(1f)) {
                                Text(
                                    text = eventTimeLabel(event),
                                    color = timeColor,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    text = event.summary,
                                    color = summaryColor,
                                    fontSize = 14.sp,
                                    fontWeight = summaryWeight,
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                    if (overflow > 0) {
                        Text(
                            "+$overflow more",
                            color = TextDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onShowMore?.invoke(events) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VisionTimeline(
    widget: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    /** When set, caps how many events are shown (Phase 6 camera-priority preview). */
    maxEvents: Int? = null,
    showTitle: Boolean = true,
) {
    val timelineRevision by viewModel.visionTimelineRevision.collectAsState()
    val limit = (maxEvents ?: widget.numberOfEvents ?: 5).coerceAtLeast(0)
    val hours = widget.numberOfHours ?: widget.hours
    val days = widget.days
    val entityId = widget.entity ?: "calendar.llm_vision_timeline"
    var events by remember { mutableStateOf(listOf<HaCalendarEvent>()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(entityId, limit, hours, days) {
        loaded = false
    }
    LaunchedEffect(entityId, limit, hours, days, timelineRevision, viewModel.client.currentBaseUrl) {
        if (limit == 0) {
            events = emptyList()
            loaded = true
            return@LaunchedEffect
        }
        while (true) {
            if (viewModel.client.currentBaseUrl.isBlank()) {
                delay(400)
                continue
            }
            val fetched = runCatching {
                viewModel.client.llmVisionEvents(entityId, limit, hours, days)
            }.getOrDefault(emptyList())
            events = fetched.sortedByDescending { it.start ?: Instant.EPOCH }.take(limit)
            loaded = true
            delay(15_000)
        }
    }
    if (limit == 0) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) {
            Text(
                text = widget.name.takeUnless { it.isNullOrBlank() } ?: "This happened around the house",
                color = TextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        when {
            !loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(minOf(4, limit.coerceAtLeast(1))) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(75.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardLight),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == 0) {
                            LoadingSpinner(color = TextDark, indicatorSize = 28.dp)
                        }
                    }
                }
            }
            events.isEmpty() -> Text(
                text = if (hours != null) "No events in the last $hours hours" else "No events",
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> events.groupBy { event ->
                event.start?.atZone(ZoneId.systemDefault())?.toLocalDate()
            }.forEach { (date, dayEvents) ->
                date?.let {
                    Text(visionDateLabel(it), color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                dayEvents.forEach { event ->
                    val start = event.start?.atZone(ZoneId.systemDefault())
                    // One-shot read: camera friendly names don't change between the 15 s
                    // refreshes, so this doesn't need a state subscription.
                    val cameraLabel = event.cameraName?.let { id ->
                        viewModel.entity(id)?.friendlyName ?: id.substringAfter('.').replace('_', ' ')
                    }
                    val timeLabel = start?.format(TIME_FORMAT).orEmpty()
                    val subtitle = listOfNotNull(
                        timeLabel.takeIf { it.isNotBlank() },
                        cameraLabel?.takeIf { it.isNotBlank() && it != "clip" },
                    ).joinToString(" • ")
                    val style = timelineEventStyle(event)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(75.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardLight)
                            .clickable {
                                val frame = event.keyFrame
                                val clip = event.clipPath
                                val snapshot = timelineSnapshotPath(frame, clip)
                                when {
                                    !clip.isNullOrBlank() -> viewModel.openVideo(
                                        path = clip,
                                        title = event.summary,
                                        subtitle = subtitle.takeIf { it.isNotBlank() },
                                        description = event.description,
                                        previewPath = snapshot,
                                    )
                                    !frame.isNullOrBlank() -> viewModel.openMedia(
                                        path = frame,
                                        title = event.summary,
                                        subtitle = subtitle.takeIf { it.isNotBlank() },
                                        description = event.description,
                                        previewPath = snapshot,
                                    )
                                    else -> {
                                        val camera = event.cameraName?.takeIf { '.' in it }
                                        viewModel.openMoreInfo(camera ?: event.entityId)
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(style.second.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            MdiIcon(style.first, tint = style.second, size = 20.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                event.summary,
                                color = TextDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        timelineSnapshotPath(event.keyFrame, event.clipPath)?.let { snapshotPath ->
                            TimelineSnapshot(
                                path = snapshotPath,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .size(59.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineSnapshot(path: String?, viewModel: HaViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    // `media-source://` paths need a websocket resolve first; only the resulting URL goes to Coil.
    var url by remember(path) { mutableStateOf<String?>(null) }
    var resolved by remember(path) { mutableStateOf(path.isNullOrBlank()) }
    LaunchedEffect(path, viewModel.client.currentBaseUrl) {
        if (!path.isNullOrBlank()) {
            url = runCatching { viewModel.client.authenticatedMediaUrl(path) }.getOrNull()
        }
        resolved = true
    }
    val placeholder = CardLight.copy(alpha = 0.12f)
    val model = url
    when {
        model != null -> SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
            contentDescription = null,
            imageLoader = loader,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                is AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize().background(placeholder))
                else -> Box(
                    modifier = Modifier.fillMaxSize().background(placeholder),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(indicatorSize = 16.dp)
                }
            }
        }
        !resolved -> Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
            LoadingSpinner(indicatorSize = 16.dp)
        }
        else -> Box(modifier.background(placeholder))
    }
}

@Composable
fun MediaImageDialog(preview: MediaPreview, viewModel: HaViewModel, onDismiss: () -> Unit) {
    var bytes by remember(preview.path) { mutableStateOf<ByteArray?>(null) }
    var loaded by remember(preview.path) { mutableStateOf(false) }
    LaunchedEffect(preview.path, viewModel.client.currentBaseUrl) {
        bytes = runCatching { viewModel.client.mediaBytes(preview.path) }.getOrNull()
            ?: runCatching { viewModel.client.cameraSnapshot(preview.path) }.getOrNull()
        loaded = true
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(
        modifier = popupSheetModifier(PopupSheetKind.Camera)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .popupSheetLook(OverlayLightPopup.sheet)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PopupSheetChrome(
                title = preview.title.orEmpty().ifBlank { "Photo" },
                onClose = onDismiss,
                overlay = OverlayLightPopup,
                subtitle = preview.subtitle,
            )
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = preview.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp)),
                    contentScale = ContentScale.Fit,
                )
                !loaded -> Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(color = TextMuted)
                }
                else -> Text("Can't load image", color = OverlayLightPopup.muted, fontSize = 14.sp)
            }
            if (!preview.description.isNullOrBlank()) {
                Text(
                    text = preview.description,
                    color = OverlayLightPopup.text,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaVideoDialog(preview: MediaPreview, viewModel: HaViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = rememberHaImageLoader(viewModel.client)
    var videoUrl by remember(preview.path) { mutableStateOf<String?>(null) }
    var loadFailed by remember(preview.path) { mutableStateOf(false) }

    val snapshotPath = preview.previewPath
    var snapshotUrl by remember(snapshotPath) { mutableStateOf<String?>(null) }
    var snapshotResolved by remember(snapshotPath) { mutableStateOf(snapshotPath.isNullOrBlank()) }

    LaunchedEffect(preview.path, viewModel.client.currentBaseUrl) {
        loadFailed = false
        videoUrl = runCatching { viewModel.client.authenticatedMediaUrl(preview.path) }
            .getOrNull()
            ?.takeIf { NetworkGuard.hostOf(it)?.let(NetworkGuard::isPrivateHost) == true }
        if (videoUrl.isNullOrBlank()) loadFailed = true
    }

    LaunchedEffect(snapshotPath, viewModel.client.currentBaseUrl) {
        if (!snapshotPath.isNullOrBlank()) {
            snapshotUrl = runCatching { viewModel.client.authenticatedMediaUrl(snapshotPath) }.getOrNull()
        }
        snapshotResolved = true
    }

    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }

    val exoPlayer = remember(videoUrl) {
        val url = videoUrl ?: return@remember null
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(viewModel.client.bearerHeaders())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }
        }
        exoPlayer.addListener(listener)
        val currentSize = exoPlayer.videoSize
        if (currentSize.width > 0 && currentSize.height > 0) {
            videoAspectRatio = currentSize.width.toFloat() / currentSize.height.toFloat()
        }
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val playerAspect = videoAspectRatio?.coerceIn(0.6f, 2.4f) ?: (16f / 9f)

    Box(
        modifier = popupSheetModifier(PopupSheetKind.Camera)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .popupSheetLook(OverlayLightPopup.sheet)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PopupSheetChrome(
                title = preview.title.orEmpty().ifBlank { "Clip" },
                onClose = onDismiss,
                overlay = OverlayLightPopup,
                subtitle = preview.subtitle,
            )

            MediaVideoPlayerSurface(
                exoPlayer = exoPlayer,
                loadFailed = loadFailed,
                snapshotUrl = snapshotUrl,
                imageLoader = imageLoader,
                aspectRatio = playerAspect,
            )

            if (!snapshotUrl.isNullOrBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Detection image",
                        color = OverlayLightPopup.muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(snapshotUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Event detection preview",
                        imageLoader = imageLoader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 280.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.06f)),
                        contentScale = ContentScale.Fit,
                    ) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                            is AsyncImagePainter.State.Error -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.Black.copy(alpha = 0.04f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Can't load detection image", color = OverlayLightPopup.muted, fontSize = 12.sp)
                            }
                            else -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.Black.copy(alpha = 0.04f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingSpinner(indicatorSize = 20.dp)
                            }
                        }
                    }
                }
            } else if (!snapshotPath.isNullOrBlank() && !snapshotResolved) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(color = TextMuted, indicatorSize = 22.dp)
                }
            }

            if (!preview.description.isNullOrBlank()) {
                Text(
                    text = preview.description,
                    color = OverlayLightPopup.text,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaVideoPlayerSurface(
    exoPlayer: ExoPlayer?,
    loadFailed: Boolean,
    snapshotUrl: String?,
    imageLoader: ImageLoader,
    aspectRatio: Float,
) {
    when {
        exoPlayer != null -> AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.camera_player_view, null) as PlayerView).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = true
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.useController = true
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black),
        )
        loadFailed -> Text("Can't load video", color = OverlayLightPopup.muted, fontSize = 14.sp)
        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!snapshotUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(snapshotUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            LoadingSpinner(color = TextMuted)
        }
    }
}

private fun visionDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(MONTH_DAY_FORMAT)
    }
}

private fun eventOverlapsDay(event: HaCalendarEvent, day: LocalDate, zone: ZoneId): Boolean {
    if (event.allDay || event.startDate != null) {
        val start = event.startDate ?: return false
        val endExclusive = event.endDate ?: start.plusDays(1)
        return !day.isBefore(start) && day.isBefore(endExclusive.coerceAtLeast(start.plusDays(1)))
    }
    val startInstant = event.start ?: return false
    val start = startInstant.atZone(zone).toLocalDate()
    val endInstant = event.end ?: startInstant
    val end = endInstant.atZone(zone).toLocalDate()
    val endInclusive = if (endInstant.atZone(zone).toLocalTime() == java.time.LocalTime.MIDNIGHT && end.isAfter(start)) {
        end.minusDays(1)
    } else {
        end
    }
    return !day.isBefore(start) && !day.isAfter(endInclusive)
}

internal fun isPastCalendarEvent(
    event: HaCalendarEvent,
    day: LocalDate,
    today: LocalDate,
    now: Instant = Instant.now(),
): Boolean {
    if (day.isBefore(today)) return true
    if (day.isAfter(today)) return false
    if (event.allDay || (event.startDate != null && event.start == null)) {
        return false
    }
    val endInstant = event.end ?: event.start ?: return false
    return now.isAfter(endInstant)
}

private fun forecastC(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val number = raw.trim('"').toDoubleOrNull()
    return if (number != null) "${number.roundToInt()} °C" else raw
}

private fun eventTimeLabel(event: HaCalendarEvent): String {
    if (event.allDay || (event.startDate != null && event.start == null)) return "Entire day"
    val zone = ZoneId.systemDefault()
    val start = event.start?.atZone(zone)?.format(TIME_FORMAT) ?: return ""
    val end = event.end?.atZone(zone)?.format(TIME_FORMAT) ?: return start
    return if (end == start) start else "$start - $end"
}

private fun primitiveContent(element: JsonElement): String {
    val primitive = element as? JsonPrimitive ?: return element.toString().trim('"')
    return primitive.contentOrNull ?: primitive.toString().trim('"')
}

@Composable
fun LightSlider(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(listOf(widget.entity)).collectAsState()
    val entity = states[widget.entity]
    val on = entity?.state == "on"
    val pct = states.brightnessPct(widget.entity)
    var lastOnPct by remember(widget.entity) { mutableIntStateOf(if (pct > 0) pct else 100) }
    var sliding by remember { mutableFloatStateOf(pct.toFloat()) }
    var isSliding by remember { mutableStateOf(false) }
    LaunchedEffect(pct, on) {
        if (on && pct > 0) lastOnPct = pct
        if (!isSliding) sliding = if (on) (if (pct > 0) pct else lastOnPct).toFloat() else 0f
    }
    val restFill = if (on) (if (pct > 0) pct else lastOnPct) / 100f else 0f
    val animatedFill by animateFloatAsState(restFill, label = "light")
    val fill = if (isSliding) sliding / 100f else animatedFill
    val shownOn = isSliding || on
    Box(
        modifier = modifier
            .height(75.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(overlay.well)
            .pointerInput(widget.entity) {
                val entityId = widget.entity ?: return@pointerInput
                val holdTimeMs = 600L
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    var dragging = false
                    var longPressed = false
                    var current = down
                    while (current.pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging && !longPressed) {
                            val dist = hypot(dx, dy)
                            if (dist > touchSlop) {
                                if (abs(dx) > abs(dy)) {
                                    dragging = true
                                    isSliding = true
                                    change.consume()
                                    sliding = (change.position.x / size.width.toFloat() * 100f).coerceIn(0f, 100f)
                                } else {
                                    return@awaitEachGesture
                                }
                            } else if (change.pressed && System.currentTimeMillis() - downTime >= holdTimeMs) {
                                longPressed = true
                                change.consume()
                                viewModel.onHold(widget)
                            }
                        } else if (dragging) {
                            change.consume()
                            sliding = (change.position.x / size.width.toFloat() * 100f).coerceIn(0f, 100f)
                        } else {
                            change.consume()
                        }
                        current = change
                    }
                    if (dragging) {
                        val value = sliding.roundToInt()
                        if (value > 0) lastOnPct = value
                        viewModel.setBrightness(entityId, value)
                        isSliding = false
                    } else if (!longPressed) {
                        viewModel.onTap(widget)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(if (shownOn) ActiveLight else Color.Transparent),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (shownOn) Color.Black else overlay.text
            MdiIcon(widget.icon, tint = tint, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(widget.name.orEmpty(), color = if (shownOn) Color.Black else overlay.muted, fontSize = 14.sp)
                val label = if (shownOn) "${(if (isSliding) sliding.roundToInt() else if (pct > 0) pct else lastOnPct)}%" else "Off"
                Text(label, color = if (shownOn) Color.Black else overlay.text, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ToggleRow(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val entity by viewModel.entityFlow(widget.entity).collectAsState()
    val on = isOn(entity?.state)
    val label = entity?.state?.replaceFirstChar { it.uppercase() } ?: widget.label ?: "Unknown"
    Row(
        modifier = modifier
            .height(75.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) ActiveLight else overlay.well)
            .widgetClicks(widget, viewModel)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdiIcon(widget.icon ?: "mdi:power", tint = if (on) Color.Black else overlay.text, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(widget.name ?: entity?.friendlyName.orEmpty(), color = if (on) Color.Black else overlay.muted, fontSize = 14.sp)
            Text(label, color = if (on) Color.Black else overlay.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
    }
}

@Composable
fun VentRow(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier, ids: List<String>) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(ids).collectAsState()
    val open = ids.any { states[it]?.state in setOf("open", "opening") }
    val label = when {
        open -> "Open"
        ids.all { states[it]?.state == "closed" } -> "Closed"
        else -> "Unknown"
    }
    val tint = if (open) Color.Black else overlay.text
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (open) ActiveLight else overlay.well)
            .combinedClickable(
                onClick = {
                    if (ids.size > 1) viewModel.tiltGroup(ids) else viewModel.onTap(widget)
                },
                onLongClick = if (widget.hold != null && widget.hold.type != "none") {
                    { viewModel.onHold(widget) }
                } else {
                    null
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdiIcon(widget.icon ?: "mdi:air-filter", tint = tint, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(widget.name ?: "Vents", color = if (open) Color.Black.copy(alpha = 0.7f) else overlay.muted, fontSize = 12.sp)
            Text(label, color = tint, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    if (title.isBlank()) return
    val overlay = LocalOverlay.current
    Text(
        text = title,
        color = overlay.text,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun ClimateCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(listOf(widget.entity, widget.activityEntity)).collectAsState()
    val climate = states[widget.entity]
    val current = climate?.attrDouble("current_temperature")
    val target = climate?.attrDouble("temperature") ?: climate?.attrDouble("target_temp_high")
    val activity = states[widget.activityEntity]
    val isHeating = climate?.state == "heat"
    val isCooling = climate?.state == "cool"
    val isAuto = climate?.state in setOf("heat_cool", "auto")
    val activityActive = activity?.state.equals("Active", ignoreCase = true)

    val modes = remember(climate?.attributes) {
        val list = climate?.attrStringList("hvac_modes")
        if (!list.isNullOrEmpty()) list else listOf("off", "cool", "heat", "heat_cool")
    }

    Column(
        modifier = modifier
            .clip(CardShape)
            .background(overlay.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                widget.name ?: "Climate",
                color = overlay.text,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (activity != null && !widget.activityEntity.isNullOrBlank()) {
                val actText = if (activityActive) "Active" else "Inactive"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activityActive) ActiveLight else overlay.well)
                        .clickable {
                            val next = if (activityActive) "Inactive" else "Active"
                            viewModel.setSelectOption(widget.activityEntity, next)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        actText,
                        color = if (activityActive) Color.Black else overlay.muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            MdiIcon(
                when {
                    isHeating -> "mdi:fire"
                    isCooling -> "mdi:snowflake"
                    isAuto -> "mdi:autorenew"
                    else -> "mdi:thermostat"
                },
                tint = when {
                    isHeating -> AccentRed
                    isCooling -> AccentBlue
                    else -> overlay.muted
                },
                size = 22.dp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${current.format(1, "°")}  →  ${target.format(1, "°")}",
                color = overlay.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(overlay.well)
                        .clickable {
                            target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it - 0.5) } }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("–", color = overlay.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    target.format(1, "°"),
                    color = overlay.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(overlay.well)
                        .clickable {
                            target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it + 0.5) } }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = overlay.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            modes.forEach { mode ->
                val isSelected = climate?.state.equals(mode, ignoreCase = true)
                val label = when (mode.lowercase()) {
                    "heat_cool" -> "Auto"
                    else -> mode.replaceFirstChar { it.uppercase() }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isSelected && mode == "cool" -> AccentBlue.copy(alpha = 0.25f)
                                isSelected && mode == "heat" -> AccentRed.copy(alpha = 0.25f)
                                isSelected -> ActiveLight
                                else -> overlay.well
                            }
                        )
                        .clickable {
                            widget.entity?.let { id -> viewModel.setHvacMode(id, mode) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = when {
                            isSelected && mode == "cool" -> AccentBlue
                            isSelected && mode == "heat" -> AccentRed
                            isSelected -> Color.Black
                            else -> overlay.muted
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
fun RoomConditions(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val display = widget.display
    val entityId = widget.entity ?: display?.tempEntity
    val watchedEntities = remember(widget, entityId, display) {
        val deducedHum = if (entityId?.endsWith("_temperature") == true) {
            entityId.replace(Regex("_temperature$"), "_humidity")
        } else null
        (display.entityIds() + listOfNotNull(entityId, deducedHum)).filterNotNull().distinct()
    }
    val states by viewModel.entitiesFlow(watchedEntities).collectAsState()
    val entityState = entityId?.let { states[it] }

    val tempVal = states.roomTemp(display, entityId)
    val humVal = states.roomHum(display, entityId)

    var points by remember(entityId) { mutableStateOf(listOf<Pair<Long, Double>>()) }

    suspend fun refreshHistory() {
        if (entityId.isNullOrBlank()) return
        val fresh = runCatching { viewModel.client.history(entityId, 12) }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            points = fresh
        }
    }

    LaunchedEffect(entityId, viewModel.client.currentBaseUrl) {
        while (true) {
            refreshHistory()
            delay(30_000L)
        }
    }

    LaunchedEffect(entityState?.state, entityState?.lastChanged) {
        if (entityState == null || entityId.isNullOrBlank()) return@LaunchedEffect
        delay(1_000L)
        refreshHistory()
    }

    val plottedPoints = remember(points, entityState?.state, entityState?.lastChanged) {
        withLivePoint(points, entityState, System.currentTimeMillis())
    }

    Box(
        modifier = modifier
            .height(140.dp)
            .clip(CardShape)
            .background(overlay.card)
            .widgetClicks(widget, viewModel)
            .padding(20.dp),
    ) {
        Sparkline(
            plottedPoints,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp),
            AccentRed.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (tempVal != null) tempVal.format(1, "°") else "—",
                color = overlay.text,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 44.sp,
            )
            if (humVal != null) {
                Text(
                    text = humVal.format(0, "%"),
                    color = overlay.text.copy(alpha = 0.65f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

private fun withLivePoint(
    points: List<Pair<Long, Double>>,
    entity: EntityState?,
    nowMs: Long,
): List<Pair<Long, Double>> {
    val currentVal = entity?.state?.toDoubleOrNull() ?: return points
    if (points.isEmpty()) {
        val startMs = nowMs - 12L * 3600_000L
        return listOf(startMs to currentVal, nowMs to currentVal)
    }
    val out = ArrayList<Pair<Long, Double>>(points.size + 2)
    out.addAll(points)
    val last = points.last()
    val changeMs = entity.lastChanged?.toEpochMilli() ?: nowMs
    if (changeMs > last.first && changeMs < nowMs) {
        out += changeMs to currentVal
    }
    if (nowMs > last.first) {
        out += nowMs to currentVal
    }
    return out
}

@Composable
fun SensorCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(listOf(widget.entity, widget.state?.entity)).collectAsState()
    val entityId = widget.entity ?: widget.state?.entity
    val value = when {
        widget.state != null -> states.formatState(widget.state)
        entityId != null -> {
            val raw = states[entityId]?.state?.toDoubleOrNull()
            val unit = states[entityId]?.attrString("unit_of_measurement").orEmpty().ifBlank { "W" }
            if (raw != null) String.format("%.2f %s", raw, unit) else states[entityId]?.state ?: "—"
        }
        widget.label != null && "[[[" !in widget.label -> widget.label
        else -> "—"
    }
    Box(
        modifier = modifier
            .height(if (widget.type == "sensor_small") 66.dp else 160.dp)
            .clip(if (widget.type == "sensor_small") RoundedCornerShape(40.dp) else CardShape)
            .background(overlay.card)
            .widgetClicks(widget.copy(entity = entityId ?: widget.entity), viewModel)
            .padding(16.dp),
    ) {
        MdiIcon(widget.icon, tint = overlay.muted, size = 22.dp, modifier = Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            SensorValueText(
                value = value,
                color = overlay.text,
                size = if (widget.type == "sensor_small") 16.sp else 32.sp,
            )
            Text(widget.name ?: states[entityId]?.friendlyName.orEmpty(), color = overlay.muted, fontSize = 14.sp)
        }
    }
}

@Composable
fun ButtonToggle(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val entity by viewModel.entityFlow(widget.entity).collectAsState()
    val on = entity?.state == "on"
    Box(
        modifier = modifier
            .height(if (widget.type == "button_toggle_small") 66.dp else 160.dp)
            .clip(if (widget.type == "button_toggle_small") RoundedCornerShape(40.dp) else CardShape)
            .background(if (on) ActiveYellow else overlay.card)
            .widgetClicks(widget, viewModel)
            .padding(16.dp),
    ) {
        MdiIcon(widget.icon, tint = if (on) Color.Black else overlay.text, size = 22.dp, modifier = Modifier.align(Alignment.TopEnd))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(if (on) "On" else "Off", color = if (on) Color.Black else overlay.text, fontSize = 32.sp, fontWeight = FontWeight.Light)
            Text(widget.name.orEmpty(), color = if (on) Color.Black.copy(alpha = 0.7f) else overlay.muted, fontSize = 14.sp)
        }
    }
}

@Composable
fun ActionChip(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(ChipDark)
            .widgetClicks(widget, viewModel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(widget.icon, tint = ChipOnDark, size = 18.dp)
        Text(widget.name.orEmpty(), color = ChipOnDark, fontSize = 14.sp)
    }
}

@Composable
fun VacuumButton(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val entity by viewModel.entityFlow(widget.entity).collectAsState()
    val on = isOn(entity?.state)
    val stop = widget.name.equals("Stop", ignoreCase = true)
    val start = widget.name.equals("Start", ignoreCase = true)
    val accented = start || stop || on
    val background = when {
        start -> VacuumStart
        stop -> VacuumStop
        on -> ActiveYellow
        else -> overlay.card
    }
    val tint = if (accented) Color.Black else overlay.text
    Column(
        modifier = modifier
            .height(120.dp)
            .clip(CardShape)
            .background(background)
            .widgetClicks(widget, viewModel)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        MdiIcon(widget.icon ?: "mdi:vacuum", tint = tint, size = 24.dp)
        Text(widget.name ?: entity?.friendlyName.orEmpty(), color = tint, fontSize = 14.sp)
    }
}

@Composable
fun MediaCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val companionEntity = widget.companionEntity ?: "media_player.living_room_appletv"
    val states by viewModel.entitiesFlow(listOf(widget.entity, companionEntity)).collectAsState()
    val tv = states[widget.entity]
    val apple = states[companionEntity]
    val playing = apple?.state in setOf("playing", "paused")
    val on = tv?.state == "on" || playing
    val tint = if (on) Color.Black else overlay.text
    Row(
        modifier = modifier
            .height(140.dp)
            .clip(CardShape)
            .background(if (on) ActiveYellow else overlay.card)
            .widgetClicks(widget, viewModel)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing && apple?.entityPicture != null) {
            EntityPicture(apple.entityPicture, viewModel, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(16.dp))
        } else {
            MdiIcon(widget.icon ?: "mdi:television-classic", tint = tint, size = 48.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(apple?.attrString("app_name") ?: widget.name ?: "TV", color = tint, fontSize = 18.sp)
            Text(
                apple?.attrString("media_title") ?: apple?.state?.replaceFirstChar { it.uppercase() } ?: "Off",
                color = if (on) Color.Black.copy(alpha = 0.7f) else overlay.muted,
            )
        }
    }
}

@Composable
fun HistoryChart(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val entity = widget.entity ?: widget.graphEntity ?: widget.series.firstOrNull()?.entity ?: when (widget.type) {
        "energy_solar_graph" -> "sensor.envoy_202234122877_current_power_production"
        "energy_usage_graph" -> "sensor.envoy_202234122877_current_net_power_consumption"
        else -> null
    }
    var points by remember(entity) { mutableStateOf(listOf<Pair<Long, Double>>()) }
    LaunchedEffect(entity, viewModel.client.currentBaseUrl) {
        while (true) {
            entity?.let {
                val fresh = runCatching { viewModel.client.history(it, 24) }.getOrNull()
                if (!fresh.isNullOrEmpty()) points = fresh
            }
            delay(45_000L)
        }
    }
    Column(
        modifier = modifier
            .height(220.dp)
            .clip(CardShape)
            .background(overlay.card)
            .clickable { entity?.let { viewModel.openMoreInfo(it) } }
            .padding(16.dp),
    ) {
        Text(widget.name ?: widget.series.firstOrNull()?.name ?: "kWh", color = overlay.muted, fontSize = 14.sp)
        Sparkline(points, Modifier.fillMaxSize(), HistoryGraph)
    }
}

@Composable
fun BatteryRuntimePanel(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(BatteryRuntimeEntities).collectAsState()
    val discharging = states["binary_sensor.envoy_battery_discharging"]?.state == "on"
    val runtime = states.formatState(
        StateFormat(kind = "text", entity = "sensor.battery_runtime_remaining"),
    )
    val load = states.number("sensor.housepanel_total_consumption_house_consumption_1h_mean", 0, " W")
    val stored = states.number("input_number.battery_energy_helper", 2, " kWh", 0.001)
    val reserve = states.number("sensor.envoy_202234122877_reserve_battery_energy", 0, " Wh")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(overlay.card)
            .clickable { viewModel.openMoreInfo("sensor.battery_runtime_remaining") }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MdiIcon("mdi:battery-charging", tint = overlay.muted, size = 24.dp)
            Text("Battery", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!discharging) {
            Text("Not discharging right now.", color = overlay.muted, fontSize = 14.sp)
            return@Column
        }
        Text(
            text = runtime.ifBlank { "—" },
            color = overlay.text,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        Text("Estimated runtime at current load", color = overlay.muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeStatTile("Load (1h avg)", load, Modifier.weight(1f))
            RuntimeStatTile("Stored", stored, Modifier.weight(1f))
            RuntimeStatTile("Reserve", reserve, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RuntimeStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(overlay.well)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, color = overlay.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(label, color = overlay.muted, fontSize = 11.sp)
    }
}

private data class MmWaveTarget(val index: Int, val x: Int, val y: Int, val z: Int)

private data class MmWaveBounds(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
    val aspectRatio: Float
        get() {
            val w = (xMax - xMin).toFloat().coerceAtLeast(1f)
            val h = (yMax - yMin).toFloat().coerceAtLeast(1f)
            return (w / h).coerceIn(0.75f, 2.5f)
        }
}

private val MmWaveTargetColors = listOf(AccentBlue, AccentRed, ActiveYellow, Color(0xFF43A047))

private fun Map<String, EntityState>.mmWaveInt(entityId: String): Int =
    this[entityId]?.state?.toDoubleOrNullSafe()?.roundToInt() ?: 0

private fun Map<String, EntityState>.mmWaveBounds(): MmWaveBounds {
    val xMin = mmWaveInt("number.secondary_living_room_switch_mmwave_width_minimum_left").takeIf { it != 0 } ?: -600
    val xMax = mmWaveInt("number.secondary_living_room_switch_mmwave_width_maximum_right").takeIf { it != 0 } ?: 600
    val yMin = mmWaveInt("number.secondary_living_room_switch_mmwave_depth_minimum_near")
    val yMax = mmWaveInt("number.secondary_living_room_switch_mmwave_depth_maximum_far").takeIf { it > 0 } ?: 600
    return MmWaveBounds(
        xMin = minOf(xMin, xMax),
        xMax = maxOf(xMin, xMax),
        yMin = minOf(yMin, yMax),
        yMax = maxOf(yMin, yMax),
    )
}

private fun MmWaveBounds.toFraction(x: Int, y: Int): Pair<Float, Float> {
    val xSpan = (xMax - xMin).toFloat().coerceAtLeast(1f)
    val ySpan = (yMax - yMin).toFloat().coerceAtLeast(1f)
    val fx = ((x - xMin) / xSpan).coerceIn(0f, 1f)
    val fy = (1f - (y - yMin) / ySpan).coerceIn(0f, 1f)
    return fx to fy
}

@Composable
private fun MmWaveZoneMap(
    bounds: MmWaveBounds,
    targets: List<MmWaveTarget>,
    overlay: OverlayColors,
    modifier: Modifier = Modifier,
) {
    val plotted = targets.filter { it.x != 0 || it.y != 0 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bounds.aspectRatio)
                .clip(RoundedCornerShape(20.dp))
                .background(overlay.well)
                .border(1.dp, overlay.muted.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
        ) {
            val pad = 14.dp
            val mapWidth = maxWidth - pad * 2
            val mapHeight = maxHeight - pad * 2
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                drawRoundRect(
                    color = overlay.card.copy(alpha = 0.65f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Fill,
                )
                drawRoundRect(
                    color = overlay.muted.copy(alpha = 0.35f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                val (sensorX, sensorY) = bounds.toFraction(0, bounds.yMin)
                drawCircle(
                    color = overlay.muted.copy(alpha = 0.8f),
                    radius = 5.dp.toPx(),
                    center = Offset(sensorX * size.width, sensorY * size.height),
                )
                plotted.forEach { target ->
                    val (fx, fy) = bounds.toFraction(target.x, target.y)
                    val color = MmWaveTargetColors[(target.index - 1) % MmWaveTargetColors.size]
                    val center = Offset(fx * size.width, fy * size.height)
                    drawCircle(color = color.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = center)
                    drawCircle(color = color, radius = 7.dp.toPx(), center = center)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 7.dp.toPx(),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            plotted.forEach { target ->
                val (fx, fy) = bounds.toFraction(target.x, target.y)
                Text(
                    text = target.index.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = pad + mapWidth * fx - 5.dp,
                            y = pad + mapHeight * fy - 7.dp,
                        ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← Left", color = overlay.muted, fontSize = 11.sp)
            Text("Right →", color = overlay.muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Near (sensor)", color = overlay.muted, fontSize = 11.sp)
            Text("Far", color = overlay.muted, fontSize = 11.sp)
        }
        if (plotted.isEmpty() && targets.isNotEmpty()) {
            Text("Positions will appear on the map when target reports arrive.", color = overlay.muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun MmWaveTargetsPanel(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(MmWaveEntities).collectAsState()
    val live by viewModel.mmWaveLive.collectAsState()
    val occupancyEntity = "binary_sensor.secondary_living_room_switch_occupancy"
    val countEntity = "input_number.secondary_living_room_mmwave_target_count"
    val bounds = states.mmWaveBounds()
    val occupied = isOn(states[occupancyEntity]?.state)
    val helperCount = states.mmWaveInt(countEntity).coerceIn(0, 4)
    val liveCount = live.count.coerceIn(0, 4)
    val count = when {
        !occupied -> 0
        liveCount > 0 -> max(max(liveCount, helperCount), 1)
        helperCount > 0 -> helperCount
        else -> 1
    }
    val targets = (1..4).mapNotNull { index ->
        if (index > count) return@mapNotNull null
        val liveSlot = live.slots[index]
        val x = liveSlot?.x ?: states.mmWaveInt("input_number.secondary_living_room_mmwave_target_${index}_x")
        val y = liveSlot?.y ?: states.mmWaveInt("input_number.secondary_living_room_mmwave_target_${index}_y")
        val z = liveSlot?.z ?: states.mmWaveInt("input_number.secondary_living_room_mmwave_target_${index}_z")
        MmWaveTarget(index, x, y, z)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(overlay.card)
            .clickable { viewModel.openMoreInfo(countEntity) }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MdiIcon("mdi:motion-sensor", tint = overlay.muted, size = 24.dp)
            Column(Modifier.weight(1f)) {
                Text("Secondary Living Room", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (occupied) "Occupied" else "Clear",
                    color = if (occupied) overlay.text else overlay.muted,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = count.toString(),
                color = overlay.text,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
        }
        MmWaveZoneMap(bounds = bounds, targets = targets, overlay = overlay)
        Text("Tracked objects", color = overlay.muted, fontSize = 13.sp)
        if (targets.isEmpty()) {
            Text("No tracked objects right now.", color = overlay.muted, fontSize = 14.sp)
        } else {
            targets.forEach { target ->
                TargetRow(target, overlay)
            }
        }
    }
}

@Composable
private fun TargetRow(target: MmWaveTarget, overlay: OverlayColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(overlay.well)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Object ${target.index}", color = overlay.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        val hasPosition = target.x != 0 || target.y != 0 || target.z != 0
        Text(
            text = if (hasPosition) {
                "X ${target.x} cm  ·  Y ${target.y} cm  ·  Z ${target.z} cm"
            } else {
                "Position pending"
            },
            color = overlay.muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun EnergyStats(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.entitiesFlow(EnergyStatsEntities).collectAsState()
    val solar = states.number("sensor.envoy_202234122877_current_power_production", 2, " kW")
    val net = states.number("sensor.envoy_202234122877_current_net_power_consumption", 2, " kW")
    val battery = states.number("input_number.battery_energy_helper", 3, " kWh", 0.001)
    val tiles = listOf(
        Triple("Solar", solar, "sensor.envoy_202234122877_current_power_production"),
        Triple("Grid", net, "sensor.envoy_202234122877_current_net_power_consumption"),
        Triple("Battery", battery, "input_number.battery_energy_helper"),
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (name, value, entityId) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(CardShape)
                    .background(overlay.card)
                    .clickable { viewModel.openMoreInfo(entityId) }
                    .padding(16.dp),
            ) {
                Text(value, color = overlay.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text(name, color = overlay.muted, fontSize = 13.sp)
            }
        }
    }
}

private data class AutoEntitiesFilter(
    val deviceClass: String? = "power",
    val excludeStates: Set<String> = setOf("unavailable", "unknown"),
    val minState: Double? = 1.0,
    val excludeEntityPatterns: List<String> = listOf(
        "sensor.housepanel_total_consumption_*",
        "sensor.envoy_*",
        "sensor.energy_grid_*",
        "sensor.inverter_*",
        "sensor.enphase_power_*",
        "sensor.emporia_vue_3_*_return",
        "sensor.emporia_vue_3_total_power_return",
        "sensor.power_production_*",
        "sensor.encharge_*",
        "sensor.usw_pro_24_poe_guestroom_port_*",
    ),
)

private data class PowerConsumerItem(
    val entityId: String,
    val name: String,
    val icon: String,
    val value: Double,
    val formattedValue: String,
    val relativeTime: String?,
)

private data class ConsumerNode(
    val item: PowerConsumerItem,
    val parentItem: PowerConsumerItem? = null,
    val children: List<ConsumerNode> = emptyList(),
    val depth: Int = 0,
)

private val ConsumerParentChildMap: Map<String, String> = mapOf(
    // Office Circuit (Breaker 1)
    "sensor.serverplug_power" to "sensor.housepanel_braker_1_vue_1_power_minute_average_2",
    "sensor.treadmillplug_switch_0_power" to "sensor.housepanel_braker_1_vue_1_power_minute_average_2",
    // Sub-devices under ServerRack / ServerPlug
    "sensor.usw_pro_24_poe_guestroom_currentnetworkequipmentpoe_power" to "sensor.serverplug_power",
    "sensor.usw_pro_24_poe_guestroom_currentcamerapoe_power" to "sensor.serverplug_power",
    "sensor.usw_pro_24_poe_guestroom_currentsmarthomeequipmentpoe_power" to "sensor.serverplug_power",
    // Internet Circuit (Vue 6)
    "sensor.internet_plug_switch_0_power" to "sensor.housepanel_vue_power_minute_average_7",
    // Sub-device under Internet Plug
    "sensor.starlink_power" to "sensor.internet_plug_switch_0_power",
    // Dining & Living Outlets (Vue 14)
    "sensor.beveragefridge_switch_0_power" to "sensor.housepanel_vue_power_minute_average_15",
    "sensor.shellyplugus_a0dd6c279bcc_power" to "sensor.housepanel_vue_power_minute_average_15",
    "sensor.speakerplug_power" to "sensor.housepanel_vue_power_minute_average_15",
    // Dryer / EV Charger Circuit (Vue 8)
    "sensor.tankerland_ev_charger_power_minute_average" to "sensor.housepanel_vue_power_minute_average_8",
    "sensor.model_3_charger_power" to "sensor.tankerland_ev_charger_power_minute_average",
)

private val DefaultProducerExportPatterns: List<String> = listOf(
    "*export*",
    "*return*",
    "*production*",
    "sensor.inverter_*",
    "sensor.envoy_*",
    "sensor.energy_grid_*",
    "sensor.encharge_*",
    "sensor.power_production_*",
    "sensor.housepanel_total_consumption_*",
    "sensor.usw_pro_24_poe_guestroom_port_*",
)

private fun cleanConsumerName(entityId: String, rawName: String): String {
    return when (entityId) {
        "sensor.housepanel_braker_1_vue_1_power_minute_average_2" -> "Office Circuit (Breaker 1)"
        "sensor.housepanel_braker_3_vue_2_refridgerator_power_minute_average_3" -> "Kitchen Refrigerator (Breaker 3)"
        "sensor.housepanel_vue_power_minute_average_4" -> "Microwave (Breaker 5)"
        "sensor.housepanel_vue_power_minute_average_5" -> "Vue 4 Circuit"
        "sensor.housepanel_braker_7b_vue_5_furnace_power_minute_average_6" -> "Furnace & Blower (Breaker 7b)"
        "sensor.housepanel_vue_power_minute_average_7" -> "Internet Circuit (Vue 6)"
        "sensor.housepanel_vue_power_minute_average_8" -> "Dryer / EV Charger (Vue 7)"
        "sensor.housepanel_vue_power_minute_average_9" -> "Vue 8 Circuit"
        "sensor.housepanel_vue_power_minute_average_10" -> "Air Conditioning (Vue 9)"
        "sensor.housepanel_vue_power_minute_average_11" -> "Cooktop (Vue 10)"
        "sensor.housepanel_vue_power_minute_average_12" -> "Oven (Breaker 12 & 14)"
        "sensor.housepanel_braker_10_vue_12_dishwasher_power_minute_average_13" -> "Dishwasher (Breaker 10)"
        "sensor.housepanel_vue_power_minute_average_14" -> "Water Heater & Instant Hot Water"
        "sensor.housepanel_vue_power_minute_average_15" -> "Dining & Living Outlets (Vue 14)"
        "sensor.housepanel_vue_power_minute_average_16" -> "Vue 15 Circuit"
        "sensor.housepanel_vue_power_minute_average_17" -> "Kitchen Countertops (Vue 16)"
        "sensor.balance_power_minute_average" -> "Other Unmonitored Balance"
        "sensor.serverplug_power" -> "Server Rack (ServerPlug)"
        "sensor.internet_plug_switch_0_power" -> "Internet Plug"
        "sensor.beveragefridge_switch_0_power" -> "Beverage Fridge"
        "sensor.shellyplugus_a0dd6c279bcc_power" -> "Greatroom Media"
        "sensor.speakerplug_power" -> "Livingroom Speaker"
        "sensor.starlink_power" -> "Starlink Dish"
        "sensor.usw_pro_24_poe_guestroom_currentnetworkequipmentpoe_power" -> "Network Equipment PoE"
        "sensor.usw_pro_24_poe_guestroom_currentcamerapoe_power" -> "Security Cameras PoE"
        "sensor.usw_pro_24_poe_guestroom_currentsmarthomeequipmentpoe_power" -> "Smart Home PoE"
        "sensor.networkequipment_garage_switch_0_power" -> "Garage Network Switch"
        "sensor.treadmillplug_switch_0_power" -> "Treadmill Plug"
        "sensor.tankerland_ev_charger_power_minute_average" -> "Tesla EV Charger"
        "sensor.model_3_charger_power" -> "Tesla Model 3"
        "sensor.entrance_light_power" -> "Entrance Light"
        "sensor.driveway_power" -> "Driveway Light"
        "sensor.atticplug_switch_0_power" -> "Attic Plug"
        else -> rawName
            .removeSuffix(" Power Minute Average")
            .removeSuffix(" Power")
            .removeSuffix(" power")
            .removePrefix("Housepanel - ")
    }
}

private fun consumerIcon(entityId: String, rawIcon: String?): String {
    if (rawIcon != null && rawIcon.startsWith("mdi:")) return rawIcon
    return when {
        entityId.contains("server") -> "mdi:server"
        entityId.contains("camerapoe") || entityId.contains("camera") -> "mdi:cctv"
        entityId.contains("networkequipmentpoe") || entityId.contains("router") -> "mdi:router-network"
        entityId.contains("smarthomeequipmentpoe") -> "mdi:home-automation"
        entityId.contains("starlink") -> "mdi:satellite-variant"
        entityId.contains("internet") || entityId.contains("wifi") -> "mdi:wifi"
        entityId.contains("fridge") || entityId.contains("refridgerator") -> "mdi:fridge"
        entityId.contains("furnace") || entityId.contains("hvac") -> "mdi:hvac"
        entityId.contains("dishwasher") -> "mdi:dishwasher"
        entityId.contains("microwave") -> "mdi:microwave"
        entityId.contains("oven") || entityId.contains("stove") -> "mdi:stove"
        entityId.contains("cooktop") -> "mdi:pot-steam"
        entityId.contains("waterheater") || entityId.contains("boiler") -> "mdi:water-boiler"
        entityId.contains("ac") || entityId.contains("air_condition") -> "mdi:air-conditioner"
        entityId.contains("ev_charger") || entityId.contains("charger") || entityId.contains("model_3") -> "mdi:car-electric"
        entityId.contains("treadmill") -> "mdi:run"
        entityId.contains("speaker") -> "mdi:speaker"
        entityId.contains("media") || entityId.contains("tv") -> "mdi:television"
        entityId.contains("balance") -> "mdi:scale-balance"
        entityId.contains("light") -> "mdi:lightbulb"
        entityId.contains("countertop") -> "mdi:countertop"
        entityId.contains("plug") || entityId.contains("outlet") -> "mdi:power-plug"
        else -> "mdi:lightning-bolt"
    }
}

private fun matchesGlob(id: String, pattern: String): Boolean {
    return when {
        pattern.endsWith("*") && pattern.startsWith("*") -> id.contains(pattern.removePrefix("*").removeSuffix("*"))
        pattern.endsWith("*") -> id.startsWith(pattern.removeSuffix("*"))
        pattern.startsWith("*") -> id.endsWith(pattern.removePrefix("*"))
        else -> id == pattern
    }
}

private fun parseAutoEntitiesFilter(filterElement: JsonElement?): AutoEntitiesFilter {
    val obj = filterElement as? JsonObject ?: return AutoEntitiesFilter()
    var deviceClass: String? = "power"
    val excludeStates = mutableSetOf("unavailable", "unknown")
    var minState: Double? = 1.0
    val excludePatterns = mutableListOf<String>()

    val includeList = obj["include"] as? JsonArray
    if (includeList != null) {
        for (item in includeList) {
            val itemObj = item as? JsonObject ?: continue
            val attrs = itemObj["attributes"] as? JsonObject
            val dc = (attrs?.get("device_class") as? JsonPrimitive)?.contentOrNull
            if (dc != null) deviceClass = dc
        }
    }

    val excludeList = obj["exclude"] as? JsonArray
    if (excludeList != null) {
        for (item in excludeList) {
            val itemObj = item as? JsonObject ?: continue
            val stateStr = (itemObj["state"] as? JsonPrimitive)?.contentOrNull
            if (stateStr != null) {
                if (stateStr.startsWith("<=")) {
                    minState = stateStr.removePrefix("<=").trim().toDoubleOrNull() ?: 1.0
                } else {
                    excludeStates.add(stateStr)
                }
            }
            val entId = (itemObj["entity_id"] as? JsonPrimitive)?.contentOrNull
            if (entId != null) {
                excludePatterns.add(entId)
            }
        }
    }

    return AutoEntitiesFilter(
        deviceClass = deviceClass,
        excludeStates = excludeStates,
        minState = minState,
        excludeEntityPatterns = if (excludePatterns.isNotEmpty()) excludePatterns else listOf(
            "sensor.housepanel_total_consumption_*",
            "sensor.envoy_*",
            "sensor.energy_grid_*",
            "sensor.inverter_*",
            "sensor.enphase_power_*",
            "sensor.emporia_vue_3_*_return",
            "sensor.emporia_vue_3_total_power_return",
            "sensor.power_production_*",
            "sensor.encharge_*",
            "sensor.usw_pro_24_poe_guestroom_port_*",
        ),
    )
}

@Composable
fun AutoEntitiesWidget(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val allStates by viewModel.states.collectAsState()
    val filter = remember(widget.filter) { parseAutoEntitiesFilter(widget.filter) }

    val (tree, totalWatts) = remember(allStates, filter) {
        val now = Instant.now()
        val allConsumers = allStates.values.asSequence()
            .filter { state ->
                if (filter.deviceClass != null && state.attrString("device_class") != filter.deviceClass) {
                    return@filter false
                }
                val id = state.entityId
                // Exclude any producer / export / return pattern
                if (filter.excludeEntityPatterns.any { pattern -> matchesGlob(id, pattern) } ||
                    DefaultProducerExportPatterns.any { pattern -> matchesGlob(id, pattern) }) {
                    return@filter false
                }
                val rawState = state.state
                if (rawState in filter.excludeStates) {
                    return@filter false
                }
                val num = rawState.toDoubleOrNull() ?: return@filter false
                if (num <= 0 || (filter.minState != null && num <= filter.minState)) {
                    return@filter false
                }
                true
            }
            .mapNotNull { state ->
                val num = state.state.toDoubleOrNull() ?: return@mapNotNull null
                val unit = state.attrString("unit_of_measurement") ?: "W"
                val formatted = if (num >= 100) {
                    "${num.roundToInt()} $unit"
                } else {
                    "${String.format(java.util.Locale.US, "%.1f", num)} $unit"
                }
                PowerConsumerItem(
                    entityId = state.entityId,
                    name = cleanConsumerName(state.entityId, state.friendlyName),
                    icon = consumerIcon(state.entityId, state.attrString("icon")),
                    value = num,
                    formattedValue = formatted,
                    relativeTime = state.lastChanged?.relativeToNow(now),
                )
            }
            .associateBy { it.entityId }

        // Find child-to-parent mappings where both exist in current active consumers
        val activeChildToParent = ConsumerParentChildMap.filter { (child, parent) ->
            child in allConsumers && parent in allConsumers
        }
        val parentToChildren = mutableMapOf<String, MutableList<String>>()
        activeChildToParent.forEach { (child, parent) ->
            parentToChildren.getOrPut(parent) { mutableListOf() }.add(child)
        }

        // Roots are consumers that don't have an active parent
        val rootItems = allConsumers.values.filter { it.entityId !in activeChildToParent }

        fun buildNode(item: PowerConsumerItem, parent: PowerConsumerItem?, depth: Int): ConsumerNode {
            val childIds = parentToChildren[item.entityId].orEmpty()
            val childNodes = childIds.mapNotNull { allConsumers[it] }
                .sortedByDescending { it.value }
                .map { buildNode(it, item, depth + 1) }
            return ConsumerNode(item, parent, childNodes, depth)
        }

        val treeNodes = rootItems.map { buildNode(it, null, 0) }
            .sortedByDescending { it.item.value }

        // Total power: use housepanel total if available, otherwise sum root items (avoids double-counting!)
        val total = allStates["sensor.housepanel_total_consumption_power_minute_average"]?.state?.toDoubleOrNull()
            ?: rootItems.sumOf { it.value }

        treeNodes to total
    }

    val totalText = if (totalWatts >= 1000) {
        String.format(java.util.Locale.US, "%.2f kW", totalWatts / 1000.0)
    } else {
        "${totalWatts.roundToInt()} W"
    }

    val title = widget.name ?: widget.title ?: "Live Power Draw (Top Consumers)"

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = overlay.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (tree.isNotEmpty()) {
                Text(
                    text = "${tree.size} circuits • $totalText",
                    color = overlay.muted,
                    fontSize = 13.sp,
                )
            }
        }

        if (tree.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(overlay.card)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No active power consumers (> 1 W)",
                    color = overlay.muted,
                    fontSize = 14.sp,
                )
            }
        } else {
            tree.forEach { node ->
                ConsumerNodeTree(node = node, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ConsumerNodeTree(
    node: ConsumerNode,
    viewModel: HaViewModel,
) {
    val overlay = LocalOverlay.current
    val item = node.item
    val depth = node.depth

    val indent = when (depth) {
        0 -> 0.dp
        1 -> 24.dp
        else -> 48.dp
    }

    val background = when (depth) {
        0 -> overlay.card
        else -> overlay.well
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent)
                .clip(RoundedCornerShape(if (depth == 0) 14.dp else 12.dp))
                .background(background)
                .clickable { viewModel.openMoreInfo(item.entityId) }
                .padding(horizontal = 14.dp, vertical = if (depth == 0) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (depth > 0) {
                MdiIcon(
                    name = "mdi:subdirectory-arrow-right",
                    tint = overlay.muted,
                    size = 18.dp,
                )
                Spacer(Modifier.width(8.dp))
            }

            Box(
                modifier = Modifier
                    .size(if (depth == 0) 36.dp else 28.dp)
                    .clip(CircleShape)
                    .background(if (depth == 0) overlay.well else overlay.card),
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon(
                    name = item.icon,
                    tint = AccentYellow,
                    size = if (depth == 0) 20.dp else 16.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = overlay.text,
                    fontSize = if (depth == 0) 14.sp else 13.sp,
                    fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subText = when {
                    node.parentItem != null && node.parentItem.value > 0 -> {
                        val pct = (item.value / node.parentItem.value * 100).roundToInt().coerceIn(1, 100)
                        "$pct% of ${node.parentItem.name}"
                    }
                    node.children.isNotEmpty() -> {
                        "${node.children.size} sub-metered ${if (node.children.size == 1) "device" else "devices"}"
                    }
                    !item.relativeTime.isNullOrBlank() -> item.relativeTime
                    else -> null
                }
                if (subText != null) {
                    Text(
                        text = subText,
                        color = overlay.muted,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = item.formattedValue,
                color = overlay.text,
                fontSize = if (depth == 0) 15.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (node.children.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                node.children.forEach { child ->
                    ConsumerNodeTree(node = child, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun EnergyDateBar(modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val today = LocalDate.now().format(MONTH_DAY_FORMAT)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(overlay.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MdiIcon("mdi:calendar", tint = overlay.text, size = 20.dp)
        Text(today, color = overlay.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            "Now",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF3D5A80))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        MdiIcon("mdi:chevron-left", tint = overlay.muted, size = 22.dp)
        MdiIcon("mdi:chevron-right", tint = overlay.muted, size = 22.dp)
        MdiIcon("mdi:dots-vertical", tint = overlay.muted, size = 22.dp)
    }
}

@Composable
fun TabsWidget(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val initial = (widget.defaultTab ?: 1).let { if (it > 0) it - 1 else 0 }.coerceIn(0, (widget.tabs.size - 1).coerceAtLeast(0))
    var selected by remember { mutableIntStateOf(initial) }
    val activeBrush = TabActiveBrush
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            widget.tabs.forEachIndexed { index, tab ->
                val active = index == selected
                Row(
                    modifier = Modifier
                        .clip(ChipShape)
                        .then(
                            if (active) Modifier.background(activeBrush)
                            else Modifier.background(if (overlay.dark) overlay.card else ChipDark),
                        )
                        .clickable { selected = index }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val tint = if (active) Color.Black else if (overlay.dark) overlay.text else ChipOnDark
                    MdiIcon(tab.icon, tint = tint, size = 18.dp)
                    Text(tab.title.orEmpty(), color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        widget.tabs.getOrNull(selected)?.let { WidgetTree(it.cards, viewModel) }
    }
}

@Composable
private fun SensorValueText(value: String, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    val number = value.substringBeforeLast(' ', missingDelimiterValue = value)
    val unit = value.substringAfterLast(' ', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() && it != number && it.any { ch -> ch.isLetter() } }
    if (unit == null) {
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Light)
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Text(number, color = color, fontSize = size, fontWeight = FontWeight.Light)
            Text(
                unit,
                color = color.copy(alpha = 0.85f),
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 3.dp, top = 4.dp),
            )
        }
    }
}

@Composable
fun Sparkline(points: List<Pair<Long, Double>>, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        var min = points.minOf { it.second }
        var max = points.maxOf { it.second }
        if (min == max) {
            min -= 1.0
            max += 1.0
        }
        val pad = ((max - min) * 0.1).coerceAtLeast(0.2)
        min -= pad
        max += pad
        val span = max - min

        val tMin = points.minOf { it.first }
        val tMax = points.maxOf { it.first }
        val tSpan = (tMax - tMin).takeIf { it > 0 }
        val pts = if (tSpan != null) points.sortedBy { it.first } else points

        val linePath = Path()
        val fillPath = Path()

        pts.forEachIndexed { index, point ->
            val x = if (tSpan != null) {
                ((point.first - tMin).toDouble() / tSpan * size.width).toFloat().coerceIn(0f, size.width)
            } else {
                size.width * index / (pts.size - 1).toFloat()
            }
            val y = (size.height - ((point.second - min) / span * size.height).toFloat()).coerceIn(0f, size.height)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()

        drawPath(fillPath, color.copy(alpha = 0.35f), style = Fill)
        drawPath(
            linePath,
            color,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun EntityPicture(path: String?, viewModel: HaViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val loader = rememberHaImageLoader(viewModel.client)
    val url = resolveHaImageUrl(path, viewModel.client.currentBaseUrl)
    if (url.isNullOrBlank()) {
        Box(modifier.background(ChipDark), contentAlignment = Alignment.Center) {
            MdiIcon("mdi:home", tint = ChipOnDark, size = 20.dp)
        }
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
        contentDescription = null,
        imageLoader = loader,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            else -> Box(Modifier.fillMaxSize().background(ChipDark), contentAlignment = Alignment.Center) {
                MdiIcon("mdi:home", tint = ChipOnDark, size = 20.dp)
            }
        }
    }
}

val PopupSheetShape = RoundedCornerShape(32.dp)

fun Modifier.popupSheetLook(sheet: Color): Modifier =
    shadow(
        elevation = 28.dp,
        shape = PopupSheetShape,
        clip = false,
        ambientColor = Color(0x4D000000),
        spotColor = Color(0x33000000),
    )
        .clip(PopupSheetShape)
        .background(sheet)
        .background(PopupSheetSheenBrush)
        .border(1.dp, Color.White.copy(alpha = 0.7f), PopupSheetShape)

@Composable
fun popupSheetModifier(kind: PopupSheetKind = PopupSheetKind.Room): Modifier {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val (widthFraction, maxWidth, heightFraction) = when (kind) {
        PopupSheetKind.Room -> Triple(0.68f, 600.dp, 0.62f)
        PopupSheetKind.Camera -> Triple(0.86f, 920.dp, 0.78f)
        PopupSheetKind.Utility -> Triple(0.74f, 720.dp, 0.70f)
        PopupSheetKind.Settings -> Triple(0.72f, 640.dp, 0.78f)
        PopupSheetKind.Detail -> Triple(0.68f, 600.dp, 0.70f)
    }
    return Modifier
        .fillMaxWidth(widthFraction)
        .widthIn(max = maxWidth)
        .height(screenH * heightFraction)
}

@Composable
fun PopupSheetChrome(
    title: String,
    onClose: () -> Unit,
    overlay: OverlayColors,
    icon: String? = null,
    accent: String? = null,
    subtitle: String? = null,
) {
    val accentTint = accentColor(accent)
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(overlay.text.copy(alpha = 0.18f)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!icon.isNullOrBlank()) {
                val iconBg = if (accent.isNullOrBlank()) {
                    overlay.text.copy(alpha = 0.08f)
                } else {
                    accentTint.copy(alpha = 0.22f)
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon(icon, tint = TextDark, size = 22.dp)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = overlay.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = overlay.muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(overlay.text.copy(alpha = 0.08f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon("mdi:close", tint = overlay.text, size = 20.dp)
            }
        }
    }
}

enum class PopupSheetKind { Room, Camera, Utility, Settings, Detail }

fun popupSheetKind(hash: String?): PopupSheetKind = when (hash) {
    KioskCommands.CAMERA_POPUP, "#music", "#camerafront_view", "#camera_alert" -> PopupSheetKind.Camera
    "#settings", "#changelog" -> PopupSheetKind.Settings
    "#weather", "#power", "#bil", "#staubinator" -> PopupSheetKind.Utility
    else -> PopupSheetKind.Room
}

@Composable
fun PopupScaffold(
    popup: PopupNode,
    viewModel: HaViewModel,
    scrollContent: Boolean = true,
    denseContent: Boolean = false,
    overlay: OverlayColors = OverlayLightPopup,
    subtitleOverride: String? = null,
    content: @Composable () -> Unit,
) {
    val outerPadding = if (denseContent) {
        PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    } else {
        PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    }
    val innerPadding = if (denseContent) {
        PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
    } else {
        PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)
    }
    val headerSpacer = if (denseContent) 6.dp else 12.dp
    CompositionLocalProvider(LocalOverlay provides overlay) {
        Column(
            modifier = popupSheetModifier(popupSheetKind(popup.hash))
                .padding(outerPadding)
                .popupSheetLook(overlay.sheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(innerPadding),
        ) {
            PopupSheetChrome(
                title = popup.name.orEmpty(),
                onClose = { viewModel.closePopup() },
                overlay = overlay,
                icon = popup.icon,
                accent = popup.accent,
                subtitle = subtitleOverride,
            )
            Spacer(Modifier.height(headerSpacer))
            if (scrollContent) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    content()
                }
            } else {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

private fun parseRadius(raw: String?): List<Dp> {
    val parts = raw?.split(Regex("\\s+"))?.mapNotNull { it.removeSuffix("px").toFloatOrNull() }
    if (parts == null || parts.size < 4) return listOf(36.dp, 36.dp, 36.dp, 36.dp)
    return parts.take(4).map { it.dp }
}
