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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.video.StreamCodec
import com.example.mobileplay.video.StreamFrameRate
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.StreamResolution
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.MutedCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.SoftIceText
import com.example.ui.theme.StatusEmerald
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    uiState: MobilePlayUiState,
    onUpdateStreamQuality: ((StreamQualitySettings) -> StreamQualitySettings) -> Unit,
    onUpdateProfile: ((ControlProfile) -> ControlProfile) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLicenses: () -> Unit
) {
    val q = uiState.streamQuality
    val caps = uiState.codecCapabilities

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "SETTINGS & PERFORMANCE",
            style = MaterialTheme.typography.titleMedium,
            color = SoftIceText
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left Column: Resolution, FPS, Codec, Bitrate
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CharcoalSurface,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("VIDEO STREAM QUALITY", style = MaterialTheme.typography.labelMedium, color = MutedCyan)

                    // Resolution
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Streaming Resolution", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StreamResolution.entries.forEach { res ->
                                FilterChip(
                                    selected = q.resolution == res,
                                    onClick = { onUpdateStreamQuality { it.copy(resolution = res) } },
                                    label = { Text(res.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MutedCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = SoftIceText
                                    ),
                                    modifier = Modifier.testTag("settings_res_${res.name.lowercase()}")
                                )
                            }
                        }
                    }

                    // Frame Rate
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Frame Rate", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StreamFrameRate.entries.forEach { fps ->
                                FilterChip(
                                    selected = q.frameRate == fps,
                                    onClick = { onUpdateStreamQuality { it.copy(frameRate = fps) } },
                                    label = { Text(fps.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MutedCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = SoftIceText
                                    ),
                                    modifier = Modifier.testTag("settings_fps_${fps.fps}")
                                )
                            }
                        }
                    }

                    // Codec (only enables HEVC selection if hardware/device supports it or clearly shows capability)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Video Codec", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = q.codec == StreamCodec.H264,
                                onClick = { onUpdateStreamQuality { it.copy(codec = StreamCodec.H264) } },
                                label = { Text("H.264") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MutedCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = SoftIceText
                                )
                            )
                            FilterChip(
                                selected = q.codec == StreamCodec.HEVC,
                                enabled = caps.hevcHardwareSupported,
                                onClick = { onUpdateStreamQuality { it.copy(codec = StreamCodec.HEVC) } },
                                label = {
                                    Text(if (caps.hevcHardwareSupported) "HEVC (HW)" else "HEVC (Unsupported)")
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MutedCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = SoftIceText
                                )
                            )
                        }
                        Text(
                            text = "Device Decoders — H.264: ${caps.h264DecoderName} | HEVC: ${caps.hevcDecoderName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (caps.h264HardwareSupported) StatusEmerald else SlateMutedText
                        )
                    }

                    // Auto vs Manual Bitrate
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Bitrate", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                            Text(
                                text = "Current target: ${q.effectiveBitrateKbps / 1000} Mbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateMutedText
                            )
                        }
                        Switch(
                            checked = q.autoQuality,
                            onCheckedChange = { auto -> onUpdateStreamQuality { it.copy(autoQuality = auto) } }
                        )
                    }

                    if (!q.autoQuality) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Manual Bitrate", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                                Text("${q.manualBitrateKbps / 1000} Mbps", style = MaterialTheme.typography.labelMedium, color = MutedCyan)
                            }
                            Slider(
                                value = (q.manualBitrateKbps / 1000f),
                                onValueChange = { mbps ->
                                    onUpdateStreamQuality { it.copy(manualBitrateKbps = (mbps.roundToInt() * 1000).coerceIn(2_000, 30_000)) }
                                },
                                valueRange = 2f..30f,
                                colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan)
                            )
                        }
                    }
                }
            }

            // Right Column: Audio, Decoding, Diagnostics Shortcut, AGPL License
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("AUDIO & ENGINE", style = MaterialTheme.typography.labelMedium, color = MutedCyan)

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Audio Volume", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                                Text("${(q.audioVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MutedCyan)
                            }
                            Slider(
                                value = q.audioVolume,
                                onValueChange = { v -> onUpdateStreamQuality { it.copy(audioVolume = v) } },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Hardware MediaCodec Decoding", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                                Text("Low-latency surface decoding when libchiaki streams", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                            }
                            Switch(
                                checked = q.hardwareDecodingEnabled,
                                onCheckedChange = { hw -> onUpdateStreamQuality { it.copy(hardwareDecodingEnabled = hw) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("On-Screen Telemetry Strip", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                                Text("Show stick & pointer strip during gameplay", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                            }
                            Switch(
                                checked = uiState.activeProfile.showDiagnosticsHud,
                                onCheckedChange = { show -> onUpdateProfile { it.copy(showDiagnosticsHud = show) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Legacy 3-Control Overlay Flag", style = MaterialTheme.typography.bodySmall, color = SoftIceText)
                                Text("Reversible Phase 1 fallback (Move + Camera + Fire)", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                            }
                            Switch(
                                checked = uiState.activeProfile.useLegacyOverlay,
                                onCheckedChange = { legacy -> onUpdateProfile { it.copy(useLegacyOverlay = legacy) } }
                            )
                        }
                    }
                }

                // Diagnostics & Open-Source Legal Section
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("DIAGNOSTICS & LICENSING", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("settings_open_diagnostics_button")
                        ) {
                            Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = MutedCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Network, Input & Device Diagnostics")
                        }
                        OutlinedButton(
                            onClick = onOpenLicenses,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("settings_open_licenses_button")
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = SlateMutedText, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GNU AGPL-3.0 & Upstream Notices")
                        }
                    }
                }
            }
        }
    }
}
