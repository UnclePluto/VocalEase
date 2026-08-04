package com.unclepluto.vocaease.singing

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred

data class RecordingResult(
    val file: File,
    val frames: Long,
    val durationMs: Long,
)

data class RecordingStart(
    val monotonicNs: Long,
    val actualInputType: String?,
)

class RawVoiceRecorder(
    private val target: File,
    private val parameters: RecordingParameters,
) {
    private val shouldStop = AtomicBoolean(false)
    private val frames = AtomicLong(0)
    val started = CompletableDeferred<RecordingStart>()

    fun currentFrame(): Long = frames.get()

    fun stop() {
        shouldStop.set(true)
    }

    @SuppressLint("MissingPermission")
    fun record(): RecordingResult {
        target.parentFile?.mkdirs()
        val record = AudioRecord.Builder()
            .setAudioSource(parameters.audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(parameters.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(parameters.bufferBytes)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        val buffer = ByteArray(parameters.bufferBytes)
        try {
            WavStreamWriter(target, parameters.sampleRate).use { writer ->
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
                started.complete(
                    RecordingStart(
                        monotonicNs = SystemClock.elapsedRealtimeNanos(),
                        actualInputType = record.routedDevice?.type?.let {
                            audioDeviceTypeLabel(it, output = false)
                        },
                    )
                )
                while (!shouldStop.get()) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 -> {
                            writer.write(buffer, count)
                            frames.addAndGet((count / RAW_BYTES_PER_FRAME).toLong())
                        }
                        count == 0 -> Unit
                        else -> error("麦克风采集错误：$count")
                    }
                }
            }
        } catch (error: Throwable) {
            if (!started.isCompleted) started.completeExceptionally(error)
            target.delete()
            throw error
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            record.release()
        }
        return RecordingResult(
            file = target,
            frames = frames.get(),
            durationMs = frames.get() * 1_000 / parameters.sampleRate,
        )
    }
}

class WavStreamWriter(
    file: File,
    private val sampleRate: Int = RAW_SAMPLE_RATE,
) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        output.setLength(0)
        output.write(ByteArray(WAV_HEADER_BYTES))
    }

    fun write(buffer: ByteArray, count: Int) {
        check(!closed) { "WAV 已关闭" }
        output.write(buffer, 0, count)
        dataBytes += count
    }

    override fun close() {
        if (closed) return
        closed = true
        output.seek(0)
        output.write(wavHeader(dataBytes, sampleRate))
        output.fd.sync()
        output.close()
    }
}

fun wavHeader(dataBytes: Long, sampleRate: Int = RAW_SAMPLE_RATE): ByteArray {
    require(dataBytes in 0..0xffff_ffffL)
    val byteRate = sampleRate * RAW_BYTES_PER_FRAME
    return ByteBuffer.allocate(WAV_HEADER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt((36 + dataBytes).toInt())
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(1)
        .putInt(sampleRate)
        .putInt(byteRate)
        .putShort(RAW_BYTES_PER_FRAME.toShort())
        .putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(dataBytes.toInt())
        .array()
}

data class QualityThresholds(
    val silenceAmplitude: Int = 32,
    val lowRmsDbfs: Double = -42.0,
    val clippingAmplitude: Int = 32_734,
    val silenceRatioWarning: Double = 0.8,
    val clippingRatioWarning: Double = 0.01,
    val stageToleranceMs: Long = 250,
    val markerWindowMs: Int = 500,
)

data class QualityMarker(
    val kind: String,
    val startMs: Long,
    val endMs: Long,
    val value: Double,
)

data class ClientQualityReport(
    val source: String = "android",
    val algorithmVersion: String = "android-wav-qc-v1",
    val generatedAt: Instant,
    val status: String,
    val readable: Boolean,
    val sampleRate: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val durationMs: Long?,
    val rmsDbfs: Double?,
    val silenceRatio: Double?,
    val clippingRatio: Double?,
    val stageComplete: Boolean,
    val usedHeadphones: Boolean,
    val routeRisk: Boolean,
    val fileWarnings: List<String>,
    val markers: List<QualityMarker>,
) {
    val participantSummary: String
        get() = when (status) {
            "ok" -> "录音技术检查通过"
            "warning" -> "录音已提交，但存在技术质量提示"
            else -> "录音文件技术检查失败"
        }
}

fun analyzeWav(
    file: File,
    expectedDurationMs: Long,
    usedHeadphones: Boolean,
    routeRisk: Boolean,
    thresholds: QualityThresholds = QualityThresholds(),
    clock: Clock = Clock.systemUTC(),
): ClientQualityReport {
    val warnings = mutableListOf<String>()
    if (!file.isFile || file.length() < WAV_HEADER_BYTES) {
        return invalidReport("WAV 文件损坏或不可读", usedHeadphones, routeRisk, clock)
    }
    return runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(WAV_HEADER_BYTES)
            input.readFully(header)
            val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
            check(String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
            val channels = view.getShort(22).toInt()
            val sampleRate = view.getInt(24)
            val bitDepth = view.getShort(34).toInt()
            val dataBytes = view.getInt(40).toLong() and 0xffff_ffffL
            check(
                channels > 0 &&
                    sampleRate > 0 &&
                    bitDepth == 16 &&
                    dataBytes <= file.length() - WAV_HEADER_BYTES &&
                    dataBytes % 2L == 0L
            )
            if (sampleRate != RAW_SAMPLE_RATE) warnings += "采样率不是 48 kHz"
            if (channels != 1) warnings += "声道数不是单声道"
            val stats = StreamingQualityStats(sampleRate, thresholds)
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var remaining = dataBytes
            var channelIndex = 0
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                input.readFully(buffer, 0, count)
                val samples = ByteBuffer.wrap(buffer, 0, count)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                while (samples.hasRemaining()) {
                    val sample = samples.get()
                    if (channelIndex == 0) stats.add(sample)
                    channelIndex = (channelIndex + 1) % channels
                }
                remaining -= count
            }
            val metrics = stats.finish()
            val frameCount = dataBytes / (RAW_BYTES_PER_FRAME * channels)
            val durationMs = frameCount * 1_000 / sampleRate
            val stageComplete = abs(durationMs - expectedDurationMs) <= thresholds.stageToleranceMs
            if (!stageComplete) warnings += "录音时长与三阶段预期不一致"
            if (metrics.rmsDbfs < thresholds.lowRmsDbfs) warnings += "整体录音音量偏低"
            if (metrics.silenceRatio >= thresholds.silenceRatioWarning) {
                warnings += "静音占比较高"
            }
            if (metrics.clippingRatio >= thresholds.clippingRatioWarning) {
                warnings += "存在削波风险"
            }
            if (!usedHeadphones) warnings += "无耳机录制可能包含伴奏串音"
            if (routeRisk) warnings += "录制期间检测到音频路由风险"
            ClientQualityReport(
                generatedAt = clock.instant(),
                status = if (warnings.isEmpty()) "ok" else "warning",
                readable = true,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = bitDepth,
                durationMs = durationMs,
                rmsDbfs = metrics.rmsDbfs,
                silenceRatio = metrics.silenceRatio,
                clippingRatio = metrics.clippingRatio,
                stageComplete = stageComplete,
                usedHeadphones = usedHeadphones,
                routeRisk = routeRisk,
                fileWarnings = warnings,
                markers = metrics.markers,
            )
        }
    }.getOrElse {
        invalidReport("WAV 文件损坏或不可读", usedHeadphones, routeRisk, clock)
    }
}

