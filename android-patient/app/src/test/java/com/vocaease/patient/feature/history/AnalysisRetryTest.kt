package com.vocaease.patient.feature.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisRetryTest {
    @Test
    fun `重试使用下一generation稳定键并严格核对响应`() = runBlocking {
        val remote = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Accepted(mutation("s1", AnalysisStatus.PROCESSING, 3))
        }
        val coordinator = AnalysisRetryCoordinator(remote, maxAttempts = 3)

        assertEquals(AnalysisRetryOutcome.Accepted(3), coordinator.retry(failed(generation = 2, attempt = 1)))
        assertEquals(listOf("retry:s1:3"), remote.keys)

        remote.retryResults += AnalysisRetryResponse.Accepted(mutation("other", AnalysisStatus.PROCESSING, 4))
        assertEquals(AnalysisRetryOutcome.Rejected, coordinator.retry(failed(generation = 3, attempt = 1)))
    }

    @Test
    fun `重复点击同generation共享single flight`() = runBlocking {
        val gate = CompletableDeferred<AnalysisRetryResponse>()
        val remote = SuspendingRetryRemote(gate)
        val coordinator = AnalysisRetryCoordinator(remote, 3)
        val first = async { coordinator.retry(failed(1, 1)) }
        remote.entered.await()
        val second = async { coordinator.retry(failed(1, 1)) }
        gate.complete(AnalysisRetryResponse.Accepted(mutation("s1", AnalysisStatus.RETRYING, 2)))

        assertEquals(AnalysisRetryOutcome.Accepted(2), first.await())
        assertEquals(AnalysisRetryOutcome.Accepted(2), second.await())
        assertEquals(1, remote.calls)
    }

    @Test
    fun `409后已retrying processing completed视为接受`() = runBlocking {
        listOf(AnalysisStatus.RETRYING, AnalysisStatus.PROCESSING, AnalysisStatus.COMPLETED).forEach { status ->
            val remote = FakeRetryRemote().apply {
                retryResults += AnalysisRetryResponse.Conflict
                details += AnalysisRetryDetail("s1", status, generation = 2, attempt = 2)
            }
            assertEquals(AnalysisRetryOutcome.Accepted(2), AnalysisRetryCoordinator(remote, 3).retry(failed(1, 1)))
            assertEquals(1, remote.keys.size)
        }
    }

    @Test
    fun `409后仍为旧failed只用同键再试一次`() = runBlocking {
        val remote = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Conflict
            retryResults += AnalysisRetryResponse.Accepted(mutation("s1", AnalysisStatus.PROCESSING, 2))
            details += AnalysisRetryDetail("s1", AnalysisStatus.FAILED, generation = 1, attempt = 1)
        }

        assertEquals(AnalysisRetryOutcome.Accepted(2), AnalysisRetryCoordinator(remote, 3).retry(failed(1, 1)))
        assertEquals(listOf("retry:s1:2", "retry:s1:2"), remote.keys)
    }

    @Test
    fun `409刷新为cancelled或归属错误时停止`() = runBlocking {
        val cancelled = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Conflict
            details += AnalysisRetryDetail("s1", AnalysisStatus.CANCELLED, 1, 1)
        }
        assertEquals(AnalysisRetryOutcome.Rejected, AnalysisRetryCoordinator(cancelled, 3).retry(failed(1, 1)))

        val other = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Conflict
            details += AnalysisRetryDetail("other", AnalysisStatus.FAILED, 1, 1)
        }
        assertEquals(AnalysisRetryOutcome.Rejected, AnalysisRetryCoordinator(other, 3).retry(failed(1, 1)))
    }

    @Test
    fun `达到最大次数显示联系医生且旧generation迟到不覆盖新结果`() = runBlocking {
        val remote = FakeRetryRemote()
        val coordinator = AnalysisRetryCoordinator(remote, maxAttempts = 3)
        assertEquals(AnalysisRetryOutcome.MaxAttempts, coordinator.retry(failed(3, 3)))
        assertTrue(remote.keys.isEmpty())
        assertEquals("暂时无法重新分析，请联系医生", AnalysisRetryOutcome.MaxAttempts.message)

        remote.retryResults += AnalysisRetryResponse.Accepted(mutation("s1", AnalysisStatus.PROCESSING, 2))
        assertEquals(AnalysisRetryOutcome.Rejected, coordinator.retry(failed(generation = 2, attempt = 1)))
        assertFalse(remote.keys.isEmpty())
    }

    @Test
    fun `重试请求取消必须原样传播给调用方`() = runBlocking {
        val cancellation = CancellationException("离开失败结果页")
        val remote = object : AnalysisRetryRemote {
            override suspend fun retry(sessionId: String, idempotencyKey: String): AnalysisRetryResponse =
                throw cancellation

            override suspend fun detail(sessionId: String): AnalysisRetryDetail = error("unexpected")
        }

        try {
            AnalysisRetryCoordinator(remote, 3).retry(failed(1, 1))
            fail("取消不应转换为拒绝结果")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }
    }

    @Test
    fun `mutation必须精确匹配目标generation且task ids合法`() = runBlocking {
        val future = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Accepted(mutation("s1", AnalysisStatus.PROCESSING, 3))
        }
        assertEquals(AnalysisRetryOutcome.Rejected, AnalysisRetryCoordinator(future, 3).retry(failed(1, 1)))

        val invalidTask = FakeRetryRemote().apply {
            retryResults += AnalysisRetryResponse.Accepted(
                AnalysisRetryMutation("s1", AnalysisStatus.PROCESSING, 2, listOf("bad task id")),
            )
        }
        assertEquals(AnalysisRetryOutcome.Rejected, AnalysisRetryCoordinator(invalidTask, 3).retry(failed(1, 1)))
    }

    private fun failed(generation: Int, attempt: Int) = AnalysisRetryDetail("s1", AnalysisStatus.FAILED, generation, attempt)
    private fun mutation(session: String, status: AnalysisStatus, generation: Int) =
        AnalysisRetryMutation(session, status, generation, listOf("task-$generation"))
}

private class FakeRetryRemote : AnalysisRetryRemote {
    val retryResults = ArrayDeque<AnalysisRetryResponse>()
    val details = ArrayDeque<AnalysisRetryDetail>()
    val keys = mutableListOf<String>()
    override suspend fun retry(sessionId: String, idempotencyKey: String): AnalysisRetryResponse {
        keys += idempotencyKey
        return retryResults.removeFirst()
    }
    override suspend fun detail(sessionId: String): AnalysisRetryDetail = details.removeFirst()
}

private class SuspendingRetryRemote(
    private val gate: CompletableDeferred<AnalysisRetryResponse>,
) : AnalysisRetryRemote {
    var calls = 0
    val entered = CompletableDeferred<Unit>()
    override suspend fun retry(sessionId: String, idempotencyKey: String): AnalysisRetryResponse {
        calls += 1
        entered.complete(Unit)
        return gate.await()
    }
    override suspend fun detail(sessionId: String): AnalysisRetryDetail = error("unexpected")
}
