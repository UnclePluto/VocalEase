package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "preparation_drafts",
    primaryKeys = ["account_scope", "draft_id"],
    indices = [
        Index(value = ["account_scope", "creation_key"], unique = true),
        Index(value = ["account_scope", "server_session_id"], unique = true),
        Index(value = ["account_scope", "expires_at"]),
    ],
)
data class PreparationDraftEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "song_title") val songTitle: String,
    @ColumnInfo(name = "song_artist") val songArtist: String,
    @ColumnInfo(name = "song_duration_seconds") val songDurationSeconds: Int,
    @ColumnInfo(name = "server_session_id") val serverSessionId: String?,
    @ColumnInfo(name = "creation_key") val creationKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
) {
    init {
        require(listOf(accountScope, draftId, songId, songTitle, songArtist, creationKey).none(String::isBlank))
        require(listOf(accountScope, draftId, songId, creationKey).all { it.length <= 128 })
        require(songTitle.length <= 256 && songArtist.length <= 256)
        require(songDurationSeconds > 0)
        require(serverSessionId == null || serverSessionId.isNotBlank() && serverSessionId.length <= 128)
        require(createdAt >= 0 && expiresAt >= createdAt)
        require(expiresAt - createdAt <= DraftEntity.MAX_RETENTION_MILLIS)
    }
}

@Dao
internal interface PreparationDraftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: PreparationDraftEntity)

    @Query(
        "SELECT * FROM preparation_drafts WHERE account_scope=:accountScope AND draft_id=:draftId",
    )
    suspend fun find(accountScope: String, draftId: String): PreparationDraftEntity?

    @Query(
        """
        UPDATE preparation_drafts
        SET server_session_id=:serverSessionId,
            song_title=:songTitle,
            song_artist=:songArtist,
            song_duration_seconds=:songDurationSeconds
        WHERE account_scope=:accountScope AND draft_id=:draftId AND server_session_id IS NULL
        """,
    )
    suspend fun bind(
        accountScope: String,
        draftId: String,
        serverSessionId: String,
        songTitle: String,
        songArtist: String,
        songDurationSeconds: Int,
    ): Int

    @Query("DELETE FROM preparation_drafts WHERE account_scope=:accountScope AND draft_id=:draftId")
    suspend fun delete(accountScope: String, draftId: String): Int
}