private data class StreamingMetrics(
    val rmsDbfs: Double,
    val silenceRatio: Double,
    val clippingRatio: Double,
    val markers: List<QualityMarker>,
)

private class StreamingQualityStats(
    private val sampleRate: Int,
    thresholds: QualityThresholds,
) {
    private val thresholds = thresholds
    private val windowSamples =
        maxOf(1, (sampleRate.toLong() * thresholds.markerWindowMs / 1_000).toInt())
    private var sampleCount = 0L
    private var silentCount = 0L
    private var clippedCount = 0L
    private var sumSquares = 0.0
    private var windowStart = 0L
    private var windowCount = 0
    private var windowSilent = 0
    private var windowClipped = 0
    private var windowSumSquares = 0.0
    private val markers = mutableListOf<QualityMarker>()

    fun add(sample: Short) {
        val numeric = sample.toDouble()
        val amplitude = abs(sample.toInt())
        sampleCount++
        sumSquares += numeric * numeric
        if (amplitude <= thresholds.silenceAmplitude) silentCount++
        if (amplitude >= thresholds.clippingAmplitude) clippedCount++
        windowCount++
        windowSumSquares += numeric * numeric
        if (amplitude <= thresholds.silenceAmplitude) windowSilent++
        if (amplitude >= thresholds.clippingAmplitude) windowClipped++
        if (windowCount == windowSamples) flushWindow()
    }

    fun finish(): StreamingMetrics {
        if (windowCount > 0) flushWindow()
        return StreamingMetrics(
            rmsDbfs = dbfs(sumSquares, sampleCount),
            silenceRatio = silentCount.toDouble() / sampleCount.coerceAtLeast(1),
            clippingRatio = clippedCount.toDouble() / sampleCount.coerceAtLeast(1),
            markers = markers,
        )
    }

    private fun flushWindow() {
        val end = windowStart + windowCount
        val silenceRatio = windowSilent.toDouble() / windowCount.coerceAtLeast(1)
        val clippingRatio = windowClipped.toDouble() / windowCount.coerceAtLeast(1)
        val rmsDbfs = dbfs(windowSumSquares, windowCount.toLong())
        val startMs = Math.round(windowStart.toDouble() * 1_000 / sampleRate)
        val endMs = Math.round(end.toDouble() * 1_000 / sampleRate)
        if (silenceRatio >= 0.95) {
            markers += QualityMarker("silence", startMs, endMs, silenceRatio)
        } else if (rmsDbfs < thresholds.lowRmsDbfs) {
            markers += QualityMarker("low_volume", startMs, endMs, rmsDbfs)
        }
        if (clippingRatio >= thresholds.clippingRatioWarning) {
            markers += QualityMarker("clipping", startMs, endMs, clippingRatio)
        }
        windowStart = end
        windowCount = 0
        windowSilent = 0
        windowClipped = 0
        windowSumSquares = 0.0
    }
}

private fun dbfs(sumSquares: Double, count: Long): Double {
    if (count <= 0 || sumSquares <= 0.0) return -120.0
    return 20 * log10(sqrt(sumSquares / count) / 32_768.0)
}

private fun invalidReport(
    warning: String,
    usedHeadphones: Boolean,
    routeRisk: Boolean,
    clock: Clock,
) = ClientQualityReport(
    generatedAt = clock.instant(),
    status = "invalid",
    readable = false,
    sampleRate = null,
    channels = null,
    bitDepth = null,
    durationMs = null,
    rmsDbfs = null,
    silenceRatio = null,
    clippingRatio = null,
    stageComplete = false,
    usedHeadphones = usedHeadphones,
    routeRisk = routeRisk,
    fileWarnings = listOf(warning),
    markers = emptyList(),
)

private const val WAV_HEADER_BYTES = 44
private const val STREAM_BUFFER_BYTES = 64 * 1024
