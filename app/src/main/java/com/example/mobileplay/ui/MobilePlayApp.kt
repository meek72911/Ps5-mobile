package com.example.mobileplay.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    viewModel: MobilePlayViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var layoutEditorReturnScreen by remember { mutableStateOf(AppScreen.CONTROLS) }

    when (uiState.activeScreen) {
        AppScreen.GAMEPLAY -> GameplayScreen(
            uiState = uiState,
            videoRenderer = viewModel.videoRenderer,
            onPadStateChanged = viewModel::onOverlayPadState,
            onTelemetryUpdated = viewModel::onOverlayTelemetry,
            onToggleDrivingMode = viewModel::toggleDrivingMode,
            onEmergencyClear = viewModel::triggerEmergencyClear,
            onSetDrawerOpen = viewModel::setDrawerOpen,
            onNavigate = viewModel::navigateTo,
            onOpenLayoutEditor = {
                layoutEditorReturnScreen = AppScreen.GAMEPLAY
                viewModel.openLayoutEditor()
            },
            onBackToHome = { viewModel.navigateTo(AppScreen.HOME) },
            onUpdateProfile = viewModel::updateProfile,
            onOpenLicenses = { viewModel.setShowLicenseDialog(true) }
        )

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
            onConnectConsole = { console ->
                viewModel.connectToConsole(console)
                viewModel.navigateTo(AppScreen.GAMEPLAY)
            }
        )

        AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
            uiState = uiState,
            onBack = { viewModel.navigateTo(AppScreen.SETTINGS) },
            onEmergencyClear = viewModel::triggerEmergencyClear
        )

        AppScreen.HOME,
        AppScreen.CONSOLES,
        AppScreen.CONTROLS,
        AppScreen.SETTINGS -> {
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
                            Icon(
                                imageVector = Icons.Default.Gamepad,
                                contentDescription = "MobilePlay",
                                tint = MutedCyan,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "MP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedCyan
                            )
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
                            onConnectConsole = { console ->
                                viewModel.connectToConsole(console)
                                viewModel.navigateTo(AppScreen.GAMEPLAY)
                            },
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
                            onConnectConsole = { console ->
                                viewModel.connectToConsole(console)
                                viewModel.navigateTo(AppScreen.GAMEPLAY)
                            },
                            onDisconnect = viewModel::disconnectSession,
                            onDeleteConsole = viewModel::deleteSavedConsole
                        )

                        AppScreen.CONTROLS -> ControlsScreen(
                            uiState = uiState,
                            onSelectProfile = viewModel::selectProfile,
                            onOpenLayoutEditor = {
                                layoutEditorReturnScreen = AppScreen.CONTROLS
                                viewModel.openLayoutEditor()
                            },
                            onDuplicateProfile = viewModel::duplicateActiveProfile,
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

    if (uiState.showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowLicenseDialog(false) },
            containerColor = CharcoalSurface,
            titleContentColor = SoftIceText,
            textContentColor = SlateMutedText,
            title = {
                Text(
                    text = "Open-Source & AGPL-3.0 Compliance",
                    style = MaterialTheme.typography.titleMedium,
                    color = MutedCyan
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MobilePlay integrates a pure-Kotlin multi-touch DualSense control engine, visual layout editor, Android Keystore AES-256-GCM vault, MediaCodec/AudioTrack renderers, and a CMake/JNI bridge targeting libchiaki.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftIceText
                    )
                    Text(
                        text = "Upstream Remote Play Foundations: Momiji (github.com/yazanj082/momiji) and chiaki-ng / libchiaki (github.com/chiaki-ng/chiaki-ng), licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "AGPL-3.0 Obligations: COPYING and NOTICE_AGPL.md are included in the repository root. Any distributed APK/AAB linked with libchiaki or Momiji sources must preserve all upstream copyright notices and provide corresponding source code.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Trademark Notice: PlayStation, PS5, and DualSense are registered trademarks of Sony Interactive Entertainment Inc. MobilePlay is an independent open-source project and is not affiliated with Sony.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setShowLicenseDialog(false) }) {
                    Text("Close", color = MutedCyan)
                }
            }
        )
    }
}
