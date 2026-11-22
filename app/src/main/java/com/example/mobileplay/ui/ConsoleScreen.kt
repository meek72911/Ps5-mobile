package com.example.mobileplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.security.RegisteredConsole
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceHigh
import com.example.ui.theme.MutedCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.SoftIceText
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.TacticalCoral

@Composable
internal fun ConsoleScreen(
    uiState: MobilePlayUiState,
    onStartOnboarding: () -> Unit,
    onScanLocalWifi: () -> Unit,
    onPairConsole: (hostName: String, hostAddress: String, psnAccountId: String, pinCode: String) -> Unit,
    onConnectConsole: (RegisteredConsole) -> Unit,
    onDisconnect: () -> Unit,
    onDeleteConsole: (String) -> Unit,
    wakeProgress: WakeProgress = WakeProgress.Idle,
    onWakeConsole: (RegisteredConsole) -> Unit = {},
    onCancelWake: () -> Unit = {}
) {
    var hostName by rememberSaveable { mutableStateOf("") }
    var hostAddress by rememberSaveable { mutableStateOf("") }
    var psnAccountId by rememberSaveable {
        mutableStateOf(uiState.sonyAuthSession?.psnAccountIdBase64.orEmpty())
    }
    var pinCode by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Consoles",
                    style = MaterialTheme.typography.titleMedium,
                    color = SoftIceText
                )
                Text(
                    text = uiState.engineStatusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.isNativeLibchiakiLoaded) StatusEmerald else StatusAmber
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.connectionState is ConnectionState.Connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("console_disconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = TacticalCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect", color = TacticalCoral)
                    }
                }

                Button(
                    onClick = onStartOnboarding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurfaceHigh,
                        contentColor = MutedCyan
                    ),
                    border = BorderStroke(1.dp, MutedCyan.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("launch_onboarding_wizard_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("5-Step Setup Wizard")
                }

                // Keystore status badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (uiState.isHardwareKeystoreAvailable) StatusEmerald else StatusAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (uiState.isHardwareKeystoreAvailable) "Hardware-backed storage" else "Device storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateMutedText
                        )
                    }
                }
            }
        }

        when (wakeProgress) {
            WakeProgress.Idle -> Unit
            is WakeProgress.Waiting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Waking ${wakeProgress.name} on local Wi-Fi…", color = SoftIceText)
                TextButton(onClick = onCancelWake) { Text("Cancel") }
            }
            is WakeProgress.Failed -> Text(wakeProgress.message, color = TacticalCoral)
        }

        // Two-Column Landscape Layout
        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Pane: LAN Discovery + Milestone 2 Native Engine Status
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiFind,
                                    contentDescription = null,
                                    tint = MutedCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Find PS5 on Wi-Fi",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = SoftIceText
                                )
                            }
                            Button(
                                onClick = onScanLocalWifi,
                                enabled = uiState.connectionState !is ConnectionState.Discovering,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MutedCyan,
                                    contentColor = ObsidianBg
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("scan_wifi_button")
                            ) {
                                if (uiState.connectionState is ConnectionState.Discovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                } else {
                                    Text(text = "Scan Wi-Fi")
                                }
                            }
                        }

                        Text(
                            text = uiState.lastScanMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateMutedText
                        )

                        if (uiState.discoveredConsoles.isNotEmpty()) {
                            uiState.discoveredConsoles.forEach { host ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = CharcoalSurfaceHigh,
                                    border = BorderStroke(1.dp, MutedCyan.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${host.hostName} (${host.hostType})",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = SoftIceText
                                            )
                                            Text(
                                                text = "${host.hostAddress} • FW ${host.systemVersion} • ${host.status}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MutedCyan
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                hostName = host.hostName
                                                hostAddress = host.hostAddress
                                            },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("Select")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Connection State / Honest Streaming Errors
                val stateBannerColor = when (uiState.connectionState) {
                    is ConnectionState.Connected -> StatusEmerald
                    is ConnectionState.Error -> TacticalCoral
                    is ConnectionState.NativeEngineRequired -> StatusAmber
                    else -> MutedCyan
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, stateBannerColor.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "REMOTE PLAY STREAM STATUS",
                            style = MaterialTheme.typography.labelMedium,
                            color = stateBannerColor
                        )
                        val detail = when (val s = uiState.connectionState) {
                            ConnectionState.StandaloneSimulator ->
                                "Select a saved PS5 to start streaming."
                            ConnectionState.Discovering -> "Sending single UDP 9302 discovery probe on local Wi-Fi..."
                            is ConnectionState.NativeEngineRequired -> s.detail
                            is ConnectionState.Pairing -> "Pairing with ${s.hostAddress}..."
                            is ConnectionState.Connecting -> s.detail ?: "Opening session to ${s.hostAddress}..."
                            is ConnectionState.Connected -> "Streaming from ${s.hostName} (${s.hostAddress})."
                            is ConnectionState.Error -> s.message
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = SoftIceText
                        )
                    }
                }

                // Saved Consoles
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SAVED CONSOLES (${uiState.savedConsoles.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = SlateMutedText
                        )
                        if (uiState.savedConsoles.isEmpty()) {
                            Text(
                                text = "Pair a PS5 to save it here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateMutedText
                            )
                        } else {
                            uiState.savedConsoles.forEach { console ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = CharcoalSurfaceHigh,
                                    border = BorderStroke(1.dp, CharcoalBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = console.hostName,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = SoftIceText
                                            )
                                            Text(
                                                text = "${console.hostAddress} • ${console.registrationNote}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SlateMutedText
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (uiState.isNativeLibchiakiLoaded && console.hasValidSessionCredentials()) {
                                                TextButton(
                                                    onClick = { onWakeConsole(console) },
                                                    enabled = wakeProgress !is WakeProgress.Waiting &&
                                                        uiState.connectionState !is ConnectionState.Connected &&
                                                        uiState.connectionState !is ConnectionState.Connecting
                                                ) {
                                                    Text("Wake & Play")
                                                }
                                            }
                                            IconButton(
                                                onClick = { onConnectConsole(console) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Connect to ${console.hostName}",
                                                    tint = MutedCyan
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDeleteConsole(console.hostId) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Remove ${console.hostName}",
                                                    tint = TacticalCoral
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Right Pane: Legitimate Remote Play Pairing Form
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CharcoalSurface,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = MutedCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Pair a PS5",
                            style = MaterialTheme.typography.titleSmall,
                            color = SoftIceText
                        )
                    }
                    Text(
                        text = "On PS5: Settings → System → Remote Play → Link Device. " +
                            "Entries are encrypted locally with Android Keystore AES-256-GCM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )

                    OutlinedTextField(
                        value = hostName,
                        onValueChange = { hostName = it },
                        label = { Text("Console Label (optional)") },
                        placeholder = { Text("Living Room PS5") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pair_host_name_input")
                    )

                    OutlinedTextField(
                        value = hostAddress,
                        onValueChange = { hostAddress = it },
                        label = { Text("Local PS5 IP Address") },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pair_host_ip_input")
                    )

                    OutlinedTextField(
                        value = psnAccountId,
                        onValueChange = { psnAccountId = it },
                        label = { Text("PSN Account ID (8-byte Base64 or Numeric)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pair_psn_account_input")
                    )

                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { input ->
                            if (input.length <= 8 && input.all { it.isDigit() }) {
                                pinCode = input
                            }
                        },
                        label = { Text("8-Digit Remote Play PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pair_pin_input")
                    )

                    uiState.pairStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Button(
                        onClick = { onPairConsole(hostName, hostAddress, psnAccountId, pinCode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MutedCyan,
                            contentColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("pair_submit_button")
                    ) {
                        Text(text = "Pair PS5")
                    }
                }
            }
        }
    }
}
