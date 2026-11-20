package com.example.mobileplay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobileplay.audio.AudioRenderer
import com.example.mobileplay.engine.ButtonShape
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.EditorTarget
import com.example.mobileplay.engine.LayoutEditorState
import com.example.mobileplay.engine.Owner
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.remoteplay.ChiakiNativeBridge
import com.example.mobileplay.remoteplay.ConnectionManager
import com.example.mobileplay.remoteplay.ConnectionState
import com.example.mobileplay.remoteplay.RemotePlayAdapter
import com.example.mobileplay.remoteplay.SonyAuthManager
import com.example.mobileplay.security.KeystoreCredentialVault
import com.example.mobileplay.security.RegisteredConsole
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.VideoRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated tests for Milestone 2 & 3 subsystems:
 * - Visual Layout Editor reducer (move, resize, opacity, shape, rebind, grid snap, undo/redo, reset)
 * - Legitimate Sony OAuth browser URL builder & PSN Account ID Base64/Numeric normalizer
 * - libchiaki JNI button bitflag & stick conversion contract
 * - ConnectionManager validation, honest NDK missing state, and Wi-Fi loss disconnect cleanup
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LayoutEditorAndAuthTest {

    @Test
    fun layoutEditorMoveResizeShapeOpacityReassignAndUndoRedo() {
        val base = ControlProfile.onFootShooterPreset()
        val fireId = base.buttons.first { it.owner == Owner.FIRE }.id

        var state = LayoutEditorState(draft = base)
            .select(EditorTarget.Button(fireId))
            .moveButton(fireId, 0.77f, 0.55f, recordUndo = true)
            .resizeSelected(0.11f, recordUndo = true)
            .setSelectedOpacity(0.45f, recordUndo = true)
            .setSelectedShape(ButtonShape.OCTAGON)
            .reassignSelectedAction(Owner.RELOAD)

        val edited = state.selectedButton!!
        assertEquals(0.77f, edited.centerX, 0.001f)
        assertEquals(0.55f, edited.centerY, 0.001f)
        assertEquals(0.11f, edited.radiusShortFraction, 0.001f)
        assertEquals(0.45f, edited.opacity, 0.001f)
        assertEquals(ButtonShape.OCTAGON, edited.shape)
        assertEquals(Owner.RELOAD, edited.owner)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)

        // Undo reassignment -> back to FIRE
        state = state.undo()
        assertEquals(Owner.FIRE, state.selectedButton!!.owner)
        assertTrue(state.canRedo)

        // Redo reassignment -> back to RELOAD
        state = state.redo()
        assertEquals(Owner.RELOAD, state.selectedButton!!.owner)

        // Undo all steps -> back to exact original baseline
        while (state.canUndo) {
            state = state.undo()
        }
        assertEquals(base, state.draft)
    }

    @Test
    fun layoutEditorGridSnapCameraRegionAndResetToDefault() {
        val base = ControlProfile.onFootShooterPreset()
        var state = LayoutEditorState(draft = base, snapToGrid = true)
            .select(EditorTarget.Joystick)
            .moveJoystick(0.213f, 0.729f, recordUndo = true)
            .resizeSelected(0.18f, recordUndo = true)
            .updateCameraRegion(startX = 0.48f, topY = 0.12f, bottomY = 0.90f, recordUndo = true)

        // Snapped to 0.02 grid increments
        assertEquals(0.22f, state.draft.joystickCenterX, 0.001f)
        assertEquals(0.72f, state.draft.joystickCenterY, 0.001f)
        assertEquals(0.18f, state.draft.joystickRadiusFraction, 0.001f)
        assertEquals(0.48f, state.draft.cameraRegionStartX, 0.001f)
        assertEquals(0.12f, state.draft.cameraRegionTopY, 0.001f)
        assertEquals(0.90f, state.draft.cameraRegionBottomY, 0.001f)

        state = state.resetLayoutToPresetDefault()
        assertEquals(base.joystickCenterX, state.draft.joystickCenterX, 0.001f)
        assertEquals(base.cameraRegionStartX, state.draft.cameraRegionStartX, 0.001f)
    }

    @Test
    fun sonyAuthManagerBuildsOfficialOAuthUriAndExtractsCode() {
        val now = 1_000_000L
        val uri = SonyAuthManager.buildBrowserSignInUri(nowMs = now)
        assertEquals("https", uri.scheme)
        assertEquals("ca.account.sony.com", uri.host)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals(SonyAuthManager.SONY_REDIRECT_URI, uri.getQueryParameter("redirect_uri"))
        val state = uri.getQueryParameter("state")
        assertFalse(state.isNullOrBlank())

        val ok = SonyAuthManager.extractAuthorizationCode(
            redirectUrl = "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123XYZ&state=$state",
            nowMs = now + 5_000L
        )
        assertTrue(ok.isSuccess)
        assertEquals("v3.abc123XYZ", ok.getOrThrow())

        val missing = SonyAuthManager.extractAuthorizationCode(
            redirectUrl = "https://remoteplay.dl.playstation.net/remoteplay/redirect?error=access_denied",
            nowMs = now + 5_000L
        )
        assertTrue(missing.isFailure)
        assertTrue(missing.exceptionOrNull()?.message?.contains("canceled", ignoreCase = true) == true)
    }

    @Test
    fun sonyAuthManagerNormalizesNumericAndBase64AccountIds() {
        // Valid 8-byte Base64 Account ID
        val b64Result = SonyAuthManager.normalizeAccountIdToBase64("AAAAAAAAAAA=")
        assertTrue(b64Result.isSuccess)
        assertEquals("AAAAAAAAAAA=", b64Result.getOrThrow())

        // Valid 64-bit numeric Account ID -> 8-byte little-endian Base64
        val numResult = SonyAuthManager.normalizeAccountIdToBase64("1234567890")
        assertTrue(numResult.isSuccess)
        val decoded = android.util.Base64.decode(numResult.getOrThrow(), android.util.Base64.NO_WRAP)
        assertEquals(8, decoded.size)

        // Invalid Base64 byte length rejected
        assertTrue(SonyAuthManager.normalizeAccountIdToBase64("YWJj").isFailure)
        assertTrue(SonyAuthManager.normalizeAccountIdToBase64("   ").isFailure)
    }

    @Test
    fun chiakiNativeBridgeMapsButtonsAndClampsSticksToUpstreamSpec() {
        val mobileMask = DualSenseButtons.CROSS or
            DualSenseButtons.CIRCLE or
            DualSenseButtons.L1 or
            DualSenseButtons.R3 or
            DualSenseButtons.PS
        val chiakiMask = ChiakiNativeBridge.toChiakiButtonMask(mobileMask)
        val expected = (1 shl 0) or (1 shl 1) or (1 shl 8) or (1 shl 11) or (1 shl 15)
        assertEquals(expected, chiakiMask)

        assertEquals(32767.toShort(), ChiakiNativeBridge.floatToStickShort(1.5f))
        assertEquals((-32767).toShort(), ChiakiNativeBridge.floatToStickShort(-1.5f))
        assertEquals(0.toShort(), ChiakiNativeBridge.floatToStickShort(0f))
    }

    @Test
    fun connectionManagerValidatesPairingReportsMissingNdkAndClearsOnWifiLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        val manager = ConnectionManager(vault)

        // Reject invalid inputs cleanly
        assertTrue(manager.pairAndSaveConsole("PS5", "", "AAAAAAAAAAA=", "12345678").isFailure)
        assertTrue(manager.pairAndSaveConsole("PS5", "192.168.1.50", "bad", "12345678").isFailure)
        assertTrue(manager.pairAndSaveConsole("PS5", "192.168.1.50", "AAAAAAAAAAA=", "1234").isFailure)

        val validRegistKey = android.util.Base64.encodeToString(ByteArray(16) { (it + 1).toByte() }, android.util.Base64.NO_WRAP)
        val validRpKey = android.util.Base64.encodeToString(ByteArray(16) { (it + 33).toByte() }, android.util.Base64.NO_WRAP)
        val pairedConsole = RegisteredConsole(
            hostId = "ps5_1",
            hostName = "Living Room PS5",
            hostAddress = "192.168.1.50",
            psnAccountId = "AAAAAAAAAAA=",
            registrationNote = "Paired",
            savedAtMs = 1L,
            rpRegistKeyBase64 = validRegistKey,
            rpKeyBase64 = validRpKey,
            rpKeyType = 3
        )

        // Without native libchiaki_jni.so, connecting MUST NOT fake ConnectionState.Connected
        manager.connectToConsole(pairedConsole, StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}
        assertTrue(manager.connectionState.value is ConnectionState.NativeEngineRequired)
        assertNotEquals(ConnectionState.Connected("Living Room PS5", "192.168.1.50"), manager.connectionState.value)

        // Simulate an active adapter session and verify Wi-Fi loss triggers stop + input clear + error state
        var stopped = false
        var inputsCleared = false
        val stubAdapter = object : RemotePlayAdapter {
            override val engineLabel = "TestStub"
            override val isNativeLibchiakiLoaded = true
            override fun registerConsole(hostAddress: String, psnAccountIdBase64: String, pinCode: String) =
                Result.success(
                    com.example.mobileplay.remoteplay.ChiakiRegistrationResult(
                        serverNickname = "PS5-Stub",
                        serverMac = "aa:bb:cc:dd:ee:ff",
                        rpRegistKey = ByteArray(16) { 7 },
                        rpKey = ByteArray(16) { 9 },
                        rpKeyType = 3
                    )
                )
            override fun startSession(
                console: RegisteredConsole,
                quality: StreamQualitySettings,
                videoRenderer: VideoRenderer,
                audioRenderer: AudioRenderer,
                onDisconnected: (String) -> Unit
            ) = Result.success(Unit)
            override fun sendControllerState(state: PadState) = Unit
            override fun stopSession() { stopped = true }
        }
        val activeManager = ConnectionManager(vault, adapter = stubAdapter)
        activeManager.connectToConsole(pairedConsole, StreamQualitySettings(), VideoRenderer(), AudioRenderer()) {}
        assertTrue(activeManager.connectionState.value is ConnectionState.Connected)

        activeManager.onWifiLost { inputsCleared = true }
        assertTrue(stopped)
        assertTrue(inputsCleared)
        assertTrue(activeManager.connectionState.value is ConnectionState.Error)
    }
}
