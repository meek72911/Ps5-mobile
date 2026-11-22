package com.example.mobileplay.overlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import com.example.mobileplay.engine.AimTuning
import kotlin.math.exp
import kotlin.math.hypot

internal class PhoneGyro(
    context: Context,
    private val rotation: () -> Int,
    private val output: (Float, Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val available: Boolean get() = sensor != null
    private var registered = false
    private var active = false
    private var tuning = AimTuning()
    private var previousNs = 0L
    private var previousRotation = -1
    private var filteredX = 0f
    private var filteredY = 0f
    private var biasX = 0f
    private var biasY = 0f
    private var calibration: ((Boolean) -> Unit)? = null
    private var calibrationStartNs = 0L
    private var sumX = 0f
    private var sumY = 0f
    private var sampleCount = 0

    fun update(enabled: Boolean, tuning: AimTuning, windowAvailable: Boolean) {
        this.tuning = tuning
        if (!windowAvailable && calibration != null) finishCalibration(false)
        if (active != enabled) resetOutput()
        active = enabled
        setRegistered(windowAvailable && (enabled || calibration != null))
    }

    fun calibrate(result: (Boolean) -> Unit) {
        if (calibration != null) return
        calibration = result
        calibrationStartNs = SystemClock.elapsedRealtimeNanos()
        sumX = 0f
        sumY = 0f
        sampleCount = 0
        resetOutput()
        if (!setRegistered(true)) finishCalibration(false)
    }

    fun tick() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (calibration != null && nowNs - calibrationStartNs > 1_500_000_000L) {
            finishCalibration(false)
        }
        if (previousNs != 0L && nowNs - previousNs > 80_000_000L) resetOutput()
    }

    private fun setRegistered(value: Boolean): Boolean {
        if (registered && !value) {
            manager?.unregisterListener(this)
            registered = false
        } else if (!registered && value && sensor != null) {
            registered = manager?.registerListener(this, sensor, 10_000, 0) == true
        }
        return registered
    }

    private fun finishCalibration(success: Boolean) {
        val callback = calibration ?: return
        calibration = null
        if (success) {
            biasX = sumX / sampleCount
            biasY = sumY / sampleCount
        }
        setRegistered(active)
        callback(success)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rawX = event.values[0]
        val rawY = event.values[1]
        if (calibration != null) {
            if (hypot(hypot(rawX, rawY), event.values[2]) > 0.26f) {
                finishCalibration(false)
                return
            }
            sumX += rawX
            sumY += rawY
            sampleCount++
            if (sampleCount >= 12 && event.timestamp - calibrationStartNs >= 500_000_000L) {
                finishCalibration(true)
            }
            return
        }
        if (!active) return

        val orientation = rotation()
        val elapsed = event.timestamp - previousNs
        if (orientation != previousRotation || elapsed <= 0L || elapsed > 80_000_000L) {
            resetOutput()
            previousRotation = orientation
        }
        val x = rawX - biasX
        val y = rawY - biasY
        val horizontal = when (orientation) {
            Surface.ROTATION_90 -> -x
            Surface.ROTATION_180 -> -y
            Surface.ROTATION_270 -> x
            else -> y
        } * if (tuning.gyroInvertX) -1f else 1f
        val vertical = when (orientation) {
            Surface.ROTATION_90 -> y
            Surface.ROTATION_180 -> -x
            Surface.ROTATION_270 -> -y
            else -> x
        } * if (tuning.gyroInvertY) -1f else 1f
        val speed = hypot(horizontal, vertical)
        val response = ((speed - 0.035f) * tuning.gyroSensitivity / 1.57f).coerceIn(0f, 1f)
        val stickX = if (speed > 0f) horizontal / speed * response else 0f
        val stickY = if (speed > 0f) vertical / speed * response else 0f
        val alpha = if (previousNs == 0L || response == 0f) 1f
            else (1.0 - exp(-elapsed / 12_000_000.0)).toFloat()
        filteredX += (stickX - filteredX) * alpha
        filteredY += (stickY - filteredY) * alpha
        previousNs = event.timestamp
        output(filteredX, filteredY)
    }

    fun resetOutput() {
        previousNs = 0L
        filteredX = 0f
        filteredY = 0f
        output(0f, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
