package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.ui.theme.AccentBlue
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val DEFAULT_WEATHER_ENTITY = "weather.forecast_tankerland_ct"
private const val DEFAULT_TEMP_ENTITY = "sensor.st_00063154_temperature"

private enum class ForecastTab { Daily, Hourly }

@Composable
fun WeatherPopup(popup: PopupNode, viewModel: HaViewModel) {
    val ui by viewModel.ui.collectAsState()
    val context = ui.weatherPopupContext
    WeatherPopupBody(
        viewModel = viewModel,
        weatherEntity = context?.entityId ?: DEFAULT_WEATHER_ENTITY,
        tempEntity = DEFAULT_TEMP_ENTITY,
        focusDate = context?.focusDate,
        initialTab = context?.initialTab,
    )
}

@Composable
private fun WeatherPopupBody(
    viewModel: HaViewModel,
    weatherEntity: String,
    tempEntity: String,
    focusDate: LocalDate?,
    initialTab: String?,
) {
    val overlay = LocalOverlay.current
    val weather by viewModel.entityFlow(weatherEntity).collectAsState()
    val sunState by viewModel.entityFlow("sun.sun").collectAsState()
    val sunAbove = sunState?.state == "above_horizon"
    val today = LocalDate.now()

    var tab by remember(weatherEntity, focusDate, initialTab) {
        mutableStateOf(
            if (initialTab == "hourly") ForecastTab.Hourly else ForecastTab.Daily,
        )
    }
    var daily by remember { mutableStateOf(listOf<JsonObject>()) }
    var hourly by remember { mutableStateOf(listOf<JsonObject>()) }
    var dailyLoaded by remember { mutableStateOf(false) }
    var hourlyLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.client.currentBaseUrl, weatherEntity, weather?.state) {
        dailyLoaded = false
        daily = runCatching { viewModel.client.weatherForecast(weatherEntity, "daily") }.getOrDefault(emptyList())
        dailyLoaded = true
    }
    LaunchedEffect(viewModel.client.currentBaseUrl, weatherEntity, tab) {
        if (tab != ForecastTab.Hourly) return@LaunchedEffect
        hourlyLoaded = false
        hourly = runCatching { viewModel.client.weatherForecast(weatherEntity, "hourly") }.getOrDefault(emptyList())
        hourlyLoaded = true
    }

    val focusedDayForecast = focusDate?.let { date ->
        daily.firstOrNull { forecastMatchesDay(it, date) }
    }
    val showingFocusedDay = focusDate != null && focusDate != today && focusedDayForecast != null

    val tempSensor by viewModel.entityFlow(tempEntity).collectAsState()
    val currentTemp = tempSensor?.state?.toDoubleOrNull()
        ?: weather?.attrDouble("temperature")
    val temp = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("temperature")) ?: currentTemp
    } else {
        currentTemp
    }
    val humidity = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("humidity")) ?: weather?.attrDouble("humidity")
    } else {
        weather?.attrDouble("humidity")
    }
    val pressure = weather?.attrDouble("pressure")
    val wind = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("wind_speed")) ?: weather?.attrDouble("wind_speed")
    } else {
        weather?.attrDouble("wind_speed")
    }
    val windBearing = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("wind_bearing")) ?: weather?.attrDouble("wind_bearing")
    } else {
        weather?.attrDouble("wind_bearing")
    }
    val conditionState = if (showingFocusedDay) {
        forecastText(focusedDayForecast?.get("condition")).ifBlank { weather?.state }
    } else {
        weather?.state
    }
    val condition = weatherConditionLabel(conditionState)
    val updatedLabel = if (showingFocusedDay) {
        focusDateLabel(focusDate)
    } else {
        relativeAgeLabel(weather?.lastUpdated ?: weather?.lastChanged)
    }

    val todayHigh = daily.firstOrNull()?.let { forecastNumber(it["temperature"]) }
    val todayLow = daily.firstOrNull()?.let { forecastNumber(it["templow"]) }
    val cardHigh = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("temperature"))
    } else {
        todayHigh
    }
    val cardLow = if (showingFocusedDay) {
        forecastNumber(focusedDayForecast?.get("templow"))
    } else {
        todayLow
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CurrentConditionsCard(
            overlay = overlay,
            condition = condition,
            weatherState = conditionState,
            sunAbove = sunAbove,
            updatedLabel = updatedLabel,
            temp = temp,
            high = cardHigh,
            low = cardLow,
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pressure != null) {
                WeatherMetricRow(overlay, "mdi:gauge", "Air pressure", "${pressure.format(1)} hPa")
            }
            if (humidity != null) {
                WeatherMetricRow(overlay, "mdi:water-percent", "Humidity", "${humidity.format(0)}%")
            }
            if (wind != null) {
                val bearing = windBearing?.let { windDirectionLabel(it) }
                val windText = buildString {
                    append("${wind.format(1)} km/h")
                    if (!bearing.isNullOrBlank()) append(" ($bearing)")
                }
                WeatherMetricRow(overlay, "mdi:weather-windy", "Wind speed", windText)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Forecast:", color = overlay.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            ForecastTabRow(tab, overlay, onSelect = { tab = it })
            when (tab) {
                ForecastTab.Daily -> DailyForecastRow(
                    overlay = overlay,
                    forecasts = daily,
                    loading = !dailyLoaded,
                    sunAbove = sunAbove,
                    focusDate = focusDate,
                )
                ForecastTab.Hourly -> HourlyForecastRow(
                    overlay = overlay,
                    forecasts = hourly,
                    loading = !hourlyLoaded,
                    focusDate = focusDate,
                )
            }
        }

        val attribution = weather?.attrString("attribution")
        if (!attribution.isNullOrBlank()) {
            Text(
                attribution,
                color = overlay.muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CurrentConditionsCard(
    overlay: OverlayColors,
    condition: String,
    weatherState: String?,
    sunAbove: Boolean,
    updatedLabel: String,
    temp: Double?,
    high: Double?,
    low: Double?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(overlay.well)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MdiIcon(
                weatherIcon(weatherState, sunAbove),
                tint = weatherTint(weatherState, sunAbove),
                size = 56.dp,
            )
            Column {
                Text(
                    condition,
                    color = overlay.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (updatedLabel.isNotBlank()) {
                    Text(updatedLabel, color = overlay.muted, fontSize = 13.sp)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                temp?.let { "${it.format(0)} °C" } ?: "—",
                color = overlay.text,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
            if (high != null || low != null) {
                Text(
                    listOfNotNull(high?.let { "${it.format(0)} °C" }, low?.let { "${it.format(0)} °C" })
                        .joinToString(" / "),
                    color = overlay.muted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun WeatherMetricRow(
    overlay: OverlayColors,
    icon: String,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(icon, tint = AccentBlue, size = 22.dp)
        }
        Column {
            Text(label, color = overlay.muted, fontSize = 13.sp)
            Text(value, color = overlay.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ForecastTabRow(tab: ForecastTab, overlay: OverlayColors, onSelect: (ForecastTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ForecastTab.entries.forEach { item ->
            Column(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(item) },
                    )
                    .padding(bottom = 6.dp),
            ) {
                Text(
                    item.name,
                    color = if (tab == item) overlay.text else overlay.muted,
                    fontSize = 15.sp,
                    fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(48.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (tab == item) AccentBlue else overlay.text.copy(alpha = 0.08f)),
                )
            }
        }
    }
}

@Composable
private fun DailyForecastRow(
    overlay: OverlayColors,
    forecasts: List<JsonObject>,
    loading: Boolean,
    sunAbove: Boolean,
    focusDate: LocalDate?,
) {
    if (loading && forecasts.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            LoadingSpinner(color = overlay.muted)
        }
        return
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(focusDate, forecasts) {
        val date = focusDate ?: return@LaunchedEffect
        val index = forecasts.indexOfFirst { forecastMatchesDay(it, date) }
        if (index > 0) {
            scrollState.scrollTo(index * 66)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        forecasts.forEach { dayForecast ->
            val condition = forecastText(dayForecast["condition"])
            val high = forecastNumber(dayForecast["temperature"])
            val low = forecastNumber(dayForecast["templow"])
            val weekday = forecastShortDay(forecastText(dayForecast["datetime"]))
            val isFocused = focusDate != null && forecastMatchesDay(dayForecast, focusDate)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isFocused) AccentBlue.copy(alpha = 0.14f) else overlay.text.copy(alpha = 0f),
                    )
                    .padding(vertical = if (isFocused) 4.dp else 0.dp),
            ) {
                Text(weekday, color = overlay.muted, fontSize = 13.sp, maxLines = 1)
                MdiIcon(
                    weatherIcon(condition, sunAbove),
                    tint = weatherTint(condition, sunAbove),
                    size = 28.dp,
                )
                Text(high?.format(0, "°") ?: "—", color = overlay.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(low?.format(0, "°") ?: "—", color = overlay.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HourlyForecastRow(
    overlay: OverlayColors,
    forecasts: List<JsonObject>,
    loading: Boolean,
    focusDate: LocalDate?,
) {
    if (loading && forecasts.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            LoadingSpinner(color = overlay.muted)
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val items = buildList {
        var lastDate: LocalDate? = null
        forecasts.forEach { hourForecast ->
            val raw = forecastText(hourForecast["datetime"])
            val instant = forecastInstant(raw)
            val localDate = instant?.atZone(zone)?.toLocalDate()
            val dayLabel = if (localDate != null && localDate != lastDate) {
                lastDate = localDate
                forecastShortDay(raw)
            } else {
                ""
            }
            val hourLabel = instant?.atZone(zone)?.format(DateTimeFormatter.ofPattern("h a")).orEmpty()
            val condition = forecastText(hourForecast["condition"])
            val temp = forecastNumber(hourForecast["temperature"])
            val hour = instant?.atZone(zone)?.hour ?: 12
            add(
                HourlyForecastItem(
                    dayLabel = dayLabel,
                    hourLabel = hourLabel,
                    condition = condition,
                    temp = temp,
                    dayIcon = hour in 7..19,
                    localDate = localDate,
                ),
            )
        }
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(focusDate, items) {
        val date = focusDate ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.localDate == date }
        if (index > 0) {
            scrollState.scrollTo(index * 52)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            val isFocused = focusDate != null && item.localDate == focusDate
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .width(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isFocused) AccentBlue.copy(alpha = 0.14f) else overlay.text.copy(alpha = 0f),
                    )
                    .padding(vertical = if (isFocused) 4.dp else 0.dp),
            ) {
                Text(
                    item.dayLabel,
                    color = overlay.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.height(14.dp),
                )
                Text(item.hourLabel, color = overlay.muted, fontSize = 12.sp, maxLines = 1)
                MdiIcon(
                    weatherIcon(item.condition, item.dayIcon),
                    tint = weatherTint(item.condition, item.dayIcon),
                    size = 26.dp,
                )
                Text(item.temp?.format(0, "°") ?: "—", color = overlay.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private data class HourlyForecastItem(
    val dayLabel: String,
    val hourLabel: String,
    val condition: String,
    val temp: Double?,
    val dayIcon: Boolean,
    val localDate: LocalDate?,
)

private fun forecastMatchesDay(forecast: JsonObject, day: LocalDate): Boolean {
    val raw = forecastText(forecast["datetime"])
    if (raw.startsWith(day.toString())) return true
    val instant = forecastInstant(raw)
    return instant?.atZone(ZoneId.systemDefault())?.toLocalDate() == day
}

private fun focusDateLabel(date: LocalDate?): String {
    if (date == null) return ""
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    }
}

private fun weatherConditionLabel(raw: String?): String {
    val value = raw?.replace('_', ' ')?.trim().orEmpty()
    if (value.isBlank()) return "—"
    return value.replaceFirstChar { it.uppercase() }
}

private fun relativeAgeLabel(instant: Instant?): String {
    if (instant == null) return ""
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minutes ago"
        else -> {
            val hours = minutes / 60
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
    }
}

private fun windDirectionLabel(bearing: Double): String {
    val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return dirs[((bearing % 360) / 22.5).roundToInt() % 16]
}

private fun forecastText(element: kotlinx.serialization.json.JsonElement?): String {
    val primitive = element as? JsonPrimitive ?: return ""
    return primitive.contentOrNull ?: primitive.toString().trim('"')
}

private fun forecastNumber(element: kotlinx.serialization.json.JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

private fun forecastInstant(raw: String): Instant? {
    if (raw.isBlank()) return null
    return runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}

private fun forecastShortDay(raw: String): String {
    val instant = forecastInstant(raw)
    if (instant == null) return raw.take(3)
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE"))
    }
}
