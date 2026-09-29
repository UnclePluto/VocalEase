package com.vocaease.patient.feature.upload

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWorkContractTest {
    @Test
    fun `唯一任务只携带账户哈希与draft且使用联网和30秒指数退避`() {
        val scopeHash = "a".repeat(64)
        val contract = UploadWorkContract(scopeHash, "draft-1")

        assertEquals("upload:$scopeHash:draft-1", contract.uniqueWorkName)
        assertEquals(setOf("account_scope_hash", "draft_id"), contract.input.keys)
        assertFalse(contract.input.values.any { it.contains("patient", ignoreCase = true) || it.contains("token", ignoreCase = true) })
        assertEquals(NetworkType.CONNECTED, contract.networkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, contract.backoffPolicy)
        assertEquals(30_000L, contract.backoffDelayMillis)
        assertEquals(5L * 60L * 60L * 1_000L, contract.maxRunMillis)
    }

    @Test
    fun `非法账户哈希与draft fail closed`() {
        listOf(
            { UploadWorkContract("patient-1", "draft-1") },
            { UploadWorkContract("a".repeat(64), "../draft") },
            { UploadWorkContract("a".repeat(64), "d".repeat(129)) },
        ).forEach { create ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { create() }
        }
    }

    @Test
    fun `通知权限只在Android13以上的本地确认动作请求`() {
        assertFalse(UploadNotificationPermission.shouldRequest(29, triggeredByLocalReviewConfirm = true, granted = false))
        assertFalse(UploadNotificationPermission.shouldRequest(33, triggeredByLocalReviewConfirm = false, granted = false))
        assertFalse(UploadNotificationPermission.shouldRequest(33, triggeredByLocalReviewConfirm = true, granted = true))
        assertTrue(UploadNotificationPermission.shouldRequest(33, triggeredByLocalReviewConfirm = true, granted = false))
        assertTrue(UploadNotificationPermission.shouldRequest(36, triggeredByLocalReviewConfirm = true, granted = false))
    }
}
