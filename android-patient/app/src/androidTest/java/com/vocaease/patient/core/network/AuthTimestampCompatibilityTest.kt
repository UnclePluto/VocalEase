package com.vocaease.patient.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AccountSnapshotDto
import com.vocaease.patient.core.network.dto.AuthTokensDto
import com.vocaease.patient.core.network.dto.toDomain
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthTimestampCompatibilityTest {
    private fun session(timestamp: String) = AuthTokensDto(
        access = "test-access",
        refresh = "test-refresh",
        refreshExpiresAt = timestamp,
        user = AccountSnapshotDto("test-patient", AccountRole.PATIENT, false),
    ).toDomain()

    @Test
    fun serverUtcOffsetIsAccepted() {
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"),
            session("2026-10-01T08:00:00+00:00").refreshExpiresAt)
    }

    @Test
    fun utcAndOtherOffsetsPreserveTheInstant() {
        listOf("2026-10-01T08:00:00.123456Z", "2026-10-01T16:00:00.123456+08:00",
            "2026-10-01T15:00:00.123456+07:00",
            "2026-10-01T03:00:00.123456-05:00").forEach { timestamp ->
            assertEquals(Instant.parse("2026-10-01T08:00:00.123456Z"), session(timestamp).refreshExpiresAt)
        }
    }

    @Test
    fun malformedAndMissingTimezoneAreRejected() {
        listOf("", "invalid", "2026-10-01T08:00:00", "2026-02-30T08:00:00Z").forEach {
            assertThrows(NetworkContractException::class.java) { session(it) }
        }
    }

    @Test
    fun sessionPlaybackGrantAcceptsServerUtcOffset() = kotlinx.coroutines.runBlocking {
        val fixture = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context.assets.open("fixtures/session.json").bufferedReader().use { it.readText() }
        val response = apiJson.decodeFromString<ApiEnvelope<com.vocaease.patient.core.network.dto.SingingSessionDto>>(fixture)
        val assetId = "44444444-4444-4444-8444-444444444444"
        val session = response.copy(data = response.data.copy(
            playback = com.vocaease.patient.core.network.dto.PlaybackBindingDto(sourceAssetId = assetId),
        ))
        val api = java.lang.reflect.Proxy.newProxyInstance(
            PatientApi::class.java.classLoader, arrayOf(PatientApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "session" -> session
                "sessionSongPlayback" -> ApiEnvelope("ok", "",
                    com.vocaease.patient.core.network.dto.SongPlaybackGrantDto(
                        assetId, "https://example.invalid/song.wav", "2026-10-01T08:00:00+00:00",
                    ), "test-grant")
                else -> error("不应调用 ${method.name}")
            }
        } as PatientApi
        val grant = com.vocaease.patient.feature.training.VocaEasePreviewGrantSource(api)
            .fetch("song-id", com.vocaease.patient.core.media.SongPlaybackMode.ORIGINAL, session.data.id)
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"), grant.expiresAt)
    }

}
