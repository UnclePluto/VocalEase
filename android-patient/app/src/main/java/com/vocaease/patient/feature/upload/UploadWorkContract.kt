package com.vocaease.patient.feature.upload

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.concurrent.TimeUnit

data class UploadWorkContract(
    val accountScopeHash: String,
    val draftId: String,
) {
    init {
        require(accountScopeHash.matches(SHA256))
        require(draftId.matches(OPAQUE_DRAFT_ID))
    }

    val uniqueWorkName: String = "upload:$accountScopeHash:$draftId"
    val input: Map<String, String> = mapOf(ACCOUNT_SCOPE_HASH_KEY to accountScopeHash, DRAFT_ID_KEY to draftId)
    val networkType: NetworkType = NetworkType.CONNECTED
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
    val backoffDelayMillis: Long = 30_000L
    val maxRunMillis: Long = 5L * 60L * 60L * 1_000L

    internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setBackoffCriteria(backoffPolicy, backoffDelayMillis, TimeUnit.MILLISECONDS)
        .setInputData(
            Data.Builder()
                .putString(ACCOUNT_SCOPE_HASH_KEY, accountScopeHash)
                .putString(DRAFT_ID_KEY, draftId)
                .build(),
        )
        .addTag(ACCOUNT_TAG_PREFIX + accountScopeHash)
        .addTag(WORK_TAG)
        .build()

    internal companion object {
        const val ACCOUNT_SCOPE_HASH_KEY = "account_scope_hash"
        const val DRAFT_ID_KEY = "draft_id"
        const val ACCOUNT_TAG_PREFIX = "upload-account:"
        const val WORK_TAG = "vocaease-upload"
        val SHA256 = Regex("[0-9a-f]{64}")
        val OPAQUE_DRAFT_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
