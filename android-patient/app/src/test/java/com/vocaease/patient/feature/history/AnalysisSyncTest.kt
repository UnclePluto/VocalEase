package com.vocaease.patient.feature.history

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisSyncTest {
    @Test
    fun `后台轮询按10 30 60 300秒持久deadline推进`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = MemoryAnalysisCheckpointStore(timeline)
        val remote = QueueAnalysisRemote(timeline, detail(AnalysisStatus.PROCESSING, generation = 1))
        val engine = AnalysisSyncEngine(store, remote)
        val contract = contract()

        engine.initialize(contract, nowEpochMillis = 1_000)
        assertEquals(11_000, store.value!!.nextDeadlineEpochMillis)
        assertEquals(AnalysisSyncDecision.NotDue(5_000), engine.runOnce(contract, 6_000))
        assertEquals(AnalysisSyncDecision.Continue(30_000), engine.runOnce(contract, 11_000))
        assertEquals(41_000, store.value!!.nextDeadlineEpochMillis)
        assertEquals(listOf("remote", "persist"), timeline.takeLast(2))

        store.value = store.value!!.copy(pollStep = 2, nextDeadlineEpochMillis = 41_000)
        remote.enqueue(detail(AnalysisStatus.PROCESSING, generation = 1))
        assertEquals(AnalysisSyncDecision.Continue(300_000), engine.runOnce(contract, 41_000))
    }

    @Test
    fun `进程重启使用剩余deadline而不重置序列`() = runBlocking {
        val store = MemoryAnalysisCheckpointStore().apply {
            value = checkpoint(pollStep = 2, deadline = 61_000)
        }
        val restarted = AnalysisSyncEngine(store, QueueAnalysisRemote())

        assertEquals(AnalysisSyncDecision.NotDue(11_000), restarted.runOnce(contract(), 50_000))
        assertEquals(2, store.value!!.pollStep)
    }

    @Test
    fun `terminal持久后停止且unknown与contract mismatch fail closed`() = runBlocking {
        val store = MemoryAnalysisCheckpointStore().apply { value = checkpoint(deadline = 1_000) }
        val remote = QueueAnalysisRemote(detail(AnalysisStatus.COMPLETED, generation = 2))
        val engine = AnalysisSyncEngine(store, remote)

        assertEquals(AnalysisSyncDecision.Terminal, engine.runOnce(contract(), 1_000))
        assertEquals(AnalysisStatus.COMPLETED, store.value!!.status)
        assertEquals(AnalysisSyncDecision.Terminal, engine.runOnce(contract(), 2_000))
        assertEquals(1, remote.calls)

        val unknownStore = MemoryAnalysisCheckpointStore().apply { value = checkpoint(deadline = 1_000) }
        assertEquals(
            AnalysisSyncDecision.Rejected,
            AnalysisSyncEngine(unknownStore, QueueAnalysisRemote(detail(AnalysisStatus.UNKNOWN, 1))).runOnce(contract(), 1_000),
        )
        val wrong = contract().copy(incarnationProof = "b".repeat(64))
        assertEquals(AnalysisSyncDecision.Rejected, engine.runOnce(wrong, 2_000))
    }

    @Test
    fun `网络故障安全重试而归属或generation倒退拒绝且不持久`() = runBlocking {
        val store = MemoryAnalysisCheckpointStore().apply { value = checkpoint(deadline = 1_000) }
        val retry = AnalysisSyncEngine(store, QueueAnalysisRemote().apply { failure = IOException("timeout") })
        assertEquals(AnalysisSyncDecision.Retry(), retry.runOnce(contract(), 1_000))

        val oldVersion = store.value
        val mismatch = AnalysisSyncEngine(store, QueueAnalysisRemote(detail(AnalysisStatus.PROCESSING, 0, sessionId = "other")))
        assertEquals(AnalysisSyncDecision.Rejected, mismatch.runOnce(contract(), 1_000))
        assertEquals(oldVersion, store.value)
    }

    @Test
    fun `后台轮询把checkpoint generation作为远端single flight下限`() = runBlocking {
        val store = MemoryAnalysisCheckpointStore().apply {
            value = checkpoint(deadline = 1_000).copy(analysisGeneration = 2)
        }
        val remote = MinimumGenerationRecordingRemote(detail(AnalysisStatus.PROCESSING, 2))

        assertEquals(
            AnalysisSyncDecision.Continue(30_000L),
            AnalysisSyncEngine(store, remote).runOnce(contract(), 1_000),
        )
        assertEquals(listOf(2), remote.minimumGenerations)
    }

    @Test
    fun `429与5xx保留Retry After安全退避而普通4xx拒绝`() = runBlocking {
        val store = MemoryAnalysisCheckpointStore().apply { value = checkpoint(deadline = 1_000) }
        val throttled = AnalysisSyncEngine(
            store,
            QueueAnalysisRemote().apply { failure = AnalysisRemoteRetryException(45_000L) },
        )
        assertEquals(AnalysisSyncDecision.Retry(45_000L), throttled.runOnce(contract(), 1_000))

        val serverFailure = AnalysisSyncEngine(
            store,
            QueueAnalysisRemote().apply { failure = AnalysisRemoteRetryException(null) },
        )
        assertEquals(AnalysisSyncDecision.Retry(), serverFailure.runOnce(contract(), 1_000))

        assertEquals(120_000L, classifyAnalysisRetry(429, "120", nowEpochMillis = 1_000L)?.retryAfterMillis)
        assertEquals(null, classifyAnalysisRetry(404, "120", nowEpochMillis = 1_000L))
        assertEquals(null, classifyAnalysisRetry(429, "not-a-date", nowEpochMillis = 1_000L)?.retryAfterMillis)
    }

    @Test
    fun `后台远端请求取消必须原样传播`() = runBlocking {
        val cancellation = CancellationException("停止分析轮询")
        val store = MemoryAnalysisCheckpointStore().apply { value = checkpoint(deadline = 1_000) }
        val engine = AnalysisSyncEngine(store, QueueAnalysisRemote().apply { failure = cancellation })

        try {
            engine.runOnce(contract(), 1_000)
            fail("取消不应转换为拒绝结果")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }
    }

    @Test
    fun `前台与后台同session共享single flight且结果不倒退`() = runBlocking {
        val gate = CompletableDeferred<AnalysisDetail>()
        val remote = SuspendingAnalysisRemote(gate)
        val synchronizer = AnalysisDetailSynchronizer(remote)
        val first = async { synchronizer.fetch("s1", minimumGeneration = 1) }
        remote.entered.await()
        val second = async { synchronizer.fetch("s1", minimumGeneration = 1) }
        gate.complete(detail(AnalysisStatus.PROCESSING, 2))

        assertEquals(2, first.await().generation)
        assertEquals(2, second.await().generation)
        assertEquals(1, remote.calls.get())
        assertEquals(null, synchronizer.accept(detail(AnalysisStatus.PROCESSING, 1)))
    }

    @Test
    fun `生产账户作用域同步器让前后台共享请求并隔离incarnation`() = runBlocking {
        val gate = CompletableDeferred<AnalysisDetail>()
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val synchronizer = AccountScopedSessionSynchronizer(
            source = { _, sessionId ->
                calls.incrementAndGet()
                entered.complete(Unit)
                gate.await().copy(sessionId = sessionId)
            },
            identity = { VersionedSessionIdentity(it.sessionId, it.generation, it.isTerminal()) },
        )
        val first = async { synchronizer.fetch("a".repeat(64), "b".repeat(64), "s1", 1) }
        entered.await()
        val second = async { synchronizer.fetch("a".repeat(64), "b".repeat(64), "s1", 1) }
        yield()
        gate.complete(detail(AnalysisStatus.PROCESSING, 2))

        assertEquals(2, first.await().generation)
        assertEquals(2, second.await().generation)
        assertEquals(1, calls.get())

        val otherProof = synchronizer.fetch("a".repeat(64), "c".repeat(64), "s1", 1)
        assertEquals(2, otherProof.generation)
        assertEquals(2, calls.get())
    }

    @Test
    fun `更高minimumGeneration不得加入旧代际single flight`() = runBlocking {
        val generationOne = CompletableDeferred<AnalysisDetail>()
        val generationTwo = CompletableDeferred<AnalysisDetail>()
        val secondEntered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val synchronizer = AccountScopedSessionSynchronizer(
            source = { _, sessionId ->
                when (calls.incrementAndGet()) {
                    1 -> generationOne.await().copy(sessionId = sessionId)
                    else -> {
                        secondEntered.complete(Unit)
                        generationTwo.await().copy(sessionId = sessionId)
                    }
                }
            },
            identity = { VersionedSessionIdentity(it.sessionId, it.generation, it.isTerminal()) },
        )

        val oldRequest = async { synchronizer.fetch("a".repeat(64), "b".repeat(64), "s1", 1) }
        yield()
        val postRetryRequest = async { synchronizer.fetch("a".repeat(64), "b".repeat(64), "s1", 2) }
        assertTrue(
            "更高代际请求应启动独立远端调用",
            withTimeoutOrNull(1_000L) { secondEntered.await(); true } == true,
        )

        generationOne.complete(detail(AnalysisStatus.PROCESSING, 1))
        generationTwo.complete(detail(AnalysisStatus.PROCESSING, 2))
        assertEquals(1, oldRequest.await().generation)
        assertEquals(2, postRetryRequest.await().generation)
        assertEquals(2, calls.get())
    }

    @Test
    fun `前台每3秒查询且离页真实取消并在terminal停止`() = runBlocking {
        val delays = mutableListOf<Long>()
        val remote = QueueAnalysisRemote(
            detail(AnalysisStatus.PROCESSING, 1),
            detail(AnalysisStatus.PROCESSING, 1),
            detail(AnalysisStatus.COMPLETED, 1),
        )
        val poller = ResultForegroundPoller(
            sessionId = "s1",
            synchronizer = AnalysisDetailSynchronizer(remote),
            delayMillis = { delays += it },
        )
        poller.runUntilTerminal()
        assertEquals(listOf(3_000L, 3_000L), delays)
        assertEquals(3, remote.calls)

        val never = CompletableDeferred<AnalysisDetail>()
        val waitingRemote = SuspendingAnalysisRemote(never)
        val job = launch { ResultForegroundPoller("s1", AnalysisDetailSynchronizer(waitingRemote)).runUntilTerminal() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(never.isCompleted)
    }

    private fun contract() = AnalysisWorkContract(
        accountScopeHash = "a".repeat(64),
        sessionId = "s1",
        incarnationProof = "c".repeat(64),
    )

    private fun checkpoint(pollStep: Int = 0, deadline: Long) = AnalysisCheckpoint(
        accountScopeHash = "a".repeat(64),
        sessionId = "s1",
        incarnationProof = "c".repeat(64),
        status = AnalysisStatus.PROCESSING,
        analysisGeneration = 1,
        pollStep = pollStep,
        nextDeadlineEpochMillis = deadline,
        version = 1,
    )

    private fun detail(status: AnalysisStatus, generation: Int, sessionId: String = "s1") =
        AnalysisDetail(sessionId, status, generation)
}

