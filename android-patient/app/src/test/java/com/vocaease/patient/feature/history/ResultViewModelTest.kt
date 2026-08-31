package com.vocaease.patient.feature.history

import com.vocaease.patient.core.network.dto.AnalysisTaskStatus
import com.vocaease.patient.core.network.dto.AnalysisTaskType
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
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultViewModelTest {
    @Test
    fun `前台每轮等待3秒并在terminal停止`() = runBlocking {
        val remote = QueueResultRemote(session(SessionStatus.PROCESSING, 1), session(SessionStatus.COMPLETED, 1))
        val gate = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val viewModel = ResultViewModel(
            SESSION_ID.toString(), remote, { AnalysisRetryOutcome.Accepted(1) }, Dispatchers.Unconfined,
            delayMillis = { delays += it; gate.await() },
        )

        viewModel.start()
        assertEquals(ResultContentState.PROCESSING, viewModel.state.value.content?.contentState)
        assertEquals(listOf(3_000L), delays)
        gate.complete(Unit)
        yield()

        assertEquals(ResultContentState.COMPLETED, viewModel.state.value.content?.contentState)
        assertEquals(2, remote.calls)
    }

    @Test
    fun `离页取消真实poll且旧generation不能覆盖新状态`() = runBlocking {
        val remote = QueueResultRemote(session(SessionStatus.PROCESSING, 2), session(SessionStatus.PROCESSING, 1))
        val gate = CompletableDeferred<Unit>()
        val viewModel = ResultViewModel(
            SESSION_ID.toString(), remote, { AnalysisRetryOutcome.Accepted(1) }, Dispatchers.Unconfined,
            delayMillis = { gate.await() },
        )
        viewModel.start()
        viewModel.stop()
        gate.complete(Unit)
        yield()

        assertEquals(1, remote.calls)
        assertEquals(2, viewModel.state.value.content?.analysisGeneration)
        assertFalse(viewModel.isPolling)
    }

    @Test
    fun `达到最大重试次数显示联系医生且不自动循环`() = runBlocking {
        var retryCalls = 0
        val viewModel = ResultViewModel(
            SESSION_ID.toString(), QueueResultRemote(session(SessionStatus.FAILED, 3)),
            retryAction = { retryCalls += 1; AnalysisRetryOutcome.MaxAttempts },
            dispatcher = Dispatchers.Unconfined,
            delayMillis = {},
        )
        viewModel.start()
        viewModel.retryAnalysis()

        assertEquals(0, retryCalls)
        assertEquals("暂时无法重新分析，请联系医生", viewModel.state.value.content?.safeFailureSummary)
        assertFalse(viewModel.state.value.content?.canRetry == true)
    }

    @Test
    fun `已有处理中内容遇429时保留内容显示错误并按Retry After继续轮询`() = runBlocking {
        val remote = QueueResultRemote(session(SessionStatus.PROCESSING, 1))
        val firstPollGate = CompletableDeferred<Unit>()
        val retryGate = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val viewModel = ResultViewModel(
            SESSION_ID.toString(), remote, { AnalysisRetryOutcome.Accepted(1) }, Dispatchers.Unconfined,
            delayMillis = { delay ->
                delays += delay
                if (delays.size == 1) firstPollGate.await() else retryGate.await()
            },
        )
        viewModel.start()
        remote.failure = AnalysisRemoteRetryException(9_000L)
        firstPollGate.complete(Unit)
        yield()

        assertEquals(ResultContentState.PROCESSING, viewModel.state.value.content?.contentState)
        assertEquals("暂时无法加载演唱结果，请重试", viewModel.state.value.errorMessage)
        assertTrue(viewModel.isPolling)
        assertEquals(listOf(3_000L, 9_000L), delays)

        remote.failure = null
        remote.enqueue(session(SessionStatus.COMPLETED, 1))
        retryGate.complete(Unit)
        yield()
        assertEquals(ResultContentState.COMPLETED, viewModel.state.value.content?.contentState)
        assertFalse(viewModel.isPolling)
        Unit
    }

    @Test
    fun `已有处理中内容遇5xx或timeout使用安全3秒继续轮询`() = runBlocking {
        listOf(AnalysisRemoteRetryException(null), IOException("timeout")).forEach { failure ->
            val remote = QueueResultRemote(session(SessionStatus.PROCESSING, 1))
            val firstPollGate = CompletableDeferred<Unit>()
            val retryGate = CompletableDeferred<Unit>()
            val delays = mutableListOf<Long>()
            val viewModel = ResultViewModel(
                SESSION_ID.toString(), remote, { AnalysisRetryOutcome.Accepted(1) }, Dispatchers.Unconfined,
                delayMillis = { delay ->
                    delays += delay
                    if (delays.size == 1) firstPollGate.await() else retryGate.await()
                },
            )
            viewModel.start()
            remote.failure = failure
            firstPollGate.complete(Unit)
            yield()

            assertEquals(listOf(3_000L, 3_000L), delays)
            assertTrue(viewModel.isPolling)
            viewModel.stop()
            retryGate.complete(Unit)
        }
    }

    @Test
    fun `服务端接受新代际后前台请求携带minimumGeneration下限`() = runBlocking {
        val remote = QueueResultRemote(
            session(SessionStatus.FAILED, 1),
            session(SessionStatus.PROCESSING, 2),
        )
        val gate = CompletableDeferred<Unit>()
        val viewModel = ResultViewModel(
            SESSION_ID.toString(), remote, { AnalysisRetryOutcome.Accepted(2) }, Dispatchers.Unconfined,
            delayMillis = { gate.await() },
        )

        viewModel.start()
        viewModel.retryAnalysis()

        assertEquals(listOf(0, 2), remote.minimumGenerations)
        assertEquals(2, viewModel.state.value.content?.analysisGeneration)
        viewModel.stop()
        gate.complete(Unit)
        Unit
    }
}

