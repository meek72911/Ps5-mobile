package com.example.mobileplay.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.PadState
import kotlin.math.abs
import kotlin.math.roundToInt

internal class AndroidGamepadInput(
    context: Context,
    private val inputGeneration: () -> Long,
    private val tuningFor: (String) -> GamepadTuning,
    private val captureRaw: () -> Boolean,
    private val onRawSample: (String, GamepadRawSample) -> Unit,
    private val onState: (Int, PadState) -> Unit,
    private val onRemoved: (Int) -> Unit,
    private val onDevices: (List<GamepadDevice>) -> Unit
) : InputManager.InputDeviceListener {
    private data class DeviceState(
        val pressedKeys: MutableSet<Int> = mutableSetOf(),
        var axes: PadState = PadState()
    )

    private val manager = context.getSystemService(InputManager::class.java)
    private val states = mutableMapOf<Int, DeviceState>()
    private var generation = -1L
    private var registered = false

    fun start() {
        if (registered) return
        manager?.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        registered = manager != null
        publishDevices()
    }

    fun stop() {
        if (registered) manager?.unregisterInputDeviceListener(this)
        registered = false
        states.clear()
    }

    override fun onInputDeviceAdded(deviceId: Int) = publishDevices()

    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDeviceRemoved(deviceId)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        states.remove(deviceId)
        onRemoved(deviceId)
        publishDevices()
    }

    private fun publishDevices() {
        onDevices((manager?.inputDeviceIds ?: intArrayOf()).asIterable().mapNotNull { id ->
            manager?.getInputDevice(id)?.takeIf(::isController)?.let { GamepadDevice(it.descriptor, it.name) }
        })
    }

    fun handleKey(event: KeyEvent): Boolean {
        val device = event.device?.takeIf(::isController) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val key = event.keyCode
        if (buttonForKey(key) == 0 && key != KeyEvent.KEYCODE_BUTTON_L2 && key != KeyEvent.KEYCODE_BUTTON_R2) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount != 0) return true
        val state = stateFor(device.id)
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
            state.pressedKeys.add(key)
        } else {
            state.pressedKeys.remove(key)
        }
        publishState(device, state)
        return true
    }

    fun handleMotion(event: MotionEvent): Boolean {
        val device = event.device?.takeIf(::isController) ?: return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return false
        }

        fun axis(primary: Int, alternate: Int? = null): AxisValue? =
            readAxis(event, device, primary) ?: alternate?.let { readAxis(event, device, it) }
        val leftX = axis(MotionEvent.AXIS_X)
        val leftY = axis(MotionEvent.AXIS_Y)
        val rightX = axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        val rightY = axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        val tuning = tuningFor(device.descriptor)
        val lx = leftX?.signed ?: 0f
        val ly = leftY?.signed ?: 0f
        val rx = rightX?.signed ?: 0f
        val ry = rightY?.signed ?: 0f
        if (captureRaw()) onRawSample(device.descriptor, GamepadRawSample(lx, ly, rx, ry))
        val leftDeadzone = maxOf(tuning.leftDeadzone, leftX?.flat ?: 0f, leftY?.flat ?: 0f).coerceAtMost(0.95f)
        val rightDeadzone = maxOf(tuning.rightDeadzone, rightX?.flat ?: 0f, rightY?.flat ?: 0f).coerceAtMost(0.95f)
        val left = normalizeStick(lx, ly, leftDeadzone)
        val right = normalizeStick(rx, ry, rightDeadzone, tuning.rightCurve)
        val hatX = axis(MotionEvent.AXIS_HAT_X)?.signed ?: 0f
        val hatY = axis(MotionEvent.AXIS_HAT_Y)?.signed ?: 0f
        var hat = 0
        if (hatX <= -0.5f) hat = hat or DualSenseButtons.DPAD_LEFT
        if (hatX >= 0.5f) hat = hat or DualSenseButtons.DPAD_RIGHT
        if (hatY <= -0.5f) hat = hat or DualSenseButtons.DPAD_UP
        if (hatY >= 0.5f) hat = hat or DualSenseButtons.DPAD_DOWN

        val state = stateFor(device.id)
        state.axes = PadState(
            leftX = left.first,
            leftY = left.second,
            rightX = right.first,
            rightY = right.second,
            l2 = axis(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)?.trigger ?: 0,
            r2 = axis(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)?.trigger ?: 0,
            buttons = hat
        )
        publishState(device, state)
        return true
    }

    private fun stateFor(deviceId: Int): DeviceState {
        val nextGeneration = inputGeneration()
        if (nextGeneration != generation) {
            states.clear()
            generation = nextGeneration
        }
        return states.getOrPut(deviceId, ::DeviceState)
    }

    private fun publishState(device: InputDevice, state: DeviceState) {
        var buttons = state.axes.buttons
        for (key in state.pressedKeys) buttons = buttons or buttonForKey(key)
        val hasAnalogL2 = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null ||
            device.getMotionRange(MotionEvent.AXIS_BRAKE) != null
        val hasAnalogR2 = device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null ||
            device.getMotionRange(MotionEvent.AXIS_GAS) != null
        onState(device.id, state.axes.copy(
            buttons = buttons,
            l2 = if (!hasAnalogL2 && KeyEvent.KEYCODE_BUTTON_L2 in state.pressedKeys) 255 else state.axes.l2,
            r2 = if (!hasAnalogR2 && KeyEvent.KEYCODE_BUTTON_R2 in state.pressedKeys) 255 else state.axes.r2
        ))
    }

    private fun buttonForKey(key: Int): Int = when (key) {
        KeyEvent.KEYCODE_BUTTON_A -> DualSenseButtons.CROSS
        KeyEvent.KEYCODE_BUTTON_B -> DualSenseButtons.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> DualSenseButtons.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> DualSenseButtons.TRIANGLE
        KeyEvent.KEYCODE_BUTTON_L1 -> DualSenseButtons.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> DualSenseButtons.R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> DualSenseButtons.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> DualSenseButtons.R3
        KeyEvent.KEYCODE_BUTTON_START -> DualSenseButtons.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> DualSenseButtons.CREATE
        KeyEvent.KEYCODE_BUTTON_MODE -> DualSenseButtons.PS
        KeyEvent.KEYCODE_DPAD_LEFT -> DualSenseButtons.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DualSenseButtons.DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> DualSenseButtons.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DualSenseButtons.DPAD_DOWN
        else -> 0
    }

    private fun readAxis(event: MotionEvent, device: InputDevice, axis: Int): AxisValue? {
        val range = device.getMotionRange(axis, event.source) ?: device.getMotionRange(axis) ?: return null
        val value = event.getAxisValue(axis)
        return if (value.isFinite() && range.range > 0f) AxisValue(value, range) else null
    }

    private data class AxisValue(val value: Float, val range: InputDevice.MotionRange) {
        val signed: Float
            get() = if (range.min < 0f && range.max > 0f) {
                (value / if (value < 0f) -range.min else range.max).coerceIn(-1f, 1f)
            } else {
                ((value - range.min) / range.range * 2f - 1f).coerceIn(-1f, 1f)
            }
        val flat: Float
            get() = abs(range.flat / maxOf(abs(range.min), abs(range.max)))
        val trigger: Int
            get() = (((value - range.min) / range.range).coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun isController(device: InputDevice): Boolean =
        !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
            device.supportsSource(InputDevice.SOURCE_JOYSTICK))
}
