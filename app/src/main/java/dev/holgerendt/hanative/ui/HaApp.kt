package dev.holgerendt.hanative.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.Changelog
import dev.holgerendt.hanative.data.ConnectionState
import dev.holgerendt.hanative.data.QrCodes
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.ui.brightnessPct
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ChipDark
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.theme.DockBackground
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import dev.holgerendt.hanative.ui.theme.PopupScrim
import dev.holgerendt.hanative.ui.theme.ScreenBackground
import dev.holgerendt.hanative.ui.theme.TextDark
import dev.holgerendt.hanative.ui.theme.TextMuted
import dev.holgerendt.hanative.ui.theme.accentColor
import dev.holgerendt.hanative.ui.widgets.CameraPopup
import dev.holgerendt.hanative.ui.widgets.ChipRow
import dev.holgerendt.hanative.ui.widgets.MediaImageDialog
import dev.holgerendt.hanative.ui.widgets.MediaVideoDialog
import dev.holgerendt.hanative.ui.widgets.PersonCard
import dev.holgerendt.hanative.ui.widgets.PersonCameraOverlay
import dev.holgerendt.hanative.ui.widgets.PopupScaffold
import dev.holgerendt.hanative.ui.widgets.RoomGrid
import dev.holgerendt.hanative.ui.widgets.VisionTimeline
import dev.holgerendt.hanative.ui.widgets.WeatherHeader
import dev.holgerendt.hanative.ui.widgets.WeekPlanner
import dev.holgerendt.hanative.ui.widgets.WidgetTree
import kotlinx.coroutines.delay

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
    if (ui.screenAsleep) {
        BackHandler { viewModel.wakeScreen() }
    }
    Box(Modifier.fillMaxSize().background(ScreenBackground)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = CardLight) {
                    DrawerMenu(viewModel)
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(ScreenBackground)) {
                HomeScreen(viewModel)
                if (connection !is ConnectionState.Connected) {
                    Text(
                        text = when (connection) {
                            is ConnectionState.Connecting -> "Connecting…"
                            is ConnectionState.Error -> (connection as ConnectionState.Error).message
                            else -> "Disconnected"
                        },
                        color = TextDark,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
                }
                if (ui.popupHash.isNullOrBlank()) {
                    BottomDock(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        val popup = viewModel.popup(ui.popupHash)
        if (popup != null) {
            InWindowOverlay(
                onDismiss = { viewModel.closePopup() },
                scrim = PopupScrim,
            ) {
                PopupHost(popup, viewModel)
            }
        }
        ui.moreInfoId?.let { MoreInfoDialog(it, viewModel) }
        ui.mediaPreview?.let { preview ->
            InWindowOverlay(
                onDismiss = { viewModel.closeMedia() },
                dismissOnScrim = true,
                scrim = PopupScrim.copy(alpha = 0.72f),
            ) {
                if (preview.isVideo) {
                    MediaVideoDialog(preview, viewModel, onDismiss = { viewModel.closeMedia() })
                } else {
                    MediaImageDialog(preview, viewModel, onDismiss = { viewModel.closeMedia() })
                }
            }
        }
        var consumeWakeGesture by remember { mutableStateOf(false) }
        LaunchedEffect(ui.screenAsleep) {
            if (ui.screenAsleep) return@LaunchedEffect
            consumeWakeGesture = true
            delay(400)
            consumeWakeGesture = false
        }
        if (ui.screenAsleep || consumeWakeGesture) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (ui.screenAsleep) Modifier.background(Color.Black) else Modifier)
                    .pointerInput(ui.screenAsleep) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            viewModel.wakeScreen()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun DrawerMenu(viewModel: HaViewModel) {
    val items = listOf(
        "Weather" to "#weather",
        "Power" to "#power",
        "Presence" to "#presence",
        "Cars" to "#bil",
        "Staubinator" to "#staubinator",
        "Camera" to "#camerafront_view",
        "Music" to "#music",
        "Changelog" to "#changelog",
        "Settings" to "#settings",
    )
    Column(Modifier.fillMaxHeight().width(280.dp).padding(20.dp)) {
        Text("Greatroom Wall", color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        items.forEach { (label, hash) ->
            Text(
                text = label,
                color = TextDark,
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
        Text("Remote setup", color = TextMuted, fontSize = 12.sp)
        ui.remoteUrls.firstOrNull()?.let {
            Text(it, color = TextDark, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("PIN ${ui.remotePin}", color = TextDark, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        Text("Stays until you change it in Settings", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
        Text(
            "Home Assistant connection",
            color = TextDark,
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
    ) {
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
                MdiIcon(menu?.icon ?: "mdi:menu", tint = TextDark, size = 28.dp)
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
        home.calendar?.let {
            Spacer(Modifier.height(24.dp))
            WeekPlanner(it, viewModel, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        // Lovelace `(min-width: 1024px)`: 50% rooms | 50% timeline. 1080px portrait qualifies.
        // When backyard person cams are active they take the right column (timeline slot), not a
        // full-width strip above rooms.
        val activePersonCameras by viewModel.activePersonCameras.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RoomGrid(home.rooms, viewModel, Modifier.weight(1f))
            when {
                activePersonCameras.isNotEmpty() -> {
                    PersonCameraOverlay(
                        cameras = activePersonCameras,
                        viewModel = viewModel,
                        fitContent = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                home.timeline != null -> {
                    VisionTimeline(home.timeline, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
}

private data class DockItem(val hash: String, val icon: String)

/** Lovelace footer order: vacuum, camera, power, cars, music, settings. Weather is the header, not the dock. */
private val DockItems = listOf(
    DockItem("#staubinator", "mdi:vacuum-outline"),
    DockItem("#camerafront_view", "mdi:video"),
    DockItem("#power", "mdi:power-plug-outline"),
    DockItem("#bil", "mdi:car-outline"),
    DockItem("#music", "mdi:music-note"),
    DockItem("#settings", "mdi:tune-variant"),
)

@Composable
private fun BottomDock(viewModel: HaViewModel, modifier: Modifier = Modifier) {
    val wide = LocalConfiguration.current.screenWidthDp >= 801
    Row(
        modifier = modifier
            .padding(
                start = if (wide) 0.dp else 10.dp,
                end = if (wide) 0.dp else 10.dp,
                bottom = 10.dp,
            )
            .fillMaxWidth(if (wide) 0.6f else 1f)
            .height(70.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(DockBackground)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItems.forEach { item ->
            DockButton(
                item = item,
                onClick = { viewModel.openPopup(item.hash) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockButton(item: DockItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(item.icon, tint = Color.White, size = 24.dp)
    }
}

@Composable
private fun PopupHost(popup: PopupNode, viewModel: HaViewModel) {
    val cameraPopup = popup.hash == "#camerafront_view"
    val musicPopup = popup.hash == "#music"
    val weatherPopup = popup.hash == "#weather"
    val ui by viewModel.ui.collectAsState()
    val weatherEntity = ui.weatherPopupContext?.entityId ?: "weather.forecast_tankerland_ct"
    val weatherState by viewModel.entityFlow(if (weatherPopup) weatherEntity else null).collectAsState()
    val weatherSubtitle = if (weatherPopup) {
        weatherState?.friendlyName?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    PopupScaffold(
        popup = popup,
        viewModel = viewModel,
        scrollContent = !cameraPopup && !musicPopup,
        denseContent = musicPopup,
        subtitleOverride = weatherSubtitle,
    ) {
        when (popup.hash) {
            "#weather" -> WeatherPopup(popup, viewModel)
            "#camerafront_view" -> CameraPopup(popup, viewModel)
            "#music" -> MusicAssistantPopup(popup, viewModel)
            "#settings" -> SettingsPopup(popup, viewModel)
            "#changelog" -> ChangelogPopup()
            else -> WidgetTree(popup.cards, viewModel)
        }
    }
}

@Composable
private fun ChangelogPopup() {
    val context = LocalContext.current
    val overlay = LocalOverlay.current
    val entries = remember(context) { Changelog.load(context, limit = 5) }
    val installedVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (installedVersion.isNotBlank()) {
            Text(
                text = "Installed $installedVersion",
                color = TextMuted,
                fontSize = 13.sp,
            )
        }
        if (entries.isEmpty()) {
            Text("No changelog entries yet.", color = TextMuted, fontSize = 14.sp)
        } else {
            entries.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(overlay.card)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.version,
                            color = TextDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        entry.date?.takeIf { it.isNotBlank() }?.let { date ->
                            Text(date, color = TextMuted, fontSize = 13.sp)
                        }
                    }
                    entry.notes.forEach { note ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("•", color = TextDark, fontSize = 14.sp)
                            Text(
                                text = note,
                                color = TextDark,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPopup(popup: PopupNode, viewModel: HaViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTimeoutCard(viewModel)
        ManagementPinCard(viewModel)
        CalendarSubscriptionsCard(viewModel)
        DebugPersonCamerasCard(viewModel)
        WidgetTree(popup.cards, viewModel)
    }
}

@Composable
private fun ScreenTimeoutCard(viewModel: HaViewModel) {
    val overlay = LocalOverlay.current
    val ui by viewModel.ui.collectAsState()
    val brightnessEntity = ui.displayBrightnessEntity.takeIf { it.isNotBlank() }
    val brightnessState by viewModel.entityFlow(brightnessEntity).collectAsState()
    var secondsText by remember { mutableStateOf(ui.screenTimeoutSeconds.toString()) }
    var timeoutError by remember { mutableStateOf<String?>(null) }
    var timeoutMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.screenTimeoutSeconds) {
        secondsText = ui.screenTimeoutSeconds.toString()
    }
    val fieldColors = settingsFieldColors(overlay)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(overlay.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Screen & display", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Idle timeout blanks the panel locally. Optionally control UniFi / HA display entities for hardware off and brightness.",
            color = overlay.muted,
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = secondsText,
            onValueChange = { value ->
                if (value.length <= 6 && value.all { it.isDigit() }) {
                    secondsText = value
                    timeoutError = null
                    timeoutMessage = null
                }
            },
            label = { Text("Idle timeout (seconds, 0 = always on)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        timeoutError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        timeoutMessage?.let { Text(it, color = Color(0xFFC5E1A5), fontSize = 13.sp) }
        Button(
            onClick = {
                val parsed = secondsText.toIntOrNull()
                if (parsed == null) {
                    timeoutMessage = null
                    timeoutError = "Enter a number of seconds"
                    return@Button
                }
                val result = viewModel.setScreenTimeoutSeconds(parsed)
                if (result.isSuccess) {
                    timeoutError = null
                    timeoutMessage = if (parsed == 0) "Screen stays on" else "Timeout saved"
                } else {
                    timeoutMessage = null
                    timeoutError = result.exceptionOrNull()?.message ?: "Could not save timeout"
                }
            },
            enabled = secondsText.isNotBlank(),
            colors = ButtonDefaults.buttonColors(ActiveYellow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save timeout", color = Color.Black)
        }
        EntityPickerField(
            label = "Turn off display entity",
            hint = "switch.uc_display turns the panel off on sleep and on when waking",
            selected = ui.displayOffEntity,
            choices = viewModel.displayOffEntityChoices(),
            noneLabel = "None (app overlay only)",
            fieldColors = fieldColors,
            onSelect = viewModel::setDisplayOffEntity,
        )
        EntityPickerField(
            label = "Brightness entity",
            hint = "number.uc_display_brightness controls panel backlight",
            selected = ui.displayBrightnessEntity,
            choices = viewModel.displayBrightnessEntityChoices(),
            noneLabel = "None",
            fieldColors = fieldColors,
            onSelect = viewModel::setDisplayBrightnessEntity,
        )
        EntityPickerField(
            label = "Auto-brightness sensor",
            hint = "Room illuminance (lx) maps to backlight while the panel is awake",
            selected = ui.displayIlluminanceEntity,
            choices = viewModel.displayIlluminanceEntityChoices(),
            noneLabel = "None (manual only)",
            fieldColors = fieldColors,
            onSelect = viewModel::setDisplayIlluminanceEntity,
        )
        if (brightnessEntity != null) {
            val entity = brightnessState
            val domain = brightnessEntity.substringBefore('.')
            val (rawMin, rawMax, rawLive) = when (domain) {
                "light" -> Triple(0f, 100f, entity.brightnessPct().toFloat())
                else -> Triple(
                    entity?.attrDouble("min")?.toFloat() ?: 0f,
                    entity?.attrDouble("max")?.toFloat() ?: 255f,
                    entity?.state?.toFloatOrNull() ?: 0f,
                )
            }
            val min = if (rawMin.isFinite()) rawMin else 0f
            val maxCandidate = if (rawMax.isFinite()) rawMax else 255f
            val max = if (maxCandidate > min) maxCandidate else min + 1f
            val live = (if (rawLive.isFinite()) rawLive else min).coerceIn(min, max)
            var slider by remember(brightnessEntity) { mutableFloatStateOf(live) }
            LaunchedEffect(live, min, max) {
                slider = live.coerceIn(min, max)
            }
            Text(
                "Brightness ${slider.toInt()}",
                color = overlay.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = slider.coerceIn(min, max),
                onValueChange = { slider = it.coerceIn(min, max) },
                onValueChangeFinished = { viewModel.setDisplayBrightness(slider.coerceIn(min, max)) },
                valueRange = min..max,
                colors = SliderDefaults.colors(thumbColor = ActiveYellow, activeTrackColor = ActiveYellow),
            )
        }
    }
}

@Composable
private fun settingsFieldColors(overlay: OverlayColors) =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = overlay.text,
        unfocusedTextColor = overlay.text,
        focusedBorderColor = overlay.text,
        unfocusedBorderColor = overlay.muted,
        focusedLabelColor = overlay.text,
        unfocusedLabelColor = overlay.muted,
        cursorColor = overlay.text,
    )

@Composable
private fun EntityPickerField(
    label: String,
    hint: String,
    selected: String,
    choices: List<Pair<String, String>>,
    noneLabel: String,
    fieldColors: androidx.compose.material3.TextFieldColors,
    onSelect: (String) -> Result<Unit>,
) {
    val overlay = LocalOverlay.current
    var filter by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(selected) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) {
        custom = selected
    }
    val filtered = remember(filter, choices) {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else choices.filter { (id, name) ->
            id.lowercase().contains(q) || name.lowercase().contains(q)
        }.take(8)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = overlay.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(hint, color = overlay.muted, fontSize = 13.sp)
        Text(
            text = selected.takeIf { it.isNotBlank() } ?: noneLabel,
            color = if (selected.isBlank()) overlay.muted else overlay.text,
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = filter,
            onValueChange = {
                filter = it
                error = null
            },
            label = { Text("Search entities") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = custom,
            onValueChange = {
                custom = it.lowercase()
                error = null
            },
            label = { Text("Or type entity_id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val result = onSelect("")
                error = result.exceptionOrNull()?.message
                if (result.isSuccess) filter = ""
            }) {
                Text(noneLabel, color = overlay.text)
            }
            TextButton(
                onClick = {
                    val result = onSelect(custom)
                    error = result.exceptionOrNull()?.message
                    if (result.isSuccess) filter = ""
                },
                enabled = custom.isNotBlank(),
            ) {
                Text("Save entity", color = overlay.text)
            }
        }
        if (filtered.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(overlay.well),
            ) {
                filtered.forEach { (id, name) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val result = onSelect(id)
                                error = result.exceptionOrNull()?.message
                                if (result.isSuccess) {
                                    custom = id
                                    filter = ""
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(name, color = overlay.text, fontSize = 14.sp)
                        Text(id, color = overlay.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugPersonCamerasCard(viewModel: HaViewModel) {
    val overlay = LocalOverlay.current
    val debugEnabled by viewModel.debugPersonCamerasEnabled.collectAsState()
    val cameraCount = viewModel.ui.collectAsState().value.dashboard?.home?.personCameras?.bindings?.size ?: 0
    if (cameraCount == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(overlay.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Debug", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Preview backyard cameras", color = overlay.text, fontSize = 16.sp)
                Text(
                    "Shows all $cameraCount person-camera streams on the home layout, ignoring sensors.",
                    color = overlay.muted,
                    fontSize = 14.sp,
                )
            }
            Switch(
                checked = debugEnabled,
                onCheckedChange = viewModel::setDebugPersonCamerasEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = ActiveYellow,
                ),
            )
        }
    }
}

@Composable
private fun CalendarSubscriptionsCard(viewModel: HaViewModel) {
    val overlay = LocalOverlay.current
    val available by viewModel.availableCalendars.collectAsState()
    val subscribed by viewModel.subscribedCalendars.collectAsState()
    val defaults = viewModel.ui.collectAsState().value.dashboard?.home?.calendar?.calendars.orEmpty()
    LaunchedEffect(Unit) { viewModel.refreshCalendars() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(overlay.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Subscribed calendars", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "These calendars appear on the 10-day planner, same as the Lovelace week-planner card.",
            color = overlay.muted,
            fontSize = 14.sp,
        )
        if (available.isEmpty()) {
            Text("No calendars found yet", color = overlay.muted, fontSize = 13.sp)
        }
        available.forEach { calendar ->
            val selected = subscribed ?: defaults.mapNotNull { it.entity }
            val on = calendar.entityId in selected
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(calendar.name, color = overlay.text, fontSize = 16.sp)
                    Text(calendar.entityId.removePrefix("calendar."), color = overlay.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = on,
                    onCheckedChange = { viewModel.setCalendarSubscribed(calendar.entityId, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = ActiveYellow,
                    ),
                )
            }
        }
        if (subscribed != null) {
            TextButton(onClick = { viewModel.resetCalendarSubscriptions() }) {
                Text("Use Lovelace defaults", color = overlay.text)
            }
        }
    }
}

@Composable
private fun ManagementPinCard(viewModel: HaViewModel) {
    val overlay = LocalOverlay.current
    val ui by viewModel.ui.collectAsState()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = overlay.text,
        unfocusedTextColor = overlay.text,
        focusedBorderColor = overlay.text,
        unfocusedBorderColor = overlay.muted,
        focusedLabelColor = overlay.text,
        unfocusedLabelColor = overlay.muted,
        cursorColor = overlay.text,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(overlay.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Remote setup PIN", color = overlay.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "This PIN is saved on the panel and survives app upgrades and reinstalls. Use it to log in on the HTTPS admin page. Changing it here signs out existing admin sessions.",
            color = overlay.muted,
            fontSize = 14.sp,
        )
        Text("Current PIN ${ui.remotePin}", color = overlay.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 8 && value.all { it.isDigit() }) pin = value
            },
            label = { Text(if (ui.pinIsUserSet) "New PIN" else "PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { value ->
                if (value.length <= 8 && value.all { it.isDigit() }) confirm = value
            },
            label = { Text("Confirm PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        message?.let { Text(it, color = Color(0xFFC5E1A5), fontSize = 13.sp) }
        Button(
            onClick = {
                val result = viewModel.setManagementPin(pin, confirm)
                if (result.isSuccess) {
                    error = null
                    message = "PIN saved"
                    pin = ""
                    confirm = ""
                } else {
                    message = null
                    error = result.exceptionOrNull()?.message ?: "Could not save PIN"
                }
            },
            enabled = pin.isNotBlank() && confirm.isNotBlank(),
            colors = ButtonDefaults.buttonColors(ActiveYellow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ui.pinIsUserSet) "Change PIN" else "Save PIN", color = Color.Black)
        }
        TextButton(
            onClick = {
                val result = viewModel.resetManagementPin()
                result.onSuccess { fresh ->
                    error = null
                    message = "New PIN $fresh — use this on the admin page"
                    pin = ""
                    confirm = ""
                }.onFailure {
                    message = null
                    error = it.message ?: "Could not reset PIN"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate new PIN", color = overlay.text)
        }
    }
}

@Composable
fun InWindowOverlay(
    onDismiss: () -> Unit,
    dismissOnScrim: Boolean = false,
    scrim: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (dismissOnScrim) onDismiss() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
                "Scan the QR code or open the HTTPS URL on the same Wi‑Fi. Accept the certificate warning, log in with the PIN below, then paste the Home Assistant token.",
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
                ui.remotePin,
                color = ActiveYellow,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("This PIN stays until you change it in Settings.", color = ChipOnDark, fontSize = 13.sp)
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

@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    color: Color = TextDark,
    indicatorSize: Dp = 22.dp,
) {
    CircularProgressIndicator(
        modifier = modifier.size(indicatorSize),
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        strokeWidth = 3.dp,
    )
}
