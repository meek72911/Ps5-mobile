package com.example.mobileplay.remoteplay

import com.example.BuildConfig
import com.example.mobileplay.engine.DualSenseButtons
import com.example.mobileplay.engine.PadState
import kotlin.math.roundToInt

/**
 * Callback interface invoked from native libchiaki threads via mobileplay_chiaki_jni.cpp.
 * Method names and JVM signatures MUST remain exact for JNI lookup.
 */
interface ChiakiStreamCallback {
    fun onVideoPacket(data: ByteArray, ptsUs: Long)
    fun onAudioHeader(channels: Int, sampleRateHz: Int)
    fun onAudioPcm(samples: ShortArray)
    fun onSessionEvent(eventType: Int, reason: String)
}

/**
 * Callback interface invoked synchronously during nativeRegisterConsole() via mobileplay_chiaki_jni.cpp.
 * Method names and JVM signatures MUST remain exact for JNI lookup.
 */
interface ChiakiRegistCallback {
    fun onRegistSuccess(
        serverNickname: String,
        serverMac: String,
        rpRegistKey: ByteArray,
        rpKey: ByteArray,
        rpKeyType: Int
    )

    fun onRegistError(errorCode: Int, message: String)
}

/**
 * Authentic credentials returned by a real PlayStation 5 PIN registration handshake via libchiaki.
 */
data class ChiakiRegistrationResult(
    val serverNickname: String,
    val serverMac: String,
    val rpRegistKey: ByteArray,
    val rpKey: ByteArray,
    val rpKeyType: Int
) {
    fun clearSensitiveBytes() {
        rpRegistKey.fill(0)
        rpKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChiakiRegistrationResult) return false
        return serverNickname == other.serverNickname &&
            serverMac == other.serverMac &&
            rpRegistKey.contentEquals(other.rpRegistKey) &&
            rpKey.contentEquals(other.rpKey) &&
            rpKeyType == other.rpKeyType
    }

    override fun hashCode(): Int {
        var result = serverNickname.hashCode()
        result = 31 * result + serverMac.hashCode()
        result = 31 * result + rpRegistKey.contentHashCode()
        result = 31 * result + rpKey.contentHashCode()
        result = 31 * result + rpKeyType
        return result
    }
}

/**
 * Exact Kotlin-side JNI contract matching app/src/main/cpp/mobileplay_chiaki_jni.cpp
 * and upstream chiaki-ng (pinned v1.10.1 / commit 7ea0b0c).
 */
object ChiakiNativeBridge {
    const val REQUIRED_REGIST_KEY_BYTES = 16
    const val REQUIRED_RP_KEY_BYTES = 16
    const val REQUIRED_PSN_ACCOUNT_ID_BYTES = 8
    const val CHIAKI_EVENT_QUIT = 0

    const val ERR_NATIVE_UNAVAILABLE = -1
    const val ERR_ALREADY_ACTIVE = -2
    const val ERR_INVALID_ARGS = -3
    const val ERR_ZERO_CREDENTIALS = -4
    const val ERR_CALLBACK_LOOKUP = -5
    const val ERR_REGIST_TIMEOUT = -6
    const val ERR_REGIST_FAILED = -7

    val isLibraryLoaded: Boolean = runCatching {
        System.loadLibrary("chiaki_jni")
        true
    }.getOrDefault(false)

    val buildInfo: String
        get() = if (isLibraryLoaded) {
            runCatching { nativeGetBuildInfo() }.getOrDefault("libchiaki_jni loaded")
        } else {
            "Native libchiaki_jni.so not compiled (pinned chiaki-ng ${BuildConfig.CHIAKI_NG_PINNED_TAG} @ ${BuildConfig.CHIAKI_NG_PINNED_COMMIT}; build arm64-v8a via GitHub Actions)"
        }

    // Exact upstream chiaki-ng ChiakiControllerButton bitflags (lib/include/chiaki/controller.h)
    private const val CHIAKI_BUTTON_CROSS = 1 shl 0
    private const val CHIAKI_BUTTON_MOON = 1 shl 1       // Circle
    private const val CHIAKI_BUTTON_BOX = 1 shl 2        // Square
    private const val CHIAKI_BUTTON_PYRAMID = 1 shl 3    // Triangle
    private const val CHIAKI_BUTTON_L1 = 1 shl 8
    private const val CHIAKI_BUTTON_R1 = 1 shl 9
    private const val CHIAKI_BUTTON_L3 = 1 shl 10
    private const val CHIAKI_BUTTON_R3 = 1 shl 11
    private const val CHIAKI_BUTTON_OPTIONS = 1 shl 12
    private const val CHIAKI_BUTTON_TOUCHPAD = 1 shl 14
    private const val CHIAKI_BUTTON_PS = 1 shl 15

