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
}
