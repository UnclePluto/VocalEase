package com.vocaease.patient.feature.profile

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutCoordinatorTest {
    @Test
    fun `无草稿时先持久化保留意图再收敛运行时与认证态`() = runBlocking {
        val account = InMemoryLogoutAccount(pendingDrafts = 0)
        val coordinator = LogoutCoordinator(account)

        val outcome = coordinator.beginLogout()

        assertEquals(LogoutOutcome.LoggedOut, outcome)
        assertTrue(account.intentPersistedBeforeSideEffects)
        assertTrue(account.workCancelledAndAwaited)
        assertTrue(account.runtimeAccessRevoked)
        assertTrue(account.serverLogoutCalled)
        assertFalse(account.authenticated)
    }

    @Test
    fun `删除意图最终清理失败时不调用远端且保留认证供重试`() = runBlocking {
        val account = InMemoryLogoutAccount(pendingDrafts = 1, failIntentCompletion = true)
        val coordinator = LogoutCoordinator(account)

        assertEquals(LogoutOutcome.NeedsDraftDecision(1), coordinator.beginLogout())
        val outcome = coordinator.confirmLogout(LogoutChoice.DELETE)

        assertEquals(LogoutOutcome.Failed("退出未完成，请重试"), outcome)
        assertTrue(account.accountDataDeleted)
        assertFalse(account.serverLogoutCalled)
        assertTrue(account.authenticated)
    }

    @Test
    fun `A账户弹出草稿选择后切到B时旧选择不得作用于B`() = runBlocking {
        val account = InMemoryLogoutAccount(pendingDrafts = 2)
        val coordinator = LogoutCoordinator(account)

        assertEquals(LogoutOutcome.NeedsDraftDecision(2), coordinator.beginLogout())
        account.switchAccount()

        assertEquals(LogoutOutcome.Superseded, coordinator.confirmLogout(LogoutChoice.DELETE))
        assertFalse(account.intentPersistedBeforeSideEffects)
        assertFalse(account.serverLogoutCalled)
        assertTrue(account.authenticated)
    }

    @Test
    fun `离线退出只在本地收敛完成后请求转移刷新令牌`() = runBlocking {
        val account = InMemoryLogoutAccount(
            pendingDrafts = 0,
            remoteResult = LogoutRemoteResult.Offline,
        )

        assertEquals(LogoutOutcome.LoggedOut, LogoutCoordinator(account).beginLogout())
        assertEquals(true, account.moveRefreshToRevocationOnly)
        assertFalse(account.authenticated)
    }

    @Test
    fun `等待工作取消被取消时传播取消且不触碰远端和认证`() {
        val account = InMemoryLogoutAccount(pendingDrafts = 0, cancelDuringAwait = true)

        org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking { LogoutCoordinator(account).beginLogout() }
        }
        assertFalse(account.serverLogoutCalled)
        assertTrue(account.authenticated)
    }
}

private class InMemoryLogoutAccount(
    private val pendingDrafts: Int,
    private val failIntentCompletion: Boolean = false,
    private val remoteResult: LogoutRemoteResult = LogoutRemoteResult.Revoked,
    private val cancelDuringAwait: Boolean = false,
) : LogoutAccountBoundary {
    private var owner = LogoutOperationOwner(
        accountScopeHash = "a".repeat(64),
        incarnationProof = "b".repeat(64),
        sessionEpoch = 7,
        operationId = "logout-operation",
    )
    private var intent: LogoutIntent? = null
    var authenticated = true
        private set
    var intentPersistedBeforeSideEffects = false
        private set
    var workCancelledAndAwaited = false
        private set
    var runtimeAccessRevoked = false
        private set
    var serverLogoutCalled = false
        private set
    var accountDataDeleted = false
        private set
    var moveRefreshToRevocationOnly: Boolean? = null
        private set

    fun switchAccount() {
        owner = LogoutOperationOwner(
            accountScopeHash = "c".repeat(64),
            incarnationProof = "d".repeat(64),
            sessionEpoch = owner.sessionEpoch + 1,
            operationId = "logout-operation-b",
        )
    }

    override suspend fun acquireOwner(): LogoutOperationOwner? = owner.takeIf { authenticated }

    override suspend fun pendingDraftCount(owner: LogoutOperationOwner): Int = pendingDrafts

    override suspend fun persistIntent(owner: LogoutOperationOwner, choice: LogoutChoice): LogoutIntent {
        return LogoutIntent(owner, choice).also {
            intent = it
            intentPersistedBeforeSideEffects = true
        }
    }

    override suspend fun pauseAndLock(intent: LogoutIntent) {
        check(this.intent == intent)
    }

    override suspend fun cancelAndAwait(intent: LogoutIntent) {
        check(this.intent == intent)
        if (cancelDuringAwait) throw CancellationException("cancelled")
        workCancelledAndAwaited = true
    }

    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) {
        check(workCancelledAndAwaited)
        runtimeAccessRevoked = true
    }

    override suspend fun deleteAccountData(intent: LogoutIntent) {
        check(this.intent == intent && intent.choice == LogoutChoice.DELETE)
        accountDataDeleted = true
    }

    override suspend fun finishLogout(owner: LogoutOperationOwner): Boolean {
        check(intentPersistedBeforeSideEffects && runtimeAccessRevoked)
        serverLogoutCalled = true
        this.moveRefreshToRevocationOnly = remoteResult == LogoutRemoteResult.Offline
        authenticated = false
        return true
    }

    override suspend fun completeIntent(intent: LogoutIntent) {
        if (failIntentCompletion) error("intent cleanup failed")
    }
}
