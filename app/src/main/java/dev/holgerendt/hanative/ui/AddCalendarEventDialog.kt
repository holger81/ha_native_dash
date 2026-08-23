package dev.holgerendt.hanative.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.HaCalendarEvent
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import dev.holgerendt.hanative.ui.theme.OverlayLightPopup
import dev.holgerendt.hanative.ui.theme.PopupScrim
import dev.holgerendt.hanative.ui.widgets.PopupSheetChrome
import dev.holgerendt.hanative.ui.widgets.PopupSheetKind
import dev.holgerendt.hanative.ui.widgets.popupSheetLook
import dev.holgerendt.hanative.ui.widgets.popupSheetModifier
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Composable
fun EditCalendarEventDialog(
    viewModel: HaViewModel,
    event: HaCalendarEvent,
    calendars: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val overlay = OverlayLightPopup
    androidx.compose.runtime.CompositionLocalProvider(LocalOverlay provides overlay) {
        InWindowOverlay(
            onDismiss = onDismiss,
            dismissOnScrim = true,
            scrim = PopupScrim,
        ) {
            Column(
                modifier = popupSheetModifier(PopupSheetKind.Detail)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .popupSheetLook(overlay.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PopupSheetChrome(
                    title = "Edit event",
                    onClose = onDismiss,
                    overlay = overlay,
                )
                AddCalendarEventForm(
                    calendars = calendars,
                    initialDate = calendarEventInitialDate(event, zone),
                    initialTitle = event.summary,
                    initialAllDay = event.allDay || event.startDate != null,
                    initialStartTime = calendarEventStartTime(event, zone),
                    initialEndTime = calendarEventEndTime(event, zone),
                    lockedCalendarId = event.entityId,
                    overlay = overlay,
                    onCancel = onDismiss,
                    onSave = { entityId, title, date, startTime, endTime, allDay, onResult ->
                        val uid = event.uid
                        if (uid.isNullOrBlank()) {
                            onResult(Result.failure(IllegalStateException("This event cannot be edited")))
                            return@AddCalendarEventForm
                        }
                        viewModel.updateCalendarEvent(
                            entityId = entityId,
                            uid = uid,
                            title = title,
                            date = date,
                            startTime = startTime,
                            endTime = endTime,
                            allDay = allDay,
                            onResult = onResult,
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun AddCalendarEventDialog(
    viewModel: HaViewModel,
    calendars: List<Pair<String, String>>,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
) {
    val overlay = OverlayLightPopup
    androidx.compose.runtime.CompositionLocalProvider(LocalOverlay provides overlay) {
        InWindowOverlay(
            onDismiss = onDismiss,
            dismissOnScrim = true,
            scrim = PopupScrim,
        ) {
            Column(
                modifier = popupSheetModifier(PopupSheetKind.Detail)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .popupSheetLook(overlay.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PopupSheetChrome(
                    title = "Add event",
                    onClose = onDismiss,
                    overlay = overlay,
                )
                AddCalendarEventForm(
                    calendars = calendars,
                    initialDate = initialDate,
                    overlay = overlay,
                    onCancel = onDismiss,
                    onSave = { entityId, title, date, startTime, endTime, allDay, onResult ->
                        viewModel.createCalendarEvent(
                            entityId = entityId,
                            title = title,
                            date = date,
                            startTime = startTime,
                            endTime = endTime,
                            allDay = allDay,
                            onResult = onResult,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AddCalendarEventForm(
    calendars: List<Pair<String, String>>,
    initialDate: LocalDate,
    overlay: OverlayColors,
    onCancel: () -> Unit,
    onSave: (
        entityId: String,
        title: String,
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        allDay: Boolean,
        onResult: (Result<Unit>) -> Unit,
    ) -> Unit,
    initialTitle: String = "",
    initialAllDay: Boolean = false,
    initialStartTime: LocalTime? = LocalTime.of(9, 0),
    initialEndTime: LocalTime? = LocalTime.of(10, 0),
    lockedCalendarId: String? = null,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = overlay.text,
        unfocusedTextColor = overlay.text,
        focusedBorderColor = overlay.text,
        unfocusedBorderColor = overlay.muted,
        focusedLabelColor = overlay.text,
        unfocusedLabelColor = overlay.muted,
        cursorColor = overlay.text,
    )
    var title by remember { mutableStateOf(initialTitle) }
    var dateText by remember(initialDate) {
        mutableStateOf(initialDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }
    var startTimeText by remember(initialStartTime) {
        mutableStateOf(initialStartTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "09:00")
    }
    var endTimeText by remember(initialEndTime) {
        mutableStateOf(initialEndTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "10:00")
    }
    var allDay by remember(initialAllDay) { mutableStateOf(initialAllDay) }
    var selectedCalendar by remember(calendars, lockedCalendarId) {
        mutableStateOf(lockedCalendarId ?: calendars.firstOrNull()?.first.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = title,
        onValueChange = {
            title = it
            error = null
        },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors,
    )
    OutlinedTextField(
        value = dateText,
        onValueChange = {
            dateText = it
            error = null
        },
        label = { Text("Date (YYYY-MM-DD)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("All day", color = overlay.text, fontSize = 15.sp)
        Switch(
            checked = allDay,
            onCheckedChange = {
                allDay = it
                error = null
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = ActiveYellow,
            ),
        )
    }
    if (!allDay) {
        OutlinedTextField(
            value = startTimeText,
            onValueChange = {
                startTimeText = it
                error = null
            },
            label = { Text("Start (HH:mm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = endTimeText,
            onValueChange = {
                endTimeText = it
                error = null
            },
            label = { Text("End (HH:mm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
    }
    if (calendars.isNotEmpty()) {
        Text("Calendar", color = overlay.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        val visibleCalendars = if (lockedCalendarId != null) {
            calendars.filter { it.first == lockedCalendarId }
        } else {
            calendars
        }
        visibleCalendars.forEach { (entityId, name) ->
            val selected = entityId == selectedCalendar
            if (lockedCalendarId != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(name, color = overlay.text, fontSize = 15.sp)
                    Text(entityId.removePrefix("calendar."), color = overlay.muted, fontSize = 12.sp)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !saving) {
                            selectedCalendar = entityId
                            error = null
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(name, color = overlay.text, fontSize = 15.sp)
                        Text(entityId.removePrefix("calendar."), color = overlay.muted, fontSize = 12.sp)
                    }
                    Text(
                        if (selected) "●" else "○",
                        color = if (selected) ActiveYellow else overlay.muted,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    } else {
        Text("No subscribed calendars", color = overlay.muted, fontSize = 14.sp)
    }
    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, enabled = !saving) {
            Text("Cancel", color = overlay.text)
        }
        Button(
            onClick = {
                val trimmedTitle = title.trim()
                if (trimmedTitle.isBlank()) {
                    error = "Title is required"
                    return@Button
                }
                if (selectedCalendar.isBlank()) {
                    error = "Choose a calendar"
                    return@Button
                }
                val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrElse {
                    error = "Invalid date"
                    return@Button
                }
                val startTime = if (allDay) {
                    null
                } else {
                    parseTime(startTimeText) ?: run {
                        error = "Invalid start time"
                        return@Button
                    }
                }
                val endTime = if (allDay || startTime == null) {
                    null
                } else {
                    parseTime(endTimeText) ?: run {
                        error = "Invalid end time"
                        return@Button
                    }
                }
                saving = true
                error = null
                onSave(selectedCalendar, trimmedTitle, date, startTime, endTime, allDay) { result ->
                    saving = false
                    result.fold(
                        onSuccess = { onCancel() },
                        onFailure = { error = it.message ?: "Could not create event" },
                    )
                }
            },
            enabled = !saving && calendars.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = ActiveYellow, contentColor = Color.Black),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Save")
            }
        }
    }
}

private fun parseTime(raw: String): LocalTime? {
    val text = raw.trim()
    if (text.isBlank()) return null
    return try {
        when {
            text.count { it == ':' } == 1 -> LocalTime.parse(text, DateTimeFormatter.ofPattern("H:mm"))
            else -> LocalTime.parse(text, DateTimeFormatter.ofPattern("HH:mm"))
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun calendarEventInitialDate(event: HaCalendarEvent, zone: ZoneId): LocalDate {
    event.startDate?.let { return it }
    return event.start?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone)
}

private fun calendarEventStartTime(event: HaCalendarEvent, zone: ZoneId): LocalTime? {
    if (event.allDay || event.startDate != null) return null
    return event.start?.atZone(zone)?.toLocalTime()
}

private fun calendarEventEndTime(event: HaCalendarEvent, zone: ZoneId): LocalTime? {
    if (event.allDay || event.startDate != null) return null
    return event.end?.atZone(zone)?.toLocalTime()
}
