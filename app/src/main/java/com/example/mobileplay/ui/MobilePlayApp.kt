package com.example.mobileplay.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.mobileplay.remoteplay.ConnectionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.MutedCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.SoftIceText

private data class RailDestination(
    val screen: AppScreen,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

private val TOP_LEVEL_DESTINATIONS = listOf(
    RailDestination(AppScreen.HOME, "Home", Icons.Default.Home, "nav_rail_home"),
    RailDestination(AppScreen.CONSOLES, "Consoles", Icons.Default.Router, "nav_rail_consoles"),
    RailDestination(AppScreen.CONTROLS, "Controls", Icons.Default.Tune, "nav_rail_controls"),
    RailDestination(AppScreen.SETTINGS, "Settings", Icons.Default.Settings, "nav_rail_settings")
)

@Composable
fun MobilePlayApp(
    viewModel: MobilePlayViewModel,
    videoOnly: Boolean = false,
    onPictureInPicture: (() -> Boolean)? = null
) {
    val uiState by viewModel.appUiState.collectAsStateWithLifecycle()
    val gamepadControls by viewModel.gamepadControls.collectAsStateWithLifecycle()
    val physicalAim by viewModel.physicalAim.collectAsStateWithLifecycle()
    var layoutEditorReturnScreen by remember { mutableStateOf(AppScreen.CONTROLS) }
    val consolePrompt by viewModel.consoleControls.prompt.collectAsStateWithLifecycle()
    val standbyPending by viewModel.consoleControls.standbyPending.collectAsStateWithLifecycle()
    val wakeProgress by viewModel.wakeProgress.collectAsStateWithLifecycle()
    val microphone by viewModel.microphoneState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setMicrophoneEnabled(true)
    }
    var confirmStandby by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(consolePrompt) {
        if (consolePrompt != null) viewModel.triggerEmergencyClear()
    }

    val editingDuringGameplay = uiState.activeScreen == AppScreen.LAYOUT_EDITOR &&
        layoutEditorReturnScreen == AppScreen.GAMEPLAY && uiState.layoutEditor != null
    val screen = if (editingDuringGameplay) AppScreen.GAMEPLAY else uiState.activeScreen
    val streamOpen = uiState.connectionState is ConnectionState.Connected ||
        uiState.connectionState is ConnectionState.Connecting
    Box(Modifier.fillMaxSize()) {
        if (screen == AppScreen.GAMEPLAY || streamOpen || videoOnly) {
            GameplayScreen(
            uiState = uiState,
            videoRenderer = viewModel.videoRenderer,
            onPadStateChanged = { if (uiState.activeScreen == AppScreen.GAMEPLAY) viewModel.onOverlayPadState(it) },
            onTelemetryUpdated = viewModel::onOverlayTelemetry,
            onToggleDrivingMode = viewModel::toggleDrivingMode,
            onEmergencyClear = viewModel::triggerEmergencyClear,
            onSetDrawerOpen = viewModel::setDrawerOpen,
            onNavigate = viewModel::navigateTo,
            onOpenLayoutEditor = {
                viewModel.setDrawerOpen(false)
                layoutEditorReturnScreen = AppScreen.GAMEPLAY
                viewModel.openLayoutEditor()
            },
            onBackToHome = { viewModel.navigateTo(AppScreen.HOME) },
            onCloseStream = {
                viewModel.disconnectSession()
                viewModel.setDrawerOpen(false)
            },
            onSelectProfile = viewModel::selectProfile,
            onUpdateProfile = viewModel::updateProfile,
            gamepadControls = gamepadControls,
            gamepadPreview = viewModel.gamepadPreview,
            gamepadRaw = viewModel.gamepadRaw,
            physicalAim = physicalAim,
            onSetHideTouchWithGamepad = viewModel::setHideTouchWithGamepad,
            onSetGamepadTuning = viewModel::setGamepadTuning,
            onOpenLicenses = { viewModel.setShowLicenseDialog(true) },
            videoOnly = videoOnly,
            onPictureInPicture = onPictureInPicture?.let { request ->
                {
                    viewModel.navigateTo(AppScreen.GAMEPLAY)
                    viewModel.setDrawerOpen(false)
                    if (!request()) actionError = "Android could not start picture-in-picture for this stream."
                }
            },
            onStandby = { if (!standbyPending) confirmStandby = true },
            microphone = microphone,
            onVolumeChange = { volume -> viewModel.updateStreamQuality { it.copy(audioVolume = volume) } },
            onToggleMicrophone = {
                if (microphone.enabled) {
                    viewModel.setMicrophoneEnabled(false)
                } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.setMicrophoneEnabled(true)
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            )
        }
        if (videoOnly) return@Box
        when (screen) {
        AppScreen.GAMEPLAY -> {
            if (editingDuringGameplay) {
                uiState.layoutEditor?.let { editor ->
                    LayoutEditorScreen(
                        editorState = editor,
                        overLiveStream = streamOpen,
                        onUpdateEditor = viewModel::updateLayoutEditor,
                        onSave = { viewModel.saveLayoutEditorAndExit(AppScreen.GAMEPLAY) },
                        onDiscard = { viewModel.discardLayoutEditor(AppScreen.GAMEPLAY) }
                    )
                }
            }
        }

        AppScreen.LAYOUT_EDITOR -> {
            val editor = uiState.layoutEditor
            if (editor != null) {
                LayoutEditorScreen(
                    editorState = editor,
                    onUpdateEditor = viewModel::updateLayoutEditor,
                    onSave = { viewModel.saveLayoutEditorAndExit(layoutEditorReturnScreen) },
                    onDiscard = { viewModel.discardLayoutEditor(layoutEditorReturnScreen) }
                )
            } else {
                viewModel.navigateTo(layoutEditorReturnScreen)
            }
        }

        AppScreen.ONBOARDING -> OnboardingScreen(
            uiState = uiState,
            onSetStep = viewModel::setOnboardingStep,
            onExitToHome = { viewModel.navigateTo(AppScreen.HOME) },
            onOpenConsoles = { viewModel.navigateTo(AppScreen.CONSOLES) },
            onOpenOfflineDemo = { viewModel.navigateTo(AppScreen.GAMEPLAY) },
            onVerifyRedirectUrl = viewModel::completeSonyRedirectUrl,
            onSaveManualAccountId = viewModel::completeSonyManualAccount,
            onScanLocalWifi = viewModel::scanLocalWifi,
            onPairConsole = viewModel::pairAndSaveConsole,
            onConnectConsole = viewModel::connectToConsole
        )

        AppScreen.DIAGNOSTICS -> {
            val diagnostics by viewModel.uiState.collectAsStateWithLifecycle()
            DiagnosticsScreen(
                uiState = diagnostics,
                onBack = { viewModel.navigateTo(if (streamOpen) AppScreen.GAMEPLAY else AppScreen.SETTINGS) },
                onEmergencyClear = viewModel::triggerEmergencyClear
            )
        }

        AppScreen.HOME,
        AppScreen.CONSOLES,
        AppScreen.CONTROLS,
        AppScreen.SETTINGS -> {
            BackHandler(enabled = streamOpen) { viewModel.navigateTo(AppScreen.GAMEPLAY) }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = CharcoalSurface,
                    contentColor = SoftIceText,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(84.dp)
                        .testTag("main_navigation_rail"),
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                        ) {
                            if (streamOpen) {
                                IconButton(
                                    onClick = { viewModel.navigateTo(AppScreen.GAMEPLAY) },
                                    modifier = Modifier.testTag("resume_stream_from_navigation")
                                ) {
                                    Icon(Icons.Default.PlayArrow, "Resume Game", tint = MutedCyan)
                                }
                            } else {
                                Image(painterResource(R.drawable.mobileplay_mark), "MobilePlay", Modifier.size(38.dp))
                            }
                        }
                    }
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TOP_LEVEL_DESTINATIONS.forEach { dest ->
                        val selected = uiState.activeScreen == dest.screen
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.navigateTo(dest.screen) },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ObsidianBg,
                                selectedTextColor = MutedCyan,
                                indicatorColor = MutedCyan,
                                unselectedIconColor = SlateMutedText,
                                unselectedTextColor = SlateMutedText
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                VerticalDivider(color = CharcoalBorder)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    when (uiState.activeScreen) {
                        AppScreen.HOME -> HomeScreen(
                            uiState = uiState,
                            controllerName = gamepadControls.deviceNames.firstOrNull(),
                            onOpenAbout = { viewModel.setShowLicenseDialog(true) },
                            onConnectConsole = viewModel::connectToConsole,
                            onStartOnboarding = { viewModel.startOnboarding(OnboardingStep.WELCOME) },
                            onLaunchGameplay = { viewModel.navigateTo(AppScreen.GAMEPLAY) },
                            onOpenLayoutEditor = {
                                layoutEditorReturnScreen = AppScreen.HOME
                                viewModel.openLayoutEditor()
                            },
                            onOpenControls = { viewModel.navigateTo(AppScreen.CONTROLS) },
                            onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) }
                        )

                        AppScreen.CONSOLES -> ConsoleScreen(
                            uiState = uiState,
                            onStartOnboarding = { viewModel.startOnboarding(OnboardingStep.WELCOME) },
                            onScanLocalWifi = viewModel::scanLocalWifi,
                            onPairConsole = viewModel::pairAndSaveConsole,
                            onConnectConsole = viewModel::connectToConsole,
                            onDisconnect = viewModel::disconnectSession,
                            onDeleteConsole = viewModel::deleteSavedConsole,
                            wakeProgress = wakeProgress,
                            onWakeConsole = viewModel::wakeAndConnect,
                            onCancelWake = viewModel::cancelWake
                        )

                        AppScreen.CONTROLS -> ControlsScreen(
                            uiState = uiState,
                            onSelectProfile = viewModel::selectProfile,
                            onOpenLayoutEditor = {
                                layoutEditorReturnScreen = AppScreen.CONTROLS
                                viewModel.openLayoutEditor()
                            },
                            onDuplicateProfile = viewModel::duplicateActiveProfile,
                            onRenameProfile = viewModel::renameActiveProfile,
                            onDeleteCustomProfile = viewModel::deleteCustomProfile,
                            onUpdateProfile = viewModel::updateProfile,
                            onUpdateTuning = viewModel::updateTuning,
                            onResetProfile = viewModel::resetCurrentProfile,
                            onExportJson = viewModel::exportCurrentProfileJson,
                            onImportJson = { raw -> viewModel.importProfileJson(raw) }
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            uiState = uiState,
                            onUpdateStreamQuality = viewModel::updateStreamQuality,
                            onUpdateProfile = viewModel::updateProfile,
                            onOpenDiagnostics = { viewModel.navigateTo(AppScreen.DIAGNOSTICS) },
                            onOpenLicenses = { viewModel.setShowLicenseDialog(true) }
                        )

                        else -> Unit
                    }
                }
            }
        }
    }

    }

    if (!videoOnly) {
        consolePrompt?.let { prompt ->
            ConsoleSessionDialog(
                prompt = prompt,
                controls = viewModel.consoleControls,
                onDisconnect = viewModel::disconnectSession,
                onLoginTimeout = {
                    viewModel.disconnectSession()
                    actionError = "PS5 login timed out. Reconnect and enter the user passcode again."
                }
            )
        }
        if (confirmStandby) {
            AlertDialog(
                onDismissRequest = { confirmStandby = false },
                title = { Text("Put PS5 into rest mode?") },
                text = { Text("The stream will close and the PS5 will enter rest mode.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStandby = false
                        if (!viewModel.standbyAndDisconnect()) {
                            actionError = "The PS5 rest-mode request could not be sent."
                        }
                    }) { Text("Disconnect & rest PS5") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStandby = false }) { Text("Cancel") }
                }
            )
        }
        actionError?.let { error ->
            AlertDialog(
                onDismissRequest = { actionError = null },
                title = { Text("Stream action") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { actionError = null }) { Text("Close") } }
            )
        }
    }

    if (uiState.showLicenseDialog && !videoOnly) {
        AboutMobilePlay(onClose = { viewModel.setShowLicenseDialog(false) })
    }
}
