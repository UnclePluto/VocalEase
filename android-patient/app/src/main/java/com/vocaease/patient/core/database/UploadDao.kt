package com.vocaease.patient.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: UploadJobEntity)

    @Query("SELECT * FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun find(accountScope: String, draftId: String): UploadJobEntity?

    @Query("SELECT * FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    fun observe(accountScope: String, draftId: String): Flow<UploadJobEntity?>

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
        UPDATE upload_jobs
        SET audio_grant_state = :audioGrantState,
            video_grant_state = :videoGrantState,
            audio_upload_state = :audioUploadState,
            video_upload_state = :videoUploadState,
            submit_state = :submitState,
            attempt_count = :attemptCount,
            next_retry_at = :nextRetryAt,
            last_safe_error = :lastSafeError
        WHERE account_scope = :accountScope AND draft_id = :draftId
        """,
    )
    suspend fun updateStepStates(
        accountScope: String,
        draftId: String,
        audioGrantState: UploadStepState,
        videoGrantState: UploadStepState,
        audioUploadState: UploadStepState,
        videoUploadState: UploadStepState,
        submitState: UploadStepState,
        attemptCount: Int,
        nextRetryAt: Long?,
        lastSafeError: String?,
    ): Int

    @Query("DELETE FROM upload_jobs WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun delete(accountScope: String, draftId: String): Int
}
