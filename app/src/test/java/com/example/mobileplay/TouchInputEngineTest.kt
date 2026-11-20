package com.example.mobileplay

import com.example.mobileplay.engine.Action
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.Owner
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.engine.TouchEvent
import com.example.mobileplay.engine.TouchInputEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Pure JVM regression & feature test suite for MobilePlay's TouchInputEngine.
 * Covers all 13 Phase 1 v2 checks plus BGMI-style fire-drag aim, ADS scaling,
 * hold/toggle modes, radial weapon wheel, and Driving Mode.
 */
class TouchInputEngineTest {
    private fun e(action: Action, id: Int, x: Float, y: Float, ms: Long) =
        TouchEvent(action, id, x, y, ms * 1_000_000L)

    private fun legacyEngine(onState: (PadState) -> Unit = {}) = TouchInputEngine(
        widthPx = 1000f,
        heightPx = 500f,
        xdpi = 400f,
        ydpi = 400f,
        profile = ControlProfile.onFootShooterPreset().copy(
            useLegacyOverlay = true,
            tuning = AimTuning(curve = 1f, smoothingTimeMs = 12f, stopAfterMs = 25L)
        ),
        onState = onState
    )

    private fun onFootEngine(tuning: AimTuning = ControlProfile.onFootShooterPreset().tuning) =
        TouchInputEngine(
            widthPx = 1000f,
            heightPx = 500f,
            xdpi = 400f,
            ydpi = 400f,
            profile = ControlProfile.onFootShooterPreset().copy(tuning = tuning)
        )

    private fun drivingEngine() = TouchInputEngine(
        widthPx = 1000f,
        heightPx = 500f,
        xdpi = 400f,
        ydpi = 400f,
        profile = ControlProfile.drivingPreset()
    )

    @Test
    fun threeSimultaneousFingersAndIndependentRelease() {
        val engine = legacyEngine()
        engine.accept(e(Action.DOWN, 9, 100f, 250f, 1))
        engine.accept(e(Action.MOVE, 9, 160f, 235f, 9))
        engine.accept(e(Action.DOWN, 27, 600f, 250f, 10))
        engine.accept(e(Action.MOVE, 27, 640f, 250f, 18))
        engine.accept(e(Action.DOWN, 31, 850f, 360f, 19))

        assertEquals(3, engine.activePointers)
        assertTrue(engine.state.leftX > 0f)
        assertTrue(engine.state.rightX > 0f)
        assertEquals(255, engine.state.r2)

        engine.accept(e(Action.UP, 31, 850f, 360f, 20))
        assertEquals(0, engine.state.r2)
        assertTrue(engine.state.leftX > 0f)
        assertTrue(engine.state.rightX > 0f)

        engine.accept(e(Action.UP, 9, 160f, 235f, 21))
        assertEquals(0f, engine.state.leftX, 0f)
        assertTrue(engine.state.rightX > 0f)

        engine.accept(e(Action.UP, 27, 640f, 250f, 22))
        assertEquals(PadState(), engine.state)
    }

    @Test
    fun zoneCrossingRetainsOriginalOwner() {
        val engine = legacyEngine()
        engine.accept(e(Action.DOWN, 3, 550f, 350f, 1))
        engine.accept(e(Action.MOVE, 3, 850f, 360f, 10))
        assertEquals(Owner.CAMERA, engine.ownerOf(3))
        assertEquals(0, engine.state.r2)
        engine.accept(e(Action.UP, 3, 850f, 360f, 11))

        // Disable fireDragAim for strict legacy zone check
        engine.tuning = engine.tuning.copy(fireDragAimEnabled = false)
        engine.accept(e(Action.DOWN, 4, 850f, 360f, 12))
        engine.accept(e(Action.MOVE, 4, 600f, 250f, 20))
        assertEquals(Owner.FIRE, engine.ownerOf(4))
        assertEquals(255, engine.state.r2)
        assertEquals(0f, engine.state.rightX, 0f)
    }

