package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class MediaType {
    AUDIO,
    VIDEO,
}

enum class MediaValidationState {
    PENDING,
    VALID,
    INVALID,
}

@Entity(
    tableName = "media",
    primaryKeys = ["account_scope", "draft_id", "type"],
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
        Index(value = ["account_scope", "encrypted_relative_path"], unique = true),
    ],
)
data class MediaEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    val type: MediaType,
    @ColumnInfo(name = "encrypted_relative_path") val encryptedRelativePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "validation_state") val validationState: MediaValidationState,
) {
    init {
        require(accountScope.isNotBlank())
        require(draftId.isNotBlank())
        require(isEncryptedMediaRelativePath(encryptedRelativePath))
        require(mimeType.isNotBlank())
        require(mimeType.length <= 128)
        require(sizeBytes >= 0)
        require(SHA_256.matches(sha256))
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

internal fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
    val segments = path.replace('\\', '/').split('/')
    return segments.none { it.isBlank() || it == "." || it == ".." }
}

private val ENCRYPTED_MEDIA_PATH = Regex("media/v1/[0-9a-f]{32}\\.vef")
internal fun isEncryptedMediaRelativePath(path: String): Boolean = isSafeRelativePath(path) && ENCRYPTED_MEDIA_PATH.matches(path)
