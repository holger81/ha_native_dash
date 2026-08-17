package dev.holgerendt.hanative.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.EntityState
import dev.holgerendt.hanative.data.HaCalendarEvent
import dev.holgerendt.hanative.data.HistoryBucket
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.HistoryGraph
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.widgets.EntityPicture
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val SkipAttributes = setOf(
    "friendly_name",
    "icon",
    "entity_picture",
    "supported_features",
    "supported_color_modes",
    "restored",
    "id",
    "user_id",
    "editable",
    "attribution",
    "forecast",
    "access_token",
    "next_dawn",
    "next_dusk",
    "next_midnight",
    "next_noon",
    "next_rising",
    "next_setting",
)

private val HistoryDomains = setOf(
    "sensor",
    "number",
    "input_number",
    "binary_sensor",
    "switch",
    "light",
    "lock",
    "cover",
    "fan",
    "climate",
)

private val ToggleDomains = setOf(
    "switch",
    "input_boolean",
    "fan",
    "automation",
    "script",
    "scene",
    "humidifier",
    "remote",
    "siren",
)

@Composable
fun MoreInfoDialog(entityId: String, viewModel: HaViewModel) {
    val states by viewModel.states.collectAsState()
    val entity = states[entityId]
    val domain = entityId.substringBefore('.')
    InWindowOverlay(
        onDismiss = { viewModel.closeMoreInfo() },
        dismissOnScrim = true,
        scrim = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(CardLight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoreInfoTitle(entityId, entity, viewModel)
            MoreInfoStateRow(entityId, entity, domain, viewModel)
            MoreInfoControls(entityId, entity, domain, viewModel)
            if (domain in HistoryDomains) {
                MoreInfoHistory(entityId, entity, viewModel)
            }
            MoreInfoExtras(entityId, entity, domain, viewModel)
            MoreInfoAttributes(entity)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { viewModel.closeMoreInfo() }, modifier = Modifier.align(Alignment.End)) {
                Text("Close", color = TextDark)
            }
        }
    }
}