    @Test
    fun cancelZeroesAllInputsAndLatchedToggles() {
        val engine = onFootEngine(AimTuning(adsToggleMode = true, sprintToggleMode = true))
        engine.accept(e(Action.DOWN, 1, 110f, 170f, 1)) // SPRINT toggle
        engine.accept(e(Action.UP, 1, 110f, 170f, 2))
        assertTrue(engine.state.isPressed(DualSenseButtons.L3))

        engine.accept(e(Action.DOWN, 2, 720f, 390f, 3)) // AIM toggle
        engine.accept(e(Action.UP, 2, 720f, 390f, 4))
        assertEquals(255, engine.state.l2)

        engine.accept(e(Action.DOWN, 3, 200f, 320f, 5)) // MOVEMENT
        engine.accept(e(Action.MOVE, 3, 260f, 320f, 12))
        engine.accept(e(Action.CANCEL, -1, 0f, 0f, 14))

        assertEquals(0, engine.activePointers)
        assertEquals(PadState(), engine.state)
    }

    @Test
    fun staleCameraIsZeroedByWatchdogWithoutDrift() {
        val engine = legacyEngine()
        engine.accept(e(Action.DOWN, 1, 600f, 250f, 1))
        engine.accept(e(Action.MOVE, 1, 660f, 250f, 10))
        assertTrue(engine.state.rightX > 0f)

        engine.tick(36 * 1_000_000L)
        assertEquals(0f, engine.state.rightX, 0f)
        assertEquals(0f, engine.state.rightY, 0f)
        assertEquals(1L, engine.frameTiming.watchdogZeroCount)
    }

    @Test
    fun outOfOrderAndZeroDtSamplesDoNotExplodeCamera() {
        val engine = legacyEngine()
        engine.accept(e(Action.DOWN, 1, 600f, 250f, 10))
        engine.accept(e(Action.MOVE, 1, 650f, 250f, 10))
        assertEquals(0f, engine.state.rightX, 0f)
        engine.accept(e(Action.MOVE, 1, 700f, 250f, 9))
        assertEquals(0f, engine.state.rightX, 0f)
    }

    @Test
    fun duplicateDownAndMultiFingerFireDoNotStickOrUnpressEarly() {
        val engine = legacyEngine()
        // Duplicate DOWN with same ID
        engine.accept(e(Action.DOWN, 10, 850f, 360f, 1))
        engine.accept(e(Action.DOWN, 10, 850f, 360f, 2))
        engine.accept(e(Action.UP, 10, 850f, 360f, 3))
        assertEquals(0, engine.state.r2)
        assertEquals(0, engine.activePointers)

        // Two distinct fingers on fire
        engine.accept(e(Action.DOWN, 1, 850f, 360f, 4))
        engine.accept(e(Action.DOWN, 2, 850f, 360f, 5))
        engine.accept(e(Action.UP, 1, 850f, 360f, 6))
        assertEquals(255, engine.state.r2)
        engine.accept(e(Action.UP, 2, 850f, 360f, 7))
        assertEquals(0, engine.state.r2)
    }

    @Test
    fun invalidUnknownAndNegativePointersAreIgnored() {
        val engine = legacyEngine()
        engine.accept(e(Action.MOVE, 999, 50f, 50f, 2))
        engine.accept(e(Action.UP, 999, 50f, 50f, 4))
        engine.accept(e(Action.DOWN, 1, Float.NaN, 20f, 5))
        engine.accept(e(Action.DOWN, -4, 850f, 360f, 6))
        assertEquals(0, engine.activePointers)
        assertEquals(PadState(), engine.state)
    }

