package dev.holgerendt.hanative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.HaCalendarEvent
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayLightPopup
import dev.holgerendt.hanative.ui.theme.PopupScrim
import dev.holgerendt.hanative.ui.theme.accentColor
import dev.holgerendt.hanative.ui.widgets.PopupSheetChrome
import dev.holgerendt.hanative.ui.widgets.popupSheetLook
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class CalendarManageAction {
    Edit,
    Delete,
}

@Composable
fun CalendarEventActionDialog(
    event: HaCalendarEvent,
    calendarName: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val overlay = OverlayLightPopup
    val stripe = accentColor(event.color?.removePrefix("var(--")?.removeSuffix(")"))
    val whenLabel = remember(event) { calendarEventWhenLabel(event) }
    val dateLabel = remember(event) { calendarEventDateLabel(event) }
    CalendarPopupSheet(
        title = "Event",
        onDismiss = onDismiss,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(stripe),
            )
            Text(
                text = calendarName?.takeIf { it.isNotBlank() }
                    ?: event.entityId.removePrefix("calendar.").replace('_', ' ')
                        .ifBlank { "Calendar" },
                color = overlay.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = event.summary,
            color = overlay.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (dateLabel.isNotBlank()) {
            DetailLine(label = "Date", value = dateLabel)
        }
        if (whenLabel.isNotBlank()) {
            DetailLine(label = "Time", value = whenLabel)
        }
        event.location?.takeIf { it.isNotBlank() }?.let {
            DetailLine(label = "Location", value = it)
        }
        event.description?.takeIf { it.isNotBlank() }?.let {
            DetailLine(label = "Notes", value = it, maxLines = 6)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = overlay.text)
            }
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = ActiveYellow, contentColor = Color.Black),
            ) {
                Text("Edit")
            }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, maxLines: Int = 3) {
    val overlay = LocalOverlay.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = overlay.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value,
            color = overlay.text,
            fontSize = 15.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DeleteCalendarEventDialog(
    viewModel: HaViewModel,
    event: HaCalendarEvent,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uid = event.uid.orEmpty()
    CalendarPopupSheet(
        title = "Delete event",
        onDismiss = onDismiss,
    ) {
        Text(
            text = "Delete \"${event.summary}\"?",
            color = LocalOverlay.current.text,
            fontSize = 15.sp,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text("Cancel", color = LocalOverlay.current.text)
            }
            Button(
                onClick = {
                    if (uid.isBlank()) {
                        error = "This event cannot be deleted"
                        return@Button
                    }
                    deleting = true
                    error = null
                    viewModel.deleteCalendarEvent(event.entityId, uid) { result ->
                        deleting = false
                        result.fold(
                            onSuccess = { onDeleted() },
                            onFailure = { error = it.message ?: "Could not delete event" },
                        )
                    }
                },
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
            ) {
                if (deleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
fun CalendarMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    CalendarPopupSheet(
        title = title,
        onDismiss = onDismiss,
    ) {
        Text(message, color = LocalOverlay.current.muted, fontSize = 14.sp)
        TextButton(onClick = onDismiss) {
            Text("OK", color = LocalOverlay.current.text)
        }
    }
}

@Composable
private fun CalendarPopupSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val overlay = OverlayLightPopup
    androidx.compose.runtime.CompositionLocalProvider(LocalOverlay provides overlay) {
        InWindowOverlay(
            onDismiss = onDismiss,
            dismissOnScrim = true,
            scrim = PopupScrim,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .widthIn(max = 440.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .popupSheetLook(overlay.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = {
                    PopupSheetChrome(
                        title = title,
                        onClose = onDismiss,
                        overlay = overlay,
                    )
                    content()
                },
            )
        }
    }
}

internal fun calendarEventWhenLabel(event: HaCalendarEvent): String {
    if (event.allDay || (event.startDate != null && event.start == null)) return "All day"
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.start?.atZone(zone)?.format(fmt) ?: return ""
    val end = event.end?.atZone(zone)?.format(fmt) ?: return start
    return if (end == start) start else "$start – $end"
}

internal fun calendarEventDateLabel(event: HaCalendarEvent): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    return when {
        event.allDay || event.startDate != null -> {
            val start = event.startDate ?: return ""
            val endExclusive = event.endDate
            if (endExclusive != null && endExclusive.isAfter(start.plusDays(1))) {
                "${start.format(fmt)} – ${endExclusive.minusDays(1).format(fmt)}"
            } else {
                start.format(fmt)
            }
        }
        event.start != null -> event.start.atZone(zone).toLocalDate().format(fmt)
        else -> ""
    }
}

@Composable
fun CalendarColorSwatches(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaViewModel.CalendarColorOptions.forEach { colorName ->
            val tint = accentColor(colorName)
            val active = selected == colorName
            Box(
                modifier = Modifier
                    .size(if (active) 26.dp else 22.dp)
                    .clip(CircleShape)
                    .background(tint)
                    .then(
                        if (active) {
                            Modifier.border(2.dp, Color.Black.copy(alpha = 0.55f), CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(colorName) },
            )
        }
    }
}