private class MemoryAnalysisCheckpointStore(
    private val timeline: MutableList<String> = mutableListOf(),
) : AnalysisCheckpointStore {
    var value: AnalysisCheckpoint? = null
    override suspend fun load(sessionId: String): AnalysisCheckpoint? = value
    override suspend fun persist(expectedVersion: Long?, checkpoint: AnalysisCheckpoint): Boolean {
        if (expectedVersion != value?.version) return false
        timeline += "persist"
        value = checkpoint
        return true
    }
}

private class QueueAnalysisRemote(
    private val timeline: MutableList<String> = mutableListOf(),
    vararg initial: AnalysisDetail,
) : AnalysisDetailRemote {
    private val queue = ArrayDeque(initial.toList())
    var failure: Exception? = null
    var calls = 0
    constructor(vararg initial: AnalysisDetail) : this(mutableListOf(), *initial)
    fun enqueue(detail: AnalysisDetail) { queue += detail }
    override suspend fun fetch(sessionId: String): AnalysisDetail {
        calls += 1
        timeline += "remote"
        failure?.let { throw it }
        return queue.removeFirst()
    }
}

private class MinimumGenerationRecordingRemote(
    private val value: AnalysisDetail,
) : AnalysisDetailRemote {
    val minimumGenerations = mutableListOf<Int>()
    override suspend fun fetch(sessionId: String): AnalysisDetail = fetch(sessionId, 0)
    override suspend fun fetch(sessionId: String, minimumGeneration: Int): AnalysisDetail {
        minimumGenerations += minimumGeneration
        return value.copy(sessionId = sessionId)
    }
}

private class SuspendingAnalysisRemote(private val result: CompletableDeferred<AnalysisDetail>) : AnalysisDetailRemote {
    val calls = AtomicInteger()
    val entered = CompletableDeferred<Unit>()
    override suspend fun fetch(sessionId: String): AnalysisDetail {
        calls.incrementAndGet()
        entered.complete(Unit)
        return result.await()
    }
}
