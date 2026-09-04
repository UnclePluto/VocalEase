package com.vocaease.patient.feature.auth

import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.RefreshTokenLease
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.RevocationHandle
import com.vocaease.patient.core.security.RevocationRemote
import com.vocaease.patient.core.security.RevocationRemoteResult
import com.vocaease.patient.core.security.RevocationScheduling
import com.vocaease.patient.core.security.RevocationTokenSink
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.core.security.DurableLogoutTokenVault
import com.vocaease.patient.core.security.BoundLogoutCredential
import com.vocaease.patient.core.security.PendingRevocationTransfer
import com.vocaease.patient.core.security.RevocationTransferSink
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthLogoutTransferTest {
    @Test
    fun `离线prepared退出原子变为pending后才提交认证终态并移交固定句柄`() = runBlocking {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val sink = RecordingRevocationSink()
        val repository = repository(vault, sink, RecordingRevocationScheduler()) { _, _ ->
            RevocationRemoteResult.Retryable
        }
        val checkpoints = mutableListOf<PreparedLogoutCheckpoint>()
        repository.restoreSession()
        assertNotNull(repository.currentAuthenticatedLease())

        assertEquals(
            true,
            repository.finishPreparedLogout(1, "logout-operation", PreparedLogoutCheckpoint.READY_TO_CLEAR) {
                checkpoints += it
                if (it == PreparedLogoutCheckpoint.AUTH_CLEARED) {
                    assertNull(vault.sessionSnapshot().accessToken)
                    assertNotNull(vault.pendingRevocationTransfer())
                }
            },
        )

        assertEquals(
            listOf(PreparedLogoutCheckpoint.AUTH_BOUND, PreparedLogoutCheckpoint.AUTH_CLEARED),
            checkpoints,
        )
        assertNull(vault.pendingRevocationTransfer())
        assertEquals("old-access" to "old-refresh", sink.stored)
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(repository.currentAuthenticatedLease())
    }

    @Test
    fun `离线移动后进程死亡由新仓库先幂等移交且不重发远端`() = runBlocking {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val sink = RecordingRevocationSink()
        var remoteCalls = 0
        val first = repository(vault, sink, RecordingRevocationScheduler()) { _, _ ->
            remoteCalls += 1
            RevocationRemoteResult.Retryable
        }
        assertThrows(SimulatedTransferCrash::class.java) {
            runBlocking {
                first.finishPreparedLogout(1, "logout-operation", PreparedLogoutCheckpoint.READY_TO_CLEAR) {
                    if (it == PreparedLogoutCheckpoint.AUTH_CLEARED) throw SimulatedTransferCrash()
                }
            }
        }
        assertNotNull(vault.pendingRevocationTransfer())

        val recovered = repository(vault, sink, RecordingRevocationScheduler()) { _, _ ->
            remoteCalls += 1
            RevocationRemoteResult.Success
        }
        val resumed = mutableListOf<PreparedLogoutCheckpoint>()
        assertEquals(
            true,
            recovered.finishPreparedLogout(0, "logout-operation", PreparedLogoutCheckpoint.AUTH_BOUND, resumed::add),
        )

        assertEquals(listOf(PreparedLogoutCheckpoint.AUTH_CLEARED), resumed)
        assertEquals(1, remoteCalls)
        assertNull(vault.pendingRevocationTransfer())
    }
    @Test
    fun `退出读取到vault失效在会话锁外发布且不会死锁`() = runBlocking {
        val vault = InvalidatedLogoutVault()
        val remote = TransferAuthRemote()
        val repository = AuthRepository(
            tokenVault = vault,
            remote = remote,
            refreshCoordinator = RefreshCoordinator(vault, remote),
            patientIdentity = PatientIdentityRemoteDataSource { PATIENT_ID },
        )

        withTimeout(1_000) { repository.logout() }

        assertEquals(AuthState.LoggedOut, repository.state.value)
    }

    @Test
    fun `离线退出先写撤销槽再清认证并仅调度不可逆句柄`() = runBlocking {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val revocationVault = RecordingRevocationSink()
        val scheduler = RecordingRevocationScheduler()
        val repository = repository(vault, revocationVault, scheduler) { _, _ ->
            RevocationRemoteResult.Retryable
        }
        repository.restoreSession()

        repository.logout()

        assertEquals("old-access" to "old-refresh", revocationVault.stored)
        assertEquals(revocationVault.transferredHandle, scheduler.scheduled)
        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refresh)
        assertEquals(AuthState.LoggedOut, repository.state.value)
    }

    @Test
    fun `revocationVault落盘失败保持加密pending且认证已安全清除`() = runBlocking {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val revocationVault = RecordingRevocationSink(IOException("disk full"))
        val scheduler = RecordingRevocationScheduler()
        val repository = repository(vault, revocationVault, scheduler) { _, _ ->
            RevocationRemoteResult.Retryable
        }
        repository.restoreSession()

        repository.logout()

        assertNull(vault.sessionSnapshot().accessToken)
        assertNull(vault.refresh)
        assertNotNull(vault.pendingRevocationTransfer())
        assertNull(scheduler.scheduled)
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertEquals(AuthOperationState.Idle, repository.operation.value)
    }

    @Test
    fun `退出被取消时保留认证并原样传播取消`() {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val repository = repository(vault, RecordingRevocationSink(), RecordingRevocationScheduler()) { _, _ ->
            throw CancellationException("cancel logout")
        }
        runBlocking { repository.restoreSession() }

        assertThrows(CancellationException::class.java) { runBlocking { repository.logout() } }

        assertEquals("old-access", vault.sessionSnapshot().accessToken)
        assertEquals("old-refresh", vault.refresh)
        assertEquals(AuthState.Authenticated, repository.state.value)
    }

    @Test
    fun `旧退出迟到成功不能清除随后登录的新会话`() = runBlocking {
        val vault = TransferTokenVault("old-access", "old-refresh")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val authRemote = TransferAuthRemote()
        val repository = AuthRepository(
            tokenVault = vault,
            remote = authRemote,
            refreshCoordinator = RefreshCoordinator(vault, authRemote),
            patientIdentity = PatientIdentityRemoteDataSource { PATIENT_ID },
            revocationTokenSink = RecordingRevocationSink(),
            revocationRemote = RevocationRemote { access, refresh ->
                assertEquals("old-access", access)
                assertEquals("old-refresh", refresh)
                started.complete(Unit)
                release.await()
                RevocationRemoteResult.Success
            },
            revocationScheduler = RecordingRevocationScheduler(),
        )
        repository.restoreSession()

        val oldLogout = async { repository.logout() }
        started.await()
        repository.login("patient-001", "password")
        release.complete(Unit)
        oldLogout.await()

        assertEquals("new-access", vault.sessionSnapshot().accessToken)
        assertEquals("new-refresh", vault.refresh)
        assertNotNull(repository.currentAuthenticatedLease())
        assertEquals(AuthState.Authenticated, repository.state.value)
    }

    private fun repository(
        vault: TransferTokenVault,
        sink: RevocationTokenSink,
        scheduler: RevocationScheduling,
        revoke: suspend (String, String) -> RevocationRemoteResult,
    ): AuthRepository {
        val remote = TransferAuthRemote()
        return AuthRepository(
            tokenVault = vault,
            remote = remote,
            refreshCoordinator = RefreshCoordinator(vault, remote),
            patientIdentity = PatientIdentityRemoteDataSource { PATIENT_ID },
            revocationTokenSink = sink,
            revocationRemote = RevocationRemote(revoke),
            revocationScheduler = scheduler,
        )
    }
}

