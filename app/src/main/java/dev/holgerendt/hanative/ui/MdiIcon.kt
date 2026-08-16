package dev.holgerendt.hanative.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.DashboardLoader

private var cachedFont: FontFamily? = null
private var cachedCodes: Map<String, String>? = null

@Composable
fun MdiIcon(
    name: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
    fontSize: TextUnit = (size.value * 0.92f).sp,
) {
    val context = LocalContext.current
    val font = remember {
        cachedFont ?: runCatching {
            FontFamily(Typeface.createFromAsset(context.assets, "fonts/materialdesignicons-webfont.ttf"))
        }.getOrDefault(FontFamily.Default).also { cachedFont = it }
    }
    val codes = remember {
        cachedCodes ?: runCatching { DashboardLoader.loadCodepoints(context) }.getOrDefault(emptyMap())
            .also { cachedCodes = it }
    }
    val key = name?.let { if (it.startsWith("mdi:")) it else "mdi:$it" }
    val hex = key?.let { codes[it] }
    val glyph = hex?.toInt(16)?.let { Character.toChars(it).concatToString() } ?: "•"
    Text(
        text = glyph,
        modifier = modifier.size(size),
        color = tint,
        fontSize = fontSize,
        fontFamily = font,
        maxLines = 1,
    )
}

fun weatherIcon(condition: String?, day: Boolean): String {
    val normalized = condition?.lowercase()?.replace('_', '-') ?: "cloudy"
    return when {
        normalized.contains("lightning") || normalized.contains("thunder") -> "mdi:weather-lightning"
        normalized.contains("pouring") -> "mdi:weather-pouring"
        normalized.contains("rain") -> "mdi:weather-rainy"
        normalized.contains("snow") -> "mdi:weather-snowy"
        normalized.contains("fog") || normalized.contains("hail") -> "mdi:weather-fog"
        normalized.contains("wind") -> "mdi:weather-windy"
        normalized.contains("partly") -> "mdi:weather-partly-cloudy"
        normalized.contains("cloud") -> "mdi:weather-cloudy"
        normalized.contains("clear") || normalized.contains("sunny") ->
            if (day) "mdi:weather-sunny" else "mdi:weather-night"
        else -> if (day) "mdi:weather-sunny" else "mdi:weather-night"
    }
}