@Composable
private fun MoreInfoTitle(entityId: String, entity: EntityState?, viewModel: HaViewModel) {
    var deviceName by remember(entityId) { mutableStateOf<String?>(null) }
    LaunchedEffect(entityId, viewModel.client.currentBaseUrl) {
        deviceName = runCatching { viewModel.client.deviceNameFor(entityId) }.getOrNull()
    }
    if (!deviceName.isNullOrBlank()) {
        Text(deviceName!!, color = TextMuted, fontSize = 13.sp)
    }
    Text(entity?.friendlyName ?: entityId, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MoreInfoStateRow(entityId: String, entity: EntityState?, domain: String, viewModel: HaViewModel) {
    val unit = entity?.attrString("unit_of_measurement").orEmpty()
    val numeric = domain in setOf("sensor", "number", "input_number") && entity?.state?.toDoubleOrNull() != null
    val display = when {
        numeric && unit.isNotBlank() -> "${entity?.state} $unit"
        numeric -> entity?.state.orEmpty()
        else -> entity?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    }
    if (domain == "person") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntityPicture(
                path = entity?.entityPicture,
                viewModel = viewModel,
                modifier = Modifier.size(56.dp).clip(CircleShape),
            )
            Column {
                Text(display, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.Light)
                entity?.lastChanged?.let { Text(it.relativeToNow(), color = TextMuted, fontSize = 13.sp) }
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MdiIcon(moreInfoIcon(entity, domain), tint = HistoryGraph, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(entity?.friendlyName ?: entityId, color = TextDark, fontSize = 16.sp)
            entity?.lastChanged?.let { Text(it.relativeToNow(), color = TextMuted, fontSize = 13.sp) }
        }
        Text(display, color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

private fun moreInfoIcon(entity: EntityState?, domain: String): String {
    entity?.attrString("icon")?.let { return it }
    return when (entity?.attrString("device_class") ?: domain) {
        "power", "energy", "current", "voltage" -> "mdi:flash"
        "temperature" -> "mdi:thermometer"
        "humidity" -> "mdi:water-percent"
        "battery" -> "mdi:battery"
        "aqi" -> "mdi:air-filter"
        "lock" -> "mdi:lock"
        "cover" -> "mdi:window-shutter"
        "person" -> "mdi:account"
        "light" -> "mdi:lightbulb"
        else -> "mdi:information-outline"
    }
}

@Composable
private fun MoreInfoControls(entityId: String, entity: EntityState?, domain: String, viewModel: HaViewModel) {
    val yellow = ButtonDefaults.buttonColors(ActiveYellow)
    when (domain) {
        "light" -> {
            val states by viewModel.states.collectAsState()
            val pct = states.brightnessPct(entityId).toFloat()
            var value by remember(pct) { mutableFloatStateOf(pct) }
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { viewModel.setBrightness(entityId, value.roundToInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = ActiveYellow, activeTrackColor = ActiveYellow),
            )
            ActionRow {
                ActionButton("On") { viewModel.callEntityService(entityId, "turn_on") }
                ActionButton("Off") { viewModel.callEntityService(entityId, "turn_off") }
            }
        }
        "climate" -> {
            val current = entity?.attrDouble("current_temperature")
            val target = entity?.attrDouble("temperature") ?: entity?.attrDouble("target_temp_high") ?: 20.0
            Text("Current  ${current.format(1, "°")}", color = TextMuted, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("–", fontSize = 28.sp, color = TextDark, modifier = Modifier.clickable {
                    viewModel.setTemperature(entityId, target - 0.5)
                }.padding(8.dp))
                Text(target.format(1, "°"), fontSize = 28.sp, color = TextDark, fontWeight = FontWeight.Medium)
                Text("+", fontSize = 28.sp, color = TextDark, modifier = Modifier.clickable {
                    viewModel.setTemperature(entityId, target + 0.5)
                }.padding(8.dp))
            }
        }
        "lock" -> ActionRow {
            ActionButton("Lock") { viewModel.callEntityService(entityId, "lock") }
            ActionButton("Unlock") { viewModel.callEntityService(entityId, "unlock") }
        }
        "cover" -> ActionRow {
            ActionButton("Open") { viewModel.callEntityService(entityId, "open_cover") }
            ActionButton("Stop") { viewModel.callEntityService(entityId, "stop_cover") }
            ActionButton("Close") { viewModel.callEntityService(entityId, "close_cover") }
        }
        "vacuum" -> ActionRow {
            ActionButton("Start") { viewModel.callEntityService(entityId, "start") }
            ActionButton("Pause") { viewModel.callEntityService(entityId, "pause") }
            ActionButton("Dock") { viewModel.callEntityService(entityId, "return_to_base") }
        }
        "media_player" -> ActionRow {
            ActionButton(if (entity?.state == "playing") "Pause" else "Play") {
                viewModel.callEntityService(entityId, "media_play_pause")
            }
        }
        in ToggleDomains -> Button(
            onClick = { viewModel.toggleEntity(entityId) },
            colors = yellow,
        ) {
            Text("Toggle", color = Color.Black)
        }
        else -> Unit
    }
}

@Composable
private fun MoreInfoHistory(entityId: String, entity: EntityState?, viewModel: HaViewModel) {
    var buckets by remember(entityId) { mutableStateOf(listOf<HistoryBucket>()) }
    LaunchedEffect(entityId, viewModel.client.currentBaseUrl) {
        buckets = runCatching { viewModel.client.historyBuckets(entityId, 24) }.getOrDefault(emptyList())
    }
    Spacer(Modifier.height(8.dp))
    Text("History", color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Text("5-minute aggregated", color = TextMuted, fontSize = 12.sp)
    if (buckets.size < 2) {
        Text("No history yet", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val unit = entity?.attrString("unit_of_measurement").orEmpty()
    HistoryGraphChart(buckets = buckets, unit = unit)
}

@Composable
private fun HistoryGraphChart(buckets: List<HistoryBucket>, unit: String) {
    val textMeasurer = rememberTextMeasurer()
    val endMs = remember { Instant.now().toEpochMilli() }
    val startMs = endMs - 24L * 60L * 60L * 1000L
    val dataMin = buckets.minOf { it.min }
    val dataMax = buckets.maxOf { it.max }
    val (yMin, yMax, yTicks) = niceAxis(dataMin, dataMax)
    val zone = ZoneId.systemDefault()
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    val decimals = historyDecimals(yMin, yMax)
    val labelStyle = TextStyle(color = TextMuted, fontSize = 10.sp)
    val unitStyle = TextStyle(color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(top = 4.dp),
    ) {
        val left = 40.dp.toPx()
        val bottom = 24.dp.toPx()
        val top = 16.dp.toPx()
        val plotW = size.width - left
        val plotH = size.height - bottom - top
        val ySpan = (yMax - yMin).takeIf { it != 0.0 } ?: 1.0
        val tSpan = (endMs - startMs).toFloat().coerceAtLeast(1f)
        fun xOf(time: Long) = left + plotW * (time - startMs).toFloat() / tSpan
        fun yOf(value: Double) = top + ((yMax - value) / ySpan * plotH).toFloat()

        yTicks.forEach { tick ->
            val y = yOf(tick)
            drawLine(
                color = TextMuted.copy(alpha = if (tick == 0.0) 0.45f else 0.18f),
                start = Offset(left, y),
                end = Offset(size.width, y),
                strokeWidth = if (tick == 0.0) 2f else 1f,
            )
            val label = tick.format(decimals)
            val layout = textMeasurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset((left - layout.size.width - 6.dp.toPx()).coerceAtLeast(0f), y - layout.size.height / 2f),
            )
        }
        if (unit.isNotBlank()) {
            val unitLayout = textMeasurer.measure(unit, unitStyle)
            drawText(textLayoutResult = unitLayout, topLeft = Offset(left, 0f))
        }

        val band = Path()
        buckets.forEachIndexed { index, bucket ->
            val x = xOf(bucket.startMs)
            val y = yOf(bucket.max)
            if (index == 0) band.moveTo(x, y) else band.lineTo(x, y)
        }
        buckets.asReversed().forEach { bucket ->
            band.lineTo(xOf(bucket.startMs), yOf(bucket.min))
        }
        band.close()
        drawPath(band, HistoryGraph.copy(alpha = 0.22f))

        val line = Path()
        buckets.forEachIndexed { index, bucket ->
            val x = xOf(bucket.startMs)
            val y = yOf(bucket.mean)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, HistoryGraph, style = Stroke(width = 3.5f))

        val xLabelCount = 5
        for (i in 0..xLabelCount) {
            val time = startMs + (endMs - startMs) * i / xLabelCount
            val instant = Instant.ofEpochMilli(time).atZone(zone)
            val label = if (i == 0 || (instant.hour == 0 && instant.minute < 20)) {
                instant.format(dateFmt)
            } else {
                instant.format(timeFmt)
            }
            val layout = textMeasurer.measure(label, labelStyle)
            val x = xOf(time) - layout.size.width / 2f
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x.coerceIn(0f, size.width - layout.size.width), size.height - layout.size.height),
            )
        }
    }
}

/** HA-style nice y-axis that includes zero when the series crosses it. */
private fun niceAxis(dataMin: Double, dataMax: Double): Triple<Double, Double, List<Double>> {
    var min = dataMin
    var max = dataMax
    if (min == max) {
        min -= 1.0
        max += 1.0
    }
    val pad = (max - min) * 0.08
    min -= pad
    max += pad
    if (dataMin < 0 && dataMax > 0) {
        min = minOf(min, 0.0)
        max = maxOf(max, 0.0)
    }
    val ticks = 5
    val range = niceCeil(max - min)
    val step = niceCeil(range / ticks)
    val niceMin = floor(min / step) * step
    val niceMax = ceil(max / step) * step
    val values = mutableListOf<Double>()
    var tick = niceMin
    var guard = 0
    while (tick <= niceMax + step / 2 && guard++ < 12) {
        values += tick
        tick += step
    }
    return Triple(niceMin, niceMax, values)
}

private fun niceCeil(value: Double): Double {
    val abs = kotlin.math.abs(value).coerceAtLeast(1e-9)
    val exp = floor(log10(abs))
    val mag = 10.0.pow(exp)
    val residual = abs / mag
    val nice = when {
        residual <= 1.0 -> 1.0
        residual <= 2.0 -> 2.0
        residual <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

@Composable
private fun MoreInfoExtras(entityId: String, entity: EntityState?, domain: String, viewModel: HaViewModel) {
    when (domain) {
        "calendar" -> CalendarEvents(entityId, viewModel)
        "camera" -> CameraSnapshot(entityId, viewModel)
        "weather" -> {
            val temp = entity?.attrDouble("temperature")
            val humidity = entity?.attrDouble("humidity")
            val wind = entity?.attrDouble("wind_speed")
            Text(
                listOfNotNull(
                    temp?.let { "Temp ${it.format(1, "°")}" },
                    humidity?.let { "Humidity ${it.format(0, "%")}" },
                    wind?.let { "Wind ${it.format(1)} km/h" },
                ).joinToString("   ·   "),
                color = TextDark,
                fontSize = 14.sp,
            )
        }
        else -> Unit
    }
}

@Composable
private fun CalendarEvents(entityId: String, viewModel: HaViewModel) {
    var events by remember(entityId) { mutableStateOf(listOf<HaCalendarEvent>()) }
    LaunchedEffect(entityId, viewModel.client.currentBaseUrl) {
        val now = Instant.now()
        events = runCatching {
            viewModel.client.calendarEvents(entityId, now, now.plus(Duration.ofDays(7)))
        }.getOrDefault(emptyList()).take(6)
    }
    if (events.isEmpty()) {
        Text("No upcoming events", color = TextMuted, fontSize = 13.sp)
        return
    }
    Text("Upcoming", color = TextMuted, fontSize = 12.sp)
    events.forEach { event ->
        val whenText = event.start?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
            ?: event.startDate?.toString()
            ?: ""
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(event.summary, color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (whenText.isNotBlank()) Text(whenText, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CameraSnapshot(entityId: String, viewModel: HaViewModel) {
    var bytes by remember(entityId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(entityId, viewModel.client.currentBaseUrl) {
        bytes = runCatching { viewModel.client.cameraSnapshot(entityId) }.getOrNull()
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = entityId,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text("No snapshot", color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun MoreInfoAttributes(entity: EntityState?) {
    val entries = entity?.attributes.orEmpty().entries
        .filter { it.key !in SkipAttributes }
        .filter { it.value.isCompact() }
        .take(12)
    if (entries.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text("Attributes", color = TextMuted, fontSize = 12.sp)
    entries.forEach { (key, value) ->
        Text("$key: ${value.pretty()}", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(ActiveYellow)) {
        Text(label, color = Color.Black)
    }
}

private fun Instant.relativeToNow(): String {
    val seconds = Duration.between(this, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> if (seconds <= 1) "1 second ago" else "$seconds seconds ago"
        seconds < 90 -> "1 minute ago"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        seconds < 5400 -> "1 hour ago"
        seconds < 86400 -> "${seconds / 3600} hours ago"
        seconds < 172800 -> "1 day ago"
        else -> "${seconds / 86400} days ago"
    }
}

private fun historyDecimals(min: Double, max: Double): Int {
    val span = kotlin.math.abs(max - min)
    return if (span < 2) 1 else 0
}

private fun JsonElement.isCompact(): Boolean = when (this) {
    is JsonPrimitive -> (contentOrNull?.length ?: 0) <= 120
    is JsonArray -> size <= 6
    is JsonObject -> size <= 4 && toString().length <= 160
    else -> false
}

private fun JsonElement.pretty(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonArray -> joinToString(", ") { it.pretty() }.take(80)
    is JsonObject -> toString().take(80)
    else -> toString().take(80)
}
