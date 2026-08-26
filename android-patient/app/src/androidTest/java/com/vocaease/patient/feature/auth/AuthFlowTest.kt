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
import java.io.DataOutputStream
import java.security.KeyStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        vault.replaceTokens("memory-access", "refresh-plain-secret")

        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("refresh-plain-secret"))
        assertEquals(AndroidTokenVault.FILE_VERSION, bytes.readIntAtStart())
        assertEquals("refresh-plain-secret", vault.readRefreshToken())
        assertEquals("memory-access", vault.accessSnapshot().value)
        assertEquals(384, Os.stat(file.absolutePath).st_mode and 511)

        val keyStore = androidKeyStore()
        val key = keyStore.getKey(AndroidTokenVault.KEY_ALIAS, null)
        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertFalse(key.encoded?.isNotEmpty() == true)
    }

    @Test
    fun 密文篡改后立即删除文件并清空内存会话() = runBlocking {
        val vault = AndroidTokenVault(context)
        vault.replaceTokens("memory-access", "refresh-secret")
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })

        assertNull(vault.readRefreshToken())

        assertFalse(file.exists())
        assertNull(vault.accessSnapshot().value)
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
        assertNull(vault.readRefreshToken())
        assertFalse(file.exists())

        vault.replaceTokens("memory-access", "refresh-secret")
        androidKeyStore().deleteEntry(AndroidTokenVault.KEY_ALIAS)
        assertNull(vault.readRefreshToken())
        assertFalse(file.exists())
        assertNull(vault.accessSnapshot().value)
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

private class UiTokenVault : com.vocaease.patient.core.security.TokenVault {
    private var access = com.vocaease.patient.core.security.AccessTokenSnapshot(null, 0)
    private var refresh: String? = null

    override fun accessSnapshot() = access
    override suspend fun readRefreshToken() = refresh
    override suspend fun replaceTokens(accessToken: String, refreshToken: String) {
        refresh = refreshToken
        access = com.vocaease.patient.core.security.AccessTokenSnapshot(accessToken, access.generation + 1)
    }
    override suspend fun clear() {
        refresh = null
        access = com.vocaease.patient.core.security.AccessTokenSnapshot(null, access.generation + 1)
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
