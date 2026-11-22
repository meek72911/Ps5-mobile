package com.example.mobileplay.remoteplay

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.example.mobileplay.audio.AudioRenderer
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.security.KeystoreCredentialVault
import com.example.mobileplay.security.RegisteredConsole
import com.example.mobileplay.video.StreamCodec
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredConsole(
    val hostAddress: String,
    val hostName: String,
    val hostId: String,
    val hostType: String,
    val systemVersion: String,
    val status: String
)

sealed interface ConnectionState {
    data object StandaloneSimulator : ConnectionState
    data object Discovering : ConnectionState
    data class NativeEngineRequired(val detail: String) : ConnectionState
    data class Pairing(val hostAddress: String) : ConnectionState
    data class Connecting(val hostAddress: String, val detail: String? = null) : ConnectionState
    data class Connected(val hostName: String, val hostAddress: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Abstraction boundary between MobilePlay's touch engine and the native libchiaki streaming stack.
 */
interface RemotePlayAdapter {
    val engineLabel: String
    val isNativeLibchiakiLoaded: Boolean

    fun registerConsole(
        hostAddress: String,
        psnAccountIdBase64: String,
        pinCode: String
    ): Result<ChiakiRegistrationResult>

    fun startSession(
        console: RegisteredConsole,
        quality: StreamQualitySettings,
        videoRenderer: VideoRenderer,
        audioRenderer: AudioRenderer,
        onConnected: () -> Unit = {},
        onDisconnected: (String) -> Unit
    ): Result<Unit>

    fun sendControllerState(state: PadState)
    fun stopSession()
    fun canRecoverSession(reason: String): Boolean = false
}

/**
 * Real local Wi-Fi UDP discovery client using PlayStation's LAN discovery probe (UDP 9302).
 * Uses a single short probe and bounded socket timeout — never runs automated stress loops.
 */
class LocalPs5Discovery {
    suspend fun scanOnce(timeoutMs: Int = 1800): Result<List<DiscoveredConsole>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = LinkedHashMap<String, DiscoveredConsole>()
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 450

                    val probeBytes = PS5_DISCOVERY_PROBE.toByteArray(StandardCharsets.UTF_8)
                    val broadcastAddress = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(probeBytes, probeBytes.size, broadcastAddress, PS5_DISCOVERY_PORT)
                    socket.send(packet)

                    val deadlineMs = System.currentTimeMillis() + timeoutMs
                    val buffer = ByteArray(2048)
                    while (System.currentTimeMillis() < deadlineMs) {
                        val response = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(response)
                            val text = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                            val parsed = parseDiscoveryResponse(
                                address = response.address?.hostAddress.orEmpty(),
                                payload = text
                            )
                            if (parsed != null) {
                                results[parsed.hostAddress] = parsed
                            }
                        } catch (_: SocketTimeoutException) {
                            // Continue until overall scan deadline expires.
                        }
                    }
                }
                results.values.toList()
            }
        }

    companion object {
        const val PS5_DISCOVERY_PORT = 9302
        private const val PS5_DISCOVERY_PROBE =
            "SRCH * HTTP/1.1\r\ndevice-discovery-protocol-version:00030010\r\n\r\n"

        fun parseDiscoveryResponse(address: String, payload: String): DiscoveredConsole? {
            if (address.isBlank() || !payload.startsWith("HTTP/1.1")) return null
            val headers = HashMap<String, String>()
            val lines = payload.split("\r\n", "\n")
            val statusLine = lines.firstOrNull().orEmpty()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    val key = lines[i].substring(0, idx).trim().lowercase()
                    val value = lines[i].substring(idx + 1).trim()
                    headers[key] = value
                }
            }
            val hostId = headers["host-id"] ?: return null
            return DiscoveredConsole(
                hostAddress = address,
                hostName = headers["host-name"] ?: "PlayStation Console",
                hostId = hostId,
                hostType = headers["host-type"] ?: "PS5",
                systemVersion = headers["system-version"] ?: "Unknown",
                status = statusLine.removePrefix("HTTP/1.1 ").ifBlank { "Responding" }
            )
        }
    }
}

