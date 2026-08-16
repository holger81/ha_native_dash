package dev.holgerendt.hanative.ui.widgets

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.hasLiveCameraSource
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.MdiIcon
import dev.holgerendt.hanative.ui.brightnessPct
import dev.holgerendt.hanative.ui.format
import dev.holgerendt.hanative.ui.formatState
import dev.holgerendt.hanative.ui.isOn
import dev.holgerendt.hanative.ui.isVisible
import dev.holgerendt.hanative.ui.number
import dev.holgerendt.hanative.ui.stateOf
import dev.holgerendt.hanative.ui.tempHum
import dev.holgerendt.hanative.ui.theme.AccentRed
import dev.holgerendt.hanative.ui.theme.ActiveLight
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.PopupCard
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.theme.VacuumStart
import dev.holgerendt.hanative.ui.theme.VacuumStop
import dev.holgerendt.hanative.ui.theme.accentColor
import dev.holgerendt.hanative.ui.weatherIcon
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)
private val ChipShape = RoundedCornerShape(24.dp)

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
        "energy_date_selection" -> { /* native charts always show a rolling window */ }
        "energy_sources_table", "energy_solar_consumed_gauge", "energy_self_sufficiency_gauge" ->
            EnergyStats(viewModel, modifier)
        "markdown" -> Text(
            text = widget.content.orEmpty().replace(Regex("[{}|]"), "").take(400),
            color = TextDark,
            modifier = modifier
                .clip(CardShape)
                .background(PopupCard)
                .padding(16.dp),
        )
        "heading" -> Text(widget.name.orEmpty(), color = Color.White, fontWeight = FontWeight.Medium, modifier = modifier.padding(8.dp))
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
            val active = chip.emphasizeUnlocked == true && entity?.state == "unlocked" ||
                isOn(entity?.state) && chip.emphasizeUnlocked != true && chip.layout != "icon|state"
            val highlighted = chip.emphasizeUnlocked == true && entity?.state == "unlocked"
            val label = when {
                chip.layout == "icon|name" -> chip.name
                chip.state != null -> states.formatState(chip.state)
                else -> chip.name
            }
            Row(
                modifier = Modifier
                    .clip(ChipShape)
                    .background(if (highlighted) ActiveYellow else ChipDark)
                    .clickable(enabled = chip.tap != null) { viewModel.onTap(chip) }
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
            .clickable { viewModel.onTap(widget) },
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
        Text(label.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        modifier = modifier.clickable { viewModel.onTap(widget) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(weatherIcon(weather?.state, day), tint = Color.White, size = 48.dp)
        Column(horizontalAlignment = Alignment.End) {
            Text(condition.replaceFirstChar { it.uppercase() }, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            Text(temp.format(1, "°C"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
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
            .clickable { viewModel.onTap(widget) }
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
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val col = ((width - gap) / 2).coerceAtLeast(1)
        val unit = ((height - gap * 6) / 7f).toInt().coerceAtLeast(1)
        fun h(rows: Int, extraGaps: Int) = unit * rows + gap * extraGaps
        val specs = listOf(
            Triple("emilia", 0 to 0, col to h(2, 1)),
            Triple("greatroom", col + gap to 0, col to unit),
            Triple("jonathan", col + gap to unit + gap, col to h(2, 1)),
            Triple("mainbed", 0 to h(2, 1) + gap, col to unit),
            Triple("office", 0 to h(3, 2) + gap, width to unit),
            Triple("hallway", 0 to h(4, 3) + gap, col to h(2, 1)),
            Triple("mainbath", col + gap to h(4, 3) + gap, col to unit),
            Triple("guestroom", col + gap to h(5, 4) + gap, col to h(2, 1)),
            Triple("secondbath", 0 to h(6, 5) + gap, col to unit),
        )
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
fun LightSlider(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val entity = states[widget.entity]
    val on = entity?.state == "on"
    val pct = states.brightnessPct(widget.entity)
    var sliding by remember { mutableFloatStateOf(pct.toFloat()) }
    LaunchedEffect(pct) { sliding = pct.toFloat() }
    val fill by animateFloatAsState(if (on) sliding / 100f else 0f, label = "light")
    Box(
        modifier = modifier
            .height(75.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(PopupCard)
            .pointerInput(widget.entity) {
                detectTapGestures(
                    onTap = { viewModel.onTap(widget) },
                    onLongPress = { viewModel.onHold(widget) },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(if (on) ActiveLight else Color.Transparent),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(widget.icon, tint = if (on) Color.Black else TextDark, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(widget.name.orEmpty(), color = if (on) Color.Black else TextMuted, fontSize = 14.sp)
                Text(if (on) "${pct}%" else "Off", color = if (on) Color.Black else TextDark, fontWeight = FontWeight.Medium)
            }
        }
        Slider(
            value = sliding,
            onValueChange = { sliding = it },
            onValueChangeFinished = { widget.entity?.let { viewModel.setBrightness(it, sliding.roundToInt()) } },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun ToggleRow(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val entity = states[widget.entity]
    val on = isOn(entity?.state)
    val label = entity?.state?.replaceFirstChar { it.uppercase() } ?: widget.label ?: "Unknown"
    Row(
        modifier = modifier
            .height(75.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) ActiveLight else PopupCard)
            .clickable { viewModel.onTap(widget) }
            .pointerInput(widget.entity) {
                detectTapGestures(onLongPress = { viewModel.onHold(widget) })
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdiIcon(widget.icon ?: "mdi:power", tint = if (on) Color.Black else TextDark, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(widget.name ?: entity?.friendlyName.orEmpty(), color = if (on) Color.Black else TextMuted, fontSize = 14.sp)
            Text(label, color = if (on) Color.Black else TextDark, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
    }
}

@Composable
fun VentRow(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier, ids: List<String>) {
    val states by viewModel.states.collectAsState()
    val open = ids.any { states[it]?.state in setOf("open", "opening") }
    val label = when {
        open -> "Open"
        ids.all { states[it]?.state == "closed" } -> "Closed"
        else -> "Unknown"
    }
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (open) ActiveLight else PopupCard)
            .clickable { viewModel.tiltGroup(ids) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdiIcon(widget.icon ?: "mdi:air-filter", tint = Color.Black, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(widget.name ?: "Vents", color = Color.Black.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(label, color = Color.Black, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
fun ClimateCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val climate = states[widget.entity]
    val current = climate?.attrDouble("current_temperature")
    val target = climate?.attrDouble("temperature") ?: climate?.attrDouble("target_temp_high")
    val activity = states[widget.activityEntity]
    val heating = activity?.state.equals("Active", ignoreCase = true) || climate?.state == "heat"
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(PopupCard)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(widget.name ?: "Climate", color = TextDark, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            MdiIcon(if (heating) "mdi:thermometer" else "mdi:thermostat", tint = if (heating) AccentRed else TextDark, size = 22.dp)
        }
        Text("${current.format(1, "°")}  →  ${target.format(1, "°")}", color = TextDark, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("–", color = TextDark, fontSize = 28.sp, modifier = Modifier.clickable {
                target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it - 0.5) } }
            }.padding(8.dp))
            Text(target.format(1, "°"), color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("+", color = TextDark, fontSize = 28.sp, modifier = Modifier.clickable {
                target?.let { widget.entity?.let { id -> viewModel.setTemperature(id, it + 0.5) } }
            }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text(climate?.state?.replaceFirstChar { it.uppercase() } ?: "—", color = TextMuted)
        }
    }
}

@Composable
fun RoomConditions(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    var points by remember { mutableStateOf(listOf<Pair<Long, Double>>()) }
    LaunchedEffect(widget.entity) {
        widget.entity?.let { points = viewModel.client.history(it, 12) }
    }
    Box(
        modifier = modifier
            .height(140.dp)
            .clip(CardShape)
            .background(PopupCard)
            .clickable { widget.entity?.let { viewModel.openMoreInfo(it) } }
            .padding(20.dp),
    ) {
        Sparkline(points, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp), AccentRed.copy(alpha = 0.7f))
        Text(states.tempHum(widget.display), color = TextDark, fontSize = 48.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun SensorCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val entity = states[widget.entity]
    val value = widget.label ?: entity?.state ?: "—"
    Box(
        modifier = modifier
            .height(if (widget.type == "sensor_small") 66.dp else 160.dp)
            .clip(if (widget.type == "sensor_small") RoundedCornerShape(40.dp) else CardShape)
            .background(PopupCard)
            .clickable { widget.entity?.let { viewModel.openMoreInfo(it) } }
            .padding(16.dp),
    ) {
        MdiIcon(widget.icon, tint = TextDark, size = 22.dp, modifier = Modifier.align(Alignment.TopEnd))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(value, color = TextDark, fontSize = if (widget.type == "sensor_small") 16.sp else 32.sp, fontWeight = FontWeight.Light)
            Text(widget.name ?: entity?.friendlyName.orEmpty(), color = TextMuted, fontSize = 14.sp)
        }
    }
}

@Composable
fun ButtonToggle(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val on = states[widget.entity]?.state == "on"
    Box(
        modifier = modifier
            .height(if (widget.type == "button_toggle_small") 66.dp else 160.dp)
            .clip(if (widget.type == "button_toggle_small") RoundedCornerShape(40.dp) else CardShape)
            .background(if (on) PopupCard else ChipDark)
            .clickable { viewModel.onTap(widget) }
            .padding(16.dp),
    ) {
        MdiIcon(widget.icon, tint = if (on) TextDark else ChipOnDark, size = 22.dp, modifier = Modifier.align(Alignment.TopEnd))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(if (on) "On" else "Off", color = if (on) TextDark else ChipOnDark, fontSize = 32.sp, fontWeight = FontWeight.Light)
            Text(widget.name.orEmpty(), color = if (on) TextMuted else ChipOnDark.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
}

@Composable
fun ActionChip(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(ChipDark)
            .clickable { viewModel.onTap(widget) }
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
    val states by viewModel.states.collectAsState()
    val on = isOn(states[widget.entity]?.state)
    val stop = widget.name.equals("Stop", ignoreCase = true)
    val start = widget.name.equals("Start", ignoreCase = true)
    val background = when {
        start -> VacuumStart
        stop -> VacuumStop
        on -> ActiveYellow
        else -> PopupCard
    }
    Column(
        modifier = modifier
            .height(120.dp)
            .clip(CardShape)
            .background(background)
            .clickable { viewModel.onTap(widget) }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        MdiIcon(widget.icon ?: "mdi:vacuum", tint = Color.Black, size = 24.dp)
        Text(widget.name ?: states[widget.entity]?.friendlyName.orEmpty(), color = Color.Black, fontSize = 14.sp)
    }
}

@Composable
fun MediaCard(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val tv = states[widget.entity]
    val apple = states[widget.companionEntity ?: "media_player.living_room_appletv"]
    val playing = apple?.state in setOf("playing", "paused")
    val on = tv?.state == "on" || playing
    Row(
        modifier = modifier
            .height(140.dp)
            .clip(CardShape)
            .background(if (on) ActiveYellow else ChipDark)
            .clickable { viewModel.onTap(widget) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing && apple?.entityPicture != null) {
            EntityPicture(apple.entityPicture, viewModel, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(16.dp))
        } else {
            MdiIcon(widget.icon ?: "mdi:television-classic", tint = if (on) Color.Black else ChipOnDark, size = 48.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(apple?.attrString("app_name") ?: widget.name ?: "TV", color = if (on) Color.Black else ChipOnDark, fontSize = 18.sp)
            Text(
                apple?.attrString("media_title") ?: apple?.state?.replaceFirstChar { it.uppercase() } ?: "Off",
                color = if (on) Color.Black else ChipOnDark.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
fun HistoryChart(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val entity = widget.entity ?: widget.graphEntity ?: widget.series.firstOrNull()?.entity
    var points by remember { mutableStateOf(listOf<Pair<Long, Double>>()) }
    LaunchedEffect(entity) {
        entity?.let { points = viewModel.client.history(it, 24) }
    }
    Column(
        modifier = modifier
            .height(180.dp)
            .clip(CardShape)
            .background(PopupCard)
            .padding(16.dp),
    ) {
        Text(widget.name ?: widget.series.firstOrNull()?.name ?: "History", color = TextDark, fontSize = 14.sp)
        Sparkline(points, Modifier.fillMaxSize(), AccentRed)
    }
}

@Composable
fun EnergyStats(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val states by viewModel.states.collectAsState()
    val solar = states.number("sensor.envoy_202234122877_current_power_production", 2, " kW")
    val net = states.number("sensor.envoy_202234122877_current_net_power_consumption", 2, " kW")
    val battery = states.number("input_number.battery_energy_helper", 3, " kWh", 0.001)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Solar" to solar, "Grid" to net, "Battery" to battery).forEach { (name, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(CardShape)
                    .background(PopupCard)
                    .padding(16.dp),
            ) {
                Text(value, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text(name, color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun TabsWidget(widget: WidgetNode, viewModel: HaViewModel, modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(widget.defaultTab ?: 0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .clip(ChipShape)
                .background(ChipDark.copy(alpha = 0.5f))
                .padding(4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            widget.tabs.forEachIndexed { index, tab ->
                val active = index == selected
                Row(
                    modifier = Modifier
                        .clip(ChipShape)
                        .background(if (active) ActiveYellow else Color.Transparent)
                        .clickable { selected = index }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MdiIcon(tab.icon, tint = if (active) Color.Black else ChipOnDark, size = 16.dp)
                    Text(tab.title.orEmpty(), color = if (active) Color.Black else ChipOnDark, fontSize = 14.sp)
                }
            }
        }
        widget.tabs.getOrNull(selected)?.let { WidgetTree(it.cards, viewModel) }
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

@Composable
fun PopupScaffold(
    popup: PopupNode,
    viewModel: HaViewModel,
    scrollContent: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor(popup.accent ?: "green")),
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon(popup.icon, tint = Color.Black, size = 22.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(popup.name.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ChipDark.copy(alpha = 0.8f))
                    .clickable { viewModel.closePopup() },
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon("mdi:close", tint = Color.White, size = 22.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        val bodyModifier = if (scrollContent) {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxSize()
        }
        Box(bodyModifier) {
            content()
        }
    }
}

private fun parseRadius(raw: String?): List<Dp> {
    val parts = raw?.split(Regex("\\s+"))?.mapNotNull { it.removeSuffix("px").toFloatOrNull() }
    if (parts == null || parts.size < 4) return listOf(36.dp, 36.dp, 36.dp, 36.dp)
    return parts.take(4).map { it.dp }
}
