package com.example.mobileplay.ui

import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import com.example.mobileplay.audio.MicrophoneState

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
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import com.example.mobileplay.engine.GyroMode
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.input.GamepadControls
import com.example.mobileplay.input.GamepadRawSample
import com.example.mobileplay.input.GamepadTuning
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
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

private enum class StreamMenuPage(val label: String) {
    PLAY("Play"), CONTROLS("Controls"), SETTINGS("Settings")
}

@Composable
internal fun GameplayScreen(
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
    onCloseStream: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ControlProfile) -> ControlProfile) -> Unit,
    gamepadControls: GamepadControls,
    gamepadPreview: StateFlow<PadState>,
    gamepadRaw: StateFlow<GamepadRawSample?>,
    physicalAim: Boolean,
    onSetHideTouchWithGamepad: (Boolean) -> Unit,
    onSetGamepadTuning: (GamepadTuning) -> Unit,
    onOpenLicenses: () -> Unit,
    videoOnly: Boolean = false,
    onPictureInPicture: (() -> Unit)? = null,
    onStandby: (() -> Unit)? = null,
    microphone: MicrophoneState = MicrophoneState(),
    onToggleMicrophone: () -> Unit = {},
    onVolumeChange: (Float) -> Unit = {}
) {
    var overlayViewRef by remember { mutableStateOf<TouchOverlayView?>(null) }
    var menuPage by remember(uiState.drawerOpen) { mutableStateOf(StreamMenuPage.PLAY) }
    var gyroCalibration by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.clearInputGeneration) {
        if (uiState.clearInputGeneration > 0L) {
            overlayViewRef?.clearInput()
        }
    }

    BackHandler(enabled = !videoOnly) {
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

        if (videoOnly) return@Box

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
                view.onPadStateChanged = onPadStateChanged
                view.onTelemetryUpdated = onTelemetryUpdated
                view.updateProfile(uiState.activeProfile)
                view.physicalAim = physicalAim
                view.systemButtonsOnly = gamepadControls.showSystemButtonsOnly
                view.gyroGameplayActive = uiState.activeScreen == AppScreen.GAMEPLAY && !uiState.drawerOpen &&
                    uiState.connectionState is ConnectionState.Connected
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
                healthNote = uiState.videoTelemetry.healthNote,
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
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onSetDrawerOpen(false) },
                            modifier = Modifier.fillMaxWidth().testTag("close_drawer_button")
                        ) {
                            Text("Resume Game")
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StreamMenuPage.entries.forEach { page ->
                                FilterChip(
                                    selected = menuPage == page,
                                    onClick = { menuPage = page },
                                    label = { Text(page.label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                        if (menuPage == StreamMenuPage.SETTINGS) {
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
                                title = "Edit Layout",
                                subtitle = "Drag, resize & rebind buttons",
                                selected = false,
                                testTag = "drawer_nav_layout_editor",
                                onClick = onOpenLayoutEditor
                            )
                            DrawerNavButton(
                                icon = Icons.Default.Router,
                                title = "Consoles & Pairing",
                                subtitle = "Find or manage a console",
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
                                title = "Stream Settings",
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

                        }

                        if (menuPage == StreamMenuPage.PLAY) {
                            var volume by remember(uiState.streamQuality.audioVolume) { mutableStateOf(uiState.streamQuality.audioVolume) }
                            Text("Volume ${(volume * 100).roundToInt()}%")
                            Slider(
                                value = volume,
                                onValueChange = { volume = it },
                                onValueChangeFinished = { onVolumeChange(volume) },
                                modifier = Modifier.testTag("drawer_stream_volume")
                            )
                            DrawerNavButton(
                                icon = Icons.Default.Edit,
                                title = "Edit Layout",
                                subtitle = "Adjust the controls while you play",
                                selected = false,
                                testTag = "drawer_quick_editor",
                                onClick = onOpenLayoutEditor
                            )
                            if (uiState.connectionState is ConnectionState.Connected ||
                                uiState.connectionState is ConnectionState.Connecting
                            ) {
                                DrawerNavButton(
                                    icon = Icons.Default.Close,
                                    title = "Close Stream",
                                    subtitle = "Disconnect from PS5",
                                    selected = false,
                                    testTag = "drawer_close_stream",
                                    onClick = onCloseStream
                                )
                                if (uiState.connectionState is ConnectionState.Connected) {
                                    DrawerNavButton(
                                        icon = if (microphone.enabled) Icons.Default.Mic else Icons.Default.MicOff,
                                        title = if (microphone.enabled) "Mute Microphone" else "Enable Microphone",
                                        subtitle = microphone.error ?: "Send phone microphone audio to PS5",
                                        selected = microphone.enabled,
                                        testTag = "drawer_microphone",
                                        onClick = onToggleMicrophone
                                    )
                                    if (onPictureInPicture != null) {
                                        DrawerNavButton(
                                            icon = Icons.Default.PictureInPictureAlt,
                                            title = "Picture-in-picture",
                                            subtitle = "Keep the stream in a floating window",
                                            selected = false,
                                            testTag = "drawer_picture_in_picture",
                                            onClick = onPictureInPicture
                                        )
                                    }
                                    if (onStandby != null) {
                                        DrawerNavButton(
                                            icon = Icons.Default.PowerSettingsNew,
                                            title = "Disconnect & rest PS5",
                                            subtitle = "Put the console in rest mode",
                                            selected = false,
                                            testTag = "drawer_standby",
                                            onClick = onStandby
                                        )
                                    }
                                }
                            }
                        }
                        if (menuPage == StreamMenuPage.CONTROLS) {
                            Text(
                                text = "Touch layout",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateMutedText
                            )

                            DrawerNavButton(
                                icon = Icons.Default.Gamepad,
                                title = "Classic Layout",
                                subtitle = "Traditional PlayStation button layout",
                                selected = uiState.activeProfile.id == ControlProfile.ID_STANDARD_CONTROLLER,
                                testTag = "drawer_controller_standard",
                                onClick = {
                                    onSelectProfile(ControlProfile.ID_STANDARD_CONTROLLER)
                                    onSetDrawerOpen(false)
                                }
                            )
                            DrawerNavButton(
                                icon = Icons.Default.Gamepad,
                                title = "BGMI Layout",
                                subtitle = "BGMI-style touch controls",
                                selected = uiState.activeProfile.id == ControlProfile.ID_ON_FOOT,
                                testTag = "drawer_controller_shooter",
                                onClick = {
                                    onSelectProfile(ControlProfile.ID_ON_FOOT)
                                    onSetDrawerOpen(false)
                                }
                            )

                            DrawerNavButton(
                                icon = Icons.Default.DirectionsCar,
                                title = if (uiState.activeProfile.mode == ControlMode.DRIVING) "Return to On-Foot" else "Driving Layout",
                                subtitle = "Switch between driving and on-foot controls",
                                selected = uiState.activeProfile.mode == ControlMode.DRIVING,
                                testTag = "drawer_controller_driving",
                                onClick = {
                                    onToggleDrivingMode()
                                    onSetDrawerOpen(false)
                                }
                            )

                            GamepadMenu(
                                controls = gamepadControls,
                                preview = gamepadPreview,
                                rawInput = gamepadRaw,
                                onSetHideTouch = onSetHideTouchWithGamepad,
                                onSetTuning = onSetGamepadTuning
                            )

                            Text("Camera Aim", style = MaterialTheme.typography.titleSmall, color = SoftIceText)
                            val aim = uiState.activeProfile.tuning
                            Text("Swipe boost ${(aim.swipeBoost * 10).roundToInt() / 10f}×", color = SoftIceText)
                            Slider(value = aim.swipeBoost, valueRange = 1f..8f, onValueChange = { value ->
                                onUpdateProfile { it.copy(tuning = it.tuning.copy(swipeBoost = value)) }
                            })
                            Text("Aiming speed ${(aim.adsSensitivityMultiplier * 100).roundToInt()}%", color = SoftIceText)
                            Slider(value = aim.adsSensitivityMultiplier, valueRange = 0.1f..1.5f, onValueChange = { value ->
                                onUpdateProfile { it.copy(tuning = it.tuning.copy(adsSensitivityMultiplier = value)) }
                            })
                            Text("Phone gyroscope", color = SoftIceText)
                            if (overlayViewRef?.hasGyroscope == true) {
                                GyroMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = aim.gyroMode == mode,
                                        onClick = { onUpdateProfile { it.copy(tuning = it.tuning.copy(gyroMode = mode)) } },
                                        label = { Text(mode.label) }
                                    )
                                }
                                if (aim.gyroMode != GyroMode.OFF) {
                                    Text("Gyro sensitivity ${(aim.gyroSensitivity * 100).roundToInt()}%", color = SoftIceText)
                                    Slider(value = aim.gyroSensitivity, valueRange = 0.5f..3f, onValueChange = { value ->
                                        onUpdateProfile { it.copy(tuning = it.tuning.copy(gyroSensitivity = value)) }
                                    })
                                    DrawerToggleRow("Invert gyro horizontal", aim.gyroInvertX, "gyro_invert_x") { enabled ->
                                        onUpdateProfile { it.copy(tuning = it.tuning.copy(gyroInvertX = enabled)) }
                                    }
                                    DrawerToggleRow("Invert gyro vertical", aim.gyroInvertY, "gyro_invert_y") { enabled ->
                                        onUpdateProfile { it.copy(tuning = it.tuning.copy(gyroInvertY = enabled)) }
                                    }
                                    TextButton(onClick = {
                                        gyroCalibration = "Hold the phone still"
                                        overlayViewRef?.calibrateGyro { success ->
                                            gyroCalibration = if (success) "Gyro calibrated" else "Hold still and try again"
                                        }
                                    }) { Text("Calibrate gyro") }
                                    gyroCalibration?.let { Text(it, color = SlateMutedText) }
                                }
                            } else {
                                Text("Gyroscope unavailable", color = SlateMutedText)
                            }

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

                        }

                        if (menuPage == StreamMenuPage.SETTINGS) {

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
                                        text = "About MobilePlay",
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
    healthNote: String?,
    onClick: () -> Unit
) {
    val (dotColor, label) = when (state) {
        is ConnectionState.Connected -> healthNote?.let { StatusAmber to it }
            ?: (StatusEmerald to "PS5 LIVE • ${state.hostName}")
        is ConnectionState.Connecting -> StatusAmber to (state.detail ?: "CONNECTING • ${state.hostAddress}")
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
private fun GamepadMenu(
    controls: GamepadControls,
    preview: StateFlow<PadState>,
    rawInput: StateFlow<GamepadRawSample?>,
    onSetHideTouch: (Boolean) -> Unit,
    onSetTuning: (GamepadTuning) -> Unit
) {
    Text("Physical controller", style = MaterialTheme.typography.titleSmall, color = SoftIceText)
    DrawerToggleRow(
        label = "Auto-hide touch layout",
        checked = controls.hideTouchControls,
        testTag = "gamepad_hide_touch",
        onCheckedChange = onSetHideTouch
    )
    if (controls.deviceNames.isEmpty()) {
        Text(
            "Pair a DualSense in Android Bluetooth settings or connect it by USB-C.",
            style = MaterialTheme.typography.bodySmall,
            color = SlateMutedText
        )
        return
    }
    Text(controls.deviceNames.joinToString(", "), color = MutedCyan, modifier = Modifier.testTag("gamepad_connected"))
    var calibrating by remember { mutableStateOf(false) }
    TextButton(onClick = { calibrating = !calibrating }) {
        Text(if (calibrating) "Close Calibration" else "Test & Calibrate")
    }
    if (!calibrating) return
    val tuning = controls.tuning
    val raw by rawInput.collectAsStateWithLifecycle()
    var leftPeak by remember { mutableStateOf(0f) }
    var rightPeak by remember { mutableStateOf(0f) }
    LaunchedEffect(raw) {
        raw?.let { sample ->
            leftPeak = maxOf(leftPeak, sample.leftRadius.coerceAtMost(1f))
            rightPeak = maxOf(rightPeak, sample.rightRadius.coerceAtMost(1f))
        }
    }
    Text("Move both sticks around their edges, then release them.")
    Text("Peak travel · Left ${(leftPeak * 100).roundToInt()}% · Right ${(rightPeak * 100).roundToInt()}%")
    val centered = raw?.takeIf { it.leftRadius < 0.25f && it.rightRadius < 0.25f }
    TextButton(
        enabled = centered != null,
        onClick = {
            val sample = centered ?: return@TextButton
            onSetTuning(tuning.copy(
                leftDeadzone = (sample.leftRadius + 0.03f).coerceIn(0.04f, GamepadControls.MAX_DEADZONE),
                rightDeadzone = (sample.rightRadius + 0.03f).coerceIn(0.04f, GamepadControls.MAX_DEADZONE)
            ))
        }
    ) { Text("Use Resting Stick Position") }
    ControllerTuningSlider("Left dead zone", tuning.leftDeadzone, 0f..GamepadControls.MAX_DEADZONE, "gamepad_deadzone") {
        onSetTuning(tuning.copy(leftDeadzone = it))
    }
    ControllerTuningSlider("Right dead zone", tuning.rightDeadzone, 0f..GamepadControls.MAX_DEADZONE, "gamepad_right_deadzone") {
        onSetTuning(tuning.copy(rightDeadzone = it))
    }
    ControllerTuningSlider("Right-stick response (100% linear)", tuning.rightCurve, 0.5f..2f, "gamepad_response") {
        onSetTuning(tuning.copy(rightCurve = it))
    }
    val state by preview.collectAsStateWithLifecycle()
    val buttons = DualSenseButtons.activeNames(state.buttons)
    Text("Controller test", style = MaterialTheme.typography.labelLarge, color = SoftIceText)
    Text(
        text = if (buttons.isEmpty()) "Press a button or move a stick"
        else buttons.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MutedCyan,
        modifier = Modifier.testTag("gamepad_test_buttons")
    )
    Text(
        "L ${(state.leftX * 100).roundToInt()}, ${(state.leftY * 100).roundToInt()}   " +
            "R ${(state.rightX * 100).roundToInt()}, ${(state.rightY * 100).roundToInt()}\n" +
            "L2 ${state.l2}/255   R2 ${state.r2}/255",
        style = MaterialTheme.typography.bodySmall,
        color = SlateMutedText,
        modifier = Modifier.testTag("gamepad_test_axes")
    )
}

@Composable
private fun ControllerTuningSlider(
    label: String,
    saved: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onSave: (Float) -> Unit
) {
    var value by remember(saved) { mutableStateOf(saved) }
    Text("$label · ${(value * 100f).roundToInt()}%", color = SoftIceText)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onSave(value) },
        valueRange = range,
        modifier = Modifier.testTag(testTag)
    )
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
