package com.vocaease.patient.feature.history

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSyncCoordinatorTest {
    @Test
    fun `首次调度先持久checkpoint再启动唯一工作`() = runBlocking {
        val scope = FakeAnalysisScope()
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 1_000L })

        coordinator.schedule("session-1")

        assertEquals(AnalysisStatus.PROCESSING, scope.checkpoint?.status)
        assertEquals(11_000L, scope.checkpoint?.nextDeadlineEpochMillis)
        assertEquals(listOf("analysis:${scope.accountScopeHash}:session-1"), scheduler.started.map { it.first.uniqueWorkName })
        assertEquals(listOf(10_000L), scheduler.started.map { it.second })
    }

    @Test
    fun `进程恢复按剩余deadline调度且不重置轮询步`() = runBlocking {
        val scope = FakeAnalysisScope().apply {
            checkpoint = checkpoint(pollStep = 2, deadline = 70_000L, version = 4)
        }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 40_000L })

        coordinator.schedule("session-1")

        assertEquals(2, scope.checkpoint?.pollStep)
        assertEquals(4L, scope.checkpoint?.version)
        assertEquals(30_000L, scheduler.started.single().second)
    }

    @Test
    fun `worker拒绝换号与同患者新incarnation`() = runBlocking {
        val scope = FakeAnalysisScope().apply { checkpoint = checkpoint() }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 20_000L })

        assertEquals(
            AnalysisSyncDecision.Rejected,
            coordinator.run(AnalysisAndroidWorkContract("b".repeat(64), "session-1", scope.incarnationProof)),
        )
        assertEquals(
            AnalysisSyncDecision.Rejected,
            coordinator.run(AnalysisAndroidWorkContract(scope.accountScopeHash, "session-1", "c".repeat(64))),
        )
    }

    @Test
    fun `terminal恢复不再排工作且取消账户只按hash`() = runBlocking {
        val scope = FakeAnalysisScope().apply {
            checkpoint = checkpoint(status = AnalysisStatus.COMPLETED)
        }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 20_000L })

        coordinator.schedule("session-1")
        coordinator.cancelAccount(scope.accountScopeHash)

        assertTrue(scheduler.started.isEmpty())
        assertEquals(listOf(scope.accountScopeHash), scheduler.cancelled)
    }

    @Test
    fun `取消与初始化并发时持久化完成后不得重新启动工作`() = runBlocking {
        val persistEntered = CompletableDeferred<Unit>()
        val allowPersist = CompletableDeferred<Unit>()
        val scope = FakeAnalysisScope().apply {
            beforePersist = {
                persistEntered.complete(Unit)
                allowPersist.await()
            }
        }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 1_000L })

        val scheduling = async { coordinator.schedule("session-1") }
        persistEntered.await()
        coordinator.cancelAccount(scope.accountScopeHash)
        allowPersist.complete(Unit)
        scheduling.await()

        assertTrue(scheduler.started.isEmpty())
        assertEquals(listOf(scope.accountScopeHash), scheduler.cancelled)
    }

    @Test
    fun `取消与worker请求并发时远端返回后不得追加下一段工作`() = runBlocking {
        val fetchEntered = CompletableDeferred<Unit>()
        val allowFetch = CompletableDeferred<Unit>()
        val scope = FakeAnalysisScope().apply { checkpoint = checkpoint(deadline = 1_000L) }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator(
            { scope },
            { CoordinatorSuspendingAnalysisRemote(fetchEntered, allowFetch) },
            scheduler,
            { 1_000L },
        )
        val contract = AnalysisAndroidWorkContract(scope.accountScopeHash, "session-1", scope.incarnationProof)

        val worker = async { coordinator.runAndSchedule(contract) }
        fetchEntered.await()
        coordinator.cancelAccount(scope.accountScopeHash)
        allowFetch.complete(Unit)

        assertEquals(AnalysisSyncDecision.Rejected, worker.await())
        assertTrue(scheduler.appended.isEmpty())
    }

    @Test
    fun `终态重试以更高generation原子重建checkpoint并替换后台链`() = runBlocking {
        val scope = FakeAnalysisScope().apply {
            checkpoint = checkpoint(status = AnalysisStatus.FAILED, pollStep = 3, deadline = 80_000L, version = 6)
        }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 90_000L })

        assertTrue(coordinator.restart("session-1", targetGeneration = 2))

        assertEquals(AnalysisStatus.RETRYING, scope.checkpoint?.status)
        assertEquals(2, scope.checkpoint?.analysisGeneration)
        assertEquals(0, scope.checkpoint?.pollStep)
        assertEquals(100_000L, scope.checkpoint?.nextDeadlineEpochMillis)
        assertEquals(7L, scope.checkpoint?.version)
        assertEquals(listOf("analysis:${scope.accountScopeHash}:session-1"), scheduler.replaced.map { it.first.uniqueWorkName })
        assertEquals(listOf(10_000L), scheduler.replaced.map { it.second })
    }

    @Test
    fun `服务端已接受更高generation时processing检查点原子推进并替换后台链`() = runBlocking {
        val scope = FakeAnalysisScope().apply {
            checkpoint = checkpoint(status = AnalysisStatus.PROCESSING, pollStep = 2, deadline = 80_000L, version = 6)
        }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 90_000L })

        assertTrue(coordinator.restart("session-1", targetGeneration = 2))

        assertEquals(AnalysisStatus.RETRYING, scope.checkpoint?.status)
        assertEquals(2, scope.checkpoint?.analysisGeneration)
        assertEquals(0, scope.checkpoint?.pollStep)
        assertEquals(100_000L, scope.checkpoint?.nextDeadlineEpochMillis)
        assertEquals(7L, scope.checkpoint?.version)
        assertEquals(listOf("analysis:${scope.accountScopeHash}:session-1"), scheduler.replaced.map { it.first.uniqueWorkName })
    }

    @Test
    fun `服务端Retry After由同一屏障追加延迟工作`() = runBlocking {
        val scope = FakeAnalysisScope().apply { checkpoint = checkpoint(deadline = 1_000L) }
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator(
            { scope },
            { ThrowingAnalysisRemote(AnalysisRemoteRetryException(45_000L)) },
            scheduler,
            { 1_000L },
        )
        val contract = AnalysisAndroidWorkContract(scope.accountScopeHash, "session-1", scope.incarnationProof)

        assertEquals(AnalysisSyncDecision.Retry(45_000L), coordinator.runAndSchedule(contract))
        assertEquals(listOf(45_000L), scheduler.appended.map { it.second })
    }

    @Test
    fun `历史旧失败会话没有checkpoint时重试仍创建新generation后台链`() = runBlocking {
        val scope = FakeAnalysisScope()
        val scheduler = FakeAnalysisScheduler()
        val coordinator = AnalysisSyncCoordinator({ scope }, { FakeAnalysisRemote() }, scheduler, { 90_000L })

        assertTrue(coordinator.restart("legacy-session", targetGeneration = 4))

        assertEquals("legacy-session", scope.checkpoint?.sessionId)
        assertEquals(AnalysisStatus.RETRYING, scope.checkpoint?.status)
        assertEquals(4, scope.checkpoint?.analysisGeneration)
        assertEquals(0L, scope.checkpoint?.version)
        assertEquals(listOf("analysis:${scope.accountScopeHash}:legacy-session"), scheduler.replaced.map { it.first.uniqueWorkName })
    }
}

