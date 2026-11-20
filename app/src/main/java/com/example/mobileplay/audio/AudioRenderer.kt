package com.example.mobileplay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioTelemetry(
    val state: String = "IDLE — No active PS5 audio stream",
    val sampleRateHz: Int = 48_000,
    val channels: Int = 2,
    val volume: Float = 0.85f,
    val framesWritten: Long = 0L,
    val underrunCount: Int = 0
)

/**
 * Low-latency Android AudioTrack renderer for 16-bit PCM stereo audio delivered by libchiaki.
 */
class AudioRenderer {
    private val _telemetry = MutableStateFlow(AudioTelemetry())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var masterVolume: Float = 0.85f
    private var framesWritten = 0L

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            masterVolume = clamped
            audioTrack?.setVolume(clamped)
        }
        _telemetry.value = _telemetry.value.copy(volume = clamped)
    }

    fun configure(channels: Int, sampleRateHz: Int): Result<Unit> = synchronized(lock) {
        runCatching {
            releaseLocked()
            val safeRate = sampleRateHz.coerceIn(8_000, 96_000)
            val channelMask = if (channels <= 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuf = AudioTrack.getMinBufferSize(
                safeRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(safeRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build()

            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = builder.build()
            track.setVolume(masterVolume)
            track.play()
            audioTrack = track
            framesWritten = 0L
            _telemetry.value = AudioTelemetry(
                state = "PLAYING (${safeRate}Hz ${if (channels <= 1) "Mono" else "Stereo"})",
                sampleRateHz = safeRate,
                channels = channels.coerceIn(1, 2),
                volume = masterVolume,
                framesWritten = 0L,
                underrunCount = 0
            )
        }.onFailure { err ->
            _telemetry.value = _telemetry.value.copy(
                state = "AUDIO ERROR: ${err.message ?: "init failed"}"
            )
        }
    }

    fun writePcm(samples: ShortArray) {
        synchronized(lock) {
            val track = audioTrack ?: return
            if (samples.isEmpty()) return
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) {
                framesWritten += written
                val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    track.underrunCount
                } else {
                    0
                }
                _telemetry.value = _telemetry.value.copy(
                    framesWritten = framesWritten,
                    underrunCount = underruns
                )
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            releaseLocked()
        }
        _telemetry.value = _telemetry.value.copy(
            state = "IDLE — No active PS5 audio stream"
        )
    }

    private fun releaseLocked() {
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }
}
