package com.example.mobileplay.engine

import kotlin.math.abs
import kotlin.math.pow

/**
 * Shape options for customizable touchscreen controls in the visual layout editor.
 */
enum class ButtonShape(val label: String) {
    CIRCLE("Circle"),
    ROUNDED_RECT("Rounded"),
    PILL("Pill"),
    OCTAGON("Octagon")
}

/**
 * Camera aiming calibration and behavior parameters.
 * Pure Kotlin — strictly validated against non-finite and out-of-range values.
 */
data class AimTuning(
    val speedForFullStickMmPerSec: Float = 350f,
    val horizontalSensitivity: Float = 1.0f,
    val verticalSensitivity: Float = 0.85f,
    val adsSensitivityMultiplier: Float = 0.55f,
    val antiDeadzone: Float = 0.12f,
    val curve: Float = 1.0f,
    val smoothingTimeMs: Float = 12f,
    val stopAfterMs: Long = 25L,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val fireDragAimEnabled: Boolean = true,
    val adsToggleMode: Boolean = false,
    val sprintToggleMode: Boolean = true,
    val crouchToggleMode: Boolean = true
) {
    init {
        require(speedForFullStickMmPerSec.isFinite() && speedForFullStickMmPerSec in 50f..1500f) {
            "speedForFullStickMmPerSec must be finite and in 50..1500 mm/s"
        }
        require(horizontalSensitivity.isFinite() && horizontalSensitivity in 0.1f..4.0f) {
            "horizontalSensitivity must be finite and in 0.1..4.0"
        }
        require(verticalSensitivity.isFinite() && verticalSensitivity in 0.1f..4.0f) {
            "verticalSensitivity must be finite and in 0.1..4.0"
        }
        require(adsSensitivityMultiplier.isFinite() && adsSensitivityMultiplier in 0.1f..1.5f) {
            "adsSensitivityMultiplier must be finite and in 0.1..1.5"
        }
        require(antiDeadzone.isFinite() && antiDeadzone in 0f..0.5f) {
            "antiDeadzone must be finite and in 0.0..0.5"
        }
        require(curve.isFinite() && curve in 0.25f..4.0f) {
            "curve must be finite and in 0.25..4.0"
        }
        require(smoothingTimeMs.isFinite() && smoothingTimeMs in 0f..100f) {
            "smoothingTimeMs must be finite and in 0..100 ms"
        }
        require(stopAfterMs in 1L..1000L) {
            "stopAfterMs must be in 1..1000 ms"
        }
    }

    /**
     * Evaluates the response curve for a normalized input speed [0..1] (with optional ADS scaling).
     * Shared by the input engine and the calibration curve visualizer.
     */
    fun evaluateMagnitude(normalizedSpeed: Float, isAds: Boolean = false): Float {
        val scaled = (abs(normalizedSpeed) * if (isAds) adsSensitivityMultiplier else 1f)
            .coerceIn(0f, 1f)
        if (scaled <= 0.002f) return 0f
        return (antiDeadzone + (1f - antiDeadzone) * scaled.pow(curve)).coerceIn(0f, 1f)
    }
}

/**
 * Editable button geometry shared between hit-testing in TouchInputEngine,
 * rendering in TouchOverlayView, and interactive editing in LayoutEditorScreen.
 * Stored in normalized [0..1] screen coordinates so layouts adapt across phone screen sizes.
 */
