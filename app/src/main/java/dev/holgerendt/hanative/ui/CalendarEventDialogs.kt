package dev.holgerendt.hanative.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
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
import dev.holgerendt.hanative.ui.widgets.PopupSheetChrome
import dev.holgerendt.hanative.ui.widgets.PopupSheetKind
import dev.holgerendt.hanative.ui.widgets.popupSheetLook
import dev.holgerendt.hanative.ui.widgets.popupSheetModifier

enum class CalendarManageAction {
    Edit,
    Delete,
}

@Composable
fun CalendarEventActionDialog(
    event: HaCalendarEvent,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    CalendarPopupSheet(
        title = "Event",
        onDismiss = onDismiss,
    ) {
        Text(
            text = event.summary,
            color = LocalOverlay.current.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LocalOverlay.current.text)
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
                modifier = popupSheetModifier(PopupSheetKind.Detail)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .popupSheetLook(overlay.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
