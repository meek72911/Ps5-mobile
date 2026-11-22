package com.example.mobileplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mobileplay.remoteplay.ConsolePrompt
import com.example.mobileplay.remoteplay.SessionConsoleControls
import kotlinx.coroutines.delay

internal sealed interface WakeProgress {
    data object Idle : WakeProgress
    data class Waiting(val name: String) : WakeProgress
    data class Failed(val message: String) : WakeProgress
}

@Composable
internal fun ConsoleSessionDialog(
    prompt: ConsolePrompt,
    controls: SessionConsoleControls,
    onDisconnect: () -> Unit,
    onLoginTimeout: () -> Unit
) {
    val pinRequest = prompt as? ConsolePrompt.LoginPin
    val keyboardRequest = prompt as? ConsolePrompt.Keyboard
    var text by remember(prompt) { mutableStateOf(keyboardRequest?.text?.value.orEmpty()) }
    var edited by remember(prompt) { mutableStateOf(false) }
    var submitted by remember(prompt) { mutableStateOf(false) }
    var sendFailed by remember(prompt) { mutableStateOf(false) }
    val cancel = {
        if (keyboardRequest != null) sendFailed = !controls.finishKeyboard(keyboardRequest, null)
        else onDisconnect()
    }

    if (keyboardRequest != null) {
        val remoteText by keyboardRequest.text.collectAsStateWithLifecycle()
        LaunchedEffect(remoteText) { if (!edited) text = remoteText }
    }
    LaunchedEffect(prompt, submitted) {
        if (pinRequest != null) {
            delay(if (submitted) 15_000L else 90_000L)
            if (controls.prompt.value === pinRequest) onLoginTimeout()
        }
    }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(if (pinRequest != null) "PS5 login passcode" else "PS5 keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pinRequest != null) {
                    Text(
                        when {
                            submitted -> "Waiting for the PS5 to verify the passcode…"
                            pinRequest.incorrect -> "The PS5 rejected that passcode. Try again."
                            else -> "Enter the four-digit passcode for your PS5 user profile."
                        }
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        edited = true
                        text = if (pinRequest == null) input.take(4096)
                        else input.filter { it in '0'..'9' }.take(4)
                        sendFailed = false
                    },
                    enabled = !submitted,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (pinRequest != null) "Passcode" else "Text") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (pinRequest != null) KeyboardType.NumberPassword else KeyboardType.Text
                    ),
                    visualTransformation = if (pinRequest != null) PasswordVisualTransformation()
                    else VisualTransformation.None
                )
                if (sendFailed) Text("The PS5 did not accept this request. Check the connection and try again.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitted && (pinRequest == null || text.length == 4),
                onClick = {
                    val success = if (pinRequest != null) controls.submitPin(pinRequest, text)
                    else keyboardRequest != null && controls.finishKeyboard(keyboardRequest, text)
                    if (success) {
                        text = ""
                        submitted = true
                    } else {
                        sendFailed = true
                    }
                }
            ) {
                Text(if (pinRequest != null) "Continue" else "Send")
            }
        },
        dismissButton = {
            TextButton(onClick = cancel) { Text(if (pinRequest != null) "Disconnect" else "Cancel") }
        }
    )
}
