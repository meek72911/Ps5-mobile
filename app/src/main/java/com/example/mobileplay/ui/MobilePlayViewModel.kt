package com.example.mobileplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mobileplay.audio.AudioRenderer
import com.example.mobileplay.audio.AudioTelemetry
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.LayoutEditorState
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.overlay.OverlayTelemetry
import com.example.mobileplay.profile.ProfileManager
import com.example.mobileplay.remoteplay.ConnectionManager
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.remoteplay.DiscoveredConsole
import com.example.mobileplay.remoteplay.SonyAuthManager
import com.example.mobileplay.remoteplay.SonyAuthSession
import com.example.mobileplay.security.KeystoreCredentialVault
import com.example.mobileplay.security.RegisteredConsole
import com.example.mobileplay.system.DeviceRuntimeMetrics
import com.example.mobileplay.system.DeviceTelemetryMonitor
import com.example.mobileplay.video.CodecCapabilities
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.VideoRenderer
import com.example.mobileplay.video.VideoTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    CONSOLES,
    CONTROLS,
    SETTINGS,
    DIAGNOSTICS,
    GAMEPLAY,
    LAYOUT_EDITOR,
    ONBOARDING
}

enum class OnboardingStep(val stepNumber: Int, val title: String) {
    WELCOME(1, "Welcome"),
    SIGN_IN(2, "Sony Sign-In"),
    FIND_PS5(3, "Find PS5"),
    PAIR_DEVICE(4, "Pair Device"),
    CONNECT(5, "Connect")
}

data class MobilePlayUiState(
    val activeScreen: AppScreen = AppScreen.HOME,
    val drawerOpen: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.WELCOME,
    val sonyAuthSession: SonyAuthSession? = null,
    val profiles: List<ControlProfile> = ControlProfile.allBuiltInPresets(),
    val activeProfile: ControlProfile = ControlProfile.onFootShooterPreset(),
    val layoutEditor: LayoutEditorState? = null,
    val streamQuality: StreamQualitySettings = StreamQualitySettings(),
    val codecCapabilities: CodecCapabilities = CodecCapabilities(),
    val telemetry: OverlayTelemetry = OverlayTelemetry(),
    val videoTelemetry: VideoTelemetry = VideoTelemetry(),
    val audioTelemetry: AudioTelemetry = AudioTelemetry(),
    val deviceMetrics: DeviceRuntimeMetrics = DeviceRuntimeMetrics(),
    val connectionState: ConnectionState = ConnectionState.StandaloneSimulator,
    val discoveredConsoles: List<DiscoveredConsole> = emptyList(),
    val savedConsoles: List<RegisteredConsole> = emptyList(),
    val lastScanMessage: String = "",
    val pairStatusMessage: String? = null,
    val profileBannerMessage: String? = null,
    val isNativeLibchiakiLoaded: Boolean = false,
    val engineStatusLabel: String = "",
    val isHardwareKeystoreAvailable: Boolean = false,
    val showLicenseDialog: Boolean = false,
    val clearInputGeneration: Long = 0L
)

class MobilePlayViewModel(application: Application) : AndroidViewModel(application) {
    private val profileManager = ProfileManager(application)
    private val vault = KeystoreCredentialVault(application)
    val connectionManager = ConnectionManager(vault)
    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()
    private val telemetryMonitor = DeviceTelemetryMonitor(application) {
        connectionManager.onWifiLost { triggerEmergencyClear() }
        videoRenderer.stopDecoder()
        audioRenderer.stop()
    }

    private val _activeScreen = MutableStateFlow(AppScreen.HOME)
    private val _drawerOpen = MutableStateFlow(false)
    private val _onboardingStep = MutableStateFlow(OnboardingStep.WELCOME)
    private val _sonyAuthSession = MutableStateFlow<SonyAuthSession?>(null)
    private val _profiles = MutableStateFlow(profileManager.loadAllProfiles())
    private val _activeProfile = MutableStateFlow(profileManager.loadActiveProfile())
    private val _layoutEditor = MutableStateFlow<LayoutEditorState?>(null)
    private val _streamQuality = MutableStateFlow(profileManager.loadStreamQuality())
    private val _telemetry = MutableStateFlow(OverlayTelemetry())
    private val _pairStatusMessage = MutableStateFlow<String?>(null)
    private val _profileBannerMessage = MutableStateFlow<String?>(null)
    private val _showLicenseDialog = MutableStateFlow(false)
    private val _clearInputGeneration = MutableStateFlow(0L)