data class ControlButtonSpec(
    val id: String,
    val owner: Owner,
    val centerX: Float,
    val centerY: Float,
    val radiusShortFraction: Float,
    val label: String,
    val subLabel: String = "",
    val isAccent: Boolean = false,
    val shape: ButtonShape = ButtonShape.CIRCLE,
    val opacity: Float = 1.0f,
    val isRect: Boolean = false,
    val halfWidthFraction: Float = 0f,
    val halfHeightFraction: Float = 0f
) {
    val effectiveShape: ButtonShape
        get() = if (isRect && shape == ButtonShape.CIRCLE) ButtonShape.ROUNDED_RECT else shape

    companion object {
        fun defaultLabelsFor(owner: Owner): Pair<String, String> = when (owner) {
            Owner.FIRE -> "FIRE" to "R2"
            Owner.AIM -> "AIM" to "L2"
            Owner.JUMP -> "JUMP" to "×"
            Owner.CROUCH -> "CROUCH" to "○"
            Owner.SPRINT -> "SPRINT" to "L3"
            Owner.RELOAD -> "RELOAD" to "□"
            Owner.WEAPON_WHEEL -> "WHEEL" to "L1"
            Owner.INTERACT -> "USE" to "R1"
            Owner.TOUCHPAD -> "TOUCHPAD" to ""
            Owner.OPTIONS -> "OPT" to ""
            Owner.PS -> "PS" to ""
            Owner.ACCELERATE -> "GAS" to "R2"
            Owner.BRAKE -> "BRAKE" to "L2"
            Owner.HANDBRAKE -> "DRIFT" to "○"
            Owner.EXIT_VEHICLE -> "EXIT" to "△"
            else -> owner.name to ""
        }

        val reassignableOwners: List<Owner> = listOf(
            Owner.FIRE,
            Owner.AIM,
            Owner.JUMP,
            Owner.CROUCH,
            Owner.SPRINT,
            Owner.RELOAD,
            Owner.WEAPON_WHEEL,
            Owner.INTERACT,
            Owner.TOUCHPAD,
            Owner.OPTIONS,
            Owner.PS,
            Owner.ACCELERATE,
            Owner.BRAKE,
            Owner.HANDBRAKE,
            Owner.EXIT_VEHICLE
        )
    }
}

/**
 * Complete user-editable control profile serialized as JSON by ProfileManager.
 */
