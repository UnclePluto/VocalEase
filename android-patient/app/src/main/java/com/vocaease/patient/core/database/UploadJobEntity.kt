package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class UploadStepState {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
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
    @ColumnInfo(name = "audio_grant_state") val audioGrantState: UploadStepState,
    @ColumnInfo(name = "video_grant_state") val videoGrantState: UploadStepState,
    @ColumnInfo(name = "audio_upload_state") val audioUploadState: UploadStepState,
    @ColumnInfo(name = "video_upload_state") val videoUploadState: UploadStepState,
    @ColumnInfo(name = "submit_state") val submitState: UploadStepState,
    @ColumnInfo(name = "audio_grant_key") val audioGrantKey: String,
    @ColumnInfo(name = "video_grant_key") val videoGrantKey: String,
    @ColumnInfo(name = "submit_key") val submitKey: String,
    @ColumnInfo(name = "audio_asset_key") val audioAssetKey: String?,
    @ColumnInfo(name = "video_asset_key") val videoAssetKey: String?,
    @ColumnInfo(name = "audio_object_key") val audioObjectKey: String?,
    @ColumnInfo(name = "video_object_key") val videoObjectKey: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long?,
    @ColumnInfo(name = "last_safe_error") val lastSafeError: String?,
) {
    init {
        require(accountScope.isNotBlank())
        require(draftId.isNotBlank())
        require(audioGrantKey.isNotBlank())
        require(videoGrantKey.isNotBlank())
        require(submitKey.isNotBlank())
        require(attemptCount >= 0)
        require(nextRetryAt == null || nextRetryAt >= 0)
        require(lastSafeError == null || lastSafeError.length <= 256)
    }
}
