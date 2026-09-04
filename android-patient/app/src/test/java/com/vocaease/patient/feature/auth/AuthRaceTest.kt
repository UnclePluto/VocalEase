package com.vocaease.patient.feature.auth

import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.network.SessionChangedException
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.RefreshTokenLease
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRaceTest {
    @Test
    fun `登录远端返回前登出使登录 CAS 失败且不能复活会话`() = runBlocking {
        val vault = LinearTokenVault()
        val remote = GatedAuthRemote(loginResult = authSession("login-access", "login-refresh"))
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator, patientIdentity())
        remote.holdLogin = true

        val login = async { repository.login("patient-001", "password") }
        remote.loginStarted.await()
        repository.logout()
        remote.releaseLogin.complete(Unit)
        login.await()

        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refreshValue())
    }

    @Test
    fun `刷新远端返回前登出使 refresh CAS 失败且不能重写已清凭据`() = runBlocking {
        val vault = LinearTokenVault("expired-access", "stored-refresh")
        val remote = GatedAuthRemote(refreshResult = authSession("refresh-access", "rotated-refresh"))
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator, patientIdentity())
        repository.restoreSession()
        remote.holdRefresh = true
        val failedEpoch = vault.sessionSnapshot().epoch

        val refresh = async { coordinator.refreshAfterUnauthorized(failedEpoch) }
        remote.refreshStarted.await()
        repository.logout()
        remote.releaseRefresh.complete(Unit)
        val result = refresh.await()

        assertTrue(result is RefreshResult.Superseded)
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(vault.sessionSnapshot().accessToken)
    }

    @Test
    fun `刷新远端失败前已登出时仍返回superseded而不是旧会话失败`() = runBlocking {
        val vault = LinearTokenVault("expired-access", "stored-refresh")
        val remote = GatedAuthRemote(refreshFailure = java.io.IOException("offline"))
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator, patientIdentity())
        repository.restoreSession()
        remote.holdRefresh = true
        val failedEpoch = vault.sessionSnapshot().epoch
        val refresh = async { coordinator.refreshAfterUnauthorized(failedEpoch) }
        remote.refreshStarted.await()

        repository.logout()
        remote.releaseRefresh.complete(Unit)

        assertTrue(refresh.await() is RefreshResult.Superseded)
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(vault.sessionSnapshot().accessToken)
    }

    @Test
    fun `旧刷新返回前新登录获胜时旧请求终止且不使用新患者token重试`() = runBlocking {
        val vault = LinearTokenVault("expired-access", "stored-refresh")
        val remote = GatedAuthRemote(
            loginResult = authSession("login-access", "login-refresh"),
            refreshResult = authSession("stale-refresh-access", "stale-refresh-token"),
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val identity = MutablePatientIdentity("11111111-1111-4111-8111-111111111111")
        val repository = AuthRepository(vault, remote, coordinator, identity)
        repository.restoreSession()
        remote.holdRefresh = true
        var requestCalls = 0
        val oldRequest = async {
            val failure = runCatching {
                coordinator.executeAuthenticated {
                    requestCalls += 1
                    if (requestCalls == 1) throw unauthorized()
                    requireNotNull(vault.sessionSnapshot().accessToken)
                }
            }.exceptionOrNull()
            assertTrue(failure is SessionChangedException)
        }
        remote.refreshStarted.await()

        identity.patientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        repository.login("patient-001", "password")
        remote.releaseRefresh.complete(Unit)
        oldRequest.await()

        assertEquals(1, requestCalls)
        assertEquals("login-access", vault.sessionSnapshot().accessToken)
        assertEquals("login-refresh", vault.refreshValue())
        assertEquals(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            repository.currentAuthenticatedLease()?.patientId,
        )
    }

    @Test
    fun `旧刷新被登出后同账号重新登录取代时返回superseded`() = runBlocking {
        val vault = LinearTokenVault("expired-access", "stored-refresh")
        val remote = GatedAuthRemote(
            loginResult = authSession("relogin-access", "relogin-refresh"),
            refreshResult = authSession("stale-refresh-access", "stale-refresh-token"),
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator, patientIdentity())
        repository.restoreSession()
        remote.holdRefresh = true
        val failedEpoch = vault.sessionSnapshot().epoch
        val refresh = async { coordinator.refreshAfterUnauthorized(failedEpoch) }
        remote.refreshStarted.await()

        repository.logout()
        repository.login("patient-001", "password")
        remote.releaseRefresh.complete(Unit)

        assertTrue(refresh.await() is RefreshResult.Superseded)
        assertEquals("relogin-access", vault.sessionSnapshot().accessToken)
        assertEquals("relogin-refresh", vault.refreshValue())
    }

    @Test
    fun `旧刷新被登出后异账号登录取代时返回superseded且不依赖loginId判定`() = runBlocking {
        val vault = LinearTokenVault("expired-access", "stored-refresh")
        val identity = MutablePatientIdentity("11111111-1111-4111-8111-111111111111")
        val remote = GatedAuthRemote(
            // 刻意复用同一 loginId；安全判定不能依赖 loginId 字符串。
            loginResult = authSession("other-access", "other-refresh"),
            refreshResult = authSession("stale-refresh-access", "stale-refresh-token"),
        )
        val coordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(vault, remote, coordinator, identity)
        repository.restoreSession()
        remote.holdRefresh = true
        val failedEpoch = vault.sessionSnapshot().epoch
        val refresh = async { coordinator.refreshAfterUnauthorized(failedEpoch) }
        remote.refreshStarted.await()

        repository.logout()
        identity.patientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        repository.login("patient-001", "password")
        remote.releaseRefresh.complete(Unit)

        assertTrue(refresh.await() is RefreshResult.Superseded)
        assertEquals("other-access", vault.sessionSnapshot().accessToken)
        assertEquals("other-refresh", vault.refreshValue())
    }

    @Test
    fun `改密请求挂起时取消保留本地凭据并传播取消`() = runBlocking {
        val vault = LinearTokenVault("access", "refresh")
        val remote = GatedAuthRemote().apply { holdChangePassword = true }
        val repository = AuthRepository(vault, remote, RefreshCoordinator(vault, remote), patientIdentity())
        repository.restoreSession()

        val change = async { repository.changePassword("old-password", "new-password") }
        remote.changePasswordStarted.await()
        change.cancel(CancellationException("cancel change"))
        val cancellation = runCatching { change.await() }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(AuthState.Authenticated, repository.state.value)
        assertEquals(AuthOperationState.Idle, repository.operation.value)
        assertEquals("access", vault.sessionSnapshot().accessToken)
        assertEquals("refresh", vault.refreshValue())
    }

    @Test
    fun `改密成功不再发额外远端登出且立即清理凭据`() = runBlocking {
        val vault = LinearTokenVault("access", "refresh")
        val remote = GatedAuthRemote()
        val repository = AuthRepository(vault, remote, RefreshCoordinator(vault, remote), patientIdentity())
        val event = async(start = CoroutineStart.UNDISPATCHED) { repository.events.first() }

        repository.changePassword("old-password", "new-password")

        assertEquals(AuthEvent.PasswordChanged, event.await())
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertEquals(AuthOperationState.Idle, repository.operation.value)
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refreshValue())
    }

    @Test
    fun `旧改密响应迟到不能清除随后登录的新会话`() = runBlocking {
        val vault = LinearTokenVault("old-access", "old-refresh")
        val remote = GatedAuthRemote(loginResult = authSession("new-access", "new-refresh")).apply {
            holdChangePassword = true
        }
        val repository = AuthRepository(vault, remote, RefreshCoordinator(vault, remote), patientIdentity())
        repository.restoreSession()

        val change = async { repository.changePassword("old-password", "new-password") }
        remote.changePasswordStarted.await()
        repository.login("patient-001", "password")
        remote.releaseChangePassword.complete(Unit)
        change.await()

        assertEquals("new-access", vault.sessionSnapshot().accessToken)
        assertEquals("new-refresh", vault.refreshValue())
        assertEquals(AuthState.Authenticated, repository.state.value)
    }

    @Test
    fun `普通登出远端挂起时取消保留凭据并传播取消`() = runBlocking {
        val vault = LinearTokenVault("access", "refresh")
        val remote = GatedAuthRemote().apply { holdLogout = true }
        val repository = AuthRepository(vault, remote, RefreshCoordinator(vault, remote), patientIdentity())
        repository.restoreSession()

        val logout = async { repository.logout() }
        remote.logoutStarted.await()
        logout.cancel(CancellationException("cancel logout"))
        val cancellation = runCatching { logout.await() }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(AuthState.Authenticated, repository.state.value)
        assertEquals(AuthOperationState.Idle, repository.operation.value)
        assertEquals("access", vault.sessionSnapshot().accessToken)
        assertEquals("refresh", vault.refreshValue())
    }
}

