package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

enum class DraftState {
    RECORDING,
    REVIEW_READY,
    READY_TO_UPLOAD,
    UPLOADING,
    SUBMITTED,
    FAILED,
}

@Entity(
    tableName = "drafts",
    primaryKeys = ["account_scope", "draft_id"],
    indices = [
        Index(value = ["account_scope", "creation_key"], unique = true),
        Index(value = ["account_scope", "session_id"], unique = true),
        Index(value = ["account_scope", "expires_at"]),
    ],
)
data class DraftEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "creation_key") val creationKey: String,
    val state: DraftState,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "interruption_reason") val interruptionReason: String?,
) {
    init {
        require(accountScope.isNotBlank())
        require(draftId.isNotBlank())
        require(songId.isNotBlank())
        require(sessionId.isNotBlank())
        require(creationKey.isNotBlank())
        require(listOf(accountScope, draftId, songId, sessionId, creationKey).all { it.length <= 128 })
        require(durationMs >= 0)
        require(createdAt >= 0)
        require(expiresAt >= createdAt)
        require(expiresAt - createdAt <= MAX_RETENTION_MILLIS)
        require(interruptionReason == null || interruptionReason.length <= 256)
    }

    companion object {
        const val MAX_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
