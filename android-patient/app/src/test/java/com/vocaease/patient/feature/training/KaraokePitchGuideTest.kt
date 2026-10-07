package com.vocaease.patient.feature.training

import com.vocaease.patient.core.media.PlaybackAnchor
import com.vocaease.patient.core.media.PitchSample
import com.vocaease.patient.core.media.SongPlaybackMode
import com.vocaease.patient.core.network.dto.ReferenceNoteDto
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class KaraokePitchGuideTest {
    private fun note(start: Long, end: Long, midi: Float, confidence: Float = .98f) =
        ReferenceNoteDto(start, end, midi, confidence)
    private fun sample(time: Long, midi: Float?, confidence: Float = .98f) =
        PitchSample(time, midi?.let { (440.0 * 2.0.pow((it - 69) / 12.0)).toFloat() }, confidence)
    private val anchors = listOf(PlaybackAnchor(0, 0, SongPlaybackMode.ACCOMPANIMENT, true, 0))
    private val target = listOf(note(0, 2000, 60f))
    private fun feedback(history: List<PitchSample>, now: Long, songMs: Long = now,
        playback: List<PlaybackAnchor> = anchors, playing: Boolean = true) =
        karaokeFeedback(target, history, playback, history.last(), now, songMs, playing)

    @Test fun vibratoBecomesOneHorizontalNote() {
        val raw = (0..19).map { note(it * 40L, (it + 1) * 40L, if (it % 2 == 0) 59.65f else 60.35f) }
        val guide = buildKaraokeGuide(raw)
        assertEquals(listOf(note(0, 800, 60f)), guide)
    }
    @Test fun keepsSustainedNoteChangesAndRealSilence() {
        val guide = buildKaraokeGuide(listOf(note(0, 300, 60.1f), note(300, 600, 62.1f), note(800, 1200, 62.2f)))
        assertEquals(listOf(60f, 62f, 62f), guide.map { it.midiNote })
        assertEquals(listOf(0L, 300L, 800L), guide.map { it.startMs })
        assertEquals(listOf(300L, 600L, 1200L), guide.map { it.endMs })
    }
    @Test fun ignoresBriefOctaveSpikeAndLowConfidenceNoise() {
        val guide = buildKaraokeGuide(listOf(note(0, 300, 60f), note(300, 320, 72f),
            note(320, 600, 60f), note(600, 900, 67f, .2f), note(900, 1200, 64f)))
        assertEquals(listOf(60f, 64f), guide.map { it.midiNote })
        assertEquals(600L, guide.first().endMs)
        assertEquals(900L, guide.last().startMs)
    }
    @Test fun cannotTurnGreenJustBecauseReferencePassed() {
        val result = feedback(listOf(sample(900, 64f), sample(950, 64f), sample(1000, 64f)), 1000)
        assertFalse(result.isMatching)
        assertTrue(result.matchedSpans.isEmpty())
    }
    @Test fun sustainedClosePitchTurnsGreenOnlyOverActuallySungTime() {
        val result = feedback(listOf(sample(400, 60.3f), sample(450, 60.4f), sample(500, 60.2f)), 500)
        assertTrue(result.isMatching)
        assertEquals(listOf(KaraokeMatchSpan(400, 500)), result.matchedSpans)
    }
    @Test fun aSingleNoiseFrameDoesNotGlowAndWrongSemitoneOrOctaveDoesNotMatch() {
        assertFalse(feedback(listOf(sample(500, 60f)), 500).isMatching)
        for (midi in listOf(61f, 72f)) {
            assertFalse(feedback(listOf(sample(400, midi), sample(450, midi), sample(500, midi)), 500).isMatching)
        }
    }
    @Test fun silenceLowConfidenceStaleSampleAndPauseImmediatelyStopGlow() {
        val correct = listOf(sample(400, 60f), sample(450, 60f), sample(500, 60f))
        assertFalse(feedback(correct + sample(550, null), 550).isMatching)
        assertFalse(feedback(correct + sample(550, 60f, .2f), 550).isMatching)
        assertFalse(feedback(correct, 900).isMatching)
        assertFalse(feedback(correct, 500, playing = false).isMatching)
        assertFalse(feedback(correct, 500, songMs = 2500).isMatching)
    }
    @Test fun usesRecordingToSongAnchorsAndDoesNotBridgePlaybackSwitchOrMissingSamples() {
        val shifted = listOf(PlaybackAnchor(0, 1000, SongPlaybackMode.ACCOMPANIMENT, true, 0))
        val result = feedback(listOf(sample(100, 60f), sample(150, 60f), sample(200, 60f)), 200, 1200, shifted)
        assertEquals(listOf(KaraokeMatchSpan(1100, 1200)), result.matchedSpans)
        assertTrue(result.isMatching)
        val switched = anchors + PlaybackAnchor(480, 480, SongPlaybackMode.ORIGINAL, true, 1)
        assertFalse(feedback(listOf(sample(400, 60f), sample(450, 60f), sample(500, 60f)), 500, playback = switched).isMatching)
        assertFalse(feedback(listOf(sample(100, 60f), sample(500, 60f)), 500).isMatching)
    }
    @Test fun toleranceHysteresisPreventsBoundaryFlickerButStopsForClearlyWrongPitch() {
        val history = listOf(sample(400, 60.6f), sample(450, 60.6f), sample(500, 60.6f))
        assertTrue(feedback(history + sample(550, 60.8f), 550).isMatching)
        assertFalse(feedback(history + sample(550, 61.1f), 550).isMatching)
    }
}