/**
 * Production JNI adapter linking MobilePlay to ChiakiNativeBridge (pinned chiaki-ng v1.10.1),
 * VideoRenderer (hardware MediaCodec), and AudioRenderer (AudioTrack PCM16 from ChiakiOpusDecoder).
 *
 * Guarantees:
 * - Performs genuine ChiakiRegist PS5 PIN registration when libchiaki_jni.so is loaded.
 * - Never passes zero-filled placeholder registration keys to nativeStartSession().
 * - Never fakes a connected status when libchiaki_jni.so is absent.
 * - Guards callback delivery after stopSession() teardown.
 */
class MomijiLibchiakiAdapter : RemotePlayAdapter {
    internal val consoleControls = SessionConsoleControls()
    private val sessionActive = AtomicBoolean(false)
    private val controllerBlocked = AtomicBoolean(true)
    private val callbackHandler by lazy { Handler(Looper.getMainLooper()) }
    private var watchdog: Runnable? = null

    override val isNativeLibchiakiLoaded: Boolean
        get() = ChiakiNativeBridge.isLibraryLoaded

    override val engineLabel: String
        get() = ChiakiNativeBridge.buildInfo

    override fun registerConsole(
        hostAddress: String,
        psnAccountIdBase64: String,
        pinCode: String
    ): Result<ChiakiRegistrationResult> {
        val cleanHost = hostAddress.trim()
        val cleanPin = pinCode.trim()
        if (cleanHost.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid local PS5 IP address."))
        }
        if (!cleanPin.matches(Regex("^\\d{8}$"))) {
            return Result.failure(IllegalArgumentException("PS5 Remote Play PIN must be exactly 8 digits."))
        }
        val accountIdBytes = SonyAuthManager.decodeAccountIdBytes(psnAccountIdBase64)
            .getOrElse { return Result.failure(it) }

        if (!isNativeLibchiakiLoaded) {
            accountIdBytes.fill(0)
            return Result.failure(
                IllegalStateException(
                    "Could not load libchiaki_jni.so. PS5 pairing requires the ARM64 MobilePlay APK."
                )
            )
        }

        val pinInt = cleanPin.toIntOrNull()
            ?: run {
                accountIdBytes.fill(0)
                return Result.failure(IllegalArgumentException("PIN must be an 8-digit integer."))
            }

        var capturedResult: ChiakiRegistrationResult? = null
        var capturedError: String? = null

        val callback = object : ChiakiRegistCallback {
            override fun onRegistSuccess(
                serverNickname: String,
                serverMac: String,
                rpRegistKey: ByteArray,
                rpKey: ByteArray,
                rpKeyType: Int
            ) {
                capturedResult = ChiakiRegistrationResult(
                    serverNickname = serverNickname,
                    serverMac = serverMac,
                    rpRegistKey = rpRegistKey.copyOf(),
                    rpKey = rpKey.copyOf(),
                    rpKeyType = rpKeyType
                )
            }

            override fun onRegistError(errorCode: Int, message: String) {
                capturedError = message.ifBlank { ChiakiNativeBridge.nativeErrorToMessage(errorCode) }
            }
        }

        return try {
            val errCode = ChiakiNativeBridge.nativeRegisterConsole(
                host = cleanHost,
                psnAccountIdBytes = accountIdBytes,
                pin = pinInt,
                timeoutMs = 15_000,
                callback = callback
            )
            if (errCode != 0) {
                capturedResult?.clearSensitiveBytes()
                Result.failure(
                    IllegalStateException(
                        capturedError ?: ChiakiNativeBridge.nativeErrorToMessage(errCode)
                    )
                )
            } else {
                val res = capturedResult
                    ?: return Result.failure(IllegalStateException("PS5 registration completed without returning credentials."))
                val validation = ChiakiNativeBridge.validateSessionCredentials(res.rpRegistKey, res.rpKey)
                if (validation.isFailure) {
                    res.clearSensitiveBytes()
                    Result.failure(validation.exceptionOrNull()!!)
                } else {
                    Result.success(res)
                }
            }
        } finally {
            accountIdBytes.fill(0)
        }
    }

