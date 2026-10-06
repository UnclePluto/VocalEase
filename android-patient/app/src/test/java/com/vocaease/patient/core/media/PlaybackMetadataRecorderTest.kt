package com.vocaease.patient.core.media
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataRecorderTest {
    @Test fun truncatedEndpointPreservesTailAndPausedBoundary() {
        for (playing in listOf(true, false)) {
            val recorder=recorder()
            recorder.record(0,0,SongPlaybackMode.ACCOMPANIMENT,playing)
            recorder.record(5000,if(playing) 5000 else 0,SongPlaybackMode.ACCOMPANIMENT,playing)
            recorder.record(10020,if(playing) 10020 else 0,SongPlaybackMode.ACCOMPANIMENT,false)
            val metadata=recorder.finish(10000,48000)
            assertEquals(10000L,metadata.anchors.last().recordingMs)
            assertEquals(if(playing) 10000L else 0L,metadata.anchors.last().songMs)
            assertEquals(playing,metadata.anchors.last().playing)
        }
    }
    @Test fun cropCreatesBothBoundariesWithoutExtrapolatingAcrossSeek() {
        val recorder=recorder()
        recorder.record(0,1000,SongPlaybackMode.ORIGINAL,true)
        recorder.record(5000,6000,SongPlaybackMode.ORIGINAL,true)
        recorder.record(10020,0,SongPlaybackMode.ORIGINAL,false)
        val metadata=recorder.finish(9800,44100,100)
        assertEquals(0L,metadata.anchors.first().recordingMs)
        assertEquals(1100L,metadata.anchors.first().songMs)
        assertEquals(9800L,metadata.anchors.last().recordingMs)
        assertEquals(10900L,metadata.anchors.last().songMs)
        assertEquals(0,metadata.anchors.last().segment)
    }
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
