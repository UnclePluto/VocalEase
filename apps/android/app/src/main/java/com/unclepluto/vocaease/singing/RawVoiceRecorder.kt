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
                started.complete(RecordingStart(SystemClock.elapsedRealtimeNanos()))
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
    val silenceAmplitude: Int = 160,
    val lowRmsDbfs: Double = -42.0,
    val clippingAmplitude: Int = 32_700,
    val silenceRatioWarning: Double = 0.35,
    val clippingRatioWarning: Double = 0.005,
    val stageToleranceMs: Long = 250,
    val markerWindowMs: Int = 250,
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
            check(dataBytes <= file.length() - WAV_HEADER_BYTES)
            if (sampleRate != RAW_SAMPLE_RATE) warnings += "采样率不是 48 kHz"
            if (channels != 1) warnings += "声道数不是单声道"
            if (bitDepth != 16) warnings += "位深不是 16-bit"
            val samples = ShortArray((dataBytes / 2).toInt())
            val bytes = ByteArray(samples.size * 2)
            input.readFully(bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            val durationMs = samples.size.toLong() * 1_000 / sampleRate.coerceAtLeast(1)
            val stageComplete = abs(durationMs - expectedDurationMs) <= thresholds.stageToleranceMs
            if (!stageComplete) warnings += "录音时长与三阶段预期不一致"
            val sumSquares = samples.fold(0.0) { sum, sample ->
                val normalized = sample.toDouble() / Short.MAX_VALUE
                sum + normalized * normalized
            }
            val rms = if (samples.isEmpty()) 0.0 else sqrt(sumSquares / samples.size)
            val rmsDbfs = if (rms <= 0.0) -120.0 else 20 * log10(rms)
            val silenceRatio = samples.count { abs(it.toInt()) <= thresholds.silenceAmplitude }
                .toDouble() / samples.size.coerceAtLeast(1)
            val clippingRatio = samples.count {
                abs(it.toInt()) >= thresholds.clippingAmplitude
            }.toDouble() / samples.size.coerceAtLeast(1)
            if (rmsDbfs < thresholds.lowRmsDbfs) warnings += "整体录音音量偏低"
            if (silenceRatio > thresholds.silenceRatioWarning) warnings += "静音占比较高"
            if (clippingRatio > thresholds.clippingRatioWarning) warnings += "存在削波风险"
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
                rmsDbfs = rmsDbfs,
                silenceRatio = silenceRatio,
                clippingRatio = clippingRatio,
                stageComplete = stageComplete,
                usedHeadphones = usedHeadphones,
                routeRisk = routeRisk,
                fileWarnings = warnings,
                markers = qualityMarkers(samples, sampleRate, thresholds),
            )
        }
    }.getOrElse {
        invalidReport("WAV 文件损坏或不可读", usedHeadphones, routeRisk, clock)
    }
}

private fun qualityMarkers(
    samples: ShortArray,
    sampleRate: Int,
    thresholds: QualityThresholds,
): List<QualityMarker> {
    val windowSamples = sampleRate * thresholds.markerWindowMs / 1_000
    if (windowSamples <= 0) return emptyList()
    val markers = mutableListOf<QualityMarker>()
    var start = 0
    while (start < samples.size) {
        val end = minOf(start + windowSamples, samples.size)
        var silent = 0
        var clipped = 0
        for (index in start until end) {
            val amplitude = abs(samples[index].toInt())
            if (amplitude <= thresholds.silenceAmplitude) silent++
            if (amplitude >= thresholds.clippingAmplitude) clipped++
        }
        val size = (end - start).coerceAtLeast(1)
        val silenceRatio = silent.toDouble() / size
        val clippingRatio = clipped.toDouble() / size
        val kind: String
        val value: Double
        when {
            clippingRatio > thresholds.clippingRatioWarning -> {
                kind = "clipping"
                value = clippingRatio
            }
            silenceRatio > 0.95 -> {
                kind = "silence"
                value = silenceRatio
            }
            else -> {
                start = end
                continue
            }
        }
        markers += QualityMarker(
            kind = kind,
            startMs = start.toLong() * 1_000 / sampleRate,
            endMs = end.toLong() * 1_000 / sampleRate,
            value = value,
        )
        start = end
    }
    return markers
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