private fun patientIdentity() = PatientIdentityRemoteDataSource {
    "11111111-1111-4111-8111-111111111111"
}

private class MutablePatientIdentity(var patientId: String) : PatientIdentityRemoteDataSource {
    override suspend fun patientUuid(): String = patientId
}

private class LinearTokenVault(
    accessToken: String? = null,
    refreshToken: String? = null,
) : TokenVault {
    private var snapshot = SessionSnapshot(accessToken, if (accessToken == null) 0 else 1)
    private var refresh = refreshToken

    override fun sessionSnapshot(): SessionSnapshot = synchronized(this) { snapshot }

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead = synchronized(this) {
        if (snapshot.epoch == expectedEpoch && refresh != null) {
            RefreshTokenRead.Available(RefreshTokenLease(requireNotNull(refresh), expectedEpoch))
        } else {
            RefreshTokenRead.Missing(snapshot.epoch)
        }
    }

    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation = synchronized(this) {
        if (snapshot.epoch != expectedEpoch) {
            SessionMutation(applied = false, snapshot)
        } else {
            refresh = refreshToken
            snapshot = SessionSnapshot(accessToken, snapshot.epoch + 1, replacementId)
            SessionMutation(applied = true, snapshot)
        }
    }

    override suspend fun clear(expectedEpoch: Long?): SessionMutation = synchronized(this) {
        if (expectedEpoch != null && snapshot.epoch != expectedEpoch) {
            SessionMutation(applied = false, snapshot)
        } else {
            refresh = null
            snapshot = SessionSnapshot(null, snapshot.epoch + 1)
            SessionMutation(applied = true, snapshot)
        }
    }
}

