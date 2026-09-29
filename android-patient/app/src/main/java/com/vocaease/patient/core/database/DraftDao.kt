package com.vocaease.patient.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun find(accountScope: String, draftId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE account_scope = :accountScope AND draft_id = :draftId")
    fun observe(accountScope: String, draftId: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE account_scope = :accountScope ORDER BY created_at DESC")
    fun observeAll(accountScope: String): Flow<List<DraftEntity>>

    @Query(
        """
        UPDATE drafts
        SET state = :state,
            duration_ms = :durationMs,
            interruption_reason = :interruptionReason
        WHERE account_scope = :accountScope AND draft_id = :draftId
        """,
    )
    suspend fun updateState(
        accountScope: String,
        draftId: String,
        state: DraftState,
        durationMs: Long,
        interruptionReason: String?,
    ): Int

    @Query(
        """
        UPDATE drafts
        SET state = :toState, duration_ms = :durationMs, interruption_reason = :interruptionReason
        WHERE account_scope = :accountScope AND draft_id = :draftId AND state = :fromState
        """,
    )
    suspend fun transitionState(
        accountScope: String,
        draftId: String,
        fromState: DraftState,
        toState: DraftState,
        durationMs: Long,
        interruptionReason: String?,
    ): Int

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun delete(accountScope: String, draftId: String): Int

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope AND expires_at <= :nowEpochMilliseconds")
    suspend fun deleteExpired(accountScope: String, nowEpochMilliseconds: Long): Int

    @Query(
        """
        SELECT * FROM drafts AS draft
        WHERE draft.account_scope = :accountScope
          AND draft.expires_at <= :nowEpochMilliseconds
          AND draft.state IN ('RECORDING', 'REVIEW_READY', 'INTERRUPTED')
          AND NOT EXISTS (
              SELECT 1 FROM upload_jobs AS job
              WHERE job.account_scope = draft.account_scope AND job.draft_id = draft.draft_id
          )
        ORDER BY draft.expires_at, draft.draft_id
        """,
    )
    suspend fun findExpiredLocal(
        accountScope: String,
        nowEpochMilliseconds: Long,
    ): List<DraftEntity>

    @Query("SELECT COUNT(*) FROM drafts WHERE account_scope = :accountScope")
    suspend fun count(accountScope: String): Int

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope")
    suspend fun deleteAll(accountScope: String): Int

    @Query("SELECT draft_id FROM drafts WHERE account_scope = :accountScope ORDER BY draft_id")
    suspend fun findAllIds(accountScope: String): List<String>
}

@Dao
internal interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(media: MediaEntity)

    @Query(
        """
        SELECT * FROM media
        WHERE account_scope = :accountScope AND draft_id = :draftId AND type = :type
        """,
    )
    suspend fun find(accountScope: String, draftId: String, type: MediaType): MediaEntity?

    @Query("SELECT * FROM media WHERE account_scope = :accountScope AND draft_id = :draftId ORDER BY type")
    suspend fun findAll(accountScope: String, draftId: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE account_scope = :accountScope AND draft_id = :draftId ORDER BY type")
    fun observeForDraft(accountScope: String, draftId: String): Flow<List<MediaEntity>>

    @Query(
        """
        UPDATE media SET validation_state = :validationState
        WHERE account_scope = :accountScope AND draft_id = :draftId AND type = :type
        """,
    )
    suspend fun updateValidation(
        accountScope: String,
        draftId: String,
        type: MediaType,
        validationState: MediaValidationState,
    ): Int

    @Query(
        "UPDATE media SET validation_state = :validationState " +
            "WHERE account_scope = :accountScope AND draft_id = :draftId",
    )
    suspend fun updateAllValidation(
        accountScope: String,
        draftId: String,
        validationState: MediaValidationState,
    ): Int

    @Query("DELETE FROM media WHERE account_scope = :accountScope AND draft_id = :draftId AND type = :type")
    suspend fun delete(accountScope: String, draftId: String, type: MediaType): Int

    @Query("DELETE FROM media WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun deleteAll(accountScope: String, draftId: String): Int

    @Query("DELETE FROM media WHERE account_scope = :accountScope")
    suspend fun deleteAllForAccount(accountScope: String): Int
}
