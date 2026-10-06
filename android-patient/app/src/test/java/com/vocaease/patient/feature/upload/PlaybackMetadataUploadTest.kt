package com.vocaease.patient.feature.upload
import com.vocaease.patient.core.media.*
import com.vocaease.patient.core.network.dto.SubmitSessionRequestDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataUploadTest {
    @Test fun uploadRetryUsesIdenticalPersistedMetadata() {
        val recorder=PlaybackMetadataRecorder(48000,"00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002",null)
        recorder.record(0,0,SongPlaybackMode.ACCOMPANIMENT,true)
        recorder.record(5000,5000,SongPlaybackMode.ORIGINAL,true)
        val persisted=Json.encodeToString(recorder.snapshot())
        val first=Json.encodeToString(SubmitSessionRequestDto(recorder.snapshot()))
        val restored=Json.decodeFromString<PlaybackMetadata>(persisted)
        assertEquals(first,Json.encodeToString(SubmitSessionRequestDto(restored)))
        assertFalse(first.contains("url"))
    }
}
