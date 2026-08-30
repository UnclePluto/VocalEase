package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class UploadStepState {
    PENDING,
    REQUESTING_GRANT,
    GRANT_READY,
    UPLOADING,
    UPLOADED,
    WAITING_RECEIPT,
    RECEIPT_RECEIVED,
    CONFIRMING,
    CONFIRMED,
    SUBMITTING,
    SUBMITTED,
    ANALYZING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
}

enum class UploadOverallState {
    PAUSED,
    WAITING_NETWORK,
    UPLOADING,
    WAITING_CALLBACK,
    CONFIRMING,
    READY_TO_SUBMIT,
    SUBMITTING,
    ANALYZING,
    FAILED,
    CANCELLED,
    COMPLETED,
}

enum class UploadPipelineStage {
    PAUSED,
    WAITING_NETWORK,
    REQUESTING_AUDIO_GRANT,
    UPLOADING_AUDIO,
    WAITING_AUDIO_RECEIPT,
    CONFIRMING_AUDIO,
    REQUESTING_VIDEO_GRANT,
    UPLOADING_VIDEO,
    WAITING_VIDEO_RECEIPT,
    CONFIRMING_VIDEO,
    SUBMITTING,
    ANALYZING,
    FAILED,
}

@Entity(
    tableName = "upload_jobs",
    primaryKeys = ["account_scope", "draft_id"],
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["account_scope", "draft_id"],
            childColumns = ["account_scope", "draft_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
            deferred = false,
        ),
    ],
    indices = [
        Index(value = ["account_scope", "draft_id"]),
        Index(value = ["account_scope", "next_retry_at"]),
    ],
)
data class UploadJobEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "overall_state") val overallState: UploadOverallState = UploadOverallState.PAUSED,
    @ColumnInfo(name = "pipeline_stage") val pipelineStage: UploadPipelineStage = UploadPipelineStage.PAUSED,
    @ColumnInfo(name = "audio_grant_state") val audioGrantState: UploadStepState,
    @ColumnInfo(name = "video_grant_state") val videoGrantState: UploadStepState,
    @ColumnInfo(name = "audio_upload_state") val audioUploadState: UploadStepState,
    @ColumnInfo(name = "video_upload_state") val videoUploadState: UploadStepState,
    @ColumnInfo(name = "audio_receipt_state") val audioReceiptState: UploadStepState = UploadStepState.PENDING,
    @ColumnInfo(name = "video_receipt_state") val videoReceiptState: UploadStepState = UploadStepState.PENDING,
    @ColumnInfo(name = "audio_confirm_state") val audioConfirmState: UploadStepState = UploadStepState.PENDING,
    @ColumnInfo(name = "video_confirm_state") val videoConfirmState: UploadStepState = UploadStepState.PENDING,
    @ColumnInfo(name = "submit_state") val submitState: UploadStepState,
    @ColumnInfo(name = "audio_grant_key") val audioGrantKey: String,
    @ColumnInfo(name = "video_grant_key") val videoGrantKey: String,
    @ColumnInfo(name = "submit_key") val submitKey: String,
    @ColumnInfo(name = "audio_asset_key") val audioAssetKey: String?,
    @ColumnInfo(name = "video_asset_key") val videoAssetKey: String?,
    @ColumnInfo(name = "audio_object_key") val audioObjectKey: String?,
    @ColumnInfo(name = "video_object_key") val videoObjectKey: String?,
    @ColumnInfo(name = "audio_receipt") val audioReceipt: String? = null,
    @ColumnInfo(name = "video_receipt") val videoReceipt: String? = null,
    @ColumnInfo(name = "audio_confirmed_at") val audioConfirmedAt: Long? = null,
    @ColumnInfo(name = "video_confirmed_at") val videoConfirmedAt: Long? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long?,
    @ColumnInfo(name = "last_safe_error") val lastSafeError: String?,
    @ColumnInfo(name = "progress_percent") val progressPercent: Int = 0,
    @ColumnInfo(name = "receipt_wait_attempt") val receiptWaitAttempt: Int = 0,
) {
    init {
        require(accountScope.isNotBlank())
        require(draftId.isNotBlank())
        require(audioGrantKey.isNotBlank())
        require(videoGrantKey.isNotBlank())
        require(submitKey.isNotBlank())
        require(listOf(audioGrantKey, videoGrantKey, submitKey).all { it.length <= 128 })
        require(attemptCount >= 0)
        require(nextRetryAt == null || nextRetryAt >= 0)
        require(lastSafeError == null || lastSafeError.length <= 256)
        require(progressPercent in 0..100)
        require(receiptWaitAttempt in 0..4)
        require(audioReceipt == null || audioReceipt.isNotBlank())
        require(videoReceipt == null || videoReceipt.isNotBlank())
        require(audioConfirmedAt == null || audioConfirmedAt >= 0)
        require(videoConfirmedAt == null || videoConfirmedAt >= 0)
        require(listOf(audioAssetKey, videoAssetKey, audioObjectKey, videoObjectKey, audioReceipt, videoReceipt).all { it == null || (it.isNotBlank() && it.length <= 512) })
    }

    companion object {
        fun newPending(
            accountScope: String,
            draftId: String,
            audioGrantKey: String,
            videoGrantKey: String,
            submitKey: String,
        ) = UploadJobEntity(
            accountScope = accountScope,
            draftId = draftId,
            overallState = UploadOverallState.PAUSED,
            pipelineStage = UploadPipelineStage.PAUSED,
            audioGrantState = UploadStepState.PENDING,
            videoGrantState = UploadStepState.PENDING,
            audioUploadState = UploadStepState.PENDING,
            videoUploadState = UploadStepState.PENDING,
            audioReceiptState = UploadStepState.PENDING,
            videoReceiptState = UploadStepState.PENDING,
            audioConfirmState = UploadStepState.PENDING,
            videoConfirmState = UploadStepState.PENDING,
            submitState = UploadStepState.PENDING,
            audioGrantKey = audioGrantKey,
            videoGrantKey = videoGrantKey,
            submitKey = submitKey,
            audioAssetKey = null,
            videoAssetKey = null,
            audioObjectKey = null,
            videoObjectKey = null,
            audioReceipt = null,
            videoReceipt = null,
            audioConfirmedAt = null,
            videoConfirmedAt = null,
            attemptCount = 0,
            nextRetryAt = null,
            lastSafeError = null,
            progressPercent = 0,
            receiptWaitAttempt = 0,
        )
    }
}