private class InvalidatedLogoutVault : TokenVault {
    private var snapshot = SessionSnapshot("access", 1)
    override fun sessionSnapshot(): SessionSnapshot = snapshot
    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead {
        val invalidation = SessionInvalidation(1, 2)
        snapshot = SessionSnapshot(null, 2)
        return RefreshTokenRead.Invalidated(invalidation, IOException("keystore invalidated"))
    }
    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation = SessionMutation(false, snapshot)
    override suspend fun clear(expectedEpoch: Long?): SessionMutation = SessionMutation(false, snapshot)
}

private class RecordingRevocationSink(
    private val failure: Throwable? = null,
) : RevocationTransferSink {
    val handle = RevocationHandle("c".repeat(64))
    var stored: Pair<String, String>? = null
    var transferredHandle: RevocationHandle? = null

    override suspend fun store(accessToken: String, refreshToken: String): RevocationHandle {
        failure?.let { throw it }
        stored = accessToken to refreshToken
        return handle
    }

    override suspend fun store(handle: RevocationHandle, accessToken: String, refreshToken: String) {
        failure?.let { throw it }
        transferredHandle = handle
        stored = accessToken to refreshToken
    }
}

private class RecordingRevocationScheduler : RevocationScheduling {
    var scheduled: RevocationHandle? = null
    override fun schedule(handle: RevocationHandle) {
        scheduled = handle
    }
}