private class FakeAnalysisScope : AnalysisAccountScope, AnalysisCheckpointStore {
    override val accountScopeHash = "a".repeat(64)
    override val incarnationProof = "d".repeat(64)
    override val leaseActive: Boolean get() = true
    var checkpoint: AnalysisCheckpoint? = null
    var beforePersist: suspend () -> Unit = {}
    override val checkpointStore: AnalysisCheckpointStore get() = this
    override suspend fun load(sessionId: String): AnalysisCheckpoint? = checkpoint
    override suspend fun persist(expectedVersion: Long?, checkpoint: AnalysisCheckpoint): Boolean {
        beforePersist()
        if (expectedVersion != this.checkpoint?.version) return false
        this.checkpoint = checkpoint
        return true
    }

    fun checkpoint(
        status: AnalysisStatus = AnalysisStatus.PROCESSING,
        pollStep: Int = 0,
        deadline: Long = 10_000L,
        version: Long = 0,
    ) = AnalysisCheckpoint(accountScopeHash, "session-1", incarnationProof, status, 1, pollStep, deadline, version)
}

private class FakeAnalysisRemote : AnalysisDetailRemote {
    override suspend fun fetch(sessionId: String) = AnalysisDetail(sessionId, AnalysisStatus.PROCESSING, 1)
}

private class ThrowingAnalysisRemote(private val failure: Exception) : AnalysisDetailRemote {
    override suspend fun fetch(sessionId: String): AnalysisDetail = throw failure
}

private class CoordinatorSuspendingAnalysisRemote(
    private val entered: CompletableDeferred<Unit>,
    private val proceed: CompletableDeferred<Unit>,
) : AnalysisDetailRemote {
    override suspend fun fetch(sessionId: String): AnalysisDetail {
        entered.complete(Unit)
        proceed.await()
        return AnalysisDetail(sessionId, AnalysisStatus.PROCESSING, 1)
    }
}

private class FakeAnalysisScheduler : AnalysisWorkScheduling {
    val started = mutableListOf<Pair<AnalysisAndroidWorkContract, Long>>()
    val appended = mutableListOf<Pair<AnalysisAndroidWorkContract, Long>>()
    val replaced = mutableListOf<Pair<AnalysisAndroidWorkContract, Long>>()
    val cancelled = mutableListOf<String>()
    override fun start(contract: AnalysisAndroidWorkContract, delayMillis: Long) { started += contract to delayMillis }
    override fun append(contract: AnalysisAndroidWorkContract, delayMillis: Long) { appended += contract to delayMillis }
    override fun replace(contract: AnalysisAndroidWorkContract, delayMillis: Long) { replaced += contract to delayMillis }
    override fun cancelAccount(accountScopeHash: String) { cancelled += accountScopeHash }
}
