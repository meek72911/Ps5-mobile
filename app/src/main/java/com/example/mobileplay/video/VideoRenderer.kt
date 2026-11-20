package com.example.mobileplay.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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

data class StreamQualitySettings(
    val resolution: StreamResolution = StreamResolution.P720,
    val frameRate: StreamFrameRate = StreamFrameRate.FPS_60,
    val codec: StreamCodec = StreamCodec.H264,
    val autoQuality: Boolean = true,
    val manualBitrateKbps: Int = 12_000,
    val hardwareDecodingEnabled: Boolean = true,
    val audioVolume: Float = 0.85f
) {
    val effectiveBitrateKbps: Int
        get() = if (autoQuality) resolution.autoBitrateKbps else manualBitrateKbps.coerceIn(2_000, 30_000)

    fun summaryLabel(): String = "${resolution.label} • ${frameRate.label} • ${codec.label.substringBefore(" ")}"
}

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
    val lastError: String? = null
)

/**
 * Thread-safe hardware MediaCodec video pipeline wired to libchiaki compressed H.264/HEVC callbacks.
 *
 * Features:
 * - Synchronized MediaCodec & Surface lifecycle (safe against surface destruction during JNI callbacks).
 * - Automatic Annex-B NAL inspection for H.264 SPS/PPS and HEVC VPS/SPS/PPS codec configuration buffers.
 * - Dynamic output format/resolution tracking via INFO_OUTPUT_FORMAT_CHANGED.
 * - Never renders synthetic or fake PS5 gameplay video and never fabricates FPS telemetry.
 */
class VideoRenderer : SurfaceHolder.Callback {
    private val _telemetry = MutableStateFlow(VideoTelemetry())
    val telemetry: StateFlow<VideoTelemetry> = _telemetry.asStateFlow()

    val capabilities: CodecCapabilities = probeDeviceCodecs()

    private val codecLock = Any()
    private var activeSurface: Surface? = null
    private var mediaCodec: MediaCodec? = null
    private var activeCodec: StreamCodec = StreamCodec.H264
    private var lastQualitySettings: StreamQualitySettings? = null
    private var decodedFrames = 0L
    private var droppedFrames = 0L
    private var consecutiveFaults = 0
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0

    fun attachTo(surfaceView: AspectRatioSurfaceView) {
        surfaceView.holder.addCallback(this)
    }

