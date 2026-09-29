package com.vocaease.patient.feature.history

interface AnalysisAccountScope {
    val accountScopeHash: String
    val incarnationProof: String
    val leaseActive: Boolean
    val checkpointStore: AnalysisCheckpointStore
}

class AnalysisSyncCoordinator(
    private val scopeProvider: () -> AnalysisAccountScope,
    private val remoteFactory: (AnalysisAccountScope) -> AnalysisDetailRemote,
    private val scheduler: AnalysisWorkScheduling,
    private val nowEpochMillis: () -> Long,
) : AnalysisWorkerGateway {
    private val barrier = Any()
    private val accountEpochs = mutableMapOf<String, Long>()

    suspend fun schedule(sessionId: String) {
        val scope = scopeProvider()
        if (!scope.leaseActive) return
        val epoch = synchronized(barrier) { accountEpochs[scope.accountScopeHash] ?: 0L }
        val contract = AnalysisAndroidWorkContract(
            scope.accountScopeHash,
            sessionId,
            scope.incarnationProof,
        )
        val engine = AnalysisSyncEngine(scope.checkpointStore, remoteFactory(scope))
        val checkpoint = engine.initialize(contract.core(), nowEpochMillis())
        if (!scope.leaseActive || checkpoint.status in TERMINAL_STATUSES) return
        synchronized(barrier) {
            if (epoch != (accountEpochs[scope.accountScopeHash] ?: 0L) || !scope.matches(contract)) return
            scheduler.start(contract, (checkpoint.nextDeadlineEpochMillis - nowEpochMillis()).coerceAtLeast(0))
        }
    }

    override suspend fun runAndSchedule(contract: AnalysisAndroidWorkContract): AnalysisSyncDecision {
        val scope = runCatching { scopeProvider() }.getOrNull() ?: return AnalysisSyncDecision.Rejected
        if (!scope.matches(contract)) return AnalysisSyncDecision.Rejected
        val epoch = synchronized(barrier) { accountEpochs[contract.accountScopeHash] ?: 0L }
        val decision = AnalysisSyncEngine(scope.checkpointStore, remoteFactory(scope))
            .runOnce(contract.core(), nowEpochMillis())
        val delay = when (decision) {
            is AnalysisSyncDecision.NotDue -> decision.remainingDelayMillis
            is AnalysisSyncDecision.Continue -> decision.delayMillis
            is AnalysisSyncDecision.Retry -> decision.minimumDelayMillis ?: return decision
            else -> return decision
        }
        synchronized(barrier) {
            val current = runCatching { scopeProvider() }.getOrNull()
            if (epoch != (accountEpochs[contract.accountScopeHash] ?: 0L) || current?.matches(contract) != true) {
                return AnalysisSyncDecision.Rejected
            }
            scheduler.append(contract, delay)
        }
        return decision
    }

    suspend fun run(contract: AnalysisAndroidWorkContract): AnalysisSyncDecision = runAndSchedule(contract)

    suspend fun restart(sessionId: String, targetGeneration: Int): Boolean {
        if (targetGeneration <= 0) return false
        val scope = runCatching { scopeProvider() }.getOrNull() ?: return false
        if (!scope.leaseActive) return false
        val epoch = synchronized(barrier) { accountEpochs[scope.accountScopeHash] ?: 0L }
        val now = nowEpochMillis()
        val checkpoint = scope.checkpointStore.load(sessionId)
        val restarted = if (checkpoint == null) {
            AnalysisCheckpoint(
                accountScopeHash = scope.accountScopeHash,
                sessionId = sessionId,
                incarnationProof = scope.incarnationProof,
                status = AnalysisStatus.RETRYING,
                analysisGeneration = targetGeneration,
                pollStep = 0,
                nextDeadlineEpochMillis = now + AnalysisSyncEngine.POLL_DELAYS.first(),
                version = 0,
            )
        } else {
            if (checkpoint.accountScopeHash != scope.accountScopeHash || checkpoint.incarnationProof != scope.incarnationProof ||
                checkpoint.status !in RESTARTABLE_STATUSES || targetGeneration <= checkpoint.analysisGeneration
            ) return false
            checkpoint.copy(
                status = AnalysisStatus.RETRYING,
                analysisGeneration = targetGeneration,
                pollStep = 0,
                nextDeadlineEpochMillis = now + AnalysisSyncEngine.POLL_DELAYS.first(),
                version = checkpoint.version + 1,
            )
        }
        if (!scope.checkpointStore.persist(checkpoint?.version, restarted)) return false
        val contract = AnalysisAndroidWorkContract(scope.accountScopeHash, sessionId, scope.incarnationProof)
        synchronized(barrier) {
            if (epoch != (accountEpochs[scope.accountScopeHash] ?: 0L) ||
                runCatching { scopeProvider() }.getOrNull()?.matches(contract) != true
            ) return false
            scheduler.replace(contract, AnalysisSyncEngine.POLL_DELAYS.first())
        }
        return true
    }

    fun cancelAccount(accountScopeHash: String) = synchronized(barrier) {
        accountEpochs[accountScopeHash] = (accountEpochs[accountScopeHash] ?: 0L) + 1L
        scheduler.cancelAccount(accountScopeHash)
    }

    private fun AnalysisAccountScope.matches(contract: AnalysisAndroidWorkContract): Boolean =
        leaseActive && accountScopeHash == contract.accountScopeHash && incarnationProof == contract.incarnationProof

    private companion object {
        val TERMINAL_STATUSES = setOf(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.CANCELLED)
        val RESTARTABLE_STATUSES = setOf(
            AnalysisStatus.UPLOADED,
            AnalysisStatus.PROCESSING,
            AnalysisStatus.RETRYING,
            AnalysisStatus.FAILED,
        )
    }
}
