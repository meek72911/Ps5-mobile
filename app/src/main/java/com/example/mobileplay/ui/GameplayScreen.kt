package com.example.mobileplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.overlay.OverlayTelemetry
import com.example.mobileplay.overlay.TouchOverlayView
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.video.AspectRatioSurfaceView
import com.example.mobileplay.video.VideoRenderer
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

@Composable
fun GameplayScreen(
    uiState: MobilePlayUiState,
    videoRenderer: VideoRenderer,
    onPadStateChanged: (PadState) -> Unit,
    onTelemetryUpdated: (OverlayTelemetry) -> Unit,
    onToggleDrivingMode: () -> Unit,
    onEmergencyClear: () -> Unit,
    onSetDrawerOpen: (Boolean) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    onOpenLayoutEditor: () -> Unit,
    onBackToHome: () -> Unit,
    onUpdateProfile: ((ControlProfile) -> ControlProfile) -> Unit,
    onOpenLicenses: () -> Unit
) {
    var overlayViewRef by remember { mutableStateOf<TouchOverlayView?>(null) }

    LaunchedEffect(uiState.clearInputGeneration) {
        if (uiState.clearInputGeneration > 0L) {
            overlayViewRef?.clearInput()
        }
    }

    BackHandler {
        if (uiState.drawerOpen) {
            onSetDrawerOpen(false)
        } else {
            onBackToHome()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // 1. 16:9 Remote Play Video SurfaceView fitted without cropping
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    AspectRatioSurfaceView(ctx).also { surfaceView ->
                        surfaceView.aspectRatio = 16f / 9f
                        videoRenderer.attachTo(surfaceView)
                    }
                },
                onRelease = { surfaceView ->
                    videoRenderer.detachFrom(surfaceView)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Honest non-streaming standby backdrop inside 16:9 frame (never simulates fake PS5 video)
            if (uiState.connectionState !is ConnectionState.Connected) {
                StandbyVideoBackdrop(
                    mode = uiState.activeProfile.mode,
                    useLegacyOverlay = uiState.activeProfile.useLegacyOverlay,
                    surfaceWidth = uiState.videoTelemetry.surfaceWidth,
                    surfaceHeight = uiState.videoTelemetry.surfaceHeight
                )
            }
        }

        // 2. Native Custom Android View for low-latency multi-touch DualSense controls
        AndroidView(
            factory = { ctx ->
                TouchOverlayView(ctx).apply {
                    overlayViewRef = this
                    updateProfile(uiState.activeProfile)
                    this.onPadStateChanged = onPadStateChanged
                    this.onTelemetryUpdated = onTelemetryUpdated
                }
            },
            update = { view ->
                overlayViewRef = view
                view.updateProfile(uiState.activeProfile)
                view.onPadStateChanged = onPadStateChanged
                view.onTelemetryUpdated = onTelemetryUpdated
            },
            onRelease = { view ->
                view.clearInput()
                if (overlayViewRef === view) overlayViewRef = null
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("touch_overlay_surface")
        )

        DisposableEffect(Unit) {
            onDispose {
                overlayViewRef?.clearInput()
            }
        }

        // 3. Minimal Top HUD Controls (respecting display cutouts / safe areas)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minimal Connection Status Pill
            ConnectionStatusPill(
                state = uiState.connectionState,
                onClick = { onNavigate(AppScreen.CONSOLES) }
            )

            // Quick Mode Toggle + Layout Editor + Clear + Settings Drawer trigger
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDriving = uiState.activeProfile.mode == ControlMode.DRIVING
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDriving) MutedCyan.copy(alpha = 0.22f) else CharcoalSurface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, if (isDriving) MutedCyan else CharcoalBorder),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleDrivingMode)
                        .testTag("mode_toggle_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isDriving) Icons.Default.DirectionsCar else Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDescription = stringResource(R.string.cd_toggle_mode),
                            tint = if (isDriving) MutedCyan else SoftIceText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isDriving) stringResource(R.string.mode_driving) else stringResource(R.string.mode_on_foot),
                            style = MaterialTheme.typography.labelLarge,
                            color = SoftIceText
                        )
                    }
                }

                IconButton(
                    onClick = onOpenLayoutEditor,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CharcoalSurface.copy(alpha = 0.82f))
                        .border(1.dp, CharcoalBorder, RoundedCornerShape(8.dp))
                        .testTag("gameplay_edit_layout_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Control Layout",
                        tint = MutedCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onEmergencyClear,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CharcoalSurface.copy(alpha = 0.82f))
                        .border(1.dp, CharcoalBorder, RoundedCornerShape(8.dp))
                        .testTag("clear_inputs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = stringResource(R.string.cd_clear_inputs),
                        tint = TacticalCoral,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onSetDrawerOpen(true) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CharcoalSurface.copy(alpha = 0.82f))
                        .border(1.dp, MutedCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .testTag("open_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.cd_settings_drawer),
                        tint = SoftIceText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 4. Compact Slide-Over Settings Drawer
        if (uiState.drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable { onSetDrawerOpen(false) }
            ) {
                Surface(
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(320.dp)
                        .clickable(enabled = false) {}
                        .testTag("settings_drawer")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MOBILEPLAY",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MutedCyan
                                )
                                Text(
                                    text = uiState.activeProfile.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SlateMutedText
                                )
                            }
                            IconButton(
                                onClick = { onSetDrawerOpen(false) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("close_drawer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_close_drawer),
                                    tint = SoftIceText
                                )
                            }
                        }

                        HorizontalDivider(color = CharcoalBorder)

                        DrawerNavButton(
                            icon = Icons.Default.Home,
                            title = "Home",
                            subtitle = "Return to launcher & quick actions",
                            selected = false,
                            testTag = "drawer_nav_home",
                            onClick = onBackToHome
                        )
                        DrawerNavButton(
                            icon = Icons.Default.Edit,
                            title = "Visual Layout Editor",
                            subtitle = "Drag, resize & rebind buttons",
                            selected = false,
                            testTag = "drawer_nav_layout_editor",
                            onClick = onOpenLayoutEditor
                        )
                        DrawerNavButton(
                            icon = Icons.Default.Router,
                            title = "Consoles & Pairing",
                            subtitle = "LAN PS5 discovery & Keystore vault",
                            selected = false,
                            testTag = "drawer_nav_console",
                            onClick = { onNavigate(AppScreen.CONSOLES) }
                        )
                        DrawerNavButton(
                            icon = Icons.Default.Tune,
                            title = "Controls & Aim Tuning",
                            subtitle = "Presets, sensitivity, response curve",
                            selected = false,
                            testTag = "drawer_nav_controls",
                            onClick = { onNavigate(AppScreen.CONTROLS) }
                        )
                        DrawerNavButton(
                            icon = Icons.Default.Settings,
                            title = "Settings & Streaming",
                            subtitle = "Resolution, FPS, codec & audio",
                            selected = false,
                            testTag = "drawer_nav_settings",
                            onClick = { onNavigate(AppScreen.SETTINGS) }
                        )
                        DrawerNavButton(
                            icon = Icons.Default.MonitorHeart,
                            title = "Diagnostics",
                            subtitle = "Pointers, sticks, decode & RAM telemetry",
                            selected = false,
                            testTag = "drawer_nav_diagnostics",
                            onClick = { onNavigate(AppScreen.DIAGNOSTICS) }
                        )

                        HorizontalDivider(color = CharcoalBorder)

                        Text(
                            text = "QUICK OVERLAY SETTINGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateMutedText
                        )

                        // Opacity Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Control Opacity",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SoftIceText
                                )
                                Text(
                                    text = "${(uiState.activeProfile.controlOpacity * 100f).roundToInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MutedCyan
                                )
                            }
                            Slider(
                                value = uiState.activeProfile.controlOpacity,
                                onValueChange = { value ->
                                    onUpdateProfile { it.copy(controlOpacity = value.coerceIn(0.15f, 1.0f)) }
                                },
                                valueRange = 0.20f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = MutedCyan,
                                    activeTrackColor = MutedCyan
                                ),
                                modifier = Modifier.testTag("drawer_opacity_slider")
                            )
                        }

                        DrawerToggleRow(
                            label = "On-Screen Telemetry Strip",
                            checked = uiState.activeProfile.showDiagnosticsHud,
                            testTag = "drawer_toggle_hud",
                            onCheckedChange = { enabled ->
                                onUpdateProfile { it.copy(showDiagnosticsHud = enabled) }
                            }
                        )

                        DrawerToggleRow(
                            label = "Legacy 3-Control Flag (M1)",
                            checked = uiState.activeProfile.useLegacyOverlay,
                            testTag = "drawer_toggle_legacy",
                            onCheckedChange = { legacy ->
                                onUpdateProfile { it.copy(useLegacyOverlay = legacy) }
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CharcoalSurfaceHigh,
                            border = BorderStroke(1.dp, CharcoalBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenLicenses)
                                .testTag("open_licenses_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MutedCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Open-Source & AGPL Notices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SoftIceText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandbyVideoBackdrop(
    mode: ControlMode,
    useLegacyOverlay: Boolean,
    surfaceWidth: Int,
    surfaceHeight: Int
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .background(CharcoalSurface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .border(1.dp, CharcoalBorder.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "STANDALONE TOUCH SIMULATOR  •  NO PS5 VIDEO STREAM",
            style = MaterialTheme.typography.labelMedium,
            color = SlateMutedText
        )
        val layoutLabel = when {
            useLegacyOverlay -> "Phase 1 Legacy 3-Control Flag Active"
            mode == ControlMode.DRIVING -> "Driving Preset • Analog Steer + R2/L2 + Camera"
            else -> "BGMI-Style On-Foot Preset • 3+ Finger Multi-Touch"
        }
        val surfaceLabel = if (surfaceWidth > 0 && surfaceHeight > 0) {
            "16:9 SurfaceView ${surfaceWidth}×${surfaceHeight}"
        } else {
            "16:9 SurfaceView Ready"
        }
        Text(
            text = "$layoutLabel  •  $surfaceLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MutedCyan.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun ConnectionStatusPill(
    state: ConnectionState,
    onClick: () -> Unit
) {
    val (dotColor, label) = when (state) {
        is ConnectionState.Connected -> StatusEmerald to "PS5 LIVE • ${state.hostName}"
        is ConnectionState.Connecting -> StatusAmber to "CONNECTING • ${state.hostAddress}"
        is ConnectionState.Discovering -> MutedCyan to "SCANNING LAN..."
        is ConnectionState.Pairing -> StatusAmber to "PAIRING..."
        is ConnectionState.NativeEngineRequired -> StatusAmber to "SIMULATOR • M2 NDK PENDING"
        is ConnectionState.Error -> TacticalCoral to "ERROR • TAP FOR DETAILS"
        ConnectionState.StandaloneSimulator -> MutedCyan to "SIMULATOR • NO PS5"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CharcoalSurface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, CharcoalBorder),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("connection_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = SoftIceText
            )
        }
    }
}

@Composable
private fun DrawerNavButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MutedCyan.copy(alpha = 0.16f) else CharcoalSurfaceHigh,
        border = BorderStroke(1.dp, if (selected) MutedCyan else CharcoalBorder),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MutedCyan else SlateMutedText,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = SoftIceText
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateMutedText
                )
            }
        }
    }
}

@Composable
private fun DrawerToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SoftIceText,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
