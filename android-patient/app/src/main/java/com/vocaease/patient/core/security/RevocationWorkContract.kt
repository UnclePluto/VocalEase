package com.vocaease.patient.core.security

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import android.content.Context
import java.util.concurrent.TimeUnit

internal data class RevocationWorkContract(
    val handle: RevocationHandle,
) {
    val uniqueWorkName = "revocation:${handle.slotId}"
    val input = mapOf(SLOT_ID_KEY to handle.slotId)
    val networkType = NetworkType.CONNECTED
    val backoffPolicy = BackoffPolicy.EXPONENTIAL
    val backoffDelayMillis = 30_000L

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<RevocationWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setBackoffCriteria(backoffPolicy, backoffDelayMillis, TimeUnit.MILLISECONDS)
        .setInputData(Data.Builder().putString(SLOT_ID_KEY, handle.slotId).build())
        .addTag(WORK_TAG)
        .build()

    companion object {
        const val SLOT_ID_KEY = "revocation_slot_id"
        const val WORK_TAG = "vocaease-revocation"
    }
}

internal enum class RevocationWorkDecision { Finished, Retry, StopRetainingSlot }

internal object RevocationRetryPolicy {
    private const val MAX_AUTOMATIC_RETRIES = 5

    fun decide(execution: RevocationExecution, runAttemptCount: Int): RevocationWorkDecision = when {
        execution == RevocationExecution.Finished -> RevocationWorkDecision.Finished
        runAttemptCount < MAX_AUTOMATIC_RETRIES -> RevocationWorkDecision.Retry
        else -> RevocationWorkDecision.StopRetainingSlot
    }
}

fun interface RevocationScheduling {
    fun schedule(handle: RevocationHandle)
}

internal class AndroidRevocationScheduler(context: Context) : RevocationScheduling {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(handle: RevocationHandle) {
        val contract = RevocationWorkContract(handle)
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            contract.request(),
        )
    }
}
