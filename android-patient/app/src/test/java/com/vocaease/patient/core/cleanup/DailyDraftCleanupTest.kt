package com.vocaease.patient.core.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DailyDraftCleanupTest {
    @Test
    fun `每日one-time契约只携带不可逆scope信息且无需网络和通知`() {
        val scopeHash = "a".repeat(64)
        val scopeToken = "b".repeat(64)

        val contract = DraftCleanupWorkContract(scopeHash, scopeToken)

        assertEquals(24L * 60L * 60L * 1_000L, contract.initialDelayMillis)
        assertEquals("vocaease-draft-cleanup-$scopeHash", contract.uniqueWorkName)
        assertEquals(setOf("scope_hash", "scope_token"), contract.input.keys)
        assertFalse(contract.requiresNetwork)
        assertFalse(contract.showsNotification)
    }
}
