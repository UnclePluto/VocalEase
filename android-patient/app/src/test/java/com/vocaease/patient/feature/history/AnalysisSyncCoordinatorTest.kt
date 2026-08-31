package com.vocaease.patient.feature.history

import kotlinx.coroutines.runBlocking
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
}

private class FakeAnalysisScope : AnalysisAccountScope, AnalysisCheckpointStore {
    override val accountScopeHash = "a".repeat(64)
    override val incarnationProof = "d".repeat(64)
    override val leaseActive: Boolean get() = true
    var checkpoint: AnalysisCheckpoint? = null
    override val checkpointStore: AnalysisCheckpointStore get() = this
    override suspend fun load(sessionId: String): AnalysisCheckpoint? = checkpoint
    override suspend fun persist(expectedVersion: Long?, checkpoint: AnalysisCheckpoint): Boolean {
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

private class FakeAnalysisScheduler : AnalysisWorkScheduling {
    val started = mutableListOf<Pair<AnalysisAndroidWorkContract, Long>>()
    val cancelled = mutableListOf<String>()
    override fun start(contract: AnalysisAndroidWorkContract, delayMillis: Long) { started += contract to delayMillis }
    override fun append(contract: AnalysisAndroidWorkContract, delayMillis: Long) = Unit
    override fun cancelAccount(accountScopeHash: String) { cancelled += accountScopeHash }
}
