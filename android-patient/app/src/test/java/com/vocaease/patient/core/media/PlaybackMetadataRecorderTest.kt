package com.vocaease.patient.core.media
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataRecorderTest {
    private fun recorder()=PlaybackMetadataRecorder(48000,"00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002",null)
    @Test fun bufferingProducesPausedSongAnchors() {
        val recorder=recorder()
        recorder.record(0,1000,SongPlaybackMode.ACCOMPANIMENT,false)
        recorder.record(2000,1000,SongPlaybackMode.ACCOMPANIMENT,false)
        val anchors=recorder.snapshot().anchors
        assertEquals(2000L,anchors.last().recordingMs-anchors.first().recordingMs)
        assertEquals(anchors.first().songMs,anchors.last().songMs)
    }
    @Test fun switchAndJumpStartCorrectSegments() {
        val recorder=recorder()
        recorder.record(0,1000,SongPlaybackMode.ACCOMPANIMENT,true)
        recorder.record(5000,6000,SongPlaybackMode.ORIGINAL,true)
        recorder.record(6000,0,SongPlaybackMode.ORIGINAL,true)
        val metadata=recorder.snapshot()
        assertEquals(1,metadata.modeChanges.size)
        assertEquals(1,metadata.anchors.last().segment)
        assertEquals(6000L,metadata.anchors[1].songMs)
    }
}
