package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

enum class UploadLocalActionType { CLEANUP_SUBMITTED, DELETE_QUEUED }

enum class UploadLocalActionStage {
    INTENT_WRITTEN,
    MEDIA_INVALIDATED,
    AUDIO_DELETED,
    VIDEO_DELETED,
    MEDIA_ROWS_DELETED,
    PREPARATION_DELETED,
    DRAFT_FINALIZED,
}

@Entity(
    tableName = "upload_local_actions",
    primaryKeys = ["account_scope", "draft_id"],
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["account_scope", "draft_id"],
            childColumns = ["account_scope", "draft_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["account_scope", "draft_id"])],
)
data class UploadLocalActionEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    val action: UploadLocalActionType,
    val stage: UploadLocalActionStage,
    @ColumnInfo(name = "audio_encrypted_relative_path") val audioEncryptedRelativePath: String,
    @ColumnInfo(name = "video_encrypted_relative_path") val videoEncryptedRelativePath: String,
)

@Dao
internal interface UploadLocalActionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: UploadLocalActionEntity)

    @Query("SELECT * FROM upload_local_actions WHERE account_scope=:accountScope AND draft_id=:draftId")
    suspend fun find(accountScope: String, draftId: String): UploadLocalActionEntity?

    @Query("SELECT * FROM upload_local_actions WHERE account_scope=:accountScope ORDER BY draft_id")
    suspend fun findAll(accountScope: String): List<UploadLocalActionEntity>

    @Query(
        "UPDATE upload_local_actions SET stage=:toStage " +
            "WHERE account_scope=:accountScope AND draft_id=:draftId AND stage=:fromStage",
    )
    suspend fun advance(
        accountScope: String,
        draftId: String,
        fromStage: UploadLocalActionStage,
        toStage: UploadLocalActionStage,
    ): Int

    @Query("DELETE FROM upload_local_actions WHERE account_scope=:accountScope AND draft_id=:draftId")
    suspend fun delete(accountScope: String, draftId: String): Int

    @Query("DELETE FROM upload_local_actions WHERE account_scope=:accountScope")
    suspend fun deleteAll(accountScope: String): Int
}
