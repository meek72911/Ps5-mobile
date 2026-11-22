package com.example.mobileplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.security.RegisteredConsole
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.ActionBlue
import com.example.ui.theme.ConsoleHeroSurface
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
fun HomeScreen(
    uiState: MobilePlayUiState,
    controllerName: String?,
    onOpenAbout: () -> Unit,
    onConnectConsole: (RegisteredConsole) -> Unit,
    onStartOnboarding: () -> Unit,
    onLaunchGameplay: () -> Unit,
    onOpenLayoutEditor: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val primaryConsole = uiState.savedConsoles.firstOrNull()
    val statusDotColor = when (uiState.connectionState) {
        is ConnectionState.Connected -> StatusEmerald
        is ConnectionState.Error -> TacticalCoral
        is ConnectionState.NativeEngineRequired -> StatusAmber
        else -> MutedCyan
    }
    val statusText = when (val st = uiState.connectionState) {
        is ConnectionState.Connected -> "Connected • ${st.hostName}"
        is ConnectionState.Connecting -> st.detail ?: "Connecting to ${st.hostAddress}..."
        is ConnectionState.Discovering -> "Scanning local Wi-Fi..."
        is ConnectionState.Pairing -> "Pairing console..."
        is ConnectionState.NativeEngineRequired -> "Offline Ready • NDK libchiaki required for PS5 stream"
        is ConnectionState.Error -> st.message
        ConnectionState.StandaloneSimulator -> if (primaryConsole != null) {
            "Ready to connect • ${uiState.deviceMetrics.networkTransportLabel}"
        } else {
            "No PS5 paired • ${uiState.deviceMetrics.networkTransportLabel}"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Hero Column: Paired PS5 + Large Connect CTA
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ConsoleHeroSurface,
            border = BorderStroke(1.dp, CharcoalBorder),
            modifier = Modifier
                .weight(1.25f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(painterResource(R.drawable.mobileplay_mark), null, Modifier.size(44.dp))
                        Text(
                            text = "MobilePlay",
                            style = MaterialTheme.typography.titleLarge,
                            color = SoftIceText
                        )
                    }

                    Text(
                        text = primaryConsole?.hostName ?: "Connect PlayStation 5",
                        style = MaterialTheme.typography.headlineSmall,
                        color = SoftIceText
                    )

                    Text(
                        text = primaryConsole?.let { "Saved console" }
                            ?: "Pair a PS5 on your local network to start playing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateMutedText
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CharcoalSurfaceHigh,
                        border = BorderStroke(1.dp, CharcoalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).background(statusDotColor, CircleShape))
                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                        }
                    }
                    Text(
                        text = controllerName ?: "Touch controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (primaryConsole != null) {
                                onConnectConsole(primaryConsole)
                            } else {
                                onStartOnboarding()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActionBlue,
                            contentColor = SoftIceText
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("home_primary_connect_button")
                    ) {
                        Icon(
                            imageVector = if (primaryConsole != null) Icons.Default.PlayArrow else Icons.Default.Router,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (primaryConsole != null) "Play" else "Set Up PS5",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onLaunchGameplay,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("home_offline_play_button")
                        ) {
                            Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Controls Demo")
                        }
                        if (primaryConsole != null) {
                            OutlinedButton(
                                onClick = onStartOnboarding,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(0.85f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_setup_wizard_button")
                            ) {
                                Text("Add PS5")
                            }
                        }
                    }
                    TextButton(onClick = onOpenAbout) { Text("About MobilePlay") }
                }
            }
        }

        // Right Column: Active Controller Profile + Streaming Quality + Quick Actions
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active Profile Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CharcoalSurface,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CONTROLLER PROFILE",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText
                    )
                    Text(
                        text = uiState.activeProfile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = SoftIceText
                    )
                    Text(
                        text = "${uiState.activeProfile.buttons.size} controls  •  Opacity ${(uiState.activeProfile.controlOpacity * 100).toInt()}%  •  ${uiState.activeProfile.mode.name.replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenLayoutEditor,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("home_edit_layout_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Edit Layout")
                        }
                        OutlinedButton(
                            onClick = onOpenControls,
                            modifier = Modifier
                                .weight(0.85f)
                                .minimumInteractiveComponentSize()
                        ) {
                            Text("Profiles")
                        }
                    }
                }
            }

            // Streaming Quality Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CharcoalSurface,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.HighQuality, contentDescription = null, tint = MutedCyan, modifier = Modifier.size(18.dp))
                        Text(
                        text = if (uiState.streamQualityConsoleName == null) "Default stream quality" else "Stream quality",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateMutedText
                        )
                    }
                    Text(
                        text = uiState.streamQuality.summaryLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        color = SoftIceText
                    )
                    Text(
                        text = "${if (uiState.streamQuality.autoQuality) "Auto" else "Manual"} ${uiState.streamQuality.effectiveBitrateKbps / 1000} Mbps  •  Audio ${(uiState.streamQuality.audioVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                            Text("Stream Setup")
                    }
                }
            }
        }
    }
}
