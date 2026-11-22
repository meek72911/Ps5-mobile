package com.example.mobileplay.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class StreamResolution(val label: String, val width: Int, val height: Int, val autoBitrateKbps: Int) {
    P540("540p", 960, 540, 6_000),
    P720("720p", 1280, 720, 10_000),
    P1080("1080p", 1920, 1080, 16_000)
}

enum class StreamFrameRate(val label: String, val fps: Int) {
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60)
}

enum class StreamCodec(val label: String, val mimeType: String) {
    H264("H.264 (AVC)", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("HEVC (H.265)", MediaFormat.MIMETYPE_VIDEO_HEVC)
}

enum class FramePacing(val label: String) {
    FAST("Lowest latency"),
    BALANCED("Balanced")
}

enum class StreamPreset(
    val label: String,
    private val resolution: StreamResolution,
    private val bitrateKbps: Int,
    private val pacing: FramePacing,
    private val frameRate: StreamFrameRate = StreamFrameRate.FPS_60
) {
    FAST("Fast", StreamResolution.P720, 8_000, FramePacing.FAST),
    BALANCED("Balanced", StreamResolution.P720, 10_000, FramePacing.BALANCED),
    SHARP("Sharp", StreamResolution.P1080, 14_000, FramePacing.BALANCED),
    BATTERY("Battery Saver", StreamResolution.P540, 4_000, FramePacing.BALANCED, StreamFrameRate.FPS_30);

    fun applyTo(settings: StreamQualitySettings): StreamQualitySettings = settings.copy(
        resolution = resolution,
        frameRate = frameRate,
        codec = StreamCodec.H264,
        autoQuality = false,
        manualBitrateKbps = bitrateKbps,
        framePacing = pacing,
        lowLatencyWifi = if (this == BATTERY) false else settings.lowLatencyWifi
    )
}

data class StreamQualitySettings(
    val resolution: StreamResolution = StreamResolution.P720,
    val frameRate: StreamFrameRate = StreamFrameRate.FPS_60,
    val codec: StreamCodec = StreamCodec.H264,
    val autoQuality: Boolean = true,
    val manualBitrateKbps: Int = 12_000,
    val hardwareDecodingEnabled: Boolean = true,
    val audioVolume: Float = 0.85f,
    val framePacing: FramePacing = FramePacing.FAST,
    val lowLatencyWifi: Boolean = false
) {
    val effectiveBitrateKbps: Int
        get() = if (autoQuality) resolution.autoBitrateKbps else manualBitrateKbps.coerceIn(2_000, 30_000)

    fun summaryLabel(): String = "${resolution.label} • ${frameRate.label} • ${codec.label.substringBefore(" ")}"
}

data class PlaybackProgress(
    val surfaceReady: Boolean,
    val decoderReady: Boolean,
    val awaitingKeyframe: Boolean,
    val decoderReadyNs: Long,
    val lastReceivedNs: Long,
    val lastOutputNs: Long,
    val lastSurfaceSubmitNs: Long,
    val lastRenderedNs: Long
)

data class CodecCapabilities(
    val h264HardwareSupported: Boolean = false,
    val hevcHardwareSupported: Boolean = false,
    val h264DecoderName: String = "Unknown",
    val hevcDecoderName: String = "Unavailable"
)

/**
 * SurfaceView that strictly preserves 16:9 Remote Play video framing without cropping.
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    var aspectRatio: Float = 16f / 9f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val containerRatio = availableWidth.toFloat() / availableHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (containerRatio > aspectRatio) {
            targetHeight = availableHeight
            targetWidth = (availableHeight * aspectRatio).roundToInt()
        } else {
            targetWidth = availableWidth
            targetHeight = (availableWidth / aspectRatio).roundToInt()
        }
        setMeasuredDimension(targetWidth, targetHeight)
    }
}

data class VideoTelemetry(
    val surfaceReady: Boolean = false,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    val decoderState: String = "IDLE — No active PS5 video stream",
    val codecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val videoFps: Float = 0f,
    val decodedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val lastDecodeTimeMs: Float = 0f,
    val lastError: String? = null,
    val surfaceSubmittedFrames: Long = 0L,
    val renderedFrames: Long = 0L,
    val droppedDecodedFrames: Long = 0L,
    val lastInputQueueMs: Float = 0f,
    val lastRenderQueueMs: Float = 0f,
    val pacingLabel: String = FramePacing.FAST.label,
    val decoderName: String? = null,
    val lowLatencyEnabled: Boolean = false,
    val healthNote: String? = null,
    val handshakeRttMs: Double? = null,
    val receivedBitrateKbps: Double? = null,
    val packetLossFraction: Double? = null,
    val firstRenderMs: Float? = null
)

/** Owns a hardware decoder and keeps compressed input and decoded output off the network thread. */
class VideoRenderer : SurfaceHolder.Callback {
    private val _telemetry = MutableStateFlow(VideoTelemetry())
    val telemetry: StateFlow<VideoTelemetry> = _telemetry.asStateFlow()
    val capabilities: CodecCapabilities = probeDeviceCodecs()

