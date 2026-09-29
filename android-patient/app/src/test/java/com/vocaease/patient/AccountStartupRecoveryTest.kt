package com.vocaease.patient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStartupRecoveryTest {
    @Test
    fun `持久退出恢复先于普通账户工作且恢复退出后不启动普通工作`() = runBlocking {
        val order = mutableListOf<String>()
        val recovery = AccountStartupRecovery(
            recoverExit = { order += "exit"; true },
            startNormalWork = { order += "normal" },
        )

        assertTrue(recovery.run())
        assertEquals(listOf("exit"), order)
    }

    @Test
    fun `无退出意图才启动普通账户工作且恢复失败保持锁定`() = runBlocking {
        val noIntentOrder = mutableListOf<String>()
        val noIntent = AccountStartupRecovery(
            recoverExit = { noIntentOrder += "exit"; false },
            startNormalWork = { noIntentOrder += "normal" },
        )
        assertFalse(noIntent.run())
        assertEquals(listOf("exit", "normal"), noIntentOrder)

        val failureOrder = mutableListOf<String>()
        val failure = AccountStartupRecovery(
            recoverExit = { failureOrder += "exit"; error("delete incomplete") },
            startNormalWork = { failureOrder += "normal" },
        )
        assertTrue(failure.run())
        assertEquals(listOf("exit"), failureOrder)
    }
}