    init {
        telemetryMonitor.start()
        audioRenderer.setVolume(_streamQuality.value.audioVolume)
    }

    private val navSnapshot = combine(
        _activeScreen,
        _drawerOpen,
        _onboardingStep,
        _sonyAuthSession,
        _clearInputGeneration
    ) { screen, drawer, step, auth, clearGen ->
        NavBucket(screen, drawer, step, auth, clearGen)
    }

    private val profileSnapshot = combine(
        _profiles,
        _activeProfile,
        _layoutEditor,
        _streamQuality,
        _profileBannerMessage
    ) { profiles, active, editor, quality, banner ->
        ProfileBucket(profiles, active, editor, quality, banner)
    }

    private val remoteSnapshot = combine(
        connectionManager.connectionState,
        connectionManager.discoveredConsoles,
        connectionManager.savedConsoles,
        connectionManager.lastScanMessage,
        _pairStatusMessage
    ) { conn, discovered, saved, scanMsg, pairMsg ->
        RemoteBucket(conn, discovered, saved, scanMsg, pairMsg)
    }

    private val mediaSnapshot = combine(
        _telemetry,
        videoRenderer.telemetry,
        audioRenderer.telemetry,
        telemetryMonitor.metrics,
        _showLicenseDialog
    ) { inputTel, videoTel, audioTel, deviceTel, licenseOpen ->
        MediaBucket(inputTel, videoTel, audioTel, deviceTel, licenseOpen)
    }

