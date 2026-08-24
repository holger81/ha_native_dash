package dev.holgerendt.hanative.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.LocalOverlay
import dev.holgerendt.hanative.ui.theme.OverlayColors
import dev.holgerendt.hanative.ui.theme.OverlayLightPopup
import dev.holgerendt.hanative.ui.theme.PopupScrim
import dev.holgerendt.hanative.ui.widgets.PopupSheetChrome
import dev.holgerendt.hanative.ui.widgets.PopupSheetKind
import dev.holgerendt.hanative.ui.widgets.popupSheetLook
import dev.holgerendt.hanative.ui.widgets.popupSheetModifier

@Composable
fun PinGateDialog(
    viewModel: HaViewModel,
    title: String = "Enter PIN",
    message: String = "Enter your management PIN to continue.",
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val overlay = OverlayLightPopup
    androidx.compose.runtime.CompositionLocalProvider(LocalOverlay provides overlay) {
        FullScreenDialogOverlay(
            onDismiss = onDismiss,
            dismissOnScrim = true,
            scrim = PopupScrim,
        ) {
            Column(
                modifier = popupSheetModifier(PopupSheetKind.Detail)
                    .wrapContentHeight()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .popupSheetLook(overlay.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PopupSheetChrome(
                    title = title,
                    onClose = onDismiss,
                    overlay = overlay,
                )
                PinGateForm(
                    overlay = overlay,
                    message = message,
                    onCancel = onDismiss,
                    onSubmit = { pin ->
                        if (viewModel.verifyManagementPin(pin)) {
                            viewModel.unlockCalendarManagement()
                            onVerified()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PinGateForm(
    overlay: OverlayColors,
    message: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
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
    Text(message, color = overlay.muted, fontSize = 14.sp)
    OutlinedTextField(
        value = pin,
        onValueChange = { value ->
            if (value.length <= 8 && value.all { it.isDigit() }) {
                pin = value
                error = null
            }
        },
        label = { Text("PIN") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors,
    )
    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) {
            Text("Cancel", color = overlay.text)
        }
        Button(
            onClick = {
                if (pin.isBlank()) {
                    error = "Enter your PIN"
                    return@Button
                }
                if (onSubmit(pin)) {
                    pin = ""
                    error = null
                } else {
                    error = "Wrong PIN"
                }
            },
            enabled = pin.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ActiveYellow, contentColor = Color.Black),
        ) {
            Text("Continue")
        }
    }
}
