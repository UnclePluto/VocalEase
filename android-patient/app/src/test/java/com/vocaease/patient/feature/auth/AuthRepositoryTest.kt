package com.vocaease.patient.feature.auth

import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.security.AccessTokenSnapshot
import com.vocaease.patient.core.security.TokenVault
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AuthRepositoryTest {
    @Test
    fun `无 refresh 会话时启动进入登录页`() = runBlocking {
        val repository = repository(vault = FakeTokenVault())

        repository.restoreSession()

        assertEquals(AuthState.LoggedOut, repository.state.value)
    }

    @Test
    fun `启动时 refresh 成功恢复正常患者会话`() = runBlocking {
        val vault = FakeTokenVault(refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshResult = session(access = "new-access"))
        val repository = repository(vault, remote)

        repository.restoreSession()

        assertEquals(AuthState.Authenticated, repository.state.value)
        assertEquals("new-access", vault.accessSnapshot().value)
        assertEquals(1, remote.refreshCalls.get())
        assertEquals(RefreshRequestDto(ClientKind.ANDROID, "stored-refresh"), remote.lastRefresh)
    }

    @Test
    fun `启动刷新失败会清除会话并发送过期事件`() = runBlocking {
        val vault = FakeTokenVault(refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshFailure = java.io.IOException("refresh rejected"))
        val repository = repository(vault, remote)
        val event = async { repository.events.first() }

        repository.restoreSession()

        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(vault.accessSnapshot().value)
        assertNull(vault.readRefreshToken())
        assertEquals(AuthEvent.SessionExpired, event.await())
    }

    @Test
    fun `登录固定发送安卓客户端且不记住并进入主页`() = runBlocking {
        val vault = FakeTokenVault()
        val remote = FakeAuthRemote(loginResult = session(access = "access", refresh = "refresh"))
        val repository = repository(vault, remote)

        repository.login("patient-001", "secret")

        assertEquals(
            LoginRequestDto(
                loginId = "patient-001",
                password = "secret",
                clientKind = ClientKind.ANDROID,
                rememberMe = false,
            ),
            remote.lastLogin,
        )
        assertEquals(AuthState.Authenticated, repository.state.value)
    }

    @Test
    fun `首次登录必须改密时不能进入主页`() = runBlocking {
        val remote = FakeAuthRemote(loginResult = session(mustChangePassword = true))
        val repository = repository(FakeTokenVault(), remote)

        repository.login("patient-001", "initial-password")

        assertEquals(AuthState.MustChangePassword, repository.state.value)
        assertFalse(repository.state.value == AuthState.Authenticated)
    }

    @Test
    fun `改密成功即使远端登出失败也清会话并提示重新登录`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "access", refreshToken = "refresh")
        val remote = FakeAuthRemote(logoutFailure = IllegalStateException("offline"))
        val repository = repository(vault, remote)
        val event = async { repository.events.first() }

        repository.changePassword("initial-password", "new-password")

        assertEquals(
            ChangePasswordRequestDto("initial-password", "new-password"),
            remote.lastChangePassword,
        )
        assertEquals(LogoutRequestDto(ClientKind.ANDROID, "refresh"), remote.lastLogout)
        assertNull(vault.accessSnapshot().value)
        assertNull(vault.readRefreshToken())
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertEquals(AuthEvent.PasswordChanged, event.await())
    }

    @Test
    fun `同一代 token 的二十个 401 只刷新一次并共享新 token`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val gate = CompletableDeferred<Unit>()
        val remote = FakeAuthRemote(
            refreshResult = session(access = "new-access", refresh = "rotated-refresh"),
            refreshGate = gate,
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val failedGeneration = vault.accessSnapshot().generation

        val requests = List(20) {
            async(Dispatchers.Default) {
                coordinator.refreshAfterUnauthorized(failedGeneration)
            }
        }
        while (remote.refreshCalls.get() == 0) delay(1)
        gate.complete(Unit)
        val results = requests.awaitAll()

        assertEquals(1, remote.refreshCalls.get())
        assertTrue(results.all { it is RefreshResult.Success })
        assertTrue(results.all { (it as RefreshResult.Success).accessToken == "new-access" })
        assertEquals("rotated-refresh", vault.readRefreshToken())
    }

    @Test
    fun `旧代请求收到 401 时复用已更新 token 不二次刷新`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "old", refreshToken = "refresh")
        val failedGeneration = vault.accessSnapshot().generation
        vault.replaceTokens("already-new", "rotated")
        val remote = FakeAuthRemote()
        val coordinator = RefreshCoordinator(vault, remote)

        val result = coordinator.refreshAfterUnauthorized(failedGeneration)

        assertEquals(0, remote.refreshCalls.get())
        assertEquals("already-new", (result as RefreshResult.Success).accessToken)
    }

    @Test
    fun `原请求只因首次401刷新重试一次`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshResult = session(access = "new-access"))
        val coordinator = RefreshCoordinator(vault, remote)
        var requestCalls = 0

        val result = coordinator.executeAuthenticated { token ->
            requestCalls += 1
            if (requestCalls == 1) throw unauthorized()
            token
        }

        assertEquals("new-access", result)
        assertEquals(2, requestCalls)
        assertEquals(1, remote.refreshCalls.get())
    }

    @Test
    fun `重试仍为401时不再刷新且原样传播`() {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshResult = session(access = "new-access"))
        val coordinator = RefreshCoordinator(vault, remote)
        var requestCalls = 0

        assertThrows(HttpException::class.java) {
            runBlocking {
                coordinator.executeAuthenticated<String> {
                    requestCalls += 1
                    throw unauthorized()
                }
            }
        }

        assertEquals(2, requestCalls)
        assertEquals(1, remote.refreshCalls.get())
    }

    @Test
    fun `刷新被取消时传播取消且保留原凭据`() {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshFailure = java.util.concurrent.CancellationException("cancelled"))
        val coordinator = RefreshCoordinator(vault, remote)

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking { coordinator.refreshAfterUnauthorized(vault.accessSnapshot().generation) }
        }

        assertEquals("expired", vault.accessSnapshot().value)
        assertEquals("stored-refresh", runBlocking { vault.readRefreshToken() })
    }

    @Test
    fun `refresh 成功但密文持久化失败时不会发布半更新会话`() = runBlocking {
        val vault = FakeTokenVault(
            accessToken = "expired",
            refreshToken = "stored-refresh",
            replaceFailure = java.io.IOException("disk full"),
        )
        val coordinator = RefreshCoordinator(
            vault,
            FakeAuthRemote(refreshResult = session(access = "new-access", refresh = "new-refresh")),
        )

        val result = coordinator.refreshAfterUnauthorized(vault.accessSnapshot().generation)

        assertTrue(result is RefreshResult.Failed)
        assertNull(vault.accessSnapshot().value)
        assertNull(vault.readRefreshToken())
    }

    @Test
    fun `刷新遇到未知编程异常时原样传播且不销毁凭据`() {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val coordinator = RefreshCoordinator(
            vault,
            FakeAuthRemote(refreshFailure = IllegalStateException("programming defect")),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.refreshAfterUnauthorized(vault.accessSnapshot().generation) }
        }

        assertEquals("expired", vault.accessSnapshot().value)
        assertEquals("stored-refresh", runBlocking { vault.readRefreshToken() })
    }

    @Test
    fun `已登录期间刷新失败会驱动仓库登出并广播过期`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshFailure = java.io.IOException("offline"))
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator)
        repository.restoreSession()
        val event = async { repository.events.first() }

        coordinator.refreshAfterUnauthorized(vault.accessSnapshot().generation)

        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertEquals(AuthEvent.SessionExpired, event.await())
    }

    private fun repository(
        vault: FakeTokenVault,
        remote: FakeAuthRemote = FakeAuthRemote(),
    ): AuthRepository = AuthRepository(
        tokenVault = vault,
        remote = remote,
        refreshCoordinator = RefreshCoordinator(vault, remote),
    )
}

