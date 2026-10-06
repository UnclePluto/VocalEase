package com.vocaease.patient.core.media

import kotlin.math.*

data class PitchSample(val recordingMs: Long, val frequencyHz: Float?, val confidence: Float)
fun pitchToMidi(frequencyHz: Float): Float = (69 + 12 * log2(frequencyHz / 440.0)).toFloat()

/** 单声部 YIN。降采样仅用于分析，编码仍收到完整原始 PCM。 */
class PitchDetector {
    fun detect(pcm: ShortArray, sampleRate: Int, recordingMs: Long): PitchSample {
        require(sampleRate > 0)
        val step = max(1, sampleRate / 8000)
        val rate = sampleRate.toDouble() / step
        val frame = DoubleArray(pcm.size / step) { pcm[it * step] / 32768.0 }
        val maximum = min((rate / MIN_HZ).toInt(), frame.size / 2)
        if (maximum < 3 || frame.isEmpty()) return PitchSample(recordingMs, null, 0f)
        val rms = sqrt(frame.sumOf { it * it } / frame.size)
        if (rms < MIN_RMS) return PitchSample(recordingMs, null, 0f)
        val normalized = DoubleArray(maximum + 1) { 1.0 }
        val length = frame.size - maximum
        var cumulative = 0.0
        for (lag in 1..maximum) {
            var difference = 0.0
            for (i in 0 until length) { val delta = frame[i] - frame[i + lag]; difference += delta * delta }
            cumulative += difference
            normalized[lag] = if (cumulative > 0) difference * lag / cumulative else 1.0
        }
        var lag = max(2, (rate / MAX_HZ).toInt())
        while (lag < maximum) {
            if (normalized[lag] < YIN_THRESHOLD) {
                while (lag + 1 < maximum && normalized[lag + 1] < normalized[lag]) lag++
                val left = normalized[lag - 1]; val center = normalized[lag]; val right = normalized[lag + 1]
                val denominator = left - 2 * center + right
                val refined = lag + if (abs(denominator) > 1e-12) .5 * (left - right) / denominator else 0.0
                val hz = (rate / refined).toFloat()
                return if (hz in MIN_HZ..MAX_HZ) PitchSample(recordingMs, hz, (1 - center).toFloat()) else PitchSample(recordingMs, null, 0f)
            }
            lag++
        }
        return PitchSample(recordingMs, null, 0f)
    }
    companion object {
        const val MIN_HZ = 65f
        const val MAX_HZ = 1000f
        const val MIN_RMS = .008
        const val YIN_THRESHOLD = .15
    }
}