    override fun startSession(
        console: RegisteredConsole,
        quality: StreamQualitySettings,
        videoRenderer: VideoRenderer,
        audioRenderer: AudioRenderer,
        onConnected: () -> Unit,
        onDisconnected: (String) -> Unit
    ): Result<Unit> {
        val registKey = console.decodeRegistKeyBytes()
        val morningKey = console.decodeRpKeyBytes()

        // Step 1: Strictly verify authentic non-zero credentials BEFORE touching native layer or codecs.
        val credCheck = ChiakiNativeBridge.validateSessionCredentials(registKey, morningKey)
        if (credCheck.isFailure) {
            registKey?.fill(0)
            morningKey?.fill(0)
            return Result.failure(credCheck.exceptionOrNull()!!)
        }

        // Step 2: Refuse to fake a session when native libchiaki_jni.so is absent.
        if (!isNativeLibchiakiLoaded) {
            registKey?.fill(0)
            morningKey?.fill(0)
            return Result.failure(
                IllegalStateException(
                    "Could not load libchiaki_jni.so. PS5 streaming requires the ARM64 MobilePlay APK."
                )
            )
        }

        // Step 3: Initialize hardware MediaCodec decoder and fail early on decoder errors.
        val decoderResult = videoRenderer.configureHardwareDecoder(quality)
        if (decoderResult.isFailure) {
            registKey?.fill(0)
            morningKey?.fill(0)
            return Result.failure(
                decoderResult.exceptionOrNull()
                    ?: IllegalStateException("Hardware MediaCodec decoder initialization failed.")
            )
        }

        sessionActive.set(true)
        controllerBlocked.set(true)
        val protocolConnected = AtomicBoolean(false)
        val callback = object : ChiakiStreamCallback {
            override fun onVideoPacket(data: ByteArray, ptsUs: Long): Boolean =
                sessionActive.get() && videoRenderer.submitAccessUnit(data, ptsUs)

            override fun onAudioHeader(channels: Int, sampleRateHz: Int) {
                if (!sessionActive.get()) return
                audioRenderer.configure(channels, sampleRateHz)
            }

            override fun onAudioPcm(samples: ShortArray) {
                if (!sessionActive.get()) return
                audioRenderer.writePcm(samples)
            }

            override fun onSessionEvent(eventType: Int, reason: String) {
                if (!sessionActive.get()) return
                consoleControls.onNativeEvent(eventType, reason)
                when (eventType) {
                    ChiakiNativeBridge.EVENT_SESSION_CONNECTED -> protocolConnected.set(true)
                    ChiakiNativeBridge.EVENT_SESSION_QUIT -> if (sessionActive.compareAndSet(true, false)) {
                        // The disconnect handler joins the Chiaki thread that delivered this callback.
                        callbackHandler.post {
                            onDisconnected(reason.ifBlank { "PS5 Remote Play session ended." })
                        }
                    }
                }
            }
        }

        return try {
            val errCode = ChiakiNativeBridge.nativeStartSession(
                host = console.hostAddress,
                registKey = registKey!!,
                morning = morningKey!!,
                width = quality.resolution.width,
                height = quality.resolution.height,
                fps = quality.frameRate.fps,
                bitrateKbps = quality.effectiveBitrateKbps,
                useHevc = quality.codec == StreamCodec.HEVC,
                callback = callback
            )
            if (errCode == 0) {
                monitorPlayback(videoRenderer, protocolConnected, onConnected, onDisconnected)
                Result.success(Unit)
            } else {
                sessionActive.set(false)
                videoRenderer.stopDecoder()
                audioRenderer.stop()
                Result.failure(
                    IllegalStateException(ChiakiNativeBridge.nativeErrorToMessage(errCode))
                )
            }
        } finally {
            registKey?.fill(0)
            morningKey?.fill(0)
        }
    }

    override fun sendControllerState(state: PadState) {
        if (!sessionActive.get() || controllerBlocked.get()) return
        ChiakiNativeBridge.sendPadStateIfLoaded(state)
    }

    override fun canRecoverSession(reason: String): Boolean = reason in setOf(
        NO_STREAM_PACKETS,
        DECODER_DID_NOT_RECOVER,
        STREAM_DID_NOT_CONNECT,
        "Unknown Error in Stream Connection",
        "Remote has disconnected from Stream Connection",
        "Connection Refused in Session Request",
        "Ctrl failed to connect"
    )

