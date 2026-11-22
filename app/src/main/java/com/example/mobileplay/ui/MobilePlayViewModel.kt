package com.example.mobileplay.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mobileplay.audio.AudioRenderer
import com.example.mobileplay.audio.AudioTelemetry
import com.example.mobileplay.audio.SessionMicrophone
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.LayoutEditorState
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.input.ControllerInputMixer
import com.example.mobileplay.input.GamepadControls
import com.example.mobileplay.input.GamepadDevice
import com.example.mobileplay.input.GamepadRawSample
import com.example.mobileplay.input.GamepadTuning
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val streamQualityConsoleName: String? = null,
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
    private val sessionMicrophone = SessionMicrophone(application)
    val microphoneState = sessionMicrophone.state
    private var pendingConnection: Job? = null
    private var pendingWake: Job? = null
    private var pendingStandbyDisconnect: Job? = null
    private var profileWriteJob: Job? = null
    private var pendingProfileWrite: ControlProfile? = null
    private val _wakeProgress = MutableStateFlow<WakeProgress>(WakeProgress.Idle)
    internal val wakeProgress: StateFlow<WakeProgress> = _wakeProgress
    internal val consoleControls get() = connectionManager.consoleControls
    private val telemetryMonitor = DeviceTelemetryMonitor(application) {
        viewModelScope.launch {
            connectionManager.onWifiLost { triggerEmergencyClear() }
        }
    }

    private val _activeScreen = MutableStateFlow(AppScreen.HOME)
    private val _drawerOpen = MutableStateFlow(false)
    private val _onboardingStep = MutableStateFlow(OnboardingStep.WELCOME)
    private val _sonyAuthSession = MutableStateFlow<SonyAuthSession?>(null)
    private val _profiles = MutableStateFlow(profileManager.loadAllProfiles())
    private val _activeProfile = MutableStateFlow(profileManager.loadActiveProfile())
    private val _layoutEditor = MutableStateFlow<LayoutEditorState?>(null)
    private val _streamQuality = MutableStateFlow(profileManager.loadStreamQuality())
    private val streamQualityTarget = MutableStateFlow<QualityTarget?>(null)
    private val _telemetry = MutableStateFlow(OverlayTelemetry())
    private val _pairStatusMessage = MutableStateFlow<String?>(null)
    private val _profileBannerMessage = MutableStateFlow<String?>(null)
    private val consolePrefs = application.getSharedPreferences("mobileplay_console_ui", Context.MODE_PRIVATE)
    private val lastConsoleId = MutableStateFlow(consolePrefs.getString("last_console", null))
    private val _showLicenseDialog = MutableStateFlow(false)
    private val _clearInputGeneration = MutableStateFlow(0L)
    private val inputLock = Any()
    private val inputMixer = ControllerInputMixer()
    private val inputPrefs = application.getSharedPreferences("mobileplay_gamepad", Context.MODE_PRIVATE)
    private val legacyDeadzone = inputPrefs.getFloat("deadzone", GamepadControls.DEFAULT_DEADZONE)
        .takeIf { it.isFinite() }?.coerceIn(0f, GamepadControls.MAX_DEADZONE) ?: GamepadControls.DEFAULT_DEADZONE
    private val gamepadTuning = mutableMapOf<String, GamepadTuning>()
    private var primaryGamepad: String? = null
    private val _gamepadControls = MutableStateFlow(GamepadControls(
        hideTouchControls = inputPrefs.getBoolean("hide_touch", true)
    ))
    val gamepadControls: StateFlow<GamepadControls> = _gamepadControls
    private val _gamepadPreview = MutableStateFlow(PadState())
    val gamepadPreview: StateFlow<PadState> = _gamepadPreview
    private val _gamepadRaw = MutableStateFlow<GamepadRawSample?>(null)
    internal val gamepadRaw: StateFlow<GamepadRawSample?> = _gamepadRaw
    private val _physicalAim = MutableStateFlow(false)
    val physicalAim: StateFlow<Boolean> = _physicalAim
    val gamepadInputGeneration: Long get() = _clearInputGeneration.value

    init {
        telemetryMonitor.start()
        audioRenderer.setVolume(_streamQuality.value.audioVolume)
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                if (state !is ConnectionState.Connected) sessionMicrophone.stop()
            }
        }
        viewModelScope.launch {
            combine(_streamQuality, connectionManager.connectionState, telemetryMonitor.metrics) { quality, session, network ->
                quality.lowLatencyWifi && network.networkTransportLabel == "Local Wi-Fi" &&
                    (session is ConnectionState.Connected || session is ConnectionState.Connecting)
            }.distinctUntilChanged().collect(telemetryMonitor::setLowLatencyWifi)
        }
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
        combine(_streamQuality, streamQualityTarget) { quality, target -> quality to target?.name },
        _profileBannerMessage
    ) { profiles, active, editor, quality, banner ->
        ProfileBucket(profiles, active, editor, quality.first, banner, quality.second)
    }

    private val remoteSnapshot = combine(
        connectionManager.connectionState,
        connectionManager.discoveredConsoles,
        combine(connectionManager.savedConsoles, lastConsoleId) { consoles, lastId ->
            consoles.sortedBy { it.hostId != lastId }
        },
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
            streamQualityConsoleName = prof.qualityConsoleName,
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

    // Navigation and gameplay chrome only need changes in surface geometry and stream health.
    // The diagnostics page subscribes to the full-rate counters separately.
    val appUiState = uiState.map { state ->
        state.copy(
            telemetry = OverlayTelemetry(),
            audioTelemetry = AudioTelemetry(),
            deviceMetrics = DeviceRuntimeMetrics(networkTransportLabel = state.deviceMetrics.networkTransportLabel),
            videoTelemetry = VideoTelemetry(
                surfaceWidth = state.videoTelemetry.surfaceWidth,
                surfaceHeight = state.videoTelemetry.surfaceHeight,
                healthNote = state.videoTelemetry.healthNote
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), uiState.value)

    fun navigateTo(screen: AppScreen) {
        if (screen != AppScreen.GAMEPLAY) {
            pendingConnection?.cancel()
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
        if (open != _drawerOpen.value) triggerEmergencyClear()
        _drawerOpen.value = open
    }

    fun setShowLicenseDialog(show: Boolean) {
        _showLicenseDialog.value = show
    }

    // ---- Profiles & Controls ----

    fun selectProfile(profileId: String) {
        val target = _profiles.value.firstOrNull { it.id == profileId } ?: return
        flushProfileEdit()
        _activeProfile.value = target
        profileManager.saveProfile(target)
        triggerEmergencyClear()
    }

    fun switchMode(mode: ControlMode) {
        flushProfileEdit()
        val loaded = profileManager.loadProfile(mode)
        _activeProfile.value = loaded
        profileManager.saveProfile(loaded)
        _profiles.value = profileManager.loadAllProfiles()
        triggerEmergencyClear()
    }

    private var previousOnFootProfileId: String? = null

    fun toggleDrivingMode() {
        val active = _activeProfile.value
        if (active.mode == ControlMode.ON_FOOT) {
            previousOnFootProfileId = active.id
            switchMode(ControlMode.DRIVING)
        } else {
            val previous = _profiles.value.firstOrNull { it.id == previousOnFootProfileId }
            if (previous != null) selectProfile(previous.id) else switchMode(ControlMode.ON_FOOT)
        }
    }

    fun updateProfile(transform: (ControlProfile) -> ControlProfile) {
        val updated = runCatching { transform(_activeProfile.value) }.getOrNull() ?: return
        if (updated == _activeProfile.value) return
        _activeProfile.value = updated
        _profiles.value = _profiles.value.map { if (it.id == updated.id) updated else it }
        pendingProfileWrite = updated
        profileWriteJob?.cancel()
        profileWriteJob = viewModelScope.launch {
            delay(220L)
            pendingProfileWrite?.let(profileManager::saveProfile)
            pendingProfileWrite = null
        }
    }

    internal fun flushProfileEdit() {
        profileWriteJob?.cancel()
        profileWriteJob = null
        pendingProfileWrite?.let(profileManager::saveProfile)
        pendingProfileWrite = null
    }

    fun updateTuning(transform: (AimTuning) -> AimTuning) {
        updateProfile { profile -> profile.copy(tuning = transform(profile.tuning)) }
    }

    fun duplicateActiveProfile(newName: String) {
        flushProfileEdit()
        val created = profileManager.duplicateProfile(_activeProfile.value, newName)
        _activeProfile.value = created
        _profiles.value = profileManager.loadAllProfiles()
        _profileBannerMessage.value = "Created profile \"${created.name}\"."
    }

    fun renameActiveProfile(newName: String) {
        val name = newName.trim().take(60)
        if (name.isNotEmpty() && !_activeProfile.value.isBuiltIn) updateProfile { it.copy(name = name) }
    }

    fun deleteCustomProfile(id: String) {
        flushProfileEdit()
        val updatedList = profileManager.deleteCustomProfile(id)
        _profiles.value = updatedList
        if (_activeProfile.value.id == id) {
            _activeProfile.value = updatedList.first()
        }
    }

    fun resetCurrentProfile() {
        flushProfileEdit()
        val reset = profileManager.resetProfile(_activeProfile.value.id)
        _activeProfile.value = reset
        _profiles.value = profileManager.loadAllProfiles()
        _profileBannerMessage.value = "Reset \"${reset.name}\" to recommended defaults."
        triggerEmergencyClear()
    }

    fun exportCurrentProfileJson(): String = profileManager.exportPrettyJson(_activeProfile.value)

    fun importProfileJson(rawJson: String): Result<ControlProfile> {
        flushProfileEdit()
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
        flushProfileEdit()
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
        profileManager.saveStreamQuality(updated, streamQualityTarget.value?.id)
        audioRenderer.setVolume(updated.audioVolume)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) sessionMicrophone.stop()
        else if (connectionManager.connectionState.value is ConnectionState.Connected) sessionMicrophone.start()
    }

    // ---- Gameplay Input & Console Connection ----

    fun onOverlayPadState(state: PadState) {
        synchronized(inputLock) {
            if (!acceptsGamepadEvents() || _drawerOpen.value) return
            inputMixer.touch = state
            connectionManager.forwardControllerState(inputMixer.current())
        }
    }

    fun acceptsGamepadEvents(): Boolean = _activeScreen.value == AppScreen.GAMEPLAY &&
        !_showLicenseDialog.value && consoleControls.prompt.value == null && !consoleControls.standbyPending.value

    fun onGamepadState(deviceId: Int, state: PadState) {
        synchronized(inputLock) {
            if (!acceptsGamepadEvents()) return
            if (_drawerOpen.value) {
                _gamepadPreview.value = state
                return
            }
            inputMixer.updateGamepad(deviceId, state)
            _physicalAim.value = inputMixer.physicalAim
            connectionManager.forwardControllerState(inputMixer.current())
        }
    }

    fun onGamepadRemoved(deviceId: Int) {
        synchronized(inputLock) {
            inputMixer.removeGamepad(deviceId)
            _physicalAim.value = inputMixer.physicalAim
            _gamepadPreview.value = PadState()
            connectionManager.forwardControllerState(inputMixer.current())
        }
    }

    internal fun onGamepadsChanged(devices: List<GamepadDevice>) {
        val descriptor = devices.firstOrNull()?.descriptor
        if (descriptor != primaryGamepad) _gamepadRaw.value = null
        primaryGamepad = descriptor
        val connected = devices.mapTo(mutableSetOf()) { it.descriptor }
        gamepadTuning.keys.retainAll(connected)
        _gamepadControls.value = _gamepadControls.value.copy(
            deviceNames = devices.map { it.name },
            tuning = descriptor?.let(::gamepadTuningFor) ?: GamepadTuning()
        )
    }

    internal fun gamepadTuningFor(descriptor: String): GamepadTuning = gamepadTuning.getOrPut(descriptor) {
        GamepadTuning(
            leftDeadzone = inputPrefs.getFloat("${descriptor}_left", legacyDeadzone)
                .takeIf { it.isFinite() }?.coerceIn(0f, GamepadControls.MAX_DEADZONE) ?: legacyDeadzone,
            rightDeadzone = inputPrefs.getFloat("${descriptor}_right", legacyDeadzone)
                .takeIf { it.isFinite() }?.coerceIn(0f, GamepadControls.MAX_DEADZONE) ?: legacyDeadzone,
            rightCurve = inputPrefs.getFloat("${descriptor}_curve", 1f)
                .takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        )
    }

    internal fun onGamepadRawSample(descriptor: String, sample: GamepadRawSample) {
        if (_drawerOpen.value && descriptor == primaryGamepad) _gamepadRaw.value = sample
    }

    internal fun capturesGamepadCalibration() = _drawerOpen.value

    fun setHideTouchWithGamepad(value: Boolean) {
        inputPrefs.edit().putBoolean("hide_touch", value).apply()
        _gamepadControls.value = _gamepadControls.value.copy(hideTouchControls = value)
    }

    fun setGamepadTuning(tuning: GamepadTuning) {
        val descriptor = primaryGamepad ?: return
        if (!tuning.leftDeadzone.isFinite() || !tuning.rightDeadzone.isFinite() || !tuning.rightCurve.isFinite()) return
        val value = tuning.copy(
            leftDeadzone = tuning.leftDeadzone.coerceIn(0f, GamepadControls.MAX_DEADZONE),
            rightDeadzone = tuning.rightDeadzone.coerceIn(0f, GamepadControls.MAX_DEADZONE),
            rightCurve = tuning.rightCurve.coerceIn(0.5f, 2f)
        )
        gamepadTuning[descriptor] = value
        inputPrefs.edit()
            .putFloat("${descriptor}_left", value.leftDeadzone)
            .putFloat("${descriptor}_right", value.rightDeadzone)
            .putFloat("${descriptor}_curve", value.rightCurve)
            .apply()
        _gamepadControls.value = _gamepadControls.value.copy(tuning = value)
    }

    fun onOverlayTelemetry(telemetry: OverlayTelemetry) {
        synchronized(inputLock) {
            _telemetry.value = telemetry.copy(padState = inputMixer.current())
        }
    }

    fun triggerEmergencyClear() {
        synchronized(inputLock) {
            inputMixer.clear()
            _physicalAim.value = false
            _gamepadPreview.value = PadState()
            _gamepadRaw.value = null
            _clearInputGeneration.value = _clearInputGeneration.value + 1L
            _telemetry.value = _telemetry.value.copy(
                padState = PadState(),
                pointers = emptyList()
            )
            connectionManager.forwardControllerState(PadState())
        }
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

    internal fun wakeAndConnect(console: RegisteredConsole) {
        if (pendingWake?.isActive == true || pendingConnection?.isActive == true) return
        _wakeProgress.value = WakeProgress.Waiting(console.hostName)
        pendingWake = viewModelScope.launch {
            connectionManager.wakeConsole(console).fold(
                onSuccess = {
                    pendingWake = null
                    _wakeProgress.value = WakeProgress.Idle
                    connectToConsole(it)
                },
                onFailure = {
                    _wakeProgress.value = WakeProgress.Failed(it.message ?: "Could not wake the PS5.")
                }
            )
        }
    }

    internal fun cancelWake() {
        pendingWake?.cancel()
        pendingWake = null
        _wakeProgress.value = WakeProgress.Idle
    }

    internal fun standbyAndDisconnect(): Boolean {
        if (connectionManager.connectionState.value !is ConnectionState.Connected) return false
        triggerEmergencyClear()
        if (!consoleControls.requestStandby()) return false
        pendingStandbyDisconnect = viewModelScope.launch {
            // Chiaki sends the queued standby command on its control thread.
            delay(2_000L)
            pendingStandbyDisconnect = null
            if (consoleControls.standbyPending.value) disconnectSession()
        }
        return true
    }

    fun connectToConsole(console: RegisteredConsole) {
        if (pendingConnection?.isActive == true) return
        cancelWake()
        navigateTo(AppScreen.GAMEPLAY)
        val activeSession = connectionManager.connectionState.value
        if (activeSession is ConnectionState.Connected || activeSession is ConnectionState.Connecting) return
        lastConsoleId.value = console.hostId
        consolePrefs.edit().putString("last_console", console.hostId).apply()
        streamQualityTarget.value = QualityTarget(console.hostId, console.hostName)
        _streamQuality.value = profileManager.loadStreamQuality(console.hostId)
        audioRenderer.setVolume(_streamQuality.value.audioVolume)
        pendingConnection = viewModelScope.launch {
            // The gameplay screen owns the SurfaceView. Connect after Android creates its surface.
            withTimeoutOrNull(5_000L) { videoRenderer.telemetry.first { it.surfaceReady } }
            connectionManager.connectToConsole(
                console = console,
                quality = _streamQuality.value,
                videoRenderer = videoRenderer,
                audioRenderer = audioRenderer,
                onInputsCleared = ::triggerEmergencyClear
            )
        }
    }

    fun deleteSavedConsole(hostId: String) {
        connectionManager.deleteSavedConsole(hostId)
    }

    fun disconnectSession() {
        sessionMicrophone.stop()
        pendingConnection?.cancel()
        pendingStandbyDisconnect?.cancel()
        cancelWake()
        connectionManager.disconnect {
            triggerEmergencyClear()
        }
        consoleControls.clearPrompt()
        consoleControls.clearStandby()
        videoRenderer.stopDecoder()
        audioRenderer.stop()
        streamQualityTarget.value = null
        _streamQuality.value = profileManager.loadStreamQuality()
        audioRenderer.setVolume(_streamQuality.value.audioVolume)
    }

    override fun onCleared() {
        flushProfileEdit()
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
        val banner: String?,
        val qualityConsoleName: String?
    )

    private data class QualityTarget(val id: String, val name: String)

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