data class ControlProfile(
    val id: String,
    val name: String,
    val mode: ControlMode,
    val tuning: AimTuning = AimTuning(),
    val controlOpacity: Float = 0.72f,
    val movementSplitFraction: Float = 0.45f,
    val joystickCenterX: Float = 0.19f,
    val joystickCenterY: Float = 0.67f,
    val joystickRadiusFraction: Float = 0.15f,
    val cameraRegionStartX: Float = 0.45f,
    val cameraRegionTopY: Float = 0.14f,
    val cameraRegionBottomY: Float = 0.96f,
    val buttons: List<ControlButtonSpec> = layoutFor(mode, false),
    val isBuiltIn: Boolean = false,
    val showDiagnosticsHud: Boolean = false,
    val useLegacyOverlay: Boolean = false
) {
    init {
        require(controlOpacity.isFinite() && controlOpacity in 0.15f..1.0f) {
            "controlOpacity must be in 0.15..1.0"
        }
        require(movementSplitFraction.isFinite() && movementSplitFraction in 0.30f..0.60f) {
            "movementSplitFraction must be in 0.30..0.60"
        }
        require(joystickRadiusFraction.isFinite() && joystickRadiusFraction in 0.08f..0.24f) {
            "joystickRadiusFraction must be in 0.08..0.24"
        }
    }

    companion object {
        const val ID_ON_FOOT = "gta_on_foot"
        const val ID_DRIVING = "driving_mode"
        const val ID_GENERIC_SHOOTER = "generic_shooter"
        const val ID_STANDARD_CONTROLLER = "standard_controller"

        /** Preset A — GTA-Style On Foot */
        fun onFootShooterPreset(): ControlProfile = ControlProfile(
            id = ID_ON_FOOT,
            name = "GTA-Style On Foot",
            mode = ControlMode.ON_FOOT,
            tuning = AimTuning(
                speedForFullStickMmPerSec = 340f,
                horizontalSensitivity = 1.0f,
                verticalSensitivity = 0.85f,
                adsSensitivityMultiplier = 0.55f,
                antiDeadzone = 0.12f,
                curve = 1.15f,
                smoothingTimeMs = 10f,
                stopAfterMs = 25L,
                fireDragAimEnabled = true,
                adsToggleMode = false,
                sprintToggleMode = true,
                crouchToggleMode = true
            ),
            controlOpacity = 0.75f,
            movementSplitFraction = 0.45f,
            joystickCenterX = 0.19f,
            joystickCenterY = 0.67f,
            joystickRadiusFraction = 0.15f,
            cameraRegionStartX = 0.45f,
            buttons = gtaOnFootButtons(),
            isBuiltIn = true,
            showDiagnosticsHud = false,
            useLegacyOverlay = false
        )

        /** Preset B — Driving */
        fun drivingPreset(): ControlProfile = ControlProfile(
            id = ID_DRIVING,
            name = "Driving",
            mode = ControlMode.DRIVING,
            tuning = AimTuning(
                speedForFullStickMmPerSec = 380f,
                horizontalSensitivity = 0.9f,
                verticalSensitivity = 0.7f,
                adsSensitivityMultiplier = 0.6f,
                antiDeadzone = 0.10f,
                curve = 1.25f,
                smoothingTimeMs = 14f,
                stopAfterMs = 25L,
                fireDragAimEnabled = false,
                adsToggleMode = false,
                sprintToggleMode = false,
                crouchToggleMode = false
            ),
            controlOpacity = 0.78f,
            movementSplitFraction = 0.45f,
            joystickCenterX = 0.21f,
            joystickCenterY = 0.68f,
            joystickRadiusFraction = 0.16f,
            cameraRegionStartX = 0.45f,
            buttons = drivingButtons(),
            isBuiltIn = true,
            showDiagnosticsHud = false,
            useLegacyOverlay = false
        )

        /** Preset C — Generic Shooter (PUBG/BGMI streamlined FPS) */
        fun genericShooterPreset(): ControlProfile = ControlProfile(
            id = ID_GENERIC_SHOOTER,
            name = "Generic Shooter",
            mode = ControlMode.ON_FOOT,
            tuning = AimTuning(
                speedForFullStickMmPerSec = 310f,
                horizontalSensitivity = 1.15f,
                verticalSensitivity = 0.90f,
                adsSensitivityMultiplier = 0.50f,
                antiDeadzone = 0.10f,
                curve = 1.10f,
                smoothingTimeMs = 8f,
                stopAfterMs = 22L,
                fireDragAimEnabled = true,
                adsToggleMode = false,
                sprintToggleMode = true,
                crouchToggleMode = true
            ),
            controlOpacity = 0.72f,
            movementSplitFraction = 0.44f,
            joystickCenterX = 0.18f,
            joystickCenterY = 0.68f,
            joystickRadiusFraction = 0.145f,
            cameraRegionStartX = 0.44f,
            buttons = genericShooterButtons(),
            isBuiltIn = true,
            showDiagnosticsHud = false,
            useLegacyOverlay = false
        )

        /** Preset D — Standard Controller (Traditional virtual DualSense layout) */
        fun standardControllerPreset(): ControlProfile = ControlProfile(
            id = ID_STANDARD_CONTROLLER,
            name = "Standard DualSense",
            mode = ControlMode.ON_FOOT,
            tuning = AimTuning(
                speedForFullStickMmPerSec = 360f,
                horizontalSensitivity = 1.0f,
                verticalSensitivity = 1.0f,
                adsSensitivityMultiplier = 0.70f,
                antiDeadzone = 0.12f,
                curve = 1.0f,
                smoothingTimeMs = 10f,
                stopAfterMs = 25L,
                fireDragAimEnabled = false,
                adsToggleMode = false,
                sprintToggleMode = false,
                crouchToggleMode = false
            ),
            controlOpacity = 0.76f,
            movementSplitFraction = 0.48f,
            joystickCenterX = 0.19f,
            joystickCenterY = 0.66f,
            joystickRadiusFraction = 0.15f,
            cameraRegionStartX = 0.48f,
            buttons = standardControllerButtons(),
            isBuiltIn = true,
            showDiagnosticsHud = false,
            useLegacyOverlay = false
        )

        fun allBuiltInPresets(): List<ControlProfile> = listOf(
            onFootShooterPreset(),
            drivingPreset(),
            genericShooterPreset(),
            standardControllerPreset()
        )

        fun legacyThreeControlButtons(): List<ControlButtonSpec> = listOf(
            ControlButtonSpec(
                id = "legacy_fire",
                owner = Owner.FIRE,
                centerX = 0.85f,
                centerY = 0.72f,
                radiusShortFraction = 0.11f,
                label = "FIRE",
                subLabel = "R2",
                isAccent = true
            )
        )

        fun gtaOnFootButtons(): List<ControlButtonSpec> = listOf(
            ControlButtonSpec("btn_fire", Owner.FIRE, 0.86f, 0.74f, 0.115f, "FIRE", "R2", isAccent = true),
            ControlButtonSpec("btn_aim", Owner.AIM, 0.72f, 0.78f, 0.088f, "AIM", "L2"),
            ControlButtonSpec("btn_jump", Owner.JUMP, 0.93f, 0.50f, 0.072f, "JUMP", "×"),
            ControlButtonSpec("btn_crouch", Owner.CROUCH, 0.82f, 0.90f, 0.068f, "CROUCH", "○"),
            ControlButtonSpec("btn_reload", Owner.RELOAD, 0.74f, 0.58f, 0.066f, "RELOAD", "□"),
            ControlButtonSpec("btn_interact", Owner.INTERACT, 0.84f, 0.34f, 0.066f, "USE", "R1"),
            ControlButtonSpec("btn_wheel", Owner.WEAPON_WHEEL, 0.93f, 0.28f, 0.070f, "WHEEL", "L1", shape = ButtonShape.OCTAGON),
            ControlButtonSpec("btn_sprint", Owner.SPRINT, 0.11f, 0.34f, 0.068f, "SPRINT", "L3", shape = ButtonShape.PILL),
            ControlButtonSpec(
                id = "btn_touchpad",
                owner = Owner.TOUCHPAD,
                centerX = 0.50f,
                centerY = 0.11f,
                radiusShortFraction = 0.055f,
                label = "TOUCHPAD",
                shape = ButtonShape.ROUNDED_RECT,
                isRect = true,
                halfWidthFraction = 0.085f,
                halfHeightFraction = 0.046f
            ),
            ControlButtonSpec(
                id = "btn_ps",
                owner = Owner.PS,
                centerX = 0.37f,
                centerY = 0.11f,
                radiusShortFraction = 0.045f,
                label = "PS",
                shape = ButtonShape.PILL,
                isRect = true,
                halfWidthFraction = 0.036f,
                halfHeightFraction = 0.040f
            ),
            ControlButtonSpec(
                id = "btn_options",
                owner = Owner.OPTIONS,
                centerX = 0.63f,
                centerY = 0.11f,
                radiusShortFraction = 0.045f,
                label = "OPT",
                shape = ButtonShape.PILL,
                isRect = true,
                halfWidthFraction = 0.038f,
                halfHeightFraction = 0.040f
            )
        )

        fun drivingButtons(): List<ControlButtonSpec> = listOf(
            ControlButtonSpec("drv_gas", Owner.ACCELERATE, 0.88f, 0.74f, 0.115f, "GAS", "R2", isAccent = true, shape = ButtonShape.ROUNDED_RECT),
            ControlButtonSpec("drv_brake", Owner.BRAKE, 0.74f, 0.78f, 0.092f, "BRAKE", "L2", shape = ButtonShape.ROUNDED_RECT),
            ControlButtonSpec("drv_drift", Owner.HANDBRAKE, 0.86f, 0.46f, 0.075f, "DRIFT", "○"),
            ControlButtonSpec("drv_exit", Owner.EXIT_VEHICLE, 0.93f, 0.25f, 0.070f, "EXIT", "△"),
            ControlButtonSpec("drv_radio", Owner.INTERACT, 0.77f, 0.34f, 0.064f, "RADIO", "R1"),
            ControlButtonSpec(
                id = "drv_touchpad",
                owner = Owner.TOUCHPAD,
                centerX = 0.50f,
                centerY = 0.11f,
                radiusShortFraction = 0.055f,
                label = "TOUCHPAD",
                shape = ButtonShape.ROUNDED_RECT,
                isRect = true,
                halfWidthFraction = 0.085f,
                halfHeightFraction = 0.046f
            ),
            ControlButtonSpec(
                id = "drv_options",
                owner = Owner.OPTIONS,
                centerX = 0.63f,
                centerY = 0.11f,
                radiusShortFraction = 0.045f,
                label = "OPT",
                shape = ButtonShape.PILL,
                isRect = true,
                halfWidthFraction = 0.038f,
                halfHeightFraction = 0.040f
            )
        )

        fun genericShooterButtons(): List<ControlButtonSpec> = listOf(
            ControlButtonSpec("fps_fire", Owner.FIRE, 0.86f, 0.73f, 0.12f, "FIRE", "R2", isAccent = true),
            ControlButtonSpec("fps_ads", Owner.AIM, 0.72f, 0.75f, 0.090f, "ADS", "L2"),
            ControlButtonSpec("fps_jump", Owner.JUMP, 0.93f, 0.46f, 0.075f, "JUMP", "×"),
            ControlButtonSpec("fps_crouch", Owner.CROUCH, 0.82f, 0.90f, 0.070f, "CROUCH", "○"),
            ControlButtonSpec("fps_reload", Owner.RELOAD, 0.74f, 0.54f, 0.068f, "RELOAD", "□"),
            ControlButtonSpec("fps_sprint", Owner.SPRINT, 0.11f, 0.35f, 0.070f, "SPRINT", "L3", shape = ButtonShape.PILL)
        )

        fun standardControllerButtons(): List<ControlButtonSpec> = listOf(
            // Shoulder triggers
            ControlButtonSpec("ds_l2", Owner.AIM, 0.10f, 0.24f, 0.074f, "L2", "", shape = ButtonShape.PILL),
            ControlButtonSpec("ds_l1", Owner.WEAPON_WHEEL, 0.21f, 0.24f, 0.068f, "L1", "", shape = ButtonShape.PILL),
            ControlButtonSpec("ds_r1", Owner.INTERACT, 0.79f, 0.24f, 0.068f, "R1", "", shape = ButtonShape.PILL),
            ControlButtonSpec("ds_r2", Owner.FIRE, 0.90f, 0.24f, 0.074f, "R2", "", isAccent = true, shape = ButtonShape.PILL),
            // Face button diamond
            ControlButtonSpec("ds_triangle", Owner.EXIT_VEHICLE, 0.85f, 0.50f, 0.068f, "△", ""),
            ControlButtonSpec("ds_square", Owner.RELOAD, 0.77f, 0.64f, 0.068f, "□", ""),
            ControlButtonSpec("ds_circle", Owner.CROUCH, 0.93f, 0.64f, 0.068f, "○", ""),
            ControlButtonSpec("ds_cross", Owner.JUMP, 0.85f, 0.78f, 0.068f, "×", ""),
            // Stick click & system strip
            ControlButtonSpec("ds_l3", Owner.SPRINT, 0.32f, 0.84f, 0.060f, "L3", ""),
            ControlButtonSpec(
                id = "ds_touchpad",
                owner = Owner.TOUCHPAD,
                centerX = 0.50f,
                centerY = 0.12f,
                radiusShortFraction = 0.055f,
                label = "TOUCHPAD",
                shape = ButtonShape.ROUNDED_RECT,
                isRect = true,
                halfWidthFraction = 0.088f,
                halfHeightFraction = 0.048f
            ),
            ControlButtonSpec(
                id = "ds_ps",
                owner = Owner.PS,
                centerX = 0.37f,
                centerY = 0.12f,
                radiusShortFraction = 0.045f,
                label = "PS",
                shape = ButtonShape.PILL,
                isRect = true,
                halfWidthFraction = 0.036f,
                halfHeightFraction = 0.040f
            ),
            ControlButtonSpec(
                id = "ds_options",
                owner = Owner.OPTIONS,
                centerX = 0.63f,
                centerY = 0.12f,
                radiusShortFraction = 0.045f,
                label = "OPT",
                shape = ButtonShape.PILL,
                isRect = true,
                halfWidthFraction = 0.038f,
                halfHeightFraction = 0.040f
            )
        )

        fun layoutFor(mode: ControlMode, useLegacyOverlay: Boolean): List<ControlButtonSpec> {
            if (useLegacyOverlay) return legacyThreeControlButtons()
            return when (mode) {
                ControlMode.ON_FOOT -> gtaOnFootButtons()
                ControlMode.DRIVING -> drivingButtons()
            }
        }
    }
}
