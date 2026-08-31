package com.vocaease.patient.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: UploadJobEntity)

    @Query("SELECT * FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun find(accountScope: String, draftId: String): UploadJobEntity?

    @Query("SELECT * FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    fun observe(accountScope: String, draftId: String): Flow<UploadJobEntity?>

    @Query("SELECT * FROM upload_jobs WHERE account_scope = :accountScope ORDER BY draft_id")
    fun observeAll(accountScope: String): Flow<List<UploadJobEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM upload_jobs
        WHERE account_scope = :accountScope
          AND overall_state NOT IN ('COMPLETED', 'CANCELLED')
        """,
    )
    suspend fun countPending(accountScope: String): Int

    @Query(
        """
        SELECT * FROM upload_jobs
        WHERE account_scope = :accountScope
          AND next_retry_at IS NOT NULL
          AND next_retry_at <= :nowEpochMilliseconds
        ORDER BY next_retry_at, draft_id
        """,
    )
    suspend fun findReadyToRetry(accountScope: String, nowEpochMilliseconds: Long): List<UploadJobEntity>

    @Query(
        """
        UPDATE upload_jobs SET
            overall_state = :overallState,
            pipeline_stage = :pipelineStage,
            audio_grant_state = :audioGrantState, video_grant_state = :videoGrantState,
            audio_upload_state = :audioUploadState, video_upload_state = :videoUploadState,
            audio_receipt_state = :audioReceiptState, video_receipt_state = :videoReceiptState,
            audio_confirm_state = :audioConfirmState, video_confirm_state = :videoConfirmState,
            submit_state = :submitState,
            audio_grant_key = :audioGrantKey, video_grant_key = :videoGrantKey, submit_key = :submitKey,
            audio_asset_key = :audioAssetKey, video_asset_key = :videoAssetKey,
            audio_object_key = :audioObjectKey, video_object_key = :videoObjectKey,
            audio_receipt = :audioReceipt, video_receipt = :videoReceipt,
            audio_confirmed_at = :audioConfirmedAt, video_confirmed_at = :videoConfirmedAt,
            attempt_count = :attemptCount, next_retry_at = :nextRetryAt, last_safe_error = :lastSafeError,
            progress_percent = :progressPercent, receipt_wait_attempt = :receiptWaitAttempt
            , resume_pipeline_stage = :resumePipelineStage
            , operation_version = operation_version + 1
        WHERE account_scope = :accountScope AND draft_id = :draftId
          AND operation_version = :expectedOperationVersion
          AND overall_state = :expectedOverallState
          AND pipeline_stage = :expectedPipelineStage
          AND audio_grant_state = :expectedAudioGrantState AND video_grant_state = :expectedVideoGrantState
          AND audio_upload_state = :expectedAudioUploadState AND video_upload_state = :expectedVideoUploadState
          AND audio_receipt_state = :expectedAudioReceiptState AND video_receipt_state = :expectedVideoReceiptState
          AND audio_confirm_state = :expectedAudioConfirmState AND video_confirm_state = :expectedVideoConfirmState
          AND submit_state = :expectedSubmitState
          AND resume_pipeline_stage IS :expectedResumePipelineStage
        """,
    )
    suspend fun checkpoint(
        accountScope: String,
        draftId: String,
        overallState: UploadOverallState,
        pipelineStage: UploadPipelineStage,
        audioGrantState: UploadStepState,
        videoGrantState: UploadStepState,
        audioUploadState: UploadStepState,
        videoUploadState: UploadStepState,
        audioReceiptState: UploadStepState,
        videoReceiptState: UploadStepState,
        audioConfirmState: UploadStepState,
        videoConfirmState: UploadStepState,
        submitState: UploadStepState,
        audioGrantKey: String,
        videoGrantKey: String,
        submitKey: String,
        audioAssetKey: String?,
        videoAssetKey: String?,
        audioObjectKey: String?,
        videoObjectKey: String?,
        audioReceipt: String?,
        videoReceipt: String?,
        audioConfirmedAt: Long?,
        videoConfirmedAt: Long?,
        attemptCount: Int,
        nextRetryAt: Long?,
        lastSafeError: String?,
        progressPercent: Int,
        receiptWaitAttempt: Int,
        resumePipelineStage: UploadPipelineStage? = null,
        expectedOperationVersion: Long,
        expectedOverallState: UploadOverallState,
        expectedPipelineStage: UploadPipelineStage,
        expectedAudioGrantState: UploadStepState,
        expectedVideoGrantState: UploadStepState,
        expectedAudioUploadState: UploadStepState,
        expectedVideoUploadState: UploadStepState,
        expectedAudioReceiptState: UploadStepState,
        expectedVideoReceiptState: UploadStepState,
        expectedAudioConfirmState: UploadStepState,
        expectedVideoConfirmState: UploadStepState,
        expectedSubmitState: UploadStepState,
        expectedResumePipelineStage: UploadPipelineStage?,
    ): Int

    @Query(
        "UPDATE upload_jobs SET operation_version = operation_version + 1 " +
            "WHERE account_scope = :accountScope AND draft_id = :draftId AND operation_version = :expectedOperationVersion",
    )
    suspend fun bumpOperationVersion(accountScope: String, draftId: String, expectedOperationVersion: Long): Int

    @Query("DELETE FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun delete(accountScope: String, draftId: String): Int
}
