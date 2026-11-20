package com.example.mobileplay.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        val originY: Float
    )

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
            Action.UP -> up(event.id)
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
            Owner.MOVEMENT, Owner.STEER -> Unit
            else -> incrementHold(owner)
        }
    }

    private fun move(e: TouchEvent) {
        val p = pointers[e.id] ?: return
        if (e.timeNs <= p.previousNs) return
        val deltaNs = e.timeNs - p.previousNs
        val dx = e.xPx - p.x
        val dy = e.yPx - p.y

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
                if (!isWeaponWheelActive()) {
                    applyCameraVelocity(dx, dy, deltaNs, e.timeNs)
                }
            }
            Owner.FIRE -> {
                val hasDedicatedCamera = pointers.values.any { it.owner == Owner.CAMERA }
                if (tuning.fireDragAimEnabled && !hasDedicatedCamera && !isWeaponWheelActive()) {
                    applyCameraVelocity(dx, dy, deltaNs, e.timeNs)
                }
            }
            else -> Unit
        }

        p.x = e.xPx
        p.y = e.yPx
        p.previousNs = e.timeNs
    }

    private fun applyCameraVelocity(dxPx: Float, dyPx: Float, deltaNs: Long, eventTimeNs: Long) {
        val seconds = deltaNs / 1_000_000_000.0f
        if (seconds <= 0f) return
        val vxMm = (dxPx / xdpi) * 25.4f / seconds
        val vyMm = (dyPx / ydpi) * 25.4f / seconds
        val isAds = isOwnerActive(Owner.AIM)
        val rawX = mappedAxis(vxMm * tuning.horizontalSensitivity, isAds)
        val rawY = mappedAxis(vyMm * tuning.verticalSensitivity, isAds)
        val targetX = if (tuning.invertX) -rawX else rawX
        val targetY = if (tuning.invertY) -rawY else rawY
        val dtMs = deltaNs / 1_000_000.0f
        val alpha = if (tuning.smoothingTimeMs <= 0f) {
            1f
        } else {
            (1.0 - exp((-dtMs / tuning.smoothingTimeMs).toDouble())).toFloat().coerceIn(0f, 1f)
        }
        rightX = (rightX + (targetX - rightX) * alpha).coerceIn(-1f, 1f)
        rightY = (rightY + (targetY - rightY) * alpha).coerceIn(-1f, 1f)
        lastCameraSampleNs = eventTimeNs
    }

    private fun mappedAxis(vMmPerSec: Float, isAds: Boolean): Float {
        val normalized = abs(vMmPerSec) / tuning.speedForFullStickMmPerSec
        val output = tuning.evaluateMagnitude(normalized, isAds)
        if (output == 0f) return 0f
        return if (vMmPerSec < 0f) -output else output
    }

    private fun up(id: Int) {
        val p = pointers.remove(id) ?: return
        when (p.owner) {
            Owner.MOVEMENT, Owner.STEER -> {
                leftX = 0f
                leftY = 0f
            }
            Owner.CAMERA -> {
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
            else -> decrementHold(p.owner)
        }
    }

    fun tick(nowNs: Long) {
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
        lastCameraSampleNs = -1L
        publish()
    }

    private fun resolveOwner(xPx: Float, yPx: Float): Owner? {
        // Explicit buttons always win over background joystick/camera zones.
        for (spec in activeLayout) {
            if (hitsButton(spec, xPx, yPx)) return spec.owner
        }
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
        if (isOwnerActive(Owner.TOUCHPAD)) mask = mask or DualSenseButtons.TOUCHPAD
        return mask
    }

    private fun publish() {
        val r2Active = isOwnerActive(Owner.FIRE) || isOwnerActive(Owner.ACCELERATE)
        val l2Active = isOwnerActive(Owner.AIM) || isOwnerActive(Owner.BRAKE)
        val next = PadState(
            leftX = leftX,
            leftY = leftY,
            rightX = rightX,
            rightY = rightY,
            l2 = if (l2Active) 255 else 0,
            r2 = if (r2Active) 255 else 0,
            buttons = buildButtonMask()
        )
        if (next != current) {
            current = next
            onState(next)
        }
    }
}
