package com.vocaease.patient.core.media

import android.media.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

data class PcmBlock(val samples: ShortArray, val sampleRate: Int, val firstSampleNanos: Long)
data class AudioCaptureResult(val sampleRate: Int, val captureStartNanos: Long, val durationMillis: Long)
interface PatientMicrophone {
    var onFailure: (Throwable) -> Unit get() = {}
        set(value) {}
    fun start(output: File, onPcm: (PcmBlock) -> Unit)
    suspend fun stop(): AudioCaptureResult
    fun release()
}

/** 唯一 AudioRecord 所有者；在 IO 线程同步编码，不丢弃编码 PCM。 */
class MicrophonePcmCapture : PatientMicrophone {
    override var onFailure: (Throwable) -> Unit = {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopping = AtomicBoolean()
    private var recorder: AudioRecord? = null
    private var result = CompletableDeferred<AudioCaptureResult>()
    private var worker: Job? = null
    override fun start(output: File, onPcm: (PcmBlock) -> Unit) {
        check(worker == null)
        worker = scope.launch {
            var encoder: PatientAudioEncoder? = null
            try {
                val audio = createRecorder(); recorder = audio
                val rate = audio.sampleRate
                encoder = PatientAudioEncoder(output, rate)
                val buffer = ShortArray((rate * .046).toInt())
                audio.startRecording()
                val startEstimate = System.nanoTime()
                var frames = 0L; var origin: Long? = null
                val stamp = AudioTimestamp()
                while (!stopping.get()) {
                    val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (stopping.get()) break
                    check(count > 0) { "麦克风读取失败" }
                    if (origin == null) {
                        origin = if (audio.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) stamp.nanoTime - stamp.framePosition * 1_000_000_000L / rate else startEstimate
                    }
                    encoder.write(buffer, count, frames * 1_000_000L / rate)
                    onPcm(PcmBlock(buffer.copyOf(count), rate, origin + frames * 1_000_000_000L / rate))
                    frames += count
                }
                encoder.finish(frames * 1_000_000L / rate)
                result.complete(AudioCaptureResult(rate, origin ?: startEstimate, frames * 1000 / rate))
            } catch (error: Throwable) {
                result.completeExceptionally(error); onFailure(error); output.delete()
            } finally {
                runCatching { recorder?.stop() };recorder?.release();recorder=null
                encoder?.release()
            }
        }
    }
    override suspend fun stop(): AudioCaptureResult {
        stopping.set(true)
        runCatching { recorder?.stop() }
        return withTimeout(10_000) { result.await() }
    }
    override fun release() {
        stopping.set(true);runCatching { recorder?.stop() };scope.cancel()
    }
    private fun createRecorder(): AudioRecord {
        for (rate in intArrayOf(48000,44100)) {
            val minimum = AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val record = try { AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 2,rate / 5)).build()
            } catch (denied: SecurityException) {
                throw IllegalStateException("麦克风权限已撤销", denied)
            } catch (_: IllegalArgumentException) { continue }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        error("麦克风不支持所需采样率")
    }
}
