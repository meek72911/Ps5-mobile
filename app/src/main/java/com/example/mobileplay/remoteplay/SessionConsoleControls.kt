package com.example.mobileplay.remoteplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface ConsolePrompt {
    class LoginPin(val incorrect: Boolean) : ConsolePrompt
    class Keyboard(initialText: String) : ConsolePrompt {
        private val _text = MutableStateFlow(initialText)
        val text = _text.asStateFlow()

        fun updateText(text: String) {
            _text.value = text
        }
    }
}

internal class SessionConsoleControls {
    private val _prompt = MutableStateFlow<ConsolePrompt?>(null)
    val prompt = _prompt.asStateFlow()
    private val _standbyPending = MutableStateFlow(false)
    val standbyPending = _standbyPending.asStateFlow()

    fun onNativeEvent(type: Int, message: String) {
        when (type) {
            ChiakiNativeBridge.EVENT_LOGIN_PIN -> _prompt.value = ConsolePrompt.LoginPin(message.isNotEmpty())
            ChiakiNativeBridge.EVENT_KEYBOARD_OPEN -> _prompt.value = ConsolePrompt.Keyboard(message)
            ChiakiNativeBridge.EVENT_KEYBOARD_UPDATE -> (_prompt.value as? ConsolePrompt.Keyboard)?.updateText(message)
            ChiakiNativeBridge.EVENT_KEYBOARD_CLOSE, ChiakiNativeBridge.EVENT_SESSION_QUIT -> clearPrompt()
            ChiakiNativeBridge.EVENT_SESSION_CONNECTED -> {
                if (_prompt.value is ConsolePrompt.LoginPin) clearPrompt()
            }
        }
    }

    fun submitPin(prompt: ConsolePrompt.LoginPin, pin: String): Boolean {
        if (_prompt.value !== prompt || pin.length != 4 || pin.any { it !in '0'..'9' }) return false
        val bytes = pin.toByteArray(Charsets.US_ASCII)
        return try {
            ChiakiNativeBridge.nativeSetLoginPin(bytes) == 0
        } finally {
            bytes.fill(0)
        }
    }

    fun finishKeyboard(prompt: ConsolePrompt.Keyboard, text: String?): Boolean {
        if (_prompt.value !== prompt) return false
        val bytes = text?.toByteArray(Charsets.UTF_8)
        return try {
            val sent = ChiakiNativeBridge.nativeFinishKeyboard(bytes) == 0
            if (sent) _prompt.compareAndSet(prompt, null)
            sent
        } finally {
            bytes?.fill(0)
        }
    }

    fun requestStandby(): Boolean {
        if (!_standbyPending.compareAndSet(expect = false, update = true)) return false
        val sent = ChiakiNativeBridge.nativeRequestStandby() == 0
        if (!sent) clearStandby()
        return sent
    }

    fun clearStandby() {
        _standbyPending.value = false
    }

    fun clearPrompt() {
        _prompt.value = null
    }
}