    private data class AccessUnit(val bytes: ByteArray, val arrivalNs: Long, val requestedPtsUs: Long, val config: Boolean)
    private data class DecoderOutput(val index: Int, val ptsUs: Long)

    private val codecLock = Any()
    private val pendingInputs = ArrayDeque<AccessUnit>(MAX_PENDING_INPUT)
    private val inputIndices = ArrayDeque<Int>()
    private val submittedAt = LinkedHashMap<Long, Long>()
    private var activeSurface: Surface? = null
    private var mediaCodec: MediaCodec? = null
    private var worker: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var lastQualitySettings: StreamQualitySettings? = null
    private var codecConfig: AccessUnit? = null
    private var needsConfig = false
    private var needsKeyframe = false
    private var codecFailed = false
    private var inputWorkScheduled = false
    private var outputWorkScheduled = false
    private var pendingOutput: DecoderOutput? = null
    private var nextFrameNs = 0L
    private var lastPtsUs = -1L
    private var lastReceivedNs = 0L
    private var lastOutputNs = 0L
    private var lastSurfaceSubmitNs = 0L
    private var lastRenderedNs = 0L
    private var decoderReadyNs = 0L
    private var lastTelemetryNs = 0L
    private var fpsWindowStartNs = 0L
    private var fpsWindowRendered = 0
    private var decodedFrames = 0L
    private var surfaceSubmittedFrames = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var outputDrops = 0L
    private var lastDecodeMs = 0f
    private var lastInputQueueMs = 0f
    private var lastRenderQueueMs = 0f
    private var renderedFps = 0f

    fun attachTo(surfaceView: AspectRatioSurfaceView) {
        surfaceView.holder.addCallback(this)
    }

