package dev.holgerendt.hanative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** mysmarthome: white wall, gray000 tiles, gray800 text. */
val ScreenBackground = Color(0xFFFFFFFF)
val CardLight = Color(0xFFF3F1EC)
val PopupCard = Color(0xE6F3F1EC)
val DockBackground = Color(0xFF2A2A2A)
val TextDark = Color(0xFF2A2A2A)
val TextMuted = Color(0xFF2A2A2A).copy(alpha = 0.7f)
val ChipDark = Color(0xFF2C2C2C)
val ChipOnDark = Color(0xFFF5F5F5)
val ActiveYellow = Color(0xFFFFC107)
val ActiveLight = Color(0xFFFFD4C1)
val AccentGreen = Color(0xFFC5E1A5)
val AccentBlue = Color(0xFF90CAF9)
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