private class QueueResultRemote(vararg values: SingingSession) : ResultSessionRemote {
    private val queue = ArrayDeque(values.toList())
    var calls = 0
    var failure: Exception? = null
    val minimumGenerations = mutableListOf<Int>()
    fun enqueue(value: SingingSession) { queue += value }
    override suspend fun fetch(sessionId: String): SingingSession = fetch(sessionId, 0)
    override suspend fun fetch(sessionId: String, minimumGeneration: Int): SingingSession {
        calls += 1
        minimumGenerations += minimumGeneration
        failure?.let { throw it }
        return queue.removeFirst()
    }
}

private fun session(status: SessionStatus, generation: Int): SingingSession {
    val resultStatus = if (status == SessionStatus.FAILED) AnalysisTaskStatus.FAILED else AnalysisTaskStatus.SUCCEEDED
    val result = SingingAnalysisResult(
        RESULT_ID, AnalysisTaskType.SINGING_AUDIO_METRICS, resultStatus, generation, "v1", false,
        UnavailableAnalysisPayload, emptyMap(), "", if (status == SessionStatus.FAILED) "分析失败" else "", generation,
    )
    return SingingSession(
        SESSION_ID, PatientSnapshot(PATIENT_ID, "MR", "患者"), SongSnapshot(SONG_ID, "小幸运", "田馥甄", 265),
        TreatmentPlanSnapshot(PLAN_ID, LocalDate.parse("2026-08-01"), 8, 24), status,
        if (status == SessionStatus.COMPLETED) 91 else null, null, 265, false, "android", generation,
        Instant.EPOCH, if (status == SessionStatus.COMPLETED) Instant.EPOCH else null, Instant.EPOCH, Instant.EPOCH,
        listOf(SessionMedia(VIDEO_ID, MediaType.SINGING_VIDEO, "ready", "video/mp4", 1024, Instant.EPOCH)),
        listOf(RESULT_ID), listOf(result),
    )
}

private val SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011")
private val PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000012")
private val SONG_ID = UUID.fromString("00000000-0000-0000-0000-000000000013")
private val PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000014")
private val VIDEO_ID = UUID.fromString("00000000-0000-0000-0000-000000000015")
private val RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000016")
