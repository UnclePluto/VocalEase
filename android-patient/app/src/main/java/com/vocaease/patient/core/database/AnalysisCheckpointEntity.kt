package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "analysis_checkpoints",
    primaryKeys = ["account_scope_hash", "session_id"],
    indices = [Index(value = ["account_scope_hash", "next_deadline_at"])],
)
data class AnalysisCheckpointEntity(
    @ColumnInfo(name = "account_scope_hash") val accountScopeHash: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "incarnation_proof") val incarnationProof: String,
    val status: String,
    @ColumnInfo(name = "analysis_generation") val analysisGeneration: Int,
    @ColumnInfo(name = "poll_step") val pollStep: Int,
    @ColumnInfo(name = "next_deadline_at") val nextDeadlineAt: Long,
    @ColumnInfo(name = "operation_version") val operationVersion: Long,
) {
    init {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")))
        require(incarnationProof.matches(Regex("[0-9a-f]{64}")))
        require(status in setOf("UPLOADED", "PROCESSING", "RETRYING", "COMPLETED", "FAILED", "CANCELLED"))
        require(analysisGeneration >= 0 && pollStep in 0..3 && nextDeadlineAt >= 0 && operationVersion >= 0)
    }
}

@Dao
internal interface AnalysisCheckpointDao {
    @Query("SELECT * FROM analysis_checkpoints WHERE account_scope_hash=:accountScopeHash AND session_id=:sessionId")
    suspend fun find(accountScopeHash: String, sessionId: String): AnalysisCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AnalysisCheckpointEntity): Long

    @Query(
        "UPDATE analysis_checkpoints SET status=:status,analysis_generation=:analysisGeneration," +
            "poll_step=:pollStep,next_deadline_at=:nextDeadlineAt,operation_version=:nextVersion " +
            "WHERE account_scope_hash=:accountScopeHash AND session_id=:sessionId " +
            "AND incarnation_proof=:incarnationProof AND operation_version=:expectedVersion",
    )
    suspend fun checkpoint(
        accountScopeHash: String,
        sessionId: String,
        incarnationProof: String,
        status: String,
        analysisGeneration: Int,
        pollStep: Int,
        nextDeadlineAt: Long,
        nextVersion: Long,
        expectedVersion: Long,
    ): Int

    @Query(
        "UPDATE analysis_checkpoints SET incarnation_proof=:newIncarnationProof," +
            "operation_version=:nextVersion WHERE account_scope_hash=:accountScopeHash AND session_id=:sessionId " +
            "AND incarnation_proof=:expectedIncarnationProof AND operation_version=:expectedVersion",
    )
    suspend fun takeover(
        accountScopeHash: String,
        sessionId: String,
        expectedIncarnationProof: String,
        newIncarnationProof: String,
        expectedVersion: Long,
        nextVersion: Long,
    ): Int

    @Query("DELETE FROM analysis_checkpoints WHERE account_scope_hash=:accountScopeHash")
    suspend fun deleteAll(accountScopeHash: String): Int
}
