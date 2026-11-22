package com.example.mobileplay.engine

/**
 * DualSense digital button bitmask constants modeled after libchiaki controller flags.
 * Pure Kotlin — zero Android SDK dependencies.
 */
object DualSenseButtons {
    const val NONE: Int = 0
    const val CROSS: Int = 1 shl 0       // Jump
    const val CIRCLE: Int = 1 shl 1      // Crouch / Handbrake
    const val SQUARE: Int = 1 shl 2      // Reload / Action
    const val TRIANGLE: Int = 1 shl 3    // Exit Vehicle / Switch Weapon
    const val L1: Int = 1 shl 4          // Weapon Wheel
    const val R1: Int = 1 shl 5          // Interact / Bumper
    const val L3: Int = 1 shl 6          // Sprint
    const val R3: Int = 1 shl 7          // Melee / Right Stick Click
    const val OPTIONS: Int = 1 shl 8     // Pause / Menu
    const val PS: Int = 1 shl 9          // PlayStation Home
    const val TOUCHPAD: Int = 1 shl 10   // Touchpad Click
    const val DPAD_LEFT: Int = 1 shl 11
    const val DPAD_RIGHT: Int = 1 shl 12
    const val DPAD_UP: Int = 1 shl 13
    const val DPAD_DOWN: Int = 1 shl 14
    const val CREATE: Int = 1 shl 15

    fun activeNames(mask: Int): List<String> = buildList {
        if (mask and CROSS != 0) add("CROSS")
        if (mask and CIRCLE != 0) add("CIRCLE")
        if (mask and SQUARE != 0) add("SQUARE")
        if (mask and TRIANGLE != 0) add("TRIANGLE")
        if (mask and L1 != 0) add("L1")
        if (mask and R1 != 0) add("R1")
        if (mask and L3 != 0) add("L3")
        if (mask and R3 != 0) add("R3")
        if (mask and OPTIONS != 0) add("OPTIONS")
        if (mask and PS != 0) add("PS")
        if (mask and TOUCHPAD != 0) add("TOUCHPAD")
        if (mask and DPAD_LEFT != 0) add("DPAD LEFT")
        if (mask and DPAD_RIGHT != 0) add("DPAD RIGHT")
        if (mask and DPAD_UP != 0) add("DPAD UP")
        if (mask and DPAD_DOWN != 0) add("DPAD DOWN")
        if (mask and CREATE != 0) add("CREATE")
    }
}

/**
 * Portable virtual DualSense controller snapshot.
 * Stick axes are normalized to [-1f, 1f]; analog triggers L2/R2 are [0, 255].
 */
data class PadState(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val l2: Int = 0,
    val r2: Int = 0,
    val buttons: Int = DualSenseButtons.NONE,
    val touchpad: TouchpadContact? = null
) {
    val isNeutral: Boolean
        get() = leftX == 0f && leftY == 0f && rightX == 0f && rightY == 0f &&
            l2 == 0 && r2 == 0 && buttons == DualSenseButtons.NONE && touchpad == null

    fun isPressed(buttonBit: Int): Boolean = (buttons and buttonBit) != 0
}

data class TouchpadContact(val x: Int, val y: Int) {
    companion object {
        const val MAX_X = 1919
        const val MAX_Y = 941
    }
}

enum class Action { DOWN, MOVE, UP, CANCEL }

enum class Owner {
    MOVEMENT,
    CAMERA,
    FIRE,
    AIM,
    JUMP,
    CROUCH,
    SPRINT,
    RELOAD,
    WEAPON_WHEEL,
    INTERACT,
    TOUCHPAD,
    OPTIONS,
    PS,
    STEER,
    ACCELERATE,
    BRAKE,
    HANDBRAKE,
    EXIT_VEHICLE,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_UP,
    DPAD_DOWN,
    CREATE,
    RIGHT_STICK_CLICK,
    RIGHT_STICK
}

internal fun Owner.isSystemButton(): Boolean =
    this == Owner.PS || this == Owner.OPTIONS || this == Owner.TOUCHPAD || this == Owner.CREATE

enum class ControlMode {
    ON_FOOT,
    DRIVING
}

data class TouchEvent(
    val action: Action,
    val id: Int,
    val xPx: Float,
    val yPx: Float,
    val timeNs: Long
)

data class PointerSnapshot(
    val id: Int,
    val owner: Owner,
    val xPx: Float,
    val yPx: Float,
    val originX: Float,
    val originY: Float,
    val ageMs: Long
)

data class FrameTimingStats(
    val lastEventIntervalMs: Float = 0f,
    val smoothedRateHz: Float = 0f,
    val totalEventsProcessed: Long = 0L,
    val watchdogZeroCount: Long = 0L
)
