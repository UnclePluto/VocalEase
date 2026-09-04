package com.vocaease.patient.core.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vocaease.patient.VocaEaseApplication
import java.util.concurrent.CancellationException

fun interface RevocationWorkerGateway {
    suspend fun revoke(handle: RevocationHandle): RevocationExecution
}

internal class RevocationWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val handle = runCatching {
            RevocationHandle(requireNotNull(inputData.getString(RevocationWorkContract.SLOT_ID_KEY)))
        }.getOrNull() ?: return Result.failure()
        val executor = runCatching {
            (applicationContext as? VocaEaseApplication)?.container?.revocationWorkerGateway
        }.getOrNull() ?: return Result.failure()
        val execution = try {
            executor.revoke(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RevocationExecution.Retry
        }
        return when (RevocationRetryPolicy.decide(execution, runAttemptCount)) {
            RevocationWorkDecision.Finished -> Result.success()
            RevocationWorkDecision.Retry -> Result.retry()
            RevocationWorkDecision.StopRetainingSlot -> Result.failure()
        }
    }
}