private suspend fun TokenVault.refreshValue(): String? =
    (readRefreshToken(sessionSnapshot().epoch) as? RefreshTokenRead.Available)?.lease?.value

private class GatedAuthRemote(
    private val loginResult: AuthSession = authSession("access", "refresh"),
    private val refreshResult: AuthSession = authSession("next-access", "next-refresh"),
    private val refreshFailure: Throwable? = null,
) : AuthRemoteDataSource {
    var holdLogin = false
    var holdRefresh = false
    var holdChangePassword = false
    var holdLogout = false
    val loginStarted = CompletableDeferred<Unit>()
    val refreshStarted = CompletableDeferred<Unit>()
    val changePasswordStarted = CompletableDeferred<Unit>()
    val logoutStarted = CompletableDeferred<Unit>()
    val releaseLogin = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val releaseChangePassword = CompletableDeferred<Unit>()
    val releaseLogout = CompletableDeferred<Unit>()

    override suspend fun login(request: LoginRequestDto): AuthSession {
        loginStarted.complete(Unit)
        if (holdLogin) releaseLogin.await()
        return loginResult
    }

    override suspend fun refresh(request: RefreshRequestDto): AuthSession {
        refreshStarted.complete(Unit)
        if (holdRefresh) releaseRefresh.await()
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun changePassword(request: ChangePasswordRequestDto) {
        changePasswordStarted.complete(Unit)
        if (holdChangePassword) releaseChangePassword.await()
    }

    override suspend fun logout(request: LogoutRequestDto) {
        logoutStarted.complete(Unit)
        if (holdLogout) releaseLogout.await()
    }
}

private fun authSession(access: String, refresh: String): AuthSession = AuthSession(
    access = access,
    refresh = refresh,
    refreshExpiresAt = Instant.parse("2026-09-01T08:00:00Z"),
    loginId = "patient-001",
    role = AccountRole.PATIENT,
    mustChangePassword = false,
)

private fun unauthorized(): retrofit2.HttpException = retrofit2.HttpException(
    retrofit2.Response.error<Any>(
        401,
        "unauthorized".toResponseBody(),
    ),
)
