package com.example.mobileplay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.profile.ProfileManager
import com.example.mobileplay.remoteplay.LocalPs5Discovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated tests for JSON profile serialization, SharedPreferences persistence,
 * and PlayStation LAN UDP discovery response parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileAndDiscoveryTest {

    @Test
    fun controlProfileJsonRoundTripPreservesAllTuningFields() {
        val original = ControlProfile(
            id = "custom_shooter",
            name = "Tactical GTA 6",
            mode = ControlMode.ON_FOOT,
            tuning = AimTuning(
                speedForFullStickMmPerSec = 420f,
                horizontalSensitivity = 1.45f,
                verticalSensitivity = 0.95f,
                adsSensitivityMultiplier = 0.48f,
                antiDeadzone = 0.16f,
                curve = 1.6f,
                smoothingTimeMs = 8f,
                stopAfterMs = 30L,
                fireDragAimEnabled = true,
                adsToggleMode = true,
                sprintToggleMode = false,
                crouchToggleMode = true
            ),
            controlOpacity = 0.64f,
            movementSplitFraction = 0.42f,
            showDiagnosticsHud = false,
            useLegacyOverlay = true
        )

        val json = ProfileManager.toJson(original)
        val restored = ProfileManager.fromJson(json, ControlProfile.onFootShooterPreset())

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.mode, restored.mode)
        assertEquals(original.controlOpacity, restored.controlOpacity, 0.001f)
        assertEquals(original.movementSplitFraction, restored.movementSplitFraction, 0.001f)
        assertEquals(original.showDiagnosticsHud, restored.showDiagnosticsHud)
        assertEquals(original.useLegacyOverlay, restored.useLegacyOverlay)
        assertEquals(original.tuning.speedForFullStickMmPerSec, restored.tuning.speedForFullStickMmPerSec, 0.001f)
        assertEquals(original.tuning.horizontalSensitivity, restored.tuning.horizontalSensitivity, 0.001f)
        assertEquals(original.tuning.verticalSensitivity, restored.tuning.verticalSensitivity, 0.001f)
        assertEquals(original.tuning.adsSensitivityMultiplier, restored.tuning.adsSensitivityMultiplier, 0.001f)
        assertEquals(original.tuning.antiDeadzone, restored.tuning.antiDeadzone, 0.001f)
        assertEquals(original.tuning.curve, restored.tuning.curve, 0.001f)
        assertEquals(original.tuning.smoothingTimeMs, restored.tuning.smoothingTimeMs, 0.001f)
        assertEquals(original.tuning.stopAfterMs, restored.tuning.stopAfterMs)
        assertEquals(original.tuning.fireDragAimEnabled, restored.tuning.fireDragAimEnabled)
        assertEquals(original.tuning.adsToggleMode, restored.tuning.adsToggleMode)
        assertEquals(original.tuning.sprintToggleMode, restored.tuning.sprintToggleMode)
        assertEquals(original.tuning.crouchToggleMode, restored.tuning.crouchToggleMode)
    }

    @Test
    fun parsesValidPs5DiscoveryResponseAndRejectsGarbage() {
        val response = """
            HTTP/1.1 200 Ok
            host-id:AA11BB22CC33
            host-type:PS5
            host-name:PS5-LivingRoom
            host-request-port:997
            device-discovery-protocol-version:00030010
            system-version:10010000
        """.trimIndent()

        val parsed = LocalPs5Discovery.parseDiscoveryResponse("192.168.1.42", response)
        assertNotNull(parsed)
        assertEquals("192.168.1.42", parsed?.hostAddress)
        assertEquals("PS5-LivingRoom", parsed?.hostName)
        assertEquals("AA11BB22CC33", parsed?.hostId)
        assertEquals("PS5", parsed?.hostType)
        assertEquals("10010000", parsed?.systemVersion)

        assertNull(LocalPs5Discovery.parseDiscoveryResponse("192.168.1.42", "GARBAGE"))
        assertNull(LocalPs5Discovery.parseDiscoveryResponse("", response))
    }

    @Test
    fun profileManagerPersistsAndReloadsCustomTuning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProfileManager(context)
        val custom = ControlProfile.drivingPreset().copy(
            controlOpacity = 0.52f,
            tuning = AimTuning(horizontalSensitivity = 1.85f, verticalSensitivity = 1.1f)
        )

        manager.saveProfile(custom)
        val reloaded = manager.loadProfile(ControlMode.DRIVING)
        assertEquals(0.52f, reloaded.controlOpacity, 0.001f)
        assertEquals(1.85f, reloaded.tuning.horizontalSensitivity, 0.001f)
        assertEquals(ControlMode.DRIVING, manager.loadLastMode())
    }

    @Test
    fun multiProfileDuplicateExportImportAndDeleteRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProfileManager(context)

        val base = ControlProfile.genericShooterPreset()
        val duplicated = manager.duplicateProfile(base, "Claw 4-Finger Custom")
        assertEquals("Claw 4-Finger Custom", duplicated.name)
        assertEquals(false, duplicated.isBuiltIn)

        val prettyJson = manager.exportPrettyJson(duplicated)
        val importedResult = manager.importProfileFromJson(prettyJson)
        assertEquals(true, importedResult.isSuccess)
        val imported = importedResult.getOrThrow()
        assertEquals("Claw 4-Finger Custom", imported.name)
        assertEquals(duplicated.buttons.size, imported.buttons.size)
        assertEquals(duplicated.joystickCenterX, imported.joystickCenterX, 0.001f)

        val afterDelete = manager.deleteCustomProfile(duplicated.id)
        assertEquals(false, afterDelete.any { it.id == duplicated.id })
        // Built-in presets cannot be deleted
        val afterAttemptBuiltInDelete = manager.deleteCustomProfile(ControlProfile.ID_ON_FOOT)
        assertEquals(true, afterAttemptBuiltInDelete.any { it.id == ControlProfile.ID_ON_FOOT })
    }

    @Test
    fun streamQualitySettingsPersistAndReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProfileManager(context)
        val settings = com.example.mobileplay.video.StreamQualitySettings(
            resolution = com.example.mobileplay.video.StreamResolution.P1080,
            frameRate = com.example.mobileplay.video.StreamFrameRate.FPS_60,
            codec = com.example.mobileplay.video.StreamCodec.HEVC,
            autoQuality = false,
            manualBitrateKbps = 24000,
            hardwareDecodingEnabled = true,
            audioVolume = 0.72f
        )

        manager.saveStreamQuality(settings)
        val loaded = manager.loadStreamQuality()
        assertEquals(settings.resolution, loaded.resolution)
        assertEquals(settings.frameRate, loaded.frameRate)
        assertEquals(settings.codec, loaded.codec)
        assertEquals(false, loaded.autoQuality)
        assertEquals(24000, loaded.manualBitrateKbps)
        assertEquals(0.72f, loaded.audioVolume, 0.001f)
    }
}