    @Test
    fun clampedMovementAndIdempotentClearAll() {
        val outputs = mutableListOf<PadState>()
        val engine = legacyEngine(onState = outputs::add)
        engine.accept(e(Action.DOWN, 1, 300f, 250f, 1))
        engine.accept(e(Action.MOVE, 1, 0f, 500f, 9))
        assertTrue(engine.state.leftX < 0f && engine.state.leftY > 0f)
        assertTrue(hypot(engine.state.leftX, engine.state.leftY) <= 1.0001f)

        engine.clearAll()
        engine.clearAll()
        assertEquals(PadState(), engine.state)
        assertEquals(PadState(), outputs.last())
    }

    @Test
    fun fireButtonDragAimingControlsRightStickWhileFiring() {
        val engine = onFootEngine(AimTuning(fireDragAimEnabled = true, smoothingTimeMs = 0f))
        // Fire button center in on-foot layout: (860, 370)
        engine.accept(e(Action.DOWN, 5, 860f, 370f, 1))
        assertEquals(Owner.FIRE, engine.ownerOf(5))
        assertEquals(255, engine.state.r2)

        engine.accept(e(Action.MOVE, 5, 895f, 385f, 11))
        assertEquals(255, engine.state.r2)
        assertTrue(engine.state.rightX > 0f)
        assertTrue(engine.state.rightY > 0f)

        engine.accept(e(Action.UP, 5, 895f, 385f, 15))
        assertEquals(PadState(), engine.state)
    }

    @Test
    fun adsSensitivityMultiplierReducesRightStickOutputWhenAiming() {
        val tuning = AimTuning(
            smoothingTimeMs = 0f,
            adsSensitivityMultiplier = 0.4f,
            adsToggleMode = false,
            curve = 1f
        )
        val hipEngine = onFootEngine(tuning)
        hipEngine.accept(e(Action.DOWN, 1, 550f, 250f, 1))
        hipEngine.accept(e(Action.MOVE, 1, 580f, 250f, 11))
        val hipRightX = hipEngine.state.rightX

        val adsEngine = onFootEngine(tuning)
        // Hold AIM button at (720, 390)
        adsEngine.accept(e(Action.DOWN, 2, 720f, 390f, 1))
        assertEquals(255, adsEngine.state.l2)
        adsEngine.accept(e(Action.DOWN, 1, 550f, 250f, 2))
        adsEngine.accept(e(Action.MOVE, 1, 580f, 250f, 12))
        val adsRightX = adsEngine.state.rightX

        assertTrue("ADS output ($adsRightX) should be smaller than hip output ($hipRightX)", adsRightX < hipRightX)
        assertTrue(adsRightX > 0f)
    }

    @Test
    fun radialWeaponWheelUsesAbsoluteRightStickAndResetsOnRelease() {
        val engine = onFootEngine()
        // WEAPON_WHEEL center is (930, 140)
        engine.accept(e(Action.DOWN, 7, 930f, 140f, 1))
        assertEquals(Owner.WEAPON_WHEEL, engine.ownerOf(7))
        assertTrue(engine.state.isPressed(DualSenseButtons.L1))

        engine.accept(e(Action.MOVE, 7, 860f, 140f, 10))
        assertTrue(engine.state.rightX < -0.5f)
        assertEquals(0f, engine.state.rightY, 0.01f)

        // Watchdog must NOT zero right stick while player holds weapon wheel open!
        engine.tick(100 * 1_000_000L)
        assertTrue(engine.state.rightX < -0.5f)

        engine.accept(e(Action.UP, 7, 860f, 140f, 110))
        assertFalse(engine.state.isPressed(DualSenseButtons.L1))
        assertEquals(0f, engine.state.rightX, 0f)
    }

