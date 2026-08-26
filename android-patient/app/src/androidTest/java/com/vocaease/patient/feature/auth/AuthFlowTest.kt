package com.vocaease.patient.feature.auth

import android.content.Context
import android.security.keystore.KeyProperties
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.VaultFileStore
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanBefore() = cleanVault()

    @After
    fun cleanAfter() = cleanVault()

    @Test
    fun refreshToken使用Keystore密文且文件仅应用可读写() = runBlocking {
        val vault = AndroidTokenVault(context)

        vault.replaceTokens(vault.sessionSnapshot().epoch, "memory-access", "refresh-plain-secret")

        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("refresh-plain-secret"))
        assertEquals(AndroidTokenVault.FILE_VERSION, bytes.readIntAtStart())
        assertEquals("refresh-plain-secret", vault.readRefreshToken(vault.sessionSnapshot().epoch)?.value)
        assertEquals("memory-access", vault.sessionSnapshot().accessToken)
        assertEquals(384, Os.stat(file.absolutePath).st_mode and 511)

        val keyStore = androidKeyStore()
        val key = keyStore.getKey(AndroidTokenVault.KEY_ALIAS, null)
        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertFalse(key.encoded?.isNotEmpty() == true)
    }

    @Test
    fun 密文篡改后立即删除文件并清空内存会话() = runBlocking {
        val vault = AndroidTokenVault(context)
        vault.replaceTokens(vault.sessionSnapshot().epoch, "memory-access", "refresh-secret")
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })

        assertNull(vault.readRefreshToken(vault.sessionSnapshot().epoch))

        assertFalse(file.exists())
        assertNull(vault.sessionSnapshot().accessToken)
    }

    @Test
    fun 错误文件版本或密钥失效都删除密文并登出() = runBlocking {
        val vault = AndroidTokenVault(context)
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(99)
            output.writeInt(12)
            output.writeInt(17)
            output.write(ByteArray(29))
        }
        assertNull(vault.readRefreshToken(vault.sessionSnapshot().epoch))
        assertFalse(file.exists())

        vault.replaceTokens(vault.sessionSnapshot().epoch, "memory-access", "refresh-secret")
        androidKeyStore().deleteEntry(AndroidTokenVault.KEY_ALIAS)
        assertNull(vault.readRefreshToken(vault.sessionSnapshot().epoch))
        assertFalse(file.exists())
        assertNull(vault.sessionSnapshot().accessToken)
    }

    @Test
    fun 原子写完成后续失败仍删除已落盘密文并清除access() {
        val store = RetainingFailureFileStore()
        val vault = AndroidTokenVault(context, fileStore = store)
        val initialEpoch = vault.sessionSnapshot().epoch

        assertThrows(IOException::class.java) {
            runBlocking {
                vault.replaceTokens(initialEpoch, "must-not-survive", "refresh-must-not-survive")
            }
        }

        assertFalse(store.exists())
        assertEquals(1, store.deleteCalls)
        assertNull(vault.sessionSnapshot().accessToken)
        assertEquals(initialEpoch + 1, vault.sessionSnapshot().epoch)
    }

    @Test
    fun 并发原子文件替换与清除各只有一个epoch获胜() = runBlocking {
        val vault = AndroidTokenVault(context)
        val initialEpoch = vault.sessionSnapshot().epoch

        val replacements = coroutineScope {
            List(20) { index ->
                async(Dispatchers.Default) {
                    vault.replaceTokens(initialEpoch, "access-$index", "refresh-$index")
                }
            }.awaitAll()
        }

        assertEquals(1, replacements.count { it.applied })
        assertEquals(initialEpoch + 1, vault.sessionSnapshot().epoch)
        assertTrue(context.getFileStreamPath(AndroidTokenVault.FILE_NAME).exists())

        val clearEpoch = vault.sessionSnapshot().epoch
        val clears = coroutineScope {
            List(20) {
                async(Dispatchers.Default) { vault.clear(clearEpoch) }
            }.awaitAll()
        }

        assertEquals(1, clears.count { it.applied })
        assertEquals(clearEpoch + 1, vault.sessionSnapshot().epoch)
        assertNull(vault.sessionSnapshot().accessToken)
        assertFalse(context.getFileStreamPath(AndroidTokenVault.FILE_NAME).exists())
    }

    @Test
    fun 登录页只有病历号密码登录且无注册入口() {
        composeRule.setContent {
            LoginScreen(
                operation = AuthOperationState.Idle,
                onLogin = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("病历号").assertExists()
        composeRule.onNodeWithText("密码").assertExists()
        composeRule.onNodeWithTag("login-submit").assertExists()
        composeRule.onNodeWithText("账号由医生创建，如需帮助请联系医生").assertExists()
        composeRule.onNodeWithText("注册").assertDoesNotExist()
    }

    @Test
    fun 首次登录只能改密且改密后显示提示返回登录() {
        val remote = UiAuthRemote()
        val vault = UiTokenVault()
        val repository = AuthRepository(
            tokenVault = vault,
            remote = remote,
            refreshCoordinator = com.vocaease.patient.core.network.RefreshCoordinator(vault, remote),
        )
        runBlocking { repository.login("patient-001", "initial-password") }

        composeRule.setContent { AuthFlow(repository) }

        composeRule.onNodeWithText("首次登录，请修改密码").assertExists()
        composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
        composeRule.onNodeWithTag("change-old-password").performTextInput("initial-password")
        composeRule.onNodeWithTag("change-new-password").performTextInput("new-password")
        composeRule.onNodeWithTag("change-password-submit").performClick()
        composeRule.waitUntil(5_000) { remote.logoutCalled }
        composeRule.onNodeWithText("密码已修改，请重新登录").assertExists()
        composeRule.onNodeWithTag("login-submit").assertExists()
    }

    private fun cleanVault() {
        context.getFileStreamPath(AndroidTokenVault.FILE_NAME).delete()
        androidKeyStore().deleteEntry(AndroidTokenVault.KEY_ALIAS)
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

private fun ByteArray.readIntAtStart(): Int =
    ((this[0].toInt() and 255) shl 24) or
        ((this[1].toInt() and 255) shl 16) or
        ((this[2].toInt() and 255) shl 8) or
        (this[3].toInt() and 255)

private class RetainingFailureFileStore : VaultFileStore {
    private var payload: ByteArray? = null
    var deleteCalls = 0
        private set

    override fun exists(): Boolean = payload != null
    override fun readFully(): ByteArray = requireNotNull(payload).copyOf()

    override fun writeAtomically(payload: ByteArray) {
        this.payload = payload.copyOf()
        throw IOException("模拟 finishWrite 后 chmod 失败")
    }

    override fun delete() {
        deleteCalls += 1
        payload = null
    }
}

private class UiTokenVault : com.vocaease.patient.core.security.TokenVault {
    private var access = com.vocaease.patient.core.security.SessionSnapshot(null, 0)
    private var refresh: String? = null

    override fun sessionSnapshot() = access
    override suspend fun readRefreshToken(expectedEpoch: Long) =
        if (access.epoch == expectedEpoch) refresh?.let {
            com.vocaease.patient.core.security.RefreshTokenLease(it, expectedEpoch)
        } else null
    override suspend fun replaceTokens(expectedEpoch: Long, accessToken: String, refreshToken: String):
        com.vocaease.patient.core.security.SessionMutation {
        if (access.epoch != expectedEpoch) {
            return com.vocaease.patient.core.security.SessionMutation(false, access)
        }
        refresh = refreshToken
        access = com.vocaease.patient.core.security.SessionSnapshot(accessToken, access.epoch + 1)
        return com.vocaease.patient.core.security.SessionMutation(true, access)
    }
    override suspend fun clear(expectedEpoch: Long?): com.vocaease.patient.core.security.SessionMutation {
        if (expectedEpoch != null && access.epoch != expectedEpoch) {
            return com.vocaease.patient.core.security.SessionMutation(false, access)
        }
        refresh = null
        access = com.vocaease.patient.core.security.SessionSnapshot(null, access.epoch + 1)
        return com.vocaease.patient.core.security.SessionMutation(true, access)
    }
}

private class UiAuthRemote : AuthRemoteDataSource {
    @Volatile var logoutCalled = false

    override suspend fun login(request: LoginRequestDto) = AuthSession(
        access = "access",
        refresh = "refresh",
        refreshExpiresAt = Instant.parse("2026-09-01T08:00:00Z"),
        loginId = request.loginId,
        role = AccountRole.PATIENT,
        mustChangePassword = true,
    )

    override suspend fun refresh(request: RefreshRequestDto) = error("本测试不应刷新")

    override suspend fun changePassword(request: ChangePasswordRequestDto) = Unit

    override suspend fun logout(request: LogoutRequestDto) {
        logoutCalled = true
    }
}
