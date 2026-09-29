package com.vocaease.patient.core.security

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RevocationExecutorTest {
    @Test
    fun `成功或明确无效会删除槽位而服务端错误与离线会保留`() = runBlocking {
        val outcomes = listOf(
            RevocationRemoteResult.Success to false,
            RevocationRemoteResult.InvalidOrExpired to false,
            RevocationRemoteResult.Retryable to true,
        )

        outcomes.forEach { (remoteResult, shouldRemain) ->
            val vault = InMemoryRevocationVault()
            val handle = vault.store("access-secret", "refresh-secret")
            val executor = RevocationExecutor(vault, vault, RevocationRemote { _, _ -> remoteResult })

            assertEquals(shouldRemain, executor.revoke(handle) == RevocationExecution.Retry)
            if (shouldRemain) assertNotNull(vault.lease(handle)) else assertNull(vault.lease(handle))
        }

        val offlineVault = InMemoryRevocationVault()
        val offlineHandle = offlineVault.store("access-secret", "refresh-secret")
        val offline = RevocationExecutor(offlineVault, offlineVault, RevocationRemote { _, _ -> throw IOException("offline") })
        assertEquals(RevocationExecution.Retry, offline.revoke(offlineHandle))
        assertNotNull(offlineVault.lease(offlineHandle))
    }

    @Test
    fun `普通写入方只有不可逆槽位句柄且读取能力属于独立接口`() = runBlocking {
        val vault = InMemoryRevocationVault()
        val commonAuthGraph: RevocationTokenSink = vault

        val handle = commonAuthGraph.store("access-secret", "refresh-secret")

        assertEquals(64, handle.slotId.length)
        assertEquals("access-secret", (vault as RevocationTokenSource).lease(handle)?.accessToken)
        assertEquals("refresh-secret", (vault as RevocationTokenSource).lease(handle)?.refreshToken)
        assertEquals(listOf(handle), vault.handles())
    }
}

private class InMemoryRevocationVault : RevocationTokenSink, RevocationTokenSource {
    private val records = mutableMapOf<String, Pair<String, String>>()

    override suspend fun store(accessToken: String, refreshToken: String): RevocationHandle {
        val handle = RevocationHandle("a".repeat(64))
        records[handle.slotId] = accessToken to refreshToken
        return handle
    }

    override suspend fun lease(handle: RevocationHandle): RevocationTokenLease? =
        records[handle.slotId]?.let { RevocationTokenLease(handle, it.first, it.second) }

    override suspend fun remove(handle: RevocationHandle) {
        records.remove(handle.slotId)
    }

    override suspend fun handles(): List<RevocationHandle> = records.keys.map(::RevocationHandle)
}
