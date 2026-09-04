package com.vocaease.patient.feature.profile

import com.vocaease.patient.core.database.AccountExitChoice
import com.vocaease.patient.core.database.AccountExitIntentEntity
import com.vocaease.patient.core.database.AccountExitStage
import com.vocaease.patient.core.database.AccountOperationKind
import com.vocaease.patient.core.database.VocaEaseDatabase

internal interface AccountExitEffects {
    suspend fun pauseAndLock(intent: LogoutIntent)
    suspend fun cancelAndAwait(intent: LogoutIntent)
    suspend fun revokeRuntimeAccess(intent: LogoutIntent)
    suspend fun deleteAccountData(intent: LogoutIntent)
}

internal class RoomAccountExitIntentStore(
    private val database: VocaEaseDatabase,
    private val accountScope: String,
) {
    init {
        require(accountScope.matches(CANONICAL_UUID))
    }

    suspend fun persist(
        owner: LogoutOperationOwner,
        choice: LogoutChoice,
        operationKind: AccountOperationKind = AccountOperationKind.LOGOUT,
    ): AccountExitIntentEntity {
        val existing = database.accountExitIntentDao().find(accountScope)
        if (existing != null) {
            require(
                existing.matches(owner) && existing.choice.name == choice.name && existing.operationKind == operationKind,
            ) { "账户操作冲突" }
            return existing
        }
        val inserted = AccountExitIntentEntity(
            accountScope = accountScope,
            accountScopeHash = owner.accountScopeHash,
            incarnationProof = owner.incarnationProof,
            sessionEpoch = owner.sessionEpoch,
            operationId = owner.operationId,
            operationKind = operationKind,
            choice = AccountExitChoice.valueOf(choice.name),
            stage = AccountExitStage.INTENT_WRITTEN,
            operationVersion = 0,
        )
        database.accountExitIntentDao().insert(inserted)
        return inserted
    }

    suspend fun find(): AccountExitIntentEntity? = database.accountExitIntentDao().find(accountScope)

    suspend fun takeover(
        current: AccountExitIntentEntity,
        owner: LogoutOperationOwner,
    ): AccountExitIntentEntity {
        require(current.accountScopeHash == owner.accountScopeHash)
        if (current.incarnationProof == owner.incarnationProof && current.sessionEpoch == owner.sessionEpoch) return current
        check(
            database.accountExitIntentDao().takeover(
                accountScope = accountScope,
                accountScopeHash = owner.accountScopeHash,
                expectedIncarnationProof = current.incarnationProof,
                newIncarnationProof = owner.incarnationProof,
                newSessionEpoch = owner.sessionEpoch,
                expectedVersion = current.operationVersion,
            ) == 1,
        ) { "账户退出恢复所有权已变化" }
        return requireNotNull(find())
    }

    suspend fun advance(
        current: AccountExitIntentEntity,
        toStage: AccountExitStage,
    ): AccountExitIntentEntity {
        check(
            database.accountExitIntentDao().advance(
                accountScope = accountScope,
                operationId = current.operationId,
                incarnationProof = current.incarnationProof,
                fromStage = current.stage,
                toStage = toStage,
                expectedVersion = current.operationVersion,
            ) == 1,
        ) { "账户退出检查点已变化" }
        return requireNotNull(find())
    }

    suspend fun deleteReady(current: AccountExitIntentEntity): Int {
        require(current.stage == AccountExitStage.READY_TO_CLEAR)
        return database.accountExitIntentDao().deleteReady(
            current.accountScope,
            current.operationId,
            current.incarnationProof,
        )
    }

    suspend fun rollbackPasswordChange(current: AccountExitIntentEntity): Int {
        require(current.operationKind == AccountOperationKind.PASSWORD_CHANGE)
        require(current.stage == AccountExitStage.READY_TO_CLEAR)
        return database.accountExitIntentDao().deleteReadyPasswordChange(
            current.accountScope,
            current.operationId,
            current.incarnationProof,
        )
    }

    suspend fun deleteCompletedLogout(current: AccountExitIntentEntity): Int {
        require(current.operationKind == AccountOperationKind.LOGOUT)
        require(current.stage == AccountExitStage.AUTH_CLEARED)
        return database.accountExitIntentDao().deleteCompletedLogout(current.accountScope, current.operationId)
    }

    suspend fun deletePreAuthenticationLogout(current: AccountExitIntentEntity): Int {
        require(current.operationKind == AccountOperationKind.LOGOUT)
        require(current.stage == AccountExitStage.READY_TO_CLEAR)
        return database.accountExitIntentDao().deletePreAuthenticationLogout(current.accountScope, current.operationId)
    }

    suspend fun deleteLegacyLocalUnlock(current: AccountExitIntentEntity): Int {
        require(current.operationKind == AccountOperationKind.LEGACY_LOCAL_UNLOCK)
        return database.accountExitIntentDao().deleteLegacyLocalUnlock(current.accountScope, current.operationId)
    }

    private fun AccountExitIntentEntity.matches(owner: LogoutOperationOwner): Boolean =
        accountScopeHash == owner.accountScopeHash &&
            incarnationProof == owner.incarnationProof &&
            sessionEpoch == owner.sessionEpoch &&
            operationId == owner.operationId

    private companion object {
        val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

internal class AccountExitProcessor(
    private val store: RoomAccountExitIntentStore,
    private val effects: AccountExitEffects,
    private val beforeCheckpoint: (AccountExitStage) -> Unit = {},
) {
    suspend fun converge(seed: AccountExitIntentEntity): AccountExitIntentEntity {
        var current = store.find() ?: error("账户退出意图不存在")
        require(current.operationId == seed.operationId && current.incarnationProof == seed.incarnationProof)
        val intent = current.toLogoutIntent()
        while (current.stage != AccountExitStage.READY_TO_CLEAR) {
            current = when (current.stage) {
                AccountExitStage.INTENT_WRITTEN -> {
                    effects.pauseAndLock(intent)
                    checkpoint(current, AccountExitStage.PAUSED_LOCKED)
                }
                AccountExitStage.PAUSED_LOCKED -> {
                    effects.cancelAndAwait(intent)
                    checkpoint(current, AccountExitStage.WORK_CANCELLED)
                }
                AccountExitStage.WORK_CANCELLED -> {
                    effects.revokeRuntimeAccess(intent)
                    checkpoint(current, AccountExitStage.RUNTIME_REVOKED)
                }
                AccountExitStage.RUNTIME_REVOKED -> {
                    if (current.choice == AccountExitChoice.DELETE) effects.deleteAccountData(intent)
                    checkpoint(
                        current,
                        if (current.choice == AccountExitChoice.DELETE) {
                            AccountExitStage.DATA_DELETED
                        } else {
                            AccountExitStage.READY_TO_CLEAR
                        },
                    )
                }
                AccountExitStage.DATA_DELETED -> checkpoint(current, AccountExitStage.READY_TO_CLEAR)
                AccountExitStage.READY_TO_CLEAR -> current
                AccountExitStage.AUTH_BOUND,
                AccountExitStage.REMOTE_REVOKED,
                AccountExitStage.AUTH_CLEARED,
                -> return current
            }
        }
        return current
    }

    private suspend fun checkpoint(
        current: AccountExitIntentEntity,
        next: AccountExitStage,
    ): AccountExitIntentEntity {
        beforeCheckpoint(current.stage)
        return store.advance(current, next)
    }

    private fun AccountExitIntentEntity.toLogoutIntent(): LogoutIntent = LogoutIntent(
        owner = LogoutOperationOwner(accountScopeHash, incarnationProof, sessionEpoch, operationId),
        choice = LogoutChoice.valueOf(choice.name),
    )
}
