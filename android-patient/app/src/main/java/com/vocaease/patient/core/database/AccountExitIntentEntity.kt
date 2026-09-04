package com.vocaease.patient.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

enum class AccountExitChoice { RETAIN, DELETE }

enum class AccountExitStage {
    INTENT_WRITTEN,
    PAUSED_LOCKED,
    WORK_CANCELLED,
    RUNTIME_REVOKED,
    DATA_DELETED,
    READY_TO_CLEAR,
}

@Entity(
    tableName = "account_exit_intents",
    primaryKeys = ["account_scope"],
)
data class AccountExitIntentEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "account_scope_hash") val accountScopeHash: String,
    @ColumnInfo(name = "incarnation_proof") val incarnationProof: String,
    @ColumnInfo(name = "session_epoch") val sessionEpoch: Long,
    @ColumnInfo(name = "operation_id") val operationId: String,
    val choice: AccountExitChoice,
    val stage: AccountExitStage,
    @ColumnInfo(name = "operation_version") val operationVersion: Long,
) {
    init {
        require(accountScope.matches(CANONICAL_UUID))
        require(accountScopeHash.matches(SHA256) && incarnationProof.matches(SHA256))
        require(sessionEpoch >= 0 && operationVersion >= 0)
        require(operationId.matches(OPAQUE_ID))
    }

    private companion object {
        val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val OPAQUE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

@Dao
internal interface AccountExitIntentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(intent: AccountExitIntentEntity)

    @Query("SELECT * FROM account_exit_intents WHERE account_scope=:accountScope")
    suspend fun find(accountScope: String): AccountExitIntentEntity?

    @Query(
        "UPDATE account_exit_intents SET stage=:toStage,operation_version=operation_version+1 " +
            "WHERE account_scope=:accountScope AND operation_id=:operationId " +
            "AND incarnation_proof=:incarnationProof AND stage=:fromStage AND operation_version=:expectedVersion",
    )
    suspend fun advance(
        accountScope: String,
        operationId: String,
        incarnationProof: String,
        fromStage: AccountExitStage,
        toStage: AccountExitStage,
        expectedVersion: Long,
    ): Int

    @Query(
        "UPDATE account_exit_intents SET incarnation_proof=:newIncarnationProof," +
            "session_epoch=:newSessionEpoch,operation_version=operation_version+1 " +
            "WHERE account_scope=:accountScope AND account_scope_hash=:accountScopeHash " +
            "AND incarnation_proof=:expectedIncarnationProof AND operation_version=:expectedVersion",
    )
    suspend fun takeover(
        accountScope: String,
        accountScopeHash: String,
        expectedIncarnationProof: String,
        newIncarnationProof: String,
        newSessionEpoch: Long,
        expectedVersion: Long,
    ): Int

    @Query(
        "DELETE FROM account_exit_intents WHERE account_scope=:accountScope AND operation_id=:operationId " +
            "AND incarnation_proof=:incarnationProof AND stage='READY_TO_CLEAR'",
    )
    suspend fun deleteReady(accountScope: String, operationId: String, incarnationProof: String): Int
}
