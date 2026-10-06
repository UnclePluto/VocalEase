package com.vocaease.patient.feature.training

import com.vocaease.patient.core.media.PitchSample
import com.vocaease.patient.core.media.pitchToMidi
import com.vocaease.patient.core.network.dto.ReferenceNoteDto
import kotlin.math.round

fun timelineX(noteMs: Long, positionMs: Long, widthPx: Float): Float =
    widthPx / 4f + (noteMs - positionMs) * widthPx / 8000f

private fun paddedRange(low: Float, high: Float): Pair<Float, Float> {
    val center = (low + high) / 2f
    val span = (high - low + 4f).coerceAtLeast(12f)
    val bottom = (center - span / 2f).coerceIn(0f, (127f - span).coerceAtLeast(0f))
    return bottom to (bottom + span).coerceAtMost(127f)
}

fun pitchRange(notes: List<Float>): Pair<Float, Float> {
    val sorted = notes.filter { it.isFinite() && it in 0f..127f }.sorted()
    if (sorted.isEmpty()) return 52f to 68f
    return paddedRange(sorted.first(), sorted.last())
}

/** 按有声持续时间而非片段个数计算音域，短促倍频和低置信度帧不撑大显示。 */
fun referencePitchRange(notes: List<ReferenceNoteDto>): Pair<Float, Float> {
    val stable = notes.filter { it.confidence >= .85f && it.midiNote.isFinite() &&
        it.midiNote in 0f..127f && it.endMs - it.startMs >= 80 }.sortedBy { it.midiNote }
    if (stable.isEmpty()) return pitchRange(notes.filter { it.confidence >= .85f }.map { it.midiNote })
    val duration = stable.sumOf { it.endMs - it.startMs }
    fun percentile(fraction: Double): Float {
        var cumulative = 0L
        for (note in stable) {
            cumulative += note.endMs - note.startMs
            if (cumulative >= duration * fraction) return note.midiNote
        }
        return stable.last().midiNote
    }
    return paddedRange(percentile(.02), percentile(.98))
}

/** 无原唱参考时以稳定人声为中心；中间六个半音以内保持不动，防止逐帧缩放。 */
class PatientPitchViewport {
    private var center = 60f
    private var initialized = false
    fun update(history: List<PitchSample>): Pair<Float, Float> {
        val recent = history.takeLast(40).filter { it.frequencyHz != null &&
            it.frequencyHz.isFinite() && it.frequencyHz > 0f && it.confidence >= .85f }
        if (recent.size >= 8 && recent.last().recordingMs - recent.first().recordingMs >= 300) {
            val pitches = recent.map { pitchToMidi(requireNotNull(it.frequencyHz)) }.sorted()
            val median = round(pitches[pitches.size / 2]).coerceIn(8f, 119f)
            if (!initialized) { center = median; initialized = true }
            else if (kotlin.math.abs(median - center) > 3f) center += (median - center).coerceIn(-1f, 1f)
        }
        return center - 8f to center + 8f
    }
}

fun pitchY(midi: Float, range: Pair<Float, Float>, height: Float): Float =
    height * (1 - ((midi - range.first) / (range.second - range.first).coerceAtLeast(1f)).coerceIn(0f, 1f))