    val uiState: StateFlow<MobilePlayUiState> = combine(
        navSnapshot,
        profileSnapshot,
        remoteSnapshot,
        mediaSnapshot
    ) { nav, prof, remote, media ->
        MobilePlayUiState(
            activeScreen = nav.screen,
            drawerOpen = nav.drawer,
            onboardingStep = nav.onboardingStep,
            sonyAuthSession = nav.authSession,
            profiles = prof.profiles,
            activeProfile = prof.activeProfile,
            layoutEditor = prof.editor,
            streamQuality = prof.quality,
            codecCapabilities = videoRenderer.capabilities,
            telemetry = media.inputTelemetry,
            videoTelemetry = media.videoTelemetry,
            audioTelemetry = media.audioTelemetry,
            deviceMetrics = media.deviceMetrics,
            connectionState = remote.connectionState,
            discoveredConsoles = remote.discovered,
            savedConsoles = remote.saved,
            lastScanMessage = remote.scanMessage,
            pairStatusMessage = remote.pairMessage,
            profileBannerMessage = prof.banner,
            isNativeLibchiakiLoaded = connectionManager.isNativeEngineAvailable,
            engineStatusLabel = connectionManager.engineStatusLabel,
            isHardwareKeystoreAvailable = vault.isHardwareKeystoreAvailable,
            showLicenseDialog = media.showLicenseDialog,
            clearInputGeneration = nav.clearGen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MobilePlayUiState(
            profiles = _profiles.value,
            activeProfile = _activeProfile.value,
            streamQuality = _streamQuality.value,
            codecCapabilities = videoRenderer.capabilities,
            isNativeLibchiakiLoaded = connectionManager.isNativeEngineAvailable,
            engineStatusLabel = connectionManager.engineStatusLabel,
            isHardwareKeystoreAvailable = vault.isHardwareKeystoreAvailable
        )
    )

    fun navigateTo(screen: AppScreen) {
        if (screen != AppScreen.GAMEPLAY) {
            triggerEmergencyClear()
        }
        if (screen == AppScreen.DIAGNOSTICS) {
            telemetryMonitor.refresh()
        }
        _activeScreen.value = screen
        _drawerOpen.value = false
    }

    fun startOnboarding(step: OnboardingStep = OnboardingStep.WELCOME) {
        triggerEmergencyClear()
        _onboardingStep.value = step
        _activeScreen.value = AppScreen.ONBOARDING
        _drawerOpen.value = false
    }

    fun setOnboardingStep(step: OnboardingStep) {
        _onboardingStep.value = step
    }

    fun completeSonyManualAccount(rawAccountId: String): Result<String> {
        val result = SonyAuthManager.normalizeAccountIdToBase64(rawAccountId)
        result.onSuccess { b64 ->
            _sonyAuthSession.value = SonyAuthSession(
                psnAccountIdBase64 = b64,
                methodLabel = "Manual PSN Account ID (8-byte Base64 validated)",
                hasAuthCode = false
            )
        }
        return result
    }

    fun completeSonyRedirectUrl(redirectUrl: String): Result<String> {
        val codeResult = SonyAuthManager.extractAuthorizationCode(redirectUrl)
        codeResult.onSuccess {
            // Never log or display the raw OAuth code; only record that a valid code was parsed.
            _sonyAuthSession.value = SonyAuthSession(
                psnAccountIdBase64 = _sonyAuthSession.value?.psnAccountIdBase64.orEmpty(),
                methodLabel = "Sony Browser OAuth Redirect Verified",
                hasAuthCode = true
            )
        }
        return codeResult.map { "Sony browser OAuth redirect verified. Proceed to console discovery or enter Account ID for local libchiaki registration." }
    }

    fun setDrawerOpen(open: Boolean) {
        if (open) triggerEmergencyClear()
        _drawerOpen.value = open
    }

    fun setShowLicenseDialog(show: Boolean) {
        _showLicenseDialog.value = show
    }

    // ---- Profiles & Controls ----

    fun selectProfile(profileId: String) {
        val target = _profiles.value.firstOrNull { it.id == profileId } ?: return
        _activeProfile.value = target
        profileManager.saveProfile(target)
        triggerEmergencyClear()
    }

    fun switchMode(mode: ControlMode) {
        val loaded = profileManager.loadProfile(mode)
        _activeProfile.value = loaded
        profileManager.saveProfile(loaded)
        _profiles.value = profileManager.loadAllProfiles()
        triggerEmergencyClear()
    }

    fun toggleDrivingMode() {
        val next = if (_activeProfile.value.mode == ControlMode.ON_FOOT) {
            ControlMode.DRIVING
        } else {
            ControlMode.ON_FOOT
        }
        switchMode(next)
    }

    fun updateProfile(transform: (ControlProfile) -> ControlProfile) {
        val updated = runCatching { transform(_activeProfile.value) }.getOrNull() ?: return
        _activeProfile.value = updated
        profileManager.saveProfile(updated)
        _profiles.value = profileManager.loadAllProfiles()
    }

    fun updateTuning(transform: (AimTuning) -> AimTuning) {
        updateProfile { profile -> profile.copy(tuning = transform(profile.tuning)) }
    }

    fun duplicateActiveProfile(newName: String) {
        val created = profileManager.duplicateProfile(_activeProfile.value, newName)
        _activeProfile.value = created
        _profiles.value = profileManager.loadAllProfiles()
        _profileBannerMessage.value = "Created profile \"${created.name}\"."
    }

    fun deleteCustomProfile(id: String) {
        val updatedList = profileManager.deleteCustomProfile(id)
        _profiles.value = updatedList
        if (_activeProfile.value.id == id) {
            _activeProfile.value = updatedList.first()
        }
    }

    fun resetCurrentProfile() {
        val reset = profileManager.resetProfile(_activeProfile.value.id)
        _activeProfile.value = reset
        _profiles.value = profileManager.loadAllProfiles()
        _profileBannerMessage.value = "Reset \"${reset.name}\" to recommended defaults."
        triggerEmergencyClear()
    }

    fun exportCurrentProfileJson(): String = profileManager.exportPrettyJson(_activeProfile.value)

    fun importProfileJson(rawJson: String): Result<ControlProfile> {
        val result = profileManager.importProfileFromJson(rawJson)
        result.onSuccess { imported ->
            _activeProfile.value = imported
            _profiles.value = profileManager.loadAllProfiles()
            _profileBannerMessage.value = "Imported profile \"${imported.name}\"."
        }.onFailure { err ->
            _profileBannerMessage.value = "Import failed: ${err.message ?: "invalid JSON"}"
        }
        return result
    }

    fun clearProfileBanner() {
        _profileBannerMessage.value = null
    }

    // ---- Visual Control Layout Editor ----

    fun openLayoutEditor(profile: ControlProfile = _activeProfile.value) {
        triggerEmergencyClear()
        _layoutEditor.value = LayoutEditorState(draft = profile)
        _activeScreen.value = AppScreen.LAYOUT_EDITOR
        _drawerOpen.value = false
    }

    fun updateLayoutEditor(transform: (LayoutEditorState) -> LayoutEditorState) {
        val current = _layoutEditor.value ?: return
        _layoutEditor.value = transform(current)
    }

    fun saveLayoutEditorAndExit(returnTo: AppScreen = AppScreen.CONTROLS) {
        val draft = _layoutEditor.value?.draft ?: return
        _activeProfile.value = draft
        profileManager.saveProfile(draft)
        _profiles.value = profileManager.loadAllProfiles()
        _layoutEditor.value = null
        _profileBannerMessage.value = "Saved layout for \"${draft.name}\"."
        _activeScreen.value = returnTo
    }

    fun discardLayoutEditor(returnTo: AppScreen = AppScreen.CONTROLS) {
        _layoutEditor.value = null
        _activeScreen.value = returnTo
    }

    // ---- Streaming & Performance Settings ----

    fun updateStreamQuality(transform: (StreamQualitySettings) -> StreamQualitySettings) {
        val updated = transform(_streamQuality.value)
        _streamQuality.value = updated
        profileManager.saveStreamQuality(updated)
        audioRenderer.setVolume(updated.audioVolume)
    }

    // ---- Gameplay Input & Console Connection ----

    fun onOverlayPadState(state: PadState) {
        connectionManager.forwardControllerState(state)
    }

    fun onOverlayTelemetry(telemetry: OverlayTelemetry) {
        _telemetry.value = telemetry
    }

    fun triggerEmergencyClear() {
        _clearInputGeneration.value = _clearInputGeneration.value + 1L
        _telemetry.value = _telemetry.value.copy(
            padState = PadState(),
            pointers = emptyList()
        )
        connectionManager.forwardControllerState(PadState())
    }

    fun scanLocalWifi() {
        viewModelScope.launch {
            telemetryMonitor.refresh()
            connectionManager.discoverLocalConsoles()
        }
    }

    fun pairAndSaveConsole(hostName: String, hostAddress: String, psnAccountId: String, pinCode: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = connectionManager.pairAndSaveConsole(hostName, hostAddress, psnAccountId, pinCode)
            _pairStatusMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Pairing could not complete." }
            )
        }
    }

    fun connectToConsole(console: RegisteredConsole) {
        connectionManager.connectToConsole(
            console = console,
            quality = _streamQuality.value,
            videoRenderer = videoRenderer,
            audioRenderer = audioRenderer,
            onInputsCleared = ::triggerEmergencyClear
        )
    }

    fun deleteSavedConsole(hostId: String) {
        connectionManager.deleteSavedConsole(hostId)
    }

    fun disconnectSession() {
        connectionManager.disconnect {
            triggerEmergencyClear()
        }
        videoRenderer.stopDecoder()
        audioRenderer.stop()
    }

    override fun onCleared() {
        telemetryMonitor.stop()
        disconnectSession()
        super.onCleared()
    }

    private data class NavBucket(
        val screen: AppScreen,
        val drawer: Boolean,
        val onboardingStep: OnboardingStep,
        val authSession: SonyAuthSession?,
        val clearGen: Long
    )

    private data class ProfileBucket(
        val profiles: List<ControlProfile>,
        val activeProfile: ControlProfile,
        val editor: LayoutEditorState?,
        val quality: StreamQualitySettings,
        val banner: String?
    )

    private data class RemoteBucket(
        val connectionState: ConnectionState,
        val discovered: List<DiscoveredConsole>,
        val saved: List<RegisteredConsole>,
        val scanMessage: String,
        val pairMessage: String?
    )

    private data class MediaBucket(
        val inputTelemetry: OverlayTelemetry,
        val videoTelemetry: VideoTelemetry,
        val audioTelemetry: AudioTelemetry,
        val deviceMetrics: DeviceRuntimeMetrics,
        val showLicenseDialog: Boolean
    )
}