private class TransferTokenVault(accessToken: String?, refreshToken: String?) : TokenVault, DurableLogoutTokenVault {
    private var snapshot = SessionSnapshot(accessToken, if (accessToken == null) 0 else 1)
    var refresh: String? = refreshToken
        private set
    private var boundOperationId: String? = null
    private var boundAccess: String? = null
    private var pending: PendingRevocationTransfer? = null

    override fun sessionSnapshot(): SessionSnapshot = snapshot

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead =
        if (snapshot.epoch == expectedEpoch && refresh != null) {
            RefreshTokenRead.Available(RefreshTokenLease(requireNotNull(refresh), expectedEpoch))
        } else {
            RefreshTokenRead.Missing(snapshot.epoch)
        }

    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation {
        if (snapshot.epoch != expectedEpoch) return SessionMutation(false, snapshot)
        refresh = refreshToken
        snapshot = SessionSnapshot(accessToken, snapshot.epoch + 1, replacementId)
        return SessionMutation(true, snapshot)
    }

    override suspend fun clear(expectedEpoch: Long?): SessionMutation {
        if (expectedEpoch != null && snapshot.epoch != expectedEpoch) return SessionMutation(false, snapshot)
        refresh = null
        snapshot = SessionSnapshot(null, snapshot.epoch + 1)
        return SessionMutation(true, snapshot)
    }

    override suspend fun bindLogoutOperation(expectedEpoch: Long, operationId: String): BoundLogoutCredential? {
        if (snapshot.epoch != expectedEpoch || pending != null) return null
        val access = snapshot.accessToken ?: return null
        if (boundOperationId != null && boundOperationId != operationId) return null
        boundOperationId = operationId
        boundAccess = access
        return BoundLogoutCredential(operationId, access, refresh ?: return null, snapshot.epoch)
    }

    override suspend fun boundLogoutOperation(expectedEpoch: Long, operationId: String): BoundLogoutCredential? {
        if (snapshot.epoch != expectedEpoch || boundOperationId != operationId) return null
        return BoundLogoutCredential(operationId, snapshot.accessToken ?: boundAccess ?: return null, refresh ?: return null, snapshot.epoch)
    }

    override suspend fun clearBoundLogout(expectedEpoch: Long, operationId: String): SessionMutation {
        if (snapshot.epoch != expectedEpoch || boundOperationId != operationId) return SessionMutation(false, snapshot)
        refresh = null
        boundAccess = null
        boundOperationId = null
        snapshot = SessionSnapshot(null, snapshot.epoch + 1)
        return SessionMutation(true, snapshot)
    }

    override suspend fun moveBoundLogoutToRevocation(
        expectedEpoch: Long,
        operationId: String,
        handle: RevocationHandle,
    ): SessionMutation {
        val credential = boundLogoutOperation(expectedEpoch, operationId) ?: return SessionMutation(false, snapshot)
        pending = PendingRevocationTransfer(operationId, handle, credential.accessToken, credential.refreshToken)
        refresh = null
        boundAccess = null
        boundOperationId = null
        snapshot = SessionSnapshot(null, snapshot.epoch + 1)
        return SessionMutation(true, snapshot)
    }

    override suspend fun pendingRevocationTransfer(): PendingRevocationTransfer? = pending

    override suspend fun completePendingRevocationTransfer(operationId: String, handle: RevocationHandle) {
        if (pending?.operationId == operationId && pending?.handle == handle) pending = null
    }
}

private class TransferAuthRemote : AuthRemoteDataSource {
    override suspend fun login(request: LoginRequestDto): AuthSession = AuthSession(
        access = "new-access",
        refresh = "new-refresh",
        refreshExpiresAt = Instant.parse("2026-09-01T08:00:00Z"),
        loginId = request.loginId,
        role = AccountRole.PATIENT,
        mustChangePassword = false,
    )

    override suspend fun refresh(request: RefreshRequestDto): AuthSession = error("unused")
    override suspend fun changePassword(request: ChangePasswordRequestDto) = Unit
    override suspend fun logout(request: LogoutRequestDto) = Unit
}

private const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
private class SimulatedTransferCrash : RuntimeException()
