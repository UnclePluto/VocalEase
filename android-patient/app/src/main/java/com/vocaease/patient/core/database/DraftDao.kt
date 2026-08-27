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

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope AND draft_id = :draftId")
    suspend fun delete(accountScope: String, draftId: String): Int

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope AND expires_at <= :nowEpochMilliseconds")
    suspend fun deleteExpired(accountScope: String, nowEpochMilliseconds: Long): Int

    @Query("SELECT COUNT(*) FROM drafts WHERE account_scope = :accountScope")
    suspend fun count(accountScope: String): Int

    @Query("DELETE FROM drafts WHERE account_scope = :accountScope")
    suspend fun deleteAll(accountScope: String): Int
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

    @Query("DELETE FROM media WHERE account_scope = :accountScope AND draft_id = :draftId AND type = :type")
    suspend fun delete(accountScope: String, draftId: String, type: MediaType): Int
}
