package com.vocaease.patient.feature.history

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisWorkContractTest {
    @Test
    fun `唯一one time任务只携带哈希session和incarnation证明且无需通知`() {
        val scope = "a".repeat(64)
        val proof = "b".repeat(64)
        val contract = AnalysisAndroidWorkContract(scope, "session-1", proof)

        assertEquals("analysis:$scope:session-1", contract.uniqueWorkName)
        assertEquals(setOf("account_scope_hash", "session_id", "incarnation_proof"), contract.input.keys)
        assertFalse(contract.input.values.any { it.contains("patient", true) || it.contains("url", true) })
        assertEquals(NetworkType.CONNECTED, contract.networkType)
        assertEquals(10_000L, contract.initialDelayMillis)
        assertFalse(contract.requiresForegroundNotification)
    }

    @Test
    fun `非法work输入fail closed`() {
        listOf(
            { AnalysisAndroidWorkContract("patient", "session-1", "b".repeat(64)) },
            { AnalysisAndroidWorkContract("a".repeat(64), "../session", "b".repeat(64)) },
            { AnalysisAndroidWorkContract("a".repeat(64), "session-1", "token") },
        ).forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }
}
