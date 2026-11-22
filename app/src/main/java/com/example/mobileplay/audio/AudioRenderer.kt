package com.example.mobileplay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioTelemetry(
    val state: String = "IDLE — No active PS5 audio stream",
    val sampleRateHz: Int = 48_000,
    val channels: Int = 2,
    val volume: Float = 0.85f,
    val framesWritten: Long = 0L,
    val underrunCount: Int = 0,
    val droppedFrames: Long = 0,
    val queuedMs: Int = 0,
    val outputRoute: String = ""
)

/**
 * Low-latency Android AudioTrack renderer for 16-bit PCM stereo audio delivered by libchiaki.
 */
class AudioRenderer {
    private val _telemetry = MutableStateFlow(AudioTelemetry())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var writerThread: HandlerThread? = null
    private var writer: Handler? = null
    private var pcm: PendingPcm? = null
    private var drainScheduled = false
    private var masterVolume: Float = 0.85f
    private var framesWritten = 0L
    private var droppedFrames = 0L
    private var lastTelemetryMs = 0L

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            masterVolume = clamped
            audioTrack?.setVolume(clamped)
            _telemetry.value = _telemetry.value.copy(volume = clamped)
        }
    }

    fun configure(channels: Int, sampleRateHz: Int): Result<Unit> = synchronized(lock) {
        runCatching {
            releaseLocked()
            val safeRate = sampleRateHz.coerceIn(8_000, 96_000)
            require(channels in 1..2) { "Unsupported channel count $channels" }
            val channelMask = if (channels <= 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val reportedMinimum = AudioTrack.getMinBufferSize(
                safeRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(reportedMinimum > 0) { "Audio output configuration unavailable" }
            val minBuf = reportedMinimum.coerceAtLeast(channels * 2 * safeRate / 100)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
                track.setVolume(masterVolume)
                track.setBufferSizeInFrames(minBuf / (2 * channels))
                track.play()
            } catch (error: Exception) {
                track.release()
                throw error
            }
            audioTrack = track
            val thread = HandlerThread("MobilePlay PCM", Process.THREAD_PRIORITY_AUDIO)
            writerThread = thread
            thread.start()
            writer = Handler(thread.looper)
            pcm = PendingPcm(maxFrames = safeRate * 60 / 1_000, channels = channels)
            framesWritten = 0L
            droppedFrames = 0L
            lastTelemetryMs = 0L
            _telemetry.value = AudioTelemetry(
                state = "PLAYING (${safeRate}Hz ${if (channels <= 1) "Mono" else "Stereo"})",
                sampleRateHz = safeRate,
                channels = channels,
                volume = masterVolume,
                framesWritten = 0L,
                underrunCount = 0
            )
        }.onFailure { err ->
            releaseLocked()
            _telemetry.value = _telemetry.value.copy(
                state = "AUDIO ERROR: ${err.message ?: "init failed"}"
            )
        }
    }

    fun writePcm(samples: ShortArray) {
        synchronized(lock) {
            val queue = pcm ?: return
            val handler = writer ?: return
            droppedFrames += queue.offer(samples)
            if (!drainScheduled && queue.first() != null) {
                drainScheduled = handler.post { drain(handler) }
            }
        }
    }

    private fun drain(handler: Handler): Unit = synchronized(lock) {
        if (writer !== handler) return@synchronized
        val track = audioTrack ?: return@synchronized
        val queue = pcm ?: return@synchronized
        for (attempt in 0 until 8) {
            val head = queue.first() ?: break
            val written = runCatching {
                track.write(head, queue.offset, head.size - queue.offset, AudioTrack.WRITE_NON_BLOCKING)
            }.getOrDefault(AudioTrack.ERROR_INVALID_OPERATION)
            if (written < 0) {
                releaseLocked()
                _telemetry.value = _telemetry.value.copy(state = "AUDIO ERROR: output write failed ($written)")
                return@synchronized
            }
            if (written == 0) {
                publishTelemetry(track, queue)
                drainScheduled = handler.postDelayed({ drain(handler) }, 3L)
                return@synchronized
            }
            framesWritten += queue.consume(written)
        }
        publishTelemetry(track, queue)
        drainScheduled = queue.first() != null && handler.post { drain(handler) }
    }

    private fun publishTelemetry(track: AudioTrack, queue: PendingPcm) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastTelemetryMs < 200L) return
        lastTelemetryMs = nowMs
        val current = _telemetry.value
        _telemetry.value = current.copy(
            framesWritten = framesWritten,
            underrunCount = track.underrunCount,
            droppedFrames = droppedFrames,
            queuedMs = queue.queuedFrames * 1_000 / current.sampleRateHz,
            outputRoute = track.routedDevice?.productName?.toString().orEmpty()
        )
    }

    fun stop() {
        synchronized(lock) {
            releaseLocked()
            _telemetry.value = _telemetry.value.copy(
                state = "IDLE — No active PS5 audio stream",
                queuedMs = 0
            )
        }
    }

    private fun releaseLocked() {
        writer?.removeCallbacksAndMessages(null)
        writerThread?.quitSafely()
        writerThread = null
        writer = null
        pcm = null
        drainScheduled = false
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }
}

/** Buffers a short PCM tail so a partial platform write never discards the rest of a frame block. */
internal class PendingPcm(maxFrames: Int, private val channels: Int) {
    private val maxSamples = maxFrames * channels
    private val chunks = ArrayDeque<ShortArray>()
    private var queuedSamples = 0
    var offset = 0
        private set
    val queuedFrames get() = queuedSamples / channels

    fun first(): ShortArray? = chunks.firstOrNull()

    fun offer(samples: ShortArray): Int {
        val validSize = samples.size - samples.size % channels
        if (validSize == 0) return 0
        val keptSize = minOf(validSize, maxSamples)
        var dropped = validSize - keptSize
        while (queuedSamples + keptSize > maxSamples && chunks.isNotEmpty()) {
            val remainder = chunks.removeFirst().size - offset
            queuedSamples -= remainder
            dropped += remainder
            offset = 0
        }
        chunks.addLast(if (keptSize == samples.size) samples else samples.copyOfRange(validSize - keptSize, validSize))
        queuedSamples += keptSize
        return dropped / channels
    }

    fun consume(samples: Int): Int {
        val before = offset
        offset += samples
        queuedSamples -= samples
        val completeFrames = offset / channels - before / channels
        if (offset == chunks.first().size) {
            chunks.removeFirst()
            offset = 0
        }
        return completeFrames
    }
}
