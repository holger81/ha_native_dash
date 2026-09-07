package dev.holgerendt.hanative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** mysmarthome light theme palette (greatroom-wall.yaml / paper-buttons-row). */
val Gray000 = Color(0xFFEDEFF2)
val Gray800 = Color(0xFF0F0F10)
val ThemeBlack = Color(0xFF28282A)
val ThemeWhite = Color(0xFFF5F7FA)

val ScreenBackground = Color(0xFFFFFFFF)
val CardLight = Gray000
val PopupCard = Color(0xF2EDEFF2)
val DockBackground = Color(0xFF2A2A2A)
val TextDark = ThemeBlack
val TextMuted = ThemeBlack.copy(alpha = 0.7f)
val ChipDark = Gray800
val ChipOnDark = Gray000
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

/** Warm glass sheet for popups. */
val OverlayLightPopup = OverlayColors(
    sheet = Color(0xF7FFFCFA),
    card = Color(0xFFFFFFFF),
    text = TextDark,
    muted = TextMuted,
    well = Color(0xFFF3F0EA),
    onWell = TextDark,
    grid = HistoryGrid,
    gridZero = HistoryGridZero,
    dark = false,
)

val PopupScrim = Color(0x8C161310)

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

/** Lovelace `--active-big`: peach → pink → blue at 145°. */
private val ActiveBigGradient: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color(0xFFFFDCB2),
        0.6f to Color(0xFFFFB0E9),
        1f to Color(0xFF689CFF),
    ),
    start = Offset.Zero,
    end = Offset(200f, 280f),
)

fun activeBigBrush(): Brush = ActiveBigGradient

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