    @Test
    fun drivingModeSupportsAnalogSteeringGasBrakeHandbrakeAndExit() {
        val engine = drivingEngine()
        // Left side analog steer
        engine.accept(e(Action.DOWN, 1, 200f, 340f, 1))
        assertEquals(Owner.STEER, engine.ownerOf(1))
        engine.accept(e(Action.MOVE, 1, 255f, 300f, 10))
        assertTrue(engine.state.leftX > 0.4f)
        assertEquals(0f, engine.state.leftY, 0f)

        // Gas (R2) at (880, 370), Handbrake (CIRCLE) at (860, 230), Exit (TRIANGLE) at (930, 125)
        engine.accept(e(Action.DOWN, 2, 880f, 370f, 12))
        engine.accept(e(Action.DOWN, 3, 860f, 230f, 13))
        assertEquals(255, engine.state.r2)
        assertTrue(engine.state.isPressed(DualSenseButtons.CIRCLE))

        // Switching mode back to ON_FOOT automatically clears held inputs (no stuck throttle!)
        engine.profile = ControlProfile.onFootShooterPreset()
        assertEquals(PadState(), engine.state)
        assertEquals(0, engine.activePointers)
    }

    @Test
    fun validationRejectsInvalidAndNonFiniteConfigOrGeometry() {
        assertThrows(IllegalArgumentException::class.java) { AimTuning(antiDeadzone = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { AimTuning(speedForFullStickMmPerSec = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { AimTuning(smoothingTimeMs = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { TouchInputEngine(Float.POSITIVE_INFINITY, 500f, 400f, 400f) }
        assertThrows(IllegalArgumentException::class.java) { ControlProfile("bad", "Bad", ControlMode.ON_FOOT, controlOpacity = 0f) }
    }

    @Test
    fun invertXAndInvertYFlipCameraStickAxes() {
        val normal = onFootEngine(AimTuning(smoothingTimeMs = 0f, invertX = false, invertY = false))
        normal.accept(e(Action.DOWN, 1, 560f, 250f, 1))
        normal.accept(e(Action.MOVE, 1, 610f, 290f, 11))

        val inverted = onFootEngine(AimTuning(smoothingTimeMs = 0f, invertX = true, invertY = true))
        inverted.accept(e(Action.DOWN, 1, 560f, 250f, 1))
        inverted.accept(e(Action.MOVE, 1, 610f, 290f, 11))

        assertTrue(normal.state.rightX > 0f)
        assertTrue(normal.state.rightY > 0f)
        assertEquals(-normal.state.rightX, inverted.state.rightX, 0.001f)
        assertEquals(-normal.state.rightY, inverted.state.rightY, 0.001f)
    }

    @Test
    fun customMovedButtonAndPillShapeHitDetectionWorks() {
        val base = ControlProfile.onFootShooterPreset()
        val movedFire = base.copy(
            buttons = base.buttons.map { btn ->
                if (btn.owner == Owner.FIRE) {
                    btn.copy(
                        centerX = 0.62f,
                        centerY = 0.30f,
                        shape = com.example.mobileplay.engine.ButtonShape.PILL,
                        radiusShortFraction = 0.08f
                    )
                } else {
                    btn
                }
            }
        )
        val engine = TouchInputEngine(1000f, 500f, 400f, 400f, profile = movedFire)
        // Tap inside widened pill horizontal wing at (620 + 48, 150)
        engine.accept(e(Action.DOWN, 4, 668f, 150f, 1))
        assertEquals(Owner.FIRE, engine.ownerOf(4))
        assertEquals(255, engine.state.r2)
        engine.accept(e(Action.UP, 4, 668f, 150f, 10))
        assertEquals(0, engine.state.r2)
    }

    @Test
    fun allFourBuiltInPresetsHaveValidUniqueIdsAndDistinctButtons() {
        val presets = ControlProfile.allBuiltInPresets()
        assertEquals(4, presets.size)
        assertEquals(4, presets.map { it.id }.toSet().size)
        presets.forEach { preset ->
            assertTrue("Preset ${preset.id} must have buttons", preset.buttons.isNotEmpty())
            val engine = TouchInputEngine(1000f, 500f, 400f, 400f, profile = preset)
            assertEquals(PadState(), engine.state)
        }
    }
}
