package com.vocaease.patient.feature.training

import com.vocaease.patient.core.media.PitchSample
import com.vocaease.patient.core.network.dto.ReferenceNoteDto
import org.junit.Assert.*
import org.junit.Test

class PitchTimelineGeometryTest {
    @Test fun timelineMovesReferenceLeft() {
        assertEquals(100f, timelineX(3000, 1000, 800f) - timelineX(3000, 2000, 800f), .1f)
        assertEquals(200f, timelineX(1000, 1000, 800f), .1f)
    }
    @Test fun fallbackShowsThreeSemitonesWithVisibleMovement() {
        val range = pitchRange(emptyList())
        assertTrue(kotlin.math.abs(pitchY(57f, range, 200f) - pitchY(60f, range, 200f)) >= 30f)
    }
    @Test fun songRangeUsesSustainedMelodyInsteadOfShortOctaveOutliers() {
        val notes = listOf(ReferenceNoteDto(0, 4000, 55f, .95f), ReferenceNoteDto(4000, 8000, 67f, .95f),
            ReferenceNoteDto(8000, 8020, 100f, .95f), ReferenceNoteDto(8020, 9000, 20f, .2f))
        val range = referencePitchRange(notes)
        assertEquals(53f, range.first, .01f)
        assertEquals(69f, range.second, .01f)
        assertTrue(pitchY(55f, range, 200f) - pitchY(67f, range, 200f) >= 140f)
    }
    @Test fun narrowMelodyHasMinimumRangeAndSameNoteMapsToSameY() {
        val range = referencePitchRange(listOf(ReferenceNoteDto(0, 2000, 60f, 1f)))
        assertEquals(12f, range.second - range.first, .01f)
        assertEquals(100f, pitchY(60f, range, 200f), .01f)
    }
    @Test fun patientViewportUsesStableRecentVoiceAndIgnoresOneSpike() {
        val viewport = PatientPitchViewport()
        val voice = (0..12).map { PitchSample(it * 50L, 110f, .98f) }
        val range = viewport.update(voice)
        assertTrue(45f in range.first..range.second)
        assertEquals(range, viewport.update(voice + PitchSample(700, 880f, .99f)))
        assertEquals(range, viewport.update(voice + PitchSample(750, null, 0f)))
    }
}
