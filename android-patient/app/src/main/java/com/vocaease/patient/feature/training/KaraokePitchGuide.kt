package com.vocaease.patient.feature.training

import com.vocaease.patient.core.media.PlaybackAnchor
import com.vocaease.patient.core.media.PitchSample
import com.vocaease.patient.core.media.pitchToMidi
import com.vocaease.patient.core.network.dto.ReferenceNoteDto
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

private const val MIN_CONFIDENCE = .85f
private const val NOTE_HOLD_MS = 80L
private const val VOICED_GAP_MS = 60L
private const val MEDIAN_RADIUS_MS = 60L
private const val ENTER_TOLERANCE = .75f
private const val EXIT_TOLERANCE = .9f
private const val MAX_SAMPLE_GAP_MS = 120L
private const val MAX_SAMPLE_AGE_MS = 150L

/** 展示层音符：时长加权中位数去抖、整数半音量化、持续换音确认。保留原始参考数据。 */
fun buildKaraokeGuide(raw: List<ReferenceNoteDto>): List<ReferenceNoteDto> {
    val voiced = raw.filter { it.startMs >= 0 && it.endMs > it.startMs &&
        it.midiNote.isFinite() && it.midiNote in 0f..127f &&
        it.confidence.isFinite() && it.confidence >= MIN_CONFIDENCE }
    val result = mutableListOf<ReferenceNoteDto>()
    var runStart = 0
    while (runStart < voiced.size) {
        var runEnd = runStart + 1
        while (runEnd < voiced.size && voiced[runEnd].startMs - voiced[runEnd - 1].endMs <= VOICED_GAP_MS) runEnd++
        val run = voiced.subList(runStart, runEnd)
        var start = run.first().startMs
        var end = start
        var pitch = round(run.first().midiNote)
        var confidence = run.first().confidence
        var pendingPitch: Float? = null
        var pendingStart = 0L
        var pendingDuration = 0L
        for (note in run) {
            val center = note.startMs + (note.endMs - note.startMs) / 2
            val low = center - MEDIAN_RADIUS_MS
            val high = center + MEDIAN_RADIUS_MS
            // 区段不重叠，以二分查找限制到120ms邻域，避免逐音符扫描整首歌。
            var left = 0
            var right = run.size
            while (left < right) {
                val middle = (left + right) / 2
                if (run[middle].endMs <= low) left = middle + 1 else right = middle
            }
            val neighbors = mutableListOf<Pair<Float, Long>>()
            var index = left
            while (index < run.size && run[index].startMs < high) {
                val other = run[index++]
                val overlap = min(other.endMs, high) - maxOf(other.startMs, low)
                if (overlap > 0) neighbors += other.midiNote to overlap
            }
            val sorted = neighbors.sortedBy { it.first }
            val half = sorted.sumOf { it.second } / 2.0
            var duration = 0L
            val median = sorted.firstOrNull { duration += it.second; duration >= half }?.first ?: note.midiNote
            val nextPitch = if (abs(median - pitch) <= .65f) pitch else round(median)
            confidence = min(confidence, note.confidence)
            if (nextPitch == pitch) {
                pendingPitch = null
                pendingDuration = 0
            } else {
                if (pendingPitch != nextPitch) {
                    pendingPitch = nextPitch
                    pendingStart = note.startMs
                    pendingDuration = 0
                }
                pendingDuration += note.endMs - note.startMs
                if (pendingDuration >= NOTE_HOLD_MS) {
                    if (pendingStart - start >= NOTE_HOLD_MS) result += ReferenceNoteDto(start, pendingStart, pitch, confidence)
                    start = pendingStart
                    pitch = nextPitch
                    pendingPitch = null
                    pendingDuration = 0
                }
            }
            end = note.endMs
        }
        if (end - start >= NOTE_HOLD_MS) result += ReferenceNoteDto(start, end, pitch, confidence)
        runStart = runEnd
    }
    return result
}

data class KaraokeMatchSpan(val startMs: Long, val endMs: Long)
data class KaraokeFeedback(val isMatching: Boolean, val matchedSpans: List<KaraokeMatchSpan>)

private fun targetAt(notes: List<ReferenceNoteDto>, time: Long): ReferenceNoteDto? {
    var left = 0
    var right = notes.size
    while (left < right) {
        val middle = (left + right) / 2
        if (notes[middle].endMs <= time) left = middle + 1 else right = middle
    }
    return notes.getOrNull(left)?.takeIf { time >= it.startMs && time < it.endMs }
}

/** 仅记录真实同音域命中；播放经过、错音、静音、低置信度及切换停顿均不点亮。 */
fun karaokeFeedback(
    notes: List<ReferenceNoteDto>,
    history: List<PitchSample>,
    anchors: List<PlaybackAnchor>,
    currentSample: PitchSample,
    recordingMs: Long,
    songMs: Long,
    playing: Boolean,
): KaraokeFeedback {
    val spans = mutableListOf<KaraokeMatchSpan>()
    var runRecordingStart: Long? = null
    var runSongStart = 0L
    var previousRecording = -1L
    var previousSong = -1L
    var previousSegment = -1
    var stable = false
    for (sample in history) {
        val anchor = anchors.lastOrNull { it.recordingMs <= sample.recordingMs }
        val time = anchor?.let { it.songMs + sample.recordingMs - it.recordingMs }
        val target = time?.let { targetAt(notes, it) }
        val hz = sample.frequencyHz
        val valid = anchor?.playing == true && hz != null && hz.isFinite() && hz > 0 &&
            sample.confidence.isFinite() && sample.confidence >= MIN_CONFIDENCE && target != null
        val contiguous = sample.recordingMs > previousRecording && sample.recordingMs - previousRecording <= MAX_SAMPLE_GAP_MS &&
            anchor?.segment == previousSegment && time != null && time > previousSong && time - previousSong <= MAX_SAMPLE_GAP_MS
        if (!contiguous) { runRecordingStart = null; stable = false }
        val matches = valid && abs(pitchToMidi(requireNotNull(hz)) - requireNotNull(target).midiNote) <=
            if (stable) EXIT_TOLERANCE else ENTER_TOLERANCE
        if (matches) {
            if (runRecordingStart == null) { runRecordingStart = sample.recordingMs; runSongStart = requireNotNull(time) }
            stable = sample.recordingMs - requireNotNull(runRecordingStart) >= NOTE_HOLD_MS
            if (stable) {
                val span = KaraokeMatchSpan(runSongStart, requireNotNull(time))
                if (spans.lastOrNull()?.startMs == span.startMs) spans[spans.lastIndex] = span else spans += span
            }
        } else { runRecordingStart = null; stable = false }
        previousRecording = sample.recordingMs
        previousSong = time ?: -1L
        previousSegment = anchor?.segment ?: -1
    }
    val age = recordingMs - currentSample.recordingMs
    val currentTarget = targetAt(notes, songMs)
    val currentHz = currentSample.frequencyHz
    val matchesNow = playing && stable && history.lastOrNull() == currentSample && age in 0..MAX_SAMPLE_AGE_MS &&
        currentTarget != null && currentHz != null && currentHz.isFinite() && currentHz > 0 &&
        currentSample.confidence >= MIN_CONFIDENCE && abs(pitchToMidi(currentHz) - currentTarget.midiNote) <= EXIT_TOLERANCE
    return KaraokeFeedback(matchesNow, spans)
}