    private fun monitorPlayback(
        video: VideoRenderer,
        protocolConnected: AtomicBoolean,
        onConnected: () -> Unit,
        onDisconnected: (String) -> Unit
    ) {
        watchdog?.let(callbackHandler::removeCallbacks)
        val probe = object : Runnable {
            private var handshakeStartedNs = System.nanoTime()
            private var trafficExpectedNs = 0L
            private var observedDecoderNs = 0L
            private var healthCheckStartedNs = 0L
            private var lastRequestNs = 0L
            private var lastStatsNs = 0L
            private var recoveryAttempts = 0
            private var firstFrameReported = false
            private var keyframeWaitObserved = false

            override fun run() {
                if (watchdog !== this || !sessionActive.get()) return
                val now = System.nanoTime()
                val terminal = inspect(now)
                if (terminal != null && sessionActive.compareAndSet(true, false)) {
                    inhibitController()
                    onDisconnected(terminal)
                } else if (sessionActive.get()) {
                    callbackHandler.postDelayed(this, 250L)
                }
            }

            private fun inspect(now: Long): String? {
                if (consoleControls.prompt.value is ConsolePrompt.LoginPin) {
                    handshakeStartedNs = now
                    return null
                }
                if (consoleControls.standbyPending.value) return null
                if (!protocolConnected.get()) {
                    return STREAM_DID_NOT_CONNECT.takeIf { now - handshakeStartedNs > 15_000_000_000L }
                }
                if (trafficExpectedNs == 0L) trafficExpectedNs = now
                val progress = video.playbackProgress()
                if (now - lastStatsNs >= 1_000_000_000L) {
                    video.reportNetworkStats(ChiakiNativeBridge.nativeGetStreamStats())
                    lastStatsNs = now
                }
                if (!progress.surfaceReady) {
                    inhibitController()
                    trafficExpectedNs = now
                    video.reportStreamHealth("Waiting for the video surface")
                    return null
                }
                if (progress.awaitingKeyframe && !keyframeWaitObserved) {
                    inhibitController()
                    video.reportStreamHealth("Waiting for a PS5 keyframe")
                    requestIdr(now)
                }
                keyframeWaitObserved = progress.awaitingKeyframe
                val lastTraffic = maxOf(trafficExpectedNs, progress.lastReceivedNs)
                if (now - lastTraffic > 4_000_000_000L) {
                    inhibitController()
                    video.reportStreamHealth("Waiting for video from the PS5")
                    if (lastRequestNs <= lastTraffic) requestIdr(now)
                    else if (now - lastRequestNs > 1_000_000_000L) return NO_STREAM_PACKETS
                    return null
                }
                if (progress.decoderReadyNs != observedDecoderNs) {
                    if (observedDecoderNs != 0L && progress.decoderReady && !progress.awaitingKeyframe) requestIdr(now)
                    observedDecoderNs = progress.decoderReadyNs
                    healthCheckStartedNs = now
                } else if (healthCheckStartedNs == 0L) {
                    healthCheckStartedNs = now
                }
                // Older MediaCodec implementations can omit frame-rendered callbacks.
                val lastDisplayProgress = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    maxOf(progress.lastRenderedNs, progress.lastSurfaceSubmitNs)
                } else {
                    progress.lastRenderedNs
                }
                val lastProgress = maxOf(healthCheckStartedNs, lastDisplayProgress)
                if (progress.decoderReady && !progress.awaitingKeyframe &&
                    lastDisplayProgress > 0L && now - lastDisplayProgress <= 1_500_000_000L
                ) {
                    controllerBlocked.set(false)
                    video.reportStreamHealth(null)
                    recoveryAttempts = 0
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        onConnected()
                    }
                    return null
                }
                if ((!progress.decoderReady || now - lastProgress > 1_500_000_000L) &&
                    progress.lastReceivedNs > 0L && now - progress.lastReceivedNs < 1_000_000_000L &&
                    now - lastRequestNs >= 2_000_000_000L
                ) {
                    inhibitController()
                    val decoding = progress.lastOutputNs > 0L && now - progress.lastOutputNs < 750_000_000L
                    video.reportStreamHealth(if (decoding) "Restoring video presentation" else "Restoring video decoder")
                    if (recoveryAttempts++ >= 2) return DECODER_DID_NOT_RECOVER
                    if (!progress.decoderReady || recoveryAttempts > 1 || decoding) video.rebuildDecoder()
                    requestIdr(now)
                }
                return null
            }