    fun isAllZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) {
            acc = acc or (b.toInt() and 0xFF)
        }
        return acc == 0
    }

    /**
     * Strictly validates cryptographic PS5 registration credentials before any session start.
     * Rejects missing, malformed, wrong-length, or zero-filled placeholder arrays.
     */
    fun validateSessionCredentials(
        registKey: ByteArray?,
        morningKey: ByteArray?
    ): Result<Unit> {
        if (registKey == null || morningKey == null) {
            return Result.failure(
                IllegalStateException("Missing PS5 registration credentials. Pair this console with an 8-digit PIN first.")
            )
        }
        if (registKey.size != REQUIRED_REGIST_KEY_BYTES) {
            return Result.failure(
                IllegalArgumentException("Invalid rp_regist_key length (${registKey.size} bytes; expected $REQUIRED_REGIST_KEY_BYTES).")
            )
        }
        if (morningKey.size != REQUIRED_RP_KEY_BYTES) {
            return Result.failure(
                IllegalArgumentException("Invalid rp_key/morning length (${morningKey.size} bytes; expected $REQUIRED_RP_KEY_BYTES).")
            )
        }
        if (isAllZero(registKey) || isAllZero(morningKey)) {
            return Result.failure(
                IllegalStateException("Placeholder zero-filled registration credentials are rejected. Complete real PS5 PIN pairing.")
            )
        }
        return Result.success(Unit)
    }

    fun nativeErrorToMessage(code: Int): String = when (code) {
        0 -> "Success"
        ERR_NATIVE_UNAVAILABLE -> "Native libchiaki_jni.so is not linked in this build."
        ERR_ALREADY_ACTIVE -> "A Remote Play registration or session is already active."
        ERR_INVALID_ARGS -> "Invalid Remote Play parameters passed to native bridge."
        ERR_ZERO_CREDENTIALS -> "Rejected empty or zero-filled PS5 session credentials."
        ERR_CALLBACK_LOOKUP -> "Native JNI callback signature mismatch."
        ERR_REGIST_TIMEOUT -> "Timed out waiting for PS5 registration response."
        ERR_REGIST_FAILED -> "PS5 rejected pairing request. Check 8-digit PIN and PSN Account ID."
        else -> "libchiaki returned error code $code."
    }

    /**
     * Translates MobilePlay's portable DualSenseButtons mask into upstream libchiaki button flags.
     */
    fun toChiakiButtonMask(mobilePlayMask: Int): Int {
        var out = 0
        if (mobilePlayMask and DualSenseButtons.CROSS != 0) out = out or CHIAKI_BUTTON_CROSS
        if (mobilePlayMask and DualSenseButtons.CIRCLE != 0) out = out or CHIAKI_BUTTON_MOON
        if (mobilePlayMask and DualSenseButtons.SQUARE != 0) out = out or CHIAKI_BUTTON_BOX
        if (mobilePlayMask and DualSenseButtons.TRIANGLE != 0) out = out or CHIAKI_BUTTON_PYRAMID
        if (mobilePlayMask and DualSenseButtons.L1 != 0) out = out or CHIAKI_BUTTON_L1
        if (mobilePlayMask and DualSenseButtons.R1 != 0) out = out or CHIAKI_BUTTON_R1
        if (mobilePlayMask and DualSenseButtons.L3 != 0) out = out or CHIAKI_BUTTON_L3
        if (mobilePlayMask and DualSenseButtons.R3 != 0) out = out or CHIAKI_BUTTON_R3
        if (mobilePlayMask and DualSenseButtons.OPTIONS != 0) out = out or CHIAKI_BUTTON_OPTIONS
        if (mobilePlayMask and DualSenseButtons.TOUCHPAD != 0) out = out or CHIAKI_BUTTON_TOUCHPAD
        if (mobilePlayMask and DualSenseButtons.PS != 0) out = out or CHIAKI_BUTTON_PS
        return out
    }

    /**
     * Converts normalized float stick [-1..1] to libchiaki signed 16-bit integer [-32767..32767].
     */
    fun floatToStickShort(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()

    fun sendPadStateIfLoaded(
        state: PadState,
        touchActive: Boolean = false,
        touchX: Int = 960,
        touchY: Int = 470
    ) {
        if (!isLibraryLoaded) return
        nativeSendControllerState(
            buttons = toChiakiButtonMask(state.buttons),
            l2 = state.l2.coerceIn(0, 255),
            r2 = state.r2.coerceIn(0, 255),
            leftX = floatToStickShort(state.leftX),
            leftY = floatToStickShort(state.leftY),
            rightX = floatToStickShort(state.rightX),
            rightY = floatToStickShort(state.rightY),
            touchActive = touchActive,
            touchX = touchX,
            touchY = touchY
        )
    }

    external fun nativeGetBuildInfo(): String

    external fun nativeRegisterConsole(
        host: String,
        psnAccountIdBytes: ByteArray,
        pin: Int,
        timeoutMs: Int,
        callback: ChiakiRegistCallback
    ): Int

    external fun nativeStartSession(
        host: String,
        registKey: ByteArray,
        morning: ByteArray,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        useHevc: Boolean,
        callback: ChiakiStreamCallback
    ): Int

    external fun nativeSendControllerState(
        buttons: Int,
        l2: Int,
        r2: Int,
        leftX: Short,
        leftY: Short,
        rightX: Short,
        rightY: Short,
        touchActive: Boolean,
        touchX: Int,
        touchY: Int
    )

    external fun nativeStopSession()
}
