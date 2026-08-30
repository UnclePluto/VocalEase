package com.vocaease.patient.core.cleanup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocaease.patient.VocaEaseApplication
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.util.concurrent.TimeUnit

data class DraftCleanupWorkContract(
    val scopeHash: String,
    val scopeToken: String,
) {
    init {
        require(scopeHash.matches(SHA256))
        require(scopeToken.matches(SHA256))
    }

    val initialDelayMillis: Long = DAY_MILLIS
    val uniqueWorkName: String = "$WORK_PREFIX$scopeHash"
    val input: Map<String, String> = mapOf(SCOPE_HASH_KEY to scopeHash, SCOPE_TOKEN_KEY to scopeToken)
    val requiresNetwork: Boolean = false
    val showsNotification: Boolean = false

    internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DraftCleanupWorker>()
        .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
        .setInputData(Data.Builder().putString(SCOPE_HASH_KEY, scopeHash).putString(SCOPE_TOKEN_KEY, scopeToken).build())
        .addTag(WORK_TAG)
        .build()

    internal companion object {
        const val SCOPE_HASH_KEY = "scope_hash"
        const val SCOPE_TOKEN_KEY = "scope_token"
        const val WORK_PREFIX = "vocaease-draft-cleanup-"
        const val WORK_TAG = "vocaease-draft-cleanup"
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

class DailyDraftCleanupScheduler internal constructor(
    private val workManager: WorkManager,
) {
    constructor(context: Context) : this(WorkManager.getInstance(context.applicationContext))

    @Synchronized
    fun replaceFor(storage: AccountScopedDraftStorage?) {
        workManager.cancelAllWorkByTag(DraftCleanupWorkContract.WORK_TAG)
        if (storage == null || !storage.isLeaseActive()) return
        val contract = DraftCleanupWorkContract(storage.accountScopeHash, storage.cleanupScopeToken)
        workManager.enqueueUniqueWork(contract.uniqueWorkName, ExistingWorkPolicy.REPLACE, contract.request())
    }

    internal fun appendNext(contract: DraftCleanupWorkContract) {
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            contract.request(),
        )
    }
}

class DraftCleanupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val scopeHash = inputData.getString(DraftCleanupWorkContract.SCOPE_HASH_KEY) ?: return Result.failure()
        val scopeToken = inputData.getString(DraftCleanupWorkContract.SCOPE_TOKEN_KEY) ?: return Result.failure()
        val contract = runCatching { DraftCleanupWorkContract(scopeHash, scopeToken) }.getOrElse {
            return Result.failure()
        }
        val application = applicationContext as? VocaEaseApplication ?: return Result.failure()
        val storage = runCatching { application.container.draftStorage.current() }.getOrElse {
            return Result.success()
        }
        if (storage.accountScopeHash != contract.scopeHash || storage.cleanupScopeToken != contract.scopeToken ||
            !storage.isLeaseActive()
        ) return Result.success()

        return try {
            storage.cleanupExpiredLocalDrafts(application.container.clock.nowEpochMilliseconds())
            if (!storage.isLeaseActive()) return Result.success()
            DailyDraftCleanupScheduler(applicationContext).appendNext(contract)
            Result.success()
        } catch (_: StaleAccountScopeException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