    fun detachFrom(surfaceView: AspectRatioSurfaceView) {
        val holder = surfaceView.holder
        holder.removeCallback(this)
        surfaceDestroyed(holder)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = synchronized(codecLock) {
        attachSurfaceLocked(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = synchronized(codecLock) {
        if (activeSurface !== holder.surface || mediaCodec == null) attachSurfaceLocked(holder.surface)
        _telemetry.value = _telemetry.value.copy(
            surfaceReady = holder.surface.isValid,
            surfaceWidth = width,
            surfaceHeight = height
        )
    }

    private fun attachSurfaceLocked(surface: Surface) {
        if (activeSurface !== surface) releaseCodecLocked()
        activeSurface = surface
        _telemetry.value = _telemetry.value.copy(
            surfaceReady = surface.isValid,
            decoderState = if (mediaCodec != null) "WAITING FOR VIDEO" else "STANDBY"
        )
        if (surface.isValid && mediaCodec == null) {
            needsKeyframe = codecConfig != null
            lastQualitySettings?.let { buildDecoderLocked(it) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = synchronized(codecLock) {
        if (activeSurface !== holder.surface) return@synchronized
        releaseCodecLocked()
        activeSurface = null
        _telemetry.value = _telemetry.value.copy(
            surfaceReady = false,
            decoderState = "SURFACE RELEASED",
            videoFps = 0f
        )
    }

    fun playbackProgress(): PlaybackProgress = synchronized(codecLock) {
        PlaybackProgress(
            surfaceReady = activeSurface?.isValid == true,
            decoderReady = mediaCodec != null && !codecFailed,
            awaitingKeyframe = needsKeyframe,
            decoderReadyNs = decoderReadyNs,
            lastReceivedNs = lastReceivedNs,
            lastOutputNs = lastOutputNs,
            lastSurfaceSubmitNs = lastSurfaceSubmitNs,
            lastRenderedNs = lastRenderedNs
        )
    }

    fun reportStreamHealth(note: String?) = synchronized(codecLock) {
        if (_telemetry.value.healthNote != note) _telemetry.value = _telemetry.value.copy(healthNote = note)
    }

    fun reportNetworkStats(stats: DoubleArray) = synchronized(codecLock) {
        _telemetry.value = _telemetry.value.copy(
            handshakeRttMs = stats.getOrNull(0)?.takeIf { it.isFinite() && it >= 0.0 },
            receivedBitrateKbps = stats.getOrNull(1)?.takeIf { it.isFinite() && it >= 0.0 },
            packetLossFraction = stats.getOrNull(2)?.takeIf { it.isFinite() && it >= 0.0 }
        )
    }

    fun configureHardwareDecoder(settings: StreamQualitySettings): Result<Unit> = synchronized(codecLock) {
        releaseCodecLocked()
        if (activeSurface?.isValid != true) {
            val error = IllegalStateException("16:9 video SurfaceView is not ready for MediaCodec output.")
            _telemetry.value = _telemetry.value.copy(decoderState = "DECODER ERROR", lastError = error.message)
            return@synchronized Result.failure(error)
        }
        codecConfig = null
        needsKeyframe = false
        lastReceivedNs = 0L
        decodedFrames = 0L
        surfaceSubmittedFrames = 0L
        renderedFrames = 0L
        droppedFrames = 0L
        outputDrops = 0L
        lastDecodeMs = 0f
        lastInputQueueMs = 0f
        lastRenderQueueMs = 0f
        lastTelemetryNs = 0L
        renderedFps = 0f
        lastQualitySettings = settings
        _telemetry.value = VideoTelemetry(
            surfaceReady = true,
            surfaceWidth = _telemetry.value.surfaceWidth,
            surfaceHeight = _telemetry.value.surfaceHeight,
            decodedWidth = settings.resolution.width,
            decodedHeight = settings.resolution.height,
            codecMime = settings.codec.mimeType,
            pacingLabel = settings.framePacing.label
        )
        buildDecoderLocked(settings).onFailure { lastQualitySettings = null }
    }

    fun rebuildDecoder(): Boolean = synchronized(codecLock) {
        val settings = lastQualitySettings ?: return@synchronized false
        if (activeSurface?.isValid != true) return@synchronized false
        releaseCodecLocked()
        needsKeyframe = true
        buildDecoderLocked(settings).isSuccess
    }

    private fun buildDecoderLocked(settings: StreamQualitySettings): Result<Unit> = runCatching {
        val surface = checkNotNull(activeSurface)
        val handler = codecHandler ?: Handler(HandlerThread("MobilePlay video codec").apply {
            start()
            worker = this
        }.looper).also { codecHandler = it }
        val mime = settings.codec.mimeType
        val requestedLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val decoderName = if (!settings.hardwareDecodingEnabled) null else {
            when (settings.codec) {
                StreamCodec.H264 -> capabilities.h264DecoderName.takeIf { capabilities.h264HardwareSupported }
                StreamCodec.HEVC -> capabilities.hevcDecoderName.takeIf { capabilities.hevcHardwareSupported }
            }
        }
        val enableLowLatency = if (requestedLowLatency) runCatching {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { decoderName?.let { name -> it.name == name } ?: (!it.isEncoder && mime in it.supportedTypes) }
            info?.getCapabilitiesForType(mime)?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true
        }.getOrDefault(false) else false

        fun startCodec(lowLatency: Boolean): MediaCodec {
            val format = MediaFormat.createVideoFormat(mime, settings.resolution.width, settings.resolution.height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            val codec = if (decoderName == null) MediaCodec.createDecoderByType(mime)
                else MediaCodec.createByCodecName(decoderName)
            try {
                codec.setCallback(codecCallback, handler)
                codec.setOnFrameRenderedListener({ source, ptsUs, renderedNs ->
                    synchronized(codecLock) {
                        if (source !== mediaCodec || ptsUs < 0L) return@synchronized
                        val now = System.nanoTime()
                        lastRenderedNs = maxOf(lastRenderedNs, renderedNs.takeIf { it in decoderReadyNs..now } ?: now)
                        lastRenderQueueMs = lastReleasedOutput.remove(ptsUs)?.let { releaseNs ->
                            nanosToMs((renderedNs - releaseNs).coerceAtLeast(0L))
                        } ?: lastRenderQueueMs
                        if (renderedFrames == 0L) {
                            _telemetry.value = _telemetry.value.copy(firstRenderMs = nanosToMs(lastRenderedNs - decoderReadyNs))
                        }
                        renderedFrames++
                        fpsWindowRendered++
                        publishTelemetryLocked(now)
                    }
                }, handler)
                codec.configure(format, surface, null, 0)
                codec.start()
                return codec
            } catch (failure: Exception) {
                runCatching { codec.release() }
                throw failure
            }
        }

        var lowLatencyActive = enableLowLatency
        val codec = try {
            startCodec(lowLatencyActive)
        } catch (failure: Exception) {
            if (!lowLatencyActive) throw failure
            lowLatencyActive = false
            startCodec(lowLatency = false)
        }
        mediaCodec = codec
        decoderReadyNs = System.nanoTime()
        fpsWindowStartNs = decoderReadyNs
        fpsWindowRendered = 0
        needsConfig = codecConfig != null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { surface.setFrameRate(settings.frameRate.fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
        _telemetry.value = _telemetry.value.copy(
            decoderState = "WAITING FOR VIDEO",
            decoderName = codec.name,
            lowLatencyEnabled = lowLatencyActive,
            lastError = null
        )
    }.onFailure { error ->
        _telemetry.value = _telemetry.value.copy(
            decoderState = "DECODER ERROR",
            lastError = error.message ?: "Failed to initialize MediaCodec"
        )
    }

    fun submitAccessUnit(data: ByteArray, presentationTimeUs: Long): Boolean = synchronized(codecLock) {
        val settings = lastQualitySettings ?: return@synchronized false
        if (data.isEmpty()) return@synchronized false
        val now = System.nanoTime()
        lastReceivedNs = now
        val config = isCodecConfigAccessUnit(data, settings.codec)
        val unit = AccessUnit(data, now, presentationTimeUs, config)
        if (config) codecConfig = unit
        val codec = mediaCodec
        if (codec == null || codecFailed) {
            if (!config) droppedFrames++
            return@synchronized false
        }
        if (config && needsConfig) {
            scheduleInputLocked(codec)
            return@synchronized true
        }
        if (!config && needsKeyframe && !isRandomAccessUnit(data, settings.codec)) {
            droppedFrames++
            return@synchronized false
        }
        if (pendingInputs.size >= MAX_PENDING_INPUT) {
            droppedFrames += pendingInputs.size + 1
            pendingInputs.clear()
            needsKeyframe = true
            needsConfig = codecConfig != null
            return@synchronized false
        }
        if (!config) needsKeyframe = false
        pendingInputs.addLast(unit)
        scheduleInputLocked(codec)
        true
    }

    private fun scheduleInputLocked(codec: MediaCodec) {
        if (inputWorkScheduled || inputIndices.isEmpty() || (pendingInputs.isEmpty() && !needsConfig)) return
        inputWorkScheduled = true
        codecHandler?.post {
            synchronized(codecLock) {
                if (codec !== mediaCodec) return@synchronized
                inputWorkScheduled = false
                drainInputLocked(codec)
            }
        }
    }

    private fun drainInputLocked(codec: MediaCodec) {
        while (!codecFailed && inputIndices.isNotEmpty()) {
            val unit = if (needsConfig) {
                needsConfig = false
                codecConfig ?: continue
            } else {
                pendingInputs.removeFirstOrNull() ?: break
            }
            val index = inputIndices.removeFirst()
            runCatching {
                val input = checkNotNull(codec.getInputBuffer(index))
                input.clear()
                if (input.capacity() < unit.bytes.size) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    droppedFrames++
                    error("Compressed video packet exceeded the MediaCodec input capacity.")
                }
                input.put(unit.bytes)
                val queuedNs = System.nanoTime()
                val ptsUs = maxOf(lastPtsUs + 1, unit.requestedPtsUs.takeIf { it > 0 } ?: queuedNs / 1_000L)
                lastPtsUs = ptsUs
                codec.queueInputBuffer(index, 0, unit.bytes.size, ptsUs, if (unit.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                if (!unit.config) {
                    lastInputQueueMs = nanosToMs(queuedNs - unit.arrivalNs)
                    rememberTimestamp(submittedAt, ptsUs, queuedNs)
                }
            }.onFailure { markCodecErrorLocked(it) }
        }
    }

    private val lastReleasedOutput = LinkedHashMap<Long, Long>()
    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = synchronized(codecLock) {
            if (codec !== mediaCodec) return@synchronized
            inputIndices.addLast(index)
            scheduleInputLocked(codec)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = synchronized(codecLock) {
            if (codec !== mediaCodec) return@synchronized
            if ((info.size == 0 && !submittedAt.containsKey(info.presentationTimeUs)) ||
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            ) {
                runCatching { codec.releaseOutputBuffer(index, false) }.onFailure { markCodecErrorLocked(it) }
                return@synchronized
            }
            val now = System.nanoTime()
            lastOutputNs = now
            decodedFrames++
            submittedAt.remove(info.presentationTimeUs)?.let { lastDecodeMs = nanosToMs(now - it) }
            pendingOutput?.let {
                runCatching { codec.releaseOutputBuffer(it.index, false) }.onFailure { markCodecErrorLocked(it) }
                outputDrops++
            }
            pendingOutput = DecoderOutput(index, info.presentationTimeUs)
            scheduleOutputLocked(codec, now)
            publishTelemetryLocked(now)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = synchronized(codecLock) {
            if (codec !== mediaCodec) return@synchronized
            val current = _telemetry.value
            _telemetry.value = current.copy(
                decodedWidth = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else current.decodedWidth,
                decodedHeight = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else current.decodedHeight
            )
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            synchronized(codecLock) {
                if (codec === mediaCodec) markCodecErrorLocked(error)
            }
        }
    }

    private fun scheduleOutputLocked(codec: MediaCodec, now: Long) {
        if (outputWorkScheduled) return
        val settings = lastQualitySettings ?: return
        outputWorkScheduled = true
        val intervalNs = 1_000_000_000L / settings.frameRate.fps
        val delayMs = if (settings.framePacing == FramePacing.BALANCED) {
            ((nextFrameNs - now).coerceIn(0L, intervalNs) + 999_999L) / 1_000_000L
        } else 0L
        codecHandler?.postDelayed({
            synchronized(codecLock) {
                if (codec !== mediaCodec) return@synchronized
                outputWorkScheduled = false
                val frame = pendingOutput ?: return@synchronized
                pendingOutput = null
                val releasedNs = System.nanoTime()
                nextFrameNs = releasedNs + intervalNs
                runCatching { codec.releaseOutputBuffer(frame.index, releasedNs) }
                    .onSuccess {
                        lastSurfaceSubmitNs = releasedNs
                        surfaceSubmittedFrames++
                        rememberTimestamp(lastReleasedOutput, frame.ptsUs, releasedNs)
                        publishTelemetryLocked(releasedNs)
                    }
                    .onFailure { markCodecErrorLocked(it) }
            }
        }, delayMs)
    }

    private fun rememberTimestamp(times: MutableMap<Long, Long>, ptsUs: Long, timeNs: Long) {
        times[ptsUs] = timeNs
        if (times.size > 96) times.remove(times.keys.first())
    }

    private fun markCodecErrorLocked(error: Throwable) {
        codecFailed = true
        _telemetry.value = _telemetry.value.copy(
            decoderState = "DECODER ERROR",
            lastError = error.message ?: "MediaCodec decode error"
        )
    }

    private fun publishTelemetryLocked(now: Long) {
        if (lastTelemetryNs != 0L && now - lastTelemetryNs < 250_000_000L) return
        lastTelemetryNs = now
        val fpsInterval = now - fpsWindowStartNs
        if (fpsInterval >= 1_000_000_000L) {
            renderedFps = fpsWindowRendered * 1_000_000_000f / fpsInterval
            fpsWindowStartNs = now
            fpsWindowRendered = 0
        }
        _telemetry.value = _telemetry.value.copy(
            decoderState = when {
                codecFailed -> "DECODER ERROR"
                lastRenderedNs > 0L -> "RENDERING"
                lastSurfaceSubmitNs > 0L -> "SUBMITTING TO SURFACE"
                else -> "WAITING FOR VIDEO"
            },
            videoFps = renderedFps,
            decodedFrames = decodedFrames,
            surfaceSubmittedFrames = surfaceSubmittedFrames,
            renderedFrames = renderedFrames,
            droppedFrames = droppedFrames,
            droppedDecodedFrames = outputDrops,
            lastDecodeTimeMs = lastDecodeMs,
            lastInputQueueMs = lastInputQueueMs,
            lastRenderQueueMs = lastRenderQueueMs
        )
    }

    fun stopDecoder() = synchronized(codecLock) {
        releaseCodecLocked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activeSurface?.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
        worker?.quitSafely()
        worker = null
        codecHandler = null
        lastQualitySettings = null
        codecConfig = null
        needsKeyframe = false
        lastReceivedNs = 0L
        _telemetry.value = VideoTelemetry(
            surfaceReady = activeSurface?.isValid == true,
            surfaceWidth = _telemetry.value.surfaceWidth,
            surfaceHeight = _telemetry.value.surfaceHeight
        )
    }

    private fun releaseCodecLocked() {
        val codec = mediaCodec
        mediaCodec = null
        pendingInputs.clear()
        inputIndices.clear()
        submittedAt.clear()
        lastReleasedOutput.clear()
        pendingOutput = null
        inputWorkScheduled = false
        outputWorkScheduled = false
        codecFailed = false
        nextFrameNs = 0L
        lastPtsUs = -1L
        lastOutputNs = 0L
        lastSurfaceSubmitNs = 0L
        lastRenderedNs = 0L
        decoderReadyNs = 0L
        if (codec != null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun nanosToMs(ns: Long): Float = ns / 1_000_000f

    companion object {
        private const val MAX_PENDING_INPUT = 6
        /**
         * Inspects an Annex-B compressed access unit to determine whether it consists solely of
         * codec configuration NAL units (H.264 SPS=7/PPS=8 or HEVC VPS=32/SPS=33/PPS=34).
         */
        fun isCodecConfigAccessUnit(data: ByteArray, codec: StreamCodec): Boolean {
            var found = false
            val allConfig = forEachNalHeader(data) { nalHeader ->
                found = true
                when (codec) {
                    StreamCodec.H264 -> (nalHeader and 0x1F) in 7..8
                    StreamCodec.HEVC -> ((nalHeader ushr 1) and 0x3F) in 32..34
                }
            }
            return found && allConfig
        }

        internal fun isRandomAccessUnit(data: ByteArray, codec: StreamCodec): Boolean =
            !forEachNalHeader(data) { nalHeader ->
                when (codec) {
                    StreamCodec.H264 -> (nalHeader and 0x1F) != 5
                    StreamCodec.HEVC -> ((nalHeader ushr 1) and 0x3F) !in 16..21
                }
            }

        private inline fun forEachNalHeader(data: ByteArray, visit: (Int) -> Boolean): Boolean {
            var idx = 0
            while (idx + 3 < data.size) {
                val startCodeLen = when {
                    idx + 4 < data.size &&
                        data[idx] == 0.toByte() &&
                        data[idx + 1] == 0.toByte() &&
                        data[idx + 2] == 0.toByte() &&
                        data[idx + 3] == 1.toByte() -> 4
                    data[idx] == 0.toByte() &&
                        data[idx + 1] == 0.toByte() &&
                        data[idx + 2] == 1.toByte() -> 3
                    else -> 0
                }
                if (startCodeLen > 0 && idx + startCodeLen < data.size) {
                    val nalHeader = data[idx + startCodeLen].toInt() and 0xFF
                    if (!visit(nalHeader)) return false
                    idx += startCodeLen + 1
                } else {
                    idx++
                }
            }
            return true
        }

        fun probeDeviceCodecs(): CodecCapabilities = runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var h264Hw = false
            var hevcHw = false
            var h264Name = "Software / Unknown"
            var hevcName = "Unavailable"
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                }
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                        if (isHw && !h264Hw) {
                            h264Hw = true
                            h264Name = info.name
                        }
                    } else if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        if (isHw && !hevcHw) {
                            hevcHw = true
                            hevcName = info.name
                        }
                    }
                }
            }
            CodecCapabilities(
                h264HardwareSupported = h264Hw,
                hevcHardwareSupported = hevcHw,
                h264DecoderName = h264Name,
                hevcDecoderName = hevcName
            )
        }.getOrDefault(CodecCapabilities())
    }
}
