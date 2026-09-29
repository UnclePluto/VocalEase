package com.vocaease.patient.feature.history

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.concurrent.TimeUnit

data class AnalysisAndroidWorkContract(
    val accountScopeHash: String,
    val sessionId: String,
    val incarnationProof: String,
) {
    private val core = AnalysisWorkContract(accountScopeHash, sessionId, incarnationProof)
    val uniqueWorkName: String = core.uniqueWorkName
    val input: Map<String, String> = mapOf(
        ACCOUNT_SCOPE_HASH_KEY to accountScopeHash,
        SESSION_ID_KEY to sessionId,
        INCARNATION_PROOF_KEY to incarnationProof,
    )
    val networkType: NetworkType = NetworkType.CONNECTED
    val initialDelayMillis: Long = AnalysisSyncEngine.POLL_DELAYS.first()
    val requiresForegroundNotification: Boolean = false

    internal fun request(delayMillis: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AnalysisSyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setInputData(
            Data.Builder()
                .putString(ACCOUNT_SCOPE_HASH_KEY, accountScopeHash)
                .putString(SESSION_ID_KEY, sessionId)
                .putString(INCARNATION_PROOF_KEY, incarnationProof)
                .build(),
        )
        .addTag(ACCOUNT_TAG_PREFIX + accountScopeHash)
        .addTag(WORK_TAG)
        .build()

    internal fun core(): AnalysisWorkContract = core

    internal companion object {
        const val ACCOUNT_SCOPE_HASH_KEY = "account_scope_hash"
        const val SESSION_ID_KEY = "session_id"
        const val INCARNATION_PROOF_KEY = "incarnation_proof"
        const val ACCOUNT_TAG_PREFIX = "analysis-account:"
        const val WORK_TAG = "vocaease-analysis"
    }
}
