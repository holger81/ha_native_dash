package dev.holgerendt.hanative.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.holgerendt.hanative.data.ConnectionState
import dev.holgerendt.hanative.data.QrCodes
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.PopupCard
import dev.holgerendt.hanative.ui.theme.ScreenBackground
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.widgets.ChipRow
import dev.holgerendt.hanative.ui.widgets.PersonCard
import dev.holgerendt.hanative.ui.widgets.PopupScaffold
import dev.holgerendt.hanative.ui.widgets.RoomGrid
import dev.holgerendt.hanative.ui.widgets.WeatherHeader
import dev.holgerendt.hanative.ui.widgets.WidgetTree
import kotlin.math.roundToInt

@Composable
fun HaApp(viewModel: HaViewModel) {
    val ui by viewModel.ui.collectAsState()
    val connection by viewModel.connection.collectAsState()
    if (ui.showSetup || ui.dashboard == null) {
        SetupScreen(viewModel)
        return
    }
    val drawerState = rememberDrawerState(if (ui.drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    LaunchedEffect(ui.drawerOpen) {
        if (ui.drawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        viewModel.setDrawer(drawerState.currentValue == DrawerValue.Open)
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ChipDark) {
                DrawerMenu(viewModel)
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(ScreenBackground)) {
            HomeScreen(viewModel)
            val popup = viewModel.popup(ui.popupHash)
            if (popup != null) {
                Dialog(
                    onDismissRequest = { viewModel.closePopup() },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    PopupHost(popup, viewModel)
                }
            }
            ui.moreInfoId?.let { MoreInfoDialog(it, viewModel) }
            if (connection !is ConnectionState.Connected) {
                Text(
                    text = when (connection) {
                        is ConnectionState.Connecting -> "Connecting…"
                        is ConnectionState.Error -> (connection as ConnectionState.Error).message
                        else -> "Disconnected"
                    },
                    color = ActiveYellow,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerMenu(viewModel: HaViewModel) {
    val items = listOf(
        "Weather" to "#weather",
        "Power" to "#power",
        "Cars" to "#bil",
        "Staubinator" to "#staubinator",
        "Camera" to "#camerafront_view",
        "Settings" to "#settings",
    )
    Column(Modifier.fillMaxHeight().width(280.dp).padding(20.dp)) {
        Text("Greatroom Wall", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        items.forEach { (label, hash) ->
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewModel.openPopup(hash) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val ui by viewModel.ui.collectAsState()
        Text("Remote setup", color = ChipOnDark, fontSize = 12.sp)
        ui.remoteUrls.firstOrNull()?.let {
            Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("PIN ${ui.remotePin}", color = ActiveYellow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Text(
            "Home Assistant connection",
            color = ActiveYellow,
            modifier = Modifier.clickable { viewModel.openSetup() }.padding(8.dp),
        )
    }
}

@Composable
private fun HomeScreen(viewModel: HaViewModel) {
    val ui by viewModel.ui.collectAsState()
    val home = ui.dashboard?.home ?: return
    val menu = home.header.firstOrNull { it.type == "menu_button" }
    val weather = home.header.firstOrNull { it.type == "weather_header" }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
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
                MdiIcon(menu?.icon ?: "mdi:menu", tint = Color.White, size = 28.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                home.people.forEach { PersonCard(it, viewModel) }
            }
            Spacer(Modifier.weight(1f))
            weather?.let { WeatherHeader(it, viewModel) }
        }
        home.chips?.let {
            Spacer(Modifier.height(16.dp))
            ChipRow(it, viewModel)
        }
        Spacer(Modifier.height(12.dp))
        RoomGrid(home.rooms, viewModel, Modifier.fillMaxSize())
    }
}

@Composable
private fun PopupHost(popup: PopupNode, viewModel: HaViewModel) {
    PopupScaffold(popup, viewModel) {
        when (popup.hash) {
            "#weather" -> WeatherPopup(viewModel)
            else -> WidgetTree(popup.cards, viewModel)
        }
    }
}

@Composable
private fun WeatherPopup(viewModel: HaViewModel) {
    val states by viewModel.states.collectAsState()
    val weather = states["weather.forecast_tankerland_ct"]
    val temp = states["sensor.st_00063154_temperature"]?.state?.toDoubleOrNull()
    val feels = states["sensor.st_00063154_feels_like"]?.state?.toDoubleOrNull()
    val wind = states["sensor.st_00063154_wind_speed_average"]?.state?.toDoubleOrNull()
    val rain = states["sensor.rain_sum_today"]?.state
    val day = states["sun.sun"]?.state == "above_horizon"
    var forecasts by remember { mutableStateOf(listOf<Map<String, String>>()) }
    LaunchedEffect(Unit) {
        val raw = runCatching { viewModel.client.weatherForecast("weather.forecast_tankerland_ct") }.getOrDefault(emptyList())
        forecasts = raw.map { obj ->
            mapOf(
                "condition" to (obj["condition"]?.toString()?.trim('"') ?: ""),
                "temp" to (obj["temperature"]?.toString() ?: ""),
                "templow" to (obj["templow"]?.toString() ?: ""),
                "datetime" to (obj["datetime"]?.toString()?.trim('"') ?: ""),
            )
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(PopupCard)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Now", color = TextMuted, fontSize = 14.sp)
            MdiIcon(weatherIcon(weather?.state, day), tint = TextDark, size = 96.dp)
            Text("${temp.format(1, "°")}  ${feels.format(1, "°")}", color = TextDark, fontSize = 42.sp, fontWeight = FontWeight.Light)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(weather?.state?.replaceFirstChar { it.uppercase() }.orEmpty(), color = TextDark)
                Text("${wind.format(1)} km/h", color = TextDark)
                Text("${rain ?: "—"} mm", color = TextDark)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            forecasts.take(5).forEach { dayForecast ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(PopupCard)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(dayForecast["datetime"]?.take(10).orEmpty(), color = TextMuted, fontSize = 11.sp)
                    MdiIcon(weatherIcon(dayForecast["condition"], true), tint = TextDark, size = 28.dp)
                    Text(dayForecast["temp"]?.let { "$it°" } ?: "—", color = TextDark, fontWeight = FontWeight.Medium)
                    Text(dayForecast["templow"]?.let { "$it°" } ?: "", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MoreInfoDialog(entityId: String, viewModel: HaViewModel) {
    val states by viewModel.states.collectAsState()
    val entity = states[entityId]
    Dialog(onDismissRequest = { viewModel.closeMoreInfo() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(CardLight)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(entity?.friendlyName ?: entityId, color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(entity?.state ?: "unknown", color = TextMuted, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            when (entityId.substringBefore('.')) {
                "light" -> {
                    val pct = states.brightnessPct(entityId).toFloat()
                    var value by remember(pct) { mutableFloatStateOf(pct) }
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        onValueChangeFinished = { viewModel.setBrightness(entityId, value.roundToInt()) },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(thumbColor = ActiveYellow, activeTrackColor = ActiveYellow),
                    )
                    Button(onClick = { viewModel.toggleEntity(entityId) }, colors = ButtonDefaults.buttonColors(ActiveYellow)) {
                        Text("Toggle", color = Color.Black)
                    }
                }
                "climate" -> {
                    val target = entity?.attrDouble("temperature") ?: 20.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("–", fontSize = 28.sp, color = TextDark, modifier = Modifier.clickable {
                            viewModel.setTemperature(entityId, target - 0.5)
                        }.padding(8.dp))
                        Text(target.format(1, "°"), fontSize = 28.sp, color = TextDark)
                        Text("+", fontSize = 28.sp, color = TextDark, modifier = Modifier.clickable {
                            viewModel.setTemperature(entityId, target + 0.5)
                        }.padding(8.dp))
                    }
                }
                else -> Button(onClick = { viewModel.toggleEntity(entityId) }, colors = ButtonDefaults.buttonColors(ActiveYellow)) {
                    Text("Toggle", color = Color.Black)
                }
            }
            Spacer(Modifier.height(12.dp))
            entity?.attributes?.entries?.take(12)?.forEach { (key, value) ->
                Text("$key: ${value.toString().take(80)}", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SetupScreen(viewModel: HaViewModel) {
    val ui by viewModel.ui.collectAsState()
    val busy = ui.setupBusy
    val error = ui.setupError
    var localUrl by remember { mutableStateOf(viewModel.savedUrl.ifBlank { "http://homeassistant.local:8123" }) }
    var localToken by remember { mutableStateOf(viewModel.savedToken) }
    var showOnDevice by remember { mutableStateOf(false) }
    val setupUrl = ui.remoteUrls.firstOrNull().orEmpty()
    val qr = remember(setupUrl) {
        if (setupUrl.isBlank()) null else runCatching { QrCodes.bitmap(setupUrl).asImageBitmap() }.getOrNull()
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = ActiveYellow,
        unfocusedBorderColor = ChipOnDark,
        focusedLabelColor = ActiveYellow,
        unfocusedLabelColor = ChipOnDark,
        cursorColor = ActiveYellow,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(ChipDark)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set up from your phone", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Scan the QR code or open the URL on the same Wi‑Fi. Enter the PIN below, then paste the Home Assistant token.",
                color = ChipOnDark,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = "Setup QR code",
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            ui.remoteUrls.forEach { url ->
                Text(url, color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            }
            if (ui.remoteUrls.isEmpty()) {
                Text("Waiting for a network address…", color = ChipOnDark)
            }
            Text("PIN", color = ChipOnDark, fontSize = 13.sp)
            Text(
                ui.remotePin.chunked(3).joinToString(" "),
                color = ActiveYellow,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = { viewModel.rotatePin() }) {
                Text("New PIN", color = ChipOnDark)
            }
            ui.managementError?.let { Text("Management server: $it", color = Color(0xFFFF8A80), fontSize = 12.sp) }
            if (!showOnDevice && error != null) {
                Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(ChipDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("On this panel", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Only needed if you cannot reach the tablet from another device.", color = ChipOnDark, fontSize = 14.sp)
            if (!showOnDevice) {
                Button(
                    onClick = { showOnDevice = true },
                    colors = ButtonDefaults.buttonColors(ChipOnDark.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enter token here", color = Color.White)
                }
            } else {
                OutlinedTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    label = { Text("URL") },
                    placeholder = { Text("http://homeassistant.local:8123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = localToken,
                    onValueChange = { localToken = it },
                    label = { Text("Long-lived access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                if (error != null) Text(error, color = Color(0xFFFF8A80))
                Button(
                    onClick = { viewModel.connectFromUi(localUrl, localToken) },
                    enabled = !busy && localUrl.isNotBlank() && localToken.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(ActiveYellow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    else Text("Connect", color = Color.Black)
                }
            }
            if (viewModel.savedUrl.isNotBlank()) {
                TextButton(onClick = { viewModel.closeSetup() }, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = ChipOnDark)
                }
            }
        }
    }
}
