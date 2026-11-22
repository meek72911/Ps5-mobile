package com.example.mobileplay

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.mobileplay.audio.AudioRenderer
import com.example.mobileplay.engine.Action
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.engine.TouchEvent
import com.example.mobileplay.engine.TouchInputEngine
import com.example.mobileplay.remoteplay.ChiakiNativeBridge
import com.example.mobileplay.remoteplay.ChiakiRegistrationResult
import com.example.mobileplay.remoteplay.ConnectionManager
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.remoteplay.MomijiLibchiakiAdapter
import com.example.mobileplay.remoteplay.RemotePlayAdapter
import com.example.mobileplay.remoteplay.SonyAuthManager
import com.example.mobileplay.security.KeystoreCredentialVault
import com.example.mobileplay.security.RegisteredConsole
import com.example.mobileplay.video.StreamCodec
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.VideoRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Milestone 2 critical engineering repair verification suite.
 * Covers failure modes, credential integrity, OAuth state/origin hardening,
 * duplicate connection rejection, mid-stream disconnect cleanup, decoder init failures,
 * and simultaneous multi-touch controller state forwarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemotePlaySecurityAndFailureTest {

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun validPairedConsole(): RegisteredConsole = RegisteredConsole(
        hostId = "aa:bb:cc:dd:ee:ff",
        hostName = "Living Room PS5",
        hostAddress = "192.168.1.50",
        psnAccountId = "AAAAAAAAAAA=",
        registrationNote = "Cryptographically paired via libchiaki",
        savedAtMs = 100L,
        rpRegistKeyBase64 = b64(ByteArray(16) { (it + 10).toByte() }),
        rpKeyBase64 = b64(ByteArray(16) { (it + 50).toByte() }),
        rpKeyType = 3,
        serverMac = "aa:bb:cc:dd:ee:ff"
    )

    @Test
    fun rejectsMissingZeroFilledAndMalformedRegistrationCredentials() {
        // Missing keys
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(null, ByteArray(16) { 1 }).isFailure)
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16) { 1 }, null).isFailure)

        // Zero-filled placeholder keys MUST be rejected
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16), ByteArray(16)).isFailure)
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16) { 1 }, ByteArray(16)).isFailure)
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16), ByteArray(16) { 1 }).isFailure)

        // Wrong-length keys MUST be rejected
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(8) { 1 }, ByteArray(16) { 1 }).isFailure)
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16) { 1 }, ByteArray(32) { 1 }).isFailure)

        // Authentic non-zero 16-byte keys succeed
        assertTrue(ChiakiNativeBridge.validateSessionCredentials(ByteArray(16) { 3 }, ByteArray(16) { 5 }).isSuccess)

        val unpaired = RegisteredConsole("id", "PS5", "192.168.1.50", "AAAAAAAAAAA=", "Unpaired", 1L)
        assertFalse(unpaired.hasValidSessionCredentials())

        val zeroed = unpaired.copy(
            rpRegistKeyBase64 = b64(ByteArray(16)),
            rpKeyBase64 = b64(ByteArray(16))
        )
        assertFalse(zeroed.hasValidSessionCredentials())

        val adapter = MomijiLibchiakiAdapter()
        val res = adapter.startSession(zeroed, StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("zero-filled", ignoreCase = true) == true)
    }

    @Test
    fun nativeLibraryAbsencePreventsFakePairingAndFakeConnection() {
        val adapter = MomijiLibchiakiAdapter()
        assertFalse("libchiaki_jni.so should not be loaded in JVM Robolectric tests", adapter.isNativeLibchiakiLoaded)

        val regRes = adapter.registerConsole("192.168.1.50", "AAAAAAAAAAA=", "12345678")
        assertTrue(regRes.isFailure)

        val startRes = adapter.startSession(validPairedConsole(), StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}
        assertTrue(startRes.isFailure)
        assertTrue(startRes.exceptionOrNull()?.message?.contains("libchiaki_jni.so") == true)
    }

    @Test
    fun sessionStartupFailurePropagatesErrorAndClearsInputs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        var cleared = false

        val failingAdapter = object : RemotePlayAdapter {
            override val engineLabel = "FailingStub"
            override val isNativeLibchiakiLoaded = true
            override fun registerConsole(hostAddress: String, psnAccountIdBase64: String, pinCode: String) =
                Result.failure<ChiakiRegistrationResult>(IllegalStateException("PS5 unreachable"))
            override fun startSession(
                console: RegisteredConsole,
                quality: StreamQualitySettings,
                videoRenderer: VideoRenderer,
                audioRenderer: AudioRenderer,
                onConnected: () -> Unit,
                onDisconnected: (String) -> Unit
            ) = Result.failure<Unit>(IllegalStateException("libchiaki handshake refused by PS5"))
            override fun sendControllerState(state: PadState) = Unit
            override fun stopSession() = Unit
        }

        val manager = ConnectionManager(vault, adapter = failingAdapter)
        val result = manager.connectToConsole(
            console = validPairedConsole(),
            quality = StreamQualitySettings(),
            videoRenderer = VideoRenderer(),
            audioRenderer = AudioRenderer(),
            onInputsCleared = { cleared = true }
        )

        assertTrue(result.isFailure)
        assertTrue(cleared)
        assertTrue(manager.connectionState.value is ConnectionState.Error)
        assertTrue((manager.connectionState.value as ConnectionState.Error).message.contains("refused"))
    }

    @Test
    fun duplicateConnectionRequestsAreRejectedWhileConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        var startCount = 0
        var connected: (() -> Unit)? = null

        val adapter = object : RemotePlayAdapter {
            override val engineLabel = "ActiveStub"
            override val isNativeLibchiakiLoaded = true
            override fun registerConsole(hostAddress: String, psnAccountIdBase64: String, pinCode: String) =
                Result.failure<ChiakiRegistrationResult>(UnsupportedOperationException())
            override fun startSession(
                console: RegisteredConsole,
                quality: StreamQualitySettings,
                videoRenderer: VideoRenderer,
                audioRenderer: AudioRenderer,
                onConnected: () -> Unit,
                onDisconnected: (String) -> Unit
            ): Result<Unit> {
                startCount++
                connected = onConnected
                return Result.success(Unit)
            }
            override fun sendControllerState(state: PadState) = Unit
            override fun stopSession() = Unit
        }

        val manager = ConnectionManager(vault, adapter = adapter)
        val first = manager.connectToConsole(validPairedConsole(), StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}
        assertTrue(manager.connectionState.value is ConnectionState.Connecting)
        connected!!.invoke()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        val second = manager.connectToConsole(validPairedConsole(), StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}

        assertTrue(first.isSuccess)
        assertTrue(second.isFailure)
        assertEquals(1, startCount)
    }

    @Test
    fun midStreamDisconnectResetsControllerStopsForwardingAndCleansUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        val sentStates = mutableListOf<PadState>()
        var disconnectTrigger: ((String) -> Unit)? = null
        var stopped = false
        var inputsCleared = false

        val adapter = object : RemotePlayAdapter {
            override val engineLabel = "StreamStub"
            override val isNativeLibchiakiLoaded = true
            override fun registerConsole(hostAddress: String, psnAccountIdBase64: String, pinCode: String) =
                Result.failure<ChiakiRegistrationResult>(UnsupportedOperationException())
            override fun startSession(
                console: RegisteredConsole,
                quality: StreamQualitySettings,
                videoRenderer: VideoRenderer,
                audioRenderer: AudioRenderer,
                onConnected: () -> Unit,
                onDisconnected: (String) -> Unit
            ): Result<Unit> {
                disconnectTrigger = onDisconnected
                onConnected()
                return Result.success(Unit)
            }
            override fun sendControllerState(state: PadState) { sentStates.add(state) }
            override fun stopSession() { stopped = true }
        }

        val manager = ConnectionManager(vault, adapter = adapter)
        manager.connectToConsole(validPairedConsole(), StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {
            inputsCleared = true
        }

        // Simultaneous Move + Aim + Fire forwarded while connected
        val engine = TouchInputEngine(1000f, 500f, 400f, 400f, ControlProfile.onFootShooterPreset()) {
            manager.forwardControllerState(it)
        }
        engine.accept(TouchEvent(Action.DOWN, 1, 180f, 340f, 1_000_000L))
        engine.accept(TouchEvent(Action.MOVE, 1, 240f, 320f, 9_000_000L))
        engine.accept(TouchEvent(Action.DOWN, 2, 560f, 250f, 10_000_000L))
        engine.accept(TouchEvent(Action.MOVE, 2, 620f, 250f, 18_000_000L))
        engine.accept(TouchEvent(Action.DOWN, 3, 860f, 370f, 19_000_000L))

        assertTrue(sentStates.isNotEmpty())
        val activeState = sentStates.last()
        assertTrue(activeState.leftX > 0f)
        assertTrue(activeState.rightX > 0f)
        assertEquals(255, activeState.r2)

        // Mid-stream PS5 disconnect event fires
        disconnectTrigger?.invoke("PS5 entered Rest Mode")
        assertTrue(stopped)
        assertTrue(inputsCleared)
        assertTrue(manager.connectionState.value is ConnectionState.Error)

        // Disconnected session MUST NOT forward any further stale controller inputs
        val countAfterDisconnect = sentStates.size
        manager.forwardControllerState(PadState(r2 = 255, leftX = 1f))
        assertEquals(countAfterDisconnect, sentStates.size)
    }

    @Test
    fun sonyOAuthRejectsMalformedRedirectsAndInvalidExpiredOrReplayedStates() {
        val now = 2_000_000L
        val uri = SonyAuthManager.buildBrowserSignInUri(nowMs = now)
        val validState = uri.getQueryParameter("state")!!

        // Wrong host (phishing / spoofed origin) rejected
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://evil.example.com/remoteplay/redirect?code=v3.abc123&state=$validState",
                nowMs = now + 1_000L
            ).isFailure
        )
        // Non-HTTPS scheme rejected
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "http://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123&state=$validState",
                nowMs = now + 1_000L
            ).isFailure
        )
        // Wrong path rejected
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/other/path?code=v3.abc123&state=$validState",
                nowMs = now + 1_000L
            ).isFailure
        )
        // Missing state rejected
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123",
                nowMs = now + 1_000L
            ).isFailure
        )
        // Wrong state rejected
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123&state=forged_state",
                nowMs = now + 1_000L
            ).isFailure
        )
        // Expired state (>10 minutes) rejected
        val expiredUri = SonyAuthManager.buildBrowserSignInUri(nowMs = now)
        val expiredState = expiredUri.getQueryParameter("state")!!
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123&state=$expiredState",
                nowMs = now + 11 * 60 * 1000L
            ).isFailure
        )
        // Valid state succeeds ONCE and is rejected on replay
        val freshUri = SonyAuthManager.buildBrowserSignInUri(nowMs = now)
        val freshState = freshUri.getQueryParameter("state")!!
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.validCode&state=$freshState",
                nowMs = now + 5_000L
            ).isSuccess
        )
        assertTrue(
            SonyAuthManager.extractAuthorizationCode(
                "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.validCode&state=$freshState",
                nowMs = now + 6_000L
            ).isFailure
        )
    }

    @Test
    fun videoRendererFailsCleanlyWithoutSurfaceAndDetectsCodecConfigNals() {
        val renderer = VideoRenderer()
        // Without an attached valid SurfaceView, configureHardwareDecoder MUST fail cleanly
        val initRes = renderer.configureHardwareDecoder(StreamQualitySettings())
        assertTrue(initRes.isFailure)
        assertEquals("DECODER ERROR", renderer.telemetry.value.decoderState)

        // Annex-B H.264 SPS (0x67 -> type 7) + PPS (0x68 -> type 8)
        val h264Config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0, 0, 0, 1, 0x68, 0xCE.toByte())
        assertTrue(VideoRenderer.isCodecConfigAccessUnit(h264Config, StreamCodec.H264))

        // Annex-B H.264 IDR frame (0x65 -> type 5) is NOT config-only
        val h264Idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        assertFalse(VideoRenderer.isCodecConfigAccessUnit(h264Idr, StreamCodec.H264))

        // Annex-B HEVC VPS (type 32 -> 0x40) + SPS (type 33 -> 0x42)
        val hevcConfig = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x01)
        assertTrue(VideoRenderer.isCodecConfigAccessUnit(hevcConfig, StreamCodec.HEVC))
    }

    @Test
    fun decoderRecoveryAcceptsRandomAccessFramesAfterParameterSets() {
        val avcKeyframe = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 1, 0x65, 0x01)
        val avcInter = byteArrayOf(0, 0, 1, 0x41, 0x01)
        val hevcKeyframe = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 1, 0x2A, 0x01)
        val hevcInter = byteArrayOf(0, 0, 1, 0x02, 0x01)

        assertTrue(VideoRenderer.isRandomAccessUnit(avcKeyframe, StreamCodec.H264))
        assertFalse(VideoRenderer.isRandomAccessUnit(avcInter, StreamCodec.H264))
        assertTrue(VideoRenderer.isRandomAccessUnit(hevcKeyframe, StreamCodec.HEVC))
        assertFalse(VideoRenderer.isRandomAccessUnit(hevcInter, StreamCodec.HEVC))
        assertFalse(VideoRenderer.isRandomAccessUnit(byteArrayOf(0, 0, 0, 1), StreamCodec.H264))
    }
}
