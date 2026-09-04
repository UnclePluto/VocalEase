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
import org.junit.Test

class AndroidRevocationVaultTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.filesDir, AndroidRevocationVault.FILE_NAME)

    @After
    fun cleanup() {
        file.delete()
        File(file.path + ".bak").delete()
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
}
