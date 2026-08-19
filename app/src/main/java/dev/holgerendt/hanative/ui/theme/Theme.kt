package dev.holgerendt.hanative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** mysmarthome: white wall, gray000 tiles, gray800 text. */
val ScreenBackground = Color(0xFFFFFFFF)
val CardLight = Color(0xFFF3F1EC)
val PopupCard = Color(0xF2F3F1EC)
/** Solid popup sheet so Lovelace’s 42% mix is not invisible on a white wall. */
val PopupOverlay = Color(0xFFF3F1EC)
val DockBackground = Color(0xFF2A2A2A)
val TextDark = Color(0xFF2A2A2A)
val TextMuted = Color(0xFF2A2A2A).copy(alpha = 0.7f)
val ChipDark = Color(0xFF2C2C2C)
val ChipOnDark = Color(0xFFF5F5F5)
val ActiveYellow = Color(0xFFFFC107)
val ActiveLight = Color(0xFFFFD4C1)
val AccentGreen = Color(0xFFC5E1A5)
val AccentBlue = Color(0xFF90CAF9)
/** HA more-info history line (`--graph-color` / info). */
val HistoryGraph = Color(0xFF03A9F4)
/** HA history chart grid (horizontal and vertical ticks). */
val HistoryGrid = Color(0xFF2A2A2A).copy(alpha = 0.18f)
val HistoryGridZero = Color(0xFF2A2A2A).copy(alpha = 0.45f)

data class OverlayColors(
    val sheet: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val well: Color,
    val onWell: Color,
    val grid: Color,
    val gridZero: Color,
    val dark: Boolean,
)

val OverlayHome = OverlayColors(
    sheet = ScreenBackground,
    card = PopupCard,
    text = TextDark,
    muted = TextMuted,
    well = CardLight,
    onWell = TextDark,
    grid = HistoryGrid,
    gridZero = HistoryGridZero,
    dark = false,
)

val OverlayPopup = OverlayColors(
    sheet = Color(0xFF2C2C2C),
    card = Color(0xFF3A3A3A),
    text = Color(0xFFF5F5F5),
    muted = Color(0xFFB8B8B8),
    well = Color(0xFF404040),
    onWell = Color.White,
    grid = Color.White.copy(alpha = 0.14f),
    gridZero = Color.White.copy(alpha = 0.32f),
    dark = true,
)

/** Frosted light sheet for popups (circular dark icon, light close). 90% opaque. */
val OverlayLightPopup = OverlayColors(
    sheet = Color(0xE6EBE9E4),
    card = Color.White,
    text = TextDark,
    muted = TextMuted,
    well = CardLight,
    onWell = TextDark,
    grid = HistoryGrid,
    gridZero = HistoryGridZero,
    dark = false,
)

val OverlayMoreInfo = OverlayPopup.copy(sheet = Color(0xFF1C1C1C), card = Color(0xFF252525))

val LocalOverlay = staticCompositionLocalOf { OverlayHome }

val TabActiveStart = Color(0xFFF3A6C4)
val TabActiveEnd = Color(0xFFC5A3E0)
val AccentOrange = Color(0xFFFFCC80)
val AccentYellow = Color(0xFFFFE082)
val AccentRed = Color(0xFFEF9A9A)
val AccentPurple = Color(0xFFCE93D8)
val AccentPink = Color(0xFFF48FB1)
val AccentBlueDark = Color(0xFF9FA8DA)
val VacuumStart = Color(0xFFA5DD9B)
val VacuumStop = Color(0xFFFF8A8A)

fun accentColor(name: String?): Color = when (name?.removePrefix("var(--")?.removeSuffix(")")) {
    "green" -> AccentGreen
    "blue" -> AccentBlue
    "orange" -> AccentOrange
    "yellow" -> AccentYellow
    "red" -> AccentRed
    "purple" -> AccentPurple
    "pink" -> AccentPink
    "blue-dark" -> AccentBlueDark
    else -> AccentGreen
}

private val colors = lightColorScheme(
    background = ScreenBackground,
    surface = CardLight,
    onBackground = TextDark,
    onSurface = TextDark,
    primary = ActiveYellow,
    onPrimary = Color.Black,
)

@Composable
fun HaNativeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextDark),
            bodyLarge = TextStyle(fontSize = 16.sp, color = TextDark),
        ),
        content = content,
    )
}
