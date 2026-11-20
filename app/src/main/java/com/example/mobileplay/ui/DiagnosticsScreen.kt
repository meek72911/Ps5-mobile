package com.example.mobileplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mobileplay.engine.DualSenseButtons
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
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    uiState: MobilePlayUiState,
    onBack: () -> Unit,
    onEmergencyClear: () -> Unit
) {
    BackHandler(onBack = onBack)

    val pad = uiState.telemetry.padState
    val timing = uiState.telemetry.timing
    val pointers = uiState.telemetry.pointers
    val video = uiState.videoTelemetry
    val audio = uiState.audioTelemetry
    val device = uiState.deviceMetrics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("diagnostics_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SoftIceText)
                }
                Column {
                    Text("DIAGNOSTICS & TELEMETRY", style = MaterialTheme.typography.titleMedium, color = SoftIceText)
                    Text(
                        text = "Measured controller input, hardware codec/audio state, and device resources (no invented metrics)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText
                    )
                }
            }

            OutlinedButton(
                onClick = onEmergencyClear,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("diagnostics_clear_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TacticalCoral, modifier = Modifier.padding(end = 6.dp))
                Text("Zero Inputs")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Pane: DualSense Sticks, Triggers, Button Matrix, Device CPU/RAM
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
                        Text("VIRTUAL DUALSENSE AXES", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StickVisualizer("LEFT STICK", pad.leftX, pad.leftY)
                            StickVisualizer("RIGHT STICK", pad.rightX, pad.rightY)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TriggerMeter("L2 (AIM / BRAKE)", pad.l2, MutedCyan, Modifier.weight(1f))
                            TriggerMeter("R2 (FIRE / GAS)", pad.r2, TacticalCoral, Modifier.weight(1f))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("BUTTON STATES", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        val buttons = listOf(
                            "CROSS (×)" to DualSenseButtons.CROSS,
                            "CIRCLE (○)" to DualSenseButtons.CIRCLE,
                            "SQUARE (□)" to DualSenseButtons.SQUARE,
                            "TRIANGLE (△)" to DualSenseButtons.TRIANGLE,
                            "L1" to DualSenseButtons.L1,
                            "R1" to DualSenseButtons.R1,
                            "L3" to DualSenseButtons.L3,
                            "OPTIONS" to DualSenseButtons.OPTIONS,
                            "PS" to DualSenseButtons.PS,
                            "TOUCHPAD" to DualSenseButtons.TOUCHPAD
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            buttons.forEach { (name, bit) ->
                                val pressed = pad.isPressed(bit)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (pressed) MutedCyan.copy(alpha = 0.25f) else CharcoalSurfaceHigh,
                                    border = BorderStroke(1.dp, if (pressed) MutedCyan else CharcoalBorder)
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pressed) SoftIceText else SlateMutedText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("PHONE HARDWARE & NETWORK", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        MetricRow("Network Transport", device.networkTransportLabel)
                        MetricRow("CPU Cores Available", "${device.cpuCoreCount}")
                        MetricRow("Device RAM Available", "${device.availableMemoryMb} MB / ${device.totalMemoryMb} MB")
                        MetricRow("App JVM Heap Used", "${device.appHeapUsedMb} MB")
                    }
                }
            }

            // Right Pane: Input Rate, Active Pointers, Video/Audio/Stream Metrics
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("INPUT ENGINE TIMING", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        MetricRow("Input Update Rate", "${timing.smoothedRateHz.roundToInt()} Hz")
                        MetricRow("Last Touch Interval", "%.2f ms".format(timing.lastEventIntervalMs))
                        MetricRow("Events Processed", "${timing.totalEventsProcessed}")
                        MetricRow("Watchdog Drift Resets", "${timing.watchdogZeroCount}")
                        MetricRow("Active Touch Pointers", "${pointers.size}")
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("VIDEO, AUDIO & NETWORK STREAM", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        MetricRow("libchiaki Native", if (uiState.isNativeLibchiakiLoaded) "LOADED" else "NOT BUNDLED (NDK CI)")
                        MetricRow("Video Surface (16:9)", if (video.surfaceReady) "${video.surfaceWidth}×${video.surfaceHeight} READY" else "DETACHED")
                        MetricRow("MediaCodec State", video.decoderState)
                        MetricRow("Video FPS", if (video.decodedFrames > 0L) "%.1f FPS".format(video.videoFps) else "N/A (no stream)")
                        MetricRow("Decode Latency", if (video.decodedFrames > 0L) "%.2f ms".format(video.lastDecodeTimeMs) else "N/A (no stream)")
                        MetricRow("Decoded / Dropped Frames", "${video.decodedFrames} / ${video.droppedFrames}")
                        MetricRow("AudioTrack PCM", audio.state)
                        MetricRow("Audio Underruns", "${audio.underrunCount}")
                        MetricRow("Network RTT / Packet Loss", "N/A (requires active libchiaki stream)")
                        video.lastError?.let { err ->
                            Text("Decoder error: $err", style = MaterialTheme.typography.labelSmall, color = StatusAmber)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickVisualizer(title: String, x: Float, y: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(90.dp)) {
                val r = size.minDimension * 0.45f
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                drawCircle(CharcoalSurfaceHigh, radius = r, center = center)
                drawCircle(CharcoalBorder, radius = r, center = center, style = Stroke(width = 2.5f))
                drawLine(CharcoalBorder, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = 1.5f)
                drawLine(CharcoalBorder, Offset(center.x, center.y - r), Offset(center.x, center.y + r), strokeWidth = 1.5f)
                val dot = Offset(center.x + x * r * 0.85f, center.y + y * r * 0.85f)
                drawLine(MutedCyan.copy(alpha = 0.6f), center, dot, strokeWidth = 3f)
                drawCircle(
                    color = if (x == 0f && y == 0f) SlateMutedText else MutedCyan,
                    radius = 7.dp.toPx(),
                    center = dot
                )
            }
        }
        Text("%+.2f, %+.2f".format(x, y), style = MaterialTheme.typography.labelMedium, color = SoftIceText)
    }
}

@Composable
private fun TriggerMeter(
    label: String,
    value: Int,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
            Text("$value/255", style = MaterialTheme.typography.labelSmall, color = SoftIceText)
        }
        LinearProgressIndicator(
            progress = { (value / 255f).coerceIn(0f, 1f) },
            color = accent,
            trackColor = CharcoalSurfaceHigh,
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = SlateMutedText)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (value.contains("READY") || value == "LOADED" || value == "Local Wi-Fi") StatusEmerald else SoftIceText
        )
    }
}
