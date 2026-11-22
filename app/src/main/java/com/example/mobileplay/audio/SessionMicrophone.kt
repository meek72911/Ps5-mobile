package com.example.mobileplay.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import com.example.mobileplay.remoteplay.ChiakiNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class MicrophoneState(val enabled: Boolean = false, val error: String? = null)

internal class SessionMicrophone(private val context: Context) {
    private class Capture(val recorder: AudioRecord, val echoCanceler: AcousticEchoCanceler?) {
        val running = AtomicBoolean(true)
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(MicrophoneState())
    val state = _state.asStateFlow()
    private var active: Capture? = null

    fun start() {
        if (active != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = MicrophoneState(error = "Microphone permission is required.")
            return
        }
        val recorder = runCatching {
            val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0)
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(maxOf(minBytes, SAMPLES_PER_FRAME * 2 * 3))
                .build()
        }.getOrNull()
        if (recorder == null) {
            _state.value = MicrophoneState(error = "Could not open the phone microphone.")
            return
        }
        val opened = runCatching {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            check(ChiakiNativeBridge.nativeSetMicrophoneEnabled(true) == 0)
        }.isSuccess
        if (!opened) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            _state.value = MicrophoneState(error = "Could not enable the microphone for this stream.")
            return
        }
        val effect = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
            } else null
        }.getOrNull()
        val capture = Capture(recorder, effect)
        active = capture
        _state.value = MicrophoneState(enabled = true)
        Thread({ captureLoop(capture) }, "MobilePlay microphone").start()
    }

    fun stop() {
        val capture = active ?: return
        active = null
        capture.running.set(false)
        _state.value = MicrophoneState()
        runCatching { capture.recorder.stop() }
        runCatching { ChiakiNativeBridge.nativeSetMicrophoneEnabled(false) }
    }

    private fun captureLoop(capture: Capture) {
        val frame = ShortArray(SAMPLES_PER_FRAME)
        var offset = 0
        var failures = 0
        try {
            while (capture.running.get()) {
                val count = capture.recorder.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                if (count < 0) break
                if (count == 0) continue
                offset += count
                if (offset < frame.size) continue
                if (!capture.running.get()) break
                val sent = ChiakiNativeBridge.nativeSendMicrophonePcm(frame)
                failures = if (sent) 0 else failures + 1
                if (failures >= 10) break
                offset = 0
            }
        } catch (_: RuntimeException) {
            // The recorder can be revoked or torn down when Android changes the input route.
        } finally {
            frame.fill(0)
            main.post {
                if (active === capture) {
                    stop()
                    _state.value = MicrophoneState(error = "Microphone disconnected. Tap to try again.")
                }
            }
            runCatching { capture.echoCanceler?.release() }
            runCatching { capture.recorder.release() }
        }
    }

    private companion object {
        const val RATE = 48_000
        const val SAMPLES_PER_FRAME = RATE / 100
    }
}
