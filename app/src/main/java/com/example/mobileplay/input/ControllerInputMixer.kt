package com.example.mobileplay.input

import com.example.mobileplay.engine.PadState
import kotlin.math.hypot
import kotlin.math.pow

internal class ControllerInputMixer {
    private val gamepads = linkedMapOf<Int, PadState>()
    var touch = PadState()
    val physicalAim: Boolean get() = gamepads.values.any { it.l2 >= 16 }

    fun updateGamepad(deviceId: Int, state: PadState) {
        if (state.isNeutral) gamepads.remove(deviceId) else gamepads[deviceId] = state
    }

    fun removeGamepad(deviceId: Int) {
        gamepads.remove(deviceId)
    }

    fun clear() {
        touch = PadState()
        gamepads.clear()
    }

    fun current(): PadState {
        if (gamepads.isEmpty()) return touch
        val left = gamepads.values.maxBy { it.leftX * it.leftX + it.leftY * it.leftY }
        val right = gamepads.values.maxBy { it.rightX * it.rightX + it.rightY * it.rightY }
        val usesPhysicalLeft = left.leftX != 0f || left.leftY != 0f
        val usesPhysicalRight = right.rightX != 0f || right.rightY != 0f
        var buttons = touch.buttons
        var l2 = touch.l2
        var r2 = touch.r2
        for (gamepad in gamepads.values) {
            buttons = buttons or gamepad.buttons
            l2 = maxOf(l2, gamepad.l2)
            r2 = maxOf(r2, gamepad.r2)
        }
        return PadState(
            leftX = if (usesPhysicalLeft) left.leftX else touch.leftX,
            leftY = if (usesPhysicalLeft) left.leftY else touch.leftY,
            rightX = if (usesPhysicalRight) right.rightX else touch.rightX,
            rightY = if (usesPhysicalRight) right.rightY else touch.rightY,
            l2 = l2,
            r2 = r2,
            buttons = buttons,
            touchpad = touch.touchpad
        )
    }
}

internal fun normalizeStick(x: Float, y: Float, deadzone: Float, curve: Float = 1f): Pair<Float, Float> {
    if (!x.isFinite() || !y.isFinite()) return 0f to 0f
    val radius = hypot(x, y)
    if (radius <= deadzone) return 0f to 0f
    val magnitude = ((radius - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
    val factor = (if (curve == 1f) magnitude else magnitude.pow(curve)) / radius
    return x * factor to y * factor
}

data class GamepadControls(
    val deviceNames: List<String> = emptyList(),
    val hideTouchControls: Boolean = true,
    val tuning: GamepadTuning = GamepadTuning()
) {
    val showSystemButtonsOnly: Boolean get() = hideTouchControls && deviceNames.isNotEmpty()

    companion object {
        const val DEFAULT_DEADZONE = 0.12f
        const val MAX_DEADZONE = 0.30f
    }
}

data class GamepadTuning(
    val leftDeadzone: Float = GamepadControls.DEFAULT_DEADZONE,
    val rightDeadzone: Float = GamepadControls.DEFAULT_DEADZONE,
    val rightCurve: Float = 1f
)

internal data class GamepadDevice(val descriptor: String, val name: String)

internal data class GamepadRawSample(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float
) {
    val leftRadius: Float get() = hypot(leftX, leftY)
    val rightRadius: Float get() = hypot(rightX, rightY)
}
