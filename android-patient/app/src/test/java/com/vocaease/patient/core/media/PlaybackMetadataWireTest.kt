package com.vocaease.patient.core.media

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMetadataWireTest {
    @Test fun requiredSchemaVersionIsSentWithDefaultJson() {
        val metadata=PlaybackMetadata(sampleRate=48000,sourceAssetId="11111111-1111-4111-8111-111111111111",accompanimentAssetId="22222222-2222-4222-8222-222222222222",referenceVersion=null,anchors=listOf(PlaybackAnchor(0,0,SongPlaybackMode.ACCOMPANIMENT,true,0)),modeChanges=emptyList())
        val wire=Json.encodeToString(PlaybackMetadata.serializer(),metadata)
        assertTrue(wire.contains("\"schema_version\":1"))
    }
}
