package com.example.mobileplay.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin multi-touch to DualSense controller state engine.
 *
 * - Strict pointer-ID ownership (a finger retains its initial owner until UP/CANCEL)
 * - Supports >= 3 simultaneous fingers (move + aim + fire + face buttons)
 * - DPI-normalized H/V camera sensitivity, ADS multiplier, anti-deadzone, power response curve,
 *   invert X/Y, optional exponential smoothing, configurable camera swipe region, and zero-drift watchdog
 * - Fire-button drag aiming, Hold/Toggle states, Radial Weapon Wheel absolute-stick mode,
 *   Driving Mode, and user-customized normalized button layouts
 */
class TouchInputEngine(
    private val widthPx: Float,
    private val heightPx: Float,
    private val xdpi: Float,
    private val ydpi: Float,
    profile: ControlProfile = ControlProfile.onFootShooterPreset(),
    private val onState: (PadState) -> Unit = {}
) {
    init {
        require(widthPx.isFinite() && heightPx.isFinite() && widthPx > 0f && heightPx > 0f) {
            "Viewport dimensions must be positive finite values"
        }
        require(xdpi.isFinite() && ydpi.isFinite() && xdpi > 0f && ydpi > 0f) {
            "DPI values must be positive finite values"
        }
    }

    private data class Pointer(
        val id: Int,
        val owner: Owner,
        var x: Float,
        var y: Float,
        var previousNs: Long,
        val downNs: Long,
        val originX: Float,
        val originY: Float,
        val cameraSamples: ArrayDeque<CameraSample> = ArrayDeque()
    )

    private data class CameraSample(val x: Float, val y: Float, val timeNs: Long)

    var profile: ControlProfile = profile
        set(value) {
            val layoutStructureChanged = field.mode != value.mode ||
                field.useLegacyOverlay != value.useLegacyOverlay ||
                field.id != value.id
            field = value
            activeLayout = resolveActiveButtons(value)
            if (layoutStructureChanged) {
                clearAll()
            }
        }

    var tuning: AimTuning
        get() = profile.tuning
        set(value) {
            profile = profile.copy(tuning = value)
        }

    private var activeLayout: List<ControlButtonSpec> = resolveActiveButtons(profile)

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var touchpadContact: TouchpadContact? = null
    private var touchpadSwiped = false
    private var touchpadClickUntilNs = 0L
    var swipeSpeedMmPerSec = 0f
        private set
    private var lastCameraSampleNs = -1L
    private var lastEventNs = -1L

    private val holdCounts = HashMap<Owner, Int>()
    private var adsToggled = false
    private var sprintToggled = false
    private var crouchToggled = false

    private var current = PadState()
    private var timingStats = FrameTimingStats()

    val state: PadState get() = current
    val activePointers: Int get() = pointers.size
    val frameTiming: FrameTimingStats get() = timingStats
    val currentLayout: List<ControlButtonSpec> get() = activeLayout
    val aiming: Boolean get() = isOwnerActive(Owner.AIM) || (physicalAim && profile.mode == ControlMode.ON_FOOT)
    var physicalAim = false
        set(value) {
            if (field == value) return
            field = value
            publish()
        }
    var systemButtonsOnly = false
        set(value) {
            if (field == value) return
            field = value
            clearAll()
        }

    fun setGyroStick(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite() || (gyroX == x && gyroY == y)) return
        gyroX = x
        gyroY = y
        publish()
    }

    fun ownerOf(id: Int): Owner? = pointers[id]?.owner

    fun movementOriginPx(): Pair<Float, Float>? = pointers.values
        .firstOrNull { it.owner == Owner.MOVEMENT || it.owner == Owner.STEER }
        ?.let { it.originX to it.originY }

    fun weaponWheelOriginPx(): Pair<Float, Float>? = pointers.values
        .firstOrNull { it.owner == Owner.WEAPON_WHEEL }
        ?.let { it.originX to it.originY }

    fun isOwnerActive(owner: Owner): Boolean = when (owner) {
        Owner.AIM -> (holdCounts[Owner.AIM] ?: 0) > 0 || adsToggled
        Owner.SPRINT -> (holdCounts[Owner.SPRINT] ?: 0) > 0 || sprintToggled
        Owner.CROUCH -> (holdCounts[Owner.CROUCH] ?: 0) > 0 || crouchToggled
        else -> (holdCounts[owner] ?: 0) > 0 || pointers.values.any { it.owner == owner }
    }

    fun activePointerSnapshots(nowNs: Long): List<PointerSnapshot> = pointers.values.map { p ->
        PointerSnapshot(
            id = p.id,
            owner = p.owner,
            xPx = p.x,
            yPx = p.y,
            originX = p.originX,
            originY = p.originY,
            ageMs = max(0L, (nowNs - p.downNs) / 1_000_000L)
        )
    }

    fun accept(event: TouchEvent) {
        if (event.action != Action.CANCEL && (!event.xPx.isFinite() || !event.yPx.isFinite())) return
        recordTiming(event.timeNs)
        when (event.action) {
            Action.DOWN -> down(event)
            Action.MOVE -> move(event)
            Action.UP -> up(event)
            Action.CANCEL -> {
                clearAll()
                return
            }
        }
        publish()
    }

    private fun resolveActiveButtons(p: ControlProfile): List<ControlButtonSpec> =
        if (p.useLegacyOverlay) ControlProfile.legacyThreeControlButtons() else p.buttons

    private fun recordTiming(timeNs: Long) {
        if (timeNs < 0L) return
        val prev = lastEventNs
        val intervalMs = if (prev >= 0L && timeNs > prev) {
            ((timeNs - prev) / 1_000_000f).coerceAtMost(500f)
        } else {
            timingStats.lastEventIntervalMs
        }
        val instantHz = if (intervalMs > 0.05f) 1000f / intervalMs else timingStats.smoothedRateHz
        val smoothedHz = if (timingStats.smoothedRateHz == 0f) {
            instantHz
        } else {
            timingStats.smoothedRateHz * 0.85f + instantHz * 0.15f
        }
        lastEventNs = timeNs
        timingStats = timingStats.copy(
            lastEventIntervalMs = intervalMs,
            smoothedRateHz = smoothedHz,
            totalEventsProcessed = timingStats.totalEventsProcessed + 1L
        )
    }

    private fun down(e: TouchEvent) {
        if (e.id < 0 || e.timeNs < 0L || pointers.containsKey(e.id)) return
        if (pointers.size >= 10) return

        val owner = resolveOwner(e.xPx, e.yPx) ?: return

        val isSingleOwnerControl = owner == Owner.MOVEMENT ||
            owner == Owner.STEER ||
            owner == Owner.CAMERA ||
            owner == Owner.TOUCHPAD ||
            owner == Owner.RIGHT_STICK ||
            owner == Owner.WEAPON_WHEEL
        if (isSingleOwnerControl && pointers.values.any { it.owner == owner }) return

        pointers[e.id] = Pointer(
            id = e.id,
            owner = owner,
            x = e.xPx,
            y = e.yPx,
            previousNs = e.timeNs,
            downNs = e.timeNs,
            originX = e.xPx,
            originY = e.yPx
        )

        when (owner) {
            Owner.CAMERA -> {
                if (!isWeaponWheelActive()) {
                    rightX = 0f
                    rightY = 0f
                    lastCameraSampleNs = e.timeNs
                }
            }
            Owner.WEAPON_WHEEL -> {
                incrementHold(owner)
                rightX = 0f
                rightY = 0f
                lastCameraSampleNs = -1L
            }
            Owner.AIM -> {
                if (tuning.adsToggleMode) adsToggled = !adsToggled else incrementHold(owner)
            }
            Owner.SPRINT -> {
                if (tuning.sprintToggleMode) sprintToggled = !sprintToggled else incrementHold(owner)
            }
            Owner.CROUCH -> {
                if (tuning.crouchToggleMode) crouchToggled = !crouchToggled else incrementHold(owner)
            }
            Owner.TOUCHPAD -> {
                touchpadSwiped = false
                touchpadClickUntilNs = 0L
                touchpadContact = touchpadPosition(e.xPx, e.yPx, e.xPx, e.yPx)
            }
            Owner.MOVEMENT, Owner.STEER, Owner.RIGHT_STICK -> Unit
            else -> incrementHold(owner)
        }
    }

    private fun move(e: TouchEvent) {
        val p = pointers[e.id] ?: return
        if (e.timeNs <= p.previousNs) return
        val deltaNs = e.timeNs - p.previousNs

        when (p.owner) {
            Owner.MOVEMENT -> {
                val radius = min(widthPx, heightPx) * profile.joystickRadiusFraction
                val nx = (e.xPx - p.originX) / radius
                val ny = (e.yPx - p.originY) / radius
                val length = hypot(nx, ny)
                leftX = (if (length > 1f) nx / length else nx).coerceIn(-1f, 1f)
                leftY = (if (length > 1f) ny / length else ny).coerceIn(-1f, 1f)
            }
            Owner.STEER -> {
                val steerHalfWidth = min(widthPx, heightPx) * (profile.joystickRadiusFraction * 1.45f)
                leftX = ((e.xPx - p.originX) / steerHalfWidth).coerceIn(-1f, 1f)
                leftY = 0f
            }
            Owner.WEAPON_WHEEL -> {
                val wheelRadius = min(widthPx, heightPx) * 0.14f
                val nx = (e.xPx - p.originX) / wheelRadius
                val ny = (e.yPx - p.originY) / wheelRadius
                val length = hypot(nx, ny)
                rightX = (if (length > 1f) nx / length else nx).coerceIn(-1f, 1f)
                rightY = (if (length > 1f) ny / length else ny).coerceIn(-1f, 1f)
                lastCameraSampleNs = -1L
            }
            Owner.CAMERA -> {
                if (!isWeaponWheelActive() && pointers.values.none { it.owner == Owner.RIGHT_STICK }) {
                    applyCameraVelocity(p, e, deltaNs)
                }
            }
            Owner.RIGHT_STICK -> {
                if (!isWeaponWheelActive()) {
                    val radius = min(widthPx, heightPx) * 0.086f
                    val x = (e.xPx - p.originX) / radius
                    val y = (e.yPx - p.originY) / radius
                    val length = hypot(x, y).coerceAtLeast(1f)
                    rightX = x / length
                    rightY = y / length
                    lastCameraSampleNs = -1L
                }
            }
            Owner.TOUCHPAD -> {
                touchpadSwiped = touchpadSwiped || touchpadExceededSlop(p, e)
                touchpadContact = touchpadPosition(e.xPx, e.yPx, p.originX, p.originY)
            }
            Owner.FIRE -> {
                val hasDedicatedCamera = pointers.values.any { it.owner == Owner.CAMERA }
                if (tuning.fireDragAimEnabled && !hasDedicatedCamera && !isWeaponWheelActive() &&
                    pointers.values.none { it.owner == Owner.RIGHT_STICK }
                ) {
                    applyCameraVelocity(p, e, deltaNs)
                }
            }
            else -> Unit
        }

        p.x = e.xPx
        p.y = e.yPx
        p.previousNs = e.timeNs
    }

    private fun applyCameraVelocity(pointer: Pointer, event: TouchEvent, deltaNs: Long) {
        if (deltaNs <= 0L) return
        val samples = pointer.cameraSamples
        if (deltaNs >= 80_000_000L) samples.clear()
        if (samples.isEmpty()) samples.add(CameraSample(pointer.x, pointer.y, pointer.previousNs))
        samples.add(CameraSample(event.xPx, event.yPx, event.timeNs))
        while (samples.size > 2 && event.timeNs - samples[1].timeNs >= 20_000_000L) samples.removeFirst()
        val start = samples.first()
        val seconds = (event.timeNs - start.timeNs) / 1_000_000_000.0f
        val vxMm = ((event.xPx - start.x) / xdpi) * 25.4f / seconds
        val vyMm = ((event.yPx - start.y) / ydpi) * 25.4f / seconds
        swipeSpeedMmPerSec = hypot(vxMm, vyMm)
        val x = vxMm * tuning.horizontalSensitivity
        val y = vyMm * tuning.verticalSensitivity
        val speed = hypot(x, y)
        val hipMagnitude = tuning.evaluateMagnitude(speed / tuning.speedForFullStickMmPerSec)
        val rawX = if (speed > 0f) (x / speed) * hipMagnitude * (if (aiming) tuning.adsSensitivityMultiplier else 1f) else 0f
        val rawY = if (speed > 0f) (y / speed) * hipMagnitude * (if (aiming) tuning.verticalAdsMultiplier else 1f) else 0f
        val magnitude = hypot(rawX, rawY)
        val cap = magnitude.coerceAtLeast(1f)
        val targetX = (if (tuning.invertX) -rawX else rawX) / cap
        val targetY = (if (tuning.invertY) -rawY else rawY) / cap
        val dtMs = deltaNs / 1_000_000.0f
        // Keep smoothing for fine aim while letting a strong swipe reach full stick quickly.
        val smoothingMs = tuning.smoothingTimeMs * (1f - 0.75f * magnitude.coerceAtMost(1f))
        val alpha = if (smoothingMs <= 0f) {
            1f
        } else {
            (1.0 - exp((-dtMs / smoothingMs).toDouble())).toFloat().coerceIn(0f, 1f)
        }
        rightX = (rightX + (targetX - rightX) * alpha).coerceIn(-1f, 1f)
        rightY = (rightY + (targetY - rightY) * alpha).coerceIn(-1f, 1f)
        lastCameraSampleNs = event.timeNs
    }

    private fun up(event: TouchEvent) {
        val p = pointers.remove(event.id) ?: return
        when (p.owner) {
            Owner.MOVEMENT, Owner.STEER -> {
                leftX = 0f
                leftY = 0f
            }
            Owner.CAMERA -> {
                if (!isWeaponWheelActive() && pointers.values.none { it.owner == Owner.RIGHT_STICK }) {
                    rightX = 0f
                    rightY = 0f
                    lastCameraSampleNs = -1L
                }
            }
            Owner.RIGHT_STICK -> {
                if (!isWeaponWheelActive()) {
                    rightX = 0f
                    rightY = 0f
                    lastCameraSampleNs = -1L
                }
            }
            Owner.WEAPON_WHEEL -> {
                decrementHold(Owner.WEAPON_WHEEL)
                if (!isWeaponWheelActive()) {
                    rightX = 0f
                    rightY = 0f
                    lastCameraSampleNs = -1L
                }
            }
            Owner.FIRE -> {
                decrementHold(Owner.FIRE)
                val hasCamera = pointers.values.any { it.owner == Owner.CAMERA }
                val hasAnotherFire = (holdCounts[Owner.FIRE] ?: 0) > 0
                if (!hasCamera && !hasAnotherFire && !isWeaponWheelActive()) {
                    rightX = 0f
                    rightY = 0f
                    lastCameraSampleNs = -1L
                }
            }
            Owner.AIM -> if (!tuning.adsToggleMode) decrementHold(Owner.AIM)
            Owner.SPRINT -> if (!tuning.sprintToggleMode) decrementHold(Owner.SPRINT)
            Owner.CROUCH -> if (!tuning.crouchToggleMode) decrementHold(Owner.CROUCH)
            Owner.TOUCHPAD -> {
                touchpadContact = null
                if (!touchpadSwiped && !touchpadExceededSlop(p, event)) {
                    touchpadClickUntilNs = event.timeNs + 45_000_000L
                }
            }
            else -> decrementHold(p.owner)
        }
    }

    private fun touchpadExceededSlop(pointer: Pointer, event: TouchEvent): Boolean =
        hypot((event.xPx - pointer.originX) / xdpi, (event.yPx - pointer.originY) / ydpi) * 25.4f >= 1.8f

    private fun touchpadPosition(x: Float, y: Float, originX: Float, originY: Float): TouchpadContact? {
        val button = activeLayout.firstOrNull {
            it.owner == Owner.TOUCHPAD && hitsButton(it, originX, originY)
        } ?: return null
        val radius = min(widthPx, heightPx) * button.radiusShortFraction
        val halfWidth = if (button.isRect && button.halfWidthFraction > 0f) widthPx * button.halfWidthFraction else radius
        val halfHeight = if (button.isRect && button.halfHeightFraction > 0f) heightPx * button.halfHeightFraction else radius
        val relativeX = ((x - widthPx * button.centerX) / (2f * halfWidth) + 0.5f).coerceIn(0f, 1f)
        val relativeY = ((y - heightPx * button.centerY) / (2f * halfHeight) + 0.5f).coerceIn(0f, 1f)
        return TouchpadContact(
            (relativeX * TouchpadContact.MAX_X).roundToInt(),
            (relativeY * TouchpadContact.MAX_Y).roundToInt()
        )
    }

    fun tick(nowNs: Long) {
        if (touchpadClickUntilNs > 0L && nowNs >= touchpadClickUntilNs) {
            touchpadClickUntilNs = 0L
            publish()
        }
        if (isWeaponWheelActive()) return
        val last = lastCameraSampleNs
        if (last >= 0L && nowNs > last && (nowNs - last) / 1_000_000L >= tuning.stopAfterMs) {
            if (rightX != 0f || rightY != 0f) {
                rightX = 0f
                rightY = 0f
                timingStats = timingStats.copy(
                    watchdogZeroCount = timingStats.watchdogZeroCount + 1L
                )
                publish()
            }
            lastCameraSampleNs = -1L
            swipeSpeedMmPerSec = 0f
        }
    }

    fun clearAll() {
        pointers.clear()
        holdCounts.clear()
        adsToggled = false
        sprintToggled = false
        crouchToggled = false
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        gyroX = 0f
        gyroY = 0f
        touchpadContact = null
        touchpadSwiped = false
        touchpadClickUntilNs = 0L
        swipeSpeedMmPerSec = 0f
        lastCameraSampleNs = -1L
        publish()
    }

    private fun resolveOwner(xPx: Float, yPx: Float): Owner? {
        // Explicit buttons always win over background joystick/camera zones.
        for (spec in activeLayout) {
            if ((!systemButtonsOnly || spec.owner.isSystemButton()) && hitsButton(spec, xPx, yPx)) return spec.owner
        }
        if (systemButtonsOnly) return null
        val splitX = widthPx * profile.cameraRegionStartX
        if (xPx < splitX) {
            return if (profile.mode == ControlMode.DRIVING) Owner.STEER else Owner.MOVEMENT
        }
        val yNorm = yPx / heightPx
        if (yNorm in profile.cameraRegionTopY..profile.cameraRegionBottomY || profile.useLegacyOverlay) {
            return Owner.CAMERA
        }
        return null
    }

    private fun hitsButton(spec: ControlButtonSpec, xPx: Float, yPx: Float): Boolean {
        val cx = widthPx * spec.centerX
        val cy = heightPx * spec.centerY
        return if (spec.isRect && spec.halfWidthFraction > 0f && spec.halfHeightFraction > 0f) {
            val hw = widthPx * spec.halfWidthFraction
            val hh = heightPx * spec.halfHeightFraction
            abs(xPx - cx) <= hw && abs(yPx - cy) <= hh
        } else {
            val radius = min(widthPx, heightPx) * spec.radiusShortFraction
            when (spec.effectiveShape) {
                ButtonShape.PILL -> {
                    abs(xPx - cx) <= radius * 1.25f && abs(yPx - cy) <= radius * 0.78f
                }
                ButtonShape.ROUNDED_RECT -> {
                    abs(xPx - cx) <= radius && abs(yPx - cy) <= radius
                }
                ButtonShape.CIRCLE, ButtonShape.OCTAGON -> {
                    hypot(xPx - cx, yPx - cy) <= radius
                }
            }
        }
    }

    private fun isWeaponWheelActive(): Boolean = (holdCounts[Owner.WEAPON_WHEEL] ?: 0) > 0

    private fun incrementHold(owner: Owner) {
        holdCounts[owner] = (holdCounts[owner] ?: 0) + 1
    }

    private fun decrementHold(owner: Owner) {
        val next = max(0, (holdCounts[owner] ?: 0) - 1)
        if (next == 0) holdCounts.remove(owner) else holdCounts[owner] = next
    }

    private fun buildButtonMask(): Int {
        var mask = DualSenseButtons.NONE
        if (isOwnerActive(Owner.JUMP)) mask = mask or DualSenseButtons.CROSS
        if (isOwnerActive(Owner.CROUCH) || isOwnerActive(Owner.HANDBRAKE)) {
            mask = mask or DualSenseButtons.CIRCLE
        }
        if (isOwnerActive(Owner.RELOAD)) mask = mask or DualSenseButtons.SQUARE
        if (isOwnerActive(Owner.EXIT_VEHICLE)) mask = mask or DualSenseButtons.TRIANGLE
        if (isOwnerActive(Owner.WEAPON_WHEEL)) mask = mask or DualSenseButtons.L1
        if (isOwnerActive(Owner.INTERACT)) mask = mask or DualSenseButtons.R1
        if (isOwnerActive(Owner.SPRINT)) mask = mask or DualSenseButtons.L3
        if (isOwnerActive(Owner.OPTIONS)) mask = mask or DualSenseButtons.OPTIONS
        if (isOwnerActive(Owner.PS)) mask = mask or DualSenseButtons.PS
        if (touchpadClickUntilNs > 0L) mask = mask or DualSenseButtons.TOUCHPAD
        if (isOwnerActive(Owner.DPAD_LEFT)) mask = mask or DualSenseButtons.DPAD_LEFT
        if (isOwnerActive(Owner.DPAD_RIGHT)) mask = mask or DualSenseButtons.DPAD_RIGHT
        if (isOwnerActive(Owner.DPAD_UP)) mask = mask or DualSenseButtons.DPAD_UP
        if (isOwnerActive(Owner.DPAD_DOWN)) mask = mask or DualSenseButtons.DPAD_DOWN
        if (isOwnerActive(Owner.CREATE)) mask = mask or DualSenseButtons.CREATE
        if (isOwnerActive(Owner.RIGHT_STICK_CLICK)) mask = mask or DualSenseButtons.R3
        return mask
    }

    private fun publish() {
        val r2Active = isOwnerActive(Owner.FIRE) || isOwnerActive(Owner.ACCELERATE)
        val l2Active = isOwnerActive(Owner.AIM) || isOwnerActive(Owner.BRAKE)
        val gyroActive = !isWeaponWheelActive() && !isOwnerActive(Owner.RIGHT_STICK) && when (tuning.gyroMode) {
            GyroMode.OFF -> false
            GyroMode.WHILE_AIMING -> profile.mode != ControlMode.DRIVING && aiming
            GyroMode.ALWAYS -> true
        }
        val x = rightX + if (gyroActive) gyroX else 0f
        val y = rightY + if (gyroActive) gyroY else 0f
        val length = hypot(x, y).coerceAtLeast(1f)
        val next = PadState(
            leftX = leftX,
            leftY = leftY,
            rightX = x / length,
            rightY = y / length,
            l2 = if (l2Active) 255 else 0,
            r2 = if (r2Active) 255 else 0,
            buttons = buildButtonMask(),
            touchpad = touchpadContact
        )
        if (next != current) {
            current = next
            onState(next)
        }
    }
}
