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
    suspend fun schedule(sessionId: String) {
        val scope = scopeProvider()
        if (!scope.leaseActive) return
        val contract = AnalysisAndroidWorkContract(
            scope.accountScopeHash,
            sessionId,
            scope.incarnationProof,
        )
        val engine = AnalysisSyncEngine(scope.checkpointStore, remoteFactory(scope))
        val checkpoint = engine.initialize(contract.core(), nowEpochMillis())
        if (!scope.leaseActive || checkpoint.status in TERMINAL_STATUSES) return
        scheduler.start(contract, (checkpoint.nextDeadlineEpochMillis - nowEpochMillis()).coerceAtLeast(0))
    }

    override suspend fun run(contract: AnalysisAndroidWorkContract): AnalysisSyncDecision {
        val scope = runCatching { scopeProvider() }.getOrNull() ?: return AnalysisSyncDecision.Rejected
        if (!scope.leaseActive || scope.accountScopeHash != contract.accountScopeHash ||
            scope.incarnationProof != contract.incarnationProof
        ) return AnalysisSyncDecision.Rejected
        return AnalysisSyncEngine(scope.checkpointStore, remoteFactory(scope))
            .runOnce(contract.core(), nowEpochMillis())
    }

    fun cancelAccount(accountScopeHash: String) = scheduler.cancelAccount(accountScopeHash)

    private companion object {
        val TERMINAL_STATUSES = setOf(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.CANCELLED)
    }
}