private class FakeTokenVault(
    accessToken: String? = null,
    refreshToken: String? = null,
    private val replaceFailure: Throwable? = null,
) : TokenVault {
    private var access = AccessTokenSnapshot(accessToken, if (accessToken == null) 0 else 1)
    private var refresh = refreshToken

    override fun accessSnapshot(): AccessTokenSnapshot = synchronized(this) { access }

    override suspend fun readRefreshToken(): String? = synchronized(this) { refresh }

    override suspend fun replaceTokens(accessToken: String, refreshToken: String) {
        replaceFailure?.let { throw it }
        synchronized(this) {
            refresh = refreshToken
            access = AccessTokenSnapshot(accessToken, access.generation + 1)
        }
    }

    override suspend fun clear() {
        synchronized(this) {
            refresh = null
            access = AccessTokenSnapshot(null, access.generation + 1)
        }
    }
}

private class FakeAuthRemote(
    private val loginResult: AuthSession = session(),
    private val refreshResult: AuthSession = session(),
    private val refreshFailure: Throwable? = null,
    private val logoutFailure: Throwable? = null,
    private val refreshGate: CompletableDeferred<Unit>? = null,
) : AuthRemoteDataSource {
    val refreshCalls = AtomicInteger()
    var lastLogin: LoginRequestDto? = null
    var lastRefresh: RefreshRequestDto? = null
    var lastChangePassword: ChangePasswordRequestDto? = null
    var lastLogout: LogoutRequestDto? = null

    override suspend fun login(request: LoginRequestDto): AuthSession {
        lastLogin = request
        return loginResult
    }

    override suspend fun refresh(request: RefreshRequestDto): AuthSession {
        lastRefresh = request
        refreshCalls.incrementAndGet()
        refreshGate?.await()
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun changePassword(request: ChangePasswordRequestDto) {
        lastChangePassword = request
    }

    override suspend fun logout(request: LogoutRequestDto) {
        lastLogout = request
        logoutFailure?.let { throw it }
    }
}

private fun session(
    access: String = "access",
    refresh: String = "refresh",
    mustChangePassword: Boolean = false,
): AuthSession = AuthSession(
    access = access,
    refresh = refresh,
    refreshExpiresAt = Instant.parse("2026-09-01T08:00:00Z"),
    loginId = "patient-001",
    role = AccountRole.PATIENT,
    mustChangePassword = mustChangePassword,
)

private fun unauthorized(): HttpException = HttpException(
    Response.error<Any>(401, "unauthorized".toResponseBody()),
)