            private fun requestIdr(now: Long) {
                ChiakiNativeBridge.nativeRequestIdr()
                lastRequestNs = now
            }

            private fun inhibitController() {
                if (controllerBlocked.compareAndSet(false, true)) {
                    ChiakiNativeBridge.sendPadStateIfLoaded(PadState())
                }
            }
        }
        watchdog = probe
        callbackHandler.postDelayed(probe, 250L)
    }

    override fun stopSession() {
        sessionActive.set(false)
        controllerBlocked.set(true)
        watchdog?.let(callbackHandler::removeCallbacks)
        watchdog = null
        if (isNativeLibchiakiLoaded) {
            ChiakiNativeBridge.nativeStopSession()
        }
    }

    private companion object {
        const val NO_STREAM_PACKETS = "Video stopped arriving from the PS5."
        const val DECODER_DID_NOT_RECOVER = "Video decoder could not recover."
        const val STREAM_DID_NOT_CONNECT = "PS5 stream did not start in time."
    }
}

/**
 * Coordinates local PS5 discovery, Keystore-backed console profiles, genuine PIN registration,
 * duplicate-request guards, and RemotePlayAdapter lifecycle with guaranteed disconnect cleanup.
 */
class ConnectionManager(
    private val vault: KeystoreCredentialVault,
    private val discovery: LocalPs5Discovery = LocalPs5Discovery(),
    private val adapter: RemotePlayAdapter = MomijiLibchiakiAdapter()
) {
    internal val consoleControls =
        (adapter as? MomijiLibchiakiAdapter)?.consoleControls ?: SessionConsoleControls()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.StandaloneSimulator)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredConsoles = MutableStateFlow<List<DiscoveredConsole>>(emptyList())
    val discoveredConsoles: StateFlow<List<DiscoveredConsole>> = _discoveredConsoles.asStateFlow()

    private val _savedConsoles = MutableStateFlow<List<RegisteredConsole>>(emptyList())
    val savedConsoles: StateFlow<List<RegisteredConsole>> = _savedConsoles.asStateFlow()

    private val _lastScanMessage = MutableStateFlow("Not scanned yet. Tap Scan Local Wi-Fi to probe UDP 9302.")
    val lastScanMessage: StateFlow<String> = _lastScanMessage.asStateFlow()

    private val operationLock = Any()
    private var sessionGeneration = 0L
    private var activeStream: StreamRequest? = null
    private var reconnectJob: Job? = null
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class StreamRequest(
        val console: RegisteredConsole,
        val quality: StreamQualitySettings,
        val video: VideoRenderer,
        val audio: AudioRenderer,
        val onInputsCleared: () -> Unit,
        val retries: Int = 0
    )

    val isNativeEngineAvailable: Boolean get() = adapter.isNativeLibchiakiLoaded
    val engineStatusLabel: String get() = adapter.engineLabel

    init {
        refreshSavedConsoles()
    }

    fun refreshSavedConsoles() {
        vault.loadConsoles().onSuccess { _savedConsoles.value = it }
    }

    internal suspend fun wakeConsole(console: RegisteredConsole): Result<RegisteredConsole> {
        if (!isNativeEngineAvailable || !console.hasValidSessionCredentials()) {
            return Result.failure(IllegalStateException("Pair this PS5 before using Wake & Play."))
        }
        if (connectionState.value is ConnectionState.Connected ||
            connectionState.value is ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Pairing
        ) {
            return Result.failure(IllegalStateException("Finish the current PS5 session before waking a console."))
        }

        val identity = consoleIdentity(console.serverMac.ifBlank { console.hostId })
        fun matches(host: DiscoveredConsole): Boolean = host.hostType.equals("PS5", ignoreCase = true) &&
            if (identity != null) identity == consoleIdentity(host.hostId)
            else host.hostAddress == console.hostAddress

        val discovered = discovery.scanOnce(timeoutMs = 650).getOrNull()?.firstOrNull(::matches)
        val hostAddress = discovered?.hostAddress ?: console.hostAddress
        if (discovered?.status?.substringBefore(' ') == "200") {
            return Result.success(console.copy(hostAddress = hostAddress))
        }
        val wakeCode = withContext(Dispatchers.IO) {
            val key = console.decodeRegistKeyBytes()
                ?: return@withContext -1
            try {
                ChiakiNativeBridge.nativeWakeConsole(hostAddress, key)
            } finally {
                key.fill(0)
            }
        }
        if (wakeCode != 0) {
            return Result.failure(IllegalStateException("Could not send the PS5 wake request (code $wakeCode)."))
        }

        return kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            var ready: DiscoveredConsole? = null
            while (ready == null) {
                ready = discovery.scanOnce(timeoutMs = 1200).getOrNull()?.firstOrNull {
                    matches(it) && it.status.substringBefore(' ') == "200"
                }
            }
            Result.success(console.copy(hostAddress = ready.hostAddress))
        } ?: Result.failure(
            IllegalStateException("PS5 did not announce that it woke up. Check same Wi-Fi and Enable Turning On PS5 from Network.")
        )
    }

    suspend fun discoverLocalConsoles() {
        synchronized(operationLock) {
            if (_connectionState.value is ConnectionState.Connected ||
                _connectionState.value is ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Pairing
            ) {
                return
            }
            _connectionState.value = ConnectionState.Discovering
        }
        _lastScanMessage.value = "Probing local Wi-Fi broadcast (UDP port 9302)..."
        val result = discovery.scanOnce()
        result.fold(
            onSuccess = { list ->
                _discoveredConsoles.value = list
                _lastScanMessage.value = if (list.isEmpty()) {
                    "No PS5 responded on local Wi-Fi (UDP 9302). Verify PS5 is awake/rest mode with Remote Play enabled on the same subnet."
                } else {
                    "Discovered ${list.size} PlayStation host(s) on local Wi-Fi."
                }
                _connectionState.value = ConnectionState.StandaloneSimulator
            },
            onFailure = { err ->
                _discoveredConsoles.value = emptyList()
                val msg = err.message ?: "UDP discovery socket failed"
                _lastScanMessage.value = "Discovery error: $msg"
                _connectionState.value = ConnectionState.Error(msg)
            }
        )
    }

    /**
     * Performs genuine PS5 PIN registration via libchiaki when available and encrypts the
     * returned cryptographic keys into Android Keystore.
     *
     * Never considers PIN format validation alone to be successful pairing.
     */
    fun pairAndSaveConsole(
        hostName: String,
        hostAddress: String,
        psnAccountId: String,
        pinCode: String
    ): Result<String> {
        val cleanHost = hostAddress.trim()
        val cleanPin = pinCode.trim()
        if (cleanHost.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid local PS5 IP address."))
        }
        val normalizedAccount = SonyAuthManager.normalizeAccountIdToBase64(psnAccountId)
            .getOrElse { return Result.failure(it) }
        if (!cleanPin.matches(Regex("^\\d{8}$"))) {
            return Result.failure(IllegalArgumentException("PS5 Remote Play PIN must be exactly 8 digits."))
        }

        synchronized(operationLock) {
            if (_connectionState.value is ConnectionState.Pairing ||
                _connectionState.value is ConnectionState.Connecting
            ) {
                return Result.failure(IllegalStateException("Another pairing or connection request is already in progress."))
            }
            _connectionState.value = ConnectionState.Pairing(cleanHost)
        }

        val regResult = adapter.registerConsole(cleanHost, normalizedAccount, cleanPin)
        return regResult.fold(
            onSuccess = { reg ->
                val registKeyB64 = Base64.encodeToString(reg.rpRegistKey, Base64.NO_WRAP)
                val rpKeyB64 = Base64.encodeToString(reg.rpKey, Base64.NO_WRAP)
                reg.clearSensitiveBytes()

                val resolvedName = hostName.trim().ifBlank {
                    reg.serverNickname.ifBlank { "PS5 ($cleanHost)" }
                }
                val console = RegisteredConsole(
                    hostId = reg.serverMac.ifBlank { "ps5_${cleanHost.replace('.', '_')}" },
                    hostName = resolvedName,
                    hostAddress = cleanHost,
                    psnAccountId = normalizedAccount,
                    registrationNote = "Cryptographically paired via libchiaki",
                    savedAtMs = System.currentTimeMillis(),
                    rpRegistKeyBase64 = registKeyB64,
                    rpKeyBase64 = rpKeyB64,
                    rpKeyType = reg.rpKeyType,
                    serverMac = reg.serverMac
                )
                val saveResult = vault.saveConsole(console)
                if (saveResult.isFailure) {
                    val err = saveResult.exceptionOrNull()?.message ?: "Android Keystore encryption failed"
                    _connectionState.value = ConnectionState.Error(err)
                    return Result.failure(saveResult.exceptionOrNull() ?: IllegalStateException(err))
                }
                refreshSavedConsoles()
                _connectionState.value = ConnectionState.StandaloneSimulator
                Result.success("Paired $resolvedName and stored cryptographic session keys in Android Keystore.")
            },
            onFailure = { err ->
                // Save unpaired host metadata ONLY (never fabricating registration keys) when native engine is missing.
                if (!adapter.isNativeLibchiakiLoaded) {
                    val unpairedRecord = RegisteredConsole(
                        hostId = "ps5_${cleanHost.replace('.', '_')}",
                        hostName = hostName.trim().ifBlank { "PS5 ($cleanHost)" },
                        hostAddress = cleanHost,
                        psnAccountId = normalizedAccount,
                        registrationNote = "Unpaired — awaiting native arm64-v8a libchiaki build",
                        savedAtMs = System.currentTimeMillis(),
                        rpRegistKeyBase64 = "",
                        rpKeyBase64 = "",
                        rpKeyType = 0,
                        serverMac = ""
                    )
                    vault.saveConsole(unpairedRecord)
                    refreshSavedConsoles()
                    val detail = err.message ?: "Native libchiaki_jni.so required for PS5 cryptographic registration."
                    _connectionState.value = ConnectionState.NativeEngineRequired(detail)
                } else {
                    _connectionState.value = ConnectionState.Error(err.message ?: "PS5 registration failed.")
                }
                Result.failure(err)
            }
        )
    }

    /**
     * Opens an authenticated PS5 Remote Play session using real stored registration credentials.
     * Guards against duplicate connection requests and rejects missing/invalid credentials.
     */
    fun connectToConsole(
        console: RegisteredConsole,
        quality: StreamQualitySettings,
        videoRenderer: VideoRenderer,
        audioRenderer: AudioRenderer,
        onInputsCleared: () -> Unit
    ): Result<Unit> = synchronized(operationLock) {
        if (_connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Connected
        ) {
            return Result.failure(
                IllegalStateException("Connection already in progress or active. Disconnect first.")
            )
        }
        if (!console.hasValidSessionCredentials()) {
            val msg = if (!adapter.isNativeLibchiakiLoaded) {
                "PS5 pairing requires the native Remote Play engine."
            } else {
                "Missing or invalid PS5 registration credentials for ${console.hostName}. Pair with an 8-digit PS5 PIN first."
            }
            _connectionState.value = if (!adapter.isNativeLibchiakiLoaded) {
                ConnectionState.NativeEngineRequired(msg)
            } else {
                ConnectionState.Error(msg)
            }
            return Result.failure(IllegalStateException(msg))
        }
        startStreamLocked(StreamRequest(console, quality, videoRenderer, audioRenderer, onInputsCleared))
    }

    private fun startStreamLocked(request: StreamRequest): Result<Unit> {
        val console = request.console
        activeStream = request
        _connectionState.value = ConnectionState.Connecting(
            console.hostAddress,
            if (request.retries == 0) "Starting video" else "Reconnecting"
        )
        val generation = ++sessionGeneration
        val result = adapter.startSession(
            console = console,
            quality = request.quality,
            videoRenderer = request.video,
            audioRenderer = request.audio,
            onConnected = {
                synchronized(operationLock) {
                    if (generation == sessionGeneration && _connectionState.value is ConnectionState.Connecting) {
                        _connectionState.value = ConnectionState.Connected(console.hostName, console.hostAddress)
                        activeStream = request.copy(retries = 0)
                        if (request.retries > 0 && _savedConsoles.value.any {
                                it.hostId == console.hostId && it.hostAddress != console.hostAddress
                            }
                        ) {
                            vault.saveConsole(console)
                            refreshSavedConsoles()
                        }
                    }
                }
            },
            onDisconnected = { reason ->
                synchronized(operationLock) {
                    if (generation == sessionGeneration) {
                        finishOrReconnectLocked(activeStream ?: request, reason, adapter.canRecoverSession(reason))
                    }
                }
            }
        )
        return result.onFailure { err ->
            val detail = err.message ?: "Streaming session failed."
            if (!adapter.isNativeLibchiakiLoaded) {
                finishOrReconnectLocked(request, detail, reconnect = false)
                _connectionState.value = ConnectionState.NativeEngineRequired(detail)
            } else {
                finishOrReconnectLocked(request, detail, reconnect = request.retries > 0)
            }
        }
    }

    private fun finishOrReconnectLocked(request: StreamRequest, reason: String, reconnect: Boolean) {
        val shutdownGeneration = ++sessionGeneration
        val standby = consoleControls.standbyPending.value
        stopStreamLocked(request)
        if (standby) {
            consoleControls.clearStandby()
            _connectionState.value = ConnectionState.StandaloneSimulator
            return
        }
        val attempt = request.retries + 1
        if (!reconnect || attempt > 2) {
            _connectionState.value = ConnectionState.Error(reason)
            return
        }
        activeStream = request.copy(retries = attempt)
        _connectionState.value = ConnectionState.Connecting(request.console.hostAddress, "Reconnecting ($attempt/2)")
        reconnectJob?.cancel()
        reconnectJob = recoveryScope.launch {
            delay(600L * attempt)
            val hits = discovery.scanOnce().getOrDefault(emptyList())
            val mac = consoleIdentity(request.console.serverMac.ifBlank { request.console.hostId })
            val rediscovered = hits.firstOrNull { mac != null && consoleIdentity(it.hostId) == mac &&
                it.hostType.equals("PS5", ignoreCase = true) }
            val next = if (rediscovered == null) request.console
                else request.console.copy(hostAddress = rediscovered.hostAddress)
            synchronized(operationLock) {
                if (shutdownGeneration != sessionGeneration) return@synchronized
                reconnectJob = null
                startStreamLocked(request.copy(console = next, retries = attempt))
            }
        }
    }

    private fun consoleIdentity(value: String): String? = value
        .replace(":", "").replace("-", "").lowercase()
        .takeIf { it.length == 12 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

    private fun stopStreamLocked(request: StreamRequest) {
        activeStream = null
        adapter.stopSession()
        consoleControls.clearPrompt()
        request.onInputsCleared()
        request.video.stopDecoder()
        request.audio.stop()
    }

    fun onWifiLost(onInputsCleared: () -> Unit) = synchronized(operationLock) {
        if (reconnectJob != null) return@synchronized
        val request = activeStream ?: return@synchronized
        val adapterSupportsRecovery = adapter is MomijiLibchiakiAdapter
        finishOrReconnectLocked(
            request.copy(onInputsCleared = onInputsCleared),
            "Local Wi-Fi disconnected. Session safely terminated and all inputs cleared.",
            reconnect = adapterSupportsRecovery
        )
    }

    fun deleteSavedConsole(hostId: String) {
        vault.removeConsole(hostId)
        refreshSavedConsoles()
    }

    fun forwardControllerState(state: PadState) {
        if (_connectionState.value is ConnectionState.Connected) {
            adapter.sendControllerState(state)
        }
    }

    fun disconnect(onInputsCleared: () -> Unit = {}) {
        synchronized(operationLock) {
            ++sessionGeneration
            reconnectJob?.cancel()
            reconnectJob = null
            val request = activeStream
            if (request != null) stopStreamLocked(request.copy(onInputsCleared = onInputsCleared))
            else {
                adapter.stopSession()
                onInputsCleared()
            }
            _connectionState.value = ConnectionState.StandaloneSimulator
        }
    }
}
