package com.vocaease.patient.feature.profile

import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LogoutChoice { RETAIN, DELETE }

data class LogoutOperationOwner(
    val accountScopeHash: String,
    val incarnationProof: String,
    val sessionEpoch: Long,
    val operationId: String,
) {
    init {
        require(accountScopeHash.matches(SHA256))
        require(incarnationProof.matches(SHA256))
        require(sessionEpoch >= 0)
        require(operationId.matches(OPAQUE_ID))
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val OPAQUE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

data class LogoutIntent(
    val owner: LogoutOperationOwner,
    val choice: LogoutChoice,
)

sealed interface LogoutRemoteResult {
    data object Revoked : LogoutRemoteResult
    data object Offline : LogoutRemoteResult
}

sealed interface LogoutOutcome {
    data class NeedsDraftDecision(val pendingDraftCount: Int) : LogoutOutcome
    data object LoggedOut : LogoutOutcome
    data object Superseded : LogoutOutcome
    data class Failed(val message: String) : LogoutOutcome
}

interface LogoutAccountBoundary {
    suspend fun <T> withLogoutClaim(owner: LogoutOperationOwner, operation: suspend () -> T): T = operation()
    suspend fun acquireOwner(): LogoutOperationOwner?
    suspend fun pendingDraftCount(owner: LogoutOperationOwner): Int
    suspend fun persistIntent(owner: LogoutOperationOwner, choice: LogoutChoice): LogoutIntent
    suspend fun pauseAndLock(intent: LogoutIntent)
    suspend fun cancelAndAwait(intent: LogoutIntent)
    suspend fun revokeRuntimeAccess(intent: LogoutIntent)
    suspend fun deleteAccountData(intent: LogoutIntent)
    suspend fun completeIntent(intent: LogoutIntent)
    suspend fun finishLogout(owner: LogoutOperationOwner): Boolean
}

class LogoutCoordinator(
    private val account: LogoutAccountBoundary,
) {
    private val operationMutex = Mutex()
    private var pendingDecisionOwner: LogoutOperationOwner? = null

    suspend fun beginLogout(): LogoutOutcome = operationMutex.withLock {
        val owner = account.acquireOwner() ?: return@withLock LogoutOutcome.Superseded
        val pending = account.pendingDraftCount(owner).coerceAtLeast(0)
        if (pending > 0) {
            pendingDecisionOwner = owner
            LogoutOutcome.NeedsDraftDecision(pending)
        } else {
            pendingDecisionOwner = null
            execute(owner, LogoutChoice.RETAIN)
        }
    }

    suspend fun confirmLogout(choice: LogoutChoice): LogoutOutcome = operationMutex.withLock {
        val pendingOwner = pendingDecisionOwner
            ?: return@withLock LogoutOutcome.Superseded
        pendingDecisionOwner = null
        val currentOwner = account.acquireOwner()
            ?: return@withLock LogoutOutcome.Superseded
        if (!pendingOwner.sameSessionAs(currentOwner)) {
            return@withLock LogoutOutcome.Superseded
        }
        execute(pendingOwner, choice)
    }

    private fun LogoutOperationOwner.sameSessionAs(other: LogoutOperationOwner): Boolean =
        accountScopeHash == other.accountScopeHash &&
            incarnationProof == other.incarnationProof &&
            sessionEpoch == other.sessionEpoch

    private suspend fun execute(owner: LogoutOperationOwner, choice: LogoutChoice): LogoutOutcome = try {
        account.withLogoutClaim(owner) {
        val intent = account.persistIntent(owner, choice)
        account.pauseAndLock(intent)
        account.cancelAndAwait(intent)
        account.revokeRuntimeAccess(intent)
        if (choice == LogoutChoice.DELETE) account.deleteAccountData(intent)
        account.completeIntent(intent)
        val committed = account.finishLogout(owner)
        if (!committed) {
            LogoutOutcome.Superseded
        } else {
            LogoutOutcome.LoggedOut
        }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LogoutOutcome.Failed("退出未完成，请重试")
    }
}
