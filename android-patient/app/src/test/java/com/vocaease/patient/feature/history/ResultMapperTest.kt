package com.vocaease.patient.feature.history

import com.vocaease.patient.core.network.dto.AnalysisTaskStatus
import com.vocaease.patient.core.network.dto.AnalysisTaskType
import com.vocaease.patient.core.network.dto.AnalysisTimeSeries
import com.vocaease.patient.core.network.dto.MediaType
import com.vocaease.patient.core.network.dto.PatientSnapshot
import com.vocaease.patient.core.network.dto.SessionMedia
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SingingAnalysisResult
import com.vocaease.patient.core.network.dto.SingingSession
import com.vocaease.patient.core.network.dto.SongSnapshot
import com.vocaease.patient.core.network.dto.TreatmentPlanSnapshot
import com.vocaease.patient.core.network.dto.UnavailableAnalysisPayload
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultMapperTest {
    @Test
    fun `只有is mock为true时显示演示结果`() {
        assertEquals("演示结果", ResultMapper.mockLabel(true))
        assertNull(ResultMapper.mockLabel(false))
        assertNull(ResultMapper.mockLabel(null))
    }

    @Test
    fun `总分只接受有限且位于零到一百分的服务端值`() {
        assertEquals("88", ResultMapper.overallScore(88.0))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1).forEach {
            assertNull(ResultMapper.overallScore(it))
        }
    }

    @Test
    fun `当前协议没有独立分项时不从总分推导`() {
        val model = ResultMapper.map(session(status = SessionStatus.COMPLETED, score = 88, result = result()))

        assertEquals(listOf("暂无单项评分", "暂无单项评分", "暂无单项评分"), model.componentScores)
    }

    @Test
    fun `只把真实合法pitch hz序列映射为带时间的点`() {
        val model = ResultMapper.map(
            session(
                status = SessionStatus.COMPLETED,
                result = result(series = mapOf("pitch_hz" to AnalysisTimeSeries(20, listOf(220.0, 0.0, 440.0)))),
            ),
        )

        assertEquals(listOf(PitchPoint(0L, 220f), PitchPoint(20L, 0f), PitchPoint(40L, 440f)), model.pitchPoints)
    }

    @Test
    fun `pitch间隔非法或数值非有限负值空值过大时拒绝整条曲线`() {
        val invalid = listOf(
            AnalysisTimeSeries(0, listOf(220.0)),
            AnalysisTimeSeries(-1, listOf(220.0)),
            AnalysisTimeSeries(20, emptyList()),
            AnalysisTimeSeries(20, listOf(Double.NaN)),
            AnalysisTimeSeries(20, listOf(Double.POSITIVE_INFINITY)),
            AnalysisTimeSeries(20, listOf(-1.0)),
            AnalysisTimeSeries(20, List(ResultMapper.MAX_PITCH_SAMPLES + 1) { 220.0 }),
        )

        invalid.forEach { series ->
            val model = ResultMapper.map(session(status = SessionStatus.COMPLETED, result = result(series = mapOf("pitch_hz" to series))))
            assertTrue(model.pitchPoints.isEmpty())
            assertEquals("暂无音准曲线", model.pitchMessage)
        }
    }

    @Test
    fun `非约定metric不能被当作音准曲线`() {
        val model = ResultMapper.map(
            session(
                status = SessionStatus.COMPLETED,
                result = result(series = mapOf("estimated_pitch" to AnalysisTimeSeries(20, listOf(220.0)))),
            ),
        )

        assertTrue(model.pitchPoints.isEmpty())
        assertEquals("暂无音准曲线", model.pitchMessage)
    }

    @Test
    fun `处理中和已上传不显示分数或曲线`() {
        listOf(SessionStatus.UPLOADED, SessionStatus.PROCESSING).forEach { status ->
            val model = ResultMapper.map(session(status = status, score = 88, result = result()))
            assertNull(model.overallScore)
            assertTrue(model.pitchPoints.isEmpty())
            assertEquals(ResultContentState.PROCESSING, model.contentState)
        }
    }

    @Test
    fun `失败只暴露安全摘要和重试入口`() {
        val model = ResultMapper.map(
            session(
                status = SessionStatus.FAILED,
                result = result(status = AnalysisTaskStatus.FAILED, errorSummary = "分析暂时失败，请重试"),
            ),
        )

        assertEquals(ResultContentState.FAILED, model.contentState)
        assertEquals("分析暂时失败，请重试", model.safeFailureSummary)
        assertTrue(model.canRetry)
        assertNull(model.overallScore)
    }

    @Test
    fun `完成但结果暂缺时显示结果同步中而不是零分`() {
        val model = ResultMapper.map(session(status = SessionStatus.COMPLETED, score = null, result = null))

        assertEquals(ResultContentState.RESULT_SYNCING, model.contentState)
        assertNull(model.overallScore)
        assertFalse(model.canRetry)
    }

    @Test
    fun `只接受与session当前generation精确匹配的metric结果`() {
        val old = result(generation = 1)
        val future = result(generation = 3)
        val model = ResultMapper.map(
            session(status = SessionStatus.COMPLETED, score = 99, result = old).copy(
                analysisGeneration = 2,
                analysisResults = listOf(old, future),
            ),
        )

        assertEquals(ResultContentState.RESULT_SYNCING, model.contentState)
        assertNull(model.overallScore)
        assertTrue(model.pitchPoints.isEmpty())
    }

    @Test
    fun `失败达到最大attempt时直接显示联系医生且不提供重试`() {
        val failed = result(status = AnalysisTaskStatus.FAILED, errorSummary = "内部错误", generation = 3).copy(attempt = 3)
        val model = ResultMapper.map(session(status = SessionStatus.FAILED, result = failed))

        assertFalse(model.canRetry)
        assertEquals("暂时无法重新分析，请联系医生", model.safeFailureSummary)
    }

    private fun session(
        status: SessionStatus,
        score: Int? = null,
        result: SingingAnalysisResult? = null,
    ) = SingingSession(
        id = SESSION_ID,
        patient = PatientSnapshot(PATIENT_ID, "MR-001", "患者甲"),
        song = SongSnapshot(SONG_ID, "晴天", "周杰伦", 269),
        treatmentPlan = TreatmentPlanSnapshot(PLAN_ID, LocalDate.parse("2026-08-01"), 8, 24),
        status = status,
        score = score,
        burpCount = null,
        durationSeconds = 180,
        isMock = result?.isMock == true,
        createdSource = "android",
        analysisGeneration = result?.generation ?: 0,
        submittedAt = Instant.parse("2026-08-30T01:00:00Z"),
        completedAt = if (status == SessionStatus.COMPLETED) Instant.parse("2026-08-30T01:03:00Z") else null,
        createdAt = Instant.parse("2026-08-30T00:59:00Z"),
        updatedAt = Instant.parse("2026-08-30T01:03:00Z"),
        media = listOf(SessionMedia(VIDEO_ID, MediaType.SINGING_VIDEO, "ready", "video/mp4", 1024, Instant.parse("2026-08-30T01:01:00Z"))),
        analysisTaskIds = result?.let { listOf(it.id) }.orEmpty(),
        analysisResults = listOfNotNull(result),
    )

    private fun result(
        status: AnalysisTaskStatus = AnalysisTaskStatus.SUCCEEDED,
        series: Map<String, AnalysisTimeSeries> = emptyMap(),
        errorSummary: String = "",
        isMock: Boolean? = true,
        generation: Int = 1,
    ) = SingingAnalysisResult(
        id = RESULT_ID,
        taskType = AnalysisTaskType.SINGING_AUDIO_METRICS,
        status = status,
        generation = generation,
        protocolVersion = "mock-v1",
        isMock = isMock,
        payload = UnavailableAnalysisPayload,
        timeSeries = series,
        errorCode = if (status == AnalysisTaskStatus.FAILED) "analysis_failed" else "",
        errorSummary = errorSummary,
        attempt = 1,
    )

    private companion object {
        val SESSION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val PATIENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val SONG_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000013")
        val PLAN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000014")
        val VIDEO_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000015")
        val RESULT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000016")
    }
}
