package com.vocaease.patient.feature.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocaease.patient.VocaEaseApplication

fun interface AnalysisWorkerGateway {
    suspend fun runAndSchedule(contract: AnalysisAndroidWorkContract): AnalysisSyncDecision
}

class AnalysisSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val contract = parseContract() ?: return Result.failure()
        val gateway = runCatching {
            ((applicationContext as? VocaEaseApplication)?.container?.repositoryFactory?.create("analysis-worker")) as? AnalysisWorkerGateway
        }.getOrNull() ?: return Result.failure()
        return when (val decision = gateway.runAndSchedule(contract)) {
            is AnalysisSyncDecision.NotDue,
            is AnalysisSyncDecision.Continue -> Result.success()
            AnalysisSyncDecision.Terminal -> Result.success()
            is AnalysisSyncDecision.Retry -> if (decision.minimumDelayMillis != null) Result.success() else Result.retry()
            AnalysisSyncDecision.Rejected -> Result.failure()
        }
    }

    private fun parseContract(): AnalysisAndroidWorkContract? = runCatching {
        AnalysisAndroidWorkContract(
            requireNotNull(inputData.getString(AnalysisAndroidWorkContract.ACCOUNT_SCOPE_HASH_KEY)),
            requireNotNull(inputData.getString(AnalysisAndroidWorkContract.SESSION_ID_KEY)),
            requireNotNull(inputData.getString(AnalysisAndroidWorkContract.INCARNATION_PROOF_KEY)),
        )
    }.getOrNull()
}

interface AnalysisWorkScheduling {
    fun start(contract: AnalysisAndroidWorkContract, delayMillis: Long)
    fun append(contract: AnalysisAndroidWorkContract, delayMillis: Long)
    fun replace(contract: AnalysisAndroidWorkContract, delayMillis: Long)
    fun cancelAccount(accountScopeHash: String)
}

class AndroidAnalysisWorkScheduler(context: Context) : AnalysisWorkScheduling {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun start(contract: AnalysisAndroidWorkContract, delayMillis: Long) {
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            contract.request(delayMillis),
        )
    }

    override fun append(contract: AnalysisAndroidWorkContract, delayMillis: Long) {
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            contract.request(delayMillis),
        )
    }

    override fun replace(contract: AnalysisAndroidWorkContract, delayMillis: Long) {
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            contract.request(delayMillis),
        )
    }

    override fun cancelAccount(accountScopeHash: String) {
        if (accountScopeHash.matches(Regex("[0-9a-f]{64}"))) {
            workManager.cancelAllWorkByTag(AnalysisAndroidWorkContract.ACCOUNT_TAG_PREFIX + accountScopeHash)
        }
    }
}
