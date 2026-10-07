package com.vocaease.patient.core.network

import com.vocaease.patient.core.media.SongPlaybackMode
import com.vocaease.patient.core.network.dto.PrivateUrlDto
import com.vocaease.patient.feature.training.VocaEasePreviewGrantSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGrantSourceTest {
    @Test fun unalignedAccompanimentCanBeListenedToFromItsOwnStart() = runBlocking {
        val api = java.lang.reflect.Proxy.newProxyInstance(
            PatientApi::class.java.classLoader, arrayOf(PatientApi::class.java),
        ) { _, method, arguments ->
            check(method.name == "previewSong")
            check(arguments!![1] == "accompaniment")
            ApiEnvelope("ok", "", PrivateUrlDto("https://example.invalid/backing.wav", "2026-10-01T08:00:00+00:00"), "preview")
        } as PatientApi
        val grant = VocaEasePreviewGrantSource(api).fetch("song", SongPlaybackMode.ACCOMPANIMENT, null)
        assertEquals("https://example.invalid/backing.wav", grant.url)
        assertEquals(0L, grant.timelineOffsetMillis)
    }
    @Test fun recordingCanStartWithItsBoundUnalignedAccompaniment() = runBlocking {
        val fixture = java.io.File("src/test/resources/fixtures/session.json").readText()
        val response = apiJson.decodeFromString<ApiEnvelope<com.vocaease.patient.core.network.dto.SingingSessionDto>>(fixture)
        val asset = "44444444-4444-4444-8444-444444444444"
        val session = response.copy(data = response.data.copy(playback = com.vocaease.patient.core.network.dto.PlaybackBindingDto(accompanimentAssetId = asset)))
        val api = java.lang.reflect.Proxy.newProxyInstance(PatientApi::class.java.classLoader, arrayOf(PatientApi::class.java)) { _, method, _ ->
            when(method.name) {
                "session" -> session
                "sessionSongPlayback" -> ApiEnvelope("ok", "", com.vocaease.patient.core.network.dto.SongPlaybackGrantDto(asset,"https://example.invalid/backing.wav","2026-10-01T08:00:00+00:00"),"test")
                else -> error("不应调用 ${method.name}")
            }
        } as PatientApi
        val grant = VocaEasePreviewGrantSource(api).fetch("song",SongPlaybackMode.ACCOMPANIMENT,session.data.id)
        assertEquals(asset, grant.assetId)
        assertEquals(0L, grant.timelineOffsetMillis)
    }
}
