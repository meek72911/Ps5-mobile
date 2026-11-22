package com.example.mobileplay

import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.Action
import com.example.mobileplay.engine.ControlButtonSpec
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.Owner
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.engine.TouchEvent
import com.example.mobileplay.engine.TouchInputEngine
import com.example.mobileplay.engine.TouchpadContact
import com.example.mobileplay.input.ControllerInputMixer
import com.example.mobileplay.input.GamepadControls
import com.example.mobileplay.input.normalizeStick
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerInputMixerTest {
    @Test
    fun connectedControllerHidesGameplayTouchesUntilDisconnectOrOptOut() {
        val connected = GamepadControls(deviceNames = listOf("DualSense"))
        assertTrue(connected.showSystemButtonsOnly)
        assertFalse(connected.copy(deviceNames = emptyList()).showSystemButtonsOnly)
        assertFalse(connected.copy(hideTouchControls = false).showSystemButtonsOnly)

        val profile = ControlProfile.standardControllerPreset()
        val engine = TouchInputEngine(1000f, 500f, 400f, 400f, profile)
        val cross = profile.buttons.first { it.owner == Owner.JUMP }
        val ps = profile.buttons.first { it.owner == Owner.PS }
        fun press(button: ControlButtonSpec, id: Int) =
            engine.accept(TouchEvent(Action.DOWN, id, button.centerX * 1000f, button.centerY * 500f, id * 1_000_000L))

        engine.systemButtonsOnly = connected.showSystemButtonsOnly
        press(cross, 1)
        assertEquals(PadState(), engine.state)
        press(ps, 2)
        assertTrue(engine.state.isPressed(DualSenseButtons.PS))

        engine.systemButtonsOnly = connected.copy(deviceNames = emptyList()).showSystemButtonsOnly
        assertEquals(PadState(), engine.state)
        press(cross, 3)
        assertTrue(engine.state.isPressed(DualSenseButtons.CROSS))
    }

    @Test
    fun responseCurveAdjustsFineMotionAndPreservesFullTravel() {
        val linear = normalizeStick(0.5f, 0f, 0.06f, curve = 1f).first
        assertTrue(normalizeStick(0.5f, 0f, 0.06f, curve = 0.65f).first > linear)
        assertTrue(normalizeStick(0.5f, 0f, 0.06f, curve = 1.6f).first < linear)
        assertEquals(1f, normalizeStick(1f, 0f, 0.06f, curve = 1.6f).first, 0.001f)
        assertEquals(0f to 0f, normalizeStick(0.04f, 0.01f, 0.06f, curve = 0.65f))
    }

    @Test
    fun physicalInputPreservesTouchAndFallsBackToGyroWhenCentered() {
        val mixer = ControllerInputMixer().apply {
            touch = PadState(leftX = 0.75f, rightY = 0.25f, r2 = 64, buttons = DualSenseButtons.PS)
            updateGamepad(7, PadState(rightX = 0.5f, l2 = 128, r2 = 48, buttons = DualSenseButtons.TRIANGLE))
        }
        assertEquals(PadState(
            leftX = 0.75f,
            rightX = 0.5f,
            l2 = 128,
            r2 = 64,
            buttons = DualSenseButtons.PS or DualSenseButtons.TRIANGLE
        ), mixer.current())
        assertTrue(mixer.physicalAim)
        mixer.removeGamepad(7)
        assertEquals(mixer.touch, mixer.current())
        assertFalse(mixer.physicalAim)
    }

    @Test
    fun removingOneControllerDoesNotClearAnotherController() {
        val mixer = ControllerInputMixer()
        mixer.updateGamepad(1, PadState(r2 = 255, buttons = DualSenseButtons.TRIANGLE))
        mixer.updateGamepad(2, PadState(leftY = 0.5f, buttons = DualSenseButtons.CROSS))
        mixer.removeGamepad(1)
        assertEquals(PadState(leftY = 0.5f, buttons = DualSenseButtons.CROSS), mixer.current())
        mixer.clear()
        assertTrue(mixer.current().isNeutral)
    }

    @Test
    fun joystickDeadzoneAndCircularLimitAreApplied() {
        assertEquals(0f to 0f, normalizeStick(0.08f, -0.03f, 0.12f))
        val (x, y) = normalizeStick(1f, -1f, 0.12f)
        assertEquals(1f, hypot(x, y), 0.001f)
        assertEquals(x, -y, 0.001f)
        val (halfX, halfY) = normalizeStick(0.56f, 0f, 0.12f)
        assertEquals(0.5f, halfX, 0.001f)
        assertEquals(0f, halfY, 0.001f)
    }

    @Test
    fun swipeUpdatesTouchpadContactWithoutClickAndReleasesIt() {
        val engine = TouchInputEngine(1000f, 500f, 400f, 400f, ControlProfile.standardControllerPreset())
        engine.accept(TouchEvent(Action.DOWN, 1, 443f, 60f, 1_000_000L))
        val start = requireNotNull(engine.state.touchpad)
        engine.accept(TouchEvent(Action.MOVE, 1, 650f, 60f, 20_000_000L))
        val moved = requireNotNull(engine.state.touchpad)
        assertTrue(moved.x > start.x)
        assertEquals(TouchpadContact.MAX_X, moved.x)
        assertFalse(engine.state.isPressed(DualSenseButtons.TOUCHPAD))
        engine.accept(TouchEvent(Action.UP, 1, 650f, 60f, 30_000_000L))
        assertTrue(engine.state.isNeutral)
    }

    @Test
    fun shortTouchpadTapClicksAndAutomaticallyReleases() {
        val engine = TouchInputEngine(1000f, 500f, 400f, 400f, ControlProfile.standardControllerPreset())
        engine.accept(TouchEvent(Action.DOWN, 1, 500f, 60f, 1_000_000L))
        assertFalse(engine.state.isPressed(DualSenseButtons.TOUCHPAD))
        engine.accept(TouchEvent(Action.UP, 1, 503f, 60f, 20_000_000L))
        assertEquals(null, engine.state.touchpad)
        assertTrue(engine.state.isPressed(DualSenseButtons.TOUCHPAD))
        engine.tick(66_000_000L)
        assertTrue(engine.state.isNeutral)
    }
}
