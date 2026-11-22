package com.example.mobileplay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.mobileplay.engine.Action
import com.example.mobileplay.engine.ButtonShape
import com.example.mobileplay.engine.ControlButtonSpec
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.FrameTimingStats
import com.example.mobileplay.engine.GyroMode
import com.example.mobileplay.engine.Owner
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.engine.PointerSnapshot
import com.example.mobileplay.engine.TouchEvent
import com.example.mobileplay.engine.TouchInputEngine
import com.example.mobileplay.engine.isSystemButton
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class OverlayTelemetry(
    val padState: PadState = PadState(),
    val pointers: List<PointerSnapshot> = emptyList(),
    val timing: FrameTimingStats = FrameTimingStats(),
    val swipeSpeedMmPerSec: Float = 0f,
    val aiming: Boolean = false
)

/**
 * Custom Android View dedicated to low-latency multi-touch DualSense input and translucent HUD rendering.
 * Renders user-customized button positions, sizes, shapes, opacities, joystick geometry, and camera swipe region.
 */
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.space_grotesk)
        }.getOrNull() ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        }.getOrNull() ?: Typeface.MONOSPACE
    }

    private val rectScratch = RectF()
    private val octagonPath = Path()
    private var engine: TouchInputEngine? = null
    private var currentProfile: ControlProfile = ControlProfile.onFootShooterPreset()
    private var currentState: PadState = PadState()
    private var running = false
    private var lastTelemetryDispatchMs = 0L
    private val gyro = PhoneGyro(context, { display?.rotation ?: 0 }) { x, y -> engine?.setGyroStick(x, y) }
    val hasGyroscope: Boolean get() = gyro.available
    var physicalAim = false
        set(value) {
            if (field == value) return
            field = value
            engine?.physicalAim = value
        }
    var systemButtonsOnly = false
        set(value) {
            if (field == value) return
            field = value
            engine?.systemButtonsOnly = value
            invalidate()
        }
    var gyroGameplayActive = false
        set(value) {
            if (field == value) return
            field = value
            updateGyro()
        }

    var onPadStateChanged: ((PadState) -> Unit)? = null
    var onTelemetryUpdated: ((OverlayTelemetry) -> Unit)? = null

    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!running) return
            val nowNs = SystemClock.uptimeMillis() * 1_000_000L
            engine?.tick(nowNs)
            gyro.tick()
            dispatchTelemetryIfNeeded(nowNs, force = false)
            postDelayed(this, 16L)
        }
    }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "MobilePlay multi-touch controller overlay"
    }

    fun updateProfile(profile: ControlProfile) {
        val changed = profile != currentProfile
        currentProfile = profile
        engine?.profile = profile
        if (changed) updateGyro()
        if (changed) {
            postInvalidateOnAnimation()
        }
    }

    fun clearInput() {
        engine?.clearAll()
        gyro.resetOutput()
        currentState = PadState()
        dispatchTelemetryIfNeeded(SystemClock.uptimeMillis() * 1_000_000L, force = true)
        invalidate()
    }

    fun currentEngineState(): PadState = engine?.state ?: currentState

    fun calibrateGyro(onResult: (Boolean) -> Unit) = gyro.calibrate(onResult)

    private fun updateGyro() {
        val foreground = running && hasWindowFocus() && windowVisibility == VISIBLE
        val tuning = currentProfile.tuning
        gyro.update(foreground && gyroGameplayActive && tuning.gyroMode != GyroMode.OFF, tuning, foreground)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateGyro()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateGyro()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine?.clearAll()
        if (w > 0 && h > 0) {
            engine = TouchInputEngine(
                widthPx = w.toFloat(),
                heightPx = h.toFloat(),
                xdpi = resources.displayMetrics.xdpi.coerceAtLeast(1f),
                ydpi = resources.displayMetrics.ydpi.coerceAtLeast(1f),
                profile = currentProfile
            ) { next ->
                currentState = next
                onPadStateChanged?.invoke(next)
                postInvalidateOnAnimation()
            }
            engine?.systemButtonsOnly = systemButtonsOnly
            engine?.physicalAim = physicalAim
            dispatchTelemetryIfNeeded(SystemClock.uptimeMillis() * 1_000_000L, force = true)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        updateGyro()
        post(watchdogTick)
    }

    override fun onDetachedFromWindow() {
        running = false
        updateGyro()
        removeCallbacks(watchdogTick)
        clearInput()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sink = engine ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                emitPointer(sink, event, event.actionIndex, Action.DOWN, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    val timeNs = event.getHistoricalEventTime(h) * 1_000_000L
                    for (p in 0 until event.pointerCount) {
                        sink.accept(
                            TouchEvent(
                                action = Action.MOVE,
                                id = event.getPointerId(p),
                                xPx = event.getHistoricalX(p, h),
                                yPx = event.getHistoricalY(p, h),
                                timeNs = timeNs
                            )
                        )
                    }
                }
                for (p in 0 until event.pointerCount) {
                    emitPointer(sink, event, p, Action.MOVE, event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                emitPointer(sink, event, event.actionIndex, Action.UP, event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                sink.accept(TouchEvent(Action.CANCEL, -1, 0f, 0f, event.eventTime * 1_000_000L))
            }
        }
        dispatchTelemetryIfNeeded(event.eventTime * 1_000_000L, force = true)
        postInvalidateOnAnimation()
        return true
    }

    private fun emitPointer(
        sink: TouchInputEngine,
        event: MotionEvent,
        index: Int,
        action: Action,
        timeMs: Long
    ) {
        if (index !in 0 until event.pointerCount) return
        sink.accept(
            TouchEvent(
                action = action,
                id = event.getPointerId(index),
                xPx = event.getX(index),
                yPx = event.getY(index),
                timeNs = timeMs * 1_000_000L
            )
        )
    }

    private fun dispatchTelemetryIfNeeded(nowNs: Long, force: Boolean) {
        val listener = onTelemetryUpdated ?: return
        val nowMs = nowNs / 1_000_000L
        if (!force && (nowMs - lastTelemetryDispatchMs) < 48L) return
        lastTelemetryDispatchMs = nowMs
        val currentEngine = engine ?: return
        listener.invoke(
            OverlayTelemetry(
                padState = currentEngine.state,
                pointers = currentEngine.activePointerSnapshots(nowNs),
                timing = currentEngine.frameTiming,
                swipeSpeedMmPerSec = currentEngine.swipeSpeedMmPerSec,
                aiming = currentEngine.aiming
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val shortEdge = min(w, h)
        val masterAlpha = currentProfile.controlOpacity.coerceIn(0.15f, 1.0f)
        val layout = engine?.currentLayout
            ?: if (currentProfile.useLegacyOverlay) ControlProfile.legacyThreeControlButtons() else currentProfile.buttons
        if (systemButtonsOnly) {
            for (spec in layout) {
                if (spec.owner.isSystemButton()) {
                    drawButton(canvas, spec, w, h, shortEdge, masterAlpha * spec.opacity.coerceIn(0.2f, 1.0f))
                }
            }
            return
        }
        val splitX = w * currentProfile.cameraRegionStartX

        // Subtle zone divider & camera aim indicator
        strokePaint.strokeWidth = 1.8f
        strokePaint.color = withAlpha(COLOR_CYAN, 0.12f * masterAlpha)
        canvas.drawLine(splitX, h * currentProfile.cameraRegionTopY, splitX, h * currentProfile.cameraRegionBottomY, strokePaint)

        drawCameraZoneHint(canvas, w, h, shortEdge, splitX, masterAlpha)

        if (currentProfile.mode == ControlMode.DRIVING && !currentProfile.useLegacyOverlay) {
            drawDrivingSteeringPad(canvas, w, h, shortEdge, masterAlpha)
        } else {
            drawFloatingJoystick(canvas, w, h, shortEdge, masterAlpha)
        }

        for (spec in layout) {
            drawButton(canvas, spec, w, h, shortEdge, masterAlpha * spec.opacity.coerceIn(0.2f, 1.0f))
        }

        val wheelOrigin = engine?.weaponWheelOriginPx()
        if (wheelOrigin != null) {
            drawRadialWeaponWheel(canvas, wheelOrigin.first, wheelOrigin.second, shortEdge)
        }

        val nowNs = SystemClock.uptimeMillis() * 1_000_000L
        val snapshots = engine?.activePointerSnapshots(nowNs).orEmpty()
        for (p in snapshots) {
            drawPointerIndicator(canvas, p, shortEdge)
        }

        if (currentProfile.showDiagnosticsHud) {
            drawCompactDiagnosticsStrip(canvas, w, h, shortEdge, snapshots.size)
        }
    }

    private fun drawCameraZoneHint(
        canvas: Canvas,
        w: Float,
        h: Float,
        shortEdge: Float,
        splitX: Float,
        alphaScale: Float
    ) {
        val rightActive = currentState.rightX != 0f || currentState.rightY != 0f
        val cx = splitX + (w - splitX) * 0.42f
        val cy = h * ((currentProfile.cameraRegionTopY + currentProfile.cameraRegionBottomY) * 0.46f)
        val reticleRadius = shortEdge * 0.052f

        strokePaint.strokeWidth = 2.2f
        strokePaint.color = withAlpha(
            COLOR_CYAN,
            (if (rightActive) 0.62f else 0.16f) * alphaScale
        )
        canvas.drawCircle(cx, cy, reticleRadius, strokePaint)

        if (rightActive) {
            val vx = cx + currentState.rightX * reticleRadius
            val vy = cy + currentState.rightY * reticleRadius
            fillPaint.color = withAlpha(COLOR_CYAN, 0.88f)
            canvas.drawCircle(vx, vy, shortEdge * 0.013f, fillPaint)
            canvas.drawLine(cx, cy, vx, vy, strokePaint)
        }

        textPaint.textSize = shortEdge * 0.024f
        textPaint.color = withAlpha(COLOR_TEXT_MUTED, 0.48f * alphaScale)
        val label = if (currentState.l2 > 0 && currentProfile.mode == ControlMode.ON_FOOT) {
            "ADS AIM"
        } else {
            "CAMERA"
        }
        canvas.drawText(label, cx, cy + reticleRadius + shortEdge * 0.038f, textPaint)
    }

    private fun drawFloatingJoystick(
        canvas: Canvas,
        w: Float,
        h: Float,
        shortEdge: Float,
        alphaScale: Float
    ) {
        val anchor = engine?.movementOriginPx()
        val active = anchor != null
        val cx = anchor?.first ?: (w * currentProfile.joystickCenterX)
        val cy = anchor?.second ?: (h * currentProfile.joystickCenterY)
        val outerR = shortEdge * currentProfile.joystickRadiusFraction
        val knobR = outerR * 0.37f

        fillPaint.color = withAlpha(COLOR_SURFACE_FILL, (if (active) 0.46f else 0.24f) * alphaScale)
        canvas.drawCircle(cx, cy, outerR, fillPaint)

        strokePaint.strokeWidth = if (active) 3.2f else 2.0f
        strokePaint.color = withAlpha(COLOR_CYAN, (if (active) 0.88f else 0.40f) * alphaScale)
        canvas.drawCircle(cx, cy, outerR, strokePaint)

        strokePaint.strokeWidth = 1.4f
        strokePaint.color = withAlpha(COLOR_CYAN, 0.18f * alphaScale)
        canvas.drawLine(cx - outerR * 0.55f, cy, cx + outerR * 0.55f, cy, strokePaint)
        canvas.drawLine(cx, cy - outerR * 0.55f, cx, cy + outerR * 0.55f, strokePaint)

        val kx = cx + currentState.leftX * (outerR - knobR * 0.55f)
        val ky = cy + currentState.leftY * (outerR - knobR * 0.55f)
        fillPaint.color = withAlpha(
            if (active) COLOR_CYAN else COLOR_TEXT_MUTED,
            (if (active) 0.72f else 0.34f) * alphaScale
        )
        canvas.drawCircle(kx, ky, knobR, fillPaint)

        strokePaint.strokeWidth = 2.4f
        strokePaint.color = withAlpha(COLOR_TEXT_PRIMARY, (if (active) 0.92f else 0.48f) * alphaScale)
        canvas.drawCircle(kx, ky, knobR, strokePaint)

        if (!active) {
            textPaint.textSize = shortEdge * 0.026f
            textPaint.color = withAlpha(COLOR_TEXT_MUTED, 0.72f * alphaScale)
            canvas.drawText("MOVE", cx, cy + outerR + shortEdge * 0.044f, textPaint)
        }
    }

    private fun drawDrivingSteeringPad(
        canvas: Canvas,
        w: Float,
        h: Float,
        shortEdge: Float,
        alphaScale: Float
    ) {
        val anchor = engine?.movementOriginPx()
        val active = anchor != null
        val cx = anchor?.first ?: (w * currentProfile.joystickCenterX)
        val cy = anchor?.second ?: (h * currentProfile.joystickCenterY)
        val halfW = shortEdge * (currentProfile.joystickRadiusFraction * 1.45f)
        val halfH = shortEdge * (currentProfile.joystickRadiusFraction * 0.50f)

        rectScratch.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        fillPaint.color = withAlpha(COLOR_SURFACE_FILL, (if (active) 0.52f else 0.28f) * alphaScale)
        canvas.drawRoundRect(rectScratch, halfH, halfH, fillPaint)

        strokePaint.strokeWidth = if (active) 3.2f else 2.0f
        strokePaint.color = withAlpha(COLOR_CYAN, (if (active) 0.90f else 0.45f) * alphaScale)
        canvas.drawRoundRect(rectScratch, halfH, halfH, strokePaint)

        strokePaint.strokeWidth = 2f
        canvas.drawLine(cx, cy - halfH * 0.52f, cx, cy + halfH * 0.52f, strokePaint)

        val puckX = cx + currentState.leftX * (halfW - halfH * 0.7f)
        fillPaint.color = withAlpha(COLOR_CYAN, (if (active) 0.82f else 0.38f) * alphaScale)
        canvas.drawCircle(puckX, cy, halfH * 0.68f, fillPaint)
        strokePaint.strokeWidth = 2.4f
        strokePaint.color = withAlpha(COLOR_TEXT_PRIMARY, 0.9f * alphaScale)
        canvas.drawCircle(puckX, cy, halfH * 0.68f, strokePaint)

        textPaint.textSize = shortEdge * 0.026f
        textPaint.color = withAlpha(COLOR_TEXT_MUTED, 0.75f * alphaScale)
        canvas.drawText("ANALOG STEER", cx, cy + halfH + shortEdge * 0.044f, textPaint)
    }

    private fun drawButton(
        canvas: Canvas,
        spec: ControlButtonSpec,
        w: Float,
        h: Float,
        shortEdge: Float,
        alphaScale: Float
    ) {
        val active = engine?.isOwnerActive(spec.owner) == true
        val cx = w * spec.centerX
        val cy = h * spec.centerY
        val baseAccent = if (spec.isAccent) COLOR_FIRE else COLOR_CYAN

        val fillColor = when {
            active -> withAlpha(baseAccent, (0.68f * alphaScale).coerceAtLeast(0.35f))
            spec.isAccent -> withAlpha(baseAccent, 0.20f * alphaScale)
            else -> withAlpha(COLOR_SURFACE_FILL, 0.44f * alphaScale)
        }
        val strokeColor = withAlpha(
            if (active) COLOR_TEXT_PRIMARY else baseAccent,
            (if (active) 0.95f else 0.56f) * alphaScale
        )
        strokePaint.strokeWidth = if (active) 3.4f else 2.2f

        val radius = shortEdge * spec.radiusShortFraction
        if (spec.isRect && spec.halfWidthFraction > 0f && spec.halfHeightFraction > 0f) {
            val hw = w * spec.halfWidthFraction
            val hh = h * spec.halfHeightFraction
            val corner = if (spec.effectiveShape == ButtonShape.PILL) hh else shortEdge * 0.018f
            rectScratch.set(cx - hw, cy - hh, cx + hw, cy + hh)
            fillPaint.color = fillColor
            canvas.drawRoundRect(rectScratch, corner, corner, fillPaint)
            strokePaint.color = strokeColor
            canvas.drawRoundRect(rectScratch, corner, corner, strokePaint)
        } else {
            when (spec.effectiveShape) {
                ButtonShape.CIRCLE -> {
                    fillPaint.color = fillColor
                    canvas.drawCircle(cx, cy, radius, fillPaint)
                    strokePaint.color = strokeColor
                    canvas.drawCircle(cx, cy, radius, strokePaint)
                }
                ButtonShape.ROUNDED_RECT -> {
                    val corner = radius * 0.34f
                    rectScratch.set(cx - radius, cy - radius, cx + radius, cy + radius)
                    fillPaint.color = fillColor
                    canvas.drawRoundRect(rectScratch, corner, corner, fillPaint)
                    strokePaint.color = strokeColor
                    canvas.drawRoundRect(rectScratch, corner, corner, strokePaint)
                }
                ButtonShape.PILL -> {
                    val hw = radius * 1.25f
                    val hh = radius * 0.78f
                    rectScratch.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    fillPaint.color = fillColor
                    canvas.drawRoundRect(rectScratch, hh, hh, fillPaint)
                    strokePaint.color = strokeColor
                    canvas.drawRoundRect(rectScratch, hh, hh, strokePaint)
                }
                ButtonShape.OCTAGON -> {
                    buildOctagonPath(cx, cy, radius)
                    fillPaint.color = fillColor
                    canvas.drawPath(octagonPath, fillPaint)
                    strokePaint.color = strokeColor
                    canvas.drawPath(octagonPath, strokePaint)
                }
            }
        }

        // Labels
        textPaint.textSize = (radius * 0.34f).coerceIn(shortEdge * 0.023f, shortEdge * 0.040f)
        textPaint.color = withAlpha(COLOR_TEXT_PRIMARY, (if (active) 1f else 0.88f) * alphaScale)
        val labelOffsetY = if (spec.subLabel.isNotEmpty() && !spec.isRect) {
            -radius * 0.06f
        } else {
            textPaint.textSize * 0.34f
        }
        canvas.drawText(spec.label, cx, cy + labelOffsetY, textPaint)

        if (spec.subLabel.isNotEmpty() && !spec.isRect) {
            textPaint.textSize = (radius * 0.28f).coerceIn(shortEdge * 0.019f, shortEdge * 0.030f)
            textPaint.color = withAlpha(
                if (active) COLOR_TEXT_PRIMARY else baseAccent,
                (if (active) 0.95f else 0.82f) * alphaScale
            )
            canvas.drawText(spec.subLabel, cx, cy + radius * 0.42f, textPaint)
        }
    }

    private fun buildOctagonPath(cx: Float, cy: Float, radius: Float) {
        octagonPath.reset()
        for (i in 0 until 8) {
            val angle = Math.toRadians(22.5 + i * 45.0)
            val px = cx + (cos(angle) * radius).toFloat()
            val py = cy + (sin(angle) * radius).toFloat()
            if (i == 0) octagonPath.moveTo(px, py) else octagonPath.lineTo(px, py)
        }
        octagonPath.close()
    }

    private fun drawRadialWeaponWheel(canvas: Canvas, cx: Float, cy: Float, shortEdge: Float) {
        val radius = shortEdge * 0.14f
        fillPaint.color = withAlpha(COLOR_BG_PANEL, 0.84f)
        canvas.drawCircle(cx, cy, radius, fillPaint)

        strokePaint.strokeWidth = 3f
        strokePaint.color = withAlpha(COLOR_CYAN, 0.92f)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        strokePaint.strokeWidth = 1.4f
        strokePaint.color = withAlpha(COLOR_CYAN, 0.35f)
        for (i in 0 until 8) {
            val angle = i * Math.PI / 4.0
            canvas.drawLine(
                cx, cy,
                cx + (cos(angle) * radius).toFloat(),
                cy + (sin(angle) * radius).toFloat(),
                strokePaint
            )
        }

        val px = cx + currentState.rightX * radius * 0.85f
        val py = cy + currentState.rightY * radius * 0.85f
        fillPaint.color = COLOR_CYAN
        canvas.drawCircle(px, py, shortEdge * 0.024f, fillPaint)
    }

    private fun drawPointerIndicator(canvas: Canvas, pointer: PointerSnapshot, shortEdge: Float) {
        val ringR = shortEdge * 0.030f
        val color = if (pointer.owner == Owner.FIRE || pointer.owner == Owner.ACCELERATE) COLOR_FIRE else COLOR_CYAN
        strokePaint.strokeWidth = 2.4f
        strokePaint.color = withAlpha(color, 0.85f)
        canvas.drawCircle(pointer.xPx, pointer.yPx, ringR, strokePaint)
    }

    private fun drawCompactDiagnosticsStrip(
        canvas: Canvas,
        w: Float,
        h: Float,
        shortEdge: Float,
        activePointerCount: Int
    ) {
        val barWidth = w * 0.42f
        val barHeight = shortEdge * 0.092f
        val left = (w - barWidth) * 0.5f
        val top = h - barHeight - shortEdge * 0.024f
        rectScratch.set(left, top, left + barWidth, top + barHeight)

        fillPaint.color = withAlpha(COLOR_BG_PANEL, 0.80f)
        canvas.drawRoundRect(rectScratch, 14f, 14f, fillPaint)
        strokePaint.strokeWidth = 1.5f
        strokePaint.color = withAlpha(COLOR_CYAN, 0.35f)
        canvas.drawRoundRect(rectScratch, 14f, 14f, strokePaint)

        monoPaint.textSize = shortEdge * 0.024f
        monoPaint.color = COLOR_TEXT_PRIMARY
        val padX = left + 16f
        val lText = "L(${fmt(currentState.leftX)},${fmt(currentState.leftY)})  " +
            "R(${fmt(currentState.rightX)},${fmt(currentState.rightY)})  " +
            "L2:${currentState.l2} R2:${currentState.r2}"
        canvas.drawText(lText, padX, top + barHeight * 0.44f, monoPaint)

        val activeBtns = DualSenseButtons.activeNames(currentState.buttons)
            .take(4)
            .joinToString("+")
            .ifEmpty { "NONE" }
        val hz = engine?.frameTiming?.smoothedRateHz?.roundToInt() ?: 0
        monoPaint.color = COLOR_CYAN
        canvas.drawText("PTR:$activePointerCount  ${hz}Hz  BTN:$activeBtns", padX, top + barHeight * 0.82f, monoPaint)
    }

    private fun fmt(v: Float): String = "%+.2f".format(v)

    private fun withAlpha(rgbColor: Int, fraction: Float): Int {
        val alpha = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()
        return (rgbColor and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {
        private const val COLOR_BG_PANEL = 0xFF0B0F14.toInt()
        private const val COLOR_SURFACE_FILL = 0xFF151B23.toInt()
        private const val COLOR_CYAN = 0xFF4DD8E8.toInt()
        private const val COLOR_FIRE = 0xFFFF6478.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFF5F7FA.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF94A3B8.toInt()
    }
}
