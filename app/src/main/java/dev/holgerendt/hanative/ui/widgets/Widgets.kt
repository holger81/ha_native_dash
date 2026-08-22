@file:OptIn(ExperimentalFoundationApi::class)

package dev.holgerendt.hanative.ui.widgets

import android.graphics.BitmapFactory
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
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.HaCalendarEvent
import dev.holgerendt.hanative.data.hasLiveCameraSource
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.StateFormat
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.LoadingSpinner
import dev.holgerendt.hanative.ui.MdiIcon
import dev.holgerendt.hanative.ui.MediaPreview
import dev.holgerendt.hanative.ui.brightnessPct
import dev.holgerendt.hanative.ui.format
import dev.holgerendt.hanative.ui.formatState
import dev.holgerendt.hanative.ui.isOn
import dev.holgerendt.hanative.ui.isVisible
import dev.holgerendt.hanative.ui.number
import dev.holgerendt.hanative.ui.stateOf
import dev.holgerendt.hanative.ui.tempHum
import dev.holgerendt.hanative.ui.theme.AccentBlue
import dev.holgerendt.hanative.ui.theme.AccentRed
import dev.holgerendt.hanative.ui.theme.ActiveLight
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.HistoryGraph
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import dev.holgerendt.hanative.ui.theme.OverlayLightPopup
import dev.holgerendt.hanative.ui.theme.TabActiveEnd
import dev.holgerendt.hanative.ui.theme.TabActiveStart
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.theme.VacuumStart
import dev.holgerendt.hanative.ui.theme.VacuumStop
import dev.holgerendt.hanative.ui.theme.accentColor
import dev.holgerendt.hanative.ui.weatherIcon
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)
private val ChipShape = RoundedCornerShape(24.dp)

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
    val states by viewModel.states.collectAsState()
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
        "light_toggle", "cover_toggle", "entity_button" -> ToggleRow(widget, viewModel, modifier)
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
    val states by viewModel.states.collectAsState()
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widget.chips.filter { states.isVisible(it) }.forEach { chip ->
            val entity = states[chip.entity]
            val highlighted = when {
                chip.emphasizeUnlocked == true -> entity?.state == "unlocked"
                chip.accent == "active" || chip.accent == "active-big" -> true
                else -> false
            }
            val label = when {
                chip.layout == "icon|name" -> chip.name
                chip.state != null -> states.formatState(chip.state)
                else -> chip.name
            }
            Row(
                modifier = Modifier
                    .clip(ChipShape)
                    .background(if (highlighted) ActiveYellow else ChipDark)
                    .widgetClicks(chip, viewModel)
                    .padding(end = 12.dp, start = 2.dp, top = 2.dp, bottom = 2.dp)
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (highlighted) Color.White else CardLight),
                    contentAlignment = Alignment.Center,
                ) {
                    MdiIcon(chip.icon, tint = TextDark, size = 18.dp)
                }
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        color = if (highlighted) Color.Black else ChipOnDark,
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
    val states by viewModel.states.collectAsState()
    val person = states[widget.entity]
    val home = person?.state == "home"
    val minutes = states[widget.homeSensor]?.state
    val label = if (home) "Home" else listOfNotNull(minutes?.toDoubleOrNull()?.roundToInt()?.toString()?.plus("min"), person?.state).firstOrNull() ?: "Away"
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
        Text(label.replaceFirstChar { it.uppercase() }, color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WeatherHeader(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val weather = states[widget.entity]
    val temp = states[widget.tempEntity ?: "sensor.st_00063154_temperature"]?.state?.toDoubleOrNull()
    val day = states[widget.sunEntity ?: "sun.sun"]?.state == "above_horizon"
    val condition = weather?.state?.replace("sunny", "clear")?.replace('-', ' ') ?: ""
    Row(
        modifier = modifier.widgetClicks(widget, viewModel),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(weatherIcon(weather?.state, day), tint = TextDark, size = 48.dp)
        Column(horizontalAlignment = Alignment.End) {
            Text(condition.replaceFirstChar { it.uppercase() }, color = TextMuted, fontSize = 14.sp)
            Text(temp.format(1, "°C"), color = TextDark, fontSize = 26.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun RoomCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
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

@Composable
fun WeekPlanner(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val columns = (if (widget.columns == null) 5 else widget.columnCount()).coerceIn(1, 7)
    val dayCount = widget.days ?: 10
    val subscribed by viewModel.subscribedCalendars.collectAsState()
    val sources = remember(subscribed, widget.calendars) { viewModel.plannerCalendars(widget.calendars) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf(listOf<HaCalendarEvent>()) }
    var forecasts by remember { mutableStateOf(listOf<Map<String, String>>()) }
    var loaded by remember { mutableStateOf(false) }
    val weatherEntity = widget.weatherEntity ?: "weather.forecast_tankerland_ct"
    LaunchedEffect(sources, dayOffset, viewModel.client.currentBaseUrl) {
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
    val today = LocalDate.now(zone)
    val days = (0 until dayCount).map { today.plusDays(dayOffset.toLong() + it) }
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
                    text = days.firstOrNull()?.format(DateTimeFormatter.ofPattern("MMMM")) ?: "",
                    color = TextDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
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
                            events = events.filter { eventOverlapsDay(it, day, zone) }
                                .sortedWith(compareBy<HaCalendarEvent> { !it.allDay }.thenBy { it.start ?: Instant.EPOCH }),
                            forecast = forecasts.firstOrNull { it["datetime"].orEmpty().startsWith(day.toString()) },
                            showCondition = widget.showCondition != false,
                            showTemperature = widget.showTemperature == true,
                            showLowTemperature = widget.showLowTemperature == true,
                            loading = false,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 280.dp),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
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
    modifier: Modifier = Modifier,
) {
    val weekday = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE"))
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
            if (showCondition || showTemperature) {
                Column(horizontalAlignment = Alignment.End) {
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    events.forEach { event ->
                        val stripe = accentColor(event.color?.removePrefix("var(--")?.removeSuffix(")"))
                            .takeIf { event.color != null } ?: AccentBlue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardLight)
                                .clickable { viewModel.openMoreInfo(event.entityId) },
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).weight(1f)) {
                                Text(
                                    text = eventTimeLabel(event),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    text = event.summary,
                                    color = TextDark,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun weatherTint(condition: String?): Color {
    val value = condition.orEmpty().lowercase()
    return when {
        "rain" in value || "pour" in value || "snow" in value -> AccentBlue
        "cloud" in value && "partly" !in value && "sun" !in value -> TextMuted
        else -> Color(0xFFFFB300)
    }
}

@Composable
fun VisionTimeline(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val limit = widget.numberOfEvents ?: 5
    val hours = widget.numberOfHours ?: widget.hours
    val days = widget.days
    val entityId = widget.entity ?: "calendar.llm_vision_timeline"
    var events by remember { mutableStateOf(listOf<HaCalendarEvent>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(entityId, limit, hours, days, viewModel.client.currentBaseUrl) {
        loaded = false
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
            delay(20_000)
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = widget.name.takeUnless { it.isNullOrBlank() } ?: "This happened around the house",
            color = TextDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        when {
            !loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(75.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardLight),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == 1) {
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
                    val cameraLabel = event.cameraName?.let { id ->
                        states[id]?.friendlyName ?: id.substringAfter('.').replace('_', ' ')
                    }
                    val timeLabel = start?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                    val subtitle = listOfNotNull(
                        timeLabel.takeIf { it.isNotBlank() },
                        cameraLabel?.takeIf { it.isNotBlank() && it != "clip" },
                    ).joinToString(" • ")
                    val style = timelineStyle(event)
                    val eventKey = event.uid ?: "${event.start}-${event.summary}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(75.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardLight)
                            .clickable {
                                val frame = event.keyFrame
                                if (!frame.isNullOrBlank()) {
                                    viewModel.openMedia(
                                        path = frame,
                                        title = event.summary,
                                        subtitle = subtitle.takeIf { it.isNotBlank() },
                                        description = event.description,
                                    )
                                } else {
                                    val camera = event.cameraName?.takeIf { '.' in it }
                                    viewModel.openMoreInfo(camera ?: event.entityId)
                                }
                                expandedId = if (expandedId == eventKey) null else eventKey
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
                        if (!event.keyFrame.isNullOrBlank()) {
                            TimelineSnapshot(
                                path = event.keyFrame,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .size(59.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                    if (expandedId == eventKey && !event.description.isNullOrBlank()) {
                        Text(
                            text = event.description.orEmpty(),
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardLight)
                                .padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineSnapshot(path: String?, viewModel: HaViewModel, modifier: Modifier) {
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var loaded by remember(path) { mutableStateOf(path.isNullOrBlank()) }
    LaunchedEffect(path, viewModel.client.currentBaseUrl) {
        if (!path.isNullOrBlank()) {
            bytes = runCatching { viewModel.client.mediaBytes(path) }.getOrNull()
        }
        loaded = true
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    when {
        bitmap != null -> Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        !loaded -> Box(modifier.background(CardLight.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            LoadingSpinner(indicatorSize = 16.dp)
        }
        else -> Box(modifier.background(CardLight.copy(alpha = 0.12f)))
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
        modifier = popupSheetModifier(PopupSheetKind.Detail)
            .padding(horizontal = 10.dp, vertical = 8.dp)
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

private fun visionDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun timelineStyle(event: HaCalendarEvent): Pair<String, Color> {
    val haystack = listOfNotNull(event.category, event.label, event.summary).joinToString(" ").lowercase()
    return when {
        "package" in haystack || "delivery" in haystack -> "mdi:package-variant-closed" to Color(0xFFEA580C)
        "car" in haystack || "vehicle" in haystack || "truck" in haystack -> "mdi:car" to Color(0xFF64748B)
        "dog" in haystack -> "mdi:dog" to Color(0xFF00DD51)
        "cat" in haystack -> "mdi:cat" to Color(0xFF00DD51)
        "person" in haystack -> "mdi:walk" to Color(0xFF3B82F6)
        "door" in haystack -> "mdi:door-closed" to Color(0xFF8B5CF6)
        else -> "mdi:motion-sensor" to Color(0xFF3B82F6)
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

private fun forecastC(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val number = raw.trim('"').toDoubleOrNull()
    return if (number != null) "${number.roundToInt()} °C" else raw
}

private fun eventTimeLabel(event: HaCalendarEvent): String {
    if (event.allDay || (event.startDate != null && event.start == null)) return "Entire day"
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.start?.atZone(zone)?.format(fmt) ?: return ""
    val end = event.end?.atZone(zone)?.format(fmt) ?: return start
    return if (end == start) start else "$start - $end"
}

private fun primitiveContent(element: JsonElement): String {
    val primitive = element as? JsonPrimitive ?: return element.toString().trim('"')
    return primitive.contentOrNull ?: primitive.toString().trim('"')
}

@Composable
fun LightSlider(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
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
    val states by viewModel.states.collectAsState()
    val entity = states[widget.entity]
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
    val states by viewModel.states.collectAsState()
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
fun ClimateCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
    val climate = states[widget.entity]
    val current = climate?.attrDouble("current_temperature")
    val target = climate?.attrDouble("temperature") ?: climate?.attrDouble("target_temp_high")
    val activity = states[widget.activityEntity]
    val heating = activity?.state.equals("Active", ignoreCase = true) || climate?.state == "heat"
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(overlay.card)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widgetClicks(widget, viewModel),
        ) {
            Text(widget.name ?: "Climate", color = overlay.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            MdiIcon(if (heating) "mdi:thermometer" else "mdi:thermostat", tint = if (heating) AccentRed else overlay.text, size = 22.dp)
        }
        Text("${current.format(1, "°")}  →  ${target.format(1, "°")}", color = overlay.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("–", color = overlay.text, fontSize = 28.sp, modifier = Modifier.clickable {
                target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it - 0.5) } }
            }.padding(8.dp))
            Text(target.format(1, "°"), color = overlay.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("+", color = overlay.text, fontSize = 28.sp, modifier = Modifier.clickable {
                target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it + 0.5) } }
            }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text(climate?.state?.replaceFirstChar { it.uppercase() } ?: "—", color = overlay.muted)
        }
    }
}

@Composable
fun RoomConditions(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
    var points by remember { mutableStateOf(listOf<Pair<Long, Double>>()) }
    LaunchedEffect(widget.entity) {
        widget.entity?.let { points = viewModel.client.history(it, 12) }
    }
    Box(
        modifier = modifier
            .height(140.dp)
            .clip(CardShape)
            .background(overlay.card)
            .widgetClicks(widget, viewModel)
            .padding(20.dp),
    ) {
        Sparkline(points, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp), AccentRed.copy(alpha = 0.7f))
        Text(states.tempHum(widget.display), color = overlay.text, fontSize = 48.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun SensorCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
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
    val states by viewModel.states.collectAsState()
    val on = states[widget.entity]?.state == "on"
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
    val states by viewModel.states.collectAsState()
    val on = isOn(states[widget.entity]?.state)
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
        Text(widget.name ?: states[widget.entity]?.friendlyName.orEmpty(), color = tint, fontSize = 14.sp)
    }
}

@Composable
fun MediaCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
    val tv = states[widget.entity]
    val apple = states[widget.companionEntity ?: "media_player.living_room_appletv"]
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
        entity?.let { points = runCatching { viewModel.client.history(it, 24) }.getOrDefault(emptyList()) }
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
    val states by viewModel.states.collectAsState()
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

@Composable
fun MmWaveTargetsPanel(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val states by viewModel.states.collectAsState()
    val occupancyEntity = "binary_sensor.secondary_living_room_switch_occupancy"
    val countEntity = "input_number.secondary_living_room_mmwave_target_count"
    val occupied = states[occupancyEntity]?.state == "on"
    val count = states[countEntity]?.state?.toIntOrNull()?.coerceIn(0, 4) ?: 0
    val targets = (1..4).mapNotNull { index ->
        val x = states["input_number.secondary_living_room_mmwave_target_${index}_x"]?.state?.toIntOrNull() ?: 0
        val y = states["input_number.secondary_living_room_mmwave_target_${index}_y"]?.state?.toIntOrNull() ?: 0
        val z = states["input_number.secondary_living_room_mmwave_target_${index}_z"]?.state?.toIntOrNull() ?: 0
        if (index <= count) MmWaveTarget(index, x, y, z) else null
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
    val states by viewModel.states.collectAsState()
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

@Composable
fun EnergyDateBar(modifier: Modifier = Modifier) {
    val overlay = LocalOverlay.current
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d"))
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
    val activeBrush = Brush.horizontalGradient(listOf(TabActiveStart, TabActiveEnd))
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
        val min = points.minOf { it.second }
        val max = points.maxOf { it.second }
        val span = (max - min).takeIf { it != 0.0 } ?: 1.0
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = size.height - ((point.second - min) / span * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, color.copy(alpha = 0.35f), style = Fill)
    }
}

@Composable
fun EntityPicture(path: String?, viewModel: HaViewModel, modifier: Modifier) {
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(path, viewModel.client.currentBaseUrl) {
        if (!path.isNullOrBlank()) {
            bytes = viewModel.client.authenticatedBytes(path)
        }
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(ChipDark), contentAlignment = Alignment.Center) {
            MdiIcon("mdi:home", tint = ChipOnDark, size = 20.dp)
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
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.42f),
                0.2f to Color.Transparent,
            ),
        )
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
    "#camerafront_view" -> PopupSheetKind.Camera
    "#settings" -> PopupSheetKind.Settings
    "#weather", "#power", "#bil", "#staubinator" -> PopupSheetKind.Utility
    else -> PopupSheetKind.Room
}

@Composable
fun PopupScaffold(
    popup: PopupNode,
    viewModel: HaViewModel,
    scrollContent: Boolean = true,
    overlay: OverlayColors = OverlayLightPopup,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOverlay provides overlay) {
        Column(
            modifier = popupSheetModifier(popupSheetKind(popup.hash))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .popupSheetLook(overlay.sheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        ) {
            PopupSheetChrome(
                title = popup.name.orEmpty(),
                onClose = { viewModel.closePopup() },
                overlay = overlay,
                icon = popup.icon,
                accent = popup.accent,
            )
            Spacer(Modifier.height(12.dp))
            if (scrollContent) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    content()
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
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
