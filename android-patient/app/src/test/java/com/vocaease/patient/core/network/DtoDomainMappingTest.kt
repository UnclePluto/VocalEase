package com.vocaease.patient.core.network

import com.vocaease.patient.core.network.dto.AnalysisPayloadIssue
import com.vocaease.patient.core.network.dto.ConfirmSessionMediaRequestDto
import com.vocaease.patient.core.network.dto.MediaType
import com.vocaease.patient.core.network.dto.PatientMediaUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.SessionMutationDto
import com.vocaease.patient.core.network.dto.SessionPageDto
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SingingSessionDto
import com.vocaease.patient.core.network.dto.UnavailableAnalysisPayload
import com.vocaease.patient.core.network.dto.UnsupportedAnalysisPayload
import com.vocaease.patient.core.network.dto.toDomain
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoDomainMappingTest {
    @Test
    fun `自由演唱详情与历史允许没有治疗计划`() {
        val detail = apiJson.decodeFromString<ApiEnvelope<SingingSessionDto>>(
            fixture("fixtures/session.json"),
        ).data.copy(treatmentPlan = null).toDomain()
        assertEquals(null, detail.treatmentPlan)
        val page = apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
            fixture("fixtures/sessions_page.json"),
        ).data
        val summary = page.results.single().copy(treatmentPlan = null).toDomain()
        assertEquals(null, summary.treatmentPlan)
    }

    @Test
    fun `confirm request 接受合法单一或双标识并拒绝空值与坏 UUID`() {
        ConfirmSessionMediaRequestDto(assetId = ASSET_ID)
        ConfirmSessionMediaRequestDto(objectKey = "private/session/audio.m4a")
        ConfirmSessionMediaRequestDto(
            assetId = ASSET_ID,
            objectKey = "private/session/audio.m4a",
        )

        assertThrows(NetworkContractException::class.java) { ConfirmSessionMediaRequestDto() }
        assertThrows(NetworkContractException::class.java) {
            ConfirmSessionMediaRequestDto(assetId = "not-a-uuid")
        }
        assertThrows(NetworkContractException::class.java) {
            ConfirmSessionMediaRequestDto(objectKey = "  ")
        }
    }

    @Test
    fun `generic grant 和 session list 使用受控枚举而不是任意字符串`() {
        val grant = PatientMediaUploadGrantRequestDto(
            ownerId = PATIENT_ID,
            mediaType = MediaType.SINGING_VIDEO,
            mime = "video/mp4",
            size = 5678,
        )

        assertEquals(MediaType.SINGING_VIDEO, grant.mediaType)
        assertEquals("completed", SessionStatus.COMPLETED.toString())
        assertThrows(SerializationException::class.java) {
            apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
                fixture("fixtures/sessions_page.json").replace(
                    "\"status\": \"completed\"",
                    "\"status\": \"future_status\"",
                ),
            )
        }
    }

    @Test
    fun `session summary page 和 mutation mapper 校验全部 UUID 日期与时间`() {
        val pageDto = apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
            fixture("fixtures/sessions_page.json"),
        ).data
        val page = pageDto.toDomain()
        val summary = page.results.single()
        assertEquals(UUID.fromString(SESSION_ID), summary.id)
        assertEquals(LocalDate.parse("2026-08-01"), requireNotNull(summary.treatmentPlan).startDate)
        assertEquals(Instant.parse("2026-08-27T09:00:00Z"), summary.createdAt)

        val mutationDto = apiJson.decodeFromString<ApiEnvelope<SessionMutationDto>>(
            fixture("fixtures/session_mutation.json"),
        ).data
        val mutation = mutationDto.toDomain()
        assertEquals(UUID.fromString(SESSION_ID), mutation.sessionId)
        assertEquals(UUID.fromString(TASK_ID), mutation.analysisTaskIds.single())

        val invalidId = apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
            fixture("fixtures/sessions_page.json").replace(SESSION_ID, "not-a-uuid"),
        ).data
        assertThrows(NetworkContractException::class.java) { invalidId.toDomain() }

        val invalidDate = apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
            fixture("fixtures/sessions_page.json").replace("2026-08-01", "2026-99-99"),
        ).data
        assertThrows(NetworkContractException::class.java) { invalidDate.toDomain() }

        val invalidTime = apiJson.decodeFromString<ApiEnvelope<SessionPageDto>>(
            fixture("fixtures/sessions_page.json").replace(
                "2026-08-27T09:00:00Z",
                "not-an-instant",
            ),
        ).data
        assertThrows(NetworkContractException::class.java) { invalidTime.toDomain() }

        val invalidTask = apiJson.decodeFromString<ApiEnvelope<SessionMutationDto>>(
            fixture("fixtures/session_mutation.json").replace(TASK_ID, "bad-task-id"),
        ).data
        assertThrows(NetworkContractException::class.java) { invalidTask.toDomain() }
    }

    @Test
    fun `非模拟未知任务未知版本和不可用 payload 不破坏 session 顶层结果`() {
        val cases = listOf(
            AnalysisMutation(
                values = mapOf("is_mock" to JsonPrimitive(false)),
                expectedIssue = AnalysisPayloadIssue.NON_MOCK,
            ),
            AnalysisMutation(
                values = mapOf("task_type" to JsonPrimitive("future_metrics")),
                expectedIssue = AnalysisPayloadIssue.UNKNOWN_TASK_TYPE,
            ),
            AnalysisMutation(
                values = mapOf("protocol_version" to JsonPrimitive("2.0")),
                expectedIssue = AnalysisPayloadIssue.UNSUPPORTED_PROTOCOL,
            ),
        )

        cases.forEach { case ->
            val session = decodeSessionWithFirstAnalysis(case.values).toDomain()
            val payload = session.analysisResults.first().payload as UnsupportedAnalysisPayload
            assertEquals(case.expectedIssue, payload.issue)
            assertEquals(88, session.score)
            assertEquals(SessionStatus.COMPLETED, session.status)
            assertTrue(session.analysisResults.first().timeSeries.containsKey("pitch_hz"))
        }

        val unavailable = decodeSessionWithFirstAnalysis(mapOf("payload" to JsonNull)).toDomain()
        assertTrue(unavailable.analysisResults.first().payload is UnavailableAnalysisPayload)
        assertEquals(88, unavailable.score)
        assertTrue(unavailable.analysisResults.first().timeSeries.containsKey("volume"))
    }

    private fun decodeSessionWithFirstAnalysis(
        replacements: Map<String, JsonElement>,
    ): SingingSessionDto {
        val root = apiJson.parseToJsonElement(fixture("fixtures/session.json")).jsonObject
        val data = root.getValue("data").jsonObject
        val results = data.getValue("analysis_results").jsonArray
        val first = results.first().jsonObject
        val mutatedFirst = JsonObject(first + replacements)
        val mutatedData = JsonObject(
            data + ("analysis_results" to kotlinx.serialization.json.JsonArray(
                listOf(mutatedFirst) + results.drop(1),
            )),
        )
        val mutatedRoot = JsonObject(root + ("data" to mutatedData))
        return apiJson.decodeFromString<ApiEnvelope<SingingSessionDto>>(
            apiJson.encodeToString(mutatedRoot),
        ).data
    }

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ) { "缺少测试 fixture：$path" }.bufferedReader().use { it.readText() }

    private data class AnalysisMutation(
        val values: Map<String, JsonElement>,
        val expectedIssue: AnalysisPayloadIssue,
    )

    private companion object {
        const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
        const val SESSION_ID = "55555555-5555-4555-8555-555555555555"
        const val ASSET_ID = "66666666-6666-4666-8666-666666666666"
        const val TASK_ID = "88888888-8888-4888-8888-888888888888"
    }
}
