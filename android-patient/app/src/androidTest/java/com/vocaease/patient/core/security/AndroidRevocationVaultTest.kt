package com.vocaease.patient.core.security

import android.system.Os
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidRevocationVaultTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.filesDir, AndroidRevocationVault.FILE_NAME)

    @After
    fun cleanup() {
        file.delete()
        File(file.path + ".bak").delete()
        context.getFileStreamPath(AndroidTokenVault.FILE_NAME).delete()
        File(context.getFileStreamPath(AndroidTokenVault.FILE_NAME).path + ".bak").delete()
    }

    @Test
    fun 独立密钥加密且文件权限为0600并支持多个撤销槽位() = runBlocking {
        val vault = AndroidRevocationVault(context)
        val first = vault.store("access-secret-a", "refresh-secret-a")
        val second = vault.store("access-secret-b", "refresh-secret-b")

        assertNotEquals(AndroidTokenVault.KEY_ALIAS, AndroidRevocationVault.KEY_ALIAS)
        assertNotEquals(AndroidTokenVault.FILE_NAME, AndroidRevocationVault.FILE_NAME)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("access-secret"))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("refresh-secret"))
        assertEquals(384, Os.stat(file.absolutePath).st_mode and 511)
        assertEquals("access-secret-a", (vault as RevocationTokenSource).lease(first)?.accessToken)
        assertEquals("refresh-secret-a", (vault as RevocationTokenSource).lease(first)?.refreshToken)
        assertEquals("refresh-secret-b", vault.lease(second)?.refreshToken)

        vault.remove(first)
        assertTrue(file.exists())
        vault.remove(second)
        assertFalse(file.exists())
    }

    @Test
    fun 进程在绑定与离线移动检查点死亡后先收敛且active与revocationOnly不并存() = runBlocking {
        val original = AndroidTokenVault(context)
        val installed = original.replaceTokens(0, "old-access", "old-refresh", "login-a")
        assertTrue(installed.applied)
        assertNotNull(original.bindLogoutOperation(installed.snapshot.epoch, "logout-a"))

        // 模拟绑定完成后进程死亡：access 与 operation 只存在于同一加密文件，仍可恢复旧 family。
        val afterBindCrash = AndroidTokenVault(context)
        val recovered = afterBindCrash.boundLogoutOperation(0, "logout-a")
        assertEquals("old-access", recovered?.accessToken)
        assertEquals("old-refresh", recovered?.refreshToken)
        assertTrue(afterBindCrash.readRefreshToken(0) is RefreshTokenRead.Missing)

        val handle = RevocationHandle("e".repeat(64))
        assertTrue(afterBindCrash.moveBoundLogoutToRevocation(0, "logout-a", handle).applied)
        assertNull(afterBindCrash.sessionSnapshot().accessToken)
        assertFalse(context.getFileStreamPath(AndroidTokenVault.FILE_NAME).readText().contains("old-refresh"))

        // 模拟 ACTIVE→PENDING 原子替换后进程死亡：启动先按固定 handle 幂等写 revocation-only。
        val afterMoveCrash = AndroidTokenVault(context)
        val pending = requireNotNull(afterMoveCrash.pendingRevocationTransfer())
        assertEquals(handle, pending.handle)
        val rejectedLogin = afterMoveCrash.replaceTokens(
            afterMoveCrash.sessionSnapshot().epoch,
            "new-access-too-early",
            "new-refresh-too-early",
            "login-before-recovery",
        )
        assertFalse(rejectedLogin.applied)
        assertEquals(handle, afterMoveCrash.pendingRevocationTransfer()?.handle)
        val revocation = AndroidRevocationVault(context)
        revocation.store(handle, pending.accessToken, pending.refreshToken)
        revocation.store(handle, pending.accessToken, pending.refreshToken)
        afterMoveCrash.completePendingRevocationTransfer(pending.operationId, pending.handle)

        assertNull(AndroidTokenVault(context).pendingRevocationTransfer())
        assertEquals("old-refresh", revocation.lease(handle)?.refreshToken)
        val newActive = AndroidTokenVault(context)
        assertTrue(newActive.replaceTokens(0, "new-access", "new-refresh", "login-b").applied)
        assertEquals("new-refresh", (newActive.readRefreshToken(1) as RefreshTokenRead.Available).lease.value)
        assertEquals("old-refresh", revocation.lease(handle)?.refreshToken)
    }

    @Test
    fun 旧operation原epoch不能绑定或清除新ACTIVE() = runBlocking {
        val vault = AndroidTokenVault(context)
        val old = vault.replaceTokens(0, "old-access", "old-refresh", "login-a")
        assertTrue(old.applied)
        assertTrue(vault.clear(old.snapshot.epoch).applied)
        val fresh = vault.replaceTokens(vault.sessionSnapshot().epoch, "new-access", "new-refresh", "login-b")
        assertTrue(fresh.applied)

        assertNull(vault.bindLogoutOperation(old.snapshot.epoch, "logout-old"))
        assertEquals("new-access", vault.sessionSnapshot().accessToken)
        assertEquals("new-refresh", (vault.readRefreshToken(fresh.snapshot.epoch) as RefreshTokenRead.Available).lease.value)
    }
}
