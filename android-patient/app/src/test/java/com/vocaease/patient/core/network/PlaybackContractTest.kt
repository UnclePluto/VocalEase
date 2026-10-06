package com.vocaease.patient.core.network
import com.vocaease.patient.core.media.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PlaybackContractTest {
    @Test fun wireUsesSourceAndAccompaniment() {
        assertEquals("\"source\"",Json.encodeToString(SongPlaybackMode.ORIGINAL))
        assertEquals("\"accompaniment\"",Json.encodeToString(SongPlaybackMode.ACCOMPANIMENT))
    }
    @Test fun rejectsNonMonotonicAnchors() {
        val a=PlaybackAnchor(100,0,SongPlaybackMode.ACCOMPANIMENT,true,0)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackMetadata(sampleRate=48000,sourceAssetId="00000000-0000-0000-0000-000000000001",accompanimentAssetId="00000000-0000-0000-0000-000000000002",referenceVersion=null,anchors=listOf(a,a),modeChanges=emptyList())
        }
    }
}