    fun detachFrom(surfaceView: AspectRatioSurfaceView) {
        surfaceView.holder.removeCallback(this)
        stopDecoder()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(codecLock) {
            activeSurface = holder.surface
            _telemetry.value = _telemetry.value.copy(
                surfaceReady = holder.surface.isValid,
                decoderState = if (mediaCodec != null) "DECODING" else "STANDBY — 16:9 Surface ready"
            )
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(codecLock) {
            activeSurface = holder.surface
            _telemetry.value = _telemetry.value.copy(
                surfaceReady = holder.surface.isValid,
                surfaceWidth = width,
                surfaceHeight = height
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(codecLock) {
            releaseCodecLocked()
            activeSurface = null
            _telemetry.value = _telemetry.value.copy(
                surfaceReady = false,
                decoderState = "SURFACE RELEASED",
                videoFps = 0f
            )
        }
    }

    fun configureHardwareDecoder(settings: StreamQualitySettings): Result<Unit> = synchronized(codecLock) {
        runCatching {
            val surface = activeSurface
            if (surface == null || !surface.isValid) {
                error("16:9 video SurfaceView is not ready for MediaCodec output.")
            }
            releaseCodecLocked()
            val mime = settings.codec.mimeType
            val format = MediaFormat.createVideoFormat(
                mime,
                settings.resolution.width,
                settings.resolution.height
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, surface, null, 0)
                codec.start()
            } catch (t: Throwable) {
                runCatching { codec.release() }
                throw t
            }
            mediaCodec = codec
            activeCodec = settings.codec
            lastQualitySettings = settings
            decodedFrames = 0L
            droppedFrames = 0L
            consecutiveFaults = 0
            fpsWindowStartMs = System.currentTimeMillis()
            fpsWindowFrames = 0
            _telemetry.value = _telemetry.value.copy(
                decoderState = "CONFIGURED (${settings.resolution.width}×${settings.resolution.height})",
                codecMime = mime,
                decodedWidth = settings.resolution.width,
                decodedHeight = settings.resolution.height,
                videoFps = 0f,
                decodedFrames = 0L,
                droppedFrames = 0L,
                lastError = null
            )
        }.onFailure { err ->
            _telemetry.value = _telemetry.value.copy(
                decoderState = "DECODER ERROR",
                lastError = err.message ?: "Failed to initialize MediaCodec"
            )
        }
    }

    fun submitAccessUnit(data: ByteArray, presentationTimeUs: Long): Boolean = synchronized(codecLock) {
        val codec = mediaCodec ?: return false
        if (data.isEmpty()) return false
        val startNs = System.nanoTime()
        val ptsUs = if (presentationTimeUs > 0L) presentationTimeUs else System.nanoTime() / 1_000L
        val flags = if (isCodecConfigAccessUnit(data, activeCodec)) {
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        } else {
            0
        }

        return runCatching {
            val inputIndex = codec.dequeueInputBuffer(4_000L)
            if (inputIndex < 0) {
                droppedFrames++
                _telemetry.value = _telemetry.value.copy(droppedFrames = droppedFrames)
                return@runCatching false
            }
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return@runCatching false
            if (inputBuffer.capacity() < data.size) {
                droppedFrames++
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                _telemetry.value = _telemetry.value.copy(
                    droppedFrames = droppedFrames,
                    lastError = "Video packet (${data.size}B) exceeded MediaCodec input buffer capacity."
                )
                return@runCatching false
            }
            inputBuffer.clear()
            inputBuffer.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, flags)

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    val w = if (outFmt.containsKey(MediaFormat.KEY_WIDTH)) outFmt.getInteger(MediaFormat.KEY_WIDTH) else _telemetry.value.decodedWidth
                    val h = if (outFmt.containsKey(MediaFormat.KEY_HEIGHT)) outFmt.getInteger(MediaFormat.KEY_HEIGHT) else _telemetry.value.decodedHeight
                    _telemetry.value = _telemetry.value.copy(decodedWidth = w, decodedHeight = h)
                } else {
                    codec.releaseOutputBuffer(outputIndex, true)
                    decodedFrames++
                    fpsWindowFrames++
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }

            consecutiveFaults = 0
            val nowMs = System.currentTimeMillis()
            val elapsedMs = nowMs - fpsWindowStartMs
            var measuredFps = _telemetry.value.videoFps
            if (elapsedMs >= 1_000L) {
                measuredFps = (fpsWindowFrames * 1_000f) / elapsedMs.toFloat()
                fpsWindowStartMs = nowMs
                fpsWindowFrames = 0
            }

            val decodeMs = (System.nanoTime() - startNs) / 1_000_000f
            _telemetry.value = _telemetry.value.copy(
                decoderState = "DECODING",
                videoFps = measuredFps,
                decodedFrames = decodedFrames,
                droppedFrames = droppedFrames,
                lastDecodeTimeMs = decodeMs
            )
            true
        }.onFailure { err ->
            consecutiveFaults++
            _telemetry.value = _telemetry.value.copy(
                decoderState = "DECODE FAULT",
                lastError = err.message ?: "MediaCodec decode error"
            )
            // Attempt automatic decoder reset once on recoverable MediaCodec errors if surface is still valid.
            if (consecutiveFaults >= 3) {
                val retrySettings = lastQualitySettings
                if (retrySettings != null && activeSurface?.isValid == true) {
                    consecutiveFaults = 0
                    configureHardwareDecoder(retrySettings)
                }
            }
        }.getOrDefault(false)
    }

    fun stopDecoder() = synchronized(codecLock) {
        releaseCodecLocked()
        lastQualitySettings = null
        _telemetry.value = _telemetry.value.copy(
            decoderState = if (activeSurface?.isValid == true) {
                "STANDBY — No active PS5 video stream"
            } else {
                "IDLE — No active PS5 video stream"
            },
            videoFps = 0f
        )
    }

    private fun releaseCodecLocked() {
        runCatching {
            mediaCodec?.stop()
            mediaCodec?.release()
        }
        mediaCodec = null
    }

    companion object {
        /**
         * Inspects an Annex-B compressed access unit to determine whether it consists solely of
         * codec configuration NAL units (H.264 SPS=7/PPS=8 or HEVC VPS=32/SPS=33/PPS=34).
         */
        fun isCodecConfigAccessUnit(data: ByteArray, codec: StreamCodec): Boolean {
            if (data.size < 5) return false
            var idx = 0
            var foundAnyNal = false
            var allConfig = true
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
                    foundAnyNal = true
                    val isConfig = when (codec) {
                        StreamCodec.H264 -> {
                            val nalType = nalHeader and 0x1F
                            nalType == 7 || nalType == 8
                        }
                        StreamCodec.HEVC -> {
                            val nalType = (nalHeader ushr 1) and 0x3F
                            nalType == 32 || nalType == 33 || nalType == 34
                        }
                    }
                    if (!isConfig) {
                        allConfig = false
                        break
                    }
                    idx += startCodeLen + 1
                } else {
                    idx++
                }
            }
            return foundAnyNal && allConfig
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
