package com.vocaease.patient.core.security

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RevocationWorkContractTest {
    @Test
    fun `撤销任务仅携带不可逆句柄并要求联网与指数退避`() {
        val handle = RevocationHandle("a".repeat(64))
        val contract = RevocationWorkContract(handle)

        assertEquals(mapOf("revocation_slot_id" to handle.slotId), contract.input)
        assertFalse(contract.input.values.any { it.contains("token", ignoreCase = true) })
        assertEquals(NetworkType.CONNECTED, contract.networkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, contract.backoffPolicy)
        assertEquals(30_000L, contract.backoffDelayMillis)
        assertEquals("revocation:${handle.slotId}", contract.uniqueWorkName)
    }

    @Test
    fun `瞬时失败最多自动重试五次且槽位完成后立即收敛`() {
        repeat(5) { attempt ->
            assertEquals(
                RevocationWorkDecision.Retry,
                RevocationRetryPolicy.decide(RevocationExecution.Retry, attempt),
            )
        }
        assertEquals(
            RevocationWorkDecision.StopRetainingSlot,
            RevocationRetryPolicy.decide(RevocationExecution.Retry, 5),
        )
        assertEquals(
            RevocationWorkDecision.Finished,
            RevocationRetryPolicy.decide(RevocationExecution.Finished, 0),
        )
    }

    @Test
    fun `非法撤销句柄不能进入WorkManager`() {
        assertThrows(IllegalArgumentException::class.java) { RevocationHandle("refresh-secret") }
    }
}
