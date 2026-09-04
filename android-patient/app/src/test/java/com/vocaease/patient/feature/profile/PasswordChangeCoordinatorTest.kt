package com.vocaease.patient.feature.profile

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordChangeCoordinatorTest {
    @Test
    fun `校验失败不准备账户边界也不调用远端`() = runBlocking {
        val boundary = FakePasswordChangeBoundary()
        val coordinator = PasswordChangeCoordinator(boundary)

        assertEquals(PasswordChangeOutcome.Failed("两次输入的新密码不一致"), coordinator.change("old", "new", "other"))
        assertEquals(emptyList<String>(), boundary.calls)
    }

    @Test
    fun `先持久锁定取消工作撤销运行时访问再改密并完成意图`() = runBlocking {
        val boundary = FakePasswordChangeBoundary()

        assertEquals(PasswordChangeOutcome.Changed, PasswordChangeCoordinator(boundary).change("old", "new", "new"))
        assertEquals(listOf("prepare", "remote", "complete"), boundary.calls)
    }

    @Test
    fun `准备失败不发远端且远端失败保留意图和认证态供重试`() = runBlocking {
        val prepareFailure = FakePasswordChangeBoundary(prepare = null)
        assertEquals(
            PasswordChangeOutcome.Superseded,
            PasswordChangeCoordinator(prepareFailure).change("old", "new", "new"),
        )
        assertEquals(listOf("prepare"), prepareFailure.calls)

        val remoteFailure = FakePasswordChangeBoundary(remoteChanged = false)
        assertEquals(
            PasswordChangeOutcome.Failed("密码修改未完成，请重试"),
            PasswordChangeCoordinator(remoteFailure).change("old", "new", "new"),
        )
        assertEquals(listOf("prepare", "remote"), remoteFailure.calls)
    }

    @Test
    fun `取消改密原样传播且不提交意图`() {
        val boundary = FakePasswordChangeBoundary(cancelRemote = true)
        assertThrows(CancellationException::class.java) {
            runBlocking { PasswordChangeCoordinator(boundary).change("old", "new", "new") }
        }
        assertEquals(listOf("prepare", "remote"), boundary.calls)
    }
}

private class FakePasswordChangeBoundary(
    private val prepare: PreparedPasswordChange? = PreparedPasswordChange(
        LogoutIntent(
            LogoutOperationOwner("a".repeat(64), "b".repeat(64), 1, "password-change"),
            LogoutChoice.RETAIN,
        ),
        "11111111-1111-4111-8111-111111111111",
    ),
    private val remoteChanged: Boolean = true,
    private val cancelRemote: Boolean = false,
) : PasswordChangeAccountBoundary {
    val calls = mutableListOf<String>()

    override suspend fun preparePasswordChange(): PreparedPasswordChange? {
        calls += "prepare"
        return prepare
    }

    override suspend fun changePassword(prepared: PreparedPasswordChange, oldPassword: String, newPassword: String): Boolean {
        calls += "remote"
        if (cancelRemote) throw CancellationException("cancel")
        return remoteChanged
    }

    override suspend fun completePasswordChange(prepared: PreparedPasswordChange): Boolean {
        calls += "complete"
        return true
    }
}
