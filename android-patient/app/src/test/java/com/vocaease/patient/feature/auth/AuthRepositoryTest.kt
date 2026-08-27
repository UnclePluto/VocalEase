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
import com.vocaease.patient.core.security.RefreshTokenLease
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.core.security.VaultInvalidatedException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        assertEquals("new-access", vault.sessionSnapshot().accessToken)
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
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refreshValue())
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
        assertEquals("patient-001", repository.currentAuthenticatedLease()?.patientId)
    }

    @Test
    fun `首次登录必须改密时不能进入主页`() = runBlocking {
        val remote = FakeAuthRemote(loginResult = session(mustChangePassword = true))
        val repository = repository(FakeTokenVault(), remote)

        repository.login("patient-001", "initial-password")

        assertEquals(AuthState.MustChangePassword, repository.state.value)
        assertFalse(repository.state.value == AuthState.Authenticated)
        assertNull(repository.currentAuthenticatedLease())
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
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refreshValue())
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(repository.currentAuthenticatedLease())
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
        val failedGeneration = vault.sessionSnapshot().epoch

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
        assertEquals("rotated-refresh", vault.refreshValue())
    }

    @Test
    fun `同一代二十个刷新失败只清除并广播一次`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val gate = CompletableDeferred<Unit>()
        val remote = FakeAuthRemote(
            refreshFailure = java.io.IOException("offline"),
            refreshGate = gate,
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val eventCount = AtomicInteger()
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { eventCount.incrementAndGet() }
        }
        val failedEpoch = vault.sessionSnapshot().epoch

        val requests = List(20) {
            async(Dispatchers.Default) {
                coordinator.refreshAfterUnauthorized(failedEpoch)
            }
        }
        while (remote.refreshCalls.get() == 0) delay(1)
        gate.complete(Unit)
        val results = requests.awaitAll()
        yield()

        assertEquals(1, remote.refreshCalls.get())
        assertTrue(results.all { it is RefreshResult.Failed })
        assertEquals(1, vault.clearCalls.get())
        assertEquals(failedEpoch + 1, vault.sessionSnapshot().epoch)
        assertEquals(1, eventCount.get())
        eventCollector.cancel()
    }

    @Test
    fun `fake解密自失效携带明确epoch且不再clear或重复广播`() = runBlocking {
        val vault = FakeTokenVault(
            accessToken = "expired",
            refreshToken = "stored-refresh",
            readFailure = IllegalArgumentException("cipher damaged"),
        )
        val coordinator = RefreshCoordinator(vault, FakeAuthRemote())
        val eventCount = AtomicInteger()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { eventCount.incrementAndGet() }
        }
        val failedEpoch = vault.sessionSnapshot().epoch

        assertTrue(coordinator.refreshAfterUnauthorized(failedEpoch) is RefreshResult.Failed)
        assertTrue(coordinator.refreshAfterUnauthorized(failedEpoch) is RefreshResult.Failed)
        yield()

        assertEquals(failedEpoch + 1, vault.sessionSnapshot().epoch)
        assertEquals(0, vault.clearCalls.get())
        assertEquals(1, eventCount.get())
        collector.cancel()
    }

    @Test
    fun `旧代请求收到 401 时复用已更新 token 不二次刷新`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "old", refreshToken = "refresh")
        val failedGeneration = vault.sessionSnapshot().epoch
        vault.replaceTokens(failedGeneration, "already-new", "rotated", "manual-replacement")
        val remote = FakeAuthRemote()
        val coordinator = RefreshCoordinator(vault, remote)

        val result = coordinator.refreshAfterUnauthorized(failedGeneration)

        assertEquals(0, remote.refreshCalls.get())
        assertEquals("already-new", (result as RefreshResult.Success).accessToken)
    }

    @Test
    fun `旧epoch失败后新登录获胜时迟到旧401复用新token`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(
            loginResult = session(access = "login-access", refresh = "login-refresh"),
            refreshFailure = java.io.IOException("offline"),
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator)
        val oldEpoch = vault.sessionSnapshot().epoch

        assertTrue(coordinator.refreshAfterUnauthorized(oldEpoch) is RefreshResult.Failed)
        repository.login("patient-001", "password")
        val lateResult = coordinator.refreshAfterUnauthorized(oldEpoch)

        assertTrue(lateResult is RefreshResult.Success)
        assertEquals("login-access", (lateResult as RefreshResult.Success).accessToken)
        assertEquals("login-access", vault.sessionSnapshot().accessToken)
        assertEquals(1, remote.refreshCalls.get())
    }

    @Test
    fun `原请求只因首次401刷新重试一次`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshResult = session(access = "new-access"))
        val coordinator = RefreshCoordinator(vault, remote)
        var requestCalls = 0

        val result = coordinator.executeAuthenticated {
            requestCalls += 1
            if (requestCalls == 1) throw unauthorized()
            requireNotNull(vault.sessionSnapshot().accessToken)
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
            runBlocking { coordinator.refreshAfterUnauthorized(vault.sessionSnapshot().epoch) }
        }

        assertEquals("expired", vault.sessionSnapshot().accessToken)
        assertEquals("stored-refresh", runBlocking { vault.refreshValue() })
    }

    @Test
    fun `登录持久化已提交后收到取消仍清除本次凭据并传播取消`() {
        val vault = FakeTokenVault(
            replaceFailureAfterApply = java.util.concurrent.CancellationException("cancel after commit"),
        )
        val repository = repository(
            vault,
            FakeAuthRemote(loginResult = session(access = "must-not-survive", refresh = "must-not-survive")),
        )

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking { repository.login("patient-001", "password") }
        }

        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(runBlocking { vault.refreshValue() })
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertEquals(AuthOperationState.Idle, repository.operation.value)
    }

    @Test
    fun `刷新持久化已提交后收到取消仍清除本次凭据并传播取消`() {
        val vault = FakeTokenVault(
            accessToken = "expired",
            refreshToken = "stored-refresh",
            replaceFailureAfterApply = java.util.concurrent.CancellationException("cancel after commit"),
        )
        val coordinator = RefreshCoordinator(
            vault,
            FakeAuthRemote(refreshResult = session(access = "must-not-survive", refresh = "must-not-survive")),
        )

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking { coordinator.refreshAfterUnauthorized(vault.sessionSnapshot().epoch) }
        }

        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(runBlocking { vault.refreshValue() })
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

        val result = coordinator.refreshAfterUnauthorized(vault.sessionSnapshot().epoch)

        assertTrue(result is RefreshResult.Failed)
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refreshValue())
    }

    @Test
    fun `刷新遇到未知编程异常时原样传播且不销毁凭据`() {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val coordinator = RefreshCoordinator(
            vault,
            FakeAuthRemote(refreshFailure = IllegalStateException("programming defect")),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.refreshAfterUnauthorized(vault.sessionSnapshot().epoch) }
        }

        assertEquals("expired", vault.sessionSnapshot().accessToken)
        assertEquals("stored-refresh", runBlocking { vault.refreshValue() })
    }

    @Test
    fun `已登录期间刷新失败会驱动仓库登出并广播过期`() = runBlocking {
        val vault = FakeTokenVault(accessToken = "expired", refreshToken = "stored-refresh")
        val remote = FakeAuthRemote(refreshFailure = java.io.IOException("offline"))
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator)
        repository.restoreSession()
        val event = async { repository.events.first() }

        coordinator.refreshAfterUnauthorized(vault.sessionSnapshot().epoch)

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
    private val readFailure: Throwable? = null,
    private val replaceFailure: Throwable? = null,
    private val replaceFailureAfterApply: Throwable? = null,
) : TokenVault {
    private var access = SessionSnapshot(accessToken, if (accessToken == null) 0 else 1)
    private var refresh = refreshToken
    val clearCalls = AtomicInteger()

    override fun sessionSnapshot(): SessionSnapshot = synchronized(this) { access }

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead = synchronized(this) {
        if (access.epoch == expectedEpoch && refresh != null) {
            readFailure?.let { failure ->
                val invalidation = SessionInvalidation(access.epoch, access.epoch + 1)
                refresh = null
                access = SessionSnapshot(null, invalidation.toEpoch)
                return@synchronized RefreshTokenRead.Invalidated(invalidation, failure)
            }
            RefreshTokenRead.Available(RefreshTokenLease(requireNotNull(refresh), expectedEpoch))
        } else {
            RefreshTokenRead.Missing(access.epoch)
        }
    }

    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation {
        replaceFailure?.let { failure ->
            synchronized(this) {
                if (access.epoch != expectedEpoch) return SessionMutation(false, access)
                val invalidation = SessionInvalidation(access.epoch, access.epoch + 1)
                refresh = null
                access = SessionSnapshot(null, invalidation.toEpoch)
                throw VaultInvalidatedException(invalidation, failure)
            }
        }
        val mutation = synchronized(this) {
            if (access.epoch != expectedEpoch) return@synchronized SessionMutation(false, access)
            refresh = refreshToken
            access = SessionSnapshot(accessToken, access.epoch + 1, replacementId)
            SessionMutation(true, access)
        }
        if (mutation.applied) replaceFailureAfterApply?.let { throw it }
        return mutation
    }

    override suspend fun clear(expectedEpoch: Long?): SessionMutation = synchronized(this) {
        clearCalls.incrementAndGet()
        if (expectedEpoch != null && access.epoch != expectedEpoch) {
            SessionMutation(false, access)
        } else {
            refresh = null
            access = SessionSnapshot(null, access.epoch + 1)
            SessionMutation(true, access)
        }
    }

    suspend fun refreshValue(): String? =
        (readRefreshToken(sessionSnapshot().epoch) as? RefreshTokenRead.Available)?.lease?.value
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
